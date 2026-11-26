package com.goa.ui.emulation

import com.goa.data.db.ChangeOrigin
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.launch
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.data.db.SessionEngine
import com.goa.telemetry.EndReason
import com.goa.telemetry.SessionTelemetry
import com.goa.engine.emulator.DosBoxStateListener
import com.goa.engine.input.GamepadInputManager
import com.goa.engine.input.GamepadPreferences
import com.goa.engine.input.StickMode
import com.goa.engine.input.PointAndClickInputManager
import com.goa.engine.motion.GyroscopeMotionManager
import com.goa.ui.controls.DevicePosture
import com.goa.ui.controls.DosPcKeyboard
import com.goa.ui.controls.FloatingKeyboardSummoner
import com.goa.ui.controls.InputSurfaceMode
import com.goa.ui.controls.JoypadCustomPositions
import com.goa.ui.controls.JoypadPreferences
import com.goa.ui.controls.JoypadRepositionScreen
import com.goa.ui.controls.PointerModeToolbar
import com.goa.ui.controls.ScummTouchpadSurface
import com.goa.ui.controls.SplitLandscapePcKeyboard
import com.goa.ui.controls.VirtualTouchOverlay
import com.goa.ui.controls.ime.GoaKeyboardHost
import com.goa.ui.theme.GOATheme

class EmulationActivity : ComponentActivity(), DosBoxStateListener {

    private var isSnapdragonGpu = true
    private lateinit var gamepadInputManager: GamepadInputManager
    private lateinit var gyroMotionManager: GyroscopeMotionManager
    private val pointerInputManager = PointAndClickInputManager()
    private var triggerPauseState: (() -> Unit)? = null
    private lateinit var patchRepository: com.goa.data.repository.GamePatchRepository
    private var activeGameId: Long = -1L
    private var activeInstallPath: String = ""
    /** The running game's save state key (slug), recorded with each Save Slot (specs/009-drive-save-states). */
    private var saveGameKey: String = ""
    /** The game runs in DOSBox Pure, the only engine here with save states. */
    private var dosSaveStates = false
    private var activeConfPath: String = ""
    private var activeSoundId: Int = 1
    private var lastDosProgram: String = ""
    private var sessionTelemetry: SessionTelemetry? = null
    /** Installer-first games: starts the game once its DOS installer has unpacked it (see InstallerFollowUp). */
    private var installerFollowUp: com.goa.domain.usecase.InstallerFollowUp? = null
    // Analog vertical look via mouse Y: Build-engine games set up for mouse aiming (BuildMouseAim), and games whose
    // profile names a key to hold for it (mouseLookKey, DOS Quake's +mlook)
    private var mouseAimGame = false
    // DOS scancode of that held key, 0 when the game aims without one
    private var mouseLookKey = 0
    // Native Quake reads the gyro as its mouse, pitch included
    private var tyrQuakeGame = false
    private var debugInputReceiver: DebugInputReceiver? = null
    private var mouseLookActive by mutableStateOf(false)
    private var userInitiatedExit: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive mode with direct gesture support
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val gameId = intent.getLongExtra(EXTRA_GAME_ID, -1L)
        val installPath = intent.getStringExtra(EXTRA_INSTALL_PATH) ?: ""
        val confPath = intent.getStringExtra(EXTRA_CONF_PATH) ?: ""
        val soundId = intent.getIntExtra(EXTRA_SOUND_ID, 1)
        val cpuCycles = intent.getStringExtra(EXTRA_CPU_CYCLES) ?: "auto"
        val memoryMb = intent.getIntExtra(EXTRA_MEMORY_MB, 16)
        val gameSlug = intent.getStringExtra(EXTRA_GAME_SLUG)

        activeGameId = gameId
        // ☰ → Video default, or this game's own choice (game page Video tab, or picked in-game before)
        val (startPreset, startSharpness) = com.goa.data.prefs.DisplayAudioPreferences.effectiveVideo(this, gameId)
        activeInstallPath = installPath
        activeConfPath = confPath
        activeSoundId = soundId
        patchRepository = com.goa.data.repository.GamePatchRepository(this)

        // Gamepad and Gyro Subsystems
        gamepadInputManager = GamepadInputManager(this, onPauseRequested = {
            runOnUiThread { triggerPauseState?.invoke() }
        })
        gamepadInputManager.setGameId(gameId)
        gamepadInputManager.register()

        gyroMotionManager = GyroscopeMotionManager(this)
        val initialUiPrefs = getSharedPreferences("goa_ui_state", MODE_PRIVATE)
        gyroMotionManager.filter.sensitivityX = initialUiPrefs.getFloat("gyro_sens_x", 25.0f)
        gyroMotionManager.filter.sensitivityY = initialUiPrefs.getFloat("gyro_sens_y", 5.0f)
        gyroMotionManager.filter.lookDeadzoneY = com.goa.engine.input.LookDeadzone.load(this)
        gyroMotionManager.lookAllowed = false // set once the control scheme is resolved below
        gyroMotionManager.start()

        // ScummVM games run on their own libretro core; everything else stays on DOSBox Pure
        val scummVmSystemDir = intent.getStringExtra(EXTRA_SCUMMVM_SYSTEM_DIR)
        val isScummVm = scummVmSystemDir != null
        if (isScummVm) {
            DosBoxEngineBridge.configureCore(
                com.goa.domain.scummvm.ScummVmSupport.CORE_LIBRARY,
                scummVmSystemDir.orEmpty(),
                intent.getStringExtra(EXTRA_SCUMMVM_SAVE_DIR).orEmpty()
            )
        }
        // Native ports (TyrQuake for Quake): the game's own engine instead of DOSBox, with the pad passed straight in
        val nativeCore = intent.getStringExtra(EXTRA_CORE_LIBRARY)
        val isTyrQuake = nativeCore == com.goa.domain.quake.QuakeSupport.CORE_LIBRARY
        val isOpenLara = nativeCore == com.goa.domain.tombraider.TombRaiderSupport.CORE_LIBRARY
        if (nativeCore != null) {
            DosBoxEngineBridge.configureCore(
                nativeCore,
                intent.getStringExtra(EXTRA_CORE_SYSTEM_DIR).orEmpty(),
                intent.getStringExtra(EXTRA_CORE_SAVE_DIR).orEmpty()
            )
            gamepadInputManager.nativePad = isTyrQuake || isOpenLara
            gamepadInputManager.nativePadIds = if (isOpenLara) {
                com.goa.domain.tombraider.TombRaiderSupport.run { retroPadIds(layout(this@EmulationActivity, gameId)) }
            } else null
        }
        // Not DOSBox: no DOS save states, mouse aim, installer follow-up or DOS screen reading
        val isNotDos = isScummVm || nativeCore != null
        dosSaveStates = !isNotDos
        // Every launch passes the game's slug (LaunchGameUseCase). This activity runs in the :emulator process, where
        // GoaApplication sets nothing up, so nothing here may reach for its database or services.
        saveGameKey = com.goa.domain.savestate.GameKey.of(gameSlug, "")
        if (!isNotDos && installPath.isNotBlank()) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // Finish or roll back a save state replacement a crash interrupted, before the player can load it
                runCatching { com.goa.domain.savestate.SaveStateStore(File(installPath)).recover() }
            }
        }

        // Resolve and apply the control scheme (Tier 1 Patch -> Tier 2 JEV -> Tier 3 SLM) before anything reads the
        // stick mode: the core, mouse look, the gyro and the session's telemetry must all see the scheme this launch
        // runs with (before it is applied, a first launch only has the FPS default)
        if (installPath.isNotBlank()) {
            val installDir = File(installPath)
            val resolver = com.goa.domain.controls.ControlSchemeResolver(this, patchRepository)
            resolver.resolveAndApplyScheme(
                gameId = activeGameId,
                slug = gameSlug,
                title = null,
                installDir = installDir,
                overwriteUser = false
            )
        }

        // Initialize stick interpretation mode from preferences
        // ScummVM titles are point-and-click: start in tap-to-point mouse mode unless the player chose otherwise
        val initialStickMode = if (isTyrQuake || isOpenLara) {
            StickMode.NATIVE_GAMEPORT // the pad and both sticks go to the native port as they are
        } else if (isScummVm && !GamepadPreferences.hasUserCustomizedControls(this, activeGameId)) {
            StickMode.VIRTUAL_MOUSE
        } else {
            GamepadPreferences.getStickMode(this, activeGameId)
        }

        // Game health telemetry: the session journal begins before the core loads the game, so a crash while it loads
        // (the emulator process dies inside initEngine) still leaves a journal behind and is reported as a crash
        sessionTelemetry = SessionTelemetry(
            context = this,
            gameId = gameId,
            engine = when {
                isTyrQuake -> SessionEngine.TYRQUAKE
                isOpenLara -> SessionEngine.OPENLARA
                isScummVm -> SessionEngine.SCUMMVM
                else -> SessionEngine.DOSBOX_PURE
            },
            configJson = org.json.JSONObject()
                .put("sound", SessionTelemetry.soundDeviceId(soundId))
                .put("cycles", cpuCycles)
                .put("memoryMb", memoryMb)
                .put("stickMode", initialStickMode.id)
                .toString()
        ).also { it.start(lifecycleScope) }

        // ☰ → Audio: how the audio output opens (Stable unless the player chose Low latency)
        DosBoxEngineBridge.setAudioLatencyMode(com.goa.data.prefs.DisplayAudioPreferences.audioLatency(this).nativeId)

        // Initialize DOSBox engine
        DosBoxEngineBridge.initEngine(
            gameDirectory = installPath,
            dosboxConfPath = confPath,
            soundHardwareId = soundId,
            cpuCycles = cpuCycles,
            memoryMb = memoryMb,
            listener = this
        )

        // The preset and sharpness this game starts with
        DosBoxEngineBridge.updateVisualPreset(startPreset, isSnapdragonGpu, startSharpness)

        mouseLookKey = if (isNotDos) 0 else GamepadPreferences.getMouseLookKey(this, activeGameId)
            ?.let { com.goa.engine.input.ControllerActions.findById(it)?.primaryScancode } ?: 0
        mouseAimGame = !isNotDos && installPath.isNotBlank() &&
            (mouseLookKey != 0 || com.goa.engine.input.BuildMouseAim.isConfigured(File(installPath)))
        tyrQuakeGame = isTyrQuake
        debugInputReceiver = DebugInputReceiver.registerIfDebuggable(this, gamepadInputManager)

        gyroMotionManager.lookAllowed = initialStickMode == StickMode.FPS_DUAL_STICK || tyrQuakeGame
        gyroMotionManager.verticalMouseLook = tyrQuakeGame
        updateMouseLook()

        if (!isNotDos && installPath.isNotBlank()) {
            val dir = File(installPath)
            val conf = if (confPath.isNotBlank()) File(confPath) else File(dir, "dosbox.conf")
            if (com.goa.domain.usecase.InstallerFollowUp.isInstallerLaunch(conf, dir)) {
                installerFollowUp = com.goa.domain.usecase.InstallerFollowUp(dir, gameSlug, null)
            }
        }

        val initialStickModeInt = initialStickMode.modeIndex
        DosBoxEngineBridge.setStickMode(initialStickModeInt)

        @OptIn(ExperimentalLayoutApi::class)
        setContent {
            // GOA OSS: dark Material You over the black game screen, so its accents stay readable
            GOATheme(darkTheme = if (com.goa.BuildConfig.OSS_EDITION) true else androidx.compose.foundation.isSystemInDarkTheme()) {
                var isPaused by remember { mutableStateOf(false) }
                // Save states in Google Drive (GOA Pro): this game's Drive copy, checked when the menu opens
                var inGameDrive by remember { mutableStateOf<com.goa.ui.savestate.InGameDrive?>(null) }
                var driveConflict by remember { mutableStateOf<com.goa.data.drive.ConflictPrompt?>(null) }
                var saveStateRevision by remember { mutableIntStateOf(0) }
                val driveScope = androidx.compose.runtime.rememberCoroutineScope()
                LaunchedEffect(isPaused) {
                    val drive = gameDrive
                    if (!isPaused || drive == null) return@LaunchedEffect
                    val keep = inGameDrive
                    inGameDrive = com.goa.ui.savestate.InGameDrive(
                        check = (keep?.check as? com.goa.ui.savestate.InGameDrive.Check.Found) ?: com.goa.ui.savestate.InGameDrive.Check.Checking
                    )
                    inGameDrive = inGameDrive!!.copy(check = checkDrive(drive))
                }
                var visualPreset by remember { mutableIntStateOf(startPreset) }
                // Debug builds: the new filter engine, reachable before it replaces the presets (specs/008-shader-filters)
                val debugFilterIds = remember { listOf(com.goa.domain.video.FilterCatalog.OFF) + filterCatalog.bundles.map { it.id } }
                var debugFilterId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                var activeStickModeInt by remember { mutableIntStateOf(initialStickModeInt) }
                var isTouchOverlayEnabled by remember { mutableStateOf(true) }
                val uiPrefs = remember { getSharedPreferences("goa_ui_state", MODE_PRIVATE) }
                var isPerfOverlayEnabled by remember { mutableStateOf(uiPrefs.getBoolean("perf_overlay", false)) }
                var isGyroEnabled by remember { mutableStateOf(true) }
                var gyroSensitivityX by remember { androidx.compose.runtime.mutableFloatStateOf(uiPrefs.getFloat("gyro_sens_x", 25.0f)) }
                var gyroSensitivityY by remember { androidx.compose.runtime.mutableFloatStateOf(uiPrefs.getFloat("gyro_sens_y", 5.0f)) }
                var isInvertY by remember { mutableStateOf(false) }
                var stickLookSpeed by remember { androidx.compose.runtime.mutableFloatStateOf(uiPrefs.getFloat(STICK_LOOK_SPEED_PREF, 1.0f)) }
                var lookDeadzone by remember { androidx.compose.runtime.mutableFloatStateOf(com.goa.engine.input.LookDeadzone.load(this@EmulationActivity)) }

                // Mouse-driven games (point-and-click adventures, RTS, management) start in tap-to-point mode
                var inputSurfaceMode by remember {
                    mutableStateOf(
                        if (initialStickMode == StickMode.VIRTUAL_MOUSE) InputSurfaceMode.SCUMM_MOUSE else InputSurfaceMode.JOYPAD
                    )
                }
                var isControlsHidden by remember { mutableStateOf(false) }
                var keyboardOverlayActive by remember { mutableStateOf(false) }
                var isRepositioningJoypad by remember { mutableStateOf(false) }
                var mouseSensitivity by remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }

                val isGamepadConnected by gamepadInputManager.isGamepadConnected.collectAsState()
                // The touch HUD presses the same buttons as a controller, through this game's profile
                val padMappings by gamepadInputManager.mappings.collectAsState()
                // the game's own button names from its profile (shown on the touch shoulder buttons)
                val padLabels = remember(padMappings) {
                    // OpenLara reads the pad itself: its own names, whatever the DOS version's profile says
                    if (isOpenLara) com.goa.domain.tombraider.TombRaiderSupport.PAD_LABELS
                    else padMappings.mapNotNull { (btn, act) -> GamepadPreferences.gameLabel(this@EmulationActivity, gameId, btn, act)?.let { btn to it } }.toMap()
                }

                val keyboardHost = remember {
                    GoaKeyboardHost(this@EmulationActivity) { sc, isDown ->
                        DosBoxEngineBridge.sendKeyEvent(sc, isDown)
                    }
                }

                triggerPauseState = {
                    keyboardHost.hide()
                    keyboardOverlayActive = false
                    isControlsHidden = false
                    if (inputSurfaceMode == InputSurfaceMode.PC_KEYBOARD || inputSurfaceMode == InputSurfaceMode.ANDROID_IME) {
                        inputSurfaceMode = InputSurfaceMode.JOYPAD
                    }
                    isPaused = true
                    DosBoxEngineBridge.setPaused(true)
                    sessionTelemetry?.setPaused(true)
                }

                val isKeyboardActive = keyboardOverlayActive || inputSurfaceMode == InputSurfaceMode.PC_KEYBOARD || inputSurfaceMode == InputSurfaceMode.ANDROID_IME

                val isImeVisible = WindowInsets.isImeVisible
                LaunchedEffect(isImeVisible) {
                    if (!isImeVisible && inputSurfaceMode == InputSurfaceMode.ANDROID_IME) {
                        keyboardHost.hide()
                        keyboardOverlayActive = false
                        inputSurfaceMode = InputSurfaceMode.JOYPAD
                    }
                }

                // Hiding the keyboard any way other than our own buttons must give the game its full screen back
                DisposableEffect(Unit) {
                    keyboardHost.onImeDismissed = {
                        keyboardOverlayActive = false
                        if (inputSurfaceMode == InputSurfaceMode.ANDROID_IME) inputSurfaceMode = InputSurfaceMode.JOYPAD
                    }
                    onDispose { keyboardHost.onImeDismissed = null }
                }

                // Back gesture handling:
                // 1. If controls are hidden in any mode, unhide them immediately
                BackHandler(enabled = isControlsHidden) {
                    isControlsHidden = false
                }

                // 2. If Android IME is open, dismiss IME and restore JOYPAD
                BackHandler(enabled = !isControlsHidden && inputSurfaceMode == InputSurfaceMode.ANDROID_IME) {
                    keyboardHost.hide()
                    keyboardOverlayActive = false
                    inputSurfaceMode = InputSurfaceMode.JOYPAD
                }

                // 3. If the touchpad is active, switch to joypad
                BackHandler(enabled = !isControlsHidden && inputSurfaceMode == InputSurfaceMode.SCUMM_MOUSE) {
                    inputSurfaceMode = InputSurfaceMode.JOYPAD
                }

                // 4. If PC keyboard overlay is active, dismiss it and restore JOYPAD
                BackHandler(enabled = !isControlsHidden && (keyboardOverlayActive || inputSurfaceMode == InputSurfaceMode.PC_KEYBOARD) && inputSurfaceMode != InputSurfaceMode.ANDROID_IME) {
                    keyboardOverlayActive = false
                    inputSurfaceMode = InputSurfaceMode.JOYPAD
                }

                // 5. If joypad repositioning is active, dismiss it
                BackHandler(enabled = isRepositioningJoypad) {
                    isRepositioningJoypad = false
                }

                // 6. Otherwise, summon in-game pause dialog
                BackHandler(enabled = !isPaused && !isRepositioningJoypad && !isKeyboardActive && !isControlsHidden) {
                    triggerPauseState?.invoke()
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    val metrics = EmulationViewportLayout.calculateMetrics(
                        maxWidth,
                        maxHeight,
                        isGamepadConnected,
                        isKeyboardActive = isKeyboardActive,
                        isControlsHidden = isControlsHidden
                    )
                    val isLandscape = metrics.posture == DevicePosture.PHONE_LANDSCAPE
                    var joypadPositions by remember(isLandscape) {
                        mutableStateOf(JoypadPreferences.loadPositions(this@EmulationActivity, isLandscape))
                    }

                    // Attach invisible IME host View to the window hierarchy
                    AndroidView(
                        factory = { keyboardHost },
                        modifier = Modifier.size(1.dp)
                    )

                    if (isGamepadConnected && !keyboardOverlayActive) {
                        // Mode 1: Physical Controller (Fullscreen 4:3 + Floating Keyboard Summoner)
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmulationSurfaceScreen(
                                pointerInputManager = pointerInputManager,
                                modifier = Modifier.size(metrics.gameWidthDp, metrics.gameHeightDp)
                            )

                            FloatingKeyboardSummoner(
                                onSummonRequested = {
                                    keyboardOverlayActive = true
                                    inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                }
                            )
                        }
                    } else {
                        // Controller with keyboard active, or touch controls
                        val showFloatingSummoner = isGamepadConnected && !keyboardOverlayActive
                        when (metrics.posture) {
                            DevicePosture.FOLDABLE_DUAL_PANE -> {
                                if (isControlsHidden) {
                                    // Fullscreen 4:3 Game Canvas + Compact Pointer Toolbar
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        EmulationSurfaceScreen(
                                            pointerInputManager = pointerInputManager,
                                            modifier = Modifier.size(metrics.gameWidthDp, metrics.gameHeightDp)
                                        )

                                        PointerModeToolbar(
                                            pointerInputManager = pointerInputManager,
                                            onPauseRequested = { triggerPauseState?.invoke() },
                                            onSwitchToJoypad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                            },
                                            onSwitchToKeyboard = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                            },
                                            onToggleTouchpad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                            },
                                            activeMode = inputSurfaceMode,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                    }
                                } else {
                                    // Mode 4: Foldables (Exact 50/50 vertical split)
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Top 50% for 4:3 Game Canvas
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(metrics.gameHeightDp)
                                                .background(Color.Black),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EmulationSurfaceScreen(
                                                pointerInputManager = pointerInputManager,
                                                modifier = Modifier.size(metrics.gameWidthDp, metrics.gameHeightDp)
                                            )

                                            if (inputSurfaceMode == InputSurfaceMode.ANDROID_IME) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xAA181A1F),
                                                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(12.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = { triggerPauseState?.invoke() },
                                                        modifier = Modifier.size(36.dp)
                                                     ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Menu,
                                                            contentDescription = "Pause Menu",
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        // Bottom 50% for Controls
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(metrics.controlsHeightDp)
                                        ) {
                                            if (isRepositioningJoypad) {
                                                JoypadRepositionScreen(
                                                    currentPositions = joypadPositions,
                                                    onSave = { newPos ->
                                                        joypadPositions = newPos
                                                        JoypadPreferences.savePositions(this@EmulationActivity, isLandscape, newPos)
                                                        isRepositioningJoypad = false
                                                    },
                                                    onDismiss = { isRepositioningJoypad = false }
                                                )
                                            } else if (isGamepadConnected) {
                                                // When physical gamepad is connected, ALWAYS render DOS PC keyboard directly
                                                DosPcKeyboard(
                                                    onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                    onSwitchToJoypad = null, // No virtual joypad swap when physical controller connected
                                                    onSwitchToMouse = null,
                                                    onSummonAndroidKeyboard = {
                                                        inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                        keyboardHost.show()
                                                    },
                                                    onClose = {
                                                        keyboardHost.hide()
                                                        keyboardOverlayActive = false
                                                        inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                    },
                                                    onPauseRequested = { triggerPauseState?.invoke() },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                when (inputSurfaceMode) {
                                                    InputSurfaceMode.JOYPAD -> {
                                                        VirtualTouchOverlay(
                                                            visible = isTouchOverlayEnabled && !isGamepadConnected,
                                                            onButton = { button, down -> gamepadInputManager.pressButton(button, down) },
                                                            mappings = padMappings,
                                                            labels = padLabels,
                                                            consoleAsPad = isTyrQuake || isOpenLara,
                                                            stickMode = activeStickModeInt,
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            onKeyboardRequested = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                                            },
                                                            onMouseRequested = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                                            },
                                                            onClose = {
                                                                isControlsHidden = true
                                                            },
                                                            customPositions = joypadPositions
                                                        )
                                                    }
                                                    InputSurfaceMode.SCUMM_MOUSE -> {
                                                        ScummTouchpadSurface(
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            onSwitchToJoypad = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                            },
                                                            onSwitchToKeyboard = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                                            },
                                                            onClose = {
                                                                isControlsHidden = true
                                                            }
                                                        )
                                                    }
                                                    InputSurfaceMode.PC_KEYBOARD, InputSurfaceMode.ANDROID_IME -> {
                                                        DosPcKeyboard(
                                                            onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                            onSwitchToJoypad = {
                                                                keyboardHost.hide()
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                                keyboardOverlayActive = false
                                                            },
                                                            onSwitchToMouse = {
                                                                keyboardHost.hide()
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                                                keyboardOverlayActive = false
                                                            },
                                                            onSummonAndroidKeyboard = {
                                                                inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                                keyboardHost.show()
                                                            },
                                                            onClose = {
                                                                keyboardHost.hide()
                                                                keyboardOverlayActive = false
                                                                isControlsHidden = true
                                                            },
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            DevicePosture.PHONE_PORTRAIT -> {
                                if (isControlsHidden) {
                                    // Fullscreen 4:3 Game Canvas + Compact Pointer Toolbar in Phone Portrait
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        EmulationSurfaceScreen(
                                            pointerInputManager = pointerInputManager,
                                            modifier = Modifier.size(metrics.gameWidthDp, metrics.gameHeightDp)
                                        )

                                        PointerModeToolbar(
                                            pointerInputManager = pointerInputManager,
                                            onPauseRequested = { triggerPauseState?.invoke() },
                                            onSwitchToJoypad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                            },
                                            onSwitchToKeyboard = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                            },
                                            onToggleTouchpad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                            },
                                            activeMode = inputSurfaceMode,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                    }
                                } else {
                                    // Mode 2: Vertical Orientation Dual-Pane (Top 4:3 Game, Bottom Controls)
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Upper portion for 4:3 Game Canvas
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(metrics.gameHeightDp)
                                                .background(Color.Black),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EmulationSurfaceScreen(
                                                pointerInputManager = pointerInputManager,
                                                modifier = Modifier.size(metrics.gameWidthDp, metrics.gameHeightDp)
                                            )

                                            if (inputSurfaceMode == InputSurfaceMode.ANDROID_IME) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xAA181A1F),
                                                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(12.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = { triggerPauseState?.invoke() },
                                                        modifier = Modifier.size(36.dp)
                                                     ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Menu,
                                                            contentDescription = "Pause Menu",
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        // Lower portion for Controls
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(metrics.controlsHeightDp)
                                        ) {
                                            if (isRepositioningJoypad) {
                                                JoypadRepositionScreen(
                                                    currentPositions = joypadPositions,
                                                    onSave = { newPos ->
                                                        joypadPositions = newPos
                                                        JoypadPreferences.savePositions(this@EmulationActivity, isLandscape, newPos)
                                                        isRepositioningJoypad = false
                                                    },
                                                    onDismiss = { isRepositioningJoypad = false }
                                                )
                                            } else if (isGamepadConnected) {
                                                // When physical gamepad is connected, ALWAYS render DOS PC keyboard directly
                                                DosPcKeyboard(
                                                    onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                    onSwitchToJoypad = null, // No virtual joypad swap when physical controller connected
                                                    onSwitchToMouse = null,
                                                    onSummonAndroidKeyboard = {
                                                        inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                        keyboardHost.show()
                                                    },
                                                    onClose = {
                                                        keyboardHost.hide()
                                                        keyboardOverlayActive = false
                                                        inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                    },
                                                    onPauseRequested = { triggerPauseState?.invoke() },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                when (inputSurfaceMode) {
                                                    InputSurfaceMode.JOYPAD -> {
                                                        VirtualTouchOverlay(
                                                            visible = isTouchOverlayEnabled && !isGamepadConnected,
                                                            onButton = { button, down -> gamepadInputManager.pressButton(button, down) },
                                                            mappings = padMappings,
                                                            labels = padLabels,
                                                            consoleAsPad = isTyrQuake || isOpenLara,
                                                            stickMode = activeStickModeInt,
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            onKeyboardRequested = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                                            },
                                                            onMouseRequested = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                                            },
                                                            onClose = {
                                                                isControlsHidden = true
                                                            },
                                                            customPositions = joypadPositions
                                                        )
                                                    }
                                                    InputSurfaceMode.SCUMM_MOUSE -> {
                                                        ScummTouchpadSurface(
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            onSwitchToJoypad = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                            },
                                                            onSwitchToKeyboard = {
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                                            },
                                                            onClose = {
                                                                isControlsHidden = true
                                                            }
                                                        )
                                                    }
                                                    InputSurfaceMode.PC_KEYBOARD, InputSurfaceMode.ANDROID_IME -> {
                                                        DosPcKeyboard(
                                                            onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                            onSwitchToJoypad = {
                                                                keyboardHost.hide()
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                                keyboardOverlayActive = false
                                                            },
                                                            onSwitchToMouse = {
                                                                keyboardHost.hide()
                                                                isControlsHidden = false
                                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                                                keyboardOverlayActive = false
                                                            },
                                                            onSummonAndroidKeyboard = {
                                                                inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                                keyboardHost.show()
                                                            },
                                                            onClose = {
                                                                keyboardHost.hide()
                                                                keyboardOverlayActive = false
                                                                isControlsHidden = true
                                                            },
                                                            onPauseRequested = { triggerPauseState?.invoke() },
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            DevicePosture.PHONE_LANDSCAPE -> {
                                // Mode 3: Horizontal Orientation (Centered 4:3 Game, Letterboxed Controls)
                                Box(modifier = Modifier.fillMaxSize()) {
                                    EmulationSurfaceScreen(
                                        pointerInputManager = pointerInputManager,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(metrics.gameWidthDp, metrics.gameHeightDp)
                                    )

                                    if (isRepositioningJoypad) {
                                        JoypadRepositionScreen(
                                            currentPositions = joypadPositions,
                                            onSave = { newPos ->
                                                joypadPositions = newPos
                                                JoypadPreferences.savePositions(this@EmulationActivity, isLandscape, newPos)
                                                isRepositioningJoypad = false
                                            },
                                            onDismiss = { isRepositioningJoypad = false }
                                        )
                                    } else if (isControlsHidden) {
                                        PointerModeToolbar(
                                            pointerInputManager = pointerInputManager,
                                            onPauseRequested = { triggerPauseState?.invoke() },
                                            onSwitchToJoypad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                            },
                                            onSwitchToKeyboard = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                            },
                                            onToggleTouchpad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                            },
                                            activeMode = inputSurfaceMode,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                    } else if (inputSurfaceMode == InputSurfaceMode.SCUMM_MOUSE) {
                                        // Keep the game visible: taps on it click where touched; toolbar sits in the pillarbox
                                        PointerModeToolbar(
                                            pointerInputManager = pointerInputManager,
                                            onPauseRequested = { triggerPauseState?.invoke() },
                                            onSwitchToJoypad = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.JOYPAD
                                            },
                                            onSwitchToKeyboard = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                            },
                                            activeMode = InputSurfaceMode.SCUMM_MOUSE,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                    } else if (isGamepadConnected && keyboardOverlayActive) {
                                        // When hardware gamepad is connected and keyboard is summoned:
                                        // Never show virtual joystick! Show DOS PC keyboard directly
                                        if (com.goa.ui.controls.splitKeyboardFits(metrics.pillarboxMarginDp, metrics.gameHeightDp + metrics.controlsHeightDp)) {
                                            SplitLandscapePcKeyboard(
                                                pillarboxMarginDp = metrics.pillarboxMarginDp,
                                                onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                onSwitchToJoypad = null,
                                                onSummonAndroidKeyboard = {
                                                    inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                    keyboardHost.show()
                                                },
                                                onClose = {
                                                    keyboardHost.hide()
                                                    keyboardOverlayActive = false
                                                    inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                },
                                                onPauseRequested = { triggerPauseState?.invoke() }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .fillMaxWidth()
                                                    .height(230.dp)
                                                    .background(Color(0xE6101216))
                                            ) {
                                                DosPcKeyboard(
                                                    onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                    onSwitchToJoypad = null,
                                                    onSwitchToMouse = null,
                                                    onSummonAndroidKeyboard = {
                                                        inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                                        keyboardHost.show()
                                                    },
                                                    onClose = {
                                                        keyboardHost.hide()
                                                        keyboardOverlayActive = false
                                                        inputSurfaceMode = InputSurfaceMode.JOYPAD
                                                    },
                                                    onPauseRequested = { triggerPauseState?.invoke() },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    } else if (inputSurfaceMode == InputSurfaceMode.PC_KEYBOARD) {
                                        val switchToJoypad = {
                                            isControlsHidden = false
                                            inputSurfaceMode = InputSurfaceMode.JOYPAD
                                        }
                                        val summonAndroidKeyboard = {
                                            inputSurfaceMode = InputSurfaceMode.ANDROID_IME
                                            keyboardHost.show()
                                        }
                                        val closeKeyboard = {
                                            keyboardHost.hide()
                                            keyboardOverlayActive = false
                                            isControlsHidden = true
                                        }
                                        if (com.goa.ui.controls.splitKeyboardFits(metrics.pillarboxMarginDp, metrics.gameHeightDp + metrics.controlsHeightDp)) {
                                            SplitLandscapePcKeyboard(
                                                pillarboxMarginDp = metrics.pillarboxMarginDp,
                                                onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                onSwitchToJoypad = switchToJoypad,
                                                onSummonAndroidKeyboard = summonAndroidKeyboard,
                                                onClose = closeKeyboard,
                                                onPauseRequested = { triggerPauseState?.invoke() }
                                            )
                                        } else {
                                            // The bars beside the game are too narrow for split keys: full-width keys at the bottom
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .fillMaxWidth()
                                                    .height(230.dp)
                                                    .background(Color(0xE6101216))
                                            ) {
                                                DosPcKeyboard(
                                                    onSendKey = { sc, isDown -> DosBoxEngineBridge.sendKeyEvent(sc, isDown) },
                                                    onSwitchToJoypad = switchToJoypad,
                                                    onSummonAndroidKeyboard = summonAndroidKeyboard,
                                                    onClose = closeKeyboard,
                                                    onPauseRequested = { triggerPauseState?.invoke() },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    } else {
                                        VirtualTouchOverlay(
                                            visible = isTouchOverlayEnabled && !isGamepadConnected,
                                            onButton = { button, down -> gamepadInputManager.pressButton(button, down) },
                                            mappings = padMappings,
                                                            labels = padLabels,
                                                            consoleAsPad = isTyrQuake || isOpenLara,
                                                            stickMode = activeStickModeInt,
                                            onPauseRequested = { triggerPauseState?.invoke() },
                                            onKeyboardRequested = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.PC_KEYBOARD
                                            },
                                            onMouseRequested = {
                                                isControlsHidden = false
                                                inputSurfaceMode = InputSurfaceMode.SCUMM_MOUSE
                                            },
                                            onClose = {
                                                isControlsHidden = true
                                            },
                                            customPositions = joypadPositions
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isPerfOverlayEnabled) {
                        PerfOverlay(
                            modifier = Modifier.align(Alignment.TopStart),
                            lookMode = if (!mouseAimGame) null else if (mouseLookActive) "mouse-aim (analog)" else "keys (PgUp/PgDn)"
                        )
                    }

                    // In-Game Pause & Settings Overlay Dialog
                    if (isPaused) {
                        InGameMenuDialog(
                            currentPreset = visualPreset,
                            debugFilterIds = if (com.goa.BuildConfig.DEBUG) debugFilterIds else emptyList(),
                            debugFilterId = debugFilterId,
                            onSelectDebugFilter = { id ->
                                debugFilterId = id
                                applyFilter(id)
                            },
                            isTouchOverlayEnabled = isTouchOverlayEnabled,
                            isGyroEnabled = isGyroEnabled,
                            gyroSensitivityX = gyroSensitivityX,
                            gyroSensitivityY = gyroSensitivityY,
                            isInvertY = isInvertY,
                            stickMode = activeStickModeInt,
                            gameId = activeGameId,
                            onMappingsChanged = {
                                gamepadInputManager.reloadMappings()
                            },
                            onToggleStickMode = { mode ->
                                activeStickModeInt = mode
                                DosBoxEngineBridge.setStickMode(mode)
                                gyroMotionManager.lookAllowed = mode == StickMode.FPS_DUAL_STICK.modeIndex || tyrQuakeGame
                                updateMouseLook()
                                val modeEnum = StickMode.fromIndex(mode)
                                GamepadPreferences.setUserStickMode(this@EmulationActivity, activeGameId, modeEnum, ChangeOrigin.USER_IN_GAME)
                            },
                            onSelectPreset = { preset ->
                                com.goa.telemetry.ConfigChangeRecorder.record(
                                    this@EmulationActivity, activeGameId, com.goa.data.db.TrackedSetting.VISUAL_PRESET,
                                    visualPreset.toString(), preset.toString(), ChangeOrigin.USER_IN_GAME
                                )
                                visualPreset = preset
                                // Remembered for this game, so it starts with this preset next time
                                com.goa.data.prefs.DisplayAudioPreferences.setGamePreset(this@EmulationActivity, activeGameId, preset)
                                val sharpness = com.goa.data.prefs.DisplayAudioPreferences.effectiveVideo(this@EmulationActivity, activeGameId).second
                                DosBoxEngineBridge.updateVisualPreset(preset, isSnapdragonGpu, sharpness)
                            },
                            onControlsOpened = { sessionTelemetry?.onControlsOpened() },
                            onToggleTouchOverlay = { enabled ->
                                isTouchOverlayEnabled = enabled
                            },
                            isPerfOverlayEnabled = isPerfOverlayEnabled,
                            onTogglePerfOverlay = { enabled ->
                                isPerfOverlayEnabled = enabled
                                uiPrefs.edit().putBoolean("perf_overlay", enabled).apply()
                            },
                            onToggleGyro = { enabled ->
                                isGyroEnabled = enabled
                                gyroMotionManager.isEnabled = enabled
                            },
                            onGyroSensitivityXChange = { sens ->
                                gyroSensitivityX = sens
                                gyroMotionManager.filter.sensitivityX = sens
                                uiPrefs.edit().putFloat("gyro_sens_x", sens).apply()
                            },
                            onGyroSensitivityYChange = { sens ->
                                gyroSensitivityY = sens
                                gyroMotionManager.filter.sensitivityY = sens
                                uiPrefs.edit().putFloat("gyro_sens_y", sens).apply()
                            },
                            onToggleInvertY = { invert ->
                                isInvertY = invert
                                gyroMotionManager.filter.invertY = invert
                            },
                            mouseSensitivity = mouseSensitivity,
                            stickLookSpeed = if (mouseAimGame) stickLookSpeed else null,
                            onStickLookSpeedChange = { speed ->
                                stickLookSpeed = speed
                                DosBoxEngineBridge.setStickLookSpeed(speed)
                                uiPrefs.edit().putFloat(STICK_LOOK_SPEED_PREF, speed).apply()
                            },
                            // Shooters only: the dual-stick scheme and native Quake look up and down
                            lookDeadzone = if (activeStickModeInt == StickMode.FPS_DUAL_STICK.modeIndex || tyrQuakeGame) lookDeadzone else null,
                            onLookDeadzoneChange = { dz ->
                                lookDeadzone = dz
                                gyroMotionManager.filter.lookDeadzoneY = dz
                                DosBoxEngineBridge.setLookDeadzoneY(dz)
                                com.goa.engine.input.LookDeadzone.save(this@EmulationActivity, dz)
                            },
                            onMouseSensitivityChange = { sens ->
                                mouseSensitivity = sens
                                pointerInputManager.mouseSensitivity = sens
                                pointerInputManager.touchSensitivity = sens
                            },
                            onRepositionJoypad = {
                                isPaused = false
                                DosBoxEngineBridge.setPaused(false)
                                isRepositioningJoypad = true
                            },
                            saveEngine = com.goa.domain.savestate.SaveEngine.of(isTyrQuake, isOpenLara, isScummVm),
                            nativeEngineName = when {
                                isTyrQuake -> "TyrQuake"
                                isOpenLara -> "OpenLara"
                                else -> null
                            },
                            onSaveState = {
                                if (isNotDos) false
                                else {
                                    val store = com.goa.domain.savestate.SaveStateStore(File(installPath))
                                    val ok = DosBoxEngineBridge.saveState(0, store.stateFile.absolutePath)
                                    // Record when, where and with which engine it was made (state_0.json)
                                    if (ok) runCatching {
                                        store.writeMetaAfterSave(
                                            com.goa.domain.savestate.SaveStateDefaults.forGame(
                                                this@EmulationActivity, saveGameKey, DosBoxEngineBridge.getCoreVersion()
                                            )
                                        )
                                    }
                                    ok
                                }
                            },
                            hasSaveState = { !isNotDos && File(installPath, "state_0.sav").isFile },
                            saveStateProblem = {
                                if (isNotDos) null else {
                                    val store = com.goa.domain.savestate.SaveStateStore(File(installPath))
                                    when (val problem = com.goa.domain.savestate.DosBoxStateFile.check(store.stateFile)) {
                                        null, com.goa.domain.savestate.DosBoxStateFile.Problem.MISSING -> null
                                        else -> problem.message + " Your game is unchanged." +
                                            (if (store.previousFile.isFile) " You can use Restore previous save state." else "")
                                    }
                                }
                            },
                            previousSavedAt = {
                                if (isNotDos) null else runCatching {
                                    // A damaged previous save state isn't offered: swapping it in would only fail to load
                                    com.goa.domain.savestate.SaveStateStore(File(installPath))
                                        .previous(saveDefaults())?.takeIf { it.problem == null }?.meta?.savedAt
                                }.getOrNull()
                            },
                            onRestorePrevious = {
                                val restored = !isNotDos && runCatching {
                                    com.goa.domain.savestate.SaveStateStore(File(installPath)).restorePrevious(saveDefaults())
                                }.getOrDefault(false)
                                // What the Drive row said was about the save state just swapped out
                                if (restored) inGameDrive = inGameDrive?.copy(downloaded = false, message = null, note = null)
                                restored
                            },
                            saveStateRevision = saveStateRevision,
                            driveRow = inGameDrive?.takeIf { !isNotDos }?.let { state ->
                                {
                                    com.goa.ui.savestate.DriveSaveRow(
                                        state = state,
                                        onDownload = {
                                            val found = state.check as? com.goa.ui.savestate.InGameDrive.Check.Found
                                            val drive = gameDrive
                                            if (found != null && drive != null) {
                                                val status = runCatching { drive.compareWith(found.copy, installPath) }.getOrNull()
                                                when (status) {
                                                    com.goa.domain.savestate.SaveStateComparison.Status.SAME ->
                                                        inGameDrive = state.copy(message = null, note = "Your save state is already the same as Drive's.")
                                                    com.goa.domain.savestate.SaveStateComparison.Status.ONLY_DRIVE, null -> {
                                                        inGameDrive = state.copy(busy = true, message = null, note = null)
                                                        driveScope.launch {
                                                            inGameDrive = downloadFromDrive(drive, found.copy, state)
                                                            saveStateRevision++
                                                        }
                                                    }
                                                    else -> driveConflict = conflictPrompt(found.copy)
                                                }
                                            }
                                        },
                                        onLoadNow = {
                                            val ok = DosBoxEngineBridge.loadState(0, File(installPath, "state_0.sav").absolutePath)
                                            inGameDrive = state.copy(
                                                downloaded = false,
                                                message = if (ok) null else "This save state could not be loaded; your game is unchanged."
                                            )
                                            if (ok) {
                                                isPaused = false
                                                DosBoxEngineBridge.setPaused(false)
                                                sessionTelemetry?.setPaused(false)
                                                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                                            }
                                        }
                                    )
                                }
                            },
                            onLoadState = {
                                if (isNotDos) false
                                else {
                                    val saveFile = java.io.File(installPath, "state_0.sav")
                                    DosBoxEngineBridge.loadState(0, saveFile.absolutePath)
                                }
                            },
                            onResume = {
                                isPaused = false
                                DosBoxEngineBridge.setPaused(false)
                                sessionTelemetry?.setPaused(false)
                                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                            },
                            onExit = {
                                userInitiatedExit = true
                                returnToLauncher()
                            }
                        )
                    }
                    driveConflict?.let { prompt ->
                        com.goa.ui.savestate.ConflictScreen(
                            prompt = prompt,
                            onChoice = { choice, _ ->
                                driveConflict = null
                                val found = inGameDrive?.check as? com.goa.ui.savestate.InGameDrive.Check.Found
                                val drive = gameDrive
                                if (choice == com.goa.domain.savestate.ConflictChoice.USE_DRIVE && found != null && drive != null) {
                                    val before = inGameDrive!!
                                    inGameDrive = before.copy(busy = true, message = null, note = null)
                                    driveScope.launch {
                                        inGameDrive = downloadFromDrive(drive, found.copy, before)
                                        saveStateRevision++
                                    }
                                }
                            },
                            onKeepNewerForAll = {}
                        )
                    }
                }
            }
        }
    }

    /** The Drive service for this game's process, when it applies: GOA Pro, GOA, a DOS game, an account linked. */
    private val gameDrive: com.goa.data.drive.DriveSaveStates? by lazy {
        if (!dosSaveStates || activeInstallPath.isBlank() || !com.goa.data.drive.DriveBackup.enabled ||
            !com.goa.domain.license.LicenseManager.isRegistered(this)
        ) return@lazy null
        val (auth, service) = com.goa.data.drive.DriveBackup.forGame(this, lifecycleScope)
        if (auth.account.value == null) null else service
    }

    private fun saveDefaults() = com.goa.domain.savestate.SaveStateDefaults.forGame(this, saveGameKey)

    private suspend fun checkDrive(drive: com.goa.data.drive.DriveSaveStates): com.goa.ui.savestate.InGameDrive.Check = try {
        drive.checkGame(saveGameKey)?.let { com.goa.ui.savestate.InGameDrive.Check.Found(it) }
            ?: com.goa.ui.savestate.InGameDrive.Check.None
    } catch (e: com.goa.data.drive.DriveException) {
        when (e.error) {
            com.goa.data.drive.DriveError.AccessRemoved -> com.goa.ui.savestate.InGameDrive.Check.NeedsAccess
            com.goa.data.drive.DriveError.NoConnection -> com.goa.ui.savestate.InGameDrive.Check.Offline
            else -> com.goa.ui.savestate.InGameDrive.Check.Offline
        }
    }

    private suspend fun downloadFromDrive(
        drive: com.goa.data.drive.DriveSaveStates,
        copy: com.goa.data.drive.DriveCopy,
        state: com.goa.ui.savestate.InGameDrive
    ): com.goa.ui.savestate.InGameDrive {
        return try {
            drive.download(copy, activeInstallPath)
            state.copy(busy = false, downloaded = true, message = null)
        } catch (e: com.goa.data.drive.DriveException) {
            state.copy(busy = false, downloaded = false, message = e.error.message)
        }
    }

    /** The side-by-side choice for this game when its phone and Drive save states differ. */
    private fun conflictPrompt(copy: com.goa.data.drive.DriveCopy): com.goa.data.drive.ConflictPrompt? {
        val local = runCatching {
            com.goa.domain.savestate.SaveStateStore(File(activeInstallPath)).current(saveDefaults())
        }.getOrNull() ?: return null
        val phone = com.goa.data.drive.CopyInfo(local.meta.device, local.meta.savedAt, local.meta.coreVersion)
        val driveInfo = com.goa.data.drive.CopyInfo(copy.meta.device, copy.meta.savedAt, copy.meta.coreVersion)
        return com.goa.data.drive.ConflictPrompt(
            title = copy.title, position = 1, total = 1, remainingAfter = 0, phone = phone, drive = driveInfo,
            preselected = com.goa.domain.savestate.SaveStateComparison.newer(
                com.goa.domain.savestate.SaveStateComparison.Side(local.meta.sha256, local.meta.savedAt),
                com.goa.domain.savestate.SaveStateComparison.Side(copy.meta.sha256, copy.meta.savedAt)
            ),
            exporting = false
        )
    }

    /**
     * Mouse-aim games start with aiming already on (see BuildMouseAim), so vertical stick/gyro goes to mouse Y whenever
     * the dual-stick FPS scheme is selected. Nothing is held down, so it is safe at the DOS prompt and in setup too.
     */
    private fun updateMouseLook() {
        if (!mouseAimGame) return
        val want = GamepadPreferences.getStickMode(this, activeGameId) == StickMode.FPS_DUAL_STICK
        if (want != mouseLookActive) applyMouseLook(want)
    }

    private fun applyMouseLook(enabled: Boolean) {
        mouseLookActive = enabled
        DosBoxEngineBridge.setStickLookSpeed(getSharedPreferences("goa_ui_state", MODE_PRIVATE).getFloat(STICK_LOOK_SPEED_PREF, 1.0f))
        DosBoxEngineBridge.setMouseLookKey(mouseLookKey)
        DosBoxEngineBridge.setMouseLook(enabled)
        if (::gyroMotionManager.isInitialized) gyroMotionManager.verticalMouseLook = enabled
    }

    private fun returnToLauncher() {
        runOnUiThread {
            val reason = if (userInitiatedExit) EndReason.USER_EXIT else EndReason.ENGINE_ERROR
            sessionTelemetry?.end(reason)
            DosBoxEngineBridge.terminateEngine()
            finish()
        }
    }

    // Lint flags ComponentActivity.dispatchKeyEvent as library-restricted; overriding the framework method is fine
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sessionTelemetry?.onPress("GAMEPAD")
        if (gamepadInputManager.handleKeyEvent(event)) {
            return true
        }
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val scancode = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x1C
            KeyEvent.KEYCODE_SPACE -> 0x39
            KeyEvent.KEYCODE_ESCAPE -> 0x01
            KeyEvent.KEYCODE_DPAD_UP -> 0x48
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x50
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x4B
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4D
            KeyEvent.KEYCODE_1 -> 0x02
            KeyEvent.KEYCODE_2 -> 0x03
            KeyEvent.KEYCODE_3 -> 0x04
            KeyEvent.KEYCODE_4 -> 0x05
            KeyEvent.KEYCODE_5 -> 0x06
            KeyEvent.KEYCODE_6 -> 0x07
            KeyEvent.KEYCODE_7 -> 0x08
            KeyEvent.KEYCODE_8 -> 0x09
            KeyEvent.KEYCODE_9 -> 0x0A
            KeyEvent.KEYCODE_0 -> 0x0B
            KeyEvent.KEYCODE_Q -> 0x10
            KeyEvent.KEYCODE_W -> 0x11
            KeyEvent.KEYCODE_E -> 0x12
            KeyEvent.KEYCODE_R -> 0x13
            KeyEvent.KEYCODE_T -> 0x14
            KeyEvent.KEYCODE_Y -> 0x15
            KeyEvent.KEYCODE_U -> 0x16
            KeyEvent.KEYCODE_I -> 0x17
            KeyEvent.KEYCODE_O -> 0x18
            KeyEvent.KEYCODE_P -> 0x19
            KeyEvent.KEYCODE_A -> 0x1E
            KeyEvent.KEYCODE_S -> 0x1F
            KeyEvent.KEYCODE_D -> 0x20
            KeyEvent.KEYCODE_F -> 0x21
            KeyEvent.KEYCODE_G -> 0x22
            KeyEvent.KEYCODE_H -> 0x23
            KeyEvent.KEYCODE_J -> 0x24
            KeyEvent.KEYCODE_K -> 0x25
            KeyEvent.KEYCODE_L -> 0x26
            KeyEvent.KEYCODE_Z -> 0x2C
            KeyEvent.KEYCODE_X -> 0x2D
            KeyEvent.KEYCODE_C -> 0x2E
            KeyEvent.KEYCODE_V -> 0x2F
            KeyEvent.KEYCODE_B -> 0x30
            KeyEvent.KEYCODE_N -> 0x31
            KeyEvent.KEYCODE_M -> 0x32
            KeyEvent.KEYCODE_DEL -> 0x0E
            KeyEvent.KEYCODE_TAB -> 0x0F
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x3A
            KeyEvent.KEYCODE_F1 -> 0x3B
            KeyEvent.KEYCODE_F2 -> 0x3C
            KeyEvent.KEYCODE_F3 -> 0x3D
            KeyEvent.KEYCODE_F4 -> 0x3E
            KeyEvent.KEYCODE_F5 -> 0x3F
            KeyEvent.KEYCODE_F6 -> 0x40
            KeyEvent.KEYCODE_F7 -> 0x41
            KeyEvent.KEYCODE_F8 -> 0x42
            KeyEvent.KEYCODE_F9 -> 0x43
            KeyEvent.KEYCODE_F10 -> 0x44
            KeyEvent.KEYCODE_F11 -> 0x57
            KeyEvent.KEYCODE_F12 -> 0x58
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0x1D
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0x38
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0x2A
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x36
            KeyEvent.KEYCODE_MOVE_HOME -> 0x47
            KeyEvent.KEYCODE_MOVE_END -> 0x4F
            KeyEvent.KEYCODE_PAGE_UP -> 0x49
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x51
            KeyEvent.KEYCODE_INSERT -> 0x52
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x53
            else -> null
        }
        if (scancode != null) {
            DosBoxEngineBridge.sendKeyEvent(scancode, isDown)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInputManager.handleMotionEvent(event)) {
            return true
        }
        // Hardware mouse only; stylus hover is handled by the emulation surface with its real dimensions
        if (pointerInputManager.handleHardwareMouseEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val isMouse = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_MOUSE
            sessionTelemetry?.onPress(if (isMouse) "POINTER" else "TOUCH")
        }
        // Mouse button presses are handled once here; the surface's touch listener skips mouse events
        pointerInputManager.handleHardwareMouseEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        gyroMotionManager.start()
        // The game stays paused (pause menu open) after GOA was in the background: Resume there unpauses it
        if (::gamepadInputManager.isInitialized) {
            gamepadInputManager.reloadMappings()
            val mode = GamepadPreferences.getStickMode(this, activeGameId)
            DosBoxEngineBridge.setStickMode(mode.modeIndex)
        }
    }

    override fun onPause() {
        super.onPause()
        gyroMotionManager.stop()
        pauseForFocusLoss()
        // A latched Run toggle would otherwise leave Shift held down in DOS while GOA is in the background
        if (::gamepadInputManager.isInitialized) gamepadInputManager.releaseLatchedKeys()
    }

    // Split screen and freeform windows: another app taking focus doesn't pause this activity, only this does.
    // Fullscreen is left to onPause: a tablet's taskbar briefly takes "top resumed" when a touch lands on the bottom
    // edge (where the on-screen KEYB button sits), which paused the game the moment the keyboard opened.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity && isInMultiWindowMode) pauseForFocusLoss()
    }

    /**
     * GOA left the foreground (home, recents, another app, screen off, a call): stop the game and its sound, and open
     * the pause menu so it is waiting there on return instead of having played on unattended.
     */
    private fun pauseForFocusLoss() {
        val openMenu = triggerPauseState
        if (openMenu != null) {
            openMenu()
        } else {
            DosBoxEngineBridge.setPaused(true)
            sessionTelemetry?.setPaused(true)
        }
    }

    override fun onDestroy() {
        debugInputReceiver?.let { runCatching { unregisterReceiver(it) } }
        sessionTelemetry?.end(EndReason.USER_EXIT)
        super.onDestroy()
        gamepadInputManager.unregister()
        gyroMotionManager.stop()
        DosBoxEngineBridge.terminateEngine()
        if (isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 500)
        }
    }

    override fun onEngineStarted() {
        // initEngine calls this on the UI thread while it holds the engine lock, so anything that calls back into the
        // engine is posted to run after initEngine returns (runOnUiThread would run it now and deadlock on that lock)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // The core can be (re)created by engine start; re-send the mouse-look flag to it
        if (mouseAimGame) main.post { if (mouseLookActive) applyMouseLook(true) }
        main.post { DosBoxEngineBridge.setLookDeadzoneY(com.goa.engine.input.LookDeadzone.load(this)) }
        // Look-spring: centre the view once the right stick stops looking up or down (key-look games like Hexen, and
        // the mouse-aim Build games, whose aim otherwise stays where the stick left it)
        val scancode = GamepadPreferences.getLookRecenterKey(this, activeGameId)
            ?.let { com.goa.engine.input.ControllerActions.findById(it)?.primaryScancode }
        if (scancode != null) main.post { DosBoxEngineBridge.setLookRecenter(scancode, LOOK_RECENTER_DELAY_MS) }
        // The engine version recorded with save states, also for those made before GOA recorded it
        if (dosSaveStates) main.post {
            com.goa.domain.savestate.SaveStateDefaults.rememberCoreVersion(this, DosBoxEngineBridge.getCoreVersion())
        }
    }
    override fun onResolutionChanged(width: Int, height: Int) {}
    override fun onAudioFormatChanged(sampleRate: Int, channels: Int) {}
    override fun onDosProgramChanged(programName: String) {
        sessionTelemetry?.onProgram(programName)
        // The installer is done and left the player at a DOS prompt: type the commands that start the game
        installerFollowUp?.let { followUp ->
            val commands = synchronized(followUp) { followUp.onProgram(programName) }
            if (commands != null) {
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.delay(600) // let the prompt finish drawing
                    DosCommandTyper.type(commands)
                }
            }
        }
        val prev = lastDosProgram
        lastDosProgram = programName.uppercase()
        val setupPrograms = setOf("SETUP", "SNDSETUP", "SETSOUND", "SETMAIN", "INSTALL", "CONFIG")
        if (prev in setupPrograms && lastDosProgram !in setupPrograms &&
            activeInstallPath.isNotBlank() && ::patchRepository.isInitialized
        ) {
            val installDir = java.io.File(activeInstallPath)
            val confFile = if (activeConfPath.isNotBlank()) java.io.File(activeConfPath) else java.io.File(installDir, "dosbox.conf")
            val preferredSound = if (activeSoundId == 5) {
                com.goa.domain.parser.SoundHardware.GRAVIS_ULTRASOUND
            } else {
                com.goa.domain.parser.SoundHardware.SOUND_BLASTER_16
            }
            patchRepository.applyPatches(
                gameId = activeGameId,
                slug = null,
                installDir = installDir,
                confFile = confFile,
                preferredSoundHardware = preferredSound
            )
        }
    }
    private val filterCatalog by lazy { com.goa.domain.video.FilterCatalog(this) }

    /** Sends a filter (or "off") to the renderer with its default parameter values. */
    private fun applyFilter(id: String) {
        val loaded = filterCatalog.load(id)
        if (loaded == null) {
            DosBoxEngineBridge.setFilter(com.goa.domain.video.FilterCatalog.OFF, "", emptyArray(), emptyArray(), emptyArray(), emptyArray(), IntArray(0), FloatArray(0))
            return
        }
        DosBoxEngineBridge.setFilter(
            id, loaded.json, loaded.vertexSources, loaded.fragmentSources, loaded.lutNames, loaded.lutPixels, loaded.lutSizes,
            loaded.bundle.parameters.map { it.default }.toFloatArray()
        )
    }

    override fun onFilterFailed(id: String, log: String) {
        android.util.Log.e("GoaFilter", "Filter $id failed: $log")
        // Some phones encrypt logcat: keep the driver's message where `adb shell run-as` can read it (debug builds)
        if (com.goa.BuildConfig.DEBUG) runCatching {
            java.io.File(filesDir, "filter-errors.log").appendText("${java.util.Date()} $id\n$log\n\n")
        }
        runOnUiThread {
            android.widget.Toast.makeText(this, "Filter $id can't run on this phone's graphics driver. Showing the plain picture.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onEngineError(message: String) {
        userInitiatedExit = false
        sessionTelemetry?.end(EndReason.ENGINE_ERROR)
    }
    override fun onEngineTerminated(exitCode: Int) {
        // If the engine terminated spontaneously (crash or batch exit without user clicking Exit),
        // it is not a user-initiated exit.
        if (exitCode != 0) {
            userInitiatedExit = false
        }
        returnToLauncher()
    }

    companion object {
        const val STICK_LOOK_SPEED_PREF = "stick_look_speed"
        /** How long the right stick rests after looking up or down before the view recentres (look-spring). */
        const val LOOK_RECENTER_DELAY_MS = 600
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_INSTALL_PATH = "extra_install_path"
        const val EXTRA_CONF_PATH = "extra_conf_path"
        const val EXTRA_SOUND_ID = "extra_sound_id"
        const val EXTRA_CPU_CYCLES = "extra_cpu_cycles"
        const val EXTRA_MEMORY_MB = "extra_memory_mb"
        const val EXTRA_GAME_SLUG = "extra_game_slug"
        const val EXTRA_SCUMMVM_SYSTEM_DIR = "extra_scummvm_system_dir"
        const val EXTRA_SCUMMVM_SAVE_DIR = "extra_scummvm_save_dir"
        const val EXTRA_CORE_LIBRARY = "extra_core_library"
        const val EXTRA_CORE_SYSTEM_DIR = "extra_core_system_dir"
        const val EXTRA_CORE_SAVE_DIR = "extra_core_save_dir"

        fun createIntent(
            context: Context,
            gameId: Long,
            installPath: String,
            confPath: String,
            soundHardwareId: Int,
            cpuCycles: String,
            memoryMb: Int,
            gameSlug: String? = null
        ): Intent {
            return Intent(context, EmulationActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, gameId)
                putExtra(EXTRA_GAME_SLUG, gameSlug)
                putExtra(EXTRA_INSTALL_PATH, installPath)
                putExtra(EXTRA_CONF_PATH, confPath)
                putExtra(EXTRA_SOUND_ID, soundHardwareId)
                putExtra(EXTRA_CPU_CYCLES, cpuCycles)
                putExtra(EXTRA_MEMORY_MB, memoryMb)
            }
        }
    }
}
