package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.render.Slope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmoothTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Double) =
        ElevationGrid(w, h, 10.0, FloatArray(w * h) { f(it % w, it / w).toFloat() })

    @Test
    fun binomialWeightsArePascalRows() {
        assertContentEquals(doubleArrayOf(1.0), Smooth.binomialWeights(0))
        assertContentEquals(doubleArrayOf(1.0, 2.0, 1.0), Smooth.binomialWeights(1))
        assertContentEquals(doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0), Smooth.binomialWeights(2))
        assertFailsWith<IllegalArgumentException> { Smooth.binomialWeights(Smooth.MAX_RADIUS + 1) }
        assertFailsWith<IllegalArgumentException> { Smooth.binomialWeights(-1) }
    }

    @Test
    fun radiusZeroIsTheSameGrid() {
        val g = grid(5, 4) { x, y -> x * 3.0 + y }
        assertSame(g, Smooth.gaussian(g, 0))
    }

    @Test
    fun planesAreKeptAwayFromEdgesAndConstantsEverywhere() {
        val plane = grid(20, 16) { x, y -> 1000.0 + 7.5 * x - 3.25 * y }
        for (r in 1..Smooth.MAX_RADIUS) {
            val s = Smooth.gaussian(plane, r)
            for (y in r until plane.height - r) for (x in r until plane.width - r) {
                assertEquals(plane[x, y], s[x, y], 1e-3f, "r=$r at $x,$y")
            }
            val flat = Smooth.gaussian(grid(7, 6) { _, _ -> 1234.5 }, r)
            flat.values.forEach { assertEquals(1234.5f, it) }
        }
    }

    @Test
    fun threeByThreeKernelOnASpike() {
        val spike = grid(5, 5) { x, y -> if (x == 2 && y == 2) 16.0 else 0.0 }
        val s = Smooth.gaussian(spike, 1)
        // Separable 1 2 1 / 4: center 16 * 4/16, edge neighbours 16 * 2/16, corners 16 * 1/16.
        assertEquals(4f, s[2, 2], 1e-6f)
        assertEquals(2f, s[1, 2], 1e-6f)
        assertEquals(2f, s[2, 3], 1e-6f)
        assertEquals(1f, s[1, 1], 1e-6f)
        assertEquals(0f, s[0, 0], 1e-6f)
        assertEquals(16f, s.values.sum(), 1e-4f)
    }

    @Test
    fun noDataStaysPutAndDoesNotSpread() {
        val g = grid(9, 9) { x, y -> if (x == 4 && y == 4) Double.NaN else 100.0 + x }
        val s = Smooth.gaussian(g, 2)
        for (i in g.values.indices) assertEquals(g.values[i].isNaN(), s.values[i].isNaN(), "cell $i")
        // Next to the hole the mean is renormalized over the remaining cells: still near the raw ramp.
        assertEquals(103f, s[3, 4], 0.5f)
        assertEquals(105f, s[5, 4], 0.5f)
    }

    @Test
    fun smoothingTamesNoiseOnASlope() {
        // A 30 degree plane with small steps, like a DEM resampled from a coarser or noisy source.
        val rnd = Random(7)
        val rise = 10.0 * tan(30.0 * PI / 180)
        val noisy = grid(64, 64) { x, y -> x * rise + 0.8 * (rnd.nextDouble() - 0.5) + 0.3 * sin(y * 1.7) }
        fun worstDeviation(g: ElevationGrid): Double {
            val s = Slope.compute(g)
            var worst = 0.0
            for (y in 3 until 61) for (x in 3 until 61) worst = maxOf(worst, abs(s[y * 64 + x] - 30.0))
            return worst
        }
        val raw = worstDeviation(noisy)
        val smoothed = worstDeviation(Smooth.gaussian(noisy, 1))
        assertTrue(smoothed < raw / 2, "slope deviation raw $raw smoothed $smoothed")
    }
}
