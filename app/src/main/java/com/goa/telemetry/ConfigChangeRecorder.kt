package com.goa.telemetry

import android.app.Application
import android.content.Context
import com.goa.GoaApplication
import com.goa.data.db.ChangeOrigin
import com.goa.data.db.ConfigChangeEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records player configuration changes (FR-004). In the :emulator process the change goes into the running session's
 * journal; in the main process (game page) it's written straight to Room with no session.
 */
object ConfigChangeRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(context: Context, gameId: Long?, setting: String, from: String, to: String, origin: ChangeOrigin) {
        if (gameId == null || gameId <= 0 || from == to) return
        val now = System.currentTimeMillis()

        val journal = SessionJournal.active
        if (journal != null || isEmulatorProcess()) {
            journal?.append(JournalFormat.configLine(JournalConfigChange(now, setting, from, to, origin)))
            return
        }

        val app = context.applicationContext as? GoaApplication ?: return
        scope.launch {
            runCatching {
                app.database.telemetryDao().insertChange(
                    ConfigChangeEventEntity(
                        gameId = gameId, sessionId = null, setting = setting, fromValue = from, toValue = to,
                        origin = origin, at = now, uploadState = TelemetryUploadPolicy.stateFor(app)
                    )
                )
            }
        }
    }

    fun isEmulatorProcess(): Boolean = Application.getProcessName().endsWith(":emulator")
}
