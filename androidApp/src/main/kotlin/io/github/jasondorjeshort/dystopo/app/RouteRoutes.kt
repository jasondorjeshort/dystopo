package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * Debug routes for offline route planning (T45; registered with one line in [Routes]):
 * - `POST /route?from=lat,lon&to=lat,lon[&mode=hike|bike|drive|crow][&name=][&wait=ms][&profile=1]`: plans on the
 *   active pack's trail graph (building it on first use; waits up to `wait`, default 120000 ms) and
 *   shows it on the map like a long-press pair would. 200 with the route, 422 with `error` and
 *   `message` when there is none (off the network, not connected), 503 while the graph is not ready.
 *   T108: `mode=crow` is the straight line with the DEM pack's heights (no graph, never 503); its `route` has
 *   `bearingDeg` and one arrival in `instructions`.
 * - `GET /route[?profile=1]`: the current route (or failure) and the card's state.
 * - `GET /route/status`: the graph for the active pack (status, build progress, timings, size).
 * - `POST /route/build`: rebuild the graph (progress in `/route/status` and `/packs`).
 * - `POST /route/longpress?lat=&lon=`: what a long-press on the map does (start, then end).
 * - `POST /route/mode?mode=hike|bike|drive|crow`: switch mode and plan again; T49: answers after the new route is
 *   planned and shown (the card state, plus `route` like `GET /route`).
 * Route results list the named trails they follow (`via`, T49); `GET /route` has the last camera fit
 * (`ui.lastFit`) and where the bottom panels sit (`ui.layout`: route card, track panels, `overlap`).
 * - `POST /route/save[?name=]`: save the current route as GPX in `files/routes/`.
 * - `GET /route/saved`: saved routes. `GET /route/gpx`: the current route as GPX.
 * - `POST /route/export`: write the current route's GPX to `files/exports/`.
 * - T65: `POST /route/import/pick`: opens the Routes list and the system file picker, as its Import does (pick a GPX
 *   in the picker; it is imported as routes and the last one opens); `GET /route/import`: the last pick's result.
 * - `POST /route/clear`: clear the route, the markers and the card; T65: also closes the route editor, so a
 *   kept route's line leaves the map too (`closedTrip` names it; the route stays in the trip store).
 * T65: `route` (in `POST /route` and `GET /route`) has the route's one set of numbers, distance, `ascentM`,
 * `descentM` and `timeS` from [io.github.jasondorjeshort.dystopo.model.RouteSummary] of its points (what the card,
 * the kept route, the trip card and navigation show); the router's own graph-based climb and time are under `graph`.
 * The route card is hidden while navigating (`ui.cardHidden`).
 */
object RouteRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        io.github.jasondorjeshort.dystopo.app.trips.TripRoutes.handle(app, r)?.let { return it } // T60: /routes (trips)
        if (r.path != "/route" && !r.path.startsWith("/route/")) return null
        return when (r.method to r.path) {
            "POST" to "/route" -> {
                val (fromLat, fromLon) = latLon(r.query["from"]) ?: return bad("from=lat,lon required")
                val (toLat, toLon) = latLon(r.query["to"]) ?: return bad("to=lat,lon required")
                val mode = r.query["mode"]?.let { TravelMode.of(it) ?: return bad("mode must be ${TravelMode.labels}") } ?: TravelMode.HIKE
                val wait = r.query["wait"]?.toLongOrNull()?.coerceIn(0, 300_000) ?: RouteSession.WAIT_MS
                val t0 = System.nanoTime()
                val result = try {
                    RouteSession.plan(fromLat, fromLon, toLat, toLon, mode, r.query["name"] ?: "Route", wait)
                } catch (e: RoutePlanner.NotReady) {
                    return Response.json(mapOf("error" to "graph_not_ready", "graph" to RoutePlanner.json(app)), 503)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                when (result) {
                    is RouteResult.Found -> Response.json(linkedMapOf("planMs" to ms, "route" to RouteSession.routeJson(result.plan, r.query["profile"] == "1"), "graph" to RoutePlanner.json(app)))
                    is RouteResult.Failed -> Response.json(result.json() + mapOf("planMs" to ms), 422)
                }
            }
            "GET" to "/route" -> Response.json(current(app, r.query["profile"] == "1"))
            "GET" to "/route/status" -> Response.json(RoutePlanner.json(app) ?: mapOf("error" to "active pack has no vector archive"))
            "POST" to "/route/build" -> Response.json(RoutePlanner.ensure(app, rebuild = true)?.json() ?: mapOf("error" to "active pack has no vector archive"))
            "POST" to "/route/longpress" -> {
                val lat = r.query["lat"]?.toDoubleOrNull() ?: return bad("lat and lon required")
                val lon = r.query["lon"]?.toDoubleOrNull() ?: return bad("lat and lon required")
                app.map.onMain { RouteSession.longPress(lat, lon) }
                Response.json(RouteSession.json())
            }
            "POST" to "/route/mode" -> {
                val mode = TravelMode.of(r.query["mode"]) ?: return bad("mode must be ${TravelMode.labels}")
                RouteSession.changeMode(mode, wait = true)
                app.map.onMain { } // T49: the UI updates posted by the plan have run
                Response.json(current(app, r.query["profile"] == "1"))
            }
            "POST" to "/route/save" -> {
                val f = RouteSession.save(r.query["name"]) ?: return Response.json(mapOf("error" to "no route"), 404)
                Response.json(mapOf("file" to f.path, "bytes" to f.length(), "saved" to RoutePlanner.saved(app).size))
            }
            "POST" to "/route/export" -> {
                val f = RouteSession.exportGpx() ?: return Response.json(mapOf("error" to "no route"), 404)
                Response.json(mapOf("file" to f.path, "bytes" to f.length()))
            }
            "GET" to "/route/saved" -> Response.json(mapOf("dir" to RoutePlanner.routesDir(app).path, "routes" to RoutePlanner.saved(app)))
            "GET" to "/route/gpx" -> {
                val p = RouteSession.plan ?: return Response.json(mapOf("error" to "no route"), 404)
                Response(200, "application/gpx+xml; charset=utf-8", RoutePlanner.gpx(p.route).toByteArray())
            }
            "POST" to "/route/import/pick" -> { // T65: the Routes list's Import (system file picker)
                app.map.onMain {
                    io.github.jasondorjeshort.dystopo.app.trips.Trips.listOpen = true
                    io.github.jasondorjeshort.dystopo.app.routing.RouteImportPicker.requests++
                }
                Response.json(io.github.jasondorjeshort.dystopo.app.routing.RouteImportPicker.json())
            }
            "GET" to "/route/import" -> Response.json(io.github.jasondorjeshort.dystopo.app.routing.RouteImportPicker.json())
            "POST" to "/route/clear" -> {
                // T65: also closes the route editor, so a kept route's line goes too (the route stays in the trip store).
                val closed = io.github.jasondorjeshort.dystopo.app.trips.Trips.editingId
                app.map.onMain { RouteSession.clear(); io.github.jasondorjeshort.dystopo.app.trips.Trips.close() }
                Response.json(RouteSession.json() + mapOf("closedTrip" to closed))
            }
            else -> Response.notFound()
        }
    }

    private fun current(app: DystopoApp, profile: Boolean): Map<String, Any?> = linkedMapOf(
        "ui" to RouteSession.json(),
        "route" to RouteSession.plan?.let { RouteSession.routeJson(it, profile) },
        "failure" to RouteSession.failure?.json(),
        "graph" to RoutePlanner.json(app),
    )

    private fun latLon(s: String?): Pair<Double, Double>? {
        val p = s?.split(',')?.map { it.trim().toDoubleOrNull() } ?: return null
        val lat = p.getOrNull(0) ?: return null
        val lon = p.getOrNull(1) ?: return null
        if (p.size != 2 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
