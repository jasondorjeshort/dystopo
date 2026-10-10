package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the route is travelled; picks the [CostModel]. */
enum class TravelMode(val label: String) {
    HIKE("hike"), BIKE("bike");

    companion object {
        fun of(label: String?): TravelMode? = entries.firstOrNull { it.label == label?.lowercase() }
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
 * Private access costs 5x in both modes (passable, but only when nothing else goes).
 */
class CostModel(val mode: TravelMode) {
    fun classWeight(c: WayClass): Double = when (mode) {
        TravelMode.HIKE -> when (c) {
            WayClass.PATH, WayClass.FOOTWAY -> 1.0
            WayClass.TRACK, WayClass.BRIDLEWAY, WayClass.STEPS -> 1.1
            WayClass.CYCLEWAY -> 1.2
            WayClass.SERVICE -> 1.4
            WayClass.MINOR -> 1.6
            WayClass.TERTIARY -> 2.5
            WayClass.SECONDARY -> 4.0
            WayClass.PRIMARY -> 6.0
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
        }
    }

    fun surfaceWeight(s: Surface): Double = if (mode == TravelMode.BIKE && s == Surface.UNPAVED) 1.3 else 1.0

    fun sacWeight(sac: Int): Double = when (mode) {
        TravelMode.HIKE -> when {
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
        var w = 1.0
        if (flags and WayAttrs.PRIVATE != 0) w *= 5.0
        if (flags and WayAttrs.INFORMAL != 0) w *= if (mode == TravelMode.HIKE) 1.3 else 1.5
        if (flags and WayAttrs.POOR_VISIBILITY != 0) w *= 1.5
        return w
    }

    /** The multiplier on edge [e]'s length. */
    fun weight(g: RoutingGraph, e: Int): Double =
        classWeight(g.wayClass(e)) * surfaceWeight(g.surface(e)) * sacWeight(g.sac(e)) * flagWeight(g.flags(e))

    /** The smallest [weight] any edge can have: the A* heuristic's factor (keeps it admissible). */
    val minWeight: Double get() = WayClass.entries.minOf { classWeight(it) }

    /** Penalty in equivalent metres for climbing [ascentM] and descending [descentM] over [lengthM]. */
    fun slopePenalty(lengthM: Double, ascentM: Double, descentM: Double): Double {
        if (lengthM <= 0) return 0.0
        val up = ascentM / lengthM
        val down = descentM / lengthM
        return when (mode) {
            TravelMode.HIKE -> 8 * ascentM + ascentM * max(0.0, up - 0.25) * 20 + descentM * max(0.0, down - 0.15) * 10
            TravelMode.BIKE -> 12 * ascentM + ascentM * max(0.0, up - 0.08) * 80 + descentM * max(0.0, down - 0.12) * 30
        }
    }

    /**
     * Moving time in seconds. Hike: Naismith's rule, 5 km/h plus an hour per 600 m of ascent. Bike:
     * 16 km/h plus an hour per 500 m of ascent (a touring pace; descents are not credited).
     */
    fun timeS(distanceM: Double, ascentM: Double): Double = when (mode) {
        TravelMode.HIKE -> distanceM / 5000.0 * 3600 + ascentM / 600.0 * 3600
        TravelMode.BIKE -> distanceM / 16000.0 * 3600 + ascentM / 500.0 * 3600
    }
}

/** Why no route was found. */
enum class RouteFailure(val code: String) {
    EMPTY_GRAPH("empty_graph"),
    START_OFF_NETWORK("start_off_network"),
    END_OFF_NETWORK("end_off_network"),
    NOT_CONNECTED("not_connected"),
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
    /** [CostModel.timeS]: Naismith for hiking. */
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
     * metres on each (stretches under [Router.MIN_VIA_M] left out): the trails a route follows.
     */
    val via: List<Pair<String, Double>> = emptyList(),
) {
    fun json(profileSamples: Boolean = true): Map<String, Any?> = linkedMapOf(
        "id" to route.id, "name" to route.name, "mode" to mode.label,
        "distanceM" to r1(distanceM), "ascentM" to ascentM?.let(::r1), "descentM" to descentM?.let(::r1),
        "timeS" to timeS.roundToLong(), "time" to hm(timeS),
        "points" to route.points.size, "edges" to edges, "expanded" to expanded, "cost" to r1(cost),
        "start" to snapJson(start), "end" to snapJson(end),
        "metersByClass" to metersByClass.entries.sortedByDescending { it.value }.associate { it.key.label to r1(it.value) },
        "via" to via.map { (n, m) -> linkedMapOf("name" to n, "distanceM" to r1(m)) }, // T49: trail names
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
 * A* over a [RoutingGraph]. Both ends snap to the nearest point on an edge (within `maxSnapM`); the
 * search starts from a virtual node on the start edge and ends at one on the end edge, so routes
 * begin and end mid-edge. The heuristic is the great-circle distance to the end times the cost
 * model's smallest weight (admissible, so routes are optimal for the model). Ends on different
 * connected components fail at once with [RouteFailure.NOT_CONNECTED] instead of searching.
 */
class Router(val graph: RoutingGraph) {
    fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode = TravelMode.HIKE,
        maxSnapM: Double = DEFAULT_SNAP_M,
        name: String = "Route",
        ids: UuidGenerator = UuidGenerator(),
    ): RouteResult {
        val g = graph
        if (g.edgeCount == 0) return RouteResult.Failed(RouteFailure.EMPTY_GRAPH, "the pack has no trails or roads to route on")
        val s = g.nearest(fromLat, fromLon, maxSnapM) ?: return offNetwork(RouteFailure.START_OFF_NETWORK, "start", fromLat, fromLon, maxSnapM)
        val t = g.nearest(toLat, toLon, maxSnapM) ?: return offNetwork(RouteFailure.END_OFF_NETWORK, "end", toLat, toLon, maxSnapM)
        val cs = g.component[g.edgeFrom[s.edge]]
        val ct = g.component[g.edgeFrom[t.edge]]
        if (cs != ct) {
            val ls = g.componentLengthM[cs]
            val lt = g.componentLengthM[ct]
            return RouteResult.Failed(
                RouteFailure.NOT_CONNECTED,
                "no trail connects start and end in this pack: they are on separate networks " +
                    "(${km(ls)} km of trail and road around the start, ${km(lt)} km around the end)",
                linkedMapOf("startNetworkKm" to km(ls), "endNetworkKm" to km(lt), "networks" to g.componentLengthM.size),
            )
        }
        val model = CostModel(mode)
        return search(model, s, t, name, ids)
    }

    private fun offNetwork(reason: RouteFailure, which: String, lat: Double, lon: Double, maxSnapM: Double): RouteResult.Failed {
        val nearest = graph.nearestDistanceM(lat, lon, FAR_SEARCH_M)
        val where = if (nearest == null) "none within ${km(FAR_SEARCH_M)} km" else "the nearest is ${nearest.roundToLong()} m away"
        return RouteResult.Failed(
            reason, "no trail or road within ${maxSnapM.roundToLong()} m of the $which point ($where)",
            linkedMapOf("maxSnapM" to maxSnapM, "nearestM" to nearest?.let { PlannedRoute.r1(it) }),
        )
    }

    /** One traversal of edge [e] from fraction [f0] to [f1] of its length. */
    private class Step(val e: Int, val f0: Double, val f1: Double)

    private fun stepCost(model: CostModel, e: Int, f0: Double, f1: Double): Double {
        val g = graph
        val part = abs(f1 - f0)
        if (part == 0.0) return 0.0
        val len = g.edgeLength[e] * part
        val fwd = f1 > f0
        val up = (if (fwd) g.edgeAscent[e] else g.edgeDescent[e]) * part
        val down = (if (fwd) g.edgeDescent[e] else g.edgeAscent[e]) * part
        return len * model.weight(g, e) + model.slopePenalty(len, up, down)
    }

    private fun search(model: CostModel, s: EdgeSnap, t: EdgeSnap, name: String, ids: UuidGenerator): RouteResult {
        val g = graph
        val n = g.nodeCount
        val start = n
        val goal = n + 1
        val best = DoubleArray(n + 2) { Double.POSITIVE_INFINITY }
        val parent = IntArray(n + 2) { -1 }
        val parentStep = arrayOfNulls<Step>(n + 2)
        val closed = BooleanArray(n + 2)
        val heap = Heap()
        val hw = model.minWeight
        fun h(v: Int): Double = if (v >= n) 0.0 else hw * TrackStats.distanceM(g.nodeLat[v] / E6, g.nodeLon[v] / E6, t.lat, t.lon)
        fun relax(u: Int, v: Int, step: Step) {
            val c = best[u] + stepCost(model, step.e, step.f0, step.f1)
            if (c < best[v]) {
                best[v] = c
                parent[v] = u
                parentStep[v] = step
                heap.push(c + h(v), v)
            }
        }
        best[start] = 0.0
        heap.push(h(start), start)
        var expanded = 0
        while (heap.size > 0) {
            val u = heap.pop()
            if (closed[u]) continue
            closed[u] = true
            expanded++
            if (u == goal) break
            if (u == start) {
                relax(start, g.edgeFrom[s.edge], Step(s.edge, s.fraction, 0.0))
                relax(start, g.edgeTo[s.edge], Step(s.edge, s.fraction, 1.0))
                if (s.edge == t.edge) relax(start, goal, Step(s.edge, s.fraction, t.fraction))
                continue
            }
            if (u == g.edgeFrom[t.edge]) relax(u, goal, Step(t.edge, 0.0, t.fraction))
            if (u == g.edgeTo[t.edge]) relax(u, goal, Step(t.edge, 1.0, t.fraction))
            for (i in g.adjStart[u] until g.adjStart[u + 1]) {
                val a = g.adjEdge[i]
                val e = a shr 1
                val forward = a and 1 == 0
                val v = if (forward) g.edgeTo[e] else g.edgeFrom[e]
                if (closed[v]) continue
                relax(u, v, if (forward) Step(e, 0.0, 1.0) else Step(e, 1.0, 0.0))
            }
        }
        if (!closed[goal]) {
            return RouteResult.Failed(RouteFailure.NOT_CONNECTED, "no trail connects start and end in this pack", mapOf("expanded" to expanded))
        }
        val steps = ArrayList<Step>()
        var v = goal
        while (v != start) {
            steps += parentStep[v]!!
            v = parent[v]
        }
        steps.reverse()
        return RouteResult.Found(plan(model, steps.filter { it.f0 != it.f1 }, best[goal], s, t, expanded, name, ids))
    }

    private fun plan(model: CostModel, steps: List<Step>, cost: Double, s: EdgeSnap, t: EdgeSnap, expanded: Int, name: String, ids: UuidGenerator): PlannedRoute {
        val g = graph
        val pts = ArrayList<RoutePoint>()
        var ascent = 0.0
        var descent = 0.0
        val byClass = LinkedHashMap<WayClass, Double>()
        val runs = ArrayList<Pair<String?, Double>>() // T49: consecutive stretches by way name
        for (st in steps) {
            val nm = g.name(st.e)
            val metres = g.edgeLength[st.e] * abs(st.f1 - st.f0)
            if (runs.isNotEmpty() && runs.last().first == nm) runs[runs.size - 1] = nm to runs.last().second + metres else runs += nm to metres
            val part = abs(st.f1 - st.f0)
            val fwd = st.f1 > st.f0
            ascent += (if (fwd) g.edgeAscent[st.e] else g.edgeDescent[st.e]) * part
            descent += (if (fwd) g.edgeDescent[st.e] else g.edgeAscent[st.e]) * part
            byClass[g.wayClass(st.e)] = (byClass[g.wayClass(st.e)] ?: 0.0) + g.edgeLength[st.e] * part
            for (p in subPoints(st.e, st.f0, st.f1)) {
                val last = pts.lastOrNull()
                if (last != null && last.lat == p.lat && last.lon == p.lon) continue
                pts += p
            }
        }
        if (pts.isEmpty()) pts += RoutePoint(s.lat, s.lon)
        if (pts.size == 1) pts += pts[0]
        val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
        val distance = TrackStats.of(track).distanceM
        val hasEle = pts.any { it.ele != null }
        val time = model.timeS(distance, if (hasEle) ascent else 0.0)
        val via = runs.filter { it.first != null && it.second >= MIN_VIA_M }.map { it.first!! to it.second }
            .fold(ArrayList<Pair<String, Double>>()) { acc, r -> if (acc.isNotEmpty() && acc.last().first == r.first) acc[acc.size - 1] = r.first to acc.last().second + r.second else acc += r; acc }
        val desc = "${km(distance)} km" + (if (hasEle) ", +${ascent.roundToLong()} m / -${descent.roundToLong()} m" else "") +
            ", about ${PlannedRoute.hm(time)}" + (if (via.isNotEmpty()) " via " + via.take(3).joinToString(", ") { it.first } else "") +
            " (${model.mode.label}; planned offline by Dystopo)"
        val route = Route(ids.next(), name, pts, description = desc, type = model.mode.label)
        return PlannedRoute(
            route, model.mode, distance, if (hasEle) ascent else null, if (hasEle) descent else null, time,
            TrackStats.profile(track, PROFILE_SAMPLES), cost, steps.size, s, t, expanded, byClass, via,
        )
    }

    /** The points of edge [e] between fractions [f0] and [f1] of its length, in travel order, ends interpolated. */
    private fun subPoints(e: Int, f0: Double, f1: Double): List<RoutePoint> {
        val g = graph
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

    companion object {
        /** How far a tapped point may be from the nearest trail or road. */
        const val DEFAULT_SNAP_M = 300.0
        /** How far an off-network failure looks for the nearest line, to say how far it is. */
        const val FAR_SEARCH_M = 10_000.0
        const val PROFILE_SAMPLES = 100
        /** T49: named stretches shorter than this are left out of [PlannedRoute.via] (a crossing, a junction stub). */
        const val MIN_VIA_M = 30.0
        private const val E6 = 1e6

        fun km(m: Double) = (m / 100).roundToLong() / 10.0
    }
}
