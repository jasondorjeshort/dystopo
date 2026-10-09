package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContoursTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Double) =
        ElevationGrid(w, h, 10.0, FloatArray(w * h) { f(it % w, it / w).toFloat() })

    private val cone = grid(101, 101) { x, y -> 100.0 - hypot(x - 50.0, y - 50.0) }

    @Test
    fun levelsAreStrictlyInsideRange() {
        assertEquals(listOf(10.0, 20.0, 30.0), Contours.levels(0.0, 40.0, 10.0))
        assertEquals(listOf(10.0, 20.0), Contours.levels(5.0, 25.0, 10.0))
        assertEquals(emptyList(), Contours.levels(1.0, 9.0, 10.0))
    }

    @Test
    fun coneGivesOneClosedCircle() {
        val lines = Contours.trace(cone, listOf(70.5))
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.closed)
        for (i in 0 until line.pointCount) {
            val r = hypot(line.points[2 * i] - 50.0, line.points[2 * i + 1] - 50.0)
            assertEquals(29.5, r, 0.1)
        }
    }

    @Test
    fun planeGivesOneOpenLineAcrossTheGrid() {
        val plane = grid(20, 10) { x, _ -> x.toDouble() }
        val line = Contours.trace(plane, listOf(7.25)).single()
        assertFalse(line.closed)
        assertEquals(10, line.pointCount)
        for (i in 0 until line.pointCount) assertEquals(7.25, line.points[2 * i], 1e-9)
        val ys = (0 until line.pointCount).map { line.points[2 * it + 1] }.toSet()
        assertEquals((0..9).map { it.toDouble() }.toSet(), ys)
    }

    @Test
    fun manyLevelsInOnePass() {
        val levels = Contours.levels(cone.min().toDouble(), cone.max().toDouble(), 10.0)
        val lines = Contours.trace(cone, levels)
        // Levels 60..100 are full circles; lower ones are clipped by the grid edge.
        assertEquals(levels.toSet(), lines.map { it.level }.toSet())
        assertTrue(lines.filter { it.level >= 60.0 }.all { it.closed })
        assertTrue(lines.filter { it.level <= 40.0 }.none { it.closed })
    }

    @Test
    fun outOfRangeLevelGivesNothing() {
        assertTrue(Contours.trace(cone, listOf(500.0)).isEmpty())
    }
}
