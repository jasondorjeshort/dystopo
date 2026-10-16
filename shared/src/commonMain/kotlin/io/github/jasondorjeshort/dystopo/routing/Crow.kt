package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.roundToInt

/**
 * T108: crow mode ([TravelMode.CROW], the idea `docs/ideas/2026-10-13-routing-modes-crow-bushwhack.md`): a straight
 * line between the points, with its distance, its initial bearing and the elevation profile from the DEM. No graph is
 * needed and none is read; no trail is named and no trail notice is given. The line is a [RouteEditor.straight] leg
 * (a point every [RouteEditor.STRAIGHT_STEP_M] with the DEM's elevation, [io.github.jasondorjeshort.dystopo.model.LegKind.STRAIGHT]
 * in a trip, drawn dashed like an off-network leg) and its directions are one arrival ([Directions.arrivalOnly]). Every
 * [Router] answers a crow request with [plan] without searching (the phone passes its DEM; a router without one gives
 * the line without heights). The time is the hike model's over the line ([CostModel.timeS]), a lower bound.
 */
object Crow {
    /** The straight line from ([fromLat], [fromLon]) to ([toLat], [toLon]) as a planned route (see the object). */
    fun plan(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        elevation: ElevationSampler?, name: String = "Route", ids: UuidGenerator = UuidGenerator(),
        units: ContourUnits = ContourUnits.METERS,
    ): PlannedRoute {
        val leg = RouteEditor(null, elevation).straight(ViaPoint(fromLat, fromLon), ViaPoint(toLat, toLon))
        val pts = leg.points
        val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
        val stats = TrackStats.of(track)
        val hasEle = pts.any { it.ele != null }
        val ascent = if (hasEle) stats.ascentM else null
        val descent = if (hasEle) stats.descentM else null
        val model = CostModel(TravelMode.CROW)
        val time = model.timeS(stats.distanceM, ascent ?: 0.0)
        val bearing = bearingDeg(fromLat, fromLon, toLat, toLon)
        val desc = UnitFormat.distance(stats.distanceM, units) + " in a straight line, bearing $bearing° (${Directions.compass(bearing.toDouble())})" +
            (if (ascent != null) ", +${UnitFormat.elevation(ascent, units)} / -${UnitFormat.elevation(descent ?: 0.0, units)}" else "") +
            " (crow; planned offline by Dystopo). " + TravelMode.CROW_NOTICE
        val route = Route(ids.next(), name, pts, description = desc, type = TravelMode.CROW.label)
        fun end(lat: Double, lon: Double) = EdgeSnap(-1, 0, 0.0, lat, lon, 0.0)
        return PlannedRoute(
            route, TravelMode.CROW, stats.distanceM, ascent, descent, time, TrackStats.profile(track, Router.PROFILE_SAMPLES),
            cost = stats.distanceM, edges = 0, start = end(fromLat, fromLon), end = end(toLat, toLon), expanded = 0,
            metersByClass = emptyMap(), via = emptyList(), memory = linkedMapOf("crow" to true, "elevation" to hasEle),
            instructions = Directions.arrivalOnly(pts),
        )
    }

    /** The initial great-circle bearing from the first point to the second, whole degrees 0 to 359. */
    fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int =
        ((TrackStats.bearingDeg(fromLat, fromLon, toLat, toLon) % 360 + 360) % 360).roundToInt() % 360
}
