package com.goa.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import com.goa.ui.theme.GoaFontFamily
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.goa.ui.theme.TokenSecondary

private fun parsePath(pathData: String): Path = PathParser().parsePathString(pathData).toPath()

@Composable
private fun logoColor(role: LogoRole, holeColor: Color): Color = when (role) {
    LogoRole.Primary -> MaterialTheme.colorScheme.primary
    LogoRole.Secondary -> if (com.goa.BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.secondary else TokenSecondary
    LogoRole.Tertiary -> MaterialTheme.colorScheme.tertiary
    LogoRole.Ink -> MaterialTheme.colorScheme.onBackground
    LogoRole.Hole -> holeColor
}

/** The GOA 5¼" floppy-disk mark, tinted from the active theme so it follows the in-app dark toggle. */
@Composable
fun GoaMark(
    modifier: Modifier = Modifier,
    holeColor: Color = MaterialTheme.colorScheme.background,
    contentDescription: String? = null
) {
    val layers = remember { GoaLogoData.markLayers.map { it to parsePath(it.pathData) } }
    val fills = layers.map { (layer, _) -> logoColor(layer.role, holeColor) }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier.aspectRatio(GoaLogoData.MARK_WIDTH / GoaLogoData.MARK_HEIGHT).then(semantics)) {
        scale(size.width / GoaLogoData.MARK_WIDTH, pivot = Offset.Zero) {
            layers.forEachIndexed { i, (_, path) -> drawPath(path, fills[i]) }
        }
    }
}

/** The GOA wordmark with the striped-sun "O". Size it with a height or width modifier. */
@Composable
fun GoaWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = "GOA"
) {
    if (com.goa.BuildConfig.OSS_EDITION) {
        OssWordmark(modifier, contentDescription)
        return
    }
    val layers = remember { GoaLogoData.wordmarkLayers.map { it to parsePath(it.pathData) } }
    val fills = layers.map { (layer, _) -> logoColor(layer.role, Color.Unspecified) }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier.aspectRatio(GoaLogoData.WORDMARK_WIDTH / GoaLogoData.WORDMARK_HEIGHT).then(semantics)) {
        scale(size.width / GoaLogoData.WORDMARK_WIDTH, pivot = Offset.Zero) {
            translate(-GoaLogoData.WORDMARK_X, -GoaLogoData.WORDMARK_Y) {
                layers.forEachIndexed { i, (_, path) -> drawPath(path, fills[i]) }
            }
        }
    }
}

/** GOA OSS's wordmark: plain "GOA OSS" in the DOS font and the Material You primary colour, no GOA logo. */
@Composable
private fun OssWordmark(modifier: Modifier, contentDescription: String?) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        Text(
            text = "GOA OSS",
            color = MaterialTheme.colorScheme.primary,
            fontFamily = GoaFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (maxHeight * 0.8f).toSp() },
            maxLines = 1,
            softWrap = false,
            modifier = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
        )
    }
}
