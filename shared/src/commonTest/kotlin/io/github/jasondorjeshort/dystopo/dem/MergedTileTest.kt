package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T357: [MergedTile], the elevation across a map's edge composed cell by cell: the same value at the same place from any
 * tile, the fine map alone past the band, the coarse map alone outside the fine box, a smooth ramp in between.
 */
class MergedTileTest {
    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)

    /** Terrain as a function of place; the coarse map reads it 20 m higher (another survey), so a seam would show. */
    private fun ground(lon: Double, lat: Double): Float = (1500 + 300 * sin(lon * 40) * sin(lat * 50)).toFloat()

    /** A pyramid whose tiles hold [f] at each cell's centre from [minZ] to [maxZ] (raw floats). */
    private fun pyramid(minZ: Int, maxZ: Int, f: (Double, Double) -> Float) = DemPyramid(minZ, maxZ, RawElevations, cacheTiles = 32) { z, x, y ->
        RawElevations.encode(FloatArray(256 * 256) { k -> f(WebMercator.xToLon(x + (k % 256 + 0.5) / 256, z), WebMercator.yToLat(y + (k / 256 + 0.5) / 256, z)) })
    }

    private val fine = MergedTile.Source("fine", "0000000f", fineBox, 8, 14, pyramid(8, 14, ::ground))
    private val wide = MergedTile.Source("wide", "0000000a", wideBox, 8, 11, pyramid(8, 11) { lon, lat -> ground(lon, lat) + 20f }, chosen = true)
    private val merged = MergedTile(listOf(wide, fine))

    private fun tileOf(lon: Double, lat: Double, z: Int) = floor(WebMercator.lonToX(lon, z)).toInt() to floor(WebMercator.latToY(lat, z)).toInt()

    @Test
    fun theDeeperMapComesFirstAndTheKeyNamesBoth() {
        assertEquals(listOf("fine", "wide"), merged.names, "depth before the map chosen last")
        assertEquals("0000000f+0000000a", merged.key)
        assertEquals(8, merged.pyramid.minZoom)
        assertEquals(14, merged.pyramid.maxZoom)
    }

    @Test
    fun aWindowAcrossATileBoundaryIsTheTwoTilesSideBySide() {
        for (z in listOf(12, 14, 15)) {
            // The tile holding the fine map's south edge at -121.75, and the one west of it.
            val (x, y) = tileOf(-121.75, 46.80, z)
            val across = merged.window(z, x * 256 - 128, y * 256, 256, 256)
            val left = merged.window(z, (x - 1) * 256, y * 256, 256, 256)
            val right = merged.window(z, x * 256, y * 256, 256, 256)
            for (j in 0 until 256) {
                for (i in 0 until 256) {
                    val want = if (i < 128) left.values[j * 256 + 128 + i] else right.values[j * 256 + i - 128]
                    assertEquals(want, across.values[j * 256 + i], "z$z cell $i,$j: the same value from either tile")
                }
            }
            // The pyramid (contour, shade and cliff paths) gives the same cells as the direct read, at the map's zooms.
            if (z <= 14) assertContentEquals(right.values, merged.pyramid.window(z, x * 256, y * 256, 256, 256).values, "z$z pyramid")
        }
    }

    @Test
    fun theFineMapAlonePastTheBandAndTheWideMapAloneOutsideTheBox() {
        val z = 14
        val inside = merged.window(z, (WebMercator.lonToX(-121.75, z) * 256).toInt(), (WebMercator.latToY(46.85, z) * 256).toInt(), 16, 16)
        val fineOnly = fine.pyramid.window(z, (WebMercator.lonToX(-121.75, z) * 256).toInt(), (WebMercator.latToY(46.85, z) * 256).toInt(), 16, 16)
        assertContentEquals(fineOnly.values, inside.values)
        val gx = (WebMercator.lonToX(-121.75, z) * 256).toInt()
        val gy = (WebMercator.latToY(46.79, z) * 256).toInt()
        assertContentEquals(wide.pyramid.window(z, gx, gy, 16, 16).values, merged.window(z, gx, gy, 16, 16).values)
    }

    @Test
    fun theStepAtTheBoxIsSpreadOverTheBand() {
        // A column of z14 cells running north across the fine map's south edge: the 20 m between the maps comes in over the
        // band, never more than the terrain's own slope plus the band's steepest share of 20 m per cell.
        val z = 14
        val gx = (WebMercator.lonToX(-121.75, z) * 256).toInt()
        val gy0 = (WebMercator.latToY(46.81, z) * 256).toInt()
        val gy1 = (WebMercator.latToY(46.79, z) * 256).toInt()
        val g = merged.window(z, gx, gy0, 1, gy1 - gy0)
        val cell = g.cellSizeMeters
        var worst = 0.0
        for (j in 1 until g.height) {
            val lat0 = WebMercator.yToLat((gy0 + j - 0.5) / 256, z)
            val lat1 = WebMercator.yToLat((gy0 + j + 0.5) / 256, z)
            val own = abs(ground(WebMercator.xToLon((gx + 0.5) / 256, z), lat1) - ground(WebMercator.xToLon((gx + 0.5) / 256, z), lat0))
            worst = maxOf(worst, (abs(g.values[j] - g.values[j - 1]) - own).toDouble())
        }
        val bandShare = 20 * 1.5 * cell / EdgeBlend.BAND_M
        assertTrue(worst <= bandShare + 0.6, "the largest step past the terrain's own is $worst m, the band's share $bandShare m")
        // Without the band the 20 m step would be there.
        val stepped = MergedTerrain.read(io.github.jasondorjeshort.dystopo.geo.MercatorWindow(z, gx, gy0, 1, gy1 - gy0), listOf(
            ElevationSource("fine", 14, fineBox) { w -> fine.pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) },
            ElevationSource("wide", 11, wideBox) { w -> wide.pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) },
        ), bandM = 0.0)
        assertTrue((1 until stepped.height).maxOf { abs(stepped.values[it] - stepped.values[it - 1]) } > 15f, "the seam the band removes")
    }

    @Test
    fun edgeTilesAreThoseReachingAFinerMapsBand() {
        val boxes = listOf(fineBox, wideBox)
        for (z in listOf(12, 14, 15)) {
            val (x, y) = tileOf(-121.75, 46.80, z)
            assertTrue(MergedTile.joins(boxes, MergedTile.extent(z, x, y)), "z$z the tile on the fine map's edge")
            val (ix, iy) = tileOf(-121.75, 46.85, z)
            // A z12 tile is about 6.7 km across here and the fine map 11 km: every z12 tile of it reaches an edge's band.
            if (z > 12) assertFalse(MergedTile.joins(boxes, MergedTile.extent(z, ix, iy)), "z$z well inside the fine map")
            val (ox, oy) = tileOf(-121.55, 46.73, z)
            assertFalse(MergedTile.joins(boxes, MergedTile.extent(z, ox, oy)), "z$z inside the wide map only")
        }
        // The wide map's own edge has nothing to blend into; one map alone never joins.
        val (wx, wy) = tileOf(-121.70, 46.68, 14)
        assertFalse(MergedTile.joins(boxes, MergedTile.extent(14, wx, wy)))
        assertFalse(MergedTile.joins(listOf(fineBox), MergedTile.extent(14, tileOf(-121.75, 46.80, 14).first, tileOf(-121.75, 46.80, 14).second)))
    }

    @Test
    fun theSharesFollowTheWeightAndMixMatchesEachMapAwayFromTheBand() {
        val z = 14
        val (x, y) = tileOf(-121.75, 46.80, z)
        val shares = merged.shares(z, x, y, 256)
        assertEquals(listOf(14, 11), merged.depths)
        for (p in 0 until 256 * 256) assertEquals(1f, shares[0][p] + shares[1][p], 1e-5f)
        // The tile straddles the box's south edge: its top rows are past the band (the fine map's), its bottom rows outside (the wide map's).
        val top = shares[0].copyOfRange(0, 256)
        val bottom = shares[1].copyOfRange(255 * 256, 256 * 256)
        val fineTop = EdgeBlend.weight(fineBox, -121.75, WebMercator.yToLat(y + 0.5 / 256, z)) >= 1.0
        if (fineTop) assertTrue(top.all { it == 1f })
        assertTrue(bottom.all { it == 1f }, "outside the fine box the wide map's drawing alone")
        val red = IntArray(256 * 256) { 0xffff0000.toInt() }
        val blue = IntArray(256 * 256) { 0x800000ff.toInt() }
        val mixed = MergedTile.mix(listOf(red, blue), shares)
        assertEquals(blue[0], mixed[255 * 256])
    }

    @Test
    fun rawElevationsRoundTrip() {
        val v = floatArrayOf(0f, -12.5f, 4392.25f, Float.NaN, 1e-3f)
        val back = RawElevations.elevations(RawElevations.encode(v + FloatArray(3)), 4, 2)
        assertEquals(v[2], back[2])
        assertTrue(back[3].isNaN())
        assertNotNull(back)
    }
}
