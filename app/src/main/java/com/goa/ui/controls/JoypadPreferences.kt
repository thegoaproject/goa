package com.goa.ui.controls

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class ControlOffset(
    val offsetX: Dp,
    val offsetY: Dp
)

data class JoypadCustomPositions(
    val dpadOffset: ControlOffset? = null,
    val buttonsOffset: ControlOffset? = null,
    val scale: Float = 1.0f
)

object JoypadPreferences {
    private const val PREFS_NAME = "goa_joypad_layout"

    fun loadPositions(context: Context, isLandscape: Boolean): JoypadCustomPositions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = if (isLandscape) "land" else "vert"

        val dpadX = prefs.getFloat("${prefix}_dpad_x", -1f)
        val dpadY = prefs.getFloat("${prefix}_dpad_y", -1f)
        val btnsX = prefs.getFloat("${prefix}_btns_x", -1f)
        val btnsY = prefs.getFloat("${prefix}_btns_y", -1f)
        val scale = prefs.getFloat("${prefix}_scale", 1.0f)

        val dpadOffset = if (dpadX >= 0f && dpadY >= 0f) ControlOffset(dpadX.dp, dpadY.dp) else null
        val btnsOffset = if (btnsX >= 0f && btnsY >= 0f) ControlOffset(btnsX.dp, btnsY.dp) else null

        return JoypadCustomPositions(
            dpadOffset = dpadOffset,
            buttonsOffset = btnsOffset,
            scale = scale.coerceIn(0.7f, 1.5f)
        )
    }

    fun savePositions(context: Context, isLandscape: Boolean, positions: JoypadCustomPositions) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = if (isLandscape) "land" else "vert"
        prefs.edit().apply {
            if (positions.dpadOffset != null) {
                putFloat("${prefix}_dpad_x", positions.dpadOffset.offsetX.value)
                putFloat("${prefix}_dpad_y", positions.dpadOffset.offsetY.value)
            } else {
                remove("${prefix}_dpad_x")
                remove("${prefix}_dpad_y")
            }
            if (positions.buttonsOffset != null) {
                putFloat("${prefix}_btns_x", positions.buttonsOffset.offsetX.value)
                putFloat("${prefix}_btns_y", positions.buttonsOffset.offsetY.value)
            } else {
                remove("${prefix}_btns_x")
                remove("${prefix}_btns_y")
            }
            putFloat("${prefix}_scale", positions.scale)
            apply()
        }
    }

    fun resetPositions(context: Context, isLandscape: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = if (isLandscape) "land" else "vert"
        prefs.edit().apply {
            remove("${prefix}_dpad_x")
            remove("${prefix}_dpad_y")
            remove("${prefix}_btns_x")
            remove("${prefix}_btns_y")
            remove("${prefix}_scale")
            apply()
        }
    }
}

