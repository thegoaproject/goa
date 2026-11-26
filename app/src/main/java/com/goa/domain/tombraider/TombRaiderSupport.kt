package com.goa.domain.tombraider

import android.content.Context
import android.util.Log
import com.goa.engine.input.GamepadButton
import java.io.File

/**
 * Tomb Raider runs natively on OpenLara (an open-source rebuild of the Tomb Raider engine, drawn with the phone's GPU)
 * instead of in DOSBox, where an emulated Pentium drawing every pixel in software manages 10-40 fps.
 *
 * OpenLara reads the game's own files: the levels (`.PHD` files in `DATA`) and videos (`.RPL` files in `FMV`) from the
 * CD's data track, and the CD's music as `audio/1/track_NN.wav`. GOG ships the CD as an image (`GAME.GOG`) and the music
 * as `NN.mp3` beside it, so on the first launch GOA copies the data track's DATA and FMV folders out once and the music,
 * as WAV, under OpenLara's names, into `GOA_OPENLARA` inside the install. The install's own files are never changed.
 */
object TombRaiderSupport {
    private const val TAG = "TombRaiderSupport"
    const val CORE_LIBRARY = "libopenlara_libretro.so"

    /** Folder (inside the install) with the files OpenLara reads. */
    private const val OUT_DIR = "GOA_OPENLARA"
    // Bump when the extracted layout changes, so old copies are redone
    private const val LAYOUT_VERSION = "2"  // 2: music as WAV, never MP3
    private const val MARKER = ".goa_openlara"

    /** The core is an optional build input (third_party/openlara, pinned in scripts/cores/cores.lock). */
    fun isAvailable(): Boolean = com.goa.BuildConfig.HAS_OPENLARA

    /** A Tomb Raider (1996, DOS) install: its executable, and its levels in a CD image or already on disk. */
    fun isTombRaider(installDir: File): Boolean = findExe(installDir) != null && findSource(installDir) != null

    private fun findExe(installDir: File): File? = installDir.walkTopDown().maxDepth(2)
        .firstOrNull { it.isFile && it.name.equals("tomb.exe", ignoreCase = true) }

    private sealed interface Source {
        data class Image(val file: File) : Source
        data class Folder(val dataDir: File) : Source
    }

    private fun findSource(installDir: File): Source? {
        // Levels already on disk (a release that copies the CD to the hard disk)
        // (GOA's own copy in OUT_DIR is not a source: re-laying it out deletes it first)
        installDir.walkTopDown().maxDepth(2).onEnter { !it.name.equals(OUT_DIR, ignoreCase = true) }
            .firstOrNull { it.isDirectory && it.name.equals("DATA", ignoreCase = true) && hasLevel(it) }
            ?.let { return Source.Folder(it) }
        // A CD image: the big files beside the game (GOG's GAME.GOG is a raw .bin)
        val candidates = installDir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 32L * 1024 * 1024 && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedByDescending { it.length() }
        for (file in candidates) {
            val image = runCatching { CdImage.open(file) }.getOrNull() ?: continue
            image.use { if (it.exists("DATA/GYM.PHD") && it.exists("DATA/TITLE.PHD")) return Source.Image(file) }
        }
        return null
    }

    private fun hasLevel(dataDir: File): Boolean =
        dataDir.listFiles().orEmpty().any { it.name.equals("TITLE.PHD", ignoreCase = true) }

    data class Launch(val contentFile: File, val systemDir: File, val saveDir: File)

    /**
     * Lays out OpenLara's files (once; later launches find them ready) and returns what to start it with, or null if
     * the install has no levels after all. The first launch copies the CD's DATA and FMV folders and decodes the music: up to half a minute.
     */
    fun prepareLaunch(context: Context, gameKey: Long, installDir: File): Launch? {
        val source = findSource(installDir) ?: return null
        val out = File(installDir, OUT_DIR)
        val marker = File(out, MARKER)
        if (marker.takeIf { it.isFile }?.readText()?.trim() != LAYOUT_VERSION) {
            out.deleteRecursively()
            out.mkdirs()
            val started = System.currentTimeMillis()
            var bytes = 0L
            when (source) {
                is Source.Image -> CdImage.open(source.file)?.use { cd ->
                    bytes += cd.extractTree("DATA", File(out, "DATA"))
                    bytes += cd.extractTree("FMV", File(out, "FMV"))
                } ?: return null
                is Source.Folder -> {
                    source.dataDir.copyRecursively(File(out, "DATA"), overwrite = true)
                    source.dataDir.parentFile?.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("FMV", true) }
                        ?.copyRecursively(File(out, "FMV"), overwrite = true)
                }
            }
            bytes += copyMusic(installDir, File(out, "audio/1"))
            marker.writeText(LAYOUT_VERSION)
            Log.i(TAG, "Laid out OpenLara's files: ${bytes / (1024 * 1024)} MB in ${System.currentTimeMillis() - started} ms")
        }
        val title = File(out, "DATA").listFiles().orEmpty().firstOrNull { it.name.equals("TITLE.PHD", ignoreCase = true) }
            ?: return null
        val systemDir = File(context.filesDir, "openlara_system").apply { mkdirs() }
        val saveDir = File(context.filesDir, "openlara_saves/$gameKey").apply { mkdirs() }
        return Launch(title, systemDir, saveDir)
    }

    /**
     * The CD's music tracks under the names OpenLara looks for, always as 16-bit PCM WAV: GOG's `02.mp3` (CD track 2)
     * becomes `track_02.wav`; the DOS game asks for music by CD track number, and so does OpenLara for the PC version.
     *
     * Never MP3: OpenLara's MP3 decoder (sound.h, MP3::decode) writes whole 1152-sample frames past the end of its
     * output buffer and returns bytes where frames are expected, so MP3 music crackles and corrupts the heap (crashes
     * later, anywhere). Its WAV reader is plain and bounded. A WAV GOA already made for DOSBox's CD audio is copied;
     * otherwise the track is decoded with Android's own decoder.
     */
    private fun copyMusic(installDir: File, dest: File): Long {
        dest.mkdirs()
        var bytes = 0L
        val tracks = installDir.listFiles().orEmpty().mapNotNull { f ->
            MUSIC_FILE.matchEntire(f.name)?.let { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2].lowercase(), f) }
        }
        for ((track, group) in tracks.groupBy { it.first }) {
            val target = File(dest, "track_%02d.wav".format(track))
            val wav = group.firstOrNull { it.second == "wav" }?.third
            val ok = if (wav != null) {
                wav.copyTo(target, overwrite = true)
                true
            } else {
                group.any { (_, _, file) -> com.goa.domain.usecase.CdImageAudio.decodeToWav(file, target) }
            }
            if (ok) bytes += target.length() else Log.w(TAG, "Track $track: no playable copy, it stays silent")
        }
        return bytes
    }

    /** OpenLara's own button names for the touch pad's labels (its libretro input descriptors). */
    val PAD_LABELS: Map<GamepadButton, String> = mapOf(
        GamepadButton.BUTTON_L1 to "Look",
        GamepadButton.BUTTON_R1 to "Walk",
        GamepadButton.BUTTON_SELECT to "Items",
        GamepadButton.BUTTON_START to "Pause"
    )

    /**
     * The two face-button layouts, chosen per game in its Controls tab. Original is the PlayStation's, by position
     * (south Action, west Jump, north Draw weapon, east Roll); Modern jumps on A and acts on X, as GOA's shooters and
     * today's Tomb Raider games do. Everything else is the same in both.
     */
    enum class PadLayout(val id: String, val label: String) {
        ORIGINAL("original", "Original"),
        MODERN("modern", "Modern")
    }

    // OpenLara's RetroPad buttons and what it does with them (src/platform/libretro/main.cpp, input descriptors)
    private const val RP_B = 0       // Action (shoot / grab)
    private const val RP_Y = 1       // Jump
    private const val RP_A = 8       // Roll
    private const val RP_X = 9       // Draw weapon

    /** The RetroPad button each pad button presses in [layout]. */
    fun retroPadIds(layout: PadLayout): Map<GamepadButton, Int> = mapOf(
        GamepadButton.BUTTON_A to if (layout == PadLayout.MODERN) RP_Y else RP_B,
        GamepadButton.BUTTON_X to if (layout == PadLayout.MODERN) RP_B else RP_Y,
        GamepadButton.BUTTON_B to RP_A,
        GamepadButton.BUTTON_Y to RP_X,
        GamepadButton.BUTTON_SELECT to 2,
        GamepadButton.BUTTON_START to 3,
        GamepadButton.DPAD_UP to 4,
        GamepadButton.DPAD_DOWN to 5,
        GamepadButton.DPAD_LEFT to 6,
        GamepadButton.DPAD_RIGHT to 7,
        GamepadButton.BUTTON_L1 to 10,
        GamepadButton.BUTTON_R1 to 11,
        GamepadButton.BUTTON_L2 to 12,
        GamepadButton.BUTTON_R2 to 13,
        GamepadButton.BUTTON_L3 to 14,
        GamepadButton.BUTTON_R3 to 15
    )

    /** What each button does in [layout], for the Controls tab, in the order shown. */
    fun buttonActions(layout: PadLayout): List<Pair<String, String>> = listOf(
        "A" to if (layout == PadLayout.MODERN) "Jump" else "Action (shoot, grab, use)",
        "X" to if (layout == PadLayout.MODERN) "Action (shoot, grab, use)" else "Jump",
        "B" to "Roll",
        "Y" to "Draw / holster weapons",
        "L1" to "Look (hold)",
        "R1" to "Walk (hold)",
        "Left stick / D-pad" to "Move",
        "Select" to "Inventory",
        "Start" to "Pause menu"
    )

    private fun layoutPrefs(context: Context) = context.getSharedPreferences("goa_openlara", Context.MODE_PRIVATE)

    fun layout(context: Context, gameId: Long): PadLayout {
        val id = layoutPrefs(context).getString("layout_$gameId", null)
        return PadLayout.entries.firstOrNull { it.id == id } ?: PadLayout.ORIGINAL
    }

    fun setLayout(context: Context, gameId: Long, layout: PadLayout) {
        layoutPrefs(context).edit().putString("layout_$gameId", layout.id).apply()
    }

    private val IMAGE_EXTENSIONS = setOf("gog", "bin", "iso", "img")
    // GOG's 02.mp3, or the WAV GOA decoded for DOSBox's CD audio (02_GOA.WAV)
    private val MUSIC_FILE = Regex("""(\d{2})(?:_GOA)?\.(mp3|ogg|wav)""", RegexOption.IGNORE_CASE)
}
