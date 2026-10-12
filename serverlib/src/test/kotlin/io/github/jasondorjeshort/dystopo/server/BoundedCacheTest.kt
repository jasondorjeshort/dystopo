package io.github.jasondorjeshort.dystopo.server

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedCacheTest {
    private val dir: File = Files.createTempDirectory("bounded-cache").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(cache: BoundedCache, rel: String, bytes: Int): File {
        val f = File(dir, rel)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
        cache.added(f)
        return f
    }

    @Test
    fun evictsLeastRecentlyUsedPastTheCap() {
        val cache = BoundedCache("t", dir, 300, clock = { now }, indexInBackground = false)
        val a = write(cache, "1/0/0.png", 100)
        val b = write(cache, "1/0/1.png", 100)
        val c = write(cache, "1/1/0.png", 100)
        cache.touch(a) // a is now the most recently used
        val d = write(cache, "1/1/1.png", 100)
        assertFalse(b.exists(), "the least recently used entry goes first")
        assertTrue(a.exists() && c.exists() && d.exists())
        assertEquals(300, cache.bytes)
        assertEquals(1, cache.evictions.get())
        assertTrue(cache.peakBytes.get() <= 300)
    }

    @Test
    fun sidecarsCountAndGoWithTheirTile() {
        val cache = BoundedCache("t", dir, 250, clock = { now }, indexInBackground = false)
        val png = write(cache, "5/1/1.png", 100)
        val src = write(cache, "5/1/1.src", 10)
        assertEquals(1, cache.entries)
        assertEquals(110, cache.bytes)
        write(cache, "5/1/2.png", 100)
        write(cache, "5/1/3.png", 100)
        assertFalse(png.exists())
        assertFalse(src.exists(), "the sidecar is evicted with its tile")
        assertEquals(200, cache.bytes)
    }

    @Test
    fun indexesWhatIsOnDiskOldestFirstAndExcludes() {
        fun put(rel: String, bytes: Int, mtime: Long) = File(dir, rel).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(mtime) }
        val old = put("v/1/1/1.pbf", 100, 1_000_000)
        val newer = put("v/1/1/2.pbf", 100, 2_000_000)
        val part = put("v/1/1/3.pbf.123.part", 500, 3_000_000)
        val top = put("planet.json", 500, 3_000_000)
        val cache = BoundedCache("t", dir, 150, exclude = { '/' !in it || it.endsWith(".part") }, clock = { now }, indexInBackground = false)
        assertTrue(cache.indexed)
        assertFalse(old.exists(), "over the cap at start: the oldest file goes")
        assertTrue(newer.exists() && part.exists() && top.exists(), "excluded files are never counted or deleted")
        assertEquals(100, cache.bytes)
    }

    @Test
    fun rewriteIsNotCountedTwiceAndResizeTrims() {
        val cache = BoundedCache("t", dir, 1000, clock = { now }, indexInBackground = false)
        val f = write(cache, "a/b.png", 100)
        f.writeBytes(ByteArray(150))
        cache.added(f)
        assertEquals(150, cache.bytes)
        write(cache, "a/c.png", 100)
        cache.resize(120)
        assertEquals(100, cache.bytes)
        assertFalse(f.exists())
        assertEquals(100, cache.clear())
        assertEquals(0, cache.bytes)
    }

    @Test
    fun cacheCapsParse() {
        assertEquals(LiveTiles.CacheCaps(200, 2048, 50), LiveTiles.CacheCaps.parse("live=200, derived=50"))
        assertEquals(LiveTiles.CacheCaps(), LiveTiles.CacheCaps.parse(""))
        assertTrue(runCatching { LiveTiles.CacheCaps.parse("tiles=5") }.isFailure)
        assertTrue(runCatching { LiveTiles.CacheCaps.parse("live=0") }.isFailure)
    }
}
