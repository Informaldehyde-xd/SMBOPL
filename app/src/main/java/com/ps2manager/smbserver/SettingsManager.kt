package com.ps2manager.smbserver

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

object SettingsManager {
    private const val PREFS_NAME = "smb_server_settings"

    private const val KEY_PORT = "port"
    private const val KEY_SHARE_NAME = "share_name"
    private const val KEY_SHARE_PATH = "share_path"
    private const val KEY_WORKGROUP = "workgroup"
    private const val KEY_NETBIOS_NAME = "netbios_name"
    private const val KEY_BIND_IP = "bind_ip"
    private const val KEY_FTP_PORT = "ftp_port"
    private const val KEY_FTP_ENABLED = "ftp_enabled"

    const val DEFAULT_PORT = 10445
    const val DEFAULT_FTP_PORT = 2121
    const val DEFAULT_FTP_ENABLED = true
    const val DEFAULT_SHARE_NAME = "PS2SMB"
    const val DEFAULT_WORKGROUP = "WORKGROUP"
    const val DEFAULT_NETBIOS_NAME = "PS2SERVER"

    fun defaultSharePath(): String =
        File(Environment.getExternalStorageDirectory(), "PS2SMB").absolutePath

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    fun getShareName(context: Context): String =
        prefs(context).getString(KEY_SHARE_NAME, DEFAULT_SHARE_NAME) ?: DEFAULT_SHARE_NAME

    fun setShareName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SHARE_NAME, name).apply()
    }

    fun getSharePath(context: Context): String =
        prefs(context).getString(KEY_SHARE_PATH, defaultSharePath()) ?: defaultSharePath()

    fun setSharePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_SHARE_PATH, path).apply()
    }

    fun getWorkgroup(context: Context): String =
        prefs(context).getString(KEY_WORKGROUP, DEFAULT_WORKGROUP) ?: DEFAULT_WORKGROUP

    fun setWorkgroup(context: Context, workgroup: String) {
        prefs(context).edit().putString(KEY_WORKGROUP, workgroup).apply()
    }

    fun getNetbiosName(context: Context): String =
        prefs(context).getString(KEY_NETBIOS_NAME, DEFAULT_NETBIOS_NAME) ?: DEFAULT_NETBIOS_NAME

    fun setNetbiosName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NETBIOS_NAME, name).apply()
    }

    /** Optional bind IP. Empty string means "listen on all interfaces". */
    fun getBindIp(context: Context): String =
        prefs(context).getString(KEY_BIND_IP, "") ?: ""

    fun setBindIp(context: Context, ip: String) {
        prefs(context).edit().putString(KEY_BIND_IP, ip).apply()
    }

    fun getFtpPort(context: Context): Int =
        prefs(context).getInt(KEY_FTP_PORT, DEFAULT_FTP_PORT)

    fun setFtpPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_FTP_PORT, port).apply()
    }

    fun isFtpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FTP_ENABLED, DEFAULT_FTP_ENABLED)

    fun setFtpEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FTP_ENABLED, enabled).apply()
    }

    fun isValidPort(port: Int): Boolean = port in 1025..65535

    fun isValidIp(ip: String): Boolean {
        if (ip.isBlank()) return true // blank = "all interfaces", always valid
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255 && part == n.toString()
        }
    }
}
