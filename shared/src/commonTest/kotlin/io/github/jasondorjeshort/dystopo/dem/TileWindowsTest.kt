package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TileWindowsTest {
    private val zoom = 13
    private val cellSize = 13.0 // ~ z13 at 47 N
    private val featherMeters = 300.0
    private val featherCells = featherMeters / cellSize

    // Four tiles around one shared corner, far from the world origin so coordinates are realistic.
    private val cornerX = 1325 * 256
    private val cornerY = 2915 * 256
    private val quad = listOf(TileId(zoom, 1324, 2914), TileId(zoom, 1325, 2914), TileId(zoom, 1324, 2915), TileId(zoom, 1325, 2915))

    /**
     * A synthetic two-source world in global pixel coordinates. The top layer has a no-data disk
     * around the tiles' shared corner and a no-data strip 12 cells east of the north-south border
     * (inside the feather band of the west tiles' edge cells, but outside a too-small halo), so
     * feather bands cross every tile border. The fallback sits 25 m above the top layer.
     */
    private fun top(gx: Int, gy: Int): Float {
        val dx = gx - cornerX + 0.5
        val dy = gy - cornerY + 0.5
        if (sqrt(dx * dx + dy * dy) < 40) return Float.NaN
        if (gx in cornerX + 12..cornerX + 16) return Float.NaN
        return (1000.0 + 0.3 * (gx - cornerX) + 0.1 * (gy - cornerY)).toFloat()
    }

    private fun fallback(gx: Int, gy: Int): Float = top(gx, gy).let { if (it.isNaN()) 1025f else it + 25f }

    private fun layer(window: MercatorWindow, f: (Int, Int) -> Float) = ElevationGrid(
        window.width, window.height, cellSize,
        FloatArray(window.width * window.height) { f(window.x0 + it % window.width, window.y0 + it / window.width) },
    )

    private fun render(window: MercatorWindow) = Composite.over(layer(window, ::top), layer(window, ::fallback), featherCells)

    private fun renderTile(tw: TileWindow) = tw.interior(render(tw.window))

    /** Cells of [grid] (rendered through [window]) that differ from [reference] rendered through [refWindow]. */
    private fun mismatches(grid: ElevationGrid, window: MercatorWindow, reference: ElevationGrid, refWindow: MercatorWindow): Int {
        var n = 0
        for (j in 0 until grid.height) for (i in 0 until grid.width) {
            val gx = window.x0 + i
            val gy = window.y0 + j
            val ri = gx - refWindow.x0
            val rj = gy - refWindow.y0
            if (ri !in 0 until refWindow.width || rj !in 0 until refWindow.height) continue
            if (grid[i, j].toBits() != reference[ri, rj].toBits()) n++
        }
        return n
    }

    @Test
    fun haloCoversTheFeatherBandAndAligns() {
        assertEquals(32, TileWindows.haloCells(300.0, 13.0)) // 23.08 -> 24 -> 32
        assertEquals(16, TileWindows.haloCells(300.0, 417.0)) // 0.72 -> 1 -> 16
        assertEquals(96, TileWindows.haloCells(300.0, 3.3)) // 90.9 -> 91 -> 96
        assertEquals(16, TileWindows.haloCells(160.0, 10.0)) // exactly 16 stays 16
        assertEquals(16, TileWindows.haloCells(0.0, 10.0)) // no feathering still gets the minimum
        assertEquals(24, TileWindows.haloCells(300.0, 13.0, align = 1))
    }

    @Test
    fun windowSurroundsTheTileSymmetrically() {
        val tile = TileId(zoom, 1324, 2914)
        val tw = TileWindow(tile, halo = 32)
        val plain = MercatorWindow(zoom, tile.x * 256, tile.y * 256, 256, 256)
        assertEquals(MercatorWindow(zoom, 1324 * 256 - 32, 2914 * 256 - 32, 320, 320), tw.window)
        assertEquals(plain.lonAt(0), tw.window.lonAt(32))
        assertEquals(plain.latAt(255), tw.window.latAt(32 + 255))
        assertEquals(plain.cellSizeMeters, tw.window.cellSizeMeters)
        assertTrue(tile in tw.window.tiles())

        val grid = layer(tw.window) { gx, gy -> (gx * 7 + gy).toFloat() }
        val inner = tw.interior(grid)
        assertEquals(256, inner.width)
        assertEquals(256, inner.height)
        assertEquals(grid[32, 32], inner[0, 0])
        assertEquals(grid[32 + 255, 32 + 255], inner[255, 255])
        assertEquals(((tile.x * 256) * 7 + tile.y * 256).toFloat(), inner[0, 0])
    }

    @Test
    fun tilesMatchASeamlessRenderExactly() {
        val halo = TileWindows.haloCells(featherMeters, cellSize)
        // One big haloed window around all four tiles is the "ground truth" render.
        val big = MercatorWindow(zoom, cornerX - 256 - halo, cornerY - 256 - halo, 512 + 2 * halo, 512 + 2 * halo)
        val reference = render(big)
        for (tile in quad) {
            val tw = TileWindow(tile, halo)
            val interiorWindow = MercatorWindow(zoom, tile.x * 256, tile.y * 256, 256, 256)
            assertEquals(0, mismatches(renderTile(tw), interiorWindow, reference, big), "tile $tile differs from the seamless render")
        }
        // The feather band really is exercised: some cells are blends, not pure top or fallback.
        val blended = reference.values.count { v -> !v.isNaN() && abs(v - 1025f) > 1e-3 && (v - 1000f) % 1f != 0f }
        assertTrue(blended > 1000, "expected many blended cells, got $blended")
    }

    @Test
    fun adjacentTilesAgreeOnSharedEdges() {
        val halo = TileWindows.haloCells(featherMeters, cellSize)
        val tiles = quad.map { TileWindow(it, halo) }
        val rendered = tiles.associateWith { renderTile(it) }
        var compared = 0
        for (a in tiles) for (b in tiles) {
            if (a == b) continue
            // a's full haloed render covers part of b's interior; those cells must be identical.
            val aFull = render(a.window)
            val bInterior = MercatorWindow(zoom, b.tile.x * 256, b.tile.y * 256, 256, 256)
            assertEquals(0, mismatches(aFull, a.window, rendered.getValue(b), bInterior), "halo of ${a.tile} disagrees with ${b.tile}")
            compared++
        }
        assertEquals(12, compared)
        // Edge columns themselves: the terrain continues across the border (0.3 m/cell slope plus at
        // most ~1.6 m/cell inside the feather ramp), with no 25 m source step.
        val west = rendered.getValue(tiles[0])
        val east = rendered.getValue(tiles[1])
        for (j in 0 until 256) {
            val step = abs(east[0, j] - west[255, j])
            assertTrue(step < 3f, "unexpected step of $step m across the border at row $j")
        }
    }

    @Test
    fun tooSmallAHaloBreaksAgreement() {
        // With a halo narrower than the feather band, the distance transform is clipped by the window
        // and cells near the border get different weights in different tiles. This proves the tests
        // above can fail.
        val halo = 8
        val tiles = quad.map { TileWindow(it, halo) }
        val rendered = tiles.associateWith { renderTile(it) }
        var mismatched = 0
        for (a in tiles) for (b in tiles) {
            if (a == b) continue
            val bInterior = MercatorWindow(zoom, b.tile.x * 256, b.tile.y * 256, 256, 256)
            mismatched += mismatches(render(a.window), a.window, rendered.getValue(b), bInterior)
        }
        assertTrue(mismatched > 0, "expected mismatches with halo $halo < feather $featherCells")
    }

    @Test
    fun terrariumRoundTripIsWithinPrecision() {
        val tw = TileWindow(TileId(zoom, 1324, 2914), 32)
        val grid = renderTile(tw)
        // Add a hole to exercise the no-data substitute.
        grid.values[7] = Float.NaN
        val pixels = TileWindows.encodeTerrarium(grid)
        assertEquals(256 * 256, pixels.size)
        val decoded = Terrarium.decode(pixels, 256, 256, grid.cellSizeMeters)
        var maxError = 0f
        for (i in grid.values.indices) {
            val expected = if (grid.values[i].isNaN()) 0f else grid.values[i]
            maxError = maxOf(maxError, abs(decoded.values[i] - expected))
        }
        assertTrue(maxError <= 1f / 256, "max decode error $maxError m")
        assertEquals(0f, decoded.values[7])
    }

    @Test
    fun forBboxPlansEveryTileWithOneHalo() {
        val bbox = BoundingBox(-121.86, 46.78, -121.66, 46.93)
        val plan = TileWindows.forBbox(bbox, 12, featherMeters)
        assertEquals(WebMercator.tilesCovering(bbox, 12).toSet(), plan.map { it.tile }.toSet())
        assertEquals(1, plan.map { it.halo }.toSet().size)
        val ref = TileWindows.referenceCellSizeMeters(bbox, 12)
        assertTrue(ref in 25.0..27.0, "z12 cell size near 47 N should be ~26 m, got $ref")
        assertEquals(TileWindows.haloCells(featherMeters, ref), plan.first().halo)
        plan.forEach { if (it.tile.z != 12) fail("wrong zoom in $it") }
    }
}
