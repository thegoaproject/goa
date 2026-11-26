package com.goa.domain.savestate

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The phone's name as recorded with a save state: the name the player gave it, else its model (research R6). */
object DeviceName {
    /** Drive allows 124 bytes per appProperties key plus value (contracts/drive-layout.md). */
    const val DRIVE_MAX_CHARS = 60

    fun get(context: Context): String {
        val named = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.trim()
        return named?.takeIf { it.isNotEmpty() } ?: Build.MODEL.orEmpty().ifBlank { "Android phone" }
    }

    /** Bytes left for the value of the `device` key (124 minus the key's 6). */
    private const val DRIVE_MAX_BYTES = 118

    /** [name] cut to what fits in Drive's appProperties: 60 characters and 118 UTF-8 bytes, whole characters only. */
    fun forDrive(name: String): String {
        var end = minOf(name.length, DRIVE_MAX_CHARS)
        while (end > 0 && (Character.isHighSurrogate(name[end - 1]) ||
                name.substring(0, end).toByteArray(Charsets.UTF_8).size > DRIVE_MAX_BYTES)
        ) end--
        return name.substring(0, end).trimEnd()
    }
}
