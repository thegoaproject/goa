package com.goa.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.goa.data.prefs.AudioLatencyMode
import com.goa.data.prefs.DisplayAudioPreferences
import com.goa.ui.theme.GOATheme

/** ☰ → Audio: Stable (what GOA always did) or Low latency. Applies to the next game started. */
@Composable
fun AudioPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(DisplayAudioPreferences.audioLatency(context)) }
    MenuPageScaffold(title = MenuPage.AUDIO.title, onBack = onBack) {
        AudioLatencyCard(mode = mode, onSelect = {
            mode = it
            DisplayAudioPreferences.setAudioLatency(context, it)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioLatencyCard(mode: AudioLatencyMode, onSelect: (AudioLatencyMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Audio latency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            val options = listOf(AudioLatencyMode.STABLE to "Stable", AudioLatencyMode.LOW_LATENCY to "Low latency")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = mode == value,
                        onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        label = { OneLineLabel(label) }
                    )
                }
            }
            Text(
                text = "Stable is the safe choice. Low latency reacts faster but may crackle on some phones. " +
                    "Applies to the next game you start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "Phone", widthDp = 411)
@Preview(name = "Tablet", widthDp = 840)
@Composable
private fun AudioLatencyCardPreview() {
    GOATheme { AudioLatencyCard(mode = AudioLatencyMode.STABLE, onSelect = {}) }
}
