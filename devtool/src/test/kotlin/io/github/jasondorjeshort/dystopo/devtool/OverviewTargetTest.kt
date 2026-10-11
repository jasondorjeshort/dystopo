package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export's one overview target per zoom (the rest of OverviewLevelsTest moved to shared with the readers, T80). */
class OverviewTargetTest {
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