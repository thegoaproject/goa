package com.goa.ui.emulation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goa.ui.controls.DevicePosture

data class ViewportMetrics(
    val posture: DevicePosture,
    val gameWidthDp: Dp,
    val gameHeightDp: Dp,
    val controlsHeightDp: Dp = 0.dp,
    val pillarboxMarginDp: Dp = 0.dp
)

object EmulationViewportLayout {

    /**
     * Determines whether the current window geometry represents a foldable/square display,
     * a portrait phone, or a landscape phone.
     */
    fun detectPosture(widthDp: Dp, heightDp: Dp): DevicePosture {
        val maxDim = maxOf(widthDp.value, heightDp.value)
        val minDim = minOf(widthDp.value, heightDp.value)
        if (minDim <= 0f) return DevicePosture.PHONE_PORTRAIT
        val ratio = maxDim / minDim
        // All modern phones have aspect ratio >= 1.70 (16:9, 19.5:9, 20:9).
        // Foldables and tablets have aspect ratio < 1.55 (e.g. Fold 8 unfolded 2448x1848 is ~1.325).
        return when {
            ratio < 1.55f -> DevicePosture.FOLDABLE_DUAL_PANE
            heightDp > widthDp -> DevicePosture.PHONE_PORTRAIT
            else -> DevicePosture.PHONE_LANDSCAPE
        }
    }

    /**
     * Computes exact partition metrics for game viewport and control surfaces.
     */
    fun calculateMetrics(
        screenWidthDp: Dp,
        screenHeightDp: Dp,
        isGamepadConnected: Boolean,
        isKeyboardActive: Boolean = false,
        isControlsHidden: Boolean = false
    ): ViewportMetrics {
        val posture = detectPosture(screenWidthDp, screenHeightDp)

        if ((isGamepadConnected && !isKeyboardActive) || isControlsHidden) {
            // Fullscreen maximum 4:3 dimensions
            val screenRatio = screenWidthDp.value / screenHeightDp.value.coerceAtLeast(1f)
            val targetRatio = 4f / 3f
            val (gWidth, gHeight) = if (screenRatio > targetRatio) {
                (screenHeightDp.value * targetRatio).dp to screenHeightDp
            } else {
                screenWidthDp to (screenWidthDp.value / targetRatio).dp
            }
            return ViewportMetrics(
                posture = posture,
                gameWidthDp = gWidth,
                gameHeightDp = gHeight,
                controlsHeightDp = 0.dp,
                pillarboxMarginDp = maxOf(0.dp, (screenWidthDp - gWidth) / 2)
            )
        }

        return when (posture) {
            DevicePosture.FOLDABLE_DUAL_PANE -> {
                // Top 50% for 4:3 game, bottom 50% for controls
                val halfHeight = screenHeightDp * 0.50f
                val maxGameWidth = (halfHeight.value * (4f / 3f)).dp
                val (gWidth, gHeight) = if (maxGameWidth <= screenWidthDp) {
                    maxGameWidth to halfHeight
                } else {
                    screenWidthDp to (screenWidthDp.value * (3f / 4f)).dp
                }
                ViewportMetrics(
                    posture = posture,
                    gameWidthDp = gWidth,
                    gameHeightDp = gHeight,
                    controlsHeightDp = halfHeight,
                    pillarboxMarginDp = maxOf(0.dp, (screenWidthDp - gWidth) / 2)
                )
            }
            DevicePosture.PHONE_PORTRAIT -> {
                // Top portion 4:3 game, bottom portion controls
                val gHeight = (screenWidthDp.value * (3f / 4f)).dp
                val cHeight = maxOf(0.dp, screenHeightDp - gHeight)
                ViewportMetrics(
                    posture = posture,
                    gameWidthDp = screenWidthDp,
                    gameHeightDp = gHeight,
                    controlsHeightDp = cHeight,
                    pillarboxMarginDp = 0.dp
                )
            }
            DevicePosture.PHONE_LANDSCAPE -> {
                // Full vertical height 4:3 centered game, left/right pillarboxes for controls
                val gWidth = (screenHeightDp.value * (4f / 3f)).dp
                val margin = maxOf(0.dp, (screenWidthDp - gWidth) / 2)
                ViewportMetrics(
                    posture = posture,
                    gameWidthDp = gWidth,
                    gameHeightDp = screenHeightDp,
                    controlsHeightDp = 0.dp,
                    pillarboxMarginDp = margin
                )
            }
        }
    }
}
