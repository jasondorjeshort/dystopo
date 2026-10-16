package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.routing.EdgeSnap
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * T108 (a T102 follow-up): the route on the route card that has not been kept, as a small file
 * (`files/route-session.json`), so it outlives the process. The card's state already lives in [RouteSession], an
 * object of the process, so an activity recreated for a configuration change (the system font scale; the manifest
 * does not list `fontScale` in `configChanges`, on purpose) finds it there; but when the process goes (Android stops a
 * background app while its user is in the system settings changing the font size, a reinstall, a force stop) the
 * route went with it. [RouteSession] writes this after every plan and deletes it on Clear and Keep, and reads it back
 * once per process.
 *
 * What is kept: the route's points as a trip path value (the [RoutePath.encode] codec, 1e-7 degrees and centimetres),
 * its id, name, description and mode, the summary numbers, the trails it follows, the turn directions (the server's
 * JSON form, [Instruction.json]) and the start and end the user pressed. Pure: [encode] and [decode] are tested.
 */
object SessionRoute {
    const val VERSION = 1
    const val FILE_NAME = "route-session.json"

    /** What [decode] gives back: the plan and the start and end the card was planned from. */
    class Restored(val plan: PlannedRoute, val start: Pair<Double, Double>, val end: Pair<Double, Double>, val savedAt: Long)

    fun encode(p: PlannedRoute, start: Pair<Double, Double>, end: Pair<Double, Double>, now: Long): String = JsonWriter.write(
        linkedMapOf(
            "version" to VERSION, "savedAt" to now, "id" to p.route.id, "name" to p.route.name, "description" to p.route.description,
            "mode" to p.mode.label,
            "path" to RoutePath.planned(p.route.points, p.mode.label, p.timeS).encode(),
            "distanceM" to p.distanceM, "ascentM" to p.ascentM, "descentM" to p.descentM, "timeS" to p.timeS,
            "via" to p.via.map { listOf(it.first, it.second) },
            "instructions" to p.instructions.map { it.json(p.mode) },
            "start" to listOf(start.first, start.second), "end" to listOf(end.first, end.second),
        ),
    )

    /** The route [text] holds; null for anything else (another version, a mode this build does not know, damage). */
    fun decode(text: String): Restored? = runCatching {
        val m = MiniJson.parseObject(text)
        if ((m["version"] as? Number)?.toInt() != VERSION) return null
        val mode = TravelMode.of(m["mode"] as? String) ?: return null
        val pts = RoutePath.decode(m["path"] as String).points
        if (pts.size < 2) return null
        fun num(k: String) = (m[k] as? Number)?.toDouble()
        fun pair(k: String) = (m[k] as List<*>).let { (it[0] as Number).toDouble() to (it[1] as Number).toDouble() }
        val route = Route(m["id"] as String, m["name"] as? String ?: "Route", pts, description = m["description"] as? String, type = mode.label)
        val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
        fun snap(lat: Double, lon: Double) = EdgeSnap(-1, 0, 0.0, lat, lon, 0.0)
        val plan = PlannedRoute(
            route, mode, num("distanceM") ?: TrackStats.of(track).distanceM, num("ascentM"), num("descentM"), num("timeS") ?: 0.0,
            TrackStats.profile(track, Router.PROFILE_SAMPLES), cost = 0.0, edges = 0,
            start = snap(pts.first().lat, pts.first().lon), end = snap(pts.last().lat, pts.last().lon), expanded = 0, metersByClass = emptyMap(),
            via = (m["via"] as? List<*>).orEmpty().mapNotNull { v -> (v as? List<*>)?.let { (it[0] as String) to (it[1] as Number).toDouble() } },
            memory = linkedMapOf("restored" to true),
            instructions = (m["instructions"] as? List<*>).orEmpty().mapNotNull { Instruction.parse(it as? Map<*, *>) },
        )
        Restored(plan, pair("start"), pair("end"), (num("savedAt") ?: 0.0).toLong())
    }.getOrNull()
}
