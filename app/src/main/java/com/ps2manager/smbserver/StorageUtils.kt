package com.ps2manager.smbserver

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

data class StorageVolumeInfo(
    val label: String,
    val path: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean
)

object StorageUtils {

    fun listVolumes(context: Context): List<StorageVolumeInfo> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()

        val volumes = try {
            storageManager.storageVolumes
        } catch (e: Exception) {
            emptyList()
        }

        return volumes.mapNotNull { volume -> volumeToInfo(volume, context) }
            .filter { it.path.isNotBlank() }
    }

    private fun volumeToInfo(volume: StorageVolume, context: Context): StorageVolumeInfo? {
        val path = getVolumePath(volume) ?: return null
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return null

        val label = when {
            volume.isPrimary -> "Internal Storage"
            else -> volume.getDescription(context) ?: "Removable Storage"
        }

        return StorageVolumeInfo(
            label = label,
            path = path,
            isRemovable = volume.isRemovable,
            isPrimary = volume.isPrimary
        )
    }

    /** Resolves the filesystem path of a StorageVolume across API levels. */
    private fun getVolumePath(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                volume.directory?.absolutePath
            } catch (e: Exception) {
                null
            }
        }
        // Pre-API 30 has no public accessor for this
        return try {
            val method = volume.javaClass.getMethod("getPath")
            method.invoke(volume) as? String
        } catch (e: Exception) {
            null
        }
    }
}
