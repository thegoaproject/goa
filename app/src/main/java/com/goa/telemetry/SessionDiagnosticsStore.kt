package com.goa.telemetry

import org.json.JSONObject
import java.io.File

/**
 * The last DOS text screen and core warnings per game, kept after its journal is ingested (journals are deleted), so
 * the troubleshooter can read what the game said when it last failed. Never uploaded as text: it stays on the device.
 */
class SessionDiagnosticsStore(private val dir: File) {
    data class Saved(val sessionId: String, val outcome: String, val at: Long, val screen: String, val log: String)

    fun save(gameId: Long, sessionId: String, outcome: String, d: SessionDiagnostics) {
        runCatching {
            dir.mkdirs()
            File(dir, "$gameId.json").writeText(
                JSONObject().put("sessionId", sessionId).put("outcome", outcome).put("at", d.at)
                    .put("screen", d.screen.take(MAX_CHARS)).put("log", d.log.take(MAX_CHARS)).toString()
            )
        }
    }

    fun load(gameId: Long): Saved? = runCatching {
        val o = JSONObject(File(dir, "$gameId.json").readText())
        Saved(o.getString("sessionId"), o.optString("outcome"), o.optLong("at"), o.optString("screen"), o.optString("log"))
    }.getOrNull()

    companion object {
        private const val MAX_CHARS = 4_000
        fun forFilesDir(filesDir: File) = SessionDiagnosticsStore(File(filesDir, "telemetry/diagnostics"))
    }
}
