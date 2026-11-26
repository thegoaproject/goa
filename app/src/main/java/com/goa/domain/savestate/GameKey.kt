package com.goa.domain.savestate

import com.goa.data.db.GameEntity
import java.util.Locale

/**
 * Which game a save state belongs to, the same on every phone (research R4): the game's slug, lower-cased. GOG games
 * carry GOG's slug, free games the catalogue's, folder-found games the scanner's; ids can't be used because
 * folder-found games get a different id on each phone.
 */
object GameKey {
    fun of(slug: String?, title: String): String =
        slug?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT) ?: fromTitle(title)

    fun of(game: GameEntity): String = of(game.slug, game.title)

    /** The scanner's slug rule (FilenameTitleParser), for games that somehow have none. */
    private fun fromTitle(title: String): String =
        title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_").replace(Regex("_+"), "_").trim('_')

    /** Where a game key lands on this phone. */
    sealed interface Resolution {
        data class Installed(val game: GameEntity) : Resolution
        data object NotInstalled : Resolution
        /** Several installs share the key, e.g. a GOG copy and a folder copy: the player picks one. */
        data class Ambiguous(val games: List<GameEntity>) : Resolution
    }

    /** Only installs with files on the phone count. */
    fun installed(games: List<GameEntity>): List<GameEntity> = games.filter { !it.localInstallPath.isNullOrBlank() }

    fun resolve(key: String, installs: List<GameEntity>): Resolution {
        val matches = installed(installs).filter { of(it) == key }
        return when (matches.size) {
            0 -> Resolution.NotInstalled
            1 -> Resolution.Installed(matches.single())
            else -> Resolution.Ambiguous(matches)
        }
    }
}
