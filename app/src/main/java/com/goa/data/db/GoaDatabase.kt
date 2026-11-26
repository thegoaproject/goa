package com.goa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromInstallStatus(status: InstallStatus): String = status.name

    @TypeConverter
    fun toInstallStatus(value: String): InstallStatus = runCatching {
        InstallStatus.valueOf(value)
    }.getOrDefault(InstallStatus.NOT_INSTALLED)

    @TypeConverter
    fun fromExtraType(type: ExtraType): String = type.name

    @TypeConverter
    fun toExtraType(value: String): ExtraType = runCatching {
        ExtraType.valueOf(value)
    }.getOrDefault(ExtraType.OTHER)
}

@Database(
    entities = [
        GameEntity::class,
        InstallerChunkEntity::class,
        GameExtraEntity::class,
        PlaySessionEntity::class,
        ConfigChangeEventEntity::class,
        AiDiagnosisEntity::class,
        AiFixEntity::class,
        GameVariantEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class GoaDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao

    abstract fun telemetryDao(): TelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: GoaDatabase? = null

        fun getInstance(context: Context): GoaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GoaDatabase::class.java,
                    "goa_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}

/** v1 → v2: adds the game health telemetry and AI optimisation tables (additive only; SQL copied from app/schemas/…/2.json). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `play_sessions` (`id` TEXT NOT NULL, `gameId` INTEGER NOT NULL, `engine` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `durationSec` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `targetFps` REAL NOT NULL, `avgEmuFps` REAL NOT NULL, `speedShortfallRatio` REAL NOT NULL, `stutterRatio` REAL NOT NULL, `audioSilentRatio` REAL NOT NULL, `underruns` INTEGER NOT NULL, `silenceMs` INTEGER NOT NULL, `inputDifficultyCount` INTEGER NOT NULL, `flags` TEXT NOT NULL, `configSnapshot` TEXT NOT NULL, `appliedFixId` TEXT, `uploadState` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_sessions_gameId` ON `play_sessions` (`gameId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_sessions_uploadState` ON `play_sessions` (`uploadState`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `config_change_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gameId` INTEGER NOT NULL, `sessionId` TEXT, `setting` TEXT NOT NULL, `fromValue` TEXT NOT NULL, `toValue` TEXT NOT NULL, `origin` TEXT NOT NULL, `at` INTEGER NOT NULL, `uploadState` TEXT NOT NULL, FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_config_change_events_gameId` ON `config_change_events` (`gameId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_config_change_events_sessionId` ON `config_change_events` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_config_change_events_uploadState` ON `config_change_events` (`uploadState`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `ai_diagnoses` (`id` TEXT NOT NULL, `gameId` INTEGER NOT NULL, `trigger` TEXT NOT NULL, `state` TEXT NOT NULL, `modelTier` TEXT, `evidenceJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `resultFixId` TEXT, `uploadState` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_diagnoses_gameId` ON `ai_diagnoses` (`gameId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_diagnoses_state` ON `ai_diagnoses` (`state`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `ai_fixes` (`id` TEXT NOT NULL, `gameId` INTEGER NOT NULL, `diagnosisId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `changesJson` TEXT NOT NULL, `overrideProfileJson` TEXT NOT NULL, `backupRef` TEXT NOT NULL, `status` TEXT NOT NULL, `baselineJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `modelTier` TEXT NOT NULL, `uploadState` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_fixes_gameId` ON `ai_fixes` (`gameId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_fixes_diagnosisId` ON `ai_fixes` (`diagnosisId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_fixes_status` ON `ai_fixes` (`status`)")
    }
}

/** v2 → v3: folder candidates (source columns on games, plus the variants table). Additive only; SQL matches app/schemas/…/3.json. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `games` ADD COLUMN `sourceKey` TEXT")
        db.execSQL("ALTER TABLE `games` ADD COLUMN `sourceUri` TEXT")
        db.execSQL("ALTER TABLE `games` ADD COLUMN `sourceKind` TEXT")
        db.execSQL("ALTER TABLE `games` ADD COLUMN `sourceFolderId` TEXT")
        db.execSQL("ALTER TABLE `games` ADD COLUMN `variantCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `games` ADD COLUMN `pickedVariantKey` TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_sourceKey` ON `games` (`sourceKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `game_variants` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gameId` INTEGER NOT NULL, `sourceKey` TEXT NOT NULL, `sourceUri` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `isRev` INTEGER NOT NULL, `year` INTEGER, `isPrimary` INTEGER NOT NULL, FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_variants_gameId` ON `game_variants` (`gameId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_variants_sourceKey` ON `game_variants` (`sourceKey`)")
    }
}
