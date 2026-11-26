package com.goa.ui.settings

/**
 * Parser for `assets/legal/help.txt`, the single source of the FAQ. The website generator (infra/website/build.py,
 * `render_legal`) reads the same file with the same rules (title line, UPPERCASE headings, "- " bullets, paragraphs, plus
 * inline **bold** and [label](url)). Pure Kotlin so it can be unit-tested on the JVM.
 */
sealed interface HelpSpan {
    data class Text(val text: String) : HelpSpan
    /** `**name**`: an in-app name such as "Fix this game". */
    data class Bold(val text: String) : HelpSpan
    /** `[label](https://…)` or a bare URL (then label == url). */
    data class Link(val label: String, val url: String) : HelpSpan
    data class Email(val address: String) : HelpSpan
}

sealed interface HelpBlock {
    data class Paragraph(val spans: List<HelpSpan>) : HelpBlock
    data class Bullets(val items: List<List<HelpSpan>>) : HelpBlock
}

data class HelpSection(val heading: String, val blocks: List<HelpBlock>)

data class HelpDocument(val title: String, val intro: List<HelpBlock>, val sections: List<HelpSection>)

object HelpContent {

    private val ACRONYMS = setOf("GOA", "GOG", "AI", "GDPR", "UK", "IP", "ID")
    /** Names that keep a capital in a heading ("GOA PRO" -> "GOA Pro"). */
    private val PROPER_NAMES = mapOf("PRO" to "Pro")
    private val CAPS_RUN = Regex("[A-Z]{3}")
    private val MARKER = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val AUTO = Regex("""https?://[^\s<,)]+|\b[\w.+-]+@[\w-]+\.[\w.]+\w\b""")

    fun parse(text: String): HelpDocument {
        val lines = text.trim().lines()
        val title = lines.firstOrNull()?.trim().orEmpty()

        val intro = mutableListOf<HelpBlock>()
        val sections = mutableListOf<HelpSection>()
        var heading: String? = null
        var blocks = mutableListOf<HelpBlock>()
        val bullets = mutableListOf<List<HelpSpan>>()

        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                blocks.add(HelpBlock.Bullets(bullets.toList()))
                bullets.clear()
            }
        }

        fun closeSection() {
            flushBullets()
            val h = heading
            if (h == null) intro.addAll(blocks) else sections.add(HelpSection(h, blocks))
            blocks = mutableListOf()
        }

        for (raw in lines.drop(1)) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flushBullets()
                line.startsWith("Last updated:") -> Unit // legal documents only; help has none
                line.startsWith("- ") -> bullets.add(spans(line.removePrefix("- ")))
                isHeading(line) -> {
                    closeSection()
                    heading = headingCase(line)
                }
                else -> {
                    flushBullets()
                    blocks.add(HelpBlock.Paragraph(spans(line)))
                }
            }
        }
        closeSection()
        return HelpDocument(title, intro, sections)
    }

    internal fun isHeading(line: String) = line == line.uppercase() && CAPS_RUN.containsMatchIn(line)

    /** "3. GAME HEALTH DATA" becomes "3. Game health data"; acronyms, words with digits and proper names are kept. */
    internal fun headingCase(line: String): String {
        val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.mapIndexed { i, w ->
            val core = w.trim(',', '.', ':', ';', '(', ')')
            when {
                core in ACRONYMS || core.any { it.isDigit() } -> w
                core in PROPER_NAMES -> w.replace(core, PROPER_NAMES.getValue(core))
                i == 0 || (i == 1 && words[0].trimEnd('.').all { it.isDigit() }) ->
                    w.take(1) + w.drop(1).lowercase()
                else -> w.lowercase()
            }
        }.joinToString(" ")
    }

    /** Inline markers first; the text between them is scanned for bare URLs and email addresses. */
    internal fun spans(text: String): List<HelpSpan> {
        val out = mutableListOf<HelpSpan>()
        var pos = 0
        for (m in MARKER.findAll(text)) {
            autoSpans(text.substring(pos, m.range.first), out)
            if (m.groups[1] != null) out.add(HelpSpan.Bold(m.groupValues[1]))
            else out.add(HelpSpan.Link(m.groupValues[2], m.groupValues[3]))
            pos = m.range.last + 1
        }
        autoSpans(text.substring(pos), out)
        return out
    }

    private fun autoSpans(text: String, out: MutableList<HelpSpan>) {
        var pos = 0
        for (m in AUTO.findAll(text)) {
            if (m.range.first > pos) out.add(HelpSpan.Text(text.substring(pos, m.range.first)))
            val v = m.value
            out.add(if (v.startsWith("http")) HelpSpan.Link(v, v) else HelpSpan.Email(v))
            pos = m.range.last + 1
        }
        if (pos < text.length) out.add(HelpSpan.Text(text.substring(pos)))
    }
}
