package com.goa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.telemetry.ConsentState

/** Plain-language summary of what opt-in sharing sends, shared by the consent sheet and Privacy settings. */
internal const val SHARED_DATA_SUMMARY =
    "Shared: how each game ran (speed, sound, crashes, how long you played), settings you changed, results of " +
        "automatic fixes, your phone's make, model, chipset, Android version and memory size, and a random player ID " +
        "you can reset."
internal const val NEVER_SHARED_SUMMARY =
    "Never shared: your GOG account, name, email, location, IP address, files, or anything you type. Used only to " +
        "make games run better in GOA. Kept for 18 months."

/** One-time opt-in prompt shown on the first game launch (FR-008). Any choice, or dismissing it, continues to the game. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(onDecision: (ConsentState?) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onDecision(null) }, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Help make DOS games run better", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "GOA can share anonymous game health data so we can fix games that don't run well and improve " +
                    "default controls and sound settings for everyone.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Text(SHARED_DATA_SUMMARY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(NEVER_SHARED_SUMMARY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("You can change this any time in ☰ → Account & Privacy.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onDecision(ConsentState.DECLINED) }, modifier = Modifier.weight(1f)) { com.goa.ui.menu.OneLineLabel("Don't share") }
                Button(onClick = { onDecision(ConsentState.GRANTED) }, modifier = Modifier.weight(1f)) { com.goa.ui.menu.OneLineLabel("Share anonymous data") }
            }
        }
    }
}
