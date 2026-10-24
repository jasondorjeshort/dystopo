package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * T138: the phone's memory trim empties the decoded-block cache and the geoid offset memo in place: both clears return
 * the bytes they held and leave their cache at zero bytes, and the same cache keeps working afterwards.
 */
class MemoryClearTest {
    @AfterTest
    fun restore() {
        Geoids.clearOffsetCache()
    }

    @Test
    fun decodedBlocksClearLeavesZeroBytes() {
        val blocks = DecodedBlocks(1L shl 20)
        blocks.put("a", FloatArray(1000))
        blocks.put("b", FloatArray(500))
        assertEquals(6000L, blocks.sizeBytes)
        assertEquals(6000L, blocks.clear())
        assertEquals(0L, blocks.sizeBytes)
        assertNull(blocks["a"])
        assertEquals(0L, blocks.clear())
        // The cache is reused after a clear (the open GeoTIFFs keep this instance): it fills and counts again.
        val c = FloatArray(250)
        assertSame(c, blocks.put("c", c))
        assertEquals(1000L, blocks.sizeBytes)
    }

    @Test
    fun geoidMemoClearLeavesZeroBytes() {
        Geoids.clearOffsetCache()
        val w = MercatorWindow(12, 655_000, 1_431_000, 256, 256)
        Geoids.memoized(w) { Geoids.Offsets(FloatArray(w.width * w.height), listOf("egm2008")) }
        assertEquals(256L * 256 * 4, Geoids.offsetCacheSize)
        assertEquals(256L * 256 * 4, Geoids.clearOffsetCache())
        assertEquals(0L, Geoids.offsetCacheSize)
        assertEquals(0, Geoids.offsetCacheEntries)
    }
}
