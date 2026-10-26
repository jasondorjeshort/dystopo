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
 *   `message` when there is none (off the network, not connected), 503 while the graph is not ready. T119: it answers
 *   once the route is the session's (`RouteSession.plan` publishes it on the main thread before returning), so a
 *   request right after it (`GET /route`, `GET /nav/directions?route=current`, `POST /nav/start?route=current`) has it.
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
 * - `GET /route/saved`: saved routes. `GET /route/gpx`: the current route as GPX (a `<rte>` without times).
 *   T137: `GET /route/gpx?track=1&kmh=<n>` is the same route as a `<trk>` resampled to one fix a second at `n` km/h
 *   ([TimedTrack]; the first fix at the request's whole second, heights interpolated), so the drive replay is
 *   `POST /route`, `GET /route/gpx?track=1&kmh=40`, `POST /location/replay` with that body; 400 without `kmh` (or
 *   with one outside 0 to 300), 404 with no route. T146: `POST /location/replay?route=current&kmh=40` replays the same
 *   fixes with no body (no 1 MiB limit); the briefs' drive replay and `tools/android-battery.py` use it.
 * - `POST /route/export`: write the current route's GPX to `files/exports/`.
 * - T65: `POST /route/import/pick`: opens the Routes list and the system file picker, as its Import does (pick a GPX
 *   in the picker; it is imported as routes and the last one opens); `GET /route/import`: the last pick's result.
 * - `POST /route/clear`: clear the route, the markers and the card; T65: also closes the route editor, so a
 *   kept route's line leaves the map too (`closedTrip` names it; the route stays in the trip store).
 * T65: `route` (in `POST /route` and `GET /route`) has the route's one set of numbers, distance, `ascentM`,
 * `descentM` and `timeS` from [io.github.jasondorjeshort.dystopo.model.RouteSummary] of its points (what the card,
 * the kept route, the trip card and navigation show); the router's own graph-based climb and time are under `graph`.
 * The route card is hidden while navigating (`ui.cardHidden`).
 * T114, route to here:
 * - `POST /route/to?lat=&lon=[&mode=][&wait=1]`: what the long-press card's "Route to here" does: a route from the
 *   phone's position (the newer of the recorder's last fix and the map's device fix) to the point, in the card's mode
 *   (or `mode`). With no fix it waits for one (`ui.phase` `waitingFix`, or `noLocation` while location is off or not
 *   permitted) and plans on the first. `wait=1` answers once it has planned or started to wait; like `GET /route`.
 * - `POST /route/from?open=1|0`: opens or closes the From choices under the From line (its Change);
 *   `POST /route/from?choice=here|map|swap[&wait=1]` taps one: Your location, Choose on map (the next long-press, or
 *   `POST /route/longpress`, is the start), Swap. 409 when the card does not offer it now (`ui.fromChoices`).
 * `GET /route` `ui` has `from` and `to` (`kind` `fix` or `point`, `label` as the card shows it; a fix's `source`
 * `recorder` or `device`, `fixTime`, `ageS` when planned and `stale`, older than [io.github.jasondorjeshort.dystopo.app.routing.RouteOrigin.STALE_S]),
 * `fromOpen`, `fromChoices`, `location` (`on`, `off`, `noPermission`), `waitingForFix` and `routeTo` (the last route to
 * here: `at`, `origin`, `waited`, and once a fix came `fixAfterMs` and `polls`).
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
            "POST" to "/route/to" -> { // T114: Route to here
                val lat = r.query["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return bad("lat and lon required")
                val lon = r.query["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return bad("lat and lon required")
                val mode = r.query["mode"]?.let { TravelMode.of(it) ?: return bad("mode must be ${TravelMode.labels}") }
                RouteSession.routeTo(lat, lon, wait = r.query["wait"] == "1", travel = mode)
                app.map.onMain { } // the UI updates posted by the plan have run
                Response.json(current(app, r.query["profile"] == "1"))
            }
            "POST" to "/route/from" -> { // T114: the From line's Change and its choices
                r.query["open"]?.let { o -> app.map.onMain { RouteSession.fromOpen = o == "1" && RouteSession.fromChoices().isNotEmpty() } }
                val choice = r.query["choice"]
                if (choice != null) {
                    if (choice !in listOf("here", "map", "swap")) return bad("choice must be here, map or swap")
                    if (choice !in RouteSession.fromChoices()) return Response.json(mapOf("error" to "the card does not offer $choice now", "ui" to RouteSession.json()), 409)
                    val wait = r.query["wait"] == "1"
                    when (choice) {
                        "here" -> RouteSession.fromHere(wait)
                        "map" -> app.map.onMain { RouteSession.chooseOnMap() }
                        else -> RouteSession.swap(wait)
                    }
                }
                app.map.onMain { }
                Response.json(current(app, r.query["profile"] == "1"))
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
                // T137: `track=1&kmh=<n>` is the route as a timed track for `POST /location/replay`; the plain path is unchanged.
                val track = r.query["track"]?.let { it == "1" || it == "true" } ?: false
                val kmh = if (!track) null else r.query["kmh"]?.toDoubleOrNull()?.takeIf { it > 0 && it <= TimedTrack.MAX_KMH }
                    ?: return bad("track=1 needs kmh=<speed in km/h>, above 0 and at most ${TimedTrack.MAX_KMH.toInt()}")
                val p = RouteSession.plan ?: return Response.json(mapOf("error" to "no route"), 404)
                val body = if (kmh != null) TimedTrack.gpx(p.route, kmh, System.currentTimeMillis() / 1000 * 1000) else RoutePlanner.gpx(p.route)
                Response(200, "application/gpx+xml; charset=utf-8", body.toByteArray())
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

/**
 * T137: a route as a timed track (pure; `TimedTrackTest`): its line resampled at one fix a second at a constant speed,
 * the first fix on the route's first point at [TimedTrack.of]'s `startMs`, each next one `kmh / 3.6` metres further
 * along the line (great-circle distance, linear between the route's points, heights interpolated where both ends have
 * one), and the last fix on the route's last point (less than a second after the one before it). Replayed through
 * `POST /location/replay` it drives navigation at that speed; the route's own GPX has no times, and replayed bare it
 * moved a whole route segment a second. Replaces `tools/android-battery.py`'s own resampling.
 */
object TimedTrack {
    const val MAX_KMH = 300.0

    fun of(points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>, kmh: Double, startMs: Long): List<io.github.jasondorjeshort.dystopo.model.TrackPoint> {
        require(kmh > 0) { "kmh must be above 0" }
        if (points.isEmpty()) return emptyList()
        val step = kmh / 3.6
        val out = ArrayList<io.github.jasondorjeshort.dystopo.model.TrackPoint>()
        fun add(lat: Double, lon: Double, ele: Double?, ms: Long) { out += io.github.jasondorjeshort.dystopo.model.TrackPoint(lat, lon, ele, ms) }
        val first = points.first()
        add(first.lat, first.lon, first.ele, startMs)
        var along = 0.0 // metres from the start to segment a's start
        var next = step // metres from the start of the next fix
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            while (d > 0 && next <= along + d) {
                val t = (next - along) / d
                val ele = if (a.ele != null && b.ele != null) a.ele!! + (b.ele!! - a.ele!!) * t else null
                add(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, ele, startMs + out.size * 1000L)
                next += step
            }
            along += d
        }
        val last = points.last()
        val lastFix = out.last()
        if (points.size > 1 && (lastFix.lat != last.lat || lastFix.lon != last.lon)) {
            val restS = (along - (next - step)) / step // the part of a second the last stretch takes
            add(last.lat, last.lon, last.ele, lastFix.time!! + kotlin.math.max(1L, Math.round(restS * 1000)))
        }
        return out
    }

    /**
     * The timed track as GPX 1.1, one line per fix with coordinates to 7 decimals (1 cm) and heights to 0.1 m: about 100
     * bytes a fix, so a track fits the loopback server's 1 MiB request body (`POST /location/replay`) up to about 10,000
     * fixes, 2.8 hours (the Paradise to Sunrise drive at 40 km/h is about 7,400). Written through
     * [io.github.jasondorjeshort.dystopo.gpx.Gpx.write], pretty-printed, it took 158 bytes a fix and the drive was refused
     * with a 413.
     */
    fun gpx(route: io.github.jasondorjeshort.dystopo.model.Route, kmh: Double, startMs: Long): String {
        val pts = of(route.points, kmh, startMs)
        val speed = if (kmh % 1.0 == 0.0) kmh.toInt().toString() else kmh.toString()
        val sb = StringBuilder(256 + pts.size * 104)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(io.github.jasondorjeshort.dystopo.gpx.Gpx.CREATOR).append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<trk><name>").append(xml(route.name)).append("</name><desc>Timed at ").append(speed).append(" km/h, one fix a second</desc>")
        route.type?.let { sb.append("<type>").append(xml(it)).append("</type>") }
        sb.append("<trkseg>\n")
        for (p in pts) {
            sb.append("<trkpt lat=\"").append(fixed(p.lat, 7)).append("\" lon=\"").append(fixed(p.lon, 7)).append("\">")
            p.ele?.let { sb.append("<ele>").append(fixed(it, 1)).append("</ele>") }
            sb.append("<time>").append(io.github.jasondorjeshort.dystopo.gpx.GpxFormat.formatTime(p.time!!)).append("</time></trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }

    /**
     * T146: the timed track as a [io.github.jasondorjeshort.dystopo.model.Track] for `POST /location/replay?route=current`,
     * the same fixes as [gpx] without the text (coordinates unrounded), so a replay of any length needs no request body.
     */
    fun track(route: io.github.jasondorjeshort.dystopo.model.Route, kmh: Double, startMs: Long): io.github.jasondorjeshort.dystopo.model.Track =
        io.github.jasondorjeshort.dystopo.model.Track(
            id = "timed-route", name = route.name,
            segments = listOf(io.github.jasondorjeshort.dystopo.model.TrackSegment(of(route.points, kmh, startMs))),
            type = route.type,
        )

    private fun fixed(v: Double, decimals: Int): String =
        java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
