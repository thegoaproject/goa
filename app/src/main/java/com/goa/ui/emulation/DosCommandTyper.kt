package com.goa.ui.emulation

import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.ui.controls.ime.GoaKeyboardHost
import kotlinx.coroutines.delay

/** Types DOS command lines into the running session as real key presses, each line followed by Enter. */
object DosCommandTyper {
    private const val LEFT_SHIFT = 0x2A
    private const val KEY_MS = 35L
    private const val LINE_MS = 250L

    suspend fun type(lines: List<String>, send: (Int, Boolean) -> Unit = DosBoxEngineBridge::sendKeyEvent) {
        for (line in lines) {
            for (ch in line + "\n") {
                val scancode = GoaKeyboardHost.charToDosScancode(ch).takeIf { it != 0 } ?: continue
                val shift = ch.isUpperCase() || GoaKeyboardHost.isShiftedPunctuation(ch)
                if (shift) send(LEFT_SHIFT, true)
                send(scancode, true)
                delay(KEY_MS)
                send(scancode, false)
                if (shift) send(LEFT_SHIFT, false)
                delay(KEY_MS)
            }
            delay(LINE_MS)
        }
    }
}
