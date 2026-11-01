package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.search.LayerFilter
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * T192: the name the route card gives a placed end ("To: Paradise"), from the pack's own layers: the named peaks
 * ([Viewshed.readPeaks], its classes), and the named points of the `outdoor_poi` and `place` layers
 * (`tilebuild/PROFILE.md`), the nearest within [RADIUS_M]; else the card says [NO_NAME]. Read at [ZOOM], the pack's
 * deepest, where neither layer is thinned. Pure (a vector pack's `tile(z, x, y)` comes in), unit-tested.
 */
object PlaceName {
    /** One named feature near the point: its name, layer, class, where, and how far from the point. */
    data class Found(val name: String, val layer: String, val kind: String, val lat: Double, val lon: Double, val distanceM: Double) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "name" to name, "layer" to layer, "kind" to kind, "lat" to lat, "lon" to lon, "distanceM" to kotlin.math.round(distanceM * 10) / 10.0,
        )
    }

    /**
     * How far a named feature may be from the long-pressed point and still name it, metres. A fingertip on the map at
     * the trail zooms (14 to 15) covers about 50 to 100 m of ground, and OSM puts an area's point (a campsite, a
     * locality) tens of metres from where a walker would say they are; past 100 m the name starts to belong to the
     * neighbouring feature rather than the point.
     */
    const val RADIUS_M = 100.0

    /** The pack's deepest zoom: the layers carry every point there. */
    const val ZOOM = 14

    /** The card's words for a point with no name near it. */
    const val NO_NAME = "a point on the map"

    /** The point layers read besides the peaks. */
    val POINT_LAYERS = setOf("outdoor_poi", "place")

    /** The nearest named feature within [radiusM] of ([lat], [lon]), or null. An exact tie goes to the peak. */
    fun nearest(tiles: (Int, Int, Int) -> ByteArray?, lat: Double, lon: Double, radiusM: Double = RADIUS_M, zoom: Int = ZOOM): Found? {
        val found = ArrayList<Found>()
        for (p in Viewshed.readPeaks(tiles, lat, lon, radiusM, zoom)) {
            found += Found(p.name, "mountain_peak", p.kind, p.lat, p.lon, TrackStats.distanceM(lat, lon, p.lat, p.lon))
        }
        readPoints(tiles, lat, lon, radiusM, zoom, found)
        return found.filter { it.distanceM <= radiusM }.minByOrNull { it.distanceM }
    }

    /** The card's words for the end at ([lat], [lon]): the name found, else [NO_NAME]. */
    fun label(found: Found?): String = found?.name ?: NO_NAME

    private fun readPoints(tiles: (Int, Int, Int) -> ByteArray?, lat: Double, lon: Double, radiusM: Double, zoom: Int, out: MutableList<Found>) {
        val n = 1 shl zoom
        val r = radiusM / WebMercator.metersPerPixel(lat, zoom, 1)
        val cx = WebMercator.lonToX(lon, zoom)
        val cy = WebMercator.latToY(lat, zoom)
        for (ty in floor(cy - r).toInt().coerceAtLeast(0)..floor(cy + r).toInt().coerceAtMost(n - 1)) {
            for (tx in floor(cx - r).toInt()..floor(cx + r).toInt()) {
                val bytes = tiles(zoom, tx.mod(n), ty) ?: continue
                val tile = runCatching { MvtDecoder.decode(LayerFilter.filter(bytes, POINT_LAYERS)) }.getOrNull() ?: continue
                for (layerName in POINT_LAYERS) {
                    val layer = tile.layer(layerName) ?: continue
                    for (f in layer.features) {
                        val name = f.properties["name"]?.asText()?.takeIf { it.isNotBlank() } ?: continue
                        val kind = f.properties["class"]?.asText() ?: ""
                        val pts = (f.geometry as? Geometry.Points)?.points ?: continue
                        for (p in pts) {
                            // A point counts in its own tile only (tiles carry a buffer).
                            if (p.x < 0 || p.y < 0 || p.x >= layer.extent || p.y >= layer.extent) continue
                            val plon = WebMercator.xToLon(tx + p.x.toDouble() / layer.extent, zoom)
                            val plat = WebMercator.yToLat(ty + p.y.toDouble() / layer.extent, zoom)
                            val d = TrackStats.distanceM(lat, lon, plat, plon)
                            if (d <= radiusM) out += Found(name, layerName, kind, plat, plon, d)
                        }
                    }
                }
            }
        }
    }
}
