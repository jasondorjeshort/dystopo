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
        assertTrue("1100 MB" in e && "1 GB budget" in e && "Settings › Storage" in e, e) // T115: the budget as the Storage screen's chips say it
    }

    @Test
    fun thePinnedBudgetGoesUpToTheCacheBudgetsTop() { // T115 (T112's follow-up): it stopped at 8 GB, below the Large preset
        val ok = io.github.jasondorjeshort.dystopo.app.SettingsRoutes.parse(mapOf("pinnedCacheMb" to "131072"))
        assertEquals(131072L, (ok as io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Ok).pinnedCacheMb)
        assertEquals(1024L * 1024, io.github.jasondorjeshort.dystopo.app.AppSettings.PINNED_CACHE_MB_RANGE.last)
        assertTrue(io.github.jasondorjeshort.dystopo.app.SettingsRoutes.parse(mapOf("pinnedCacheMb" to "2000000")) is io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Error)
        // The two caps stay where they were (the cache budget sets them; on their own they stop at 8 GB).
        assertTrue(io.github.jasondorjeshort.dystopo.app.SettingsRoutes.parse(mapOf("onlineCacheMb" to "131072")) is io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Error)
        // The screen offers the large sizes, and a 128 GB budget reads as one.
        assertTrue(131072L in io.github.jasondorjeshort.dystopo.app.storage.Storage.BUDGET_CHOICES_MB)
        val e = assertNotNull(budgetError(pinnedBytes = 0, estimateBytes = 200L * 1024 * mb, alreadyBytes = 0, budgetMb = 131072))
        assertTrue("over their 128 GB budget" in e, e)
        assertEquals("1.5 GB", io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(1536))
        assertEquals("512 MB", io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(512))
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
