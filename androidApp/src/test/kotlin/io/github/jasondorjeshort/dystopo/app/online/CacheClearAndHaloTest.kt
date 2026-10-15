package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.OnlineRoutes
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T106: `POST /online/cache/clear` keeps a pinned corridor (it emptied the whole cache, pinned tiles too: a T95
 * follow-up), and a contour tile made again drops its stale `.halo-` variants (they stayed until the trim: a T88 one).
 */
class CacheClearAndHaloTest {
    private val dir: File = Files.createTempDirectory("cache-clear").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // Paradise to Panorama Point (public), 300 m each side, as in PinnedTrimTest.
    private val area = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268)), 300.0), 8, 15)

    @Test
    fun aPinnedCorridorSurvivesTheCacheClear() {
        val cache = TileCache(dir, 10_000_000)
        val pins = AreaPins(listOf(AreaPins.cover(area)))
        cache.keep = { pins.isPinned(it) }
        val tile = ByteArray(500)
        val inArea = TileCover.of(area, 15).tiles().map { (x, y) -> "10.0.2.2_8770/dem@d1/15/$x/$y.png" } +
            TileCover.of(area, 14).tiles().map { (x, y) -> "10.0.2.2_8770/osm@o1/14/$x/$y.pbf" }
        inArea.forEach { cache.put(it, tile) }
        // Tiles elsewhere (Mont Blanc at z15) and a contour tile made from them.
        val elsewhere = (0 until 20).map { i -> "10.0.2.2_8770/dem@d1/15/${17_000 + i}/11_700.png" } + "10.0.2.2_8770/contours-v9-ft-512@d1/16/34000/23400.pbf"
        elsewhere.forEach { cache.put(it, tile) }

        val r = OnlineRoutes.clearCache(cache, pinnedToo = false)
        assertEquals(elsewhere.size, r["deleted"])
        assertEquals(inArea.size, r["pinnedKept"])
        assertTrue(inArea.all { cache.contains(it) }, "a pinned tile went")
        assertTrue(elsewhere.none { cache.contains(it) })
        assertEquals(cache.bytes, cache.pinnedBytes)

        // pinned=1 is the old behaviour: everything goes.
        val all = OnlineRoutes.clearCache(cache, pinnedToo = true)
        assertEquals(inArea.size, all["deleted"])
        assertEquals(0, cache.size)
    }

    @Test
    fun aContourTileMadeWholeDropsItsHaloVariants() {
        val cache = TileCache(dir, 10_000_000)
        val key = "sources/contours-v9-ft-512@d1/12/661/1443.pbf"
        val partA = OnlineTiles.contourKey(key, listOf(Triple(12, 661, 1442)))
        val partAB = OnlineTiles.contourKey(key, listOf(Triple(12, 661, 1442), Triple(12, 662, 1442)))
        val neighbour = OnlineTiles.contourKey("sources/contours-v9-ft-512@d1/12/661/1444.pbf", listOf(Triple(12, 661, 1445)))
        listOf(partAB, partA, neighbour).forEach { cache.put(it, ByteArray(100)) }
        assertTrue(partA.contains(TileCache.HALO_INFIX))

        // Made again with one halo tile still missing: the variant missing two is stale.
        assertEquals(1, cache.clearVariants(key, kept = partA))
        assertFalse(cache.contains(partAB))
        assertTrue(cache.contains(partA))
        // Made whole: the plain key is written and every variant goes; the neighbour's (y 1444) stays.
        cache.put(key, ByteArray(120))
        assertEquals(1, cache.clearVariants(key, kept = key))
        assertFalse(cache.contains(partA))
        assertTrue(cache.contains(key))
        assertTrue(cache.contains(neighbour))
        assertEquals(220L, cache.bytes)
        assertFalse(File(dir, partA).exists(), "the file is gone too")
        assertEquals(0, cache.clearVariants(key, kept = key))
        assertEquals(0, cache.clearVariants("sources/contours-v9-ft-512@d1/12/999/1.pbf", kept = "x"), "no directory")
    }
}
