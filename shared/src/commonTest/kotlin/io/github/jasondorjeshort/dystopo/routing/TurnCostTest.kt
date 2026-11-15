package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T281 (ADR 0062): the drive search on directed-edge states ([DriveSearch]) and the turn function's geometry
 * ([TurnCost]). T282: the production table ([DriveTurns], the T282 section below); the T281 tests keep their
 * test-only tables: [LEFT] (a turn across traffic of 30 degrees or more 60 s, a reversal 100 s), [RANDOM] (a seeded
 * pseudo-random cost per pair of edges) or [JUNCTIONS] (50 s at a node of three or more roads).
 *
 * Synthetic roads near the equator (about 111 m per 0.001 degree); points are (lat, lon).
 */
class TurnCostTest {
    private val source = GraphSource("turns-outdoors.pmtiles", "fp", "", 14, 0, drive = true)
    private val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)

    private fun graph(vararg lines: WayLine) = RoutingGraphBuilder().build(lines.toList(), source)

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun router(g: RoutingGraph, table: TurnTable, side: DrivingSide = DrivingSide.RIGHT) =
        TileRouter(WholeGraph(g), turns = TurnCost(table, side))

    private fun drive(r: Router, a: Pair<Double, Double>, b: Pair<Double, Double>) = found(r.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))

    /** Cost units of [s] seconds of turning. */
    private fun sec(s: Double) = CostModel(TravelMode.DRIVE).secondsCost(s)

    // ---- The geometry ----

    @Test
    fun theTurnFunctionReadsAnglesSidesReversalsAndRoads() {
        // A crossroads at (0, 0): roads to the south, north, east and west, plus a copy of the east road (the same
        // geometry, name and class, as a pack overlap would give) that must count once.
        val east = minor.copy(name = "East Road")
        val g = graph(
            WayLine.of(minor, -0.001 to 0.0, 0.0 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.001 to 0.0),
            WayLine.of(east, 0.0 to 0.0, 0.0 to 0.001), WayLine.of(minor, 0.0 to 0.0, 0.0 to -0.001),
        )
        val copy = graph(WayLine.of(east, 0.0 to 0.0, 0.0 to 0.001))
        val p = GraphPiece(0, 0, 0, g, joinZoom = -1)
        val q = GraphPiece(1, 0, 0, copy, joinZoom = -1)
        val x = (0 until g.nodeCount).first { g.nodeLat[it] == 0 && g.nodeLon[it] == 0 }
        val junction = Junction(listOf(p, q), intArrayOf(x, (0 until copy.nodeCount).first { copy.nodeLon[it] == 0 }))
        assertEquals(4, junction.roads.size, "four roads, the copy counted once")
        fun edgeTo(lat: Int, lon: Int) = (0 until g.edgeCount).first { e ->
            val o = if (g.edgeFrom[e] == x) g.edgeTo[e] else g.edgeFrom[e]
            g.nodeLat[o] == lat && g.nodeLon[o] == lon
        }
        val south = edgeTo(-1000, 0)
        val seen = ArrayList<Turn>()
        val probe = TurnCost(TurnTable { seen += it; 1.0 })
        // Arriving from the south (heading north): east is a right turn, west a left one, south the reversal.
        fun turn(out: Int): Turn {
            seen.clear()
            probe.seconds(p, south, g.edgeTo[south] == x, junction, p, out, g.edgeFrom[out] == x)
            return seen.single()
        }
        assertEquals(90.0, turn(edgeTo(0, 1000)).angleDeg, 0.5)
        assertEquals(-90.0, turn(edgeTo(0, -1000)).angleDeg, 0.5)
        assertEquals(0.0, turn(edgeTo(1000, 0)).angleDeg, 0.5)
        assertTrue(turn(south).reversal, "back the way it came")
        assertTrue(!turn(edgeTo(1000, 0)).reversal)
        assertTrue(turn(edgeTo(0, -1000)).acrossTraffic, "a left turn crosses traffic that keeps right")
        assertTrue(!turn(edgeTo(0, 1000)).acrossTraffic)
        assertEquals(4, turn(edgeTo(0, 1000)).roads)
        val left = TurnCost(TurnTable { seen += it; 1.0 }, DrivingSide.LEFT)
        seen.clear(); left.seconds(p, south, g.edgeTo[south] == x, junction, p, edgeTo(0, 1000), true)
        assertTrue(seen.single().acrossTraffic, "where traffic keeps left the right turn crosses it")
        // The bearing cache is built on first use and counted in the graph's size.
        val fresh = graph(WayLine.of(east, 0.0 to 0.0, 0.0 to 0.001))
        val before = fresh.approxBytes()
        fresh.endBearing
        assertEquals(before + 8L * fresh.edgeCount, fresh.approxBytes())
        // The zero table never builds a junction's roads (nor the bearings).
        assertEquals(0.0, TurnCost(TurnTable.ZERO).cost(p, south, true, Junction(listOf(p), intArrayOf(x)), p, south, false))
        assertTrue(!TurnCost().free, "T282: the drive table prices turns")
    }

    // ---- The search ----

    @Test
    fun aRouteMayPassANodeTwiceToAvoidATurn() {
        // From the south road onto the west road at X: a left turn (60 s). Three rights round the block north-east of X
        // (333 m more at 40 km/h, about 30 s) and straight on through X again cost less.
        val g = graph(
            WayLine.of(minor, -0.003 to 0.0, 0.0 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.0 to -0.003),
            WayLine.of(minor, 0.0 to 0.0, 0.001 to 0.0, 0.001 to 0.001), WayLine.of(minor, 0.001 to 0.001, 0.0 to 0.001, 0.0 to 0.0),
        )
        val a = -0.002 to 0.0
        val b = 0.0 to -0.002
        val withTurns = drive(router(g, LEFT), a, b)
        val atX = withTurns.route.points.count { abs(it.lat) < 1e-7 && abs(it.lon) < 1e-7 }
        assertEquals(2, atX, "X passed twice: ${withTurns.route.points}")
        assertTrue(withTurns.distanceM > 700, "${withTurns.distanceM}")
        val nodeSearch = drive(TileRouter(WholeGraph(g), driveTurns = false), a, b)
        assertEquals(444.8, nodeSearch.distanceM, 2.0) // the left turn, blind to it
        assertTrue(withTurns.cost < nodeSearch.cost + sec(60.0), "${withTurns.cost} against ${nodeSearch.cost + sec(60.0)}")
        // At zero cost the drive search finds the node search's route.
        assertEquals(nodeSearch.cost, drive(router(g, TurnTable.ZERO), a, b).cost, 1e-6)
    }

    @Test
    fun aOneWayEndToEndAndBothEndsOnOneEdge() {
        // A block of one-way streets, clockwise on the map (A -> B -> C -> D -> A): every corner is a left turn.
        val oneway = minor.copy(flags = WayAttrs.ONEWAY)
        val g = graph(
            WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002), WayLine.of(oneway, 0.0 to 0.002, 0.002 to 0.002),
            WayLine.of(oneway, 0.002 to 0.002, 0.002 to 0.0), WayLine.of(oneway, 0.002 to 0.0, 0.0 to 0.0),
        )
        val r = router(g, LEFT)
        val zero = router(g, TurnTable.ZERO)
        // Backwards along A-B: round the block, four left turns (off the first edge at B, then C and D, onto the last at A).
        val back = drive(r, 0.0 to 0.0015, 0.0 to 0.0005)
        val backZero = drive(zero, 0.0 to 0.0015, 0.0 to 0.0005)
        assertEquals(779.2, back.distanceM, 3.0)
        assertEquals(backZero.cost + sec(4 * 60.0), back.cost, 1e-6)
        // With the flow, start and end on one edge in either order of fraction: the direct stretch, no turn.
        val ahead = drive(r, 0.0 to 0.0005, 0.0 to 0.0015)
        assertEquals(111.3, ahead.distanceM, 1.0)
        assertEquals(drive(zero, 0.0 to 0.0005, 0.0 to 0.0015).cost, ahead.cost, 1e-6)
        // A two-way street: both orders direct.
        val street = graph(WayLine.of(minor, 0.0 to 0.0, 0.0 to 0.002), WayLine.of(minor, 0.0 to 0.002, 0.002 to 0.002))
        for ((a, b) in listOf((0.0 to 0.0005) to (0.0 to 0.0015), (0.0 to 0.0015) to (0.0 to 0.0005))) {
            val p = drive(router(street, LEFT), a, b)
            assertEquals(111.3, p.distanceM, 1.0)
            assertEquals(p.distanceM * CostModel.DRIVE_REF_KMH / 40.0, p.cost, 1.0)
        }
    }

    @Test
    fun theTurnsOffTheFirstEdgeAndOntoTheLastArePriced() {
        // West road -> J1 -> north road -> J2 -> west again: heading east, left at J1; heading north, left at J2.
        val g = graph(
            WayLine.of(minor, 0.0 to -0.002, 0.0 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0),
            WayLine.of(minor, 0.002 to 0.0, 0.002 to -0.002),
        )
        val a = 0.0 to -0.001
        val b = 0.002 to -0.001
        val zero = drive(router(g, TurnTable.ZERO), a, b)
        assertEquals(zero.cost + sec(120.0), drive(router(g, LEFT), a, b).cost, 1e-6)
        // Backwards the same corners are right turns: free.
        assertEquals(drive(router(g, TurnTable.ZERO), b, a).cost, drive(router(g, LEFT), b, a).cost, 1e-6)
    }

    @Test
    fun aDeadEndStartAndAReversalAtADeadEnd() {
        // A dead-end spur J-D off a through road; the start is at the dead end itself.
        val g = graph(
            WayLine.of(minor, 0.0 to -0.002, 0.0 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.0 to 0.002),
            WayLine.of(minor, 0.0 to 0.0, -0.002 to 0.0),
        )
        val deadEnd = -0.0025 to 0.0 // snaps onto D
        val r = router(g, LEFT)
        // Leaving the dead end: the start's edge is taken towards J, no reversal paid; at J heading north, east is right.
        val out = drive(r, deadEnd, 0.0 to 0.001)
        assertEquals(drive(router(g, TurnTable.ZERO), deadEnd, 0.0 to 0.001).cost, out.cost, 1e-6)
        // Starting on the spur facing the dead end is no constraint either (both directions of the start edge are seeds).
        val mid = drive(r, -0.0015 to 0.0, 0.0 to 0.001)
        assertEquals(drive(router(g, TurnTable.ZERO), -0.0015 to 0.0, 0.0 to 0.001).cost, mid.cost, 1e-6)
        // The start on a one-way into N, where the way on is a left turn (60 s) and a 56 m dead-end stub goes straight on:
        // driving into the stub, turning round at its end (25 s, never refused) and turning right at N costs less.
        val oneWayIn = graph(
            WayLine.of(minor.copy(flags = WayAttrs.ONEWAY), -0.002 to 0.0, 0.0 to 0.0),
            WayLine.of(minor, 0.0 to 0.0, 0.0005 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.0 to -0.002),
        )
        val uTurn = TurnTable { t ->
            when {
                t.reversal -> 25.0
                t.acrossTraffic && abs(t.angleDeg) >= 30 -> 60.0
                else -> 0.0
            }
        }
        val round = drive(router(oneWayIn, uTurn), -0.001 to 0.0, 0.0 to -0.001)
        assertTrue(round.route.points.any { abs(it.lat - 0.0005) < 1e-7 && abs(it.lon) < 1e-7 }, "turned at the stub's end: ${round.route.points}")
        assertEquals(333.6, round.distanceM, 1.5)
        assertEquals(round.distanceM * CostModel.DRIVE_REF_KMH / 40.0 + sec(25.0), round.cost, 2.0)
        // Blind to turns, the left turn.
        assertEquals(222.4, drive(TileRouter(WholeGraph(oneWayIn), driveTurns = false), -0.001 to 0.0, 0.0 to -0.001).distanceM, 1.0)
    }

    @Test
    fun aMirroredGraphWithTheOtherDrivingSideCostsTheSame() {
        // The node-twice block and a T-junction, mirrored east-west: what was a left turn is a right one.
        fun lines(m: Double) = arrayOf(
            WayLine.of(minor, -0.003 to 0.0, 0.0 to 0.0), WayLine.of(minor, 0.0 to 0.0, 0.0 to -0.003 * m),
            WayLine.of(minor, 0.0 to 0.0, 0.001 to 0.0, 0.001 to 0.001 * m), WayLine.of(minor, 0.001 to 0.001 * m, 0.0 to 0.001 * m, 0.0 to 0.0),
            WayLine.of(minor, -0.002 to 0.0, -0.002 to 0.002 * m),
        )
        val g = graph(*lines(1.0))
        val mirror = graph(*lines(-1.0))
        for ((a, b) in listOf((-0.0025 to 0.0) to (0.0 to -0.002), (-0.0025 to 0.0) to (-0.002 to 0.0015), (0.0 to -0.002) to (-0.0025 to 0.0))) {
            val right = drive(router(g, RANDOM_SIDED), a, b)
            val left = drive(router(mirror, RANDOM_SIDED, DrivingSide.LEFT), a.first to -a.second, b.first to -b.second)
            assertEquals(right.cost, left.cost, 1e-6, "$a -> $b")
            assertEquals(right.distanceM, left.distanceM, 0.5, "$a -> $b")
        }
        // Not vacuous: the mirror on the same side costs differently.
        val same = drive(router(mirror, LEFT), -0.0025 to 0.0, 0.0 to 0.002)
        val orig = drive(router(g, LEFT), -0.0025 to 0.0, 0.0 to -0.002)
        assertTrue(abs(same.cost - orig.cost) > sec(10.0), "${same.cost} against ${orig.cost}")
    }

    // ---- Against a plain edge Dijkstra on seeded random graphs ----

    /** A jittered lattice of roads with gaps, one-ways, diagonals and mixed classes and speeds. */
    private fun randomGraph(rnd: Random, n: Int = 6, step: Double = 0.002): RoutingGraph {
        val lat = Array(n) { DoubleArray(n) }
        val lon = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) { lat[i][j] = i * step + rnd.nextDouble(-0.25, 0.25) * step; lon[i][j] = j * step + rnd.nextDouble(-0.25, 0.25) * step }
        val classes = listOf(WayClass.SERVICE, WayClass.MINOR, WayClass.MINOR, WayClass.TERTIARY, WayClass.SECONDARY)
        fun attrs(): WayAttrs {
            val flags = when (rnd.nextInt(8)) { 0 -> WayAttrs.ONEWAY; 1 -> WayAttrs.ONEWAY_REVERSE; else -> 0 }
            return WayAttrs(classes[rnd.nextInt(classes.size)], Surface.PAVED, flags = flags, maxspeedKmh = if (rnd.nextInt(4) == 0) 30 + 10 * rnd.nextInt(5) else 0)
        }
        val lines = ArrayList<WayLine>()
        for (i in 0 until n) for (j in 0 until n) {
            if (j + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), lat[i][j] to lon[i][j], lat[i][j + 1] to lon[i][j + 1])
            if (i + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), lat[i][j] to lon[i][j], lat[i + 1][j] to lon[i + 1][j])
            if (i + 1 < n && j + 1 < n && rnd.nextInt(10) == 0) lines += WayLine.of(attrs(), lat[i][j] to lon[i][j], lat[i + 1][j + 1] to lon[i + 1][j + 1])
        }
        return RoutingGraphBuilder().build(lines, source)
    }

    /**
     * The reference: a one-directional Dijkstra over directed-edge states, written plainly, with the router's snapping,
     * edge costs and ends (both directions of the start edge seeded; the goal entered from either end of its edge after a
     * turn; both ends on one edge travelled directly). Infinite when there is no route.
     */
    private fun reference(g: RoutingGraph, turns: TurnCost, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val model = CostModel(TravelMode.DRIVE)
        val s = g.nearest(a.first, a.second, Router.DEFAULT_SNAP_M) { model.usable(g, it) } ?: return Double.POSITIVE_INFINITY
        val t = g.nearest(b.first, b.second, Router.DEFAULT_SNAP_M) { model.usable(g, it) } ?: return Double.POSITIVE_INFINITY
        val piece = GraphPiece(0, 0, 0, g, joinZoom = -1)
        fun part(e: Int, f0: Double, f1: Double): Double {
            val p = abs(f1 - f0)
            if (p == 0.0) return 0.0
            if (!model.allows(g, e, f1 > f0)) return Double.POSITIVE_INFINITY
            return g.edgeLength[e] * p * model.weight(g, e)
        }
        var best = if (s.edge == t.edge) part(s.edge, s.fraction, t.fraction) else Double.POSITIVE_INFINITY
        val dist = DoubleArray(2 * g.edgeCount) { Double.POSITIVE_INFINITY }
        val done = BooleanArray(2 * g.edgeCount)
        val heap = MinHeap()
        fun offer(st: Int, c: Double) { if (c < dist[st]) { dist[st] = c; heap.push(c, st) } }
        offer(2 * s.edge, part(s.edge, s.fraction, 1.0))
        offer(2 * s.edge + 1, part(s.edge, s.fraction, 0.0))
        while (heap.size > 0) {
            val st = heap.pop()
            if (done[st]) continue
            done[st] = true
            val c = dist[st]
            if (c >= best) break
            val e = st shr 1
            val fwd = st and 1 == 0
            val v = if (fwd) g.edgeTo[e] else g.edgeFrom[e]
            val j = Junction(listOf(piece), intArrayOf(v))
            if (v == g.edgeFrom[t.edge]) best = minOf(best, c + turns.cost(piece, e, fwd, j, piece, t.edge, true) + part(t.edge, 0.0, t.fraction))
            if (v == g.edgeTo[t.edge]) best = minOf(best, c + turns.cost(piece, e, fwd, j, piece, t.edge, false) + part(t.edge, 1.0, t.fraction))
            for (i in g.adjStart[v] until g.adjStart[v + 1]) {
                val ad = g.adjEdge[i]
                val e2 = ad shr 1
                val atFrom = ad and 1 == 0
                val w = part(e2, if (atFrom) 0.0 else 1.0, if (atFrom) 1.0 else 0.0)
                if (w == Double.POSITIVE_INFINITY) continue
                offer(2 * e2 + if (atFrom) 0 else 1, c + turns.cost(piece, e, fwd, j, piece, e2, atFrom) + w)
            }
        }
        return best
    }

    @Test
    fun theBidirectionalSearchEqualsAPlainEdgeDijkstraOnRandomGraphs() {
        var routes = 0
        var unreachable = 0
        var turned = 0
        for (seed in 1..25) {
            val rnd = Random(seed)
            val g = randomGraph(rnd)
            val turns = TurnCost(RANDOM)
            val r = TileRouter(WholeGraph(g), turns = turns)
            repeat(12) {
                val a = rnd.nextDouble(0.0, 0.01) to rnd.nextDouble(0.0, 0.01)
                val b = rnd.nextDouble(0.0, 0.01) to rnd.nextDouble(0.0, 0.01)
                val want = reference(g, turns, a, b)
                val got = r.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
                if (want == Double.POSITIVE_INFINITY) {
                    assertIs<RouteResult.Failed>(got, "seed $seed $a -> $b: the reference found none")
                    unreachable++
                } else {
                    val p = found(got)
                    assertEquals(want, p.cost, 1e-6 * max(1.0, want), "seed $seed $a -> $b")
                    routes++
                    if (p.cost > reference(g, TurnCost(TurnTable.ZERO), a, b) + 1.0) turned++
                }
            }
        }
        assertTrue(routes > 200 && turned > 100, "routes $routes, of which $turned paid for turns; unreachable $unreachable")
    }


    // ---- The overlay against the full search on seeded random tiled graphs ----

    /**
     * Roads over four z11 graph tiles in a row near the equator (each about 0.176 degrees wide): a jittered lattice every
     * 0.03 degrees, all tertiary or secondary (so every border crossing is an overlay border node), with one-ways and
     * gaps. Most crossings are mid-road cuts (two roads); on one row at each border a side road meets the crossing road
     * exactly on the tile border, making the border node a junction ([junctions] lists their places).
     */
    private class TiledRoads(val g: RoutingGraph, val junctions: List<Pair<Int, Int>>)

    private fun randomTiled(rnd: Random): TiledRoads {
        val tileDeg = 360.0 / (1 shl GraphTiler.ZOOM)
        val borders = (1..3).map { it * tileDeg }
        val cols = 24
        val rows = 5
        val lat = Array(rows) { DoubleArray(cols) }
        val lon = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) for (j in 0 until cols) {
            lat[i][j] = 0.015 + i * 0.03 + rnd.nextDouble(-0.006, 0.006)
            var x = 0.015 + j * 0.03 + rnd.nextDouble(-0.006, 0.006)
            for (bx in borders) if (abs(x - bx) < 0.002) x = bx + if (x < bx) -0.002 else 0.002 // no lattice node near a border
            lon[i][j] = x
        }
        fun attrs(): WayAttrs {
            val flags = when (rnd.nextInt(10)) { 0 -> WayAttrs.ONEWAY; 1 -> WayAttrs.ONEWAY_REVERSE; else -> 0 }
            return WayAttrs(if (rnd.nextBoolean()) WayClass.TERTIARY else WayClass.SECONDARY, Surface.PAVED, flags = flags)
        }
        val lines = ArrayList<WayLine>()
        val junctions = ArrayList<Pair<Int, Int>>()
        for (i in 0 until rows) for (j in 0 until cols) {
            if (j + 1 < cols) {
                val bx = borders.firstOrNull { it > lon[i][j] && it < lon[i][j + 1] }
                if (bx != null && (i == 2 && bx == borders[1] || i == 4 && bx == borders[0] || i == 0 && bx == borders[2])) {
                    // A side road meets this road exactly on the border: a border junction.
                    val bxE6 = kotlin.math.round(bx * 1e6) / 1e6
                    val t = (bxE6 - lon[i][j]) / (lon[i][j + 1] - lon[i][j])
                    val jLat = kotlin.math.round((lat[i][j] + t * (lat[i][j + 1] - lat[i][j])) * 1e6) / 1e6
                    val a = WayAttrs(WayClass.TERTIARY, Surface.PAVED)
                    lines += WayLine.of(a, lat[i][j] to lon[i][j], jLat to bxE6)
                    lines += WayLine.of(a, jLat to bxE6, lat[i][j + 1] to lon[i][j + 1])
                    val k = if (i + 1 < rows) i + 1 else i - 1
                    lines += WayLine.of(a, jLat to bxE6, lat[k][j] to lon[k][j])
                    junctions += kotlin.math.round(bxE6 * 1e6).toInt() to kotlin.math.round(jLat * 1e6).toInt()
                } else if (bx != null || rnd.nextInt(10) < 9) {
                    lines += WayLine.of(attrs(), lat[i][j] to lon[i][j], lat[i][j + 1] to lon[i][j + 1])
                }
            }
            if (i + 1 < rows && rnd.nextInt(10) < 9) lines += WayLine.of(attrs(), lat[i][j] to lon[i][j], lat[i + 1][j] to lon[i + 1][j])
        }
        return TiledRoads(RoutingGraphBuilder().build(lines, source), junctions)
    }

    @Test
    fun anOverlayRouteCostsWhatTheFullSearchCostsOnRandomTiledGraphs() {
        var exact = 0
        var residual = 0
        var throughOverlay = 0
        val maxTurn = sec(OVERLAY_MAX_S)
        for (seed in 1..6) {
            val rnd = Random(1000 + seed)
            val roads = randomTiled(rnd)
            val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(roads.g))))
            // Every border junction is on a tile border and is a node of both tiles.
            for ((lon, lat) in roads.junctions) {
                assertTrue(tg.tilesIn(lon / 1e6 - 1e-5, lat / 1e6, lon / 1e6 + 1e-5, lat / 1e6).count { (x, y) -> tg.pieces(x, y).any { it.nodesAt(lon, lat).isNotEmpty() } } == 2, "junction $lon,$lat on a border")
            }
            val turns = TurnCost(OVERLAY)
            val full = TileRouter(tg, turns = turns)
            val fast = TileRouter(tg, DriveOverlay(turns = turns), turns = turns, minDistanceM = 20_000.0)
            repeat(10) {
                val a = rnd.nextDouble(0.02, 0.14) to rnd.nextDouble(0.01, 0.06)
                val b = rnd.nextDouble(0.02, 0.14) to rnd.nextDouble(0.64, 0.69)
                val (from, to) = if (rnd.nextBoolean()) a to b else b to a
                val fr = full.route(from.first, from.second, to.first, to.second, TravelMode.DRIVE, maxSnapM = 3000.0)
                val or = fast.route(from.first, from.second, to.first, to.second, TravelMode.DRIVE, maxSnapM = 3000.0)
                if (fr is RouteResult.Failed) { assertIs<RouteResult.Failed>(or, "seed $seed: the full search found none"); return@repeat } // a one-way trap
                val f = found(fr)
                val o = found(or)
                if (o.memory["overlayTiles"] == null) return@repeat // fell back to the full search
                throughOverlay++
                fun passes(p: PlannedRoute) = roads.junctions.count { (lon, lat) -> p.route.points.any { abs(it.lon * 1e6 - lon) < 1.5 && abs(it.lat * 1e6 - lat) < 1.5 } }
                val count = passes(f) + passes(o)
                val label = "seed $seed $from -> $to: overlay ${o.cost}, full ${f.cost}, border junctions passed $count"
                if (count == 0) {
                    assertEquals(f.cost, o.cost, 1e-6 * f.cost, label)
                    assertEquals(f.distanceM, o.distanceM, 0.5, label)
                    exact++
                } else {
                    assertTrue(abs(f.cost - o.cost) <= count * maxTurn + 1e-6, label)
                    residual++
                }
            }
        }
        assertTrue(throughOverlay >= 30 && exact >= 10 && residual >= 3, "through the overlay $throughOverlay, exact $exact, with the residual $residual")
    }

    // ---- T282: the production table (ADR 0062 Decision 4) ----

    private fun drive(g: RoutingGraph, a: Pair<Double, Double>, b: Pair<Double, Double>) = drive(TileRouter(WholeGraph(g)), a, b)

    private fun PlannedRoute.passes(lat: Double, lon: Double) = route.points.any { abs(it.lat - lat) < 1e-7 && abs(it.lon - lon) < 1e-7 }

    @Test
    fun theTableFollowsTheAngleTheSideAndTheClass() {
        assertEquals(0.0, DriveTurns.angleSeconds(29.9, true))
        assertEquals(0.75, DriveTurns.angleSeconds(45.0, false), 1e-9)
        assertEquals(3.0, DriveTurns.angleSeconds(90.0, false), 1e-9)
        assertEquals(4.5, DriveTurns.angleSeconds(120.0, false), 1e-9)
        assertEquals(6.0, DriveTurns.angleSeconds(170.0, false), 1e-9)
        assertEquals(7.0, DriveTurns.angleSeconds(90.0, true), 1e-9)
        assertEquals(9.0, DriveTurns.angleSeconds(150.0, true), 1e-9)
        // A crossroads: Valley Road (primary) south to north, a service road east, a minor road west.
        val valley = WayAttrs(WayClass.PRIMARY, Surface.PAVED, name = "Valley Road")
        val g = graph(
            WayLine.of(valley, -0.002 to 0.0, 0.0 to 0.0, 0.002 to 0.0),
            WayLine.of(WayAttrs(WayClass.SERVICE, Surface.PAVED), 0.0 to 0.0, 0.0 to 0.002), WayLine.of(minor, 0.0 to 0.0, 0.0 to -0.002),
        )
        val p = GraphPiece(0, 0, 0, g, joinZoom = -1)
        val x = (0 until g.nodeCount).first { g.nodeLat[it] == 0 && g.nodeLon[it] == 0 }
        val j = Junction(listOf(p), intArrayOf(x))
        assertEquals(4, j.roads.size)
        fun road(lat: Double, lon: Double): JunctionRoad {
            val e = (0 until g.edgeCount).first { e ->
                val o = if (g.edgeFrom[e] == x) g.edgeTo[e] else g.edgeFrom[e]
                abs(g.nodeLat[o] / 1e6 - lat) < 1e-7 && abs(g.nodeLon[o] / 1e6 - lon) < 1e-7
            }
            return JunctionRoad(p, e, g.edgeFrom[e] == x)
        }
        val south = road(-0.002, 0.0)
        val north = road(0.002, 0.0)
        val east = road(0.0, 0.002)
        val west = road(0.0, -0.002)
        fun sec(from: JunctionRoad, to: JunctionRoad) = DriveTurns.seconds(Turn(from, to, j, DrivingSide.RIGHT))
        assertEquals(0.0, sec(south, north), 1e-9, "straight on")
        assertEquals(3.0 + 3.0 + 2.0, sec(south, east), 0.05, "right onto the service road: 3 s, 4 class steps capped at 3, 2 s rough")
        assertEquals(7.0 + 3.0, sec(south, west), 0.05, "left onto the minor road: 7 s across traffic, 3 class steps")
        assertEquals(3.0, sec(west, south), 0.05, "right onto the primary road: nothing for joining a higher class")
        assertEquals(0.0, sec(east, west), 1e-9, "straight across")
        assertEquals(DriveTurns.REVERSAL_S, sec(south, south), 1e-9, "a reversal")
        // Where traffic keeps left the sides swap.
        assertEquals(3.0 + 3.0, DriveTurns.seconds(Turn(south, west, j, DrivingSide.LEFT)), 0.05)
    }

    @Test
    fun theOwnersShapeKeepsToTheMainRoad() {
        // The owner, 2026-11-14: a same-speed side road reached by two left turns, a few metres shorter, beat the main
        // road. Main Road runs north from A through J1, bends left at C1 (two roads only: free) and runs west through C2.
        // Side Road leaves it at J1 by a left turn and rejoins it at C2 by another, 2.2 m shorter.
        val main = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Main Road")
        val side = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Side Road")
        val g = graph(
            WayLine.of(main, -0.002 to 0.0, 0.0 to 0.0, 0.002 to 0.0, 0.002 to -0.002, 0.002 to -0.004),
            WayLine.of(side, 0.0 to 0.0, 0.00002 to -0.002, 0.002 to -0.002),
        )
        val a = -0.001 to 0.0
        val b = 0.002 to -0.003
        val zero = drive(router(g, TurnTable.ZERO), a, b)
        assertTrue(zero.passes(0.00002, -0.002), "at zero cost the side road: ${zero.route.points}")
        val table = drive(g, a, b)
        assertTrue(table.passes(0.002, 0.0), "with the table the main road: ${table.route.points}")
        assertTrue(table.distanceM > zero.distanceM + 2.0, "${table.distanceM} against ${zero.distanceM}")
        assertEquals(0, table.turns)
        assertEquals(0.0, table.turnCostS!!, 1e-9)
        // The side road pays a left turn at each end, 7 s each: the drive search's route there costs the zero route plus 14 s.
        val forced = drive(router(g, DriveTurns), a, 0.001 to -0.002)
        assertEquals(1, forced.turns)
        assertEquals(DriveTurns.ACROSS_90_S, forced.turnCostS!!, 0.1)
        assertTrue(zero.cost + CostModel(TravelMode.DRIVE).secondsCost(2 * DriveTurns.ACROSS_90_S - 0.5) > table.cost, "${zero.cost} ${table.cost}")
        // Blind to turns (Quicker planning): the side road, and no turn fields.
        val quick = drive(TileRouter(WholeGraph(g), driveTurns = false), a, b)
        assertEquals(zero.distanceM, quick.distanceM, 0.01)
        assertEquals(null, quick.turnCostS)
        assertTrue("turnCostS" !in quick.json(), "${quick.json().keys}")
        assertTrue("turnCostS" in table.json() && "turns" in table.json())
    }

    @Test
    fun aRoadThatBendsAtASpurStaysFree() {
        // Pass Road heads east to X and bends north there; an unnamed minor spur goes straight on east. The bend is the
        // through exit: free both ways. Where the spur is Pass Road too (two exits match), the bend is a priced left.
        val pass = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Pass Road")
        fun g(spur: WayAttrs) = graph(WayLine.of(pass, 0.0 to -0.002, 0.0 to 0.0, 0.002 to 0.0), WayLine.of(spur, 0.0 to 0.0, 0.0 to 0.001))
        val up = drive(g(minor), 0.0 to -0.001, 0.001 to 0.0)
        assertEquals(0.0, up.turnCostS!!, 1e-9)
        assertEquals(0, up.turns)
        assertEquals(0.0, drive(g(minor), 0.001 to 0.0, 0.0 to -0.001).turnCostS!!, 1e-9)
        val ambiguous = drive(g(pass), 0.0 to -0.001, 0.001 to 0.0)
        assertEquals(1, ambiguous.turns)
        assertEquals(DriveTurns.ACROSS_90_S, ambiguous.turnCostS!!, 0.1)
        // The planned time includes the seconds.
        assertEquals(up.timeS + DriveTurns.ACROSS_90_S, ambiguous.timeS, 0.2)
    }

    @Test
    fun theRoadThroughAHairpinIsCheaperThanTheLotInside() {
        // T108's case (Sunrise Point): a secondary road climbs to a hairpin apex A, where a viewpoint spur leaves (three
        // roads, so the hairpin itself is the through exit), and comes back down; a service lot joins the two legs below.
        // At zero cost the lot is about 9 s quicker; with the table it pays 11 s of turns (in: a right, 3 class steps,
        // onto a rough road; out: a right) and the road through the apex, free, wins. Without the through exit the
        // 163 degree hairpin would pay 6 s and the lot would still win.
        val road = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Sunrise Road")
        val g = graph(
            WayLine.of(road, -0.003 to 0.0, -0.002 to 0.0, 0.0 to 0.0003, -0.002 to 0.0006, -0.003 to 0.0006),
            WayLine.of(WayAttrs(WayClass.SERVICE, Surface.PAVED), -0.002 to 0.0, -0.002 to 0.0006),
            WayLine.of(WayAttrs(WayClass.SERVICE, Surface.PAVED, name = "Viewpoint"), 0.0 to 0.0003, 0.0005 to 0.0003),
        )
        val a = -0.0028 to 0.0
        val b = -0.0028 to 0.0006
        val zero = drive(router(g, TurnTable.ZERO), a, b)
        assertTrue(!zero.passes(0.0, 0.0003), "at zero cost through the lot: ${zero.route.points}")
        val table = drive(g, a, b)
        assertTrue(table.passes(0.0, 0.0003), "with the table through the apex: ${table.route.points}")
        assertEquals(0.0, table.turnCostS!!, 1e-9)
        assertTrue(table.timeS < zero.timeS + 11.0, "${table.timeS} against the lot's ${zero.timeS + 11.0}")
        assertTrue(table.timeS + 6.0 > zero.timeS + 11.0, "the through exit decides it: ${table.timeS} + 6 against ${zero.timeS + 11.0}")
    }

    @Test
    fun aRoundaboutIsPricedByItsAnglesAlone() {
        // A roundabout the graph does not mark: four one-way arcs, anticlockwise (right-hand traffic), radius about 33 m,
        // named "Circle"; four minor arms. Entering from the south, each exit pays a right turn in and a right turn out;
        // the ring itself is free (the through exit at each arm).
        val ring = WayAttrs(WayClass.MINOR, Surface.PAVED, flags = WayAttrs.ONEWAY, name = "Circle")
        val r = 0.0003
        fun arc(a0: Double): WayLine {
            val pts = (0..6).map { k -> val t = (a0 + 90.0 * k / 6) * kotlin.math.PI / 180; r * kotlin.math.sin(t) to r * kotlin.math.cos(t) }
            return WayLine.of(ring, *pts.toTypedArray())
        }
        // Angles anticlockwise from east: east (0) -> north (90) -> west (180) -> south (270) -> east.
        val g = graph(
            arc(0.0), arc(90.0), arc(180.0), arc(270.0),
            WayLine.of(minor, -r to 0.0, -0.002 to 0.0), WayLine.of(minor, r to 0.0, 0.002 to 0.0),
            WayLine.of(minor, 0.0 to r, 0.0 to 0.002), WayLine.of(minor, 0.0 to -r, 0.0 to -0.002),
        )
        val south = -0.0015 to 0.0
        val costs = listOf(0.0 to 0.0015, 0.0015 to 0.0, 0.0 to -0.0015).map { b -> drive(g, south, b) }
        // T282's report states these: two rights of about 67 degrees, 1.9 s each, 3.75 s whichever exit.
        for ((i, p) in costs.withIndex()) {
            assertEquals(2, p.turns, "exit ${i + 1}: ${p.turnCostS}")
            assertEquals(ROUNDABOUT_S, p.turnCostS!!, 0.1, "exit ${i + 1}")
        }
    }

    @Test
    fun aStartAtANodeAtZeroCostIsTheNodeSearchsRoute() {
        // T281's fix (a start at a node seeds a zero-length state in both directions, so the backward search does not skip
        // states it may not travel), at zero cost: every node of a small grid with one-way streets as the start, to
        // every other node, on both searches ([BothSearches] fails on any difference).
        val oneway = minor.copy(flags = WayAttrs.ONEWAY)
        val g = graph(
            WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002), WayLine.of(minor, 0.0 to 0.002, 0.0 to 0.004),
            WayLine.of(oneway, 0.002 to 0.004, 0.002 to 0.002), WayLine.of(minor, 0.002 to 0.002, 0.002 to 0.0),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0), WayLine.of(oneway, 0.0 to 0.002, 0.002 to 0.002),
            WayLine.of(minor, 0.0 to 0.004, 0.002 to 0.004), WayLine.of(oneway, 0.002 to 0.0, 0.003 to 0.0),
        )
        val both = bothSearches(g)
        val nodes = (0 until g.nodeCount).map { g.nodeLat[it] / 1e6 to g.nodeLon[it] / 1e6 }
        var routes = 0
        for (a in nodes) for (b in nodes) {
            if (a == b) continue
            both.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
            routes++
        }
        assertTrue(routes >= 30, "$routes routes")
    }

    @Test
    fun aDrivePreferenceIsReadWithUnknownKeysAsTheDefault() {
        assertEquals(DrivePreference.TIME, DrivePreference.DEFAULT)
        assertEquals(DrivePreference.QUICK, DrivePreference.of(" Quick "))
        assertEquals(null, DrivePreference.of("fuel"))
        assertEquals(DrivePreference.TIME, DrivePreference.read("fuel"))
        assertEquals(DrivePreference.TIME, DrivePreference.read(null))
        assertTrue(DrivePreference.TIME.turns && !DrivePreference.QUICK.turns)
    }

    companion object {
        /** T282: the turn seconds of any exit of [aRoundaboutIsPricedByItsAnglesAlone]'s roundabout. */
        const val ROUNDABOUT_S = 3.75

        /** Test-only: a turn across traffic of 30 degrees or more 60 s, a reversal 100 s, anything else free. */
        val LEFT = TurnTable { t ->
            when {
                t.reversal -> 100.0
                t.acrossTraffic && abs(t.angleDeg) >= 30 -> 60.0
                else -> 0.0
            }
        }

        /** Test-only: 50 s at a junction of three or more distinct roads, nothing elsewhere. */
        val JUNCTIONS = TurnTable { t -> if (t.roads >= 3) 50.0 else 0.0 }

        private fun mix(vararg v: Int): Int {
            var h = 0x2545F491
            for (x in v) { h = (h xor x) * 0x5bd1e995; h = h xor (h ushr 15) }
            return h and 0x7FFFFFFF
        }

        /** Test-only: 0 to 25 s by a hash of the two edges and directions, plus 30 s for a reversal. */
        val RANDOM = TurnTable { t ->
            (mix(t.inRoad.edge, if (t.inRoad.atFrom) 1 else 0, t.outRoad.edge, if (t.outRoad.atFrom) 1 else 0) % 26).toDouble() + if (t.reversal) 30.0 else 0.0
        }

        /** Test-only: an arbitrary cost of the angle and side only (so a mirror image with the other side costs the same). */
        val RANDOM_SIDED = TurnTable { t ->
            val a = abs(t.angleDeg)
            (if (t.acrossTraffic) 7.0 else 2.0) * a / 90 + (if (t.reversal) 25.0 else 0.0) + (t.roads - 2).coerceAtLeast(0) * 1.5
        }

        /** The overlay test's ceiling on one turn, seconds ([OVERLAY]). */
        const val OVERLAY_MAX_S = 60.0

        /**
         * Test-only, for the overlay: free where only two roads meet (a mid-road tile cut) unless it is a reversal (60 s),
         * else 0 to 20 s by a hash of the tile and the two edges.
         */
        val OVERLAY = TurnTable { t ->
            when {
                t.reversal -> OVERLAY_MAX_S
                t.roads <= 2 -> 0.0
                else -> (mix(t.inRoad.piece.x, t.inRoad.piece.y, t.inRoad.edge, if (t.inRoad.atFrom) 1 else 0, t.outRoad.piece.x, t.outRoad.edge, if (t.outRoad.atFrom) 1 else 0) % 21).toDouble()
            }
        }
    }
}

/**
 * T281: plans every drive twice, on the drive search at zero turn cost and on the node search ([TileRouter.driveTurns]
 * off), and fails unless both find a route of the same cost or fail for the same reason; answers with the drive
 * search's result. The drive tests route through it, so every drive fixture checks the two searches agree.
 */
internal class BothSearches(private val turns: Router, private val nodes: Router) : Router by turns {
    override fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
    ): RouteResult {
        val a = turns.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
        if (mode != TravelMode.DRIVE) return a
        val b = nodes.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, UuidGenerator())
        val label = "($fromLat, $fromLon) -> ($toLat, $toLon)"
        when {
            a is RouteResult.Found && b is RouteResult.Found -> {
                assertEquals(b.plan.cost, a.plan.cost, 1e-6 * max(1.0, b.plan.cost), "$label: the drive search at zero turn cost against the node search")
                assertEquals(b.plan.distanceM, a.plan.distanceM, 0.5, "$label distance")
            }
            a is RouteResult.Failed && b is RouteResult.Failed -> assertEquals(b.reason, a.reason, "$label: ${a.message} / ${b.message}")
            else -> fail("$label: drive search $a, node search $b")
        }
        return a
    }
}

/** T281: [BothSearches] over one in-memory graph. */
internal fun bothSearches(g: RoutingGraph): Router = bothSearches(WholeGraph(g))

/** T281: [BothSearches] over [tiles] (with [overlay], shared by both). */
internal fun bothSearches(tiles: GraphPieces, overlay: DriveOverlay? = null): Router =
    BothSearches(TileRouter(tiles, overlay, turns = TurnCost(TurnTable.ZERO)), TileRouter(tiles, overlay, driveTurns = false))
