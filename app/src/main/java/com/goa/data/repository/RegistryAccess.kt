package com.goa.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.goa.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Proves to GOA's registry service that this is GOA as installed from Google Play, so the game patch registry is only
 * served to genuine Play copies. Google Play signs an integrity verdict for a random request hash; the registry service
 * has Google check it and hands back a session token (signed by a key that only the server has), valid for a week. The
 * registry is then fetched with that token, so Play Integrity runs about once a week per install.
 *
 * Copies that aren't from Google Play (sideloaded or self-built) are refused and keep GOA's genre defaults. Debug builds
 * skip all this with a developer key (goa.registryDevKey) that release builds never contain.
 */
class RegistryAccess(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Thrown when getting a session failed for a reason worth retrying soon (network, server or Google outage). */
    class TransientFailure(message: String) : IOException(message)

    /**
     * Headers that authorize a registry request, or null when this install can't get access right now (not a Play
     * install, or recently refused, so it doesn't ask again on every app open).
     */
    suspend fun authHeaders(base: String): Map<String, String>? {
        if (BuildConfig.REGISTRY_DEV_KEY.isNotEmpty()) return mapOf("X-GOA-Dev" to BuildConfig.REGISTRY_DEV_KEY)
        val now = System.currentTimeMillis()
        val token = prefs.getString(KEY_SESSION, null)
        if (token != null && prefs.getLong(KEY_SESSION_EXPIRES, 0L) - RENEW_BEFORE_MS > now) return bearer(token)
        if (prefs.getLong(KEY_REFUSED_UNTIL, 0L) > now) return null
        if (BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT == 0L) {
            Log.w(TAG, "No Play Integrity cloud project configured (goa.integrityCloudProject); registry unavailable")
            return null
        }
        return newSession(base)?.let(::bearer)
    }

    /** The server rejected the session (expired or its key rotated): forget it so the next request gets a new one. */
    fun dropSession() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_SESSION_EXPIRES).apply()
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private suspend fun newSession(base: String): String? {
        val requestHash = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        val integrityToken = try {
            val provider = IntegrityManagerFactory.createStandard(context)
                .prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT).build()
                ).await()
            provider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        } catch (e: Exception) {
            // No Play Store, outdated Play services, not signed in, Play unreachable...: ask again in a while
            Log.w(TAG, "Play Integrity unavailable (${e.message}); registry unavailable for now")
            refuseFor(CLIENT_ERROR_BACKOFF_MS)
            return null
        }

        val conn = (URL("$base/v1/session").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("integrityToken", integrityToken).put("requestHash", requestHash).toString().toByteArray())
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val token = body.getString("token")
                    prefs.edit()
                        .putString(KEY_SESSION, token)
                        .putLong(KEY_SESSION_EXPIRES, TimeUnit.SECONDS.toMillis(body.getLong("expiresAt")))
                        .remove(KEY_REFUSED_UNTIL)
                        .apply()
                    return token
                }
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val reason = runCatching { JSONObject(conn.errorStream.bufferedReader().use { it.readText() }).optString("reason") }.getOrNull()
                    Log.w(TAG, "Registry access refused: not a Google Play install ($reason)")
                    refuseFor(REFUSED_BACKOFF_MS)
                    return null
                }
                429, in 500..599 -> throw TransientFailure("Registry session returned $code")
                else -> {
                    Log.w(TAG, "Registry session returned $code")
                    refuseFor(CLIENT_ERROR_BACKOFF_MS)
                    return null
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun refuseFor(ms: Long) {
        prefs.edit().putLong(KEY_REFUSED_UNTIL, System.currentTimeMillis() + ms).apply()
    }

    companion object {
        private const val TAG = "RegistryAccess"
        private const val PREFS = "goa_registry_access"
        private const val KEY_SESSION = "session"
        private const val KEY_SESSION_EXPIRES = "session_expires"
        private const val KEY_REFUSED_UNTIL = "refused_until"
        /** Renew a session this long before it expires, so a sync never starts with one about to lapse. */
        private val RENEW_BEFORE_MS = TimeUnit.HOURS.toMillis(6)
        /** After the server says "not a Play install", wait this long before asking Play Integrity again. */
        private val REFUSED_BACKOFF_MS = TimeUnit.HOURS.toMillis(24)
        private val CLIENT_ERROR_BACKOFF_MS = TimeUnit.HOURS.toMillis(1)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
