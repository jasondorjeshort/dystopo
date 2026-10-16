package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How the route is travelled; picks the [CostModel]. Hiking (and biking) first, [DRIVE] (T73) a full second mode
 * (ADR 0032): what drive does and does not do is said once, in [Router.DRIVE_NOTICE]. [CROW] (T108): a straight line
 * between the points ([Crow]), no graph and no trail; its line under the mode picker is [CROW_NOTICE].
 */
enum class TravelMode(val label: String) {
    HIKE("hike"), BIKE("bike"), DRIVE("drive"), CROW("crow");

    companion object {
        fun of(label: String?): TravelMode? = entries.firstOrNull { it.label == label?.lowercase() }

        /** The modes the pickers offer, in their order (the route card, the trip card, the server's message). */
        val labels: String get() = entries.dropLast(1).joinToString(", ") { it.label } + " or " + entries.last().label

        /**
         * T108 (ADR 0033 Decision 3): how a stored mode is named on a card: a known mode by its label; a mode this
         * build does not know (a newer Dystopo's, synced here) plainly as itself, marked as unknown, never as hike.
         */
        fun labelOf(mode: String?): String = of(mode)?.label ?: "${mode?.trim()?.ifEmpty { null } ?: "no mode"} (unknown mode)"

        /** T108: true for a mode this build can plan and edit; false for a newer Dystopo's (shown and followed, not edited). */
        fun known(mode: String?): Boolean = of(mode) != null

        /** T108: why a trip in a mode this build does not know is not edited here (it is shown and can be followed). */
        fun unknownModeMessage(mode: String?): String =
            "this route's mode \"${mode ?: ""}\" is from a newer Dystopo: update this app to edit it (it can still be shown and followed)"

        /** T108: the one line under the mode picker when crow is chosen. */
        const val CROW_NOTICE = "A crow route is a straight line between the points, not a path to walk."
    }
}

/**
 * Edge cost for one [mode], in "equivalent flat metres":
 * `length x class weight x surface weight x difficulty weight + slope penalty`.
 *
 * Hike: paths and footways 1.0, tracks, bridleways and steps 1.1, cycleways 1.2, service roads 1.4,
 * minor roads 1.6, tertiary 2.5, secondary 4, primary 6; surface does not matter; SAC grades from
 * demanding mountain hiking up cost 1.2, 1.6, 2.5, 4; informal 1.3 and poorly visible trails 1.5.
 * Slope: each metre of ascent costs 8 m (Naismith: 600 m of climb is an hour, like 5 km on the flat),
 * plus extra on average grades over 25 % up and 15 % down.
 *
 * Bike: cycleways 0.9, minor roads 1.0, tracks and service roads 1.1, tertiary 1.2, secondary 1.6,
 * primary 2.5, paths 1.6, bridleways 1.8, footways 2.0, steps 25; unpaved 1.3; SAC 2 and up 1.5, 3 up 3,
 * 4 up 10. Slope: 12 m per metre of ascent, plus extra over 8 % up and 12 % down.
 *
 * Private access costs 5x in both modes (passable, but only when nothing else goes). Trunks and
 * motorways (in the graph since T73) are closed to both. Hike and bike ignore one-ways and motor access.
 *
 * Drive (T73): travel time, as metres at [DRIVE_REF_KMH]: `length x DRIVE_REF_KMH / speed`, with the
 * edge's tagged `maxspeed` (clamped to 5..[DRIVE_REF_KMH]) or the class speed ([driveSpeedKmh]:
 * motorway 105, trunk 85, primary 75, secondary 65, tertiary 55, minor 40, service 15 (T108; 20 before), track 15 km/h);
 * unpaved 1.25x. Paths, footways, cycleways, bridleways and steps are closed, so are lines whose
 * motor access is no or private; one-ways only the way they go ([allows]); `destination` roads only at
 * a route's ends (the router's destination zones). No slope penalty.
 */
class CostModel(val mode: TravelMode) {
    fun classWeight(c: WayClass): Double = when (mode) {
        TravelMode.DRIVE -> driveSpeedKmh(c).let { if (it > 0) DRIVE_REF_KMH / it else Double.POSITIVE_INFINITY }
        TravelMode.HIKE, TravelMode.CROW -> when (c) { // T108: crow never searches the graph; hike's weights if asked
            WayClass.PATH, WayClass.FOOTWAY -> 1.0
            WayClass.TRACK, WayClass.BRIDLEWAY, WayClass.STEPS -> 1.1
            WayClass.CYCLEWAY -> 1.2
            WayClass.SERVICE -> 1.4
            WayClass.MINOR -> 1.6
            WayClass.TERTIARY -> 2.5
            WayClass.SECONDARY -> 4.0
            WayClass.PRIMARY -> 6.0
            WayClass.TRUNK, WayClass.MOTORWAY -> Double.POSITIVE_INFINITY
        }
        TravelMode.BIKE -> when (c) {
            WayClass.CYCLEWAY -> 0.9
            WayClass.MINOR -> 1.0
            WayClass.TRACK, WayClass.SERVICE -> 1.1
            WayClass.TERTIARY -> 1.2
            WayClass.SECONDARY -> 1.6
            WayClass.PRIMARY -> 2.5
            WayClass.PATH -> 1.6
            WayClass.BRIDLEWAY -> 1.8
            WayClass.FOOTWAY -> 2.0
            WayClass.STEPS -> 25.0
            WayClass.TRUNK, WayClass.MOTORWAY -> Double.POSITIVE_INFINITY
        }
    }

    fun surfaceWeight(s: Surface): Double = when {
        s != Surface.UNPAVED -> 1.0
        mode == TravelMode.BIKE -> 1.3
        mode == TravelMode.DRIVE -> 1.25
        else -> 1.0
    }

    fun sacWeight(sac: Int): Double = when (mode) {
        TravelMode.DRIVE -> 1.0
        TravelMode.HIKE, TravelMode.CROW -> when {
            sac <= 2 -> 1.0
            sac == 3 -> 1.2
            sac == 4 -> 1.6
            sac == 5 -> 2.5
            else -> 4.0
        }
        TravelMode.BIKE -> when {
            sac <= 1 -> 1.0
            sac == 2 -> 1.5
            sac == 3 -> 3.0
            else -> 10.0
        }
    }

    fun flagWeight(flags: Int): Double {
        if (mode == TravelMode.DRIVE) return if (flags and WayAttrs.MOTOR_NO != 0) Double.POSITIVE_INFINITY else 1.0
        var w = 1.0
        if (flags and WayAttrs.PRIVATE != 0) w *= 5.0
        if (flags and WayAttrs.INFORMAL != 0) w *= if (mode == TravelMode.HIKE || mode == TravelMode.CROW) 1.3 else 1.5
        if (flags and WayAttrs.POOR_VISIBILITY != 0) w *= 1.5
        return w
    }

    /** The multiplier on edge [e]'s length; infinite when this mode may not use the edge at all. */
    fun weight(g: RoutingGraph, e: Int): Double {
        val c = if (mode == TravelMode.DRIVE) {
            val cls = g.wayClass(e)
            if (driveSpeedKmh(cls) <= 0) return Double.POSITIVE_INFINITY
            DRIVE_REF_KMH / speedKmh(g, e)
        } else classWeight(g.wayClass(e))
        return c * surfaceWeight(g.surface(e)) * sacWeight(g.sac(e)) * flagWeight(g.flags(e))
    }

    /** Whether this mode may use edge [e] at all (in some direction): a finite [weight]. */
    fun usable(g: RoutingGraph, e: Int): Boolean = !weight(g, e).isInfinite()

    /**
     * Whether edge [e] may be travelled from its `from` node to its `to` node ([forward]) or back: drive
     * mode keeps to one-ways (T73); hiking and cycling go both ways.
     */
    fun allows(g: RoutingGraph, e: Int, forward: Boolean): Boolean {
        if (mode != TravelMode.DRIVE) return true
        val f = g.flags(e)
        return if (forward) f and WayAttrs.ONEWAY_REVERSE == 0 else f and WayAttrs.ONEWAY == 0
    }

    /** T73: the speed drive mode assumes on edge [e], km/h: its tagged limit, else the class speed. */
    fun speedKmh(g: RoutingGraph, e: Int): Double {
        val tagged = g.speedKmh(e)
        return if (tagged > 0) tagged.toDouble().coerceIn(5.0, DRIVE_REF_KMH) else driveSpeedKmh(g.wayClass(e))
    }

    /** T73: seconds to drive [metres] of edge [e] ([speedKmh]; unpaved slower). */
    fun edgeTimeS(g: RoutingGraph, e: Int, metres: Double): Double =
        metres / (speedKmh(g, e) / 3.6) * surfaceWeight(g.surface(e))

    /** The smallest [weight] any edge can have: the A* heuristic's factor (keeps it admissible). */
    val minWeight: Double get() = if (mode == TravelMode.DRIVE) 1.0 else WayClass.entries.minOf { classWeight(it) }

    /** Penalty in equivalent metres for climbing [ascentM] and descending [descentM] over [lengthM]. */
    fun slopePenalty(lengthM: Double, ascentM: Double, descentM: Double): Double {
        if (lengthM <= 0) return 0.0
        val up = ascentM / lengthM
        val down = descentM / lengthM
        return when (mode) {
            TravelMode.HIKE, TravelMode.CROW -> 8 * ascentM + ascentM * max(0.0, up - 0.25) * 20 + descentM * max(0.0, down - 0.15) * 10
            TravelMode.BIKE -> 12 * ascentM + ascentM * max(0.0, up - 0.08) * 80 + descentM * max(0.0, down - 0.12) * 30
            TravelMode.DRIVE -> 0.0
        }
    }

    /**
     * Moving time in seconds from distance and ascent alone (the trip summary's model, [RouteSummary]).
     * Hike: Naismith's rule, 5 km/h plus an hour per 600 m of ascent. Bike: 16 km/h plus an hour per
     * 500 m of ascent (a touring pace; descents are not credited). Drive: [DRIVE_SUMMARY_KMH], used only
     * for a drive leg without a router time (straight or imported; T89): a routed drive leg keeps the
     * router's own sum of each road's time ([edgeTimeS]) and the summary adds those up. Crow (T108): Naismith over the
     * straight line, a lower bound for anyone walking it (a crow route is not a path to walk).
     */
    fun timeS(distanceM: Double, ascentM: Double): Double = when (mode) {
        TravelMode.HIKE, TravelMode.CROW -> distanceM / 5000.0 * 3600 + ascentM / 600.0 * 3600
        TravelMode.BIKE -> distanceM / 16000.0 * 3600 + ascentM / 500.0 * 3600
        TravelMode.DRIVE -> distanceM / (DRIVE_SUMMARY_KMH * 1000.0) * 3600
    }

    companion object {
        /** Drive costs are metres at this speed; also the largest speed drive mode assumes. */
        const val DRIVE_REF_KMH = 130.0

        /** The average speed a drive route's summary assumes on legs the router did not plan (straight or imported; T89). */
        const val DRIVE_SUMMARY_KMH = 45.0

        /** T73: the speed drive mode assumes on an untagged line of class [c], km/h; 0 when cars may not use it. */
        fun driveSpeedKmh(c: WayClass): Double = when (c) {
            WayClass.MOTORWAY -> 105.0
            WayClass.TRUNK -> 85.0
            WayClass.PRIMARY -> 75.0
            WayClass.SECONDARY -> 65.0
            WayClass.TERTIARY -> 55.0
            WayClass.MINOR -> 40.0
            // T108: 15, not 20: at 20 a parking lot inside a hairpin (Sunrise Point on Sunrise Park Road: 73 m of lot
            // against 205 m of road, both 13 s) was as fast as the road and the drive cut through it; parking aisles and
            // driveways are slower than 20 (the packs do not say which service road is one).
            WayClass.SERVICE -> 15.0
            WayClass.TRACK -> 15.0
            WayClass.PATH, WayClass.FOOTWAY, WayClass.CYCLEWAY, WayClass.BRIDLEWAY, WayClass.STEPS -> 0.0
        }
    }
}

/** Why no route was found. */
enum class RouteFailure(val code: String) {
    EMPTY_GRAPH("empty_graph"),
    START_OFF_NETWORK("start_off_network"),
    END_OFF_NETWORK("end_off_network"),
    NOT_CONNECTED("not_connected"),
    /** T73: drive mode on a pack built before its road data (one-ways, motor access, speed limits). */
    DRIVE_UNSUPPORTED("drive_unsupported"),
    /** T77: the server a [RemoteRouter] asked did not answer or could not plan. */
    REMOTE_UNAVAILABLE("remote_unavailable"),
    /** T89: the search settled [TileRouter.maxSettled] nodes without finding a route (a budget, not proof there is none). */
    SEARCH_LIMIT("search_limit"),
}

/** A route found by [Router]: the [Route] (T24 model) and its numbers. */
data class PlannedRoute(
    val route: Route,
    val mode: TravelMode,
    /** Along the route's points (as T24's stats measure it). */
    val distanceM: Double,
    /** From the graph's DEM samples along each edge; null when the graph has no elevation. */
    val ascentM: Double?,
    val descentM: Double?,
    /** [CostModel.timeS]: Naismith for hiking; for driving the sum of each road's time ([CostModel.edgeTimeS]). */
    val timeS: Double,
    /** T24's profile sampling ([TrackStats.profile]) of the route's points. */
    val profile: List<ProfileSample>,
    val cost: Double,
    val edges: Int,
    val start: EdgeSnap,
    val end: EdgeSnap,
    /** Nodes A* settled. */
    val expanded: Int,
    val metersByClass: Map<WayClass, Double>,
    /**
     * T49: the named ways along the route in travel order, consecutive edges of one name merged, with the
     * metres on each (stretches under [Router.MIN_VIA_M] left out; T87: on hike and bike routes, roads under
     * [Router.MIN_VIA_ROAD_M]): the trails a route follows.
     */
    val via: List<Pair<String, Double>> = emptyList(),
    /**
     * T76: what the search held: `tiles` (graph tiles it read), `graphBytes` ([RoutingGraph.approxBytes]
     * of those tiles), `searchBytes` and `searchNodes` (its own per-node arrays).
     */
    val memory: Map<String, Any?> = emptyMap(),
    /** T102: the turn directions along [route]'s points ([Directions]); empty when the router has no graph to read them from. */
    val instructions: List<Instruction> = emptyList(),
) {
    fun json(profileSamples: Boolean = true): Map<String, Any?> = linkedMapOf(
        "id" to route.id, "name" to route.name, "mode" to mode.label,
        "distanceM" to r1(distanceM), "ascentM" to ascentM?.let(::r1), "descentM" to descentM?.let(::r1),
        "timeS" to timeS.roundToLong(), "time" to hm(timeS),
        "points" to route.points.size, "edges" to edges, "expanded" to expanded, "cost" to r1(cost),
        "start" to snapJson(start), "end" to snapJson(end),
        "metersByClass" to metersByClass.entries.sortedByDescending { it.value }.associate { it.key.label to r1(it.value) },
        "via" to via.map { (n, m) -> linkedMapOf("name" to n, "distanceM" to r1(m)) }, // T49: trail names
        "bearingDeg" to if (mode == TravelMode.CROW && route.points.size >= 2) route.points.let { Crow.bearingDeg(it.first().lat, it.first().lon, it.last().lat, it.last().lon) } else null, // T108
        "memory" to memory.ifEmpty { null }, // T76
        "instructions" to instructions.map { it.json(mode) }, // T102
        "profile" to if (profileSamples) profile.map { listOf(r1(it.distanceM), it.eleM?.let(::r1)) } else null,
    )

    companion object {
        fun r1(v: Double) = (v * 10).roundToLong() / 10.0
        fun hm(s: Double): String {
            val m = (s / 60).roundToInt()
            return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        }

        fun snapJson(s: EdgeSnap): Map<String, Any?> = linkedMapOf(
            "lat" to (s.lat * 1e6).roundToLong() / 1e6, "lon" to (s.lon * 1e6).roundToLong() / 1e6,
            "distanceM" to r1(s.distanceM), "edge" to s.edge,
        )
    }
}

sealed class RouteResult {
    data class Found(val plan: PlannedRoute) : RouteResult()

    data class Failed(val reason: RouteFailure, val message: String, val details: Map<String, Any?> = emptyMap()) : RouteResult() {
        fun json(): Map<String, Any?> = linkedMapOf("error" to reason.code, "message" to message) + details
    }
}


/**
 * Plans routes (T45; an interface since T76, PLANNING › Routing). [TileRouter] is the offline
 * implementation, A* over graph tiles; a remote router or a hierarchy for long routes (T77) is
 * another implementation of the same two calls. `Router(graph)` routes on one in-memory graph.
 */
interface Router {
    /**
     * A route from ([fromLat], [fromLon]) to ([toLat], [toLon]) in [mode]. Both ends snap onto the
     * nearest line within [maxSnapM]; the route starts and ends at those snapped points, mid-edge.
     */
    fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode = TravelMode.HIKE,
        maxSnapM: Double = DEFAULT_SNAP_M,
        name: String = "Route",
        ids: UuidGenerator = UuidGenerator(),
    ): RouteResult

    /** The nearest point on any line within [maxDistanceM], with the line's name and class; null when none is that close. */
    fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap?

    /** [nearest] among the lines [mode] may use (T73: drive mode only roads open to cars, in graphs with the road data). */
    fun nearest(lat: Double, lon: Double, maxDistanceM: Double, mode: TravelMode): EdgeSnap? = nearest(lat, lon, maxDistanceM)

    /** T102: the graph's junctions, for turn directions along any line on it ([Directions]); null without a graph (a remote router). */
    val junctions: JunctionSource? get() = null

    companion object {
        /** How far a tapped point may be from the nearest trail or road. */
        const val DEFAULT_SNAP_M = 300.0
        /** T89: the default settle budget of one search ([TileRouter.maxSettled]; the statewide 570 km drive in full settled 0.5 to 1.2 million). */
        const val DEFAULT_MAX_SETTLED = 1_500_000
        /** How far an off-network failure looks for the nearest line, to say how far it is. */
        const val FAR_SEARCH_M = 10_000.0
        const val PROFILE_SAMPLES = 100
        /** T49: named stretches shorter than this are left out of [PlannedRoute.via] (a crossing, a junction stub). */
        const val MIN_VIA_M = 30.0
        /**
         * T87: on a hike or bike route a named road (service and up) needs this much to be in [PlannedRoute.via]: a
         * trail that crosses or briefly follows a road (a parking lot access, a bridge on the park road) does not
         * make the road one of the trails the route follows. Drive routes keep [MIN_VIA_M].
         */
        const val MIN_VIA_ROAD_M = 100.0

        /**
         * What drive mode does and does not do, in one sentence and one place (ADR 0032): the route card, the trip card,
         * the browser's trip card (the server's `notice`) and every drive route's description say this.
         */
        const val DRIVE_NOTICE = "Drive routes are planned offline from the roads in the map packs, with their one-way streets " +
            "and speed limits; they do not know turn restrictions, traffic or seasonal closures."

        fun km(m: Double) = (m / 100).roundToLong() / 10.0
    }
}

/** A [TileRouter] over one in-memory graph (no tiles): tests, tools, and the T60 editor's single-graph callers. */
fun Router(graph: RoutingGraph): Router = TileRouter(WholeGraph(graph))

/**
 * Bidirectional A* over graph tiles (T76; T45's unidirectional A* before). Tiles come from [tiles]
 * as the search reaches them, so a route reads only the tiles around it, and boundary nodes join
 * the pieces of neighbouring tiles and of other packs ([GraphPiece.twin]), so a route crosses pack
 * borders.
 *
 * Both searches run on the same reduced edge costs: with h_t(v) the great-circle distance to the end
 * and h_s(v) from the start, both times the cost model's smallest weight, the forward search keys a
 * node by d_f(v) + p(v) and the backward one by d_r(v) - p(v), where p = (h_t - h_s) / 2 (the
 * average potentials of Goldberg and Harrelson, consistent for both). The search stops once the two
 * smallest keys add up to the best start-to-end cost found, so the route is optimal for the
 * [CostModel], like the unidirectional search was. Costs are direction aware (ascent one way is
 * descent the other); the backward search prices each edge in the direction of travel. Ends on
 * separate networks fail before any search when the graph's component summary tells them apart
 * (T89: [RoutingGraph.footComponent]; conclusive within one graph file, and across files when either
 * end's part of the network stays inside its pack); otherwise when either search runs out of nodes,
 * which takes as long as the smaller of the two networks.
 */
class TileRouter(
    val tiles: GraphPieces,
    /** T77: the long-drive overlay; drives of [DriveOverlay.MIN_DISTANCE_M] or more search it between their end areas. */
    val overlay: DriveOverlay? = null,
    /**
     * T89: a search (the overlay's and the full fallback alike) that settles this many nodes stops and fails with
     * [RouteFailure.SEARCH_LIMIT], so an unreachable or very long route costs a bounded amount of time and memory
     * (about 42 bytes of search state per node: 1.5 million nodes is about 63 MB).
     */
    val maxSettled: Int = Router.DEFAULT_MAX_SETTLED,
    /**
     * T102 (from T101): the units of the distances and heights written into route descriptions and failure messages
     * ([UnitFormat]); the phone passes the units preference, the server and the devtool keep metres. The numbers in
     * the results and the failures' details stay metres.
     */
    val units: ContourUnits = ContourUnits.METERS,
) : Router, JunctionSource {
    private fun d(m: Double) = UnitFormat.distance(m, units)

    override val junctions: JunctionSource get() = this

    /**
     * T102: the ways at the node at exactly ([lonE6], [latE6]) in every piece that has one there (tile cuts and pack
     * borders: the twins' ways together), each with its bearing over [Directions.BEARING_M] and whether [mode] may
     * leave along it; null when no piece has a node there. Drive reads only graphs with the road data.
     */
    override fun junction(lonE6: Int, latE6: Int, mode: TravelMode): List<Branch>? {
        val model = CostModel(mode)
        val box = TiledGraph.around(latE6 / E6, lonE6 / E6, 1.0)
        var found = false
        val out = ArrayList<Branch>()
        for ((x, y) in tiles.tilesIn(box[0], box[1], box[2], box[3])) for (p in tiles.pieces(x, y)) {
            if (mode == TravelMode.DRIVE && !p.graph.source.drive) continue
            for (n in p.nodesAt(lonE6, latE6)) {
                found = true
                val g = p.graph
                for (i in g.adjStart[n] until g.adjStart[n + 1]) out += branch(g, n, g.adjEdge[i], model)
            }
        }
        if (!found) return null
        // Parallel copies of one way (overlapping packs; twins of one graph file share a cut edge's ends).
        return out.distinctBy { listOf(it.nextLonE6, it.nextLatE6, it.name, it.wayClass, it.level) }
    }

    /** Edge `a shr 1` leaving node [n] (`a and 1 == 0`: [n] is its from node), seen from [n]. */
    private fun branch(g: RoutingGraph, n: Int, a: Int, model: CostModel): Branch {
        val e = a shr 1
        val atFrom = a and 1 == 0
        val last = g.pointCount(e) - 1
        val step = if (atFrom) 1 else -1
        var k = if (atFrom) 1 else last - 1
        val lat0 = g.nodeLat[n] / E6
        val lon0 = g.nodeLon[n] / E6
        var plat = lat0
        var plon = lon0
        var d = 0.0
        while (true) {
            val lat = g.pointLatE6(e, k) / E6
            val lon = g.pointLonE6(e, k) / E6
            d += TrackStats.distanceM(plat, plon, lat, lon)
            plat = lat; plon = lon
            if (d >= Directions.BEARING_M || k == (if (atFrom) last else 0)) break
            k += step
        }
        val next = if (atFrom) 1 else last - 1
        return Branch(
            TrackStats.bearingDeg(lat0, lon0, plat, plon), g.pointLonE6(e, next), g.pointLatE6(e, next), g.name(e), g.wayClass(e),
            model.usable(g, e) && model.allows(g, e, atFrom), if (model.mode == TravelMode.DRIVE) model.speedKmh(g, e) else 0.0, g.nodeLevel[n].toInt(),
        )
    }

    /** A snapped end: the piece it lies in and the snap on that piece's graph. */
    private class End(val piece: GraphPiece, val snap: EdgeSnap)

    /** One traversal of edge [e] of [piece] from fraction [f0] to [f1] of its length. */
    private class Step(val piece: GraphPiece, val e: Int, val f0: Double, val f1: Double)

    /**
     * The pieces one route has asked for, each tile looked up once: the search holds these objects
     * whatever the shared cache evicts meanwhile, so a node is never met again in a reloaded copy.
     */
    private class View(val tiles: GraphPieces, val overlay: DriveOverlay? = null, val detail: Set<Long> = emptySet()) {
        private val memo = HashMap<Long, List<GraphPiece>>()
        /** T89: level-2 pieces by level-2 tile (one object for all the graph tiles inside), null where level 2 does not apply. */
        private val memo2 = HashMap<Long, GraphPiece?>()
        /** T89: level-2 tiles holding an end area (searched through level 1 or in full). */
        private val detail2: Set<Long> = if (overlay == null || !overlay.level2) emptySet() else {
            val step = overlay.tileZoom - overlay.level2Zoom
            detail.mapTo(HashSet()) { k -> tileKey((k and 0xFFFFFFFFL).toInt() shr step, (k shr 32).toInt() shr step) }
        }
        var overlayTiles = 0
            private set
        var level2Tiles = 0
            private set

        fun pieces(x: Int, y: Int): List<GraphPiece> = tileKey(x, y).let { k ->
            memo.getOrPut(k) {
                // T77: away from the ends a tile one pack alone covers is searched through its overlay.
                if (overlay == null || k in detail) return@getOrPut tiles.pieces(x, y)
                level2(x, y)?.let { return@getOrPut listOf(it) } // T89: whole z8 cells far from both ends
                level1(x, y)?.let { overlayTiles++; return@getOrPut listOf(it) }
                tiles.pieces(x, y)
            }
        }

        /** The level-1 overlay piece of graph tile ([x], [y]), when one pack alone has it. */
        fun level1(x: Int, y: Int): GraphPiece? {
            val o = overlay ?: return null
            val src = tiles.sources(x, y)
            return if (src == null) o.piece(x, y, tiles.pieces(x, y))
            else if (src.size == 1) o.piece(x, y, src[0].first, src[0].second) { tiles.piece(src[0].first, x, y) }
            else null
        }

        /**
         * T89: the level-2 piece covering graph tile ([x], [y]): its level-2 tile holds no end area, every graph
         * tile in it comes from one and the same graph file, and that file's level-2 cell is cached or on file.
         */
        private fun level2(x: Int, y: Int): GraphPiece? {
            val o = overlay ?: return null
            if (!o.level2) return null
            val step = o.tileZoom - o.level2Zoom
            val x2 = x shr step
            val y2 = y shr step
            val k2 = tileKey(x2, y2)
            if (k2 in detail2) return null
            return memo2.getOrPut(k2) {
                var only: Pair<Int, GraphSource>? = null
                for (dy in 0 until (1 shl step)) for (dx in 0 until (1 shl step)) {
                    val src = tiles.sources((x2 shl step) + dx, (y2 shl step) + dy) ?: return@getOrPut null
                    if (src.isEmpty()) continue
                    if (src.size != 1) return@getOrPut null
                    if (only == null) only = src[0] else if (only.second != src[0].second) return@getOrPut null
                }
                val (index, source) = only ?: return@getOrPut null
                o.piece2(x2, y2, index, source) { sx, sy -> level1(sx, sy) }?.also { level2Tiles++ }
            }
        }
    }

    override fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
    ): RouteResult {
        // T108: crow is a straight line, never a search (no heights here: the editor and the phone pass their DEM to Crow).
        if (mode == TravelMode.CROW) return RouteResult.Found(Crow.plan(fromLat, fromLon, toLat, toLon, null, name, ids, units))
        if (tiles.isEmpty) return RouteResult.Failed(RouteFailure.EMPTY_GRAPH, "the pack has no trails or roads to route on")
        val model = CostModel(mode)
        val long = overlay != null && mode == TravelMode.DRIVE &&
            TrackStats.distanceM(fromLat, fromLon, toLat, toLon) >= DriveOverlay.MIN_DISTANCE_M
        if (long) {
            // T77: the tiles around both ends in full, the rest through the overlay; if that finds nothing, everything in full.
            val near = maxOf(DriveOverlay.DETAIL_M, maxSnapM * 2)
            val detail = HashSet<Long>()
            for ((lat, lon) in listOf(fromLat to fromLon, toLat to toLon)) {
                val b = TiledGraph.around(lat, lon, near)
                for ((x, y) in tiles.tilesIn(b[0], b[1], b[2], b[3])) detail += tileKey(x, y)
            }
            val r = route(View(tiles, overlay, detail), model, fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
            // T89: a component-summary refusal is final; only a search the overlay could not carry is repeated in full (within the budget).
            if (r !is RouteResult.Failed || r.reason != RouteFailure.NOT_CONNECTED || r.details["componentSummary"] == true) return r
        }
        return route(View(tiles), model, fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
    }

    private fun route(
        view: View, model: CostModel, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
    ): RouteResult {
        // T73: drive mode snaps only onto roads cars may use, in graphs built with the road data.
        val accept: ((GraphPiece, Int) -> Boolean)? = if (mode == TravelMode.DRIVE) { p, e -> p.graph.source.drive && model.usable(p.graph, e) } else null
        val s = snap(view, fromLat, fromLon, maxSnapM, accept) ?: return offNetwork(view, model, RouteFailure.START_OFF_NETWORK, "start", fromLat, fromLon, maxSnapM)
        val t = snap(view, toLat, toLon, maxSnapM, accept) ?: return offNetwork(view, model, RouteFailure.END_OFF_NETWORK, "end", toLat, toLon, maxSnapM)
        separate(model, s, t)?.let { return it }
        return Search(view, model, s, t).run(name, ids)
    }

    /**
     * T89: the component summary's answer when it is conclusive: the two ends lie on parts of one graph
     * file's network that no line [model] may use joins, and either the network is that one file or one
     * of the two parts never reaches the edge of its pack (so no other pack can join them). Null otherwise.
     */
    private fun separate(model: CostModel, s: End, t: End): RouteResult.Failed? {
        val gs = s.piece.graph
        val gt = t.piece.graph
        if (gs.source != gt.source) return null
        val cs = gs.components(model.mode) ?: return null
        val ct = gt.components(model.mode) ?: return null
        if (!model.usable(gs, s.snap.edge) || !model.usable(gt, t.snap.edge)) return null
        val a = cs[gs.edgeFrom[s.snap.edge]]
        val b = ct[gt.edgeFrom[t.snap.edge]]
        if (a shr 1 == b shr 1) return null
        val bothOpen = a and 1 == 1 && b and 1 == 1
        if (bothOpen && tiles.fileCount != 1) return null
        val inside = if (bothOpen) "" else if (a and 1 == 0) "; the start's part stays inside ${gs.source.file}" else "; the end's part stays inside ${gs.source.file}"
        return RouteResult.Failed(
            RouteFailure.NOT_CONNECTED,
            if (model.mode == TravelMode.DRIVE) {
                "no road open to cars connects start and end: they are on separate road networks of the map packs " +
                    "(the graph's component summary$inside; drive mode only knows the roads in the map packs)"
            } else {
                "no trail connects start and end: they are on separate networks (the graph's component summary$inside)"
            },
            linkedMapOf("componentSummary" to true, "expanded" to 0),
        )
    }

    override fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap? = snap(View(tiles), lat, lon, maxDistanceM)?.let { e ->
        e.snap.copy(name = e.piece.graph.name(e.snap.edge), wayClass = e.piece.graph.wayClass(e.snap.edge))
    }

    override fun nearest(lat: Double, lon: Double, maxDistanceM: Double, mode: TravelMode): EdgeSnap? {
        if (mode != TravelMode.DRIVE) return nearest(lat, lon, maxDistanceM)
        val model = CostModel(mode)
        return snap(View(tiles), lat, lon, maxDistanceM) { p, e -> p.graph.source.drive && model.usable(p.graph, e) }?.let { e ->
            e.snap.copy(name = e.piece.graph.name(e.snap.edge), wayClass = e.piece.graph.wayClass(e.snap.edge))
        }
    }

    /** The nearest point within [maxM] on an edge [accept] takes (any edge when null). */
    private fun snap(view: View, lat: Double, lon: Double, maxM: Double, accept: ((GraphPiece, Int) -> Boolean)? = null): End? {
        val box = TiledGraph.around(lat, lon, maxM)
        var best: End? = null
        for ((x, y) in tiles.tilesIn(box[0], box[1], box[2], box[3])) {
            for (p in view.pieces(x, y)) {
                val s = (if (accept == null) p.graph.nearest(lat, lon, maxM) else p.graph.nearest(lat, lon, maxM) { e -> accept(p, e) }) ?: continue
                if (best == null || s.distanceM < best.snap.distanceM) best = End(p, s)
            }
        }
        return best
    }

    private fun offNetwork(view: View, model: CostModel, reason: RouteFailure, which: String, lat: Double, lon: Double, maxSnapM: Double): RouteResult.Failed {
        if (model.mode == TravelMode.DRIVE) {
            // A pack built before the drive data is here: say so instead of "no road" (its roads cannot be trusted for cars).
            val old = snap(view, lat, lon, maxSnapM) { p, _ -> !p.graph.source.drive }
            if (old != null) return driveUnsupported(old.piece.graph.source.file)
            val nearest = snap(view, lat, lon, Router.FAR_SEARCH_M) { p, e -> p.graph.source.drive && model.usable(p.graph, e) }?.snap?.distanceM
            val where = if (nearest == null) "none within ${d(Router.FAR_SEARCH_M)}" else "the nearest is ${d(nearest)} away"
            return RouteResult.Failed(
                reason, "no road open to cars within ${d(maxSnapM)} of the $which point ($where)",
                linkedMapOf("maxSnapM" to maxSnapM, "nearestM" to nearest?.let { PlannedRoute.r1(it) }),
            )
        }
        val nearest = snap(view, lat, lon, Router.FAR_SEARCH_M)?.snap?.distanceM
        val where = if (nearest == null) "none within ${d(Router.FAR_SEARCH_M)}" else "the nearest is ${d(nearest)} away"
        return RouteResult.Failed(
            reason, "no trail or road within ${d(maxSnapM)} of the $which point ($where)",
            linkedMapOf("maxSnapM" to maxSnapM, "nearestM" to nearest?.let { PlannedRoute.r1(it) }),
        )
    }

    private fun driveUnsupported(file: String) = RouteResult.Failed(
        RouteFailure.DRIVE_UNSUPPORTED,
        "drive mode needs a map pack built with road data (one-way streets, roads closed to cars, speed limits); " +
            "$file was built before it. Download or build the pack again to drive here; hiking and cycling work as before",
        linkedMapOf("file" to file),
    )

    private fun stepCost(model: CostModel, p: GraphPiece, e: Int, f0: Double, f1: Double): Double {
        // T77: a shortcut is only ever travelled whole and forwards (the ends lie in tiles searched in full).
        p.overlay?.let { o -> return if (f0 == 0.0 && f1 == 1.0) o.cost(e) else Double.POSITIVE_INFINITY }
        val g = p.graph
        val part = abs(f1 - f0)
        if (part == 0.0) return 0.0
        val fwd = f1 > f0
        if (!model.allows(g, e, fwd)) return Double.POSITIVE_INFINITY
        val w = model.weight(g, e)
        if (w.isInfinite()) return Double.POSITIVE_INFINITY
        val len = g.edgeLength[e] * part
        val up = (if (fwd) g.edgeAscent[e] else g.edgeDescent[e]) * part
        val down = (if (fwd) g.edgeDescent[e] else g.edgeAscent[e]) * part
        return len * w + model.slopePenalty(len, up, down)
    }

    /**
     * One search. Nodes have dense ids: 0 is the start, 1 the end, and each piece the search touches
     * gets a block of ids (its base plus the node's index in the piece). Per node and direction: the
     * best cost, the parent and how it was reached ([via]: `edge * 2 + reversed` for an edge
     * travelled from the parent, -1 for a step to a twin, -2/-3 for the partial edge of an end).
     */
    private inner class Search(val view: View, val model: CostModel, val s: End, val t: End) {
        private val pieces = ArrayList<GraphPiece>()
        private val bases = IntList()
        private val baseOf = HashMap<GraphPiece, Int>()
        private var size = 2
        private var cap = 0
        private var dist = arrayOf(DoubleArray(0), DoubleArray(0))
        private var parent = arrayOf(IntArray(0), IntArray(0))
        private var via = arrayOf(IntArray(0), IntArray(0))
        private var closed = arrayOf(BooleanArray(0), BooleanArray(0))
        private var pot = DoubleArray(0)
        private val heaps = arrayOf(Heap(), Heap())
        private val networkM = DoubleArray(2)
        private val hw = model.minWeight
        private val st = TrackStats.distanceM(s.snap.lat, s.snap.lon, t.snap.lat, t.snap.lon)
        private val drive = model.mode == TravelMode.DRIVE
        /** T73: `destination` edges drive mode may use (those joined to an end through other destination edges), by [key]. */
        private val destOk = HashSet<Long>()
        var expanded = 0

        init {
            grow(2)
            pot[START] = hw * st / 2
            pot[GOAL] = -hw * st / 2
            if (drive) { destinationZone(s); destinationZone(t) }
        }

        private fun key(p: GraphPiece, i: Int): Long = (base(p).toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)

        /** [stepCost], and for drive mode infinite on a `destination` edge outside the ends' zones. */
        private fun cost(p: GraphPiece, e: Int, f0: Double, f1: Double): Double {
            if (drive && p.graph.flags(e) and WayAttrs.MOTOR_DESTINATION != 0 && key(p, e) !in destOk) return Double.POSITIVE_INFINITY
            return stepCost(model, p, e, f0, f1)
        }

        /**
         * T73: `motor_access=destination` roads are for reaching a place on them, so a drive route may use
         * them at its start and its end only: when an end lies on one, every destination edge joined to it
         * through other destination edges (up to [MAX_DESTINATION_EDGES]) is usable; any other is not.
         */
        private fun destinationZone(end: End) {
            val g0 = end.piece.graph
            val e0 = end.snap.edge
            if (g0.flags(e0) and WayAttrs.MOTOR_DESTINATION == 0) return
            destOk += key(end.piece, e0)
            val queue = ArrayDeque<Pair<GraphPiece, Int>>()
            val seen = HashSet<Long>()
            queue.addLast(end.piece to g0.edgeFrom[e0]); queue.addLast(end.piece to g0.edgeTo[e0])
            var added = 0
            while (queue.isNotEmpty() && added < MAX_DESTINATION_EDGES) {
                val (p, n) = queue.removeFirst()
                if (!seen.add(key(p, n))) continue
                val g = p.graph
                for (i in g.adjStart[n] until g.adjStart[n + 1]) {
                    val e = g.adjEdge[i] shr 1
                    if (g.flags(e) and WayAttrs.MOTOR_DESTINATION == 0 || !model.usable(g, e)) continue
                    if (destOk.add(key(p, e))) {
                        added++
                        queue.addLast(p to g.edgeFrom[e]); queue.addLast(p to g.edgeTo[e])
                    }
                }
                if (p.boundary[n]) {
                    for (q in neighbourPieces(g.nodeLon[n], g.nodeLat[n])) {
                        if (q === p || !q.graph.source.drive) continue
                        val lq = q.twin(g.nodeLon[n], g.nodeLat[n], tolerant = q.graph.source != p.graph.source, level = g.nodeLevel[n].toInt())
                        if (lq >= 0) queue.addLast(q to lq)
                    }
                }
            }
        }

        private fun grow(n: Int) {
            if (n <= cap) return
            val c = maxOf(n, cap * 2, 64)
            for (d in 0..1) {
                val old = cap
                dist[d] = dist[d].copyOf(c).also { it.fill(Double.POSITIVE_INFINITY, old, c) }
                parent[d] = parent[d].copyOf(c).also { it.fill(-1, old, c) }
                via[d] = via[d].copyOf(c)
                closed[d] = closed[d].copyOf(c)
            }
            pot = pot.copyOf(c).also { it.fill(Double.NaN, cap, c) }
            cap = c
        }

        /** The id block of [p], added on first touch. */
        fun base(p: GraphPiece): Int = baseOf.getOrPut(p) {
            val b = size
            size += p.graph.nodeCount
            grow(size)
            pieces += p
            bases.add(b)
            b
        }

        /** Index into [pieces] of node [v] (not the start or end). */
        private fun pieceIndex(v: Int): Int {
            var lo = 0
            var hi = bases.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (bases[mid] <= v) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun potential(v: Int): Double {
            val p = pot[v]
            if (!p.isNaN()) return p
            val pi = pieceIndex(v)
            val g = pieces[pi].graph
            val n = v - bases[pi]
            val lat = g.nodeLat[n] / E6
            val lon = g.nodeLon[n] / E6
            val toEnd = TrackStats.distanceM(lat, lon, t.snap.lat, t.snap.lon)
            val fromStart = TrackStats.distanceM(s.snap.lat, s.snap.lon, lat, lon)
            return (hw * (toEnd - fromStart) / 2).also { pot[v] = it }
        }

        private var mu = Double.POSITIVE_INFINITY
        private var meet = -1
        private var direct = false

        /** Offers cost [c] for node [v] in direction [d] (0 forward, 1 backward), reached from [from] by [how]. */
        private fun relax(d: Int, v: Int, c: Double, from: Int, how: Int) {
            if (closed[d][v] || c >= dist[d][v]) return
            dist[d][v] = c
            parent[d][v] = from
            via[d][v] = how
            heaps[d].push(if (d == 0) c + potential(v) else c - potential(v), v)
            val other = dist[1 - d][v]
            if (other < Double.POSITIVE_INFINITY && c + other < mu) {
                mu = c + other
                meet = v
                direct = false
            }
        }

        private fun expandEnd(d: Int) {
            val e = if (d == 0) s else t
            val b = base(e.piece)
            val g = e.piece.graph
            val f = e.snap.fraction
            val edge = e.snap.edge
            // Forward: from the start along its edge to either end. Backward: from either end of the end's edge to the end.
            if (d == 0) {
                relax(0, b + g.edgeFrom[edge], cost(e.piece, edge, f, 0.0), START, -2)
                relax(0, b + g.edgeTo[edge], cost(e.piece, edge, f, 1.0), START, -3)
            } else {
                relax(1, b + g.edgeFrom[edge], cost(e.piece, edge, 0.0, f), GOAL, -2)
                relax(1, b + g.edgeTo[edge], cost(e.piece, edge, 1.0, f), GOAL, -3)
            }
            closed[d][if (d == 0) START else GOAL] = true
        }

        private fun expand(d: Int, u: Int) {
            val pi = pieceIndex(u)
            val p = pieces[pi]
            val g = p.graph
            val b = bases[pi]
            val lu = u - b
            val du = dist[d][u]
            for (i in g.adjStart[lu] until g.adjStart[lu + 1]) {
                val a = g.adjEdge[i]
                val e = a shr 1
                val atFrom = a and 1 == 0 // u is the edge's `from` node
                val v = b + if (atFrom) g.edgeTo[e] else g.edgeFrom[e]
                // Travel order is u -> v forward and v -> u backward.
                val rev = if (d == 0) !atFrom else atFrom
                val c = if (rev) cost(p, e, 1.0, 0.0) else cost(p, e, 0.0, 1.0)
                if (c < Double.POSITIVE_INFINITY) networkM[d] += g.edgeLength[e] / 2.0
                if (closed[d][v]) continue
                relax(d, v, du + c, u, e * 2 + if (rev) 1 else 0)
            }
            if (p.boundary[lu]) {
                val lon = g.nodeLon[lu]
                val lat = g.nodeLat[lu]
                for (q in neighbourPieces(lon, lat)) {
                    if (q === p || (drive && !q.graph.source.drive)) continue
                    val lq = q.twin(lon, lat, tolerant = q.graph.source != p.graph.source, level = g.nodeLevel[lu].toInt()) // T89: same level only
                    if (lq >= 0) relax(d, base(q) + lq, du, u, -1)
                }
            }
        }

        /** Pieces of every tile within [GraphPieces.JOIN_M] of the point. */
        private fun neighbourPieces(lonE6: Int, latE6: Int): List<GraphPiece> {
            val box = TiledGraph.around(latE6 / E6, lonE6 / E6, GraphPieces.JOIN_M * 2)
            val tl = tiles.tilesIn(box[0], box[1], box[2], box[3])
            return if (tl.size == 1) view.pieces(tl[0].first, tl[0].second) else tl.flatMap { (x, y) -> view.pieces(x, y) }
        }

        fun run(name: String, ids: UuidGenerator): RouteResult {
            if (s.piece === t.piece && s.snap.edge == t.snap.edge) {
                mu = cost(s.piece, s.snap.edge, s.snap.fraction, t.snap.fraction)
                direct = mu < Double.POSITIVE_INFINITY // a one-way travelled backwards is not direct (T73)
            }
            dist[0][START] = 0.0
            dist[1][GOAL] = 0.0
            expandEnd(0)
            expandEnd(1)
            expanded = 2
            var exhausted = -1
            while (true) {
                val f = heaps[0]
                val r = heaps[1]
                if (f.size == 0 || r.size == 0) {
                    exhausted = if (f.size == 0) 0 else 1
                    break
                }
                if (f.peekKey() + r.peekKey() >= mu) break
                if (expanded >= maxSettled && mu == Double.POSITIVE_INFINITY) {
                    // T89: the budget: fail plainly instead of searching on through every pack.
                    return RouteResult.Failed(
                        RouteFailure.SEARCH_LIMIT,
                        "no route found within the search limit ($maxSettled junctions settled, ${d(networkM[0])} of " +
                            (if (drive) "road" else "trail and road") + " around the start and ${d(networkM[1])} around the end); " +
                            "the ends may not be connected in the map packs",
                        linkedMapOf("expanded" to expanded, "maxSettled" to maxSettled),
                    )
                }
                val d = if (f.size <= r.size) 0 else 1
                val u = heaps[d].pop()
                if (closed[d][u]) continue
                closed[d][u] = true
                expanded++
                expand(d, u)
            }
            if (mu == Double.POSITIVE_INFINITY) {
                val startKm = Router.km(networkM[0])
                val endKm = Router.km(networkM[1])
                val (sAt, eAt) = if (exhausted == 0) d(networkM[0]) to "at least ${d(networkM[1])}" else "at least ${d(networkM[0])}" to d(networkM[1])
                return RouteResult.Failed(
                    RouteFailure.NOT_CONNECTED,
                    if (drive) {
                        "no road open to cars connects start and end (one-way streets and roads closed to cars count; " +
                            "drive mode only knows the roads in the map packs): $sAt of road reachable from the start, $eAt to the end"
                    } else {
                        "no trail connects start and end: they are on separate networks " +
                            "($sAt of trail and road around the start, $eAt around the end)"
                    },
                    linkedMapOf("startNetworkKm" to startKm, "endNetworkKm" to endKm, "exhausted" to if (exhausted == 0) "start" else "end", "expanded" to expanded),
                )
            }
            val steps = ArrayList<Step>()
            if (direct) {
                steps += Step(s.piece, s.snap.edge, s.snap.fraction, t.snap.fraction)
            } else {
                var v = meet
                while (v != START) { step(0, v)?.let { steps += it }; v = parent[0][v] }
                steps.reverse()
                v = meet
                while (v != GOAL) { step(1, v)?.let { steps += it }; v = parent[1][v] }
            }
            val memory = linkedMapOf<String, Any?>(
                "tiles" to pieces.size, "graphBytes" to pieces.sumOf { it.graph.approxBytes() },
                "searchBytes" to cap.toLong() * SEARCH_BYTES_PER_NODE, "searchNodes" to size,
            )
            if (view.overlay != null) {
                memory["overlayTiles"] = view.overlayTiles // T77
                if (view.overlay.level2) memory["level2Tiles"] = view.level2Tiles // T89: z8 cells the search used
            }
            val expandedSteps = ArrayList<Step>(steps.size)
            for (st in steps) {
                val o = st.piece.overlay
                if (o == null) expandedSteps += st
                else for ((p, e, fwd) in o.expand(st.e)) expandedSteps += if (fwd) Step(p, e, 0.0, 1.0) else Step(p, e, 1.0, 0.0)
            }
            return RouteResult.Found(plan(model, expandedSteps.filter { it.f0 != it.f1 }, mu, s, t, expanded, name, ids, memory))
        }

        /** The step that reached [v] in direction [d], in travel order; null for a twin. */
        private fun step(d: Int, v: Int): Step? {
            val how = via[d][v]
            if (how == -1) return null
            val end = if (d == 0) s else t
            if (how == -2 || how == -3) {
                val f = end.snap.fraction
                val tip = if (how == -2) 0.0 else 1.0
                return if (d == 0) Step(end.piece, end.snap.edge, f, tip) else Step(end.piece, end.snap.edge, tip, f)
            }
            val p = pieces[pieceIndex(v)]
            val e = how shr 1
            return if (how and 1 == 1) Step(p, e, 1.0, 0.0) else Step(p, e, 0.0, 1.0)
        }
    }

    private fun plan(
        model: CostModel, steps: List<Step>, cost: Double, s: End, t: End, expanded: Int, name: String, ids: UuidGenerator,
        memory: Map<String, Any?>,
    ): PlannedRoute {
        val pts = ArrayList<RoutePoint>()
        var ascent = 0.0
        var descent = 0.0
        val byClass = LinkedHashMap<WayClass, Double>()
        val runs = ArrayList<Pair<String?, Double>>() // T49: consecutive stretches by way name
        val runRoadM = ArrayList<Double>() // T87: metres of each run on roads (service and up)
        var driveS = 0.0 // T73
        for (st in steps) {
            val g = st.piece.graph
            val nm = g.name(st.e)
            val metres = g.edgeLength[st.e] * abs(st.f1 - st.f0)
            if (model.mode == TravelMode.DRIVE) driveS += model.edgeTimeS(g, st.e, metres.toDouble())
            if (runs.isNotEmpty() && runs.last().first == nm) runs[runs.size - 1] = nm to runs.last().second + metres else { runs += nm to metres; runRoadM += 0.0 }
            if (g.wayClass(st.e).code >= WayClass.SERVICE.code) runRoadM[runRoadM.size - 1] += metres.toDouble()
            val part = abs(st.f1 - st.f0)
            val fwd = st.f1 > st.f0
            ascent += (if (fwd) g.edgeAscent[st.e] else g.edgeDescent[st.e]) * part
            descent += (if (fwd) g.edgeDescent[st.e] else g.edgeAscent[st.e]) * part
            byClass[g.wayClass(st.e)] = (byClass[g.wayClass(st.e)] ?: 0.0) + g.edgeLength[st.e] * part
            for (p in subPoints(g, st.e, st.f0, st.f1)) {
                val last = pts.lastOrNull()
                if (last != null && last.lat == p.lat && last.lon == p.lon) continue
                pts += p
            }
        }
        if (pts.isEmpty()) pts += RoutePoint(s.snap.lat, s.snap.lon)
        if (pts.size == 1) pts += pts[0]
        val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
        val distance = TrackStats.of(track).distanceM
        val hasEle = pts.any { it.ele != null }
        val time = if (model.mode == TravelMode.DRIVE) driveS else model.timeS(distance, if (hasEle) ascent else 0.0)
        fun minVia(i: Int) = if (model.mode != TravelMode.DRIVE && runRoadM[i] * 2 > runs[i].second) Router.MIN_VIA_ROAD_M else Router.MIN_VIA_M
        val via = runs.filterIndexed { i, it -> it.first != null && it.second >= minVia(i) }.map { it.first!! to it.second }
            .fold(ArrayList<Pair<String, Double>>()) { acc, r -> if (acc.isNotEmpty() && acc.last().first == r.first) acc[acc.size - 1] = r.first to acc.last().second + r.second else acc += r; acc }
        val desc = d(distance) + (if (hasEle) ", +${UnitFormat.elevation(ascent, units)} / -${UnitFormat.elevation(descent, units)}" else "") +
            ", about ${PlannedRoute.hm(time)}" + (if (via.isNotEmpty()) " via " + via.take(3).joinToString(", ") { it.first } else "") +
            " (${model.mode.label}; planned offline by Dystopo)" + if (model.mode == TravelMode.DRIVE) " " + Router.DRIVE_NOTICE else "" // ADR 0032
        val route = Route(ids.next(), name, pts, description = desc, type = model.mode.label)
        fun named(e: End) = e.snap.copy(name = e.piece.graph.name(e.snap.edge), wayClass = e.piece.graph.wayClass(e.snap.edge))
        return PlannedRoute(
            route, model.mode, distance, if (hasEle) ascent else null, if (hasEle) descent else null, time,
            TrackStats.profile(track, Router.PROFILE_SAMPLES), cost, steps.size, named(s), named(t), expanded, byClass, via, memory,
            Directions.of(pts, model.mode, this), // T102
        )
    }

    /** The points of edge [e] between fractions [f0] and [f1] of its length, in travel order, ends interpolated. */
    private fun subPoints(g: RoutingGraph, e: Int, f0: Double, f1: Double): List<RoutePoint> {
        val cum = g.cumulative(e)
        val total = cum.last()
        val n = cum.size
        fun at(d: Double): RoutePoint {
            var k = 0
            while (k < n - 2 && cum[k + 1] < d) k++
            val span = cum[k + 1] - cum[k]
            val f = if (span > 0) ((d - cum[k]) / span).coerceIn(0.0, 1.0) else 0.0
            val lat = (g.pointLatE6(e, k) + f * (g.pointLatE6(e, k + 1) - g.pointLatE6(e, k))) / E6
            val lon = (g.pointLonE6(e, k) + f * (g.pointLonE6(e, k + 1) - g.pointLonE6(e, k))) / E6
            val a = g.pointEle(e, k)
            val b = g.pointEle(e, k + 1)
            val ele = when {
                a.isNaN() && b.isNaN() -> null
                a.isNaN() -> b.toDouble()
                b.isNaN() -> a.toDouble()
                else -> a + f * (b - a)
            }
            return RoutePoint(lat, lon, ele?.let { (it * 10).roundToLong() / 10.0 })
        }
        fun point(k: Int) = RoutePoint(
            g.pointLatE6(e, k) / E6, g.pointLonE6(e, k) / E6, g.pointEle(e, k).takeUnless { it.isNaN() }?.toDouble(),
        )
        val d0 = f0 * total
        val d1 = f1 * total
        val out = ArrayList<RoutePoint>()
        out += if (f0 == 0.0) point(0) else if (f0 == 1.0) point(n - 1) else at(d0)
        if (d0 < d1) {
            for (k in 0 until n) if (cum[k] > d0 && cum[k] < d1) out += point(k)
        } else {
            for (k in n - 1 downTo 0) if (cum[k] < d0 && cum[k] > d1) out += point(k)
        }
        out += if (f1 == 0.0) point(0) else if (f1 == 1.0) point(n - 1) else at(d1)
        return out
    }

    /** A binary min-heap of (priority, node) with lazy deletion (stale entries are skipped by the caller). */
    private class Heap {
        private var pri = DoubleArray(256)
        private var node = IntArray(256)
        var size = 0
            private set

        fun peekKey(): Double = pri[0]

        fun push(p: Double, v: Int) {
            if (size == pri.size) { pri = pri.copyOf(size * 2); node = node.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (pri[parent] <= p) break
                pri[i] = pri[parent]; node[i] = node[parent]
                i = parent
            }
            pri[i] = p; node[i] = v
        }

        fun pop(): Int {
            val top = node[0]
            size--
            if (size > 0) {
                val p = pri[size]
                val v = node[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && pri[c + 1] < pri[c]) c++
                    if (pri[c] >= p) break
                    pri[i] = pri[c]; node[i] = node[c]
                    i = c
                }
                pri[i] = p; node[i] = v
            }
            return top
        }
    }

    private companion object {
        const val START = 0
        const val GOAL = 1
        const val E6 = 1e6
        /** Two directions of cost (8), parent (4), how (4) and closed (1), plus the potential (8). */
        const val SEARCH_BYTES_PER_NODE = 42L

        fun tileKey(x: Int, y: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
        /** T73: how many `destination` edges one end's zone may hold (a gated area's streets, a long private drive). */
        const val MAX_DESTINATION_EDGES = 5000
    }
}
