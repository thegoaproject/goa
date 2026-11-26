package com.goa.telemetry

import android.content.Context
import com.goa.data.db.TelemetryDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConsentState { UNASKED, GRANTED, DECLINED }

/**
 * Opt-in data-sharing choice (FR-008, FR-009). Stored in SharedPreferences `goa_privacy`, the same file
 * [TelemetryUploadPolicy] reads when tagging new records.
 */
class ConsentRepository(private val context: Context, private val dao: TelemetryDao) {

    private val prefs = context.getSharedPreferences(TelemetryUploadPolicy.PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    fun current(): ConsentState = _state.value

    suspend fun set(newState: ConsentState) {
        prefs.edit()
            .putString(TelemetryUploadPolicy.KEY_CONSENT, newState.name)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
        _state.value = newState
        when (newState) {
            ConsentState.GRANTED -> AnonymousPlayerId.getOrCreate(context)
            // Nothing already queued may leave the device once sharing is off
            ConsentState.DECLINED -> dao.withdrawAllPending()
            ConsentState.UNASKED -> Unit
        }
    }

    private fun read(): ConsentState =
        prefs.getString(TelemetryUploadPolicy.KEY_CONSENT, null)
            ?.let { name -> ConsentState.entries.firstOrNull { it.name == name } }
            ?: ConsentState.UNASKED

    companion object {
        const val KEY_CHANGED_AT = "consent_changed_at"
    }
}
