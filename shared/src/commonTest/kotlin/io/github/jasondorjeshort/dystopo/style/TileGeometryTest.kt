package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileGeometryTest {
    private val unit = PixelRect(0.0, 0.0, 10.0, 10.0)

    private fun assertArray(expected: DoubleArray, actual: DoubleArray, eps: Double = 1e-9) {
        assertEquals(expected.size, actual.size, "size: ${actual.toList()}")
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) <= eps, "index $i: ${expected.toList()} vs ${actual.toList()}")
    }

    @Test
    fun placementAtSameZoom() {
        // Tile 13/1325/2886 drawn into a window whose top-left global pixel is (339100, 738800).
        val p = TilePlacement(TileId(13, 1325, 2886), 4096, 13, 339_100, 738_800)
        assertEquals(1.0 / 16, p.scale)
        assertEquals(1325 * 256.0 - 339_100, p.offsetX)
        assertEquals(2886 * 256.0 - 738_800, p.offsetY)
        assertEquals(p.offsetX + 128.0, p.x(TilePoint(2048, 0)))
        assertEquals(PixelRect(p.offsetX, p.offsetY, p.offsetX + 256, p.offsetY + 256), p.rect)
    }

    @Test
    fun overzoomScalesAndPicksParentTiles() {
        // A z15 image over z14 tiles: each tile unit covers 2/16 px and a tile spans 512 px.
        val p = TilePlacement(TileId(14, 10, 20), 4096, 15, 10 * 512, 20 * 512)
        assertEquals(2.0 / 16, p.scale)
        assertEquals(512.0, p.tilePixels)
        assertEquals(0.0, p.x(TilePoint(0, 0)))
        assertEquals(512.0, p.x(TilePoint(4096, 0)))
        // A 600x300 z15 window starting mid-tile needs two z14 tiles across, one down.
        val tiles = TilePlacement.tilesFor(14, 15, 10 * 512 + 256, 20 * 512, 600, 300)
        assertEquals(listOf(TileId(14, 10, 20), TileId(14, 11, 20)), tiles)
        // Same window at the archive's own zoom.
        assertEquals(6, TilePlacement.tilesFor(15, 15, 10 * 512 + 256, 20 * 512, 600, 300).size)
    }

    @Test
    fun polylineClipKeepsInsideRunsAndCutsOnTheEdge() {
        // In, out across the right edge, back in, then out the bottom.
        val line = doubleArrayOf(2.0, 2.0, 14.0, 2.0, 14.0, 6.0, 6.0, 6.0, 6.0, 20.0)
        val parts = Clip.polyline(line, unit)
        assertEquals(2, parts.size)
        assertArray(doubleArrayOf(2.0, 2.0, 10.0, 2.0), parts[0])
        assertArray(doubleArrayOf(10.0, 6.0, 6.0, 6.0, 6.0, 10.0), parts[1])
        // Entirely outside, and a segment crossing two edges.
        assertTrue(Clip.polyline(doubleArrayOf(-5.0, -5.0, -1.0, 20.0), unit).isEmpty())
        assertArray(doubleArrayOf(0.0, 5.0, 10.0, 5.0), Clip.polyline(doubleArrayOf(-5.0, 5.0, 15.0, 5.0), unit).single())
    }

    @Test
    fun ringClipStaysClosedAndKeepsOrientation() {
        // Clockwise (y down) square half outside to the right.
        val ring = doubleArrayOf(5.0, 2.0, 15.0, 2.0, 15.0, 8.0, 5.0, 8.0)
        val clipped = assertNotNull(Clip.ring(ring, unit))
        val xs = (0 until clipped.size / 2).map { clipped[2 * it] }
        assertTrue(xs.all { it in 5.0..10.0 }, "$xs")
        assertEquals(5.0 * 6.0, area(clipped), 1e-9)
        assertTrue(signedArea(clipped) > 0, "orientation kept")
        assertNull(Clip.ring(doubleArrayOf(20.0, 20.0, 30.0, 20.0, 30.0, 30.0), unit))
    }

    @Test
    fun mergeJoinsPiecesInEitherDirection() {
        val a = doubleArrayOf(0.0, 0.0, 5.0, 0.0)
        val b = doubleArrayOf(5.0, 0.0, 5.0, 5.0)
        val c = doubleArrayOf(9.0, 5.0, 5.0, 5.0) // reversed relative to the chain
        val d = doubleArrayOf(-4.0, 3.0, 0.0, 0.0) // joins at the start
        val far = doubleArrayOf(50.0, 50.0, 60.0, 60.0)
        val merged = LineMerge.merge(listOf(b, far, a, c, d))
        assertEquals(2, merged.size)
        val chain = merged.maxBy { it.size }
        val pts = (0 until chain.size / 2).map { chain[2 * it] to chain[2 * it + 1] }
        val expected = listOf(-4.0 to 3.0, 0.0 to 0.0, 5.0 to 0.0, 5.0 to 5.0, 9.0 to 5.0)
        assertTrue(pts == expected || pts == expected.reversed(), "$pts")
    }

    @Test
    fun mergeToleratesSmallGaps() {
        val merged = LineMerge.merge(listOf(doubleArrayOf(0.0, 0.0, 10.0, 0.0), doubleArrayOf(10.3, 0.2, 20.0, 0.0)), 0.5)
        assertEquals(1, merged.size)
        assertEquals(1, LineMerge.merge(listOf(doubleArrayOf(0.0, 0.0, 10.0, 0.0), doubleArrayOf(10.0, 0.0, 20.0, 0.0))).size)
        assertEquals(2, LineMerge.merge(listOf(doubleArrayOf(0.0, 0.0, 10.0, 0.0), doubleArrayOf(11.0, 0.0, 20.0, 0.0)), 0.5).size)
    }

    private fun signedArea(r: DoubleArray): Double {
        var s = 0.0
        val n = r.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
        }
        return s / 2
    }

    private fun area(r: DoubleArray) = abs(signedArea(r))
}
