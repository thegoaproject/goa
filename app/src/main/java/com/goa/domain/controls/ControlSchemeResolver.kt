package com.goa.domain.controls

import android.content.Context
import com.goa.data.repository.GamePatchProfile
import com.goa.data.repository.GamePatchRepository
import com.goa.engine.input.ControllerAction
import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.GamepadPreferences
import com.goa.engine.input.StickMode
import java.io.File

enum class ResolvedGenre {
    PLATFORMER,
    FPS_ACTION,
    FLIGHT_SIM,
    RPG_ADVENTURE,
    GENERIC
}

data class ResolvedControlScheme(
    val stickMode: StickMode,
    val suggestedBindings: Map<GamepadButton, ControllerAction> = emptyMap(),
    val source: String, // "PATCH_REGISTRY", "JEV_HEURISTIC", "SLM_AI", "GLOBAL_DEFAULT"
    val confidence: Float = 1.0f,
    val summary: String = "",
    val tier: ControlsTier = ControlsTier.GUESS
)

/**
 * Intelligent Multi-Tier Controller Scheme Resolver:
 * - Tier 1: Curated Patch Registry (Pre-compiled in game_patches.json)
 * - Tier 2: JEV Heuristic Decision Engine (fast on-device regex / keyword analysis)
 * - Tier 3: On-Device SLM (Small Language Model / MediaPipe / LiteRT background worker)
 */
/** Profiles from patches/published_defaults.json use this id prefix (contracts/published-defaults.md). */
const val PUBLISHED_PROFILE_PREFIX = "published-"

/** [ResolvedControlScheme.source] when a fallback was not applied because the game already has a better layout. */
const val KEPT_SOURCE = "KEPT"

class ControlSchemeResolver(
    private val context: Context,
    private val patchRepository: GamePatchRepository = GamePatchRepository(context)
) {
    /** What the resolver would pick for a game, and the registry profile behind it (null for engine/genre/guess fallbacks). */
    private class Decision(val scheme: ResolvedControlScheme, val profile: GamePatchProfile?)

    /**
     * What a re-scan would choose, WITHOUT applying it: lets the UI show it beside the player's own mappings and let them
     * decide. Ignores the player-customised flag and the no-downgrade rule; applying is the caller's explicit choice.
     */
    fun previewScheme(gameId: Long, slug: String?, title: String?, installDir: File): ResolvedControlScheme =
        decide(gameId, slug, title, installDir).scheme

    /**
     * Resolves the optimal control scheme for a game given its metadata and extracted install directory.
     * Does NOT overwrite user-modified controls.
     */
    fun resolveAndApplyScheme(
        gameId: Long,
        slug: String?,
        title: String?,
        installDir: File,
        overwriteUser: Boolean = false
    ): ResolvedControlScheme {
        val profiles = patchRepository.findMatchingProfiles(gameId, slug, installDir)
        // Not a button: a player's own layout keeps the game's look-spring
        GamepadPreferences.setLookRecenterKey(
            context, gameId, profiles.firstNotNullOfOrNull { it.controlOverrides?.lookRecenterKey }
        )
        GamepadPreferences.setMouseLookKey(
            context, gameId, profiles.firstNotNullOfOrNull { it.controlOverrides?.mouseLookKey }
        )
        if (!overwriteUser && GamepadPreferences.hasUserCustomizedControls(context, gameId)) {
            return ResolvedControlScheme(
                stickMode = GamepadPreferences.getStickMode(context, gameId),
                suggestedBindings = GamepadPreferences.loadMappings(context, gameId),
                source = "USER_CUSTOMIZED",
                confidence = 1.0f,
                summary = "User configured controls",
                tier = ControlsTier.CUSTOM
            )
        }

        val decision = decide(gameId, slug, title, installDir, profiles)
        val profile = decision.profile ?: return applyOrKeep(gameId, decision.scheme)

        // A registry profile is the current truth: it replaces whatever was stored
        val previousMode = GamepadPreferences.getStickMode(context, gameId).id
        GamepadPreferences.applyControlOverrides(
            context, gameId, profile.controlOverrides!!, overwriteUser = true,
            sourceTag = ControlsTier.tag(decision.scheme.tier, profile.name)
        )
        val mode = GamepadPreferences.getStickMode(context, gameId)
        if (profile.id.startsWith(PUBLISHED_PROFILE_PREFIX)) {
            // A community default reached this game (recorded once; unchanged values are ignored)
            com.goa.telemetry.ConfigChangeRecorder.record(
                context, gameId, com.goa.data.db.TrackedSetting.STICK_MODE, previousMode, mode.id,
                com.goa.data.db.ChangeOrigin.PUBLISHED_DEFAULT
            )
        }
        return decision.scheme.copy(stickMode = mode, suggestedBindings = GamepadPreferences.loadMappings(context, gameId))
    }

    private fun decide(
        gameId: Long, slug: String?, title: String?, installDir: File,
        profiles: List<GamePatchProfile> = patchRepository.findMatchingProfiles(gameId, slug, installDir)
    ): Decision {
        // The engine the install is built on (Build, Doom, SCI/AGI/SCUMM...): hard evidence from its files
        val engine = EngineLayouts.detect(installDir)

        // --- Tier 1: Registry profile (curated, or harvested from a manual / a guess) ---
        val profile = profiles.firstOrNull { it.controlOverrides != null }
        if (profile != null) {
            val tier = ControlsTier.ofProfile(profile.evidence, profile.confidence, profile.id)
            // A genre fallback or a plain guess never beats what the install's own files say about its engine
            if (engine != null && (tier == ControlsTier.GUESS || tier == ControlsTier.GENRE)) return Decision(engineScheme(engine), null)
            return Decision(profileScheme(profile, tier), profile)
        }

        // --- Engine fallback: no profile, but the install is a known engine ---
        if (engine != null) return Decision(engineScheme(engine), null)

        // --- Tier 2: JEV Heuristic Decision Engine ---
        val jevEngine = JevControlDecisionEngine()
        val jevResult = jevEngine.evaluateDocumentation(installDir, slug, title)
        if (jevResult != null && jevResult.confidence >= 0.70f) return Decision(jevResult, null)

        // --- Tier 3 Fallback / Default ---
        // Weak or no documentation evidence: a keyboard or point-and-click default, never the shooter scheme
        val fallback = if (jevResult != null && jevResult.confidence > 0.5f) jevResult
        else jevEngine.fallbackScheme(installDir, slug, title)
        return Decision(fallback, null)
    }

    /** A profile's controls as a full scheme: the buttons it names, the generic defaults for the rest. */
    private fun profileScheme(profile: GamePatchProfile, tier: ControlsTier): ResolvedControlScheme {
        val overrides = profile.controlOverrides!!
        val bindings = ControllerActions.defaultBindings.toMutableMap()
        overrides.buttonBindings?.forEach { (btnKey, actionId) ->
            val btn = com.goa.engine.input.GamepadButton.values().firstOrNull { it.key.equals(btnKey, true) || it.name.equals(btnKey, true) }
            val action = ControllerActions.findById(actionId)
            if (btn != null && action != null) bindings[btn] = action
        }
        val mode = StickMode.values().firstOrNull { it.id == overrides.stickMode } ?: StickMode.FPS_DUAL_STICK
        return ResolvedControlScheme(mode, bindings, "PATCH_REGISTRY", 1.0f, profile.description, tier)
    }

    private fun engineScheme(engine: EngineLayouts.Match): ResolvedControlScheme {
        val bindings = engine.bindings.mapNotNull { (btnKey, actionId) ->
            val btn = com.goa.engine.input.GamepadButton.values().firstOrNull { it.key == btnKey }
            val action = ControllerActions.findById(actionId)
            if (btn != null && action != null) btn to action else null
        }.toMap()
        return ResolvedControlScheme(engine.stickMode, bindings, "ENGINE", 0.85f, engine.summary, ControlsTier.ENGINE)
    }

    /**
     * Applies a fallback (engine, genre or guess) unless the game already has a more trustworthy layout, such as an exact
     * match applied earlier while the registry was available. A registry profile found now still replaces it (it is the
     * current truth); only the fallbacks are held back, so being offline or unsynced can never downgrade a game.
     */
    private fun applyOrKeep(gameId: Long, scheme: ResolvedControlScheme): ResolvedControlScheme {
        val stored = ControlsTier.parseTag(GamepadPreferences.getSourceTag(context, gameId))?.first
        if (ControlsTier.isDowngrade(stored, scheme.tier)) {
            return ResolvedControlScheme(
                stickMode = GamepadPreferences.getStickMode(context, gameId),
                suggestedBindings = GamepadPreferences.loadMappings(context, gameId),
                source = KEPT_SOURCE,
                confidence = 1.0f,
                summary = "Kept the current ${stored!!.label.lowercase()}; the re-scan only found a ${scheme.tier.label.lowercase()}",
                tier = stored
            )
        }
        applyResolvedScheme(gameId, scheme)
        return scheme
    }

    private fun applyResolvedScheme(gameId: Long, scheme: ResolvedControlScheme) {
        GamepadPreferences.setStickMode(context, gameId, scheme.stickMode)
        for ((btn, action) in scheme.suggestedBindings) {
            GamepadPreferences.saveMapping(context, gameId, btn, action)
        }
        GamepadPreferences.setSourceTag(context, gameId, ControlsTier.tag(scheme.tier, scheme.summary))
    }
}

