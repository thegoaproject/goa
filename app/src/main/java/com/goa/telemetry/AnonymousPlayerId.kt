package com.goa.telemetry

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * One random UUID per install for shared telemetry (FR-010a). Never derived from hardware, Android ID or the GOG
 * account. Resetting keeps the previous ids locally only so "Delete my shared data" can cover them.
 */
object AnonymousPlayerId {
    private const val KEY_ID = "player_id"
    private const val KEY_PREVIOUS = "previous_player_ids"

    private fun prefs(context: Context) = context.getSharedPreferences(TelemetryUploadPolicy.PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(context: Context): String =
        prefs(context).getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs(context).edit().putString(KEY_ID, id).commit()
        }

    fun current(context: Context): String? = prefs(context).getString(KEY_ID, null)

    @Synchronized
    fun reset(context: Context): String {
        val old = current(context)
        val previous = previous(context).toMutableList().apply { if (old != null) add(old) }
        val fresh = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_ID, fresh).putString(KEY_PREVIOUS, JSONArray(previous).toString()).commit()
        return fresh
    }

    fun previous(context: Context): List<String> =
        runCatching { JSONArray(prefs(context).getString(KEY_PREVIOUS, "[]")) }.getOrDefault(JSONArray())
            .let { arr -> (0 until arr.length()).map { arr.getString(it) } }

    fun clearPrevious(context: Context) {
        prefs(context).edit().remove(KEY_PREVIOUS).commit()
    }
}
