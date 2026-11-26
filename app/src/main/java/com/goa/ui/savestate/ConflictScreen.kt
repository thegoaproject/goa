package com.goa.ui.savestate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goa.ui.menu.FullScreenDialogWindow
import com.goa.data.drive.ConflictPrompt
import com.goa.data.drive.CopyInfo
import com.goa.domain.savestate.ConflictChoice
import com.goa.ui.menu.OneLineLabel

/**
 * One game whose phone and Drive save states differ, asked one at a time (contracts/ui-drive-backup.md "Conflict
 * screen"): two cards side by side (stacked on narrow screens), the newer pre-selected. The cards are the choice;
 * Keep selected applies it, Skip leaves both copies alone.
 */
@Composable
fun ConflictScreen(
    prompt: ConflictPrompt,
    onChoice: (choice: ConflictChoice, applyToRemaining: Boolean) -> Unit,
    onKeepNewerForAll: () -> Unit
) {
    // A new game resets the selection to its newer copy
    var selected by remember(prompt.title, prompt.position) { mutableStateOf(prompt.preselected) }
    var applyToRemaining by remember(prompt.title, prompt.position) { mutableStateOf(false) }
    val keepFocus = remember { FocusRequester() }
    LaunchedEffect(prompt.title, prompt.position) { runCatching { keepFocus.requestFocus() } }

    Dialog(
        onDismissRequest = { onChoice(ConflictChoice.SKIP, false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)
    ) {
        // The window covers the whole display; safeDrawingPadding below keeps the buttons clear of the bars
        FullScreenDialogWindow()
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = prompt.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (prompt.total > 1) Text(
                            "${prompt.position} of ${prompt.total}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (prompt.exporting) "Google Drive has a newer save state"
                        else "Your phone and Google Drive have different save states.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val newer = if (prompt.drive.savedAt > prompt.phone.savedAt) ConflictChoice.USE_DRIVE else ConflictChoice.KEEP_PHONE
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val phoneCard = @Composable { m: Modifier ->
                            CopyCard("This phone", prompt.phone, newer == ConflictChoice.KEEP_PHONE,
                                selected == ConflictChoice.KEEP_PHONE, { selected = ConflictChoice.KEEP_PHONE }, m)
                        }
                        val driveCard = @Composable { m: Modifier ->
                            CopyCard("Google Drive", prompt.drive, newer == ConflictChoice.USE_DRIVE,
                                selected == ConflictChoice.USE_DRIVE, { selected = ConflictChoice.USE_DRIVE }, m)
                        }
                        if (maxWidth >= 560.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                phoneCard(Modifier.weight(1f)); driveCard(Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                phoneCard(Modifier.fillMaxWidth()); driveCard(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (prompt.phone.coreVersion.isNotBlank() && prompt.drive.coreVersion.isNotBlank() &&
                        prompt.phone.coreVersion != prompt.drive.coreVersion
                    ) {
                        Text(
                            "Made with different DOSBox Pure versions; the older one may not load.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (prompt.remainingAfter > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = applyToRemaining, role = Role.Checkbox, onValueChange = { applyToRemaining = it }
                            )
                        ) {
                            Checkbox(checked = applyToRemaining, onCheckedChange = null)
                            Spacer(Modifier.padding(start = 8.dp))
                            val n = prompt.remainingAfter
                            Text("Do this for the remaining $n game${if (n == 1) "" else "s"}")
                        }
                        TextButton(onClick = onKeepNewerForAll, modifier = Modifier.align(Alignment.End)) {
                            OneLineLabel("Keep the newer for all")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Actions pinned to the bottom
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onChoice(ConflictChoice.SKIP, applyToRemaining) }, modifier = Modifier.weight(1f)) {
                        OneLineLabel("Skip")
                    }
                    Button(
                        onClick = { onChoice(selected, applyToRemaining) },
                        modifier = Modifier.weight(1f).focusRequester(keepFocus)
                    ) { OneLineLabel("Keep selected") }
                }
            }
        }
    }
}

@Composable
private fun CopyCard(
    source: String, info: CopyInfo, isNewer: Boolean, selected: Boolean, onSelect: () -> Unit, modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.widthIn(min = 200.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text(source, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (info.device.isNotBlank()) Text(info.device, style = MaterialTheme.typography.bodyMedium)
            Text(SaveStateFormat.date(info.savedAt), style = MaterialTheme.typography.bodyMedium)
            Text(SaveStateFormat.age(info.savedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (isNewer) {
                Spacer(Modifier.height(4.dp))
                StatusTag("Newer", Tone.GOOD)
            }
        }
    }
}
