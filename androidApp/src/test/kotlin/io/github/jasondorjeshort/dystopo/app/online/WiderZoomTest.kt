package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T254: a vector tile the phone lacks is cut from the nearest ancestor in the online cache ([WiderZoom] over
 * [OnlineFetcher.cachedOnly]); the cut answer is never written into the tile cache and never counted in the negative
 * cache, so the tile itself is fetched once the network is back.
 */
class WiderZoomTest {
    private val dir: File = Files.createTempDirectory("wider").toFile()
    private var now = 1_000_000L
    @Volatile private var up = true

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val road = VectorTile(listOf(Layer("transportation", listOf(
        Feature(1, emptyMap(), Geometry.Lines(listOf(listOf(TilePoint(0, 0), TilePoint(4096, 4096))))),
    )))).encode()

    private val cache = TileCache(dir, 1 shl 22) { now }

    /** A server without `/version`: every tile is [road] while [up], unreachable otherwise. */
    private val fetcher = OnlineFetcher("http://10.0.2.2:8765", cache, { url ->
        when {
            !up -> throw ConnectFailed("airplane mode")
            url.endsWith("/version") -> HttpReply(404, ByteArray(0))
            else -> HttpReply(200, road)
        }
    }, clock = { now })

    private val wider = WiderZoom { z, x, y -> fetcher.cachedOnly("osm", "$z/$x/$y.pbf") }

    private fun negatives(): Map<*, *> = fetcher.json()["negativeCache"] as Map<*, *>

    /** The z9 view was looked at online; then the phone went offline and the view zoomed to z14. */
    private fun offlineAtZ14(): WiderZoom.Cut {
        assertIs<Fetch.Hit>(fetcher.get("osm", "9/265/182.pbf"))
        up = false
        now += 1_000
        assertIs<Fetch.Unavailable>(fetcher.get("osm", "14/8496/5840.pbf"))
        return assertNotNull(wider.vector(14, 8496, 5840), "cut from z9")
    }

    @Test
    fun aWiderAnswerIsNeverWrittenIntoTheTileCache() {
        val cut = offlineAtZ14()
        assertEquals(9, cut.fromZoom)
        assertTrue(VectorTile.decode(cut.bytes).layer("transportation")!!.features.isNotEmpty(), "the road reaches the child")
        assertEquals(1, cache.size, "the cache holds the z9 ancestor only")
        assertNull(fetcher.cachedOnly("osm", "14/8496/5840.pbf"))
        assertFalse(fetcher.hasCached("osm", "14/8496/5840.pbf"))
        assertFalse(File(dir, "10.0.2.2_8765/osm/14/8496/5840.pbf").exists())
    }

    @Test
    fun aWiderAnswerIsNeverCountedInTheNegativeCache() {
        val requests = fetcher.requests.get()
        offlineAtZ14()
        assertEquals(requests + 2, fetcher.requests.get(), "the cut asked the cache only, no lookup of its own")
        assertEquals(mapOf("missing" to 0, "failed" to 0), negatives().mapKeys { it.key.toString() })
        // The network is back: the tile itself is fetched at once, not skipped as missing.
        up = true
        now += 120_000
        val back = assertIs<Fetch.Hit>(fetcher.get("osm", "14/8496/5840.pbf"))
        assertFalse(back.cached, "fetched over the network")
        assertEquals(0L, fetcher.negativeHits.get())
    }

    @Test
    fun noAncestorWithinTheDepthCapMeansNoAnswer() {
        assertIs<Fetch.Hit>(fetcher.get("osm", "8/132/91.pbf"))
        assertNull(wider.vector(14, 8496, 5840), "six levels up is past the cap")
        assertNotNull(wider.vector(13, 4248, 2920), "five levels up is within it")
        assertEquals(1L, wider.misses.get())
    }
}
