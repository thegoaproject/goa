package com.goa.domain.usecase

import java.io.File

/**
 * What a launch should start. GOG's DOS games open a text menu ("1) Run Mortal Kombat  2) Run Sound Setup  3) Exit")
 * that needs a keyboard; other games keep a separate setup program (SETUP.EXE, SETSOUND.EXE) beside the game.
 * GOA reads both deterministically and offers them as buttons, so Play starts the game and Setup starts setup.
 */
sealed interface LaunchTarget {
    /** The game itself (the default: the menu's first entry, or the conf's own program). */
    data object Play : LaunchTarget
    /** An entry of GOG's launcher menu: [label] is the batch label its number jumps to. */
    data class MenuEntry(val label: String) : LaunchTarget
    /** A setup program beside the game, run in place of the game ([relativePath] from the install folder). */
    data class Program(val relativePath: String) : LaunchTarget
}

/** One choice the game page can offer besides Play. */
data class LaunchOption(val target: LaunchTarget, val name: String, val kind: Kind) {
    enum class Kind { SETUP, EXTRA }
}

/**
 * GOG's launcher menu inside a conf's [autoexec]: `choice /c123`, `if errorlevel N goto label`, and ECHO lines that
 * name each number. Parsed and rewritten without running anything.
 */
object LauncherMenu {
    data class Entry(val number: Int, val label: String, val name: String, val kind: Kind)
    enum class Kind { GAME, SETUP, EXTRA, EXIT }
    data class Menu(val label: String?, val entries: List<Entry>) {
        val game: Entry get() = entries.first { it.kind == Kind.GAME }
    }

    private val CHOICE = Regex("""(?im)^\s*@?choice\s+/c\s*([0-9a-z]+)\b.*$""")
    private val ERRORLEVEL = Regex("""(?im)^\s*if\s+errorlevel\s+(\d+)\s+goto\s+:?(\w+)\s*$""")
    private val NAMED_OPTION = Regex("""(\d)\)\s*([^\r\n]*?)\s*[^\x20-\x7e]*\s*$""")
    private val SETUP_WORDS = Regex("""(?i)\b(setup|set ?up|settings|sound|config\w*|install\w*|options)\b""")
    private val EXIT_WORDS = Regex("""(?i)\b(exit|quit)\b""")

    private fun autoexec(conf: String): String {
        val start = Regex("""(?im)^\s*\[autoexec]\s*$""").find(conf) ?: return ""
        return conf.substring(start.range.last + 1)
    }

    /** Index of the `choice` line in [lines] and the errorlevel jumps right after it (blank lines allowed between). */
    private fun choiceBlock(lines: List<String>): Pair<Int, IntRange>? {
        val at = lines.indexOfFirst { CHOICE.matches(it) }
        if (at < 0) return null
        var last = at
        for (i in at + 1 until lines.size) {
            when {
                ERRORLEVEL.matches(lines[i]) -> last = i
                lines[i].isBlank() -> continue
                else -> break
            }
        }
        return if (last == at) null else at to (at..last)
    }

    /** The menu, or null when the conf has none (or only one real choice, which isn't a menu worth showing). */
    fun parse(conf: String): Menu? {
        val auto = autoexec(conf)
        val lines = auto.lines()
        val (choiceAt, block) = choiceBlock(lines) ?: return null
        val jumps = block.mapNotNull { ERRORLEVEL.matchEntire(lines[it])?.let { m -> m.groupValues[1].toInt() to m.groupValues[2] } }.toMap()
        val before = lines.subList(0, choiceAt)
        val names = before.mapNotNull { line ->
            if (!line.trimStart().removePrefix("@").startsWith("echo", ignoreCase = true)) return@mapNotNull null  // "@ECHO  2) Twin Dragon"
            NAMED_OPTION.find(line.replace(Regex("""\x1b\[[0-9;]*m"""), ""))?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() }
        }.toMap()
        val menuLabel = before.mapNotNull { Regex("""^\s*:(\w+)\s*$""").matchEntire(it)?.groupValues?.get(1) }.lastOrNull()

        val entries = jumps.toSortedMap().map { (number, label) ->
            val name = names[number].orEmpty().ifBlank { label }
            val body = section(auto, label)
            val kind = when {
                EXIT_WORDS.containsMatchIn(name) || label.equals("exit", true) || body.trim().equals("exit", true) -> Kind.EXIT
                SETUP_WORDS.containsMatchIn(name) || SETUP_WORDS.containsMatchIn(label) ||
                    programsIn(body).any { MainExecutablePicker.isInstaller(it) } -> Kind.SETUP
                else -> Kind.EXTRA
            }
            Entry(number, label, cleanName(name), kind)
        }.toMutableList()
        // The game is the first entry that isn't setup or exit (normally 1)
        val gameAt = entries.indexOfFirst { it.kind == Kind.EXTRA }
        if (gameAt < 0) return null
        entries[gameAt] = entries[gameAt].copy(kind = Kind.GAME)
        return Menu(menuLabel, entries).takeIf { m -> m.entries.count { it.kind != Kind.EXIT } >= 2 }
    }

    /** The lines under `:label` up to the next label. */
    private fun section(auto: String, label: String): String {
        val start = Regex("""(?im)^\s*:${Regex.escape(label)}\s*$""").find(auto) ?: return ""
        val rest = auto.substring(start.range.last + 1)
        val end = Regex("""(?m)^\s*:\w+\s*$""").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun programsIn(body: String): List<String> = body.lines().map { it.trim().substringBefore(' ') }
        .filter { Regex("""(?i)^[\w~-]+\.(exe|com|bat)$""").matches(it) }

    /** "Run Sound Setup" -> "Sound Setup"; menu prose trimmed for a button. */
    private fun cleanName(name: String) = name.replace(Regex("""(?i)^(run|start|launch)\s+"""), "").trim()
        .replaceFirstChar { it.uppercase() }

    /**
     * The conf with the menu answered: the jump into the menu goes straight to [label] (the menu is never drawn),
     * and every "back to the menu" afterwards ends the session instead, so Setup returns to GOA, not to a keyboard menu.
     */
    fun answer(conf: String, menu: Menu, label: String): String {
        val autoStart = Regex("""(?im)^\s*\[autoexec]\s*$""").find(conf)?.range?.last?.plus(1) ?: return conf
        val lines = conf.substring(autoStart).lines().toMutableList()
        if (lines.none { Regex("""(?i)^\s*:${Regex.escape(label)}\s*$""").matches(it) }) return conf
        // The menu itself (when an older GOA hasn't already cut it out) jumps to the choice too
        choiceBlock(lines)?.let { (choiceAt, block) ->
            for (i in block.last downTo block.first + 1) lines.removeAt(i)
            lines[choiceAt] = "goto $label"
        }
        val menuLabel = menu.label
        if (menuLabel != null) {
            val gotoMenu = Regex("""(?i)^(\s*)goto\s+:?${Regex.escape(menuLabel)}\s*$""")
            val defAt = lines.indexOfFirst { Regex("""(?i)^\s*:${Regex.escape(menuLabel)}\s*$""").matches(it) }
            for (i in lines.indices) {
                val m = gotoMenu.matchEntire(lines[i]) ?: continue
                lines[i] = m.groupValues[1] + if (defAt >= 0 && i < defAt) "goto $label" else "exit"
            }
        }
        return conf.substring(0, autoStart) + lines.joinToString("\n")
    }

    /**
     * The menu for an install: from its conf, or, when an older GOA cut the menu out of it, from GOG's original conf
     * in the game folder, as long as the entries' labels are still in the conf.
     */
    fun forInstall(conf: String?, installDir: File): Menu? {
        conf ?: return null
        parse(conf)?.let { return it }
        val original = GogConfAdapter.pickGogConf(installDir)?.takeIf { it.isFile }?.readText() ?: return null
        val menu = parse(original) ?: return null
        val labels = Regex("""(?im)^\s*:(\w+)\s*$""").findAll(conf).map { it.groupValues[1].lowercase() }.toSet()
        return menu.takeIf { m -> m.entries.filter { it.kind != Kind.EXIT }.all { it.label.lowercase() in labels } }
    }

    /** The options to offer beside Play for this install (empty when there is nothing else to start). */
    fun options(conf: String?, installDir: File, mainExecutable: String?): List<LaunchOption> {
        val menu = forInstall(conf, installDir)
        if (menu != null) {
            return menu.entries.filter { it.kind == Kind.SETUP || it.kind == Kind.EXTRA }.map {
                LaunchOption(LaunchTarget.MenuEntry(it.label), it.name,
                    if (it.kind == Kind.SETUP) LaunchOption.Kind.SETUP else LaunchOption.Kind.EXTRA)
            }
        }
        val setup = SetupProgram.find(installDir, mainExecutable, conf) ?: return emptyList()
        return listOf(LaunchOption(LaunchTarget.Program(setup.relativeTo(installDir).path), "Setup", LaunchOption.Kind.SETUP))
    }
}

/** Writes the conf a launch actually runs: the game's conf with GOG's menu answered, or with setup in its place. */
object LaunchConf {
    const val SESSION_NAME = ".goa-launch.conf"  // ".goa-" confs are skipped by GOA's conf scanners

    /**
     * The conf file to start for [target]: [conf] itself when nothing needs answering, else a copy beside it (DOSBox
     * resolves the conf's relative mounts from its own folder) with the menu answered or the setup program swapped in.
     */
    fun prepare(conf: File, target: LaunchTarget, mainExecutable: String?): File {
        if (!conf.isFile) return conf
        val text = conf.readText()
        val menu = LauncherMenu.forInstall(text, conf.parentFile ?: return conf)
        val answered = when (target) {
            LaunchTarget.Play -> menu?.let { LauncherMenu.answer(text, it, it.game.label) }
            is LaunchTarget.MenuEntry -> menu?.let { LauncherMenu.answer(text, it, target.label) }
            is LaunchTarget.Program -> mainExecutable?.let { SetupProgram.answer(text, it, target.relativePath) }
        } ?: text
        // CD images with music DOSBox Pure can't play mount GOA's copy of the cue sheet (CdImageAudio)
        val launchText = CdImageAudio.rewriteMounts(answered, conf.parentFile ?: return conf)
        if (launchText == text) return conf
        return File(conf.parentFile, SESSION_NAME).apply { writeText(launchText) }
    }
}

/** A setup program beside the game's own program, for games without GOG's menu. */
object SetupProgram {
    // In order of preference; INSTALL is left out (it can reinstall over the game)
    private val NAMES = listOf("setup", "setsound", "sndsetup", "soundset", "setmain", "sound", "config", "setupsnd")

    fun find(installDir: File, mainExecutable: String?, conf: String?): File? {
        val main = mainExecutable?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (conf != null && !runsLine(conf, main)) return null
        val dir = GogConfAdapter.shallowestFirst(installDir.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.equals(main, ignoreCase = true) }.toList(), installDir).firstOrNull()?.parentFile
            ?: return null
        val programs = dir.listFiles()?.filter { it.isFile && ExtractionManager.isDosExecutable(it) }.orEmpty()
        return NAMES.firstNotNullOfOrNull { name -> programs.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) } }
    }

    private fun runsLine(conf: String, main: String) =
        Regex("""(?im)^[ \t]*${Regex.escape(main)}([ \t].*)?$""").containsMatchIn(conf) ||
            Regex("""(?im)^[ \t]*${Regex.escape(main.substringBeforeLast('.'))}([ \t].*)?$""").containsMatchIn(conf)

    /** The conf with the game's own program line replaced by [setup] (same folder), ending the session after it. */
    fun answer(conf: String, main: String, setup: String): String {
        val stem = Regex.escape(main.substringBeforeLast('.'))
        val line = Regex("""(?im)^([ \t]*)(?:${Regex.escape(main)}|$stem)(?:[ \t].*)?$""")
        val name = setup.substringAfterLast('/').substringAfterLast('\\')
        val m = line.find(conf) ?: return conf
        val indent = m.groupValues[1]
        return conf.replaceRange(m.range, "$indent$name\n${indent}exit")
    }
}
