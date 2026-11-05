package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.packs.DownloadStatus
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T222: a region job's wrap-up through a kill and the words of a stop in its build phase. */
class RegionWrapUpTest {
    private val area = AreaOfInterest(AreaShape.Box(-121.85, 46.8, -121.7, 46.9), 8, 14, "rainier")

    @Test
    fun theWrapUpSurvivesTheStateFileAndWritesTheSameRecord() {
        val block = RegionDownload.regionBlock(area, "http://server:8080", 1_000L, 5_000L)
        // Through the state file, as a kill leaves it.
        val back = MiniJson.parseObject(MiniJson.encode(mapOf("region" to block)))["region"]
        @Suppress("UNCHECKED_CAST")
        val r = RegionDownload.recordOf("rainier", back as Map<String, Any?>, 4_000L, 61_000L)
        assertEquals(area.id, r.id, "the same id, so the record is the area the job started")
        assertEquals("rainier", r.pack)
        assertEquals("rainier", r.name)
        assertEquals("pack", r.kind)
        assertEquals("done", r.state)
        assertTrue(r.pinned)
        assertEquals("http://server:8080", r.source)
        assertEquals(4_000L, r.bytes)
        assertEquals(5_000L, r.estimateBytes)
        assertEquals(60_000L, r.downloadMs)
        assertEquals(1_000L, r.createdAt)
    }

    @Test
    fun withoutAnEstimateTheRecordUsesTheBytes() {
        val r = RegionDownload.recordOf("rainier", RegionDownload.regionBlock(area, "http://s", 1L, null), 7L, 2L)
        assertEquals(7L, r.estimateBytes)
    }

    @Test
    fun aStopInTheBuildPhaseReadsDownloadStoppedWithRetryAndKeepsNoProgress() {
        val row = DownloadStatus.of("failed", DownloadStatus.classify(java.io.IOException("server build failed: no data")))
        assertEquals("Download stopped: the map could not be built.", row.sentence)
        assertEquals(listOf("Retry", "Remove"), row.actions.map { it.label })
        // The connection lost while following the server's build reads as the connection.
        assertEquals("Download stopped: the connection dropped.", DownloadStatus.of("failed", DownloadStatus.classify(java.net.ConnectException("failed to connect"))).sentence)
        // The first-run card prints the reason after its own "Download failed: ".
        assertEquals("the connection dropped", RegionDownload.reasonOf(DownloadStatus.Failure.CONNECTION, null))
        assertEquals("Not enough space. Free 2 MB or remove a download.", RegionDownload.reasonOf(DownloadStatus.Failure.SPACE, 2L * 1_048_576))
    }

    @Test
    fun theOverlayShowsTheSentenceAndTheControlsOnTheMap() {
        val stopped = OnlineUi("online", null, mapOf("state" to "failed", "error" to "the connection dropped", "sentence" to "Download stopped: the connection dropped.", "actions" to listOf("Retry", "Remove")), false)
        assertEquals("Download stopped: the connection dropped." to OnlineUi.Dot.RED, stopped.result)
        assertEquals(listOf("Retry"), stopped.resultActions, "Remove is the row's, in the pack list")
        val paused = OnlineUi("online", null, mapOf("state" to "paused", "sentence" to "Paused at 40%. Continues when you are online", "actions" to listOf("Resume now", "Remove")), false)
        assertEquals("Paused at 40%. Continues when you are online" to null, paused.result)
        assertEquals(listOf("Resume now"), paused.resultActions)
        val space = OnlineUi("online", null, mapOf("state" to "failed", "sentence" to "Not enough space. Free 300 MB or remove a download.", "actions" to listOf("Storage", "Retry", "Remove")), false)
        assertEquals(listOf("Storage", "Retry"), space.resultActions)
        assertNull(OnlineUi("online", null, mapOf("state" to "idle"), false).result)
    }
}
