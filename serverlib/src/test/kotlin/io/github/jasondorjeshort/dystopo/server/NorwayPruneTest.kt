package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.File
import java.nio.file.Files
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T92: live tiles from before the T66 Norway zero-cell rule are deleted once per cache. */
class NorwayPruneTest {
    private val dir: File = Files.createTempDirectory("norway").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun tile(lat: Double, lon: Double, z: Int) =
        Triple(z, floor(WebMercator.lonToX(lon, z)).toInt(), floor(WebMercator.latToY(lat, z)).toInt())

    private fun dem(cache: File, t: Triple<Int, Int, Int>, provenance: String): File {
        val png = File(cache, "live/${LiveTiles.CACHE_VERSION}/${t.first}/${t.second}/${t.third}.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(100)) }
        File(png.path.removeSuffix(".png") + ".src").writeText(provenance)
        return png
    }

    private fun derived(cache: File, key: String, t: Triple<Int, Int, Int>, ext: String) =
        File(cache, "derived/$key/${t.first}/${t.second}/${t.third}.$ext").apply { parentFile.mkdirs(); writeBytes(ByteArray(40)) }

    @Test
    fun kartverketTilesAndDerivedTilesOverNorwayGoOnce() {
        val cache = File(dir, "cache")
        val coast = tile(61.0, 5.2, 12) // the west coast: Kartverket
        val inland = tile(68.0, 20.5, 12) // Swedish side of the border, inside the coverage box: Copernicus
        val rainier = tile(46.85, -121.76, 12)
        val oldNorway = dem(cache, coast, "kartverket-dtm,nn2000-geoid,egm2008")
        val sweden = dem(cache, inland, "copernicus-glo30,egm2008")
        val us = dem(cache, rainier, "usgs-3dep-13,egm2008")
        val contours = derived(cache, "contours-c3-m-abcd1234-v1", coast, "pbf")
        val shade = derived(cache, "shade1-multi-v1", inland, "png")
        val usContours = derived(cache, "contours-c3-m-abcd1234-v1", rainier, "pbf")
        val live = LiveTiles(emptyList(), emptyList(), cacheRoot = cache, outDir = File(dir, "out"), threads = 1)

        assertTrue(live.pruneNorwayTiles(wait = true) != null)
        assertFalse(oldNorway.exists() || File(oldNorway.path.removeSuffix(".png") + ".src").exists(), "the Kartverket tile and its sidecar")
        assertTrue(sweden.isFile && us.isFile, "other sources' DEM tiles stay")
        assertFalse(contours.exists() || shade.exists(), "derived tiles over Norway's box")
        assertTrue(usContours.isFile)
        @Suppress("UNCHECKED_CAST")
        val p = (live.stats()["staleCachePrune"] as Map<String, Any?>)[LiveTiles.NORWAY_PRUNE] as Map<String, Any?>
        assertEquals("deleted", p["status"])
        assertEquals(1, p["demTiles"])
        assertEquals(2, p["derivedTiles"])
        assertEquals(4L, p["files"])
        assertEquals(100L + "kartverket-dtm,nn2000-geoid,egm2008".length + 2 * 40, p["bytes"])
        // The caches' byte counts dropped with them.
        assertEquals(2 * 100L + "copernicus-glo30,egm2008".length + "usgs-3dep-13,egm2008".length, live.liveCache.bytes)
        assertEquals(40L, live.derivedCache.bytes)

        // Once per cache: a Kartverket tile rendered after the rule stays.
        val fresh = dem(cache, coast, "kartverket-dtm,nn2000-geoid,egm2008")
        assertTrue(File(cache, "live/${LiveTiles.NORWAY_PRUNE_MARKER}").isFile)
        assertNull(LiveTiles(emptyList(), emptyList(), cacheRoot = cache, outDir = File(dir, "out"), threads = 1).pruneNorwayTiles(wait = true))
        assertTrue(fresh.isFile)
    }
}
