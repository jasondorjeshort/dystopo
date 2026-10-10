package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewLevelsTest {
    private val window = MercatorWindow(zoom = 10, x0 = 300 * 256, y0 = 360 * 256, width = 256, height = 256)

    @Test
    fun defaultsToTheWindowsOwnCellSize() {
        assertEquals(window.cellSizeMeters, OverviewLevels.targetMeters(window))
    }

    @Test
    fun overrideIsScopedAndPerThread() {
        OverviewLevels.withTarget(42.0) {
            assertEquals(42.0, OverviewLevels.targetMeters(window))
            OverviewLevels.withTarget(7.0) { assertEquals(7.0, OverviewLevels.targetMeters(window)) }
            assertEquals(42.0, OverviewLevels.targetMeters(window), "inner scope restores the outer one")
            var other = 0.0
            thread { other = OverviewLevels.targetMeters(window) }.join()
            assertEquals(window.cellSizeMeters, other, "other threads are not affected")
        }
        assertEquals(window.cellSizeMeters, OverviewLevels.targetMeters(window))
    }

    @Test
    fun aTallExportUsesOneTargetForEveryTile() {
        // 30 to 60 degrees north at z6: tile cell sizes differ by ~1.7x from top to bottom, so
        // per-window choices would straddle several 2x overview steps.
        val plan = TileWindows.forBbox(BoundingBox(-122.0, 30.0, -121.0, 60.0), 6, featherMeters = 300.0)
        val sizes = plan.map { it.window.cellSizeMeters }
        assertTrue(sizes.max() / sizes.min() > 1.5, "sizes ${sizes.min()}..${sizes.max()}")
        val target = TilesCommand.overviewTargetMeters(plan)
        assertEquals(sizes.min(), target)
        // The finest tile decides, so no tile reads coarser data than its own pixels.
        assertTrue(sizes.all { target <= it })
    }
}
