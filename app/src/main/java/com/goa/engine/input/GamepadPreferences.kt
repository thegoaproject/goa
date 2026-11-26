package com.goa.engine.input

import com.goa.data.db.ChangeOrigin
import com.goa.data.db.TrackedSetting
import com.goa.telemetry.ConfigChangeRecorder
import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Identifies configurable hardware gamepad buttons.
 */
enum class GamepadButton(val key: String, val label: String) {
    BUTTON_A("btn_a", "Button A (South)"),
    BUTTON_B("btn_b", "Button B (East)"),
    BUTTON_X("btn_x", "Button X (West)"),
    BUTTON_Y("btn_y", "Button Y (North)"),
    BUTTON_L1("btn_l1", "LB (Left Bumper)"),
    BUTTON_R1("btn_r1", "RB (Right Bumper)"),
    BUTTON_L2("btn_l2", "LT (Left Trigger)"),
    BUTTON_R2("btn_r2", "RT (Right Trigger)"),
    BUTTON_L3("btn_l3", "L3 (Left Stick Click)"),
    BUTTON_R3("btn_r3", "R3 (Right Stick Click)"),
    BUTTON_START("btn_start", "Start Button"),
    BUTTON_SELECT("btn_select", "Select / Back Button"),
    // The D-pad is four buttons like any other: arrows by default (menus, classic games), items in shooters
    DPAD_UP("btn_dpad_up", "D-pad Up"),
    DPAD_DOWN("btn_dpad_down", "D-pad Down"),
    DPAD_LEFT("btn_dpad_left", "D-pad Left"),
    DPAD_RIGHT("btn_dpad_right", "D-pad Right");

    val defaultAction: ControllerAction get() = ControllerActions.defaultBindings[this] ?: ControllerActions.ENTER_SPACE
}

/**
 * Determines how analog thumbsticks are mapped in DOS games.
 */
enum class StickMode(val id: String, val modeIndex: Int, val title: String, val description: String) {
    FPS_DUAL_STICK(
        "fps_dual_stick",
        0,
        "Dual-Stick FPS (Move + Mouse Look)",
        "Left stick moves & strafes; Right stick looks around via mouse yaw. Best for Doom, Duke3D, Quake, Blood."
    ),
    CLASSIC_KEYBOARD(
        "classic_keyboard",
        1,
        "Classic Keyboard (Arrow Keys)",
        "Left stick directly sends DOS Arrow Keys (Up/Down/Left/Right). Best for Commander Keen, Prince of Persia, Rayman, RPGs."
    ),
    NATIVE_GAMEPORT(
        "native_gameport",
        2,
        "Native Gameport Joystick (Port 201h)",
        "Directly emulates IBM PC 15-pin Gameport hardware. Best for Flight Sims & Space Combat (X-Wing, Wing Commander, Descent, Flight Sim)."
    ),
    VIRTUAL_MOUSE(
        "virtual_mouse",
        3,
        "Virtual Mouse (Point & Click Adventures)",
        "Left stick drives smooth analog mouse pointer with acceleration; Right stick provides micro-precision. Best for Point-and-Click Adventures (Beneath a Steel Sky, Flight of the Amazon Queen)."
    ),
    KEYS_POINTER(
        "keys_pointer",
        4,
        "Keys + Pointer (Arrows + Mouse)",
        "Left stick sends arrow keys to walk and turn; Right stick moves the mouse pointer to aim, use and pick up. Best for System Shock and Ultima Underworld."
    );

    companion object {
        fun fromIndex(index: Int): StickMode = values().firstOrNull { it.modeIndex == index } ?: FPS_DUAL_STICK
    }
}

/**
 * Predefined actions that can be assigned to controller buttons in DOS games.
 */
data class ControllerAction(
    val id: String,
    val name: String,
    val description: String,
    val defaultKeyLabel: String,
    val primaryScancode: Int? = null,
    val secondaryScancode: Int? = null,
    val isWeaponNext: Boolean = false,
    val isWeaponPrev: Boolean = false,
    val isPauseMenu: Boolean = false,
    val isMouseLeft: Boolean = false,
    val isMouseRight: Boolean = false,
    /** Toggle: the first press holds the keys down, the next press lets them go (run toggle, crouch toggle). */
    val isLatch: Boolean = false,
    /** Weapon cycling: how many number-key slots the game has (Hexen 4, Doom 7, Blood 10: slot 10 is the 0 key). */
    val weaponSlots: Int = 7
)

object ControllerActions {
    val ENTER_SPACE = ControllerAction(
        id = "enter_space",
        name = "Enter / Space",
        description = "Select in menus, open doors / use in-game",
        defaultKeyLabel = "Enter (0x1C) • Space (0x39)",
        primaryScancode = 0x1C, // Enter
        secondaryScancode = 0x39 // Space
    )

    val ESC_CROUCH = ControllerAction(
        id = "esc_crouch",
        name = "Esc / Crouch (Z)",
        description = "Back / cancel in menus, crouch in Build games",
        defaultKeyLabel = "Esc (0x01) • 'Z' (0x2C)",
        primaryScancode = 0x01, // Esc
        secondaryScancode = 0x2C // 'Z'
    )

    val SPACE_USE = ControllerAction(
        id = "space_use",
        name = "Space (Use / Open)",
        description = "Open doors, flip switches, activate items",
        defaultKeyLabel = "Space (0x39)",
        primaryScancode = 0x39 // Space
    )

    val ENTER_SELECT = ControllerAction(
        id = "enter_select",
        name = "Enter (Select)",
        description = "Select menu items / confirm prompts",
        defaultKeyLabel = "Enter (0x1C)",
        primaryScancode = 0x1C // Enter
    )

    val ESC_MENU = ControllerAction(
        id = "esc_menu",
        name = "Esc (Menu / Cancel)",
        description = "In-game DOS menu / back / cancel",
        defaultKeyLabel = "Esc (0x01)",
        primaryScancode = 0x01 // Esc
    )

    val SPRINT_SHIFT = ControllerAction(
        id = "sprint_shift",
        name = "Run (Shift)",
        description = "Speed / sprint modifier (hold)",
        defaultKeyLabel = "Left + Right Shift",
        // Both Shift keys: Wolfenstein 3-D, Rise of the Triad and Catacomb 3-D run on the RIGHT one only (their released
        // source: buttonscan[bt_run] = sc_RShift), Doom/Build/Quake take either
        primaryScancode = 0x2A, // Left Shift
        secondaryScancode = 0x36 // Right Shift
    )

    // One side only: pinball flippers (Epic Pinball, Pinball World, Psycho Pinball: left Shift = left flipper)
    val LEFT_SHIFT = ControllerAction(
        id = "key_lshift", name = "Left Shift", description = "The left Shift key alone (left flipper in pinball games)",
        defaultKeyLabel = "Left Shift (0x2A)", primaryScancode = 0x2A
    )

    val RIGHT_SHIFT = ControllerAction(
        id = "key_rshift", name = "Right Shift", description = "The right Shift key alone (right flipper in pinball games)",
        defaultKeyLabel = "Right Shift (0x36)", primaryScancode = 0x36
    )

    val CROUCH = ControllerAction(
        id = "crouch",
        name = "Crouch (Z)",
        description = "Crouch / duck in FPS games (left stick click by modern convention)",
        defaultKeyLabel = "'Z' (0x2C)",
        primaryScancode = 0x2C // 'Z'
    )

    // Every action sends one key, so a button never does two things at once: 'A' + '/' made Quake jump and switch
    // weapons ('/' is its next-weapon key), so each engine's jump key is its own action
    val JUMP_BUILD = ControllerAction(
        id = "jump_build",
        name = "Jump (A)",
        description = "Jump in Build engine games (Duke Nukem 3D, Shadow Warrior, Blood, Redneck Rampage)",
        defaultKeyLabel = "'A' (0x1E)",
        primaryScancode = 0x1E // 'A'
    )

    val JUMP_SLASH = ControllerAction(
        id = "jump_slash",
        name = "Jump (/)",
        description = "Jump in Hexen and Strife",
        defaultKeyLabel = "'/' (0x35)",
        primaryScancode = 0x35 // '/'
    )

    // Quake binds Enter to +jump, and its menus select with Enter: one key that jumps in play and confirms in menus
    val JUMP_ENTER = ControllerAction(
        id = "jump_enter",
        name = "Jump / Select (Enter)",
        description = "Jump in Quake and Hexen II, select in their menus",
        defaultKeyLabel = "Enter (0x1C)",
        primaryScancode = 0x1C // Enter
    )

    val NEXT_WEAPON_SLASH = ControllerAction(
        id = "next_weapon_slash",
        name = "Next Weapon (/)",
        description = "Quake and Hexen II's own next-weapon key (impulse 10): skips weapons you don't have",
        defaultKeyLabel = "'/' (0x35)",
        primaryScancode = 0x35 // '/'
    )

    val FIRE_CTRL = ControllerAction(
        id = "fire_ctrl",
        name = "Fire (Ctrl)",
        description = "Primary weapon fire / attack",
        defaultKeyLabel = "Left Ctrl (0x1D)",
        primaryScancode = 0x1D // Left Ctrl
    )

    val ALT_FIRE = ControllerAction(
        id = "alt_fire",
        name = "Alt Fire / Strafe (Alt)",
        description = "Secondary attack or classic strafe key",
        defaultKeyLabel = "Left Alt (0x38)",
        primaryScancode = 0x38 // Left Alt
    )

    val NEXT_WEAPON = ControllerAction(
        id = "next_weapon",
        name = "Next Weapon",
        description = "Cycle to the next weapon slot (number keys 1-7)",
        defaultKeyLabel = "Keys '1'..'7'",
        isWeaponNext = true
    )

    val PREV_WEAPON = ControllerAction(
        id = "prev_weapon",
        name = "Previous Weapon",
        description = "Cycle to the previous weapon slot (number keys 7-1)",
        defaultKeyLabel = "Keys '7'..'1'",
        isWeaponPrev = true
    )

    // Both Shift keys: engines differ on which one runs (Doom takes either, Rise of the Triad and Wolfenstein 3D the right)
    val RUN_TOGGLE = ControllerAction(
        id = "run_toggle",
        name = "Run Toggle (Shift)",
        description = "Press to keep running, press again to walk",
        defaultKeyLabel = "Left + Right Shift (toggle)",
        primaryScancode = 0x2A,
        secondaryScancode = 0x36,
        isLatch = true
    )

    val CROUCH_TOGGLE = ControllerAction(
        id = "crouch_toggle",
        name = "Crouch Toggle (Z)",
        description = "Press to stay crouched, press again to stand (games that crouch on Z: Duke Nukem 3D, Blood, Shadow Warrior)",
        defaultKeyLabel = "'Z' (toggle)",
        primaryScancode = 0x2C,
        isLatch = true
    )

    /** "toggle_key_c": a letter key held down until pressed again, for games that crouch (or run) on another key. */
    private const val TOGGLE_PREFIX = "toggle_"

    private fun toggleOf(base: ControllerAction) = base.copy(
        id = TOGGLE_PREFIX + base.id,
        name = "${base.name} (toggle)",
        description = "Press to hold ${base.defaultKeyLabel} down, press again to let go",
        defaultKeyLabel = "${base.defaultKeyLabel} (toggle)",
        isLatch = true
    )

    val AUTOMAP_TAB = ControllerAction(
        id = "automap_tab",
        name = "Automap (Tab)",
        description = "Toggle overhead automap",
        defaultKeyLabel = "Tab (0x0F)",
        primaryScancode = 0x0F // Tab
    )

    val PAUSE_MENU = ControllerAction(
        id = "pause_menu",
        name = "Pause Menu",
        description = "Open Goa in-game pause dialog",
        defaultKeyLabel = "Goa Pause Menu",
        isPauseMenu = true
    )

    /**
     * A button the game has no use for. Profiles put it on spare buttons instead of repeating a key: a repeated Esc is
     * "Exit race" in Deathtrack, so a stray shoulder press quit the race.
     */
    val NOT_USED = ControllerAction(
        id = "none",
        name = "Not used",
        description = "This game has nothing on this button",
        defaultKeyLabel = "-"
    )

    val QUICK_SAVE = ControllerAction(
        id = "quick_save",
        name = "Quick Save (F6)",
        description = "Trigger DOS quick save",
        defaultKeyLabel = "F6 (0x40)",
        primaryScancode = 0x40 // F6
    )

    val QUICK_LOAD = ControllerAction(
        id = "quick_load",
        name = "Quick Load (F9)",
        description = "Trigger DOS quick load",
        defaultKeyLabel = "F9 (0x43)",
        primaryScancode = 0x43 // F9
    )

    val JUMP_CTRL = ControllerAction(
        id = "jump_ctrl",
        name = "Jump (Ctrl)",
        description = "Jump in Commander Keen & classic platformers",
        defaultKeyLabel = "Left Ctrl (0x1D)",
        primaryScancode = 0x1D // Left Ctrl
    )

    val POGO_ALT = ControllerAction(
        id = "pogo_alt",
        name = "Pogo (Alt)",
        description = "Toggle pogo stick in Commander Keen",
        defaultKeyLabel = "Left Alt (0x38)",
        primaryScancode = 0x38 // Left Alt
    )

    val FIRE_SPACE = ControllerAction(
        id = "fire_space",
        name = "Fire (Space)",
        description = "Fire raygun / weapon in Commander Keen & platformers",
        defaultKeyLabel = "Space (0x39)",
        primaryScancode = 0x39 // Space
    )

    val STATUS_F1 = ControllerAction(
        id = "status_f1",
        name = "Status / Help (F1)",
        description = "Status screen in Keen & classic DOS games",
        defaultKeyLabel = "F1 (0x3B)",
        primaryScancode = 0x3B // F1
    )

    val MOUSE_LCLICK = ControllerAction(
        id = "mouse_lclick",
        name = "Left Mouse Click",
        description = "Left mouse button click (primary interaction / walk / examine)",
        defaultKeyLabel = "Mouse Left",
        isMouseLeft = true
    )

    val MOUSE_RCLICK = ControllerAction(
        id = "mouse_rclick",
        name = "Right Mouse Click",
        description = "Right mouse button click (secondary interaction / use / menu)",
        defaultKeyLabel = "Mouse Right",
        isMouseRight = true
    )

    // Plain letter keys: sports, strategy and sim games bind actions to A/S/D/Q… rather than Ctrl/Alt/Space
    private fun letterKey(letter: Char, scancode: Int) = ControllerAction(
        id = "key_$letter",
        name = "Key ${letter.uppercaseChar()}",
        description = "The ${letter.uppercaseChar()} key, for games that bind actions to letters (sports, strategy, sims)",
        defaultKeyLabel = "${letter.uppercaseChar()} (0x${scancode.toString(16).uppercase().padStart(2, '0')})",
        primaryScancode = scancode
    )

    val KEY_PLUS = ControllerAction(
        id = "key_plus",
        name = "Key + (numpad)",
        description = "Numpad plus: throttle up in flight sims, zoom or speed up in many games",
        defaultKeyLabel = "Numpad + (0x4E)",
        primaryScancode = 0x4E
    )

    val KEY_MINUS = ControllerAction(
        id = "key_minus",
        name = "Key - (numpad)",
        description = "Numpad minus: throttle down in flight sims, zoom or slow down in many games",
        defaultKeyLabel = "Numpad - (0x4A)",
        primaryScancode = 0x4A
    )

    /** F2-F5, F7, F8, F10-F12 (F1, F6 and F9 are STATUS_F1, QUICK_SAVE and QUICK_LOAD): sports, sims and strategy hotkeys. */
    val functionKeys: List<ControllerAction> = listOf(
        // F1/F6/F9 also have named actions (Help, Quick Save, Quick Load); these plain ones are for games that use those
        // keys for something else (Dragon Strike: F6 = air brakes)
        1 to 0x3B, 2 to 0x3C, 3 to 0x3D, 4 to 0x3E, 5 to 0x3F, 6 to 0x40, 7 to 0x41, 8 to 0x42, 9 to 0x43, 10 to 0x44, 11 to 0x57, 12 to 0x58
    ).map { (n, scancode) ->
        ControllerAction(
            id = "key_f$n", name = "Key F$n", description = "The F$n function key, for games that bind actions to it",
            defaultKeyLabel = "F$n (0x${scancode.toString(16).uppercase().padStart(2, '0')})", primaryScancode = scancode
        )
    }

    /**
     * Numeric keypad keys. DOS games tell them apart from the grey arrows (Mortal Kombat 3: player 1 punches on keypad
     * 4/1, kicks on 6/3, blocks on 2, runs on 0), so they are sent as libretro's own keypad codes (RETROK_KP0 = 256...),
     * which the core passes straight through.
     */
    // Arrow keys, one per action: the D-pad's default (menus and classic games keep the D-pad they always had)
    val ARROW_UP = ControllerAction(id = "arrow_up", name = "Up Arrow", description = "The Up arrow key", defaultKeyLabel = "Up (0x48)", primaryScancode = 0x48)
    val ARROW_DOWN = ControllerAction(id = "arrow_down", name = "Down Arrow", description = "The Down arrow key", defaultKeyLabel = "Down (0x50)", primaryScancode = 0x50)
    val ARROW_LEFT = ControllerAction(id = "arrow_left", name = "Left Arrow", description = "The Left arrow key", defaultKeyLabel = "Left (0x4B)", primaryScancode = 0x4B)
    val ARROW_RIGHT = ControllerAction(id = "arrow_right", name = "Right Arrow", description = "The Right arrow key", defaultKeyLabel = "Right (0x4D)", primaryScancode = 0x4D)

    private fun plainKey(id: String, name: String, label: String, scancode: Int, what: String) = ControllerAction(
        id = id, name = name, description = what,
        defaultKeyLabel = "$label (0x${scancode.toString(16).uppercase().padStart(2, '0')})", primaryScancode = scancode
    )

    // Punctuation and navigation keys shooters put items and weapon cycling on (Duke 3D and Shadow Warrior: ' next
    // weapon, ; previous, ` quick kick, [ ] inventory; Strife: Insert / Delete inventory)
    val punctuationKeys: List<ControllerAction> = listOf(
        plainKey("key_apostrophe", "Key '", "'", 0x28, "The ' key (next weapon in Duke Nukem 3D and Shadow Warrior)"),
        plainKey("key_semicolon", "Key ;", ";", 0x27, "The ; key (previous weapon in Duke Nukem 3D and Shadow Warrior)"),
        plainKey("key_backquote", "Key `", "`", 0x29, "The ` key (quick kick in Duke Nukem 3D)"),
        plainKey("key_lbracket", "Key [", "[", 0x1A, "The [ key (inventory left in Heretic, Hexen, Duke Nukem 3D, Blood)"),
        plainKey("key_rbracket", "Key ]", "]", 0x1B, "The ] key (inventory right in Heretic, Hexen, Duke Nukem 3D, Blood)"),
        plainKey("key_backslash", "Key \\", "\\", 0x2B, "The \\ key"),
        plainKey("key_insert", "Insert", "Insert", 0x52, "The Insert key (inventory left in Strife)"),
        plainKey("key_delete", "Delete", "Delete", 0x53, "The Delete key (inventory right in Strife)")
    )

    // Top-row number keys: pick one weapon straight away (R1 = grenade: Duke 3D's pipe bombs are 6, Quake's grenade launcher 6)
    val digitKeys: List<ControllerAction> = (1..10).map { n ->
        val digit = n % 10
        plainKey("key_$digit", "Key $digit", "$digit", if (digit == 0) 0x0B else digit + 1, "The top-row $digit key (weapon slot $digit)")
    }

    val keypadKeys: List<ControllerAction> = (0..9).map { n ->
        ControllerAction(
            id = "key_kp$n", name = "Keypad $n", description = "The numeric keypad's $n key",
            defaultKeyLabel = "Keypad $n", primaryScancode = 256 + n
        )
    }

    val letterKeys: List<ControllerAction> = listOf(
        letterKey('a', 0x1E),
        letterKey('b', 0x30),
        letterKey('c', 0x2E),
        letterKey('d', 0x20),
        letterKey('e', 0x12),
        letterKey('f', 0x21),
        letterKey('g', 0x22),
        letterKey('h', 0x23),
        letterKey('i', 0x17),
        letterKey('j', 0x24),
        letterKey('k', 0x25),
        letterKey('l', 0x26),
        letterKey('m', 0x32),
        letterKey('n', 0x31),
        letterKey('o', 0x18),
        letterKey('p', 0x19),
        letterKey('q', 0x10),
        letterKey('r', 0x13),
        letterKey('s', 0x1F),
        letterKey('t', 0x14),
        letterKey('u', 0x16),
        letterKey('v', 0x2F),
        letterKey('w', 0x11),
        letterKey('x', 0x2D),
        letterKey('y', 0x15),
        letterKey('z', 0x2C)
    )

    val allActions: List<ControllerAction> = listOf(
        NOT_USED,
        ENTER_SPACE,
        ESC_CROUCH,
        FIRE_CTRL,
        JUMP_CTRL,
        POGO_ALT,
        FIRE_SPACE,
        SPACE_USE,
        SPRINT_SHIFT,
        RUN_TOGGLE,
        LEFT_SHIFT,
        RIGHT_SHIFT,
        CROUCH,
        CROUCH_TOGGLE,
        JUMP_BUILD,
        JUMP_SLASH,
        JUMP_ENTER,
        NEXT_WEAPON,
        NEXT_WEAPON_SLASH,
        PREV_WEAPON,
        AUTOMAP_TAB,
        STATUS_F1,
        ESC_MENU,
        ENTER_SELECT,
        ALT_FIRE,
        PAUSE_MENU,
        QUICK_SAVE,
        QUICK_LOAD,
        MOUSE_LCLICK,
        MOUSE_RCLICK
    ) + letterKeys + listOf(KEY_PLUS, KEY_MINUS) + functionKeys + keypadKeys + punctuationKeys + digitKeys +
        listOf(ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT)

    /** "next_weapon_4" / "prev_weapon_10": weapon cycling over the game's own number of slots (2 to 10). */
    private val SLOTTED_WEAPON = Regex("""(next|prev)_weapon_(\d+)""")

    private fun slottedWeapon(id: String): ControllerAction? {
        val m = SLOTTED_WEAPON.matchEntire(id) ?: return null
        val slots = m.groupValues[2].toInt().takeIf { it in 2..10 } ?: return null
        val base = if (m.groupValues[1] == "next") NEXT_WEAPON else PREV_WEAPON
        val last = if (slots == 10) "0" else "$slots"
        return base.copy(
            id = id,
            name = "${base.name} (1-$last)",
            description = "Cycle through weapon slots 1-$last",
            defaultKeyLabel = if (base === NEXT_WEAPON) "Keys '1'..'$last'" else "Keys '$last'..'1'",
            weaponSlots = slots
        )
    }

    fun findById(id: String): ControllerAction? = allActions.firstOrNull { it.id == id }
        ?: slottedWeapon(id)
        ?: id.takeIf { it.startsWith(TOGGLE_PREFIX) }?.let { base ->
            letterKeys.firstOrNull { it.id == base.removePrefix(TOGGLE_PREFIX) }?.let(::toggleOf)
        }

    val defaultBindings: Map<GamepadButton, ControllerAction> = mapOf(
        GamepadButton.BUTTON_A to ENTER_SPACE,
        GamepadButton.BUTTON_B to ESC_MENU,
        GamepadButton.BUTTON_X to JUMP_BUILD,
        GamepadButton.BUTTON_Y to NEXT_WEAPON,
        GamepadButton.BUTTON_L1 to PREV_WEAPON,
        GamepadButton.BUTTON_R1 to NEXT_WEAPON,
        GamepadButton.BUTTON_L2 to SPRINT_SHIFT,
        GamepadButton.BUTTON_R2 to FIRE_CTRL,
        GamepadButton.BUTTON_L3 to CROUCH_TOGGLE,
        GamepadButton.BUTTON_R3 to RUN_TOGGLE,
        GamepadButton.BUTTON_START to ESC_MENU,
        GamepadButton.BUTTON_SELECT to AUTOMAP_TAB,
        GamepadButton.DPAD_UP to ARROW_UP,
        GamepadButton.DPAD_DOWN to ARROW_DOWN,
        GamepadButton.DPAD_LEFT to ARROW_LEFT,
        GamepadButton.DPAD_RIGHT to ARROW_RIGHT
    )
}

/**
 * Persistent manager for in-game controller mappings, supporting per-game overrides
 * and global defaults via SharedPreferences.
 */
object GamepadPreferences {
    private const val PREFS_NAME = "goa_gamepad_mappings"

    private fun getPrefsFile(context: Context): java.io.File {
        return java.io.File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
    }

    private fun readStringFromDisk(context: Context, key: String): String? {
        return try {
            val file = getPrefsFile(context)
            if (!file.exists()) return null
            val text = file.readText()
            val pattern = Regex("""<string name="$key">([^<]+)</string>""")
            // SharedPreferences stores values XML-escaped; decode so "Revolution & Scumm" doesn't render as "&amp;"
            pattern.find(text)?.groupValues?.get(1)
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.replace("&apos;", "'")
                ?.replace("&amp;", "&")
        } catch (_: Exception) {
            null
        }
    }

    private fun readBooleanFromDisk(context: Context, key: String): Boolean? {
        return try {
            val file = getPrefsFile(context)
            if (!file.exists()) return null
            val text = file.readText()
            val pattern = Regex("""<boolean name="$key" value="([^"]+)" />""")
            pattern.find(text)?.groupValues?.get(1)?.toBoolean()
        } catch (_: Exception) {
            null
        }
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun prefKey(gameId: Long?, button: GamepadButton): String {
        return if (gameId != null && gameId > 0) "map_${gameId}_${button.key}" else "map_global_${button.key}"
    }

    /**
     * Loads the effective controller mappings for the given game (or global defaults).
     */
    fun loadMappings(context: Context, gameId: Long?): Map<GamepadButton, ControllerAction> {
        val prefs = getPrefs(context)
        val result = mutableMapOf<GamepadButton, ControllerAction>()

        for (btn in GamepadButton.values()) {
            val key = prefKey(gameId, btn)
            val globalKey = prefKey(null, btn)

            val actionId = readStringFromDisk(context, key)
                ?: readStringFromDisk(context, globalKey)
                ?: prefs.getString(key, null)
                ?: prefs.getString(globalKey, null)
                ?: ControllerActions.defaultBindings[btn]?.id

            val action = actionId?.let { ControllerActions.findById(it) }
                ?: ControllerActions.defaultBindings[btn]
                ?: ControllerActions.ENTER_SPACE

            result[btn] = action
        }
        // Dual-stick shooters: right stick click toggles Run. Older presets and profiles put Use, Run (hold) or the pause
        // menu there; those give way unless the player chose R3's action themselves. (Crouch is NOT forced onto L3: most
        // DOS shooters, Doom and Wolfenstein included, have no crouch, so a profile only binds it where the game has one.)
        val r3 = result[GamepadButton.BUTTON_R3]
        if ((r3 == ControllerActions.SPACE_USE || r3 == ControllerActions.SPRINT_SHIFT || r3 == ControllerActions.PAUSE_MENU) &&
            getStickMode(context, gameId) == StickMode.FPS_DUAL_STICK &&
            !prefs.getBoolean(userSetKey(gameId, GamepadButton.BUTTON_R3), false)
        ) {
            result[GamepadButton.BUTTON_R3] = ControllerActions.RUN_TOGGLE
        }
        return result
    }

    private fun userSetKey(gameId: Long?, button: GamepadButton) = "user_set_" + prefKey(gameId, button)

    private fun labelKey(gameId: Long?, button: GamepadButton) = "label_" + prefKey(gameId, button)
    private const val LABEL_SEP = '\t'

    /**
     * The game's own name for what [button] does ("Jump"), from the profile that set it, while the button still does
     * [action]; null otherwise (then show [ControllerAction.name]).
     */
    fun gameLabel(context: Context, gameId: Long?, button: GamepadButton, action: ControllerAction): String? {
        val stored = getPrefs(context).getString(labelKey(gameId, button), null) ?: return null
        val actionId = stored.substringBefore(LABEL_SEP)
        return stored.substringAfter(LABEL_SEP, "").takeIf { actionId == action.id && it.isNotBlank() }
    }

    /**
     * Saves a customized mapping for a specific button.
     */
    fun saveMapping(context: Context, gameId: Long?, button: GamepadButton, action: ControllerAction) {
        val prefs = getPrefs(context)
        prefs.edit().putString(prefKey(gameId, button), action.id).commit()
    }

    /**
     * Player-initiated remap: saves it, marks the game as player-customised and records the change for game health
     * telemetry. Presets and AI fixes use [saveMapping] directly so they aren't counted as player changes.
     */
    fun saveUserMapping(context: Context, gameId: Long?, button: GamepadButton, action: ControllerAction, origin: ChangeOrigin) {
        val previous = loadMappings(context, gameId)[button]?.id ?: ""
        saveMapping(context, gameId, button, action)
        getPrefs(context).edit().putBoolean(userSetKey(gameId, button), true).apply()
        markUserCustomized(context, gameId)
        ConfigChangeRecorder.record(context, gameId, TrackedSetting.buttonSetting(button.name), previous, action.id, origin)
    }

    /** Player-initiated stick mode change; see [saveUserMapping]. */
    fun setUserStickMode(context: Context, gameId: Long?, mode: StickMode, origin: ChangeOrigin) {
        val previous = getStickMode(context, gameId).id
        setStickMode(context, gameId, mode)
        markUserCustomized(context, gameId)
        ConfigChangeRecorder.record(context, gameId, TrackedSetting.STICK_MODE, previous, mode.id, origin)
    }

    /**
     * Resets mappings for a specific game (or global defaults) to factory preset.
     */
    fun resetMappings(context: Context, gameId: Long?) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for (btn in GamepadButton.values()) {
            editor.remove(prefKey(gameId, btn))
        }
        val userCustomizedKey = if (gameId != null && gameId > 0) "user_customized_$gameId" else "user_customized_global"
        editor.remove(userCustomizedKey)
        editor.remove(sourceTagKey(gameId))      // the layout's origin is decided again by the next resolve
        editor.commit()
    }

    /**
     * Returns the active analog stick interpretation mode for the given game (or global fallback).
     */
    fun getStickMode(context: Context, gameId: Long?): StickMode {
        val key = if (gameId != null && gameId > 0) "stick_mode_$gameId" else "stick_mode_global"
        val globalKey = "stick_mode_global"
        val diskMode = readStringFromDisk(context, key) ?: readStringFromDisk(context, globalKey)
        if (diskMode != null) {
            val mode = StickMode.values().firstOrNull { it.id == diskMode }
            if (mode != null) return mode
        }
        val prefs = getPrefs(context)
        val modeId = prefs.getString(key, null) ?: prefs.getString(globalKey, null) ?: StickMode.FPS_DUAL_STICK.id
        return StickMode.values().firstOrNull { it.id == modeId } ?: StickMode.FPS_DUAL_STICK
    }

    /**
     * Stores the preferred analog stick interpretation mode for the given game.
     */
    fun setStickMode(context: Context, gameId: Long?, mode: StickMode) {
        val prefs = getPrefs(context)
        val key = if (gameId != null && gameId > 0) "stick_mode_$gameId" else "stick_mode_global"
        prefs.edit().putString(key, mode.id).commit()
    }

    /**
     * Returns true if custom mappings exist for the given game or globally.
     */
    fun hasCustomMappings(context: Context, gameId: Long?): Boolean {
        val prefs = getPrefs(context)
        return GamepadButton.values().any { btn ->
            readStringFromDisk(context, prefKey(gameId, btn)) != null || prefs.contains(prefKey(gameId, btn))
        }
    }

    /**
     * Returns true if the user has manually customized button mappings or stick mode.
     */
    fun hasUserCustomizedControls(context: Context, gameId: Long?): Boolean {
        val userCustomizedKey = if (gameId != null && gameId > 0) "user_customized_$gameId" else "user_customized_global"
        val diskVal = readBooleanFromDisk(context, userCustomizedKey)
        if (diskVal != null) return diskVal
        val prefs = getPrefs(context)
        return prefs.getBoolean(userCustomizedKey, false)
    }

    fun markUserCustomized(context: Context, gameId: Long?) {
        val prefs = getPrefs(context)
        val userCustomizedKey = if (gameId != null && gameId > 0) "user_customized_$gameId" else "user_customized_global"
        // The origin badge becomes "Custom": these are the player's own mappings now, not a profile's or a fallback's
        prefs.edit().putBoolean(userCustomizedKey, true).putString(sourceTagKey(gameId), CUSTOM_SOURCE_TAG).commit()
    }

    /** Origin tag for a game whose controls the player changed (parsed by ControlsTier as the CUSTOM tier). */
    const val CUSTOM_SOURCE_TAG = "CUSTOM: Your own button mappings"

    private fun sourceTagKey(gameId: Long?) = if (gameId != null && gameId > 0) "control_source_$gameId" else "control_source_global"

    fun getSourceTag(context: Context, gameId: Long?): String? {
        val key = if (gameId != null && gameId > 0) "control_source_$gameId" else "control_source_global"
        val diskTag = readStringFromDisk(context, key)
        if (diskTag != null) return diskTag
        val prefs = getPrefs(context)
        return prefs.getString(key, null)
    }

    /**
     * The action whose key centres this game's view after the right stick stops looking up or down (from its profile's
     * lookRecenterKey), or null. Kept apart from the button mappings, so a player's own layout keeps it.
     */
    fun setLookRecenterKey(context: Context, gameId: Long, actionId: String?) {
        if (gameId <= 0) return
        getPrefs(context).edit().apply {
            if (actionId != null) putString("look_recenter_$gameId", actionId) else remove("look_recenter_$gameId")
        }.apply()
    }

    fun getLookRecenterKey(context: Context, gameId: Long): String? =
        if (gameId <= 0) null else getPrefs(context).getString("look_recenter_$gameId", null)

    /** The action whose key this game holds for mouse look (its profile's mouseLookKey), or null. Not a button either. */
    fun setMouseLookKey(context: Context, gameId: Long, actionId: String?) {
        if (gameId <= 0) return
        getPrefs(context).edit().apply {
            if (actionId != null) putString("mouse_look_key_$gameId", actionId) else remove("mouse_look_key_$gameId")
        }.apply()
    }

    fun getMouseLookKey(context: Context, gameId: Long): String? =
        if (gameId <= 0) null else getPrefs(context).getString("mouse_look_key_$gameId", null)

    fun setSourceTag(context: Context, gameId: Long?, tag: String) {
        val prefs = getPrefs(context)
        val key = if (gameId != null && gameId > 0) "control_source_$gameId" else "control_source_global"
        prefs.edit().putString(key, tag).commit()
    }

    /**
     * Applies preset control overrides (e.g. from GamePatchProfile or AI resolver) without
     * overwriting manual player customizations unless overwriteUser is true.
     */
    fun applyControlOverrides(
        context: Context,
        gameId: Long,
        overrides: com.goa.data.repository.ControlPatchOverrides,
        overwriteUser: Boolean = false,
        sourceTag: String? = null
    ) {
        if (!overwriteUser && hasUserCustomizedControls(context, gameId)) return

        overrides.stickMode?.let { modeId ->
            val mode = StickMode.values().firstOrNull { it.id == modeId }
            if (mode != null) {
                setStickMode(context, gameId, mode)
            }
        }

        overrides.buttonBindings?.forEach { (btnKey, actionId) ->
            val btn = GamepadButton.values().firstOrNull {
                it.name.equals(btnKey, ignoreCase = true) || it.key.equals(btnKey, ignoreCase = true)
            }
            val action = ControllerActions.findById(actionId)
            if (btn != null && action != null) {
                saveMapping(context, gameId, btn, action)
                // the game's own name for it, kept with the action it describes (a later remap drops it)
                val label = overrides.buttonLabels?.get(btnKey)
                getPrefs(context).edit().apply {
                    if (label != null) putString(labelKey(gameId, btn), action.id + LABEL_SEP + label) else remove(labelKey(gameId, btn))
                }.apply()
            }
        }

        sourceTag?.let { setSourceTag(context, gameId, it) }
    }
}

