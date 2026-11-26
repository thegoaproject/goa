package com.goa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.goa.BuildConfig

// ============================================================================
// Core Design Token Language
// ============================================================================
val TokenPrimary = Color(0xFFC0533A)       // #c0533a - Terracotta / Rust Red
val TokenSecondary = Color(0xFFE8845A)     // #e8845a - Warm Clay / Peach Orange
val TokenAccent = Color(0xFF5C8A3C)        // #5c8a3c - Retro Moss Olive Green
val TokenBackground = Color(0xFFFDF6F0)    // #fdf6f0 - Vintage Off-White Warm Paper
val TokenSurface = Color(0xFFFFFFFF)       // #ffffff - Clean Card Surface
val TokenText = Color(0xFF2D2320)          // #2d2320 - Deep Espresso Charcoal

// ============================================================================
// Light Mode Tokens (Faithful to design token language)
// ============================================================================
val LightPrimary = TokenPrimary
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDBD2)
val LightOnPrimaryContainer = Color(0xFF3E1005)

val LightSecondary = TokenSecondary
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFDCD1)
val LightOnSecondaryContainer = Color(0xFF341104)

val LightTertiary = TokenAccent
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFDFF0D1)
val LightOnTertiaryContainer = Color(0xFF132405)

val LightBackground = TokenBackground
val LightOnBackground = TokenText

val LightSurface = TokenSurface
val LightOnSurface = TokenText

val LightSurfaceVariant = Color(0xFFF4E8DF)
val LightOnSurfaceVariant = Color(0xFF584C47)
val LightSurfaceContainerHighest = Color(0xFFEDE0D5)

val LightOutline = Color(0xFF8A7B74)
val LightOutlineVariant = Color(0xFFD8C7C0)

// ============================================================================
// Dark Mode Tokens (Harmonious night inversion derived from token palette)
// ============================================================================
val DarkPrimary = Color(0xFFFFB4A1)
val DarkOnPrimary = Color(0xFF5A1B0B)
val DarkPrimaryContainer = Color(0xFF7A2E1C)
val DarkOnPrimaryContainer = Color(0xFFFFDBD2)

val DarkSecondary = Color(0xFFFFB598)
val DarkOnSecondary = Color(0xFF4E210E)
val DarkSecondaryContainer = Color(0xFF6B3620)
val DarkOnSecondaryContainer = Color(0xFFFFDCD1)

val DarkTertiary = Color(0xFFA1D780) // High-contrast moss green for dark surfaces
val DarkOnTertiary = Color(0xFF1C3A06)
val DarkTertiaryContainer = Color(0xFF33511B)
val DarkOnTertiaryContainer = Color(0xFFDCF6C7)

val DarkBackground = Color(0xFF1A1412) // Warm deep obsidian matching #2d2320 text hue
val DarkOnBackground = Color(0xFFF7EDE7)

val DarkSurface = Color(0xFF221A18)
val DarkOnSurface = Color(0xFFF7EDE7)

val DarkSurfaceVariant = Color(0xFF352B27)
val DarkOnSurfaceVariant = Color(0xFFD8C7C0)
val DarkSurfaceContainerHighest = Color(0xFF453833)

val DarkOutline = Color(0xFFA08F88)
val DarkOutlineVariant = Color(0xFF53443F)

// ============================================================================
// Emulation & Status Badges
// ============================================================================
val EmulationPillarboxBlack = Color(0xFF000000)

// GOA's accents. In GOA OSS (BuildConfig.OSS_EDITION) they are the matching Material You roles instead, so every
// screen that uses them follows the phone's wallpaper colours (or stays grey without them).
val RetroAmber: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.primary else TokenSecondary
val RetroGreen: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.tertiary else TokenAccent
val RetroCyan: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.secondary else Color(0xFF429EA6)

val ErrorRed = Color(0xFFBA1A1A)

// In-game controls (drawn over the black game screen; GOA OSS runs the game screen in dark Material You)
val ControlAccent: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.primary else Color(0xFFFFD54F)
val ControlAccentAlt: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.tertiary else Color(0xFF00E5FF)
val ControlBrand: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.primary else TokenPrimary
val ControlBrandLight: Color
    @Composable @ReadOnlyComposable get() = if (BuildConfig.OSS_EDITION) MaterialTheme.colorScheme.inversePrimary else TokenSecondary
