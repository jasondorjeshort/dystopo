package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowGridTest {
    private fun grid(w: Int, h: Int, cell: Double = 30.0, f: (Int, Int) -> Double) =
        ElevationGrid(w, h, cell, FloatArray(w * h) { f(it % w, it / w).toFloat() })

    @Test
    fun pitIsFilledAndDrainsThroughItsRim() {
        // A plane falling to the east and south with a 3x3 pit in the middle: the pit is raised to just above its
        // spill cell and drains on; nothing stays a sink inside the grid, and nothing else is raised.
        val g = grid(11, 9) { x, y -> if (x in 4..6 && y in 3..5) 0.0 else 100.0 - 2.0 * x - 0.5 * y }
        val flow = FlowGrid.build(g)
        assertEquals(9, flow.raisedCells)
        for (i in 0 until flow.size) {
            val x = i % 11
            val y = i / 11
            val onEdge = x == 0 || y == 0 || x == 10 || y == 8
            if (!onEdge) assertTrue(flow.receiver[i] >= 0, "interior cell ($x,$y) must drain")
            assertTrue(flow.filled[i] >= g.values[i])
        }
        // The pit fills to its lowest rim cell, (7,6) at 83 m, and only float steps above it.
        assertTrue(flow.filled[4 * 11 + 5].let { it > 83f && it < 83.01f }, "pit filled to ${flow.filled[4 * 11 + 5]}")
        assertTrue(flow.maxRaiseMeters in 83.0..83.01)
    }

    @Test
    fun areaIsConservedAndTheNetworkFlowsDownhill() {
        val rnd = Random(7)
        val w = 60
        val h = 50
        val g = grid(w, h, 10.0) { x, y -> 500.0 + 3.0 * y + 2.0 * x + rnd.nextDouble() * 8.0 }
        val flow = FlowGrid.build(g)
        var outletArea = 0.0
        for (i in 0 until flow.size) {
            val r = flow.receiver[i]
            if (r < 0) outletArea += flow.areaM2[i] else assertTrue(flow.filled[r] < flow.filled[i], "cell $i drains uphill")
        }
        assertEquals(w * h * 100.0, outletArea, w * h * 100.0 * 1e-5)
        assertEquals(w * h, flow.order.size)
        // Every cell comes after its donors.
        val pos = IntArray(flow.size)
        for ((k, c) in flow.order.withIndex()) pos[c] = k
        for (i in 0 until flow.size) flow.receiver[i].takeIf { it >= 0 }?.let { assertTrue(pos[it] > pos[i]) }
    }

    @Test
    fun noDataIsOutsideAndItsNeighboursAreOutlets() {
        val g = grid(20, 20) { x, y -> if (x in 8..11 && y in 8..11) Double.NaN else 50.0 + abs(x - 9.5) + abs(y - 9.5) }
        val flow = FlowGrid.build(g)
        for (y in 8..11) for (x in 8..11) {
            val i = y * 20 + x
            assertEquals(-1, flow.receiver[i])
            assertEquals(0f, flow.areaM2[i])
            assertTrue(flow.filled[i].isNaN())
        }
        // Everything drains into the hole's rim (the lowest ground), none of it through no data.
        var outletArea = 0.0
        for (i in 0 until flow.size) if (flow.receiver[i] < 0 && !g.values[i].isNaN()) outletArea += flow.areaM2[i]
        assertEquals((400 - 16) * 900.0, outletArea, 1.0)
        assertEquals(400 - 16, flow.order.size)
    }

    @Test
    fun rowAreasAreUsedPerRow() {
        val g = grid(3, 4) { _, y -> 10.0 - y }
        val flow = FlowGrid.build(g, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        // The middle column of the bottom row gathers its own column at least.
        var total = 0.0
        for (i in 0 until flow.size) if (flow.receiver[i] < 0) total += flow.areaM2[i]
        assertEquals(3 * (1.0 + 2.0 + 3.0 + 4.0), total, 1e-4)
    }
}
