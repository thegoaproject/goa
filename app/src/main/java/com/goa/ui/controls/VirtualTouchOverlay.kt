package com.goa.ui.controls

import android.view.MotionEvent
import com.goa.ui.theme.ControlAccent
import com.goa.ui.theme.ControlBrand
import com.goa.ui.theme.ControlAccentAlt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.engine.input.ControllerAction
import com.goa.engine.input.GamepadButton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A shoulder-button label for [action]: "FIRE", "USE", "RUN", "JUMP"... ([fallback] before mappings load). */
internal fun shortLabel(action: ControllerAction?, fallback: String): String = when (action?.id) {
    null -> fallback
    "fire_ctrl", "fire_space" -> "FIRE"
    "space_use", "enter_space" -> "USE"
    "alt_fire" -> "ALT"
    "sprint_shift" -> "RUN"
    "run_toggle" -> "RUN ⇆"
    "crouch" -> "CROUCH"
    "crouch_toggle" -> "CROUCH ⇆"
    "jump_build", "jump_ctrl", "jump_slash", "jump_enter" -> "JUMP"
    "next_weapon", "next_weapon_slash" -> "NEXT"
    "prev_weapon" -> "PREV"
    "automap_tab" -> "MAP"
    "esc_menu", "esc_crouch" -> "ESC"
    "enter_select" -> "ENTER"
    "pause_menu" -> "MENU"
    "mouse_lclick" -> "L-CLICK"
    "mouse_rclick" -> "R-CLICK"
    "status_f1" -> "F1"
    "quick_save" -> "SAVE"
    "quick_load" -> "LOAD"
    "pogo_alt" -> "POGO"
    else -> action.defaultKeyLabel.substringBefore(" (").uppercase().take(8) + if (action.isLatch) " ⇆" else ""
}

/** Portrait: the top bar (pause, L1, badge, R1, close) is 34dp buttons + 12dp padding; the controls start below it. */
private val PortraitTopBarClearance = 56.dp
private val PortraitConsoleGap = 12.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VirtualTouchOverlay(
    visible: Boolean,
    onPauseRequested: () -> Unit,
    onKeyboardRequested: () -> Unit = {},
    onMouseRequested: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    customPositions: JoypadCustomPositions? = null,
    /**
     * The game's own pad: ABXY and the shoulder buttons press these buttons through the game's controller profile
     * (GamepadInputManager.pressButton), so touch and a real controller do the same thing in every game. Null keeps
     * the fixed keys (previews).
     */
    onButton: ((GamepadButton, Boolean) -> Unit)? = null,
    /** What each button does in this game, for the shoulder button labels. */
    mappings: Map<GamepadButton, ControllerAction> = emptyMap(),
    /** The game's own names for buttons ("BLOCK", "JUMP"), from its profile; they beat the generic [shortLabel]. */
    labels: Map<GamepadButton, String> = emptyMap(),
    /** The stick mode in effect ([StickMode.modeIndex]), so each stick says what it does in this game. */
    stickMode: Int = 0,
    /**
     * Native ports (Quake, Tomb Raider) read the pad, not DOS keys: the centre buttons press Select and Start through
     * [onButton] instead of sending Tab and Esc, and show the game's own names for them from [labels].
     */
    consoleAsPad: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (leftStickLabel, rightStickLabel) = stickLabels(stickMode)
    // Shared live dual-stick coordinates so left and right thumbsticks operate simultaneously
    var leftStickX by remember { mutableFloatStateOf(0f) }
    var leftStickY by remember { mutableFloatStateOf(0f) }
    var rightStickX by remember { mutableFloatStateOf(0f) }
    var rightStickY by remember { mutableFloatStateOf(0f) }
    var weaponSlot by remember { androidx.compose.runtime.mutableIntStateOf(2) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight * 1.2f
            val pillarboxMargin = if (isLandscape) maxOf(0.dp, (maxWidth - maxHeight * (4f / 3f)) / 2) else 0.dp

            val scale = customPositions?.scale ?: 1.0f
            val controlSize = (146 * scale).dp
            val stickSize = (128 * scale).dp
            val dpadCompactSize = (94 * scale).dp

            val defaultLeftStart = if (isLandscape) {
                if (pillarboxMargin > 160.dp) maxOf(20.dp, (pillarboxMargin - 146.dp) / 2) else 24.dp
            } else {
                24.dp
            }
            // Portrait: the sticks, ABXY diamond and the MAP/ESC row below them are one group, centred in the space
            // under the top bar (≈46dp: shoulder buttons sit there, so nothing may start above it)
            val portraitClusterHeight = controlSize - 10.dp + stickSize
            val portraitGroupHeight = portraitClusterHeight + PortraitConsoleGap + 40.dp
            val defaultLeftTop = if (isLandscape) {
                maxOf(16.dp, (maxHeight - 146.dp) * 0.25f)
            } else {
                maxOf(PortraitTopBarClearance, (maxHeight - portraitGroupHeight) / 2)
            }

            val leftStart = customPositions?.dpadOffset?.offsetX ?: defaultLeftStart
            val leftTop = customPositions?.dpadOffset?.offsetY ?: defaultLeftTop

            val buttonsEnd = customPositions?.buttonsOffset?.offsetX ?: defaultLeftStart
            val buttonsTop = customPositions?.buttonsOffset?.offsetY ?: defaultLeftTop
            val buttonsX = maxWidth - controlSize - buttonsEnd

            // Right Analog Stick positioned ergonomically below/inward of the 4-button diamond
            val rightStickXPos = if (isLandscape) {
                maxOf(maxWidth * 0.56f, buttonsX - stickSize * 0.65f)
            } else {
                maxOf(maxWidth * 0.54f, buttonsX - stickSize * 0.48f)
            }
            val rightStickYPos = (buttonsTop + controlSize - 10.dp).coerceAtMost(maxHeight - stickSize - 52.dp)

            // Classic Gravis D-Pad positioned below/inward of the Left Analog Stick for menu/weapon control
            val dpadXPos = if (isLandscape) {
                (leftStart + stickSize * 0.60f).coerceAtMost(maxWidth * 0.42f - dpadCompactSize)
            } else {
                (leftStart + stickSize * 0.52f).coerceAtMost(maxWidth * 0.44f - dpadCompactSize)
            }
            val dpadYPos = (leftTop + controlSize + 2.dp).coerceAtMost(maxHeight - dpadCompactSize - 52.dp)

            // Top Bar with Pause Button, Shoulder Triggers (L1/R1 + Fire), & Gravis Dual-Analog Branding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xAA181A1F),
                        border = BorderStroke(1.dp, Color(0x44FFFFFF))
                    ) {
                        IconButton(
                            onClick = onPauseRequested,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Pause Menu",
                                tint = Color.White
                            )
                        }
                    }

                    // Left Bumper: Use / Open (Space)
                    GravisShoulderTrigger(
                        tag = "L1",
                        label = labels[GamepadButton.BUTTON_L1]?.uppercase() ?: shortLabel(mappings[GamepadButton.BUTTON_L1], "USE"),
                        scancode = 0x39,
                        onPress = onButton?.let { press -> { down: Boolean -> press(GamepadButton.BUTTON_L1, down) } }
                    )
                }

                // Keeps the shoulder buttons at the two ends of the top bar
                Spacer(modifier = Modifier.weight(1f))

                // Right Trigger: Primary Fire (Left Ctrl) + Optional Hide Controls Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GravisShoulderTrigger(
                        tag = "R1",
                        label = labels[GamepadButton.BUTTON_R1]?.uppercase() ?: shortLabel(mappings[GamepadButton.BUTTON_R1], "FIRE"),
                        scancode = 0x1D,
                        accentColor = Color(0xFFE53935),
                        onPress = onButton?.let { press -> { down: Boolean -> press(GamepadButton.BUTTON_R1, down) } }
                    )
                    if (onClose != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xAA181A1F),
                            border = BorderStroke(1.dp, Color(0x44FFFFFF))
                        ) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hide Controls",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Console buttons MAP (Select) + ESC (Start)
            val consoleButtons: @Composable () -> Unit = {
                val padConsole = if (consoleAsPad) onButton else null
                GravisCenterConsoleButton(
                    label = padConsole?.let { labels[GamepadButton.BUTTON_SELECT]?.uppercase() } ?: "MAP",
                    subLabel = "TAB",
                    scancode = 0x0F,
                    accentColor = ControlAccent,
                    onPress = padConsole?.let { press -> { down: Boolean -> press(GamepadButton.BUTTON_SELECT, down) } }
                )
                GravisCenterConsoleButton(
                    label = padConsole?.let { labels[GamepadButton.BUTTON_START]?.uppercase() } ?: "ESC",
                    subLabel = "START",
                    scancode = 0x01,
                    accentColor = Color(0xFFE53935),
                    onPress = padConsole?.let { press -> { down: Boolean -> press(GamepadButton.BUTTON_START, down) } }
                )
            }
            if (!isLandscape) {
                Row(
                    modifier = if (customPositions?.dpadOffset == null && customPositions?.buttonsOffset == null) {
                        // Default portrait layout: directly under the stick cluster
                        Modifier.align(Alignment.TopCenter).offset(y = leftTop + portraitClusterHeight + PortraitConsoleGap)
                    } else {
                        Modifier.align(Alignment.Center).offset(y = (-14).dp)
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { consoleButtons() }
            }

            // Bottom Mode Buttons: PC Keyboard & Touchpad & Hide
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Landscape: MAP and ESC sit down here at the bottom edge, not over the middle of the game
                if (isLandscape) {
                    consoleButtons()
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCC1A1C22),
                    border = BorderStroke(1.5.dp, Color(0xFF4A4E5C)),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onKeyboardRequested
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Toggle PC Keyboard",
                            tint = ControlAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        com.goa.ui.menu.OneLineLabel(
                            text = "KEYB",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                if (onMouseRequested != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xCC1A1C22),
                        border = BorderStroke(1.5.dp, ControlAccentAlt.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMouseRequested
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mouse,
                                contentDescription = "Toggle touchpad",
                                tint = ControlAccentAlt,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            com.goa.ui.menu.OneLineLabel(
                                text = "MOUSE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                if (onClose != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xCC1A1C22),
                        border = BorderStroke(1.5.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hide Controls",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            com.goa.ui.menu.OneLineLabel(
                                text = "HIDE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }

            // 1. LEFT ANALOG STICK: Move Forward/Back + Strafe Left/Right
            Box(
                modifier = Modifier
                    .offset(x = leftStart, y = leftTop)
                    .size(controlSize)
            ) {
                GravisAnalogStick(
                    label = leftStickLabel,
                    accentColor = ControlAccent,
                    onStickMoved = { nx, ny ->
                        leftStickX = nx
                        leftStickY = ny
                        DosBoxEngineBridge.sendAnalogSticks(leftStickX, leftStickY, rightStickX, rightStickY)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. COMPACT GRAVIS D-PAD: Menu navigation & classic directional input
            Box(
                modifier = Modifier
                    .offset(x = dpadXPos, y = dpadYPos)
                    .size(dpadCompactSize)
            ) {
                GravisDpad(
                    onButton = onButton,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. RIGHT ANALOG STICK: Turn Left/Right + Look Up/Down
            Box(
                modifier = Modifier
                    .offset(x = rightStickXPos, y = rightStickYPos)
                    .size(stickSize)
            ) {
                GravisAnalogStick(
                    label = rightStickLabel,
                    accentColor = ControlAccentAlt,
                    fireOnDoubleTapHold = true,
                    onStickMoved = { nx, ny ->
                        rightStickX = nx
                        rightStickY = ny
                        DosBoxEngineBridge.sendAnalogSticks(leftStickX, leftStickY, rightStickX, rightStickY)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 4. XBOX ABXY ACTION CLUSTER (A=South/Green, B=East/Red, X=West/Blue, Y=North/Yellow)
            Box(
                modifier = Modifier
                    .offset(x = buttonsX, y = buttonsTop)
                    .size(controlSize)
            ) {
                XboxActionCluster(
                    currentWeaponSlot = weaponSlot,
                    onCycleWeapon = { nextSlot -> weaponSlot = nextSlot },
                    onButton = onButton,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GravisShoulderTrigger(
    tag: String,
    label: String,
    scancode: Int,
    accentColor: Color = ControlAccent,
    onPress: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isPressed) accentColor.copy(alpha = 0.35f) else Color(0xCC181A1F),
        border = BorderStroke(1.5.dp, if (isPressed) accentColor else accentColor.copy(alpha = 0.55f)),
        modifier = modifier
            .size(width = ShoulderWidth, height = ShoulderHeight)
            .scale(if (isPressed) 0.94f else 1f)
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        onPress?.invoke(true) ?: DosBoxEngineBridge.sendKeyEvent(scancode, true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        onPress?.invoke(false) ?: DosBoxEngineBridge.sendKeyEvent(scancode, false)
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Fixed size whatever the game calls the action: one line, the label shrinks or loses words, never wraps
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tag,
                color = accentColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.width(5.dp))
            FitText(text = label, modifier = Modifier.weight(1f), maxSize = 11.sp, minSize = 7.sp)
        }
    }
}

/** The shoulder buttons' fixed size: fits L1, the badge and R1 across a 360dp-wide phone in portrait. */
private val ShoulderWidth = 84.dp
private val ShoulderHeight = 34.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GravisCenterConsoleButton(
    label: String,
    subLabel: String,
    scancode: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
    // The pad button's own press (native ports); null sends [scancode]
    onPress: ((Boolean) -> Unit)? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isPressed) Color(0xFF323642) else Color(0xDD14161B),
        border = BorderStroke(1.5.dp, if (isPressed) accentColor else Color(0xFF424756)),
        modifier = modifier
            .scale(if (isPressed) 0.93f else 1f)
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        onPress?.invoke(true) ?: DosBoxEngineBridge.sendKeyEvent(scancode, true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        onPress?.invoke(false) ?: DosBoxEngineBridge.sendKeyEvent(scancode, false)
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            com.goa.ui.menu.OneLineLabel(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Gravis Dual-Analog Thumbstick with spring-return, machined brass collar,
 * concave rubberized thumb cap, and live normalized (-1..1) vector output.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GravisAnalogStick(
    label: String = "",
    accentColor: Color = ControlAccent,
    fireOnDoubleTapHold: Boolean = false,
    onStickMoved: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var sizePx by remember { mutableStateOf(IntSize(0, 0)) }
    var knobX by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }
    var isActive by remember { mutableStateOf(false) }
    var lastDownTime by remember { mutableStateOf(0L) }
    var isDoubleTapFiring by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .pointerInteropFilter { event ->
                val w = sizePx.width.toFloat()
                val h = sizePx.height.toFloat()
                if (w <= 0f || h <= 0f) return@pointerInteropFilter false

                val cx = w / 2f
                val cy = h / 2f
                val maxTravel = minOf(cx, cy) * 0.56f

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val now = System.currentTimeMillis()
                        if (fireOnDoubleTapHold && (now - lastDownTime) < 280L) {
                            isDoubleTapFiring = true
                            DosBoxEngineBridge.sendKeyEvent(0x1D, true) // Hold Fire while aiming
                        }
                        lastDownTime = now
                        isActive = true

                        val dx = event.x - cx
                        val dy = event.y - cy
                        val dist = hypot(dx, dy)
                        val clampedDist = minOf(dist, maxTravel)
                        val scale = if (dist > 0.001f) clampedDist / dist else 0f
                        knobX = (dx * scale) / maxTravel
                        knobY = (dy * scale) / maxTravel
                        onStickMoved(knobX, knobY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - cx
                        val dy = event.y - cy
                        val dist = hypot(dx, dy)
                        val clampedDist = minOf(dist, maxTravel)
                        val scale = if (dist > 0.001f) clampedDist / dist else 0f
                        knobX = (dx * scale) / maxTravel
                        knobY = (dy * scale) / maxTravel
                        onStickMoved(knobX, knobY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isActive = false
                        knobX = 0f
                        knobY = 0f
                        onStickMoved(0f, 0f)
                        if (isDoubleTapFiring) {
                            isDoubleTapFiring = false
                            DosBoxEngineBridge.sendKeyEvent(0x1D, false)
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(cx, cy)
            val maxTravel = r * 0.52f

            // 1. Outer Recessed Socket Well
            drawCircle(
                color = Color(0xAA0A0B0E),
                radius = r,
                center = Offset(cx, cy)
            )

            // 2. Beveled Gate Ring
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        if (isActive) accentColor.copy(alpha = 0.85f) else Color(0xFF454A56),
                        Color(0xFF1E2026)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                radius = r - 2f,
                center = Offset(cx, cy),
                style = Stroke(width = if (isActive) 3.5f else 2.5f)
            )

            // 3. Octagonal / Circular Gate Dish
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF22252C), Color(0xFF121418)),
                    center = Offset(cx, cy),
                    radius = r * 0.86f
                ),
                radius = r * 0.86f,
                center = Offset(cx, cy)
            )

            // 4. Crosshair Axis Guide Lines
            drawLine(
                color = Color(0xFF2E323C),
                start = Offset(cx - r * 0.72f, cy),
                end = Offset(cx + r * 0.72f, cy),
                strokeWidth = 1.5f
            )
            drawLine(
                color = Color(0xFF2E323C),
                start = Offset(cx, cy - r * 0.72f),
                end = Offset(cx, cy + r * 0.72f),
                strokeWidth = 1.5f
            )

            // 5. Central Brass Pivot Collar & Stick Shaft
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFD54F), Color(0xFFB8860B), Color(0xFF5A420C)),
                    center = Offset(cx, cy),
                    radius = r * 0.20f
                ),
                radius = r * 0.20f,
                center = Offset(cx, cy)
            )

            val stickPos = Offset(cx + knobX * maxTravel, cy + knobY * maxTravel)

            // Steel/Brass Shaft connecting center socket to displaced thumb cap
            drawLine(
                color = Color(0xFF8C7335),
                start = Offset(cx, cy),
                end = stickPos,
                strokeWidth = r * 0.14f
            )

            // 6. Thumb Cap Drop Shadow
            drawCircle(
                color = Color(0x99000000),
                radius = r * 0.38f,
                center = stickPos + Offset(2f, 4f)
            )

            // 7. Concave Rubberized Gravis Thumb Cap
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF3A3F4B), Color(0xFF23262E), Color(0xFF14161B)),
                    center = stickPos - Offset(r * 0.08f, r * 0.08f),
                    radius = r * 0.38f
                ),
                radius = r * 0.38f,
                center = stickPos
            )

            // Outer Grip Ring on Thumb Cap
            drawCircle(
                color = if (isActive) accentColor else Color(0xFF505666),
                radius = r * 0.36f,
                center = stickPos,
                style = Stroke(width = 2.2f)
            )

            // Inner Concave Dish & Center Brass Pip
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF181A20), Color(0xFF282C35)),
                    center = stickPos + Offset(2f, 2f),
                    radius = r * 0.24f
                ),
                radius = r * 0.24f,
                center = stickPos
            )
            drawCircle(
                color = if (isDoubleTapFiring) Color(0xFFFF5252) else accentColor.copy(alpha = 0.8f),
                radius = r * 0.06f,
                center = stickPos
            )
        }

        if (label.isNotEmpty()) {
            com.goa.ui.menu.OneLineLabel(
                text = label,
                color = Color(0xAA9EA4B4),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
            )
        }
    }
}

/**
 * Authentic 1991 Advanced Gravis PC GamePad D-Pad:
 * - Circular slate-charcoal disc with 8 radial traction ridges
 * - 4 cardinal directional relief arrows (▲ ▼ ◀ ▶)
 * - Iconic brass/gold screw-in mini-joystick socket in center
 * - Fluid 8-way multi-directional touch tracking (cardinals + diagonals)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GravisDpad(
    modifier: Modifier = Modifier,
    /** The D-pad's four directions are bindable pad buttons (arrows by default, items in shooters); null sends arrows */
    onButton: ((GamepadButton, Boolean) -> Unit)? = null
) {
    // Directions are tracked by their arrow scancode (it drives the highlight) and sent as D-pad buttons
    fun send(scancode: Int, down: Boolean) {
        val button = when (scancode) {
            0x48 -> GamepadButton.DPAD_UP
            0x50 -> GamepadButton.DPAD_DOWN
            0x4B -> GamepadButton.DPAD_LEFT
            else -> GamepadButton.DPAD_RIGHT
        }
        val press = onButton
        if (press != null) press(button, down) else DosBoxEngineBridge.sendKeyEvent(scancode, down) // no pad: plain arrows
    }
    var sizePx by remember { mutableStateOf(IntSize(0, 0)) }
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }

    Box(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .pointerInteropFilter { event ->
                val w = sizePx.width.toFloat()
                val h = sizePx.height.toFloat()
                if (w <= 0f || h <= 0f) return@pointerInteropFilter false

                val centerX = w / 2f
                val centerY = h / 2f
                val radius = minOf(centerX, centerY)
                val deadzone = radius * 0.22f

                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - centerX
                        val dy = event.y - centerY
                        val dist = hypot(dx, dy)

                        val newKeys = mutableSetOf<Int>()
                        if (dist >= deadzone && dist <= radius * 1.35f) {
                            val threshold = radius * 0.20f
                            if (dy < -threshold) newKeys.add(0x48) // UP
                            if (dy > threshold) newKeys.add(0x50)  // DOWN
                            if (dx < -threshold) newKeys.add(0x4B) // LEFT
                            if (dx > threshold) newKeys.add(0x4D)  // RIGHT
                        }

                        // Release keys that are no longer pressed
                        val toRelease = pressedKeys - newKeys
                        toRelease.forEach { send(it, false) }

                        // Press newly activated keys
                        val toPress = newKeys - pressedKeys
                        toPress.forEach { send(it, true) }

                        pressedKeys = newKeys
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressedKeys.forEach { sc -> send(sc, false) }
                        pressedKeys = emptySet()
                        true
                    }
                    else -> false
                }
            }
    ) {
        val arrowActiveColor = ControlAccent
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(cx, cy)

            // 1. Outer Dark Drop Shadow / Recessed Bezel
            drawCircle(
                color = Color(0x990A0B0D),
                radius = r,
                center = Offset(cx, cy)
            )

            // 2. Beveled Metallic Rim
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF454A56), Color(0xFF202227)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                radius = r - 2f,
                center = Offset(cx, cy),
                style = Stroke(width = 4f)
            )

            // 3. Main Circular Dish with subtle textured radial gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF282B33), Color(0xFF181A1F)),
                    center = Offset(cx, cy),
                    radius = r - 4f
                ),
                radius = r - 4f,
                center = Offset(cx, cy)
            )

            // 4. Inner Recessed Disc
            drawCircle(
                color = Color(0xFF141518),
                radius = r * 0.78f,
                center = Offset(cx, cy)
            )

            // 5. 8 Radial Traction Ridges / Grooves
            val angles = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
            angles.forEach { deg ->
                val rad = deg * PI / 180.0
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                val pStart = Offset(cx + cosA * r * 0.35f, cy + sinA * r * 0.35f)
                val pEnd = Offset(cx + cosA * r * 0.75f, cy + sinA * r * 0.75f)

                // Groove shadow
                drawLine(
                    color = Color(0xFF0D0E10),
                    start = pStart + Offset(1f, 1f),
                    end = pEnd + Offset(1f, 1f),
                    strokeWidth = 3f
                )
                // Groove highlight
                drawLine(
                    color = Color(0xFF3E434F),
                    start = pStart,
                    end = pEnd,
                    strokeWidth = 2.5f
                )
            }

            // 6. Cardinal Direction Indicators (▲ ▼ ◀ ▶)
            val upActive = 0x48 in pressedKeys
            val downActive = 0x50 in pressedKeys
            val leftActive = 0x4B in pressedKeys
            val rightActive = 0x4D in pressedKeys

            // UP Arrow
            drawDirectionArrow(
                center = Offset(cx, cy - r * 0.54f),
                angleRad = -PI / 2,
                arrowSize = r * 0.13f,
                active = upActive,
                activeColor = arrowActiveColor
            )
            // DOWN Arrow
            drawDirectionArrow(
                center = Offset(cx, cy + r * 0.54f),
                angleRad = PI / 2,
                arrowSize = r * 0.13f,
                active = downActive,
                activeColor = arrowActiveColor
            )
            // LEFT Arrow
            drawDirectionArrow(
                center = Offset(cx - r * 0.54f, cy),
                angleRad = PI,
                arrowSize = r * 0.13f,
                active = leftActive,
                activeColor = arrowActiveColor
            )
            // RIGHT Arrow
            drawDirectionArrow(
                center = Offset(cx + r * 0.54f, cy),
                angleRad = 0.0,
                arrowSize = r * 0.13f,
                active = rightActive,
                activeColor = arrowActiveColor
            )

            // 7. Iconic Central Brass Screw-in Mini-Stick Socket
            // Brass Outer Bezel
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFD54F), Color(0xFFD4AF37), Color(0xFF8C6D1F)),
                    center = Offset(cx - 2f, cy - 2f),
                    radius = r * 0.22f
                ),
                radius = r * 0.22f,
                center = Offset(cx, cy)
            )

            // Inner Machined Brass Rim
            drawCircle(
                color = Color(0xFF6B5212),
                radius = r * 0.18f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )

            // Dark Center Thread Pit
            drawCircle(
                color = Color(0xFF0F1013),
                radius = r * 0.14f,
                center = Offset(cx, cy)
            )

            // Central Screw Thread Slot / Socket Detail
            drawLine(
                color = Color(0xFF6E561E),
                start = Offset(cx - r * 0.08f, cy),
                end = Offset(cx + r * 0.08f, cy),
                strokeWidth = 2.5f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionArrow(
    center: Offset,
    angleRad: Double,
    arrowSize: Float,
    active: Boolean,
    activeColor: Color
) {
    val tip = Offset(
        center.x + (cos(angleRad) * arrowSize).toFloat(),
        center.y + (sin(angleRad) * arrowSize).toFloat()
    )
    val leftRad = angleRad + 2.5
    val rightRad = angleRad - 2.5
    val leftPt = Offset(
        center.x + (cos(leftRad) * arrowSize * 0.9f).toFloat(),
        center.y + (sin(leftRad) * arrowSize * 0.9f).toFloat()
    )
    val rightPt = Offset(
        center.x + (cos(rightRad) * arrowSize * 0.9f).toFloat(),
        center.y + (sin(rightRad) * arrowSize * 0.9f).toFloat()
    )

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftPt.x, leftPt.y)
        lineTo(rightPt.x, rightPt.y)
        close()
    }

    val arrowColor = if (active) activeColor else Color(0xFF8F95A5)
    drawPath(path = path, color = arrowColor)
}

/**
 * Authentic Gravis 4-Button Cluster arranged in classic diamond format with Modern Console FPS Mapping:
 * - 1: Red (Top) -> Scancode 0x1D (Ctrl / Primary Fire)
 * - 2: Yellow (Right) -> Cycles Weapons ('1'..'7' in Doom/Heretic/Duke3D + ']' Inventory Next)
 * - 3: Green (Bottom) -> Scancode 0x39 + 0x1C (Space / Use / Open Doors + Enter in Menus)
 * - 4: Blue (Left) -> Scancode 0x2A + 0x1E + 0x2F (Left Shift Sprint/Run in Doom + 'A'/'/' Jump in Build/Hexen)
 */
/**
 * Modern Xbox ABXY Action Cluster:
 * - Y: YELLOW (Top / North) -> Weapon Next / Cycle Weapon (Scancode 0x1B ']' + 1..7)
 * - B: RED (Right / East) -> Escape / Cancel / Crouch (Scancode 0x01 Esc + 0x2C 'Z')
 * - A: GREEN (Bottom / South) -> Enter (Select) + Space (Open Doors / Use) (Scancode 0x1C + 0x39)
 * - X: BLUE (Left / West) -> Left Shift (Sprint) + Jump (Scancode 0x2A + 0x1E 'A' + 0x35 '/')
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun XboxActionCluster(
    currentWeaponSlot: Int = 2,
    onCycleWeapon: (Int) -> Unit = {},
    onButton: ((GamepadButton, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    fun press(button: GamepadButton): ((Boolean) -> Unit)? = onButton?.let { cb -> { down: Boolean -> cb(button, down) } }
    Box(modifier = modifier) {
        // Button Y: YELLOW (Top) -> Next Weapon / Cycle Slots
        XboxButton(
            letter = "Y",
            onPress = press(GamepadButton.BUTTON_Y),
            scancode = 0x1B, // ']'
            joypadButtonId = 9, // Libretro X / Gameport Button 4
            highlightColor = Color(0xFFFFF176),
            baseColor = Color(0xFFFBC02D),
            shadowColor = Color(0xFFC49000),
            textColor = Color(0xFF1E2024),
            onTap = {
                val next = if (currentWeaponSlot >= 7) 1 else currentWeaponSlot + 1
                onCycleWeapon(next)
                val numScancodes = intArrayOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
                val sc = numScancodes.getOrElse(next - 1) { 0x03 }
                DosBoxEngineBridge.sendKeyEvent(sc, true)
                DosBoxEngineBridge.sendKeyEvent(sc, false)
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Button B: RED (Right) -> Escape / Cancel / Back / Crouch
        XboxButton(
            letter = "B",
            onPress = press(GamepadButton.BUTTON_B),
            scancode = 0x01, // Esc (Back / Cancel in menu)
            secondaryScancode = 0x2C, // 'Z' (Crouch in Build)
            joypadButtonId = 8, // Libretro A / Gameport Button 2
            highlightColor = Color(0xFFFF5252),
            baseColor = Color(0xFFD32F2F),
            shadowColor = Color(0xFF8B0000),
            textColor = Color.White,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // Button A: GREEN (Bottom) -> Enter (Select) + Space (Open / Use)
        XboxButton(
            letter = "A",
            onPress = press(GamepadButton.BUTTON_A),
            scancode = 0x1C, // Enter / Select
            secondaryScancode = 0x39, // Space / Open / Use
            joypadButtonId = 0, // Libretro B / Gameport Button 1
            highlightColor = Color(0xFF81C784),
            baseColor = Color(0xFF388E3C),
            shadowColor = Color(0xFF1B5E20),
            textColor = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Button X: BLUE (Left) -> Left Shift (Sprint) + 'A' / '/' (Jump in Build & Hexen)
        XboxButton(
            letter = "X",
            onPress = press(GamepadButton.BUTTON_X),
            scancode = 0x2A, // Left Shift (Sprint)
            secondaryScancode = 0x1E, // 'A' (Jump in Build)
            tertiaryScancode = 0x35, // '/' (Jump in Hexen)
            joypadButtonId = 1, // Libretro Y / Gameport Button 3
            highlightColor = Color(0xFF64B5F6),
            baseColor = Color(0xFF1976D2),
            shadowColor = Color(0xFF0D47A1),
            textColor = Color.White,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

/** Legacy alias for backwards compatibility */
@Composable
fun GravisActionCluster(
    currentWeaponSlot: Int = 2,
    onCycleWeapon: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) = XboxActionCluster(currentWeaponSlot, onCycleWeapon, modifier = modifier)

/**
 * Domed 3D circular Xbox-style action button (A, B, X, Y)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun XboxButton(
    letter: String,
    scancode: Int,
    secondaryScancode: Int? = null,
    tertiaryScancode: Int? = null,
    joypadButtonId: Int? = null,
    highlightColor: Color,
    baseColor: Color,
    shadowColor: Color,
    textColor: Color,
    onTap: (() -> Unit)? = null,
    /** When set, the press goes through the game's controller profile instead of the fixed keys and [onTap]. */
    onPress: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .size(48.dp)
            .scale(if (isPressed) 0.92f else 1f)
            .clip(CircleShape)
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        if (onPress != null) { onPress(true); return@pointerInteropFilter true }
                        DosBoxEngineBridge.sendKeyEvent(scancode, true)
                        if (secondaryScancode != null) {
                            DosBoxEngineBridge.sendKeyEvent(secondaryScancode, true)
                        }
                        if (tertiaryScancode != null) {
                            DosBoxEngineBridge.sendKeyEvent(tertiaryScancode, true)
                        }
                        if (joypadButtonId != null) {
                            DosBoxEngineBridge.sendJoypadButton(joypadButtonId, true)
                        }
                        onTap?.invoke()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        if (onPress != null) { onPress(false); return@pointerInteropFilter true }
                        DosBoxEngineBridge.sendKeyEvent(scancode, false)
                        if (secondaryScancode != null) {
                            DosBoxEngineBridge.sendKeyEvent(secondaryScancode, false)
                        }
                        if (tertiaryScancode != null) {
                            DosBoxEngineBridge.sendKeyEvent(tertiaryScancode, false)
                        }
                        if (joypadButtonId != null) {
                            DosBoxEngineBridge.sendJoypadButton(joypadButtonId, false)
                        }
                        true
                    }
                    else -> false
                }
            },
        shape = CircleShape,
        color = Color(0xFF141518),
        border = BorderStroke(2.dp, Color(0xFF333742))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = if (isPressed) {
                            listOf(baseColor, shadowColor)
                        } else {
                            listOf(highlightColor, baseColor, shadowColor)
                        },
                        center = Offset(20f, 20f),
                        radius = 45f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            com.goa.ui.menu.OneLineLabel(
                text = letter,
                color = textColor,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp
            )
        }
    }
}

/** Legacy alias for backwards compatibility */
@Composable
fun GravisButton(
    number: String,
    scancode: Int,
    secondaryScancode: Int? = null,
    tertiaryScancode: Int? = null,
    highlightColor: Color,
    baseColor: Color,
    shadowColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) = XboxButton(
    letter = number,
    scancode = scancode,
    secondaryScancode = secondaryScancode,
    tertiaryScancode = tertiaryScancode,
    highlightColor = highlightColor,
    baseColor = baseColor,
    shadowColor = shadowColor,
    textColor = textColor,
    modifier = modifier
)

/** What the left and right touch sticks do in each stick mode ([StickMode.modeIndex]). */
internal fun stickLabels(stickMode: Int): Pair<String, String> = when (stickMode) {
    1 -> "ARROW KEYS" to "ARROW KEYS"
    2 -> "JOYSTICK" to "JOYSTICK 2"
    3 -> "POINTER" to "FINE POINTER"
    4 -> "WALK / TURN" to "POINTER"
    else -> "MOVE / STRAFE" to "LOOK / TURN"
}
