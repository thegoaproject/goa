package com.goa.data.repository

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goa.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fetches the game patch registry (curated and harvested per-game profiles) from GOA's registry service when the app
 * opens, so improved controls and sound settings reach players without an app update. The registry is not bundled in
 * the app: until the first sync completes, games use GOA's genre defaults and pick up their profile on the next launch.
 * A download that is older than the cached copy, empty, or unparseable never replaces it.
 */
class RegistrySync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val base = BuildConfig.REGISTRY_BASE_URL.trimEnd('/')
        if (base.isEmpty()) return Result.success()
        val ctx = applicationContext
        val cache = File(ctx.filesDir, GamePatchRepository.REGISTRY_PATH)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = RegistryAccess(ctx)

        return runCatching {
            // Not a Google Play install (or refused recently): keep whatever is cached and GOA's genre defaults
            val auth = access.authHeaders(base) ?: return Result.success()
            val conn = (URL("$base/v1/registry").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                auth.forEach { (name, value) -> setRequestProperty(name, value) }
                prefs.getString(KEY_ETAG, null)?.takeIf { cache.exists() }?.let { setRequestProperty("If-None-Match", it) }
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        // Session expired or revoked: the retry gets a new one
                        Log.i(TAG, "Registry session no longer valid; renewing")
                        access.dropSession()
                        Result.retry()
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        Log.i(TAG, "Registry unchanged (304)")
                        Result.success()
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val cachedVersion = if (cache.exists()) versionOf(cache.readText()) else 0L
                        val accepted = accept(text, cachedVersion)
                        if (accepted == null) {
                            Log.w(TAG, "Ignoring registry download (older, empty or unreadable); keeping the cached copy")
                        } else {
                            cache.parentFile?.mkdirs()
                            val tmp = File(cache.parentFile, cache.name + ".tmp").apply { writeText(text) }
                            tmp.renameTo(cache)
                            prefs.edit().putString(KEY_ETAG, conn.getHeaderField("ETag")).apply()
                            GamePatchRepository.markProfilesChanged()
                            Log.i(TAG, "Registry v$accepted: updated")
                        }
                        Result.success()
                    }
                    429, in 500..599 -> {
                        Log.w(TAG, "Registry fetch returned $code; will retry")
                        Result.retry()
                    }
                    else -> {
                        Log.w(TAG, "Registry fetch returned $code")
                        Result.success()
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Log.w(TAG, "Registry sync failed (${it.javaClass.simpleName}: ${it.message}); keeping the cached copy", it)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RegistrySync"
        private const val PREFS = "goa_registry"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_CHECK = "last_check"
        private const val MIN_PROFILES = 100
        /** App opens within this window reuse the last check; an unchanged registry is a cheap 304 anyway. */
        private val THROTTLE_MS = TimeUnit.MINUTES.toMillis(10)
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The document's version, or 0 when it can't be read. */
        fun versionOf(text: String): Long = runCatching { JSONObject(text).getLong("documentVersion") }.getOrDefault(0L)

        /**
         * The version to adopt when [text] is a registry document worth replacing the cached copy with, else null:
         * it must parse with the production parser, hold a plausible number of profiles, and be newer than [cachedVersion].
         */
        fun accept(text: String, cachedVersion: Long): Long? {
            val version = versionOf(text)
            if (version <= cachedVersion) return null
            val profiles = runCatching { GamePatchRepository.parseProfilesJson(text) }.getOrNull() ?: return null
            return if (profiles.size >= MIN_PROFILES) version else null
        }

        /** Called whenever the app opens: checks for a newer registry, at most once every few minutes. */
        fun syncOnOpen(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < THROTTLE_MS) return
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(
                // REPLACE: an app open must not be ignored because an earlier attempt is still backing off after a failure
                "goa_registry_sync", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RegistrySync>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }
}
