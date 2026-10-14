package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ElevationImageDecoder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T98: a contour tile over a DEM pack's edge, made over a configured server's DEM, reads at least at the pack's first
 * zoom. The post-wave 13 sweep's device tour had the debug build's default server set but not running: the z8 view's
 * contour tiles (plans sampling z7, the Rainier pack starting at z8) read the server's z7 and z6 tiles only, found
 * none, and drew no line, while the pack under them had every elevation.
 */
class PackFloorContourTest {
    /** The Rainier pack's bounds (`areas.json` rainier) and first zoom. */
    private val box = PackBounds(-122.05, 46.6, -121.4, 47.1)
    private val packs = listOf(box to 8)
    private val base = ContourTileOptions()

    /** A synthetic volcano: 4392 m at the summit, 100 m lower per km, 500 m beyond. */
    private fun elevation(lon: Double, lat: Double): Float {
        val km = hypot((lon + 121.760) * 111.32 * cos(Math.toRadians(46.853)), (lat - 46.853) * 110.57)
        return maxOf(500.0, 4392.0 - 100.0 * km).toFloat()
    }

    /** An "encoded" tile is its z, x, y; decoding computes the volcano at each pixel centre. */
    private fun encode(z: Int, x: Int, y: Int) = java.nio.ByteBuffer.allocate(12).putInt(z).putInt(x).putInt(y).array()

    private val decoder = object : ElevationImageDecoder {
        override fun decode(bytes: ByteArray): ArgbImage = error("elevations only")
        override fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray {
            val b = java.nio.ByteBuffer.wrap(bytes)
            val (z, x, y) = Triple(b.int, b.int, b.int)
            return FloatArray(width * height) { i ->
                val px = x * width + i % width + 0.5
                val py = y * height + i / width + 0.5
                elevation(WebMercator.xToLon(px / width, z), WebMercator.yToLat(py / height, z))
            }
        }
    }

    /**
     * The phone's online pyramid ([OnlineTiles.pyramid], from the server's first zoom) with the server unreachable:
     * the pack answers its tiles (z8 to z14 overlapping its box, whole tiles as the region packs store them), nothing else does.
     */
    private fun offlinePyramid() = DemPyramid(OnlineTiles.DEM_MIN_ZOOM, OnlineTiles.DEM_MAX_ZOOM, decoder) { z, x, y ->
        if (z in 8..14 && OnlineTiles.tileOverlaps(box, z, x, y)) encode(z, x, y) else null
    }

    @Test
    fun serverTilesOverAPackReadAtItsFirstZoom() {
        // The tour's z8 view: contour tiles 8/41/89 and 8/41/90 over the pack, and MapLibre's fallback z7 tiles.
        for ((z, x, y) in listOf(Triple(8, 41, 90), Triple(8, 41, 89), Triple(7, 20, 45), Triple(7, 20, 44))) {
            assertTrue(ContourTiles.plan(z, x, y, base).readZoom < 8, "$z/$x/$y samples above the pack's first zoom")
            val options = OnlineTiles.serverContourOptions(base, packs, z, x, y)
            val plan = ContourTiles.plan(z, x, y, options)
            assertEquals(8, plan.readZoom, "$z/$x/$y")
            assertEquals(ContourTiles.plan(z, x, y, base).sampleZoom, plan.sampleZoom, "$z/$x/$y traces the same grid")
        }
        // Plans already at or below the pack's first zoom, and tiles away from the pack, are unchanged.
        assertSame(base, OnlineTiles.serverContourOptions(base, packs, 9, 82, 180))
        assertSame(base, OnlineTiles.serverContourOptions(base, packs, 12, 663, 1443))
        assertSame(base, OnlineTiles.serverContourOptions(base, packs, 8, 41, 92))
        assertSame(base, OnlineTiles.serverContourOptions(base, emptyList(), 8, 41, 90))
        // Never deeper than the tile's spec zoom: a z6 tile reads z7 at most.
        assertEquals(7, ContourTiles.plan(6, 10, 22, OnlineTiles.serverContourOptions(base, packs, 6, 10, 22)).readZoom)
    }

    @Test
    fun offlineContourTilesOverThePackDrawLines() {
        for ((z, x, y) in listOf(Triple(8, 41, 90), Triple(7, 20, 45))) {
            // Before T98: the plan's own zoom, which the unreachable server alone was asked for: no line at all.
            val before = ContourTiles.generate(offlinePyramid(), z, x, y, base)
            assertEquals(0, before.lines, "$z/$x/$y from the server's zooms only")
            val after = ContourTiles.generate(offlinePyramid(), z, x, y, OnlineTiles.serverContourOptions(base, packs, z, x, y))
            // The volcano from 500 m to 4392 m: the 2000 ft lines from 2000 to 14000 ft, closed rings around the summit.
            assertTrue(after.lines >= 7, "$z/$x/$y drew ${after.lines} lines")
            assertTrue(after.indexLines >= 1, "$z/$x/$y has the 10000 ft index line")
        }
    }
}
