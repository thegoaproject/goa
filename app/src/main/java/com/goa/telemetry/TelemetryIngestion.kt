package com.goa.telemetry

import android.app.ActivityManager
import android.content.Context
import com.goa.GoaApplication
import com.goa.data.db.ConfigChangeEventEntity
import com.goa.data.db.PlaySessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-process entry point that turns finished (or crashed) session journals into Room rows. */
object TelemetryIngestion {

    private val mutex = Mutex()

    /** Post-ingest hooks (AI rollback evaluation and diagnosis triggers register here). */
    @Volatile
    var onIngested: (suspend (Context, List<SessionJournalIngestor.Ingested>) -> Unit)? = null

    suspend fun run(context: Context): List<SessionJournalIngestor.Ingested> {
        if (ConfigChangeRecorder.isEmulatorProcess()) return emptyList()
        val app = context.applicationContext as? GoaApplication ?: return emptyList()
        val dao = app.database.telemetryDao()
        return mutex.withLock {
            val ingestor = SessionJournalIngestor(
                journalDir = JournalFormat.directory(app),
                store = object : SessionJournalIngestor.Store {
                    override suspend fun commit(session: PlaySessionEntity, changes: List<ConfigChangeEventEntity>) =
                        // Sessions for games no longer in the catalog can't satisfy the foreign key; drop them
                        runCatching { dao.insertSessionWithChanges(session, changes) }.getOrDefault(false)
                },
                isEmulatorAlive = { isEmulatorRunning(app) },
                uploadState = { TelemetryUploadPolicy.stateFor(app) }
            )
            ingestor.ingestAll().also { ingested ->
                if (ingested.isNotEmpty()) {
                    onIngested?.invoke(app, ingested)
                    if (TelemetryUploadPolicy.stateFor(app) == com.goa.data.db.UploadState.PENDING) TelemetryUploader.uploadSoon(app)
                }
            }
        }
    }

    private fun isEmulatorRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:emulator" }
    }
}
