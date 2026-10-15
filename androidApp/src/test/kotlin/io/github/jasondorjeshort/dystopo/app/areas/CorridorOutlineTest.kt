package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T106 (a T95 follow-up): the Offline areas sheet drew a corridor as one translucent line as wide as its buffer, which
 * folded into overlapping wedges at bends; now it fills [CorridorOutline]'s polygons once. Every outline point lies at
 * the buffer distance from the line (so nothing folds over), a hairpin gives one ring, a loop route a hole.
 */
class CorridorOutlineTest {
    private val lat0 = 46.80 // Rainier

    /** A point [east] and [north] metres from Paradise's longitude and [lat0]. */
    private fun at(east: Double, north: Double) = AreaPoint(lat0 + north / 111_195.0, -121.735 + east / (111_195.0 * cos(lat0 * PI / 180)))

    /** Ground metres from [p] to the nearest point of [line] (local flat projection, fine over a few km). */
    private fun distanceM(line: List<AreaPoint>, lon: Double, lat: Double): Double {
        val k = cos(lat0 * PI / 180) * 111_195.0
        fun xy(lo: Double, la: Double) = (lo - -121.735) * k to (la - lat0) * 111_195.0
        val (px, py) = xy(lon, lat)
        return line.zipWithNext().minOf { (a, b) ->
            val (ax, ay) = xy(a.lon, a.lat)
            val (bx, by) = xy(b.lon, b.lat)
            val dx = bx - ax
            val dy = by - ay
            val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }

    /** Area in m² of a lon/lat ring (shoelace in the local projection; positive counter-clockwise). */
    private fun areaM2(r: List<CorridorOutline.LonLat>): Double {
        val k = cos(lat0 * PI / 180) * 111_195.0
        return (0 until r.size - 1).sumOf { i -> r[i].lon * k * r[i + 1].lat * 111_195.0 - r[i + 1].lon * k * r[i].lat * 111_195.0 } / 2
    }

    private fun assertOnTheBuffer(line: List<AreaPoint>, buffer: Double, polys: List<CorridorOutline.Polygon>) {
        val cell = buffer / 5
        for (p in polys) for (ring in listOf(p.outer) + p.holes) for (pt in ring) {
            val d = distanceM(line, pt.lon, pt.lat)
            assertTrue(abs(d - buffer) < cell, "an outline point ${d.toInt()} m from the line, buffer ${buffer.toInt()} m")
        }
    }

    @Test
    fun aStraightCorridorIsOneCapsule() {
        val line = listOf(at(0.0, 0.0), at(1000.0, 0.0))
        val polys = CorridorOutline.polygons(line, 100.0)
        assertEquals(1, polys.size)
        assertEquals(0, polys[0].holes.size)
        assertOnTheBuffer(line, 100.0, polys)
        val expected = 1000.0 * 200 + PI * 100 * 100
        val area = areaM2(polys[0].outer)
        assertTrue(area > 0, "outer rings run counter-clockwise")
        assertTrue(abs(area - expected) / expected < 0.03, "area $area against $expected")
        val first = polys[0].outer.first()
        assertEquals(first, polys[0].outer.last(), "rings are closed")
    }

    @Test
    fun aHairpinIsOneRingWithNoFold() {
        // Up 2 km and back down 80 m to the side, 300 m each side: the two legs' bands overlap completely, which the
        // old wide line drew as darker wedges at the turn and wherever the legs met.
        val line = listOf(at(0.0, 0.0), at(0.0, 2000.0), at(80.0, 2000.0), at(80.0, 0.0))
        val polys = CorridorOutline.polygons(line, 300.0)
        assertEquals(1, polys.size)
        assertEquals(0, polys[0].holes.size)
        assertOnTheBuffer(line, 300.0, polys)
        // A body 680 m wide (two legs 80 m apart, 300 m out each side), and at each end half a disc and 80 m by 300 m.
        val expected = 2000.0 * 680 + PI * 300 * 300 + 2 * 80.0 * 300
        val area = areaM2(polys[0].outer)
        assertTrue(abs(area - expected) / expected < 0.03, "area $area against $expected")
    }

    @Test
    fun aLoopRouteHasAHole() {
        // A square loop of 3 km sides, 300 m each side: the middle is not in the corridor.
        val line = listOf(at(0.0, 0.0), at(3000.0, 0.0), at(3000.0, 3000.0), at(0.0, 3000.0), at(0.0, 0.0))
        val polys = CorridorOutline.polygons(line, 300.0)
        assertEquals(1, polys.size)
        assertEquals(1, polys[0].holes.size)
        assertOnTheBuffer(line, 300.0, polys)
        assertTrue(areaM2(polys[0].holes[0]) < 0, "holes run clockwise")
        val hole = -areaM2(polys[0].holes[0])
        assertTrue(abs(hole - 2400.0 * 2400) / (2400.0 * 2400) < 0.03, "hole $hole")
    }

    @Test
    fun aLongDriveStaysWithinTheGridCap() {
        // About 570 km north to south with 500 m each side: the grid is capped, the outline still follows the buffer
        // within a cell (here larger than a fifth of the buffer).
        val line = (0..60).map { i -> AreaPoint(44.0 + i * 0.085, -121.0 + 0.3 * kotlin.math.sin(i / 4.0)) }
        val t0 = System.nanoTime()
        val polys = CorridorOutline.polygons(line, 500.0)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(1, polys.size)
        assertTrue(ms < 2_000, "made in $ms ms")
        val points = polys.sumOf { it.outer.size }
        assertTrue(points in 100..20_000, "$points outline points")
        // Width at the middle: points of the outline near the line's middle latitude are about 500 m from it.
        val mid = line[30]
        val near = polys[0].outer.filter { abs(it.lat - mid.lat) < 0.01 }
        assertTrue(near.isNotEmpty())
        val kx = cos(mid.lat * PI / 180) * 111_195.0
        for (p in near) {
            val d = line.zipWithNext().minOf { (a, b) ->
                val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * 111_195.0
                val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * 111_195.0
                val dx = bx - ax; val dy = by - ay
                val t = (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            }
            assertTrue(abs(d - 500) < 1_100, "a point ${d.toInt()} m from the line")
        }
    }

    @Test
    fun oneOrNoPoint() {
        val disc = CorridorOutline.polygons(listOf(at(0.0, 0.0)), 200.0)
        assertEquals(1, disc.size)
        val area = areaM2(disc[0].outer)
        assertTrue(abs(area - PI * 200 * 200) / (PI * 200 * 200) < 0.03, "a disc: $area")
        assertEquals(emptyList(), CorridorOutline.polygons(emptyList(), 200.0))
        assertEquals(emptyList(), CorridorOutline.polygons(listOf(at(0.0, 0.0), at(10.0, 0.0)), 0.0))
    }
}
