package com.goa.engine.input

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.goa.engine.emulator.DosBoxEngineBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * High-precision input manager handling SCUMM point-and-click interactions,
 * hardware USB/Bluetooth mouse, and active/EMR styluses (Honor Pen, Wacom EMR, S-Pen).
 */
private const val TAP_PRESS_MS = 90L

class PointAndClickInputManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    // Configurable sensitivities
    var mouseSensitivity: Float = 1.0f
    var touchSensitivity: Float = 1.0f
    var isPointerCaptureEnabled: Boolean = true

    // Finger/stylus taps place the DOS cursor where the screen is touched (direct mouse mode)
    var tapToPointEnabled: Boolean = true

    // One-shot: the next finger tap sends a right click instead of a left click
    private val _rightClickNextTap = MutableStateFlow(false)
    val rightClickNextTap: StateFlow<Boolean> = _rightClickNextTap.asStateFlow()

    fun setRightClickNextTap(armed: Boolean) {
        _rightClickNextTap.value = armed
    }

    private var attachedView: View? = null

    // Hardware Mouse State
    private var lastButtonState = 0
    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private var hasLastMousePos = false

    // Stylus State (Wacom EMR, Active Honor Pen, S-Pen)
    private var lastStylusX = 0f
    private var lastStylusY = 0f
    private var hasLastStylusPos = false
    private var activeStylusButton = 0
    private var lastStylusTimestamp = 0L

    // Touchscreen / SCUMM Finger State
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var isLongPressTriggered = false
    private var isTwoFingerRightClicking = false
    private var longPressJob: Job? = null

    /**
     * Attaches input listeners to the emulation SurfaceView.
     * Hardware mouse events arrive via [handleHardwareMouseEvent] from the Activity (so they work over
     * letterbox overlays too) and, once captured, via this view's captured-pointer listener.
     */
    fun attachToView(view: View) {
        attachedView = view
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        view.setOnTouchListener { v, event ->
            // Mouse events were already handled at Activity level; consume without double-processing
            if (isMouseEvent(event)) true else handleTouchEvent(event, v.width.toFloat(), v.height.toFloat(), v)
        }

        view.setOnGenericMotionListener { v, event ->
            if (isMouseEvent(event)) true else handleGenericMotionEvent(event, v.width.toFloat(), v.height.toFloat(), v)
        }

        view.setOnCapturedPointerListener { v, event ->
            processHardwareMouseEvent(event, v)
        }
    }

    /**
     * Activity-level entry point for USB/Bluetooth mouse events. Returns false for non-mouse events.
     */
    fun handleHardwareMouseEvent(event: MotionEvent): Boolean {
        if (!isMouseEvent(event)) return false
        return processHardwareMouseEvent(event, attachedView)
    }

    private fun isMouseEvent(event: MotionEvent): Boolean =
        event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || event.isFromSource(InputDevice.SOURCE_MOUSE)

    /**
     * Handles primary touch events (finger touches, stylus tip contact, and mouse button presses).
     */
    fun handleTouchEvent(event: MotionEvent, surfaceWidth: Float, surfaceHeight: Float, view: View? = null): Boolean {
        val toolType = event.getToolType(0)
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE || event.isFromSource(InputDevice.SOURCE_MOUSE)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            event.isFromSource(InputDevice.SOURCE_STYLUS)

        if (isMouse) {
            return processHardwareMouseEvent(event, view)
        }

        if (isStylus) {
            return processStylusEvent(event, surfaceWidth, surfaceHeight)
        }

        // Finger touch (SCUMM point & click handling)
        return processFingerTouchEvent(event, surfaceWidth, surfaceHeight)
    }

    /**
     * Handles generic motion events (hardware mouse hover, scroll wheel, stylus in-air hover).
     */
    fun handleGenericMotionEvent(event: MotionEvent, surfaceWidth: Float, surfaceHeight: Float, view: View? = null): Boolean {
        val toolType = event.getToolType(0)
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE || event.isFromSource(InputDevice.SOURCE_MOUSE)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            event.isFromSource(InputDevice.SOURCE_STYLUS)

        if (isMouse) {
            return processHardwareMouseEvent(event, view)
        }

        if (isStylus) {
            return processStylusEvent(event, surfaceWidth, surfaceHeight)
        }

        return false
    }

    /**
     * Processes hardware USB and Bluetooth mouse events.
     */
    private fun processHardwareMouseEvent(event: MotionEvent, view: View?): Boolean {
        // A real mouse drives the DOS cursor with relative motion
        DosBoxEngineBridge.setDirectMouse(false)

        // Request pointer capture on first interaction. Captured events are delivered to the focused
        // view, so the emulation surface takes focus and receives them via its captured-pointer listener.
        if (isPointerCaptureEnabled && view != null && !view.hasPointerCapture()) {
            view.requestFocus()
            view.requestPointerCapture()
        }

        // Scroll wheel handling
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll > 0) {
                DosBoxEngineBridge.sendMouseWheel(1)
            } else if (vScroll < 0) {
                DosBoxEngineBridge.sendMouseWheel(-1)
            }
            return true
        }

        // Movement / Hover handling
        val dx: Float
        val dy: Float
        if (view?.hasPointerCapture() == true) {
            dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * mouseSensitivity
            dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * mouseSensitivity
        } else {
            if (!hasLastMousePos) {
                lastMouseX = event.x
                lastMouseY = event.y
                hasLastMousePos = true
            }
            dx = (event.x - lastMouseX) * mouseSensitivity
            dy = (event.y - lastMouseY) * mouseSensitivity
            lastMouseX = event.x
            lastMouseY = event.y
        }

        if (dx != 0f || dy != 0f) {
            DosBoxEngineBridge.sendRelativeMouse(dx, dy)
        }

        // Button state transitions
        val currentButtonState = event.buttonState
        val changedButtons = currentButtonState xor lastButtonState
        if (changedButtons != 0) {
            // Left Button (Primary)
            if (changedButtons and MotionEvent.BUTTON_PRIMARY != 0) {
                val isDown = (currentButtonState and MotionEvent.BUTTON_PRIMARY) != 0
                DosBoxEngineBridge.sendMouseButton(1, isDown)
            }
            // Right Button (Secondary)
            if (changedButtons and MotionEvent.BUTTON_SECONDARY != 0) {
                val isDown = (currentButtonState and MotionEvent.BUTTON_SECONDARY) != 0
                DosBoxEngineBridge.sendMouseButton(2, isDown)
            }
            // Middle Button (Tertiary)
            if (changedButtons and MotionEvent.BUTTON_TERTIARY != 0) {
                val isDown = (currentButtonState and MotionEvent.BUTTON_TERTIARY) != 0
                DosBoxEngineBridge.sendMouseButton(4, isDown)
            }
            lastButtonState = currentButtonState
        }

        return true
    }

    /**
     * Processes Wacom EMR, Honor Pen (V Fold), and active styluses with in-air hover,
     * pressure/tip contact, and barrel button right-click.
     */
    private fun processStylusEvent(event: MotionEvent, surfaceWidth: Float, surfaceHeight: Float): Boolean {
        lastStylusTimestamp = System.currentTimeMillis()
        DosBoxEngineBridge.setDirectMouse(tapToPointEnabled)
        val toolType = event.getToolType(0)
        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER

        // Barrel buttons (Honor Magic Pen barrel button, S-Pen button, Wacom side rocker)
        val isBarrelPressed = (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0)

        // Compute normalized pointer position (-1.0 to 1.0 for Libretro pointer device)
        val normX = if (surfaceWidth > 0) (event.x / surfaceWidth) * 2f - 1f else 0f
        val normY = if (surfaceHeight > 0) (event.y / surfaceHeight) * 2f - 1f else 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                // In-air hover: move DOS cursor smoothly under the hovering pen tip
                if (hasLastStylusPos) {
                    val dx = (event.x - lastStylusX) * mouseSensitivity
                    val dy = (event.y - lastStylusY) * mouseSensitivity
                    if (dx != 0f || dy != 0f) {
                        DosBoxEngineBridge.sendRelativeMouse(dx, dy)
                    }
                }
                lastStylusX = event.x
                lastStylusY = event.y
                hasLastStylusPos = true
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
            }

            MotionEvent.ACTION_DOWN -> {
                // Stylus touched down on glass
                if (hasLastStylusPos) {
                    val dx = (event.x - lastStylusX) * mouseSensitivity
                    val dy = (event.y - lastStylusY) * mouseSensitivity
                    if (dx != 0f || dy != 0f) {
                        DosBoxEngineBridge.sendRelativeMouse(dx, dy)
                    }
                }
                lastStylusX = event.x
                lastStylusY = event.y
                hasLastStylusPos = true
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, true)

                // Dispatch click: barrel button or eraser triggers Right-Click (2), tip triggers Left-Click (1)
                activeStylusButton = if (isBarrelPressed || isEraser) 2 else 1
                DosBoxEngineBridge.sendMouseButton(activeStylusButton, true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Stylus dragging on screen
                if (hasLastStylusPos) {
                    val dx = (event.x - lastStylusX) * mouseSensitivity
                    val dy = (event.y - lastStylusY) * mouseSensitivity
                    if (dx != 0f || dy != 0f) {
                        DosBoxEngineBridge.sendRelativeMouse(dx, dy)
                    }
                }
                lastStylusX = event.x
                lastStylusY = event.y
                hasLastStylusPos = true
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, true)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
                if (activeStylusButton != 0) {
                    DosBoxEngineBridge.sendMouseButton(activeStylusButton, false)
                    activeStylusButton = 0
                }
                hasLastStylusPos = false
            }
        }

        return true
    }

    /**
     * Processes touchscreen finger events tailored for SCUMM point-and-click adventure games:
     * - Tap-to-Click (Left Click) at the touched spot when [tapToPointEnabled]
     * - Drag tracking (moves cursor)
     * - Press-and-hold (Long Press > 350ms, timer-based so a perfectly still finger works) -> Right-Click
     * - Two-finger tap, or a tap after [rightClickNextTap] is armed -> Right-Click
     * - Palm rejection when stylus was recently active
     */
    private fun processFingerTouchEvent(event: MotionEvent, surfaceWidth: Float, surfaceHeight: Float): Boolean {
        // Palm rejection: ignore finger touch if stylus was used in the last 400ms
        if (System.currentTimeMillis() - lastStylusTimestamp < 400L) {
            return true
        }

        DosBoxEngineBridge.setDirectMouse(tapToPointEnabled)
        val normX = if (surfaceWidth > 0) (event.x / surfaceWidth) * 2f - 1f else 0f
        val normY = if (surfaceHeight > 0) (event.y / surfaceHeight) * 2f - 1f else 0f

        // Multi-touch: Two fingers -> Right-Click
        if (event.pointerCount >= 2) {
            longPressJob?.cancel()
            if (!isTwoFingerRightClicking) {
                isTwoFingerRightClicking = true
                DosBoxEngineBridge.sendMouseButton(2, true)
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP) {
                isTwoFingerRightClicking = false
                DosBoxEngineBridge.sendMouseButton(2, false)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = System.currentTimeMillis()
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                isLongPressTriggered = false
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
                // Also as motion, for games that ignore the driver's position (The Oregon Trail Deluxe)
                if (tapToPointEnabled && surfaceWidth > 0 && surfaceHeight > 0) {
                    DosBoxEngineBridge.pointMouseAt(event.x / surfaceWidth, event.y / surfaceHeight)
                }

                // Long-press -> Right-Click (Look / Inspect in SCUMM), fired by timer rather than on MOVE
                longPressJob?.cancel()
                longPressJob = scope.launch {
                    delay(350L)
                    if (!isDragging && !isTwoFingerRightClicking) {
                        isLongPressTriggered = true
                        DosBoxEngineBridge.sendMouseButton(2, true)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dist = hypot(event.x - touchDownX, event.y - touchDownY)
                val dx = (event.x - lastTouchX) * touchSensitivity
                val dy = (event.y - lastTouchY) * touchSensitivity
                if (dx != 0f || dy != 0f) {
                    DosBoxEngineBridge.sendRelativeMouse(dx, dy)
                }
                lastTouchX = event.x
                lastTouchY = event.y

                if (dist > 18f && !isDragging) {
                    isDragging = true
                    if (!isLongPressTriggered) longPressJob?.cancel()
                }

                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
            }

            MotionEvent.ACTION_UP -> {
                longPressJob?.cancel()
                val duration = System.currentTimeMillis() - touchDownTime
                if (isLongPressTriggered) {
                    DosBoxEngineBridge.sendMouseButton(2, false)
                    isLongPressTriggered = false
                } else if (!isDragging && duration < 320L) {
                    // Quick Tap -> Left Click (or Right Click when armed from the mouse toolbar). The press has to span
                    // several emulator frames: cores sample input once per frame, so a same-millisecond release is invisible
                    val button = if (_rightClickNextTap.value) 2 else 1
                    _rightClickNextTap.value = false
                    DosBoxEngineBridge.sendPointerAbsolute(normX, normY, button == 1)
                    DosBoxEngineBridge.sendMouseButton(button, true)
                    scope.launch {
                        delay(TAP_PRESS_MS)
                        DosBoxEngineBridge.sendMouseButton(button, false)
                        DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
                    }
                } else {
                    DosBoxEngineBridge.sendMouseButton(1, false)
                    DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
                }
                isDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                DosBoxEngineBridge.sendMouseButton(1, false)
                DosBoxEngineBridge.sendMouseButton(2, false)
                DosBoxEngineBridge.sendPointerAbsolute(normX, normY, false)
                isDragging = false
                isLongPressTriggered = false
                isTwoFingerRightClicking = false
            }
        }

        return true
    }
}

