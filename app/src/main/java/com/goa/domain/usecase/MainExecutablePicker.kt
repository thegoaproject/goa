package com.goa.domain.usecase

import java.util.Locale
import kotlin.math.ln

/**
 * Chooses which DOS program an install should launch when no dosbox.conf names one.
 *
 * Directory order is meaningless (OMF 2097 listed DOS4GW.EXE first, Tyrian NETMODEM.EXE), so candidates are ranked:
 * names matching the game's slug or initials win, utilities (help viewers, order forms, network launchers) lose, and
 * DOS extenders/runtimes are never picked because they only run another program.
 */
object MainExecutablePicker {

    /**
     * [batchCommands]: for a .bat, the programs it runs (first word of each command line, `call`/`lh` dropped), so a
     * batch file that never starts a game program (System Shock's SHOCKGUS.BAT only loads Gravis patches and says
     * "Type SShock") is not mistaken for the game's launcher. Null for programs and unread batch files.
     */
    data class Candidate(val relativePath: String, val sizeBytes: Long, val batchCommands: List<String>? = null) {
        val fileName: String get() = relativePath.substringAfterLast('/').substringAfterLast('\\')
        val stem: String get() = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        val depth: Int get() = relativePath.count { it == '/' || it == '\\' }
    }

    // DOS extenders and runtimes: launching one alone prints a usage error
    private val RUNTIMES = setOf(
        "dos4gw", "dos4g", "dos32a", "pmodew", "cwsdpmi", "rtm", "32rtm", "dpmi16bi", "dpmiload", "causeway", "wdosx"
    )

    // Installers, configurators and unpackers like id's DEICE (also filtered before ranking)
    private val INSTALLER_PREFIXES = listOf("setup", "install", "setsound", "unins", "gogdos", "ipx", "deice")
    private val INSTALLER_SUBSTRINGS = listOf("config", "dosbox")

    // Bundled utilities: runnable, but rarely the game
    private val UTILITY_STEMS = setOf(
        "help", "helpme", "order", "register", "readme", "catalog", "vendor", "info", "uninstal", "unzip", "pkunzip"
    )
    private val UTILITY_PREFIXES = listOf("net", "modem", "ser", "order", "readme", "register", "catalog")
    private val UTILITY_SUBSTRINGS = listOf("modem", "edit")

    // Decompression helpers shipped beside installer-first archives (Curse of Enchantia -BP.EXE, Operation Body Count
    // UNCMP.EXE): they print usage or unpack a file, never run the game
    private val UNPACKER_STEMS = setOf("uncmp", "unpack", "unarc", "unpak", "depack", "extract", "lharc", "lha", "arj")

    // Sound-card helpers run before a game (Gravis patch loaders, mixer and init tools): never the game itself
    private val SOUND_HELPER_STEMS = setOf("loadpats", "ultrinit", "ultramid", "sbconfig", "mixerset", "sbmix", "diagnose")

    private val LAUNCHER_STEMS = setOf("play", "start", "run", "go", "game", "main")

    fun isRuntime(fileName: String): Boolean = RUNTIMES.contains(stemOf(fileName))

    fun isInstaller(fileName: String): Boolean {
        val stem = stemOf(fileName)
        return INSTALLER_PREFIXES.any { stem.startsWith(it) } || INSTALLER_SUBSTRINGS.any { stem.contains(it) }
    }

    fun isUnpacker(fileName: String): Boolean {
        val stem = stemOf(fileName)
        // A leading symbol ("-bp") is a helper's naming convention, not a game
        return stem in UNPACKER_STEMS || stem.firstOrNull()?.isLetterOrDigit() == false
    }

    fun isUtility(fileName: String): Boolean {
        val stem = stemOf(fileName)
        return isUnpacker(fileName) || stem in UTILITY_STEMS || stem in SOUND_HELPER_STEMS || UTILITY_PREFIXES.any { stem.startsWith(it) } ||
            UTILITY_SUBSTRINGS.any { stem.contains(it) }
    }

    /**
     * True when a stored main executable should be re-picked: installs made before this ranking existed, and
     * installer-first archives once their installer has produced the game.
     */
    fun isLikelyWrongPick(fileName: String): Boolean = isRuntime(fileName) || isUtility(fileName) || isInstaller(fileName)

    fun pick(candidates: List<Candidate>, slug: String?, title: String?): Candidate? {
        val gamePrograms = candidates.filterNot { isRuntime(it.fileName) || isInstaller(it.fileName) || isUtility(it.fileName) }
            .map { it.stem }.toSet()
        return candidates
            .filterNot { isRuntime(it.fileName) || isInstaller(it.fileName) }
            .maxWithOrNull(compareBy<Candidate> { score(it, slug, title) - (if (startsNoGame(it, gamePrograms)) 150.0 else 0.0) }
                .thenBy { -it.relativePath.length })
    }

    /** A batch file whose commands run none of the install's game programs (only helpers and DOS commands). */
    private fun startsNoGame(candidate: Candidate, gamePrograms: Set<String>): Boolean {
        val commands = candidate.batchCommands ?: return false
        return commands.none { it != candidate.stem && it in gamePrograms }
    }

    /** The program stems a batch file runs: `SSHOCK %1` -> sshock, `call play.bat` -> play, `lh loadpats` -> loadpats. */
    fun batchCommands(text: String): List<String> = text.lines().mapNotNull { raw ->
        var line = raw.trim().removePrefix("@").trim()
        if (line.isEmpty() || line.startsWith(":") || line.startsWith("rem", ignoreCase = true)) return@mapNotNull null
        var words = line.split(Regex("\\s+"))
        while (words.size > 1 && words[0].lowercase(Locale.ROOT) in setOf("call", "lh", "loadhigh")) words = words.drop(1)
        words.firstOrNull()?.substringAfterLast('\\')?.substringAfterLast('/')?.substringBeforeLast('.')?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    internal fun score(candidate: Candidate, slug: String?, title: String?): Double {
        val stem = candidate.stem
        val words = nameWords(slug, title)
        var score = 0.0

        if (words.any { word -> word.length >= 3 && (stem.startsWith(word) || (stem.length >= 3 && word.startsWith(stem))) }) {
            score += 100
        } else if (words.any { word -> commonPrefix(word, stem) >= 4 }) {
            score += 60
        }
        // "one_must_fall_2097" -> OMF.EXE; two-letter initials are too ambiguous (doom_episode_1 -> DE*.EXE)
        val initials = words.filter { word -> word.first().isLetter() }.joinToString("") { it.first().toString() }
        if (initials.length >= 3 && stem.trimEnd { it.isDigit() } == initials) score += 100

        if (stem in LAUNCHER_STEMS) score += 30
        if (isUtility(candidate.fileName)) score -= 80
        score += if (candidate.depth == 0) 20.0 else -10.0 * candidate.depth
        // Size only breaks ties: the game binary is usually the largest program
        score += (ln((candidate.sizeBytes.coerceAtLeast(1024L) / 1024.0)) / ln(2.0)).coerceAtMost(20.0)
        return score
    }

    private fun nameWords(slug: String?, title: String?): List<String> {
        val source = slug?.takeIf { it.isNotBlank() } ?: title.orEmpty()
        return source.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }

    private fun commonPrefix(a: String, b: String): Int = a.zip(b).takeWhile { (x, y) -> x == y }.size

    private fun stemOf(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').lowercase(Locale.ROOT)
}
