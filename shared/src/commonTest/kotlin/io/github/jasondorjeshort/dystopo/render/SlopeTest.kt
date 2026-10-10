package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlopeTest {
    private fun grid(w: Int, h: Int, cell: Double = 10.0, f: (Int, Int) -> Double) =
        ElevationGrid(w, h, cell, FloatArray(w * h) { f(it % w, it / w).toFloat() })

    /** A plane of [slopeDeg] whose downhill direction faces [aspectDeg] (clockwise from north). */
    private fun plane(slopeDeg: Double, aspectDeg: Double, cell: Double = 10.0): ElevationGrid {
        val rise = tan(slopeDeg * PI / 180)
        val a = aspectDeg * PI / 180
        // Uphill is opposite the aspect. Columns grow east, rows grow south.
        val upEast = -sin(a)
        val upNorth = -cos(a)
        return grid(9, 9, cell) { x, y -> 1000.0 + rise * cell * (x * upEast - y * upNorth) }
    }

    @Test
    fun flatPlaneIsZero() {
        val s = Slope.compute(grid(6, 5) { _, _ -> 1234.0 })
        s.forEach { assertEquals(0f, it) }
        Slope.computeAspect(grid(6, 5) { _, _ -> 1234.0 }).forEach { assertTrue(it.isNaN()) }
    }

    @Test
    fun planesOfKnownSlope() {
        for (deg in listOf(5.0, 27.0, 30.0, 35.0, 45.0, 60.0, 80.0)) {
            for (aspect in listOf(0.0, 37.0, 90.0, 200.0, 315.0)) {
                val s = Slope.compute(plane(deg, aspect)).interior()
                s.forEach { assertEquals(deg, it.toDouble(), 0.1, "slope $deg aspect $aspect") }
            }
        }
    }

    @Test
    fun cellSizeMatters() {
        // 10 m of rise per 10 m cell is 45 degrees; the same numbers on 20 m cells are atan(0.5).
        val s10 = Slope.compute(grid(5, 5, 10.0) { x, _ -> x * 10.0 })[12]
        val s20 = Slope.compute(grid(5, 5, 20.0) { x, _ -> x * 10.0 })[12]
        assertEquals(45.0, s10.toDouble(), 1e-4)
        assertEquals(26.565, s20.toDouble(), 1e-3)
    }

    @Test
    fun aspectOfPlaneRisingEastFacesWest() {
        val a = Slope.computeAspect(grid(5, 5) { x, _ -> x * 3.0 })
        a.forEach { assertEquals(270.0, it.toDouble(), 1e-4) }
    }

    @Test
    fun aspectOfPlaneRisingSouthFacesNorth() {
        // Row 0 is north; rising with y means rising to the south.
        val a = Slope.computeAspect(grid(5, 5) { _, y -> y * 3.0 })
        a.forEach { assertTrue(it < 1e-4 || it > 360 - 1e-4, "aspect $it") }
    }

    @Test
    fun aspectMatchesConstructedPlanes() {
        for (aspect in listOf(10.0, 90.0, 135.0, 181.0, 270.0, 300.0)) {
            val a = Slope.computeAspect(plane(30.0, aspect)).interior()
            a.forEach { assertEquals(aspect, it.toDouble(), 0.01, "aspect $aspect") }
        }
    }

    @Test
    fun noDataStaysNoDataAndDoesNotSpread() {
        val g = grid(5, 5) { x, _ -> x * 10.0 }
        g.values[12] = Float.NaN
        val s = Slope.compute(g)
        assertTrue(s[12].isNaN())
        // Neighbors still get a finite slope (the hole is filled with their own elevation).
        assertTrue(s.withIndex().filter { it.index != 12 }.none { it.value.isNaN() })
    }

    /** Interior cells only: edge cells see a clamped (flattened) neighborhood by design. */
    private fun FloatArray.interior(w: Int = 9, h: Int = 9) =
        (1 until h - 1).flatMap { y -> (1 until w - 1).map { x -> this[y * w + x] } }

    @Test
    fun bandEdges() {
        val labels = Slope.bands.map { it.label }
        assertContentEquals(listOf("27-29", "30-31", "32-34", "35-45", "46-50", "51-59", "60+"), labels)
        assertEquals(-1, Slope.bandIndex(26.99f))
        assertEquals(-1, Slope.bandIndex(Float.NaN))
        assertEquals(0, Slope.bandIndex(27f))
        assertEquals(0, Slope.bandIndex(29.9f))
        assertEquals(1, Slope.bandIndex(30f))
        assertEquals(2, Slope.bandIndex(34.5f))
        assertEquals(3, Slope.bandIndex(35f))
        assertEquals(3, Slope.bandIndex(45.9f))
        assertEquals(4, Slope.bandIndex(46f))
        assertEquals(5, Slope.bandIndex(59.9f))
        assertEquals(6, Slope.bandIndex(60f))
        assertEquals(6, Slope.bandIndex(90f))
    }

    @Test
    fun bandFractionsIgnoreNoData() {
        val f = Slope.bandFractions(floatArrayOf(10f, 28f, 28f, 40f, Float.NaN, 70f))
        assertEquals(0.4, f[0], 1e-9)
        assertEquals(0.2, f[3], 1e-9)
        assertEquals(0.2, f[6], 1e-9)
        assertEquals(0.0, f[1], 1e-9)
    }
}
