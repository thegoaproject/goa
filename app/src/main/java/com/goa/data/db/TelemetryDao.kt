package com.goa.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    // ---- Sessions ----

    /** IGNORE keeps ingestion idempotent on the journal's sessionId. Returns -1 when the row already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: PlaySessionEntity): Long

    @Query("SELECT * FROM play_sessions WHERE id = :id")
    suspend fun getSession(id: String): PlaySessionEntity?

    @Query("SELECT * FROM play_sessions WHERE gameId = :gameId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentSessions(gameId: Long, limit: Int): List<PlaySessionEntity>

    @Query("SELECT * FROM play_sessions WHERE gameId = :gameId ORDER BY startedAt DESC")
    fun sessionsFlow(gameId: Long): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions WHERE uploadState = 'PENDING' ORDER BY startedAt LIMIT :limit")
    suspend fun pendingSessions(limit: Int = 50): List<PlaySessionEntity>

    @Query("UPDATE play_sessions SET uploadState = 'SENT' WHERE id IN (:ids)")
    suspend fun markSessionsSent(ids: List<String>)

    // ---- Configuration changes ----

    @Insert
    suspend fun insertChange(change: ConfigChangeEventEntity): Long

    @Insert
    suspend fun insertChanges(changes: List<ConfigChangeEventEntity>)

    @Query("SELECT * FROM config_change_events WHERE gameId = :gameId ORDER BY at DESC LIMIT :limit")
    suspend fun recentChanges(gameId: Long, limit: Int): List<ConfigChangeEventEntity>

    @Query("SELECT * FROM config_change_events WHERE gameId = :gameId ORDER BY at DESC LIMIT :limit")
    fun recentChangesFlow(gameId: Long, limit: Int): Flow<List<ConfigChangeEventEntity>>

    @Query("SELECT COUNT(*) FROM config_change_events WHERE gameId = :gameId AND setting = :setting AND origin IN ('USER_IN_GAME', 'USER_GAME_PAGE')")
    suspend fun userChangeCount(gameId: Long, setting: String): Int

    @Query("SELECT COUNT(*) FROM config_change_events WHERE gameId = :gameId AND setting = :setting AND origin = 'PUBLISHED_DEFAULT' AND toValue = :value")
    suspend fun publishedDefaultRecorded(gameId: Long, setting: String, value: String): Int

    /** Only player changes and AI undos are shared (contracts/telemetry-ingest-api.md). */
    @Query("SELECT * FROM config_change_events WHERE uploadState = 'PENDING' AND origin IN ('USER_IN_GAME', 'USER_GAME_PAGE', 'AI_UNDO') ORDER BY at LIMIT :limit")
    suspend fun pendingChanges(limit: Int = 200): List<ConfigChangeEventEntity>

    @Query("UPDATE config_change_events SET uploadState = 'SENT' WHERE id IN (:ids)")
    suspend fun markChangesSent(ids: List<Long>)

    // ---- AI diagnoses ----

    @Upsert
    suspend fun upsertDiagnosis(diagnosis: AiDiagnosisEntity)

    @Query("SELECT * FROM ai_diagnoses WHERE id = :id")
    suspend fun getDiagnosis(id: String): AiDiagnosisEntity?

    @Query("SELECT * FROM ai_diagnoses WHERE gameId = :gameId AND state IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeDiagnosis(gameId: Long): AiDiagnosisEntity?

    /** Diagnoses still queued or running, across all games; drives the "AI optimising" indicator. */
    @Query("SELECT * FROM ai_diagnoses WHERE state IN ('QUEUED', 'RUNNING')")
    fun activeDiagnosesFlow(): Flow<List<AiDiagnosisEntity>>

    @Query("SELECT * FROM ai_diagnoses WHERE gameId = :gameId ORDER BY startedAt DESC")
    fun diagnosesFlow(gameId: Long): Flow<List<AiDiagnosisEntity>>

    // ---- AI fixes ----

    @Upsert
    suspend fun upsertFix(fix: AiFixEntity)

    @Query("SELECT * FROM ai_fixes WHERE id = :id")
    suspend fun getFix(id: String): AiFixEntity?

    @Query("SELECT * FROM ai_fixes WHERE gameId = :gameId AND status IN ('APPLIED', 'VERIFIED') ORDER BY createdAt DESC")
    suspend fun activeFixes(gameId: Long): List<AiFixEntity>

    /** Fixes still in effect (applied, verified or kept), oldest first so newer ones win when layered. */
    @Query("SELECT * FROM ai_fixes WHERE gameId = :gameId AND status IN ('APPLIED', 'VERIFIED', 'KEPT') ORDER BY createdAt ASC")
    suspend fun effectiveFixes(gameId: Long): List<AiFixEntity>

    @Query("SELECT * FROM ai_fixes WHERE status IN ('APPLIED', 'VERIFIED', 'KEPT') ORDER BY createdAt ASC")
    suspend fun allEffectiveFixes(): List<AiFixEntity>

    @Query("SELECT candidateId FROM ai_fixes WHERE gameId = :gameId AND status = 'ROLLED_BACK'")
    suspend fun rolledBackCandidates(gameId: Long): List<String>

    @Query("SELECT * FROM ai_fixes WHERE gameId = :gameId ORDER BY createdAt DESC")
    fun fixesFlow(gameId: Long): Flow<List<AiFixEntity>>

    @Query("SELECT * FROM ai_fixes ORDER BY createdAt DESC")
    fun allFixesFlow(): Flow<List<AiFixEntity>>

    @Query("SELECT * FROM ai_fixes WHERE uploadState = 'PENDING' ORDER BY createdAt LIMIT :limit")
    suspend fun pendingFixOutcomes(limit: Int = 50): List<AiFixEntity>

    @Query("UPDATE ai_fixes SET uploadState = 'SENT' WHERE id IN (:ids)")
    suspend fun markFixOutcomesSent(ids: List<String>)

    // ---- Consent withdrawal (FR-009) ----

    @Query("UPDATE play_sessions SET uploadState = 'LOCAL_ONLY' WHERE uploadState = 'PENDING'")
    suspend fun withdrawPendingSessions()

    @Query("UPDATE config_change_events SET uploadState = 'LOCAL_ONLY' WHERE uploadState = 'PENDING'")
    suspend fun withdrawPendingChanges()

    @Query("UPDATE ai_fixes SET uploadState = 'LOCAL_ONLY' WHERE uploadState = 'PENDING'")
    suspend fun withdrawPendingFixOutcomes()

    @Transaction
    suspend fun withdrawAllPending() {
        withdrawPendingSessions()
        withdrawPendingChanges()
        withdrawPendingFixOutcomes()
    }

    @Transaction
    suspend fun insertSessionWithChanges(session: PlaySessionEntity, changes: List<ConfigChangeEventEntity>): Boolean {
        if (insertSession(session) == -1L) return false
        if (changes.isNotEmpty()) insertChanges(changes)
        return true
    }
}
