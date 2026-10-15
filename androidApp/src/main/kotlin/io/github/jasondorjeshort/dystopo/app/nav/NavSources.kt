package io.github.jasondorjeshort.dystopo.app.nav

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.io.File

/**
 * What navigation can follow (T61), resolved into a [NavPath]: the route on the map (T45's current plan),
 * a kept route from the trip store (T65; by id or id prefix), a saved route (a GPX in `files/routes/`, by
 * route id, id prefix or file name), a stored track (by id), or a GPX document (its first route, or its
 * first track with `prefer=track`/when it has no route; the file's waypoints ride along).
 *
 * T65: a route's planned time ([Resolved.planTimeS]) is its [RouteSummary] time, the same numbers the route
 * card, the trip card and the Routes list show (T24 ascent, the T45 hike model), so `etaVsPlan` starts at 1.
 *
 * T102: each route comes with its turn directions ([Resolved.directions], along the [NavPath]): the route on the map
 * brings the plan's own (local or from the server), moved onto the navigation line; a kept trip or a saved route reads
 * them from the graphs of the packs on the device (via points and straight legs included for a trip); without a pack
 * there, only depart and arrive (and a trip's via points). Tracks have none.
 */
object NavSources {
    class Resolved(
        val path: NavPath,
        val source: Map<String, Any?>,
        /** The plan's total time (s) for `etaVsPlan`; null for tracks and GPX files (navigation's own model time is used). */
        val planTimeS: Double? = null,
        val mode: TravelMode? = null,
        /** T102: the turn directions along [path] (every priority; navigation filters by level). */
        val directions: List<Instruction> = emptyList(),
    )

    class NotFound(message: String) : Exception(message)

    fun currentRoute(): Resolved {
        val p = RouteSession.plan ?: throw NotFound("no route on the map: plan one first (long-press start and end, or POST /route)")
        val s = RouteSession.summaryOf(p)
        val path = NavPath.of(RouteSession.unifiedRoute(p))
        return Resolved(
            path,
            linkedMapOf("kind" to "route", "route" to "current", "id" to p.route.id, "name" to p.route.name, "mode" to p.mode.label),
            s.timeS, p.mode, onto(path, p.instructions),
        )
    }

    /** T65: a route kept in the trip store (Keep on the route card, the Routes list, synced from another device). */
    fun keptRoute(key: String): Resolved? {
        val plans = Trips.store { it.routePlans() }
        val p: RoutePlan = plans.firstOrNull { it.id == key } ?: plans.filter { key.length >= 4 && it.id.startsWith(key) }.singleOrNull() ?: return null
        require(p.path.points.size >= 2) { "route ${p.id} has fewer than two points" }
        val s = RouteSummary.of(p, samples = 0)
        // T102: along the trip's own points (NavPath counts consecutive equal points once, as Directions does).
        val directions = Directions.ofPath(p.path, RoutePlanner.localJunctions(p.path.points.map { it.lat to it.lon }))
        return Resolved(
            NavPath.of(p.toRoute(), p.waypoints),
            linkedMapOf("kind" to "trip", "route" to key, "id" to p.id, "name" to p.name, "mode" to p.path.mode),
            s.timeS, TravelMode.of(p.path.mode), directions,
        )
    }

    fun savedRoute(key: String): Resolved {
        val dir = RoutePlanner.routesDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }.orEmpty().sortedByDescending { it.lastModified() }
        for (f in files) {
            val doc = runCatching { Gpx.parse(f.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            val r = doc.routes.firstOrNull() ?: continue
            if (r.id == key || r.id.startsWith(key) || f.name == key || f.name == "$key.gpx") {
                return Resolved(NavPath.of(r, doc.waypoints), linkedMapOf("kind" to "route", "route" to key, "id" to r.id, "name" to r.name, "file" to f.name), directions = alongLine(r.points, r.type))
            }
        }
        throw NotFound("no route $key: not the current route, not a kept route (GET /routes) and not saved in ${dir.path}")
    }

    /** `current`, else a kept route (trip store), else a saved GPX in `files/routes/`. */
    fun route(key: String): Resolved = if (key == "current") currentRoute() else keptRoute(key) ?: savedRoute(key)

    fun track(id: String): Resolved {
        val t = Tracking.of(DystopoApp.instance)
        val track = t.call { t.store.track(id) } ?: throw NotFound("no track $id")
        val wps = t.call { t.store.waypoints(id) }
        require(track.points.size >= 2) { "track $id has fewer than two points" }
        return Resolved(NavPath.of(track, wps), linkedMapOf("kind" to "track", "id" to track.id, "name" to track.name, "points" to track.points.size))
    }

    fun gpx(text: String, preferTrack: Boolean, label: String?): Resolved {
        val doc = Gpx.parse(text)
        val r = doc.routes.firstOrNull()
        val t = doc.tracks.firstOrNull()
        return when {
            r != null && r.points.size >= 2 && !(preferTrack && t != null) ->
                Resolved(NavPath.of(r, doc.waypoints), linkedMapOf("kind" to "gpx-route", "id" to r.id, "name" to r.name, "file" to label), directions = alongLine(r.points, r.type))
            t != null && t.points.size >= 2 ->
                Resolved(NavPath.of(t, doc.waypoints), linkedMapOf("kind" to "gpx-track", "id" to t.id, "name" to t.name, "file" to label))
            else -> throw IllegalArgumentException("the GPX has no route or track with two points")
        }
    }

    /** T102: a planned route's directions moved onto [path] (its points may be quantised or deduplicated differently). */
    fun onto(path: NavPath, list: List<Instruction>): List<Instruction> {
        if (list.isEmpty()) return list
        val scale = list.last().alongM.takeIf { it > 0 }?.let { path.totalM / it } ?: 1.0
        return Directions.reanchor(list, scale) { lat, lon, near -> path.project(lat, lon, near, behindM = 100.0, aheadM = 300.0, jumpM = 10.0).alongM }
    }

    /** T102: directions along a GPX route's points from the packs on the device, in the route's mode (its `type`). */
    private fun alongLine(points: List<RoutePoint>, type: String?): List<Instruction> =
        if (points.size < 2) emptyList()
        else Directions.of(points, TravelMode.of(type) ?: TravelMode.HIKE, RoutePlanner.localJunctions(points.map { it.lat to it.lon }))

    /** A GPX file under the app's files dir (`tracks/`, `routes/`, `exports/`), by name. */
    fun file(name: String, preferTrack: Boolean): Resolved {
        val base = DystopoApp.instance.filesDir
        val f = listOf("tracks", "routes", "exports").map { File(File(base, it), name) }.firstOrNull { it.isFile && !name.contains("..") }
            ?: throw NotFound("no file $name in files/tracks, files/routes or files/exports")
        return gpx(f.readText(Charsets.UTF_8), preferTrack, f.name)
    }
}
