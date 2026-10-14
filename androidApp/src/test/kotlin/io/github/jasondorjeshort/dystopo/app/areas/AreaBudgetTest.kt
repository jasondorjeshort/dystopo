package io.github.jasondorjeshort.dystopo.app.areas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T91: the pinned budget check of an area download, and a trip corridor's default zooms by mode. */
class AreaBudgetTest {
    private val mb = 1024L * 1024L

    @Test
    fun aDownloadThatFitsTheBudgetStarts() {
        assertNull(budgetError(pinnedBytes = 500 * mb, estimateBytes = 400 * mb, alreadyBytes = 0, budgetMb = 1024))
    }

    @Test
    fun aDownloadOverTheBudgetIsRefusedWithTheNumbers() {
        val e = assertNotNull(budgetError(pinnedBytes = 900 * mb, estimateBytes = 200 * mb, alreadyBytes = 0, budgetMb = 1024))
        assertTrue("1100 MB" in e && "1024 MB budget" in e && "Settings › Storage" in e, e)
    }

    @Test
    fun downloadingAnAreaAgainDoesNotCountItsOwnTilesTwice() {
        assertNull(budgetError(pinnedBytes = 900 * mb, estimateBytes = 200 * mb, alreadyBytes = 200 * mb, budgetMb = 1024))
    }

    @Test
    fun aDriveCorridorStopsAtZ13AndAWalkAtZ15() {
        assertEquals(13, corridorMaxZoom("drive"))
        assertEquals(15, corridorMaxZoom("hike"))
        assertEquals(15, corridorMaxZoom("bike"))
        assertEquals(15, corridorMaxZoom(null))
    }
}
