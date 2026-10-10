package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cheap, image-free regression check: the report numbers `render` would print for synthetic
 * terrain. Catches contour tracing, cutting, simplification and label-placement changes with a
 * readable message, and runs even where image comparison is too strict.
 */
class RenderReportRegressionTest {
    private fun assertNear(expected: Int, actual: Int, tolerance: Int, what: String) =
        assertTrue(abs(actual - expected) <= tolerance, "$what: expected $expected ± $tolerance, got $actual")

    @Test
    fun mixedTerrainAtZoom13() {
        val case = GoldenCase(SyntheticTerrain.MIXED, zoom = 13, sizePx = 512)
        val grid = case.grid()
        val (_, stats) = MapRenderer.render(grid, case.zoom, case.style(), Timings())

        assertEquals(EXPECTED_MIN, grid.min().toDouble(), 0.5, "min elevation")
        assertEquals(EXPECTED_MAX, grid.max().toDouble(), 0.5, "max elevation")
        assertEquals(200.0, stats.interval, "z13 interval (ft)")
        assertEquals(5, stats.indexEvery)
        assertEquals(EXPECTED_LEVELS, stats.levels, "contour levels")
        assertNear(EXPECTED_LINES_TRACED, stats.linesTraced, 2, "lines traced")
        assertNear(EXPECTED_LINES, stats.lines, maxOf(3, EXPECTED_LINES / 10), "lines drawn")
        assertNear(EXPECTED_POINTS_DRAWN, stats.pointsDrawn, EXPECTED_POINTS_DRAWN / 10, "points drawn")
        // Label count depends on measured text width, so on the installed font: looser.
        assertNear(EXPECTED_LABELS, stats.labels, maxOf(1, EXPECTED_LABELS / 4), "labels")
    }

    private companion object {
        // Measured on 2026-10-09 (JDK 21, Windows). Update deliberately, with the reason in the commit.
        const val EXPECTED_MIN = 933.96
        const val EXPECTED_MAX = 1607.20
        const val EXPECTED_LEVELS = 11
        const val EXPECTED_LINES_TRACED = 33
        const val EXPECTED_LINES = 35
        const val EXPECTED_POINTS_DRAWN = 955
        const val EXPECTED_LABELS = 2
    }
}
