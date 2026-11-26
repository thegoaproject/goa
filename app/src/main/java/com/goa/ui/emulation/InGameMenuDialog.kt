package com.goa.ui.emulation

import kotlin.math.roundToInt
import com.goa.data.db.ChangeOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.engine.input.ControllerAction
import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.GamepadPreferences
import com.goa.engine.input.StickMode
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InGameMenuDialog(
    currentPreset: Int,
    isTouchOverlayEnabled: Boolean,
    isGyroEnabled: Boolean,
    gyroSensitivityX: Float = 25.0f,
    gyroSensitivityY: Float = 5.0f,
    isInvertY: Boolean = false,
    stickMode: Int = 0,
    gameId: Long? = null,
    onToggleStickMode: ((Int) -> Unit)? = null,
    onMappingsChanged: (() -> Unit)? = null,
    onSelectPreset: (Int) -> Unit,
    // Debug builds only (specs/008-shader-filters proof): filters from the new engine, by id; empty hides the row
    debugFilterIds: List<String> = emptyList(),
    debugFilterId: String? = null,
    onSelectDebugFilter: (String) -> Unit = {},
    onToggleTouchOverlay: (Boolean) -> Unit,
    onToggleGyro: (Boolean) -> Unit,
    onGyroSensitivityXChange: (Float) -> Unit = {},
    onGyroSensitivityYChange: (Float) -> Unit = {},
    onToggleInvertY: (Boolean) -> Unit = {},
    mouseSensitivity: Float = 1.0f,
    onMouseSensitivityChange: (Float) -> Unit = {},
    // Mouse-aim (Build) games only; null hides the slider
    stickLookSpeed: Float? = null,
    onStickLookSpeedChange: (Float) -> Unit = {},
    // Shooters only; null hides the slider
    lookDeadzone: Float? = null,
    onLookDeadzoneChange: (Float) -> Unit = {},
    onRepositionJoypad: () -> Unit = {},
    isPerfOverlayEnabled: Boolean = false,
    onTogglePerfOverlay: (Boolean) -> Unit = {},
    onControlsOpened: () -> Unit = {},
    /** The game's engine: only DOSBox has save states; the others show how their game saves. */
    saveEngine: com.goa.domain.savestate.SaveEngine = com.goa.domain.savestate.SaveEngine.DOSBOX,
    // A native port's name (TyrQuake, OpenLara): it reads the pad itself, so the DOS stick modes and remapping are hidden
    nativeEngineName: String? = null,
    onSaveState: () -> Boolean,
    onLoadState: () -> Boolean,
    /** Whether the game has a save state to load: tells "none yet" apart from one the engine rejected. */
    hasSaveState: () -> Boolean = { true },
    /** Why the save state can't be loaded (damaged, not a save state), checked before Load Slot; null when fine. */
    saveStateProblem: () -> String? = { null },
    /** When the previous save state (kept by a Drive download or import) was made; null when there is none. */
    previousSavedAt: () -> Long? = { null },
    /** Swaps the save state and the previous one: true when it did. */
    onRestorePrevious: () -> Boolean = { false },
    /** Bumped when the save state files changed from outside the menu (a Drive download). */
    saveStateRevision: Int = 0,
    /** The "Download save from Google Drive" row (GOA Pro, GOA only); null hides it. */
    driveRow: (@Composable () -> Unit)? = null,
    onResume: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val presetNames = listOf("Clean Modern", "Sharp Retro", "Classic CRT", "Stretch")
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // A Drive download replaced the save state: a message about the old one no longer applies
    LaunchedEffect(saveStateRevision) { if (saveStateRevision > 0) statusMessage = null }
    var showRemapSheet by remember { mutableStateOf(false) }
    var editingButton by remember { mutableStateOf<GamepadButton?>(null) }
    var mappings by remember(gameId) {
        mutableStateOf(GamepadPreferences.loadMappings(context, gameId))
    }

    if (showRemapSheet) {
        AlertDialog(
            onDismissRequest = { showRemapSheet = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Remap Controller Buttons",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RetroAmber
                    )
                    TextButton(
                        onClick = {
                            GamepadPreferences.resetMappings(context, gameId)
                            mappings = GamepadPreferences.loadMappings(context, gameId)
                            onMappingsChanged?.invoke()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        com.goa.ui.menu.OneLineLabel("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(GamepadButton.values().toList()) { button ->
                        val action = mappings[button] ?: button.defaultAction
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingButton = button },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = button.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = action.defaultKeyLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = action.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showRemapSheet = false }) {
                    com.goa.ui.menu.OneLineLabel("Done")
                }
            }
        )
    }

    if (editingButton != null) {
        val btn = editingButton!!
        val currentAction = mappings[btn] ?: btn.defaultAction
        AlertDialog(
            onDismissRequest = { editingButton = null },
            title = {
                Text(
                    text = "Assign ${btn.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(ControllerActions.allActions) { action ->
                        val selected = action.id == currentAction.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    GamepadPreferences.saveUserMapping(context, gameId, btn, action, ChangeOrigin.USER_IN_GAME)
                                    mappings = GamepadPreferences.loadMappings(context, gameId)
                                    onMappingsChanged?.invoke()
                                    editingButton = null
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    GamepadPreferences.saveUserMapping(context, gameId, btn, action, ChangeOrigin.USER_IN_GAME)
                                    mappings = GamepadPreferences.loadMappings(context, gameId)
                                    onMappingsChanged?.invoke()
                                    editingButton = null
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = action.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = action.defaultKeyLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingButton = null }) {
                    com.goa.ui.menu.OneLineLabel("Cancel")
                }
            }
        )
    }

    PinnedActionsDialog(
        onDismissRequest = onResume,
        title = {
            Text(
                text = "GAME PAUSED",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = RetroAmber
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Save / Load State Section
                Column {
                    Text(
                        text = "Save States",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!saveEngine.hasSaveStates) {
                        Text(
                            text = saveEngine.note.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val ok = onSaveState()
                                    statusMessage = if (ok) "Save state created successfully!" else "Failed to save state."
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                com.goa.ui.menu.OneLineLabel("Save Slot")
                            }
                            Button(
                                onClick = {
                                    val problem = saveStateProblem()
                                    val ok = problem == null && onLoadState()
                                    statusMessage = when {
                                        problem != null -> problem
                                        ok -> "Save state loaded successfully!"
                                        hasSaveState() -> "This save state could not be loaded; your game is unchanged."
                                        else -> "No saved state found."
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                com.goa.ui.menu.OneLineLabel("Load Slot")
                            }
                        }

                        if (driveRow != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            driveRow()
                        }

                        // A Drive download or import replaced the save state: the one before can be put back
                        var restoreRevision by remember { mutableStateOf(0) }
                        val previousAt = remember(saveStateRevision, restoreRevision) { previousSavedAt() }
                        var confirmRestore by remember { mutableStateOf(false) }
                        if (previousAt != null) {
                            TextButton(onClick = { confirmRestore = true }) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                com.goa.ui.menu.OneLineLabel("Restore previous save state")
                            }
                        }
                        if (confirmRestore && previousAt != null) {
                            AlertDialog(
                                onDismissRequest = { confirmRestore = false },
                                text = {
                                    Text("Swap back to the save state from ${com.goa.ui.savestate.SaveStateFormat.date(previousAt)}?")
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmRestore = false
                                        statusMessage = if (onRestorePrevious()) {
                                            "Previous save state restored. Load Slot to play from it."
                                        } else "Couldn't restore the previous save state."
                                        restoreRevision++
                                    }) { com.goa.ui.menu.OneLineLabel("Swap") }
                                },
                                dismissButton = { TextButton(onClick = { confirmRestore = false }) { com.goa.ui.menu.OneLineLabel("Cancel") } }
                            )
                        }

                        if (statusMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = statusMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = RetroGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // CRT / Visual Shaders Section
                Column {
                    Text(
                        text = "Visual Shader Preset",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetNames.forEachIndexed { index, name ->
                            FilterChip(
                                selected = debugFilterId == null && currentPreset == index,
                                onClick = { onSelectPreset(index) },
                                label = { com.goa.ui.menu.OneLineLabel(name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    if (debugFilterIds.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "New filters (debug build)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            debugFilterIds.forEach { id ->
                                FilterChip(
                                    selected = debugFilterId == id,
                                    onClick = { onSelectDebugFilter(id) },
                                    label = { com.goa.ui.menu.OneLineLabel(id, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                // Controls Toggles & Gyro Options
                Column {
                    Text(
                        text = "Controls & Input",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (nativeEngineName != null) {
                        Text(
                            text = "$nativeEngineName reads your controller directly. Its button layout is on this " +
                                "game's Controls tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (onToggleStickMode != null && nativeEngineName == null) {
                        Text(
                            text = "Analog Stick Mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = stickMode == 0,
                                onClick = { onToggleStickMode(0) },
                                label = { com.goa.ui.menu.OneLineLabel("Dual-Stick FPS", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == 1,
                                onClick = { onToggleStickMode(1) },
                                label = { com.goa.ui.menu.OneLineLabel("Classic Keyboard", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == 2,
                                onClick = { onToggleStickMode(2) },
                                label = { com.goa.ui.menu.OneLineLabel("Gameport (201h)", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == 4,
                                onClick = { onToggleStickMode(4) },
                                label = { com.goa.ui.menu.OneLineLabel("Keys + Pointer", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (nativeEngineName == null) {
                        OutlinedButton(
                            onClick = {
                                showRemapSheet = true
                                onControlsOpened()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            com.goa.ui.menu.OneLineLabel("Remap Controller Buttons")
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Performance Overlay (fps / audio)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isPerfOverlayEnabled,
                            onCheckedChange = onTogglePerfOverlay
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "On-Screen Touch HUD",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isTouchOverlayEnabled,
                            onCheckedChange = onToggleTouchOverlay
                        )
                    }

                    if (isTouchOverlayEnabled) {
                        OutlinedButton(
                            onClick = onRepositionJoypad,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            com.goa.ui.menu.OneLineLabel("Reposition Virtual Joypad")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Gyroscope Motion Look",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isGyroEnabled,
                            onCheckedChange = onToggleGyro
                        )
                    }

                    if (isGyroEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Invert Pitch (Y-Axis)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = isInvertY,
                                onCheckedChange = onToggleInvertY
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Horizontal Sensitivity (Yaw / X)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${gyroSensitivityX.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = gyroSensitivityX,
                            onValueChange = onGyroSensitivityXChange,
                            valueRange = 5.0f..60.0f
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vertical Sensitivity (Pitch / Y)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${gyroSensitivityY.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = gyroSensitivityY,
                            onValueChange = onGyroSensitivityYChange,
                            valueRange = 1.0f..30.0f
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (stickLookSpeed != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Right Stick Look Up/Down Speed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format("%.1fx", stickLookSpeed),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = stickLookSpeed,
                            onValueChange = onStickLookSpeedChange,
                            valueRange = 0.25f..4.0f
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (lookDeadzone != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vertical Look Deadzone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(lookDeadzone * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = lookDeadzone,
                            onValueChange = onLookDeadzoneChange,
                            valueRange = 0f..com.goa.engine.input.LookDeadzone.MAX
                        )
                        Text(
                            text = "How far the right stick or the phone must move up or down before the view does",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mouse / Stylus Speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("%.1fx", mouseSensitivity),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = mouseSensitivity,
                        onValueChange = onMouseSensitivityChange,
                        valueRange = 0.5f..3.0f
                    )
                }
            }
        },
        actions = {
            // Pinned below the options, one line each: the labels shrink to fit rather than wrap
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                com.goa.ui.controls.FitText(
                    text = "Exit to Launcher",
                    color = LocalContentColor.current,
                    maxSize = 14.sp,
                    minSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                    dropWords = false
                )
            }
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RetroGreen, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                com.goa.ui.controls.FitText(
                    text = "Resume",
                    color = LocalContentColor.current,
                    maxSize = 14.sp,
                    minSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                    dropWords = false
                )
            }
        }
    )
}
