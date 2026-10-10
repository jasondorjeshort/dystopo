package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourCutSteepTest {
    private val spec = ContourSpec(10.0, 5, ContourUnits.METERS)

    /** Gentle slope on the left (1 m/cell), a cliff in the middle (20 m/cell), gentle again on the right. */
    private fun terrain(): ElevationGrid {
        val w = 120
        val h = 40
        fun z(x: Int): Double = when {
            x < 40 -> x.toDouble()
            x < 60 -> 40.0 + (x - 40) * 20.0
            else -> 440.0 + (x - 60).toDouble()
        }
        return ElevationGrid(w, h, 10.0, FloatArray(w * h) { z(it % w).toFloat() })
    }

    @Test
    fun nonIndexLinesVanishInTheCliffButIndexLinesStay() {
        val grid = terrain()
        val levels = spec.levelsMeters(grid.min().toDouble(), grid.max().toDouble())
        val lines = Contours.trace(grid, levels)
        // With a 10 m interval and 20 m/cell gradient, lines in the cliff are 0.5 px apart.
        val cut = Contours.cutSteep(lines, grid, spec, minSpacing = 3.0)
        val cliffLevels = cut.filter { it.level > 45 && it.level < 435 }.map { it.level }.toSet()
        assertTrue(cliffLevels.isNotEmpty())
        assertTrue(cliffLevels.all { spec.isIndex(it) }, "non-index lines survived in the cliff: $cliffLevels")
        // Gentle slopes keep every line (1 m/cell gradient, 10 px apart).
        val gentle = cut.filter { it.level <= 35 || it.level >= 445 }.map { it.level }.toSet()
        assertEquals(lines.filter { it.level <= 35 || it.level >= 445 }.map { it.level }.toSet(), gentle)
    }

    @Test
    fun minSpacingZeroDisablesCutting() {
        val grid = terrain()
        val lines = Contours.trace(grid, spec.levelsMeters(grid.min().toDouble(), grid.max().toDouble()))
        assertEquals(lines, Contours.cutSteep(lines, grid, spec, minSpacing = 0.0))
    }

    @Test
    fun aLineCrossingACliffIsCutIntoPieces() {
        // Vertical cliff band across a horizontal slope: a contour running along x at constant z is
        // not what we want, so build a slope in y with a steep band in x and trace a level that
        // crosses the band.
        val w = 100
        val h = 60
        fun z(x: Int, y: Int): Double = y * 2.0 + if (x in 45..55) (x - 45) * 30.0 else if (x > 55) 300.0 else 0.0
        val grid = ElevationGrid(w, h, 10.0, FloatArray(w * h) { z(it % w, it / w).toFloat() })
        val lines = Contours.trace(grid, listOf(30.0, 50.0))
        val cut = Contours.cutSteep(lines, grid, spec, minSpacing = 3.0)
        // Level 30 is non-index; its piece inside the cliff band (x 45..) must be gone, the gentle part stays.
        val gentle = cut.filter { it.level == 30.0 }
        assertTrue(gentle.isNotEmpty())
        for (line in gentle) for (i in 0 until line.pointCount) {
            val x = line.points[2 * i]
            assertTrue(x < 45.0, "point at x=$x inside the cliff")
        }
        assertTrue(gentle.all { it.points[0] < 1e-9 || it.points[it.points.size - 2] < 1e-9 }, "gentle piece reaches the grid edge")
        // Level 50 is an index contour (every 5th 10 m line): kept whole, cliff included.
        assertEquals(lines.filter { it.level == 50.0 }, cut.filter { it.level == 50.0 })
    }
}
