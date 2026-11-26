package com.goa.domain.controls

import com.goa.engine.input.ControllerActions
import com.goa.engine.input.StickMode
import java.io.File
import java.util.Locale

enum class GameEngine(val label: String) {
    BUILD("Build engine"),
    ID_TECH_1("Doom (id Tech 1) engine"),
    QUAKE("Quake engine"),
    WOLF3D("Wolfenstein 3D engine"),
    POINT_AND_CLICK("Sierra / LucasArts adventure engine")
}

/**
 * Engine fallback: games built on the same engine share their controls (every Build engine game is dual-stick with
 * mouse look, every Sierra SCI/AGI or LucasArts SCUMM game is point-and-click). Detected from files in the install, so
 * it is stronger evidence than a title keyword and is used before the genre fallback.
 */
object EngineLayouts {
    data class Match(val engine: GameEngine, val stickMode: StickMode, val bindings: Map<String, String>, val summary: String)

    private val FPS_BINDINGS: Map<String, String> =
        com.goa.engine.input.GamepadButton.values().associate { it.key to (ControllerActions.defaultBindings[it] ?: ControllerActions.ENTER_SPACE).id }

    /**
     * Per engine, only the moves the engine really has, on GOA's shooter convention: A jumps, X uses, Y next weapon,
     * B and Start Esc. Every button sends one key, so none does two things at once.
     * - Build: A jumps ('A'), X opens (Space, which also selects in Build menus), crouch toggles on L3 ('Z').
     * - Quake / Hexen II: A is Enter (bound to +jump, and it selects in their menus), Y is their own next-weapon key
     *   ('/', impulse 10). They have no use key (doors open on touch), so X is free; L3 holds swim down / crouch ('Z').
     * - Doom engine: their menus only select with Enter, so Start is Enter (Heretic and Hexen use their inventory item
     *   with it in play). Hexen and Strife jump on '/'; Doom and Heretic can't jump, so A uses and X cycles weapons back.
     * R3 toggles Run everywhere.
     */
    private fun fpsBindings(engine: GameEngine, canJump: Boolean): Map<String, String> = FPS_BINDINGS + when (engine) {
        GameEngine.BUILD -> mapOf("btn_a" to "jump_build", "btn_x" to "space_use", "btn_b" to "esc_menu", "btn_l3" to "crouch_toggle")
        GameEngine.QUAKE -> mapOf(
            "btn_a" to "jump_enter", "btn_x" to "none", "btn_y" to "next_weapon_slash", "btn_b" to "esc_menu", "btn_l3" to "crouch"
        )
        else -> mapOf(
            "btn_a" to if (canJump) "jump_slash" else "space_use",
            "btn_x" to if (canJump) "space_use" else "prev_weapon",
            "btn_b" to "esc_menu",
            "btn_l3" to "space_use",
            "btn_start" to "enter_select"
        )
    } + mapOf("btn_r3" to "run_toggle") + when (engine) {
        // D-pad left/right cycle weapons; up/down keep their arrows (the game's own items need its own profile)
        GameEngine.QUAKE -> mapOf("btn_dpad_left" to "prev_weapon_8", "btn_dpad_right" to "next_weapon_slash")
        else -> mapOf("btn_dpad_left" to "prev_weapon", "btn_dpad_right" to "next_weapon")
    }

    /** Hexen and Strife are the id Tech 1 games that jump. */
    private val JUMPING_WADS = setOf("hexen.wad", "hexdd.wad", "strife1.wad", "strife0.wad")

    // Wolfenstein-style keyboard shooter: Ctrl fire, Alt strafe, Space open, Shift run
    private val WOLF_BINDINGS = mapOf(
        "btn_a" to "fire_ctrl", "btn_b" to "fire_space", "btn_x" to "alt_fire", "btn_y" to "sprint_shift",
        "btn_l1" to "alt_fire", "btn_r1" to "fire_ctrl", "btn_l2" to "alt_fire", "btn_r2" to "fire_ctrl",
        "btn_l3" to "pause_menu", "btn_r3" to "run_toggle", "btn_start" to "enter_select", "btn_select" to "esc_menu"
    )

    fun detect(installDir: File): Match? {
        if (!installDir.isDirectory) return null
        val names = installDir.walkTopDown().maxDepth(2).filter { it.isFile }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        return detectFromNames(names)
    }

    /** Pure detection from the lower-cased file names found in an install (testable without a filesystem). */
    fun detectFromNames(names: Set<String>): Match? {
        fun any(pred: (String) -> Boolean) = names.any(pred)
        return when {
            "tiles000.art" in names || any { it.endsWith(".grp") } || "blood.rff" in names ->
                fps(GameEngine.BUILD)
            any { it.endsWith(".wad") } ->
                fps(GameEngine.ID_TECH_1, canJump = names.any { it in JUMPING_WADS })
            "pak0.pak" in names ->
                fps(GameEngine.QUAKE)
            any { it.startsWith("vswap.") || it.startsWith("maphead.") } ->
                Match(GameEngine.WOLF3D, StickMode.CLASSIC_KEYBOARD, WOLF_BINDINGS, "${GameEngine.WOLF3D.label} detected: arrows move, Ctrl fire, Alt strafe, Space open")
            looksPointAndClick(names) ->
                GenreLayouts.byId("strategy_mouse")!!.let {
                    Match(GameEngine.POINT_AND_CLICK, it.stickMode, it.bindings, "${GameEngine.POINT_AND_CLICK.label} detected: left and right mouse buttons")
                }
            else -> null
        }
    }

    private fun fps(engine: GameEngine, canJump: Boolean = true) =
        Match(engine, StickMode.FPS_DUAL_STICK, fpsBindings(engine, canJump), "${engine.label} detected: left stick moves, right stick looks")

    /** Sierra AGI/SCI and LucasArts SCUMM install signatures. */
    private fun looksPointAndClick(names: Set<String>): Boolean {
        val sci = "resource.map" in names && names.any { it.startsWith("resource.0") }
        val agi = "logdir" in names && "viewdir" in names
        val scumm = names.any { it.endsWith(".lfl") || it.endsWith(".la0") || it.endsWith(".sou") || (it.startsWith("monkey") && it.endsWith(".000")) }
        return sci || agi || scumm
    }
}
