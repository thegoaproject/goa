package com.goa.ui.controls

import androidx.activity.compose.BackHandler
import com.goa.ui.theme.ControlAccent
import com.goa.ui.theme.ControlAccentAlt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen

@Composable
fun JoypadRepositionScreen(
    currentPositions: JoypadCustomPositions,
    onSave: (JoypadCustomPositions) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onDismiss() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        val density = LocalDensity.current
        val isLandscape = maxWidth > maxHeight * 1.2f
        val pillarboxMargin = if (isLandscape) maxOf(0.dp, (maxWidth - maxHeight * (4f / 3f)) / 2) else 0.dp

        // Smart defaults (further up on screen and slightly inward)
        val defaultDpadStart = if (isLandscape) {
            if (pillarboxMargin > 160.dp) maxOf(24.dp, (pillarboxMargin - 150.dp) / 2) else 32.dp
        } else {
            48.dp
        }
        val defaultDpadTop = if (isLandscape) {
            maxOf(20.dp, (maxHeight - 150.dp) * 0.48f)
        } else {
            36.dp
        }
        val defaultButtonsEnd = defaultDpadStart
        val defaultButtonsTop = defaultDpadTop

        var scale by remember { mutableFloatStateOf(currentPositions.scale) }
        val controlSize = (150 * scale).dp

        var dpadStart by remember {
            mutableStateOf(currentPositions.dpadOffset?.offsetX ?: defaultDpadStart)
        }
        var dpadTop by remember {
            mutableStateOf(currentPositions.dpadOffset?.offsetY ?: defaultDpadTop)
        }
        var buttonsEnd by remember {
            mutableStateOf(currentPositions.buttonsOffset?.offsetX ?: defaultButtonsEnd)
        }
        var buttonsTop by remember {
            mutableStateOf(currentPositions.buttonsOffset?.offsetY ?: defaultButtonsTop)
        }

        // Clamp helper
        fun clampDpad(x: Dp, y: Dp) {
            val minX = 8.dp
            val maxX = maxOf(minX, maxWidth - controlSize - 8.dp)
            val minY = 8.dp
            val maxY = maxOf(minY, maxHeight - controlSize - 8.dp)
            dpadStart = x.coerceIn(minX, maxX)
            dpadTop = y.coerceIn(minY, maxY)
        }

        fun clampButtons(end: Dp, y: Dp) {
            val minEnd = 8.dp
            val maxEnd = maxOf(minEnd, maxWidth - controlSize - 8.dp)
            val minY = 8.dp
            val maxY = maxOf(minY, maxHeight - controlSize - 8.dp)
            buttonsEnd = end.coerceIn(minEnd, maxEnd)
            buttonsTop = y.coerceIn(minY, maxY)
        }

        // Top Toolbar HUD
        Surface(
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            color = Color(0xF0181A20),
            border = BorderStroke(1.dp, Color(0xFF3A3F4D)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "REPOSITION JOYPAD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = RetroAmber,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Touch and drag D-Pad and Buttons to reposition",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }

                // Scale Adjuster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    com.goa.ui.menu.OneLineLabel(
                        text = "Size:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        IconButton(
                            onClick = { scale = (scale - 0.05f).coerceAtLeast(0.75f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Smaller", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    com.goa.ui.menu.OneLineLabel(
                        text = "${(scale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.width(36.dp)
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        IconButton(
                            onClick = { scale = (scale + 0.05f).coerceAtMost(1.35f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Larger", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Actions: Reset, Cancel, Done
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            dpadStart = defaultDpadStart
                            dpadTop = defaultDpadTop
                            buttonsEnd = defaultButtonsEnd
                            buttonsTop = defaultButtonsTop
                            scale = 1.0f
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        com.goa.ui.menu.OneLineLabel("Reset", fontSize = 12.sp)
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White.copy(alpha = 0.7f))
                    }

                    Button(
                        onClick = {
                            onSave(
                                JoypadCustomPositions(
                                    dpadOffset = ControlOffset(dpadStart, dpadTop),
                                    buttonsOffset = ControlOffset(buttonsEnd, buttonsTop),
                                    scale = scale
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RetroGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        com.goa.ui.menu.OneLineLabel("Save & Apply", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Draggable D-Pad with Visual Frame
        Box(
            modifier = Modifier
                .offset(x = dpadStart, y = dpadTop)
                .size(controlSize)
                .clip(CircleShape)
                .border(2.dp, ControlAccentAlt, CircleShape)
                .background(ControlAccentAlt.copy(alpha = 0.2f))
                .pointerInput(controlSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp() }
                        val dyDp = with(density) { dragAmount.y.toDp() }
                        clampDpad(dpadStart + dxDp, dpadTop + dyDp)
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.85f)) {
                GravisDpad(modifier = Modifier.fillMaxSize())
            }
            // Drag indicator badge in center
            Surface(
                shape = CircleShape,
                color = Color(0xCC000000),
                border = BorderStroke(1.dp, ControlAccentAlt),
                modifier = Modifier
                    .size(42.dp)
                    .align(Alignment.Center)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.OpenWith,
                        contentDescription = "Drag D-Pad",
                        tint = ControlAccentAlt,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Draggable Action Buttons with Visual Frame
        val buttonsX = maxWidth - controlSize - buttonsEnd
        Box(
            modifier = Modifier
                .offset(x = buttonsX, y = buttonsTop)
                .size(controlSize)
                .clip(CircleShape)
                .border(2.dp, ControlAccent, CircleShape)
                .background(ControlAccent.copy(alpha = 0.2f))
                .pointerInput(controlSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp() }
                        val dyDp = with(density) { dragAmount.y.toDp() }
                        clampButtons(buttonsEnd - dxDp, buttonsTop + dyDp)
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.85f)) {
                GravisActionCluster(modifier = Modifier.fillMaxSize())
            }
            // Drag indicator badge in center
            Surface(
                shape = CircleShape,
                color = Color(0xCC000000),
                border = BorderStroke(1.dp, ControlAccent),
                modifier = Modifier
                    .size(42.dp)
                    .align(Alignment.Center)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.OpenWith,
                        contentDescription = "Drag Action Buttons",
                        tint = ControlAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

