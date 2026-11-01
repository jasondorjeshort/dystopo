package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.cos
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T192: the route card's name for a placed end: the nearest named peak, outdoor point or place within 100 m, read from
 * the pack's own layers at z14; else "a point on the map". Synthetic tiles near Paradise (a public place).
 */
class PlaceNameTest {
    private val extent = 4096
    private val tiles = HashMap<Triple<Int, Int, Int>, MutableMap<String, MutableList<Feature>>>()

    private fun point(layer: String, lat: Double, lon: Double, vararg kv: Pair<String, Any>, z: Int = 14) {
        val fx = WebMercator.lonToX(lon, z)
        val fy = WebMercator.latToY(lat, z)
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        val props = kv.associate { (k, v) -> k to if (v is String) Value.StringValue(v) else Value.IntValue((v as Int).toLong()) }
        tiles.getOrPut(Triple(z, x, y)) { LinkedHashMap() }.getOrPut(layer) { ArrayList() } +=
            Feature(null, props, Geometry.Points(listOf(TilePoint(((fx - x) * extent).toInt(), ((fy - y) * extent).toInt()))))
    }

    private fun read(): (Int, Int, Int) -> ByteArray? {
        val encoded = tiles.mapValues { (_, layers) -> VectorTile(layers.map { (n, fs) -> Layer(n, fs, extent) }).encode() }
        return { z, x, y -> encoded[Triple(z, x, y)] }
    }

    /** [m] metres north and east of ([lat], [lon]). */
    private fun off(lat: Double, lon: Double, northM: Double, eastM: Double) =
        lat + northM / 111_195.0 to lon + eastM / (111_195.0 * cos(lat * kotlin.math.PI / 180))

    @Test
    fun theNearestNamedFeatureWithinARadiusNamesTheEnd() {
        val lat = 46.7860
        val lon = -121.7360
        off(lat, lon, 40.0, 0.0).let { (a, b) -> point("place", a, b, "class" to "locality", "name" to "Paradise") }
        off(lat, lon, 0.0, 70.0).let { (a, b) -> point("outdoor_poi", a, b, "class" to "trailhead", "name" to "A Trailhead") }
        off(lat, lon, 20.0, 0.0).let { (a, b) -> point("outdoor_poi", a, b, "class" to "toilets") } // unnamed: skipped
        off(lat, lon, 0.0, -30.0).let { (a, b) -> point("water_name", a, b, "class" to "lake", "name" to "Not Read") } // not a read layer
        val f = PlaceName.nearest(read(), lat, lon)!!
        assertEquals("Paradise", f.name)
        assertEquals("place", f.layer)
        assertEquals("locality", f.kind)
        assertEquals(40.0, f.distanceM, 1.0)
        assertEquals("Paradise", PlaceName.label(f))
    }

    @Test
    fun aPeakNamesItsSummitAndBareGroundIsAPointOnTheMap() {
        val lat = 46.8020
        val lon = -121.7270
        point("mountain_peak", lat + 0.0002, lon, "class" to "peak", "name" to "Panorama Point", "ele" to 2097)
        off(lat, lon, -60.0, 0.0).let { (a, b) -> point("outdoor_poi", a, b, "class" to "viewpoint", "name" to "A Viewpoint") }
        assertEquals("Panorama Point", PlaceName.nearest(read(), lat, lon)!!.name)
        // 1 km away from everything: nothing within 100 m.
        val (bareLat, bareLon) = off(lat, lon, 1000.0, 1000.0)
        assertNull(PlaceName.nearest(read(), bareLat, bareLon))
        assertEquals("a point on the map", PlaceName.label(null))
        // 120 m off the viewpoint and the peak: past the radius.
        val (nearLat, nearLon) = off(lat, lon, -180.0, 0.0)
        assertNull(PlaceName.nearest(read(), nearLat, nearLon))
    }

    @Test
    fun aFeatureInTheNeighbouringTileCounts() {
        // A point 30 m east of a tile edge, the end 30 m west of it.
        val z = 14
        val edgeLon = WebMercator.xToLon(floor(WebMercator.lonToX(-121.7360, z)) + 1.0, z)
        val lat = 46.7860
        val (_, east) = off(lat, edgeLon, 0.0, 30.0)
        val (_, west) = off(lat, edgeLon, 0.0, -30.0)
        point("outdoor_poi", lat, east, "class" to "hut", "name" to "A Hut")
        assertEquals("A Hut", PlaceName.nearest(read(), lat, west)?.name)
    }
}
