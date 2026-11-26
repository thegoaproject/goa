package com.goa.ui.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * [leading] text with a [trailing] element (a chip or a button) beside it when there is room, or below it when there
 * isn't. A plain Row squeezes the text instead: with large fonts (Settings → Display size and text, used by people with
 * low vision) "Button A (South)" wrapped one word, or one bracket, per line next to its chip.
 *
 * Beside: the trailing element takes its natural width, at most [trailingMaxFraction] of the row, and the text keeps
 * at least [leadingMinFraction] of the row (or all it needs, if less) and never less than its longest word.
 * Below: the text gets the full width and the trailing element sits under it, aligned to the start.
 */
@Composable
fun SideOrBelow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    trailingMaxFraction: Float = 0.5f,
    leadingMinFraction: Float = 0.45f,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(contents = listOf({ Box { leading() } }, { Box { trailing() } }), modifier = modifier) { (leads, trails), c ->
        val lead = leads.first()
        val trail = trails.first()
        val gapPx = gap.roundToPx()
        val width = c.maxWidth
        val trailWidth = trail.maxIntrinsicWidth(Constraints.Infinity)
        val room = width - trailWidth - gapPx
        val beside = width == Constraints.Infinity || (
            trailWidth <= width * trailingMaxFraction &&
                room >= lead.minIntrinsicWidth(Constraints.Infinity) &&
                room >= min(lead.maxIntrinsicWidth(Constraints.Infinity), (width * leadingMinFraction).toInt())
            )
        if (beside && width != Constraints.Infinity) {
            val t = trail.measure(Constraints(maxWidth = width, maxHeight = c.maxHeight))
            val leadWidth = max(0, width - t.width - gapPx)
            val l = lead.measure(Constraints(minWidth = leadWidth, maxWidth = leadWidth, maxHeight = c.maxHeight))
            val height = max(l.height, t.height).coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                l.place(0, (height - l.height) / 2)
                t.place(width - t.width, (height - t.height) / 2)
            }
        } else if (beside) {
            // Unbounded width (e.g. inside a horizontal scroll): everything at its natural size, side by side
            val l = lead.measure(Constraints(maxHeight = c.maxHeight))
            val t = trail.measure(Constraints(maxHeight = c.maxHeight))
            val height = max(l.height, t.height)
            layout(l.width + gapPx + t.width, height) {
                l.place(0, (height - l.height) / 2)
                t.place(l.width + gapPx, (height - t.height) / 2)
            }
        } else {
            val l = lead.measure(Constraints(minWidth = width, maxWidth = width))
            val t = trail.measure(Constraints(maxWidth = width))
            val height = (l.height + gapPx + t.height).coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                l.place(0, 0)
                t.place(0, l.height + gapPx)
            }
        }
    }
}
