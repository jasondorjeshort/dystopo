package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ElevationImageDecoder
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T106 (a T98 follow-up): `contourGeneration.noDemOverPack` counts a contour tile sampled with no elevation over a DEM
 * pack only when the pack has an elevation in that tile's window. A pack's bounds are a box, and a coast inside the box
 * has no data in the pack either: an empty contour tile there is right. The pack here is the Rainier box with a
 * synthetic sea west of 121.8 W.
 */
class NoDemOverPackTest {
    private val box = PackBounds(-122.05, 46.6, -121.4, 47.1)
    private val coastLon = -121.8
    private val base = ContourTileOptions()

    private fun encode(z: Int, x: Int, y: Int) = java.nio.ByteBuffer.allocate(12).putInt(z).putInt(x).putInt(y).array()

    /** Land at 1500 m east of the coast, no data (NaN) west of it, as a pack built over a coast stores it. */
    private val decoder = object : ElevationImageDecoder {
        override fun decode(bytes: ByteArray): ArgbImage = error("elevations only")
        override fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray {
            val b = java.nio.ByteBuffer.wrap(bytes)
            val (z, x, y) = Triple(b.int, b.int, b.int)
            return FloatArray(width * height) { i ->
                val lon = WebMercator.xToLon((x * width + i % width + 0.5) / width, z)
                if (lon < coastLon) Float.NaN else 1500f + (lon - coastLon).toFloat() * 1000f
            }
        }
    }

    /** The pack alone (z8 to z14 over its box), as TileService's pack pyramid reads it. */
    private val pack = DemPyramid(8, 14, decoder) { z, x, y -> if (z in 8..14 && OnlineTiles.tileOverlaps(box, z, x, y)) encode(z, x, y) else null }

    /** The pack's own window of contour tile z/x/y from its first zoom up, as `TileService.packWindow` reads it. */
    private fun packWindow(z: Int, x: Int, y: Int): FloatArray =
        ContourTiles.sample(pack, ContourTiles.plan(z, x, y, OnlineTiles.readFrom(base, 8, z, x, y), pack.tileSize)).values

    private fun tile(z: Int, lon: Double, lat: Double) = Triple(z, floor(WebMercator.lonToX(lon, z)).toInt(), floor(WebMercator.latToY(lat, z)).toInt())

    private fun counted(z: Int, x: Int, y: Int): Boolean {
        val emptyGrid = FloatArray(64) { Float.NaN } // the online grid with the server unreachable: nothing sampled
        return noDemWherePackHasData(emptyGrid, sequenceOf(packWindow(z, x, y)))
    }

    @Test
    fun anEmptyTileOffTheCoastIsNotCounted() {
        val (z, x, y) = tile(12, -121.98, 46.85) // inside the box, all sea
        assertTrue(OnlineTiles.tileOverlaps(box, z, x, y))
        assertFalse(counted(z, x, y), "$z/$x/$y: the pack has no elevation there either")
    }

    @Test
    fun anEmptyTileOverThePacksLandIsCounted() {
        val (z, x, y) = tile(12, -121.68, 46.85)
        assertTrue(counted(z, x, y), "$z/$x/$y: the pack has elevations there; an empty tile is T98's defect")
        // T98's own case: the tour's z8 tile over the pack, read from the pack's first zoom.
        assertTrue(counted(8, 41, 90))
    }

    @Test
    fun aTileWithElevationIsNeverCounted() {
        val grid = FloatArray(64) { if (it == 3) 1200f else Float.NaN }
        assertFalse(noDemWherePackHasData(grid, sequence { error("not read when the grid has a value") }))
        assertFalse(noDemWherePackHasData(FloatArray(4) { Float.NaN }, emptySequence()), "no pack overlaps")
    }
}
