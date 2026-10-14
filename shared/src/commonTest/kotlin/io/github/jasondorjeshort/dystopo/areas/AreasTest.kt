package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AreasTest {
    // A line near Paradise (public), roughly north-east for 3 km.
    private val line = listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268))
    private val corridor = AreaOfInterest(AreaShape.Corridor(line, 300.0), 8, 15, "test")

    private fun tileOf(lat: Double, lon: Double, z: Int) = floor(WebMercator.lonToX(lon, z)).toInt() to floor(WebMercator.latToY(lat, z)).toInt()

    @Test
    fun boxCoverIsEveryTileOfTheBox() {
        val t = TileCover.box(-121.80, 46.75, -121.70, 46.85, 12)
        val (x0, y0) = tileOf(46.85, -121.80, 12)
        val (x1, y1) = tileOf(46.75, -121.70, 12)
        assertEquals((x1 - x0 + 1) * (y1 - y0 + 1), t.size)
        assertTrue(t.contains(x0, y0) && t.contains(x1, y1))
        assertFalse(t.contains(x1 + 1, y1))
    }

    @Test
    fun corridorHoldsEveryPointWithinTheBuffer() {
        val rnd = Random(7)
        val cum = Polyline.cumulative(line)
        for (z in listOf(10, 13, 15, 16)) {
            val set = TileCover.corridor(line, 300.0, z)
            repeat(400) {
                // A point on the line, then moved up to 0.98 of the buffer in a random direction.
                val p = Polyline.slice(line, 0.0, rnd.nextDouble() * cum.last(), cum).last()
                val a = rnd.nextDouble() * 2 * PI
                val d = rnd.nextDouble() * 0.98 * 300.0
                val lat = p.lat + d * sin(a) / AreaOfInterest.M_PER_DEG
                val lon = p.lon + d * cos(a) / (AreaOfInterest.M_PER_DEG * cos(p.lat * PI / 180))
                val (x, y) = tileOf(lat, lon, z)
                assertTrue(set.contains(x, y), "z$z: point $lat,$lon (${d.toInt()} m from the line) not covered")
            }
        }
    }

    @Test
    fun corridorIsThinnerThanItsBox() {
        val z = 16
        val set = TileCover.corridor(line, 300.0, z)
        val b = corridor.bounds
        val box = TileCover.box(b.minLon, b.minLat, b.maxLon, b.maxLat, z)
        assertTrue(set.size < box.size * 0.8, "corridor ${set.size} tiles vs box ${box.size}")
        // A tile far from the line (the box's south-east corner) is out.
        val (x, y) = tileOf(b.minLat, b.maxLon, z)
        assertFalse(set.contains(x, y))
    }

    @Test
    fun singlePointCorridorIsTheTileAroundIt() {
        val z = 10
        val p = AreaPoint(46.79, -121.73)
        val set = TileCover.corridor(listOf(p), 50.0, z)
        val (x, y) = tileOf(p.lat, p.lon, z)
        assertTrue(set.contains(x, y))
        assertEquals(1, set.size)
    }

    @Test
    fun rowsRoundTripAndDilate() {
        val set = TileCover.corridor(line, 300.0, 14)
        val back = TileSet.fromRows(14, set.rows())
        assertEquals(set.tiles(), back.tiles())
        val grown = set.dilate(1)
        assertTrue(grown.size > set.size)
        for ((x, y) in set.tiles()) for (dx in -1..1) for (dy in -1..1) assertTrue(grown.contains(x + dx, y + dy))
        assertEquals(1, set.ancestors(14).size)
    }

    @Test
    fun rectSegmentDistance() {
        assertEquals(0.0, TileCover.rectSegmentDistance(0.0, 0.0, 1.0, 1.0, -1.0, 0.5, 2.0, 0.5))
        assertEquals(1.0, TileCover.rectSegmentDistance(0.0, 0.0, 1.0, 1.0, 2.0, -5.0, 2.0, 5.0), 1e-12)
        assertEquals(kotlin.math.sqrt(2.0), TileCover.rectSegmentDistance(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0), 1e-12)
        assertEquals(0.0, TileCover.rectSegmentDistance(0.0, 0.0, 1.0, 1.0, 0.2, 0.2, 0.3, 0.3))
    }

    @Test
    fun areaJsonRoundTripAndId() {
        val back = AreaOfInterest.parse(corridor.json())
        assertEquals(corridor.shape, back.shape)
        assertEquals(corridor.id, back.id)
        assertEquals(corridor.id, corridor.withName("other").id, "the name is not part of the id")
        assertNotEquals(corridor.id, corridor.copy(maxZoom = 14).id)
        val box = AreaOfInterest.fromQuery(mapOf("bbox" to "-121.8,46.75,-121.7,46.85", "maxZoom" to "13"))
        assertEquals(AreaShape.KIND_BOX, box.kind)
        assertEquals(13, box.maxZoom)
        assertEquals(box, AreaOfInterest.parse(box.json()))
        val q = AreaOfInterest.fromQuery(mapOf("line" to "46.7859,-121.7356;46.8016,-121.7355", "bufferM" to "250"))
        assertEquals(250.0, (q.shape as AreaShape.Corridor).bufferM)
        assertEquals(46.7859, q.shape.points[0].lat)
        assertTrue(q.id.startsWith("a") && q.id.length == 17)
    }

    @Test
    fun badAreasAreRefused() {
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.fromQuery(mapOf("bbox" to "1,2,1,3")) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.fromQuery(mapOf("line" to "46.7,-121.7", "bufferM" to "5")) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.fromQuery(mapOf("line" to "46.7,-121.7", "maxZoom" to "20")) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.fromQuery(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.parse(mapOf("kind" to "circle")) }
    }

    @Test
    fun boundsGrowByTheBuffer() {
        val b = corridor.bounds
        assertTrue(b.minLat < 46.7859 - 0.0026 && b.maxLat > 46.8037 + 0.0026)
        assertTrue(b.minLon < -121.7356 - 0.0038)
    }

    @Test
    fun manifestRoundTrip() {
        val tiles = AreaManifest.tileItems(corridor, 6..15, 8..14)
        val pack = FileItem("dem", "x", "x-dem.pmtiles", "/out/x-dem.pmtiles", 1234, "ab".repeat(32), "png", 8, 13, listOf(1.0, 2.0, 3.0, 4.0))
        val m = AreaManifest(
            corridor.id, "test", corridor, AreaManifest.STATUS_READY,
            tiles + pack + AssetItem("glyphs", "/glyphs/{fontstack}/{range}.pbf", 768, 5_000_000, bundled = true) + LocalItem("graph", "x-outdoors.pmtiles", false, "packs only"),
            source = "http://example", generatedAt = "2026-10-12T00:00:00Z", notes = listOf("n"),
        )
        val back = AreaManifest.parse(io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(m.json()))
        assertEquals(m.json(), back.json())
        assertEquals(tiles.sumOf { it.count }, back.tileCount)
        assertFalse(back.bytesExact)
        assertEquals(1234L, back.packs.single().bytes)
        assertTrue(m.summary()["items"].toString().contains("perZoom"))
        assertFalse(m.summary()["items"].toString().contains("tiles=[["))
        assertFailsWith<IllegalArgumentException> { ManifestItem.parse(mapOf("kind" to "pack", "role" to "dem", "pack" to "x", "file" to "f", "url" to "u", "bytes" to 1, "sha256" to "zz")) }
    }

    @Test
    fun pinsMatchCacheKeys() {
        val pins = AreaPins(listOf(AreaPins.cover(corridor)))
        val (x, y) = tileOf(46.80, -121.7355, 15)
        assertTrue(pins.isPinned("10.0.2.2_9130/dem/15/$x/$y.png"))
        assertTrue(pins.isPinned("sources/osm/15/$x/$y.pbf"))
        assertTrue(pins.isPinned("10.0.2.2_9130/dem@3f2a/15/$x/$y.png")) // T74 versioned keys
        assertTrue(pins.isPinned("10.0.2.2_9130/glyphs@a1/Noto%20Sans%20Regular/0-255.pbf"))
        // Deeper than the area: the ancestor decides.
        assertTrue(pins.isPinned("sources/contours-v9-ft-512/16/${2 * x}/${2 * y + 1}.pbf"))
        assertTrue(pins.isPinned("10.0.2.2_9130/dem/6/${x shr 9}/${y shr 9}.png"))
        assertTrue(pins.isPinned("10.0.2.2_9130/glyphs/Noto%20Sans%20Regular/0-255.pbf"))
        val (fx, fy) = tileOf(45.83, 6.86, 15) // Mont Blanc: not in the area
        assertFalse(pins.isPinned("sources/dem/15/$fx/$fy.png"))
        assertFalse(pins.isPinned("sources/dem/x/y/z.png"))
        assertFalse(AreaPins.NONE.isPinned("10.0.2.2_9130/glyphs/a/0-255.pbf"))
    }

    @Test
    fun manifestVersionsRoundTripAndAreLeftOutWhenUnknown() { // T95
        val tiles = AreaManifest.tileItems(corridor, 6..15, 8..14)
        val m = AreaManifest(corridor.id, "test", corridor, AreaManifest.STATUS_READY, tiles, versions = mapOf("osm" to "b1.0", "dem" to "best-v2.aaaa1111"))
        val back = AreaManifest.parse(io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(m.json()))
        assertEquals(mapOf("dem" to "best-v2.aaaa1111", "osm" to "b1.0"), back.versions)
        assertEquals(m.json(), back.json())
        val none = AreaManifest(corridor.id, "test", corridor, AreaManifest.STATUS_READY, tiles)
        assertFalse("versions" in none.json(), "a manifest without versions reads as before T95")
        assertEquals(emptyMap(), AreaManifest.parse(io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(none.json())).versions)
    }

    @Test
    fun pinsHoldTheVersionTheirTilesWereFetchedAt() { // T95
        val src = "10.0.2.2_8775"
        val pins = AreaPins(listOf(AreaPins.cover(corridor)), listOf(mapOf(src to mapOf("dem" to "v1", "osm" to "o1"))))
        val (x, y) = tileOf(46.80, -121.7355, 15)
        assertEquals(setOf("v1"), pins.versions(src, "dem"))
        assertEquals(emptySet(), pins.versions("other_1", "dem"))
        assertTrue(pins.holds(src, "dem", "v1", 15, x, y))
        assertTrue(pins.holds(src, "dem", "v1", 16, 2 * x, 2 * y), "deeper tiles follow their ancestor")
        assertFalse(pins.holds(src, "dem", "v2", 15, x, y), "not at another version")
        assertFalse(pins.holds("other_1", "dem", "v1", 15, x, y), "not for another source")
        val (fx, fy) = tileOf(45.83, 6.86, 15)
        assertFalse(pins.holds(src, "dem", "v1", 15, fx, fy), "not outside the cover")
        // A cover without versions (a transient pin before T95, or a source never asked) holds nothing at any version.
        assertFalse(AreaPins(listOf(AreaPins.cover(corridor))).holds(src, "dem", "v1", 15, x, y))
        assertEquals(Triple(15, x, y), AreaPins.tileOf("$src/dem@v1/15/$x/$y.png"))
        assertEquals(null, AreaPins.tileOf("$src/glyphs/Noto%20Sans%20Regular/0-255.pbf"))
    }

    @Test
    fun anAreaIsOlderThanItsSourceWhenAKnownVersionDiffers() { // T95
        assertEquals(listOf("dem"), AreaPins.older(mapOf("dem" to "v1", "osm" to "o1"), mapOf("dem" to "v2", "osm" to "o1")))
        assertEquals(listOf("dem", "osm"), AreaPins.older(mapOf("dem" to "v1", "osm" to "o1"), mapOf("dem" to "v2", "osm" to "o2")))
        assertEquals(emptyList(), AreaPins.older(mapOf("dem" to "v1"), mapOf("dem" to "v1", "osm" to "o2")), "a name the area never recorded is not compared")
        assertEquals(emptyList(), AreaPins.older(mapOf("dem" to ""), mapOf("dem" to "v2")), "fetched from a source without versions")
        assertEquals(emptyList(), AreaPins.older(mapOf("dem" to "v1"), emptyMap()), "the source's versions are not known yet")
    }

    @Test
    fun sliceAndSimplify() {
        val cum = Polyline.cumulative(line)
        val total = cum.last()
        assertTrue(total in 2300.0..2600.0, "length $total")
        val s = Polyline.slice(line, 1000.0, 2300.0, cum)
        assertEquals(1300.0, Polyline.lengthM(s), 1.0)
        assertEquals(line[1], s[1], "the vertex inside the slice is kept")
        assertEquals(1, Polyline.slice(line, total + 10, total + 20, cum).size)
        val dense = (0..100).map { AreaPoint(46.78 + it * 0.0002, -121.73 + if (it % 2 == 0) 0.0 else 0.00001) }
        val simple = Polyline.simplify(dense, 5.0)
        assertEquals(2, simple.size)
        assertEquals(dense.first(), simple.first())
        assertEquals(dense.last(), simple.last())
    }
}
