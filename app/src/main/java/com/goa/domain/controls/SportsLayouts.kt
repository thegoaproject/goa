package com.goa.domain.controls

import java.util.Locale

/**
 * Button layouts for DOS sports games, which are keyboard games but not "Ctrl = fire" ones.
 *
 * EA Sports titles (FIFA, NHL, Madden, NBA Live) put pass/shoot/lob/sprint on the letter keys; most other sports games
 * use Space, Ctrl and Alt. Values are [com.goa.engine.input.ControllerAction] ids keyed by GamepadButton.key.
 */
object SportsLayouts {
    const val LETTERS_ID = "sports_letters"
    const val CLASSIC_ID = "sports_classic"

    /** A/S/D/Q: pass on S, shoot on D, lob or tackle on A, sprint on Q. */
    val LETTERS: Map<String, String> = mapOf(
        "btn_a" to "key_s", "btn_b" to "key_d", "btn_x" to "key_a", "btn_y" to "key_q",
        "btn_l1" to "key_q", "btn_r1" to "key_q", "btn_l2" to "key_a", "btn_r2" to "key_q",
        "btn_l3" to "pause_menu", "btn_r3" to "pause_menu", "btn_start" to "enter_select", "btn_select" to "pause_menu"
    )

    /** Space, Ctrl and Alt: the common DOS action-button trio. */
    val CLASSIC: Map<String, String> = mapOf(
        "btn_a" to "fire_space", "btn_b" to "fire_ctrl", "btn_x" to "alt_fire", "btn_y" to "enter_select",
        "btn_l1" to "alt_fire", "btn_r1" to "fire_ctrl", "btn_l2" to "alt_fire", "btn_r2" to "fire_ctrl",
        "btn_l3" to "pause_menu", "btn_r3" to "pause_menu", "btn_start" to "enter_select", "btn_select" to "pause_menu"
    )

    fun bindingsFor(layoutId: String): Map<String, String>? = when (layoutId) {
        LETTERS_ID -> LETTERS
        CLASSIC_ID -> CLASSIC
        else -> null
    }

    private val EA_WORDS = listOf("fifa", "nhl", "madden", "nba", "nbalive", "pga", "euro96", "world cup", "worldcup", "actua")
    // "pool of radiance" is an RPG, not billiards
    private val NOT_SPORTS = listOf("pool of radiance", "pools of", "manager", "management", "pinball")
    private val SPORT_WORDS = listOf(
        "soccer", "football", "hockey", "basketball", "baseball", "tennis", "golf", "rugby", "cricket", "boxing", "bowling",
        "wrestling", "skiing", "snowboard", "racquet", "volleyball", "sensible", "fifa", "nhl", "madden", "nba", "pga",
        "olympic", "summer games", "winter games", "track and field", "decathlon", "pool", "billiards", "snooker"
    )

    fun isSports(slug: String?, title: String?): Boolean {
        val text = textOf(slug, title)
        // Management sims ("Soccer Manager", "Premier Manager") are menu and mouse games, not action sports
        if (NOT_SPORTS.any { text.contains(it) }) return false
        return SPORT_WORDS.any { hasWord(text, it) }
    }

    /** Letter-key layouts are the EA Sports convention. */
    fun isEaStyle(slug: String?, title: String?): Boolean {
        val text = textOf(slug, title)
        return EA_WORDS.any { hasWord(text, it) }
    }

    /** The layout to try first for this game: letters for EA Sports titles, Space/Ctrl/Alt for everything else. */
    fun preferredLayoutId(slug: String?, title: String?) = if (isEaStyle(slug, title)) LETTERS_ID else CLASSIC_ID

    /** True when [word] occurs in [text] as a whole word (a plural "s" is allowed): "nba" is not inside "pinball". */
    internal fun hasWord(text: String, word: String): Boolean =
        Regex("(?<![a-z0-9])" + Regex.escape(word) + "s?(?![a-z0-9])").containsMatchIn(text)

    private fun textOf(slug: String?, title: String?) =
        "${slug.orEmpty()} ${title.orEmpty()}".lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
}
