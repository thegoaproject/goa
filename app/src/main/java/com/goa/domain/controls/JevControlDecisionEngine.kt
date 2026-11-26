package com.goa.domain.controls

import com.goa.engine.input.ControllerAction
import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.StickMode
import java.io.File
import java.util.Locale

/**
 * JEV (Justified Evidence & Valuation) Decision Engine:
 * Deterministic heuristic rule evaluator that scans documentation, readmes, and manual texts
 * to extract movement style, key bindings, and input characteristics for DOS games.
 */
class JevControlDecisionEngine {

    companion object {
        private val DOC_EXTENSIONS = setOf("txt", "doc", "me", "1st", "hlp", "nfo")
        private val DOC_NAMES = listOf("readme", "manual", "help", "guide", "keys", "commands", "controls", "install")
    }

    fun evaluateDocumentation(
        installDir: File,
        slug: String?,
        title: String?
    ): ResolvedControlScheme? {
        val lowerSlug = slug?.lowercase(Locale.ROOT) ?: ""
        val lowerTitle = title?.lowercase(Locale.ROOT) ?: ""

        // Fast path: known title keywords
        if (lowerSlug.contains("keen") || lowerTitle.contains("commander keen")) {
            return ResolvedControlScheme(
                stickMode = StickMode.CLASSIC_KEYBOARD,
                suggestedBindings = mapOf(
                    GamepadButton.BUTTON_A to ControllerActions.JUMP_CTRL,
                    GamepadButton.BUTTON_B to ControllerActions.POGO_ALT,
                    GamepadButton.BUTTON_X to ControllerActions.FIRE_SPACE,
                    GamepadButton.BUTTON_Y to ControllerActions.STATUS_F1
                ),
                source = "JEV_HEURISTIC",
                confidence = 0.98f,
                summary = "Commander Keen platformer detected: Arrow navigation + Jump (Ctrl), Pogo (Alt), Fire (Space)",
                tier = ControlsTier.GENRE
            )
        }

        // Known genres have their own button conventions (EA Sports: A/S/D/Q; flight: +/- throttle; RPG: I/C/M; strategy: mouse)
        GenreLayouts.genreOf(lowerSlug, lowerTitle)?.let { genre -> return genreScheme(genre, lowerSlug, lowerTitle) }

        // Find documentation files in install directory
        val docFiles = findDocFiles(installDir)
        if (docFiles.isEmpty()) return null

        val docTextBuilder = StringBuilder()
        for (f in docFiles.take(4)) {
            runCatching {
                val text = f.readText().take(64 * 1024)
                docTextBuilder.append("\n=== File: ${f.name} ===\n").append(text)
            }
        }
        val fullText = docTextBuilder.toString()
        if (fullText.isBlank()) return null

        return evaluateText(fullText, lowerSlug, lowerTitle)
    }

    private fun schemeFor(layout: GenreLayout, confidence: Float, summary: String, tier: ControlsTier): ResolvedControlScheme {
        val bindings = layout.bindings.mapNotNull { (btnKey, actionId) ->
            val btn = GamepadButton.values().firstOrNull { it.key == btnKey }
            val action = ControllerActions.findById(actionId)
            if (btn != null && action != null) btn to action else null
        }.toMap()
        return ResolvedControlScheme(layout.stickMode, bindings, "JEV_HEURISTIC", confidence, summary, tier)
    }

    private fun genreScheme(genre: GameGenre, slug: String, title: String): ResolvedControlScheme {
        val layout = GenreLayouts.byId(GenreLayouts.layoutIdsFor(genre, slug, title).first())!!
        val confidence = if (layout.id == SportsLayouts.LETTERS_ID) 0.80f else 0.72f
        return schemeFor(layout, confidence, "${genre.name.lowercase().replaceFirstChar { it.uppercase() }} game detected: ${layout.label}", ControlsTier.GENRE)
    }

    /**
     * What a game with no profile and no readable manual gets. Most DOS games are keyboard games and about a third are
     * point-and-click, so this is never a shooter scheme: that is only chosen on evidence.
     */
    fun fallbackScheme(installDir: File, slug: String?, title: String?): ResolvedControlScheme {
        val lowerSlug = slug?.lowercase(Locale.ROOT).orEmpty()
        val lowerTitle = title?.lowercase(Locale.ROOT).orEmpty()
        EngineLayouts.detect(installDir)?.let { e ->
            return ResolvedControlScheme(e.stickMode, e.bindings.toActions(), "ENGINE", 0.85f, e.summary, ControlsTier.ENGINE)
        }
        GenreLayouts.genreOf(lowerSlug, lowerTitle)?.let { return genreScheme(it, lowerSlug, lowerTitle).copy(confidence = 0.5f) }
        return schemeFor(GenreLayouts.byId("generic_keys")!!, 0.5f, "Keyboard game: Space, Ctrl and Alt for actions", ControlsTier.GUESS)
    }

    private fun Map<String, String>.toActions() = mapNotNull { (btnKey, actionId) ->
        val btn = GamepadButton.values().firstOrNull { it.key == btnKey }
        val action = ControllerActions.findById(actionId)
        if (btn != null && action != null) btn to action else null
    }.toMap()

    fun evaluateText(text: String, slug: String = "", title: String = ""): ResolvedControlScheme {
        val lower = text.lowercase(Locale.ROOT)

        var flightScore = 0
        var fpsScore = 0
        var platformerScore = 0

        // 1. Flight / Space Combat evidence
        val flightKeywords = listOf(
            "flightstick", "gameport", "joystick port", "ch flightstick", "thrustmaster",
            "throttle", "rudder", "afterburner", "missile", "cockpit", "pitch", "roll", "yaw",
            "calibrate joystick", "port 201"
        )
        for (kw in flightKeywords) {
            val count = lower.split(kw).size - 1
            flightScore += count * 15
        }

        // 2. FPS / Mouse Look evidence
        val fpsKeywords = listOf(
            "mouse look", "free look", "mouselook", "strafe", "turn left", "turn right",
            "primary weapon", "shotgun", "rocket launcher", "ammo", "automap", "crosshair"
        )
        for (kw in fpsKeywords) {
            val count = lower.split(kw).size - 1
            fpsScore += count * 15
        }

        // 3. 2D Platformer / Arrow Navigation evidence
        val platformKeywords = listOf(
            "pogo", "jump", "arrow keys to walk", "walk left", "walk right", "climb",
            "duck", "ladder", "platforms", "raygun", "lives left", "score"
        )
        for (kw in platformKeywords) {
            val count = lower.split(kw).size - 1
            platformerScore += count * 15
        }

        val maxScore = maxOf(flightScore, fpsScore, platformerScore)
        if (maxScore < 20) {
            return schemeFor(GenreLayouts.byId("generic_keys")!!, 0.50f, "Generic DOS keyboard default", ControlsTier.GUESS)
        }

        val (mode, genre, summary) = when {
            flightScore >= fpsScore && flightScore >= platformerScore -> {
                Triple(StickMode.NATIVE_GAMEPORT, ResolvedGenre.FLIGHT_SIM, "Flight simulator / space combat detected")
            }
            platformerScore >= fpsScore -> {
                Triple(StickMode.CLASSIC_KEYBOARD, ResolvedGenre.PLATFORMER, "Platformer / side-scroller detected")
            }
            else -> {
                Triple(StickMode.FPS_DUAL_STICK, ResolvedGenre.FPS_ACTION, "First-person shooter / 3D action detected")
            }
        }

        val confidence = (maxScore.toFloat() / (flightScore + fpsScore + platformerScore + 20).toFloat()).coerceIn(0.60f, 0.95f)

        // Extract key bindings from text
        val bindings = extractKeyBindings(lower, genre)

        return ResolvedControlScheme(
            stickMode = mode,
            suggestedBindings = bindings,
            source = "JEV_HEURISTIC",
            confidence = confidence,
            summary = summary,
            tier = ControlsTier.GENRE
        )
    }

    private fun extractKeyBindings(
        lowerText: String,
        genre: ResolvedGenre
    ): Map<GamepadButton, ControllerAction> {
        val result = ControllerActions.defaultBindings.toMutableMap()

        when (genre) {
            ResolvedGenre.PLATFORMER -> {
                result[GamepadButton.BUTTON_A] = ControllerActions.JUMP_CTRL
                result[GamepadButton.BUTTON_B] = ControllerActions.POGO_ALT
                result[GamepadButton.BUTTON_X] = ControllerActions.FIRE_SPACE
                result[GamepadButton.BUTTON_Y] = ControllerActions.STATUS_F1
            }
            ResolvedGenre.FLIGHT_SIM -> {
                result[GamepadButton.BUTTON_A] = ControllerActions.FIRE_CTRL
                result[GamepadButton.BUTTON_B] = ControllerActions.ALT_FIRE
                result[GamepadButton.BUTTON_X] = ControllerActions.NEXT_WEAPON
                result[GamepadButton.BUTTON_Y] = ControllerActions.AUTOMAP_TAB
            }
            ResolvedGenre.FPS_ACTION -> {
                result[GamepadButton.BUTTON_A] = ControllerActions.ENTER_SPACE
                result[GamepadButton.BUTTON_B] = ControllerActions.ESC_CROUCH
                result[GamepadButton.BUTTON_X] = ControllerActions.JUMP_BUILD
                result[GamepadButton.BUTTON_Y] = ControllerActions.NEXT_WEAPON
                result[GamepadButton.BUTTON_R2] = ControllerActions.FIRE_CTRL
                result[GamepadButton.BUTTON_L2] = ControllerActions.SPRINT_SHIFT
            }
            else -> {}
        }

        return result
    }

    private fun findDocFiles(installDir: File): List<File> {
        if (!installDir.exists() || !installDir.isDirectory) return emptyList()
        val found = mutableListOf<File>()

        installDir.walkTopDown().maxDepth(3).forEach { file ->
            if (file.isFile) {
                val ext = file.extension.lowercase(Locale.ROOT)
                val base = file.nameWithoutExtension.lowercase(Locale.ROOT)
                if (ext in DOC_EXTENSIONS || DOC_NAMES.any { base.contains(it) }) {
                    found.add(file)
                }
            }
        }
        return found.sortedByDescending { it.length() }
    }
}
