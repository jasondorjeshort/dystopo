package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.geo.TileId
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileIdTest {
    @Test
    fun zoomStartsCountAllCoarserTiles() {
        assertEquals(0L, TileIds.zoomStart(0))
        assertEquals(1L, TileIds.zoomStart(1))
        assertEquals(5L, TileIds.zoomStart(2))
        assertEquals(21L, TileIds.zoomStart(3))
        assertEquals(85L, TileIds.zoomStart(4))
        assertEquals((1L shl 40) / 3, TileIds.zoomStart(20))
    }

    @Test
    fun lowZoomIdsMatchTheSpecification() {
        // Zoom 1 is visited north-west, south-west, south-east, north-east.
        assertEquals(0L, TileIds.fromZxy(0, 0, 0))
        assertEquals(1L, TileIds.fromZxy(1, 0, 0))
        assertEquals(2L, TileIds.fromZxy(1, 0, 1))
        assertEquals(3L, TileIds.fromZxy(1, 1, 1))
        assertEquals(4L, TileIds.fromZxy(1, 1, 0))
        assertEquals(5L, TileIds.fromZxy(2, 0, 0))
        assertEquals(TileId(2, 0, 0), TileIds.toZxy(5))
        assertEquals(TileId(1, 1, 0), TileIds.toZxy(4))
    }

    @Test
    fun everyTileAtLowZoomsHasAUniqueIdAndRoundTrips() {
        for (z in 0..6) {
            val n = 1 shl z
            val seen = HashSet<Long>()
            for (x in 0 until n) for (y in 0 until n) {
                val id = TileIds.fromZxy(z, x, y)
                assert(id >= TileIds.zoomStart(z) && id < TileIds.zoomStart(z) + n.toLong() * n) { "$z/$x/$y -> $id" }
                assert(seen.add(id)) { "duplicate id $id at $z/$x/$y" }
                assertEquals(TileId(z, x, y), TileIds.toZxy(id))
                assertEquals(z, TileIds.zoomOf(id))
            }
        }
    }

    @Test
    fun consecutiveIdsAreNeighbouringTiles() {
        // A Hilbert curve moves one tile at a time; this holds at every zoom.
        for (z in 1..7) {
            var prev = TileIds.toZxy(TileIds.zoomStart(z))
            val count = 1L shl (2 * z)
            for (i in 1 until count) {
                val cur = TileIds.toZxy(TileIds.zoomStart(z) + i)
                assertEquals(1, abs(cur.x - prev.x) + abs(cur.y - prev.y), "jump between $prev and $cur")
                prev = cur
            }
        }
    }

    @Test
    fun highZoomRoundTrips() {
        val rnd = Random(7)
        repeat(2000) {
            val z = rnd.nextInt(0, 27)
            val n = 1L shl z
            val x = rnd.nextLong(n).toInt()
            val y = rnd.nextLong(n).toInt()
            assertEquals(TileId(z, x, y), TileIds.toZxy(TileIds.fromZxy(z, x, y)))
        }
        assertEquals(TileId(30, (1 shl 30) - 1, 0), TileIds.toZxy(TileIds.fromZxy(30, (1 shl 30) - 1, 0)))
    }

    @Test
    fun rejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { TileIds.fromZxy(2, 4, 0) }
        assertFailsWith<IllegalArgumentException> { TileIds.fromZxy(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TileIds.toZxy(-1) }
    }
}
