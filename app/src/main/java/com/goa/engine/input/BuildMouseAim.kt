package com.goa.engine.input

import java.io.File

/**
 * Detects games set up for GOA's Build-engine mouse aiming: Apogee CONTROL-library configs (Duke Nukem 3D, Shadow
 * Warrior, Redneck Rampage, NAM, ...) with toggle aiming (`MouseAiming = 0`) saved as switched on (Shadow Warrior's
 * `MouseAimingOn = 1`, Duke's `AimingFlag = 1`). The game then starts with vertical mouse motion mapped to
 * `analog_lookingupanddown`, giving proportional pitch that stays where it is put, with no key to hold.
 * Games without that setup keep PageUp/PageDown look, so tilting never makes them walk.
 */
object BuildMouseAim {
    private val TOGGLE = Regex("""(?im)^\s*MouseAiming\s*=\s*0\s*$""")
    private val SAVED_ON = Regex("""(?im)^\s*(MouseAimingOn|AimingFlag)\s*=\s*1\s*$""")

    fun isConfigured(cfgText: String): Boolean = TOGGLE.containsMatchIn(cfgText) && SAVED_ON.containsMatchIn(cfgText)

    /** True when any top-level CFG in the install directory is configured for mouse aiming. */
    fun isConfigured(installDir: File): Boolean =
        installDir.listFiles { f -> f.isFile && f.extension.equals("cfg", ignoreCase = true) && f.length() < 256 * 1024 }
            .orEmpty()
            .any { cfg -> runCatching { isConfigured(cfg.readText(Charsets.ISO_8859_1)) }.getOrDefault(false) }
}
