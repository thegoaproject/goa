package com.goa.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goa.data.drive.DriveError
import com.goa.data.drive.SaveStateList
import com.goa.data.drive.SaveStateListRow
import com.goa.domain.savestate.SaveStateMeta
import com.goa.ui.savestate.SaveStateFormat
import com.goa.ui.savestate.StatusTag
import com.goa.ui.savestate.label
import com.goa.ui.savestate.tone

/** Who is looking at the list decides its footer (contracts/ui-drive-backup.md, Storage & Cache section). */
sealed interface SaveStatesFooter {
    /** GOA OSS (or the feature switched off): phone save states only, no mention of Drive. */
    data object None : SaveStatesFooter
    data class NotPro(val onUnlock: () -> Unit) : SaveStatesFooter
    data class NoAccount(val onChooseAccount: () -> Unit) : SaveStatesFooter
    data class Linked(val account: String, val onOpenDriveBackup: () -> Unit) : SaveStatesFooter
}

/**
 * ☰ → Storage & Cache, Save States: every game with a save state on this phone or in Drive, newest first, with its
 * backup status (US5, FR-022..FR-024). Built from Drive's file details only: opening it uploads and downloads nothing.
 */
@Composable
fun SaveStatesSection(list: SaveStateList, footer: SaveStatesFooter, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Save States",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh, enabled = !list.loading) { OneLineLabel("Refresh") }
        }
        if (list.rows.isEmpty() && !list.loading) {
            Text(
                "No save states yet. Use Save Slot in a DOS game's menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (list.rows.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    list.rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SaveStateRow(row, showStatus = footer is SaveStatesFooter.Linked)
                    }
                }
            }
        }
        Footer(list, footer)
    }
}

@Composable
private fun SaveStateRow(row: SaveStateListRow, showStatus: Boolean) {
    var open by rememberSaveable(row.key, row.gameId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.phone?.let { "${SaveStateFormat.shortDate(it.savedAt)} · ${formatBytes(row.phoneBytes)}" } ?: "–",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val status = row.status
            if (row.damaged != null) {
                // Shown with or without Drive: the player should know before they press Load Slot
                Spacer(Modifier.width(8.dp))
                StatusTag("Damaged", com.goa.ui.savestate.Tone.BAD)
            } else if (showStatus && status != null) {
                Spacer(Modifier.width(8.dp))
                StatusTag(status.label(), status.tone())
            }
        }
        row.damaged?.let { problem ->
            Text(
                problem.message + when {
                    row.previousSavedAt != null -> " Restore the previous one from the game's menu, or make a new one with Save Slot."
                    showStatus && row.drive != null -> " Import from Google Drive to replace it, or make a new one with Save Slot."
                    else -> " Make a new one with Save Slot."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        AnimatedVisibility(open) {
            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.phone?.let { Detail("This phone", it) }
                if (showStatus) row.drive?.let { Detail("Google Drive", it) }
                row.previousSavedAt?.let {
                    Text("Previous save state kept: ${SaveStateFormat.date(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Detail(source: String, meta: SaveStateMeta) {
    val device = meta.device.takeIf { it.isNotBlank() }?.let { "$it, " }.orEmpty()
    Text(
        "$source: $device${SaveStateFormat.date(meta.savedAt)} (${SaveStateFormat.age(meta.savedAt)})",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun Footer(list: SaveStateList, footer: SaveStatesFooter) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (footer) {
        SaveStatesFooter.None -> Unit
        is SaveStatesFooter.NotPro -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Back up save states to Google Drive with GOA Pro", style = small, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = footer.onUnlock) { OneLineLabel("Unlock GOA Pro") }
        }
        is SaveStatesFooter.NoAccount -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Back up save states to Google Drive", style = small, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = footer.onChooseAccount) { OneLineLabel("Choose account") }
        }
        // The account line gets the full width (an email address is long); the link goes under it
        is SaveStatesFooter.Linked -> Column(modifier = Modifier.fillMaxWidth()) {
            val error = list.driveError
            val text = when {
                list.loading -> null
                error == DriveError.NoConnection -> "Couldn't check Google Drive: no connection"
                error != null -> "Couldn't check Google Drive: ${error.message}"
                else -> "Google Drive: ${footer.account}" +
                    (list.checkedAt?.let { " · checked ${SaveStateFormat.date(it).removePrefix("Today ")}" } ?: "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (text == null) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking Google Drive…", style = small, color = muted)
                } else {
                    Text(text, style = small, color = if (error != null) MaterialTheme.colorScheme.error else muted)
                }
            }
            TextButton(onClick = footer.onOpenDriveBackup, modifier = Modifier.align(Alignment.End)) {
                OneLineLabel("Google Drive Backup ›")
            }
        }
    }
}
