package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T89: the T76 and T77 follow-ups on synthetic packs (lines in z14 tiles, read by the real builder):
 * bridges and tunnels cut at a tile border are never joined to the road beneath, within one pack or
 * between two; two packs loaded in either order through one shared tile cache give the same route; a
 * route between unconnected parts of a pack fails at once from the component summary; and a corrupt
 * graph tile is read once and then skipped.
 */
class RoutingRobustnessTest {
    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun failed(r: RouteResult): RouteResult.Failed {
        assertIs<RouteResult.Failed>(r, "expected a failure, got $r")
        return r
    }

    // ---- Synthetic packs ----

    private val extent = 4096
    private val x0 = 2648 // a z14 column at Mount Rainier's longitude
    private val lat = 46.79
    private val yRow = tileXy(-121.8, lat).second.toInt()

    private fun lonOf(x: Double) = x / (1 shl 14) * 360 - 180
    private fun latOf(y: Double): Double = kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y / (1 shl 14)))) * 180 / PI

    private fun tileXy(lon: Double, lat: Double): Pair<Double, Double> {
        val n = 1 shl 14
        val r = lat * PI / 180
        return (lon + 180) / 360 * n to (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
    }

    private fun s(vararg kv: Pair<String, String>): Map<String, Value> = kv.associate { (k, v) -> k to Value.StringValue(v) }

    private class Line(val props: Map<String, Value>, val pts: List<Pair<Double, Double>>)

    /** An outdoors (profile 2) pack holding z14 columns [cols] of rows [rows]: each tile carries every line; the builder clips. */
    private fun pack(lines: List<Line>, cols: IntRange, rows: IntRange = yRow..yRow): PmtilesReader {
        val w = PmtilesWriter(TileType.MVT)
        for (y in rows) for (x in cols) {
            val fs = lines.map { l ->
                Feature(null, l.props, Geometry.Lines(listOf(l.pts.map { (la, lo) ->
                    val (fx, fy) = tileXy(lo, la)
                    TilePoint(round((fx - x) * extent).toInt(), round((fy - y) * extent).toInt())
                })))
            }
            w.addTile(14, x, y, VectorTile(listOf(Layer("transportation", fs, extent))).encode())
        }
        val chunks = ArrayList<ByteArray>()
        val meta = """{"dystopo:profile":"outdoors","dystopo:profile_version":"2"}"""
        w.finish(
            ByteSink { chunks += it }, meta, minLon = lonOf(cols.first.toDouble()), minLat = latOf(rows.last + 1.0),
            maxLon = lonOf(cols.last + 1.0), maxLat = latOf(rows.first.toDouble()), minZoom = 14, maxZoom = 14,
        )
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return PmtilesReader.open(ByteRangeSource { offset, length ->
            bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
        })
    }

    private fun graphFile(lines: List<Line>, cols: IntRange, name: String, rows: IntRange = yRow..yRow): GraphTileArchive =
        GraphTileFile.open(GraphTileFile.encode(RoutingGraphBuilder().build(pack(lines, cols, rows), "$name-outdoors.pmtiles")))

    // ---- Bridges at tile borders ----

    private val road = s("class" to "minor", "name" to "Valley Road")
    private val bridge = s("class" to "minor", "brunnel" to "bridge", "name" to "High Bridge")

    /**
     * A valley road along [lat] and a bridge crossing over it diagonally, the two crossing exactly on the
     * border between z14 columns x0 and x0 + 1 (where both are cut). Nothing else joins them.
     */
    private val overpass: List<Line> = run {
        val bx = lonOf(x0 + 1.0)
        listOf(
            Line(road, listOf(lat to bx - 0.01, lat to bx + 0.01)),
            Line(bridge, listOf(lat - 0.004 to bx - 0.006, lat + 0.004 to bx + 0.006)),
        )
    }

    @Test
    fun aBridgeCutAtATileBorderIsNotJoinedToTheRoadBelow() {
        val bx = lonOf(x0 + 1.0)
        val union = listOf(graphFile(overpass, x0..x0 + 1, "union"))
        val split = listOf(graphFile(overpass, x0..x0, "west"), graphFile(overpass, x0 + 1..x0 + 1, "east"))
        val graph = RoutingGraphBuilder().build(pack(overpass, x0..x0 + 1), "union-outdoors.pmtiles")
        // The bridge's cut point is a node of its own level beside the road's.
        assertTrue((0 until graph.nodeCount).any { graph.nodeLevel[it] == RoutingGraph.LEVEL_BRIDGE }, "a bridge-level node")
        for ((label, files) in listOf("one pack" to union, "two packs meeting at the border" to split)) {
            val r = TileRouter(TiledGraph(files))
            for (mode in listOf(TravelMode.HIKE, TravelMode.DRIVE)) {
                // Along the road and along the bridge, each across the border: fine.
                val along = found(r.route(lat, bx - 0.009, lat, bx + 0.009, mode))
                assertEquals(setOf("Valley Road"), along.via.map { it.first }.toSet(), "$label $mode road")
                val over = found(r.route(lat - 0.0035, bx - 0.0052, lat + 0.0035, bx + 0.0052, mode))
                assertEquals(setOf("High Bridge"), over.via.map { it.first }.toSet(), "$label $mode bridge")
                // From the road onto the bridge: there is no junction where they cross.
                val f = failed(r.route(lat, bx - 0.009, lat + 0.0035, bx + 0.0052, mode))
                assertEquals(RouteFailure.NOT_CONNECTED, f.reason, "$label $mode: ${f.message}")
            }
        }
    }

    // ---- Two packs, either order, one tile cache ----

    /** A trail along [lat] over five columns and a track north of it, joined in three places (the T76 stitch network). */
    private val network: List<Line> = run {
        val w = lonOf(x0 - 2.0) + 0.002
        val e = lonOf(x0 + 3.0) - 0.002
        val mid = lonOf(x0 + 0.5)
        val trail = s("class" to "path", "subclass" to "path", "name" to "Border Trail")
        val track = s("class" to "track")
        listOf(
            Line(trail, listOf(lat to w, lat to mid, lat to e)),
            Line(track, listOf(lat + 0.005 to w + 0.003, lat + 0.005 to e - 0.003)),
            Line(trail, listOf(lat to w + 0.003, lat + 0.005 to w + 0.003)),
            Line(trail, listOf(lat to mid, lat + 0.005 to mid + 0.001)),
            Line(trail, listOf(lat to e - 0.003, lat + 0.005 to e - 0.003)),
        )
    }

    @Test
    fun twoPacksLoadedInEitherOrderThroughOneCacheGiveOneRoute() {
        val west = graphFile(network, x0 - 2..x0, "west")
        val east = graphFile(network, x0..x0 + 2, "east") // overlapping by one column
        val from = lat + 0.0001 to lonOf(x0 - 2.0) + 0.005
        val to = lat + 0.0049 to lonOf(x0 + 3.0) - 0.010
        val cache = GraphTileCache()
        val results = ArrayList<PlannedRoute>()
        // West first fills the shared cache with pieces indexed 0 = west; then east first, then west first again.
        for (files in listOf(listOf(west, east), listOf(east, west), listOf(west, east), listOf(east, west))) {
            results += found(TileRouter(TiledGraph(files, cache)).route(from.first, from.second, to.first, to.second))
        }
        assertTrue(cache.hits > 0, "the later routers reused the cached pieces: ${cache.json()}")
        val first = results.first()
        for ((i, r) in results.withIndex()) {
            assertEquals(first.distanceM, r.distanceM, 0.01, "route $i distance")
            assertEquals(first.cost, r.cost, 0.01, "route $i cost")
            assertEquals(first.route.points, r.route.points, "route $i points")
        }
        // The same in drive mode on roads, through the drive overlay, both orders.
        val roads = network.map { Line(s("class" to "tertiary", "name" to "Border Road"), it.pts) }
        val rw = graphFile(roads, x0 - 2..x0, "rw")
        val re = graphFile(roads, x0..x0 + 2, "re")
        val overlay = DriveOverlay()
        val drives = listOf(listOf(rw, re), listOf(re, rw)).map { files ->
            found(TileRouter(TiledGraph(files, cache), overlay).route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        }
        assertEquals(drives[0].distanceM, drives[1].distanceM, 0.01)
        assertEquals(drives[0].route.points, drives[1].route.points)
    }

    // ---- Unconnected parts: the component summary ----

    /** Two grids of tracks in one pack, 2 km apart, never joined: each about 30 km of track. */
    private fun twoGrids(): List<Line> {
        val track = s("class" to "track")
        val out = ArrayList<Line>()
        for (g in 0..1) {
            val w = lonOf(x0 - 2.0) + 0.002 + g * 0.045
            for (i in 0..8) {
                out += Line(track, listOf(lat - 0.012 + i * 0.003 to w, lat - 0.012 + i * 0.003 to w + 0.02))
                out += Line(track, listOf(lat - 0.012 to w + i * 0.0025, lat + 0.012 to w + i * 0.0025))
            }
        }
        return out
    }

    @Test
    fun aRouteBetweenUnconnectedPartsFailsAtOnce() {
        val rows = yRow - 1..yRow + 1
        val file = graphFile(twoGrids(), x0 - 3..x0 + 3, "grids", rows)
        val w = lonOf(x0 - 2.0) + 0.002
        val a = lat to w + 0.010
        val b = lat to w + 0.045 + 0.010
        val r = TileRouter(TiledGraph(listOf(file)))
        for (mode in listOf(TravelMode.HIKE, TravelMode.BIKE, TravelMode.DRIVE)) {
            val f = failed(r.route(a.first, a.second, b.first, b.second, mode))
            assertEquals(RouteFailure.NOT_CONNECTED, f.reason, "$mode")
            assertEquals(true, f.details["componentSummary"], "$mode: ${f.message}")
            assertEquals(0, f.details["expanded"], "$mode settled nothing")
            // Within one grid the summary does not get in the way.
            found(r.route(a.first, a.second, a.first + 0.009, a.second + 0.008, mode))
        }
        // Without the summary (labels unknown, as for graph files before T89) the same refusal needs a search through a grid.
        val g0 = file.whole()
        val blind = RoutingGraph(
            g0.source, g0.nodeLon, g0.nodeLat, g0.nodeEle, g0.edgeFrom, g0.edgeTo, g0.edgeLength, g0.edgeAscent, g0.edgeDescent,
            g0.edgeClass, g0.edgeAttrs, g0.edgeFlags, g0.geomStart, g0.geomLon, g0.geomLat, g0.geomEle, g0.edgeName, g0.names, g0.edgeSpeed,
        )
        val slow = failed(Router(blind).route(a.first, a.second, b.first, b.second, TravelMode.HIKE))
        assertTrue(slow.details["expanded"] as Int > 50, "${slow.details}")
        // Both grids lie inside the pack (closed): even with another pack in the network the answer stands.
        val other = graphFile(network, x0 + 5..x0 + 6, "other")
        val two = failed(TileRouter(TiledGraph(listOf(file, other))).route(a.first, a.second, b.first, b.second, TravelMode.HIKE))
        assertEquals(true, two.details["componentSummary"], two.message)
        assertTrue(two.message.contains("stays inside"), two.message)
        // Labels survive the file: both grids closed, components told apart.
        val g = file.whole()
        val labels = g.footComponent!!
        assertTrue(labels.all { it and 1 == 0 }, "no part reaches the pack's edge")
        assertEquals(2, labels.map { it shr 1 }.toSet().size)
    }

    @Test
    fun partsThatReachThePacksEdgeAreOpenAndSearchedAcrossPacks() {
        // The stitch network cut into two packs: in the west pack alone the trail reaches the east edge (open).
        val west = graphFile(network, x0 - 2..x0 - 1, "west")
        val labels = west.whole().footComponent!!
        assertTrue(labels.any { it and 1 == 1 }, "the trail leaves the west pack")
        val east = graphFile(network, x0..x0 + 2, "east")
        val from = lat + 0.0001 to lonOf(x0 - 2.0) + 0.005
        val to = lat + 0.0049 to lonOf(x0 + 3.0) - 0.010
        found(TileRouter(TiledGraph(listOf(west, east))).route(from.first, from.second, to.first, to.second))
    }

    // ---- The tiler does not cut a hair from a point (the 223 or 224 points of T76) ----

    @Test
    fun aPointAHairPastATileBorderIsTheJoinNotANewCut() {
        val border = 330.0 / 2048 * 360 - 180 // a z11 column border
        val trail = WayAttrs(WayClass.PATH, name = "Hair Trail")
        // The middle vertex lies 23 cm east of the border: the first segment crosses it 23 cm before its end.
        val g = RoutingGraphBuilder().build(
            listOf(WayLine.of(trail, lat to border - 0.004, lat to border + 0.000003, lat to border + 0.004)),
            GraphSource("hair", "fp", "", 14, 0),
        )
        val whole = found(Router(g).route(lat, border - 0.0039, lat, border + 0.0039))
        val tiled = found(TileRouter(TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(g))))).route(lat, border - 0.0039, lat, border + 0.0039))
        assertEquals(whole.route.points.size, tiled.route.points.size, "no extra point at the border: ${tiled.route.points}")
        assertEquals(whole.distanceM, tiled.distanceM, 0.01)
        assertEquals(2, GraphTiler.split(g).size, "still two tiles, joined at the vertex")
    }

    // ---- A corrupt tile: read once, then skipped ----

    @Test
    fun aCorruptTileIsReadOnceAndThenSkipped() {
        val g = RainierRoutingTest().graph()
        val bytes = GraphTileFile.encode(g, 13)
        val probe = GraphTileFile.open(bytes)
        val last = probe.entries.last()
        val bad = bytes.copyOf().also { it[(last.offset + 2).toInt()] = (it[(last.offset + 2).toInt()] + 1).toByte() }
        var reads = 0
        val archive = GraphTileFile.open { offset, length ->
            if (offset == last.offset) reads++
            bad.copyOfRange(offset.toInt(), minOf(bad.size.toLong(), offset + length).toInt())
        }
        val cache = GraphTileCache()
        repeat(5) { assertNull(cache.get(archive, 0, last.x, last.y)) }
        assertEquals(1, reads, "read and checked once")
        assertEquals(1L, cache.loadErrors)
        assertEquals(4L, cache.corruptHits)
        assertEquals(1, cache.corrupt().size)
        assertTrue((cache.corrupt()[0]["error"] as String).contains("checksum"), "${cache.corrupt()}")
        // Rebuilt (forgotten): read again.
        cache.forget(archive)
        assertNull(cache.get(archive, 0, last.x, last.y))
        assertEquals(2, reads)
    }
}
