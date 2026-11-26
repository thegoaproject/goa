package com.goa.domain.controls

import com.goa.engine.input.StickMode
import java.util.Locale

enum class GameGenre { SPORTS, FLIGHT, RPG, STRATEGY }

/** A named set of button bindings (GamepadButton.key -> ControllerAction id) plus the stick mode it needs. */
data class GenreLayout(val id: String, val label: String, val stickMode: StickMode, val bindings: Map<String, String>)

/**
 * Per-genre button layouts. They are the conventions of each genre, not facts about a given game, so they serve two
 * jobs: the default for a game with no profile, and what "Fix this game" offers when the controls feel wrong.
 * Sports layouts live in [SportsLayouts].
 */
object GenreLayouts {
    private fun buttons(
        a: String, b: String, x: String, y: String, l1: String, r1: String, l2: String, r2: String,
        l3: String = "pause_menu", r3: String = "pause_menu", start: String = "enter_select", select: String = "esc_menu"
    ) = mapOf(
        "btn_a" to a, "btn_b" to b, "btn_x" to x, "btn_y" to y, "btn_l1" to l1, "btn_r1" to r1,
        "btn_l2" to l2, "btn_r2" to r2, "btn_l3" to l3, "btn_r3" to r3, "btn_start" to start, "btn_select" to select
    )

    /** Most DOS games: Space/Ctrl/Alt for actions, Enter to confirm, Esc for the menu. */
    val GENERIC_KEYS = buttons("fire_space", "fire_ctrl", "alt_fire", "enter_select", "esc_menu", "enter_select", "alt_fire", "fire_ctrl")

    /** Point-and-click: left and right mouse buttons; the D-pad stays on the arrow keys. */
    val MOUSE = buttons("mouse_lclick", "mouse_rclick", "fire_space", "enter_select", "esc_menu", "enter_select", "mouse_rclick", "mouse_lclick")

    private val RPG_KEYS = buttons("enter_select", "esc_menu", "fire_space", "key_i", "key_c", "key_m", "key_s", "key_a", r3 = "key_t")

    // Flight: Space fires, Ctrl/Alt are secondary weapons, +/- is throttle, G gear, B brakes, F flaps
    private val FLIGHT_KEYS = buttons("fire_space", "fire_ctrl", "key_g", "key_b", "key_f", "key_minus", "key_plus", "alt_fire")

    val ALL: List<GenreLayout> = listOf(
        GenreLayout(SportsLayouts.LETTERS_ID, "Sports layout (A / S / D / Q keys)", StickMode.CLASSIC_KEYBOARD, SportsLayouts.LETTERS),
        GenreLayout(SportsLayouts.CLASSIC_ID, "Sports layout (Space / Ctrl / Alt)", StickMode.CLASSIC_KEYBOARD, SportsLayouts.CLASSIC),
        GenreLayout("flight_keys", "Flight keyboard (Space fire, +/- throttle, G gear)", StickMode.CLASSIC_KEYBOARD, FLIGHT_KEYS),
        GenreLayout("rpg_keys", "RPG keys (I inventory, C character, M map)", StickMode.CLASSIC_KEYBOARD, RPG_KEYS),
        GenreLayout("strategy_mouse", "Point-and-click (stick moves the pointer)", StickMode.VIRTUAL_MOUSE, MOUSE),
        GenreLayout("generic_keys", "Keyboard (Space / Ctrl / Alt, Enter)", StickMode.CLASSIC_KEYBOARD, GENERIC_KEYS)
    )

    fun byId(id: String): GenreLayout? = ALL.firstOrNull { it.id == id }

    private val FLIGHT_WORDS = listOf(
        "flight", "red baron", "falcon 3", "falcon at", "falcon 4", "f-16 falcon", "f-15", "f15", "f-117", "f117", "f-19", "wing commander", "x-wing", "xwing",
        "tie fighter", "jet fighter", "strike fighter", "privateer", "comanche", "apache", "gunship", "yeager", "aces of",
        "su-27", "su27", "strike commander", "harrier", "tornado", "spitfire", "battle of britain", "dogfight",
        "lhx", "helicopter", "mig-29", "mig29", "pacific air", "air warrior"
    )
    private val RPG_WORDS = listOf(
        "ultima", "might and magic", "might & magic", "wizardry", "eye of the beholder", "dungeon master", "bard's tale",
        "bards tale", "betrayal at krondor", "krondor", "realms of arkania", "lands of lore", "darklands", "pool of radiance",
        "dungeon hack", "stonekeep", "shadowcaster", "menzoberranzan", "rpg", "dark sun", "darksun", "cyberpunk"
    )
    private val STRATEGY_WORDS = listOf(
        "civilization", "colonization", "command & conquer", "command and conquer", "warcraft", "dune ii", "dune 2",
        "settlers", "simcity", "sim city", "transport tycoon", "theme park", "age of empires", "master of orion", "x-com",
        "ufo enemy", "populous", "panzer general", "lords of the realm", "railroad tycoon", "tycoon", "strategy", "wargame",
        "red alert", "starcraft", "total annihilation", "heroes of might", "stronghold"
    )
    private val NOT_FLIGHT = listOf("street fighter", "streetfighter", "final fight", "mortal", "flight of the amazon")
    // Text adventures that happen to mention a dungeon master
    private val NOT_RPG = listOf("zork")

    fun genreOf(slug: String?, title: String?): GameGenre? {
        val text = "${slug.orEmpty()} ${title.orEmpty()}".lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
        val raw = "${slug.orEmpty()} ${title.orEmpty()}".lowercase(Locale.ROOT).replace('_', ' ') // keeps "f-15"
        return when {
            SportsLayouts.isSports(slug, title) -> GameGenre.SPORTS
            NOT_FLIGHT.none { text.contains(it) } && FLIGHT_WORDS.any { SportsLayouts.hasWord(raw, it) || SportsLayouts.hasWord(text, it) } -> GameGenre.FLIGHT
            NOT_RPG.none { text.contains(it) } && RPG_WORDS.any { SportsLayouts.hasWord(text, it) } -> GameGenre.RPG
            STRATEGY_WORDS.any { SportsLayouts.hasWord(text, it) } -> GameGenre.STRATEGY
            else -> null
        }
    }

    /** Layout ids for [genre], best first. */
    fun layoutIdsFor(genre: GameGenre, slug: String?, title: String?): List<String> = when (genre) {
        GameGenre.SPORTS -> {
            val first = SportsLayouts.preferredLayoutId(slug, title)
            listOf(first, if (first == SportsLayouts.LETTERS_ID) SportsLayouts.CLASSIC_ID else SportsLayouts.LETTERS_ID)
        }
        GameGenre.FLIGHT -> listOf("flight_keys")
        GameGenre.RPG -> listOf("rpg_keys", "generic_keys")
        GameGenre.STRATEGY -> listOf("strategy_mouse", "generic_keys")
    }
}
