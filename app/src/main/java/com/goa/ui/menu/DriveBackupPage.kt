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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goa.data.drive.DriveAuth
import com.goa.data.drive.GameResult
import com.goa.data.drive.Outcome
import com.goa.data.drive.TransferRun
import com.goa.ui.savestate.AmbiguityDialog
import com.goa.ui.savestate.ConflictScreen
import com.goa.ui.savestate.ImportSummarySheet
import com.goa.ui.savestate.SaveStateFormat
import com.goa.ui.savestate.StatusTag
import com.goa.ui.savestate.rememberDriveAccess
import com.goa.ui.savestate.tone

/** ☰ → Google Drive Backup (contracts/ui-drive-backup.md): the account, Export, Import and their results. */
@Composable
fun DriveBackupPage(viewModel: DriveBackupViewModel, onBack: () -> Unit) {
    val registered by viewModel.registered.collectAsState()
    val account by viewModel.account.collectAsState()
    val run by viewModel.run.collectAsState()
    val lastExport by viewModel.lastExport.collectAsState()
    val lastImport by viewModel.lastImport.collectAsState()
    val message by viewModel.message.collectAsState()
    val access = rememberDriveAccess(viewModel.auth, viewModel::showMessage)
    var showRegister by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }
    val exportFocus = remember { FocusRequester() }

    MenuPageScaffold(title = MenuPage.DRIVE_BACKUP.title, onBack = onBack) {
        Text(
            "Your save states, copied to your own Google Drive. Only GOA's folder is visible to GOA.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (!registered) {
            PageCard {
                Bullets()
                Button(onClick = { showRegister = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    OneLineLabel("Unlock GOA Pro")
                }
            }
        } else {
            PageCard {
                InfoRow("Google account", account ?: "None") {
                    // Down from here goes to the main action, Export (it sits under the left side, not under Change)
                    TextButton(onClick = { access.changeAccount() }, modifier = Modifier.focusProperties { down = exportFocus }) {
                        OneLineLabel(if (account == null) "Choose account" else "Change")
                    }
                }
                InfoRow("Last export", lastExport.describe())
                InfoRow("Last import", lastImport.describe())
            }
            PageCard { Bullets() }

            val current = run
            if (current != null && current.running) {
                ProgressRow(current, onCancel = viewModel::cancel)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { access.ensure(viewModel::export) },
                        modifier = Modifier.weight(1f).height(48.dp).focusRequester(exportFocus)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        OneLineLabel("Export to Drive")
                    }
                    OutlinedButton(onClick = { access.ensure(viewModel::import) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        OneLineLabel("Import from Drive")
                    }
                }
                if (account != null) {
                    TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.align(Alignment.End)) {
                        OneLineLabel("Unlink")
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            current?.let { r -> if (!r.running && (r.results.isNotEmpty() || r.error != null)) Results(r, viewModel::retry) }
        }
    }

    // The run's questions
    run?.let { r ->
        r.summary?.let { s ->
            if (r.phase == TransferRun.Phase.SUMMARY) {
                ImportSummarySheet(s, onImport = { viewModel.confirmImport(true) }, onCancel = { viewModel.confirmImport(false) })
            }
        }
        r.ambiguity?.let { AmbiguityDialog(it, viewModel::chooseInstall) }
        r.conflict?.let { ConflictScreen(it, viewModel::answer, viewModel::keepNewerForAll) }
    }
    if (showRegister) com.goa.ui.license.RegisterDialog(onDismiss = { showRegister = false })
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink Google Drive?") },
            text = { Text("GOA loses access to its folder in your Drive. Your save states stay there.") },
            confirmButton = {
                TextButton(onClick = { confirmUnlink = false; viewModel.unlink() }) { com.goa.ui.menu.OneLineLabel("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { com.goa.ui.menu.OneLineLabel("Cancel") } }
        )
    }
}

@Composable
private fun PageCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun Bullets() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Saves go to \"GOA Save States\" in My Drive",
            "Ordinary files: you can open or download them, and use them in DOSBox Pure elsewhere",
            "Only save states (Save Slot); games keep their own saves separately"
        ).forEach { line ->
            Row {
                Text("●", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

private fun DriveAuth.LastRun?.describe(): String =
    this?.let { "${SaveStateFormat.date(it.at)}  ($count game${if (count == 1) "" else "s"})" } ?: "Never"

@Composable
private fun ProgressRow(run: TransferRun, onCancel: () -> Unit) {
    PageCard {
        val verb = if (run.kind == TransferRun.Kind.EXPORT) "Exporting" else "Importing"
        val text = when {
            run.phase == TransferRun.Phase.TRANSFERRING && run.total > 0 ->
                "$verb ${(run.done + 1).coerceAtMost(run.total)} of ${run.total}" + (run.current?.let { ": $it" } ?: "")
            run.phase == TransferRun.Phase.RESOLVING || run.phase == TransferRun.Phase.SUMMARY -> "Waiting for your choice…"
            else -> "Checking Google Drive…"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { OneLineLabel("Cancel") }
        }
        if (run.phase == TransferRun.Phase.TRANSFERRING && run.total > 0) {
            LinearProgressIndicator(progress = { run.done / run.total.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Results(run: TransferRun, onRetry: () -> Unit) {
    PageCard {
        val done = run.results.count {
            it.outcome == Outcome.UPLOADED || it.outcome == Outcome.ADDED || it.outcome == Outcome.REPLACED
        }
        val allSame = run.error == null && run.results.isNotEmpty() && run.results.all { it.outcome == Outcome.SAME }
        val heading = when {
            allSame && run.kind == TransferRun.Kind.EXPORT -> "Drive is already up to date"
            allSame -> "This phone is already up to date"
            done == 0 && run.kind == TransferRun.Kind.EXPORT -> "Nothing exported"
            done == 0 -> "Nothing imported"
            run.kind == TransferRun.Kind.EXPORT -> "Exported $done game${if (done == 1) "" else "s"}"
            else -> "Imported $done game${if (done == 1) "" else "s"}"
        }
        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        run.error?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        run.results.sortedBy { it.title.lowercase() }.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ResultRow(r)
        }
        if (run.failed.isNotEmpty() && run.kind != TransferRun.Kind.DOWNLOAD) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(48.dp)) { OneLineLabel("Retry") }
        }
    }
}

@Composable
private fun ResultRow(r: GameResult) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusTag(r.outcome.label, r.outcome.tone())
        }
        val detail = when {
            r.outcome == Outcome.REPLACED -> "This phone's copy kept as the previous save state"
            else -> r.reason
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
