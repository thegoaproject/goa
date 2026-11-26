package com.goa.domain.controls

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goa.engine.input.GamepadPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SlmManualScanWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SlmManualScanWorker"
        const val KEY_GAME_ID = "game_id"
        const val KEY_GAME_SLUG = "game_slug"
        const val KEY_GAME_TITLE = "game_title"
        const val KEY_INSTALL_DIR = "install_dir"

        fun enqueue(
            context: Context,
            gameId: Long,
            slug: String?,
            title: String?,
            installDir: File
        ) {
            val data = workDataOf(
                KEY_GAME_ID to gameId,
                KEY_GAME_SLUG to (slug ?: ""),
                KEY_GAME_TITLE to (title ?: ""),
                KEY_INSTALL_DIR to installDir.absolutePath
            )
            val request = OneTimeWorkRequestBuilder<SlmManualScanWorker>()
                .setInputData(data)
                .addTag("slm_scan_$gameId")
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Enqueued SLM manual scan worker for game $gameId ($title)")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val gameId = inputData.getLong(KEY_GAME_ID, -1L)
        if (gameId == -1L) return@withContext Result.failure()

        val slug = inputData.getString(KEY_GAME_SLUG)
        val title = inputData.getString(KEY_GAME_TITLE)
        val installDirPath = inputData.getString(KEY_INSTALL_DIR) ?: return@withContext Result.failure()
        val installDir = File(installDirPath)

        if (GamepadPreferences.hasUserCustomizedControls(applicationContext, gameId)) {
            Log.i(TAG, "Game $gameId has user customizations; skipping automated SLM scan.")
            return@withContext Result.success()
        }

        try {
            val resolver = ControlSchemeResolver(applicationContext)
            val scheme = resolver.resolveAndApplyScheme(
                gameId = gameId,
                slug = slug,
                title = title,
                installDir = installDir,
                overwriteUser = false
            )
            Log.i(TAG, "SLM scan resolved controls for game $gameId: ${scheme.stickMode.id} via ${scheme.source} (${scheme.summary})")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error performing background manual scan for game $gameId", e)
            Result.failure()
        }
    }
}

