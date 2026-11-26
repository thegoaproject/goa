package com.goa.ui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * How a label is shown on one line in a fixed width: the longest form of [text] at the largest size that fits.
 * Sizes are tried from [sizes]' first (largest) to last; when even the smallest is too wide, trailing words go
 * ("GRENADE LAUNCHER" -> "GRENADE") if [dropWords], and as a last resort the full text at the smallest size
 * (the caller ellipsizes it).
 */
internal fun fitOneLine(
    text: String,
    sizes: List<Float>,
    dropWords: Boolean,
    fits: (String, Float) -> Boolean
): Pair<String, Float> {
    val forms = buildList {
        add(text)
        if (dropWords) {
            val words = text.trim().split(Regex("\\s+"))
            for (n in words.size - 1 downTo 1) add(words.take(n).joinToString(" "))
        }
    }
    for (form in forms) for (size in sizes) if (fits(form, size)) return form to size
    return text to sizes.last()
}

/**
 * One line of text that never wraps: it shrinks (from [maxSize] down to [minSize]) to fit the width it is given, then
 * drops trailing words. Used on the touch pad's buttons, which keep a fixed size whatever the game calls the action.
 */
@Composable
internal fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    maxSize: TextUnit = 11.sp,
    minSize: TextUnit = 7.sp,
    fontWeight: FontWeight = FontWeight.Black,
    letterSpacing: TextUnit = 0.8.sp,
    dropWords: Boolean = true
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        val (shown, size) = remember(text, maxWidth, maxSize, minSize, fontWeight, letterSpacing, dropWords) {
            val sizes = generateSequence(maxSize.value) { it - 0.5f }.takeWhile { it >= minSize.value - 0.01f }.toList()
            fitOneLine(text, sizes, dropWords) { form, s ->
                maxWidth == Constraints.Infinity || measurer.measure(
                    form,
                    style = TextStyle(fontSize = s.sp, fontWeight = fontWeight, letterSpacing = letterSpacing),
                    maxLines = 1,
                    softWrap = false
                ).size.width <= maxWidth
            }
        }
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = shown,
                color = color,
                fontSize = size.sp,
                fontWeight = fontWeight,
                letterSpacing = letterSpacing,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
