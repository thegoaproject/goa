package com.goa.domain.usecase

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Unpacks the self-extracting PKZIP archives that 90s shareware ships inside its download, so the game files exist
 * without running a DOS installer:
 *  - split sets `NAME.1`, `NAME.2`, … that DEICE.EXE joins into one SFX (DOOM, Heretic and Hexen shareware)
 *  - single SFX executables, e.g. One Must Fall 2.1's `OMF/OMF21.EXE`
 *
 * Contents are written next to the archive. Consumed SFX executables are deleted so they are never picked as the game;
 * split parts are left alone (they don't look like programs). Apogee `.SHR` installers are a proprietary format and
 * are not handled here: those games run their own INSTALL.EXE on first launch.
 */
object EmbeddedArchiveExpander {

    private val LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val END_OF_CENTRAL_DIR = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private const val STUB_SEARCH_BYTES = 256 * 1024
    private const val EOCD_SEARCH_BYTES = 65_557  // 22-byte record + max 65,535-byte comment
    private val SPLIT_PART = Regex("""^(.+)\.(\d)$""")

    /** Expands every embedded archive under [dir]; returns how many archives were unpacked. */
    fun expandAll(dir: File): Int {
        var expanded = 0
        for (parts in findSplitSets(dir)) {
            if (extract(parts, parts.first().parentFile!!)) expanded++
        }
        dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) && isSelfExtractingZip(listOf(it)) }
            .toList()
            .forEach { sfx ->
                if (extract(listOf(sfx), sfx.parentFile!!)) {
                    sfx.delete()
                    expanded++
                }
            }
        return expanded
    }

    /** Groups `NAME.1..N` siblings whose concatenation is a self-extracting zip, in part order. */
    internal fun findSplitSets(dir: File): List<List<File>> =
        dir.walkTopDown()
            .filter { it.isFile && SPLIT_PART.matches(it.name) }
            .groupBy { File(it.parentFile, SPLIT_PART.find(it.name)!!.groupValues[1].lowercase(Locale.ROOT)) }
            .values
            .map { group -> group.sortedBy { SPLIT_PART.find(it.name)!!.groupValues[2].toInt() } }
            .filter { parts ->
                val numbers = parts.map { SPLIT_PART.find(it.name)!!.groupValues[2].toInt() }
                parts.size >= 2 && numbers == (1..parts.size).toList() && isSelfExtractingZip(parts)
            }

    /**
     * True when the concatenated [parts] start with a DOS stub or zip header, contain a zip entry within the first
     * 256 KB, and end with a zip end-of-central-directory record. Plain game executables and data files fail this.
     */
    internal fun isSelfExtractingZip(parts: List<File>): Boolean {
        if (parts.first().length() < 4) return false
        val head = readHead(parts, STUB_SEARCH_BYTES)
        val startsLikeProgram = (head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()) || startsWith(head, LOCAL_HEADER)
        if (!startsLikeProgram || indexOf(head, LOCAL_HEADER) < 0) return false
        return indexOf(readTail(parts.last(), EOCD_SEARCH_BYTES), END_OF_CENTRAL_DIR) >= 0
    }

    private fun extract(parts: List<File>, destination: File): Boolean {
        val offset = indexOf(readHead(parts, STUB_SEARCH_BYTES), LOCAL_HEADER)
        if (offset < 0) return false
        val root = destination.canonicalFile
        var entries = 0
        return runCatching {
            openJoined(parts).use { joined ->
                joined.skipFully(offset.toLong())
                ZipInputStream(joined).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(root, entry.name.replace('\\', '/')).canonicalFile
                        // Zip-slip guard: never write outside the archive's own directory
                        if (!target.path.startsWith(root.path + File.separator)) continue
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            if (entry.time > 0) target.setLastModified(entry.time)
                            entries++
                        }
                    }
                }
            }
            entries > 0
        }.getOrDefault(false)
    }

    private fun openJoined(parts: List<File>): InputStream =
        SequenceInputStream(Collections.enumeration(parts.map { it.inputStream().buffered() }))

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    // Read across part boundaries: a split can fall inside the DOS stub, before the first zip entry
    private fun readHead(parts: List<File>, max: Int): ByteArray =
        openJoined(parts).use { it.readNBytes(minOf(max.toLong(), parts.sumOf { part -> part.length() }).toInt()) }

    private fun readTail(file: File, max: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            val length = minOf(max.toLong(), raf.length()).toInt()
            raf.seek(raf.length() - length)
            ByteArray(length).also { raf.readFully(it) }
        }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
