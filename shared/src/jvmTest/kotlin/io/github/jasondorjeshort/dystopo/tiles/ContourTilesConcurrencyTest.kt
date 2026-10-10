package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One [DemPyramid] shared by many threads: tiles built concurrently equal the ones built one at a time. */
class ContourTilesConcurrencyTest {
    private val decoder = ImageDecoder { bytes ->
        val (z, x, y) = bytes.decodeToString().split("/").map { it.toInt() }
        val world = 256.0 * (1L shl z)
        ArgbImage(256, 256, IntArray(256 * 256) { i ->
            val u = (x * 256 + i % 256 + 0.5) / world
            val v = (y * 256 + i / 256 + 0.5) / world
            // Two cones, so tiles differ from each other.
            val e = maxOf(3000.0 - hypot(u - 0.3501, v - 0.3502) * 2.0e6, 2000.0 - hypot(u - 0.3507, v - 0.3499) * 1.5e6, 0.0)
            Terrarium.encode(e.toFloat())
        })
    }

    private fun pyramid(cacheTiles: Int) = DemPyramid(8, 13, decoder, cacheTiles) { z, x, y -> "$z/$x/$y".encodeToByteArray() }

    @Test
    fun concurrentTilesEqualSequentialOnes() {
        val tiles = ArrayList<Triple<Int, Int, Int>>()
        for (z in listOf(7, 9, 11, 12, 13, 14)) {
            val n = 1 shl z
            val cx = (0.3503 * n).toInt()
            val cy = (0.3501 * n).toInt()
            for (dx in -1..1) for (dy in -1..1) tiles += Triple(z, cx + dx, cy + dy)
        }
        val expected = tiles.map { (z, x, y) -> ContourTiles.generate(pyramid(64), z, x, y).bytes }

        // A small cache forces evictions and re-decodes while other threads read.
        val shared = pyramid(8)
        val stats = ContourTileStats()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (tiles.indices + tiles.indices).map { i ->
            pool.submit<ByteArray> {
                start.await()
                val (z, x, y) = tiles[i]
                val n = stats.begin()
                val plan = ContourTiles.plan(z, x, y)
                val tile = ContourTiles.build(ContourTiles.sample(shared, plan), plan)
                stats.end(plan, tile, 1.0, 0.0, n)
                tile.bytes
            }
        }
        start.countDown()
        val results = futures.map { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()
        for (i in results.indices) assertContentEquals(expected[i % tiles.size], results[i], "tile ${tiles[i % tiles.size]}")
        val json = stats.json()
        assertEquals(0, json["inFlight"])
        assertTrue((json["maxConcurrent"] as Int) >= 2, "tiles generated concurrently: $json")
        assertTrue(shared.cachedTiles <= 8)
    }
}
