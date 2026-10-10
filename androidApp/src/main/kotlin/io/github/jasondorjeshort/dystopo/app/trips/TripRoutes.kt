package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.sync.ShareLinkClient
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import java.security.MessageDigest

/**
 * Debug routes for trips (T60; reached through one line in RouteRoutes). Every change goes through the
 * same [Trips] calls as the UI, so through the store's operation log and sync.
 * - `GET /routes`: the Routes list (summary numbers) and the editor state (open route, tool, last edit,
 *   overlay with drag counts).
 * - `POST /routes?points=lat,lon;lat,lon[;...][&name=][&mode=hike|bike][&wait=ms]` (or `from=`/`to=`):
 *   creates a route through those via points (snapped to trails, legs routed, straight where there is
 *   no trail), stores it and opens it in the editor. `POST /routes/keep[?name=]`: keeps the current
 *   long-press route (T45 card) as a trip.
 * - `GET /routes/{id}[?profile=1]`: the route (via points, legs with kind and point count, waypoints,
 *   summary, GPX size and SHA-256). `GET /routes/{id}.gpx`: its GPX (`rte`/`rtept` with via marks,
 *   `wpt`, our ids).
 * - `POST /routes/{id}/via?op=add|insert|move|remove[&index=][&lat=&lon=]`: edit via points (add: where
 *   it fits, else at the end; insert: before `index`).
 * - `POST /routes/{id}/drag?index=&lat=&lon=`: drags via point `index` to the place with touch events
 *   sent to the map view (the finger path: the route must be open and the point on screen).
 * - `POST /routes/{id}/waypoint?lat=&lon=&name=[&sym=]`, `DELETE /routes/{id}/waypoint/{wid}`.
 * - `POST /routes/{id}/mode?mode=`, `/rename?name=`, `/reroute`, `/open`, `/export`; `DELETE /routes/{id}`.
 * - T67: `POST /routes/{id}/share`: the trip card's "Share link" (syncs with the sync peer, asks it for a
 *   read-only link, copies it; answers `url`, `gpx`, `token`, `existing`, `copied`, `sync`);
 *   `GET /routes/{id}/share`: the last link made here; `DELETE /routes/{id}/share`: revokes it.
 * - `POST /routes/import[?replace=1]` with a GPX body. `POST /routes/ui?open=1|0` (the Routes list),
 *   `POST /routes/close` (closes the editor), `POST /routes/tool?tool=none|via|waypoint`,
 *   `POST /routes/tap?lat=&lon=` (a tap on the map with the current tool).
 */
object TripRoutes {
    private val item = Regex("^/routes/([0-9a-fA-F-]{8,64})(\\.gpx|/[a-z]+(?:/[0-9a-fA-F-]+)?)?$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/routes" && !r.path.startsWith("/routes/")) return null
        return try {
            route(app, r)
        } catch (e: NoSuchElementException) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: IllegalStateException) {
            Response.json(mapOf("error" to e.message), 409)
        }
    }

    private fun route(app: DystopoApp, r: Request): Response {
        val q = r.query
        when (r.method to r.path) {
            "GET" to "/routes" -> return Response.json(state())
            "POST" to "/routes" -> {
                val pts = q["points"]?.split(';')?.map { latLon(it) ?: throw IllegalArgumentException("bad point '$it'") }
                    ?: listOfNotNull(q["from"]?.let(::latLon), q["to"]?.let(::latLon)).takeIf { it.size == 2 }
                    ?: throw IllegalArgumentException("points=lat,lon;lat,lon or from= and to= required")
                require(pts.size in 2..50) { "2 to 50 points" }
                val mode = q["mode"] ?: "hike"
                require(mode == "hike" || mode == "bike") { "mode must be hike or bike" }
                val wait = q["wait"]?.toLongOrNull()?.coerceIn(0, 300_000) ?: 120_000
                val p = Trips.create(pts, q["name"] ?: "Route", mode, wait)
                return Response.json(planJson(p, q["profile"] == "1") + mapOf("edit" to Trips.lastEdit))
            }
            "POST" to "/routes/keep" -> {
                val planned = RouteSession.plan ?: return Response.json(mapOf("error" to "no planned route"), 404)
                return Response.json(planJson(Trips.keep(planned, q["name"] ?: planned.route.name), false))
            }
            "POST" to "/routes/import" -> {
                require(r.body.isNotEmpty()) { "POST a GPX file as the body" }
                val res = Trips.import(r.bodyText, q["replace"] == "1")
                return Response.json(linkedMapOf("imported" to res.imported.map { it.id }, "skipped" to res.skipped, "waypoints" to res.waypoints, "ignored" to res.ignored))
            }
            "POST" to "/routes/ui" -> {
                val open = q["open"] != "0"
                app.map.onMain { Trips.listOpen = open }
                if (open) Trips.refresh()
                return Response.json(state())
            }
            "POST" to "/routes/close" -> {
                Trips.close()
                app.map.onMain { }
                return Response.json(state())
            }
            "POST" to "/routes/tool" -> {
                val t = q["tool"] ?: "none"
                require(t in listOf("none", "via", "waypoint")) { "tool must be none, via or waypoint" }
                app.map.onMain { Trips.tool = t }
                return Response.json(state())
            }
            "POST" to "/routes/tap" -> {
                val lat = q["lat"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                val lon = q["lon"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                val used = app.map.onMain { Trips.overlay.onTap?.invoke(lat, lon) ?: false }
                return Response.json(mapOf("used" to used) + state())
            }
        }
        val m = item.matchEntire(r.path) ?: return Response.notFound()
        val id = m.groupValues[1].lowercase()
        val suffix = m.groupValues[2]
        when {
            suffix == ".gpx" && r.method == "GET" -> {
                val text = Trips.gpx(id) ?: throw NoSuchElementException("no route $id")
                return Response(200, "application/gpx+xml; charset=utf-8", text.toByteArray())
            }
            suffix.isEmpty() && r.method == "GET" -> return Response.json(planJson(Trips.plan(id) ?: throw NoSuchElementException("no route $id"), q["profile"] == "1"))
            suffix.isEmpty() && r.method == "DELETE" -> return Response.json(mapOf("deleted" to Trips.delete(id)))
            r.method == "POST" && suffix == "/via" -> {
                val index = q["index"]?.toIntOrNull()
                val lat = q["lat"]?.toDoubleOrNull()
                val lon = q["lon"]?.toDoubleOrNull()
                fun need() = require(lat != null && lon != null) { "lat and lon required" }
                val p = when (q["op"]) {
                    "add" -> { need(); Trips.addVia(id, lat!!, lon!!) }
                    "insert" -> { need(); Trips.insertVia(id, index ?: throw IllegalArgumentException("index required"), lat!!, lon!!) }
                    "move" -> { need(); Trips.moveVia(id, index ?: throw IllegalArgumentException("index required"), lat!!, lon!!) }
                    "remove" -> Trips.removeVia(id, index ?: throw IllegalArgumentException("index required"))
                    else -> throw IllegalArgumentException("op must be add, insert, move or remove")
                }
                return Response.json(planJson(p, false) + mapOf("edit" to Trips.lastEdit))
            }
            r.method == "POST" && suffix == "/drag" -> {
                val index = q["index"]?.toIntOrNull() ?: throw IllegalArgumentException("index required")
                val lat = q["lat"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                val lon = q["lon"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                if (Trips.editingId != id) Trips.open(id, fit = false)
                val before = Trips.overlay.drags
                val sent = app.map.onMain { Trips.overlay.simulateDrag(index, lat, lon) }
                if (!sent) return Response.json(mapOf("error" to "via point $index is not on screen (open the route and show it first)"), 409)
                // The drag's reroute runs on the trips worker: wait for it to land in the store.
                val t0 = System.currentTimeMillis()
                while (Trips.overlay.drags == before && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20)
                Trips.drain()
                val p = Trips.plan(id) ?: throw NoSuchElementException("no route $id")
                return Response.json(planJson(p, false) + mapOf("drag" to Trips.overlay.lastDrag, "edit" to Trips.lastEdit))
            }
            r.method == "POST" && suffix == "/waypoint" -> {
                val lat = q["lat"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                val lon = q["lon"]?.toDoubleOrNull() ?: throw IllegalArgumentException("lat and lon required")
                val w = Trips.addWaypoint(id, lat, lon, q["name"] ?: "Waypoint", q["sym"])
                return Response.json(mapOf("waypoint" to w.id) + planJson(Trips.plan(id)!!, false))
            }
            r.method == "DELETE" && suffix.startsWith("/waypoint/") -> return Response.json(mapOf("deleted" to Trips.removeWaypoint(id, suffix.removePrefix("/waypoint/").lowercase())))
            r.method == "POST" && suffix == "/mode" -> {
                val mode = q["mode"]
                require(mode == "hike" || mode == "bike") { "mode must be hike or bike" }
                return Response.json(planJson(Trips.setMode(id, mode!!), false))
            }
            r.method == "POST" && suffix == "/reroute" -> return Response.json(planJson(Trips.reroute(id), false) + mapOf("edit" to Trips.lastEdit))
            r.method == "POST" && suffix == "/rename" -> {
                val name = q["name"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("name required")
                return Response.json(mapOf("renamed" to Trips.rename(id, name)))
            }
            r.method == "POST" && suffix == "/open" -> return Response.json(planJson(Trips.open(id) ?: throw NoSuchElementException("no route $id"), false))
            r.method == "POST" && suffix == "/share" -> return Response.json(Trips.shareLink(id))
            r.method == "GET" && suffix == "/share" -> return Response.json(mapOf("link" to ShareLinkClient.of(app).last(id)))
            r.method == "DELETE" && suffix == "/share" -> return Response.json(Trips.revokeShareLink(id))
            r.method == "POST" && suffix == "/export" -> {
                val f = Trips.export(id) ?: throw NoSuchElementException("no route $id")
                return Response.json(mapOf("file" to f.path, "bytes" to f.length()))
            }
        }
        return Response.notFound()
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "routes" to Trips.store { it.routeList() }.map { it.json() },
        "listOpen" to Trips.listOpen, "editing" to Trips.editingId, "tool" to Trips.tool, "message" to Trips.message,
        "lastEdit" to Trips.lastEdit, "overlay" to Trips.overlay.json(),
    )

    fun planJson(p: RoutePlan, profile: Boolean): Map<String, Any?> {
        val gpx = RouteGpx.gpx(p).toByteArray()
        return linkedMapOf(
            "id" to p.id, "name" to p.name, "mode" to p.path.mode, "type" to p.type, "description" to p.description,
            "via" to p.path.via.map { listOf(it.lat, it.lon) },
            "legs" to p.path.legs.map { linkedMapOf("kind" to it.kind.label, "points" to it.points.size) },
            "waypoints" to p.waypoints.map { linkedMapOf("id" to it.id, "name" to it.name, "lat" to it.lat, "lon" to it.lon, "ele" to it.ele, "sym" to it.symbol) },
            "summary" to RouteSummary.of(p).json(profile),
            "gpxBytes" to gpx.size, "gpxSha256" to MessageDigest.getInstance("SHA-256").digest(gpx).joinToString("") { "%02x".format(it) },
        )
    }

    private fun latLon(s: String): Pair<Double, Double>? {
        val p = s.split(',').map { it.trim().toDoubleOrNull() }
        val lat = p.getOrNull(0) ?: return null
        val lon = p.getOrNull(1) ?: return null
        if (p.size != 2 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
