package com.goa.telemetry

import android.content.Context
import com.goa.data.db.ChangeOrigin
import com.goa.data.db.SessionEngine
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Session journal: append-only JSONL written by the
// :emulator process, ingested by the main process. One file per session, so an emulator crash loses at most 10 s.

enum class EndReason { USER_EXIT, ENGINE_ERROR, ENGINE_FAILED_START }

data class JournalStart(
    val sessionId: String,
    val gameId: Long,
    val engine: SessionEngine,
    val startedAt: Long,
    val targetFps: Double,
    val config: String,          // JSON object of effective settings
    val appliedFixId: String?,
    val firstLaunch: Boolean
)

/** One 10 s heartbeat, aggregated from five 2 s GoaPerf windows. */
data class Heartbeat(
    val at: Long,
    val emuFps: Double,
    val targetFps: Double,
    val late: Int,
    val dosSpeedPct: Double,     // 0 = core doesn't report speed (ScummVM)
    val presentFps: Double,
    val audioLevelDbfs: Double,  // loudest level the game produced; -120 = silence
    val underruns: Int,
    val silenceMs: Int,
    val program: String,
    val paused: Boolean,
    val gameFps: Double = 0.0,   // frames the game itself drew (DOSBox Pure perfstats)
    val cycles: Long = 0,        // emulated CPU cycles per ms DOSBox Pure ran at
    val cpu: String = ""         // where the emulation threads ran: "dos=cpu7 3.2/4.3GHz frame=cpu4 ... boost"
)

data class JournalConfigChange(val at: Long, val setting: String, val from: String, val to: String, val origin: ChangeOrigin)

/** Everything read back from one journal file. */
data class ParsedJournal(
    val start: JournalStart?,
    val heartbeats: List<Heartbeat>,
    val changes: List<JournalConfigChange>,
    val inputBursts: List<Long>,
    val controlsOpenedAt: List<Long>,
    val fixRequested: Boolean,
    val endAt: Long?,
    val endReason: EndReason?,
    val lastLineAt: Long?,
    /** The last DOS text screen and core warnings seen in the session (null when none were captured). */
    val diagnostics: SessionDiagnostics? = null
)

/** What the game last showed on its text screen and what the core last warned about, for the troubleshooter. */
data class SessionDiagnostics(val at: Long, val screen: String, val log: String)

object JournalFormat {
    const val DIR = "telemetry/journal"

    fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun startLine(s: JournalStart): String = JSONObject()
        .put("t", "start").put("sessionId", s.sessionId).put("gameId", s.gameId).put("engine", s.engine.name)
        .put("startedAt", s.startedAt).put("targetFps", s.targetFps)
        .put("config", runCatching { JSONObject(s.config) }.getOrDefault(JSONObject()))
        .put("appliedFixId", s.appliedFixId ?: JSONObject.NULL).put("firstLaunch", s.firstLaunch)
        .toString()

    fun heartbeatLine(h: Heartbeat): String = JSONObject()
        .put("t", "hb").put("at", h.at).put("emuFps", h.emuFps).put("targetFps", h.targetFps).put("late", h.late)
        .put("dosSpeedPct", h.dosSpeedPct).put("presentFps", h.presentFps).put("audioLevelDbfs", h.audioLevelDbfs)
        .put("underruns", h.underruns).put("silenceMs", h.silenceMs).put("program", h.program).put("paused", h.paused)
        .put("gameFps", h.gameFps).put("cycles", h.cycles).put("cpu", h.cpu)
        .toString()

    fun configLine(c: JournalConfigChange): String = JSONObject()
        .put("t", "cfg").put("at", c.at).put("setting", c.setting).put("from", c.from).put("to", c.to).put("origin", c.origin.name)
        .toString()

    fun inputLine(at: Long, presses: Int, windowMs: Long, source: String): String = JSONObject()
        .put("t", "input").put("at", at).put("presses", presses).put("windowMs", windowMs).put("source", source)
        .toString()

    fun uiLine(at: Long, what: String): String = JSONObject().put("t", "ui").put("at", at).put("what", what).toString()

    fun fixRequestLine(at: Long): String = JSONObject().put("t", "fix_req").put("at", at).toString()

    fun diagLine(d: SessionDiagnostics): String =
        JSONObject().put("t", "diag").put("at", d.at).put("screen", d.screen).put("log", d.log).toString()

    fun endLine(at: Long, reason: EndReason): String = JSONObject().put("t", "end").put("at", at).put("reason", reason.name).toString()

    /** Parses a journal, skipping lines that are corrupt (e.g. half-written at crash time). */
    fun parse(lines: List<String>): ParsedJournal {
        var start: JournalStart? = null
        val beats = mutableListOf<Heartbeat>()
        val changes = mutableListOf<JournalConfigChange>()
        val inputs = mutableListOf<Long>()
        val controls = mutableListOf<Long>()
        var fixRequested = false
        var endAt: Long? = null
        var endReason: EndReason? = null
        var lastAt: Long? = null
        var diag: SessionDiagnostics? = null

        for (line in lines) {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            runCatching {
                when (o.getString("t")) {
                    "start" -> {
                        start = JournalStart(
                            sessionId = o.getString("sessionId"),
                            gameId = o.getLong("gameId"),
                            engine = SessionEngine.valueOf(o.getString("engine")),
                            startedAt = o.getLong("startedAt"),
                            targetFps = o.optDouble("targetFps", 60.0),
                            config = o.optJSONObject("config")?.toString() ?: "{}",
                            appliedFixId = if (o.isNull("appliedFixId")) null else o.optString("appliedFixId").ifBlank { null },
                            firstLaunch = o.optBoolean("firstLaunch", false)
                        )
                        lastAt = maxOf(lastAt ?: 0L, o.getLong("startedAt"))
                    }
                    "hb" -> {
                        beats += Heartbeat(
                            at = o.getLong("at"), emuFps = o.getDouble("emuFps"), targetFps = o.getDouble("targetFps"),
                            late = o.getInt("late"), dosSpeedPct = o.getDouble("dosSpeedPct"), presentFps = o.getDouble("presentFps"),
                            audioLevelDbfs = o.getDouble("audioLevelDbfs"), underruns = o.getInt("underruns"),
                            silenceMs = o.getInt("silenceMs"), program = o.optString("program"), paused = o.optBoolean("paused"),
                            gameFps = o.optDouble("gameFps", 0.0), cycles = o.optLong("cycles", 0L), cpu = o.optString("cpu")
                        )
                        lastAt = maxOf(lastAt ?: 0L, o.getLong("at"))
                    }
                    "cfg" -> {
                        changes += JournalConfigChange(
                            o.getLong("at"), o.getString("setting"), o.getString("from"), o.getString("to"),
                            ChangeOrigin.valueOf(o.getString("origin"))
                        )
                        lastAt = maxOf(lastAt ?: 0L, o.getLong("at"))
                    }
                    "input" -> { inputs += o.getLong("at"); lastAt = maxOf(lastAt ?: 0L, o.getLong("at")) }
                    "ui" -> { controls += o.getLong("at"); lastAt = maxOf(lastAt ?: 0L, o.getLong("at")) }
                    "fix_req" -> { fixRequested = true; lastAt = maxOf(lastAt ?: 0L, o.getLong("at")) }
                    "diag" -> diag = SessionDiagnostics(o.getLong("at"), o.optString("screen"), o.optString("log"))
                    "end" -> {
                        endAt = o.getLong("at")
                        endReason = EndReason.valueOf(o.getString("reason"))
                        lastAt = maxOf(lastAt ?: 0L, o.getLong("at"))
                    }
                }
            }
        }
        return ParsedJournal(start, beats, changes, inputs, controls, fixRequested, endAt, endReason, lastAt, diag)
    }
}

/**
 * Writer used by EmulationActivity in the :emulator process. Every line is flushed and synced before returning so a
 * native crash right after still leaves a readable journal.
 */
class SessionJournal(private val file: File) {

    val sessionId: String get() = file.nameWithoutExtension

    @Synchronized
    fun append(line: String) {
        runCatching {
            FileOutputStream(file, true).use { out ->
                out.write((line + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        }
    }

    companion object {
        /** Opens a new journal and writes its start line. */
        fun begin(context: Context, start: JournalStart): SessionJournal =
            SessionJournal(File(JournalFormat.directory(context), "${start.sessionId}.jsonl")).also {
                it.append(JournalFormat.startLine(start))
            }

        /** The journal of the session running in this process, so recorders anywhere in the process can append to it. */
        @Volatile
        var active: SessionJournal? = null
    }
}
