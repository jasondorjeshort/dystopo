package io.github.jasondorjeshort.dystopo.app

import android.graphics.RectF
import org.maplibre.android.style.layers.SymbolLayer

/**
 * `GET /labels` (debug): rendered features per layer in the current view, symbol layers first, the
 * device counterpart of demo-check.py's per-layer counts. A symbol layer with features in its source
 * but zero rendered here means its labels were not placed (glyphs missing, collisions, a bad
 * expression); compare with `/screenshot`. Also reports the style's glyph URL.
 */
class LabelRoutes(private val app: DystopoApp) {
    fun handle(r: Request): Response? {
        if (r.method != "GET" || r.path != "/labels") return null
        return Response.json(
            app.map.onMain {
                val m = app.map.map ?: return@onMain mapOf("ready" to false)
                val view = app.map.mapView ?: return@onMain mapOf("ready" to false)
                val style = m.style ?: return@onMain mapOf("ready" to true, "styleLoaded" to false)
                val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                val symbols = LinkedHashMap<String, Int>()
                val others = LinkedHashMap<String, Int>()
                for (layer in style.layers) {
                    val n = m.queryRenderedFeatures(box, layer.id).size
                    if (layer is SymbolLayer) symbols[layer.id] = n else if (n > 0) others[layer.id] = n
                }
                mapOf(
                    "ready" to true,
                    "styleLoaded" to style.isFullyLoaded,
                    "symbolLayers" to symbols,
                    "symbolsRendered" to symbols.values.sum(),
                    "otherLayers" to others,
                    "textScale" to AppSettings.textScale,
                )
            },
        )
    }
}
