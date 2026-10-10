package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShadeTilesTest {
    // A cone around a world point: about 10 m drop per z13 pixel (around 40 degrees there), flat beyond 3000 m down.
    private val peakU = (2 * 256 + 200.0) / (256.0 * 4)
    private val peakV = (1 * 256 + 100.0) / (256.0 * 4)

    private fun elevationAtWorld(u: Double, v: Double): Double = (3000.0 - hypot(u - peakU, v - peakV) * 2.0e7).coerceAtLeast(0.0)

    private val decoder = ImageDecoder { bytes ->
        val (z, x, y) = bytes.decodeToString().split("/").map { it.toInt() }
        val world = 256.0 * (1L shl z)
        ArgbImage(256, 256, IntArray(256 * 256) { i ->
            val px = x * 256 + i % 256 + 0.5
            val py = y * 256 + i / 256 + 0.5
            Terrarium.encode(elevationAtWorld(px / world, py / world).toFloat())
        })
    }

    private fun pyramid(min: Int, max: Int, has: (Int, Int, Int) -> Boolean = { _, _, _ -> true }) =
        DemPyramid(min, max, decoder) { z, x, y -> if (has(z, x, y)) "$z/$x/$y".encodeToByteArray() else null }

    private fun peakTile(z: Int): Pair<Int, Int> {
        val world = 256.0 * (1L shl z)
        return (peakU * world / 256).toInt() to (peakV * world / 256).toInt()
    }

    private val multiSlope = ShadeOptions(HillshadeMode.MULTI, 0.8, slope = true)

    @Test
    fun edgesMatchTheNeighbourExactly() {
        val dem = pyramid(8, 13)
        val (tx, ty) = peakTile(13)
        for (options in listOf(multiSlope, ShadeOptions(HillshadeMode.SINGLE, 1.0), ShadeOptions(HillshadeMode.OFF, 0.0, slope = true))) {
            val a = ShadeTiles.generate(dem, 13, tx, ty, options)
            val b = ShadeTiles.generate(dem, 13, tx + 1, ty, options)
            // A tile straddling the seam (half of a, half of b) must agree pixel for pixel with both:
            // every pixel only depends on the DEM cells around it, which the halo provides at the edges.
            val pa = ShadeTiles.plan(13, tx, ty, dem.maxZoom)
            val straddle = pa.copy(windowX = pa.windowX + 128)
            val s = ShadeTiles.render(ShadeTiles.sample(dem, straddle), straddle, options)
            for (j in 0 until 256) {
                for (i in 0 until 128) assertEquals(a.argb[j * 256 + 128 + i], s.argb[j * 256 + i], "left half ($i,$j) $options")
                for (i in 128 until 256) assertEquals(b.argb[j * 256 + i - 128], s.argb[j * 256 + i], "right half ($i,$j) $options")
            }
        }
    }

    @Test
    fun steepGroundIsTintedFlatGroundIsClear() {
        val dem = pyramid(8, 13)
        val (tx, ty) = peakTile(13)
        val tile = ShadeTiles.generate(dem, 13, tx, ty, ShadeOptions(HillshadeMode.OFF, 0.0, slope = true))
        assertTrue(tile.slopePixels > 10_000, "cone flanks tinted: ${tile.slopePixels}")
        assertEquals(0, tile.noDataPixels)
        // The tint is one of the band colours at the slope opacity.
        val colours = Slope.bands.map { it.rgb }.toSet()
        val alpha = (ShadeTiles.SLOPE_OPACITY * 255).toInt()
        for (p in tile.argb) if (p != 0) {
            assertTrue((p and 0xFFFFFF) in colours, "band colour ${p.toString(16)}")
            assertTrue(((p ushr 24) - alpha) in -1..1, "alpha ${(p ushr 24)}")
        }
        // Far from the cone the ground is flat (0 m): no hillshade, no tint, fully transparent.
        val far = ShadeTiles.generate(dem, 13, tx + 6, ty + 6, multiSlope)
        assertTrue(far.argb.all { it == 0 }, "flat ground stays transparent")
        assertEquals(0, ShadeTiles.generate(dem, 13, tx, ty, ShadeOptions(HillshadeMode.MULTI, 1.0)).slopePixels)
    }

    @Test
    fun intensityAndNightScaleTheOverlay() {
        val dem = pyramid(8, 13)
        val (tx, ty) = peakTile(13)
        fun meanAlpha(o: ShadeOptions) = ShadeTiles.generate(dem, 13, tx, ty, o).argb.sumOf { (it ushr 24).toLong() } / 65536.0
        val full = meanAlpha(ShadeOptions(HillshadeMode.SINGLE, 1.0))
        val half = meanAlpha(ShadeOptions(HillshadeMode.SINGLE, 0.5))
        val night = meanAlpha(ShadeOptions(HillshadeMode.SINGLE, 1.0, night = true))
        assertTrue(full > 20, "hillshade draws on the cone: $full")
        assertEquals(full / 2, half, full * 0.03)
        assertTrue(night < full, "night is dimmer: $night vs $full")
        assertTrue(meanAlpha(ShadeOptions(HillshadeMode.SINGLE, 0.0)) == 0.0)
    }

    @Test
    fun overzoomSamplesTheDeepestPackZoomAndStaysSmooth() {
        val plan = ShadeTiles.plan(15, 100, 200, demMaxZoom = 13)
        assertEquals(13, plan.sampleZoom)
        assertEquals(2, plan.reduction)
        assertEquals(64, plan.gridTileSize)
        assertEquals(100 * 64 - ShadeTiles.HALO, plan.windowX)
        assertEquals(0, ShadeTiles.plan(12, 1, 1, 13).reduction)
        assertEquals(ShadeTiles.MAX_REDUCTION, ShadeTiles.plan(20, 1, 1, 13).reduction)

        val dem = pyramid(8, 13)
        val (tx, ty) = peakTile(15)
        val tile = ShadeTiles.generate(dem, 15, tx, ty + 1, ShadeOptions(HillshadeMode.MULTI, 1.0))
        assertEquals(256 * 256, tile.argb.size)
        assertEquals(0, tile.noDataPixels)
        // Interpolated shade: neighbouring pixels differ by little (no 4-px facets).
        var maxStep = 0
        for (j in 0 until 256) for (i in 1 until 256) {
            maxStep = maxOf(maxStep, kotlin.math.abs((tile.argb[j * 256 + i] ushr 24) - (tile.argb[j * 256 + i - 1] ushr 24)))
        }
        assertTrue(maxStep < 24, "alpha step between neighbours $maxStep")
    }

    @Test
    fun noDataIsTransparent() {
        val (tx, ty) = peakTile(13)
        // The pack has the peak tile at z13 only (no ancestors beyond z13): its neighbours are empty.
        val dem = pyramid(13, 13) { z, x, y -> z == 13 && x == tx && y == ty }
        val tile = ShadeTiles.generate(dem, 13, tx, ty, multiSlope)
        assertTrue(tile.noDataPixels in 1..1024, "only the outermost pixels lack a neighbour: ${tile.noDataPixels}")
        val outside = ShadeTiles.generate(dem, 13, tx + 3, ty, multiSlope)
        assertEquals(256 * 256, outside.noDataPixels)
        assertTrue(outside.argb.all { it == 0 })
    }

    @Test
    fun optionKeysDifferWheneverPixelsDo() {
        val keys = listOf(
            ShadeOptions(HillshadeMode.SINGLE, 1.0), ShadeOptions(HillshadeMode.MULTI, 1.0), ShadeOptions(HillshadeMode.MULTI, 0.5),
            ShadeOptions(HillshadeMode.MULTI, 0.5, slope = true), ShadeOptions(HillshadeMode.MULTI, 0.5, slope = true, night = true),
            ShadeOptions(HillshadeMode.OFF, 0.0, slope = true),
        ).map { it.key }
        assertEquals(keys.size, keys.toSet().size, keys.toString())
        assertTrue(keys.all { it.matches(Regex("[a-z0-9-]+")) }, "url- and path-safe: $keys")
        assertNotEquals(ShadeOptions(HillshadeMode.MULTI, 0.5).key, ShadeOptions(HillshadeMode.MULTI, 0.51).key)
    }

    @Test
    fun fusedFieldMatchesTheSharedHillshadeAndSlope() {
        // Rough terrain with no-data holes, including on the border.
        val w = 40
        val values = FloatArray(w * w) { i ->
            val x = i % w
            val y = i / w
            (1500 + 300 * kotlin.math.sin(x * 0.7) * kotlin.math.cos(y * 0.45) + 40.0 * x - 25.0 * y).toFloat()
        }
        for (i in listOf(0, 5, 77, 401, 402, 441, w * w - 1, w * 20)) values[i] = Float.NaN
        val grid = io.github.jasondorjeshort.dystopo.dem.ElevationGrid(w, w, 12.5, values)
        val gradient = io.github.jasondorjeshort.dystopo.render.Gradient.of(grid)
        for (zf in listOf(1.0, 2.3)) {
            val single = ShadeTiles.field(grid, zf, HillshadeMode.SINGLE, slope = true)
            val multi = ShadeTiles.field(grid, zf, HillshadeMode.MULTI, slope = false)
            val refSingle = io.github.jasondorjeshort.dystopo.render.Hillshade.compute(gradient, zFactor = zf)
            val refMulti = io.github.jasondorjeshort.dystopo.render.Hillshade.multidirectional(gradient, zFactor = zf)
            val refSlope = Slope.compute(gradient)
            for (i in values.indices) {
                if (values[i].isNaN()) {
                    assertTrue(single.shade!![i].isNaN() && multi.shade!![i].isNaN() && single.tan2!![i].isNaN())
                    continue
                }
                assertEquals(refSingle[i], single.shade!![i], 1e-5f, "single $i")
                assertEquals(refMulti[i], multi.shade!![i], 1e-5f, "multi $i")
                val deg = kotlin.math.atan(kotlin.math.sqrt(single.tan2!![i].toDouble())) * 180 / kotlin.math.PI
                assertEquals(refSlope[i].toDouble(), deg, 1e-3, "slope $i")
            }
            assertEquals(null, multi.tan2)
        }
    }

    @Test
    fun zFactorGrowsAtCoarseZooms() {
        assertEquals(1.0, ShadeTiles.zFactor(13))
        assertEquals(1.0, ShadeTiles.zFactor(16))
        assertTrue(ShadeTiles.zFactor(10) > 2.0)
        assertEquals(4.0, ShadeTiles.zFactor(2))
    }

    @Test
    fun statsReportPerZoomTimings() {
        val dem = pyramid(8, 13)
        val (tx, ty) = peakTile(13)
        val stats = ShadeTileStats()
        val plan = ShadeTiles.plan(13, tx, ty, 13)
        val tile = ShadeTiles.render(ShadeTiles.sample(dem, plan), plan, multiSlope)
        stats.add(plan, tile, 12.0, 3.0, 4.0, 1234, multiSlope)
        val json = stats.json()
        assertEquals(1L, json["count"])
        @Suppress("UNCHECKED_CAST")
        val byZoom = json["byZoom"] as Map<String, Map<String, Any?>>
        assertEquals(12.0, byZoom["13"]?.get("maxMs"))
        assertContentEquals(listOf("13/$tx/$ty"), (json["recent"] as List<*>).map { (it as Map<*, *>)["tile"] })
    }
}
