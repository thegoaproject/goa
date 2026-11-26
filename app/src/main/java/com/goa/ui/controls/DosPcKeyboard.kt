package com.goa.ui.controls

import android.view.MotionEvent
import com.goa.ui.theme.ControlBrand
import com.goa.ui.theme.ControlBrandLight
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MODIFIER_SCANCODES = setOf(0x2A, 0x36, 0x1D, 0x38)

private val PC_KEY_ROWS: List<List<PcKeyDescriptor>> = listOf(
    // Row 0: Esc, F1-F12, Ins, Del, Home, End, PgUp, PgDn
    listOf(
        PcKeyDescriptor("Esc", 0x01, weight = 1.3f, category = KeyCategory.ACTION),
        PcKeyDescriptor("F1", 0x3B, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F2", 0x3C, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F3", 0x3D, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F4", 0x3E, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F5", 0x3F, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F6", 0x40, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F7", 0x41, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F8", 0x42, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F9", 0x43, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F10", 0x44, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F11", 0x57, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("F12", 0x58, category = KeyCategory.FUNCTION),
        PcKeyDescriptor("Ins", 0x52, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("Del", 0x53, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("Home", 0x47, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("End", 0x4F, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("PgUp", 0x49, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("PgDn", 0x51, category = KeyCategory.NAVIGATION)
    ),
    // Row 1: Numbers
    listOf(
        PcKeyDescriptor("`", 0x29, shiftedLabel = "~"),
        PcKeyDescriptor("1", 0x02, shiftedLabel = "!"),
        PcKeyDescriptor("2", 0x03, shiftedLabel = "@"),
        PcKeyDescriptor("3", 0x04, shiftedLabel = "#"),
        PcKeyDescriptor("4", 0x05, shiftedLabel = "$"),
        PcKeyDescriptor("5", 0x06, shiftedLabel = "%"),
        PcKeyDescriptor("6", 0x07, shiftedLabel = "^"),
        PcKeyDescriptor("7", 0x08, shiftedLabel = "&"),
        PcKeyDescriptor("8", 0x09, shiftedLabel = "*"),
        PcKeyDescriptor("9", 0x0A, shiftedLabel = "("),
        PcKeyDescriptor("0", 0x0B, shiftedLabel = ")"),
        PcKeyDescriptor("-", 0x0C, shiftedLabel = "_"),
        PcKeyDescriptor("=", 0x0D, shiftedLabel = "+"),
        PcKeyDescriptor("⌫", 0x0E, weight = 2.0f, category = KeyCategory.ACTION)
    ),
    // Row 2: Tab + QWERTY
    listOf(
        PcKeyDescriptor("Tab", 0x0F, weight = 1.5f, category = KeyCategory.ACTION),
        PcKeyDescriptor("q", 0x10), PcKeyDescriptor("w", 0x11), PcKeyDescriptor("e", 0x12),
        PcKeyDescriptor("r", 0x13), PcKeyDescriptor("t", 0x14), PcKeyDescriptor("y", 0x15),
        PcKeyDescriptor("u", 0x16), PcKeyDescriptor("i", 0x17), PcKeyDescriptor("o", 0x18),
        PcKeyDescriptor("p", 0x19),
        PcKeyDescriptor("[", 0x1A, shiftedLabel = "{"),
        PcKeyDescriptor("]", 0x1B, shiftedLabel = "}"),
        PcKeyDescriptor("\\", 0x2B, weight = 1.3f, shiftedLabel = "|")
    ),
    // Row 3: Caps + ASDF
    listOf(
        PcKeyDescriptor("Caps", 0x3A, weight = 1.75f, category = KeyCategory.ACTION),
        PcKeyDescriptor("a", 0x1E), PcKeyDescriptor("s", 0x1F), PcKeyDescriptor("d", 0x20),
        PcKeyDescriptor("f", 0x21), PcKeyDescriptor("g", 0x22), PcKeyDescriptor("h", 0x23),
        PcKeyDescriptor("j", 0x24), PcKeyDescriptor("k", 0x25), PcKeyDescriptor("l", 0x26),
        PcKeyDescriptor(";", 0x27, shiftedLabel = ":"),
        PcKeyDescriptor("'", 0x28, shiftedLabel = "\""),
        PcKeyDescriptor("Enter", 0x1C, weight = 2.25f, category = KeyCategory.ACTION)
    ),
    // Row 4: Shift + ZXCV
    listOf(
        PcKeyDescriptor("Shift", 0x2A, weight = 2.25f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("z", 0x2C), PcKeyDescriptor("x", 0x2D), PcKeyDescriptor("c", 0x2E),
        PcKeyDescriptor("v", 0x2F), PcKeyDescriptor("b", 0x30), PcKeyDescriptor("n", 0x31),
        PcKeyDescriptor("m", 0x32),
        PcKeyDescriptor(",", 0x33, shiftedLabel = "<"),
        PcKeyDescriptor(".", 0x34, shiftedLabel = ">"),
        PcKeyDescriptor("/", 0x35, shiftedLabel = "?"),
        PcKeyDescriptor("Shift", 0x36, weight = 1.75f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("↑", 0x48, category = KeyCategory.NAVIGATION)
    ),
    // Row 5: Ctrl, Alt, Space, Arrows
    listOf(
        PcKeyDescriptor("Ctrl", 0x1D, weight = 1.5f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("Alt", 0x38, weight = 1.25f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("Space", 0x39, weight = 6.0f, category = KeyCategory.ACTION),
        PcKeyDescriptor("Alt", 0x38, weight = 1.25f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("Ctrl", 0x1D, weight = 1.25f, category = KeyCategory.MODIFIER),
        PcKeyDescriptor("←", 0x4B, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("↓", 0x50, category = KeyCategory.NAVIGATION),
        PcKeyDescriptor("→", 0x4D, category = KeyCategory.NAVIGATION)
    )
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DosPcKeyboard(
    onSendKey: (dosScancode: Int, isDown: Boolean) -> Unit,
    onSwitchToJoypad: (() -> Unit)? = null,
    onSummonAndroidKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onPauseRequested: (() -> Unit)? = null,
    onSwitchToMouse: (() -> Unit)? = null
) {
    val mods = remember { mutableStateMapOf<Int, ModifierLatchState>() }
    var capsOn by remember { mutableStateOf(false) }

    val isShifted = (mods[0x2A] ?: ModifierLatchState.OFF) != ModifierLatchState.OFF ||
        (mods[0x36] ?: ModifierLatchState.OFF) != ModifierLatchState.OFF

    fun releaseOnceModifiers() {
        for ((code, state) in mods.toMap()) {
            if (state == ModifierLatchState.ONCE) {
                onSendKey(code, false)
                mods[code] = ModifierLatchState.OFF
            }
        }
    }

    fun press(desc: PcKeyDescriptor) {
        if (desc.scancode in MODIFIER_SCANCODES) {
            val current = mods[desc.scancode] ?: ModifierLatchState.OFF
            val nextState = current.next()
            mods[desc.scancode] = nextState
            when (nextState) {
                ModifierLatchState.OFF -> onSendKey(desc.scancode, false)
                ModifierLatchState.ONCE, ModifierLatchState.LOCKED -> onSendKey(desc.scancode, true)
            }
            return
        }
        onSendKey(desc.scancode, true)
    }

    fun release(desc: PcKeyDescriptor) {
        if (desc.scancode in MODIFIER_SCANCODES) return
        onSendKey(desc.scancode, false)
        if (desc.scancode == 0x3A) {
            capsOn = !capsOn
        }
        releaseOnceModifiers()
    }

    DisposableEffect(Unit) {
        onDispose {
            for ((code, state) in mods.toMap()) {
                if (state != ModifierLatchState.OFF) {
                    onSendKey(code, false)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0121316))
            .padding(4.dp)
    ) {
        val totalRows = PC_KEY_ROWS.size
        val availableHeight = maxHeight - 36.dp // Reserve top bar
        val rowHeight = (availableHeight / totalRows).coerceIn(20.dp, 44.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Options / Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onPauseRequested != null) {
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
                }

                com.goa.ui.menu.OneLineLabel(
                    text = "PC KEYBOARD",
                    hideIfTooLong = true,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    // Takes the space left over, so on a narrow screen the title gives way before any button shrinks
                    modifier = Modifier.weight(1f)
                )

                // Switch to Joypad
                if (onSwitchToJoypad != null) {
                    HeaderOptionButton(
                        text = "Joypad",
                        icon = Icons.Default.Gamepad,
                        onClick = onSwitchToJoypad
                    )
                }

                if (onSwitchToMouse != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    HeaderOptionButton(
                        text = "Mouse",
                        icon = Icons.Default.Mouse,
                        onClick = onSwitchToMouse
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Summon Android IME
                HeaderOptionButton(
                    text = "Android KB",
                    icon = Icons.Default.Keyboard,
                    onClick = onSummonAndroidKeyboard
                )

                if (onClose != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    HeaderOptionButton(
                        text = "Hide",
                        icon = Icons.Default.Close,
                        onClick = onClose
                    )
                }
            }

            // Keyboard rows
            PC_KEY_ROWS.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    row.forEach { desc ->
                        val state = mods[desc.scancode] ?: ModifierLatchState.OFF
                        val isLit = state != ModifierLatchState.OFF || (desc.scancode == 0x3A && capsOn)
                        val isLocked = state == ModifierLatchState.LOCKED

                        val displayLabel = when {
                            isShifted && desc.shiftedLabel != null -> desc.shiftedLabel
                            desc.label.length == 1 && desc.label[0].isLetter() && (isShifted xor capsOn) -> desc.label.uppercase()
                            else -> desc.label
                        }

                        KeyCap(
                            label = displayLabel,
                            weight = desc.weight,
                            category = desc.category,
                            isLit = isLit,
                            isLocked = isLocked,
                            onDown = { press(desc) },
                            onUp = { release(desc) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowScope.KeyCap(
    label: String,
    weight: Float,
    category: KeyCategory,
    isLit: Boolean,
    isLocked: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    val baseBg = when (category) {
        KeyCategory.ACTION -> Color(0xFF2A2D35)
        KeyCategory.MODIFIER -> Color(0xFF262A33)
        KeyCategory.FUNCTION -> Color(0xFF1E2129)
        KeyCategory.NAVIGATION -> Color(0xFF22262E)
        else -> Color(0xFF333742)
    }

    val targetBg = when {
        isPressed -> ControlBrandLight // Accent / Secondary color on press
        isLit -> if (isLocked) ControlBrand else ControlBrand.copy(alpha = 0.6f)
        else -> baseBg
    }

    val animatedBg by animateColorAsState(targetBg, tween(100), label = "keyBg")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(animatedBg, RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = if (isLit) ControlBrandLight else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp)
            )
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        onDown()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        onUp()
                        true
                    }
                    else -> false
                }
            }
    ) {
        com.goa.ui.menu.OneLineLabel(
            text = label,
            fontSize = if (label.length > 2) 10.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            minScale = 0.55f // "F10", "PgUp" and "Home" fit a narrow key instead of being cut off
        )
    }
}

@Composable
fun HeaderOptionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            com.goa.ui.menu.OneLineLabel(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

