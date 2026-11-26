package com.goa.ui.savestate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.data.drive.DriveSaveStates
import com.goa.data.drive.ImportSummary
import com.goa.ui.menu.OneLineLabel

/** What import will do, before anything changes (contracts/ui-drive-backup.md "Import summary"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSummarySheet(summary: ImportSummary, onImport: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Import from Google Drive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(top = 12.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (summary.nothingToDo) {
                    Text(
                        when {
                            summary.notOnPhone.isEmpty() -> "Everything in Drive is already on this phone."
                            summary.same.isEmpty() -> "Nothing to import yet: install these games first."
                            else -> "Nothing new to import. Install the games below first to bring their save states."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                SummaryRow(summary.toAdd.size, "to add", "no save state on this phone", summary.toAdd)
                SummaryRow(summary.same.size, "already the same", null, summary.same)
                SummaryRow(summary.differ.size, "differ: you choose", null, summary.differ)
                SummaryRow(summary.notOnPhone.size, "not on this phone", null, summary.notOnPhone,
                    note = DriveSaveStates.NOT_INSTALLED_MESSAGE)
            }
            Spacer(Modifier.padding(top = 16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (summary.nothingToDo) {
                    Button(onClick = onCancel) { OneLineLabel("Close") }
                } else {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.width(140.dp)) { OneLineLabel("Cancel") }
                    Button(onClick = onImport, modifier = Modifier.width(140.dp)) { OneLineLabel("Import") }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SummaryRow(count: Int, label: String, hint: String?, games: List<String>, note: String? = null) {
    if (count == 0) return
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp)
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) Text("($hint)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Hide games" else "Show games")
        }
        AnimatedVisibility(open) {
            Column(modifier = Modifier.padding(start = 40.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                games.forEach { title ->
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
