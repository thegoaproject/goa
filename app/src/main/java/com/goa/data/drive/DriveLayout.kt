package com.goa.data.drive

import com.goa.domain.savestate.DeviceName
import com.goa.domain.savestate.SaveStateMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What GOA writes to the player's Google Drive (specs/009-drive-save-states/contracts/drive-layout.md). Players,
 * other tools and later GOA versions rely on it: changing names or properties needs a migration.
 */
object DriveLayout {
    const val ROOT_NAME = "GOA Save States"
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val STATE_MIME = "application/octet-stream"
    const val NOTE_MIME = "text/plain"
    const val NOTE_NAME = "About this save state.txt"
    const val LAYOUT_VERSION = "1"

    const val KIND = "goaKind"
    const val KIND_ROOT = "root"
    const val KIND_GAME = "game"
    const val KIND_SAVE_STATE = "saveState"
    const val KIND_NOTE = "note"

    const val GAME_KEY = "gameKey"
    const val SAVED_AT = "savedAt"
    const val DEVICE = "device"
    /** The game's real title: folder and file names have characters other file systems refuse replaced. */
    const val TITLE = "title"
    const val CORE_VERSION = "coreVersion"
    const val SHA256 = "sha256"
    private const val LAYOUT = "layout"

    /** Drive's limit for one appProperties entry: key plus value, in UTF-8 bytes. */
    const val MAX_PROPERTY_BYTES = 124

    private val FORBIDDEN = Regex("""[/\\:*?"<>|]""")

    /**
     * The game folder's name: the title with characters other file systems refuse replaced by `-`, a subtitle's
     * colon by a spaced dash ("One Must Fall: 2097" → "One Must Fall - 2097").
     */
    fun folderName(title: String): String = title.replace(SUBTITLE_COLON, " - ").replace(FORBIDDEN, "-")
        .replace(SPACES, " ").trim().ifEmpty { "Game" }
    private val SUBTITLE_COLON = Regex("""\s*:\s+""")
    private val SPACES = Regex("""\s{2,}""")

    fun stateFileName(title: String): String = folderName(title) + ".state"

    fun rootProperties() = mapOf(KIND to KIND_ROOT, LAYOUT to LAYOUT_VERSION)

    fun gameProperties(gameKey: String) = fit(mapOf(KIND to KIND_GAME, GAME_KEY to gameKey))

    fun noteProperties(gameKey: String) = fit(mapOf(KIND to KIND_NOTE, GAME_KEY to gameKey))

    fun saveStateProperties(meta: SaveStateMeta, title: String? = null) = fit(
        listOfNotNull(
            KIND to KIND_SAVE_STATE,
            title?.takeIf { it.isNotBlank() }?.let { TITLE to it },
            GAME_KEY to meta.gameKey,
            SAVED_AT to meta.savedAt.toString(),
            DEVICE to DeviceName.forDrive(meta.device),
            CORE_VERSION to meta.coreVersion,
            SHA256 to meta.sha256
        ).toMap()
    )

    /** A save state's metadata read back from Drive's appProperties, or null when it isn't one of GOA's. */
    fun metaFrom(properties: Map<String, String>): SaveStateMeta? {
        if (properties[KIND] != KIND_SAVE_STATE) return null
        val key = properties[GAME_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return SaveStateMeta(
            gameKey = key,
            savedAt = properties[SAVED_AT]?.toLongOrNull() ?: 0L,
            device = properties[DEVICE].orEmpty(),
            coreVersion = properties[CORE_VERSION].orEmpty(),
            sha256 = properties[SHA256].orEmpty()
        )
    }

    /** Cuts values so each entry stays within [MAX_PROPERTY_BYTES]. */
    private fun fit(properties: Map<String, String>): Map<String, String> = properties.mapValues { (key, value) ->
        var v = value
        while ((key + v).toByteArray(Charsets.UTF_8).size > MAX_PROPERTY_BYTES) v = v.dropLast(1)
        v
    }

    private val NOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The plain-text note next to each save state: what it is and how to use it outside GOA. */
    fun noteText(title: String, meta: SaveStateMeta, versionName: String, versionCode: Int, zone: ZoneId): String {
        val saved = NOTE_TIME.format(Instant.ofEpochMilli(meta.savedAt).atZone(zone))
        val core = meta.coreVersion.ifBlank { "(unknown version)" }
        return """
            |$title
            |Saved $saved (${zone.id}) on ${meta.device}
            |GOA $versionName ($versionCode), DOSBox Pure $core
            |
            |This is a DOSBox Pure save state made by GOA (goodoldandroid.app).
            |
            |To use it outside GOA, for example in RetroArch: load the same game files with DOSBox Pure $core, copy "${stateFileName(title)}" into RetroArch's states folder, rename it after the game's content file (for example DUKE3D.state), and load the state.
            |
            |Save states only load with the same game files and a compatible DOSBox Pure version.
            |""".trimMargin()
    }
}
