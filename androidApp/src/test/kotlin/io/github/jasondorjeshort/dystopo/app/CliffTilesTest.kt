package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.CliffSides
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T49: rewritten cliff tiles survive a restart in the contour cache's LRU. */
class CliffTilesTest {
    private val root: File = Files.createTempDirectory("cliffs").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** Terrain falling toward the south everywhere: elevation = 5000 - global pixel row. */
    private fun pyramid(decodes: IntArray) = DemPyramid(10, 14, { bytes ->
        decodes[0]++
        val (_, _, y) = bytes.decodeToString().split('/').map { it.toInt() }
        ArgbImage(256, 256, IntArray(256 * 256) { i -> Terrarium.encode(5000f - (y * 256 + i / 256) * 0.5f) })
    }) { z, x, y -> "$z/$x/$y".toByteArray() }

    private fun cliffTile(vararg pts: Pair<Int, Int>): ByteArray {
        val f = Feature(3L, mapOf("class" to Value.StringValue("cliff")), Geometry.Lines(listOf(pts.map { TilePoint(it.first, it.second) })))
        return VectorTile(listOf(Layer(CliffSides.LAYER, listOf(f)))).encode()
    }

    @Test
    fun rewritesAreStoredOnDiskAndReadBackAfterARestart() {
        val cache = TileCache(root, 1 shl 20)
        val dir = ContourCache.cliffDir("osm11111", "dem22222")
        val decodes = intArrayOf(0)
        val westward = cliffTile(3000 to 2048, 1000 to 2048) // low side (south) on its left: must be reversed
        val eastward = cliffTile(1000 to 1024, 3000 to 1024) // already right
        val first = CliffTiles({ _, _, _ -> pyramid(decodes) }) { cache }
        val turned = first.orient(13, 1310, 2860, westward, dir)
        assertFalse(turned.contentEquals(westward))
        val same = first.orient(13, 1310, 2861, eastward, dir)
        assertTrue(same === eastward)
        assertEquals(2L, first.diskWrites.get())
        assertEquals(0, cache.get(ContourCache.key(dir, 13, 1310, 2861))!!.size, "unchanged tiles are an empty marker")

        // A new process (fresh memory cache, same disk): no DEM decode, same bytes.
        val decodedBefore = decodes[0]
        val second = CliffTiles({ _, _, _ -> error("the DEM must not be read for a cached tile") }) { TileCache(root, 1 shl 20) }
        assertContentEquals(turned, second.orient(13, 1310, 2860, westward, dir))
        assertTrue(second.orient(13, 1310, 2861, eastward, dir) === eastward)
        assertEquals(2L, second.diskHits.get())
        assertEquals(decodedBefore, decodes[0])
        // Without a directory (no DEM pack for the tile) nothing is written.
        val third = CliffTiles({ _, _, _ -> pyramid(decodes) }) { cache }
        third.orient(13, 1311, 2860, westward, null)
        assertEquals(0L, third.diskWrites.get())
    }

    @Test
    fun cliffDirectoriesLiveAsLongAsBothArchives() {
        val dir = ContourCache.cliffDir("osm11111", "dem22222")
        assertEquals("cliffs1-osm11111-dem22222", dir)
        assertTrue(ContourCache.keeps(dir, setOf("osm11111", "dem22222")))
        assertFalse(ContourCache.keeps(dir, setOf("dem22222")), "vector archive gone")
        assertFalse(ContourCache.keeps(dir, setOf("osm11111")), "DEM archive gone")
        assertFalse(ContourCache.keeps("cliffs0-osm11111-dem22222", setOf("osm11111", "dem22222")), "older version")
    }
}
