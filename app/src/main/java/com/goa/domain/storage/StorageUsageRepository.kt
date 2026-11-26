package com.goa.domain.storage

import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.data.storage.AppStorageManager
import com.goa.domain.scan.GameSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** The Storage page's three figures. */
data class StorageSummary(val freeBytes: Long, val goaUsedBytes: Long, val tempBytes: Long)

/** One row of the Storage page's game list. [sizeBytes] is null while it is still being measured. */
data class InstalledGameStorage(
    val gameId: Long,
    val title: String,
    val boxArtUrl: String,
    val source: GameSource,
    val sizeBytes: Long?,
    val state: State,
    val locked: Boolean = false
) {
    enum class State { READY, INSTALLING, UNINSTALLING, UNINSTALL_FAILED }
}

/**
 * Works out where GOA's storage goes, from the files themselves: free space, what GOA uses, temporary archives, and
 * each installed game's size (what uninstalling it frees: its install folder plus its temporary archives).
 * Only reads GOA's own app-specific folders.
 */
class StorageUsageRepository(
    private val storage: AppStorageManager,
    /** GOA's internal files folder (ScummVM saves, settings data); null in tests. */
    private val internalFilesDir: File?,
    /** Games with an install in progress or done (GameDao.getActiveInstalls). */
    private val activeInstalls: suspend () -> List<GameEntity>
) {

    suspend fun summary(): StorageSummary = withContext(Dispatchers.IO) {
        val games = activeInstalls()
        val inProgress = games.filter { isInProgress(it.installStatus) }.map { it.id }.toSet()
        val temp = (storage.downloadsDir().listFiles() ?: emptyArray())
            .filter { it.name.toLongOrNull() !in inProgress }
            .sumOf { storage.folderSize(it) }
        StorageSummary(
            freeBytes = storage.getAvailableDiskSpace(),
            goaUsedBytes = storage.folderSize(storage.baseDir) + (separateInternalDir()?.let { storage.folderSize(it) } ?: 0L),
            tempBytes = temp
        )
    }

    /**
     * Every installed or installing game: first all rows unmeasured (in title order), then again as each size is known,
     * and finally once more sorted largest first. Sorting only at the end keeps rows from jumping while they fill in.
     */
    fun installedGames(): Flow<List<InstalledGameStorage>> = flow {
        val games = activeInstalls().sortedBy { it.title.lowercase() }
        var rows = games.map { it.toRow(sizeBytes = null) }
        emit(rows)
        games.forEachIndexed { index, game ->
            val size = sizeOf(game)
            rows = rows.toMutableList().also { it[index] = it[index].copy(sizeBytes = size) }
            emit(rows)
        }
        emit(sortLargestFirst(rows))
    }.flowOn(Dispatchers.IO)

    /** The internal files folder, unless it overlaps the game folder (no external storage: both are filesDir). */
    private fun separateInternalDir(): File? {
        val internal = internalFilesDir?.canonicalFile ?: return null
        val base = storage.baseDir.canonicalFile
        val overlaps = internal.toPath().startsWith(base.toPath()) || base.toPath().startsWith(internal.toPath())
        return if (overlaps) null else internal
    }

    /** What uninstalling this game frees. */
    fun sizeOf(game: GameEntity): Long {
        val installDir = game.localInstallPath?.let { File(it) } ?: File(storage.baseDir, "games/${game.id}")
        return storage.folderSize(installDir) + storage.folderSize(File(storage.downloadsDir(), game.id.toString()))
    }

    /** Deletes temporary archives except those of installs in progress. */
    suspend fun clearTemporaryFiles(): AppStorageManager.CleanResult = withContext(Dispatchers.IO) {
        val inProgress = activeInstalls().filter { isInProgress(it.installStatus) }.map { it.id }.toSet()
        storage.cleanDownloadsExcept(inProgress)
    }

    companion object {
        fun isInProgress(status: InstallStatus) =
            status == InstallStatus.DOWNLOADING || status == InstallStatus.DOWNLOAD_PAUSED || status == InstallStatus.EXTRACTING

        /** Largest first; rows still being measured go last, in the order they came. */
        fun sortLargestFirst(rows: List<InstalledGameStorage>): List<InstalledGameStorage> =
            rows.sortedWith(compareByDescending<InstalledGameStorage> { it.sizeBytes ?: -1L })

        private fun GameEntity.toRow(sizeBytes: Long?) = InstalledGameStorage(
            gameId = id,
            title = title,
            boxArtUrl = boxArtUrl,
            source = GameSource.of(id),
            sizeBytes = sizeBytes,
            state = if (isInProgress(installStatus)) InstalledGameStorage.State.INSTALLING else InstalledGameStorage.State.READY
        )
    }
}
