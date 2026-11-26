package com.goa.domain.controls

import java.io.File
import java.util.Locale

object SlmManualExtractor {

    private val DOC_EXTENSIONS = setOf("txt", "doc", "me", "1st", "hlp", "nfo")
    private val DOC_NAMES = listOf("readme", "manual", "help", "guide", "keys", "commands", "controls", "install")
    private val CONTROL_KEYWORDS = listOf("control", "keyboard", "key", "movement", "fire", "jump", "joystick", "mouse", "command", "button", "strafe", "pogo")

    /**
     * Discovers documentation files in the game install directory and extracts
     * relevant control documentation text, trimmed to fit within token limits.
     */
    fun extractDocumentation(installDir: File, maxChars: Int = 4000): String {
        if (!installDir.exists() || !installDir.isDirectory) return ""

        val candidateFiles = installDir.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile }
            .filter { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                val base = file.nameWithoutExtension.lowercase(Locale.ROOT)
                ext in DOC_EXTENSIONS || DOC_NAMES.any { base.contains(it) }
            }
            .sortedByDescending { file ->
                val name = file.name.lowercase(Locale.ROOT)
                when {
                    name.contains("control") || name.contains("keys") -> 100
                    name.contains("readme") -> 80
                    name.contains("manual") -> 70
                    name.contains("help") -> 50
                    else -> 10
                }
            }
            .toList()

        if (candidateFiles.isEmpty()) return ""

        val extractedBlocks = mutableListOf<String>()
        var totalLength = 0

        for (file in candidateFiles.take(5)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val controlSections = isolateControlSections(text)
            val excerpt = if (controlSections.isNotBlank()) controlSections else text.take(1500)

            extractedBlocks.add("--- File: ${file.name} ---\n$excerpt")
            totalLength += excerpt.length
            if (totalLength >= maxChars) break
        }

        return extractedBlocks.joinToString("\n\n").take(maxChars)
    }

    /**
     * Isolates blocks of text near control-related headings or keywords.
     */
    private fun isolateControlSections(content: String): String {
        val lines = content.lines()
        val relevantLines = mutableListOf<String>()
        var recording = false
        var linesRecorded = 0

        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT).trim()
            val hasKeyword = CONTROL_KEYWORDS.any { lower.contains(it) }

            if (hasKeyword && (line.startsWith("=") || line.startsWith("#") || line.uppercase(Locale.ROOT) == line || line.endsWith(":"))) {
                recording = true
                linesRecorded = 0
            }

            if (recording) {
                relevantLines.add(line)
                linesRecorded++
                if (linesRecorded > 40) {
                    recording = false
                }
            } else if (hasKeyword && line.length > 5) {
                relevantLines.add(line)
            }
        }

        return relevantLines.joinToString("\n").trim()
    }
}

