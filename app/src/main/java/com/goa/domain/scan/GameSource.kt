package com.goa.domain.scan

import com.goa.domain.license.GameSlots

/**
 * Where a library game came from, for the library's source filter. There is no origin column: the id range says it
 * (the same ranges GameSlots relies on), so this is the single place that interprets them.
 *  - 9_000_000_000 until 9_100_000_000: prepackaged (bundled in the app or the freeware catalogue)
 *  - 8_000_000_000 until 9_000_000_000: the player's own files (folder candidates and older single imports)
 *  - anything else: a GOG product id
 */
enum class GameSource {
    PREPACKAGED, GOG, MY_FOLDERS;

    companion object {
        const val MY_FOLDERS_START = 8_000_000_000L
        const val MY_FOLDERS_END = 9_000_000_000L   // exclusive

        fun of(gameId: Long): GameSource = when {
            GameSlots.isFreeGame(gameId) -> PREPACKAGED
            gameId in MY_FOLDERS_START until MY_FOLDERS_END -> MY_FOLDERS
            else -> GOG
        }
    }
}
