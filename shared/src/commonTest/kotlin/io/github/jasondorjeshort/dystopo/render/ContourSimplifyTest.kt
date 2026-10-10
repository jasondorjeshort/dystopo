package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourSimplifyTest {
    private fun circle(n: Int, r: Double): DoubleArray = DoubleArray(2 * n) { i ->
        val a = 2 * PI * (i / 2) / n
        if (i % 2 == 0) 100 + r * cos(a) else 100 + r * sin(a)
    }

    @Test
    fun colinearPointsCollapseToEndPoints() {
        val pts = DoubleArray(200) { i -> if (i % 2 == 0) (i / 2).toDouble() else 2.0 * (i / 2) }
        val out = ContourSimplify.simplify(pts, closed = false, tolerance = 0.1)
        assertEquals(2, out.size / 2)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 99.0, 198.0), out)
    }

    @Test
    fun zigzagAboveToleranceIsKept() {
        // y alternates 0, 1, 0, 1 ... every point is a corner 1 unit off any chord.
        val pts = DoubleArray(40) { i -> if (i % 2 == 0) (i / 2).toDouble() else (i / 2 % 2).toDouble() }
        assertEquals(20, ContourSimplify.simplify(pts, false, 0.4).size / 2)
        assertTrue(ContourSimplify.simplify(pts, false, 1.5).size / 2 <= 3)
    }

    @Test
    fun reducesPointsAndRespectsToleranceOnACircle() {
        val original = circle(2000, 50.0)
        for (tol in listOf(0.1, 0.5, 2.0)) {
            val out = ContourSimplify.simplify(original, closed = true, tolerance = tol)
            val dev = ContourSimplify.maxDeviation(original, out, closed = true)
            assertTrue(dev <= tol + 1e-9, "tol $tol: deviation $dev")
            assertTrue(out.size < original.size / 4, "tol $tol: ${out.size / 2} points left")
            // The ring stays closed around the center, still roughly radius 50.
            for (i in 0 until out.size / 2) assertEquals(50.0, hypot(out[2 * i] - 100, out[2 * i + 1] - 100), 1e-6)
        }
        // Tighter tolerance keeps more points.
        assertTrue(
            ContourSimplify.simplify(original, true, 0.1).size > ContourSimplify.simplify(original, true, 2.0).size,
        )
    }

    @Test
    fun closedRingKeepsFirstPointAndHasNoDuplicateClosingPoint() {
        val original = circle(100, 20.0)
        val out = ContourSimplify.simplify(original, closed = true, tolerance = 0.5)
        assertEquals(original[0], out[0])
        assertEquals(original[1], out[1])
        val last = out.size / 2 - 1
        assertTrue(hypot(out[2 * last] - out[0], out[2 * last + 1] - out[1]) > 1e-6)
    }

    @Test
    fun zeroToleranceAndTinyLinesAreUnchanged() {
        val pts = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 0.0)
        assertContentEquals(pts, ContourSimplify.simplify(pts, false, 0.0))
        assertContentEquals(doubleArrayOf(0.0, 0.0, 5.0, 5.0), ContourSimplify.simplify(doubleArrayOf(0.0, 0.0, 5.0, 5.0), false, 10.0))
    }

    @Test
    fun maxDeviationMeasuresTheWorstPoint() {
        val original = doubleArrayOf(0.0, 0.0, 5.0, 3.0, 10.0, 0.0)
        val simplified = doubleArrayOf(0.0, 0.0, 10.0, 0.0)
        assertEquals(3.0, ContourSimplify.maxDeviation(original, simplified, closed = false), 1e-9)
    }

    @Test
    fun simplifyAllOnTracedContoursCutsPointCountALot() {
        val w = 201
        val grid = ElevationGrid(w, w, 10.0, FloatArray(w * w) { (100.0 - hypot(it % w - 100.0, it / w - 100.0)).toFloat() })
        val lines = Contours.trace(grid, Contours.levels(0.0, 100.0, 5.0))
        val before = lines.sumOf { it.pointCount }
        val after = ContourSimplify.simplifyAll(lines, 0.5)
        val afterCount = after.sumOf { it.pointCount }
        assertTrue(afterCount < before / 2, "before $before after $afterCount")
        for ((a, b) in lines.zip(after)) {
            assertEquals(a.level, b.level)
            assertTrue(ContourSimplify.maxDeviation(a.points, b.points, a.closed) <= 0.5 + 1e-9)
        }
    }

    @Test
    fun chaikinSmoothingRoundsCornersAndKeepsEndPoints() {
        val pts = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0)
        val out = ContourSimplify.smooth(pts, closed = false, iterations = 1)
        assertEquals(6, out.size / 2)
        assertEquals(0.0, out[0]); assertEquals(0.0, out[1])
        assertEquals(10.0, out[out.size - 2]); assertEquals(10.0, out[out.size - 1])
        // The corner at (10,0) is cut: no output point is exactly there.
        for (i in 0 until out.size / 2) assertTrue(!(out[2 * i] == 10.0 && out[2 * i + 1] == 0.0))
        // Closed rings double their point count.
        val ring = ContourSimplify.smooth(circle(10, 5.0), closed = true, iterations = 2)
        assertEquals(40, ring.size / 2)
    }
}
