package com.goa.data.repository

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goa.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fetches community defaults published by the team (contracts/published-defaults.md) so improved settings reach players
 * without an app update (FR-015). Older documents and unparseable downloads never replace the cache.
 */
class PublishedDefaultsSync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val base = BuildConfig.FREEWARE_CDN_BASE_URL.trimEnd('/')
        if (base.isEmpty() || BuildConfig.FREEWARE_CDN_CLIENT_KEY.isEmpty()) return Result.success()
        val ctx = applicationContext
        val cache = File(ctx.filesDir, GamePatchRepository.PUBLISHED_DEFAULTS_PATH)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return runCatching {
            val conn = (URL("$base/${GamePatchRepository.PUBLISHED_DEFAULTS_PATH}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("X-GOA-Client", BuildConfig.FREEWARE_CDN_CLIENT_KEY)
                prefs.getString(KEY_ETAG, null)?.takeIf { cache.exists() }?.let { setRequestProperty("If-None-Match", it) }
            }
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                HttpURLConnection.HTTP_OK -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val incoming = JSONObject(text)
                    val cachedVersion = runCatching { JSONObject(cache.readText()).getInt("documentVersion") }.getOrDefault(0)
                    // Parse with the production parser before trusting it
                    GamePatchRepository.parseProfilesJson(text)
                    if (incoming.getInt("documentVersion") >= cachedVersion) {
                        cache.parentFile?.mkdirs()
                        File(cache.parentFile, cache.name + ".tmp").apply { writeText(text) }.renameTo(cache)
                        prefs.edit().putString(KEY_ETAG, conn.getHeaderField("ETag")).apply()
                    } else {
                        Log.w(TAG, "Ignoring published defaults v${incoming.getInt("documentVersion")} older than cached v$cachedVersion")
                    }
                }
                else -> Log.w(TAG, "Published defaults fetch returned ${conn.responseCode}")
            }
            conn.disconnect()
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            Result.success()
        }.getOrElse {
            Log.w(TAG, "Published defaults sync failed; keeping the cached copy", it)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PublishedDefaultsSync"
        private const val PREFS = "goa_published_defaults"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_SYNC = "last_sync"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Daily sync, plus an immediate one when the cache is more than a day old. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "goa_published_defaults", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PublishedDefaultsSync>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            )
            val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L)
            if (System.currentTimeMillis() - last > TimeUnit.DAYS.toMillis(1)) {
                wm.enqueueUniqueWork(
                    "goa_published_defaults_now", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<PublishedDefaultsSync>().setConstraints(constraints).build()
                )
            }
        }
    }
}
