package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T63: threads that miss the same DEM tile together share one fetch and decode. */
class DemPyramidInFlightTest {
    private val decoder = ImageDecoder { bytes ->
        val e = bytes.decodeToString().substringAfterLast('/').toFloat()
        ArgbImage(256, 256, IntArray(256 * 256) { Terrarium.encode(e) })
    }

    @Test
    fun concurrentMissesOfOneTileDecodeOnce() {
        val fetches = ConcurrentHashMap<String, AtomicInteger>()
        val p = DemPyramid(8, 12, decoder, cacheTiles = 16) { z, x, y ->
            fetches.getOrPut("$z/$x/$y") { AtomicInteger() }.incrementAndGet()
            Thread.sleep(80) // a slow pack read: every thread arrives while the first is still loading
            if (y == 99) null else "$z/$x/$y".encodeToByteArray()
        }
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val futures = (0 until 24).map { i ->
            pool.submit<FloatArray?> {
                start.await()
                // Two stored tiles and one the pack does not have, each asked for by 8 threads at once.
                p.tile(10, 5, listOf(7, 8, 99)[i % 3])
            }
        }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(mapOf("10/5/7" to 1, "10/5/8" to 1, "10/5/99" to 1), fetches.mapValues { it.value.get() })
        assertEquals(2L, p.tilesDecoded)
        assertEquals(0L, p.duplicateDecodes)
        assertTrue(p.inFlightWaits + p.cacheHits >= 21, "waits ${p.inFlightWaits}, hits ${p.cacheHits}")
        assertTrue(p.inFlightWaits >= 1, "some thread waited for an in-flight load")
        assertEquals(0, p.loadsInFlight)
        for (i in results.indices) {
            if (i % 3 == 2) {
                assertNull(results[i])
            } else {
                assertSame(results[i % 3], results[i], "every thread gets the one decoded array")
                assertEquals(7f + i % 3, results[i]!![0], 0.5f)
            }
        }
    }

    @Test
    fun aFailedLoadIsRetriedByTheNextCaller() {
        val calls = AtomicInteger()
        val p = DemPyramid(8, 12, decoder, cacheTiles = 16) { z, x, y ->
            if (calls.incrementAndGet() == 1) error("transient read error")
            "$z/$x/$y".encodeToByteArray()
        }
        assertFailsWith<IllegalStateException> { p.tile(10, 1, 2) }
        assertEquals(0, p.loadsInFlight)
        assertEquals(2f, p.tile(10, 1, 2)!![0], 0.5f)
        assertEquals(2, calls.get())
    }
}
