package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T88: a DEM tile deeper than the deepest rendered zoom is synthesized from it ([DirectSources.synthesisSources]
 * names the tiles up front so their renders run together). The list must be exactly what [DemPyramid.window]
 * reads, or a render is started for nothing or a needed one is still started late.
 */
class SynthesisSourcesTest {
    private fun read(t: TileId, k: Int): Set<TileId> {
        val asked = HashSet<TileId>()
        val flat = ArgbImage(256, 256, IntArray(256 * 256) { (0xFF shl 24) or (128 shl 16) or (100 shl 8) }) // 100 m in Terrarium
        val p = DemPyramid(8, k, { flat }, cacheTiles = 16) { z, x, y -> asked += TileId(z, x, y); ByteArray(1) }
        p.window(t.z, t.x * 256, t.y * 256, 256, 256)
        return asked
    }

    @Test
    fun matchesWhatThePyramidReads() {
        val k = 12
        for (t in listOf(TileId(13, 1324, 2884), TileId(13, 1325, 2885), TileId(13, 1326, 2887), TileId(14, 2650, 5770), TileId(14, 2651, 5771), TileId(15, 5301, 11543))) {
            assertEquals(read(t, k), DirectSources.synthesisSources(t, k).toSet(), "tile $t")
        }
    }

    @Test
    fun aDeeperTileUsesItsAncestorAndTheNeighboursOnItsEdgeSide() {
        // z13 x even: the left column's first samples reach into the ancestor's west neighbour; y odd: the south one.
        val s = DirectSources.synthesisSources(TileId(13, 1326, 2887), 12).toSet()
        assertEquals(setOf(TileId(12, 663, 1443), TileId(12, 662, 1443), TileId(12, 663, 1444), TileId(12, 662, 1444)), s)
        // At or above the rendered zoom: the tile itself.
        assertEquals(listOf(TileId(12, 663, 1443)), DirectSources.synthesisSources(TileId(12, 663, 1443), 12))
    }

    @Test
    fun theWorldEdgeIsClamped() {
        val s = DirectSources.synthesisSources(TileId(13, 0, 0), 12)
        assertEquals(listOf(TileId(12, 0, 0)), s)
        val last = (1 shl 13) - 1
        assertTrue(DirectSources.synthesisSources(TileId(13, last, last), 12).all { it.x < (1 shl 12) && it.y < (1 shl 12) })
    }
}
