package com.goa.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.data.folders.AddedFolder
import com.goa.data.folders.ScanState

/** The text under "Add Folder": what it does, how much it found, or what the scan is doing right now. */
fun addFolderSubtitle(folders: List<AddedFolder>, ownGames: Int, scan: ScanState): String = when {
    scan is ScanState.Scanning -> "Scanning… ${scan.found} game${if (scan.found == 1) "" else "s"} found in ${scan.dirs} folder${if (scan.dirs == 1) "" else "s"}"
    scan is ScanState.Failed -> scan.message
    folders.isEmpty() -> "Scan a folder for DOS games on this device"
    else -> "${folders.size} folder${if (folders.size == 1) "" else "s"} · $ownGames game${if (ownGames == 1) "" else "s"}"
}

/**
 * The row under the "Connect GOG" banner: add a folder to scan, see how many games it holds, rescan, or remove folders.
 * It is always shown, whether or not GOG is connected.
 */
@Composable
fun AddFolderRow(
    folders: List<AddedFolder>,
    ownGames: Int,
    scanState: ScanState,
    onAddFolder: (initialTreeUri: String?) -> Unit,
    onRescan: () -> Unit,
    onRemoveFolder: (AddedFolder) -> Unit,
    /** The X: hide this row from the library (folders stay manageable in ☰ → Storage & Cache). */
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AddedFolder?>(null) }
    val scanning = scanState is ScanState.Scanning

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddFolder(null) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add Folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        addFolderSubtitle(folders, ownGames, scanState),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (scanState is ScanState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                if (folders.isNotEmpty()) {
                    IconButton(onClick = onRescan, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan folders")
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Hide folders" else "Manage folders"
                        )
                    }
                }
                if (onClose != null) HintCloseButton(LibraryViewModel.HINT_FOLDERS) { onClose() }
            }
            if (scanning) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(visible = expanded && folders.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    folders.forEach { folder ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    if (folder.needsRegrant) "Access lost. Reconnect to scan it again."
                                    else "${folder.lastCount} game${if (folder.lastCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (folder.needsRegrant) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (folder.needsRegrant) TextButton(onClick = { onAddFolder(folder.treeUri) }) { com.goa.ui.menu.OneLineLabel("Reconnect") }
                            IconButton(onClick = { removing = folder }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove ${folder.name}", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton(onClick = { onAddFolder(null) }) { com.goa.ui.menu.OneLineLabel("Add another folder") }
                }
            }
        }
    }

    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${folder.name}?") },
            text = { Text("Games from this folder that you haven't installed will leave your library. Games you have installed are kept.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemoveFolder(folder) }) { com.goa.ui.menu.OneLineLabel("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { com.goa.ui.menu.OneLineLabel("Cancel") } }
        )
    }
}
