package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.Geodesic
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.roundToInt

/**
 * T256: shown to people as "Line" ([TravelMode.display]; the stored value stays `crow`, ADR 0033): the line follows the
 * geodesic on the WGS84 ellipsoid ([io.github.jasondorjeshort.dystopo.geo.Geodesic]), its length and initial bearing are
 * the geodesic's, it works anywhere (no pack or coverage is asked for) and is split at longitude 180. A line with no
 * terrain heights under it (the open ocean, or no DEM on the phone there) plans with no profile and its description
 * says so ([NO_TERRAIN]).
 *
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
        val distance = lengthM(pts) // T256: the geodesic's length, not haversine hops
        val hasEle = pts.any { it.ele != null }
        val ascent = if (hasEle) stats.ascentM else null
        val descent = if (hasEle) stats.descentM else null
        val model = CostModel(TravelMode.CROW)
        val time = model.timeS(distance, ascent ?: 0.0)
        val bearing = bearingDeg(fromLat, fromLon, toLat, toLon)
        val desc = UnitFormat.distance(distance, units) + " in a straight line, bearing $bearing° (${Directions.compass(bearing.toDouble())})" +
            (if (ascent != null) ", +${UnitFormat.elevation(ascent, units)} / -${UnitFormat.elevation(descent ?: 0.0, units)}" else ", " + NO_TERRAIN.replaceFirstChar { it.lowercase() }) +
            " (line; planned offline by Dystopo). " + TravelMode.CROW_NOTICE
        val route = Route(ids.next(), name, pts, description = desc, type = TravelMode.CROW.label)
        fun end(lat: Double, lon: Double) = EdgeSnap(-1, 0, 0.0, lat, lon, 0.0)
        return PlannedRoute(
            route, TravelMode.CROW, distance, ascent, descent, time, if (hasEle) TrackStats.profile(track, Router.PROFILE_SAMPLES) else emptyList(),
            cost = distance, edges = 0, start = end(fromLat, fromLon), end = end(toLat, toLon), expanded = 0,
            metersByClass = emptyMap(), via = emptyList(), memory = linkedMapOf("crow" to true, "elevation" to hasEle),
            instructions = Directions.arrivalOnly(pts).map { it.copy(alongM = distance) }, // T256: at the geodesic's length
        )
    }

    /** The initial bearing of the geodesic from the first point to the second, whole degrees 0 to 359 (T256: on the ellipsoid). */
    fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int =
        Geodesic.initialBearingDeg(fromLat, fromLon, toLat, toLon).roundToInt() % 360

    /** T256: the length of a line of points along the geodesic, each hop on the ellipsoid. */
    fun lengthM(points: List<RoutePoint>): Double = Geodesic.lengthM(points, { it.lat }, { it.lon })

    /** T256 (ADR 0031: listed for the owner): what a line's card and description say when no terrain lies under it. */
    const val NO_TERRAIN = "No terrain under this line, so no elevation profile"
}
