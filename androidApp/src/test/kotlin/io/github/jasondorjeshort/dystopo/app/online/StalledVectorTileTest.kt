package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * T138 (T123's leftover): a vector tile whose fetch stalled is answered 503, which MapLibre asks for again by itself, and
 * the fetcher fetches it again once its per-tile failure memo has passed, so the tile comes without a style reload. A tile
 * the source does not have stays a 404 (MapLibre draws it empty).
 */
class StalledVectorTileTest {
    private val dir: File = Files.createTempDirectory("stalled").toFile()
    private var now = 1_000_000L
    private val calls = AtomicInteger()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun statusPerLookup() {
        assertEquals(200, vectorTileStatus(Fetch.Hit(byteArrayOf(1), cached = false, source = "s")))
        assertEquals(404, vectorTileStatus(Fetch.Missing))
        assertEquals(503, vectorTileStatus(Fetch.Unavailable("SocketTimeoutException")))
    }

    @Test
    fun aStalledTileIsFetchedAgainAfterTheFailureMemo() {
        var stalled = true
        val f = OnlineFetcher("http://10.0.2.2:8765", TileCache(dir, 1 shl 20) { now }, { url ->
            if (url.endsWith("/version")) HttpReply(404, ByteArray(0))
            else {
                calls.incrementAndGet()
                if (stalled) throw SocketTimeoutException("Read timed out") else HttpReply(200, byteArrayOf(7, 7))
            }
        }, clock = { now })
        // The stall: 503, and MapLibre's first retries (1 s apart) are answered from the failure memo without a fetch.
        assertEquals(503, vectorTileStatus(f.get("osm", "11/400/700.pbf")))
        assertEquals(1, calls.get())
        stalled = false // the host answers again
        for (s in listOf(1, 2, 3, 5, 9, 17)) {
            now = 1_000_000L + s * 1_000L
            assertEquals(503, vectorTileStatus(f.get("osm", "11/400/700.pbf")), "retry at $s s")
        }
        assertEquals(1, calls.get(), "the retries inside the memo did not fetch")
        // MapLibre's next retry (33 s after the failure) is past the 30 s memo: fetched, drawn.
        now = 1_000_000L + 33_000L
        assertIs<Fetch.Hit>(f.get("osm", "11/400/700.pbf"))
        assertEquals(2, calls.get())
        assertEquals("online", f.state, "a stalled read is this tile's failure, not the server's")
    }

    @Test
    fun aFailedFetchIsNotAMiss() {
        val f = OnlineFetcher("http://10.0.2.2:8765", TileCache(dir, 1 shl 20) { now }, { url ->
            if (url.endsWith("/version")) HttpReply(404, ByteArray(0)) else throw IOException("stalled")
        }, clock = { now })
        assertIs<Fetch.Unavailable>(f.get("osm", "12/1/2.pbf"))
    }
}
