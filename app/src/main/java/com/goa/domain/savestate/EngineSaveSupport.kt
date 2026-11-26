package com.goa.domain.savestate

/**
 * Which engines have save states (Save Slot / Load Slot, and their Drive copies), and how the others save
 * (contracts/ui-drive-backup.md "In-game menu, games without save states"). Only DOSBox Pure can snapshot a running
 * game: TyrQuake's retro_serialize_size() is 0, and OpenLara and ScummVM games save from their own menus.
 */
enum class SaveEngine(val hasSaveStates: Boolean, val note: String?) {
    DOSBOX(true, null),
    TYRQUAKE(false, "Quake saves from its own menu: press Start, then Save Game."),
    OPENLARA(false, "Tomb Raider saves from its own menu."),
    SCUMMVM(false, "This game keeps its own saves: save from the game's menu (in ScummVM games, press F5).");

    companion object {
        fun of(isTyrQuake: Boolean, isOpenLara: Boolean, isScummVm: Boolean): SaveEngine = when {
            isTyrQuake -> TYRQUAKE
            isOpenLara -> OPENLARA
            isScummVm -> SCUMMVM
            else -> DOSBOX
        }
    }
}
