package io.github.jasondorjeshort.dystopo.app.areas

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
import kotlin.test.assertTrue

/**
 * T75: a pinned area's tiles survive a cache trim that evicts everything else, and become ordinary least recently
 * used entries once unpinned. The real [TileCache] with the [TileCache.keep] hook set to [AreaPins.isPinned], as
 * [Areas.attach] sets it on the phone.
 */
class PinnedTrimTest {
    private val dir: File = Files.createTempDirectory("pinned-trim").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // Paradise to Panorama Point (public), 300 m each side.
    private val area = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268)), 300.0), 8, 15)

    @Test
    fun pinnedTilesSurviveATrimAndGoOnceUnpinned() {
        val cache = TileCache(dir, 10_000_000) { now }
        var pins = AreaPins(listOf(AreaPins.cover(area)))
        cache.keep = { pins.isPinned(it) }
        val tile = ByteArray(1000)
        // The area's z15 DEM tiles, written first (so the oldest, which an LRU would evict first).
        val inArea = TileCover.of(area, 15).tiles().map { (x, y) -> "10.0.2.2_9130/dem/15/$x/$y.png" }
        for (k in inArea) { now += 10; cache.put(k, tile) }
        // Tiles somewhere else (Mont Blanc), viewed after the download.
        val x0 = floor(WebMercator.lonToX(6.86, 15)).toInt()
        val y0 = floor(WebMercator.latToY(45.83, 15)).toInt()
        val elsewhere = (0 until 50).map { i -> "10.0.2.2_9130/dem/15/${x0 + i % 10}/${y0 + i / 10}.png" }
        for (k in elsewhere) { now += 10; cache.put(k, tile) }
        // A contour tile one zoom deeper than the area and a glyph range are pinned too.
        val (cx, cy) = TileCover.of(area, 15).tiles().first()
        cache.put("10.0.2.2_9130/contours-v9-ft-512/16/${cx * 2}/${cy * 2}.pbf", tile)
        cache.put("10.0.2.2_9130/glyphs/Noto%20Sans%20Regular/0-255.pbf", tile)

        cache.resize(5_000) // a tiny cap: everything unpinned must go
        assertTrue(inArea.all { cache.contains(it) }, "a pinned tile was evicted")
        assertTrue(elsewhere.none { cache.contains(it) }, "unpinned tiles stayed")
        assertTrue(cache.contains("10.0.2.2_9130/contours-v9-ft-512/16/${cx * 2}/${cy * 2}.pbf"))
        assertTrue(cache.contains("10.0.2.2_9130/glyphs/Noto%20Sans%20Regular/0-255.pbf"))
        assertEquals(elsewhere.size.toLong(), cache.evictions.get())

        // Unpinned: the next trim treats them like any other entry.
        pins = AreaPins.NONE
        cache.resize(5_000)
        assertTrue(cache.bytes <= 5_000, "over the cap after unpinning: ${cache.bytes}")
        assertTrue(inArea.count { cache.contains(it) } <= 5)
    }
}
