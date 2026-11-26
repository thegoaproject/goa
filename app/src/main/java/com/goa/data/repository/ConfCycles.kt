package com.goa.data.repository

object ConfCycles {
    /**
     * Writes a profile's CPU speed (curated profiles, AI CPU fixes) into [cpu]. "auto" means the profile has no opinion:
     * the game's own value stays, and one GOA replaced earlier is put back from the marker comment.
     */
    fun apply(text: String, cycles: String): String {
        val marker = Regex("""(?im)^#\s*goa-original-cycles=(.*)\r?\n?""")
        val cyclesLine = Regex("""(?im)^\s*cycles\s*=.*$""")
        var content = text
        val saved = marker.find(content)
        if (cycles.equals("auto", ignoreCase = true)) {
            if (saved == null) return content
            val original = saved.groupValues[1].trim()
            content = content.replaceRange(saved.range, "")
            return when {
                original.isEmpty() -> content.replace(Regex("""(?im)^\s*cycles\s*=.*\r?\n?"""), "")
                cyclesLine.containsMatchIn(content) -> content.replace(cyclesLine, "cycles=$original")
                else -> content
            }
        }
        if (saved == null) {
            val current = cyclesLine.find(content)?.value?.substringAfter('=')?.trim().orEmpty()
            content = insertInCpuSection(content, "# goa-original-cycles=$current")
        }
        return if (cyclesLine.containsMatchIn(content)) content.replace(cyclesLine, "cycles=$cycles")
        else insertInCpuSection(content, "cycles=$cycles")
    }

    private fun insertInCpuSection(content: String, line: String): String {
        val header = Regex("""(?im)^\[cpu\]\s*$""").find(content)
        if (header != null) {
            val at = header.range.last + 1
            return content.substring(0, at) + "\n" + line + content.substring(at)
        }
        val autoexec = content.indexOf("[autoexec]", ignoreCase = true)
        val block = "[cpu]\n$line\n\n"
        return if (autoexec >= 0) content.substring(0, autoexec) + block + content.substring(autoexec) else content + "\n" + block
    }
}
