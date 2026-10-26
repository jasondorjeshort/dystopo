package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.devtool.CountriesCommand.Companion.signedArea
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountriesTest {
    private fun ring(vararg lonLat: Double): List<AreaPoint> = (lonLat.indices step 2).map { AreaPoint(lonLat[it + 1], lonLat[it]) }

    private fun wkbPolygon(buf: ByteBuffer, rings: List<List<AreaPoint>>) {
        buf.put(if (buf.order() == ByteOrder.LITTLE_ENDIAN) 1 else 0).putInt(3).putInt(rings.size)
        for (r in rings) {
            buf.putInt(r.size)
            for (p in r) buf.putDouble(p.lon).putDouble(p.lat)
        }
    }

    @Test
    fun readsWkbPolygonsWithTheirRingOrderInEitherByteOrder() {
        val outer = ring(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0)
        val hole = ring(2.0, 2.0, 2.0, 4.0, 4.0, 4.0, 2.0, 2.0)
        val island = ring(20.0, 0.0, 21.0, 0.0, 21.0, 1.0, 20.0, 0.0)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val buf = ByteBuffer.allocate(1000).order(order)
            buf.put(if (order == ByteOrder.LITTLE_ENDIAN) 1 else 0).putInt(6).putInt(2)
            wkbPolygon(buf, listOf(outer, hole))
            wkbPolygon(buf, listOf(island))
            val polys = CountriesCommand.wkbPolygons(buf.array().copyOf(buf.position()))
            assertEquals(listOf(listOf(outer, hole), listOf(island)), polys)
        }
    }

    @Test
    fun aRingAcrossTheAntimeridianIsSplitIntoPiecesThatDoNotCrossIt() {
        val crossing = ring(178.0, -17.0, -178.0, -17.0, -178.0, -16.0, 178.0, -16.0, 178.0, -17.0)
        val pieces = CountriesCommand.splitAtAntimeridian(crossing)
        assertEquals(2, pieces.size)
        for (p in pieces) {
            assertTrue(p.first() == p.last() && p.size >= 4)
            assertTrue(CountriesCommand.maxLonStep(p) <= 180.0, "$p")
            assertTrue(p.all { abs(it.lon) <= 180.0 })
        }
        assertTrue(pieces.any { r -> r.all { it.lon >= 178.0 } } && pieces.any { r -> r.all { it.lon <= -178.0 } })
        val total = pieces.sumOf { abs(signedArea(it)) }
        assertEquals(4.0, total, 1e-9)
        // A ring that only touches 180 is left alone.
        val touching = ring(179.0, 0.0, 180.0, 0.0, 180.0, 1.0, 179.0, 0.0)
        assertTrue(CountriesCommand.splitAtAntimeridian(touching).single() === touching)
    }

    @Test
    fun polarAndLongEdgesGetVerticesOnTheEdge() {
        val polar = ring(-180.0, -90.0, -180.0, -70.0, 180.0, -70.0, 180.0, -90.0, -180.0, -90.0)
        val d = CountriesCommand.densifyPolarEdges(polar)
        assertTrue(CountriesCommand.maxLonStep(d) <= 90.0, "$d")
        val long = CountriesCommand.densifyLongEdges(ring(-170.0, 0.0, 170.0, 10.0, -170.0, 0.0))
        assertTrue(CountriesCommand.maxLonStep(long) <= 90.0)
        assertEquals(0.0, signedArea(long), 1e-9)
    }

    @Test
    fun boundsRunAcrossTheAntimeridianWhenThatIsSmaller() {
        val east = ring(177.0, -18.0, 180.0, -18.0, 180.0, -16.0, 177.0, -18.0)
        val west = ring(-180.0, -17.0, -178.0, -17.0, -178.0, -16.0, -180.0, -17.0)
        assertEquals(listOf(177.0, -18.0, -178.0, -16.0), CountriesCommand.bounds(listOf(east, west)))
        assertEquals(listOf(177.0, -18.0, 180.0, -16.0), CountriesCommand.bounds(listOf(east)))
    }

    private fun circle(lat: Double, lon: Double, radiusM: Double, n: Int, clockwise: Boolean = false): List<AreaPoint> {
        val pts = (0 until n).map { i ->
            val a = 2 * PI * i / n * (if (clockwise) -1 else 1)
            AreaPoint(lat + radiusM * sin(a) / 111_320.0, lon + radiusM * cos(a) / (111_320.0 * cos(lat * PI / 180)))
        }
        return pts + pts[0]
    }

    @Test
    fun aCountryOverTheBudgetIsSimplifiedOrientedAndItsToleranceMeasured() {
        val big = circle(46.85, -121.76, 50_000.0, 5000, clockwise = true)
        val hole = circle(46.85, -121.76, 10_000.0, 100)
        val row = CountriesCommand.Row("AAA", null, "A", "A", "Europe", "Sovereign country", 1.0, emptyList())
        val built = CountriesCommand.finish(row, listOf(CountriesCommand.RawRing(big, true), CountriesCommand.RawRing(hole, false)), 400)
        assertTrue(built.rings.sumOf { it.size - 1 } <= 400, "${built.rings.sumOf { it.size - 1 }}")
        assertTrue(built.toleranceM >= built.parameterM, "the measured tolerance ${built.toleranceM} covers the parameter ${built.parameterM}")
        assertTrue(signedArea(built.rings[0]) > 0, "outer counter-clockwise (it came in clockwise)")
        assertTrue(signedArea(built.rings[1]) < 0, "hole clockwise (it came in counter-clockwise)")
        val small = CountriesCommand.finish(row, listOf(CountriesCommand.RawRing(hole, true)), 400)
        assertEquals(0.0, small.toleranceM)
        assertEquals(100, small.rings.single().size - 1)
    }

    @Test
    fun aTinyIslandKeepsATriangle() {
        val islet = circle(0.0, 0.0, 100.0, 20)
        val s = CountriesCommand.simplifyRing(islet, 5_000.0)
        assertEquals(4, s.size)
        assertTrue(abs(signedArea(s)) > 0)
    }

    @Test
    fun theCoverOfABoxShapedPolygonIsTheBoxAndOfACircleTheDisc() {
        val box = ring(-121.9, 46.7, -121.6, 46.7, -121.6, 46.95, -121.9, 46.95, -121.9, 46.7)
        for (z in 8..14) {
            assertEquals(TileCover.box(-121.9, 46.7, -121.6, 46.95, z).dilate(1).size.toLong(), CountriesCommand.Cover.count(listOf(box), 0.0, z, 1), "z$z")
        }
        // A 5 km circle (a fine polygon) against the one-point corridor of the same radius: within a few percent.
        val disc = circle(46.853, -121.76, 5000.0, 720)
        for (z in 10..15) {
            val corridor = TileCover.corridor(listOf(AreaPoint(46.853, -121.76)), 5000.0, z).dilate(1).size.toDouble()
            val polygon = CountriesCommand.Cover.count(listOf(disc), 0.0, z, 1).toDouble()
            assertTrue(abs(polygon - corridor) / corridor < 0.05, "z$z polygon $polygon corridor $corridor")
        }
    }

    @Test
    fun theBundledSetPassesTheBuildChecks() {
        assertEquals(emptyList(), CountriesCommand.validate(Countries.all))
    }
}
