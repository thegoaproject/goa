package com.goa.ui.catalog

import com.goa.data.db.GameEntity
import com.goa.domain.scan.GameSource

/**
 * The library's source filter (Prepackaged / GOG / My Folders). The player unticks sources to hide them; the unticked
 * ones are what is stored, so a source added later (a first folder, connecting GOG) is visible by default.
 */
object SourceFilter {
    /** The sources offered in the menu, in menu order: GOG only when connected, My Folders only when there is something of the player's. */
    fun visibleSources(loggedIn: Boolean, hasFolders: Boolean, hasOwnGames: Boolean): List<GameSource> = buildList {
        add(GameSource.PREPACKAGED)
        if (loggedIn) add(GameSource.GOG)
        if (hasFolders || hasOwnGames) add(GameSource.MY_FOLDERS)
    }

    /** Hidden sources that are actually offered: an unticked GOG must not hide games while the menu doesn't show GOG. */
    fun activeHidden(hidden: Set<GameSource>, visible: Collection<GameSource>): Set<GameSource> = hidden.filterTo(HashSet()) { it in visible }

    fun filter(games: List<GameEntity>, hidden: Set<GameSource>): List<GameEntity> =
        if (hidden.isEmpty()) games else games.filter { GameSource.of(it.id) !in hidden }

    /** The hidden set after the player ticks or unticks [source]; the last visible source can't be unticked (that would show nothing). */
    fun toggle(hidden: Set<GameSource>, source: GameSource, visible: Collection<GameSource>): Set<GameSource> {
        if (source in hidden) return hidden - source
        val stillShown = visible.count { it != source && it !in hidden }
        return if (stillShown == 0) hidden else hidden + source
    }

    fun serialize(hidden: Set<GameSource>): Set<String> = hidden.map { it.name }.toSet()

    fun parse(stored: Set<String>?): Set<GameSource> =
        stored.orEmpty().mapNotNull { runCatching { GameSource.valueOf(it) }.getOrNull() }.toSet()
}
