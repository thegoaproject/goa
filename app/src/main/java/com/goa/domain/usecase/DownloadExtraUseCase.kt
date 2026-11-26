package com.goa.domain.usecase

import android.content.Context
import android.util.Log
import com.goa.data.api.GogLibraryClient
import com.goa.data.db.ExtraType
import com.goa.data.db.GameDao
import com.goa.data.db.GameExtraEntity
import com.goa.data.repository.AuthRepository
import com.goa.data.storage.AppStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipInputStream

sealed class ExtraContent {
    data class Pdf(val file: File, val title: String) : ExtraContent()
    data class Audio(val tracks: List<File>, val title: String) : ExtraContent()
    data class Images(val images: List<File>, val title: String) : ExtraContent()
    data class Generic(val file: File, val title: String) : ExtraContent()
}

class DownloadExtraUseCase(
    private val context: Context,
    private val gameDao: GameDao,
    private val storageManager: AppStorageManager,
    private val authRepository: AuthRepository,
    private val libraryClient: GogLibraryClient
) {
    private val _downloadingExtraIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingExtraIds: StateFlow<Set<String>> = _downloadingExtraIds.asStateFlow()

    suspend fun downloadExtra(extra: GameExtraEntity): Result<File> = withContext(Dispatchers.IO) {
        if (_downloadingExtraIds.value.contains(extra.id)) {
            return@withContext Result.failure(IllegalStateException("Already downloading ${extra.name}"))
        }

        _downloadingExtraIds.value = _downloadingExtraIds.value + extra.id

        try {
            val extrasDir = storageManager.getExtrasDir(extra.gameId)
            if (!extrasDir.exists()) {
                extrasDir.mkdirs()
            }

            val token = authRepository.getValidAccessToken()
            var currentUrl = if (extra.downloadUrl.contains("downlink") ||
                extra.downloadUrl.contains("api.gog.com") ||
                extra.downloadUrl.contains("embed.gog.com")
            ) {
                libraryClient.resolveDownlink(token ?: "", extra.downloadUrl).getOrDefault(extra.downloadUrl)
            } else {
                extra.downloadUrl
            }

            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    if (token != null && currentUrl.contains("gog.com") &&
                        !currentUrl.contains("content-system") &&
                        !currentUrl.contains("cdn.gog.com")
                    ) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }

                val code = conn.responseCode
                if ((code == HttpURLConnection.HTTP_MOVED_PERM ||
                     code == HttpURLConnection.HTTP_MOVED_TEMP ||
                     code == HttpURLConnection.HTTP_SEE_OTHER ||
                     code == 307 || code == 308) && redirects < 5
                ) {
                    val newLoc = conn.getHeaderField("Location")
                    if (!newLoc.isNullOrBlank()) {
                        currentUrl = if (newLoc.startsWith("http")) newLoc else URL(url, newLoc).toString()
                        conn.disconnect()
                        redirects++
                        continue
                    }
                }
                break
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Server returned HTTP $responseCode from $currentUrl")
            }

            // Extract filename from Content-Disposition or URL path
            val resolvedFileName = resolveFileName(conn, currentUrl, extra)
            val targetFile = File(extrasDir, resolvedFileName)
            val tempFile = File(extrasDir, "${targetFile.name}.part")

            Log.i(TAG, "Downloading extra '${extra.name}' from $currentUrl to ${targetFile.absolutePath}")

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
            }

            // If the downloaded file is a ZIP archive, automatically unpack it into a dedicated folder
            val finalFile = if (targetFile.name.endsWith(".zip", ignoreCase = true)) {
                val unpackDir = File(extrasDir, targetFile.nameWithoutExtension)
                if (!unpackDir.exists()) unpackDir.mkdirs()
                extractZipArchive(targetFile, unpackDir)

                val allFiles = unpackDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()
                val pdf = allFiles.firstOrNull { it.extension.equals("pdf", ignoreCase = true) }
                if (extra.type == ExtraType.MANUAL_PDF && pdf != null) {
                    pdf
                } else {
                    unpackDir
                }
            } else {
                targetFile
            }

            val updatedExtra = extra.copy(
                localFilePath = finalFile.absolutePath,
                isDownloaded = true,
                sizeBytes = if (finalFile.length() > 0) finalFile.length() else extra.sizeBytes
            )
            gameDao.updateExtra(updatedExtra)

            Log.i(TAG, "Successfully downloaded extra '${extra.name}' -> ${finalFile.absolutePath}")
            Result.success(finalFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download extra '${extra.name}'", e)
            Result.failure(e)
        } finally {
            _downloadingExtraIds.value = _downloadingExtraIds.value - extra.id
        }
    }

    fun resolveExtraContent(extra: GameExtraEntity): ExtraContent? {
        val path = extra.localFilePath ?: return null
        val target = File(path)
        if (!target.exists()) return null

        // If target is a zip file that hasn't been extracted yet
        val dirOrFile = if (target.name.endsWith(".zip", ignoreCase = true)) {
            val destDir = File(target.parentFile ?: storageManager.getExtrasDir(extra.gameId), target.nameWithoutExtension)
            if (!destDir.exists()) destDir.mkdirs()
            extractZipArchive(target, destDir)
            destDir
        } else {
            target
        }

        val allFiles = if (dirOrFile.isDirectory) {
            dirOrFile.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.startsWith("__MACOSX") }.toList()
        } else {
            listOf(dirOrFile)
        }

        // 1. Audio check (soundtracks)
        val audioExts = setOf("mp3", "flac", "ogg", "wav", "aac", "m4a", "opus")
        val audioFiles = allFiles.filter { it.extension.lowercase() in audioExts }.sortedBy { it.name }
        if (audioFiles.isNotEmpty() && (extra.type == ExtraType.SOUNDTRACK || audioFiles.size == allFiles.size || extra.name.contains("soundtrack", ignoreCase = true))) {
            return ExtraContent.Audio(audioFiles, extra.name)
        }

        // 2. PDF check (manuals, guides, reference cards)
        val pdfFile = allFiles.firstOrNull { it.extension.equals("pdf", ignoreCase = true) }
        if (pdfFile != null && (extra.type == ExtraType.MANUAL_PDF || extra.type == ExtraType.REFERENCE_CARD || extra.name.contains("manual", ignoreCase = true))) {
            return ExtraContent.Pdf(pdfFile, extra.name)
        }

        // 3. Image check (wallpapers, artworks, avatars)
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val imageFiles = allFiles.filter { it.extension.lowercase() in imageExts }.sortedBy { it.name }
        if (imageFiles.isNotEmpty() && (extra.type == ExtraType.WALLPAPER || extra.name.contains("wallpaper", ignoreCase = true) || extra.name.contains("avatar", ignoreCase = true) || extra.name.contains("artwork", ignoreCase = true))) {
            return ExtraContent.Images(imageFiles, extra.name)
        }

        // Fallback: if audio files exist
        if (audioFiles.isNotEmpty()) {
            return ExtraContent.Audio(audioFiles, extra.name)
        }

        // Fallback: if PDF exists
        if (pdfFile != null) {
            return ExtraContent.Pdf(pdfFile, extra.name)
        }

        // Fallback: if images exist
        if (imageFiles.isNotEmpty()) {
            return ExtraContent.Images(imageFiles, extra.name)
        }

        return ExtraContent.Generic(dirOrFile, extra.name)
    }

    private fun extractZipArchive(file: File, destDir: File) {
        try {
            ZipInputStream(file.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = File(entry.name).name // avoid directory traversal
                        if (entryName.isNotBlank() && !entryName.startsWith(".") && !entryName.startsWith("__MACOSX")) {
                            val outFile = File(destDir, entryName)
                            outFile.outputStream().buffered().use { out ->
                                zis.copyTo(out)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract zip archive $file", e)
        }
    }

    private fun resolveFileName(conn: HttpURLConnection, finalUrl: String, extra: GameExtraEntity): String {
        val contentDisposition = conn.getHeaderField("Content-Disposition")
        if (!contentDisposition.isNullOrBlank()) {
            val filenameRegex = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE)
            val match = filenameRegex.find(contentDisposition)
            if (match != null) {
                val rawName = match.groupValues[1].trim()
                if (rawName.isNotBlank()) {
                    return runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                }
            }
        }

        val urlPath = try {
            URL(finalUrl).path.substringAfterLast('/')
        } catch (e: Exception) {
            ""
        }
        if (urlPath.contains(".") && !urlPath.endsWith("/")) {
            return runCatching { URLDecoder.decode(urlPath, "UTF-8") }.getOrDefault(urlPath)
        }

        val sanitizedBase = extra.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        val extension = when (extra.type) {
            ExtraType.MANUAL_PDF -> ".pdf"
            ExtraType.SOUNDTRACK -> ".zip"
            ExtraType.WALLPAPER -> ".zip"
            else -> if (sanitizedBase.contains(".")) "" else ".bin"
        }

        return if (sanitizedBase.endsWith(extension, ignoreCase = true)) {
            sanitizedBase
        } else {
            "$sanitizedBase$extension"
        }
    }

    companion object {
        private const val TAG = "DownloadExtraUseCase"
    }
}

