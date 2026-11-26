package com.goa.data.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtTimestamp: Long,
    val userId: String,
    val username: String,
    val avatarUrl: String?
)

class SecureSessionStorage(context: Context) {

    // Built in Application.onCreate, so a keystore failure here would stop GOA from opening at all. When the stored
    // session can't be decrypted (its key is gone, e.g. a file copied from another phone, or a broken keystore), it
    // is thrown away and the player signs in to GOG again.
    private val prefs: SharedPreferences = runCatching { open(context) }.getOrElse { first ->
        android.util.Log.w(TAG, "GOG session storage unreadable; starting signed out", first)
        context.deleteSharedPreferences(PREFS_FILENAME)
        runCatching { open(context) }.getOrElse { second ->
            android.util.Log.e(TAG, "Keystore unavailable; the GOG session is kept in memory only", second)
            InMemoryPrefs()
        }
    }

    private fun open(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveSession(session: UserSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtTimestamp)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_AVATAR_URL, session.avatarUrl)
            .apply()
    }

    fun getSession(): UserSession? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val userId = prefs.getString(KEY_USER_ID, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val avatarUrl = prefs.getString(KEY_AVATAR_URL, null)

        return UserSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtTimestamp = expiresAt,
            userId = userId,
            username = username,
            avatarUrl = avatarUrl
        )
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    fun isSessionValid(): Boolean {
        val session = getSession() ?: return false
        // Buffer by 60 seconds
        return System.currentTimeMillis() < (session.expiresAtTimestamp - 60_000L)
    }

    /** Last resort when the keystore can't be used at all: the session lasts until GOA closes, and never touches disk. */
    private class InMemoryPrefs : SharedPreferences {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private var clear = false
            private fun put(key: String, value: Any?) = apply { changes[key] = value }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toMutableSet())
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }

    companion object {
        private const val TAG = "GoaSession"
        private const val PREFS_FILENAME = "goa_secure_session_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_AVATAR_URL = "avatar_url"
    }
}

