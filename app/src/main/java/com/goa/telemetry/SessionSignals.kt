package com.goa.telemetry

import com.goa.data.db.SessionFlag
import com.goa.data.db.SessionOutcome

/** Session health derived from a journal (thresholds: research R4, data-model.md PlaySession classification). */
data class SessionSummary(
    val outcome: SessionOutcome,
    val durationSec: Long,
    val avgEmuFps: Double,
    val speedShortfallRatio: Double,
    val stutterRatio: Double,
    val audioSilentRatio: Double,
    val underruns: Long,
    val silenceMs: Long,
    val inputDifficultyCount: Int,
    val flags: Set<SessionFlag>
)

object SessionSignals {
    const val EARLY_EXIT_SEC = 60L
    const val AUDIO_GRACE_MS = 30_000L           // silent title screens and menus at the start don't count
    const val SILENT_DBFS = -60.0
    const val NO_AUDIO_RATIO = 0.9
    const val SLOW_RATIO = 0.5
    const val SPEED_OK_PCT = 95.0
    private const val DISPLAY_HZ = 60.0
    private val SETUP_PROGRAMS = listOf("SETUP", "INSTALL", "SETSOUND", "SNDSETUP", "CONFIG")

    fun summarize(
        start: JournalStart,
        heartbeats: List<Heartbeat>,
        endAt: Long,
        endReason: EndReason?,
        inputBursts: Int,
        controlsOpenedAfterInput: Boolean,
        fixRequested: Boolean
    ): SessionSummary {
        val durationSec = ((endAt - start.startedAt).coerceAtLeast(0L)) / 1000
        // A game's own setup screen (silent, static, menu-paced) says nothing about how the game itself runs
        val active = heartbeats.filterNot { it.paused || isSetupProgram(it.program) }

        val audioBeats = active.filter { it.at - start.startedAt > AUDIO_GRACE_MS }
        val audioSilentRatio = ratio(audioBeats) { it.audioLevelDbfs < SILENT_DBFS }

        val speedShortfallRatio = ratio(active) { beat ->
            val speedLow = if (beat.dosSpeedPct > 0) beat.dosSpeedPct < SPEED_OK_PCT else beat.emuFps < beat.targetFps * SPEED_OK_PCT / 100
            speedLow || beat.late > 0
        }
        val stutterRatio = ratio(active) { it.presentFps < 0.9 * minOf(DISPLAY_HZ, it.targetFps) }

        // The game's own first-run setup (Tyrian, One Must Fall) isn't the player giving up
        val ranSetup = start.firstLaunch || heartbeats.any { isSetupProgram(it.program) }
        val outcome = when (endReason) {
            null, EndReason.ENGINE_ERROR -> SessionOutcome.CRASHED
            EndReason.ENGINE_FAILED_START -> SessionOutcome.FAILED_TO_START
            EndReason.USER_EXIT -> if (durationSec < EARLY_EXIT_SEC && !ranSetup) SessionOutcome.EARLY_EXIT else SessionOutcome.COMPLETED
        }

        val flags = buildSet {
            // A first launch is often spent in the game's own (silent, menu-paced) setup, even inside the main program
            if (!start.firstLaunch && audioBeats.isNotEmpty() && audioSilentRatio >= NO_AUDIO_RATIO) add(SessionFlag.NO_AUDIO)
            if (!start.firstLaunch && speedShortfallRatio > SLOW_RATIO) add(SessionFlag.SLOW)
            if (inputBursts >= 1 && (controlsOpenedAfterInput || durationSec < EARLY_EXIT_SEC)) add(SessionFlag.INPUT_DIFFICULTY)
            if (outcome == SessionOutcome.EARLY_EXIT) add(SessionFlag.EARLY_EXIT)
            if (outcome == SessionOutcome.CRASHED) add(SessionFlag.CRASH)
            if (fixRequested) add(SessionFlag.FIX_REQUESTED)
        }

        return SessionSummary(
            outcome = outcome,
            durationSec = durationSec,
            avgEmuFps = if (active.isEmpty()) 0.0 else active.sumOf { it.emuFps } / active.size,
            speedShortfallRatio = speedShortfallRatio,
            stutterRatio = stutterRatio,
            audioSilentRatio = audioSilentRatio,
            underruns = heartbeats.sumOf { it.underruns.toLong() },
            silenceMs = heartbeats.sumOf { it.silenceMs.toLong() },
            inputDifficultyCount = inputBursts,
            flags = flags
        )
    }

    fun isSetupProgram(program: String): Boolean = SETUP_PROGRAMS.any { program.uppercase().startsWith(it) }

    private inline fun ratio(beats: List<Heartbeat>, predicate: (Heartbeat) -> Boolean): Double =
        if (beats.isEmpty()) 0.0 else beats.count(predicate).toDouble() / beats.size
}
