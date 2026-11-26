package com.goa.data.drive

import android.content.Context
import com.goa.BuildConfig
import com.goa.data.db.GameEntity
import com.goa.domain.license.LicenseManager
import com.goa.domain.savestate.SaveStateDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the Google Drive backup of save states for a process: the library's (one app-wide service, GoaApplication)
 * and a game's (:emulator, for the in-game download). Both read the same `goa_drive` account and GOA Pro state.
 */
object DriveBackup {
    /** GOA (not GOA OSS), and switched on for this build. */
    val enabled: Boolean get() = BuildConfig.DRIVE_BACKUP && !BuildConfig.OSS_EDITION

    fun create(
        context: Context,
        auth: DriveAuth,
        scope: CoroutineScope,
        isPro: () -> Boolean,
        installs: suspend () -> List<GameEntity>,
        onExported: (Int) -> Unit = { auth.recordExport(it) },
        onImported: (Int) -> Unit = { auth.recordImport(it) }
    ): DriveSaveStates {
        val app = context.applicationContext
        val client = DriveClient(HttpClient(OkHttp), token = { refresh -> auth.token(refresh) })
        return DriveSaveStates(
            client = client,
            installs = installs,
            available = { enabled && isPro() && auth.account.value != null },
            defaults = { key -> SaveStateDefaults.forGame(app, key) },
            app = DriveSaveStates.AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            scope = scope,
            onExported = onExported,
            onImported = onImported
        )
    }

    /** For a game's process: no library, just this game's Drive copy. */
    fun forGame(context: Context, scope: CoroutineScope): Pair<DriveAuth, DriveSaveStates> {
        val auth = DriveAuth(context)
        return auth to create(context, auth, scope, isPro = { LicenseManager.isRegistered(context) }, installs = { emptyList() })
    }
}
