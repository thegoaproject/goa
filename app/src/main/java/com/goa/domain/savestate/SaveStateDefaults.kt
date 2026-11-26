package com.goa.domain.savestate

import android.content.Context

/**
 * What a save state is recorded with when GOA has nothing better: this phone's name and the DOSBox Pure version
 * that last ran. The version is remembered at each DOSBox start, because outside a game no core is loaded to ask.
 */
object SaveStateDefaults {
    private const val PREFS = "goa_savestates"
    private const val KEY_CORE_VERSION = "dosboxCoreVersion"

    fun rememberCoreVersion(context: Context, version: String) {
        if (version.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CORE_VERSION, version).apply()
    }

    fun coreVersion(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CORE_VERSION, null).orEmpty()

    fun forGame(context: Context, gameKey: String, coreVersion: String = coreVersion(context)) =
        SaveStateStore.Defaults(gameKey = gameKey, device = DeviceName.get(context), coreVersion = coreVersion)
}
