package com.goa.telemetry

import android.content.Context
import com.goa.data.db.UploadState

/** Opt-in gate (FR-008): records are queued for upload only while sharing consent is GRANTED. */
object TelemetryUploadPolicy {
    const val PREFS = "goa_privacy"
    const val KEY_CONSENT = "consent_state"

    fun stateFor(context: Context): UploadState =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONSENT, null) == "GRANTED") {
            UploadState.PENDING
        } else {
            UploadState.LOCAL_ONLY
        }
}
