package com.goa.domain.savestate

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** A save state on the phone: the file and what is known about it. */
data class LocalSaveState(val file: File, val meta: SaveStateMeta) {
    val bytes: Long get() = file.length()
    /** Why the file can't be loaded (damaged, not a save state), or null when its header is right. */
    val problem: DosBoxStateFile.Problem? get() = DosBoxStateFile.check(file)
}

/** A download that isn't a save state GOA can load: [problem] says why. Nothing was replaced. */
class NotASaveStateException(val problem: DosBoxStateFile.Problem) : IOException(problem.message)

/**
 * The save state files in one game's install folder (specs/009-drive-save-states/contracts/local-files.md):
 *
 * - `state_0.sav` + `state_0.json`: the save state DOSBox Pure loads, and its [SaveStateMeta]
 * - `state_0.prev.sav` + `state_0.prev.json`: the one an import or download replaced, restorable
 * - `state_0.tmp` + `state_0.tmp.json`: a download being moved into place
 *
 * Replacing is a chain of renames, and [recover] finishes or rolls back a chain a crash interrupted, so the folder
 * always holds a loadable `state_0.sav` (the old one or the new one), never a half-written one.
 */
class SaveStateStore(private val dir: File) {
    val stateFile = File(dir, "state_0.sav")
    private val metaFile = File(dir, "state_0.json")
    val previousFile = File(dir, "state_0.prev.sav")
    private val previousMetaFile = File(dir, "state_0.prev.json")
    /** Where a download lands before it is checked and moved into place. */
    val tempFile = File(dir, "state_0.tmp")
    private val tempMetaFile = File(dir, "state_0.tmp.json")
    private val swapFile = File(dir, "state_0.swap.sav")
    private val swapMetaFile = File(dir, "state_0.swap.json")

    /** Values for a save state that has no metadata yet (made before GOA recorded it). */
    data class Defaults(val gameKey: String, val device: String, val coreVersion: String)

    /**
     * The current save state, or null when there is none. One without `state_0.json` is given one now: saved at the
     * file's modification time, on this phone, with the current engine. Metadata whose checksum no longer matches the
     * file is stale: the checksum is recomputed and the save time kept.
     */
    fun current(defaults: Defaults): LocalSaveState? = read(stateFile, metaFile, defaults)

    /** The previous save state kept by the last import or download, or null. */
    fun previous(defaults: Defaults): LocalSaveState? = read(previousFile, previousMetaFile, defaults)

    /** Records the save state the player just made with Save Slot. */
    fun writeMetaAfterSave(defaults: Defaults, now: Long = System.currentTimeMillis()): SaveStateMeta? {
        if (!stateFile.isFile) return null
        val meta = SaveStateMeta(
            gameKey = defaults.gameKey, savedAt = now, device = defaults.device,
            coreVersion = defaults.coreVersion, sha256 = sha256(stateFile)
        ).stamped(stateFile)
        writeAtomically(metaFile, meta.encode())
        return meta
    }

    /**
     * Moves the checked download in [tempFile] into place as the current save state with [meta]. The current pair, if
     * any, becomes the previous pair (replacing an older previous pair), unless [keepCurrent] is false (the current one
     * is damaged: the previous pair is left alone and the damaged file dropped). Throws [IOException] and leaves the
     * current save state untouched when the download does not match [meta]'s checksum, and
     * [NotASaveStateException] when it isn't a save state DOSBox Pure can load.
     */
    fun replaceFromTemp(meta: SaveStateMeta, defaults: Defaults, keepCurrent: Boolean = true) {
        if (!tempFile.isFile || sha256(tempFile) != meta.sha256) {
            tempFile.delete()
            tempMetaFile.delete()
            throw IOException("The downloaded save state is damaged")
        }
        DosBoxStateFile.check(tempFile)?.let { problem ->
            tempFile.delete()
            tempMetaFile.delete()
            throw NotASaveStateException(problem)
        }
        // The pending metadata lets recover() finish the move after a crash
        writeAtomically(tempMetaFile, meta.encode())
        if (stateFile.isFile && !keepCurrent) {
            stateFile.delete()
            metaFile.delete()
        }
        if (stateFile.isFile) {
            current(defaults) // make sure the pair being kept has its metadata
            previousFile.delete()
            previousMetaFile.delete()
            rename(metaFile, previousMetaFile)
            rename(stateFile, previousFile)
        }
        rename(tempFile, stateFile)
        rename(tempMetaFile, metaFile)
        writeAtomically(metaFile, meta.stamped(stateFile).encode())
    }

    /** Swaps the current and previous save states, so restoring can itself be undone the same way. */
    fun restorePrevious(defaults: Defaults): Boolean {
        if (!previousFile.isFile) return false
        current(defaults)
        previous(defaults)
        if (stateFile.isFile) {
            rename(stateFile, swapFile)
            rename(metaFile, swapMetaFile)
        }
        rename(previousFile, stateFile)
        rename(previousMetaFile, metaFile)
        if (swapFile.isFile) {
            rename(swapFile, previousFile)
            rename(swapMetaFile, previousMetaFile)
        }
        return true
    }

    /**
     * Finishes or rolls back a replacement or restore that a crash interrupted, and deletes leftovers. Call before
     * reading the folder after GOA starts (a run or a game launch).
     */
    fun recover() {
        // A restore stopped half way: put the swapped-out pair wherever its place is free
        if (swapFile.isFile) {
            if (!stateFile.isFile) {
                rename(swapFile, stateFile); rename(swapMetaFile, metaFile)
            } else if (!previousFile.isFile) {
                rename(swapFile, previousFile); rename(swapMetaFile, previousMetaFile)
            }
        }
        val pending = if (tempMetaFile.isFile) SaveStateMeta.decode(tempMetaFile.readText()) else null
        when {
            // The old save state was moved aside but the download not yet moved in
            pending != null && !stateFile.isFile && tempFile.isFile && sha256(tempFile) == pending.sha256 -> {
                rename(tempFile, stateFile)
                rename(tempMetaFile, metaFile)
            }
            // The download is in place but its metadata was not yet
            pending != null && stateFile.isFile && !tempFile.isFile && sha256(stateFile) == pending.sha256 ->
                rename(tempMetaFile, metaFile)
            // Nothing in place at all (the download was damaged after the old one moved): bring the old one back
            !stateFile.isFile && previousFile.isFile && pending != null -> {
                rename(previousFile, stateFile)
                rename(previousMetaFile, metaFile)
            }
        }
        tempFile.delete()
        tempMetaFile.delete()
        // Save Slot writes here first and renames it over state_0.sav when complete: a leftover is a save cut short
        File(dir, "state_0.sav.part").delete()
    }

    private fun read(file: File, meta: File, defaults: Defaults): LocalSaveState? {
        if (!file.isFile) return null
        val stored = if (meta.isFile) SaveStateMeta.decode(meta.readText()) else null
        if (stored != null && stored.fileBytes == file.length() && stored.fileModified == file.lastModified()) {
            return LocalSaveState(file, stored)
        }
        val sha = sha256(file)
        val fresh = (stored?.copy(sha256 = sha) ?: SaveStateMeta(
            gameKey = defaults.gameKey, savedAt = file.lastModified(), device = defaults.device,
            coreVersion = defaults.coreVersion, sha256 = sha
        )).stamped(file)
        writeAtomically(meta, fresh.encode())
        return LocalSaveState(file, fresh)
    }

    private fun SaveStateMeta.stamped(file: File) = copy(fileBytes = file.length(), fileModified = file.lastModified())

    private fun rename(from: File, to: File) {
        if (!from.exists()) return
        if (to.exists() && !to.delete()) throw IOException("Could not replace ${to.name}")
        if (!from.renameTo(to)) throw IOException("Could not move ${from.name} to ${to.name}")
    }

    private fun writeAtomically(target: File, text: String) {
        val part = File(target.parentFile, target.name + ".part")
        part.writeText(text)
        rename(part, target)
    }

    companion object {
        /** Lower-case hex SHA-256 of [file], read in chunks (save states are about 12 MB). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
