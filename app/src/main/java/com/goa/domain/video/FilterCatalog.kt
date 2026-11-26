package com.goa.domain.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A filter read from assets, ready to hand to the renderer (DosBoxEngineBridge.setFilter). */
class LoadedFilter(
    val bundle: FilterBundle,
    val json: String,
    val vertexSources: Array<String>,
    val fragmentSources: Array<String>,
    val lutNames: Array<String>,
    /** RGBA8, unpremultiplied, direct buffers in [lutNames] order. */
    val lutPixels: Array<ByteBuffer>,
    /** width, height per LUT, flattened. */
    val lutSizes: IntArray
)

/**
 * The video filters bundled in assets/filters/<id>/ (built by scripts/filters/build-filters.py). "off" is built in: the
 * plain picture, no bundle.
 */
class FilterCatalog(private val context: Context) {

    /** Every bundled filter that parses, in folder order; broken bundles are logged and left out. */
    val bundles: List<FilterBundle> by lazy {
        (context.assets.list(ROOT) ?: emptyArray()).sorted().mapNotNull { id ->
            runCatching { FilterBundle.parse(read("$ROOT/$id/filter.json")) }
                .onFailure { Log.e(TAG, "Filter bundle $id is unusable", it) }
                .getOrNull()
        }
    }

    fun bundle(id: String): FilterBundle? = bundles.firstOrNull { it.id == id }

    /** Reads everything the renderer needs for [id]; null for "off", unknown ids and unreadable bundles. */
    fun load(id: String): LoadedFilter? {
        if (id == OFF) return null
        return runCatching {
            val dir = "$ROOT/$id"
            val json = read("$dir/filter.json")
            val bundle = FilterBundle.parse(json)
            val luts = bundle.textures.map { t -> decodeRgba("$dir/${t.file}") }
            LoadedFilter(
                bundle = bundle,
                json = json,
                vertexSources = bundle.passes.map { read("$dir/${it.vertex}") }.toTypedArray(),
                fragmentSources = bundle.passes.map { read("$dir/${it.fragment}") }.toTypedArray(),
                lutNames = bundle.textures.map { it.name }.toTypedArray(),
                lutPixels = luts.map { it.first }.toTypedArray(),
                lutSizes = luts.flatMap { listOf(it.second, it.third) }.toIntArray()
            )
        }.onFailure { Log.e(TAG, "Couldn't load filter $id", it) }.getOrNull()
    }

    private fun read(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private fun decodeRgba(path: String): Triple<ByteBuffer, Int, Int> {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("can't decode $path")
        // ARGB_8888 is stored as R, G, B, A bytes: exactly what glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE) wants
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val result = Triple(buffer, bitmap.width, bitmap.height)
        bitmap.recycle()
        return result
    }

    companion object {
        const val ROOT = "filters"
        const val OFF = "off"
        private const val TAG = "FilterCatalog"
    }
}
