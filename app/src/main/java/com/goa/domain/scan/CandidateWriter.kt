package com.goa.domain.scan

import com.goa.data.db.CandidateKey
import com.goa.data.db.GameDao
import com.goa.data.db.GameEntity
import com.goa.data.db.GameVariantEntity
import com.goa.data.db.InstallStatus

/** The few storage operations a scan needs; [GameDaoCandidateStore] backs it with Room, tests with a map. */
interface CandidateStore {
    suspend fun idForSourceKey(sourceKey: String): Long?
    suspend fun idExists(id: Long): Boolean
    suspend fun insertIfAbsent(game: GameEntity): Boolean
    suspend fun refresh(id: Long, game: ScannedGame, folderId: String): Boolean
    suspend fun replaceVariants(gameId: Long, variants: List<GameVariantEntity>)
    suspend fun pickedVariantKey(id: Long): String?
    suspend fun candidateKeys(folderId: String): List<CandidateKey>
    suspend fun deleteCandidates(ids: List<Long>)
}

class GameDaoCandidateStore(private val dao: GameDao) : CandidateStore {
    override suspend fun idForSourceKey(sourceKey: String) = dao.idForSourceKey(sourceKey)
    override suspend fun idExists(id: Long) = dao.getGameByIdSync(id) != null
    override suspend fun insertIfAbsent(game: GameEntity) = dao.insertIfAbsent(game) != -1L
    override suspend fun refresh(id: Long, game: ScannedGame, folderId: String) = dao.refreshCandidate(
        id, game.title, game.slug, game.year, game.primary.size, game.primary.uri, game.primary.kind, folderId, game.variants.size
    ) > 0
    override suspend fun replaceVariants(gameId: Long, variants: List<GameVariantEntity>) {
        dao.deleteVariantsForGame(gameId)
        dao.upsertVariants(variants)
    }
    override suspend fun pickedVariantKey(id: Long) = dao.pickedVariantKey(id)
    override suspend fun candidateKeys(folderId: String) = dao.candidateKeysForFolder(folderId)
    override suspend fun deleteCandidates(ids: List<Long>) = dao.deleteCandidates(ids)
}

/**
 * Writes a scan into the library as uninstalled games, safely:
 *  - inserts only if absent and refreshes only rows that are still NOT_INSTALLED, so a rescan never touches a game that
 *    is installed, downloading or has play time;
 *  - ids come from the stable source key, so scanning twice changes nothing;
 *  - games that disappeared from the folder are removed only while they are still uninstalled.
 */
class CandidateWriter(
    private val store: CandidateStore,
    /** Wraps a chunk in one database transaction (so the library list updates once per chunk, not once per row). */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val chunkSize: Int = 200
) {
    data class Applied(val inserted: Int, val refreshed: Int, val removed: Int)

    suspend fun apply(folderId: String, folderName: String, games: List<ScannedGame>): Applied {
        var inserted = 0
        var refreshed = 0
        val claimed = HashSet<Long>()                      // ids handed out earlier in this scan, not yet visible in the store
        val keptKeys = HashSet<String>()

        for (chunk in games.chunked(chunkSize)) {
            inTransaction {
                for (game in chunk) {
                    val key = "$folderId|g:${game.groupKey}"
                    keptKeys += key
                    val existing = store.idForSourceKey(key)
                    val id = FolderCandidateIds.allocate(key, existing) { candidate -> candidate in claimed || store.idExists(candidate) }
                    claimed += id
                    // A version the player chose stays chosen for as long as it is still in the folder
                    val picked = existing?.let { store.pickedVariantKey(it) }
                    val primary = game.variants.firstOrNull { it.sourceKey == picked } ?: game.primary
                    val effective = if (primary === game.primary) game else game.copy(primary = primary)
                    if (existing == null) {
                        val isNew = store.insertIfAbsent(newEntity(id, key, folderId, folderName, effective))
                        if (isNew) inserted++
                    } else if (store.refresh(id, effective, folderId)) {
                        refreshed++
                    }
                    store.replaceVariants(id, game.variants.map { v ->
                        GameVariantEntity(
                            gameId = id, sourceKey = v.sourceKey, sourceUri = v.uri, sourceKind = v.kind, displayName = v.displayName,
                            sizeBytes = v.size, isRev = v.parsed.isRev, year = v.parsed.year, isPrimary = v === primary
                        )
                    })
                }
            }
        }

        val stale = store.candidateKeys(folderId).filter { it.sourceKey !in keptKeys }.map { it.id }
        stale.chunked(500).forEach { store.deleteCandidates(it) }
        return Applied(inserted, refreshed, stale.size)
    }

    private fun newEntity(id: Long, key: String, folderId: String, folderName: String, game: ScannedGame) = GameEntity(
        id = id,
        title = game.title,
        slug = game.slug,
        boxArtUrl = "https://images.gog.com/placeholder.jpg",
        bannerUrl = null,
        description = "Found in $folderName.",
        releaseYear = game.year,
        isDosNative = true,
        downloadSize = game.primary.size,
        installStatus = InstallStatus.NOT_INSTALLED,
        sourceKey = key,
        sourceUri = game.primary.uri,
        sourceKind = game.primary.kind,
        sourceFolderId = folderId,
        variantCount = game.variants.size
    )
}
