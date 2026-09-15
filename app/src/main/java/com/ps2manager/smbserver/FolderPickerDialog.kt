package com.ps2manager.smbserver

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun FolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val volumes = remember { StorageUtils.listVolumes(context) }
    val defaultRoot = volumes.firstOrNull { it.isPrimary }?.let { File(it.path) }
        ?: Environment.getExternalStorageDirectory()

    var currentDir by remember {
        mutableStateOf(File(initialPath).takeIf { it.exists() && it.isDirectory } ?: defaultRoot)
    }
    var currentVolumeRoot by remember {
        mutableStateOf(
            volumes.firstOrNull { currentDir.absolutePath.startsWith(it.path) }?.path
                ?: defaultRoot.absolutePath
        )
    }

    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Share Folder") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                if (volumes.size > 1) {
                    Text(
                        "Storage",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp)
                    ) {
                        volumes.forEach { volume ->
                            val selected = currentVolumeRoot == volume.path
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    currentDir = File(volume.path)
                                    currentVolumeRoot = volume.path
                                },
                                label = { Text(volume.label) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Divider(modifier = Modifier.padding(bottom = 8.dp))
                }

                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (currentDir.absolutePath != currentVolumeRoot && currentDir.parentFile != null) {
                    TextButton(onClick = { currentDir = currentDir.parentFile!! }) {
                        Text("⬆ Up")
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(subDirs) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("\uD83D\uDCC1  ")
                            Text(dir.name)
                        }
                    }
                    if (subDirs.isEmpty()) {
                        item {
                            Text(
                                "No subfolders here",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentDir.absolutePath) }) {
                Text("Use This Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
