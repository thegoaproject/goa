package com.goa.domain.license

import android.content.Context
import com.goa.data.db.GameDao
import com.goa.data.db.GameEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Whether this player has registered GOA, and how many of their free game slots are in use. */
class LicenseManager(context: Context, private val gameDao: GameDao) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _registered = MutableStateFlow(prefs.getBoolean(KEY_REGISTERED, false))

    /** Whether GOA is registered: bought through Google Play, or grandfathered. */
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    /** "play" (bought through Google Play), "grandfathered" (installed games before registration existed) or "debug". */
    val source: String? get() = prefs.getString(KEY_SOURCE, null)

    val occupiedSlots: Flow<Set<Long>> = gameDao.observeActiveInstalls().map(::ownGames)

    /** True after a Google Play refund or revocation un-registered GOA (cleared by registering again). */
    val revoked: Boolean get() = prefs.getBoolean(KEY_REVOKED, false)

    /**
     * Own games that can't be launched while GOA isn't registered: all but the [GameSlots.FREE_SLOTS] most recently
     * played. Empty unless the player is unregistered with more own games installed than that, which only happens
     * after a refund. Follows registration changes as well as installs.
     */
    val lockedGames: Flow<Set<Long>> = combine(gameDao.observeActiveInstalls(), registered) { games, isRegistered ->
        if (isRegistered) emptySet() else {
            val own = ownWithLastPlayed(games)
            own.map { it.first }.toSet() - GameSlots.keptGames(own)
        }
    }

    fun setRegistered(value: Boolean, source: String) {
        prefs.edit().putBoolean(KEY_REGISTERED, value).putString(KEY_SOURCE, if (value) source else null)
            .putBoolean(KEY_REVOKED, if (value) false else prefs.getBoolean(KEY_REVOKED, false)).apply()
        _registered.value = value
    }

    /** Google Play no longer lists the purchase (refund, chargeback, revoked): back to the free version. */
    fun revoke() {
        prefs.edit().putBoolean(KEY_REGISTERED, false).putString(KEY_SOURCE, null).putBoolean(KEY_REVOKED, true).apply()
        _registered.value = false
    }

    /**
     * Unregistered with more own games installed than the free slots (after a refund): only the
     * most recently played ones launch. Installs are capped at the free slots, so nothing else is ever over the limit.
     */
    suspend fun requirePlayable(gameId: Long) {
        if (registered.value) return
        val own = ownWithLastPlayed(gameDao.getActiveInstalls())
        if (!GameSlots.canPlay(gameId, own, registered.value)) {
            throw RegistrationRequiredException(
                "Only ${GameSlots.FREE_SLOTS} of your own games can be played on the free version. Unlock GOA Pro " +
                    "to play this one; it and its saves are still here."
            )
        }
    }

    suspend fun occupied(): Set<Long> = ownGames(gameDao.getActiveInstalls())

    suspend fun decide(gameId: Long?): GameSlots.Decision = GameSlots.decide(gameId, occupied(), registered.value)

    suspend fun requireSlot(gameId: Long?) {
        if (decide(gameId) == GameSlots.Decision.NeedsRegistration) throw RegistrationRequiredException()
    }

    /**
     * Runs once, the first time a build with registration starts: anyone who already has their own games installed
     * was testing GOA before it had a price, so they're registered as thanks. New installs start unregistered.
     */
    suspend fun grandfatherExistingInstallsOnce() {
        if (prefs.getBoolean(KEY_GRANDFATHER_CHECKED, false)) return
        if (!registered.value && occupied().isNotEmpty()) setRegistered(true, SOURCE_GRANDFATHERED)
        prefs.edit().putBoolean(KEY_GRANDFATHER_CHECKED, true).apply()
    }

    private fun ownWithLastPlayed(games: List<GameEntity>): List<Pair<Long, Long?>> =
        games.filter { GameSlots.occupiesSlot(it.id, it.installStatus) }.map { it.id to it.lastPlayedTimestamp }

    private fun ownGames(games: List<GameEntity>): Set<Long> =
        games.filter { GameSlots.occupiesSlot(it.id, it.installStatus) }.map { it.id }.toSet()

    companion object {
        const val SOURCE_PLAY = "play"
        const val SOURCE_GRANDFATHERED = "grandfathered"
        const val SOURCE_DEBUG = "debug"
        private const val PREFS = "goa_license"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_SOURCE = "registered_source"
        private const val KEY_GRANDFATHER_CHECKED = "grandfather_checked"
        private const val KEY_REVOKED = "revoked"

        /**
         * Whether GOA is registered, read straight from storage: for the game's own process (:emulator), where no
         * LicenseManager exists. The main process writes it; a game reads it when it opens.
         */
        fun isRegistered(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REGISTERED, false)
    }
}
