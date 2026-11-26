package com.goa.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goa.data.folders.AddedFolder
import com.goa.data.folders.ScanState
import com.goa.ui.savestate.SaveStateFormat

/**
 * ☰ → Storage & Cache → Watched folders: the folders GOA looks in for DOS games (and every folder inside them), with
 * adding, removing, reconnecting and rescanning. The library's Add Folder card does the same until the player hides it.
 */
@Composable
fun WatchedFoldersSection(
    folders: List<AddedFolder>,
    scanState: ScanState,
    onAdd: () -> Unit,
    onReconnect: (AddedFolder) -> Unit,
    onRemove: (AddedFolder) -> Unit,
    onRescan: () -> Unit
) {
    var removing by remember { mutableStateOf<AddedFolder?>(null) }
    val scanning = scanState as? ScanState.Scanning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Watched folders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (folders.isNotEmpty()) {
                TextButton(onClick = onRescan, enabled = scanning == null) { OneLineLabel("Rescan") }
            }
        }
        Text(
            if (folders.isEmpty()) "GOA finds DOS games in folders you add, including every folder inside them."
            else "GOA finds DOS games in these folders, including every folder inside them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (folders.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    folders.forEachIndexed { index, folder ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        FolderRow(
                            folder = folder,
                            scanning = scanning?.takeIf { it.folderId == folder.id },
                            failed = (scanState as? ScanState.Failed)?.takeIf { it.folderId == folder.id }?.message,
                            onReconnect = { onReconnect(folder) },
                            onRemove = { removing = folder }
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            OneLineLabel(if (folders.isEmpty()) "Add a folder" else "Add another folder")
        }
    }

    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop watching ${folder.name}?") },
            text = {
                Text(
                    "Games from this folder that you haven't installed will leave your library. Games you have " +
                        "installed are kept, and nothing in the folder itself is deleted."
                )
            },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(folder) }) { com.goa.ui.menu.OneLineLabel("Stop watching") } },
            dismissButton = { TextButton(onClick = { removing = null }) { com.goa.ui.menu.OneLineLabel("Cancel") } }
        )
    }
}

@Composable
private fun FolderRow(
    folder: AddedFolder,
    scanning: ScanState.Scanning?,
    failed: String?,
    onReconnect: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val (detail, isError) = when {
                    folder.needsRegrant -> "Access lost. Reconnect to scan it again." to true
                    failed != null -> failed to true
                    scanning != null -> "Scanning… ${scanning.found} game${if (scanning.found == 1) "" else "s"} found" to false
                    else -> buildString {
                        append("${folder.lastCount} game${if (folder.lastCount == 1) "" else "s"}")
                        if (folder.lastScanMs > 0) append(" · scanned ${SaveStateFormat.date(folder.lastScanMs)}")
                    } to false
                }
                Text(
                    detail, style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (folder.needsRegrant) TextButton(onClick = onReconnect) { OneLineLabel("Reconnect") }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Stop watching ${folder.name}", modifier = Modifier.size(20.dp))
            }
        }
        if (scanning != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    }
}
