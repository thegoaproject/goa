package com.goa.ui.controls

import android.view.MotionEvent
import com.goa.ui.theme.ControlAccent
import com.goa.ui.theme.ControlAccentAlt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.engine.input.PointAndClickInputManager
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScummTouchpadSurface(
    onPauseRequested: () -> Unit,
    onSwitchToJoypad: () -> Unit,
    onSwitchToKeyboard: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val leftClick = remember(coroutineScope) { HeldClick(1, coroutineScope) }
    val rightClick = remember(coroutineScope) { HeldClick(2, coroutineScope) }
    var speedMultiplier by remember { mutableFloatStateOf(1.0f) }
    var rightClickLock by remember { mutableStateOf(false) }

    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var downTime by remember { mutableStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14161C))
            .padding(6.dp)
    ) {
        // Header Options Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The title takes what's left, so on a narrow screen it gives way before any button label shrinks
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onPauseRequested)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu / Pause / Exit",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                com.goa.ui.menu.OneLineLabel(
                    text = "TOUCHPAD",
                    hideIfTooLong = true,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = ControlAccentAlt,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            // Speed segmented control & Switch buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Segmented cursor speed control (equal-width buttons + speed icon)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Trackpad Cursor Speed",
                        tint = ControlAccentAlt,
                        modifier = Modifier
                            .padding(start = 4.dp, end = 2.dp)
                            .size(14.dp)
                    )
                    listOf(1.0f to "1x", 1.5f to "1.5x", 2.0f to "2x").forEach { (speed, label) ->
                        val isSelected = speedMultiplier == speed
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) ControlAccentAlt.copy(alpha = 0.3f) else Color.Transparent)
                                .clickable { speedMultiplier = speed },
                            contentAlignment = Alignment.Center
                        ) {
                            com.goa.ui.menu.OneLineLabel(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ControlAccentAlt else Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                HeaderOptionButton(text = "Joypad", icon = Icons.Default.Gamepad, onClick = onSwitchToJoypad)
                HeaderOptionButton(text = "Keyboard", icon = Icons.Default.Keyboard, onClick = onSwitchToKeyboard)
                if (onClose != null) {
                    HeaderOptionButton(text = "Hide", icon = Icons.Default.Close, onClick = onClose)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Virtual Touchpad Active Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E212A))
                .border(1.5.dp, Color(0xFF343948), RoundedCornerShape(10.dp))
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Trackpad drives the cursor relatively, unlike direct taps on the game surface
                            DosBoxEngineBridge.setDirectMouse(false)
                            downTime = System.currentTimeMillis()
                            downX = event.x
                            downY = event.y
                            lastX = event.x
                            lastY = event.y
                            isDragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.x - lastX) * speedMultiplier
                            val dy = (event.y - lastY) * speedMultiplier
                            if (dx != 0f || dy != 0f) {
                                DosBoxEngineBridge.sendRelativeMouse(dx, dy)
                            }
                            lastX = event.x
                            lastY = event.y
                            if (hypot(event.x - downX, event.y - downY) > 12f) {
                                isDragging = true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val duration = System.currentTimeMillis() - downTime
                            if (!isDragging && duration < 300L) {
                                // Tap to click
                                val button = if (rightClickLock) 2 else 1
                                rightClickLock = false
                                DosBoxEngineBridge.sendMouseButton(button, true)
                                coroutineScope.launch {
                                    delay(40L)
                                    DosBoxEngineBridge.sendMouseButton(button, false)
                                }
                            }
                            isDragging = false
                        }
                    }
                    true
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "PRECISION TRACKPAD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.3f),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Slide finger to aim cursor • Tap to click • Two fingers to right-click",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.25f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Dual Physical Mouse Buttons (Left & Right Click Bar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Left Click Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF282C37),
                border = BorderStroke(1.5.dp, Color(0xFF434A5D)),
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> leftClick.down()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> leftClick.up()
                        }
                        true
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        com.goa.ui.menu.OneLineLabel(
                            text = "LEFT CLICK",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        com.goa.ui.menu.OneLineLabel(
                            text = "Walk / Select / Action",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Double Click Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF20232B),
                border = BorderStroke(1.dp, Color(0xFF383E4E)),
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxSize()
                    .clickable {
                        coroutineScope.launch {
                            DosBoxEngineBridge.sendMouseButton(1, true)
                            delay(35L)
                            DosBoxEngineBridge.sendMouseButton(1, false)
                            delay(60L)
                            DosBoxEngineBridge.sendMouseButton(1, true)
                            delay(35L)
                            DosBoxEngineBridge.sendMouseButton(1, false)
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    com.goa.ui.menu.OneLineLabel(
                        text = "2x CLICK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF80D8FF)
                    )
                }
            }

            // Esc: skips cutscenes and intros in most ScummVM games (Lure's two-minute intro, Beneath a Steel Sky's
            // comic), which otherwise need the on-screen keyboard. Labelled as the key it is: during play some games
            // use Esc for their menu or quit prompt (Lure asks "Are you sure (y/n)?"; Esc again or a right-click is No)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF20232B),
                border = BorderStroke(1.dp, Color(0xFF383E4E)),
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxSize()
                    .clickable {
                        coroutineScope.launch {
                            DosBoxEngineBridge.sendKeyEvent(ESC_SCANCODE, true)
                            delay(80L)
                            DosBoxEngineBridge.sendKeyEvent(ESC_SCANCODE, false)
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        com.goa.ui.menu.OneLineLabel(text = "ESC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD180))
                        com.goa.ui.menu.OneLineLabel(text = "Skip", fontSize = 9.sp, color = Color.White.copy(alpha = 0.55f))
                    }
                }
            }

            // Right Click Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF282C37),
                border = BorderStroke(1.5.dp, Color(0xFF434A5D)),
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> rightClick.down()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rightClick.up()
                        }
                        true
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        com.goa.ui.menu.OneLineLabel(
                            text = "RIGHT CLICK",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = ControlAccent
                        )
                        com.goa.ui.menu.OneLineLabel(
                            text = "Look / Inspect",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}


/**
 * Compact landscape toolbar for point-and-click games. Unlike [ScummTouchpadSurface] it leaves the game
 * visible: the player taps directly on the game surface (handled by [PointAndClickInputManager]).
 */
/**
 * A mouse button held for as long as the finger is down, but at least [MIN_CLICK_MS]. Some engines (Lure of the
 * Temptress) poll the button state once per frame rather than reading click events, and a very quick tap would
 * otherwise press and release between two polls and never be seen.
 */
private const val ESC_SCANCODE = 0x01

private class HeldClick(private val button: Int, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var downAt = 0L
    private var pendingRelease: kotlinx.coroutines.Job? = null

    fun down() {
        // A quick second tap while the first is still held: finish that click first so both register
        pendingRelease?.takeIf { it.isActive }?.let {
            it.cancel()
            DosBoxEngineBridge.sendMouseButton(button, false)
        }
        downAt = android.os.SystemClock.uptimeMillis()
        DosBoxEngineBridge.sendMouseButton(button, true)
    }

    fun up() {
        val remaining = MIN_CLICK_MS - (android.os.SystemClock.uptimeMillis() - downAt)
        if (remaining <= 0) {
            DosBoxEngineBridge.sendMouseButton(button, false)
        } else {
            pendingRelease = scope.launch {
                delay(remaining)
                DosBoxEngineBridge.sendMouseButton(button, false)
            }
        }
    }

    private companion object {
        const val MIN_CLICK_MS = 60L
    }
}

@Composable
fun PointerModeToolbar(
    pointerInputManager: PointAndClickInputManager,
    onPauseRequested: () -> Unit,
    onSwitchToJoypad: () -> Unit,
    onSwitchToKeyboard: () -> Unit,
    onToggleTouchpad: (() -> Unit)? = null,
    activeMode: InputSurfaceMode? = null,
    modifier: Modifier = Modifier
) {
    val rightClickArmed by pointerInputManager.rightClickNextTap.collectAsState()

    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PointerToolbarButton(icon = Icons.Default.Menu, label = "Menu", onClick = onPauseRequested)
        PointerToolbarButton(
            icon = Icons.Default.Mouse,
            label = "R-Click",
            highlighted = rightClickArmed,
            onClick = { pointerInputManager.setRightClickNextTap(!rightClickArmed) }
        )
        if (onToggleTouchpad != null) {
            PointerToolbarButton(
                icon = Icons.Default.TouchApp,
                label = "Pad",
                highlighted = activeMode == InputSurfaceMode.SCUMM_MOUSE,
                onClick = onToggleTouchpad
            )
        }
        PointerToolbarButton(
            icon = Icons.Default.Gamepad,
            label = "Joypad",
            highlighted = activeMode == InputSurfaceMode.JOYPAD,
            onClick = onSwitchToJoypad
        )
        PointerToolbarButton(
            icon = Icons.Default.Keyboard,
            label = "Keys",
            highlighted = activeMode == InputSurfaceMode.PC_KEYBOARD,
            onClick = onSwitchToKeyboard
        )
    }
}

@Composable
private fun PointerToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) ControlAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (highlighted) ControlAccent else Color.White.copy(alpha = 0.2f)),
        modifier = Modifier
            .size(52.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) ControlAccent else Color.White,
                modifier = Modifier.size(20.dp)
            )
            com.goa.ui.menu.OneLineLabel(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}
