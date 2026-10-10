package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Navigation thresholds (T61). */
data class NavOptions(
    /** A fix farther than this from the line counts as off the line. */
    val offRouteM: Double = 50.0,
    /** ...and only after it has stayed off for this long (fix time) is the walker off route. */
    val offRouteSeconds: Double = 10.0,
    /** Back on route once a fix is this close again (below [offRouteM], so a walker on the edge does not flap). */
    val backOnRouteM: Double = 30.0,
    /** Arrived when this close to the end of the line. */
    val arriveM: Double = 30.0,
    /** Fixes less accurate than this are ignored for the off-route decision (they still move the progress). */
    val maxAccuracyM: Double = 75.0,
    val mode: TravelMode = TravelMode.HIKE,
) {
    init {
        require(offRouteM > 0 && backOnRouteM in 0.0..offRouteM && offRouteSeconds >= 0 && arriveM > 0) { "bad navigation options" }
    }
}

/** Something that happened while navigating; times are the fixes' times (epoch ms). */
data class NavEvent(
    val type: String,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val alongM: Double,
    /** OFF_ROUTE: seconds from the first fix off the line to the event; BACK_ON_ROUTE: seconds off route. */
    val afterS: Double? = null,
    /** BACK_ON_ROUTE: the farthest the walker got from the line. */
    val maxDistanceM: Double? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "type" to type, "timeMs" to timeMs, "lat" to r6(lat), "lon" to r6(lon), "distanceM" to r1(distanceM), "alongM" to r1(alongM),
        "afterS" to afterS?.let(::r1), "maxDistanceM" to maxDistanceM?.let(::r1),
    )

    companion object {
        const val OFF_ROUTE = "offRoute"
        const val BACK_ON_ROUTE = "backOnRoute"
        const val ARRIVED = "arrived"
        const val WAYPOINT = "waypoint"
    }
}

/** Distance and time to somewhere along the line. */
data class NavLeg(val name: String, val distanceM: Double, val ascentM: Double, val timeS: Double, val etaMs: Long) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name, "distanceM" to r1(distanceM), "ascentM" to r1(ascentM), "timeS" to timeS.roundToLong(), "etaMs" to etaMs,
    )
}

/** Which way to walk to get back on the line: [bearingDeg] true, [distanceM], and an 8-point compass word. */
data class RejoinHint(val lat: Double, val lon: Double, val bearingDeg: Double, val distanceM: Double, val relativeDeg: Double?) {
    val compass: String get() = compassWord(bearingDeg)

    /** "Head NE 140 m to rejoin", with [distance] already formatted in the user's units; [relativeDeg] (to the heading) adds "ahead-right" and so on. */
    fun text(distance: String): String = buildString {
        append("Head ").append(compass).append(' ').append(distance).append(" to rejoin")
        relativeDeg?.let { append(" (").append(relativeWord(it)).append(')') }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to r6(lat), "lon" to r6(lon), "bearingDeg" to r1(bearingDeg), "compass" to compass,
        "distanceM" to r1(distanceM), "relativeDeg" to relativeDeg?.let(::r1), "relative" to relativeDeg?.let(::relativeWord),
    )

    companion object {
        private val WORDS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun compassWord(deg: Double): String = WORDS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

        /** -180..180 relative to the heading: ahead, ahead-right, right, behind-right, behind, ... */
        fun relativeWord(rel: Double): String {
            val a = abs(rel)
            val side = if (rel >= 0) "right" else "left"
            return when {
                a < 22.5 -> "ahead"
                a < 67.5 -> "ahead-$side"
                a < 112.5 -> side
                a < 157.5 -> "behind-$side"
                else -> "behind"
            }
        }
    }
}

/** Everything navigation knows after a fix. */
data class NavState(
    val fixes: Int,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val projection: Projection,
    val offRoute: Boolean,
    /** Seconds the current excursion off the line has lasted (0 when on it). */
    val offForS: Double,
    val next: NavLeg?,
    val end: NavLeg,
    val rejoin: RejoinHint?,
    val arrived: Boolean,
    val headingDeg: Double?,
) {
    val progress: Double get() = projection.alongM
}

/**
 * Follows a walker along a [NavPath] (T61): progress along the line, off-route detection, distance
 * and time to the next waypoint and to the end, and the way back when off the line. Pure: fed fixes
 * (position and fix time), it answers [NavState] and appends [events]; the app wires it to the GPS,
 * injected fixes and replays.
 *
 * - Off route: the perpendicular distance to the line is over [NavOptions.offRouteM] on every fix for
 *   [NavOptions.offRouteSeconds] (fix time, so a replay at any speed decides the same); one
 *   [NavEvent.OFF_ROUTE] then, and a [NavEvent.BACK_ON_ROUTE] once a fix is within
 *   [NavOptions.backOnRouteM] again (the gap between the two thresholds stops flapping on the edge).
 * - Time: the routing hike (or bike) model ([CostModel.timeS], Naismith for hiking) over the
 *   remaining distance and remaining climb of the line, so the ETA at the start is the planned time.
 *   Off route, the straight-line way back is added.
 */
class Navigator(val path: NavPath, val options: NavOptions = NavOptions()) {
    private val model = CostModel(options.mode)
    private val _events = ArrayList<NavEvent>()
    val events: List<NavEvent> get() = _events

    var state: NavState? = null
        private set
    private var lastAlong: Double? = null
    private var offCandidateSince: Long? = null
    private var offSince: Long? = null
    private var offMax = 0.0
    private var arrived = false
    private var maxAlong = 0.0
    private var lastTime: Long? = null
    private var passed = 0
    var fixes = 0
        private set

    /** Total time of the line by the model (what a route plan would say). */
    val plannedTimeS: Double get() = model.timeS(path.totalM, path.totalAscentM)

    fun onFix(lat: Double, lon: Double, timeMs: Long, accuracyM: Double? = null, headingDeg: Double? = null): NavState {
        fixes++
        // Before any progress, look from the start: on a loop the start and the end are the same place.
        val p = path.project(lat, lon, lastAlong ?: 0.0)
        val before = maxAlong
        lastAlong = p.alongM
        if (p.distanceM <= options.offRouteM) maxAlong = max(maxAlong, p.alongM)
        // A fix older than the last one (a replay after live fixes, a clock change) starts the off-route clock over.
        lastTime?.let { if (timeMs < it) restartClock(timeMs) }
        lastTime = timeMs
        val usable = accuracyM == null || accuracyM <= options.maxAccuracyM
        if (usable) updateOffRoute(p, lat, lon, timeMs)
        while (passed < path.waypoints.size && path.waypoints[passed].alongM <= p.alongM + WAYPOINT_REACHED_M && offSince == null) {
            val w = path.waypoints[passed]
            if (w.alongM > ARRIVE_IGNORE_START_M) _events += NavEvent(NavEvent.WAYPOINT + ":" + w.name, timeMs, lat, lon, p.distanceM, p.alongM)
            passed++
        }
        val offRoute = offSince != null
        val rejoinM = if (offRoute) p.distanceM else 0.0
        val endRemaining = path.totalM - p.alongM
        // Arrival needs progress on an earlier fix: the end of a loop is also its start.
        if (!arrived && endRemaining <= options.arriveM && p.distanceM <= options.offRouteM && path.totalM > options.arriveM * 2 && before >= path.totalM / 2) {
            arrived = true
            _events += NavEvent(NavEvent.ARRIVED, timeMs, lat, lon, p.distanceM, p.alongM)
        }
        val end = leg("End", path.totalM, p.alongM, rejoinM, timeMs)
        val nextWp = path.waypoints.drop(passed).firstOrNull { it.alongM > p.alongM + WAYPOINT_REACHED_M }
        val next = nextWp?.let { leg(it.name, it.alongM, p.alongM, rejoinM, timeMs) }
        val rejoin = if (offRoute || p.distanceM > options.offRouteM) {
            val b = TrackStats.bearingDeg(lat, lon, p.lat, p.lon)
            RejoinHint(p.lat, p.lon, b, p.distanceM, headingDeg?.let { rel(b - it) })
        } else null
        val s = NavState(
            fixes = fixes, timeMs = timeMs, lat = lat, lon = lon, projection = p, offRoute = offRoute,
            offForS = offSince?.let { (timeMs - it) / 1000.0 } ?: 0.0,
            next = next, end = end, rejoin = rejoin, arrived = arrived, headingDeg = headingDeg,
        )
        state = s
        return s
    }

    private fun restartClock(timeMs: Long) {
        if (offCandidateSince != null) offCandidateSince = timeMs
        if (offSince != null) offSince = timeMs
    }

    private fun updateOffRoute(p: Projection, lat: Double, lon: Double, timeMs: Long) {
        val d = p.distanceM
        if (offSince == null) {
            if (d > options.offRouteM) {
                val since = offCandidateSince ?: timeMs.also { offCandidateSince = it }
                offMax = max(offMax, d)
                if ((timeMs - since) / 1000.0 >= options.offRouteSeconds) {
                    offSince = since
                    _events += NavEvent(NavEvent.OFF_ROUTE, timeMs, lat, lon, d, p.alongM, afterS = (timeMs - since) / 1000.0)
                }
            } else {
                offCandidateSince = null
                offMax = 0.0
            }
        } else {
            offMax = max(offMax, d)
            if (d <= options.backOnRouteM) {
                _events += NavEvent(NavEvent.BACK_ON_ROUTE, timeMs, lat, lon, d, p.alongM, afterS = (timeMs - offSince!!) / 1000.0, maxDistanceM = offMax)
                offSince = null
                offCandidateSince = null
                offMax = 0.0
            }
        }
    }

    private fun leg(name: String, toAlongM: Double, fromAlongM: Double, rejoinM: Double, nowMs: Long): NavLeg {
        val dist = max(0.0, toAlongM - fromAlongM) + rejoinM
        val climb = max(0.0, path.ascentAt(toAlongM) - path.ascentAt(fromAlongM))
        val t = model.timeS(dist, climb)
        return NavLeg(name, dist, climb, t, nowMs + (t * 1000).roundToLong())
    }

    companion object {
        /** A waypoint counts as reached (and the next one becomes "next") this far before it. */
        const val WAYPOINT_REACHED_M = 15.0
        private const val ARRIVE_IGNORE_START_M = 1.0

        /** An angle in -180..180. */
        fun rel(deg: Double): Double {
            var d = (deg % 360 + 360) % 360
            if (d > 180) d -= 360
            return d
        }
    }
}

internal fun r1(v: Double) = (v * 10).roundToLong() / 10.0
internal fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6
