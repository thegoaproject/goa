package com.goa.domain.usecase

import java.io.File
import java.util.Locale

/**
 * Turns GOG's DOSBox configuration into one GOA can run.
 *
 * GOG's Windows DOSBox runs from a `DOSBOX` folder beside the game, so its autoexec says `mount C ".."` and
 * `imgmount d "..\game.dat" -t iso`: Windows paths relative to that folder. GOA runs DOSBox with the install folder as
 * its current directory. Every mount path is therefore resolved against the files actually on disk: ".." is the
 * folder that holds the game (found from the autoexec's `cd` and program lines, so a game in a subfolder of a pack,
 * like "Tomb Raider 1/", mounts that subfolder), and an image is looked up by name, ignoring case. A mount whose
 * folder does not exist (GOG's `..\cloud_saves`) is dropped.
 */
object GogConfAdapter {
    private val WRAPPER_DIRS = setOf("dosbox", "__redist", "__support", "commonappdata")
    private val MOUNT = Regex("""^(\s*@?)mount\s+([a-z]):?\s+(?:"([^"]*)"|(\S+))(.*)$""", RegexOption.IGNORE_CASE)
    private val IMGMOUNT = Regex("""^(\s*@?)imgmount\s+([a-z0-9]):?\s+(.*)$""", RegexOption.IGNORE_CASE)
    private val CD = Regex("""^\s*@?cd\s+\\?([^\s]+)\s*$""", RegexOption.IGNORE_CASE)
    private val PROGRAM = Regex("""^\s*@?(?:call\s+)?([^\s"]+\.(?:exe|com|bat))\b""", RegexOption.IGNORE_CASE)
    /** What our own earlier, broken rewrite left behind: `imgmount d "..\x"` with `mount d "..\x"` cut out of it. */
    private val MANGLED_IMG = Regex("""(?im)^\s*img\s+-t\s""")

    /**
     * GOG's confs in this install, best first. GOA's DOSBox has no 3dfx Glide, so a software-rendering conf
     * (Tomb Raider's `dosboxTR_soft.conf` runs TOMBS.EXE instead of the Glide TOMB.EXE) beats the "single" one.
     */
    fun pickGogConf(installDir: File): File? {
        val confs = installDir.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.extension.equals("conf", ignoreCase = true) && !it.name.contains(".goa-") }
            .filterNot { it.name.equals("dosbox.conf", ignoreCase = true) }      // ours, or GOG's generic DOSBox defaults
            .toList()
        fun named(s: String) = confs.firstOrNull { it.name.contains(s, ignoreCase = true) && hasAutoexec(it) }
        return named("_soft") ?: named("single") ?: named("dosbox")
    }

    private fun hasAutoexec(f: File) = runCatching { f.readText().contains("[autoexec]", ignoreCase = true) }.getOrDefault(false)

    /** GOG's conf text, adapted. Lines outside [autoexec] are kept as they are. */
    fun adapt(text: String, installDir: File): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim().equals("[autoexec]", ignoreCase = true) }
        if (start < 0) return text
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val auto = lines.subList(start + 1, end)
        val cRoot = gameRoot(auto, installDir) ?: installDir
        val adapted = auto.mapNotNull { adaptLine(it, installDir, cRoot) }
        return (lines.subList(0, start + 1) + adapted + lines.subList(end, lines.size)).joinToString("\n")
    }

    /**
     * An installed conf whose mounts an earlier GOA broke (`img -t iso`, or `c:` with nothing mounted on C): its
     * [autoexec] is rebuilt from GOG's original conf. Null when the conf is fine or there is no GOG conf to rebuild from.
     */
    fun repair(content: String, installDir: File): String? {
        val auto = autoexecOf(content) ?: return null
        val noC = auto.none { MOUNT.find(it)?.groupValues?.get(2).equals("c", ignoreCase = true) } &&
            auto.any { it.trim().equals("c:", ignoreCase = true) }
        val mangled = MANGLED_IMG.containsMatchIn(auto.joinToString("\n")) || noC
        val gog = pickGogConf(installDir) ?: return null
        val fresh = autoexecOf(adapt(gog.readText(), installDir)) ?: return null
        val lines = content.lines()
        val start = lines.indexOfFirst { it.trim().equals("[autoexec]", ignoreCase = true) }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        if (mangled) return (lines.subList(0, start + 1) + fresh + lines.subList(end, lines.size)).joinToString("\n")

        // Drive C mounted on the wrong folder by an earlier GOA (Shadow Warrior on dragon/DRAGONBU): only that line
        // is put right, the rest of the autoexec stays as it is. GOG installs only: an archive from the player's own
        // folders can carry some packager's dosbox*.conf that never described this install
        if (!isGogInstall(installDir)) return null
        val at = (start + 1 until end).firstOrNull { isCMount(lines[it]) } ?: return null
        val want = fresh.firstOrNull { isCMount(it) } ?: return null
        if (sameFolder(mountPath(lines[at]), mountPath(want), installDir)) return null
        return lines.toMutableList().apply { this[at] = want }.joinToString("\n")
    }

    /** GOG's own files: goggame-*.info / goggame.dll, a GAME.GOG image, or the __support folder. */
    private fun isGogInstall(installDir: File): Boolean = installDir.walkTopDown().maxDepth(2).any {
        it.name.startsWith("goggame", ignoreCase = true) || it.extension.equals("gog", ignoreCase = true) ||
            (it.isDirectory && it.name.equals("__support", ignoreCase = true))
    }

    private fun isCMount(line: String) = MOUNT.find(line)?.groupValues?.get(2).equals("c", ignoreCase = true)

    private fun mountPath(line: String): String = MOUNT.find(line)!!.let { m -> m.groupValues[3].ifEmpty { m.groupValues[4] } }

    private fun sameFolder(a: String, b: String, installDir: File): Boolean {
        fun dir(p: String) = runCatching { File(installDir, p.replace('\\', '/').ifEmpty { "." }).canonicalFile }.getOrNull()
        return dir(a) == dir(b)
    }

    private fun autoexecOf(text: String): List<String>? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim().equals("[autoexec]", ignoreCase = true) }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        return lines.subList(start + 1, end)
    }

    private fun adaptLine(line: String, installDir: File, cRoot: File): String? {
        MOUNT.find(line)?.let { m ->
            val (lead, drive) = m.destructured.let { it.component1() to it.component2() }
            val path = m.groupValues[3].ifEmpty { m.groupValues[4] }
            val rest = m.groupValues[5].replace(Regex("""(?i)\s+-t\s+dir\b"""), "")
            val dir = if (isGameFolder(path)) cRoot else resolve(path, cRoot, installDir)?.takeIf { it.isDirectory }
                ?: return null                                   // a folder this install doesn't have (..\cloud_saves)
            return "${lead}mount $drive \"${relative(dir, installDir)}\"$rest"
        }
        IMGMOUNT.find(line)?.let { m ->
            val (lead, drive, args) = m.destructured
            val tokens = tokenize(args)
            val firstOption = tokens.indexOfFirst { it.startsWith("-") }.let { if (it < 0) tokens.size else it }
            val images = tokens.subList(0, firstOption).map { p ->
                resolve(p, cRoot, installDir)?.takeIf { it.isFile }?.let { relative(it, installDir) } ?: p.replace('\\', '/')
            }
            val options = tokens.subList(firstOption, tokens.size).joinToString(" ")
                .replace(Regex("""(?i)-t\s+cdrom\b"""), "-t iso")
            return "${lead}imgmount $drive ${images.joinToString(" ") { "\"$it\"" }}${if (options.isEmpty()) "" else " $options"}"
        }
        return line
    }

    private fun isGameFolder(path: String) =
        path.trim().trimEnd('\\', '/').lowercase(Locale.ROOT) in setOf("..", ".", ".app", "app", "")

    /** A GOG path ("..\game.dat", "..\..\x", "cd\game.cue") on disk: from the game's folder first, then the install root. */
    private fun resolve(path: String, cRoot: File, installDir: File): File? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return cRoot
        for (base in listOf(cRoot, installDir)) {
            var cur: File? = base
            for (p in parts) cur = cur?.listFiles()?.firstOrNull { it.name.equals(p, ignoreCase = true) }
            if (cur != null) return cur
        }
        // last resort: the file by name anywhere in the install (GOG moved it out of __support/app)
        val name = parts.last()
        return shallowestFirst(installDir.walkTopDown().maxDepth(4)
            .filter { it.name.equals(name, ignoreCase = true) && !inWrapper(it, installDir) }.toList(), installDir).firstOrNull()
    }

    /**
     * The folder GOG's ".." stands for: where the autoexec's `cd` chain and program are found. For a pack
     * ("Tomb Raider 1/TOMBRAID/TOMB.EXE" after `cd TOMBRAID`) that is the game's subfolder, not the install root.
     */
    private fun gameRoot(auto: List<String>, installDir: File): File? {
        // Only the `cd`s on the way to the first program count: later ones belong to other menu entries
        // (Shadow Warrior's Twin Dragon entry does `cd .\dragon`, which has nothing to do with sw.exe)
        val cds = mutableListOf<String>()
        var program: String? = null
        for (l in auto) {
            PROGRAM.find(l)?.let { program = it.groupValues[1] }
            if (program != null) break
            CD.find(l)?.let { cds += it.groupValues[1].trimEnd('\\') }
        }
        val exe = program ?: return null
        val cdPath = cds.lastOrNull()?.replace('\\', '/')?.split('/')?.filter { it.isNotEmpty() && it != ".." && it != "." }.orEmpty()
        // Shallowest copy first: a pack can keep a backup of the program deeper down (dragon/DRAGONBU/SW.EXE), and
        // the order a folder lists its files in is not fixed
        val hits = shallowestFirst(installDir.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals(exe, ignoreCase = true) && !inWrapper(it, installDir) }
            .toList(), installDir)
        for (hit in hits) {
            var dir: File = hit.parentFile ?: continue
            var ok = true
            for (p in cdPath.reversed()) {
                if (!dir.name.equals(p, ignoreCase = true)) { ok = false; break }
                dir = dir.parentFile ?: run { ok = false; null } ?: break
            }
            if (ok && dir.path.startsWith(installDir.path)) return dir
        }
        return hits.firstOrNull()?.parentFile
    }

    /** Files nearest the install root first, then by path, so a pick never depends on the order a folder lists in. */
    fun shallowestFirst(files: List<File>, root: File): List<File> =
        files.sortedWith(compareBy({ it.relativeTo(root).path.count { c -> c == File.separatorChar } }, { it.path }))

    private fun inWrapper(f: File, root: File): Boolean =
        f.relativeTo(root).path.split(File.separatorChar).dropLast(1).any { it.lowercase(Locale.ROOT) in WRAPPER_DIRS }

    private fun relative(f: File, installDir: File): String =
        f.relativeTo(installDir).path.replace(File.separatorChar, '/').ifEmpty { "." }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var has = false
        for (c in s) when {
            c == '"' -> { quoted = !quoted; has = true }
            c.isWhitespace() && !quoted -> if (has) { out += cur.toString(); cur.clear(); has = false }
            else -> { cur.append(c); has = true }
        }
        if (has) out += cur.toString()
        return out
    }
}
