package com.goa.telemetry

import com.goa.data.db.ChangeOrigin
import com.goa.data.db.ConfigChangeEventEntity
import com.goa.data.db.PlaySessionEntity
import org.json.JSONArray
import org.json.JSONObject

/** Builds the opt-in upload body (contracts/telemetry-ingest-api.md). Whitelist only: no PII can slip in. */
object TelemetryPayloadBuilder {
    const val MAX_SESSIONS = 50
    const val MAX_CHANGES = 200
    const val MAX_AI_OUTCOMES = 50

    private val SHARED_ORIGINS = setOf(ChangeOrigin.USER_IN_GAME, ChangeOrigin.USER_GAME_PAGE, ChangeOrigin.AI_UNDO)

    /** An AI fix outcome joined with its diagnosis trigger, ready for upload. */
    data class FixOutcome(
        val id: String, val gameId: Long, val trigger: String, val modelTier: String,
        val candidateId: String, val status: String, val at: Long
    )

    /** The JSON plus the local row ids it covers (including rows skipped for unknown games, so they leave the queue). */
    data class Payload(val json: String, val sessionIds: List<String>, val changeIds: List<Long>, val fixIds: List<String>)

    fun build(
        consent: ConsentState,
        playerId: String,
        device: DeviceInfo,
        sessions: List<PlaySessionEntity>,
        changes: List<ConfigChangeEventEntity>,
        fixes: List<FixOutcome>,
        gameKey: (Long) -> String?
    ): Payload? {
        if (consent != ConsentState.GRANTED) return null
        val sessionBatch = sessions.take(MAX_SESSIONS)
        val changeBatch = changes.take(MAX_CHANGES)
        val fixBatch = fixes.take(MAX_AI_OUTCOMES)

        val sessionJson = JSONArray()
        sessionBatch.forEach { s ->
            val key = gameKey(s.gameId) ?: return@forEach
            sessionJson.put(
                JSONObject()
                    .put("id", s.id).put("gameKey", key).put("engine", s.engine.name)
                    .put("startedAt", s.startedAt).put("durationSec", s.durationSec).put("outcome", s.outcome.name)
                    .put("targetFps", s.targetFps).put("avgEmuFps", s.avgEmuFps)
                    .put("speedShortfallRatio", s.speedShortfallRatio).put("stutterRatio", s.stutterRatio)
                    .put("audioSilentRatio", s.audioSilentRatio).put("underruns", s.underruns).put("silenceMs", s.silenceMs)
                    .put("inputDifficultyCount", s.inputDifficultyCount)
                    .put("flags", JSONArray(s.flagSet().map { it.name }))
                    .put("config", runCatching { JSONObject(s.configSnapshot) }.getOrDefault(JSONObject()))
                    .put("appliedFixCandidate", JSONObject.NULL)
            )
        }

        val changeJson = JSONArray()
        changeBatch.filter { it.origin in SHARED_ORIGINS }.forEach { c ->
            val key = gameKey(c.gameId) ?: return@forEach
            changeJson.put(
                JSONObject().put("gameKey", key).put("setting", c.setting).put("from", c.fromValue)
                    .put("to", c.toValue).put("origin", c.origin.name).put("at", c.at)
            )
        }

        val outcomeJson = JSONArray()
        fixBatch.forEach { f ->
            val key = gameKey(f.gameId) ?: return@forEach
            outcomeJson.put(
                JSONObject().put("gameKey", key).put("trigger", f.trigger).put("modelTier", f.modelTier)
                    .put("candidateId", f.candidateId).put("status", f.status).put("at", f.at)
            )
        }

        val json = JSONObject()
            .put("schema", 1)
            .put("playerId", playerId)
            .put(
                "device",
                JSONObject().put("make", device.make).put("model", device.model).put("soc", device.soc)
                    .put("androidApi", device.androidApi).put("abi", device.abi).put("ramGb", device.ramGb)
            )
            .put("sessions", sessionJson)
            .put("configChanges", changeJson)
            .put("aiOutcomes", outcomeJson)
            .toString()
        return Payload(json, sessionBatch.map { it.id }, changeBatch.map { it.id }, fixBatch.map { it.id })
    }
}
