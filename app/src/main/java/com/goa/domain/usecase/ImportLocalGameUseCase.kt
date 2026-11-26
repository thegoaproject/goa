package com.goa.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.goa.data.db.GameDao
import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.data.storage.AppStorageManager
import com.goa.domain.scan.FilenameTitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ImportLocalGameUseCase(
    private val context: Context,
    private val gameDao: GameDao,
    private val storageManager: AppStorageManager,
    private val extractionManager: ExtractionManager,
    /** Throws RegistrationRequiredException when the player has no free game slot (LicenseManager.requireSlot). */
    private val requireSlot: suspend (gameId: Long?) -> Unit = {}
) {
    companion object {
        private const val TAG = "ImportLocalGame"
    }

    suspend fun importLocalGame(
        uri: Uri,
        customTitle: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            requireSlot(null)
            val contentResolver = context.contentResolver
            val originalFilename = getFileNameFromUri(uri) ?: "imported_game.zip"

            // Derive title, year and slug from the file name
            val parsed = FilenameTitleParser.parse(originalFilename)
            val cleanTitle = customTitle?.takeIf { it.isNotBlank() } ?: parsed.title

            val gameId = 8_000_000_000L + (System.currentTimeMillis() % 1_000_000_000L)
            val slug = if (customTitle.isNullOrBlank()) parsed.slug else cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_").trim('_')

            // Create initial GameEntity in DB
            val entity = GameEntity(
                id = gameId,
                title = cleanTitle,
                slug = slug,
                boxArtUrl = "https://images.gog.com/placeholder.jpg",
                bannerUrl = null,
                description = "Locally imported MS-DOS title from $originalFilename.",
                releaseYear = parsed.year,
                isDosNative = true,
                downloadSize = 0L,
                installStatus = InstallStatus.EXTRACTING
            )
            gameDao.insertGame(entity)

            // Stage imported file into downloadDir
            val downloadDir = storageManager.getDownloadDir(gameId)
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, originalFilename)

            Log.i(TAG, "Copying $uri to ${targetFile.absolutePath}")
            contentResolver.openInputStream(uri).use { inputStream ->
                if (inputStream == null) {
                    throw IllegalStateException("Unable to open content stream from URI: $uri")
                }
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Extract and sanitize
            val extractResult = extractionManager.extractAndSanitize(gameId, onProgress)
            if (extractResult.isFailure) {
                gameDao.updateStatus(gameId, InstallStatus.ERROR)
                val err = extractResult.exceptionOrNull() ?: RuntimeException("Extraction failed")
                Log.e(TAG, "Extraction failed for local game: $originalFilename", err)
                throw err
            }

            Log.i(TAG, "Successfully imported local game: $cleanTitle (ID: $gameId)")
            gameId
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve display name from uri $uri", e)
        }
        return name ?: uri.lastPathSegment
    }
}
