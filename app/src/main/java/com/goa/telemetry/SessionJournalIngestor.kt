package com.goa.telemetry

import com.goa.data.db.ConfigChangeEventEntity
import com.goa.data.db.PlaySessionEntity
import com.goa.data.db.UploadState
import java.io.File

/**
 * Main-process ingestion of session journals into Room (contracts/session-journal.md, rules 1–6).
 * A journal without an `end` line becomes a crash once it's stale and the :emulator process is gone.
 */
class SessionJournalIngestor(
    private val journalDir: File,
    private val store: Store,
    private val isEmulatorAlive: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val uploadState: () -> UploadState
) {
    interface Store {
        /** Writes the session and its changes in one transaction; false if the session id already exists. */
        suspend fun commit(session: PlaySessionEntity, changes: List<ConfigChangeEventEntity>): Boolean
    }

    data class Ingested(val session: PlaySessionEntity, val fixRequested: Boolean)

    suspend fun ingestAll(): List<Ingested> {
        val files = journalDir.listFiles { f -> f.isFile && f.extension == "jsonl" }?.sortedBy { it.name } ?: return emptyList()
        val ingested = mutableListOf<Ingested>()
        for (file in files) {
            val journal = runCatching { JournalFormat.parse(file.readLines()) }.getOrNull()
            val start = journal?.start
            if (journal == null || start == null) {
                file.delete()                       // rule 4: nothing usable without a start line
                continue
            }
            val ended = journal.endReason != null
            val lastAt = journal.lastLineAt ?: start.startedAt
            val crashed = !ended && now() - lastAt > STALE_MS && !isEmulatorAlive()
            if (!ended && !crashed) continue          // rule 3: still running, or too recent to call

            val endAt = journal.endAt ?: lastAt
            // Input difficulty only counts once per burst; "controls opened after input" links the two signals
            val firstInput = journal.inputBursts.minOrNull()
            val controlsAfterInput = firstInput != null && journal.controlsOpenedAt.any { it >= firstInput }
            val summary = SessionSignals.summarize(
                start, journal.heartbeats, endAt, journal.endReason,
                journal.inputBursts.size, controlsAfterInput, journal.fixRequested
            )
            val upload = uploadState()
            val session = PlaySessionEntity(
                id = start.sessionId,
                gameId = start.gameId,
                engine = start.engine,
                startedAt = start.startedAt,
                endedAt = endAt,
                durationSec = summary.durationSec,
                outcome = summary.outcome,
                targetFps = start.targetFps,
                avgEmuFps = summary.avgEmuFps,
                speedShortfallRatio = summary.speedShortfallRatio,
                stutterRatio = summary.stutterRatio,
                audioSilentRatio = summary.audioSilentRatio,
                underruns = summary.underruns,
                silenceMs = summary.silenceMs,
                inputDifficultyCount = summary.inputDifficultyCount,
                flags = summary.flags.joinToString(",") { it.name },
                configSnapshot = start.config,
                appliedFixId = start.appliedFixId,
                uploadState = upload
            )
            val changes = journal.changes.map { change ->
                ConfigChangeEventEntity(
                    gameId = start.gameId, sessionId = start.sessionId, setting = change.setting,
                    fromValue = change.from, toValue = change.to, origin = change.origin, at = change.at, uploadState = upload
                )
            }
            val isNew = store.commit(session, changes)
            // what the game last showed (its error screen) outlives the journal, for the troubleshooter
            journal.diagnostics?.let { d ->
                SessionDiagnosticsStore(File(journalDir.parentFile, "diagnostics")).save(start.gameId, start.sessionId, summary.outcome.name, d)
            }
            file.delete()                             // rule 5: only after the transaction (or a duplicate)
            if (isNew) ingested += Ingested(session, journal.fixRequested)
        }
        return ingested
    }

    companion object {
        const val STALE_MS = 30_000L
    }
}
