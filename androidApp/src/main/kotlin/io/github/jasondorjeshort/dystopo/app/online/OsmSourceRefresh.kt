package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

/**
 * T138: replaces the map's vector source ([SOURCE_ID], the roads, water and labels) in place, so MapLibre asks for its
 * tiles again without a style reload (the hillshade, contours and overlays stay as they are; `GET /health`
 * `map.styleLoads` does not move). [StalledOsmTiles] asks for it once a tile that was answered empty after a failed fetch
 * has come back.
 *
 * MapLibre keeps a source's tiles while its tile set is unchanged, so the new source has a new tile URL (the style's,
 * whose version carries `OnlineService.osmSourceRefreshes`). A source cannot be removed while layers use it: those
 * layers are removed (the references stay usable), the source replaced and the layers put back at their indexes, all on
 * the main thread in one go.
 */
object OsmSourceRefresh {
    const val SOURCE_ID = "osm"
    /** T144: the `skipped` reason while the style loads; `OnlineService` asks again for such a refresh. */
    const val STYLE_LOADING = "style loading"

    /** The vector source's definition in [styleJson] (`tiles`, `minzoom`, `maxzoom`, `attribution`), or null. Pure, tested. */
    fun osmSource(styleJson: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        val sources = MiniJson.parseObject(styleJson)["sources"] as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        return sources[SOURCE_ID] as? Map<String, Any?>
    }

    /** The source [layer] draws from, or null (background). T155: hillshade and raster layers too ([DemSourceRefresh]). */
    internal fun sourceOf(layer: Layer): String? = when (layer) {
        is LineLayer -> layer.sourceId
        is FillLayer -> layer.sourceId
        is SymbolLayer -> layer.sourceId
        is CircleLayer -> layer.sourceId
        is FillExtrusionLayer -> layer.sourceId
        is HeatmapLayer -> layer.sourceId
        is HillshadeLayer -> layer.sourceId
        is RasterLayer -> layer.sourceId
        else -> null
    }

    /**
     * Replaces the vector source of [style] with the one [styleJson] defines; main thread. Returns what was done for the
     * record (`layers` moved, the new `url` without its token), or a `skipped` reason.
     */
    fun refresh(style: Style, styleJson: String): Map<String, Any?> {
        if (!style.isFullyLoaded) return mapOf("skipped" to STYLE_LOADING)
        if (style.getSource(SOURCE_ID) == null) return mapOf("skipped" to "no vector source")
        val def = osmSource(styleJson) ?: return mapOf("skipped" to "no vector source in the style")
        val url = (def["tiles"] as? List<*>)?.firstOrNull() as? String ?: return mapOf("skipped" to "no tile URL")
        val users = style.layers.withIndex().filter { sourceOf(it.value) == SOURCE_ID }
        for ((_, layer) in users) style.removeLayer(layer)
        style.removeSource(SOURCE_ID)
        val set = TileSet("2.2.0", url).apply {
            (def["minzoom"] as? Number)?.let { setMinZoom(it.toFloat()) }
            (def["maxzoom"] as? Number)?.let { setMaxZoom(it.toFloat()) }
            (def["attribution"] as? String)?.takeIf { it.isNotEmpty() }?.let { attribution = it }
        }
        style.addSource(VectorSource(SOURCE_ID, set))
        for ((index, layer) in users) style.addLayerAt(layer, index)
        return mapOf("layers" to users.size, "url" to url.substringBefore("&t=").substringBefore("?t="))
    }
}
