package com.goa.domain.parser

import java.io.File
import java.util.Locale

enum class SoundHardware {
    PRO_AUDIO_SPECTRUM_16,
    SOUND_BLASTER_16,
    SOUND_BLASTER_PRO,
    GRAVIS_ULTRASOUND,
    ROLAND_MT_32,
    GENERAL_MIDI,
    ADLIB
}

enum class CpuCore {
    AUTO,
    DYNAMIC,
    NORMAL
}

enum class MidiDevice {
    DEFAULT,
    GENERAL_MIDI,
    ROLAND_MT32,
    ADLIB,
    NONE
}

data class EmulationPreset(
    val gameId: Long,
    val executable: String,
    val parameters: String = "",
    val cpuCore: CpuCore = CpuCore.AUTO,
    val cpuCycles: String = "auto",
    val memoryMb: Int = 16,
    val enableEms: Boolean = true,
    val enableXms: Boolean = true,
    val soundHardware: SoundHardware = SoundHardware.SOUND_BLASTER_16,
    val sbPort: Int = 0x220,
    val sbIrq: Int = 7,
    val sbDma: Int = 1,
    val sbHdma: Int = 5,
    val midiDevice: MidiDevice = MidiDevice.DEFAULT
)

class DosBoxConfParser {

    fun parse(confFile: File, gameId: Long, fallbackExecutable: String? = null): EmulationPreset {
        if (!confFile.exists()) {
            return EmulationPreset(
                gameId = gameId,
                executable = fallbackExecutable ?: "GAME.EXE"
            )
        }

        var currentSection = ""
        var memsize = 16
        var core = CpuCore.AUTO
        var cycles = "auto"
        var enableEms = true
        var enableXms = true
        var soundHardware = SoundHardware.SOUND_BLASTER_16
        var sbPort = 0x220
        var sbIrq = 7
        var sbDma = 1
        var sbHdma = 5
        var midiDevice = MidiDevice.DEFAULT
        var autoexecExe = ""
        var autoexecParams = ""

        confFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                return@forEachLine
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).lowercase(Locale.ROOT)
                return@forEachLine
            }

            if (currentSection == "autoexec") {
                val lower = trimmed.lowercase(Locale.ROOT)
                if (lower.startsWith("mount") || lower.startsWith("imgmount") || lower == "c:" || lower == "exit" || lower.startsWith("@echo")) {
                    return@forEachLine
                }

                // First non-mount/exit line is usually the executable
                if (autoexecExe.isEmpty()) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.isNotEmpty()) {
                        autoexecExe = parts[0]
                        if (parts.size > 1) {
                            autoexecParams = parts.drop(1).joinToString(" ")
                        }
                    }
                }
                return@forEachLine
            }

            val eqIdx = trimmed.indexOf('=')
            if (eqIdx == -1) return@forEachLine

            val key = trimmed.substring(0, eqIdx).trim().lowercase(Locale.ROOT)
            val value = trimmed.substring(eqIdx + 1).trim()

            when (currentSection) {
                "dosbox" -> {
                    if (key == "memsize") memsize = value.toIntOrNull() ?: 16
                }
                "cpu" -> {
                    when (key) {
                        "core" -> core = when (value.lowercase(Locale.ROOT)) {
                            "dynamic" -> CpuCore.DYNAMIC
                            "normal" -> CpuCore.NORMAL
                            else -> CpuCore.AUTO
                        }
                        "cycles" -> cycles = value
                    }
                }
                "dos" -> {
                    if (key == "xms") enableXms = value.toBooleanStrictOrNull() ?: true
                    if (key == "ems") enableEms = value.toBooleanStrictOrNull() ?: true
                }
                "sblaster" -> {
                    when (key) {
                        "sbtype" -> {
                            val v = value.lowercase(Locale.ROOT)
                            soundHardware = when {
                                v.contains("pas") -> SoundHardware.PRO_AUDIO_SPECTRUM_16
                                v.contains("pro") -> SoundHardware.SOUND_BLASTER_PRO
                                v.contains("gus") -> SoundHardware.GRAVIS_ULTRASOUND
                                else -> SoundHardware.SOUND_BLASTER_16
                            }
                        }
                        "sbbase" -> sbPort = value.toIntOrNull(16) ?: 0x220
                        "irq" -> sbIrq = value.toIntOrNull() ?: 7
                        "dma" -> sbDma = value.toIntOrNull() ?: 1
                        "hdma" -> sbHdma = value.toIntOrNull() ?: 5
                    }
                }
                "midi" -> {
                    if (key == "mididevice") {
                        midiDevice = when (value.lowercase(Locale.ROOT)) {
                            "mt32" -> MidiDevice.ROLAND_MT32
                            "general", "gm" -> MidiDevice.GENERAL_MIDI
                            "none" -> MidiDevice.NONE
                            else -> MidiDevice.DEFAULT
                        }
                    }
                }
            }
        }

        val resolvedExe = if (autoexecExe.isNotEmpty()) autoexecExe else (fallbackExecutable ?: "GAME.EXE")

        return EmulationPreset(
            gameId = gameId,
            executable = resolvedExe,
            parameters = autoexecParams,
            cpuCore = core,
            cpuCycles = cycles,
            memoryMb = memsize,
            enableEms = enableEms,
            enableXms = enableXms,
            soundHardware = soundHardware,
            sbPort = sbPort,
            sbIrq = sbIrq,
            sbDma = sbDma,
            sbHdma = sbHdma,
            midiDevice = midiDevice
        )
    }
}

