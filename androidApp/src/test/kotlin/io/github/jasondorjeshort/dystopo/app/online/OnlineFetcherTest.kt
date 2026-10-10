package io.github.jasondorjeshort.dystopo.app.online

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OnlineFetcherTest {
    private val dir: File = Files.createTempDirectory("fetcher").toFile()
    private var now = 1_000_000L
    private val calls = AtomicInteger()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** A fake server: [handler] answers each URL (or throws). */
    private fun fetcher(handler: (String) -> HttpReply) =
        OnlineFetcher("http://10.0.2.2:8765", TileCache(dir, 1 shl 20) { now }, { url -> calls.incrementAndGet(); handler(url) }, clock = { now })

    @Test
    fun fetchesOnceThenServesFromTheCache() {
        val f = fetcher { url -> HttpReply(200, url.toByteArray()) }
        val first = assertIs<Fetch.Hit>(f.get("dem", "12/1/2.png"))
        assertEquals(false, first.cached)
        assertContentEquals("http://10.0.2.2:8765/dem/12/1/2.png".toByteArray(), first.bytes)
        val second = assertIs<Fetch.Hit>(f.get("dem", "12/1/2.png"))
        assertEquals(true, second.cached)
        assertEquals(1, calls.get())
        assertEquals("online", f.state)
        assertTrue(File(dir, "10.0.2.2_8765/dem/12/1/2.png").isFile, "cached under the server's key")
    }

    @Test
    fun notFoundIsRememberedForAWhile() {
        val f = fetcher { HttpReply(404, ByteArray(0)) }
        assertIs<Fetch.Missing>(f.get("dem", "6/0/0.png"))
        assertIs<Fetch.Missing>(f.get("dem", "6/0/0.png"))
        assertEquals(1, calls.get(), "second lookup answered by the negative cache")
        now += 11 * 60_000L
        assertIs<Fetch.Missing>(f.get("dem", "6/0/0.png"))
        assertEquals(2, calls.get(), "asked again after the TTL")
    }

    @Test
    fun aViewChangeRetriesOldNegativeEntriesOnly() {
        var status = 404
        val f = fetcher { HttpReply(status, byteArrayOf(1)) }
        assertIs<Fetch.Missing>(f.get("dem", "9/1/1.png"))
        status = 200
        // Moments later (same view, a small pan): still remembered, no new request.
        now += 5_000L
        assertEquals(0, f.retryNegative())
        assertIs<Fetch.Missing>(f.get("dem", "9/1/1.png"))
        assertEquals(1, calls.get())
        // Later, the view changes: the 404 is forgotten and the tile is asked for again.
        now += 15_000L
        assertEquals(1, f.retryNegative())
        assertIs<Fetch.Hit>(f.get("dem", "9/1/1.png"))
        assertEquals(2, calls.get())
    }

    @Test
    fun unreachableServerBacksOffAndRecovers() {
        var up = false
        val f = fetcher { if (up) HttpReply(200, byteArrayOf(7)) else throw ConnectFailed("connection refused") }
        assertIs<Fetch.Unavailable>(f.get("dem", "8/1/1.png"))
        assertEquals("offline", f.state)
        // Other tiles fail fast without touching the network during the backoff.
        repeat(20) { assertIs<Fetch.Unavailable>(f.get("dem", "8/1/${it + 2}.png")) }
        assertEquals(1, calls.get())
        assertEquals(20L, f.backoffSkips.get())
        now += 6_000 // past the first 5 s backoff
        up = true
        assertIs<Fetch.Hit>(f.get("dem", "8/1/2.png"))
        assertEquals("online", f.state)
        assertEquals(1, f.generation, "one offline-to-online transition")
    }

    @Test
    fun cachedTilesStillServeWhileOffline() {
        var up = true
        val f = fetcher { if (up) HttpReply(200, byteArrayOf(1)) else throw ConnectFailed("network unreachable") }
        assertIs<Fetch.Hit>(f.get("osm", "10/5/5.pbf"))
        up = false
        assertIs<Fetch.Unavailable>(f.get("osm", "10/5/6.pbf"))
        val hit = assertIs<Fetch.Hit>(f.get("osm", "10/5/5.pbf"))
        assertTrue(hit.cached)
    }

    @Test
    fun aReadFailureOnlyBlocksThatTile() {
        val f = fetcher { url -> if (url.endsWith("bad.png")) throw IOException("read timed out") else HttpReply(200, byteArrayOf(2)) }
        assertIs<Fetch.Unavailable>(f.get("dem", "9/bad.png"))
        assertIs<Fetch.Unavailable>(f.get("dem", "9/bad.png"))
        assertEquals(1, calls.get(), "the failed tile waits out its short TTL")
        assertIs<Fetch.Hit>(f.get("dem", "9/good.png"))
        assertEquals("online", f.state)
        now += 31_000
        assertIs<Fetch.Unavailable>(f.get("dem", "9/bad.png"))
        assertEquals(3, calls.get())
    }

    @Test
    fun concurrentRequestsForOneTileShareAFetch() {
        val release = CountDownLatch(1)
        val f = fetcher {
            release.await(5, TimeUnit.SECONDS)
            HttpReply(200, byteArrayOf(9))
        }
        val pool = Executors.newFixedThreadPool(4)
        val results = (1..4).map { pool.submit<Fetch> { f.get("dem", "10/1/1.png") } }
        Thread.sleep(200)
        release.countDown()
        results.forEach { assertIs<Fetch.Hit>(it.get(5, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, calls.get())
    }

    @Test
    fun gzippedVectorTilesAreInflated() {
        val raw = "vector tile".toByteArray()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        val f = fetcher { HttpReply(200, gz) }
        val hit = assertIs<Fetch.Hit>(f.get("osm", "3/1/1.pbf", OnlineFetcher::gunzipIfNeeded))
        assertContentEquals(raw, hit.bytes)
        assertContentEquals(raw, OnlineFetcher.gunzipIfNeeded(raw), "plain bytes pass through")
    }

    @Test
    fun serverKeysArePathSafe() {
        assertEquals("10.0.2.2_8765", OnlineFetcher.keyFor("http://10.0.2.2:8765/"))
        assertEquals("tiles.example.org_dystopo", OnlineFetcher.keyFor("https://tiles.example.org/dystopo"))
    }
}
