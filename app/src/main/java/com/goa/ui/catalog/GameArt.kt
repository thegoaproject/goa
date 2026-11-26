package com.goa.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.max
import coil.compose.AsyncImage
import com.goa.data.api.toSecureGogImageUrl
import com.goa.ui.theme.GoaFontFamily
import com.goa.ui.theme.MinRetroTextSize

/**
 * A game's box art. Underneath the picture sits a title tile (the game's initials in the retro face), so a game with
 * no artwork, one whose link has gone stale, or one still loading shows that instead of an empty square.
 */
@Composable
fun GameArt(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = title
) {
    Box(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val size = with(LocalDensity.current) { max(maxWidth * 0.32f, MinRetroTextSize.toDp()).toSp() }
            Text(
                text = monogram(title),
                style = TextStyle(fontFamily = GoaFontFamily, fontSynthesis = FontSynthesis.None, fontSize = size),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
        AsyncImage(
            model = url.toSecureGogImageUrl(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** "One Must Fall: 2097" → "OM", "The Last Express" → "LE", "God of Thunder" → "GT", "Ken's Labyrinth" → "KL", "DOOM" → "D". */
internal fun monogram(title: String): String {
    // Apostrophes join a word ("Ken's"), anything else separates words
    val words = title.replace(Regex("['’]"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val minor = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for")
    val significant = words.filterNot { it.lowercase() in minor }.ifEmpty { words }
    return significant.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}
