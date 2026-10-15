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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic networks on a small grid (about 111 m per 0.001 degree of latitude). */
class RouterTest {
    private val path = WayAttrs(WayClass.PATH)
    private val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
    private val source = GraphSource("test", "fp", "", 14, 0)

    private fun graph(vararg lines: WayLine, ele: ElevationSampler? = null) = RoutingGraphBuilder(ele).build(lines.toList(), source)

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    @Test
    fun crossingLinesGetAJunction() {
        // A plus sign: two lines that cross without a shared vertex.
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.002),
            WayLine.of(path, -0.001 to 0.001, 0.001 to 0.001),
        )
        assertEquals(5, g.nodeCount, "four ends and the crossing")
        assertEquals(4, g.edgeCount)
        val p = found(Router(g).route(0.0, 0.0, 0.001, 0.001))
        assertEquals(111.3 + 111.3, p.distanceM, 2.0)
    }

    @Test
    fun sharedMiddleVertexSplitsTheLine() {
        // A T: the stem ends on a vertex in the middle of the bar.
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.001, 0.0 to 0.002),
            WayLine.of(path, 0.0 to 0.001, 0.001 to 0.001),
        )
        assertEquals(4, g.nodeCount)
        assertEquals(3, g.edgeCount)
        assertEquals(1, g.componentLengthM.size)
    }

    @Test
    fun endsWithinASnapToleranceJoin() {
        // The second line starts 0.5 m from the first line's end (a tile border, a sloppy OSM join).
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.001),
            WayLine.of(path, 0.0 to 0.0010045, 0.0 to 0.002),
        )
        assertEquals(1, g.componentLengthM.size)
        assertEquals(3, g.nodeCount)
    }

    @Test
    fun bridgesDoNotJoinWhatTheyCross() {
        val bridge = WayAttrs(WayClass.MINOR, brunnel = true)
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.002),
            WayLine.of(bridge, -0.001 to 0.001, 0.001 to 0.001),
        )
        assertEquals(2, g.componentLengthM.size)
        val r = Router(g).route(0.0, 0.0, 0.001, 0.001)
        assertIs<RouteResult.Failed>(r)
        assertEquals(RouteFailure.NOT_CONNECTED, r.reason)
        assertTrue(r.message.contains("separate networks"), r.message)
    }

    @Test
    fun noTrailNearTheStartIsAClearFailure() {
        val g = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.002))
        val r = Router(g).route(0.01, 0.0, 0.0, 0.002)
        assertIs<RouteResult.Failed>(r)
        assertEquals(RouteFailure.START_OFF_NETWORK, r.reason)
        assertTrue(r.message.contains("nearest is 1.11 km"), r.message)
        // T102: in the units given (the phone's preference, UnitFormat's rule); the details stay metres.
        val feet = TileRouter(WholeGraph(g), units = io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET).route(0.01, 0.0, 0.0, 0.002)
        assertTrue((feet as RouteResult.Failed).message.contains(" mi away") && " mi of the start point" in feet.message, feet.message)
        assertEquals(1111.9, (feet.details["nearestM"] as Double), 0.2)
        val r2 = Router(g).route(0.0, 0.0, 0.0, 0.03)
        assertEquals(RouteFailure.END_OFF_NETWORK, (r2 as RouteResult.Failed).reason)
    }

    @Test
    fun routesStartAndEndMidEdge() {
        val g = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.001, 0.0 to 0.002))
        val p = found(Router(g).route(0.0001, 0.0005, 0.0001, 0.0015))
        assertEquals(111.3, p.distanceM, 1.0)
        assertEquals(0.0005, p.route.points.first().lon, 1e-6)
        assertEquals(0.0015, p.route.points.last().lon, 1e-6)
        // Backwards along the same edge.
        val back = found(Router(g).route(0.0, 0.0015, 0.0, 0.0005))
        assertEquals(111.3, back.distanceM, 1.0)
        assertTrue(back.route.points.first().lon > back.route.points.last().lon)
    }

    @Test
    fun hikersTakeTheTrailAndCyclistsTheRoad() {
        // From A to B: a trail straight across (1 km) or a minor road around (1.4 km).
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
        )
        val hike = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        assertEquals(setOf(WayClass.PATH), hike.metersByClass.keys)
        val bike = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.BIKE))
        assertEquals(setOf(WayClass.MINOR), bike.metersByClass.keys)
        assertTrue(bike.timeS < hike.timeS)
    }

    @Test
    fun slopePenaltyPrefersAFlatDetour() {
        // A hill in the middle of the direct line: 200 m high within 300 m; the detour is flat and 1.6x longer.
        val hill = ElevationSampler { lat, lon ->
            val d = kotlin.math.sqrt((lat - 0.0) * (lat - 0.0) + (lon - 0.0045) * (lon - 0.0045)) * 111_195
            if (d < 300) 1000 + 200 * (1 - d / 300) else 1000.0
        }
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(path, 0.0 to 0.0, 0.0035 to 0.002, 0.0035 to 0.007, 0.0 to 0.009),
            ele = hill,
        )
        val hike = found(Router(g).route(0.0, 0.0, 0.0, 0.009))
        assertTrue(hike.distanceM > 1300, "took the detour: ${hike.distanceM}")
        assertEquals(0.0, hike.ascentM!!, 1.0)
        // The direct line climbs and descends about 200 m.
        val e = (0 until g.edgeCount).first { g.edgeAscent[it] > 100 }
        assertEquals(200.0, g.edgeAscent[e].toDouble(), 15.0)
        assertEquals(200.0, g.edgeDescent[e].toDouble(), 15.0)
    }

    @Test
    fun naismithTime() {
        val m = CostModel(TravelMode.HIKE)
        assertEquals(3600.0 + 3600.0, m.timeS(5000.0, 600.0), 1e-6)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val ele = ElevationSampler { lat, lon -> 1000 + lat * 1e5 + lon * 3e4 }
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0005 to 0.0003, 0.0 to 0.002),
            WayLine.of(WayAttrs(WayClass.TRACK, Surface.UNPAVED, sac = 3, flags = WayAttrs.INFORMAL), -0.001 to 0.001, 0.001 to 0.001),
            ele = ele,
        )
        val bytes = g.encode()
        val back = RoutingGraph.decode(bytes)
        assertEquals(g, back)
        assertEquals(g.json(), back.json())
        val e = (0 until back.edgeCount).first { back.wayClass(it) == WayClass.TRACK }
        assertEquals(Surface.UNPAVED, back.surface(e))
        assertEquals(3, back.sac(e))
        assertEquals(WayAttrs.INFORMAL, back.flags(e))
        // Corruption and truncation are caught by the checksum.
        val bad = bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { RoutingGraph.decode(bad) }
        assertFailsWith<IllegalArgumentException> { RoutingGraph.decode(bytes.copyOf(bytes.size - 7)) }
        assertFailsWith<IllegalArgumentException> { RoutingGraph.decode("nope".encodeToByteArray()) }
    }

    @Test
    fun noElevationMeansNoAscent() {
        val g = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.002))
        val p = found(Router(g).route(0.0, 0.0, 0.0, 0.002))
        assertEquals(null, p.ascentM)
        assertTrue(p.route.points.all { it.ele == null })
        assertEquals(false, g.json()["withElevation"])
    }

    @Test
    fun fileNameSitsNextToThePack() {
        // T49: one graph per vector archive, so two archives of one pack never share a file.
        assertEquals("rainier-outdoors-graph.idx", RoutingGraph.fileNameFor("rainier-outdoors.pmtiles"))
        assertEquals("rainier-osm-graph.idx", RoutingGraph.fileNameFor("rainier-osm.pmtiles"))
        assertEquals("osm-graph.idx", RoutingGraph.fileNameFor("osm.pmtiles"))
        assertEquals("rainier-graph.idx", RoutingGraph.legacyFileNameFor("rainier-outdoors.pmtiles"))
    }

    // ---- From a vector archive ----

    private val extent = 4096

    private fun tileXy(z: Int, lon: Double, lat: Double): Pair<Double, Double> {
        val n = 1 shl z
        val r = lat * PI / 180
        return (lon + 180) / 360 * n to (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
    }

    /** A line feature in tile (x, y) at z14 with points given in lon/lat (may run into the tile buffer). */
    private fun line(x: Int, y: Int, props: Map<String, Value>, vararg pts: Pair<Double, Double>): Feature {
        val tp = pts.map { (lat, lon) ->
            val (fx, fy) = tileXy(14, lon, lat)
            TilePoint(((fx - x) * extent).let { kotlin.math.round(it).toInt() }, ((fy - y) * extent).let { kotlin.math.round(it).toInt() })
        }
        return Feature(null, props, Geometry.Lines(listOf(tp)))
    }

    private fun s(vararg kv: Pair<String, String>): Map<String, Value> = kv.associate { (k, v) -> k to Value.StringValue(v) }

    private fun archive(tiles: Map<Pair<Int, Int>, List<Feature>>): PmtilesReader {
        val w = PmtilesWriter(TileType.MVT)
        for ((xy, fs) in tiles.entries.sortedBy { it.key.second * 100000 + it.key.first }) {
            w.addTile(14, xy.first, xy.second, VectorTile(listOf(Layer("transportation", fs, extent), Layer("water", emptyList(), extent))).encode())
        }
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = -121.75, minLat = 46.78, maxLon = -121.70, maxLat = 46.81, minZoom = 14, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return PmtilesReader.open(ByteRangeSource { offset, length ->
            bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
        })
    }

    @Test
    fun linesAcrossTileBordersAreJoined() {
        // A trail running east across the border between two z14 tiles; each tile has it with a buffer.
        val lat = 46.795
        val (fx, fy) = tileXy(14, -121.73, lat)
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        val border = (x + 1).toDouble() / (1 shl 14) * 360 - 180
        val a = -121.73
        val b = border + (border - a) // as far east of the border as a is west
        val props = s("class" to "path", "subclass" to "path", "surface" to "unpaved")
        val buffer = (b - border) * 0.1
        val road = s("class" to "rail")
        val reader = archive(mapOf(
            (x to y) to listOf(line(x, y, props, lat to a, lat to border + buffer), line(x, y, road, lat - 0.001 to a, lat + 0.001 to a)),
            (x + 1 to y) to listOf(line(x + 1, y, props, lat to border - buffer, lat to b)),
        ))
        val builder = RoutingGraphBuilder()
        val g = builder.build(reader, "test-outdoors.pmtiles")
        val st = builder.stats!!
        assertEquals(2, st.tiles)
        assertEquals(2, st.features, "the rail line is not routable")
        assertEquals(1, g.componentLengthM.size)
        val p = found(Router(g).route(lat, a, lat, b))
        val expect = TrackStats.distanceM(lat, a, lat, b)
        assertEquals(expect, p.distanceM, 2.0)
        assertEquals(setOf(WayClass.PATH), p.metersByClass.keys)
        assertEquals(RoutingGraphBuilder.fingerprint(reader.header), g.source.fingerprint)
    }

    @Test
    fun openMapTilesClassesAreRoutable() {
        assertEquals(WayClass.FOOTWAY, WayClass.classify("path", "footway"))
        assertEquals(WayClass.CYCLEWAY, WayClass.classify("path", "cycleway"))
        assertEquals(WayClass.TRACK, WayClass.classify("track", null))
        assertEquals(WayClass.MINOR, WayClass.classify("minor", null))
        assertEquals(WayClass.MOTORWAY, WayClass.classify("motorway", null)) // T73: in the graph, for drive mode only
        assertEquals(null, WayClass.classify("path", "via_ferrata"))
        assertEquals(null, WayClass.classify("rail", "rail"))
        val a = assertNotNull(WayAttrs.of(s("class" to "path", "sac_scale" to "alpine_hiking", "access" to "private", "brunnel" to "bridge")))
        assertEquals(4, a.sac)
        assertEquals(WayAttrs.PRIVATE, a.flags)
        assertTrue(a.brunnel)
    }

    @Test
    fun aShortRoadStretchIsNotOneOfAHikesTrails() {
        // T87: two named trails joined by a named road. 67 m of road is a crossing, not a trail the hike follows;
        // 134 m is (MIN_VIA_ROAD_M is 100 m). Trails keep MIN_VIA_M (30 m).
        fun via(roadEndLon: Double, mode: TravelMode): List<String> {
            val g = graph(
                WayLine.of(path.copy(name = "Lower Trail"), 0.0 to 0.0, 0.0 to 0.002),
                WayLine.of(minor.copy(name = "Park Road"), 0.0 to 0.002, 0.0 to roadEndLon),
                WayLine.of(path.copy(name = "Upper Trail"), 0.0 to roadEndLon, 0.0 to roadEndLon + 0.002),
            )
            return found(Router(g).route(0.0, 0.0, 0.0, roadEndLon + 0.002, mode)).via.map { it.first }
        }
        assertEquals(listOf("Lower Trail", "Upper Trail"), via(0.0026, TravelMode.HIKE))
        assertEquals(listOf("Lower Trail", "Upper Trail"), via(0.0026, TravelMode.BIKE))
        assertEquals(listOf("Lower Trail", "Park Road", "Upper Trail"), via(0.0032, TravelMode.HIKE))
    }
}
