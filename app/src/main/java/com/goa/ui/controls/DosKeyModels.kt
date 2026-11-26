package com.goa.ui.controls

/**
 * Structural posture and form factor of the device.
 */
enum class DevicePosture {
    /**
     * Unfolded foldable device (e.g. Galaxy Z Fold 8) or square tablet.
     * Aspect ratio < 1.30. Enforces 50/50 vertical split.
     */
    FOLDABLE_DUAL_PANE,

    /**
     * Standard phone held vertically (Height > Width, aspect ratio >= 1.30).
     * Top: 4:3 game. Bottom: controls.
     */
    PHONE_PORTRAIT,

    /**
     * Standard phone held horizontally (Width > Height, aspect ratio >= 1.30).
     * Centered 4:3 game flanked by pillarbox controls.
     */
    PHONE_LANDSCAPE
}

/**
 * Active input UI surface presented to the user.
 */
enum class InputSurfaceMode {
    /** Touch D-pad and action buttons. */
    JOYPAD,

    /** Full PC on-screen keyboard. */
    PC_KEYBOARD,

    /** Android system IME soft keyboard. */
    ANDROID_IME,

    /** Virtual touchpad and mouse controls for SCUMM and point-and-click games. */
    SCUMM_MOUSE,

    /** Minimal floating keyboard summoner for connected physical gamepad. */
    CONTROLLER_MINIMAL
}

/**
 * Tri-state latch machine for DOS modifiers (Ctrl, Alt, Shift).
 */
enum class ModifierLatchState {
    OFF,
    ONCE,
    LOCKED;

    fun next(): ModifierLatchState = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }
}

/**
 * Semantic category of an on-screen PC key.
 */
enum class KeyCategory {
    FUNCTION,
    ALPHANUMERIC,
    PUNCTUATION,
    MODIFIER,
    NAVIGATION,
    ACTION
}

/**
 * Descriptor for a PC key cap.
 */
data class PcKeyDescriptor(
    val label: String,
    val scancode: Int,
    val shiftedLabel: String? = null,
    val weight: Float = 1.0f,
    val category: KeyCategory = KeyCategory.ALPHANUMERIC
)

