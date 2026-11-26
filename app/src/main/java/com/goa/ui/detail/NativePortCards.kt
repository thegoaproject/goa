package com.goa.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.domain.nativeport.NativeEngine
import com.goa.domain.quake.QuakeSupport
import com.goa.domain.tombraider.TombRaiderSupport
import com.goa.domain.tombraider.TombRaiderSupport.PadLayout

/**
 * Cards for games with a native port (Quake on TyrQuake, Tomb Raider on OpenLara). The port reads the controller
 * itself, so GOA's DOS key mappings don't apply to it: these cards show the port's own layout instead.
 */

@Composable
private fun PortCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two options side by side, one selected (Material 3's segmented button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> TwoWayChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ButtonTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((button, action) in rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = button,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(140.dp)
                )
                Text(text = action, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Native port or DOSBox for this game. Takes effect on the next launch; [onChange] lets the Controls tab switch between
 * the port's layout and the DOS mapping editor.
 */
@Composable
fun NativeEngineCard(gameId: Long, port: NativeEngine.Port, onChange: (NativeEngine.Choice) -> Unit) {
    val context = LocalContext.current
    var choice by remember(gameId) { mutableStateOf(NativeEngine.choice(context, gameId)) }
    PortCard {
        CardTitle("Engine", "How GOA runs this game")
        TwoWayChoice(NativeEngine.Choice.entries, choice, { it.label }) {
            choice = it
            NativeEngine.setChoice(context, gameId, it)
            onChange(it)
        }
        Text(
            text = when (choice) {
                NativeEngine.Choice.NATIVE -> "${port.engineName}: the game's own engine rebuilt for phones, using " +
                    "the game's files. Smooth and sharp, with ${port.engineName}'s own menus and options."
                NativeEngine.Choice.DOSBOX -> "The original DOS game on an emulated 90s PC, exactly as it was. Much " +
                    "slower: an emulated Pentium manages 10 to 40 fps in this game."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Tomb Raider on OpenLara: Original (PlayStation) or Modern face buttons, and what every button does. */
@Composable
fun OpenLaraControlsCard(gameId: Long) {
    val context = LocalContext.current
    var layout by remember(gameId) { mutableStateOf(TombRaiderSupport.layout(context, gameId)) }
    PortCard {
        CardTitle("Controller Layout", "OpenLara reads your controller directly.")
        TwoWayChoice(PadLayout.entries, layout, { it.label }) {
            layout = it
            TombRaiderSupport.setLayout(context, gameId, it)
        }
        Text(
            text = when (layout) {
                PadLayout.ORIGINAL -> "The 1996 PlayStation layout, by button position."
                PadLayout.MODERN -> "A jumps and X acts, like today's action games."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ButtonTable(TombRaiderSupport.buttonActions(layout))
    }
}

/** Quake on TyrQuake: GOA's shooter layout as Quake binds it (set by GOA before every launch). */
@Composable
fun QuakeControlsCard() {
    PortCard {
        CardTitle(
            "Controller Layout",
            "TyrQuake reads your controller directly. GOA sets these buttons each time Quake starts."
        )
        ButtonTable(QuakeSupport.BUTTON_ACTIONS)
    }
}
