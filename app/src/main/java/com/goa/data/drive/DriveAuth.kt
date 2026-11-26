package com.goa.data.drive

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Google account whose Drive holds the save states, and access to it (research R2). Google Play services'
 * AuthorizationClient asks once for `drive.file` (only files GOA creates); after that every run gets a fresh
 * short-lived token without any screen. No token is ever stored: only the account's name, in `goa_drive`.
 */
class DriveAuth(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(prefs.getString(KEY_ACCOUNT, null))
    /** The linked account's name (an email address), or null when none is linked. */
    val account: StateFlow<String?> = _account.asStateFlow()

    private var lastToken: String? = null

    /** The system account chooser for Google accounts (no GET_ACCOUNTS permission needed). */
    fun chooseAccountIntent(): Intent = AccountManager.newChooseAccountIntent(
        _account.value?.let { Account(it, ACCOUNT_TYPE) }, null, arrayOf(ACCOUNT_TYPE), null, null, null, null
    )

    /** Reads the chooser's result and links that account. False when the player backed out. */
    fun onAccountChosen(data: Intent?): Boolean {
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() } ?: return false
        if (name != _account.value) {
            lastToken = null
            prefs.edit().putString(KEY_ACCOUNT, name).apply()
            _account.value = name
        }
        return true
    }

    sealed interface Access {
        data class Granted(val token: String) : Access
        /** Google must show its consent screen first: launch this, then pass the result to [onConsentResult]. */
        data class NeedsConsent(val intent: PendingIntent) : Access
        data class Failed(val error: DriveError) : Access
    }

    /** Asks for access to the linked account's Drive. Shows nothing itself; see [Access.NeedsConsent]. */
    suspend fun authorize(): Access {
        val name = _account.value ?: return Access.Failed(DriveError.Other("no Google account chosen"))
        return try {
            val result = Identity.getAuthorizationClient(app).authorize(request(name)).await()
            when {
                result.hasResolution() && result.pendingIntent != null -> Access.NeedsConsent(result.pendingIntent!!)
                result.accessToken != null -> Access.Granted(result.accessToken!!.also { lastToken = it })
                else -> Access.Failed(DriveError.AccessRemoved)
            }
        } catch (e: ApiException) {
            // DEVELOPER_ERROR (10): no OAuth client matches this build's package and signing certificate
            Access.Failed(
                if (e.statusCode == 10) DriveError.Other("not available in this build of GOA yet.")
                else if (e.statusCode == 7) DriveError.NoConnection
                else DriveError.Other("error ${e.statusCode}")
            )
        } catch (e: java.io.IOException) {
            Access.Failed(DriveError.NoConnection)
        }
    }

    /** The token from Google's consent screen, or null when the player declined. */
    fun onConsentResult(data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()?.also { lastToken = it }

    /**
     * A token for a run, without any screen: [refresh] drops the one Drive just refused first. Throws
     * [DriveException] ([DriveError.AccessRemoved]) when consent is needed again.
     */
    suspend fun token(refresh: Boolean): String {
        if (refresh) lastToken?.let { old ->
            runCatching { Identity.getAuthorizationClient(app).clearToken(ClearTokenRequest.builder().setToken(old).build()).await() }
            lastToken = null
        }
        if (!refresh) lastToken?.let { return it }
        return when (val access = authorize()) {
            is Access.Granted -> access.token
            is Access.NeedsConsent -> throw DriveException(DriveError.AccessRemoved)
            is Access.Failed -> throw DriveException(access.error)
        }
    }

    /** Unlink: withdraws GOA's access in the Google account and forgets the account. Drive files stay. */
    suspend fun unlink() {
        val name = _account.value
        if (name != null) runCatching {
            Identity.getAuthorizationClient(app).revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(name, ACCOUNT_TYPE)).setScopes(listOf(Scope(SCOPE))).build()
            ).await()
        }
        lastToken = null
        prefs.edit().clear().apply()
        _account.value = null
    }

    private fun request(name: String) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .setAccount(Account(name, ACCOUNT_TYPE))
        .build()

    // ---- Last runs, shown on the Drive Backup page ----

    data class LastRun(val at: Long, val count: Int)

    fun lastExport(): LastRun? = lastRun(KEY_LAST_EXPORT_AT, KEY_LAST_EXPORT_COUNT)
    fun lastImport(): LastRun? = lastRun(KEY_LAST_IMPORT_AT, KEY_LAST_IMPORT_COUNT)

    fun recordExport(count: Int, at: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LAST_EXPORT_AT, at).putInt(KEY_LAST_EXPORT_COUNT, count).apply()

    fun recordImport(count: Int, at: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LAST_IMPORT_AT, at).putInt(KEY_LAST_IMPORT_COUNT, count).apply()

    private fun lastRun(atKey: String, countKey: String): LastRun? =
        prefs.getLong(atKey, 0L).takeIf { it > 0 }?.let { LastRun(it, prefs.getInt(countKey, 0)) }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val ACCOUNT_TYPE = "com.google"
        private const val PREFS = "goa_drive"
        private const val KEY_ACCOUNT = "accountName"
        private const val KEY_LAST_EXPORT_AT = "lastExportAt"
        private const val KEY_LAST_EXPORT_COUNT = "lastExportCount"
        private const val KEY_LAST_IMPORT_AT = "lastImportAt"
        private const val KEY_LAST_IMPORT_COUNT = "lastImportCount"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
