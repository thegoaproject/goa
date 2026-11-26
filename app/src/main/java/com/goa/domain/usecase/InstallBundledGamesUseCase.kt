package com.goa.domain.usecase

import android.content.Context
import android.util.Log
import com.goa.data.db.GameDao
import com.goa.data.db.InstallStatus
import com.goa.data.storage.AppStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Automatically extracts bundled DOS game archives from the APK assets
 * directly into the local storage on first launch, making them instantly
 * READY_TO_PLAY with 0 network dependency.
 */
class InstallBundledGamesUseCase(
    private val context: Context,
    private val gameDao: GameDao,
    private val storageManager: AppStorageManager,
    private val extractionManager: ExtractionManager
) {
    companion object {
        private const val TAG = "InstallBundledGames"
        private const val BUNDLED_ASSETS_DIR = "bundled_games"

        // Empty since 11 Nov 2026: the APK ships no games. The bundled OMF 2097 was the 1994 shareware, whose licence
        // asks permission for commercial bundles; the catalogue downloads the freeware full version instead.
        val BUNDLED_GAMES: Map<Long, String> = emptyMap()

        private const val OPT_OUT_PREFS = "goa_bundled_optout"

        private fun optOutPrefs(context: Context) = context.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)

        /** The player uninstalled this bundled game: don't unpack it again on the next launch. */
        fun optOut(context: Context, gameId: Long) {
            if (gameId in BUNDLED_GAMES) optOutPrefs(context).edit().putBoolean(gameId.toString(), true).apply()
        }

        /** The player installs it again on purpose. */
        fun clearOptOut(context: Context, gameId: Long) {
            if (gameId in BUNDLED_GAMES) optOutPrefs(context).edit().remove(gameId.toString()).apply()
        }

        fun isOptedOut(context: Context, gameId: Long) = optOutPrefs(context).getBoolean(gameId.toString(), false)
    }

    suspend fun installBundledGamesIfNeeded() = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        val availableAssets = try {
            assetManager.list(BUNDLED_ASSETS_DIR)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        for ((gameId, assetFileName) in BUNDLED_GAMES) {
            if (!availableAssets.contains(assetFileName)) continue
            if (isOptedOut(context, gameId)) continue // the player removed it; only they bring it back

            val existingGame = gameDao.getGameByIdSync(gameId) ?: continue
            if (existingGame.installStatus == InstallStatus.READY_TO_PLAY) {
                val installDir = storageManager.getGameInstallDir(gameId)
                if (installDir.exists() && (installDir.listFiles()?.isNotEmpty() == true)) {
                    continue // Already installed and valid
                }
            }

            try {
                Log.i(TAG, "Unpacking bundled game: ${existingGame.title} ($assetFileName)")
                val downloadDir = storageManager.getDownloadDir(gameId)
                downloadDir.mkdirs()
                val targetFile = File(downloadDir, assetFileName)

                assetManager.open("$BUNDLED_ASSETS_DIR/$assetFileName").use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val result = extractionManager.extractAndSanitize(gameId)
                if (result.isSuccess) {
                    Log.i(TAG, "Successfully installed bundled game: ${existingGame.title}")
                } else {
                    Log.e(TAG, "Failed to extract bundled game: ${existingGame.title}", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error installing bundled asset $assetFileName for game $gameId", e)
            }
        }
    }
}
