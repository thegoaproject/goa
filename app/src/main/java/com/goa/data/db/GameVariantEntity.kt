package com.goa.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One file or folder that can install a candidate game; the primary one is mirrored into the game's own source columns. */
@Entity(
    tableName = "game_variants",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index(value = ["sourceKey"], unique = true)]
)
data class GameVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val sourceKey: String,       // "<folderId>|<documentId>"
    val sourceUri: String,
    val sourceKind: String,      // SourceKind.*
    val displayName: String,     // original file or folder name
    val sizeBytes: Long,
    val isRev: Boolean,
    val year: Int?,
    val isPrimary: Boolean
)
