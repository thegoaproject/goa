package com.goa.domain.scan

import java.util.Locale

/**
 * Turns a file or folder name from a game collection into a clean title, release year and slug.
 *
 * "Operation Body Count (1994)(Capstone Software)(Rev).7z" -> title "Operation Body Count", year 1994, a revision.
 * "setup_alone_in_the_dark_1.0_(28191).exe" -> "Alone In The Dark". "Legend Of Kyrandia, The (1992)" -> "The Legend Of Kyrandia".
 * The slug has no year or publisher, so harvested profile patterns such as `alone_in_the_dark` still match it.
 */
object FilenameTitleParser {
    data class Parsed(
        val title: String,
        val year: Int?,
        val slug: String,
        /** A revised or patched dump of a game that usually also exists unrevised: the grouping prefers the other one. */
        val isRev: Boolean,
        /** Same for every file of the same game: lower-case letters and digits only, without a leading article. */
        val groupKey: String
    )

    private val KNOWN_EXTENSIONS = setOf("7z", "zip", "exe", "com", "bat", "sh", "rar", "iso", "cue", "bin", "img", "arj", "ace", "lha", "gz", "tar")
    private val GROUP = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
    private val YEAR = Regex("""^(19[89]\d|20[0-3]\d)$""")
    private val REV_GROUP = Regex("""(?i)^(rev(ision)?(\b|\d|\s|_).*|rev|fixed|patched|fix|hack(ed)?)$""")
    private val TRAILING_ARTICLE = Regex("""^(.*?),\s*(The|A|An)$""", RegexOption.IGNORE_CASE)
    private val LEADING_ARTICLE = Regex("""^(the|a|an)(?=[a-z0-9]{2,})""")

    /** @param stripExtension false for folder names, where a dot is part of the name ("Doom 1.2"). */
    fun parse(rawName: String, stripExtension: Boolean = true): Parsed {
        var name = rawName.trim()
        if (stripExtension) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in KNOWN_EXTENSIONS) name = name.substringBeforeLast('.')
        }

        // (1992) (Publisher) (Rev) [!] {tags}: read what is useful, drop the rest
        var year: Int? = null
        var isRev = false
        for (m in GROUP.findAll(name)) {
            val inner = m.groupValues[1].trim()
            if (year == null && YEAR.matches(inner)) year = inner.toInt()
            if (REV_GROUP.matches(inner)) isRev = true
        }
        name = name.replace(GROUP, " ").trim()

        // GOG installers: setup_<title>_<version>_(<build>).exe
        val gogStyle = Regex("(?i)^setup_").containsMatchIn(name) || Regex("(?i)_(setup|gog)$").containsMatchIn(name.trimEnd('_', ' '))
        name = name.replace(Regex("(?i)^setup_"), "")
        name = name.trimEnd('_', ' ', '-')
            .replace(Regex("(?i)_setup$"), "")
            .replace(Regex("(?i)_gog$"), "")
            .replace(Regex("(?i)_en$"), "")
        if (gogStyle) name = name.replace(Regex("""[_ ]v?\d+(\.\d+)+\s*$"""), "")

        // Separators become spaces, but a dot between digits is a version ("Tyrian 2.1", "Doom 1.2")
        name = name.replace(Regex("""[-_]|(?<!\d)\.|\.(?!\d)"""), " ").replace(Regex("""\s+"""), " ").trim()
        name = TRAILING_ARTICLE.matchEntire(name)?.let { "${it.groupValues[2]} ${it.groupValues[1]}" } ?: name

        // All-lowercase names (file names made by installers) get Title Case; any capitals are deliberate ("FIFA 97")
        val hasUpper = name.any { it.isUpperCase() }
        val title = (if (hasUpper) name else name.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }).ifBlank { "Imported DOS Game" }

        val slug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_").replace(Regex("_+"), "_").trim('_')
        val alnum = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return Parsed(title, year, slug, isRev, alnum.replace(LEADING_ARTICLE, ""))
    }
}
