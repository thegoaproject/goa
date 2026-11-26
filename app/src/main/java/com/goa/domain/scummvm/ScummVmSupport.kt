package com.goa.domain.scummvm

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/** Everything the app needs to start the bundled ScummVM libretro core for an installed game. */
data class ScummVmLaunch(
    val contentFile: File,   // the `.scummvm` file handed to the core as content
    val systemDir: File,     // holds scummvm/extra + scummvm/theme
    val saveDir: File
)

object ScummVmSupport {
    const val CORE_LIBRARY = "libscummvm_libretro.so"
    private const val SUPPORT_ASSET = "scummvm_support.zip"
    private const val SUPPORT_VERSION = 1

    /** The core and its support data are optional build inputs (scripts/cores/fetch-cores.sh, pinned in scripts/cores/cores.lock); both ship together. */
    fun isAvailable(context: Context): Boolean =
        runCatching { context.assets.list("")?.contains(SUPPORT_ASSET) == true }.getOrDefault(false)

    /**
     * Prepares a launch for a detected ScummVM game: unpacks engine data once, keeps saves per game outside the
     * install folder (so uninstalling or re-patching never touches them) and writes the `.scummvm` content file
     * that tells the core which game to start.
     */
    fun prepareLaunch(context: Context, installDir: File, gameKey: Long, game: ScummVmGame): ScummVmLaunch {
        val systemDir = File(context.filesDir, "scummvm_system")
        ensureSupportFiles(context, systemDir)

        val saveDir = File(context.filesDir, "scummvm_saves/$gameKey").apply { mkdirs() }
        val contentFile = File(installDir, "${game.gameId}.scummvm")
        if (!contentFile.exists() || contentFile.readText().trim() != game.gameId) {
            contentFile.writeText(game.gameId + "\n")
        }
        return ScummVmLaunch(contentFile, systemDir, saveDir)
    }

    private fun ensureSupportFiles(context: Context, systemDir: File) {
        val marker = File(systemDir, ".version")
        if (marker.exists() && marker.readText().trim() == SUPPORT_VERSION.toString()) return

        systemDir.deleteRecursively()
        systemDir.mkdirs()
        val rootPath = systemDir.canonicalPath + File.separator
        context.assets.open(SUPPORT_ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val out = File(systemDir, entry.name)
                    // Guard against zip-slip even though the archive is ours
                    if (!out.canonicalPath.startsWith(rootPath)) return@forEach
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        marker.writeText(SUPPORT_VERSION.toString())
    }
}
