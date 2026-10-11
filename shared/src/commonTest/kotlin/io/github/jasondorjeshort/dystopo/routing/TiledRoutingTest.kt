package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T76: graph tiles, the graph file, routes over tiles and across packs. */
class TiledRoutingTest {
    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun within(expect: Double, actual: Double, share: Double, what: String) =
        assertTrue(abs(actual - expect) <= share * abs(expect), "$what: $actual vs $expect (more than ${share * 100} % apart)")

    private fun tiled(g: RoutingGraph, zoom: Int = GraphTiler.ZOOM, cache: GraphTileCache = GraphTileCache()): TileRouter =
        TileRouter(TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(g, zoom))), cache))

    // ---- Golden: today's (T45 to T68) numbers for Paradise to Panorama Point on the fixture ----

    /** The unidirectional A* before T76, on the fixture graph: distance, ascent, descent, cost. */
    private val golden = listOf(
        doubleArrayOf(46.7864, -121.7366, 46.8024, -121.7289, 2761.207511133386, 453.7901166374014, 53.50837460524252, 6391.459041294407),
        doubleArrayOf(46.8024, -121.7289, 46.7864, -121.7366, 2761.207511133385, 53.50837460524252, 453.7901166374014, 3258.1089088377535),
        doubleArrayOf(46.7989, -121.7227, 46.7914, -121.7325, 1120.7338639644236, 0.699999988079071, 247.6999969482422, 1302.222884728534),
    )

    @Test
    fun paradiseToPanoramaPointMatchesTheRouteBeforeTiles() {
        val g = RainierRoutingTest().graph()
        // The whole graph, the z11 graph file (one tile here) and a z16 file (tiles of about 400 m, so the
        // route crosses many tile borders and every border is a cut in the graph).
        for ((label, router) in listOf("whole" to Router(g), "z11" to tiled(g), "z16" to tiled(g, 16))) {
            for (c in golden) {
                val p = found(router.route(c[0], c[1], c[2], c[3]))
                within(c[4], p.distanceM, 0.001, "$label distance")
                within(c[5], p.ascentM!!, 0.001, "$label ascent")
                within(c[6], p.descentM!!, 0.001, "$label descent")
                within(c[7], p.cost, 0.001, "$label cost")
            }
        }
        val z16 = found(tiled(g, 16).route(golden[0][0], golden[0][1], golden[0][2], golden[0][3]))
        assertTrue((z16.memory["tiles"] as Int) > 5, "z16 route reads several tiles: ${z16.memory}")
        assertEquals(listOf("Skyline Trail"), z16.via.map { it.first }, "names survive the cuts")
    }

    @Test
    fun aRouteLoadsOnlyTheTilesItTouches() {
        // Paradise and the Sunrise path are in different z11 tiles: a route at Paradise never reads Sunrise's.
        val g = RainierRoutingTest().graph()
        val archive = GraphTileFile.open(GraphTileFile.encode(g))
        assertTrue(archive.entries.size >= 2, "Paradise and Sunrise tiles: ${archive.entries.size}")
        val tg = TiledGraph(listOf(archive))
        val p = found(TileRouter(tg).route(46.7864, -121.7366, 46.8024, -121.7289))
        assertTrue((p.memory["tiles"] as Int) < archive.entries.size, "${p.memory}")
        assertTrue((tg.cache.json()["tiles"] as Int) < archive.entries.size, "Sunrise never read: ${tg.cache.json()}")
        assertTrue((p.memory["graphBytes"] as Long) < tg.wholeBytes(), "${p.memory} vs whole ${tg.wholeBytes()}")
    }

    @Test
    fun separateNetworksFailWhenOneSideRunsOut() {
        val r = tiled(RainierRoutingTest().graph()).route(46.7864, -121.7366, 46.9180, -121.6400)
        assertIs<RouteResult.Failed>(r)
        assertEquals(RouteFailure.NOT_CONNECTED, r.reason)
        assertTrue(r.message.startsWith("no trail connects start and end"), r.message)
        assertTrue(r.message.contains("separate networks"), r.message)
        assertEquals("end", r.details["exhausted"], "the short Sunrise path runs out first")
    }

    // ---- The tiler and the file ----

    private val path = WayAttrs(WayClass.PATH, Surface.UNPAVED, name = "Long Trail")

    @Test
    fun edgesAreCutAtTileBordersAndKeepTheirLength() {
        // One straight 3 km line across several z14 tiles, with a hill on it.
        val hill = ElevationSampler { _, lon -> 1000 + 300 * kotlin.math.sin((lon + 121.75) * 150) }
        val g = RoutingGraphBuilder(hill).build(listOf(WayLine.of(path, 46.79 to -121.75, 46.791 to -121.71)), GraphSource("t", "fp", "", 14, 0))
        assertEquals(1, g.edgeCount)
        val tiles = GraphTiler.split(g, 15)
        assertTrue(tiles.size >= 3, "tiles: $tiles")
        assertEquals(g.edgeLength[0].toDouble(), tiles.sumOf { t -> (0 until t.graph.edgeCount).sumOf { t.graph.edgeLength[it].toDouble() } }, 0.3)
        assertEquals(g.edgeAscent[0].toDouble(), tiles.sumOf { t -> t.graph.edgeAscent.sumOf { it.toDouble() } }, 0.3)
        assertEquals(g.edgeDescent[0].toDouble(), tiles.sumOf { t -> t.graph.edgeDescent.sumOf { it.toDouble() } }, 0.3)
        // Each cut is one position written into both tiles.
        val positions = tiles.flatMap { t -> (0 until t.graph.nodeCount).map { t.graph.nodeLon[it] to t.graph.nodeLat[it] } }
        assertEquals(2 + (tiles.size - 1) * 2, positions.size)
        assertEquals(tiles.size + 1, positions.toSet().size)
        assertTrue(tiles.all { t -> t.graph.names == listOf("Long Trail") })
        // Stitched back, the route is the same as on the whole line.
        val whole = found(Router(g).route(46.79, -121.75, 46.791, -121.71))
        val bytes = GraphTileFile.encode(g, 15)
        val viaTiles = found(TileRouter(TiledGraph(listOf(GraphTileFile.open(bytes)))).route(46.79, -121.75, 46.791, -121.71))
        assertEquals(whole.distanceM, viaTiles.distanceM, 0.5)
        assertEquals(whole.ascentM!!, viaTiles.ascentM!!, 0.5)
        assertEquals(whole.cost, viaTiles.cost, 2.0)
        val stitched = GraphTileFile.open(bytes).whole()
        assertEquals(1, stitched.componentLengthM.size)
        assertEquals(tiles.size, stitched.edgeCount)
    }

    @Test
    fun graphFileRoundTripsAndRefusesOldVersions() {
        val g = RainierRoutingTest().graph()
        val bytes = GraphTileFile.encode(g, 13)
        val a = GraphTileFile.open(bytes)
        assertEquals(g.source, a.source)
        assertEquals(13, a.tileZoom)
        assertEquals(bytes.size.toLong(), a.fileBytes)
        for (e in a.entries) {
            val t = a.tile(e.x, e.y)!!
            assertEquals(e.nodes, t.nodeCount)
            assertEquals(e.edges, t.edgeCount)
        }
        assertNull(a.tile(0, 0))
        assertEquals(g.json()["km"], a.whole().json()["km"])
        // The single-graph encoding (version 2, the file before T76) is refused, saying why.
        val old = assertFailsWith<IllegalArgumentException> { GraphTileFile.open(g.encode()) }
        assertTrue(old.message!!.contains("version 2") && old.message!!.contains("before T76"), old.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { RoutingGraph.decode(bytes) }.message!!.contains("tiled"))
        // A damaged header or tile is caught by its checksum; a damaged tile is skipped by the cache, not thrown.
        assertFailsWith<IllegalArgumentException> { GraphTileFile.open(bytes.copyOf().also { it[12] = (it[12] + 1).toByte() }) }
        assertFailsWith<IllegalArgumentException> { GraphTileFile.open(bytes.copyOf(20)) }
        val last = a.entries.last()
        val bad = bytes.copyOf().also { it[(last.offset + 2).toInt()] = (it[(last.offset + 2).toInt()] + 1).toByte() }
        val ba = GraphTileFile.open(bad)
        assertFailsWith<IllegalArgumentException> { ba.tile(last.x, last.y) }
        val cache = GraphTileCache()
        assertNull(cache.get(ba, 0, last.x, last.y))
        assertEquals(1L, cache.loadErrors)
    }

    @Test
    fun theCacheEvictsLeastRecentlyUsedTiles() {
        val g = RainierRoutingTest().graph()
        val cache = GraphTileCache(maxBytes = 1) // keeps one tile at a time
        val r = tiled(g, 16, cache)
        found(r.route(46.7864, -121.7366, 46.8024, -121.7289))
        found(r.route(46.7864, -121.7366, 46.8024, -121.7289))
        assertEquals(1, cache.json()["tiles"])
        assertTrue(cache.evictions > 5, "evictions ${cache.evictions}")
    }

    // ---- Two packs that share a border ----

    private val extent = 4096
    /** A z11 tile border (a multiple of 8 at z14), so the packs' border is also a graph tile border. */
    private val x0 = 2648
    private val lat = 46.79
    private val yRow = tileXy(14, -121.8, lat).second.toInt()

    private fun lonOf(x: Double) = x / (1 shl 14) * 360 - 180

    private fun tileXy(z: Int, lon: Double, lat: Double): Pair<Double, Double> {
        val n = 1 shl z
        val r = lat * PI / 180
        return (lon + 180) / 360 * n to (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
    }

    /**
     * A network over z14 columns x0 - 2 to x0 + 2: a trail along [lat] end to end, a track 550 m north
     * of it, joined by three connectors (west end, the middle of column x0, east end), so a route from
     * west to east has a choice to make in the shared column.
     */
    private val network: List<Pair<Map<String, Value>, List<Pair<Double, Double>>>> = run {
        val w = lonOf(x0 - 2.0) + 0.002
        val e = lonOf(x0 + 3.0) - 0.002
        val mid = lonOf(x0 + 0.5)
        val trail = s("class" to "path", "subclass" to "path", "name" to "Border Trail")
        val track = s("class" to "track")
        listOf(
            trail to listOf(lat to w, lat to mid, lat to e),
            track to listOf(lat + 0.005 to w + 0.003, lat + 0.005 to e - 0.003),
            trail to listOf(lat to w + 0.003, lat + 0.005 to w + 0.003),
            trail to listOf(lat to mid, lat + 0.005 to mid + 0.001),
            trail to listOf(lat to e - 0.003, lat + 0.005 to e - 0.003),
        )
    }

    private fun s(vararg kv: Pair<String, String>): Map<String, Value> = kv.associate { (k, v) -> k to Value.StringValue(v) }

    /** A synthetic outdoors pack holding z14 columns [cols]: each tile carries every line (the builder clips to the tile). */
    private fun pack(cols: IntRange): PmtilesReader {
        val w = PmtilesWriter(TileType.MVT)
        for (x in cols) {
            val fs = network.map { (props, pts) ->
                Feature(null, props, Geometry.Lines(listOf(pts.map { (la, lo) ->
                    val (fx, fy) = tileXy(14, lo, la)
                    TilePoint(round((fx - x) * extent).toInt(), round((fy - yRow) * extent).toInt())
                })))
            }
            w.addTile(14, x, yRow, VectorTile(listOf(Layer("transportation", fs, extent))).encode())
        }
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = lonOf(cols.first.toDouble()), minLat = lat - 0.01, maxLon = lonOf(cols.last + 1.0), maxLat = lat + 0.01, minZoom = 14, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return PmtilesReader.open(ByteRangeSource { offset, length ->
            bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
        })
    }

    private val slope = ElevationSampler { la, lo -> 1500 + (la - lat) * 40_000 + (lo + 121.8) * 2_000 }

    private fun graphFile(cols: IntRange, name: String): GraphTileArchive =
        GraphTileFile.open(GraphTileFile.encode(RoutingGraphBuilder(slope).build(pack(cols), "$name-outdoors.pmtiles")))

    @Test
    fun aRouteAcrossTwoPacksMatchesTheUnionPack() {
        val union = graphFile(x0 - 2..x0 + 2, "union")
        val from = lat + 0.0001 to lonOf(x0 - 2.0) + 0.005 // west, only in the west pack
        val to = lat + 0.0049 to lonOf(x0 + 3.0) - 0.010 // east on the track, only in the east pack
        val expect = found(TileRouter(TiledGraph(listOf(union))).route(from.first, from.second, to.first, to.second))
        assertTrue(expect.distanceM > 3500, "${expect.distanceM}")
        val expectGap = expect.route.points.zipWithNext().maxOf { (a, b) -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }
        for ((label, packs) in listOf(
            "overlapping by one column" to listOf(graphFile(x0 - 2..x0, "west"), graphFile(x0..x0 + 2, "east")),
            "abutting" to listOf(graphFile(x0 - 2..x0 - 1, "west"), graphFile(x0..x0 + 2, "east")),
            "abutting, east first" to listOf(graphFile(x0..x0 + 2, "east"), graphFile(x0 - 2..x0 - 1, "west")),
        )) {
            // Each pack alone cannot do it: the start or the end is off its network.
            for (p in packs) assertIs<RouteResult.Failed>(TileRouter(TiledGraph(listOf(p))).route(from.first, from.second, to.first, to.second), label)
            val got = found(TileRouter(TiledGraph(packs)).route(from.first, from.second, to.first, to.second))
            within(expect.distanceM, got.distanceM, 0.001, "$label distance")
            within(expect.cost, got.cost, 0.001, "$label cost")
            within(expect.ascentM!!, got.ascentM!!, 0.001, "$label ascent")
            assertEquals(expect.via.map { it.first }, got.via.map { it.first }, label)
            // The line is continuous: no jump between consecutive points longer than the network's own.
            val gaps = got.route.points.zipWithNext().maxOf { (a, b) -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }
            assertTrue(gaps <= expectGap + 5, "$label largest step $gaps m, union $expectGap m")
            // And back the other way.
            val back = found(TileRouter(TiledGraph(packs)).route(to.first, to.second, from.first, from.second))
            within(expect.distanceM, back.distanceM, 0.01, "$label back")
        }
    }

    @Test
    fun nearestNamesTheLine() {
        val r = TileRouter(TiledGraph(listOf(graphFile(x0 - 2..x0 + 2, "u"))))
        val snap = r.nearest(lat + 0.0002, lonOf(x0 - 1.0), 100.0)!!
        assertEquals("Border Trail", snap.name)
        assertEquals(WayClass.PATH, snap.wayClass)
        assertNull(r.nearest(lat + 0.03, lonOf(x0 - 1.0), 100.0))
        val off = r.route(lat + 0.03, lonOf(x0 - 1.0), lat, lonOf(x0 + 1.0))
        assertIs<RouteResult.Failed>(off)
        assertEquals(RouteFailure.START_OFF_NETWORK, off.reason)
        assertIs<RouteResult.Failed>(TileRouter(TiledGraph(emptyList())).route(lat, -121.8, lat, -121.7)).also { assertEquals(RouteFailure.EMPTY_GRAPH, it.reason) }
    }
}
