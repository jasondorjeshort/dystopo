package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.render.ContourStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContourTilesTest {
    /** A cone in world coordinates: the same terrain at every zoom, so levels can be compared across zooms. */
    private val peakU = (2 * 256 + 200.0) / (256.0 * 4) // world fractions (0..1)
    private val peakV = (1 * 256 + 100.0) / (256.0 * 4)

    private fun elevationAtWorld(u: Double, v: Double): Double {
        // Cone 3000 m high, falling 2 m per 1e-6 world units: about 1 m per pixel at z13, so a z13 tile drops ~250 m.
        val d = hypot(u - peakU, v - peakV)
        return 3000.0 - d * 2.0e6
    }

    /** Fake "PNG": the tile address; the decoder renders the cone for it. */
    private fun encodeAddress(z: Int, x: Int, y: Int) = "$z/$x/$y".encodeToByteArray()

    private val decoder = ImageDecoder { bytes ->
        val (z, x, y) = bytes.decodeToString().split("/").map { it.toInt() }
        val world = 256.0 * (1L shl z)
        ArgbImage(256, 256, IntArray(256 * 256) { i ->
            val px = x * 256 + i % 256 + 0.5
            val py = y * 256 + i / 256 + 0.5
            Terrarium.encode(elevationAtWorld(px / world, py / world).toFloat().coerceAtLeast(0f))
        })
    }

    private val tiles256 = ContourTileOptions(tileSize = 256)

    /** A pyramid holding z[min]..[max] but only the tiles [has] admits. */
    private fun pyramid(min: Int, max: Int, has: (Int, Int, Int) -> Boolean = { _, _, _ -> true }) =
        DemPyramid(min, max, decoder) { z, x, y -> if (has(z, x, y)) encodeAddress(z, x, y) else null }

    // The peak sits at these global pixel coordinates at zoom z.
    private fun peakTile(z: Int): Pair<Int, Int> {
        val world = 256.0 * (1L shl z)
        return (peakU * world / 256).toInt() to (peakV * world / 256).toInt()
    }

    @Test
    fun exactPixelsAtPackZoomsAndBilinearOverzoom() {
        val dem = pyramid(8, 12)
        val (tx, ty) = peakTile(12)
        val t = assertNotNull(dem.tile(12, tx, ty))
        assertEquals(t[37 * 256 + 41], dem.elevation(12, tx * 256 + 41, ty * 256 + 37))

        // z13 is beyond the pack: sampled from z12. A z13 pixel centre at (2p + 0.5) maps to z12 (p + 0.25 - 0.5 + ...);
        // the cone is linear away from its tip, so bilinear interpolation reproduces the analytic value closely.
        val gx = tx * 512 + 100
        val gy = ty * 512 + 300
        val world = 256.0 * (1L shl 13)
        val expected = elevationAtWorld((gx + 0.5) / world, (gy + 0.5) / world)
        assertTrue(abs(dem.elevation(13, gx, gy) - expected) < 1.0, "overzoom ${dem.elevation(13, gx, gy)} vs $expected")
        assertNull(dem.tile(13, gx / 256, gy / 256))
    }

    @Test
    fun missingTilesFallBackToAncestorsAndNothingIsNaN() {
        val (tx, ty) = peakTile(12)
        // z12 has only the peak tile; its neighbours come from z11.
        val dem = pyramid(10, 12) { z, x, y -> z < 12 || (x == tx && y == ty) }
        val inside = dem.elevation(12, tx * 256 + 10, ty * 256 + 10)
        val outside = dem.elevation(12, (tx + 1) * 256 + 10, ty * 256 + 10)
        assertTrue(!inside.isNaN() && !outside.isNaN())
        val world = 256.0 * (1L shl 12)
        val expected = elevationAtWorld(((tx + 1) * 256 + 10.5) / world, (ty * 256 + 10.5) / world)
        assertTrue(abs(outside - expected) < 2.0, "fallback $outside vs $expected")

        // Nothing at all outside the pack's lowest zoom footprint: NaN, and contour tiles there are empty.
        val sparse = pyramid(10, 12) { z, x, y -> z == 10 && x == tx shr 2 && y == ty shr 2 }
        assertTrue(sparse.elevation(12, (tx shr 2) * 1024 + 2000, (ty shr 2) * 1024 + 10).isNaN())
    }

    @Test
    fun contourTileHasStyleLevelsAndIndexFlags() {
        val dem = pyramid(8, 14)
        val z = 13
        val (tx, ty) = peakTile(z)
        val tile = ContourTiles.generate(dem, z, tx, ty, tiles256)
        val spec = ContourStyle.forZoom(z, ContourUnits.FEET)
        assertEquals(spec, tile.spec)
        assertTrue(tile.lines > 0, "the peak tile has contours")

        val decoded = VectorTile.decode(tile.bytes)
        val layer = assertNotNull(decoded.layer("contours"))
        assertEquals(4096, layer.extent)
        assertEquals(tile.lines, layer.features.size)
        var index = 0
        for (f in layer.features) {
            val ele = f.properties.getValue("ele").asDouble()!!
            assertEquals(0.0, ele % spec.interval, "ele $ele is a multiple of ${spec.interval} ft")
            val level = f.properties.getValue("level").asDouble()!!.toInt()
            assertEquals(if (ele % (spec.interval * spec.indexEvery) == 0.0) 1 else 0, level, "index flag for $ele")
            index += level
            val g = f.geometry as Geometry.Lines
            for (p in g.lines.single()) {
                // Inside the tile plus the halo (4 px = 64 units), never further.
                assertTrue(p.x in -70..4166 && p.y in -70..4166, "point $p")
            }
        }
        assertEquals(tile.indexLines, index)
    }

    @Test
    fun linesContinueAcrossTheSeam() {
        val dem = pyramid(8, 14)
        val z = 13
        val (tx, ty) = peakTile(z)
        // The tile right of the peak and its neighbour further right share the edge x = 4096 | x = 0.
        val left = VectorTile.decode(ContourTiles.generate(dem, z, tx + 1, ty, tiles256).bytes).layer("contours")!!
        val right = VectorTile.decode(ContourTiles.generate(dem, z, tx + 2, ty, tiles256).bytes).layer("contours")!!
        // Every level that crosses the shared edge in one tile reaches past it (into the halo) in the other.
        val crossingLeft = left.features.filter { f -> (f.geometry as Geometry.Lines).lines.single().any { it.x > 4096 } }
            .map { it.properties.getValue("ele").asDouble() }.toSet()
        val crossingRight = right.features.filter { f -> (f.geometry as Geometry.Lines).lines.single().any { it.x < 0 } }
            .map { it.properties.getValue("ele").asDouble() }.toSet()
        assertTrue(crossingLeft.isNotEmpty(), "some contours cross the seam")
        assertEquals(crossingLeft, crossingRight)
    }

    @Test
    fun maplibreSizedTilesTraceTheNextDemZoom() {
        val dem = pyramid(8, 14)
        val options = ContourTileOptions() // 512 px, MapLibre's vector tile size
        assertEquals(13, ContourTiles.demZoom(12, options))
        val (tx, ty) = peakTile(13)
        // Contour tile 12/(tx/2)/(ty/2) covers DEM z13 tiles tx..tx+1: same levels as the z13 spec.
        val tile = ContourTiles.generate(dem, 12, tx / 2, ty / 2, options)
        assertEquals(ContourStyle.forZoom(13, ContourUnits.FEET), tile.spec)
        val layer = VectorTile.decode(tile.bytes).layer("contours")!!
        assertTrue(layer.features.isNotEmpty())
        // 8 units per DEM pixel; the 4 px halo reaches 32 units past the edge.
        for (f in layer.features) for (p in (f.geometry as Geometry.Lines).lines.single()) {
            assertTrue(p.x in -40..4136 && p.y in -40..4136, "point $p")
        }
    }

    @Test
    fun cacheEvictsAndCountsHits() {
        val dem = DemPyramid(8, 12, decoder, cacheTiles = 4) { z, x, y -> encodeAddress(z, x, y) }
        repeat(3) { dem.tile(12, 10, 10) }
        assertEquals(1L, dem.tilesDecoded)
        assertEquals(2L, dem.cacheHits)
        for (i in 0 until 6) dem.tile(12, 20 + i, 10)
        assertEquals(4, dem.cachedTiles)
    }
}
