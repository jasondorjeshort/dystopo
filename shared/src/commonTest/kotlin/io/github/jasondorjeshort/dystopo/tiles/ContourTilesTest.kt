package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
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
        assertEquals(14, ContourTiles.demZoom(13, options))
        val (tx, ty) = peakTile(14)
        // Contour tile 13/(tx/2)/(ty/2) covers DEM z14 tiles tx..tx+1: same levels as the z14 spec, full resolution.
        val tile = ContourTiles.generate(dem, 13, tx / 2, ty / 2, options)
        assertEquals(ContourStyle.forZoom(14, ContourUnits.FEET), tile.spec)
        val layer = VectorTile.decode(tile.bytes).layer("contours")!!
        assertTrue(layer.features.isNotEmpty())
        // 8 units per DEM pixel; the 4 px halo reaches 32 units past the edge.
        for (f in layer.features) for (p in (f.geometry as Geometry.Lines).lines.single()) {
            assertTrue(p.x in -40..4136 && p.y in -40..4136, "point $p")
        }
    }

    @Test
    fun demZoomPerTileZoom() {
        val options = ContourTileOptions() // 512 px tiles
        // tile zoom -> (spec zoom, sampled DEM zoom, grid cells across the tile)
        val expected = mapOf(
            6 to Triple(7, 5, 128), 7 to Triple(8, 6, 128), 8 to Triple(9, 7, 128), 9 to Triple(10, 8, 128),
            10 to Triple(11, 10, 256), 11 to Triple(12, 11, 256), 12 to Triple(13, 12, 256),
            13 to Triple(14, 14, 512), 14 to Triple(15, 15, 512), 15 to Triple(16, 16, 512), 16 to Triple(17, 17, 512),
        )
        for ((z, e) in expected) {
            val plan = ContourTiles.plan(z, 3, 5, options)
            assertEquals(e, Triple(plan.specZoom, plan.sampleZoom, plan.gridTileSize), "tile zoom $z")
            assertEquals(e.third + 2 * options.halo, plan.windowSize)
            // The window is the tile in sampled-zoom pixels, plus the halo.
            assertEquals(3 * e.third - options.halo, plan.windowX)
            assertEquals(5 * e.third - options.halo, plan.windowY)
            // The sampled DEM is never finer than one cell per screen pixel, and at most 4 screen pixels per cell.
            assertTrue(plan.cellScale in 1..4)
        }
        // 256-px tiles (the devtool convention) trace their own zoom at full resolution from z13 on.
        val p256 = ContourTiles.plan(13, 0, 0, ContourTileOptions(tileSize = 256))
        assertEquals(13, p256.specZoom)
        assertEquals(13, p256.sampleZoom)
        // The thresholds are options: everything at full resolution when both are 0.
        val full = ContourTileOptions(halfResolutionBelow = 0, quarterResolutionBelow = 0)
        assertEquals(9, ContourTiles.plan(8, 0, 0, full).sampleZoom)
    }

    @Test
    fun sampleAtDemZoomReadsTheHillshadeZoomAndLeavesPackPlansAlone() {
        val pack = ContourTileOptions()
        val direct = ContourTileOptions(sampleAtDemZoom = 12)
        // tile zoom -> DEM zoom read with the hillshade rendered to z12: the spec zoom, at most 12, never coarser than the plan.
        val expected = mapOf(7 to 8, 8 to 9, 9 to 10, 10 to 11, 11 to 12, 12 to 12, 13 to 14, 14 to 15)
        for ((z, read) in expected) {
            val a = ContourTiles.plan(z, 3, 5, pack)
            val b = ContourTiles.plan(z, 3, 5, direct)
            assertEquals(0, a.oversample, "tile zoom $z")
            assertEquals(a.sampleZoom, a.readZoom)
            assertEquals(read, b.readZoom, "tile zoom $z")
            // The traced grid, its zoom and window are the pack plan's; only where it is read from differs.
            assertEquals(a, b.copy(oversample = 0), "tile zoom $z")
            // What is read: the same ground, 2^oversample times the cells, at the read zoom.
            val r = b.readWindow
            assertEquals(read, r.sampleZoom)
            assertEquals(b.windowSize shl b.oversample, r.windowSize)
            assertEquals(b.windowX shl b.oversample, r.windowX)
            assertEquals(a, a.readWindow)
        }
    }

    @Test
    fun oversampledTilesReadOnlyTheDeeperZoomAndMatchThePackTile() {
        val reads = HashMap<Int, Int>()
        val dem = DemPyramid(8, 12, decoder) { z, x, y -> reads[z] = (reads[z] ?: 0) + 1; encodeAddress(z, x, y) }
        val (tx, ty) = peakTile(12)
        for (z in listOf(9, 10, 11)) {
            val x = tx shr (12 - z)
            val y = ty shr (12 - z)
            val packPlan = ContourTiles.plan(z, x, y)
            val plan = ContourTiles.plan(z, x, y, ContourTileOptions(sampleAtDemZoom = 12))
            reads.clear()
            val grid = ContourTiles.sample(dem, plan)
            assertEquals(setOf(plan.readZoom), reads.keys, "tile zoom $z reads only DEM z${plan.readZoom}")
            // Each cell is the average of its block of the deeper window.
            val k = plan.oversample
            val fine = dem.window(plan.readZoom, plan.windowX shl k, plan.windowY shl k, plan.windowSize shl k, plan.windowSize shl k)
            for ((i, j) in listOf(0 to 0, 17 to 5, plan.windowSize - 1 to plan.windowSize - 1)) {
                var sum = 0.0
                for (dj in 0 until (1 shl k)) for (di in 0 until (1 shl k)) sum += fine[(i shl k) + di, (j shl k) + dj]
                assertTrue(abs(grid[i, j] - sum / (1 shl (2 * k))) < 1e-3, "z$z cell $i,$j")
            }
            val a = ContourTiles.build(ContourTiles.sample(dem, packPlan), packPlan)
            val b = ContourTiles.build(grid, plan)
            val levels = { t: ContourTile -> VectorTile.decode(t.bytes).layer("contours")!!.features.map { it.properties.getValue("ele").asDouble() }.toSet() }
            assertEquals(levels(a), levels(b), "tile zoom $z levels")
            assertTrue(abs(a.lines - b.lines) <= maxOf(1, a.lines / 10), "tile zoom $z: ${a.lines} vs ${b.lines} lines")
        }
        // NaN cells are left out of the average; a block of NaN stays NaN.
        val nan = Float.NaN
        val reduced = ContourTiles.reduce(floatArrayOf(1f, 3f, nan, nan, nan, nan, nan, nan, 2f, 2f, 0f, 0f, 2f, 2f, 0f, 0f), 4, 1)
        assertEquals(2f, reduced[0])
        assertTrue(reduced[1].isNaN())
        assertEquals(listOf(2f, 0f), reduced.toList().subList(2, 4))
    }

    @Test
    fun coarseGridKeepsTheLevelsAndShapeOfFullResolution() {
        val dem = pyramid(8, 14)
        val (tx, ty) = peakTile(13)
        val full = ContourTileOptions(halfResolutionBelow = 0, quarterResolutionBelow = 0)
        val coarse = ContourTileOptions()
        assertEquals(1, ContourTiles.plan(12, tx / 2, ty / 2, coarse).reduction)
        val a = VectorTile.decode(ContourTiles.generate(dem, 12, tx / 2, ty / 2, full).bytes).layer("contours")!!
        val b = VectorTile.decode(ContourTiles.generate(dem, 12, tx / 2, ty / 2, coarse).bytes).layer("contours")!!
        val levelsA = a.features.map { it.properties.getValue("ele").asDouble() }.toSet()
        val levelsB = b.features.map { it.properties.getValue("ele").asDouble() }.toSet()
        assertEquals(levelsA, levelsB)
        // Each coarse vertex lies on (within 2 screen px = 16 units of) the full-resolution line of its level.
        for (f in b.features) {
            val ele = f.properties.getValue("ele").asDouble()
            val ref = a.features.filter { it.properties.getValue("ele").asDouble() == ele }
                .map { (it.geometry as Geometry.Lines).lines.single() }
            // Inside the tile only: the coarse halo reaches further than the full-resolution one.
            for (p in (f.geometry as Geometry.Lines).lines.single().filter { it.x in 0..4096 && it.y in 0..4096 }) {
                val d = ref.minOf { line -> line.zipWithNext().minOf { (u, v) -> segmentDistance(p, u, v) } }
                assertTrue(d < 16.0, "coarse point $p of $ele is $d units from the full-resolution line")
            }
        }
    }

    private fun segmentDistance(p: TilePoint, a: TilePoint, b: TilePoint): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    @Test
    fun windowFastPathMatchesPerPixelSampling() {
        val (tx, ty) = peakTile(12)
        // z12 has a checkerboard of tiles around the peak; the rest falls back to z11.
        val dem = pyramid(10, 12) { z, x, y -> z < 12 || (x + y) % 2 == 0 }
        val x0 = tx * 256 - 70
        val y0 = ty * 256 - 30
        val w = 300
        val h = 290
        val grid = dem.window(12, x0, y0, w, h)
        for (j in 0 until h step 7) for (i in 0 until w step 3) {
            assertEquals(dem.elevation(12, x0 + i, y0 + j), grid[i, j], "pixel $i,$j")
        }
        // Past the pack (overzoom) and at the world's edge, too.
        for ((z, ox, oy) in listOf(Triple(14, tx * 1024 - 300, ty * 1024 + 17), Triple(13, -5, -7))) {
            val g = dem.window(z, ox, oy, 260, 250)
            for (j in 0 until 250 step 3) for (i in 0 until 260 step 5) {
                assertEquals(dem.elevation(z, ox + i, oy + j), g[i, j], "z$z pixel $i,$j")
            }
        }
    }

    @Test
    fun windowsBelowTheLowestZoomAreBoxAverages() {
        val (tx, ty) = peakTile(10)
        val dem = pyramid(10, 12)
        val coarse = dem.window(8, (tx / 4) * 256 + 20, (ty / 4) * 256 + 30, 40, 30)
        val fine = dem.window(10, ((tx / 4) * 256 + 20) * 4, ((ty / 4) * 256 + 30) * 4, 160, 120)
        for ((i, j) in listOf(0 to 0, 39 to 29, 17 to 11)) {
            var sum = 0.0
            for (dj in 0 until 4) for (di in 0 until 4) sum += fine[i * 4 + di, j * 4 + dj]
            assertTrue(abs(coarse[i, j] - sum / 16) < 1e-3, "cell $i,$j: ${coarse[i, j]} vs ${sum / 16}")
        }
        assertTrue(dem.covers(8, (tx / 4) * 256, (ty / 4) * 256, 10, 10))
        // A z7 contour tile over the peak samples DEM z6, two zooms below the pack, and still has lines.
        val tile = ContourTiles.generate(dem, 7, tx shr 3, ty shr 3)
        assertEquals(6, ContourTiles.plan(7, 0, 0).sampleZoom)
        assertTrue(tile.lines > 0)
    }

    @Test
    fun statsReportPerZoomAndConcurrency() {
        val dem = pyramid(8, 14)
        val stats = ContourTileStats()
        val (tx, ty) = peakTile(13)
        assertEquals(1, stats.begin())
        assertEquals(2, stats.begin())
        val plan = ContourTiles.plan(12, tx / 2, ty / 2)
        val tile = ContourTiles.build(ContourTiles.sample(dem, plan), plan)
        stats.end(plan, tile, 120.0, 20.0, 2)
        stats.end(plan, tile, 80.0, 10.0, 1)
        val json = stats.json()
        assertEquals(2L, json["count"])
        assertEquals(2, json["maxConcurrent"])
        assertEquals(0, json["inFlight"])
        @Suppress("UNCHECKED_CAST")
        val z12 = (json["byZoom"] as Map<String, Map<String, Any?>>).getValue("12")
        assertEquals(80.0, z12["minMs"])
        assertEquals(100.0, z12["avgMs"])
        assertEquals(120.0, z12["maxMs"])
        assertEquals(15.0, z12["avgSampleMs"])
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

    @Test
    fun aLowContourTileAveragesThePyramidsShallowestZoom() {
        // T87: a z8 contour tile samples DEM z7. A pyramid that starts at z8 averages z8 down (lines); one declared from
        // z6 whose source has nothing below z8 (the phone's direct sources, a DEM pack from z8) leaves the window NaN.
        val (tx, ty) = peakTile(8)
        val fromSourceMin = ContourTiles.generate(pyramid(8, 12), 8, tx, ty)
        assertTrue(fromSourceMin.lines > 0, "z8 tile from a z8 pyramid: ${fromSourceMin.lines} lines")
        assertEquals(7, ContourTiles.plan(8, tx, ty).sampleZoom)
        val declaredLower = ContourTiles.generate(pyramid(6, 12) { z, _, _ -> z >= 8 }, 8, tx, ty)
        assertEquals(0, declaredLower.lines)
    }
}
