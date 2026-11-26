package com.goa.domain.nativeport

import android.content.Context
import com.goa.domain.quake.QuakeSupport
import com.goa.domain.tombraider.TombRaiderSupport
import java.io.File

/**
 * Games GOA can run two ways: on a native port of their own engine (Quake on TyrQuake, Tomb Raider on OpenLara: smooth
 * and sharp, but the port's own menus and options), or as the original DOS game in DOSBox (exactly as in the 90s, but
 * an emulated Pentium manages only 10-40 fps). The player picks per game on its Controls tab; native is the default.
 */
object NativeEngine {

    enum class Port(val engineName: String) {
        TYRQUAKE("TyrQuake"),
        OPENLARA("OpenLara")
    }

    enum class Choice(val id: String, val label: String) {
        NATIVE("native", "Native"),
        DOSBOX("dosbox", "DOSBox")
    }

    /** The native port this install can run on (the core is in the build and the game's files are there), or null. */
    fun portFor(installDir: File): Port? = when {
        QuakeSupport.isAvailable() && QuakeSupport.findMainPak(installDir) != null -> Port.TYRQUAKE
        TombRaiderSupport.isAvailable() && TombRaiderSupport.isTombRaider(installDir) -> Port.OPENLARA
        else -> null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("goa_native_engine", Context.MODE_PRIVATE)

    fun choice(context: Context, gameId: Long): Choice {
        val id = prefs(context).getString("engine_$gameId", null)
        return Choice.entries.firstOrNull { it.id == id } ?: Choice.NATIVE
    }

    fun setChoice(context: Context, gameId: Long, choice: Choice) {
        prefs(context).edit().putString("engine_$gameId", choice.id).apply()
    }

    /** True when this launch should use the native port. */
    fun useNative(context: Context, gameId: Long, port: Port?): Boolean = port != null && choice(context, gameId) == Choice.NATIVE
}
