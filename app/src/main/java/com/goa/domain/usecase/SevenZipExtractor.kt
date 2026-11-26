package com.goa.domain.usecase

import android.util.Log
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Unpacks .7z archives with the native 7-Zip engine. DOS collections compress their executables with 7-Zip's BCJ2
 * filter, which pure-Java readers reject, so the whole archive is handed to 7-Zip itself. Entries that would land
 * outside the destination are skipped, like the zip path.
 */
object SevenZipExtractor {

    private const val TAG = "SevenZipExtractor"
    private val MAGIC = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun isSevenZip(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return runCatching {
            file.inputStream().use { s -> ByteArray(MAGIC.size).also { s.read(it) }.contentEquals(MAGIC) }
        }.getOrDefault(false)
    }

    @Synchronized
    private fun ensureNativeLoaded() {
        if (SevenZip.isInitializedSuccessfully()) return
        System.loadLibrary("7-Zip-JBinding")
        SevenZip.initLoadedLibraries()
    }

    /** Returns the number of files written; throws if any file failed to unpack (corrupt, truncated or unsupported). */
    fun extract(archive: File, destinationDir: File, onProgress: (Int) -> Unit = {}): Int {
        ensureNativeLoaded()
        destinationDir.mkdirs()
        val root = destinationDir.canonicalPath + File.separator
        var written = 0
        val failures = mutableListOf<String>()

        RandomAccessFile(archive, "r").use { raf ->
            SevenZip.openInArchive(null, RandomAccessFileInStream(raf)).use { arc ->
                val count = arc.numberOfItems
                var total = 0L
                var current: FileOutputStream? = null
                var currentName = ""

                val callback = object : IArchiveExtractCallback {
                    override fun setTotal(total0: Long) { total = total0 }
                    override fun setCompleted(done: Long) { if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100)) }

                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        if (mode != ExtractAskMode.EXTRACT) return null
                        val name = (arc.getStringProperty(index, PropID.PATH) ?: return null).replace('\\', '/')
                        val target = File(destinationDir, name)
                        if (!target.canonicalPath.startsWith(root) && target.canonicalPath != destinationDir.canonicalPath) {
                            Log.w(TAG, "Skipping path-traversal entry: $name")
                            return null
                        }
                        if (arc.getProperty(index, PropID.IS_FOLDER) == true) {
                            target.mkdirs()
                            return null
                        }
                        target.parentFile?.mkdirs()
                        currentName = name
                        val out = FileOutputStream(target).also { current = it }
                        return ISequentialOutStream { data -> out.write(data); data.size }
                    }

                    override fun prepareOperation(mode: ExtractAskMode) {}

                    override fun setOperationResult(result: ExtractOperationResult) {
                        current?.close()
                        if (current != null) {
                            if (result == ExtractOperationResult.OK) written++ else failures += "$currentName: $result"
                        }
                        current = null
                    }
                }
                arc.extract(IntArray(count) { it }, false, callback)
            }
        }
        if (failures.isNotEmpty()) throw SevenZipException("${failures.size} file(s) failed to unpack, first: ${failures.first()}")
        return written
    }
}
