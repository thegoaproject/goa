package com.goa.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Game health telemetry and AI optimisation.
// Room stores these enums by name.

enum class SessionEngine { DOSBOX_PURE, SCUMMVM, TYRQUAKE, OPENLARA }

enum class SessionOutcome { COMPLETED, CRASHED, EARLY_EXIT, FAILED_TO_START }

enum class SessionFlag { NO_AUDIO, SLOW, INPUT_DIFFICULTY, EARLY_EXIT, CRASH, FIX_REQUESTED }

/** LOCAL_ONLY = no sharing consent; PENDING = queued for the opt-in uploader; SENT = accepted by the collector. */
enum class UploadState { LOCAL_ONLY, PENDING, SENT }

enum class ChangeOrigin { USER_IN_GAME, USER_GAME_PAGE, AI_FIX, AI_UNDO, PUBLISHED_DEFAULT }

enum class DiagnosisTrigger { AUTO_NO_AUDIO, AUTO_CRASHES, AUTO_SLOW, AUTO_INPUT, USER_REQUEST }

enum class DiagnosisState { QUEUED, RUNNING, FIX_APPLIED, FIX_PROPOSED, NO_CONFIDENT_FIX, FAILED }

/** GEMINI_NANO only when the OS already provides it; GOA never downloads a model. */
enum class ModelTier { GEMINI_NANO, RULES_ONLY }

enum class FixStatus { PROPOSED, APPLIED, VERIFIED, KEPT, UNDONE, ROLLED_BACK, DISCARDED }

/** Settings tracked by [ConfigChangeEventEntity.setting]; buttons use [buttonSetting]. */
object TrackedSetting {
    const val STICK_MODE = "STICK_MODE"
    const val SOUND_DEVICE = "SOUND_DEVICE"
    const val CPU_CYCLES = "CPU_CYCLES"
    const val VISUAL_PRESET = "VISUAL_PRESET"
    const val POINTER_MODE = "POINTER_MODE"
    const val BUTTON_PREFIX = "BUTTON:"

    fun buttonSetting(buttonName: String) = BUTTON_PREFIX + buttonName
}

@Entity(
    tableName = "play_sessions",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("uploadState")]
)
data class PlaySessionEntity(
    @PrimaryKey val id: String,                 // UUID from the session journal
    val gameId: Long,
    val engine: SessionEngine,
    val startedAt: Long,
    val endedAt: Long?,                         // crashes: time of the last heartbeat
    val durationSec: Long,
    val outcome: SessionOutcome,
    val targetFps: Double,
    val avgEmuFps: Double,
    val speedShortfallRatio: Double,            // 0..1
    val stutterRatio: Double,                   // 0..1
    val audioSilentRatio: Double,               // 0..1
    val underruns: Long,
    val silenceMs: Long,
    val inputDifficultyCount: Int,
    val flags: String,                          // CSV of SessionFlag names
    val configSnapshot: String,                 // JSON of effective settings at start
    val appliedFixId: String?,
    val uploadState: UploadState
) {
    fun flagSet(): Set<SessionFlag> =
        flags.split(',').mapNotNull { name -> SessionFlag.entries.firstOrNull { it.name == name.trim() } }.toSet()
}

@Entity(
    tableName = "config_change_events",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("sessionId"), Index("uploadState")]
)
data class ConfigChangeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val sessionId: String?,                     // null when changed from the game page
    val setting: String,                        // TrackedSetting value
    val fromValue: String,
    val toValue: String,
    val origin: ChangeOrigin,
    val at: Long,
    val uploadState: UploadState
)

@Entity(
    tableName = "ai_diagnoses",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("state")]
)
data class AiDiagnosisEntity(
    @PrimaryKey val id: String,
    val gameId: Long,
    val trigger: DiagnosisTrigger,
    val state: DiagnosisState,
    val modelTier: ModelTier?,
    val evidenceJson: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val resultFixId: String?,
    val uploadState: UploadState = UploadState.LOCAL_ONLY
)

@Entity(
    tableName = "ai_fixes",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("diagnosisId"), Index("status")]
)
data class AiFixEntity(
    @PrimaryKey val id: String,
    val gameId: Long,
    val diagnosisId: String,
    val candidateId: String,
    val changesJson: String,                    // [{ setting, from, to, reason }]
    val overrideProfileJson: String,            // GamePatchProfile fragment in ai_overrides.json
    val backupRef: String,                      // JSON: conf backup path + sha256 + prior preference values
    val status: FixStatus,
    val baselineJson: String,                   // pre-fix health baseline for RollbackPolicy
    val createdAt: Long,
    val resolvedAt: Long?,
    val modelTier: ModelTier,
    val uploadState: UploadState = UploadState.LOCAL_ONLY
)
