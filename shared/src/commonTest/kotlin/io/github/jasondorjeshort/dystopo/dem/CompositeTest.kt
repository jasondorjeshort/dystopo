package io.github.jasondorjeshort.dystopo.dem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Float) =
        ElevationGrid(w, h, 10.0, FloatArray(w * h) { f(it % w, it / w) })

    // Top layer covers x >= 10 with elevation 100; fallback is 0 everywhere.
    private val top = grid(40, 5) { x, _ -> if (x >= 10) 100f else Float.NaN }
    private val fallback = grid(40, 5) { _, _ -> 0f }

    @Test
    fun fillsGapsAndFeathersTheEdge() {
        val out = Composite.over(top, fallback, featherCells = 10.0)
        val row = (0 until 40).map { out[it, 2] }
        // Outside coverage: pure fallback. Deep inside: pure top layer.
        (0 until 10).forEach { assertEquals(0f, row[it]) }
        (20 until 40).forEach { assertEquals(100f, row[it]) }
        // Inside the band: strictly increasing, no jumps bigger than a smooth ramp allows.
        for (x in 10 until 20) {
            assertTrue(row[x] > row[x - 1], "not increasing at $x: ${row[x - 1]} -> ${row[x]}")
            assertTrue(row[x] - row[x - 1] < 20f, "step too large at $x")
        }
    }

    @Test
    fun noFeatherIsAHardCut() {
        val out = Composite.over(top, fallback, featherCells = 0.0)
        assertEquals(0f, out[9, 0])
        assertEquals(100f, out[10, 0])
    }

    @Test
    fun noDataInBothStaysNoData() {
        val holes = grid(4, 1) { x, _ -> if (x == 0) Float.NaN else 5f }
        val out = Composite.over(holes, holes, 2.0)
        assertTrue(out[0, 0].isNaN())
        assertEquals(0.75, Composite.coverage(out))
    }

    @Test
    fun distanceTransform() {
        val d = Composite.distanceToNoData(top)
        assertEquals(0.0, d[9])
        assertEquals(1.0, d[10])
        assertEquals(5.0, d[14], 1e-9)
    }
}
