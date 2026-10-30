package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrainageTest {
    private val w = 41
    private val h = 121
    private val axis = 20
    private val cell = 30.0

    /**
     * A V-shaped valley falling south down column [axis]: 9 m a cell up each side, 1.5 m a row down the axis. Low
     * rims on the west, east and north edges (half a metre below the ridge cell inside them) make those edge cells
     * outlets, so the valley's catchment is all inside the grid; the bottom row is flat at 0 m, the outlet.
     */
    private fun valleyHeight(x: Int, y: Int): Double {
        if (y == h - 1) return 0.0
        val xi = x.coerceIn(1, w - 2)
        val yi = y.coerceAtLeast(1)
        val z = 9.0 * abs(xi - axis) + 1.5 * (h - 1 - yi) + 10.0
        return if (x != xi || y != yi) z - 0.5 else z
    }

    private fun valley(edit: (Int, Int, Double) -> Double = { _, _, z -> z }) =
        ElevationGrid(w, h, cell, FloatArray(w * h) { edit(it % w, it / w, valleyHeight(it % w, it / w)).toFloat() })

    /** The axis cell on the outlet row: rows 1 to h-3 of the valley, the axis cell above it and itself. */
    private val bottomKm2 = (39 * (h - 3) + 2) * cell * cell / 1e6

    /** The lines ordered down the valley; with [onAxis], every point must lie on the axis. */
    private fun axisLines(result: DrainageResult, onAxis: Boolean = true): List<DrainageLine> {
        if (onAxis) for (line in result.lines) {
            for (k in 0 until line.pointCount) assertEquals(axis.toDouble(), line.points[2 * k], "every channel point on the axis")
        }
        return result.lines.sortedBy { it.points[1] }
    }

    @Test
    fun oneChannelDownTheAxisWithTheCatchmentGrowingDownstream() {
        val result = Drainage.extract(valley())
        val lines = axisLines(result)
        // The axis cell at row y gathers rows 1..y of the 39 cells between the ridges (the row above the flat
        // outlet row drains straight into it, so it stops one row short).
        val flow = FlowGrid.build(valley())
        for (y in 1..h - 3) {
            assertEquals(39 * y * cell * cell, flow.areaM2[y * w + axis].toDouble(), 39 * y * cell * cell * 1e-5)
            assertFalse(flow.open[y * w + axis], "row $y: catchment inside the grid")
        }
        // The axis slope is about 2.9 degrees, so the gate is at its upper clamp (4 x 0.1 km2): the channel starts
        // at the first row past 0.4 km2, row 12, and runs unbroken to the bottom row.
        assertEquals(12.0, lines.first().points[1])
        assertEquals((h - 1).toDouble(), lines.last().points[lines.last().points.size - 1])
        for (k in 1 until lines.size) {
            val prev = lines[k - 1]
            assertEquals(prev.points[prev.points.size - 1], lines[k].points[1], "lines join end to start")
            assertTrue(lines[k].catchmentKm2 > prev.catchmentKm2)
        }
        // Small at the head, seasonal further down (the catchment's mean slope, about 17 degrees, pulls the 1 km2
        // boundary a little lower), never back to a lower class.
        assertEquals(listOf(StreamClass.LIKELY_DRY, StreamClass.SEASONAL), lines.map { it.streamClass })
        val boundaryRow = lines[1].points[1].toInt()
        val boundaryArea = 39 * boundaryRow * cell * cell / 1e6
        assertTrue(boundaryArea in 0.9..1.0, "class change at $boundaryArea km2")
        assertTrue(lines.all { it.catchmentComplete })
        assertEquals(bottomKm2, lines.last().catchmentKm2, 1e-3)
    }

    @Test
    fun aPitOnTheAxisIsFilledAndTheChannelRunsThroughIt() {
        val lines = axisLines(Drainage.extract(valley { x, y, z -> if (x == axis && y == 60) z - 10 else z }))
        val rows = lines.flatMap { l -> (0 until l.pointCount).map { l.points[2 * it + 1].toInt() } }.toSet()
        assertEquals((12..h - 1).toSet(), rows)
    }

    @Test
    fun flatGroundAndMaskedCellsAreNotDrawnButTheNetworkRunsOn() {
        // A level floor across rows 70 to 80 between the rims (a lake bed) and a mask over rows 40 to 45 (a water
        // polygon). The rim cells beside the floor are higher than it and drain into it.
        val floor = valleyHeight(axis, 80)
        val g = valley { x, y, z -> if (y in 70..80 && x in 1..w - 2) floor else z }
        val mask = BooleanArray(w * h) { it / w in 40..45 }
        val result = Drainage.extract(g, suppress = mask)
        // The lake floor drains along its own epsilon gradient, which need not keep to the axis.
        val lines = axisLines(result, onAxis = false)
        val rows = lines.flatMap { l -> (0 until l.pointCount).map { l.points[2 * it + 1].toInt() } }
        assertTrue(rows.none { it in 40..45 }, "masked rows drawn")
        assertTrue(rows.none { it in 71..79 }, "flat rows drawn")
        assertTrue(result.stats.maskSuppressed == 6 && result.stats.flatSuppressed >= 9, "${result.stats}")
        // Below the lake the channel carries everything above it, and the two rim columns down to the lake's last
        // row (81 cells each), which now drain into it instead of along the edge.
        assertEquals(bottomKm2 + 2 * 81 * cell * cell / 1e6, lines.last().catchmentKm2, 1e-3)
    }

    @Test
    fun aValleyOpenToTheEdgeReportsALowerBound() {
        // Without the rims the edge cells drain inward: the area may continue beyond the grid.
        val g = ElevationGrid(w, h, cell, FloatArray(w * h) { 9.0f * abs(it % w - axis) + 1.5f * (h - 1 - it / w) })
        val lines = Drainage.extract(g).lines
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.catchmentComplete })
    }

    @Test
    fun shortHeadwaterPiecesAreDropped() {
        // A raised threshold leaves only the last few rows over it: fewer than minCells cells is dropped.
        val params = DrainageParams(thresholdKm2 = 39 * 117 * cell * cell / 1e6 / 4.0)
        val result = Drainage.extract(valley(), params)
        assertTrue(result.lines.isEmpty())
        assertEquals(1, result.stats.droppedShort)
    }
}
