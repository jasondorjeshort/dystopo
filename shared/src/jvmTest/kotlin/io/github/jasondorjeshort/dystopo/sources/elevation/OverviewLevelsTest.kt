package io.github.jasondorjeshort.dystopo.sources.elevation

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
}
