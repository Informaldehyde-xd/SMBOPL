package com.ps2manager.smbserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkRuntimePermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmbControllerScreen(
                        onRequestStorage = { requestStoragePermission() },
                        onStartServer = {
                            val intent = Intent(this, SmbServerService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        },
                        onStopServer = {
                            stopService(Intent(this, SmbServerService::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun checkRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }
}

@Composable
fun SmbControllerScreen(
    onRequestStorage: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val status by SmbServerService.status.collectAsState()

    var port by remember { mutableStateOf(SettingsManager.getPort(context).toString()) }
    var shareName by remember { mutableStateOf(SettingsManager.getShareName(context)) }
    var sharePath by remember { mutableStateOf(SettingsManager.getSharePath(context)) }
    var workgroup by remember { mutableStateOf(SettingsManager.getWorkgroup(context)) }
    var netbiosName by remember { mutableStateOf(SettingsManager.getNetbiosName(context)) }
    var bindIp by remember { mutableStateOf(SettingsManager.getBindIp(context)) }
    var showFolderPicker by remember { mutableStateOf(false) }

    val portValue = port.toIntOrNull()
    val portValid = portValue != null && SettingsManager.isValidPort(portValue)
    val ipValid = SettingsManager.isValidIp(bindIp)
    val settingsValid = portValid && ipValid && shareName.isNotBlank() && sharePath.isNotBlank()
    val isRunning = status.state == ServerState.RUNNING || status.state == ServerState.STARTING

    val saveSettings: () -> Unit = {
        SettingsManager.setPort(context, portValue ?: SettingsManager.DEFAULT_PORT)
        SettingsManager.setShareName(context, shareName)
        SettingsManager.setSharePath(context, sharePath)
        SettingsManager.setWorkgroup(context, workgroup)
        SettingsManager.setNetbiosName(context, netbiosName)
        SettingsManager.setBindIp(context, bindIp)
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = sharePath,
            onDismiss = { showFolderPicker = false },
            onConfirm = { picked ->
                sharePath = picked
                showFolderPicker = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("OPL SMB Server Node", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        StatusCard(status)
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRequestStorage, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
            Text("1. Grant All Files Access (Required)")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Port (1025–65535)") },
            isError = !portValid,
            supportingText = { if (!portValid) Text("Enter a port above 1024") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = bindIp,
            onValueChange = { bindIp = it },
            label = { Text("Bind IP (optional, blank = all interfaces)") },
            isError = !ipValid,
            supportingText = {
                if (!ipValid) Text("Enter a valid IPv4 address or leave blank")
                else if (status.ipAddress != null) Text("Device IP: ${status.ipAddress}")
            },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = shareName,
            onValueChange = { shareName = it },
            label = { Text("Share Name") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = workgroup,
            onValueChange = { workgroup = it },
            label = { Text("Workgroup") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = netbiosName,
            onValueChange = { netbiosName = it },
            label = { Text("NetBIOS Name") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = sharePath,
            onValueChange = { sharePath = it },
            label = { Text("Share Folder Path") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { showFolderPicker = true }, enabled = !isRunning) {
                    Text("Browse")
                }
            }
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = saveSettings,
            enabled = settingsValid && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                saveSettings()
                onStartServer()
            },
            enabled = settingsValid && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. Start SMB Server")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStopServer,
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Server")
        }
    }
}

@Composable
fun StatusCard(status: ServerStatus) {
    val (label, color) = when (status.state) {
        ServerState.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
        ServerState.STARTING -> "STARTING…" to MaterialTheme.colorScheme.tertiary
        ServerState.ERROR -> "ERROR" to MaterialTheme.colorScheme.error
        ServerState.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: ", fontWeight = FontWeight.Bold)
                Text(label, color = color, fontWeight = FontWeight.Bold)
            }
            if (status.state == ServerState.RUNNING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Address: ${status.ipAddress ?: "unknown"}:${status.port}")
                Text("Share: \\\\${status.ipAddress ?: "?"}\\${status.shareName}")
                Text("Folder: ${status.sharePath}")
                Text("Uptime: ${formatUptime(status.uptimeSeconds)}")
                if (status.lastLogLine.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Last activity:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(status.lastLogLine, style = MaterialTheme.typography.bodySmall)
                }
            } else if (status.state == ServerState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status.errorMessage ?: "Unknown error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
