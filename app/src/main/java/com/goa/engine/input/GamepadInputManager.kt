package com.goa.engine.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.goa.engine.emulator.DosBoxEngineBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for in-game controller bindings and gamepad detection. */
object GamepadMappings {
    data class Binding(val button: String, val action: String)

    val defaultBindings: List<Binding> = listOf(
        Binding("A", "Enter / Space (select, use, open doors)"),
        Binding("B", "Esc (back, menu)"),
        Binding("X", "Jump (A / /), in games that jump"),
        Binding("Y", "Next weapon / inventory"),
        Binding("LB / RB", "Previous / next weapon + inventory"),
        Binding("LT", "Run (Shift)"),
        Binding("RT", "Fire (Ctrl)"),
        Binding("L3", "Crouch toggle (Z), in games that crouch"),
        Binding("R3", "Run toggle (Shift)"),
        Binding("Start", "Esc (in-game menu)"),
        Binding("Select", "Tab (automap)"),
        Binding("Start + Select / Guide", "Pause menu"),
        Binding("D-pad", "Arrow keys (items and weapons in shooters)"),
        Binding("Left stick", "Move / strafe"),
        Binding("Right stick", "Turn / look")
    )

    val bindings: List<Binding> get() = defaultBindings

    /**
     * Inspects an InputDevice to check if it has gamepad sources or gamepad buttons.
     * Compatible with standard controllers, Honor/EMUI Bluetooth drivers, and virtual wrappers.
     */
    fun isGamepad(dev: InputDevice?): Boolean {
        if (dev == null) return false
        val sources = dev.sources

        // 1. Direct source check
        if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (sources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0
        ) {
            return true
        }

        // 2. Check for typical gamepad face buttons
        val hasFaceButtons = dev.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y
        )
        if (hasFaceButtons.any { it }) return true

        // 3. Check for bumpers/triggers or system gamepad buttons
        val hasOtherButtons = dev.hasKeys(
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2
        )
        if (hasOtherButtons.any { it }) return true

        // 4. Directional pad without alphanumeric keyboard
        if ((sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD &&
            dev.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            val dpadKeys = dev.hasKeys(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT
            )
            if (dpadKeys.all { it }) return true
        }

        return false
    }

    /**
     * Determines if at least one physical or connected game controller is available.
     */
    fun hasConnectedGamepad(): Boolean {
        val ids = InputDevice.getDeviceIds()
        return ids.any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            isGamepad(dev)
        }
    }

    /**
     * Returns the name of the first connected gamepad, if available.
     */
    fun getConnectedGamepadName(): String? {
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (isGamepad(dev)) {
                return dev.name.takeIf { it.isNotBlank() } ?: "Bluetooth Gamepad"
            }
        }
        return null
    }

    /**
     * Returns all connected input devices identified as controllers.
     */
    fun getConnectedGamepads(): List<InputDevice> {
        val ids = InputDevice.getDeviceIds()
        val list = mutableListOf<InputDevice>()
        for (id in ids) {
            val dev = InputDevice.getDevice(id)
            if (dev != null && isGamepad(dev)) {
                list.add(dev)
            }
        }
        return list
    }
}

class GamepadInputManager(
    private val context: Context,
    private val onPauseRequested: () -> Unit
) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val _isGamepadConnected = MutableStateFlow(hasConnectedGamepad())
    val isGamepadConnected: StateFlow<Boolean> = _isGamepadConnected.asStateFlow()

    private var activeGameId: Long? = null
    private var customMappings: Map<GamepadButton, ControllerAction> = emptyMap()
        set(value) { field = value; _mappings.value = GamepadButton.values().associateWith { actionFor(it) } }
    private val _mappings = MutableStateFlow<Map<GamepadButton, ControllerAction>>(emptyMap())
    /** What each button does in the current game; the touch HUD labels its shoulder buttons from it. */
    val mappings: StateFlow<Map<GamepadButton, ControllerAction>> = _mappings.asStateFlow()

    fun actionFor(button: GamepadButton): ControllerAction =
        customMappings[button] ?: ControllerActions.defaultBindings[button] ?: ControllerActions.ENTER_SPACE

    private var isStartPressed = false
    private var isSelectPressed = false
    private var isRTriggerDown = false
    private var isLTriggerDown = false
    private var isHatLeft = false
    private var isHatRight = false
    private var isHatUp = false
    private var isHatDown = false
    private var currentWeaponSlot = 2 // Default: Pistol / Shotgun slot
    /** Toggle actions currently held down, by action id. */
    private val latched = mutableMapOf<String, ControllerAction>()

    init {
        reloadMappings()
    }

    fun setGameId(gameId: Long?) {
        activeGameId = gameId
        reloadMappings()
    }

    fun reloadMappings() {
        customMappings = GamepadPreferences.loadMappings(context, activeGameId)
    }

    fun setGamepadConnectedForTesting(connected: Boolean) {
        _isGamepadConnected.value = connected
    }

    fun register() {
        inputManager.registerInputDeviceListener(this, null)
        _isGamepadConnected.value = hasConnectedGamepad()
        reloadMappings()
    }

    fun unregister() {
        inputManager.unregisterInputDeviceListener(this)
    }

    private fun hasConnectedGamepad(): Boolean = GamepadMappings.hasConnectedGamepad()

    override fun onInputDeviceAdded(deviceId: Int) {
        _isGamepadConnected.value = hasConnectedGamepad()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        _isGamepadConnected.value = hasConnectedGamepad()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        _isGamepadConnected.value = hasConnectedGamepad()
    }

    private fun cycleWeapon(forward: Boolean, slots: Int) {
        val last = slots.coerceIn(2, 10)
        val from = currentWeaponSlot.coerceIn(1, last)
        currentWeaponSlot = if (forward) {
            if (from >= last) 1 else from + 1
        } else {
            if (from <= 1) last else from - 1
        }
        // The number key alone: ']' / '[' used to go with it, which also moved Duke 3D's inventory on every switch
        val scancode = if (currentWeaponSlot == 10) 0x0B else currentWeaponSlot + 1 // '1' is 0x02 ... '9' 0x0A, '0' 0x0B
        DosBoxEngineBridge.sendKeyEvent(scancode, true)
        DosBoxEngineBridge.sendKeyEvent(scancode, false)
    }

    private fun sendKeys(action: ControllerAction, down: Boolean) {
        action.primaryScancode?.let { DosBoxEngineBridge.sendKeyEvent(it, down) }
        action.secondaryScancode?.let { DosBoxEngineBridge.sendKeyEvent(it, down) }
    }

    /** Toggle actions: the first press holds the action's keys down, the next press lets them go. */
    private fun toggleLatch(action: ControllerAction) {
        if (latched.remove(action.id) != null) {
            sendKeys(action, false)
        } else {
            latched[action.id] = action
            sendKeys(action, true)
        }
    }

    /** Lets go of every toggled key (run, crouch), e.g. when GOA goes to the background, so none stays stuck down. */
    fun releaseLatchedKeys() {
        latched.values.toList().also { latched.clear() }.forEach { sendKeys(it, false) }
    }

    private fun executeAction(action: ControllerAction, isDown: Boolean) {
        if (action.isPauseMenu) {
            if (isDown) onPauseRequested()
            return
        }
        if (action.isWeaponNext) {
            if (isDown) cycleWeapon(forward = true, slots = action.weaponSlots)
            return
        }
        if (action.isWeaponPrev) {
            if (isDown) cycleWeapon(forward = false, slots = action.weaponSlots)
            return
        }
        if (action.isLatch) {
            if (isDown) toggleLatch(action)
            return
        }
        if (action.isMouseLeft) {
            DosBoxEngineBridge.sendMouseButton(1, isDown)
            return
        }
        if (action.isMouseRight) {
            DosBoxEngineBridge.sendMouseButton(2, isDown)
            return
        }
        if (action.primaryScancode != null) {
            DosBoxEngineBridge.sendKeyEvent(action.primaryScancode, isDown)
        }
        if (action.secondaryScancode != null) {
            DosBoxEngineBridge.sendKeyEvent(action.secondaryScancode, isDown)
        }
    }

    /**
     * One pad button going down or up, from a physical controller or the touch HUD: the gameport/libretro joypad
     * button, plus the action this game's profile gives the button (keys, toggles, weapon cycling, mouse clicks).
     */
    /**
     * Native ports (TyrQuake) take the pad itself and bind each button to a game command: buttons go straight to the
     * core as RetroPad buttons, never as DOS keys. A and B are swapped so the south button reaches JOY_A, which Quake's
     * menus select with (and B, JOY_B, goes back), as on an Xbox-style pad.
     */
    @Volatile var nativePad: Boolean = false

    /**
     * A native port's own button table (OpenLara: the player's Original or Modern layout), RetroPad id per button;
     * null keeps the default below.
     */
    @Volatile var nativePadIds: Map<GamepadButton, Int>? = null

    private fun nativePadId(button: GamepadButton): Int = nativePadIds?.get(button) ?: when (button) {
        GamepadButton.BUTTON_A -> 8       // RetroPad A
        GamepadButton.BUTTON_B -> 0       // RetroPad B
        GamepadButton.BUTTON_X -> 1       // RetroPad Y (west)
        GamepadButton.BUTTON_Y -> 9       // RetroPad X (north)
        GamepadButton.BUTTON_SELECT -> 2
        GamepadButton.BUTTON_START -> 3
        GamepadButton.DPAD_UP -> 4
        GamepadButton.DPAD_DOWN -> 5
        GamepadButton.DPAD_LEFT -> 6
        GamepadButton.DPAD_RIGHT -> 7
        GamepadButton.BUTTON_L1 -> 10
        GamepadButton.BUTTON_R1 -> 11
        GamepadButton.BUTTON_L2 -> 12
        GamepadButton.BUTTON_R2 -> 13
        GamepadButton.BUTTON_L3 -> 14
        GamepadButton.BUTTON_R3 -> 15
    }

    fun pressButton(button: GamepadButton, isDown: Boolean) {
        if (nativePad) {
            DosBoxEngineBridge.sendJoypadButton(nativePadId(button), isDown)
            return
        }
        val joypadId = when (button) {
            GamepadButton.BUTTON_A -> 0   // Libretro Joypad B / Gameport Button 1
            GamepadButton.BUTTON_B -> 8   // Libretro Joypad A / Gameport Button 2
            GamepadButton.BUTTON_X -> 1   // Libretro Joypad Y / Gameport Button 3
            GamepadButton.BUTTON_Y -> 9   // Libretro Joypad X / Gameport Button 4
            GamepadButton.BUTTON_L1 -> 10 // Libretro L
            GamepadButton.BUTTON_R1 -> 11 // Libretro R
            GamepadButton.BUTTON_L2 -> 12 // Libretro L2
            GamepadButton.BUTTON_R2 -> 13 // Libretro R2
            GamepadButton.BUTTON_START -> 3 // Libretro Start
            GamepadButton.BUTTON_SELECT -> 2 // Libretro Select
            GamepadButton.DPAD_UP -> 4      // Libretro D-pad (gameport games read it as the joystick hat)
            GamepadButton.DPAD_DOWN -> 5
            GamepadButton.DPAD_LEFT -> 6
            GamepadButton.DPAD_RIGHT -> 7
            else -> null
        }
        if (joypadId != null) {
            DosBoxEngineBridge.sendJoypadButton(joypadId, isDown)
        }
        executeAction(actionFor(button), isDown)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // Track Start + Select combo for in-game pause
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_START -> isStartPressed = isDown
            KeyEvent.KEYCODE_BUTTON_SELECT -> isSelectPressed = isDown
        }

        if (isStartPressed && isSelectPressed) {
            onPauseRequested()
            return true
        }

        // Guide / Mode button also opens pause menu
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && isDown) {
            onPauseRequested()
            return true
        }

        // Check configurable button mappings
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GamepadButton.BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B -> GamepadButton.BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.BUTTON_R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButton.BUTTON_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButton.BUTTON_R3
            KeyEvent.KEYCODE_BUTTON_START -> GamepadButton.BUTTON_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadButton.BUTTON_SELECT
            else -> null
        }

        if (button != null) {
            pressButton(button, isDown)
            return true
        }

        // A controller's D-pad is bindable (items in shooters); a keyboard's arrow keys stay arrow keys
        val dpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> GamepadButton.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButton.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButton.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButton.DPAD_RIGHT
            else -> null
        }
        if (dpad != null && isControllerDpad(event)) {
            if (event.repeatCount == 0) pressButton(dpad, isDown)
            return true
        }

        // Keyboard arrows and navigation keys
        val dosScancode = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0x48        // Arrow Up
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x50      // Arrow Down
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x4B      // Arrow Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4D     // Arrow Right
            KeyEvent.KEYCODE_ESCAPE -> 0x01         // Esc
            KeyEvent.KEYCODE_ENTER -> 0x1C          // Enter
            else -> return false
        }

        DosBoxEngineBridge.sendKeyEvent(dosScancode, isDown)
        return true
    }

    /** D-pad keys from a game controller, not a keyboard's arrow keys (which also arrive as KEYCODE_DPAD_*). */
    private fun isControllerDpad(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        if (dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        return (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK || GamepadMappings.isGamepad(dev)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0
        ) {
            val deadzone = 0.15f

            // Left Thumbstick (Move Forward/Back + Strafe Left/Right)
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
            val moveX = if (kotlin.math.abs(axisX) > deadzone) axisX else 0f
            val moveY = if (kotlin.math.abs(axisY) > deadzone) axisY else 0f

            // Right Thumbstick (Turn Left/Right + Look Up/Down)
            val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
            val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
            val rawRX = event.getAxisValue(MotionEvent.AXIS_RX)
            val rawRY = event.getAxisValue(MotionEvent.AXIS_RY)
            val rightAxisX = if (kotlin.math.abs(rawZ) >= kotlin.math.abs(rawRX)) rawZ else rawRX
            val rightAxisY = if (kotlin.math.abs(rawRZ) >= kotlin.math.abs(rawRY)) rawRZ else rawRY
            val lookX = if (kotlin.math.abs(rightAxisX) > deadzone) rightAxisX else 0f
            val lookY = if (kotlin.math.abs(rightAxisY) > deadzone) rightAxisY else 0f

            // Dispatch continuous dual-analog coordinates to native FPS wrapper
            DosBoxEngineBridge.sendAnalogSticks(moveX, moveY, lookX, lookY)

            // D-Pad Hat Axes (Physical controller D-pad for menu navigation and classic control)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val wantHatLeft = hatX < -0.5f
            val wantHatRight = hatX > 0.5f
            val wantHatUp = hatY < -0.5f
            val wantHatDown = hatY > 0.5f

            if (wantHatLeft != isHatLeft) { isHatLeft = wantHatLeft; pressButton(GamepadButton.DPAD_LEFT, isHatLeft) }
            if (wantHatRight != isHatRight) { isHatRight = wantHatRight; pressButton(GamepadButton.DPAD_RIGHT, isHatRight) }
            if (wantHatUp != isHatUp) { isHatUp = wantHatUp; pressButton(GamepadButton.DPAD_UP, isHatUp) }
            if (wantHatDown != isHatDown) { isHatDown = wantHatDown; pressButton(GamepadButton.DPAD_DOWN, isHatDown) }

            // Analog Triggers (RT / LT)
            val rTrig = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
            val lTrig = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val wantRTrig = rTrig > 0.4f
            val wantLTrig = lTrig > 0.4f

            if (wantRTrig != isRTriggerDown) {
                isRTriggerDown = wantRTrig
                DosBoxEngineBridge.sendJoypadButton(13, isRTriggerDown)
                val rtAction = customMappings[GamepadButton.BUTTON_R2] ?: ControllerActions.FIRE_CTRL
                if (!nativePad) executeAction(rtAction, isRTriggerDown)
            }
            if (wantLTrig != isLTriggerDown) {
                isLTriggerDown = wantLTrig
                DosBoxEngineBridge.sendJoypadButton(12, isLTriggerDown)
                val ltAction = customMappings[GamepadButton.BUTTON_L2] ?: ControllerActions.SPRINT_SHIFT
                if (!nativePad) executeAction(ltAction, isLTriggerDown)
            }

            return true
        }
        return false
    }
}
