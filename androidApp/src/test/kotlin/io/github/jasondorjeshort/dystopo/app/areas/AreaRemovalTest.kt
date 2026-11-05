package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T221: what removing an area deletes ([removalMatcher] over the real [TileCache], in chunks with a counter), the row it
 * leaves until the tiles are gone ([removingLine], [rowsInPlace]) and the lines around it.
 */
class AreaRemovalTest {
    private val dir: File = Files.createTempDirectory("area-removal").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // Two boxes on Rainier (public) that overlap: A's eastern half is B's western half.
    private val a = AreaOfInterest(AreaShape.Box(-121.80, 46.80, -121.74, 46.86), 15, 15)
    private val b = AreaOfInterest(AreaShape.Box(-121.77, 46.80, -121.71, 46.86), 15, 15)
    private val src = "10.0.2.2_8770/dem@d1"

    private fun keys(area: AreaOfInterest) = TileCover.of(area, 15).tiles().map { (x, y) -> "$src/15/$x/$y.png" }.toSet()

    private fun byPrefix(area: AreaOfInterest) =
        mapOf(src to TilesItem(AreaManifest.ROUTE_DEM, "png", "", mapOf(15 to TileCover.of(area, 15)), 0))

    @Test
    fun removingOneAreaKeepsTheTilesAnotherPinnedAreaShares() {
        // Area A has left the pins, as it has when Areas.remove clears; B is still pinned.
        val pins = AreaPins(listOf(AreaPins.cover(b)))
        val shared = keys(a).filter { pins.isPinned(it) }.toSet()
        val onlyA = keys(a) - shared
        val onlyB = keys(b) - keys(a)
        assertTrue(onlyA.isNotEmpty() && shared.isNotEmpty() && onlyB.isNotEmpty(), "the boxes overlap")
        assertTrue(shared.any { it in keys(b) }, "they share tiles of B's own")
        val elsewhere = "$src/15/1/1.png"
        val cache = TileCache(dir, 100_000_000)
        cache.keep = { pins.isPinned(it) }
        (onlyA + shared + onlyB + elsewhere).forEach { cache.put(it, ByteArray(10)) }
        val heard = ArrayList<Pair<Int, Int>>()
        val gone = cache.clearWhere(chunk = 3, onChunk = { d, t -> heard += d to t }, matches = removalMatcher(byPrefix(a), pins::isPinned))
        assertEquals(onlyA.size, gone)
        onlyA.forEach { assertFalse(cache.contains(it), "$it was only A's") }
        (shared + onlyB + elsewhere).forEach { assertTrue(cache.contains(it), "$it stays") }
        shared.forEach { assertTrue(File(dir, it).isFile, "$it stays on disk") }
        assertEquals(0 to onlyA.size, heard.first())
        assertEquals(onlyA.size to onlyA.size, heard.last())
        assertEquals(heard.map { it.first }.sorted(), heard.map { it.first }, "the counter only rises")
        assertTrue(heard.size > 2, "in chunks: $heard")
    }

    @Test
    fun aTilePinnedDuringTheRemovalStays() {
        val cache = TileCache(dir, 100_000_000)
        val ks = (0 until 10).map { "$src/15/$it/1.png" }
        ks.forEach { cache.put(it, ByteArray(10)) }
        var pinned = emptySet<String>()
        val gone = cache.clearWhere(chunk = 2, onChunk = { d, _ -> if (d == 2) pinned = setOf(ks.last()) }) { it !in pinned }
        assertEquals(9, gone, "the first pass counted ten; one was pinned before its chunk came")
        assertTrue(cache.contains(ks.last()))
        assertEquals(1, cache.size)
    }

    @Test
    fun clearWhereAsksARefusedKeyOnce() { // OnlineFetcher's version change counts what it refuses in its predicate
        val cache = TileCache(dir, 100_000_000)
        cache.put("a/1/0/0.png", ByteArray(1))
        cache.put("b/1/0/0.png", ByteArray(1))
        var refused = 0
        val gone = cache.clearWhere { k -> if (k.startsWith("b")) { refused++; false } else true }
        assertEquals(1, gone)
        assertEquals(1, refused)
    }

    @Test
    fun theRowReadsRemovingWithItsCount() {
        assertEquals("Removing", removingLine(0, null))
        assertEquals("Removing", removingLine(0, 0))
        assertEquals("Removing: 0 of 1 tile", removingLine(0, 1))
        assertEquals("Removing: 1,200 of 4,000 tiles", removingLine(1200, 4000))
    }

    private fun rec(id: String, at: Long) = Areas.Record(id = id, name = id, area = a, createdAt = at)

    @Test
    fun aRemovingRowStaysWhereItWas() {
        val one = rec("one", 1)
        val three = rec("three", 3)
        val two = Areas.Removal(rec("two", 2), 10)
        val four = Areas.Removal(rec("four", 4), 11)
        assertEquals(listOf<Any>(one, two, three, four), rowsInPlace(listOf(one, three), listOf(four, two)))
        assertEquals(listOf<Any>(two), rowsInPlace(emptyList(), listOf(two)))
    }

    @Test
    fun theRoutesReadARemovalAsTheRowDoes() {
        val j = removalJson(Areas.Removal(rec("one", 1), 1000, 256, 512), now = 1500)
        assertEquals("removing", j["state"])
        assertEquals("one", j["id"])
        assertEquals(mapOf("done" to 256, "total" to 512, "line" to "Removing: 256 of 512 tiles", "ms" to 500L), j["removing"])
    }

    @Test
    fun oneFailedTileIsSingular() {
        assertEquals("1 tile could not be fetched.", failedTilesLine(1))
        assertEquals("3 tiles could not be fetched.", failedTilesLine(3))
    }

    @Test
    fun aRowOpensToItsZoomsAndBounds() {
        val medium = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356)), 500.0), 8, 14, demMaxZoom = 13)
        val lines = areaDetailLines(medium)
        assertEquals("Zooms z8-14, elevation to z13", lines[0])
        assertTrue(lines[1].startsWith("Bounds 46.7"), lines[1])
        assertEquals(listOf("Zooms z15-15", "Bounds 46.80, -121.80 to 46.86, -121.74"), areaDetailLines(a))
    }
}
