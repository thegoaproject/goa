package com.goa.data.downloader

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goa.GoaApplication
import com.goa.data.db.InstallStatus
import com.goa.data.repository.FreewareCdn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class GameDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val gameId = inputData.getLong(KEY_GAME_ID, -1L)
        val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return@withContext Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val targetFilename = inputData.getString(KEY_TARGET_FILENAME) ?: "installer_part.bin"

        if (gameId == -1L) return@withContext Result.failure()

        val app = applicationContext as GoaApplication
        val gameDao = app.database.gameDao()
        val storageManager = app.storageManager

        val destDir = storageManager.getDownloadDir(gameId)
        val destFile = File(destDir, targetFilename)

        var existingBytes = if (destFile.exists()) destFile.length() else 0L

        try {
            gameDao.updateStatus(gameId, InstallStatus.DOWNLOADING)

            val token = app.authRepository.getValidAccessToken()
            var currentUrl = if (downloadUrl.contains("downlink") || downloadUrl.contains("api.gog.com") || downloadUrl.contains("embed.gog.com")) {
                app.libraryClient.resolveDownlink(token ?: "", downloadUrl).getOrDefault(downloadUrl)
            } else {
                downloadUrl
            }

            var conn: java.net.HttpURLConnection
            var redirects = 0
            while (true) {
                val url = java.net.URL(currentUrl)
                conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 GOA/1.0")
                    FreewareCdn.requestHeaders(currentUrl).forEach { (name, value) -> setRequestProperty(name, value) }
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                    if (token != null && currentUrl.contains("gog.com") && !currentUrl.contains("content-system") && !currentUrl.contains("cdn.gog.com")) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val code = conn.responseCode
                if ((code == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                     code == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                     code == java.net.HttpURLConnection.HTTP_SEE_OTHER ||
                     code == 307 || code == 308) && redirects < 5
                ) {
                    val newLoc = conn.getHeaderField("Location")
                    if (!newLoc.isNullOrBlank()) {
                        currentUrl = if (newLoc.startsWith("http")) newLoc else java.net.URL(url, newLoc).toString()
                        conn.disconnect()
                        redirects++
                        continue
                    }
                }
                break
            }

            val responseCode = conn.responseCode
            if (responseCode != java.net.HttpURLConnection.HTTP_OK && responseCode != java.net.HttpURLConnection.HTTP_PARTIAL) {
                throw java.io.IOException("HTTP error $responseCode from $currentUrl")
            }

            val isPartial = responseCode == java.net.HttpURLConnection.HTTP_PARTIAL
            if (!isPartial) {
                // Server does not support Range or returned full file, restart from 0
                existingBytes = 0L
                destFile.delete()
            }

            val contentLength = conn.contentLengthLong.let { if (it > 0) it else 0L }
            val totalBytes = existingBytes + contentLength

            val buffer = ByteArray(64 * 1024)
            var downloaded = existingBytes
            var lastProgressTime = 0L
            var lastProgressPercent = -1

            conn.inputStream.use { input ->
                FileOutputStream(destFile, isPartial).use { fos ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) break
                        fos.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val progressPercent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0

                        if (now - lastProgressTime >= 400 || (progressPercent != lastProgressPercent && now - lastProgressTime >= 200)) {
                            lastProgressTime = now
                            lastProgressPercent = progressPercent
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS_PERCENT to progressPercent,
                                    KEY_DOWNLOADED_BYTES to downloaded,
                                    KEY_TOTAL_BYTES to totalBytes
                                )
                            )
                        }
                    }
                }
            }
            conn.disconnect()

            if (isStopped) {
                gameDao.updateStatus(gameId, InstallStatus.DOWNLOAD_PAUSED)
                return@withContext Result.retry()
            }

            // Mirrored freeware must match the published original byte-for-byte (licence terms + corrupt-download guard)
            FreewareCdn.expectedSha256(destFile.name)?.let { expected ->
                val actual = sha256Hex(destFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    android.util.Log.e("GameDownloadWorker", "Checksum mismatch for ${destFile.name}: expected $expected, got $actual")
                    destFile.delete()
                    gameDao.updateStatus(gameId, InstallStatus.ERROR)
                    return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Download corrupted (checksum mismatch)"))
                }
            }

            // Update chunk status
            val chunk = gameDao.getChunksForGameSync(gameId).find { it.id == chunkId }
            if (chunk != null) {
                gameDao.updateChunk(
                    chunk.copy(
                        downloadedBytes = destFile.length(),
                        isCompleted = true
                    )
                )
            }

            Result.success(
                workDataOf(
                    KEY_DOWNLOADED_PATH to destFile.absolutePath,
                    KEY_GAME_ID to gameId
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("GameDownloadWorker", "Download failed for game $gameId chunk $chunkId", e)
            gameDao.updateStatus(gameId, InstallStatus.ERROR)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Download failed")))
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_GAME_ID = "game_id"
        const val KEY_CHUNK_ID = "chunk_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_TARGET_FILENAME = "target_filename"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_DOWNLOADED_PATH = "downloaded_path"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}

