package io.github.jasondorjeshort.dystopo.model

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.RouteFailure
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Edits a [RoutePath] (T60): adding, moving and removing via points reroutes only the legs next to
 * the change, through the trail [graph] (T45's [Router]) between consecutive via points, and falls
 * back to a straight [LegKind.STRAIGHT] leg where no trail connects them (off the network, separate
 * networks, or no graph at all). A via point within [snapM] of a trail is moved onto it, so a trail
 * leg starts and ends exactly at its via points; elsewhere it stays where it was put.
 *
 * Straight legs get a point every [STRAIGHT_STEP_M] with elevation from [elevation] when there is a
 * sampler (the DEM pack), else only their two ends. Results are [RoutePath.quantized], so what the
 * editor returns is exactly what the store keeps.
 *
 * T108: crow ([TravelMode.CROW]) never snaps and never asks the router: every leg is straight, with no note. A path
 * in a mode this build does not know (a newer Dystopo's, synced here) is not edited: every edit refuses it with
 * [TravelMode.unknownModeMessage] (ADR 0033 Decision 3: fail plainly rather than replan it as a hike); [setMode] to a
 * known mode is the way out. [directions] keeps the directions of legs an edit left alone ([Directions.splice]).
 */
class RouteEditor(
    private val graph: Router?, // T76: a router (tiles, several packs); Router(graph) for one graph
    private val elevation: ElevationSampler? = null,
    val snapM: Double = Router.DEFAULT_SNAP_M,
) {
    /** Why legs built since the last [takeNotes] are straight (failure codes). */
    private val notes = ArrayList<String>()

    /** The router's messages for those legs (T73: e.g. why drive mode is refused on an older pack), by code. */
    private val messages = LinkedHashMap<String, String>()

    /** Reasons for straight legs from the last edit (`start_off_network`, `not_connected`, `no_graph`, ...), then cleared. */
    fun takeNotes(): List<String> = ArrayList(notes).also { notes.clear() }

    /** The router's message for each reason in the last edit's notes (one per code), then cleared. */
    fun takeMessages(): Map<String, String> = LinkedHashMap(messages).also { messages.clear() }

    /** T108: the server's inner instructions of each leg it planned in the last edit (by the leg as stored), for [directions]. */
    private val remoteInner = HashMap<RouteLeg, List<Instruction>>()

    /**
     * T102: [path]'s turn directions from this editor's graph (every leg; via points; straight legs). T108: when [path]
     * came from [before] by an edit and [beforeDirections] are [before]'s saved directions (same mode), a leg the edit
     * left as it was keeps its instructions from them, and a leg the server planned in this edit keeps the server's
     * ([Directions.splice]), so a trip planned where this phone has no graph keeps what was said along it.
     */
    fun directions(path: RoutePath, before: RoutePath? = null, beforeDirections: List<Instruction>? = null): List<Instruction> {
        val known = HashMap<Int, List<Instruction>>()
        val old = if (before != null && beforeDirections != null && before.mode == path.mode) Directions.innerByLeg(before, beforeDirections) else null
        val used = HashSet<Int>()
        for ((i, leg) in path.legs.withIndex()) {
            remoteInner[leg]?.let { known[i] = it; continue }
            if (old == null) continue
            val j = before!!.legs.indices.firstOrNull { j -> j !in used && sameLeg(before.legs[j], leg) } ?: continue
            used += j
            known[i] = old[j]
        }
        return Directions.splice(path, graph?.junctions, known)
    }

    private fun sameLeg(a: RouteLeg, b: RouteLeg): Boolean =
        a.points.size == b.points.size && a.points.first() == b.points.first() && a.points.last() == b.points.last() && a == b

    /** [lat], [lon] snapped onto the trail network when one is within [snapM]. */
    fun snap(lat: Double, lon: Double): ViaPoint = snap(lat, lon, "hike")

    /** [lat], [lon] snapped onto the network [mode] travels (T73: roads open to cars for drive) when it is within [snapM]; crow: as given. */
    fun snap(lat: Double, lon: Double, mode: String): ViaPoint {
        if (TravelMode.of(mode) == TravelMode.CROW) return ViaPoint(lat, lon) // T108: a crow line runs between the points themselves
        val s = graph?.nearest(lat, lon, snapM, TravelMode.of(mode) ?: TravelMode.HIKE) ?: return ViaPoint(lat, lon)
        return ViaPoint(s.lat, s.lon)
    }

    /** One leg from [a] to [b]: along trails when they connect, else straight; crow: straight (T108). */
    fun leg(a: ViaPoint, b: ViaPoint, mode: String): RouteLeg = leg(a, b, mode) { }

    /** [leg], handing [planned] the inner instructions of a leg a remote router (the server) planned (T108). */
    private fun leg(a: ViaPoint, b: ViaPoint, mode: String, planned: (List<Instruction>) -> Unit): RouteLeg {
        if (TravelMode.of(mode) == TravelMode.CROW) return straight(a, b) // T108: no router, no note
        val g = graph
        if (g == null) {
            notes += "no_graph"
            return straight(a, b)
        }
        val travel = TravelMode.of(mode) ?: TravelMode.HIKE
        return when (val r = g.route(a.lat, a.lon, b.lat, b.lon, travel, snapM)) {
            is RouteResult.Found -> {
                val pts = r.plan.route.points.toMutableList()
                // The router starts and ends on its own snap of the via points: pin the ends to them exactly.
                pts[0] = pts[0].copy(lat = a.lat, lon = a.lon)
                pts[pts.size - 1] = pts.last().copy(lat = b.lat, lon = b.lon)
                // T108: the server's directions along a leg it planned (this phone may have no graph there).
                if (r.plan.memory["remote"] != null) planned(r.plan.instructions.filter { it.type != io.github.jasondorjeshort.dystopo.routing.Maneuver.DEPART && it.type != io.github.jasondorjeshort.dystopo.routing.Maneuver.ARRIVE })
                // T89: a drive leg keeps the router's time (per-road speeds), which the summary adds up.
                RouteLeg(LegKind.TRAIL, pts.map { RoutePoint(it.lat, it.lon, it.ele) }, r.plan.timeS.takeIf { travel == TravelMode.DRIVE })
            }
            is RouteResult.Failed -> {
                notes += r.reason.code
                messages.getOrPut(r.reason.code) { r.message }
                straight(a, b)
            }
        }
    }

    /**
     * A straight leg, with DEM elevations every [STRAIGHT_STEP_M] when there is a sampler (T108: at most
     * [MAX_STRAIGHT_POINTS] points, so a long crow line stays well inside the path's size cap).
     */
    fun straight(a: ViaPoint, b: ViaPoint): RouteLeg {
        val d = TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
        val sampler = elevation ?: return RouteLeg(LegKind.STRAIGHT, listOf(RoutePoint(a.lat, a.lon), RoutePoint(b.lat, b.lon)))
        val n = min(MAX_STRAIGHT_POINTS - 1, max(1, ceil(d / STRAIGHT_STEP_M).toInt()))
        val pts = (0..n).map { i ->
            val f = i.toDouble() / n
            val lat = a.lat + (b.lat - a.lat) * f
            val lon = a.lon + (b.lon - a.lon) * f
            val e = sampler.elevation(lat, lon)
            RoutePoint(if (i == 0) a.lat else if (i == n) b.lat else lat, if (i == 0) a.lon else if (i == n) b.lon else lon, e.takeUnless { it.isNaN() })
        }
        return RouteLeg(LegKind.STRAIGHT, pts)
    }

    /** A new path through [points] (snapped), every leg routed. */
    fun create(points: List<Pair<Double, Double>>, mode: String = "hike"): RoutePath {
        val via = points.map { (lat, lon) -> snap(lat, lon, mode) }
        return build(mode, via, List(max(0, via.size - 1)) { null })
    }

    /** Moves via point [index] to ([lat], [lon]) and reroutes the legs on either side. */
    fun moveVia(path: RoutePath, index: Int, lat: Double, lon: Double): RoutePath {
        require(index in path.via.indices) { "no via point $index (the route has ${path.via.size})" }
        val via = path.via.toMutableList()
        via[index] = snap(lat, lon, path.mode)
        val keep = path.legs.mapIndexed { i, l -> l.takeIf { i != index - 1 && i != index } }
        return build(path.mode, via, keep)
    }

    /** Inserts a via point before [index] (0: a new start; `via.size`: a new end) and routes the legs it splits or adds. */
    fun insertVia(path: RoutePath, index: Int, lat: Double, lon: Double): RoutePath {
        require(index in 0..path.via.size) { "insert index $index outside 0..${path.via.size}" }
        val via = path.via.toMutableList()
        via.add(index, snap(lat, lon, path.mode))
        // Legs before the new point keep their place, legs after it shift by one; the one or two touching it are new.
        val keep = ArrayList<RouteLeg?>()
        for (i in 0 until via.size - 1) {
            keep += when {
                i < index - 1 -> path.legs.getOrNull(i)
                i > index -> path.legs.getOrNull(i - 1)
                else -> null
            }
        }
        return build(path.mode, via, keep)
    }

    /**
     * Adds a via point where it fits: into the leg it lies nearest to when that leg passes within
     * [insertWithinM], else as the new end. Returns the path and the index the point got.
     */
    fun addVia(path: RoutePath, lat: Double, lon: Double, insertWithinM: Double = 150.0): Pair<RoutePath, Int> {
        if (path.via.size >= 2) {
            val (leg, d) = nearestLeg(path, lat, lon)
            if (leg >= 0 && d <= insertWithinM) return insertVia(path, leg + 1, lat, lon) to leg + 1
        }
        return insertVia(path, path.via.size, lat, lon) to path.via.size
    }

    /** Removes via point [index]; its two legs become one, rerouted. */
    fun removeVia(path: RoutePath, index: Int): RoutePath {
        require(index in path.via.indices) { "no via point $index (the route has ${path.via.size})" }
        val via = path.via.toMutableList()
        via.removeAt(index)
        val keep = ArrayList<RouteLeg?>()
        for (i in 0 until via.size - 1) {
            keep += when {
                i < index - 1 -> path.legs.getOrNull(i)
                i >= index -> path.legs.getOrNull(i + 1)
                else -> null
            }
        }
        return build(path.mode, via, keep)
    }

    /** The same via points in another [mode], every trail and straight leg rerouted (fixed legs stay). */
    fun setMode(path: RoutePath, mode: String): RoutePath =
        build(mode, path.via, path.legs.map { it.takeIf { l -> l.kind == LegKind.FIXED } })

    /** Every leg rerouted, fixed ones included (a pack with trails arrived). */
    fun reroute(path: RoutePath): RoutePath = build(path.mode, path.via, List(path.legs.size) { null })

    private fun build(mode: String, via: List<ViaPoint>, keep: List<RouteLeg?>): RoutePath {
        require(TravelMode.of(mode) != null) { TravelMode.unknownModeMessage(mode) } // T108 (ADR 0033 Decision 3)
        val planned = HashMap<Int, List<Instruction>>()
        val legs = if (via.size < 2) emptyList() else List(via.size - 1) { i -> keep.getOrNull(i) ?: leg(via[i], via[i + 1], mode) { planned[i] = it } }
        val path = RoutePath(mode, via, legs).quantized()
        remoteInner.clear()
        for ((i, inner) in planned) remoteInner[path.legs[i]] = inner
        return path
    }

    companion object {
        /** Spacing of elevation samples along a straight leg. */
        const val STRAIGHT_STEP_M = 25.0

        /** T108: the most points a straight leg gets (beyond 50 km the samples spread out). */
        const val MAX_STRAIGHT_POINTS = 2_001

        /** The leg of [path] passing nearest to ([lat], [lon]) and its distance in metres; (-1, inf) when there are no legs. */
        fun nearestLeg(path: RoutePath, lat: Double, lon: Double): Pair<Int, Double> {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for ((i, leg) in path.legs.withIndex()) {
                val d = RouteGeometry.nearestOnLine(leg.points, lat, lon).distanceM
                if (d < bestD) { bestD = d; best = i }
            }
            return best to bestD
        }

        /** The via point nearest to ([lat], [lon]) and its distance in metres; (-1, inf) for none. */
        fun nearestVia(path: RoutePath, lat: Double, lon: Double): Pair<Int, Double> {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for ((i, v) in path.via.withIndex()) {
                val d = TrackStats.distanceM(lat, lon, v.lat, v.lon)
                if (d < bestD) { bestD = d; best = i }
            }
            return best to bestD
        }

        /** True for a failure code that means the via points are simply not on one trail network. */
        fun isOffNetwork(code: String) = code in setOf(RouteFailure.START_OFF_NETWORK.code, RouteFailure.END_OFF_NETWORK.code, RouteFailure.NOT_CONNECTED.code)
    }
}

/** Small geometry on point lists in degrees (local equirectangular projection; fine at route scale). */
object RouteGeometry {
    /** Where a point projects onto a polyline: [distanceM] off it and [alongM] from its start. */
    data class Projection(val distanceM: Double, val alongM: Double, val lat: Double, val lon: Double)

    fun nearestOnLine(points: List<RoutePoint>, lat: Double, lon: Double): Projection {
        if (points.isEmpty()) return Projection(Double.POSITIVE_INFINITY, 0.0, lat, lon)
        val kx = cos(lat * kotlin.math.PI / 180) * M_PER_DEG
        val ky = M_PER_DEG
        var best = Projection(TrackStats.distanceM(lat, lon, points[0].lat, points[0].lon), 0.0, points[0].lat, points[0].lon)
        var along = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * ky
            val bx = (b.lon - lon) * kx; val by = (b.lat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) min(1.0, max(0.0, -(ax * dx + ay * dy) / len2)) else 0.0
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            val seg = TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            if (d < best.distanceM) best = Projection(d, along + t * seg, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            along += seg
        }
        return best
    }

    private const val M_PER_DEG = 111_195.0
}
