package io.github.jasondorjeshort.dystopo.app.nav

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import java.io.File

/**
 * What navigation can follow (T61), resolved into a [NavPath]: the route on the map (T45's current plan),
 * a saved route (a GPX in `files/routes/`, by route id, id prefix or file name: the lookup that exists until
 * T60's route store lands), a stored track (by id), or a GPX document (its first route, or its first
 * track with `prefer=track`/when it has no route; the file's waypoints ride along).
 */
object NavSources {
    class Resolved(val path: NavPath, val source: Map<String, Any?>, val plan: PlannedRoute? = null)

    class NotFound(message: String) : Exception(message)

    fun currentRoute(): Resolved {
        val p = RouteSession.plan ?: throw NotFound("no route on the map: plan one first (long-press start and end, or POST /route)")
        return Resolved(NavPath.of(p.route), linkedMapOf("kind" to "route", "route" to "current", "id" to p.route.id, "name" to p.route.name, "mode" to p.mode.label), p)
    }

    fun savedRoute(key: String): Resolved {
        val dir = RoutePlanner.routesDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }.orEmpty().sortedByDescending { it.lastModified() }
        for (f in files) {
            val doc = runCatching { Gpx.parse(f.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            val r = doc.routes.firstOrNull() ?: continue
            if (r.id == key || r.id.startsWith(key) || f.name == key || f.name == "$key.gpx") {
                return Resolved(NavPath.of(r, doc.waypoints), linkedMapOf("kind" to "route", "route" to key, "id" to r.id, "name" to r.name, "file" to f.name))
            }
        }
        throw NotFound("no saved route $key in ${dir.path}")
    }

    fun route(key: String): Resolved = if (key == "current") currentRoute() else savedRoute(key)

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
                Resolved(NavPath.of(r, doc.waypoints), linkedMapOf("kind" to "gpx-route", "id" to r.id, "name" to r.name, "file" to label))
            t != null && t.points.size >= 2 ->
                Resolved(NavPath.of(t, doc.waypoints), linkedMapOf("kind" to "gpx-track", "id" to t.id, "name" to t.name, "file" to label))
            else -> throw IllegalArgumentException("the GPX has no route or track with two points")
        }
    }

    /** A GPX file under the app's files dir (`tracks/`, `routes/`, `exports/`), by name. */
    fun file(name: String, preferTrack: Boolean): Resolved {
        val base = DystopoApp.instance.filesDir
        val f = listOf("tracks", "routes", "exports").map { File(File(base, it), name) }.firstOrNull { it.isFile && !name.contains("..") }
            ?: throw NotFound("no file $name in files/tracks, files/routes or files/exports")
        return gpx(f.readText(Charsets.UTF_8), preferTrack, f.name)
    }
}
