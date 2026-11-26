package com.goa.engine.extractor

import uk.co.armedpineapple.innoextract.service.ExtractService
import java.io.File

interface InnoExtractListener {
    fun onProgress(currentBytes: Long, totalBytes: Long, currentFile: String)
    fun onMessage(logLine: String)
    fun onError(errorCode: Int, errorMessage: String)
    fun onComplete(extractedFileCount: Int)
}

object InnoExtractBridge {

    /**
     * Unpacks a GOG Inno Setup executable and all its sibling .bin parts into destinationDir.
     */
    fun extractArchive(
        installerPath: String,
        destinationDir: String,
        gogExtractOnly: Boolean = true,
        lowercase: Boolean = true,
        listener: InnoExtractListener
    ): Int {
        val file = File(installerPath)
        val outDir = File(destinationDir)
        if (!file.exists()) {
            listener.onError(1, "Installer file not found: $installerPath")
            return 1
        }

        var currentFile = ""
        var extractedCount = 0

        val service = ExtractService { line, _ ->
            val trimmed = line.trim()
            if (trimmed.startsWith(" - ") || trimmed.startsWith("Extracting")) {
                currentFile = trimmed.removePrefix(" - ").trim()
                extractedCount++
                listener.onProgress(extractedCount.toLong(), 0L, currentFile)
                listener.onMessage("Extracting: $currentFile")
            } else if (trimmed.isNotEmpty()) {
                listener.onMessage(trimmed)
            }
        }

        return try {
            val code = service.extract(file, outDir)
            if (code == 0) {
                listener.onComplete(extractedCount)
            } else {
                listener.onError(code, "InnoExtract failed with code $code")
            }
            code
        } catch (e: Exception) {
            listener.onError(-1, e.message ?: "Unknown extraction error")
            -1
        }
    }

    fun cancelExtraction(): Boolean {
        return true
    }
}
