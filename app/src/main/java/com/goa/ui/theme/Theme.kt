package com.goa.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.goa.BuildConfig

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    background = LightBackground,
    onBackground = LightOnBackground,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

// GOA OSS without Material You colours: neutral greys only
private val OssGreyLight = lightColorScheme(
    primary = Color(0xFF5E5E5E), onPrimary = Color.White, primaryContainer = Color(0xFFE2E2E2), onPrimaryContainer = Color(0xFF1B1B1B),
    secondary = Color(0xFF5E5E5E), onSecondary = Color.White, secondaryContainer = Color(0xFFE2E2E2), onSecondaryContainer = Color(0xFF1B1B1B),
    tertiary = Color(0xFF5E5E5E), onTertiary = Color.White, tertiaryContainer = Color(0xFFE2E2E2), onTertiaryContainer = Color(0xFF1B1B1B),
    background = Color(0xFFF9F9F9), onBackground = Color(0xFF1B1B1B), surface = Color(0xFFF9F9F9), onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE2E2E2), onSurfaceVariant = Color(0xFF474747), outline = Color(0xFF777777), outlineVariant = Color(0xFFC6C6C6)
)
private val OssGreyDark = darkColorScheme(
    primary = Color(0xFFC6C6C6), onPrimary = Color(0xFF303030), primaryContainer = Color(0xFF474747), onPrimaryContainer = Color(0xFFE2E2E2),
    secondary = Color(0xFFC6C6C6), onSecondary = Color(0xFF303030), secondaryContainer = Color(0xFF474747), onSecondaryContainer = Color(0xFFE2E2E2),
    tertiary = Color(0xFFC6C6C6), onTertiary = Color(0xFF303030), tertiaryContainer = Color(0xFF474747), onTertiaryContainer = Color(0xFFE2E2E2),
    background = Color(0xFF131313), onBackground = Color(0xFFE2E2E2), surface = Color(0xFF131313), onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF474747), onSurfaceVariant = Color(0xFFC6C6C6), outline = Color(0xFF919191), outlineVariant = Color(0xFF474747)
)

val LocalThemeIsDark = staticCompositionLocalOf { false }

@Composable
fun GoaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    customColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // GOA OSS: 100% Material You, from the phone's wallpaper (Android 12+), else plain greys. Nothing of GOA's own palette.
        BuildConfig.OSS_EDITION -> {
            val context = LocalContext.current
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                darkTheme -> OssGreyDark
                else -> OssGreyLight
            }
        }
        customColorScheme != null -> customColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalThemeIsDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

@Composable
fun GOATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    customColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) = GoaTheme(darkTheme, dynamicColor, customColorScheme, content)
