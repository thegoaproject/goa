package com.goa.telemetry

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
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
import com.goa.GoaApplication
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Sends queued health records to the collection service, only while sharing consent is GRANTED (FR-008, FR-011). */
class TelemetryUploader(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? GoaApplication ?: return Result.success()
        val base = BuildConfig.TELEMETRY_BASE_URL.trimEnd('/')
        if (base.isEmpty() || BuildConfig.FREEWARE_CDN_CLIENT_KEY.isEmpty()) return Result.success()
        val prefs = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BLOCKED_VERSION + BuildConfig.VERSION_CODE, false)) return Result.success()

        val dao = app.database.telemetryDao()
        val gameDao = app.database.gameDao()
        var batchLimit = TelemetryPayloadBuilder.MAX_SESSIONS

        repeat(MAX_BATCHES_PER_RUN) {
            // Re-check consent right before every request
            val consent = ConsentRepository(app, dao).current()
            if (consent != ConsentState.GRANTED) return Result.success()

            val sessions = dao.pendingSessions(batchLimit)
            val changes = dao.pendingChanges()
            val fixes = dao.pendingFixOutcomes().map { fix ->
                TelemetryPayloadBuilder.FixOutcome(
                    id = fix.id, gameId = fix.gameId,
                    trigger = dao.getDiagnosis(fix.diagnosisId)?.trigger?.name ?: "USER_REQUEST",
                    modelTier = fix.modelTier.name, candidateId = fix.candidateId, status = fix.status.name,
                    at = fix.resolvedAt ?: fix.createdAt
                )
            }
            if (sessions.isEmpty() && changes.isEmpty() && fixes.isEmpty()) return Result.success()

            val gameIds = (sessions.map { it.gameId } + changes.map { it.gameId } + fixes.map { it.gameId }).toSet()
            val keys = gameIds.associateWith { id -> gameDao.getGameByIdSync(id)?.let { game -> gameKeyFor(id, game.slug) } }
            val payload = TelemetryPayloadBuilder.build(
                consent, AnonymousPlayerId.getOrCreate(app), DeviceProfile.read(app), sessions, changes, fixes
            ) { gameId -> keys[gameId] } ?: return Result.success()

            when (val code = post("$base/v1/sessions", payload.json)) {
                202, 400 -> {
                    // 400: the batch can never validate; drop it rather than retry forever
                    if (code == 400) Log.w(TAG, "Collector rejected a batch as invalid; dropping it")
                    dao.markSessionsSent(payload.sessionIds)
                    dao.markChangesSent(payload.changeIds)
                    dao.markFixOutcomesSent(payload.fixIds)
                }
                403 -> {
                    prefs.edit().putBoolean(KEY_BLOCKED_VERSION + BuildConfig.VERSION_CODE, true).apply()
                    return Result.success()
                }
                413 -> batchLimit = (batchLimit / 2).coerceAtLeast(1)
                else -> return Result.retry()             // 429, 5xx, network errors
            }
        }
        return Result.success()
    }

    private fun post(url: String, json: String): Int = runCatching {
        val body = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("X-GOA-Client", BuildConfig.FREEWARE_CDN_CLIENT_KEY)
            setRequestProperty("X-GOA-App-Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        conn.outputStream.use { it.write(body) }
        conn.responseCode.also { conn.disconnect() }
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "TelemetryUploader"
        private const val STATE_PREFS = "goa_telemetry_state"
        private const val KEY_BLOCKED_VERSION = "upload_blocked_v"
        private const val MAX_BATCHES_PER_RUN = 10
        private const val PERIODIC = "goa_telemetry_upload"
        private const val ONE_OFF = "goa_telemetry_upload_now"

        /** Catalog freeware uses its slug; GOG library titles use their product id (no title, no account data). */
        fun gameKeyFor(gameId: Long, slug: String?): String =
            if (gameId >= 9_000_000_000L && !slug.isNullOrBlank()) slug.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(80) else "gog:$gameId"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TelemetryUploader>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            )
        }

        fun uploadSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_OFF, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TelemetryUploader>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            )
        }
    }
}
