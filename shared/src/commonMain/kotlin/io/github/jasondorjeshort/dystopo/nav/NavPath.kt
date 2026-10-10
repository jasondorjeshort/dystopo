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
     */
    fun project(
        lat: Double,
        lon: Double,
        nearAlongM: Double? = null,
        behindM: Double = 150.0,
        aheadM: Double = 2000.0,
        jumpM: Double = 50.0,
    ): Projection {
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

    private fun clampToWindow(i: Int, lat: Double, lon: Double, from: Double, to: Double): Projection {
        val p = projectOnSegment(i, lat, lon)
        val along = p.alongM.coerceIn(max(from, cumM[i]), minOf(to, cumM[i + 1]))
        val q = pointAt(along)
        return Projection(along, TrackStats.distanceM(lat, lon, q.lat, q.lon), q.lat, q.lon, i)
    }

    companion object {
        private const val DEG = PI / 180
        private const val M_PER_DEG = TrackStats.EARTH_RADIUS_M * DEG

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
