package com.goa.engine.input

import android.content.Context
import kotlin.math.abs
import kotlin.math.sign

/**
 * The vertical look deadzone: how far the right stick (or the gyro) has to go up or down before the view starts looking
 * up or down. Old shooters rarely need it, and a view that tips whenever the thumb drifts while turning is annoying, so
 * the up/down channel gets a deadzone of its own, set under Controls and in the in-game menu. Turning is unaffected.
 */
object LookDeadzone {
    /** In goa_ui_state, beside the other look settings: 0 (off) to [MAX], a share of the stick's travel. */
    const val PREF = "look_deadzone_y"
    const val DEFAULT = 0.15f
    const val MAX = 0.6f

    /** The gyro's pitch rate (rad/s) ignored at a deadzone of 1.0: at the most (0.6) about 34 degrees a second. */
    const val GYRO_RATE_PER_UNIT = 1.0f

    fun load(context: Context): Float =
        context.getSharedPreferences("goa_ui_state", Context.MODE_PRIVATE).getFloat(PREF, DEFAULT).let(::sanitize)

    fun save(context: Context, deadzone: Float) =
        context.getSharedPreferences("goa_ui_state", Context.MODE_PRIVATE).edit().putFloat(PREF, sanitize(deadzone)).apply()

    fun sanitize(deadzone: Float): Float = if (deadzone.isNaN()) DEFAULT else deadzone.coerceIn(0f, MAX)

    /** [value] (-1..1) with the inner [deadzone] removed and the rest stretched back to the full range. */
    fun apply(value: Float, deadzone: Float): Float {
        val d = sanitize(deadzone)
        val a = abs(value)
        return if (a <= d) 0f else sign(value) * ((a - d) / (1f - d)).coerceAtMost(1f)
    }
}
