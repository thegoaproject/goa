package com.goa.domain.license

import com.goa.data.db.InstallStatus

/**
 * GOA is shareware: every feature and every free catalog game is unlimited, and unregistered players can keep
 * [FREE_SLOTS] of their own games (GOG installs and imports) installed at a time. Only starting an install is gated;
 * an installed game always plays.
 */
object GameSlots {
    const val FREE_SLOTS = 3

    // Freeware/shareware catalog (seeded and APK-bundled games) lives in this id block; GOG ids are far below it and
    // imports use 8_xxx_xxx_xxx
    private val FREE_CATALOG = 9_000_000_000L until 9_100_000_000L

    fun isFreeGame(gameId: Long): Boolean = gameId in FREE_CATALOG

    /** A game holds a slot from the moment its install starts until it is uninstalled; failed installs don't. */
    fun occupiesSlot(gameId: Long, status: InstallStatus): Boolean =
        !isFreeGame(gameId) && status != InstallStatus.NOT_INSTALLED && status != InstallStatus.ERROR

    sealed interface Decision {
        data object Allowed : Decision
        /** Allowed, using free slot [slotNumber] of [FREE_SLOTS]. */
        data class FreeSlot(val slotNumber: Int) : Decision
        data object NeedsRegistration : Decision
    }

    /**
     * Own games that stay playable for an unregistered player: normally all of them, since installs are capped at
     * [FREE_SLOTS]. Only after a refunded registration can there be more; then the [FREE_SLOTS] most recently played
     * stay playable and the rest wait, files untouched, until the player registers again or uninstalls some.
     * [ownGames] = (id, lastPlayedTimestamp) of own games holding a slot.
     */
    fun keptGames(ownGames: List<Pair<Long, Long?>>): Set<Long> =
        ownGames.sortedWith(compareByDescending<Pair<Long, Long?>> { it.second ?: Long.MIN_VALUE }.thenBy { it.first })
            .take(FREE_SLOTS).map { it.first }.toSet()

    fun canPlay(gameId: Long, ownGames: List<Pair<Long, Long?>>, registered: Boolean): Boolean =
        registered || isFreeGame(gameId) || ownGames.none { it.first == gameId } || gameId in keptGames(ownGames)

    /** [occupied] = ids of own games currently holding a slot. */
    fun decide(gameId: Long?, occupied: Set<Long>, registered: Boolean): Decision = when {
        registered -> Decision.Allowed
        gameId != null && isFreeGame(gameId) -> Decision.Allowed
        gameId != null && gameId in occupied -> Decision.Allowed // resuming or repairing a game that already has a slot
        occupied.size < FREE_SLOTS -> Decision.FreeSlot(occupied.size + 1)
        else -> Decision.NeedsRegistration
    }
}

/** Thrown by install/import (no free slot) or launch (game locked after a refund); the UI shows the Register screen. */
class RegistrationRequiredException(
    message: String = "You have ${GameSlots.FREE_SLOTS} of your own games installed. Unlock GOA Pro to install more, or uninstall one."
) : IllegalStateException(message)
