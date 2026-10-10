package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T49: shade tiles live in the contour LRU: a running byte cap, oldest option sets first, gone with their pack. */
class ShadeCacheTest {
    private val root: File = Files.createTempDirectory("shade").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val rainier = "aaaa1111"
    private val montblanc = "bbbb2222"

    @Test
    fun directoryNames() {
        val d = ContourCache.shadeDir("s1-multi100-slope-day", rainier)
        assertEquals("shade1-s1_multi100_slope_day-aaaa1111", d)
        assertEquals("$d/12/654/1431.png", ContourCache.shadeKey(d, 12, 654, 1431))
        assertTrue(ContourCache.keeps(d, setOf(rainier)))
        assertFalse(ContourCache.keeps(d, setOf(montblanc)), "pack deleted")
        assertFalse(ContourCache.keeps("shade0-s1_multi100_slope_day-aaaa1111", setOf(rainier)), "older version")
    }

    @Test
    fun theCapHoldsWhileRunningAndTheLeastRecentOptionSetGoesFirst() {
        val cache = TileCache(root, 10_000) { now }
        val looks = listOf("s1-multi100-slope-day", "s1-single60-noslope-day", "s1-multi100-noslope-night")
        // A screenful of tiles in each of three looks, flipping back to the first between them.
        for ((i, look) in looks.withIndex()) {
            val dir = ContourCache.shadeDir(look, rainier)
            for (t in 0 until 4) {
                now += 10
                cache.put(ContourCache.shadeKey(dir, 13, 1300 + t, 2860), ByteArray(1000))
                assertTrue(cache.bytes <= 10_000, "over the cap after a write: ${cache.bytes}")
            }
            if (i > 0) {
                now += 10
                for (t in 0 until 4) cache.get(ContourCache.shadeKey(ContourCache.shadeDir(looks[0], rainier), 13, 1300 + t, 2860))
            }
        }
        // Contour tiles share the cap.
        now += 10
        cache.put(ContourCache.key(ContourCache.dirName("ft", 512, rainier), 12, 650, 1430), ByteArray(1500))
        assertTrue(cache.bytes <= 10_000)
        val dirs = cache.byPrefix()
        // The first look, used last, is whole; the second look, the least recently used, lost tiles first.
        assertEquals(4, dirs[ContourCache.shadeDir(looks[0], rainier)]?.first)
        assertTrue((dirs[ContourCache.shadeDir(looks[1], rainier)]?.first ?: 0) < 4, "$dirs")
        assertEquals(4, dirs[ContourCache.shadeDir(looks[2], rainier)]?.first)
        assertTrue(cache.evictions.get() > 0)

        // A smaller cap (the contourCacheMb setting) applies at once.
        cache.resize(3_000)
        assertTrue(cache.bytes <= 3_000)
    }

    @Test
    fun aDeletedPacksShadeTilesAreCollected() {
        val cache = TileCache(root, 1 shl 20) { now }
        val r = ContourCache.shadeDir("s1-multi100-slope-day", rainier)
        val m = ContourCache.shadeDir("s1-multi100-slope-day", montblanc)
        cache.put(ContourCache.shadeKey(r, 13, 1, 1), ByteArray(10))
        cache.put(ContourCache.shadeKey(m, 13, 2, 2), ByteArray(10))
        assertEquals(1, ContourCache.collectGarbage(root, cache, setOf(rainier)))
        assertEquals(setOf(r), cache.byPrefix().keys)
    }
}
