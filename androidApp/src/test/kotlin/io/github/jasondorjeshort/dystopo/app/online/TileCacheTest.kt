package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileCacheTest {
    private val dir: File = Files.createTempDirectory("tilecache").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun cache(max: Long) = TileCache(dir, max) { now }

    @Test
    fun storesAndReadsBack() {
        val c = cache(1000)
        c.put("s/dem/1/0/0.png", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), c.get("s/dem/1/0/0.png"))
        assertNull(c.get("s/dem/1/0/1.png"))
        assertEquals(1L, c.hits.get())
        assertEquals(1L, c.misses.get())
        assertEquals(3L, c.bytes)
    }

    @Test
    fun evictsLeastRecentlyUsedFirst() {
        val c = cache(300)
        c.put("a", ByteArray(100))
        c.put("b", ByteArray(100))
        c.put("c", ByteArray(100))
        c.get("a") // a is now the most recently used
        c.put("d", ByteArray(100)) // over the cap: b goes
        assertTrue(c.contains("a"))
        assertFalse(c.contains("b"))
        assertTrue(c.contains("c"))
        assertTrue(c.contains("d"))
        assertEquals(300L, c.bytes)
        assertEquals(1L, c.evictions.get())
        assertFalse(File(dir, "b").exists(), "evicted file deleted")
    }

    @Test
    fun replacingAKeyKeepsTheTotalRight() {
        val c = cache(1000)
        c.put("k", ByteArray(100))
        c.put("k", ByteArray(40))
        assertEquals(40L, c.bytes)
        assertEquals(1, c.size)
    }

    @Test
    fun indexSurvivesARestartInAccessOrder() {
        val c = cache(10_000)
        c.put("old", ByteArray(100))
        now += 120_000
        c.put("new", ByteArray(100))
        now += 120_000
        c.get("old") // touched: newer than "new" on disk
        // A restart with a smaller cap keeps the most recently used entry.
        val again = cache(150)
        assertTrue(again.contains("old"))
        assertFalse(again.contains("new"))
        assertEquals(100L, again.bytes)
    }

    @Test
    fun rejectsKeysThatEscapeTheDirectory() {
        val c = cache(1000)
        assertFailsWith<IllegalArgumentException> { c.put("../x", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { c.get("/abs") }
        assertFailsWith<IllegalArgumentException> { c.get("a//b") }
    }

    @Test
    fun clearByPrefix() {
        val c = cache(1000)
        c.put("s1/dem/1", ByteArray(1))
        c.put("s1/osm/1", ByteArray(1))
        c.put("s2/dem/1", ByteArray(1))
        assertEquals(2, c.clear("s1/"))
        assertEquals(1, c.size)
    }
}
