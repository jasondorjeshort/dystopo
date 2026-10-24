package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T138 (T123's leftover): a vector tile whose fetch stalled is recorded, fetched again once the fetcher's per-tile failure
 * memo has passed, and counted as recovered when it comes (the map's vector source is then refreshed, no style reload).
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
    fun retriesAfterTheMemoThenBacksOff() {
        val s = StalledOsmTiles()
        s.failed("11/400/700.pbf", now)
        s.failed("11/400/700.pbf", now + 5_000) // asked again by a second request: keeps its schedule
        assertEquals(1, s.size)
        assertEquals(emptyList(), s.due(now + 29_000))
        assertEquals(listOf("11/400/700.pbf"), s.due(now + 30_000))
        s.result("11/400/700.pbf", ok = false, now = now + 30_000)
        assertEquals(emptyList(), s.due(now + 89_000), "the wait doubles to 60 s")
        assertEquals(listOf("11/400/700.pbf"), s.due(now + 90_000))
        s.result("11/400/700.pbf", ok = true, now = now + 90_000)
        assertEquals(0, s.size)
        assertEquals(1L, s.recovered)
        assertEquals(2L, s.retried)
    }

    @Test
    fun aStyleLoadOrALongWaitDropsTheEntries() {
        val s = StalledOsmTiles(capacity = 3)
        s.failed("a", now)
        s.failed("b", now + 1)
        s.styleLoaded(now + 1)
        assertEquals(0, s.size)
        assertEquals(2L, s.droppedByStyleLoad)
        for (p in listOf("c", "d", "e", "f")) s.failed(p, now + 10)
        assertEquals(3, s.size, "the oldest out first")
        s.due(now + 10 + s.giveUpMs + 1)
        assertEquals(0, s.size)
        assertEquals(3L, s.givenUp)
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
        val s = StalledOsmTiles()
        val path = "11/400/700.pbf"
        assertIs<Fetch.Unavailable>(f.get("osm", path))
        s.failed(path, now)
        stalled = false // the host answers again
        now += 10_000
        assertIs<Fetch.Unavailable>(f.get("osm", path), "inside the fetcher's 30 s memo: not fetched")
        assertEquals(1, calls.get())
        now += 20_000
        val due = s.due(now)
        assertEquals(listOf(path), due)
        val got = f.get("osm", path)
        assertIs<Fetch.Hit>(got)
        s.result(path, ok = true, now = now)
        assertEquals(2, calls.get())
        assertEquals(1L, s.recovered)
        assertIs<Fetch.Hit>(f.get("osm", path), "the map's next request is answered from the cache")
        assertEquals(2, calls.get())
    }

    @Test
    fun theVectorSourceIsReadFromTheStyle() {
        val json = """{"version":8,"sources":{"dem":{"type":"raster-dem"},"osm":{"type":"vector","tiles":["http://127.0.0.1:1/osm/{z}/{x}/{y}.pbf?v=a-c1-o2&t=x"],"minzoom":0,"maxzoom":14}},"layers":[]}"""
        val osm = OsmSourceRefresh.osmSource(json)!!
        assertTrue((osm["tiles"] as List<*>).single().toString().contains("-o2"))
        assertEquals(14L, (osm["maxzoom"] as Number).toLong())
    }
}
