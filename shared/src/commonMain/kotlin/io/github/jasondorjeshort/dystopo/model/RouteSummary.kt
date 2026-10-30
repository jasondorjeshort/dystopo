package io.github.jasondorjeshort.dystopo.model

import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.roundToLong

/** A route waypoint placed on the route: [alongM] from the start, [offM] beside it. */
data class WaypointAt(val id: String, val name: String, val alongM: Double, val offM: Double, val eleM: Double?)

/** T108: one leg's length along it and the initial bearing from its start to its end via point (whole degrees). */
data class LegBearing(val distanceM: Double, val bearingDeg: Int)

/**
 * The trip summary of a [RoutePlan] (T60): distance, ascent and descent by T24's rules
 * ([TrackStats]: the 3 m turning-point hysteresis), the estimated time by T45's hike model
 * ([CostModel.timeS]: Naismith for hiking; T89: for driving the sum of the legs' router times,
 * [RouteLeg.timeS], with [CostModel.DRIVE_SUMMARY_KMH] only for legs that have none), T24's profile sampling ([TrackStats.profile]) and where
 * each waypoint sits along the route (profile markers). A pure function of the plan, so every replica
 * shows the same numbers. T108: [bearings], each leg's length and the initial bearing from its start to its end
 * via point (for a crow route, the line's own bearing).
 */
data class RouteSummary(
    val mode: String,
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    val timeS: Double,
    val points: Int,
    val viaPoints: Int,
    val legs: Int,
    /** Metres of the route on straight legs (no trail). */
    val straightM: Double,
    val profile: List<ProfileSample>,
    val waypoints: List<WaypointAt>,
    val bearings: List<LegBearing> = emptyList(),
) {
    fun json(profileSamples: Boolean = true): Map<String, Any?> = linkedMapOf(
        "mode" to mode, "distanceM" to r1(distanceM), "ascentM" to ascentM?.let(::r1), "descentM" to descentM?.let(::r1),
        "minEleM" to minEleM?.let(::r1), "maxEleM" to maxEleM?.let(::r1),
        "timeS" to timeS.roundToLong(), "time" to hm(timeS), "points" to points, "viaPoints" to viaPoints, "legs" to legs,
        "straightM" to r1(straightM),
        "waypoints" to waypoints.map { linkedMapOf("id" to it.id, "name" to it.name, "alongM" to r1(it.alongM), "offM" to r1(it.offM), "eleM" to it.eleM?.let(::r1)) },
        "bearings" to bearings.map { linkedMapOf("distanceM" to r1(it.distanceM), "bearingDeg" to it.bearingDeg) }, // T108
        "profile" to if (profileSamples) profile.map { listOf(r1(it.distanceM), it.eleM?.let(::r1)) } else null,
    )

    companion object {
        fun of(plan: RoutePlan, samples: Int = PROFILE_SAMPLES): RouteSummary = of(plan.path, plan.waypoints, samples)

        fun of(path: RoutePath, waypoints: List<Waypoint>, samples: Int = PROFILE_SAMPLES): RouteSummary {
            val pts = path.points
            val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
            val stats = TrackStats.of(track)
            val travel = TravelMode.of(path.mode) ?: TravelMode.HIKE
            val time = if (travel == TravelMode.DRIVE && path.legs.isNotEmpty()) driveTimeS(path) else CostModel(travel).pathTimeS(if (stats.ascentM != null) track[0] else emptyList(), stats.distanceM, stats.ascentM ?: 0.0) // T164: the user's fitted pace when there is one
            val straight = path.legs.filter { it.kind == LegKind.STRAIGHT }.sumOf { leg ->
                leg.points.zipWithNext { a, b -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }.sum()
            }
            val profile = if (samples >= 2 && pts.size >= 2) TrackStats.profile(track, samples) else emptyList()
            val along = waypoints.map { w ->
                val p = RouteGeometry.nearestOnLine(pts, w.lat, w.lon)
                WaypointAt(w.id, w.name, if (pts.isEmpty()) 0.0 else p.alongM, if (pts.isEmpty()) 0.0 else p.distanceM, w.ele ?: eleAt(profile, p.alongM))
            }
            val bearings = path.legs.map { leg ->
                val a = leg.points.first()
                val b = leg.points.last()
                LegBearing(leg.points.zipWithNext { p, q -> TrackStats.distanceM(p.lat, p.lon, q.lat, q.lon) }.sum(), io.github.jasondorjeshort.dystopo.routing.Crow.bearingDeg(a.lat, a.lon, b.lat, b.lon))
            }
            return RouteSummary(
                path.mode, stats.distanceM, stats.ascentM, stats.descentM, stats.minEleM, stats.maxEleM, time,
                pts.size, path.via.size, path.legs.size, straight, profile, along, bearings,
            )
        }

        /**
         * T89: a drive route's time: each leg's router time ([RouteLeg.timeS], the router's per-road speeds, the
         * number `devtool route` and `GET /route` report), else the leg's length at [CostModel.DRIVE_SUMMARY_KMH]
         * (straight and imported legs).
         */
        fun driveTimeS(path: RoutePath): Double = path.legs.sumOf { leg ->
            leg.timeS ?: CostModel(TravelMode.DRIVE).timeS(leg.points.zipWithNext { a, b -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }.sum(), 0.0)
        }

        /** The profile's elevation at [d] metres (linear between samples), or null. */
        fun eleAt(profile: List<ProfileSample>, d: Double): Double? {
            if (profile.isEmpty()) return null
            val i = profile.indexOfFirst { it.distanceM >= d }
            if (i <= 0) return profile[if (i == 0) 0 else profile.size - 1].eleM
            val a = profile[i - 1]
            val b = profile[i]
            val ea = a.eleM ?: return b.eleM
            val eb = b.eleM ?: return ea
            val span = b.distanceM - a.distanceM
            return if (span > 0) ea + (eb - ea) * (d - a.distanceM) / span else ea
        }

        const val PROFILE_SAMPLES = 100

        fun r1(v: Double) = (v * 10).roundToLong() / 10.0

        fun hm(s: Double): String {
            val m = (s / 60).roundToLong()
            return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        }
    }
}
