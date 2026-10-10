package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.options.ShadeService
import io.github.jasondorjeshort.dystopo.app.tracking.ReplayChecks
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T65 (T57 follow-ups): `/stats` counters stay cumulative when a reload replaces the tile service (a pack
 * install, a synced setting, `/reload`), now including the shade and cliff counters; and the replay 409.
 */
class CumulativeCountersTest {
    @Test
    fun cliffAndShadeCountersSurviveAReopen() {
        val counters = TileCounters()
        // First tile service: one cliff tile scanned (an empty tile has no cliffs, so it is passed through).
        val first = CliffTiles({ _, _, _ -> null }, counters.cliffs) { null }
        first.orient(13, 1, 1, ByteArray(0))
        first.orient(10, 1, 1, ByteArray(0)) // below MIN_ZOOM: not scanned
        assertEquals(1L, first.json()["tilesScanned"])
        // A reopen builds new services on the same counters: the totals carry on.
        val second = CliffTiles({ _, _, _ -> null }, counters.cliffs) { null }
        second.orient(14, 2, 2, ByteArray(0))
        assertEquals(2L, second.json()["tilesScanned"])
        assertEquals(2L, counters.cliffs.tilesScanned.get())

        val dir = Files.createTempDirectory("shade").toFile()
        try {
            val s1 = ShadeService(dir, "none", { _, _, _ -> null }, null, 1, counters = counters.shade)
            s1.served.served.incrementAndGet()
            s1.cacheHits.addAndGet(3)
            val s2 = ShadeService(dir, "none", { _, _, _ -> null }, null, 1, counters = counters.shade)
            assertSame(s1.stats, s2.stats)
            assertEquals(1L, s2.served.served.get())
            assertEquals(3L, s2.cacheHits.get())
            // Without shared counters (the old behaviour) a new service starts from zero.
            assertEquals(0L, ShadeService(dir, "none", { _, _, _ -> null }, null, 1).cacheHits.get())
        } finally {
            dir.deleteRecursively()
        }
        assertEquals(0L, counters.serviceOpens.get(), "opens are counted by the app, not by the services")
    }

    @Test
    fun replayIsRefusedWithRecordWhileRecording() {
        val conflict = ReplayChecks.refusal(rate = 100.0, bodyEmpty = false, record = "Skyline", recording = true)!!
        assertEquals(409, conflict.first)
        assertTrue("POST /tracks/stop" in conflict.second && "without record=" in conflict.second, conflict.second)
        // Without record= the fixes go into the recording; with record= and nothing recording it starts one.
        assertNull(ReplayChecks.refusal(100.0, bodyEmpty = false, record = null, recording = true))
        assertNull(ReplayChecks.refusal(100.0, bodyEmpty = false, record = "Skyline", recording = false))
        assertEquals(400, ReplayChecks.refusal(0.0, false, null, false)!!.first)
        assertEquals(400, ReplayChecks.refusal(10_001.0, false, null, false)!!.first)
        assertEquals(400, ReplayChecks.refusal(Double.NaN, false, null, false)!!.first)
        assertEquals(400, ReplayChecks.refusal(1.0, true, "x", true)!!.first, "a bad request is a 400 before the 409")
    }
}
