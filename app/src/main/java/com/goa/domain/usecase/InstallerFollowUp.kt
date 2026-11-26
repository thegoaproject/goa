package com.goa.domain.usecase

import java.io.File

/**
 * Installer-first games (Apogee `.SHR` shareware such as Commander Keen and Wolfenstein 3D, and many archives in
 * players' collections) launch their own DOS installer, which unpacks the game and then drops back to a bare DOS
 * prompt. Players were left at `C:\KEEN>` not knowing what to type.
 *
 * Deterministic follow-up, no AI: the core reports each DOS program as it starts. When the installer has run and the
 * DOS shell comes back, and the install folder now holds a game program (the same ranked pick used at install
 * time), the commands that start it are returned once for the session to type. Later launches go straight to the
 * game: LaunchGameUseCase re-picks the executable once the installer has produced it.
 */
class InstallerFollowUp(
    private val installDir: File,
    private val slug: String?,
    private val title: String?,
    private val findGame: (File, String?, String?) -> File? = ExtractionManager::findMainExecutable
) {
    private var installerRan = false

    /** True once the game has been started (or there was nothing to do); the follow-up never repeats in a session. */
    var done = false
        private set

    /** Feed every program change; returns the command lines to type, at most once per session. */
    fun onProgram(name: String): List<String>? {
        if (done) return null
        val program = name.trim().uppercase()
        if (isInstallerProgram(program)) {
            installerRan = true
            return null
        }
        if (!installerRan || program != SHELL) return null
        val game = findGame(installDir, slug, title) ?: return null // the player quit the installer early
        done = true
        return commandsFor(game, installDir)
    }

    companion object {
        /** The name the core reports while the DOS command prompt is in charge. */
        const val SHELL = "DOSBOX"

        fun isInstallerProgram(program: String) = program.startsWith("INSTALL")

        /** `C:` `CD \KEEN` `KEEN1.EXE`: the install folder is drive C. */
        fun commandsFor(game: File, installDir: File): List<String> {
            val dir = game.parentFile?.takeIf { it != installDir }?.relativeTo(installDir)?.path?.replace('/', '\\')
            return listOf("c:", if (dir.isNullOrEmpty()) "cd \\" else "cd \\$dir", game.name.lowercase())
        }

        /** Whether this launch starts the top-level installer (the autoexec runs INSTALL.EXE or INSTALL.BAT). */
        fun isInstallerLaunch(conf: File, installDir: File): Boolean {
            val installer = ExtractionManager.findInstaller(installDir) ?: return false
            if (!conf.isFile) return false
            val autoexec = conf.readLines().dropWhile { !it.trim().equals("[autoexec]", ignoreCase = true) }.drop(1)
            return autoexec.map { it.trim().removePrefix("call ").removePrefix("CALL ").trim() }.any {
                it.equals(installer.name, ignoreCase = true) || it.equals(installer.nameWithoutExtension, ignoreCase = true)
            }
        }
    }
}
