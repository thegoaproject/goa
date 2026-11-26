package com.goa.domain.tombraider

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads files out of a CD-ROM's data track (ISO 9660), as GOG ships it: a cooked 2048-byte-sector `.iso` or a raw
 * 2352-byte-sector `.bin`/`.gog` (Mode 1, or Mode 2 Form 1). Only plain ISO 9660 names are read (no Joliet or Rock
 * Ridge): DOS games' CDs need nothing else.
 */
class CdImage private constructor(
    private val raf: RandomAccessFile,
    private val sectorSize: Int,
    private val dataOffset: Int,
    private val root: Entry
) : Closeable {

    data class Entry(val name: String, val lba: Long, val size: Long, val isDirectory: Boolean)

    /** The entries of the directory at [path] ("" or "/" is the root, "DATA" a top-level folder), any case. */
    fun list(path: String): List<Entry>? {
        var dir = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            dir = readDirectory(dir).firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return readDirectory(dir)
    }

    fun exists(path: String): Boolean {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return list(parent)?.any { !it.isDirectory && it.name.equals(name, ignoreCase = true) } == true
    }

    /** Copies the file [entry] to [dest]. */
    fun extract(entry: Entry, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.outputStream().buffered(1 shl 16).use { out ->
            var remaining = entry.size
            var lba = entry.lba
            val sector = ByteArray(USER_DATA)
            while (remaining > 0) {
                readSector(lba++, sector)
                val n = minOf(remaining, USER_DATA.toLong()).toInt()
                out.write(sector, 0, n)
                remaining -= n
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Copies the folder at [path] (and everything under it) into [destDir]; returns the bytes written. */
    fun extractTree(path: String, destDir: File): Long {
        val entries = list(path) ?: return 0
        var total = 0L
        for (e in entries) {
            val target = File(destDir, e.name)
            if (e.isDirectory) {
                total += extractTree("$path/${e.name}", target)
            } else {
                extract(e, target)
                total += e.size
            }
        }
        return total
    }

    override fun close() = raf.close()

    private fun readSector(lba: Long, into: ByteArray) {
        raf.seek(lba * sectorSize + dataOffset)
        raf.readFully(into, 0, USER_DATA)
    }

    private fun readDirectory(dir: Entry): List<Entry> {
        val out = mutableListOf<Entry>()
        val sector = ByteArray(USER_DATA)
        val sectors = (dir.size + USER_DATA - 1) / USER_DATA
        for (i in 0 until sectors) {
            readSector(dir.lba + i, sector)
            var pos = 0
            while (pos < USER_DATA) {
                val len = sector[pos].toInt() and 0xFF
                if (len == 0) break  // the rest of this sector is padding; records never cross sectors
                if (pos + len > USER_DATA || len < 34) break
                val nameLen = sector[pos + 32].toInt() and 0xFF
                val rawName = String(sector, pos + 33, nameLen, Charsets.ISO_8859_1)
                if (!(nameLen == 1 && (rawName[0] == '\u0000' || rawName[0] == '\u0001'))) {
                    val flags = sector[pos + 25].toInt()
                    out += Entry(
                        name = cleanName(rawName),
                        lba = le32(sector, pos + 2),
                        size = le32(sector, pos + 10),
                        isDirectory = flags and 0x02 != 0
                    )
                }
                pos += len
            }
        }
        return out
    }

    companion object {
        private const val USER_DATA = 2048

        /** Opens [file] as a CD data track, or null when it isn't one. */
        fun open(file: File): CdImage? {
            if (!file.isFile || file.length() < 17L * 2048) return null
            val raf = RandomAccessFile(file, "r")
            // Cooked ISO; raw Mode 1 (12 sync + 4 header bytes); raw Mode 2 Form 1 (plus an 8-byte subheader)
            for ((size, offset) in listOf(2048 to 0, 2352 to 16, 2352 to 24)) {
                val pvd = ByteArray(USER_DATA)
                val at = 16L * size + offset
                if (at + USER_DATA > raf.length()) continue
                raf.seek(at)
                raf.readFully(pvd)
                if (pvd[0].toInt() == 1 && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") {
                    val rootRecord = 156  // the root directory's record inside the primary volume descriptor
                    val root = Entry("", le32(pvd, rootRecord + 2), le32(pvd, rootRecord + 10), true)
                    return CdImage(raf, size, offset, root)
                }
            }
            raf.close()
            return null
        }

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        /** "LEVEL1.PHD;1" -> "LEVEL1.PHD", "README.;1" -> "README". */
        internal fun cleanName(raw: String): String = raw.substringBefore(';').trimEnd('.')
    }
}
