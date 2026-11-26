package com.goa.data.prefs

import android.content.Context
import android.content.SharedPreferences

/** How the audio output is opened: STABLE is what GOA always did; LOW_LATENCY reacts faster but may crackle. */
enum class AudioLatencyMode(val nativeId: Int) { STABLE(0), LOW_LATENCY(1) }

/** The rules behind the saved values, kept free of Android so they can be unit-tested. */
internal object DisplayAudioRules {
    const val DEFAULT_PRESET = 0          // Clean Modern
    const val DEFAULT_SHARPNESS = 0.6f
    private val PRESETS = 0..3            // 0 Clean Modern, 1 Sharp Retro, 2 Classic CRT, 3 Fullscreen Stretch

    /** One of 0 Clean Modern, 1 Sharp Retro, 2 Classic CRT, 3 Fullscreen Stretch; any other stored value reads as 0. */
    fun sanitizePreset(value: Int): Int = if (value in PRESETS) value else DEFAULT_PRESET

    /** Clamped to 0.0-1.0; a value that isn't a number reads as the default. */
    fun sanitizeSharpness(value: Float): Float = if (value.isNaN()) DEFAULT_SHARPNESS else value.coerceIn(0f, 1f)

    /** An unknown stored value reads as STABLE. */
    fun parseLatency(value: String?): AudioLatencyMode =
        AudioLatencyMode.entries.firstOrNull { it.name == value } ?: AudioLatencyMode.STABLE

    /** A game's own choice wins, each value on its own; otherwise the global default. */
    fun resolveVideo(global: Pair<Int, Float>, override: Pair<Int?, Float?>): Pair<Int, Float> =
        (override.first ?: global.first) to (override.second ?: global.second)
}

/**
 * The Video and Audio pages' choices, and each game's own video choice (from its page's Video tab or the in-game menu).
 * Games read these when they start. Keys are listed in specs/007-hamburger-menu-pages/contracts/preferences.md.
 */
object DisplayAudioPreferences {
    private const val PREFS = "goa_display_audio"
    private const val PRESET = "video.preset"
    private const val SHARPNESS = "video.sharpness"
    private const val LATENCY = "audio.latency"
    private const val PRESET_RESET_MARKER = "video.preset.cleanModernReset"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun globalVideo(context: Context): Pair<Int, Float> {
        val p = prefs(context)
        return DisplayAudioRules.sanitizePreset(p.getInt(PRESET, DisplayAudioRules.DEFAULT_PRESET)) to
            DisplayAudioRules.sanitizeSharpness(p.getFloat(SHARPNESS, DisplayAudioRules.DEFAULT_SHARPNESS))
    }

    /**
     * Clean Modern is the default every game starts with: a global choice saved before 2026-11-13 (when the game page
     * labelled Clean Modern the default whatever the global choice was) goes back to it, once. Games' own choices stay.
     */
    fun resetGlobalPresetOnce(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(PRESET_RESET_MARKER, false)) return
        p.edit().remove(PRESET).putBoolean(PRESET_RESET_MARKER, true).apply()
    }

    fun setGlobalPreset(context: Context, preset: Int) =
        prefs(context).edit().putInt(PRESET, DisplayAudioRules.sanitizePreset(preset)).apply()

    fun setGlobalSharpness(context: Context, sharpness: Float) =
        prefs(context).edit().putFloat(SHARPNESS, DisplayAudioRules.sanitizeSharpness(sharpness)).apply()

    /** The game's own choices; null where it uses the global default. */
    fun gameVideoOverride(context: Context, gameId: Long): Pair<Int?, Float?> {
        val p = prefs(context)
        val preset = if (p.contains("$PRESET.$gameId")) DisplayAudioRules.sanitizePreset(p.getInt("$PRESET.$gameId", 0)) else null
        val sharpness = if (p.contains("$SHARPNESS.$gameId")) {
            DisplayAudioRules.sanitizeSharpness(p.getFloat("$SHARPNESS.$gameId", DisplayAudioRules.DEFAULT_SHARPNESS))
        } else null
        return preset to sharpness
    }

    fun setGamePreset(context: Context, gameId: Long, preset: Int) =
        prefs(context).edit().putInt("$PRESET.$gameId", DisplayAudioRules.sanitizePreset(preset)).apply()

    fun setGameSharpness(context: Context, gameId: Long, sharpness: Float) =
        prefs(context).edit().putFloat("$SHARPNESS.$gameId", DisplayAudioRules.sanitizeSharpness(sharpness)).apply()

    /** "Use GOA default": forget the game's own video choices. */
    fun clearGameOverride(context: Context, gameId: Long) =
        prefs(context).edit().remove("$PRESET.$gameId").remove("$SHARPNESS.$gameId").apply()

    /** The preset and sharpness a game starts with. */
    fun effectiveVideo(context: Context, gameId: Long): Pair<Int, Float> =
        DisplayAudioRules.resolveVideo(globalVideo(context), gameVideoOverride(context, gameId))

    fun audioLatency(context: Context): AudioLatencyMode = DisplayAudioRules.parseLatency(prefs(context).getString(LATENCY, null))

    fun setAudioLatency(context: Context, mode: AudioLatencyMode) = prefs(context).edit().putString(LATENCY, mode.name).apply()
}
