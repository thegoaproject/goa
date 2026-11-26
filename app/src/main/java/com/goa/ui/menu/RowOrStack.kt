package com.goa.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Buttons of equal width side by side, or stacked full width when one of [labels] would not fit whole in its share of
 * the row: with large fonts (low vision) "Save Slot" / "Load Slot" had shrunk to "Sa…" / "Lo…".
 *
 * [chrome] is what a button adds around its label (icon, gap, content padding). [content] gets the modifier to put on
 * each button.
 */
@Composable
fun RowOrStack(
    labels: List<String>,
    modifier: Modifier = Modifier,
    chrome: Dp = 16.dp + 6.dp + 48.dp,   // a 16 dp icon, its gap, and Button's 24 dp padding each side
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    spacing: Dp = 8.dp,
    content: @Composable (itemModifier: Modifier) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val need = with(density) { labels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp() } + chrome
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val count = labels.size.coerceAtLeast(1)
        val share = (maxWidth - spacing * (count - 1)) / count
        if (need > share) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                content(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically
            ) { content(Modifier.weight(1f)) }
        }
    }
}
