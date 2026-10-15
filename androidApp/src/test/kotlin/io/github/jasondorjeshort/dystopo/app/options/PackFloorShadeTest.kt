package io.github.jasondorjeshort.dystopo.app.options

import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ElevationImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.tiles.ShadeOptions
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T104 (T98's follow-up): a shade tile over a DEM pack's edge, made over a configured server's DEM, reads at least at
 * the pack's first zoom. With the server set but unreachable, the z6 and z7 slope and multi-direction hillshade tiles at
 * the device tour's Rainier centre read the online pyramid's own z6 and z7 levels, which only the server fills, and came
 * out blank, while the Rainier pack (z8 to z14) under them had every elevation: the contour tiles' hole T98 fixed
 * ([PackFloorContourTest][io.github.jasondorjeshort.dystopo.app.online.PackFloorContourTest]).
 */
class PackFloorShadeTest {
    /** The Rainier pack's bounds (the pack's header) and first zoom. */
    private val box = PackBounds(-122.05, 46.6, -121.4, 47.1)
    private val packs = listOf(box to 8)

    /** A synthetic volcano: 4392 m at the summit, 600 m lower per km (31 degrees), 500 m beyond. */
    private fun elevation(lon: Double, lat: Double): Float {
        val km = hypot((lon + 121.760) * 111.32 * cos(Math.toRadians(46.853)), (lat - 46.853) * 110.57)
        return maxOf(500.0, 4392.0 - 600.0 * km).toFloat()
    }

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

    /** The phone's online pyramid (from the server's first zoom) with the server unreachable: only the pack answers. */
    private fun offlinePyramid() = DemPyramid(OnlineTiles.DEM_MIN_ZOOM, OnlineTiles.DEM_MAX_ZOOM, decoder) { z, x, y ->
        if (z in 8..14 && OnlineTiles.tileOverlaps(box, z, x, y)) encode(z, x, y) else null
    }

    /** The tile under the tour's Rainier centre (46.85, -121.72) at [z]. */
    private fun centre(z: Int) = Triple(z, WebMercator.lonToX(-121.72, z).toInt(), WebMercator.latToY(46.85, z).toInt())

    private val looks = listOf(
        ShadeOptions(HillshadeMode.OFF, 0.0, slope = SlopeShading.AVALANCHE),
        ShadeOptions(HillshadeMode.OFF, 0.0, slope = SlopeShading.STEEPNESS),
        ShadeOptions(HillshadeMode.MULTI, 1.0),
    )

    @Test
    fun readZoomIsThePackFloorAboveIt() {
        for (z in listOf(6, 7)) {
            val (_, x, y) = centre(z)
            assertEquals(8, OnlineTiles.packFloor(packs, z, x, y), "z$z over the pack")
            assertEquals(8, OnlineTiles.shadeReadZoom(OnlineTiles.packFloor(packs, z, x, y), z))
        }
        // At and below the pack's first zoom the tile reads its own zoom; away from the pack there is no floor.
        val (_, x8, y8) = centre(8)
        assertNull(OnlineTiles.shadeReadZoom(OnlineTiles.packFloor(packs, 8, x8, y8), 8))
        assertNull(OnlineTiles.packFloor(packs, 8, x8 + 4, y8))
        assertNull(OnlineTiles.packFloor(emptyList(), 7, 20, 45))
        // Never more than ShadeTiles.MAX_OVERSAMPLE zooms deeper: a z5 tile over a z8 pack keeps its own zoom.
        assertNull(OnlineTiles.shadeReadZoom(8, 5))
        assertEquals(8, OnlineTiles.shadeReadZoom(8, 6))
    }

    @Test
    fun offlineShadeTilesOverThePackAreNotBlank() {
        for (z in listOf(6, 7)) {
            val (_, x, y) = centre(z)
            val plan = ShadeTiles.plan(z, x, y, OnlineTiles.DEM_MAX_ZOOM)
            for (o in looks) {
                // Before T104: the tile's own zoom, which the unreachable server alone was asked for.
                val before = ShadeTiles.render(ShadeTiles.sample(offlinePyramid(), plan), plan, o)
                assertEquals(256 * 256, before.noDataPixels, "z$z ${o.key} from the server's zoom only")
                val readZoom = OnlineTiles.shadeReadZoom(OnlineTiles.packFloor(packs, z, x, y), z)
                val after = ShadeTiles.render(ShadeTiles.sample(offlinePyramid(), plan, readZoom), plan, o)
                // The pack's box (about 30 by 35 pixels of a z6 tile) has data, the rest stays transparent; the volcano is drawn.
                val withData = 256 * 256 - after.noDataPixels
                assertTrue(withData > 500 shl (2 * (z - 6)), "z$z ${o.key}: $withData pixels with data")
                assertTrue(after.argb.count { it != 0 } > 10, "z$z ${o.key} draws the volcano")
                if (o.slope != SlopeShading.OFF) assertTrue(after.slopePixels > 0, "z$z ${o.key} tints the flanks")
            }
        }
    }
}
