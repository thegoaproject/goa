package com.goa.domain.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One video filter as built by scripts/filters/build-filters.py into assets/filters/<id>/filter.json.
 * Format: specs/008-shader-filters/contracts/filter-bundle.md.
 */
@Serializable
data class FilterBundle(
    val format: Int,
    val id: String,
    val name: String,
    val description: String,
    val speed: String,
    val historyDepth: Int = 0,
    val textures: List<FilterTexture> = emptyList(),
    val passes: List<FilterPass>,
    val parameters: List<FilterParameter> = emptyList(),
    val credits: List<FilterCredit>
) {
    companion object {
        const val FORMAT = 1
        val SPEEDS = setOf("LIGHT", "MEDIUM", "HEAVY", "UNMEASURED")

        /** Licences GOA (GPL-3.0-or-later) can ship filters under (FR-018). */
        val LICENCES = setOf(
            "Public domain", "MIT", "BSD-3-Clause", "GPL-2.0-or-later", "GPL-3.0-or-later", "GPL (any version)"
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates; throws [IllegalArgumentException] with the reason when the bundle isn't usable. */
        fun parse(text: String): FilterBundle {
            val b = json.decodeFromString(serializer(), text)
            require(b.format == FORMAT) { "${b.id}: bundle format ${b.format}, expected $FORMAT" }
            require(b.id.matches(Regex("[a-z0-9-]{1,32}"))) { "bad filter id '${b.id}'" }
            require(b.speed in SPEEDS) { "${b.id}: speed ${b.speed}" }
            require(b.passes.isNotEmpty()) { "${b.id}: no passes" }
            require(b.credits.isNotEmpty()) { "${b.id}: no credits" }
            b.credits.forEach { require(it.licence in LICENCES) { "${b.id}: licence '${it.licence}' isn't GPL-3.0-compatible" } }
            b.parameters.forEach { p ->
                require(p.step > 0f) { "${b.id}: parameter ${p.id} step ${p.step}" }
                require(p.min <= p.default && p.default <= p.max) { "${b.id}: parameter ${p.id} default ${p.default} outside ${p.min}..${p.max}" }
            }
            return b
        }
    }
}

@Serializable
data class FilterTexture(
    val name: String,
    val file: String,
    val linear: Boolean = false,
    val wrap: String = "clamp_to_edge",
    val mipmap: Boolean = false
)

@Serializable
data class FilterScale(val type: String, val factor: Float = 1f)

@Serializable
data class FilterUniform(val name: String, val type: String)

@Serializable
data class FilterPass(
    val vertex: String,
    val fragment: String,
    val alias: String? = null,
    val scaleX: FilterScale,
    val scaleY: FilterScale,
    val filterLinear: Boolean = true,
    val wrap: String = "clamp_to_edge",
    val mipmapInput: Boolean = false,
    val format: String = "RGBA8",
    val frameCountMod: Int = 0,
    val uniforms: Map<String, FilterUniform> = emptyMap(),
    val samplers: Map<String, String> = emptyMap()
)

@Serializable
data class FilterParameterUniform(val pass: Int, val name: String)

@Serializable
data class FilterParameter(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val visible: Boolean = true,
    val uniforms: List<FilterParameterUniform> = emptyList()
)

@Serializable
data class FilterCredit(val component: String, val authors: String, val licence: String, val source: String)
