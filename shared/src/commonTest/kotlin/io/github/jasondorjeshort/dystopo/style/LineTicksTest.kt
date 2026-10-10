package io.github.jasondorjeshort.dystopo.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineTicksTest {
    @Test
    fun ticksStandOnTheRequestedSide() {
        // Eastward line (y down): its right is south, +y.
        val east = doubleArrayOf(0.0, 50.0, 100.0, 50.0)
        val right = LineTicks.ticks(east, 10.0, 4.0, right = true)
        assertEquals(10, right.size, "one tick every 10 px from 5 px in")
        assertEquals(5.0, right.first()[0], 1e-9)
        for (t in right) {
            assertEquals(50.0, t[1], 1e-9)
            assertEquals(t[0], t[2], 1e-9)
            assertEquals(54.0, t[3], 1e-9)
        }
        assertTrue(LineTicks.ticks(east, 10.0, 4.0, right = false).all { it[3] == 46.0 })
        // A bend: ticks follow each segment's own normal. Southward leg: right is west (-x).
        val bend = doubleArrayOf(0.0, 0.0, 20.0, 0.0, 20.0, 20.0)
        val ticks = LineTicks.ticks(bend, 10.0, 3.0, right = true)
        assertEquals(4, ticks.size)
        assertEquals(3.0, ticks[0][3] - ticks[0][1], 1e-9)
        assertEquals(-3.0, ticks[3][2] - ticks[3][0], 1e-9)
    }

    @Test
    fun downhillSideFromElevation() {
        val east = doubleArrayOf(0.0, 50.0, 100.0, 50.0)
        // Ground falls toward +y (south, the line's right).
        assertEquals(true, LineTicks.rightIsDownhill(east, 3.0, { _, y -> (1000 - 10 * y).toFloat() }))
        assertEquals(false, LineTicks.rightIsDownhill(east, 3.0, { _, y -> (1000 + 10 * y).toFloat() }))
        // Same terrain, line drawn the other way: the answer flips.
        val west = doubleArrayOf(100.0, 50.0, 0.0, 50.0)
        assertEquals(false, LineTicks.rightIsDownhill(west, 3.0, { _, y -> (1000 - 10 * y).toFloat() }))
        assertNull(LineTicks.rightIsDownhill(east, 3.0, { _, _ -> 500f }), "flat")
        assertNull(LineTicks.rightIsDownhill(east, 3.0, { _, _ -> Float.NaN }), "no data")
    }

    @Test
    fun downhillSideIsDecidedPerSegment() {
        // A cliff that bends around a spur: east along y = 50, then north along x = 100.
        // Ground: a ridge whose crest runs along the line's left, so the low side is south
        // on the first leg and east on the second.
        val bend = doubleArrayOf(0.0, 50.0, 100.0, 50.0, 100.0, -50.0)
        val ground = { x: Double, y: Double -> (1000 - 10 * maxOf(y - 50, 0.0) - 10 * maxOf(x - 100, 0.0) + 0.1 * (50 - y)).toFloat() }
        val d = LineTicks.downhillTicks(bend, 10.0, 4.0, 6.0, ground)
        assertEquals(2, d.segments)
        assertEquals(0, d.segmentsUndecided)
        // First leg (eastward): right is +y, the low side. Second leg (northward): right is +x, also low.
        assertEquals(0, d.segmentsLeft)
        for (t in d.ticks) {
            if (t[1] == 50.0 && t[0] < 100.0) assertEquals(54.0, t[3], 1e-9, "first-leg tick points south")
            else assertEquals(t[0] + 4.0, t[2], 1e-9, "second-leg tick points east")
        }

        // Same line, terrain where the second leg's low side is the left (west): only that leg flips.
        val other = { x: Double, y: Double -> (if (y < 40) 1000 + 10 * (x - 100) else 1000 - 10 * (y - 50)).toFloat() }
        val flipped = LineTicks.downhillTicks(bend, 10.0, 4.0, 6.0, other)
        assertEquals(1, flipped.segmentsLeft)
        val secondLeg = flipped.ticks.filter { it[0] == 100.0 && it[1] < 45.0 }
        assertTrue(secondLeg.isNotEmpty())
        assertTrue(secondLeg.all { it[2] < it[0] }, "second-leg ticks point west")
        assertTrue(flipped.ticks.filter { it[1] == 50.0 && it[0] < 95.0 }.all { it[3] > it[1] }, "first leg stays south")
    }

    @Test
    fun undecidedSegmentsFollowTheWholeLine() {
        // Two legs on a south-facing slope, the second one flat (a bench): it takes the line's answer.
        val line = doubleArrayOf(0.0, 50.0, 60.0, 50.0, 120.0, 50.0)
        val ground = { x: Double, y: Double -> if (x > 60) 500f else (1000 - 10 * y).toFloat() }
        val d = LineTicks.downhillTicks(line, 10.0, 4.0, 3.0, ground)
        assertEquals(1, d.segmentsUndecided)
        assertEquals(0, d.segmentsLeft)
        assertTrue(d.ticks.all { it[3] > it[1] })
        // Nothing to go on anywhere: the right side, as OSM draws cliffs.
        val none = LineTicks.downhillTicks(line, 10.0, 4.0, 3.0, { _, _ -> Float.NaN })
        assertEquals(2, none.segmentsUndecided)
        assertTrue(none.ticks.all { it[3] > it[1] })
    }

    @Test
    fun bilinearSamplesBetweenCellCenters() {
        // 2 x 2 grid: cell (i, j) holds 10 * i + 100 * j, centered at (i + 0.5, j + 0.5).
        val f = LineTicks.bilinear(2, 2) { i, j -> (10 * i + 100 * j).toFloat() }
        assertEquals(0f, f(0.5, 0.5))
        assertEquals(5f, f(1.0, 0.5), 1e-4f)
        assertEquals(55f, f(1.0, 1.0), 1e-4f)
        assertEquals(110f, f(1.9, 1.9), 1e-4f, "within half a cell of the edge: the edge value")
        assertTrue(f(-0.1, 1.0).isNaN())
        assertTrue(f(2.0, 1.0).isNaN())
        val holes = LineTicks.bilinear(2, 2) { i, _ -> if (i == 1) Float.NaN else 1f }
        assertTrue(holes(1.0, 1.0).isNaN(), "a NaN neighbour makes the sample NaN")
        assertEquals(1f, holes(0.4, 0.4))
    }
}
