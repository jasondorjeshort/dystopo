package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContourCacheTest {
    private val root: File = Files.createTempDirectory("contours").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val rainier = "aaaa1111"
    private val montblanc = "bbbb2222"

    @Test
    fun directoryNamesCarryUnitSizeIntervalAndPack() {
        assertEquals("v2-ft-512-aaaa1111", ContourCache.dirName("ft", 512, rainier))
        assertEquals("v2-m-512-i100-bbbb2222", ContourCache.dirName("m", 512, montblanc, "-i100"))
        assertEquals("v2-ft-512-aaaa1111/12/654/1431.pbf", ContourCache.key(ContourCache.dirName("ft", 512, rainier), 12, 654, 1431))
        assertTrue(ContourCache.keeps("v2-m-512-i100-bbbb2222", setOf(montblanc)))
        assertFalse(ContourCache.keeps("v1-ft-512-aaaa1111", setOf(rainier)), "older version")
        assertFalse(ContourCache.keeps("v2-ft-512-cccc3333", setOf(rainier, montblanc)), "no such pack")
    }

    @Test
    fun perPackDirectoriesUnderOneLruCapAndRemovalOnPackDelete() {
        val cache = TileCache(root, 1000) { now }
        val r = ContourCache.dirName("ft", 512, rainier)
        val m = ContourCache.dirName("ft", 512, montblanc)
        cache.put(ContourCache.key(r, 12, 1, 1), ByteArray(300))
        cache.put(ContourCache.key(m, 12, 2, 2), ByteArray(300))
        cache.put(ContourCache.key(m, 12, 2, 3), ByteArray(300))
        // A switch back to Rainier reads its tile: it becomes the most recently used.
        now += 1000
        assertNotNull(cache.get(ContourCache.key(r, 12, 1, 1)))
        // One cap over both packs: the next write evicts Mont Blanc's oldest tile, not Rainier's.
        cache.put(ContourCache.key(r, 13, 1, 1), ByteArray(300))
        assertTrue(cache.contains(ContourCache.key(r, 12, 1, 1)))
        assertFalse(cache.contains(ContourCache.key(m, 12, 2, 2)))
        assertEquals(900L, cache.bytes)
        // Both packs on the device: nothing is collected (a switch keeps the other pack's tiles).
        assertEquals(0, ContourCache.collectGarbage(root, cache, setOf(rainier, montblanc)))
        assertEquals(setOf(r, m), cache.byPrefix().keys)
        // Mont Blanc deleted: its directory goes, from disk and from the index.
        File(root, "v1-ft-512-$rainier/9/1/1.pbf").apply { parentFile.mkdirs(); writeBytes(ByteArray(5)) }
        assertEquals(2, ContourCache.collectGarbage(root, cache, setOf(rainier)))
        assertEquals(setOf(r), cache.byPrefix().keys)
        assertEquals(600L, cache.bytes)
        assertEquals(listOf(r), root.listFiles()!!.filter { it.isDirectory }.map { it.name })
    }
}
