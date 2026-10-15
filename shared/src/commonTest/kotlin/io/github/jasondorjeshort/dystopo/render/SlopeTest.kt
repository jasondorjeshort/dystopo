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
    fun avalancheBandEdges() {
        val bands = Slope.avalancheBands
        assertContentEquals(listOf("25-29", "30-34", "35-45", "46-50", "51-59", "60+"), bands.map { it.label })
        fun band(d: Float) = Slope.bandIndex(d, bands)
        assertEquals(-1, band(24.99f))
        assertEquals(-1, band(Float.NaN))
        assertEquals(0, band(25f))
        assertEquals(0, band(29.9f))
        assertEquals(1, band(30f))
        assertEquals(1, band(34.9f))
        assertEquals(2, band(35f))
        assertEquals(2, band(38f))
        assertEquals(2, band(45.9f))
        assertEquals(3, band(46f))
        assertEquals(4, band(59.9f))
        assertEquals(5, band(60f))
        assertEquals(5, band(90f))
    }

    @Test
    fun steepnessBandEdges() {
        val bands = Slope.steepnessBands
        assertContentEquals(listOf("10-19", "20-29", "30-39", "40-49", "50-59", "60+"), bands.map { it.label })
        assertEquals(-1, Slope.bandIndex(9.99f, bands))
        assertEquals(0, Slope.bandIndex(10f, bands))
        assertEquals(2, Slope.bandIndex(38f, bands))
        assertEquals(5, Slope.bandIndex(89f, bands))
    }

    @Test
    fun bandsTileTheRangeWithoutGapsAndSteepnessDarkens() {
        for (bands in listOf(Slope.avalancheBands, Slope.steepnessBands)) {
            for (k in 1 until bands.size) assertEquals(bands[k - 1].maxDeg, bands[k].minDeg, "no gap below ${bands[k].label}")
            assertTrue(bands.last().maxDeg > 90.0, "the last band takes vertical ground")
            assertEquals(bands.size, bands.map { it.rgb }.toSet().size, "one colour per band")
        }
        // Steepness: lightness falls band by band (steeper always reads darker).
        fun luma(rgb: Int) = 0.299 * ((rgb shr 16) and 0xFF) + 0.587 * ((rgb shr 8) and 0xFF) + 0.114 * (rgb and 0xFF)
        val l = Slope.steepnessBands.map { luma(it.rgb) }
        for (k in 1 until l.size) assertTrue(l[k] < l[k - 1], "band ${Slope.steepnessBands[k].label} darker than the one below: $l")
        // Avalanche: the most frequent release angles (35-45, around 38) are the reddest band.
        fun redness(rgb: Int) = ((rgb shr 16) and 0xFF) - maxOf((rgb shr 8) and 0xFF, rgb and 0xFF)
        val reddest = Slope.avalancheBands.maxBy { redness(it.rgb) }
        assertEquals("35-45", reddest.label)
    }

    @Test
    fun shadingsParseTheirKeysAndTheOldOnOff() {
        for (s in SlopeShading.entries) assertEquals(s, SlopeShading.parse(s.key))
        assertEquals(SlopeShading.AVALANCHE, SlopeShading.parse("on"))
        assertEquals(SlopeShading.OFF, SlopeShading.parse(" Off "))
        assertEquals(SlopeShading.STEEPNESS, SlopeShading.parse("STEEPNESS"))
        assertEquals(null, SlopeShading.parse("maybe"))
        assertTrue(SlopeShading.OFF.bands.isEmpty())
        assertEquals(-1, Slope.bandIndex(40f, SlopeShading.OFF.bands))
    }

    @Test
    fun bandFractionsIgnoreNoData() {
        val f = Slope.bandFractions(floatArrayOf(10f, 28f, 28f, 40f, Float.NaN, 70f), Slope.avalancheBands)
        assertEquals(0.4, f[0], 1e-9)
        assertEquals(0.2, f[2], 1e-9)
        assertEquals(0.2, f[5], 1e-9)
        assertEquals(0.0, f[1], 1e-9)
        val g = Slope.bandFractions(floatArrayOf(5f, 15f, 15f, 45f, Float.NaN), Slope.steepnessBands)
        assertEquals(0.5, g[0], 1e-9)
        assertEquals(0.25, g[3], 1e-9)
    }
}
