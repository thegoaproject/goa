package com.goa.ui.savestate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.data.drive.DriveCopy
import com.goa.ui.menu.OneLineLabel

/** The in-game Drive row's state (contracts/ui-drive-backup.md "In-game menu, Save States section"). */
data class InGameDrive(
    val check: Check = Check.Checking,
    val busy: Boolean = false,
    /** A download just finished: offer to load it. */
    val downloaded: Boolean = false,
    /** Something went wrong (shown in the error colour). */
    val message: String? = null,
    /** Plain information, such as "already the same". */
    val note: String? = null
) {
    sealed interface Check {
        data object Checking : Check
        /** No save state in Drive for this game: the row is hidden. */
        data object None : Check
        data object Offline : Check
        /** Google must ask again; in a game GOA shows no consent screens. */
        data object NeedsAccess : Check
        data class Found(val copy: DriveCopy) : Check
    }
}

@Composable
fun DriveSaveRow(state: InGameDrive, onDownload: () -> Unit, onLoadNow: () -> Unit) {
    val check = state.check
    if (check == InGameDrive.Check.None && !state.downloaded && state.message == null && state.note == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (check) {
            InGameDrive.Check.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking Google Drive…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InGameDrive.Check.None -> Unit
            else -> {
                val found = check as? InGameDrive.Check.Found
                OutlinedButton(onClick = onDownload, enabled = found != null && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    OneLineLabel("Download save from Google Drive")
                }
                val detail = when (check) {
                    is InGameDrive.Check.Found ->
                        "Drive copy: ${SaveStateFormat.date(check.copy.meta.savedAt)}" +
                            check.copy.meta.device.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
                    InGameDrive.Check.Offline -> "No connection"
                    InGameDrive.Check.NeedsAccess -> "Open ☰ → Drive Backup to allow access"
                    else -> null
                }
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.downloaded) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloaded", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onLoadNow) { OneLineLabel("Load now") }
        }
        state.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
