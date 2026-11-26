package com.goa.data.repository

import com.goa.engine.input.ControllerActions
import com.goa.engine.input.GamepadButton
import com.goa.engine.input.StickMode

/**
 * Allowed values for the game patch format. Shared by the published-defaults gate and the AI fix validator so neither
 * can push a setting DOSBox or the input layer doesn't understand.
 */
object PatchSchema {
    val SBTYPES = setOf("sb1", "sb2", "sbpro1", "sbpro2", "sb16", "gb", "none")
    val SBBASES = setOf("220", "240", "260", "280", "2a0", "2c0", "2e0", "300")
    val IRQS = setOf("3", "5", "7", "9", "10", "11", "12")
    val DMAS = setOf("0", "1", "3")
    val HDMAS = setOf("5", "6", "7")
    val OPLMODES = setOf("auto", "cms", "opl2", "dualopl2", "opl3", "opl3gold", "none")
    val OPLEMUS = setOf("default", "compat", "fast", "mame", "nuked")
    val RATES = setOf("8000", "11025", "16000", "22050", "32000", "44100", "48000", "49716")
    val BOOLEANS = setOf("true", "false")
    private val CYCLES = Regex("""^(auto|max|max \d{1,3}%|fixed \d{2,6}|\d{2,6})$""")

    val stickModeIds: Set<String> get() = StickMode.entries.map { it.id }.toSet()
    val buttonKeys: Set<String> get() = GamepadButton.entries.map { it.key }.toSet()

    fun isValidCycles(value: String) = CYCLES.matches(value.trim())

    fun validateProfile(profile: GamePatchProfile): List<String> {
        val errors = mutableListOf<String>()
        val d = profile.dosboxOverrides
        fun check(key: String, value: String, allowed: Set<String>) {
            if (value !in allowed) errors += "$key=$value not in $allowed"
        }
        check("sbtype", d.sbtype, SBTYPES)
        check("sbbase", d.sbbase, SBBASES)
        check("irq", d.irq, IRQS)
        check("dma", d.dma, DMAS)
        check("hdma", d.hdma, HDMAS)
        check("oplmode", d.oplmode, OPLMODES)
        check("oplemu", d.oplemu, OPLEMUS)
        check("rate", d.rate, RATES)
        check("gus", d.gus, BOOLEANS)
        if (!isValidCycles(d.cycles)) errors += "cycles=${d.cycles} is not auto|max|max N%|fixed N|N"

        profile.controlOverrides?.let { c ->
            c.stickMode?.let { if (it !in stickModeIds) errors += "stickMode=$it not in $stickModeIds" }
            c.buttonBindings?.forEach { (button, action) ->
                if (button !in buttonKeys) errors += "button $button not in $buttonKeys"
                if (ControllerActions.findById(action) == null) errors += "$button action $action is not a ControllerActions id"
            }
        }
        return errors
    }
}
