package com.goa.ui.detail

import kotlin.math.roundToInt
import com.goa.data.db.ChangeOrigin
import android.content.Context
import android.hardware.input.InputManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goa.engine.input.ControllerAction
import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.GamepadMappings
import com.goa.engine.input.GamepadPreferences
import com.goa.engine.input.StickMode
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberGamepadConnected(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var connected by remember { mutableStateOf(GamepadMappings.hasConnectedGamepad()) }

    DisposableEffect(context, lifecycleOwner) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val update = { connected = GamepadMappings.hasConnectedGamepad() }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { update() }
            override fun onInputDeviceRemoved(deviceId: Int) { update() }
            override fun onInputDeviceChanged(deviceId: Int) { update() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                update()
            }
        }
        im.registerInputDeviceListener(listener, null)
        lifecycleOwner.lifecycle.addObserver(observer)
        update()
        onDispose {
            im.unregisterInputDeviceListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return connected
}

@Composable
fun rememberGamepadName(): String? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var name by remember { mutableStateOf(GamepadMappings.getConnectedGamepadName()) }

    DisposableEffect(context, lifecycleOwner) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val update = { name = GamepadMappings.getConnectedGamepadName() }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { update() }
            override fun onInputDeviceRemoved(deviceId: Int) { update() }
            override fun onInputDeviceChanged(deviceId: Int) { update() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                update()
            }
        }
        im.registerInputDeviceListener(listener, null)
        lifecycleOwner.lifecycle.addObserver(observer)
        update()
        onDispose {
            im.unregisterInputDeviceListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return name
}

private const val MIN_SCAN_FEEDBACK_MS = 700L

private data class ScanResult(val success: Boolean, val message: String)

/** A re-scan result held back for the player's decision because they have their own mappings. */
private data class RescanCompare(
    val suggested: com.goa.domain.controls.ResolvedControlScheme,
    val yourMode: StickMode,
    val yourMappings: Map<GamepadButton, com.goa.engine.input.ControllerAction>
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ControlsTab(
    isGamepadConnected: Boolean,
    gamepadName: String? = null,
    gameId: Long? = null,
    gameTitle: String? = null,
    gameSlug: String? = null,
    installPath: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("goa_ui_state", android.content.Context.MODE_PRIVATE) }
    var touchOverlayEnabled by remember { mutableStateOf(true) }
    var touchOpacity by remember { mutableFloatStateOf(0.7f) }
    var gyroAimingEnabled by remember { mutableStateOf(true) }
    var gyroSensitivityX by remember { mutableFloatStateOf(uiPrefs.getFloat("gyro_sens_x", 25.0f)) }
    var gyroSensitivityY by remember { mutableFloatStateOf(uiPrefs.getFloat("gyro_sens_y", 5.0f)) }
    var stickLookSpeed by remember {
        mutableFloatStateOf(uiPrefs.getFloat(com.goa.ui.emulation.EmulationActivity.STICK_LOOK_SPEED_PREF, 1.0f))
    }
    var invertY by remember { mutableStateOf(false) }
    var lookDeadzone by remember { mutableFloatStateOf(com.goa.engine.input.LookDeadzone.load(context)) }

    // Persistent in-game controller mappings
    var mappings by remember(gameId) {
        if (gameId != null && !GamepadPreferences.hasUserCustomizedControls(context, gameId)) {
            val installDir = installPath?.let { java.io.File(it) } ?: java.io.File(context.filesDir, "games/$gameId")
            com.goa.domain.controls.ControlSchemeResolver(context).resolveAndApplyScheme(
                gameId = gameId,
                slug = gameSlug,
                title = gameTitle,
                installDir = installDir,
                overwriteUser = false
            )
        }
        mutableStateOf(GamepadPreferences.loadMappings(context, gameId))
    }
    var stickMode by remember(gameId) {
        mutableStateOf(GamepadPreferences.getStickMode(context, gameId))
    }
    var sourceTag by remember(gameId) {
        mutableStateOf(GamepadPreferences.getSourceTag(context, gameId))
    }

    val prefs = remember(context) { GamepadPreferences.getPrefs(context) }
    val prefListener = remember(prefs, gameId) {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.contains("${gameId ?: ""}") || key.contains("global"))) {
                mappings = GamepadPreferences.loadMappings(context, gameId)
                stickMode = GamepadPreferences.getStickMode(context, gameId)
                sourceTag = GamepadPreferences.getSourceTag(context, gameId)
            }
        }
    }

    DisposableEffect(prefs, prefListener) {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        mappings = GamepadPreferences.loadMappings(context, gameId)
        stickMode = GamepadPreferences.getStickMode(context, gameId)
        sourceTag = GamepadPreferences.getSourceTag(context, gameId)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(gameId) {
        mappings = GamepadPreferences.loadMappings(context, gameId)
        stickMode = GamepadPreferences.getStickMode(context, gameId)
        sourceTag = GamepadPreferences.getSourceTag(context, gameId)
        onPauseOrDispose { }
    }

    val scope = rememberCoroutineScope()
    var isScanning by remember(gameId) { mutableStateOf(false) }
    var scanResult by remember(gameId) { mutableStateOf<ScanResult?>(null) }

    var editingButton by remember { mutableStateOf<GamepadButton?>(null) }
    var rescanCompare by remember(gameId) { mutableStateOf<RescanCompare?>(null) }
    // The badge reads "Custom" as soon as the player has changed anything (also for games customised before badges existed)
    val isCustom = remember(mappings, stickMode, sourceTag, gameId) { GamepadPreferences.hasUserCustomizedControls(context, gameId) }
    // Something to reset: the player's own changes (a game GOA set to keyboard is not "custom")
    val hasCustom = remember(mappings, stickMode, gameId, isCustom) {
        GamepadPreferences.hasCustomMappings(context, gameId) || isCustom ||
            (gameId == null && stickMode != StickMode.FPS_DUAL_STICK)
    }

    // Games with a native port (Quake, Tomb Raider): the port reads the pad itself, so with it chosen the tab shows the
    // port's own layout instead of DOS key mappings
    val nativePort by androidx.compose.runtime.produceState<com.goa.domain.nativeport.NativeEngine.Port?>(null, installPath) {
        value = installPath?.let { path ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.goa.domain.nativeport.NativeEngine.portFor(java.io.File(path))
            }
        }
    }
    var engineChoice by remember(gameId) {
        mutableStateOf(gameId?.let { com.goa.domain.nativeport.NativeEngine.choice(context, it) })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Physical Gamepad Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (isGamepadConnected) RetroGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isGamepadConnected) RetroGreen else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Gamepad,
                            contentDescription = null,
                            tint = if (isGamepadConnected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = if (isGamepadConnected) "Physical Controller Connected" else "No Gamepad Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isGamepadConnected) {
                            val name = gamepadName ?: "Bluetooth / USB Gamepad"
                            "$name • Auto-mapped. Touch HUD automatically hidden."
                        } else {
                            "Touch HUD will automatically be active during gameplay."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val port = nativePort
        if (port != null && gameId != null) {
            NativeEngineCard(gameId, port, onChange = { engineChoice = it })
            if (engineChoice == com.goa.domain.nativeport.NativeEngine.Choice.NATIVE) {
                when (port) {
                    com.goa.domain.nativeport.NativeEngine.Port.OPENLARA -> OpenLaraControlsCard(gameId)
                    com.goa.domain.nativeport.NativeEngine.Port.TYRQUAKE -> QuakeControlsCard()
                }
                return@Column  // the DOS mapping editor below doesn't apply to a native port
            }
        }

        // Stored In-Game Controller Mappings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "In-Game Controller Mappings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (gameTitle != null) "Stored mappings for $gameTitle" else "Active controller mappings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (hasCustom) {
                        TextButton(
                            onClick = {
                                // Back to what GOA chooses for this game (registry profile, engine, genre)
                                scope.launch {
                                    if (gameId != null) {
                                        resetControlsToGoa(context, gameId)
                                    } else {
                                        GamepadPreferences.resetMappings(context, gameId)
                                        GamepadPreferences.setStickMode(context, gameId, StickMode.FPS_DUAL_STICK)
                                    }
                                    mappings = GamepadPreferences.loadMappings(context, gameId)
                                    stickMode = GamepadPreferences.getStickMode(context, gameId)
                                    sourceTag = GamepadPreferences.getSourceTag(context, gameId)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            com.goa.ui.menu.OneLineLabel("Reset to GOA's controls", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Analog Stick Interpretation Mode Selector
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Gamepad, contentDescription = null, tint = RetroAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Analog Stick Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = stickMode == StickMode.FPS_DUAL_STICK,
                                onClick = {
                                    stickMode = StickMode.FPS_DUAL_STICK
                                    GamepadPreferences.setUserStickMode(context, gameId, StickMode.FPS_DUAL_STICK, ChangeOrigin.USER_GAME_PAGE)
                                },
                                label = { com.goa.ui.menu.OneLineLabel("Dual-Stick FPS", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == StickMode.CLASSIC_KEYBOARD,
                                onClick = {
                                    stickMode = StickMode.CLASSIC_KEYBOARD
                                    GamepadPreferences.setUserStickMode(context, gameId, StickMode.CLASSIC_KEYBOARD, ChangeOrigin.USER_GAME_PAGE)
                                },
                                label = { com.goa.ui.menu.OneLineLabel("Classic Keyboard", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == StickMode.NATIVE_GAMEPORT,
                                onClick = {
                                    stickMode = StickMode.NATIVE_GAMEPORT
                                    GamepadPreferences.setUserStickMode(context, gameId, StickMode.NATIVE_GAMEPORT, ChangeOrigin.USER_GAME_PAGE)
                                },
                                label = { com.goa.ui.menu.OneLineLabel("Gameport (201h)", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == StickMode.VIRTUAL_MOUSE,
                                onClick = {
                                    stickMode = StickMode.VIRTUAL_MOUSE
                                    GamepadPreferences.setUserStickMode(context, gameId, StickMode.VIRTUAL_MOUSE, ChangeOrigin.USER_GAME_PAGE)
                                },
                                label = { com.goa.ui.menu.OneLineLabel("Virtual Mouse", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = stickMode == StickMode.KEYS_POINTER,
                                onClick = {
                                    stickMode = StickMode.KEYS_POINTER
                                    GamepadPreferences.setUserStickMode(context, gameId, StickMode.KEYS_POINTER, ChangeOrigin.USER_GAME_PAGE)
                                },
                                label = { com.goa.ui.menu.OneLineLabel("Keys + Pointer", style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        Text(
                            text = stickMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )

                        if (!sourceTag.isNullOrBlank() || isCustom) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Control Scheme Origin",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = RetroAmber,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val parsed = if (isCustom) com.goa.domain.controls.ControlsTier.CUSTOM to "Tap Re-scan to compare with what GOA would pick."
                                        else com.goa.domain.controls.ControlsTier.parseTag(sourceTag)
                                        if (parsed != null) {
                                            val (tier, detail) = parsed
                                            Text(
                                                text = tier.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = when (tier) {
                                                    com.goa.domain.controls.ControlsTier.EXACT, com.goa.domain.controls.ControlsTier.CUSTOM -> com.goa.ui.theme.RetroGreen
                                                    com.goa.domain.controls.ControlsTier.GUESS -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    else -> RetroAmber
                                                }
                                            )
                                            Text(
                                                text = "${tier.blurb}. $detail",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        } else {
                                            Text(
                                                text = sourceTag ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    if (gameId != null) {
                                        TextButton(
                                            enabled = !isScanning,
                                            onClick = {
                                                isScanning = true
                                                scanResult = null
                                                val before = mappings to stickMode
                                                if (isCustom) {
                                                    // The player's own mappings: show what a re-scan would pick beside them and let them choose
                                                    scope.launch {
                                                        val suggested = withContext(Dispatchers.IO) {
                                                            runCatching {
                                                                com.goa.domain.controls.ControlSchemeResolver(context).previewScheme(
                                                                    gameId, gameSlug, gameTitle,
                                                                    installPath?.let { java.io.File(it) } ?: java.io.File(context.filesDir, "games/$gameId")
                                                                )
                                                            }.getOrNull()
                                                        }
                                                        isScanning = false
                                                        if (suggested == null) scanResult = ScanResult(false, "Scan failed")
                                                        else if (suggested.stickMode == stickMode && GamepadButton.values().all { suggested.suggestedBindings[it] == mappings[it] })
                                                            scanResult = ScanResult(true, "A re-scan picks the same controls you already have.")
                                                        else rescanCompare = RescanCompare(suggested, stickMode, mappings)
                                                    }
                                                    return@TextButton
                                                }
                                                scope.launch {
                                                    val started = System.currentTimeMillis()
                                                    val scheme = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val installDir = installPath?.let { java.io.File(it) } ?: java.io.File(context.filesDir, "games/$gameId")
                                                            com.goa.domain.controls.ControlSchemeResolver(context).resolveAndApplyScheme(
                                                                gameId = gameId,
                                                                slug = gameSlug,
                                                                title = gameTitle,
                                                                installDir = installDir,
                                                                overwriteUser = false
                                                            )
                                                        }
                                                    }
                                                    // Keep the spinner up long enough to register; the scan itself is near-instant
                                                    delay((MIN_SCAN_FEEDBACK_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                                                    mappings = GamepadPreferences.loadMappings(context, gameId)
                                                    stickMode = GamepadPreferences.getStickMode(context, gameId)
                                                    sourceTag = GamepadPreferences.getSourceTag(context, gameId)
                                                    scanResult = scheme.fold(
                                                        onSuccess = {
                                                            val changed = (mappings to stickMode) != before
                                                            ScanResult(
                                                                success = true,
                                                                message = when {
                                                                    it.source == "USER_CUSTOMIZED" ->
                                                                        "You've customised these controls, so Re-scan left them alone. Tap Reset to let GOA choose again."
                                                                    it.source == com.goa.domain.controls.KEPT_SOURCE -> it.summary
                                                                    else -> "${if (changed) "Controls updated" else "Scan complete, no changes"} • " +
                                                                        "${it.tier.label}: ${it.stickMode.title}"
                                                                }
                                                            )
                                                        },
                                                        onFailure = { ScanResult(success = false, message = "Scan failed: ${it.message}") }
                                                    )
                                                    isScanning = false
                                                }
                                            }
                                        ) {
                                            if (isScanning) {
                                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                com.goa.ui.menu.OneLineLabel("Scanning…", style = MaterialTheme.typography.labelSmall)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                com.goa.ui.menu.OneLineLabel("Re-scan", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isScanning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = RetroAmber)
                        }

                        scanResult?.let { result ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (result.success) Icons.Default.Check else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (result.success) RetroGreen else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                val gameportMode = stickMode == StickMode.NATIVE_GAMEPORT
                Text(
                    text = if (gameportMode) {
                        "Gameport mode sends your buttons to the game as a real PC joystick (face buttons are joystick buttons 1-4), " +
                            "so there is nothing to remap here. Choose \"Joystick\" in the game's own controls menu. " +
                            "If the game only reads the keyboard, switch the stick mode back to Classic Keyboard."
                    } else "Tap any button mapping to customize its action in DOS emulation:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // List of configurable buttons
                (if (gameportMode) emptyList() else GamepadButton.values().toList()).forEach { btn ->
                    val action = mappings[btn] ?: ControllerActions.ENTER_SPACE
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingButton = btn }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(0.44f)) {
                                Text(
                                    text = btn.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = RetroAmber
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = action.defaultKeyLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(0.56f)) {
                                Text(
                                    // the game's own name for it when its profile gave one ("Jump" on Ctrl, not "Fire")
                                    text = GamepadPreferences.gameLabel(context, gameId, btn, action) ?: action.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = action.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Fixed Analog Sticks & Navigation info
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Analog & System Navigation (Fixed):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val fixedBindings = when (stickMode) {
                    StickMode.CLASSIC_KEYBOARD -> listOf(
                        "Left Stick" to "Arrow Keys (Up/Down/Left/Right)",
                        "Right Stick" to "Arrow Keys (Secondary Up/Down/Left/Right)",
                        "Start + Select / Guide" to "Goa In-Game Pause Menu"
                    )
                    StickMode.NATIVE_GAMEPORT -> listOf(
                        "Left Stick" to "IBM PC Gameport Port 201h (Axis X1 / Y1)",
                        "Right Stick" to "IBM PC Gameport Port 201h (Axis X2 / Y2 / Throttle)",
                        "Start + Select / Guide" to "Goa In-Game Pause Menu"
                    )
                    StickMode.FPS_DUAL_STICK -> listOf(
                        "Left Stick" to "Move / Strafe (Up/Down + Comma/Period)",
                        "Right Stick" to "Turn / Look (Analog Mouse Yaw)",
                        "Start + Select / Guide" to "Goa In-Game Pause Menu"
                    )
                    StickMode.VIRTUAL_MOUSE -> listOf(
                        "Left Stick" to "Curved Accelerated Mouse Pointer",
                        "Right Stick" to "Micro-Precision Pointer Targeting",
                        "Start + Select / Guide" to "Goa In-Game Pause Menu"
                    )
                    StickMode.KEYS_POINTER -> listOf(
                        "Left Stick" to "Arrow Keys (Walk / Turn)",
                        "Right Stick" to "Mouse Pointer (Aim / Use / Pick Up)",
                        "Start + Select / Guide" to "Goa In-Game Pause Menu"
                    )
                }

                fixedBindings.forEach { (btn, act) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = btn,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.42f)
                        )
                        Text(
                            text = act,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.58f)
                        )
                    }
                }
            }
        }

        // On-Screen Touch HUD Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = RetroAmber)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Virtual Touch HUD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(checked = touchOverlayEnabled, onCheckedChange = { touchOverlayEnabled = it })
                }

                if (touchOverlayEnabled) {
                    Text(
                        text = "Touch Controls Opacity: ${(touchOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = touchOpacity,
                        onValueChange = { touchOpacity = it },
                        valueRange = 0.2f..1.0f
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Virtual Joystick (Xbox ABXY) Default Key Mappings:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val virtualHudButtons = listOf(
                        Triple("Button A (South • Green)", "Enter (0x1C) • Space (0x39)", "Select / Open Doors & Use"),
                        Triple("Button B (East • Red)", "Esc (0x01) • 'Z' (0x2C)", "Back & Cancel / Crouch"),
                        Triple("Button X (West • Blue)", "'A' (0x1E) • '/' (0x35) • Shift (0x2A)", "Jump (Build/Hexen) / Sprint"),
                        Triple("Button Y (North • Yellow)", "Keys '1'..'7' • ']' (0x1B)", "Next Weapon / Cycle Slots"),
                        Triple("L1 Bumper (Top-Left)", "Space (0x39)", "Use / Open Doors & Switches"),
                        Triple("R1 Trigger (Top-Right)", "Left Ctrl (0x1D)", "Primary Fire / Shoot"),
                        Triple("MAP (Center-Left)", "Tab (0x0F)", "Overhead Automap"),
                        Triple("ESC (Center-Right)", "Esc (0x01)", "In-Game DOS Menu")
                    )

                    virtualHudButtons.forEach { (btn, keys, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(0.44f)) {
                                Text(
                                    text = btn,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = keys,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = RetroAmber,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Gyroscope Motion Aiming Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = null, tint = RetroAmber)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Gyroscope Motion Look",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(checked = gyroAimingEnabled, onCheckedChange = { gyroAimingEnabled = it })
                }

                if (gyroAimingEnabled) {
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
                        onValueChange = {
                            gyroSensitivityX = it
                            uiPrefs.edit().putFloat("gyro_sens_x", it).apply()
                        },
                        valueRange = 5.0f..60.0f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                        onValueChange = {
                            gyroSensitivityY = it
                            uiPrefs.edit().putFloat("gyro_sens_y", it).apply()
                        },
                        valueRange = 1.0f..30.0f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                        onValueChange = {
                            stickLookSpeed = it
                            uiPrefs.edit().putFloat(com.goa.ui.emulation.EmulationActivity.STICK_LOOK_SPEED_PREF, it).apply()
                        },
                        valueRange = 0.25f..4.0f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                        onValueChange = {
                            lookDeadzone = it
                            com.goa.engine.input.LookDeadzone.save(context, it)
                        },
                        valueRange = 0f..com.goa.engine.input.LookDeadzone.MAX
                    )
                    Text(
                        text = "How far the right stick or the phone must move up or down before the view does. " +
                            "Old shooters rarely need to look up, so a little keeps the view steady while you turn.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Invert Vertical Look (Pitch)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(checked = invertY, onCheckedChange = { invertY = it })
                    }
                }
            }
        }
    }

    // Modal Dialog to edit mapping for selected button
    rescanCompare?.let { compare ->
        AlertDialog(
            onDismissRequest = { rescanCompare = null },
            title = { Text("Use GOA's controls?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${compare.suggested.tier.label}: ${compare.suggested.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("", Modifier.weight(0.3f))
                        Text("Yours", Modifier.weight(0.35f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("GOA's", Modifier.weight(0.35f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                    if (compare.yourMode != compare.suggested.stickMode) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Sticks", Modifier.weight(0.3f), style = MaterialTheme.typography.labelSmall)
                            Text(compare.yourMode.title.substringBefore(" ("), Modifier.weight(0.35f), style = MaterialTheme.typography.labelSmall, color = RetroAmber)
                            Text(compare.suggested.stickMode.title.substringBefore(" ("), Modifier.weight(0.35f), style = MaterialTheme.typography.labelSmall, color = RetroAmber)
                        }
                    }
                    GamepadButton.values().forEach { btn ->
                        val yours = compare.yourMappings[btn]?.name ?: "-"
                        val goas = compare.suggested.suggestedBindings[btn]?.name ?: "-"
                        val differs = yours != goas
                        Row(Modifier.fillMaxWidth()) {
                            Text(btn.label.substringBefore(" ("), Modifier.weight(0.3f), style = MaterialTheme.typography.labelSmall)
                            Text(yours, Modifier.weight(0.35f), style = MaterialTheme.typography.labelSmall, color = if (differs) RetroAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(goas, Modifier.weight(0.35f), style = MaterialTheme.typography.labelSmall, color = if (differs) RetroAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Hand control back to GOA: clear the player's mappings and apply what the re-scan found
                    rescanCompare = null
                    GamepadPreferences.resetMappings(context, gameId)
                    scope.launch {
                        val applied = withContext(Dispatchers.IO) {
                            runCatching {
                                com.goa.domain.controls.ControlSchemeResolver(context).resolveAndApplyScheme(
                                    gameId!!, gameSlug, gameTitle,
                                    installPath?.let { java.io.File(it) } ?: java.io.File(context.filesDir, "games/$gameId"),
                                    overwriteUser = false
                                )
                            }.getOrNull()
                        }
                        mappings = GamepadPreferences.loadMappings(context, gameId)
                        stickMode = GamepadPreferences.getStickMode(context, gameId)
                        sourceTag = GamepadPreferences.getSourceTag(context, gameId)
                        scanResult = ScanResult(applied != null, applied?.let { "Using ${it.tier.label.lowercase()}: ${it.stickMode.title}" } ?: "Couldn't apply")
                    }
                }) { com.goa.ui.menu.OneLineLabel("Use these mappings") }
            },
            dismissButton = { TextButton(onClick = { rescanCompare = null }) { com.goa.ui.menu.OneLineLabel("Keep yours") } }
        )
    }

    if (editingButton != null) {
        val targetButton = editingButton!!
        val currentAction = mappings[targetButton] ?: ControllerActions.ENTER_SPACE

        AlertDialog(
            onDismissRequest = { editingButton = null },
            title = {
                Text(
                    text = "Remap ${targetButton.label}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ControllerActions.allActions.forEach { act ->
                        val isSelected = act.id == currentAction.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    GamepadPreferences.saveUserMapping(context, gameId, targetButton, act, ChangeOrigin.USER_GAME_PAGE)
                                    mappings = GamepadPreferences.loadMappings(context, gameId)
                                    editingButton = null
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        GamepadPreferences.saveUserMapping(context, gameId, targetButton, act, ChangeOrigin.USER_GAME_PAGE)
                                        mappings = GamepadPreferences.loadMappings(context, gameId)
                                        editingButton = null
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = act.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = act.defaultKeyLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = act.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingButton = null }) {
                    com.goa.ui.menu.OneLineLabel("Close")
                }
            }
        )
    }
}

/** The player's button mappings cleared and the scheme GOA picks for this game (registry, engine, genre) applied again. */
private suspend fun resetControlsToGoa(context: android.content.Context, gameId: Long) =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val app = context.applicationContext as? com.goa.GoaApplication ?: return@withContext
        GamepadPreferences.resetMappings(app, gameId)
        val game = app.database.gameDao().getGameByIdSync(gameId) ?: return@withContext
        com.goa.domain.controls.ControlSchemeResolver(app).resolveAndApplyScheme(
            gameId = gameId, slug = game.slug, title = game.title,
            installDir = game.localInstallPath?.let { java.io.File(it) } ?: java.io.File(app.filesDir, "games/$gameId"),
            overwriteUser = false
        )
    }
