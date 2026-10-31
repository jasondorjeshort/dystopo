package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.StatsOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.Waypoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** A vertex of the line being followed. */
data class NavPoint(val lat: Double, val lon: Double, val ele: Double? = null)

/** A named point along the line, at [alongM] metres from its start. */
data class NavWaypoint(val name: String, val lat: Double, val lon: Double, val alongM: Double)

/**
 * Where a position sits relative to a [NavPath]: the closest point on the line ([lat], [lon]), how far
 * along the line that is ([alongM]), and the perpendicular distance to it ([distanceM]).
 */
data class Projection(val alongM: Double, val distanceM: Double, val lat: Double, val lon: Double, val segment: Int)

/**
 * The line navigation follows (T61): a planned route's points or a track's points (segments joined
 * in order), with cumulative distance and ascent so the remaining distance and climb from any point
 * along it are a lookup. Distances along the line are great-circle sums ([TrackStats.distanceM]),
 * so they match the route and track stats; projection onto a segment uses a local flat frame, which
 * is exact to centimetres at trail-segment lengths.
 */
class NavPath(val name: String, points: List<NavPoint>, waypoints: List<Pair<String, NavPoint>> = emptyList()) {
    val points: List<NavPoint> = points.filterIndexed { i, p -> i == 0 || p.lat != points[i - 1].lat || p.lon != points[i - 1].lon }

    /** Metres from the start to each point. */
    val cumM = DoubleArray(this.points.size)

    /**
     * Metres climbed from the start to each point (0 without elevations). T65: the total is the route's one
     * ascent figure, T24's rule ([TrackStats.ascentDescent] with the 3 m turning-point hysteresis, what the
     * route card, the trip card and the Routes list show); the rises between vertices only spread it along
     * the line (each rise counts in proportion), so the remaining climb at the start is that same figure.
     */
    val cumAscentM = DoubleArray(this.points.size)
    val hasElevation: Boolean = this.points.count { it.ele != null } >= 2

    /** Descent by the same rule as [totalAscentM] (T65). */
    val totalDescentM: Double

    init {
        require(this.points.size >= 2) { "a path needs at least two distinct points" }
        var lastEle: Double? = this.points[0].ele
        for (i in 1 until this.points.size) {
            val a = this.points[i - 1]
            val b = this.points[i]
            cumM[i] = cumM[i - 1] + TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            val e = b.ele
            cumAscentM[i] = cumAscentM[i - 1] + if (e != null && lastEle != null) max(0.0, e - lastEle) else 0.0
            if (e != null) lastEle = e
        }
        val (up, down) = TrackStats.ascentDescent(this.points.mapNotNull { it.ele }, StatsOptions().elevationThresholdM)
        totalDescentM = down
        val raw = cumAscentM.last()
        if (raw > 0) for (i in cumAscentM.indices) cumAscentM[i] *= up / raw
    }

    val totalM: Double get() = cumM.last()
    val totalAscentM: Double get() = cumAscentM.last()

    /** The waypoints projected onto the line, in travel order. */
    val waypoints: List<NavWaypoint> = waypoints.map { (n, p) ->
        NavWaypoint(n, p.lat, p.lon, project(p.lat, p.lon).alongM)
    }.sortedBy { it.alongM }

    /** Climb from the start to [alongM] (interpolated within a segment). */
    fun ascentAt(alongM: Double): Double {
        val i = segmentAt(alongM)
        val f = fraction(i, alongM)
        return cumAscentM[i] + (cumAscentM[i + 1] - cumAscentM[i]) * f
    }

    /** The point [alongM] metres from the start. */
    fun pointAt(alongM: Double): NavPoint {
        val i = segmentAt(alongM)
        val f = fraction(i, alongM)
        val a = points[i]
        val b = points[i + 1]
        val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele
        return NavPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, ele)
    }

    private fun segmentAt(alongM: Double): Int {
        var lo = 0
        var hi = cumM.size - 1
        val d = alongM.coerceIn(0.0, totalM)
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumM[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }

    private fun fraction(i: Int, alongM: Double): Double {
        val len = cumM[i + 1] - cumM[i]
        return if (len <= 0) 0.0 else ((alongM - cumM[i]) / len).coerceIn(0.0, 1.0)
    }

    /** Closest point on segment [i] to ([lat], [lon]). */
    fun projectOnSegment(i: Int, lat: Double, lon: Double): Projection {
        val a = points[i]
        val b = points[i + 1]
        val kx = cos(lat * DEG) * M_PER_DEG
        val ky = M_PER_DEG
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (lon - a.lon) * kx
        val py = (lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        val segLen = cumM[i + 1] - cumM[i]
        return Projection(cumM[i] + segLen * t, sqrt(dx * dx + dy * dy), a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, i)
    }

    /**
     * The closest point of the whole line; with [nearAlongM] (the last known progress), a point within
     * [behindM] behind to [aheadM] ahead of it wins unless the best point elsewhere is more than
     * [jumpM] closer. That keeps progress on the right leg where a loop or an out-and-back passes the
     * same ground twice (the start and end of a loop, both directions of a spur).
     *
     * T180, the ahead tie-break: inside the window, every pass of the line near the position (a local minimum of the
     * distance along the line, [passes]) within [snapM] is a candidate, and the one with the least sum of its distance
     * from [nearAlongM] along the line (a pass behind counting [BEHIND_WEIGHT] times) and its distance from the position
     * wins (so a pass much farther off, the other side of a switchback's corner, does not win on nearness alone); so where two legs of an out-and-back lie on the same
     * ground, progress stays on the leg it was on, and at the far end it goes on to the way back rather than running
     * backwards. Only when no pass in the window is within [snapM] (off the line, or the walker is somewhere else
     * after a gap) does the rest of the line count, by the old rule.
     */
    fun project(
        lat: Double,
        lon: Double,
        nearAlongM: Double? = null,
        behindM: Double = 150.0,
        aheadM: Double = 2000.0,
        jumpM: Double = 50.0,
        snapM: Double = SNAP_M,
    ): Projection {
        if (nearAlongM != null) {
            val from = nearAlongM - behindM
            val to = nearAlongM + aheadM
            val best = passes(lat, lon, from, to).filter { it.distanceM <= snapM }.minByOrNull { cost(it.alongM, nearAlongM) + it.distanceM }
            if (best != null) return best
        }
        var best: Projection? = null
        var bestNear: Projection? = null
        for (i in 0 until points.size - 1) {
            val p = projectOnSegment(i, lat, lon)
            if (best == null || p.distanceM < best.distanceM) best = p
            if (nearAlongM != null) {
                // The segment overlaps the window: take its closest point inside the window.
                val s0 = cumM[i]
                val s1 = cumM[i + 1]
                if (s1 >= nearAlongM - behindM && s0 <= nearAlongM + aheadM) {
                    val q = if (p.alongM in (nearAlongM - behindM)..(nearAlongM + aheadM)) p else clampToWindow(i, lat, lon, nearAlongM - behindM, nearAlongM + aheadM)
                    if (bestNear == null || q.distanceM < bestNear.distanceM) bestNear = q
                }
            }
        }
        val b = best!!
        return if (bestNear != null && bestNear.distanceM <= b.distanceM + jumpM) bestNear else b
    }

    /**
     * T180: the passes of the line by a position between [fromM] and [toM] along it: each local minimum of the distance
     * to the line (a segment's inner closest point, or a vertex closer than both neighbouring segments' insides, or an
     * end of the line), one [Projection] each, in order along the line. An out-and-back's two legs give two passes;
     * a walker beyond the far end gives one (the far vertex). Only the segments over the window are looked at.
     */
    fun passes(lat: Double, lon: Double, fromM: Double = 0.0, toM: Double = totalM): List<Projection> {
        if (toM < 0 || fromM > totalM || toM < fromM) return emptyList()
        val first = segmentAt(fromM.coerceAtLeast(0.0))
        val last = segmentAt(toM.coerceAtMost(totalM))
        val out = ArrayList<Projection>(4)
        var prev: Projection? = if (first > 0) projectOnSegment(first - 1, lat, lon) else null
        var cur = projectOnSegment(first, lat, lon)
        for (i in first..last) {
            val next = if (i + 1 < points.size - 1) projectOnSegment(i + 1, lat, lon) else null
            val len = cumM[i + 1] - cumM[i]
            val t = if (len <= 0) 0.0 else (cur.alongM - cumM[i]) / len
            val inner = t > T_EPS && t < 1 - T_EPS
            // A vertex is a pass once, from the segment that starts at it: the one before ends at it.
            val atStart = t <= T_EPS && (prev == null || prev.distanceM >= cur.distanceM - 1e-6) && (prev == null || prev.alongM >= cumM[i] - 1e-6)
            val atEnd = t >= 1 - T_EPS && next == null
            if ((inner || atStart || atEnd) && cur.alongM in fromM..toM) out += cur
            prev = cur
            cur = next ?: break
        }
        return out
    }

    /** T180: the ahead tie-break's cost of a pass at [alongM] from progress [nearM]: its distance, behind counting double. */
    private fun cost(alongM: Double, nearM: Double): Double = if (alongM >= nearM) alongM - nearM else BEHIND_WEIGHT * (nearM - alongM)

    private fun clampToWindow(i: Int, lat: Double, lon: Double, from: Double, to: Double): Projection {
        val p = projectOnSegment(i, lat, lon)
        val along = p.alongM.coerceIn(max(from, cumM[i]), minOf(to, cumM[i + 1]))
        val q = pointAt(along)
        return Projection(along, TrackStats.distanceM(lat, lon, q.lat, q.lon), q.lat, q.lon, i)
    }

    companion object {
        private const val DEG = PI / 180
        private const val M_PER_DEG = TrackStats.EARTH_RADIUS_M * DEG
        private const val T_EPS = 1e-9

        /**
         * T180: a pass of the line this close to the position is on it for the tie-break and the trackers (GPS on two
         * walks of one trail is a few metres apart, 15 to 20 under trees; switchback legs are rarely closer than this).
         */
        const val SNAP_M = 25.0

        /** T180: a pass behind the last progress counts this many times its distance against one ahead. */
        const val BEHIND_WEIGHT = 2.0

        /** A planned route: its points; named points become waypoints. */
        fun of(route: Route, extraWaypoints: List<Waypoint> = emptyList()): NavPath = NavPath(
            route.name,
            route.points.map { NavPoint(it.lat, it.lon, it.ele) },
            route.points.filter { !it.name.isNullOrBlank() }.map { it.name!! to NavPoint(it.lat, it.lon, it.ele) } +
                extraWaypoints.map { it.name to NavPoint(it.lat, it.lon, it.ele) },
        )

        /** A track: every segment's points in order (navigation crosses the gaps); [waypoints] are projected onto it. */
        fun of(track: Track, waypoints: List<Waypoint> = emptyList()): NavPath = NavPath(
            track.name,
            track.points.map { NavPoint(it.lat, it.lon, it.ele) },
            waypoints.map { it.name to NavPoint(it.lat, it.lon, it.ele) },
        )
    }
}

/**
 * T180: progress along a [NavPath] fix by fix, the retrace fix of T163's follow-up (a line that passes the same ground
 * twice, an out-and-back, made progress run backwards on the way back and never reach "arrived").
 *
 * - **Sequential:** each fix is placed by [NavPath.project] near the last progress (the window, the ahead tie-break),
 *   so on two legs over the same ground progress stays on its leg and at the far end goes on to the way back.
 * - **The turn (hysteresis):** a walker who turns back before the far end first runs backwards on the leg out; once
 *   that backward stretch reaches [turnBackM] (an unbroken run, a forward step of more than [RUN_SLACK_M] ends it), a
 *   pass of the line ahead of where it began, within [snapM], takes over: progress jumps to the way back ([jumps]).
 *   Shorter steps back (a dropped glove, a look at the junction) never jump.
 * - **The near-start guard:** no jump while the backward run began within [nearStartM] of the start.
 * - **The full search** (the whole line, still the pass nearest the last progress) only after a gap of [longGapMs]
 *   between fixes or when nothing in the window is within [snapM] (off the line); else only the window is read.
 */
class PathTracker(
    val path: NavPath,
    val snapM: Double = NavPath.SNAP_M,
    val turnBackM: Double = TurnAround.TURNED_BACK_M,
    val nearStartM: Double = TurnAround.NEAR_START_M,
    val longGapMs: Long = LONG_GAP_MS,
) {
    /** The progress after the last fix; null before the first. */
    var alongM: Double? = null
        private set
    private var lastTimeMs: Long? = null
    private var runStartM: Double? = null
    private var runMinM = 0.0

    /** Jumps to the way back after a sustained backward stretch, and full-line searches made. */
    var jumps = 0
        private set
    var fullSearches = 0
        private set

    /** The last jump: from where (the backward run's start and its end) to where. */
    var lastJump: Triple<Double, Double, Double>? = null
        private set

    fun place(lat: Double, lon: Double, timeMs: Long): Projection {
        val near = alongM
        val gap = lastTimeMs?.let { timeMs - it > longGapMs } ?: false
        lastTimeMs = timeMs
        // Before any progress, look from the start: on a loop the start and the end are the same place.
        var p = if (gap && near != null) full(lat, lon, near) else path.project(lat, lon, near ?: 0.0, snapM = snapM)
        if (p.distanceM > snapM && !gap) {
            val f = full(lat, lon, near ?: 0.0)
            if (f.distanceM <= snapM) p = f
        }
        if (near != null && p.distanceM <= snapM) {
            if (p.alongM < near) {
                val start = runStartM ?: near.also { runStartM = it; runMinM = it }
                runMinM = minOf(runMinM, p.alongM)
                if (start - p.alongM >= turnBackM && start >= nearStartM) {
                    val ahead = path.passes(lat, lon, start + snapM, path.totalM).filter { it.distanceM <= snapM }.minByOrNull { it.alongM }
                    if (ahead != null) {
                        jumps++
                        lastJump = Triple(start, p.alongM, ahead.alongM)
                        p = ahead
                        runStartM = null
                    }
                }
            } else if (runStartM != null && p.alongM > runMinM + RUN_SLACK_M) {
                runStartM = null
            }
        } else if (p.distanceM > snapM) {
            runStartM = null
        }
        alongM = p.alongM
        return p
    }

    private fun full(lat: Double, lon: Double, near: Double): Projection {
        fullSearches++
        return path.project(lat, lon, near, behindM = path.totalM + 1, aheadM = path.totalM + 1, snapM = snapM)
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "alongM" to alongM?.let(::r1), "jumps" to jumps, "fullSearches" to fullSearches,
        "lastJump" to lastJump?.let { (a, b, c) -> linkedMapOf("runFromM" to r1(a), "runToM" to r1(b), "toM" to r1(c)) },
        "snapM" to snapM, "turnBackM" to turnBackM, "nearStartM" to nearStartM,
    )

    companion object {
        /** A gap between fixes this long (a tunnel, the phone off) reads the whole line again. */
        const val LONG_GAP_MS = 120_000L

        /** A backward run survives forward noise of this much (4 m of GPS noise moves progress up to 20 m between fixes). */
        const val RUN_SLACK_M = 25.0
    }
}
