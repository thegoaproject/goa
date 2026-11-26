package com.goa.ui.navigation

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class LauncherGamepadAction {
    object NavigateUp : LauncherGamepadAction()
    object NavigateDown : LauncherGamepadAction()
    object NavigateLeft : LauncherGamepadAction()
    object NavigateRight : LauncherGamepadAction()
    object Select : LauncherGamepadAction()       // Button A / Enter
    object Back : LauncherGamepadAction()         // Button B / Escape
    object PlayOrAction : LauncherGamepadAction() // Button X / Play
    object Menu : LauncherGamepadAction()         // Button Y / Start / Menu
}

class LauncherGamepadController {
    private val _actions = MutableSharedFlow<LauncherGamepadAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<LauncherGamepadAction> = _actions.asSharedFlow()

    private var lastDpadRepeatTime = 0L
    private var lastAxisY = 0f
    private var lastAxisX = 0f

    /**
     * On while the ☰ menu or one of its pages is showing. Navigation and select then go to Compose's own focus
     * handling (MainActivity turns A into DPAD_CENTER and the stick into D-pad presses); only Back and Menu are still
     * emitted, so the menu and pages can close and the menu can be toggled.
     */
    @Volatile
    var uiFocusMode: Boolean = false

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (uiFocusMode) return handleUiFocusKey(event)
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> LauncherGamepadAction.NavigateUp
            KeyEvent.KEYCODE_DPAD_DOWN -> LauncherGamepadAction.NavigateDown
            KeyEvent.KEYCODE_DPAD_LEFT -> LauncherGamepadAction.NavigateLeft
            KeyEvent.KEYCODE_DPAD_RIGHT -> LauncherGamepadAction.NavigateRight
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                LauncherGamepadAction.Select
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE ->
                LauncherGamepadAction.Back
            KeyEvent.KEYCODE_BUTTON_X -> LauncherGamepadAction.PlayOrAction
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU ->
                LauncherGamepadAction.Menu
            else -> null
        }

        return if (action != null) {
            _actions.tryEmit(action)
            true
        } else {
            false
        }
    }

    private fun handleUiFocusKey(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> LauncherGamepadAction.Back
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> LauncherGamepadAction.Menu
            else -> return false // D-pad, A, Enter, X: let the focused control have them
        }
        // Consume the release too, so the system never sees half a Back press
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) _actions.tryEmit(action)
        return true
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (uiFocusMode) return false
        val isJoystickOrGamepad = (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK ||
            (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
            (event.source and android.view.InputDevice.SOURCE_CLASS_JOYSTICK) != 0

        if (!isJoystickOrGamepad) {
            return false
        }

        val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
        val axisX = event.getAxisValue(MotionEvent.AXIS_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)

        val effY = if (kotlin.math.abs(hatY) > 0.5f) hatY else axisY
        val effX = if (kotlin.math.abs(hatX) > 0.5f) hatX else axisX

        val now = System.currentTimeMillis()
        val threshold = 0.5f

        // Handle Y axis (Up/Down) with debouncing
        if (effY < -threshold && (lastAxisY >= -threshold || now - lastDpadRepeatTime > 220)) {
            lastDpadRepeatTime = now
            lastAxisY = effY
            _actions.tryEmit(LauncherGamepadAction.NavigateUp)
            return true
        } else if (effY > threshold && (lastAxisY <= threshold || now - lastDpadRepeatTime > 220)) {
            lastDpadRepeatTime = now
            lastAxisY = effY
            _actions.tryEmit(LauncherGamepadAction.NavigateDown)
            return true
        } else if (kotlin.math.abs(effY) <= threshold) {
            lastAxisY = 0f
        }

        // Handle X axis (Left/Right) with debouncing
        if (effX < -threshold && (lastAxisX >= -threshold || now - lastDpadRepeatTime > 250)) {
            lastDpadRepeatTime = now
            lastAxisX = effX
            _actions.tryEmit(LauncherGamepadAction.NavigateLeft)
            return true
        } else if (effX > threshold && (lastAxisX <= threshold || now - lastDpadRepeatTime > 250)) {
            lastDpadRepeatTime = now
            lastAxisX = effX
            _actions.tryEmit(LauncherGamepadAction.NavigateRight)
            return true
        } else if (kotlin.math.abs(effX) <= threshold) {
            lastAxisX = 0f
        }

        return false
    }
}

