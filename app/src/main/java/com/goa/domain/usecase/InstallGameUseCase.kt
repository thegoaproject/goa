package com.goa.domain.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.goa.data.db.GameDao
import com.goa.data.db.InstallStatus
import com.goa.data.downloader.GameDownloadWorker
import com.goa.data.storage.AppStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstallGameUseCase(
    private val context: Context,
    private val gameDao: GameDao,
    private val storageManager: AppStorageManager,
    private val extractionManager: ExtractionManager,
    /** Throws RegistrationRequiredException when the player has no free game slot (LicenseManager.requireSlot). */
    private val requireSlot: suspend (gameId: Long?) -> Unit = {},
    /** Installs candidates found in scanned folders (games with a source Uri); null where folders aren't supported. */
    private val localSourceInstaller: LocalSourceInstaller? = null
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Why a background install of a folder candidate failed, for the UI to show (the status alone can't say). */
    val messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    suspend fun startInstall(gameId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val game = gameDao.getGameByIdSync(gameId)
                ?: throw IllegalArgumentException("Game not found: $gameId")
            requireSlot(gameId)
            InstallBundledGamesUseCase.clearOptOut(context, gameId)

            // A game found in a scanned folder installs from there, in the background, so leaving the screen can't stop it
            val sourceUri = game.sourceUri
            val sourceKind = game.sourceKind
            if (sourceUri != null && sourceKind != null) {
                if (game.installStatus != InstallStatus.NOT_INSTALLED && game.installStatus != InstallStatus.ERROR) return@runCatching
                val installer = localSourceInstaller ?: throw IllegalStateException("Installing from a folder isn't available.")
                gameDao.updateStatus(gameId, InstallStatus.DOWNLOADING)   // takes the slot and shows progress immediately
                scope.launch {
                    installer.install(gameId, sourceKind, sourceUri, game.downloadSize).onFailure {
                        messages.tryEmit(installFailureMessage(game.title, it))
                    }
                }
                return@runCatching
            }

            // Ensure sufficient space (download + extraction buffer)
            val requiredSpace = (game.downloadSize * 2.2).toLong()
            if (!storageManager.hasSufficientSpace(requiredSpace)) {
                throw IllegalStateException("Insufficient disk space. At least ${(requiredSpace / (1024 * 1024))} MB required.")
            }

            val downloadDir = storageManager.getDownloadDir(gameId)
            val downloadedFiles = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val totalDownloaded = downloadedFiles.sumOf { it.length() }
            if (downloadedFiles.isNotEmpty() && (game.downloadSize <= 0 || totalDownloaded >= (game.downloadSize * 0.90))) {
                // Already downloaded installer, run extraction directly!
                val extractResult = extractionManager.extractAndSanitize(gameId)
                if (extractResult.isFailure) {
                    gameDao.updateStatus(gameId, InstallStatus.ERROR)
                    throw extractResult.exceptionOrNull() ?: RuntimeException("Extraction failed")
                }
                return@runCatching
            }

            val chunks = gameDao.getChunksForGameSync(gameId)
            if (chunks.isEmpty()) {
                throw IllegalStateException("No download installer chunks available for this game.")
            }

            gameDao.updateStatus(gameId, InstallStatus.DOWNLOADING)

            var continuation = workManager.beginUniqueWork(
                "install_$gameId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GameDownloadWorker>()
                    .setInputData(
                        workDataOf(
                            GameDownloadWorker.KEY_GAME_ID to gameId,
                            GameDownloadWorker.KEY_CHUNK_ID to chunks[0].id,
                            GameDownloadWorker.KEY_DOWNLOAD_URL to chunks[0].downloadUrl,
                            GameDownloadWorker.KEY_TARGET_FILENAME to chunks[0].fileName
                        )
                    )
                    .build()
            )

            for (i in 1 until chunks.size) {
                continuation = continuation.then(
                    OneTimeWorkRequestBuilder<GameDownloadWorker>()
                        .setInputData(
                            workDataOf(
                                GameDownloadWorker.KEY_GAME_ID to gameId,
                                GameDownloadWorker.KEY_CHUNK_ID to chunks[i].id,
                                GameDownloadWorker.KEY_DOWNLOAD_URL to chunks[i].downloadUrl,
                                GameDownloadWorker.KEY_TARGET_FILENAME to chunks[i].fileName
                            )
                        )
                        .build()
                )
            }

            continuation.enqueue()

            // Observe completion to trigger extraction
            scope.launch {
                workManager.getWorkInfosForUniqueWorkFlow("install_$gameId")
                    .filter { workInfos ->
                        workInfos.isNotEmpty() && (workInfos.all { it.state == WorkInfo.State.SUCCEEDED } || workInfos.any { it.state == WorkInfo.State.FAILED })
                    }
                    .firstOrNull()
                    ?.let { workInfos ->
                        if (workInfos.all { it.state == WorkInfo.State.SUCCEEDED }) {
                            val extractResult = extractionManager.extractAndSanitize(gameId)
                            extractResult.onFailure { err ->
                                android.util.Log.e("GOA", "Extraction failed for $gameId", err)
                                gameDao.updateStatus(gameId, InstallStatus.ERROR)
                            }
                        } else {
                            gameDao.updateStatus(gameId, InstallStatus.ERROR)
                        }
                    }
            }

            Unit
        }
    }

    suspend fun cancelInstall(gameId: Long) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork("install_$gameId")
        storageManager.cleanDownloads(gameId)
        gameDao.updateStatus(gameId, InstallStatus.NOT_INSTALLED)
    }

    /**
     * Deletes the game's files and marks it not installed. Returns false when some files couldn't be removed: the game
     * then keeps its status (it isn't shown as uninstalled with files left behind), and uninstalling can be retried.
     */
    suspend fun uninstallGame(gameId: Long): Boolean = withContext(Dispatchers.IO) {
        // An install still queued or running (a retry waiting for the network) would otherwise finish afterwards and
        // mark the game installed again, with its files gone
        workManager.cancelUniqueWork("install_$gameId")
        InstallBundledGamesUseCase.optOut(context, gameId)
        val removed = storageManager.deleteGameFiles(gameId)
        if (removed) {
            gameDao.updateInstallDetails(
                id = gameId,
                status = InstallStatus.NOT_INSTALLED,
                localInstallPath = null,
                mainExecutable = null,
                dosboxConfPath = null
            )
        }
        removed
    }

    fun observeDownloadProgress(gameId: Long): Flow<Int> {
        return workManager.getWorkInfosForUniqueWorkFlow("install_$gameId").map { workInfos ->
            val active = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            active?.progress?.getInt(GameDownloadWorker.KEY_PROGRESS_PERCENT, 0) ?: 0
        }
    }
}

/** The sentence shown when a folder install fails; source and space problems already read well on their own. */
fun installFailureMessage(title: String, error: Throwable): String = when (error) {
    is SourceUnavailableException, is NotEnoughSpaceException -> error.message ?: "Couldn't install $title."
    else -> "Couldn't install $title: ${error.message ?: "something went wrong"}. You can try again."
}
