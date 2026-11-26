package com.goa.domain.scummvm

import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A GOG install that is really a ScummVM game: [gameId] is ScummVM's engine id (e.g. "sky"), [target] its config section. */
data class ScummVmGame(
    val gameId: String,
    val target: String,
    val language: String?
)

/**
 * Recognises GOG installs that ship Windows ScummVM instead of a DOS executable, so GOA can run the game data
 * with its bundled ScummVM core. GOG describes these precisely: `goggame-*.info` names the launch task
 * (`ScummVM\scummvm.exe -c "..\beneath.ini" beneath`) and that ini holds `gameid=`.
 */
object ScummVmGameDetector {
    fun detect(installDir: File): ScummVmGame? {
        if (!installDir.isDirectory) return null
        return detectFromGogInfo(installDir) ?: detectFromBundledIni(installDir) ?: detectFromDataFiles(installDir)
    }

    /**
     * Freeware releases ship bare ScummVM data with no launcher or metadata, so recognise them by their data files.
     * Every file in an entry must be present (any one of the alternatives for a name written as "a|b").
     */
    private val DATA_SIGNATURES: List<Pair<String, List<String>>> = listOf(
        // LucasArts SCUMM games (DOS releases from the player's own collection): ScummVM runs them better than DOSBox
        // (saves, touch pointer, CD audio). Most specific first: Day of the Tentacle ships Maniac Mansion inside it, and
        // Monkey Island and Loom CD share 000.LFL + DISK01.LEC (only Monkey Island has a second disk file).
        // Signatures were taken from the collection's archives (3,407 games) and are unique to each game there.
        "ft" to listOf("ft.la0"),                              // Full Throttle
        "dig" to listOf("dig.la0"),                            // The Dig
        "atlantis" to listOf("atlantis.000|atlantis.001"),     // Indiana Jones and the Fate of Atlantis (floppy and CD)
        "tentacle" to listOf("tentacle.000|tentacle.001"),     // Day of the Tentacle
        "samnmax" to listOf("samnmax.000|samnmax.001|samnmax.sm0"), // Sam & Max Hit the Road
        "monkey2" to listOf("monkey2.000|monkey2.001"),        // Monkey Island 2: LeChuck's Revenge
        "monkey" to listOf("monkey.000|disk02.lec", "000.lfl|monkey.000"), // The Secret of Monkey Island (EGA, VGA, CD)
        "loom" to listOf("000.lfl", "disk01.lec"),             // Loom CD (after Monkey Island: same files minus disk 2)
        "indy3" to listOf("00.lfl", "indy256.exe|indy.exe|75.lfl"), // Indiana Jones and the Last Crusade
        "loom" to listOf("00.lfl", "loom.exe|65.lfl"),         // Loom (EGA floppy)
        "zak" to listOf("00.lfl", "zakexe.exe|zak.exe|zak.bat"), // Zak McKracken and the Alien Mindbenders
        "maniac" to listOf("00.lfl", "maniac.exe"),            // Maniac Mansion
        "sky" to listOf("sky.dnr", "sky.dsk"),                 // Beneath a Steel Sky
        "queen" to listOf("queen.1|queen.1c"),                 // Flight of the Amazon Queen
        "lure" to listOf("disk1.vga", "disk4.vga")             // Lure of the Temptress
    )

    private fun detectFromDataFiles(installDir: File): ScummVmGame? {
        val names = installDir.walkTopDown().maxDepth(3).filter { it.isFile }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        for ((gameId, required) in DATA_SIGNATURES) {
            if (required.all { req -> req.split('|').any { it in names } }) return ScummVmGame(gameId, gameId, null)
        }
        return null
    }

    private fun detectFromGogInfo(installDir: File): ScummVmGame? {
        val infoFile = installDir.listFiles { f -> f.isFile && f.name.startsWith("goggame-") && f.name.endsWith(".info") }
            ?.minByOrNull { it.name } ?: return null
        val info = runCatching { JSONObject(infoFile.readText()) }.getOrNull() ?: return null
        val language = info.optString("language").takeIf { it.isNotBlank() }
        val tasks = info.optJSONArray("playTasks") ?: return null

        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            if (!task.optString("path").endsWith("scummvm.exe", ignoreCase = true)) continue
            val args = tokenize(task.optString("arguments"))
            val iniArg = args.indexOf("-c").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            val target = args.lastOrNull { !it.startsWith("-") && it != iniArg }
            val iniFile = iniArg?.let { findIni(installDir, it) }
            val sections = iniFile?.let { parseIni(it.readText()) }.orEmpty()
            val section = target?.let { sections[it.lowercase(Locale.ROOT)] }
            val gameId = section?.get("gameid") ?: target ?: continue
            return ScummVmGame(gameId, target ?: gameId, language)
        }
        return null
    }

    /** No GOG task file: fall back to any ini beside a bundled ScummVM that names a gameid. */
    private fun detectFromBundledIni(installDir: File): ScummVmGame? {
        val hasScummVm = installDir.walkTopDown().maxDepth(2).any { it.isFile && it.name.equals("scummvm.exe", ignoreCase = true) }
        if (!hasScummVm) return null
        val candidates = installDir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.equals("ini", ignoreCase = true) }
        for (ini in candidates) {
            val sections = runCatching { parseIni(ini.readText()) }.getOrNull() ?: continue
            val (target, section) = sections.entries.firstOrNull { it.value.containsKey("gameid") } ?: continue
            return ScummVmGame(section.getValue("gameid"), target, section["language"])
        }
        return null
    }

    /** GOG writes Windows-relative ini paths ("..\beneath.ini"); the installer may have moved the file to __support/app. */
    private fun findIni(installDir: File, windowsPath: String): File? {
        val name = windowsPath.substringAfterLast('\\').substringAfterLast('/')
        val relative = File(installDir, windowsPath.replace('\\', '/')).let { runCatching { it.canonicalFile }.getOrNull() }
        if (relative != null && relative.isFile) return relative
        return installDir.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    /** Splits a command line honouring double quotes. */
    internal fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var has = false
        for (c in line) {
            when {
                c == '"' -> { quoted = !quoted; has = true }
                c.isWhitespace() && !quoted -> if (has) { out += cur.toString(); cur.clear(); has = false }
                else -> { cur.append(c); has = true }
            }
        }
        if (has) out += cur.toString()
        return out
    }

    /** Minimal ini reader: lower-cased section names, trimmed keys/values, `;`/`#` comments ignored. */
    internal fun parseIni(text: String): Map<String, Map<String, String>> {
        val sections = LinkedHashMap<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = sections.getOrPut(line.substring(1, line.length - 1).trim().lowercase(Locale.ROOT)) { mutableMapOf() }
            } else {
                val eq = line.indexOf('=')
                if (eq > 0) current?.put(line.substring(0, eq).trim().lowercase(Locale.ROOT), line.substring(eq + 1).trim())
            }
        }
        return sections
    }
}
