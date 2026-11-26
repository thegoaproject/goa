package com.goa.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    // Games
    @Query("SELECT * FROM games WHERE isDosNative = 1 ORDER BY title ASC")
    fun getDosGames(): Flow<List<GameEntity>>

    /** Games with an install in progress or done (GameSlots decides which of them hold a free slot). */
    @Query("SELECT * FROM games WHERE installStatus NOT IN ('NOT_INSTALLED', 'ERROR')")
    suspend fun getActiveInstalls(): List<GameEntity>

    @Query("SELECT * FROM games WHERE installStatus NOT IN ('NOT_INSTALLED', 'ERROR')")
    fun observeActiveInstalls(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE isDosNative = 1 AND lastPlayedTimestamp IS NOT NULL ORDER BY lastPlayedTimestamp DESC")
    fun getRecentlyPlayedGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    fun getGameById(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun getGameByIdSync(id: Long): GameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<GameEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: GameEntity)

    @Update
    suspend fun updateGame(game: GameEntity)

    @Query("UPDATE games SET installStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: InstallStatus)

    @Query("""
        UPDATE games 
        SET installStatus = :status, 
            localInstallPath = :localInstallPath, 
            mainExecutable = :mainExecutable, 
            dosboxConfPath = :dosboxConfPath 
        WHERE id = :id
    """)
    suspend fun updateInstallDetails(
        id: Long,
        status: InstallStatus,
        localInstallPath: String?,
        mainExecutable: String?,
        dosboxConfPath: String?
    )

    @Query("""
        UPDATE games 
        SET lastPlayedTimestamp = :timestamp, 
            playTimeMinutes = playTimeMinutes + :addedMinutes 
        WHERE id = :id
    """)
    suspend fun updateLastPlayed(id: Long, timestamp: Long, addedMinutes: Long = 0L)

    @Delete
    suspend fun deleteGame(game: GameEntity)

    // Folder candidates. insertGame/insertGames REPLACE a row, which would wipe an installed game's state on a rescan, so
    // candidates only ever use these: insert if absent, then refresh the not-yet-installed ones in place.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(game: GameEntity): Long

    @Query("SELECT id FROM games WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun idForSourceKey(sourceKey: String): Long?

    @Query("""
        UPDATE games
        SET title = :title, slug = :slug, releaseYear = :year, downloadSize = :size, sourceUri = :uri,
            sourceKind = :kind, sourceFolderId = :folderId, variantCount = :variantCount
        WHERE id = :id AND installStatus = 'NOT_INSTALLED'
    """)
    suspend fun refreshCandidate(
        id: Long, title: String, slug: String, year: Int?, size: Long, uri: String, kind: String, folderId: String, variantCount: Int
    ): Int

    @Query("SELECT id, sourceKey FROM games WHERE sourceFolderId = :folderId AND installStatus = 'NOT_INSTALLED' AND sourceKey IS NOT NULL")
    suspend fun candidateKeysForFolder(folderId: String): List<CandidateKey>

    @Query("DELETE FROM games WHERE id IN (:ids) AND installStatus = 'NOT_INSTALLED' AND sourceKey IS NOT NULL")
    suspend fun deleteCandidates(ids: List<Long>)

    @Query("DELETE FROM games WHERE sourceFolderId = :folderId AND installStatus = 'NOT_INSTALLED' AND sourceKey IS NOT NULL")
    suspend fun deleteNotInstalledByFolder(folderId: String)

    // A bigger watched folder replacing nested ones takes over their games (FolderMerge)
    @Query("SELECT id, sourceKey, installStatus = 'NOT_INSTALLED' AS notInstalled FROM games WHERE sourceFolderId = :folderId AND sourceKey IS NOT NULL")
    suspend fun folderGameRows(folderId: String): List<FolderGameRow>

    @Query("SELECT sourceKey FROM games WHERE sourceFolderId = :folderId AND sourceKey IS NOT NULL")
    suspend fun sourceKeysForFolder(folderId: String): List<String>

    @Query("UPDATE games SET sourceKey = :sourceKey, sourceFolderId = :folderId WHERE id = :id")
    suspend fun rekeyFolderGame(id: Long, sourceKey: String, folderId: String)

    @Query("UPDATE games SET sourceFolderId = :folderId WHERE id = :id")
    suspend fun moveFolderGame(id: Long, folderId: String)

    @Query("SELECT COUNT(*) FROM games WHERE sourceKey IS NOT NULL AND sourceFolderId = :folderId")
    suspend fun countForFolder(folderId: String): Int

    @Query("SELECT COUNT(*) FROM games WHERE id >= 8000000000 AND id < 9000000000")
    fun observeMyFolderGameCount(): Flow<Int>

    /** Every game without files on the phone; GameCatalogRepository.pruneGogLibrary picks the GOG ones by id range. */
    @Query("SELECT id FROM games WHERE installStatus = 'NOT_INSTALLED'")
    suspend fun notInstalledIds(): List<Long>

    /** Removes GOG library rows that have no files (the WHERE re-checks it, in case an install started meanwhile). */
    @Query("DELETE FROM games WHERE id IN (:ids) AND installStatus = 'NOT_INSTALLED'")
    suspend fun deleteNotInstalled(ids: List<Long>)

    @Query("DELETE FROM installer_chunks WHERE gameId IN (:ids)")
    suspend fun deleteChunksForGames(ids: List<Long>)

    // Variants (other revisions of a candidate)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariants(variants: List<GameVariantEntity>)

    @Query("DELETE FROM game_variants WHERE gameId = :gameId")
    suspend fun deleteVariantsForGame(gameId: Long)

    @Query("SELECT * FROM game_variants WHERE gameId = :gameId ORDER BY isPrimary DESC, isRev ASC, sizeBytes DESC")
    fun variantsFor(gameId: Long): Flow<List<GameVariantEntity>>

    @Query("SELECT * FROM game_variants WHERE id = :variantId LIMIT 1")
    suspend fun variantById(variantId: Long): GameVariantEntity?

    @Query("UPDATE game_variants SET isPrimary = (id = :variantId) WHERE gameId = :gameId")
    suspend fun setPrimaryVariant(gameId: Long, variantId: Long)

    @Query("SELECT pickedVariantKey FROM games WHERE id = :id")
    suspend fun pickedVariantKey(id: Long): String?

    @Query("UPDATE games SET pickedVariantKey = :key WHERE id = :id")
    suspend fun setPickedVariant(id: Long, key: String?)

    /** Makes a variant the one the game installs from (only while the game is not installed). */
    @Query("""
        UPDATE games SET sourceUri = :uri, sourceKind = :kind, downloadSize = :size
        WHERE id = :gameId AND installStatus = 'NOT_INSTALLED'
    """)
    suspend fun applyVariantToGame(gameId: Long, uri: String, kind: String, size: Long): Int

    // Installer Chunks
    @Query("SELECT * FROM installer_chunks WHERE gameId = :gameId ORDER BY chunkIndex ASC")
    fun getChunksForGame(gameId: Long): Flow<List<InstallerChunkEntity>>

    @Query("SELECT * FROM installer_chunks WHERE gameId = :gameId ORDER BY chunkIndex ASC")
    suspend fun getChunksForGameSync(gameId: Long): List<InstallerChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<InstallerChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: InstallerChunkEntity)

    @Query("DELETE FROM installer_chunks WHERE gameId = :gameId")
    suspend fun deleteChunksForGame(gameId: Long)

    // Extras
    @Query("SELECT * FROM game_extras WHERE gameId = :gameId ORDER BY type ASC, name ASC")
    fun getExtrasForGame(gameId: Long): Flow<List<GameExtraEntity>>

    @Query("SELECT * FROM game_extras WHERE gameId = :gameId ORDER BY type ASC, name ASC")
    suspend fun getExtrasForGameSync(gameId: Long): List<GameExtraEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtras(extras: List<GameExtraEntity>)

    @Update
    suspend fun updateExtra(extra: GameExtraEntity)
}

/** Projection used when diffing a rescan against what is already stored. */
data class CandidateKey(val id: Long, val sourceKey: String?)

data class FolderGameRow(val id: Long, val sourceKey: String, val notInstalled: Boolean)
