package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.test.Test
import kotlin.test.assertEquals

/** T87: the Hawaii z9 contour 500 (`IndexOutOfBoundsException: Index 5 out of bounds for length 5`). */
class CommonLevelTest {
    private val arcSecond = 1.0 / 3600

    /** A 1/3 arc-second 3DEP cell with [levels] levels, each overview twice the pixel size of the one before. */
    private fun cell(levels: Int) = List(levels) { i -> arcSecond / 3 * (1 shl i) }

    @Test
    fun aWindowOverCellsWithFewerOverviewsGetsALevelEveryCellHas() {
        val full = cell(7)
        val oceanCell = cell(5)
        // A z9 window wants about 400 m cells: the full cell would read its level 5 (330 m), which the ocean cell lacks.
        val target = 400.0
        assertEquals(5, RemoteTiff.levelFor(full, target, 111_320.0))
        assertEquals(4, RemoteTiff.levelFor(oceanCell, target, 111_320.0))
        // In either order (ThreeDepSource used to take the first cell's choice).
        assertEquals(4, RemoteTiff.commonLevel(listOf(full, oceanCell), target, 111_320.0))
        assertEquals(4, RemoteTiff.commonLevel(listOf(oceanCell, full), target, 111_320.0))
    }

    @Test
    fun noCellsIsLevelZero() {
        assertEquals(0, RemoteTiff.commonLevel(emptyList(), 30.0, 111_320.0))
    }
}
