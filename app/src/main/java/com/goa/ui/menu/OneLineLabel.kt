package com.goa.ui.menu

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A button or menu label that stays on one line: when it doesn't fit, it shrinks (down to 70% of its size, or [minScale]) instead of
 * wrapping, and only then ends in "…". This is the app's rule for every button: text never wraps or spills over. It is
 * a plain Text underneath, so it works inside components that ask for intrinsic sizes (segmented buttons, menus).
 * The parameters are Text's, so it can stand in for one.
 */
@Composable
fun OneLineLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    /** How small it may shrink, as a share of its size, before it ends in "…" (keyboard keys go further). */
    minScale: Float = 0.7f,
    /** A decorative title: rather than end in "…" when it can't fit even at its smallest, show nothing. */
    hideIfTooLong: Boolean = false
) {
    val start = when {
        fontSize.isSp -> fontSize.value
        style.fontSize.isSp -> style.fontSize.value
        else -> 14f
    }
    val min = start * minScale
    var size by remember(text, start) { mutableFloatStateOf(start) }
    var fitted by remember(text, start) { mutableStateOf(false) }
    var tooLong by remember(text, start) { mutableStateOf(false) }
    Text(
        text = text,
        style = style.merge(
            TextStyle(
                color = color, fontSize = size.sp, fontWeight = fontWeight, fontFamily = fontFamily,
                letterSpacing = letterSpacing, textAlign = textAlign ?: TextAlign.Unspecified
            )
        ),
        maxLines = 1,
        softWrap = false,
        // A hideable title keeps Clip so it still reports when it's too wide (an ellipsized line never does)
        overflow = if (size <= min && !hideIfTooLong) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier.drawWithContent { if (fitted && !(hideIfTooLong && tooLong)) drawContent() },
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && size > min) {
                size = (size - 0.5f).coerceAtLeast(min)
            } else {
                tooLong = layout.didOverflowWidth
                fitted = true
            }
        }
    )
}
