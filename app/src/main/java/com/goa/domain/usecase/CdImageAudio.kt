package com.goa.domain.usecase

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * CD images whose music tracks DOSBox Pure can't play. Its CD code reads audio tracks only from 16-bit PCM WAV or Ogg
 * Vorbis (dosbox-pure src/dos/cdrom_image.cpp), and one unreadable track fails the whole cue sheet, so the CD never
 * mounts: GOG's Tomb Raider (GAME.DAT, a cue sheet with MP3 music tracks) quit to DOS at once with no CD.
 *
 * Before each launch, the MP3 tracks of every mounted cue sheet are decoded once to WAV files beside it (Android's own
 * decoder), and GOA_CD.CUE, a copy of the cue pointing at them, is written next to the original. The launch's copy of
 * the conf mounts GOA_CD.CUE instead; GOG's files and the install's dosbox.conf are left as they are. A track that
 * can't be decoded (no space, a broken file) is left out, so the game still gets its CD, without that piece of music.
 */
object CdImageAudio {
    private const val TAG = "CdImageAudio"
    const val CUE_NAME = "GOA_CD.CUE"

    /** Audio track types DOSBox Pure reads itself; anything else on a FILE line is decoded to WAV. */
    private val PLAYABLE = setOf("BINARY", "MOTOROLA", "WAVE")
    private val DECODABLE_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "opus")

    private val IMGMOUNT = Regex("""(?im)^(\s*imgmount\s+[a-z]\s+)(?:"([^"]+)"|(\S+))(.*)$""")
    private val FILE_LINE = Regex("""(?i)^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+(\S+)\s*$""")

    data class CueFile(val name: String, val type: String)

    /** The FILE entries of a cue sheet, or null when [text] isn't one. */
    fun parseCue(text: String): List<CueFile>? {
        val files = text.lines().mapNotNull { line ->
            FILE_LINE.matchEntire(line)?.let { m -> CueFile(m.groupValues[1].ifEmpty { m.groupValues[2] }, m.groupValues[3].uppercase(Locale.ROOT)) }
        }
        return files.takeIf { it.isNotEmpty() && Regex("""(?im)^\s*TRACK\s+\d+""").containsMatchIn(text) }
    }

    /** True for a FILE entry DOSBox Pure can't play and GOA decodes (an MP3 declared as MP3, or with an .mp3 name). */
    fun needsDecoding(file: CueFile): Boolean =
        file.type !in PLAYABLE || file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in DECODABLE_EXTENSIONS &&
            file.type != "BINARY"

    /** True for a file DOSBox Pure reads itself: PCM WAV ("RIFF....WAVE") or Ogg ("OggS"). */
    fun isPlayableAudio(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        val head = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 4) return false
        val text = String(head, Charsets.ISO_8859_1)
        return text.startsWith("OggS") || (n >= 12 && text.startsWith("RIFF") && text.substring(8, 12) == "WAVE")
    }

    /** "02.mp3" -> "02_GOA.WAV": an 8.3-safe name beside the cue sheet (the CD is read through the DOS drive). */
    fun wavNameFor(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "").take(4).ifEmpty { "TRK" }
        return "${base}_GOA.WAV"
    }

    /**
     * The cue sheet with each decoded track pointing at its WAV ([decoded]: original name -> WAV name) and every track
     * that needs decoding but has no WAV left out (its FILE line and the TRACK/INDEX lines under it).
     */
    fun rewriteCue(text: String, decoded: Map<String, String>, needsWav: (CueFile) -> Boolean = ::needsDecoding): String {
        val out = StringBuilder()
        var skipping = false
        for (line in text.lines()) {
            val m = FILE_LINE.matchEntire(line)
            if (m != null) {
                val file = CueFile(m.groupValues[1].ifEmpty { m.groupValues[2] }, m.groupValues[3].uppercase(Locale.ROOT))
                skipping = false
                if (needsWav(file)) {
                    val wav = decoded[file.name]
                    if (wav == null) { skipping = true; continue }
                    out.append("FILE \"").append(wav).append("\" WAVE\r\n")
                    continue
                }
            } else if (skipping) {
                continue
            }
            if (line.isNotBlank()) out.append(line.trimEnd()).append("\r\n")
        }
        return out.toString()
    }

    /** The conf with each imgmount of a cue sheet that has a GOA_CD.CUE beside it mounting that copy instead. */
    fun rewriteMounts(conf: String, confDir: File): String = IMGMOUNT.replace(conf) { m ->
        val path = m.groupValues[2].ifEmpty { m.groupValues[3] }
        val image = resolve(confDir, path) ?: return@replace m.value
        val copy = File(image.parentFile, CUE_NAME)
        if (!copy.isFile || image.name.equals(CUE_NAME, ignoreCase = true)) return@replace m.value
        val sep = if (path.contains('\\')) '\\' else '/'
        val dir = path.substringBeforeLast(sep, "")
        val newPath = if (dir.isEmpty()) CUE_NAME else "$dir$sep$CUE_NAME"
        "${m.groupValues[1]}\"$newPath\"${m.groupValues[4]}"
    }

    /** The image files the conf's [autoexec] mounts with imgmount, resolved on disk. */
    fun mountedImages(conf: String, confDir: File): List<File> = IMGMOUNT.findAll(conf)
        .mapNotNull { resolve(confDir, it.groupValues[2].ifEmpty { it.groupValues[3] }) }.toList()

    private fun resolve(confDir: File, path: String): File? {
        val rel = path.replace('\\', '/')
        val direct = File(confDir, rel)
        if (direct.isFile) return direct
        // DOS is case-insensitive: "..\game.dat" on GOG's conf names GAME.DAT
        var dir: File = confDir
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            dir = when (part) {
                "." -> dir
                ".." -> dir.parentFile ?: return null
                else -> dir.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
        }
        return dir.takeIf { it.isFile }
    }

    /**
     * Decodes the music tracks of every cue sheet [conf] mounts and writes GOA_CD.CUE beside each one that needed it.
     * Runs before each launch; tracks already decoded are kept. Returns true when a GOA_CD.CUE is in place.
     */
    fun prepare(conf: String, confDir: File, decode: (File, File) -> Boolean = ::decodeToWav): Boolean {
        var prepared = false
        for (image in mountedImages(conf, confDir)) {
            if (image.length() > 64 * 1024) continue // a cue sheet is a few hundred bytes; skip ISO/BIN images
            val text = runCatching { image.readText() }.getOrNull() ?: continue
            val files = parseCue(text) ?: continue
            // By content too: DOSBox Pure plays any WAV or Ogg file whatever the cue calls it (Ogg tracks marked MP3)
            val toDecode = files.filter { needsDecoding(it) && !isPlayableAudio(resolve(image.parentFile, it.name)) }
            if (toDecode.isEmpty()) continue
            val decoded = mutableMapOf<String, String>()
            for (file in toDecode) {
                val src = resolve(image.parentFile, file.name) ?: continue
                val wav = File(image.parentFile, wavNameFor(file.name))
                val fresh = wav.isFile && wav.length() > 44 && wav.lastModified() >= src.lastModified()
                if (fresh || decode(src, wav)) decoded[file.name] = wav.name
                else wav.delete()
            }
            val cue = File(image.parentFile, CUE_NAME)
            val newText = rewriteCue(text, decoded) { f -> toDecode.any { it.name == f.name } }
            if (!cue.isFile || cue.readText() != newText) cue.writeText(newText)
            Log.i(TAG, "${image.name}: ${decoded.size} of ${toDecode.size} music tracks playable as WAV, mounted via $CUE_NAME")
            prepared = true
        }
        return prepared
    }

    /** Decodes [src] (any format Android can decode) to a 16-bit PCM WAV at [dst]; false when it can't. */
    fun decodeToWav(src: File, dst: File): Boolean {
        val tmp = File(dst.parentFile, dst.name + ".part")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var ok = false
        try {
            ok = decodeInto(src, tmp, extractor) { codec = it } && tmp.renameTo(dst)
        } catch (e: Exception) {
            Log.w(TAG, "could not decode ${src.name}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            if (!ok) tmp.delete()
        }
        return ok
    }

    private fun decodeInto(src: File, out: File, extractor: MediaExtractor, onCodec: (MediaCodec) -> Unit): Boolean {
        extractor.setDataSource(src.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // Leave room: a WAV is ~10 MB a minute, and the device must not fill up
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val estimate = durationUs / 1_000_000L * rate * channels * 2
        if (out.parentFile!!.usableSpace < estimate + 64L * 1024 * 1024) {
            Log.w(TAG, "not enough space to decode ${src.name}")
            return false
        }

        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        onCodec(decoder)
        decoder.configure(format, null, null, 0)
        decoder.start()
        var dataBytes = 0L
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44)) // the header goes in once the length is known
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIndex)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        raf.write(bytes)
                        dataBytes += info.size
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (channels !in 1..2 || dataBytes == 0L) return false
            raf.seek(0)
            raf.write(wavHeader(dataBytes, rate, channels))
        }
        return true
    }

    /** A 44-byte RIFF header for [dataBytes] of 16-bit little-endian PCM. */
    fun wavHeader(dataBytes: Long, rate: Int, channels: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}
