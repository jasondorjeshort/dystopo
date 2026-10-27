package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T140 (ADR 0041): the polygon kind, its cover, the versions and the memory guard. Public places only. */
class AreaPolygonTest {
    private val paradise = AreaPoint(46.786, -121.735)

    // Around Rainier (public): an outer ring, a hole in it, and a second part to the south-east.
    private val outer = listOf(AreaPoint(46.70, -121.95), AreaPoint(46.72, -121.55), AreaPoint(46.98, -121.60), AreaPoint(47.00, -121.90))
    private val hole = listOf(AreaPoint(46.80, -121.82), AreaPoint(46.82, -121.70), AreaPoint(46.90, -121.72), AreaPoint(46.88, -121.80))
    private val part = listOf(AreaPoint(46.50, -121.40), AreaPoint(46.52, -121.20), AreaPoint(46.62, -121.25))
    private val poly = AreaOfInterest(AreaShape.Polygon(listOf(outer, hole, part)), 8, 15, "Rainier test")

    @Test
    fun polygonJsonRoundTripsWithClosedRingsAndVersion2() {
        val j = poly.json()
        assertEquals("polygon", j["kind"])
        val rings = j["rings"] as List<*>
        assertEquals(3, rings.size)
        assertEquals((rings[0] as List<*>).first(), (rings[0] as List<*>).last(), "written closed, as GeoJSON")
        assertEquals(0.0, j["bufferM"])
        val back = AreaOfInterest.parse(MiniJson.parseObject(MiniJson.encode(j)))
        assertEquals(poly.shape, back.shape, "read back open")
        assertEquals(poly.id, back.id)
        assertEquals(2, poly.manifestVersion)
        assertEquals(1, AreaOfInterest(AreaShape.Box(-121.9, 46.7, -121.6, 46.9), 8, 13).manifestVersion)
        // The same ring given open is the same area; the buffer and zooms are part of the id, the name is not.
        val open = AreaOfInterest.parse(mapOf("kind" to "polygon", "rings" to listOf(outer.map { listOf(it.lon, it.lat) })))
        val closed = AreaOfInterest.parse(mapOf("kind" to "polygon", "rings" to listOf((outer + outer.first()).map { listOf(it.lon, it.lat) }), "name" to "x"))
        assertEquals(open.id, closed.id)
        assertNotEquals(open.id, AreaOfInterest(AreaShape.Polygon(listOf(outer), 100.0), 8, 15).id)
        assertNotEquals(open.id, AreaOfInterest(AreaShape.Box(-121.95, 46.70, -121.55, 47.00), 8, 15).id)
        // The query form: latitude first, rings after a '|'.
        val q = AreaOfInterest.fromQuery(mapOf("ring" to outer.joinToString(";") { "${it.lat},${it.lon}" } + "|" + hole.joinToString(";") { "${it.lat},${it.lon}" }, "maxZoom" to "13"))
        assertEquals(listOf(outer, hole), (q.shape as AreaShape.Polygon).rings)
        assertEquals(13, q.maxZoom)
        // The manifest of a polygon is version 2 and reads back.
        val m = AreaManifest(poly.id, "t", poly, AreaManifest.STATUS_READY, AreaManifest.tileItems(poly, 6..10, 8..10))
        assertEquals(2, m.json()["version"])
        assertEquals(m.json(), AreaManifest.parse(MiniJson.encode(m.json())).json())
        val box = AreaOfInterest(AreaShape.Box(-121.9, 46.7, -121.6, 46.9), 8, 13)
        assertEquals(1, AreaManifest(box.id, "b", box, AreaManifest.STATUS_READY, emptyList()).json()["version"], "a box manifest stays version 1")
    }

    @Test
    fun badPolygonsAreRefused() {
        assertFailsWith<IllegalArgumentException> { AreaOfInterest(AreaShape.Polygon(listOf(outer.take(2))), 8, 15) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest(AreaShape.Polygon(emptyList()), 8, 15) }
        // A ring across the antimeridian (Fiji's longitudes) is refused: split it at 180.
        val e = assertFailsWith<IllegalArgumentException> {
            AreaOfInterest(AreaShape.Polygon(listOf(listOf(AreaPoint(-17.0, 178.0), AreaPoint(-17.0, -179.0), AreaPoint(-16.0, -179.0)))), 8, 15)
        }
        assertTrue(e.message!!.contains("antimeridian"))
        assertFailsWith<IllegalArgumentException> { AreaOfInterest(AreaShape.Polygon(listOf(outer), -1.0), 8, 15) }
    }

    @Test
    fun versionThenKindThenFieldsEachWithItsSentence() {
        val v3 = mapOf("version" to 3, "id" to "a1", "area" to mapOf("kind" to "region", "code" to "X"), "items" to emptyList<Any>())
        val e1 = assertFailsWith<NeedsNewerApp> { AreaManifest.parse(v3) }
        assertEquals("manifest version 3 is newer than this app reads (2)", e1.message, "the version is checked before the kind")
        val kind = mapOf("version" to 2, "area" to mapOf("kind" to "region", "code" to "X"))
        val e2 = assertFailsWith<NeedsNewerApp> { AreaManifest.parse(kind) }
        assertEquals("kind must be box, corridor or polygon (got 'region')", e2.message)
        assertTrue(e2.message!!.startsWith("kind must be"), "the prefix older servers answer and the phone falls back on")
        // A bad field of a known kind is an ordinary refusal, not a newer app's.
        val bad = assertFailsWith<IllegalArgumentException> { AreaManifest.parse(mapOf("version" to 2, "area" to mapOf("kind" to "polygon", "rings" to "x"))) }
        assertTrue(bad !is NeedsNewerApp)
        assertTrue(NeedsNewerApp.USER_SENTENCE.startsWith("This phone runs an older Dystopo"))
    }

    /** Brute force: a tile is in when its centre is inside by even-odd (in Mercator, as the fill) or an edge touches its square. */
    private fun bruteForce(rings: List<List<AreaPoint>>, z: Int): Set<Pair<Int, Int>> {
        val xs = rings.map { r -> r.map { WebMercator.lonToX(it.lon, z) } }
        val ys = rings.map { r -> r.map { WebMercator.latToY(it.lat, z) } }
        val all = rings.flatten()
        val x0 = floor(WebMercator.lonToX(all.minOf { it.lon }, z)).toInt() - 2
        val x1 = floor(WebMercator.lonToX(all.maxOf { it.lon }, z)).toInt() + 2
        val y0 = floor(WebMercator.latToY(all.maxOf { it.lat }, z)).toInt() - 2
        val y1 = floor(WebMercator.latToY(all.minOf { it.lat }, z)).toInt() + 2
        val out = HashSet<Pair<Int, Int>>()
        for (ty in y0..y1) for (tx in x0..x1) {
            val cx = tx + 0.5
            val cy = ty + 0.5
            var inside = false
            var touches = false
            for (k in rings.indices) {
                val n = xs[k].size
                for (i in 0 until n) {
                    val j = (i + n - 1) % n
                    val ax = xs[k][i]; val ay = ys[k][i]; val bx = xs[k][j]; val by = ys[k][j]
                    if ((ay > cy) != (by > cy) && cx < ax + (cy - ay) / (by - ay) * (bx - ax)) inside = !inside
                    if (TileCover.rectSegmentDistance(tx.toDouble(), ty.toDouble(), tx + 1.0, ty + 1.0, ax, ay, bx, by) <= 0.0) touches = true
                }
            }
            if (inside || touches) out += tx to ty
        }
        return out
    }

    @Test
    fun polygonWithAHoleAndTwoPartsMatchesTheBruteForceAtZ10AndZ12() {
        val rings = (poly.shape as AreaShape.Polygon).rings
        for (z in listOf(10, 12)) {
            val fill = TileCover.polygon(rings, 0.0, z)
            val want = bruteForce(rings, z)
            assertEquals(want, fill.tiles().toSet(), "z$z")
            assertEquals(fill.tiles(), TileSet.fromRows(z, fill.rows()).tiles())
        }
    }

    @Test
    fun theHolesMiddleIsOutAndBothPartsAreIn() {
        val rings = (poly.shape as AreaShape.Polygon).rings
        val z = 14
        val fill = TileCover.polygon(rings, 0.0, z)
        fun tile(lat: Double, lon: Double) = floor(WebMercator.lonToX(lon, z)).toInt() to floor(WebMercator.latToY(lat, z)).toInt()
        val (hx, hy) = tile(46.85, -121.76)
        assertTrue(!fill.contains(hx, hy), "the hole's middle")
        val (ox, oy) = tile(46.75, -121.75)
        assertTrue(fill.contains(ox, oy), "the outer ring's inside")
        val (px, py) = tile(46.55, -121.27)
        assertTrue(fill.contains(px, py), "the second part")
        val (gx, gy) = tile(46.60, -121.50)
        assertTrue(!fill.contains(gx, gy), "between the parts")
    }

    @Test
    fun aBufferedPolygonHoldsEveryPointWithinTheBufferOfItsEdges() {
        val rings = listOf(outer)
        val z = 14
        val set = TileCover.polygon(rings, 400.0, z)
        val plain = TileCover.polygon(rings, 0.0, z)
        assertTrue(set.size > plain.size)
        for ((x, y) in plain.tiles()) assertTrue(set.contains(x, y))
        // Points 380 m outside each vertex, away from the centre, are covered.
        val c = AreaPoint(outer.sumOf { it.lat } / outer.size, outer.sumOf { it.lon } / outer.size)
        for (p in outer) {
            val dLat = p.lat - c.lat
            val dLon = (p.lon - c.lon) * cos(p.lat * PI / 180)
            val len = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
            val lat = p.lat + 380.0 / AreaOfInterest.M_PER_DEG * dLat / len
            val lon = p.lon + 380.0 / (AreaOfInterest.M_PER_DEG * cos(p.lat * PI / 180)) * dLon / len
            assertTrue(set.contains(floor(WebMercator.lonToX(lon, z)).toInt(), floor(WebMercator.latToY(lat, z)).toInt()), "380 m out of $p")
        }
    }

    @Test
    fun aroundAPointIsTheOnePointCorridorThenA48VertexCircle() {
        val five = AreaOfInterest.around(paradise, 5000.0)
        assertEquals(AreaShape.Corridor(listOf(paradise), 5000.0), five.shape, "every build since T75 reads it")
        assertEquals(AreaShape.Corridor(listOf(paradise), 20_000.0), AreaOfInterest.around(paradise, 20_000.0).shape)
        val far = AreaOfInterest.around(paradise, 30_000.0)
        val ring = (far.shape as AreaShape.Polygon).rings.single()
        assertEquals(48, ring.size)
        // The circle holds the disc: points 29.9 km out in any direction are inside, and its area is just above pi r^2.
        for (k in 0 until 96) {
            val a = 2 * PI * k / 96
            val lat = paradise.lat + 29_900.0 * cos(a) / AreaOfInterest.M_PER_DEG
            val lon = paradise.lon + 29_900.0 * sin(a) / (AreaOfInterest.M_PER_DEG * cos(paradise.lat * PI / 180))
            assertTrue(PolygonRings.contains(ring, lat, lon), "bearing $k")
        }
        val area = PolygonRings.areaM2(listOf(ring))
        assertTrue(area > PI * 30_000.0 * 30_000.0 && area < PI * 30_000.0 * 30_000.0 * 1.01, "area $area")
        assertEquals(far, AreaOfInterest.fromQuery(mapOf("around" to "46.786,-121.735", "radiusM" to "30000")))
    }

    @Test
    fun theFiveKilometreDiscAtParadiseIsAbout554TilesAnd68Mb() {
        // ADR 0041's measure: DEM z6 to z15, vector z8 to z14, one tile of margin, at latitude 46.85.
        val items = AreaManifest.tileItems(AreaOfInterest.around(paradise, 5000.0, 8, 15), 6..15, 8..14)
        val tiles = items.sumOf { it.count }
        val mb = items.sumOf { it.bytes } / 1e6 // the ADR's megabytes are decimal
        assertTrue(abs(tiles - 554) <= 554 * 0.05, "tiles $tiles")
        assertTrue(abs(mb - 68) <= 68 * 0.05, "MB $mb")
    }

    @Test
    fun outerRingsAndArea() {
        val rings = (poly.shape as AreaShape.Polygon).rings
        assertEquals(listOf(0, 1, 0), PolygonRings.depths(rings))
        assertEquals(listOf(outer, part), PolygonRings.outer(rings))
        val a = PolygonRings.areaM2(rings)
        assertEquals(PolygonRings.ringAreaM2(outer) - PolygonRings.ringAreaM2(hole) + PolygonRings.ringAreaM2(part), a, 1.0)
    }

    @Test
    fun theUpperBoundCountsEachOuterRingsOwnBoxSoAPairAcrossTheAntimeridianIsNotChargedForTheOcean() {
        // Two parts either side of 180 (Fiji's longitudes, a public example): each part is small, their joint box spans the world.
        val east = listOf(AreaPoint(-17.2, 177.2), AreaPoint(-17.2, 178.7), AreaPoint(-16.0, 178.7), AreaPoint(-16.0, 177.2))
        val west = listOf(AreaPoint(-17.0, -179.99), AreaPoint(-17.0, -179.6), AreaPoint(-16.6, -179.6), AreaPoint(-16.6, -179.99))
        val area = AreaOfInterest(AreaShape.Polygon(listOf(east, west)), 8, 13)
        val dem = 6..13
        val osm = 8..13
        val perRing = AreaLimit.upperBound(area, dem, osm)
        val wholeBox = AreaLimit.upperBound(AreaOfInterest(AreaShape.Box(area.bounds.minLon, area.bounds.minLat, area.bounds.maxLon, area.bounds.maxLat), 8, 13), dem, osm)
        val limit = 50_000L
        assertTrue(wholeBox > limit, "whole box $wholeBox")
        assertTrue(perRing < limit, "per ring $perRing")
        // The fill stays within the bound, and the hole of a ring is not counted.
        assertTrue(AreaManifest.tileItems(area, dem, osm).sumOf { it.count } <= perRing)
        val holed = AreaOfInterest(AreaShape.Polygon(listOf(outer, hole)), 8, 15)
        assertEquals(AreaLimit.upperBound(AreaOfInterest(AreaShape.Polygon(listOf(outer)), 8, 15), dem, osm), AreaLimit.upperBound(holed, dem, osm))
        // T152: the heap limit is the tile cache index's, said and never a refusal.
        assertEquals(AreaLimit.maxTiles(512L shl 20), (512L shl 20) / AreaLimit.HEAP_SHARE / AreaLimit.BYTES_PER_TILE)
        assertTrue(AreaLimit.pastHeap(perRing, perRing - 1))
        assertFalse(AreaLimit.pastHeap(perRing, perRing))
    }
}
