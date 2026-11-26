package com.goa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.goa.R

// Material 3 Expressive Typography Scale
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// ---------------------------------------------------------------------------------------------------------------------
// Retro face (Modern DOS 8x16, the typeface goodoldandroid.app uses; CC0, credited under Settings → Open Source Licenses
// & Credits). It is NOT the app's typeface: the app keeps the system font above. It is used only for the hero game title
// on a game's card and inside the Help & FAQ, Privacy Policy, Terms of Use and licence dialogs.
// It has a single weight, so Bold maps to the same file and FontSynthesis.None stops Compose smearing it into a fake
// bold. Characters it lacks (Cyrillic, CJK, ...) fall back to the system font per glyph.
// ---------------------------------------------------------------------------------------------------------------------

val GoaFontFamily = FontFamily(
    Font(R.font.modern_dos_8x16, FontWeight.Normal),
    Font(R.font.modern_dos_8x16, FontWeight.Bold)
)

/** Smallest size the retro face is used at: below this a bitmap face blurs. */
val MinRetroTextSize = 14.sp

private fun retro(weight: FontWeight, size: Int, lineHeight: Int) = TextStyle(
    fontFamily = GoaFontFamily,
    fontWeight = weight,
    fontSynthesis = FontSynthesis.None,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp // monospaced face: extra tracking breaks its rhythm
)

/** All 15 Material 3 styles in the retro face (styles left out would silently keep the system font). */
val RetroTypography = Typography(
    displayLarge = retro(FontWeight.ExtraBold, 57, 72),
    displayMedium = retro(FontWeight.Bold, 45, 58),
    displaySmall = retro(FontWeight.Bold, 36, 46),
    headlineLarge = retro(FontWeight.SemiBold, 32, 40),
    headlineMedium = retro(FontWeight.SemiBold, 28, 36),
    headlineSmall = retro(FontWeight.SemiBold, 24, 32),
    titleLarge = retro(FontWeight.Bold, 22, 28),
    titleMedium = retro(FontWeight.Medium, 16, 24),
    titleSmall = retro(FontWeight.Medium, 14, 20),
    bodyLarge = retro(FontWeight.Normal, 16, 24),
    bodyMedium = retro(FontWeight.Normal, 14, 20),
    bodySmall = retro(FontWeight.Normal, 14, 20),
    labelLarge = retro(FontWeight.Bold, 14, 20),
    labelMedium = retro(FontWeight.Medium, 14, 20),
    labelSmall = retro(FontWeight.Medium, 14, 20)
)

/** Everything inside uses the retro face; colours and shapes are inherited from the surrounding theme. */
@Composable
fun RetroTypographyScope(content: @Composable () -> Unit) {
    MaterialTheme(typography = RetroTypography, content = content)
}

/** Every style of [this] by name, for tests and tooling. */
fun Typography.allStyles(): Map<String, TextStyle> = mapOf(
    "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
    "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
    "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
    "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
    "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall
)
