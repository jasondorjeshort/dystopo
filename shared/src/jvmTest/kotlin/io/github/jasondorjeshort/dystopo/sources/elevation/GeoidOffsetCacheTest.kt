package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T80 (T82's soak: an unbounded per-window memo grew to 2.19 GB): the geoid offset memo stays under its byte cap. */
class GeoidOffsetCacheTest {
    private val cap = Geoids.offsetCacheBytes

    @AfterTest
    fun restore() {
        Geoids.offsetCacheBytes = cap
        Geoids.clearOffsetCache()
    }

    @Test
    fun manyWindowsStayUnderTheCap() {
        Geoids.clearOffsetCache()
        Geoids.offsetCacheBytes = 8L shl 20
        var computed = 0
        // Live tiles: 432 x 432 windows (a z13 tile with its halo), about 0.73 MB of offsets each, as in the soak.
        for (i in 0 until 3000) {
            val w = MercatorWindow(13, 1_300_000 + (i % 60) * 256, 2_900_000 + (i / 60) * 256, 432, 432)
            Geoids.memoized(w) { computed++; Geoids.Offsets(FloatArray(w.width * w.height), emptyList()) }
            assertTrue(Geoids.offsetCacheSize <= Geoids.offsetCacheBytes, "memo at ${Geoids.offsetCacheSize} bytes after $i windows")
        }
        assertEquals(3000, computed)
        assertTrue(Geoids.offsetCacheEntries in 1..12, "${Geoids.offsetCacheEntries} windows kept")
    }

    @Test
    fun aRepeatedWindowIsComputedOnce() {
        Geoids.clearOffsetCache()
        val w = MercatorWindow(12, 655_000, 1_431_000, 256, 256)
        var computed = 0
        val a = Geoids.memoized(w) { computed++; Geoids.Offsets(FloatArray(4), listOf("egm2008")) }
        val b = Geoids.memoized(w.copy()) { computed++; Geoids.Offsets(FloatArray(4), listOf("egm2008")) }
        assertSame(a, b)
        assertEquals(1, computed)
    }
}