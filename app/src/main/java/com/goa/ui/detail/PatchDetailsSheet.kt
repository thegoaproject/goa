package com.goa.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.data.repository.GamePatchProfile

/** "N game settings pre-patched": every setting GOA writes into the game's own files, grouped by profile and file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchDetailsSheet(profiles: List<GamePatchProfile>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            item {
                Text("Pre-patched settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Written into the game's own config files before it starts, so it runs right without setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            profiles.filter { it.configRules.isNotEmpty() }.forEach { profile ->
                item(key = profile.id) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        profile.configRules.forEach { rule ->
                            Text(
                                rule.filePatterns.joinToString(", "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            rule.entries.forEach { (key, value) ->
                                Text(
                                    "$key = $value",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            profiles.firstOrNull { !it.universal }?.dosboxOverrides?.let { d ->
                item(key = "dosbox") {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("Emulator (dosbox.conf)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        listOf(
                            "sbtype" to d.sbtype, "sbbase" to d.sbbase, "irq" to d.irq, "dma" to d.dma, "hdma" to d.hdma,
                            "oplmode" to d.oplmode, "gus" to d.gus, "cycles" to d.cycles
                        ).forEach { (k, v) ->
                            Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
