package com.goa.telemetry

import android.content.Context
import com.goa.data.db.SessionEngine
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.ui.emulation.PerfStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Per-session health capture in the :emulator process: owns the session journal, polls GoaPerf every 2 s and writes
 * an aggregated heartbeat every 10 s, and forwards input, controls-screen and fix-request signals.
 */
class SessionTelemetry(
    private val context: Context,
    private val gameId: Long,
    private val engine: SessionEngine,
    private val configJson: String,
    private val appliedFixId: String? = null
) {
    private var journal: SessionJournal? = null
    private var pollJob: Job? = null
    private var ended = false
    @Volatile private var paused = false
    @Volatile private var program = ""

    // Aggregate of the GoaPerf windows since the last heartbeat
    private var windows = 0
    private var emuFpsSum = 0.0
    private var presentFpsSum = 0.0
    private var targetFps = 60.0
    private var minSpeed = Double.MAX_VALUE
    private var lateSum = 0
    private var maxLevel = -120.0
    private var underrunSum = 0
    private var silenceSum = 0

    private val inputTracker = InputDifficultyTracker(
        clock = System::currentTimeMillis,
        frameChangeCount = { runCatching { DosBoxEngineBridge.getFrameChangeCount() }.getOrDefault(0L) },
        onBurst = { at, presses, windowMs, source -> journal?.append(JournalFormat.inputLine(at, presses, windowMs, source)) }
    )

    fun start(scope: CoroutineScope) {
        if (journal != null || gameId <= 0) return
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val firstLaunch = !prefs.getBoolean("launched_$gameId", false)
        prefs.edit().putBoolean("launched_$gameId", true).apply()

        val start = JournalStart(
            sessionId = UUID.randomUUID().toString(),
            gameId = gameId,
            engine = engine,
            startedAt = System.currentTimeMillis(),
            targetFps = 60.0,
            config = configJson,
            appliedFixId = appliedFixId,
            firstLaunch = firstLaunch
        )
        journal = SessionJournal.begin(context, start).also { SessionJournal.active = it }

        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (!paused) sample()
                if (windows >= WINDOWS_PER_HEARTBEAT) writeHeartbeat()
                captureDiagnostics()
            }
        }
    }

    fun setPaused(isPaused: Boolean) {
        if (paused == isPaused) return
        if (windows > 0) writeHeartbeat()
        paused = isPaused
        writeHeartbeat(forcePausedLine = true)
    }

    fun onProgram(name: String) {
        program = name.uppercase()
    }

    /** source: GAMEPAD | TOUCH | POINTER */
    fun onPress(source: String) = inputTracker.onPress(source)

    fun onControlsOpened() {
        journal?.append(JournalFormat.uiLine(System.currentTimeMillis(), "controls"))
    }


    @Synchronized
    fun end(reason: EndReason) {
        captureDiagnostics()
        if (ended) return
        ended = true
        pollJob?.cancel()
        if (windows > 0) writeHeartbeat()
        val effectiveReason = if (reason == EndReason.ENGINE_ERROR && !hasHeartbeat) EndReason.ENGINE_FAILED_START else reason
        journal?.append(JournalFormat.endLine(System.currentTimeMillis(), effectiveReason))
        if (SessionJournal.active === journal) SessionJournal.active = null
    }

    private var hasHeartbeat = false

    @Synchronized
    private fun sample() {
        val stats = runCatching { DosBoxEngineBridge.getPerfStats() }.getOrNull() ?: return
        if (stats.size < PerfStat.COUNT) return
        val target = stats[PerfStat.TARGET_FPS].toDouble()
        if (target <= 0.0) return                       // engine not producing windows yet
        windows++
        targetFps = target
        emuFpsSum += stats[PerfStat.EMU_FPS]
        presentFpsSum += stats[PerfStat.PRESENT_FPS]
        minSpeed = minOf(minSpeed, stats[PerfStat.CORE_SPEED_PCT].toDouble())
        lateSum += stats[PerfStat.LATE_FRAMES].toInt()
        maxLevel = maxOf(maxLevel, stats[PerfStat.AUDIO_LEVEL_DBFS].toDouble())
        underrunSum += stats[PerfStat.UNDERRUNS].toInt()
        silenceSum += stats[PerfStat.SILENCE_MS].toInt()
        gameFpsSum += stats[PerfStat.GAME_FPS]
        cyclesSum += stats[PerfStat.CORE_CYCLES].toDouble()
    }

    private var gameFpsSum = 0.0
    private var cyclesSum = 0.0

    private var lastDiagnostics = ""

    /**
     * The DOS text screen and the core's warnings, written whenever they change (so a native crash still leaves the
     * last error message in the journal). Error screens are what the troubleshooter reads first.
     */
    private fun captureDiagnostics() {
        if (engine != SessionEngine.DOSBOX_PURE) return
        val screen = runCatching { DosBoxEngineBridge.getScreenText() }.getOrDefault("")
        val log = runCatching { DosBoxEngineBridge.getRecentLog() }.getOrDefault("")
        if (screen.isBlank() && log.isBlank()) return
        val key = screen + "\u0000" + log
        if (key == lastDiagnostics) return
        lastDiagnostics = key
        journal?.append(JournalFormat.diagLine(SessionDiagnostics(System.currentTimeMillis(), screen, log)))
    }

    @Synchronized
    private fun writeHeartbeat(forcePausedLine: Boolean = false) {
        val j = journal ?: return
        if (windows == 0 && !forcePausedLine) return
        val n = windows.coerceAtLeast(1)
        j.append(
            JournalFormat.heartbeatLine(
                Heartbeat(
                    at = System.currentTimeMillis(),
                    emuFps = emuFpsSum / n,
                    targetFps = targetFps,
                    late = lateSum,
                    dosSpeedPct = if (minSpeed == Double.MAX_VALUE) 0.0 else minSpeed,
                    presentFps = presentFpsSum / n,
                    audioLevelDbfs = maxLevel,
                    underruns = underrunSum,
                    silenceMs = silenceSum,
                    program = program,
                    paused = paused || windows == 0,
                    gameFps = gameFpsSum / n,
                    cycles = (cyclesSum / n).toLong(),
                    cpu = runCatching { DosBoxEngineBridge.getPerfThreads() }.getOrDefault("")
                )
            )
        )
        if (windows > 0) hasHeartbeat = true
        windows = 0; emuFpsSum = 0.0; presentFpsSum = 0.0; minSpeed = Double.MAX_VALUE
        lateSum = 0; maxLevel = -120.0; underrunSum = 0; silenceSum = 0; gameFpsSum = 0.0; cyclesSum = 0.0
    }

    companion object {
        const val STATE_PREFS = "goa_telemetry_state"
        private const val POLL_MS = 2_000L
        private const val WINDOWS_PER_HEARTBEAT = 5

        /** Sound hardware id (LaunchGameUseCase) to the normalised device id used in config snapshots and changes. */
        fun soundDeviceId(soundHardwareId: Int): String = when (soundHardwareId) {
            0 -> "pas16"; 1 -> "sb16"; 2 -> "sbpro"; 3 -> "mt32"; 4 -> "gm"; 5 -> "gus"; 6 -> "adlib"; else -> "sb16"
        }
    }
}
