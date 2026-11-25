package io.github.jasondorjeshort.dystopo.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T357 (ADR 0066 decision 4): the tiles across a map's edge. The composition itself is `MergedTileTest`'s (shared); here the
 * caches' keys (an edge tile is kept under both maps' identities, so switching one off or removing it leaves those tiles
 * unread and the collection deletes them once a map is gone) and the order of the tile paths (where the cache or the network
 * holds a tile deeper than both maps, the lookup's answer comes first: the held tile answers whole, never blended with a map).
 */
class EdgeTilesTest {
    private val main = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app")

    @Test
    fun anEdgeTilesCacheDirectoriesAreKeptWhileBothMapsAre() {
        val both = setOf("0000000f", "0000000a", "osm11111")
        val contour = ContourCache.dirName("ft", 512, "0000000f+0000000a")
        val shade = ContourCache.shadeDir("multi-1.0", "0000000f+0000000a")
        val cliff = ContourCache.cliffDir("osm11111", "0000000f+0000000a")
        for (dir in listOf(contour, shade, cliff)) {
            assertTrue(ContourCache.keeps(dir, both), dir)
            assertFalse(ContourCache.keeps(dir, both - "0000000f"), "$dir: the fine map gone")
            assertFalse(ContourCache.keeps(dir, both - "0000000a"), "$dir: the wide map gone")
        }
        // One map's directories as before.
        assertTrue(ContourCache.keeps(ContourCache.dirName("ft", 512, "0000000a"), both - "0000000f"))
    }

    /** The body of `fun <name>(` in [text], to the next function at the same indent. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("    fun $name(")
        check(start >= 0) { "no fun $name" }
        val end = Regex("\n    (private |internal )?fun ").find(text, start + 10)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    private fun assertBefore(body: String, first: String, then: String) {
        val a = body.indexOf(first)
        val b = body.indexOf(then)
        assertTrue(a >= 0, "missing: $first")
        assertTrue(b >= 0, "missing: $then")
        assertTrue(a < b, "'$first' must come before '$then'")
    }

    @Test
    fun theHeldAndOnlinePathsAnswerBeforeAnEdgeTileIsComposed() {
        val tiles = File(main, "TileService.kt").readText()
        val dem = body(tiles, "demTile")
        assertBefore(dem, "held.tile(Holdings.Role.DEM, z, x, y)", "edgeDem(z, x, y)")
        assertBefore(dem, "packCoversDem(z, x, y)", "edgeDem(z, x, y)")
        val contour = body(tiles, "contourTile")
        assertBefore(contour, "packCoversContour(z, x, y)", "edgeFor(z, x, y)")
        assertBefore(contour, "held.deeperOver(z, x, y) != null", "edgeFor(z, x, y)")
        assertTrue("shadeFromMaps(z, x, y)" in body(tiles, "shadeTile"), "shade tiles over deeper held cells skip the maps' branch")
        val shade = File(main, "options/ShadeService.kt").readText()
        for (name in listOf("tile", "sunTile")) {
            val b = body(shade, name)
            assertBefore(b, "heldPyramid()", "edgeFor(z, x, y)")
            assertBefore(b, "packCovers || online == null", "edgeFor(z, x, y)")
        }
    }
}
