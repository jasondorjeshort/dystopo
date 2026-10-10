package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hillshade and slope computed from one shared [Gradient] must match computing each from the grid. */
class GradientTest {
    private fun terrain() = SyntheticTerrain.MIXED.grid(96, 80, 12.0, 0.0, 0.0).let { g ->
        // Punch a no-data hole so the NaN handling is shared too.
        val values = g.values.copyOf()
        for (y in 30 until 36) for (x in 40 until 47) values[y * g.width + x] = Float.NaN
        ElevationGrid(g.width, g.height, g.cellSizeMeters, values)
    }

    @Test
    fun sharedGradientGivesIdenticalResultsAtZFactorOne() {
        val grid = terrain()
        val gradient = Gradient.of(grid)
        assertContentEquals(Slope.compute(grid), Slope.compute(gradient))
        assertContentEquals(Hillshade.compute(grid), Hillshade.compute(gradient))
        assertContentEquals(Hillshade.compute(grid, 200.0, 30.0), Hillshade.compute(gradient, 200.0, 30.0))
        assertContentEquals(Hillshade.multidirectional(grid), Hillshade.multidirectional(gradient))
        assertTrue(Slope.compute(gradient).any { it.isNaN() }, "hole stays no-data")
    }

    @Test
    fun zFactorScalesTheSharedGradient() {
        val grid = terrain()
        val gradient = Gradient.of(grid)
        for (z in listOf(0.5, 2.0, 3.0)) {
            val direct = Hillshade.compute(grid, zFactor = z)
            val shared = Hillshade.compute(gradient, zFactor = z)
            val directMulti = Hillshade.multidirectional(grid, zFactor = z)
            val sharedMulti = Hillshade.multidirectional(gradient, zFactor = z)
            for (i in direct.indices) {
                if (direct[i].isNaN()) {
                    assertTrue(shared[i].isNaN())
                    continue
                }
                // Only the order of two multiplications differs.
                assertEquals(direct[i], shared[i], 1e-5f)
                assertEquals(directMulti[i], sharedMulti[i], 1e-5f)
            }
        }
    }
}
