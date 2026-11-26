package com.goa.domain.quake

import android.content.Context
import com.goa.domain.usecase.LaunchTarget
import java.io.File
import java.util.Locale

/**
 * Quake runs natively on the TyrQuake libretro core (Quake's GPL source, built for ARM) instead of in DOSBox, where a
 * phone emulating a 1996 Pentium drawing every pixel in software manages about 10 fps. TyrQuake reads the game's own
 * PAK files: `id1/pak0.pak` is Quake, a `hipnotic` or `rogue` PAK its mission packs.
 *
 * Its controls are Quake's own: TyrQuake turns each pad button into a Quake key (JOY_A, JOY_L2 ...) that config.cfg
 * binds to a command, and reads the analog sticks itself (left moves, right looks). GOA writes its shooter layout as
 * those binds in TyrQuake's own save folder before each launch, so the DOS version's config and the install itself
 * are never touched.
 *
 * GOA's TyrQuake build (scripts/cores/patches/tyrquake) levels the view once the right stick has rested 0.6 s after
 * looking up or down when Quake's lookspring is on, which GOA turns on once per save folder; the player can turn it
 * off under Options → Lookspring. It also reads the mouse beside the pad, which is how the phone's gyro aims (turn and
 * pitch); a tilt leaves the view where it aims.
 */
object QuakeSupport {
    const val CORE_LIBRARY = "libtyrquake_libretro.so"

    /** The core is an optional build input (third_party/tyrquake, pinned in scripts/cores/cores.lock). */
    fun isAvailable(): Boolean = com.goa.BuildConfig.HAS_TYRQUAKE

    /** Quake's main PAK in an install (`id1/pak0.pak`, any case), or null when this isn't Quake. */
    fun findMainPak(installDir: File): File? = findPak(installDir, "id1")

    /** A mission pack's PAK (`hipnotic` Scourge of Armagon, `rogue` Dissolution of Eternity), if installed. */
    fun findPak(installDir: File, gameDir: String): File? = installDir.walkTopDown().maxDepth(3)
        .filter { it.isFile && it.name.equals("pak0.pak", ignoreCase = true) && it.parentFile?.name.equals(gameDir, ignoreCase = true) }
        .minByOrNull { it.path.length }

    /**
     * The PAK a launch loads: Quake itself for Play, or a mission pack for the GOG menu entry that runs it. GOG's
     * launcher menu (dosbox_quake_single.conf) runs `quake.exe -game hipnotic` under its `:mp1` label and
     * `-game rogue` under `:mp2`; the `-game` folder holds the pack's PAK. Null when that folder has none.
     */
    fun pakFor(installDir: File, target: LaunchTarget): File? {
        val gameDir = (target as? LaunchTarget.MenuEntry)?.let { gameDirForLabel(installDir, it.label) }
        return if (gameDir != null) findPak(installDir, gameDir) else findMainPak(installDir)
    }

    /** The `-game` folder the batch block under `:label` passes to quake.exe, in any of the install's confs. */
    internal fun gameDirForLabel(installDir: File, label: String): String? {
        val block = Regex("""(?ims)^\s*:${Regex.escape(label)}\s*$(.*?)(?=^\s*:\w|\z)""")
        val gameArg = Regex("""(?i)quake\S*\s+.*?-game\s+"?([A-Za-z0-9_]+)""")
        return installDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("conf", ignoreCase = true) }
            .firstNotNullOfOrNull { conf ->
                block.find(runCatching { conf.readText() }.getOrDefault(""))?.groupValues?.get(1)
                    ?.let { gameArg.find(it)?.groupValues?.get(1) }
            }
    }

    data class Launch(val contentFile: File, val systemDir: File, val saveDir: File)

    /**
     * Saves and config per game (and per mission pack) outside the install; GOA's pad binds are written before each
     * launch.
     */
    fun prepareLaunch(context: Context, gameKey: Long, pak: File): Launch {
        val systemDir = File(context.filesDir, "tyrquake_system").apply { mkdirs() }
        val pack = pak.parentFile?.name?.lowercase(Locale.ROOT)?.takeIf { it != "id1" }
        val saveDir = File(context.filesDir, "tyrquake_saves/$gameKey" + (pack?.let { "-$it" } ?: "")).apply { mkdirs() }
        writePadBinds(saveDir, pak)
        return Launch(pak, systemDir, saveDir)
    }

    /**
     * The config.cfg TyrQuake reads and writes: in a folder of the save directory named after the PAK's folder
     * (libretro.c: save dir + basename of the content's directory, so `id1/config.cfg`, `hipnotic/config.cfg`).
     */
    internal fun configFile(saveDir: File, pak: File): File = File(File(saveDir, pak.parentFile?.name ?: "id1"), "config.cfg")

    /**
     * GOA's pad binds into the config TyrQuake reads before this launch (the player's other binds and settings kept),
     * look-spring switched on the first time, and native Quake's own autoexec.cfg.
     */
    internal fun writePadBinds(saveDir: File, pak: File) {
        val config = configFile(saveDir, pak)
        config.parentFile?.mkdirs()
        // An existing config.cfg stops TyrQuake setting its first-boot binds, so a new one starts with them
        var text = withPadBinds(if (config.isFile) config.readText() else FIRST_BOOT_CONFIG)
        // Quake writes its own lookspring "0" into config.cfg, so GOA's default is set once; the player's later choice
        // in Quake's Options menu is kept (Quake rewrites config.cfg on exit, so the marker is a file of its own)
        val lookspringMarker = File(saveDir, LOOKSPRING_MARKER)
        val setLookspring = !lookspringMarker.exists()
        if (setLookspring) text = withSetting(text, "lookspring", "1")
        config.writeText(text)
        if (setLookspring) lookspringMarker.writeText("")
        // TyrQuake searches its save folder before the game's folders, so this autoexec.cfg is the one it runs, not
        // the game folder's, which holds the DOS version's settings (lookspring 0, +mlook and DOS-only key names)
        File(config.parentFile, "autoexec.cfg").takeUnless { it.isFile }?.writeText(AUTOEXEC)
        // Versions before 2026-11-12 wrote the binds one folder up, where TyrQuake never looks
        File(saveDir, "config.cfg").delete()
    }

    private const val LOOKSPRING_MARKER = ".goa-lookspring-default"

    internal const val AUTOEXEC = "// GOA: native Quake runs this file instead of the game folder's autoexec.cfg, which holds " +
        "the DOS version's settings\n"

    /** [config] with the cvar [name] set to [value] (replacing its line, or added at the end). */
    internal fun withSetting(config: String, name: String, value: String): String {
        val line = Regex("""^\s*${Regex.escape(name)}\s.*$""", RegexOption.IGNORE_CASE)
        val lines = config.lines().filter { it.isNotBlank() }
        val set = "$name \"$value\""
        return (if (lines.any { line.matches(it) }) lines.map { if (line.matches(it)) set else it } else lines + set)
            .joinToString("\n", postfix = "\n")
    }

    /** What TyrQuake sets when it boots a game with no config.cfg yet (libretro.c, retro_load_game). */
    private val FIRST_BOOT_CONFIG = listOf(
        "gamma \"0.95\"",
        "bind \"'\" \"toggleconsole\"",
        "bind \"~\" \"toggleconsole\"",
        "bind \"`\" \"toggleconsole\"",
        "bind \"f\" \"+moveup\"",
        "bind \"c\" \"+movedown\"",
        "bind \"a\" \"+moveleft\"",
        "bind \"d\" \"+moveright\"",
        "bind \"w\" \"+forward\"",
        "bind \"s\" \"+back\"",
        "bind \"e\" \"impulse 10\"",
        "bind \"q\" \"impulse 12\""
    ).joinToString("\n")

    /**
     * GOA's shooter layout as Quake binds, by the Quake key each button reaches (GamepadInputManager sends A as JOY_A
     * and B as JOY_B for this core, which is what Quake's menus select and go back with): one command per button.
     * impulse 10 / 12 are next / previous weapon, impulse 6 the grenade launcher (QuakeC weapons.qc).
     */
    val PAD_BINDS: List<Pair<String, String>> = listOf(
        "JOY_A" to "+jump",
        "JOY_B" to "togglemenu",
        "JOY_X" to "impulse 10",
        "JOY_L" to "impulse 12",
        "JOY_R" to "impulse 6",
        "JOY_L2" to "+speed",
        "JOY_R2" to "+attack",
        "JOY_L3" to "+movedown",
        "JOY_R3" to "centerview",
        "JOY_START" to "togglemenu",
        "JOY_SELECT" to "+showscores",
        "JOY_UP" to "+forward",
        "JOY_DOWN" to "+back",
        "JOY_LEFT" to "impulse 12",
        "JOY_RIGHT" to "impulse 10"
    )

    /** What each button does on TyrQuake (GOA's shooter layout, as [PAD_BINDS] binds it), for the Controls tab. */
    val BUTTON_ACTIONS: List<Pair<String, String>> = listOf(
        "A" to "Jump",
        "B" to "Menu",
        "Y" to "Next weapon",
        "X" to "Not used (doors open as you walk up to them)",
        "R2" to "Fire",
        "L2" to "Run (hold)",
        "R1" to "Grenade launcher",
        "L1" to "Previous weapon",
        "D-pad left / right" to "Previous / next weapon",
        "D-pad up / down" to "Forward / back",
        "Left stick" to "Move",
        "Right stick" to "Look (the view levels out when you let go)",
        "L3" to "Swim down",
        "R3" to "Centre view",
        "Start" to "Menu",
        "Select" to "Scores"
    )

    /** [config] with every pad bind set to GOA's layout (other lines, the player's keys and settings, kept). */
    fun withPadBinds(config: String): String {
        val padKeys = PAD_BINDS.map { it.first.uppercase(Locale.ROOT) }.toSet() + "JOY_Y"
        val bindLine = Regex("""^\s*(?:un)?bind\s+"?([A-Za-z0-9_]+)"?(\s+.*)?$""", RegexOption.IGNORE_CASE)
        val kept = config.lines().filter { line ->
            val key = bindLine.matchEntire(line)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            line.isNotBlank() && (key == null || key !in padKeys)
        }
        // X (west) is Use in GOA's layout; Quake has no use key (doors open on touch), so it stays unbound
        return (kept + PAD_BINDS.map { (key, command) -> "bind \"$key\" \"$command\"" } + "unbind \"JOY_Y\"")
            .joinToString("\n", postfix = "\n")
    }
}
