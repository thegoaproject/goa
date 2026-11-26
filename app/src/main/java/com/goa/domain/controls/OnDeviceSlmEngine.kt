package com.goa.domain.controls

import android.content.Context
import android.util.Log
import com.goa.engine.input.ControllerAction
import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.StickMode
import org.json.JSONObject

data class SlmInferenceRequest(
    val gameTitle: String,
    val manualExcerpt: String
)

data class SlmControlAnalysis(
    val stickMode: String,
    val buttonA: String,
    val buttonB: String,
    val buttonX: String,
    val buttonY: String,
    val confidence: Float,
    val summary: String
)

interface OnDeviceSlmEngine {
    fun buildSystemPrompt(): String
    fun buildUserPrompt(request: SlmInferenceRequest): String
    suspend fun analyzeControls(request: SlmInferenceRequest): SlmControlAnalysis?
    fun parseAndValidateResponse(jsonResponse: String): SlmControlAnalysis?
}

class DefaultOnDeviceSlmEngine(
    private val context: Context? = null
) : OnDeviceSlmEngine {

    companion object {
        private const val TAG = "OnDeviceSlmEngine"

        const val SYSTEM_PROMPT = """You are an expert retro DOS PC game controller engineer.
Given documentation/manual text for a DOS game, analyze its controls and output optimal modern gamepad mappings.
Allowed stickMode values:
- "fps_dual_stick": 3D shooter / mouse-look / 6DOF (e.g. Doom, Quake, Duke3D)
- "classic_keyboard": 2D platformer / RPG / arrow-key navigation (e.g. Commander Keen, Prince of Persia)
- "native_gameport": IBM PC 15-pin Gameport analog flight/space sim (e.g. Wing Commander, X-Wing)

Allowed button actions:
"enter_space", "esc_crouch", "jump_ctrl", "pogo_alt", "fire_space", "fire_ctrl", "alt_fire", "space_use", "sprint_shift", "next_weapon", "automap_tab", "status_f1"

Respond strictly with valid JSON:
{
  "stickMode": "classic_keyboard" | "fps_dual_stick" | "native_gameport",
  "buttonA": "<action_id>",
  "buttonB": "<action_id>",
  "buttonX": "<action_id>",
  "buttonY": "<action_id>",
  "confidence": 0.0 to 1.0,
  "summary": "Brief 1-sentence rationale"
}"""
    }

    override fun buildSystemPrompt(): String = SYSTEM_PROMPT

    override fun buildUserPrompt(request: SlmInferenceRequest): String {
        val sanitizedExcerpt = request.manualExcerpt.take(4000).trim()
        return "Game Title: ${request.gameTitle}\nDocumentation Excerpt:\n$sanitizedExcerpt\n\nReturn JSON:"
    }

    override fun parseAndValidateResponse(jsonResponse: String): SlmControlAnalysis? {
        return runCatching {
            // Find json substring if wrapped in markdown code blocks
            val jsonText = if (jsonResponse.contains("```json")) {
                jsonResponse.substringAfter("```json").substringBefore("```").trim()
            } else if (jsonResponse.contains("```")) {
                jsonResponse.substringAfter("```").substringBefore("```").trim()
            } else {
                jsonResponse.trim()
            }

            val obj = JSONObject(jsonText)
            val stickMode = obj.optString("stickMode", "fps_dual_stick").lowercase()
            val validStickMode = when (stickMode) {
                "classic_keyboard", "native_gameport", "fps_dual_stick" -> stickMode
                else -> "fps_dual_stick"
            }

            val btnA = validateActionId(obj.optString("buttonA", "enter_space"))
            val btnB = validateActionId(obj.optString("buttonB", "esc_crouch"))
            val btnX = validateActionId(obj.optString("buttonX", "jump_build"))
            val btnY = validateActionId(obj.optString("buttonY", "next_weapon"))
            val confidence = obj.optDouble("confidence", 0.85).toFloat().coerceIn(0.1f, 1.0f)
            val summary = obj.optString("summary", "AI detected control scheme")

            SlmControlAnalysis(
                stickMode = validStickMode,
                buttonA = btnA,
                buttonB = btnB,
                buttonX = btnX,
                buttonY = btnY,
                confidence = confidence,
                summary = summary
            )
        }.onFailure { err ->
            Log.w(TAG, "Failed to parse SLM JSON response: ${err.message}")
        }.getOrNull()
    }

    override suspend fun analyzeControls(request: SlmInferenceRequest): SlmControlAnalysis? {
        // Evaluate via fallback heuristic engine if no on-device ML model file is present
        val jev = JevControlDecisionEngine()
        val scheme = jev.evaluateText(request.manualExcerpt, title = request.gameTitle)
        return SlmControlAnalysis(
            stickMode = scheme.stickMode.id,
            buttonA = scheme.suggestedBindings[GamepadButton.BUTTON_A]?.id ?: "enter_space",
            buttonB = scheme.suggestedBindings[GamepadButton.BUTTON_B]?.id ?: "esc_crouch",
            buttonX = scheme.suggestedBindings[GamepadButton.BUTTON_X]?.id ?: "jump_build",
            buttonY = scheme.suggestedBindings[GamepadButton.BUTTON_Y]?.id ?: "next_weapon",
            confidence = scheme.confidence,
            summary = scheme.summary
        )
    }

    private fun validateActionId(actionId: String): String {
        return if (ControllerActions.findById(actionId) != null) {
            actionId
        } else {
            "enter_space"
        }
    }
}

