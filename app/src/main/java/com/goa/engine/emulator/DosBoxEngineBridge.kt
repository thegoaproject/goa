package com.goa.engine.emulator

import android.view.Surface

interface DosBoxStateListener {
    fun onEngineStarted()
    fun onResolutionChanged(width: Int, height: Int)
    fun onAudioFormatChanged(sampleRate: Int, channels: Int)
    fun onDosProgramChanged(programName: String) {}
    fun onEngineError(message: String)
    fun onEngineTerminated(exitCode: Int)
    /** A video filter can't be compiled on this phone's GPU; the plain picture is shown instead (FR-004). */
    fun onFilterFailed(id: String, log: String) {}
}

object DosBoxEngineBridge {
    init {
        try {
            System.loadLibrary("dosbox_engine")
        } catch (e: UnsatisfiedLinkError) {
            // Allows unit tests to run without loading native lib
        }
    }

    /**
     * Selects the libretro core ("libdosbox_pure.so" by default, "libscummvm_libretro.so" for ScummVM games) and its
     * support-data/save directories. Call before [initEngine].
     */
    external fun configureCore(libraryName: String, systemDir: String, saveDir: String)

    /**
     * Latest 2-second performance window (also logged under logcat tag "GoaPerf"). Index with [PerfStat].
     */
    external fun getPerfStats(): FloatArray

    /**
     * Running count of presented frames whose content differed from the previous one. Telemetry compares it across a
     * burst of presses to tell whether the game reacted to input.
     */
    external fun getFrameChangeCount(): Long

    /** The last DOS text screen (80x25, read from the frame against the VGA font); empty outside text modes. */
    external fun getScreenText(): String

    /** The core's recent warnings and errors, one per line. */
    external fun getRecentLog(): String

    /** Which CPU core and clock each emulation thread last ran on, and whether PerfBoost is asking for big cores. */
    external fun getPerfThreads(): String

    /**
     * Build-engine mouse aiming: holds the game's Mouse_Aiming key (ScrLck after patching) and turns right-stick Y and
     * gyro pitch into vertical mouse motion, which the game maps to smooth analog look up/down.
     */
    external fun setMouseLook(enabled: Boolean)

    /**
     * Mouse look through a held key (DOS Quake's +mlook, a DOS scancode as [sendKeyEvent] takes): with [setMouseLook]
     * on, the key is held only while the right stick or gyro moves the view up or down, and let go a moment after, so
     * the game's vertical mouse never walks and its view centring works. 0 = the game aims without a key (Build).
     */
    external fun setMouseLookKey(dosScancode: Int)

    /** The vertical look deadzone (LookDeadzone, 0-0.6): right-stick Y inside it doesn't look up or down. */
    external fun setLookDeadzoneY(deadzone: Float)

    /** Right-stick vertical look rate in mouse-aim games, 1.0 = default. */
    external fun setStickLookSpeed(speed: Float)

    /**
     * Look-spring for key-look games (Heretic, Hexen) and mouse-aim Build games (Duke, Shadow Warrior): [dosScancode]
     * (as [sendKeyEvent] takes) is tapped once the right stick has been at rest for [delayMs] after looking up or down.
     * 0 switches it off.
     */
    external fun setLookRecenter(dosScancode: Int, delayMs: Int)

    /**
     * Initializes the emulator instance and passes GOG configuration parameters.
     */
    external fun initEngine(
        gameDirectory: String,
        dosboxConfPath: String,
        soundHardwareId: Int,       // 0=PAS16, 1=SB16, 2=SBPro, 3=MT32, 4=GeneralMIDI
        cpuCycles: String,          // "auto", "max", or integer string
        memoryMb: Int,
        listener: DosBoxStateListener
    ): Boolean

    /**
     * Binds the Android SurfaceView hardware surface for Vulkan/GLES rendering.
     */
    external fun setSurface(surface: Surface?): Boolean

    /**
     * Sets the video filter (specs/008-shader-filters/contracts/native-bridge.md): a bundle read by FilterCatalog, or
     * id "off" with empty arrays for the plain picture. Applied by the render thread at its next frame; until then the
     * previous filter keeps drawing. [paramValues] are in the bundle's `parameters` order.
     */
    external fun setFilter(
        id: String,
        json: String,
        vertexSources: Array<String>,
        fragmentSources: Array<String>,
        lutNames: Array<String>,
        lutPixels: Array<java.nio.ByteBuffer>,
        lutSizes: IntArray,
        paramValues: FloatArray
    )

    /** Changes one parameter of the current filter (index in its `parameters`), from the next frame. */
    external fun setFilterParam(index: Int, value: Float)

    /** VGA double-scan for DOS CRT: frames under 400 lines are drawn with each row twice before filtering. */
    external fun setDoubleScan(enabled: Boolean)

    /** "Fill the screen": stretch to the whole surface instead of keeping the 4:3 shape. */
    external fun setFillScreen(enabled: Boolean)

    /**
     * How the audio output opens (☰ → Audio): 0 = Stable (what GOA always did), 1 = Low latency. Call before initEngine.
     */
    external fun setAudioLatencyMode(mode: Int)

    /**
     * Updates post-processing upscaler and presentation shaders.
     *
     * @param presetId 0=Clean Modern (SGSR/FSR), 1=Sharp Retro, 2=Classic CRT, 3=Stretch
     * @param isSnapdragonGpu true if Qualcomm Adreno GPU detected
     * @param sharpness 0.0 to 1.0
     */
    external fun updateVisualPreset(
        presetId: Int,
        isSnapdragonGpu: Boolean,
        sharpness: Float
    )

    /**
     * Dispatches physical gamepad or virtual touch key events to DOS keyboard/joystick interrupts.
     */
    external fun sendKeyEvent(dosScancode: Int, isDown: Boolean)

    /**
     * Dispatches normalized dual-analog stick coordinates (-1.0 to 1.0):
     * - In FPS Mode: Left Stick moves/strafes, Right Stick looks via mouse yaw.
     * - In Classic Keyboard Mode: Left Stick sends pure DOS arrow keys (Up/Down/Left/Right).
     */
    external fun sendAnalogSticks(leftX: Float, leftY: Float, rightX: Float, rightY: Float)

    /**
     * Sets the analog stick interpretation mode:
     * 0 = FPS Dual-Stick (Move + Mouse Look)
     * 1 = Classic Keyboard (Direct DOS Arrow Keys)
     */
    external fun setStickMode(mode: Int)
    external fun sendJoypadButton(buttonId: Int, isDown: Boolean)

    /**
     * Dispatches relative mouse motion (from touch drag or gyroscope aiming).
     */
    external fun sendRelativeMouse(deltaX: Float, deltaY: Float)

    /**
     * Dispatches continuous motion pitch (-1.0 to 1.0) derived from physical device tilt.
     * Operates identically to the right analog stick Y axis for vertical look in Build/Doom/Heretic.
     */
    external fun sendMotionPitch(pitchY: Float)

    /**
     * Dispatches mouse button events (left=1, right=2, middle=4).
     */
    external fun sendMouseButton(buttonMask: Int, isDown: Boolean)

    /**
     * Tap-to-point for DOS games that read mouse motion and draw their own cursor (DOSBox Pure's direct mode alone
     * leaves those cursors where they were): moves the cursor to ([u], [v]), 0-1 across the game surface view, and
     * holds a left click sent meanwhile until it is there. DOSBox core only; a no-op elsewhere.
     */
    external fun pointMouseAt(u: Float, v: Float)

    /**
     * Dispatches mouse wheel events (+1 = up, -1 = down).
     */
    external fun sendMouseWheel(deltaLines: Int)

    /**
     * Dispatches absolute pointer coordinates (normalized from -1.0 to 1.0) and pressed state.
     */
    external fun sendPointerAbsolute(normalizedX: Float, normalizedY: Float, isPressed: Boolean)

    /**
     * Switches the DOS mouse between direct (cursor jumps to the absolute pointer position, for
     * finger/stylus taps) and relative (hardware mouse, virtual-mouse stick). No-op if unchanged.
     */
    external fun setDirectMouse(enabled: Boolean)

    /**
     * Pauses/Resumes emulation loop (called when InGameMenuDialog is active).
     */
    external fun setPaused(paused: Boolean)

    /**
     * Triggers quick save or quick load.
     */
    external fun saveState(slotIndex: Int, savePath: String): Boolean
    external fun loadState(slotIndex: Int, savePath: String): Boolean

    /**
     * The running core's version (libretro `library_version`, e.g. "1.0-preview7"), recorded with each save state.
     * Empty before a core has loaded.
     */
    external fun getCoreVersion(): String

    /**
     * Shuts down the engine and flushes save files to disk.
     */
    external fun terminateEngine()
}

