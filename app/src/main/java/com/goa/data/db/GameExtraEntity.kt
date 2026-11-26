package com.goa.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ExtraType {
    MANUAL_PDF,
    SOUNDTRACK,
    REFERENCE_CARD,
    WALLPAPER,
    OTHER
}

@Entity(
    tableName = "game_extras",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["gameId"])]
)
data class GameExtraEntity(
    @PrimaryKey val id: String,                         // e.g. "extra_hexen_manual"
    val gameId: Long,                                  // Associated game ID
    val name: String,                                  // e.g. "Manual (PDF)" or "Soundtrack"
    val type: ExtraType,                               // MANUAL_PDF, SOUNDTRACK, REFERENCE_CARD
    val downloadUrl: String,                           // GOG authenticated URL
    val sizeBytes: Long,                               // File size
    val localFilePath: String? = null,                 // Path once downloaded, null if remote
    val isDownloaded: Boolean = false                  // Downloaded state flag
)

