package com.goa.domain.savestate

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Whether a file is a DOSBox Pure save state the game can load, checked without the emulator, so a bad file is turned
 * away with a message instead of crashing the game (DOSBox Pure applies a state as it reads it and crashes a little
 * later on one cut short: verified with 1.0-preview7 on Android and Linux).
 *
 * DOSBox Pure's own format (dbp_serialize.cpp, DBPSerialize_All): the magic number 0xD05B5747 little-endian ("GW[" 0xD0),
 * a format version byte, a byte that is 0 when DOS and the game were running (the core refuses any other), then the
 * machine's parts, each followed by its own end offset as a size_t. So a complete state ends with the number of bytes
 * before that last offset, optionally followed by zeros. Only 64-bit builds' states are accepted (GOA's, desktop
 * RetroArch): a 4-byte offset can't be told apart from the low half of an 8-byte one whose zero high bytes were cut.
 *
 * RetroArch saves the same state wrapped: in a RASTATE container (a "MEM " chunk), compressed in RZIP by default. Such a
 * file is kept as it is (so checksums still match Drive's) and unwrapped when loaded; see [unwrap].
 */
object DosBoxStateFile {
    enum class Problem(val message: String) {
        MISSING("There's no save state file."),
        EMPTY("The save state file is empty."),
        NOT_A_SAVE_STATE("The file isn't a DOSBox Pure save state, or it's damaged."),
        INCOMPLETE("The save state file is incomplete: it was cut short."),
        NEWER_VERSION("The save state was made by a newer DOSBox Pure than this version of GOA has."),
        NOT_IN_GAME("The save state was made before the game had started, so it can't be loaded.")
    }

    /** "GW[" 0xD0: DOSBox Pure's 0xD05B5747 in file order. */
    private val MAGIC = byteArrayOf(0x47, 0x57, 0x5B, 0xD0.toByte())
    private val RZIP = "#RZIPv".toByteArray()
    private val RASTATE = "RASTATE".toByteArray()

    /**
     * The newest state format GOA's DOSBox Pure reads (1.0-preview7, scripts/cores/cores.lock, writes version 8).
     * Raise it with the core: a newer core still reads older versions.
     */
    const val NEWEST_VERSION = 8

    /** Magic, version, running flag. */
    const val HEADER_BYTES = 6

    /** How far back from the end the last offset may sit behind zero padding. */
    private const val TAIL_BYTES = 64 * 1024

    /** Biggest state GOA unwraps in memory (DOSBox Pure's are about 1-20 MB). */
    private const val MAX_UNWRAPPED = 256L * 1024 * 1024

    /** Null when [file] looks like a complete, loadable save state. */
    fun check(file: File): Problem? {
        if (!file.isFile) return Problem.MISSING
        val size = file.length()
        if (size == 0L) return Problem.EMPTY
        return runCatching {
            RandomAccessFile(file, "r").use { f ->
                val start = ByteArray(minOf(size, 8L).toInt())
                f.readFully(start)
                if (start.startsWith(RZIP) || start.startsWith(RASTATE)) {
                    // RetroArch's: unwrap in memory, then check what's inside
                    val raw = unwrap(file.readBytes()) ?: return@use Problem.NOT_A_SAVE_STATE
                    return@use check(raw)
                }
                headerProblem(start)?.let { return@use it }
                val tailLength = minOf(size, TAIL_BYTES.toLong()).toInt()
                val tail = ByteArray(tailLength)
                f.seek(size - tailLength)
                f.readFully(tail)
                if (endsComplete(tail, size - tailLength)) null else Problem.INCOMPLETE
            }
        }.getOrElse { Problem.NOT_A_SAVE_STATE }
    }

    /** Null when [state] (a whole, unwrapped state) is complete and loadable. */
    fun check(state: ByteArray): Problem? {
        if (state.isEmpty()) return Problem.EMPTY
        if (state.startsWith(RZIP) || state.startsWith(RASTATE)) {
            return check(unwrap(state) ?: return Problem.NOT_A_SAVE_STATE)
        }
        headerProblem(state)?.let { return it }
        return if (endsComplete(state, 0)) null else Problem.INCOMPLETE
    }

    private fun headerProblem(header: ByteArray): Problem? {
        if (header.size < HEADER_BYTES) return Problem.NOT_A_SAVE_STATE
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return Problem.NOT_A_SAVE_STATE
        val version = header[4].toInt() and 0xFF
        if (version < 1) return Problem.NOT_A_SAVE_STATE
        if (version > NEWEST_VERSION) return Problem.NEWER_VERSION
        if (header[5].toInt() != 0) return Problem.NOT_IN_GAME
        return null
    }

    /**
     * Whether the state ends with its last part's end offset: [tail] is the file's last bytes, starting at [tailStart].
     * Zeros after it are padding (RetroArch and DOSBox Pure fill the buffer they were given).
     */
    private fun endsComplete(tail: ByteArray, tailStart: Long): Boolean {
        var end = tail.size
        while (end >= 8) {
            if (le64(tail, end - 8) == tailStart + end - 8) return true
            if (tail[end - 1].toInt() != 0) return false
            end--
        }
        return false
    }

    /**
     * DOSBox Pure's own state from a RetroArch one (RZIP-compressed and/or a RASTATE container), or the bytes as they
     * are when they aren't wrapped; null when a wrapper is damaged.
     */
    fun unwrap(bytes: ByteArray): ByteArray? = runCatching {
        var data = bytes
        if (data.startsWith(RZIP)) data = unRzip(data) ?: return null
        if (data.startsWith(RASTATE)) data = memChunk(data) ?: return null
        data
    }.getOrNull()

    /** RetroArch's RZIP: "#RZIPv" version "#", u32 chunk size, u64 total, then (u32 length, zlib data) chunks. */
    private fun unRzip(data: ByteArray): ByteArray? {
        if (data.size < 20 || data[7] != '#'.code.toByte()) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val total = b.getLong(12)
        if (total <= 0 || total > MAX_UNWRAPPED) return null
        val out = ByteArrayOutputStream(total.toInt())
        var p = 20
        val buffer = ByteArray(64 * 1024)
        while (p + 4 <= data.size && out.size() < total) {
            val n = b.getInt(p); p += 4
            if (n <= 0 || p + n > data.size) return null
            val inflater = Inflater()
            try {
                inflater.setInput(data, p, n)
                while (!inflater.finished()) {
                    val got = inflater.inflate(buffer)
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    out.write(buffer, 0, got)
                    if (out.size() > total) return null
                }
            } finally {
                inflater.end()
            }
            p += n
        }
        return if (out.size().toLong() == total) out.toByteArray() else null
    }

    /** RetroArch's RASTATE container: "RASTATE" version, then 8-byte aligned (tag, u32 size, data) chunks. */
    private fun memChunk(data: ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var p = 8
        while (p + 8 <= data.size) {
            val tag = String(data, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            if (size < 0 || p + 8 + size > data.size) return null
            if (tag == "MEM ") return data.copyOfRange(p + 8, p + 8 + size)
            if (tag == "END ") return null
            p = (p + 8 + size + 7) and 7.inv()
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun le64(a: ByteArray, at: Int) = ByteBuffer.wrap(a, at, 8).order(ByteOrder.LITTLE_ENDIAN).long

}
