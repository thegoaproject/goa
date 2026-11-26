package com.goa.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class InstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    DOWNLOAD_PAUSED,
    EXTRACTING,
    READY_TO_PLAY,
    ERROR
}

/** Where a candidate game from a scanned folder comes from (null source columns = GOG, catalogue or old single imports). */
object SourceKind {
    const val ARCHIVE = "ARCHIVE"      // .7z / .zip
    const val INSTALLER = "INSTALLER"  // .exe installer
    const val FOLDER = "FOLDER"        // an already-extracted DOS game directory
}

@Entity(tableName = "games", indices = [Index(value = ["sourceKey"], unique = true)])
data class GameEntity(
    @PrimaryKey val id: Long,                          // GOG product ID (e.g. 1207658742)
    val title: String,                                 // Game title (e.g. "Hexen: Beyond Heretic")
    val slug: String,                                  // GOG slug (e.g. "hexen_beyond_heretic")
    val boxArtUrl: String,                             // High-res cover art URL
    val bannerUrl: String?,                            // Wide background banner URL
    val description: String,                           // Clean plain-text/HTML synopsis
    val releaseYear: Int?,                             // Original release year (e.g. 1995)
    val isDosNative: Boolean,                          // Filter flag (true for MS-DOS games)
    val downloadSize: Long,                            // Total installer size in bytes
    val installStatus: InstallStatus,                  // Current installation lifecycle state
    val localInstallPath: String? = null,              // Absolute path to extracted DOS directory
    val mainExecutable: String? = null,                // DOS executable (e.g. "HEXEN.EXE")
    val dosboxConfPath: String? = null,                // Path to primary dosbox.conf
    val lastPlayedTimestamp: Long? = null,             // Epoch millis for recent sorting
    val playTimeMinutes: Long = 0L,                    // Accumulated playtime
    val sourceKey: String? = null,                     // Folder candidates: "<folderId>|g:<groupKey>", unique, stable across rescans
    val sourceUri: String? = null,                     // Folder candidates: document Uri of the archive/installer, or the directory
    val sourceKind: String? = null,                    // SourceKind.*
    val sourceFolderId: String? = null,                // Which added folder it was found in (drives remove/rescan)
    @ColumnInfo(defaultValue = "0") val variantCount: Int = 0,  // Revisions of the same game found in the folders
    val pickedVariantKey: String? = null               // The revision the player chose; a rescan keeps it instead of the best-ranked one
)

