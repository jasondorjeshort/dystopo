package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.RawElevations
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T395 (issue #35): a pyramid whose first zoom is deeper than the zoom a reader asks answers in bounded memory and
 * work. The point answer over a z15-only pyramid built a gigabyte window before T395 (the coarse layer four zooms
 * down, `downsample` building the whole z15 window under it), and `covers` looked up every z15 tile under it first.
 */
class DemPyramidBoundsTest {
    // A public point (Rainier's summit area), a cone 2,000 m high over 3 km, flat 1,000 m around it.
    private val lat = 46.853
    private val lon = -121.76

    private fun elevation(z: Int, px: Double, py: Double): Float {
        val cx = WebMercator.lonToX(lon, z) * 256
        val cy = WebMercator.latToY(lat, z) * 256
        val m = WebMercator.metersPerPixel(lat, z, 256)
        val d = hypot(px - cx, py - cy) * m
        return (3000.0 - d * 2000.0 / 3000.0).coerceAtLeast(1000.0).toFloat()
    }

    /** z15-only tiles within [radiusTiles] of the point's tile; [fetches] counts every fetch asked, found or not. */
    private class Counted(val dem: DemPyramid, val fetches: () -> Long, val found: () -> Long)

    private fun z15Only(radiusTiles: Int): Counted {
        val z = 15
        val ptx = WebMercator.lonToX(lon, z).toInt()
        val pty = WebMercator.latToY(lat, z).toInt()
        var fetches = 0L
        var found = 0L
        val dem = DemPyramid(z, z, RawElevations, cacheTiles = 16) { tz, x, y ->
            fetches++
            if (kotlin.math.abs(x - ptx) > radiusTiles || kotlin.math.abs(y - pty) > radiusTiles) {
                null
            } else {
                found++
                RawElevations.encode(FloatArray(256 * 256) { i -> elevation(tz, x * 256 + i % 256 + 0.5, y * 256 + i / 256 + 0.5) })
            }
        }
        return Counted(dem, { fetches }, { found })
    }

    /** The z15 tiles under [Horizon.windowsAround]'s windows at [zoom]: each is looked up at most once by the fix. */
    private fun tilesUnderWindows(zoom: Int): Long = Horizon.windowsAround(lat, lon, zoom).sumOf { (w, _) ->
        val k = 15 - w.zoom
        val tx = ((w.x0.toLong() + w.width) shl k) / 256 - (w.x0.toLong() shl k) / 256 + 1
        val ty = ((w.y0.toLong() + w.height) shl k) / 256 - (w.y0.toLong() shl k) / 256 + 1
        tx * ty
    }

    @Test
    fun pointAnswerOverAZ15OnlyPyramidIsBounded() {
        val c = z15Only(radiusTiles = 6)
        val a = ShadeTiles.pointAnswer(c.dem, lat, lon, 1_782_302_400_000L) // 2026-06-24 12:00 UTC
        assertFalse(a.horizon.groundM.isNaN(), "ground at the point")
        assertTrue(a.horizon.groundM > 2900, "the cone's top: ${a.horizon.groundM}")
        assertEquals(14, a.zoom)
        // Every z15 tile under the fine (z14) and coarse (z11) windows at most once: some 5,000, against a 1 GB array
        // (or thousands of covers lookups and then the array) before T395.
        val bound = tilesUnderWindows(14)
        assertTrue(bound < 8_000, "the windows' z15 tiles: $bound")
        assertTrue(c.fetches() <= bound, "fetches ${c.fetches()} within the bound $bound")
        assertTrue(c.found() <= 2L * 13 * 13, "only the tiles that exist are decoded, once per window at most: ${c.found()}")
        // The coarse layer is the z15 mean wherever a z15 tile exists: 13 x 13 z15 tiles are 208 x 208 z11 cells.
        val (cw, _) = Horizon.windowsAround(lat, lon, 14)[1]
        val held = c.dem.window(cw.zoom, cw.x0, cw.y0, cw.width, cw.height).values.count { !it.isNaN() }
        assertEquals(208 * 208, held, "coarse cells held")
    }

    @Test
    fun coversBelowMinZoomOverALargeWindowLooksUpABoundedNumberOfTiles() {
        val c = z15Only(radiusTiles = 0)
        val (coarse, _) = Horizon.windowsAround(lat, lon, 14)[1]
        assertEquals(11, coarse.zoom)
        assertTrue(c.dem.covers(coarse.zoom, coarse.x0, coarse.y0, coarse.width, coarse.height), "may cover")
        assertTrue(c.fetches() <= DemPyramid.COVERS_LOOKUPS, "covers looked up ${c.fetches()} tiles")
        // A small window is still answered exactly (at most 64 lookups): no tile under it, false.
        val far = z15Only(radiusTiles = 0)
        val tx = WebMercator.lonToX(lon, 12).toInt() + 3
        val ty = WebMercator.latToY(lat, 12).toInt()
        assertFalse(far.dem.covers(12, tx * 256, ty * 256, 256, 256))
        assertEquals(64L, far.fetches())
        assertTrue(far.dem.covers(12, (tx - 3) * 256, ty * 256, 256, 256))
    }

    @Test
    fun meansMatchTheWholeWindowsAndSpanTilesPastEightZooms() {
        // Two z10 tiles with distinct constant values and NaN cells; the z1 cell over them is the mean of their non-NaN cells.
        val dem = DemPyramid(10, 10, RawElevations, cacheTiles = 4) { _, x, y ->
            when {
                x == 0 && y == 0 -> RawElevations.encode(FloatArray(65_536) { if (it % 2 == 0) 100f else Float.NaN })
                x == 1 && y == 0 -> RawElevations.encode(FloatArray(65_536) { 400f })
                else -> null
            }
        }
        // k = 9: one z1 cell spans 2 x 2 z10 tiles (no Int overflow, no 512 x 512 fine window per cell).
        val g = dem.window(1, 0, 0, 2, 2).values
        assertEquals((100.0 * 32_768 + 400.0 * 65_536) / (32_768 + 65_536), g[0].toDouble(), 1e-3)
        assertTrue(g[1].isNaN() && g[2].isNaN() && g[3].isNaN())
        // k = 2: a cell inside one tile, the same as before (the mean of its 4 x 4 cells).
        val h = dem.window(8, 62, 0, 4, 1).values
        assertEquals(100f, h[0])
        assertEquals(100f, h[1])
        assertEquals(400f, h[2])
        assertEquals(400f, h[3])
        assertFailsWith<IllegalArgumentException> { dem.window(10, 0, 0, 70_000, 70_000) }
    }
}
