package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T155 (T138's follow-up): a DEM tile whose fetch failed is recorded, fetched again (at once when a source answers again),
 * and the elevation source with its derived contour and shade sources replaced once per settle window.
 */
class StalledDemTileTest {
    private val dir: File = Files.createTempDirectory("stalled-dem").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun aSourceThatAnswersAgainMakesEveryRecordedTileDue() {
        val s = StalledOsmTiles()
        assertTrue(s.failed("12/662/1443.png", now))
        assertFalse(s.failed("12/662/1443.png", now + 1), "already recorded: keeps its schedule")
        assertTrue(s.failed("12/663/1443.png", now + 2))
        assertEquals("12/662/1443.png", s.oldest())
        assertEquals(emptyList(), s.due(now + 5_000), "the backoff's first retry is 30 s away")
        assertEquals(2, s.wake(now + 5_000))
        assertEquals(listOf("12/662/1443.png", "12/663/1443.png"), s.due(now + 5_000))
        assertEquals(1L, s.woken)
        s.result("12/662/1443.png", ok = true, now = now + 6_000)
        s.result("12/663/1443.png", ok = true, now = now + 6_000)
        assertNull(s.oldest())
        assertEquals(0, s.wake(now + 7_000), "nothing to wake")
        assertEquals(2L, s.recovered)
    }

    @Test
    fun aRetryBatchIsDrawnAtOnceAndNeverTwiceInAWindow() {
        val c = OsmRefreshCoalescer(settleMs = 10_000, quietMs = 0)
        c.recovered(0, count = 15) // one batch, as OnlineService reports it
        assertTrue(c.claim(0), "quiet gap 0: the refresh follows the batch")
        assertFalse(c.claim(0), "claimed once")
        c.recovered(3_000, count = 2)
        assertFalse(c.claim(3_000))
        assertFalse(c.claim(9_999))
        assertTrue(c.claim(10_000))
        assertEquals(2L, c.refreshes)
        assertEquals(14L + 1L, c.coalesced)
    }

    @Test
    fun theElevationSourceAndItsDerivedSourcesAreReadFromTheStyle() {
        val json = """{"version":8,"sources":{
            "dem":{"type":"raster-dem","tiles":["http://127.0.0.1:1/dem/{z}/{x}/{y}.png?v=none-sources-0-d2&t=secret"],"tileSize":256,"encoding":"terrarium","minzoom":6,"maxzoom":15},
            "contours":{"type":"vector","tiles":["http://127.0.0.1:1/contours/{z}/{x}/{y}.pbf?v=none-sources-0-d2-ft&t=secret"],"minzoom":8,"maxzoom":16},
            "shade":{"type":"raster","tiles":["http://127.0.0.1:1/shade/{z}/{x}/{y}.png?v=none-multi-d2&t=secret"],"tileSize":256},
            "osm":{"type":"vector","tiles":["http://127.0.0.1:1/osm/{z}/{x}/{y}.pbf?v=a-c1&t=secret"]}},"layers":[]}"""
        val s = DemSourceRefresh.sources(json)
        assertEquals(listOf("dem", "contours", "shade"), s.keys.toList(), "the vector map is not one of them")
        assertEquals("terrarium", s.getValue("dem")["encoding"])
        assertEquals("http://127.0.0.1:1/dem/{z}/{x}/{y}.png?v=none-sources-0-d2", DemSourceRefresh.urlOf(s.getValue("dem")), "no token in the record")
        val noShade = json.replace("\"shade\":", "\"other\":")
        assertEquals(listOf("dem", "contours"), DemSourceRefresh.sources(noShade).keys.toList(), "shade only when a map option draws it")
    }

    @Test
    fun haloVariantsNamingARecoveredTileAreFound() {
        val key = "sources/contours-v9-ft-512@x/12/660/1440.halo-13-1320-2881_13-1321-2881.pbf"
        assertTrue(OnlineTiles.namesHaloTile(key, setOf("13/1321/2881.png")))
        assertFalse(OnlineTiles.namesHaloTile(key, setOf("13/1321/2882.png")))
        assertFalse(OnlineTiles.namesHaloTile("sources/contours-v9-ft-512@x/12/660/1440.pbf", setOf("13/1321/2881.png")), "the whole tile is not a variant")
        val made = OnlineTiles.contourKey("sources/contours/12/1/2.pbf", listOf(Triple(13, 4, 5)))
        assertTrue(OnlineTiles.namesHaloTile(made, setOf("13/4/5.png")), "the key contourKey writes")
    }

    @Test
    fun aRenderThatFailedOnTheNetworkFailsAgainAtOnceForAWhile() {
        val f = FailedRenders(ttlMs = 30_000)
        assertFalse(f.isBlocked("k", now))
        f.failed("k", now)
        assertTrue(f.isBlocked("k", now + 29_999))
        assertFalse(f.isBlocked("k", now + 30_000), "expired")
        f.failed("k", now)
        f.forget("k") // the stall recovery renders it again
        assertFalse(f.isBlocked("k", now + 1))
        f.failed("a", now)
        f.failed("b", now)
        assertEquals(2, f.size(now))
        assertEquals(2, f.clear())
        assertEquals(1L, f.blocked)
    }

    @Test
    fun aHostWhoseReadsAllStallIsAskedOnlyEveryProbeInterval() {
        val b = StallBreaker()
        val host = "10.0.2.2:8751"
        repeat(StallBreaker.TRIP_STALLS - 1) { b.stalled(host, now) }
        assertTrue(b.admit(host, now), "not yet: five stalls")
        b.stalled(host, now)
        assertEquals(listOf(host), b.openHosts())
        assertFalse(b.admit(host, now + 1), "open: refused at once")
        assertTrue(b.admit(host, now + StallBreaker.PROBE_MS), "one probe per interval")
        assertFalse(b.admit(host, now + StallBreaker.PROBE_MS + 1))
        assertTrue(b.admit("other.example:443", now + 1), "per host")
        b.answered(host, now + StallBreaker.PROBE_MS + 500)
        assertTrue(b.admit(host, now + StallBreaker.PROBE_MS + 501), "the probe answered: closed")
        assertEquals(1L, b.opened)
    }

    @Test
    fun aHealthyLinkWhoseReadsStallNowAndThenNeverOpens() {
        val b = StallBreaker()
        val host = "prd-tnm.s3.amazonaws.com"
        var t = now
        repeat(200) { i ->
            if (i % 5 == 0) b.stalled(host, t) else b.answered(host, t) // T88: one read in five stalled on the emulator
            t += 300
        }
        assertTrue(b.openHosts().isEmpty())
        // Stalls in a row soon after an answer do not open it either (the quiet time since the last answer).
        repeat(StallBreaker.TRIP_STALLS * 2) { b.stalled(host, t + 4_000) }
        assertTrue(b.openHosts().isEmpty())
        b.stalled(host, t + StallBreaker.TRIP_QUIET_MS)
        assertEquals(listOf(host), b.openHosts(), "no answer for the quiet time: open")
    }

    @Test
    fun aRecoveredDirectTileInTheCacheAnswersTheMapPastTheFetchersFailureMemo() {
        val calls = AtomicInteger()
        val cache = TileCache(dir, 1 shl 20) { now }
        val f = OnlineFetcher(DirectSources.SERVER, cache, { url ->
            if (url.endsWith("/version")) HttpReply(404, ByteArray(0))
            else {
                calls.incrementAndGet()
                throw SocketTimeoutException("Read timed out")
            }
        }, clock = { now })
        val path = "13/1328/2884.png"
        assertIs<Fetch.Unavailable>(f.get("dem", path))
        now += 5_000
        assertIs<Fetch.Unavailable>(f.get("dem", path), "the 30 s memo")
        val n = calls.get()
        // DirectSources.retryDem puts the recovered tile where the fetcher looks first.
        cache.put("${DirectSources.KEY}/dem/$path", byteArrayOf(1, 2, 3))
        val hit = f.get("dem", path)
        assertIs<Fetch.Hit>(hit, "the map's request after the refresh is answered from the cache")
        assertTrue(hit.cached)
        assertEquals(n, calls.get(), "no network")
    }
}
