package com.goa.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

object DynamicTheme {

    /**
     * Extracts vibrant/dominant colors from a game's box art bitmap
     * and produces an adaptive Material 3 ColorScheme.
     */
    fun createColorSchemeFromBitmap(bitmap: Bitmap): ColorScheme {
        val palette = Palette.from(bitmap).generate()

        val primaryInt = palette.getVibrantColor(DarkPrimary.value.toInt())
        val secondaryInt = palette.getMutedColor(DarkSecondary.value.toInt())
        val surfaceInt = palette.getDarkMutedColor(DarkSurface.value.toInt())

        return darkColorScheme(
            primary = Color(primaryInt),
            secondary = Color(secondaryInt),
            surface = Color(surfaceInt),
            background = DarkBackground,
            onPrimary = DarkOnPrimary,
            onSecondary = DarkOnSecondary,
            onSurface = DarkOnSurface,
            onBackground = DarkOnBackground
        )
    }
}
