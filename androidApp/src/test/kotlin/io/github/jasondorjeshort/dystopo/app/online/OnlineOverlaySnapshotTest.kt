package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T144: the online indicator's snapshot ([OnlineUi]), made off the UI thread and only read by the composable: the lines it
 * shows for each state of the connection and of "Download this region".
 */
class OnlineOverlaySnapshotTest {
    private fun view(source: String) = OnlineView(51.48, -0.01, 13.0, io.github.jasondorjeshort.dystopo.packs.PackBounds(-0.05, 51.46, 0.03, 51.5), source)

    @Test
    fun hiddenWithNoServerAndNothingRunning() {
        assertTrue(OnlineUi("none", null, mapOf("state" to "idle"), running = false).hidden)
        assertFalse(OnlineUi("none", null, mapOf("state" to "downloading"), running = true).hidden)
    }

    @Test
    fun theChipNamesTheStateAndTheSource() {
        assertEquals("Online" to OnlineUi.Dot.GREEN, OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false).label)
        assertEquals("Paused" to OnlineUi.Dot.GREY, OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false, notice = "limit").label)
        assertEquals("Offline" to OnlineUi.Dot.RED, OnlineUi("offline", null, emptyMap(), false).label)
        assertEquals("Connecting" to OnlineUi.Dot.GREY, OnlineUi("unknown", null, emptyMap(), false).label)
        assertEquals("live from server", OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false).sourceText)
        assertEquals("live from public sources", OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false, direct = true).sourceText)
        assertEquals("from cache", OnlineUi("online", view(OnlineService.CACHED), emptyMap(), false).sourceText)
        assertEquals("pack", OnlineUi("online", view(OnlineTiles.PACK), emptyMap(), false).sourceText)
        assertEquals("no data here", OnlineUi("online", null, emptyMap(), false).sourceText)
    }

    @Test
    fun downloadOfferedOnlyOnlineOverNonPackGroundWithNothingRunning() {
        assertTrue(OnlineUi("online", view(OnlineService.CACHED), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", view(OnlineTiles.PACK), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("offline", view(OnlineService.CACHED), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", null, mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", view(OnlineService.CACHED), mapOf("state" to "building"), true).offerDownload)
    }

    @Test
    fun progressWhileRunning() {
        val d = mapOf<String, Any?>("state" to "building", "name" to "view-x", "percent" to 24.6, "packLabel" to "outdoors, Overpass")
        assertEquals("Building on server view-x (outdoors, Overpass): 25%", OnlineUi("online", null, d, true).progress)
        assertEquals("Building on this phone view-x (outdoors, Overpass): 25%", OnlineUi("online", null, d, true, direct = true).progress)
        assertEquals("Downloading view-x: 60%", OnlineUi("online", null, mapOf("state" to "downloading", "name" to "view-x", "percent" to 60), true).progress)
        assertNull(OnlineUi("online", null, d, false).progress)
    }

    @Test
    fun savedAsPackOnceTheViewIsThePacksAndFailuresInRed() {
        val done = mapOf<String, Any?>("state" to "done", "name" to "view-x", "packLabel" to "outdoors, Overpass")
        assertEquals("Saved as pack view-x · outdoors, Overpass" to null, OnlineUi("online", view(OnlineTiles.PACK), done, false).result)
        assertNull(OnlineUi("online", view(OnlineService.CACHED), done, false).result, "not before the map shows the pack")
        assertEquals("Download failed: HTTP 503" to OnlineUi.Dot.RED, OnlineUi("online", null, mapOf("state" to "failed", "error" to "HTTP 503"), false).result)
        assertNull(OnlineUi("online", null, mapOf("state" to "idle"), false).result)
    }
}
