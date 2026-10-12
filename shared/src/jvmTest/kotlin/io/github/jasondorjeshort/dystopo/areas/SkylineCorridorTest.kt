package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corridor of the Skyline loop fixture (`gpx/skyline-loop.gpx`, synthetic, public trails at Paradise): the
 * T75 done-when case. Tile counts per zoom stay small (a corridor is much less than its bounding box) and the
 * manifest says how much it would download.
 */
class SkylineCorridorTest {
    private val text = javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }
    private val points = Gpx.parse(text).tracks.single().points.map { AreaPoint(it.lat, it.lon) }
    private val area = AreaOfInterest(AreaShape.Corridor(points, AreaOfInterest.DEFAULT_BUFFER_M), 8, 15, "Skyline loop")

    @Test
    fun corridorManifest() {
        val items = AreaManifest.tileItems(area, 6..15, 8..14)
        val dem = items.single { it.route == "dem" }
        val osm = items.single { it.route == "osm" }
        // About 9 km of trail with 500 m on each side: tens of z15 tiles, not hundreds.
        assertTrue(dem.tiles.getValue(15).size in 20..200, "z15 ${dem.tiles.getValue(15).size}")
        assertTrue(dem.tiles.getValue(8).size <= 9)
        val b = area.bounds
        val box = TileCover.box(b.minLon, b.minLat, b.maxLon, b.maxLat, 16)
        val corridor16 = TileCover.of(area, 16)
        assertTrue(corridor16.size < box.size, "corridor ${corridor16.size} vs box ${box.size} at z16")
        val m = AreaManifest(area.id, "Skyline loop", area, AreaManifest.STATUS_READY, items)
        assertTrue(m.bytes in 2_000_000L..60_000_000L, "estimate ${m.bytes}")
        assertEquals(dem.count + osm.count, m.tileCount)
        println("skyline corridor: dem ${dem.json()["perZoom"]} osm ${osm.json()["perZoom"]} estimate ${m.bytes} bytes; z16 corridor ${corridor16.size} vs box ${box.size}")
    }

    @Test
    fun aheadSegmentIsTheNextPartOfTheLine() {
        val cum = Polyline.cumulative(points)
        val ahead = Polyline.slice(points, 2000.0, 4000.0, cum)
        assertEquals(2000.0, Polyline.lengthM(ahead), 2.0)
        val seg = AreaOfInterest(AreaShape.Corridor(ahead, 300.0), 12, 15)
        val pins = AreaPins(listOf(AreaPins.cover(seg)))
        // The start of the loop (the trailhead) is not in the next segment's z15 cover; its middle is.
        val mid = ahead[ahead.size / 2]
        val x = kotlin.math.floor(io.github.jasondorjeshort.dystopo.geo.WebMercator.lonToX(mid.lon, 15)).toInt()
        val y = kotlin.math.floor(io.github.jasondorjeshort.dystopo.geo.WebMercator.latToY(mid.lat, 15)).toInt()
        assertTrue(pins.isPinned("s/dem/15/$x/$y.png"))
    }
}
