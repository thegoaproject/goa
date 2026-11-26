package com.goa.data.storage

import android.content.Context
import java.io.File

open class AppStorageManager(private val context: Context? = null) {

    open val baseDir: File
        get() = context?.getExternalFilesDir(null) ?: context?.filesDir ?: File("/tmp/goa")

    open fun getDownloadDir(gameId: Long): File {
        val dir = File(baseDir, "downloads/$gameId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    open fun getGameInstallDir(gameId: Long): File {
        val dir = File(baseDir, "games/$gameId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    open fun getExtrasDir(gameId: Long): File {
        val dir = File(baseDir, "extras/$gameId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    open fun getSavesDir(gameId: Long): File {
        val dir = File(baseDir, "saves/$gameId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    open fun cleanDownloads(gameId: Long): Boolean {
        val dir = File(baseDir, "downloads/$gameId")
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun deleteGameFiles(gameId: Long): Boolean {
        val installDir = File(baseDir, "games/$gameId")
        val downloadsDir = File(baseDir, "downloads/$gameId")
        var success = true
        if (installDir.exists()) {
            success = installDir.deleteRecursively() && success
        }
        if (downloadsDir.exists()) {
            success = downloadsDir.deleteRecursively() && success
        }
        return success
    }

    /** Temporary installer and download archives, one folder per game id. */
    fun downloadsDir(): File = File(baseDir, "downloads")

    /** Bytes a folder takes, counting everything below it (symbolic links are not followed); 0 when it doesn't exist. */
    fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) || it == dir }
            .forEach { if (it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath())) total += it.length() }
        return total
    }

    data class CleanResult(val freedBytes: Long, val keptAny: Boolean)

    /**
     * Deletes the temporary archives of every game except [activeGameIds] (installs in progress still need theirs).
     * Returns what was freed, and whether anything was kept for an active install.
     */
    fun cleanDownloadsExcept(activeGameIds: Set<Long>): CleanResult {
        val children = downloadsDir().listFiles() ?: return CleanResult(0L, false)
        var freed = 0L
        var kept = false
        for (child in children) {
            if (child.name.toLongOrNull() in activeGameIds) {
                if (folderSize(child) > 0) kept = true
                continue
            }
            val size = folderSize(child)
            if (child.deleteRecursively()) freed += size
        }
        return CleanResult(freed, kept)
    }

    fun getAvailableDiskSpace(): Long {
        return baseDir.usableSpace
    }

    fun hasSufficientSpace(requiredBytes: Long, safetyBufferBytes: Long = 100 * 1024 * 1024L): Boolean {
        return getAvailableDiskSpace() > (requiredBytes + safetyBufferBytes)
    }
}

