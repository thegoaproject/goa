package com.goa.data.repository

import com.goa.data.api.GogDownloadEntry
import com.goa.data.api.GogExtraEntry
import com.goa.data.api.GogLibraryClient
import com.goa.data.api.toSecureGogImageUrl
import com.goa.data.db.ExtraType
import com.goa.domain.scan.GameSource
import com.goa.data.db.GameDao
import com.goa.data.db.GameEntity
import com.goa.data.db.GameExtraEntity
import com.goa.data.db.InstallStatus
import com.goa.data.db.InstallerChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import com.goa.ui.detail.formatCleanDescription
import java.io.File
import java.util.Locale

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val current: Int, val total: Int, val currentTitle: String) : SyncState()
    object Completed : SyncState()
    data class Error(val message: String) : SyncState()
}

class GameCatalogRepository(
    private val libraryClient: GogLibraryClient,
    private val authRepository: AuthRepository,
    private val gameDao: GameDao
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun getDosGames(): Flow<List<GameEntity>> = gameDao.getDosGames()

    fun getRecentlyPlayedGames(): Flow<List<GameEntity>> = gameDao.getRecentlyPlayedGames()

    fun getGameById(id: Long): Flow<GameEntity?> = gameDao.getGameById(id)

    suspend fun seedFreewareGamesIfEmpty() = withContext(Dispatchers.IO) {
        DefaultCatalogSeed.games.forEach { game ->
            val existing = gameDao.getGameByIdSync(game.id)
            if (existing == null) {
                gameDao.insertGame(game)
            }
        }
        DefaultCatalogSeed.chunks.forEach { chunk ->
            gameDao.insertChunks(listOf(chunk))
        }
    }

    /**
     * Drops GOG games that aren't installed and aren't in [ownedIds]; null drops all of them (sign-out), so the next
     * account never sees the previous one's library. Installed games stay: their files are on this phone.
     */
    suspend fun pruneGogLibrary(ownedIds: Set<Long>?) = withContext(Dispatchers.IO) {
        val drop = gameDao.notInstalledIds()
            .filter { GameSource.of(it) == GameSource.GOG && (ownedIds == null || it !in ownedIds) }
        drop.chunked(500).forEach { ids ->
            gameDao.deleteChunksForGames(ids)
            gameDao.deleteNotInstalled(ids)
        }
    }

    suspend fun syncLibrary(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authRepository.getValidAccessToken()
                ?: throw IllegalStateException("Not authenticated with GOG")

            _syncState.value = SyncState.Syncing(0, 0, "Fetching owned games...")

            val ownedIds = libraryClient.fetchOwnedGames(token).getOrThrow()
            val total = ownedIds.size
            pruneGogLibrary(ownedIds.toSet())  // another account's games, or ones refunded or removed

            ownedIds.forEachIndexed { index, gameId ->
                try {
                    // Check if game already in DB and not forcing
                    val existing = gameDao.getGameByIdSync(gameId)
                    val existingChunks = gameDao.getChunksForGameSync(gameId)
                    val hasBrokenChunks = existingChunks.isEmpty() ||
                            existingChunks.any { it.downloadUrl.isBlank() } ||
                            (existingChunks.size > 1 && existingChunks.map { it.fileName }.distinct().size < existingChunks.size) ||
                            (existingChunks.firstOrNull()?.fileName?.endsWith(".bin", ignoreCase = true) == true)
                    val hasBrokenUrls = existing?.boxArtUrl?.startsWith("//") == true ||
                            (existing?.bannerUrl != null && existing.bannerUrl.startsWith("//"))

                    if (existing != null && !force && !hasBrokenChunks && !hasBrokenUrls) {
                        // Still re-evaluate isDosNative on existing DB rows in case detection rules improved
                        val shouldBeDos = isDosGame(
                            title = existing.title,
                            tags = emptyList(),
                            downloads = emptyList(),
                            isUsingDosBox = false,
                            releaseYear = existing.releaseYear
                        )
                        if (shouldBeDos != existing.isDosNative) {
                            gameDao.insertGame(existing.copy(isDosNative = shouldBeDos))
                        }
                        return@forEachIndexed
                    }

                    _syncState.value = SyncState.Syncing(index + 1, total, "Syncing item ${index + 1} of $total")

                    // Fetch details
                    val detailsResult = libraryClient.fetchGameDetails(token, gameId)
                    val details = detailsResult.getOrNull()

                    // Fetch product info for tags, slug, images, and modern downloads expansion
                    val productResult = libraryClient.fetchProductDetails(token, gameId)
                    val product = productResult.getOrNull()

                    // Fetch v2 game metadata for authoritative isUsingDosBox and globalReleaseDate
                    val v2Result = libraryClient.fetchV2GameDetails(gameId)
                    val v2Details = v2Result.getOrNull()

                    val title = details?.title ?: product?.title ?: v2Details?.embedded?.product?.title ?: "Unknown Game ($gameId)"
                    val slug = product?.slug ?: title.lowercase(Locale.ROOT).replace("[^a-z0-9]+".toRegex(), "_")

                    // Extract box art URL and banner
                    val imagesObj = product?.images
                    val rawBoxArt = imagesObj?.get("boxArtImage")?.toString()?.replace("\"", "")
                        ?: imagesObj?.get("icon")?.toString()?.replace("\"", "")
                        ?: details?.backgroundImage
                        ?: "https://images.gog.com/placeholder.jpg"
                    val boxArtUrl = rawBoxArt.toSecureGogImageUrl() ?: "https://images.gog.com/placeholder.jpg"

                    val rawBanner = details?.backgroundImage
                        ?: imagesObj?.get("background")?.toString()?.replace("\"", "")
                    val bannerUrl = rawBanner.toSecureGogImageUrl()

                    val rawDesc = product?.description?.get("full")?.jsonPrimitive?.contentOrNull
                        ?: product?.description?.get("lead")?.jsonPrimitive?.contentOrNull
                        ?: product?.description?.get("full")?.toString()?.replace("\"", "")
                        ?: product?.description?.get("lead")?.toString()?.replace("\"", "")
                        ?: ""
                    val description = formatCleanDescription(rawDesc)

                    // Check if MS-DOS native
                    val rawGlobalDate = v2Details?.embedded?.product?.globalReleaseDate
                        ?: product?.originalReleaseDate
                        ?: product?.globalReleaseDate
                    val parsedGlobalYear = rawGlobalDate?.let { dateStr ->
                        Regex("""\b(19\d{2}|20\d{2})\b""").find(dateStr)?.value?.toIntOrNull()
                    } ?: Regex("""\b(19[89]\d|200\d)\b""").find(title)?.value?.toIntOrNull()
                    val resolvedYear = parsedGlobalYear ?: existing?.releaseYear

                    val isDos = isDosGame(
                        title = title,
                        tags = product?.tags ?: emptyList(),
                        downloads = details?.downloads ?: emptyList(),
                        isUsingDosBox = v2Details?.isUsingDosBox == true,
                        releaseYear = resolvedYear
                    )

                    val chunkEntities = mutableListOf<InstallerChunkEntity>()
                    val extraEntities = mutableListOf<GameExtraEntity>()

                    // 1. Try parsing modern product.downloads format (api.gog.com/products/{id}?expand=downloads)
                    val productDownloadsObj = product?.downloads as? JsonObject
                    if (productDownloadsObj != null) {
                        val installersArray = productDownloadsObj["installers"] as? JsonArray
                        if (installersArray != null && installersArray.isNotEmpty()) {
                            // Find windows installers, preferring English
                            val windowsInstallers = installersArray.mapNotNull { it as? JsonObject }
                                .filter { it["os"]?.jsonPrimitive?.content.equals("windows", ignoreCase = true) }
                            val chosenInstaller = windowsInstallers.find { it["language"]?.jsonPrimitive?.content.equals("en", ignoreCase = true) }
                                ?: windowsInstallers.firstOrNull()
                                ?: installersArray.firstOrNull() as? JsonObject

                            if (chosenInstaller != null) {
                                val filesArray = chosenInstaller["files"] as? JsonArray
                                val rawName = chosenInstaller["name"]?.jsonPrimitive?.content ?: title
                                val cleanBase = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    .removeSuffix(".exe")
                                    .removeSuffix(".bin")
                                    .trimEnd('_', '.')
                                    .ifBlank { "setup_$gameId" }

                                filesArray?.forEachIndexed { fileIdx, fileElem ->
                                    val fObj = fileElem.jsonObject
                                    val fDownlink = fObj["downlink"]?.jsonPrimitive?.content ?: ""
                                    val fSize = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                                    val chunkFileName = if (filesArray.size == 1 || fileIdx == 0) {
                                        "$cleanBase.exe"
                                    } else {
                                        "$cleanBase-$fileIdx.bin"
                                    }
                                    if (fDownlink.isNotBlank()) {
                                        chunkEntities.add(
                                            InstallerChunkEntity(
                                                id = "${gameId}_chunk_$fileIdx",
                                                gameId = gameId,
                                                fileName = chunkFileName,
                                                downloadUrl = fDownlink,
                                                totalBytes = fSize,
                                                downloadedBytes = 0L,
                                                isCompleted = false,
                                                chunkIndex = fileIdx
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Bonus content / extras from product API
                        val bonusArray = productDownloadsObj["bonus_content"] as? JsonArray
                        bonusArray?.forEachIndexed { bonusIdx, bElem ->
                            val bObj = bElem.jsonObject
                            val bName = bObj["name"]?.jsonPrimitive?.content ?: "Bonus $bonusIdx"
                            val bTypeStr = bObj["type"]?.jsonPrimitive?.content ?: ""
                            val bFiles = bObj["files"] as? JsonArray
                            val firstFile = bFiles?.firstOrNull()?.jsonObject
                            val bDownlink = firstFile?.get("downlink")?.jsonPrimitive?.content ?: ""
                            val bSize = firstFile?.get("size")?.jsonPrimitive?.longOrNull ?: 0L

                            val extraType = when {
                                bTypeStr.contains("manual", ignoreCase = true) || bName.contains("manual", ignoreCase = true) -> ExtraType.MANUAL_PDF
                                bTypeStr.contains("soundtrack", ignoreCase = true) || bName.contains("soundtrack", ignoreCase = true) -> ExtraType.SOUNDTRACK
                                bName.contains("reference", ignoreCase = true) -> ExtraType.REFERENCE_CARD
                                bName.contains("wallpaper", ignoreCase = true) -> ExtraType.WALLPAPER
                                else -> ExtraType.OTHER
                            }

                            if (bDownlink.isNotBlank()) {
                                extraEntities.add(
                                    GameExtraEntity(
                                        id = "${gameId}_extra_$bonusIdx",
                                        gameId = gameId,
                                        name = bName,
                                        type = extraType,
                                        downloadUrl = bDownlink,
                                        sizeBytes = bSize,
                                        localFilePath = null,
                                        isDownloaded = false
                                    )
                                )
                            }
                        }
                    }

                    // 2. Fallback to embed account/gameDetails if product downloads was empty
                    if (chunkEntities.isEmpty() && details?.downloads != null && details.downloads.isNotEmpty()) {
                        val downloads = details.downloads
                        val rawName = details.title.ifBlank { title.ifBlank { "setup_$gameId" } }
                        val cleanBase = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            .removeSuffix(".exe")
                            .removeSuffix(".bin")
                            .trimEnd('_', '.')
                            .ifBlank { "setup_$gameId" }

                        downloads.forEachIndexed { chunkIndex, dl ->
                            val chunkSize = parseSizeToBytes(dl.size)
                            if (dl.downlink.isNotBlank()) {
                                val chunkFileName = if (downloads.size == 1 || chunkIndex == 0) {
                                    "$cleanBase.exe"
                                } else {
                                    "$cleanBase-$chunkIndex.bin"
                                }
                                chunkEntities.add(
                                    InstallerChunkEntity(
                                        id = "${gameId}_chunk_$chunkIndex",
                                        gameId = gameId,
                                        fileName = chunkFileName,
                                        downloadUrl = dl.downlink,
                                        totalBytes = chunkSize,
                                        downloadedBytes = 0L,
                                        isCompleted = false,
                                        chunkIndex = chunkIndex
                                    )
                                )
                            }
                        }
                    }

                    if (extraEntities.isEmpty() && details?.extras != null) {
                        val existingExtras = gameDao.getExtrasForGameSync(gameId).associateBy { it.id }
                        details.extras.forEachIndexed { extraIndex, ex ->
                            val extraId = "${gameId}_extra_$extraIndex"
                            val existingExtra = existingExtras[extraId]
                            val extraSize = parseSizeToBytes(ex.size)
                            val extraType = when {
                                ex.type.contains("manual", ignoreCase = true) || ex.name.contains("manual", ignoreCase = true) -> ExtraType.MANUAL_PDF
                                ex.type.contains("soundtrack", ignoreCase = true) || ex.name.contains("soundtrack", ignoreCase = true) -> ExtraType.SOUNDTRACK
                                ex.name.contains("reference", ignoreCase = true) -> ExtraType.REFERENCE_CARD
                                ex.name.contains("wallpaper", ignoreCase = true) -> ExtraType.WALLPAPER
                                else -> ExtraType.OTHER
                            }
                            val isDownloaded = existingExtra?.isDownloaded == true &&
                                existingExtra.localFilePath?.let { File(it).exists() } == true
                            val localPath = if (isDownloaded) existingExtra?.localFilePath else null

                            if (ex.downlink.isNotBlank()) {
                                extraEntities.add(
                                    GameExtraEntity(
                                        id = extraId,
                                        gameId = gameId,
                                        name = ex.name,
                                        type = extraType,
                                        downloadUrl = ex.downlink,
                                        sizeBytes = extraSize,
                                        localFilePath = localPath,
                                        isDownloaded = isDownloaded
                                    )
                                )
                            }
                        }
                    }

                    val totalDownloadSize = chunkEntities.sumOf { it.totalBytes }

                    val isActuallyInstalled = existing?.installStatus == InstallStatus.READY_TO_PLAY &&
                        !existing.localInstallPath.isNullOrBlank() &&
                        !existing.mainExecutable.isNullOrBlank() &&
                        File(existing.localInstallPath).let { dir ->
                            dir.exists() && (
                                File(dir, existing.mainExecutable).let { it.exists() && it.length() > 0 } ||
                                dir.walkTopDown().any { it.isFile && it.name.equals(existing.mainExecutable, ignoreCase = true) && it.length() > 0 }
                            )
                        }

                    val effectiveStatus = if (existing?.installStatus == InstallStatus.READY_TO_PLAY) {
                        if (isActuallyInstalled) InstallStatus.READY_TO_PLAY else InstallStatus.NOT_INSTALLED
                    } else {
                        existing?.installStatus ?: InstallStatus.NOT_INSTALLED
                    }

                    val gameEntity = GameEntity(
                        id = gameId,
                        title = title,
                        slug = slug,
                        boxArtUrl = boxArtUrl,
                        bannerUrl = bannerUrl,
                        description = description,
                        releaseYear = resolvedYear,
                        isDosNative = isDos,
                        downloadSize = totalDownloadSize,
                        installStatus = effectiveStatus,
                        localInstallPath = if (isActuallyInstalled) existing?.localInstallPath else null,
                        mainExecutable = if (isActuallyInstalled) existing?.mainExecutable else null,
                        dosboxConfPath = if (isActuallyInstalled) existing?.dosboxConfPath else null,
                        lastPlayedTimestamp = existing?.lastPlayedTimestamp,
                        playTimeMinutes = existing?.playTimeMinutes ?: 0L
                    )

                    gameDao.insertGame(gameEntity)
                    if (chunkEntities.isNotEmpty()) {
                        gameDao.deleteChunksForGame(gameId)
                        gameDao.insertChunks(chunkEntities)
                    }
                    if (extraEntities.isNotEmpty()) {
                        gameDao.insertExtras(extraEntities)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GameCatalogRepository", "Failed to sync details for game $gameId", e)
                }
            }

            _syncState.value = SyncState.Completed
        }.onFailure { err ->
            _syncState.value = SyncState.Error(err.message ?: "Failed to sync library")
        }
    }

    private fun isDosGame(
        title: String,
        tags: List<String>,
        downloads: List<GogDownloadEntry>,
        isUsingDosBox: Boolean = false,
        releaseYear: Int? = null
    ): Boolean = DosClassifier.isDosGame(title, tags, downloads, isUsingDosBox, releaseYear)
}

/** Which GOG games are DOS games (shown in GOA's library). */
object DosClassifier {
    fun isDosGame(
        title: String,
        tags: List<String>,
        downloads: List<GogDownloadEntry>,
        isUsingDosBox: Boolean = false,
        releaseYear: Int? = null
    ): Boolean {
        val lowerTitle = title.lowercase(Locale.ROOT)

        // Exclude modern Windows remakes, source ports, and non-DOS Win9x/modern titles
        val modernPortExclusions = listOf(
            "enhanced", "redux", "rtx", "remastered", "definitive edition", "(2013)",
            "doom + doom ii", "civilization® iii", "shadow warrior 2", "tomb raider 2",
            "tomb raider 3", "red baron 3d", "dink smallwood hd", "interstate",
            "thief", "unreal", "alpha centauri", "system shock® 2", "system shock 2",
            "rogue squadron", "industry giant", "pod gold", "speed busters",
            "slave zero", "corsairs", "simcity™ 3000", "simcity 3000", "deus ex",
            "monkey island™ 2 special edition", "the secret of monkey island™: special edition",
            "settlers® 3", "settlers® 4", "settlers 3", "settlers 4", "heretic + hexen",
            "fallout 3", "fallout 4", "fallout: new vegas", "fallout new vegas", "fallout tactics"
        )
        if (modernPortExclusions.any { lowerTitle.contains(it) }) {
            return false
        }

        // Authoritative GOG v2 DOSBox flag
        if (isUsingDosBox) {
            return true
        }

        // Tag detection
        if (tags.any { it.equals("DOS", ignoreCase = true) || it.contains("DOSBox", ignoreCase = true) }) {
            return true
        }

        // Title heuristics for known classic DOS titles on GOG
        val knownDosKeywords = listOf(
            "hexen", "heretic", "doom", "quake", "duke nukem", "blood", "rise of the triad",
            "wolfenstein 3d", "spear of destiny", "blake stone", "system shock", "tomb raider 1",
            "commander keen", "fallout", "warcraft", "x-com", "ufo: enemy unknown",
            "master of orion", "alone in the dark", "simcity", "theme park", "theme hospital", "syndicate",
            "dungeon keeper", "rayman", "wing commander", "descent", "screamer", "daggerfall",
            "arena", "battlespire", "redguard", "terminator: future shock", "skynet",
            "lands of lore", "eye of the beholder", "magic carpet", "ultimabox", "ultima",
            "albion", "colonization", "civilization", "dark forces", "crusader: no remorse",
            "crusader: no regret", "strike commander", "privateer", "populous", "redneck rampage",
            "shadow warrior", "death rally", "mortal kombat", "one must fall", "bio menace",
            "stargunner", "wacky wheels", "tyrian", "jill of the jungle", "jagged alliance",
            "beneath a steel sky", "flight of the amazon queen", "lure of the temptress",
            "dragonsphere", "teenagent", "red baron 1", "red baron pack", "akalabeth",
            "indiana jones", "monkey island", "loom", "sam & max", "day of the tentacle",
            "full throttle", "the dig", "zak mckracken", "maniac mansion", "simon the sorcerer",
            "gobliiins", "kyrandia", "king's quest", "space quest", "police quest",
            "leisure suit larry", "quest for glory", "gabriel knight", "phantasmagoria",
            "betrayal at krondor", "might and magic", "wizardry", "gold box", "dark sun",
            "ravenloft", "menzoberranzan", "stonekeep", "realms of arkania", "ishar",
            "settlers", "master of magic", "panzer general", "fantasy general", "star control",
            "tie fighter", "x-wing", "rebel assault", "earthsiege", "mechwarrior", "shattered steel",
            "quarantine", "whiplash", "fatal racing", "slipstream 5000", "normality",
            "realms of the haunting", "terra nova", "system shock: classic", "war wind",
            "the last express", "little big adventure", "bioforge", "cyberia", "ecstatica"
        )

        // A keyword names the game when the title starts with it ("Syndicate Plus", "Heretic: Shadow of the Serpent
        // Riders"); inside a title it only counts for games released before 1999, when DOS was over: "Street Racing
        // Syndicate" (2005) isn't Syndicate
        val modernEra = releaseYear != null && releaseYear >= 1999
        val stripped = lowerTitle.trimStart().removePrefix("the ")
        if (knownDosKeywords.any { stripped.startsWith(it) || (!modernEra && lowerTitle.contains(it)) }) {
            return true
        }

        // Release year heuristic: classic PC games originally released in 1980..1996
        if (releaseYear != null && releaseYear in 1980..1996) {
            return true
        }

        // Check download filenames for DOS indicators
        if (!modernEra && downloads.any { it.name.contains("dos", ignoreCase = true) || it.downlink.contains("dos", ignoreCase = true) }) {
            return true
        }

        return false
    }
}

/** "1.2 GB" -> bytes. */
private fun parseSizeToBytes(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L
        val trimmed = sizeStr.trim().uppercase(Locale.ROOT)
        return try {
            when {
                trimmed.endsWith("GB") -> {
                    val num = trimmed.removeSuffix("GB").trim().toDouble()
                    (num * 1024 * 1024 * 1024).toLong()
                }
                trimmed.endsWith("MB") -> {
                    val num = trimmed.removeSuffix("MB").trim().toDouble()
                    (num * 1024 * 1024).toLong()
                }
                trimmed.endsWith("KB") -> {
                    val num = trimmed.removeSuffix("KB").trim().toDouble()
                    (num * 1024).toLong()
                }
                trimmed.endsWith("B") -> {
                    trimmed.removeSuffix("B").trim().toLong()
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
