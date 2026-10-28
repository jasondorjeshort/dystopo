package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.Source
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

/**
 * T155 (T138's follow-up for DEM tiles): replaces the map's elevation source and the sources made from it in place, so
 * MapLibre asks for their tiles again without a style reload (the vector map and the overlays stay; `GET /health`
 * `map.styleLoads` does not move). `OnlineService` asks for it once a DEM tile that was answered 404 after a failed fetch
 * ([StalledOsmTiles], `stalledDem`) has come back.
 *
 * The raster-dem source [DEM] (the native hillshade) is swapped, not re-added layer by layer as vector data would be, and
 * with it the sources derived from the same DEM tiles: the contours ([CONTOURS], vector) and the generated hillshade and
 * slope ([SHADE], raster, present only when a map option draws it). A contour or shade tile made while its DEM tile was
 * missing was served with a hole and not kept (`OnlineTiles`), but MapLibre kept it; the swap asks for it again. Cliff
 * ticks are oriented from the DEM packs only (`TileService.cliffPyramid`), never from an online DEM tile, so the vector
 * source is not touched.
 *
 * Each new source has a new tile URL (the style's, whose version carries `OnlineService.demSourceRefreshes` as `-d<n>`):
 * MapLibre keeps a source's tiles while its tile set is unchanged. A source cannot be removed while layers use it, so
 * those layers are removed (the references stay usable), the sources replaced and the layers put back at their indexes,
 * all on the main thread in one go, as [OsmSourceRefresh] does for the vector source.
 */
object DemSourceRefresh {
    const val DEM = "dem"
    const val CONTOURS = "contours"
    const val SHADE = "shade"
    val SOURCE_IDS = listOf(DEM, CONTOURS, SHADE)

    /** The definitions of [SOURCE_IDS] in [styleJson] (`type`, `tiles`, `minzoom`, `maxzoom`, `encoding`, ...), those present only. Pure, tested. */
    fun sources(styleJson: String): Map<String, Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val all = MiniJson.parseObject(styleJson)["sources"] as? Map<String, Any?> ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, Any?>>()
        @Suppress("UNCHECKED_CAST")
        for (id in SOURCE_IDS) (all[id] as? Map<String, Any?>)?.let { out[id] = it }
        return out
    }

    /** The first tile URL of [def] without its loopback token, for the record. Pure, tested. */
    fun urlOf(def: Map<String, Any?>): String? =
        ((def["tiles"] as? List<*>)?.firstOrNull() as? String)?.substringBefore("&t=")?.substringBefore("?t=")

    private fun sourceFor(id: String, def: Map<String, Any?>): Source? {
        val url = (def["tiles"] as? List<*>)?.firstOrNull() as? String ?: return null
        val set = TileSet("2.2.0", url).apply {
            (def["minzoom"] as? Number)?.let { setMinZoom(it.toFloat()) }
            (def["maxzoom"] as? Number)?.let { setMaxZoom(it.toFloat()) }
            (def["encoding"] as? String)?.let { encoding = it }
            (def["attribution"] as? String)?.takeIf { it.isNotEmpty() }?.let { attribution = it }
        }
        val tileSize = (def["tileSize"] as? Number)?.toInt() ?: 256
        return when (def["type"]) {
            "raster-dem" -> RasterDemSource(id, set, tileSize)
            "raster" -> RasterSource(id, set, tileSize)
            "vector" -> VectorSource(id, set)
            else -> null
        }
    }

    /**
     * Replaces the elevation source and its derived sources of [style] with the ones [styleJson] defines; main thread.
     * Returns what was done for the record (`sources` swapped, `layers` moved, the new DEM `url` without its token), or a
     * `skipped` reason ([OsmSourceRefresh.STYLE_LOADING] while the style loads: `OnlineService` asks again).
     */
    fun refresh(style: Style, styleJson: String): Map<String, Any?> {
        if (!style.isFullyLoaded) return mapOf("skipped" to OsmSourceRefresh.STYLE_LOADING)
        val defs = sources(styleJson).filterKeys { style.getSource(it) != null }
        if (DEM !in defs) return mapOf("skipped" to "no elevation source")
        val made = defs.mapNotNull { (id, def) -> sourceFor(id, def)?.let { id to it } }.toMap()
        if (made.size != defs.size) return mapOf("skipped" to "a source without a tile URL or of an unknown type")
        val users = style.layers.withIndex().filter { OsmSourceRefresh.sourceOf(it.value) in made.keys }
        for ((_, layer) in users) style.removeLayer(layer)
        for (id in made.keys) style.removeSource(id)
        for (source in made.values) style.addSource(source)
        for ((index, layer) in users) style.addLayerAt(layer, index)
        return mapOf("sources" to made.keys.toList(), "layers" to users.size, "url" to defs[DEM]?.let(::urlOf))
    }
}
