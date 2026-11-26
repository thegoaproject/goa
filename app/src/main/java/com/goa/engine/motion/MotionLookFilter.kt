package com.goa.engine.motion

import android.view.Surface
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

data class MouseDelta(val deltaX: Float, val deltaY: Float)

class MotionLookFilter(
    var sensitivityX: Float = 25.0f,
    var sensitivityY: Float = 5.0f,
    var deadzone: Float = 0.04f,
    var exponent: Float = 1.3f,
    var invertY: Boolean = false,
    /** The vertical look deadzone (see LookDeadzone): slow pitching and small tilts don't look up or down. */
    var lookDeadzoneY: Float = 0f
) {
    /**
     * Filters angular velocity in radians/second and translates into mouse movement deltas.
     * Takes display rotation into account so that device orientation does not alter coordinate mapping.
     *
     * @param radX Angular velocity along X (pitch / vertical look in portrait, roll in landscape)
     * @param radY Angular velocity along Y (roll in portrait, pitch / vertical look in landscape)
     * @param radZ Angular velocity along Z (yaw / horizontal turn in landscape)
     * @param displayRotation Surface.ROTATION_0, ROTATION_90, ROTATION_180, or ROTATION_270
     */
    fun filter(
        radX: Float,
        radY: Float,
        radZ: Float,
        displayRotation: Int = Surface.ROTATION_0
    ): MouseDelta {
        val (rawTurn, rawPitch) = when (displayRotation) {
            Surface.ROTATION_90 -> {
                // Device rotated 90 deg counter-clockwise (natural landscape for many handhelds):
                // Yaw / turning is around device X or Z; pitch / looking up-down is around device Y
                // Turning: twisting device left/right rotates around -X or Z
                val turn = -radX
                val pitch = -radY
                turn to pitch
            }
            Surface.ROTATION_270 -> {
                // Reverse landscape (180 deg from ROTATION_90)
                val turn = radX
                val pitch = radY
                turn to pitch
            }
            Surface.ROTATION_180 -> {
                val turn = radZ
                val pitch = -radX
                turn to pitch
            }
            else -> {
                // ROTATION_0 (Portrait)
                val turn = -radZ
                val pitch = radX
                turn to pitch
            }
        }

        val turnMagnitude = abs(rawTurn)
        val filteredTurn = if (turnMagnitude > deadzone) {
            val adjusted = (turnMagnitude - deadzone) / (1.0f - deadzone)
            sign(rawTurn) * adjusted.pow(exponent) * sensitivityX
        } else {
            0.0f
        }

        val pitchMagnitude = abs(rawPitch)
        val pitchDeadzone = deadzone + com.goa.engine.input.LookDeadzone.sanitize(lookDeadzoneY) *
            com.goa.engine.input.LookDeadzone.GYRO_RATE_PER_UNIT
        val filteredPitch = if (pitchMagnitude > pitchDeadzone) {
            // Past the deadzone the response carries on from zero, at the usual scale
            val adjusted = (pitchMagnitude - pitchDeadzone) / (1.0f - deadzone)
            val pitchSign = if (invertY) -sign(rawPitch) else sign(rawPitch)
            pitchSign * adjusted.pow(exponent) * sensitivityY
        } else {
            0.0f
        }

        return MouseDelta(filteredTurn, filteredPitch)
    }

    /**
     * Translates accelerometer/gravity sensor readings (in m/s^2) into a normalized continuous
     * pitch value in [-1.0, 1.0] representing virtual right stick Y deflection for vertical looking.
     *
     * @param gX Gravity/accel X in m/s^2
     * @param gY Gravity/accel Y in m/s^2
     * @param gZ Gravity/accel Z in m/s^2
     * @param neutralPitchAngle Resting baseline angle in radians (or null if uncalibrated)
     * @param displayRotation Surface.ROTATION_0, ROTATION_90, ROTATION_180, or ROTATION_270
     * @return Pair of (calculated current pitch angle in radians, normalized pitch deflection in [-1.0, 1.0])
     */
    fun computeTiltPitch(
        gX: Float,
        gY: Float,
        gZ: Float,
        neutralPitchAngle: Float?,
        displayRotation: Int = Surface.ROTATION_0
    ): Pair<Float, Float> {
        val (gDown, gIn) = when (displayRotation) {
            Surface.ROTATION_90 -> gX to -gZ
            Surface.ROTATION_270 -> -gX to -gZ
            Surface.ROTATION_180 -> -gY to -gZ
            else -> gY to -gZ // ROTATION_0
        }

        val tiltAngle = kotlin.math.atan2(gDown, gIn)
        if (neutralPitchAngle == null) {
            return tiltAngle to 0.0f
        }

        val diff = tiltAngle - neutralPitchAngle

        // Scale deadzone and response range inversely with sensitivityY:
        // At default minimum sensitivity (e.g. 5.0f), requires a generous deadzone (~6.5 deg) and deliberate tilt
        // At higher sensitivity (e.g. 25.0f), deadzone narrows (~3 deg) and responds to subtle wrist motion
        val sensRatio = (sensitivityY / 20.0f).coerceIn(0.15f, 2.0f)
        val tiltDeadzone = 0.05f + 0.07f * (1.0f - (sensitivityY / 25.0f).coerceIn(0.0f, 1.0f))
        val maxDeflectionRange = 0.25f / sensRatio

        val absDiff = abs(diff)
        if (absDiff <= tiltDeadzone) {
            return tiltAngle to 0.0f
        }

        val clampedExcess = (absDiff - tiltDeadzone).coerceAtMost(maxDeflectionRange) / maxDeflectionRange
        val minThreshold = (0.20f * (sensitivityY / 10.0f).coerceIn(0.5f, 1.0f))
        val intensity = minThreshold + (1.0f - minThreshold) * clampedExcess.pow(1.2f)
        val rawPitch = sign(diff) * intensity
        val finalPitch = if (invertY) -rawPitch else rawPitch

        return tiltAngle to com.goa.engine.input.LookDeadzone.apply(finalPitch.coerceIn(-1.0f, 1.0f), lookDeadzoneY)
    }
}
