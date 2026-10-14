package io.github.jasondorjeshort.dystopo.app.storage

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.File
import java.nio.file.Files
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T95 (the T91 verifier's finding): the Storage screen's online row counted only unpinned bytes while its holds line
 * still listed the pinned tiles, so with an area pinned it read 0 MB over "dem 5.7 MB". Each row now counts its own.
 */
class StorageRowsTest {
    private val dir: File = Files.createTempDirectory("storage-rows").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // Paradise to Panorama Point (public), 300 m each side.
    private val area = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268)), 300.0), 8, 15)

    @Test
    fun theOnlineRowAndThePinnedRowEachHoldTheirOwnTiles() {
        val cache = TileCache(dir, 1L shl 24) { now }
        val pins = AreaPins(listOf(AreaPins.cover(area)))
        cache.keep = { pins.isPinned(it) }
        val inArea = TileCover.of(area, 15).tiles().map { (x, y) -> "10.0.2.2_8775/dem@v.1/15/$x/$y.png" }
        for (k in inArea) cache.put(k, ByteArray(1000))
        val (cx, cy) = TileCover.of(area, 15).tiles().first()
        cache.put("10.0.2.2_8775/contours-v9-ft-512@v.1/15/$cx/$cy.pbf", ByteArray(100))
        // Every pinned tile: the online row must not list them (it read 0 MB over "dem 5.7 MB" before).
        assertNull(Storage.onlineBreakdown(cache, pinned = false)["dem"])
        assertEquals(inArea.size * 1000L, Storage.onlineBreakdown(cache, pinned = true).getValue("dem")["bytes"])
        assertEquals(100L, Storage.onlineBreakdown(cache, pinned = true).getValue("contours")["bytes"])
        // Browsing elsewhere (Mont Blanc) adds unpinned tiles to the online row only.
        val x0 = floor(WebMercator.lonToX(6.86, 15)).toInt()
        val y0 = floor(WebMercator.latToY(45.83, 15)).toInt()
        for (i in 0 until 7) cache.put("10.0.2.2_8775/osm@o.1/15/${x0 + i}/$y0.pbf", ByteArray(300))
        assertEquals(mapOf("osm" to mapOf("tiles" to 7L, "bytes" to 2100L)), Storage.onlineBreakdown(cache, pinned = false))
        assertEquals(cache.pinnedBytes, Storage.onlineBreakdown(cache, pinned = true).values.sumOf { it.getValue("bytes") })
        assertEquals(cache.bytes - cache.pinnedBytes, Storage.onlineBreakdown(cache, pinned = false).values.sumOf { it.getValue("bytes") })
        assertEquals(inArea.size + 1, cache.pinnedSize)
        assertEquals(7, cache.size - cache.pinnedSize)
    }

    @Test
    fun clearOnTheOnlineRowKeepsThePinnedTiles() { // the PR #99 verifier: Clear took 297 tiles, the area's among them
        val cache = TileCache(dir, 1L shl 24) { now }
        val pins = AreaPins(listOf(AreaPins.cover(area)))
        cache.keep = { pins.isPinned(it) }
        val inArea = TileCover.of(area, 15).tiles().map { (x, y) -> "10.0.2.2_8775/dem@v.1/15/$x/$y.png" }
        for (k in inArea) cache.put(k, ByteArray(1000))
        val x0 = floor(WebMercator.lonToX(6.86, 15)).toInt()
        val y0 = floor(WebMercator.latToY(45.83, 15)).toInt()
        val elsewhere = (0 until 9).map { i -> "10.0.2.2_8775/osm@o.1/15/${x0 + i}/$y0.pbf" }
        for (k in elsewhere) cache.put(k, ByteArray(300))
        val pinnedBefore = cache.pinnedBytes
        assertEquals(elsewhere.size, cache.clearUnkept())
        assertTrue(inArea.all { cache.contains(it) }, "a pinned tile was cleared")
        assertTrue(elsewhere.none { cache.contains(it) }, "an unpinned tile stayed")
        assertEquals(pinnedBefore, cache.bytes)
        assertEquals(pinnedBefore, cache.pinnedBytes)
        assertEquals(emptyMap(), Storage.onlineBreakdown(cache, pinned = false))
    }
}
