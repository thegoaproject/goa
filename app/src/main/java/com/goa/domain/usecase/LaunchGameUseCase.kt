package com.goa.domain.usecase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.goa.data.db.GameDao
import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.data.repository.GamePatchRepository
import com.goa.domain.nativeport.NativeEngine
import com.goa.domain.parser.DosBoxConfParser
import com.goa.domain.parser.SoundHardware
import com.goa.domain.quake.QuakeSupport
import com.goa.domain.scummvm.ScummVmGameDetector
import com.goa.domain.scummvm.ScummVmSupport
import com.goa.domain.tombraider.TombRaiderSupport
import com.goa.ui.emulation.EmulationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LaunchGameUseCase(
    private val context: Context,
    private val gameDao: GameDao,
    private val patchRepository: GamePatchRepository = GamePatchRepository(context),
    private val confParser: DosBoxConfParser = DosBoxConfParser(),
    /** Throws RegistrationRequiredException for own games locked after a refund (LicenseManager.requirePlayable). */
    private val requirePlayable: suspend (gameId: Long) -> Unit = {}
) {
    /** [target]: the game (default; GOG's keyboard menu is answered for the player) or its setup / extra programs. */
    suspend fun launchGame(gameId: Long, target: LaunchTarget = LaunchTarget.Play): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val game = gameDao.getGameByIdSync(gameId)
                ?: throw IllegalArgumentException("Game not found: $gameId")
            requirePlayable(gameId)

            if (game.installStatus != InstallStatus.READY_TO_PLAY || game.localInstallPath == null) {
                throw IllegalStateException("Game is not ready to play: ${game.title}")
            }

            val installDir = File(game.localInstallPath)

            // Quake and Tomb Raider can run natively (their own engine rebuilt for phones: TyrQuake, OpenLara) instead
            // of on a 10-40 fps emulated Pentium; the player picks per game (NativeEngine). Setup programs stay in DOSBox.
            val nativePort = if (target !is LaunchTarget.Program) NativeEngine.portFor(installDir) else null
            if (NativeEngine.useNative(context, gameId, nativePort)) {
                val native = when (nativePort) {
                    NativeEngine.Port.TYRQUAKE -> QuakeSupport.pakFor(installDir, target)?.let { pak ->
                        QuakeSupport.prepareLaunch(context, gameId, pak).let {
                            NativeLaunch(QuakeSupport.CORE_LIBRARY, it.contentFile, it.systemDir, it.saveDir)
                        }
                    }
                    NativeEngine.Port.OPENLARA -> TombRaiderSupport.prepareLaunch(context, gameId, installDir)?.let {
                        NativeLaunch(TombRaiderSupport.CORE_LIBRARY, it.contentFile, it.systemDir, it.saveDir)
                    }
                    null -> null
                }
                if (native != null) {
                    gameDao.updateLastPlayed(gameId, System.currentTimeMillis(), 0L)
                    val nativeIntent = EmulationActivity.createIntent(
                        context = context,
                        gameId = gameId,
                        installPath = game.localInstallPath,
                        confPath = native.content.absolutePath,
                        soundHardwareId = 1,
                        cpuCycles = "auto",
                        memoryMb = 16,
                        gameSlug = game.slug
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(EmulationActivity.EXTRA_CORE_LIBRARY, native.coreLibrary)
                        putExtra(EmulationActivity.EXTRA_CORE_SYSTEM_DIR, native.systemDir.absolutePath)
                        putExtra(EmulationActivity.EXTRA_CORE_SAVE_DIR, native.saveDir.absolutePath)
                    }
                    context.startActivity(nativeIntent)
                    return@runCatching
                }
            }

            // GOG point-and-click titles ship Windows ScummVM rather than a DOS exe, and freeware ones are bare
            // ScummVM data: run both on the ScummVM core and skip all DOSBox configuration
            val scummVmGame = if (ScummVmSupport.isAvailable(context)) ScummVmGameDetector.detect(installDir) else null
            if (scummVmGame != null) {
                val launch = ScummVmSupport.prepareLaunch(context, installDir, gameId, scummVmGame)
                gameDao.updateLastPlayed(gameId, System.currentTimeMillis(), 0L)
                val scummVmIntent = EmulationActivity.createIntent(
                    context = context,
                    gameId = gameId,
                    installPath = game.localInstallPath,
                    confPath = launch.contentFile.absolutePath,
                    soundHardwareId = 1,
                    cpuCycles = "auto",
                    memoryMb = 16,
                    gameSlug = game.slug
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EmulationActivity.EXTRA_SCUMMVM_SYSTEM_DIR, launch.systemDir.absolutePath)
                    putExtra(EmulationActivity.EXTRA_SCUMMVM_SAVE_DIR, launch.saveDir.absolutePath)
                }
                context.startActivity(scummVmIntent)
                return@runCatching
            }

            val rootConf = File(installDir, "dosbox.conf")
            val confFile = if (rootConf.exists()) {
                rootConf
            } else if (!game.dosboxConfPath.isNullOrBlank() && File(game.dosboxConfPath).exists()) {
                File(game.dosboxConfPath)
            } else {
                installDir.walkTopDown().firstOrNull { it.extension.equals("conf", ignoreCase = true) && !it.name.startsWith(".goa-") }
                    ?: rootConf
            }

            val mainExecutable = repairMainExecutable(game, installDir, confFile)

            val initialPreset = confParser.parse(confFile, gameId, mainExecutable)
            patchRepository.applyPatches(
                gameId = gameId,
                slug = game.slug,
                installDir = installDir,
                confFile = confFile,
                preferredSoundHardware = initialPreset.soundHardware
            )
            val preset = confParser.parse(confFile, gameId, mainExecutable)

            val soundId = when (preset.soundHardware) {
                SoundHardware.PRO_AUDIO_SPECTRUM_16 -> 0
                SoundHardware.SOUND_BLASTER_16 -> 1
                SoundHardware.SOUND_BLASTER_PRO -> 2
                SoundHardware.ROLAND_MT_32 -> 3
                SoundHardware.GENERAL_MIDI -> 4
                SoundHardware.GRAVIS_ULTRASOUND -> 5
                SoundHardware.ADLIB -> 6
                else -> 1
            }

            // Music tracks DOSBox Pure can't play (MP3 on GOG's Tomb Raider CD) decoded once, so the CD mounts
            if (confFile.isFile) runCatching { CdImageAudio.prepare(confFile.readText(), confFile.parentFile ?: installDir) }

            // GOG's launcher menu answered (or setup swapped in) in a copy of the conf, so no keyboard is needed
            val launchConf = LaunchConf.prepare(confFile, target, mainExecutable)

            // Update last played timestamp
            gameDao.updateLastPlayed(gameId, System.currentTimeMillis(), 0L)

            val intent = EmulationActivity.createIntent(
                context = context,
                gameId = gameId,
                installPath = game.localInstallPath,
                confPath = launchConf.absolutePath,
                soundHardwareId = soundId,
                cpuCycles = preset.cpuCycles,
                memoryMb = preset.memoryMb,
                gameSlug = game.slug
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        }
    }

    // Installs made before executable ranking can launch a DOS extender, utility or unpacker (OMF 2097 ran DOS4GW.EXE,
    // Tyrian NETMODEM.EXE, DOOM DEICE.EXE), and installer-first archives launch INSTALL.EXE until it has produced the
    // game: re-pick and rewrite the generated autoexec line that names it. Returns the executable to launch.
    private suspend fun repairMainExecutable(game: GameEntity, installDir: File, confFile: File): String? {
        val current = game.mainExecutable ?: return null
        if (!MainExecutablePicker.isLikelyWrongPick(current) || !confFile.exists()) return current
        // Installs made before embedded-archive support still hold DOOM's DOOMS_19.1/.2 or OMF's OMF21.EXE packed
        EmbeddedArchiveExpander.expandAll(installDir)
        val replacement = ExtractionManager.findMainExecutable(installDir, game.slug, game.title)
            ?: ExtractionManager.findInstaller(installDir)?.takeIf { MainExecutablePicker.isUnpacker(current) }
            ?: return current
        if (replacement.name.equals(current, ignoreCase = true)) return current

        val relDir = replacement.parentFile?.takeIf { it != installDir }?.relativeTo(installDir)?.path?.replace('/', '\\')
        val launchLine = if (relDir.isNullOrBlank()) replacement.name else "cd $relDir\n${replacement.name}"
        var replaced = false
        val lines = confFile.readLines().map { line ->
            if (!replaced && line.trim().equals(current, ignoreCase = true)) {
                replaced = true
                launchLine
            } else line
        }
        if (!replaced) return current

        confFile.writeText(lines.joinToString("\n", postfix = "\n"))
        gameDao.updateGame(game.copy(mainExecutable = replacement.name))
        Log.i("LaunchGameUseCase", "Repaired main executable for ${game.title}: $current -> ${replacement.name}")
        return replacement.name
    }
}

/** What a native port starts with: its core, the game file it loads, and its own system and save folders. */
private data class NativeLaunch(val coreLibrary: String, val content: File, val systemDir: File, val saveDir: File)
