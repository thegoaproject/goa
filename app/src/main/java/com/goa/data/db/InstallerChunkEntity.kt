package com.goa.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "installer_chunks",
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
data class InstallerChunkEntity(
    @PrimaryKey val id: String,                         // e.g. "1207658742_part1"
    val gameId: Long,                                  // Associated game ID
    val fileName: String,                              // e.g. "setup_hexen_1.0.exe" or "...-1.bin"
    val downloadUrl: String,                           // Authenticated GOG CDN URL
    val totalBytes: Long,                              // Expected file size
    val downloadedBytes: Long = 0L,                    // Current downloaded bytes
    val isCompleted: Boolean = false,                  // Download completion flag
    val chunkIndex: Int                                // Order index (0 for .exe, 1..N for .bin)
)

