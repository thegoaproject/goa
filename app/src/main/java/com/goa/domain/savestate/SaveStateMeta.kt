package com.goa.domain.savestate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What GOA knows about one save state: the `state_0.json` file next to `state_0.sav` in the game's install folder
 * (specs/009-drive-save-states/contracts/local-files.md), and the same fields in Drive's appProperties.
 *
 * [savedAt] is set when the player saves or a save state is imported, never from the file's modification time
 * (copies and downloads reset that); the one exception is a save state made before this metadata existed.
 * [fileBytes] / [fileModified] stay on the phone; Drive gets the other fields.
 */
@Serializable
data class SaveStateMeta(
    val v: Int = 1,
    val gameKey: String,
    val savedAt: Long,
    val device: String,
    val coreVersion: String,
    val sha256: String,
    /** Size and modification time of the file when [sha256] was taken: unchanged means no need to hash it again. */
    val fileBytes: Long = -1,
    val fileModified: Long = -1
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): SaveStateMeta? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}
