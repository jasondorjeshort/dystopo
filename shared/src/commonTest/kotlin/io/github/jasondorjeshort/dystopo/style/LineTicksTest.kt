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
}
