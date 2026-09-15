package com.ps2manager.smbserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ps2manager.smbserver.jlan.OplServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.filesys.smb.server.SMBServer
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

data class ServerStatus(
    val state: ServerState = ServerState.STOPPED,
    val ipAddress: String? = null,
    val port: Int = SettingsManager.DEFAULT_PORT,
    val shareName: String = SettingsManager.DEFAULT_SHARE_NAME,
    val sharePath: String = "",
    val startTimeMillis: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val lastLogLine: String = "",
    val errorMessage: String? = null
)

private class TeeOutputStream(
    private val first: OutputStream,
    private val second: OutputStream
) : OutputStream() {
    override fun write(b: Int) {
        first.write(b)
        try { second.write(b) } catch (_: Exception) { /* don't let a log-write failure affect stdout/stderr */ }
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        first.write(b, off, len)
        try { second.write(b, off, len) } catch (_: Exception) { }
    }
    override fun flush() {
        first.flush()
        try { second.flush() } catch (_: Exception) { }
    }
}

class SmbServerService : Service() {

    companion object {
        private const val TAG = "SmbServerService"
        private val _status = MutableStateFlow(ServerStatus())
        val status = _status.asStateFlow()
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "SMB_CHANNEL"
        private const val TICK_INTERVAL_MS = 5000L
        // Cap the debug log so a long play session can't slowly fill up shared storage
        // (which, left unbounded, could eventually cause write failures on the same
        // volume the game data lives on).
        private const val MAX_LOG_BYTES = 5L * 1024 * 1024
        const val DEBUG_LOG_PATH = "/storage/emulated/0/Download/smbopl-debug.log"
    }

    private var smbServer: SMBServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var debugLogStream: OutputStream? = null
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guard against a double start (e.g. the Activity button is tapped twice, or the
        // system redelivers the start intent): starting a second SMBServer on the same port
        // throws (crashing this attempt) and would also leak the first server's threads,
        // locks and log file handle.
        if (smbServer != null) {
            return START_STICKY
        }
        try {
            createNotificationChannel()
            redirectDebugOutputToFile()

            val port = SettingsManager.getPort(this)
            val shareName = SettingsManager.getShareName(this)
            val sharePath = SettingsManager.getSharePath(this)
            val workgroup = SettingsManager.getWorkgroup(this)
            val netbiosName = SettingsManager.getNetbiosName(this)
            val bindIp = SettingsManager.getBindIp(this)

            _status.value = ServerStatus(
                state = ServerState.STARTING,
                port = port,
                shareName = shareName,
                sharePath = sharePath
            )

            val notification = buildNotification(_status.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }

            acquireLocks()

            val config = OplServerConfiguration(
                netbiosName, port, shareName, sharePath, workgroup
            )
            if (bindIp.isNotBlank()) {
                config.setBindAddress(InetAddress.getByName(bindIp))
            }

            val server = SMBServer(config)
            config.addServer(server)
            server.startServer()
            smbServer = server

            _status.value = _status.value.copy(
                state = ServerState.RUNNING,
                ipAddress = bindIp.ifBlank { NetworkUtils.getLocalIpAddress() },
                startTimeMillis = System.currentTimeMillis()
            )
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SMB server", e)
            val errorStatus = _status.value.copy(
                state = ServerState.ERROR,
                errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
            )
            _status.value = errorStatus
            showErrorNotification(errorStatus)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun redirectDebugOutputToFile() {
        try {
            val logFile = File(DEBUG_LOG_PATH)
            logFile.parentFile?.mkdirs()
            val fileOut = java.io.BufferedOutputStream(FileOutputStream(logFile, false), 64 * 1024)
            fileOut.write("=== SMBOPL debug log started at ${java.util.Date()} ===\n".toByteArray())
            fileOut.flush()
            debugLogStream = fileOut
            originalOut = System.out
            originalErr = System.err

            // autoFlush=false: with "Debug" level logging removed there's much less output, but
            // flushing (an effectively synchronous disk write) on every single line was still
            // stalling the calling thread — often an SMB worker thread mid-request. We flush on
            // our own timer instead (see startTicker/flushDebugLog).
            System.setOut(PrintStream(TeeOutputStream(originalOut!!, fileOut), false))
            System.setErr(PrintStream(TeeOutputStream(originalErr!!, fileOut), false))
        } catch (e: Exception) {
            Log.e(TAG, "Could not set up debug log file", e)
        }
    }

    private fun flushDebugLog() {
        try {
            val stream = debugLogStream ?: return
            stream.flush()
            val logFile = File(DEBUG_LOG_PATH)
            if (logFile.length() > MAX_LOG_BYTES) {
                // Rotate: truncate and start fresh rather than growing without bound.
                stream.close()
                val fresh = java.io.BufferedOutputStream(FileOutputStream(logFile, false), 64 * 1024)
                fresh.write("=== SMBOPL debug log rotated at ${java.util.Date()} ===\n".toByteArray())
                debugLogStream = fresh
                System.setOut(PrintStream(TeeOutputStream(originalOut!!, fresh), false))
                System.setErr(PrintStream(TeeOutputStream(originalErr!!, fresh), false))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not flush/rotate debug log", e)
        }
    }

    private fun startTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                val current = _status.value
                if (current.state == ServerState.RUNNING) {
                    val uptime = (System.currentTimeMillis() - current.startTimeMillis) / 1000
                    val updated = current.copy(uptimeSeconds = uptime)
                    _status.value = updated
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIF_ID, buildNotification(updated))
                }
                flushDebugLog()
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
        mainHandler.post(tickerRunnable!!)
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun showErrorNotification(errorStatus: ServerStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(errorStatus))
    }

    private fun buildNotification(status: ServerStatus): android.app.Notification {
        val ipText = status.ipAddress ?: "unknown IP"
        val uptimeText = formatUptime(status.uptimeSeconds)
        val contentText = when (status.state) {
            ServerState.RUNNING -> "$ipText:${status.port} • Up $uptimeText"
            ServerState.STARTING -> "Starting server..."
            ServerState.ERROR -> "Error: ${status.errorMessage ?: "server stopped"}"
            ServerState.STOPPED -> "Server stopped"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OPL SMB Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOnlyAlertOnce(true)
            .setOngoing(status.state == ServerState.RUNNING || status.state == ServerState.STARTING)
            .build()
    }

    private fun formatUptime(totalSeconds: Long): String {
        val h = TimeUnit.SECONDS.toHours(totalSeconds)
        val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Log.e(TAG, "Could not determine active network transport — assuming Wi-Fi to be safe", e)
            true
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmbServer::WakeLock")
        // No timeout: a fixed 10-hour cap meant a long play session would silently lose its
        // wake lock mid-game (the CPU can then sleep and stall/drop the SMB connection, which
        // shows up as a "crash" to the player). The lock is explicitly released in onDestroy
        // when the user stops the server, so it's safe to hold indefinitely while running.
        wakeLock?.acquire()

        if (isOnWifi()) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "SmbServer::WifiLock")
            wifiLock?.acquire()
        } else {
            Log.d(TAG, "Not on Wi-Fi — skipping Wi-Fi lock")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OPL Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopTicker()
        try {
            smbServer?.shutdownServer(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMB server", e)
        }
        smbServer = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()

        // Restore stdout/stderr and close the log file so we don't leak the file descriptor
        // (or leave the process's console output silently pointed at a closed stream) if the
        // server is stopped and started again in the same process.
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        try { debugLogStream?.flush(); debugLogStream?.close() } catch (_: Exception) { }
        debugLogStream = null
        originalOut = null
        originalErr = null

        _status.value = _status.value.copy(state = ServerState.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
