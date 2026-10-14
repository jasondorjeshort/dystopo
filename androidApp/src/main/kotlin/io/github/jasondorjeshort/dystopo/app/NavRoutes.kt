package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.nav.NavSources
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * Debug routes for navigation mode (T61; registered with one line in [Routes], before [TrackRoutes] so it
 * sees `POST /location` first):
 * - `POST /nav/start?route=current|<kept route id or id prefix>|<saved route id, id prefix or file>` (T45's plan on
 *   the map; T65: a route kept in the trip store, as `GET /routes` lists them; else a route in `files/routes/`), `?track=<id>` (a stored track), `?file=<name>` (a GPX in `files/tracks|routes|exports`),
 *   or a GPX body (its route, or its track with `prefer=track`). Options: `offRouteM` (50), `offRouteS` (10),
 *   `backOnRouteM` (30), `arriveM` (30), `mode=hike|bike|drive` (the plan's mode for `route=current`),
 *   `camera=follow|heading|free` (follow), `zoom` (15; `zoom=keep`), `sound=0`, `vibrate=0`. Answers like `GET /nav`.
 * - `GET /nav`: the path (length, climb, planned time, waypoints), the latest state (progress, distance off
 *   the line, next waypoint and end with distance, climb, time, ETA; the rejoin hint), heading and its
 *   source, compass, camera mode, keep-screen-on, alerts, fix counts per origin.
 * - `GET /nav/status`: `offRoute` now, `counts` per event type and every event (`offRoute` with `afterS`
 *   from the first fix off the line, `backOnRoute` with `maxDistanceM`, `waypoint:<name>`, `arrived`).
 * - T65: `POST /nav/snooze?min=10` (alerts silent for 10 minutes, at most 240), `?until=back` (silent until back on the
 *   route, so the next excursion sounds again), `?off=1`; events are still detected and listed; `snooze` in `/nav` and
 *   `/nav/status` (`suppressed` counts silenced alerts). The off-route banner has the same two buttons.
 * - T65 `GET /nav` also has `etaVsPlan`, a pace ratio: (fix time since the first fix + the model's time still to go to
 *   the end, the way back included when off route) / the planned time (the route's [io.github.jasondorjeshort.dystopo.model.RouteSummary]
 *   time for routes, the line's model time for tracks and files); 1.0 at the start, above 1 behind plan, below 1 ahead;
 *   `pace` has its three parts. `fix`: stale-fix handling (a GPS fix or the last known location more than 30 s old when it
 *   arrives is dropped, `droppedTooOld`; the last known location more than 2 km from the line does not start navigation,
 *   `droppedTooFar`; with no usable fix for 30 s the bar says "No GPS fix for N s", `lost`; T91: the 30 s and 2 km are the
 *   settings `navStaleS` and `navStartM`, read at each start, and the banner's Snooze length is `navSnoozeMin`). `layout`: where the Tracks and
 *   Routes chips sit relative to the stats bar ([TopChips]). `path.totalAscentM` is the route's one ascent figure (T24 rule).
 * - Starting navigation closes the route editor sheet and hides the T45 route card; the navigation line draws above
 *   the planned and kept route lines (T65).
 * - `POST /nav/stop`; `POST /nav/camera?mode=follow|heading|free`; `POST /nav/heading?deg=` (simulated
 *   heading for injected fixes; no `deg` clears it).
 * - `POST /location?...&bearing=<deg>` (observed, not handled): the bearing becomes the simulated heading.
 * Fixes reach navigation from `POST /location`, `POST /location/replay` (the GPX's own times, so off-route
 * timing is the same at any replay rate) and the device GPS while navigating.
 */
object NavRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.method == "POST" && r.path == "/location") {
            r.query["bearing"]?.toDoubleOrNull()?.let { NavSession.simulateHeading(it, apply = false) }
            return null
        }
        if (r.path != "/nav" && !r.path.startsWith("/nav/")) return null
        return when (r.method to r.path) {
            "GET" to "/nav" -> Response.json(NavSession.json())
            "GET" to "/nav/status" -> Response.json(NavSession.status())
            "POST" to "/nav/stop" -> Response.json(NavSession.stop() + NavSession.json())
            "POST" to "/nav/start" -> start(r)
            "POST" to "/nav/camera" -> {
                val m = camera(r.query["mode"]) ?: return bad("mode must be follow, heading or free")
                NavSession.setCamera(m)
                Response.json(NavSession.json())
            }
            "POST" to "/nav/snooze" -> { // T65
                val off = r.query["off"] == "1"
                val until = r.query["until"]
                val min = r.query["min"]?.let { it.toDoubleOrNull() ?: return bad("min must be a number") }
                if (!off && until != "back" && min == null) return bad("min=<minutes>, until=back or off=1 required")
                try {
                    Response.json(mapOf("snooze" to NavSession.snooze(if (until == "back") null else min, off)) + NavSession.json())
                } catch (e: IllegalArgumentException) {
                    bad(e.message ?: "bad snooze")
                }
            }
            "POST" to "/nav/heading" -> {
                val raw = r.query["deg"]
                val d = raw?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return bad("deg must be a number") }
                NavSession.simulateHeading(d) // re-runs the last fix with it before answering
                Response.json(NavSession.json())
            }
            else -> Response.notFound()
        }
    }

    private fun start(r: Request): Response {
        val q = r.query
        fun d(k: String): Double? = q[k]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("$k must be a number") }
        return try {
            val preferTrack = q["prefer"] == "track"
            val res = when {
                q["route"] != null -> NavSources.route(q["route"]!!)
                q["track"] != null -> NavSources.track(q["track"]!!)
                q["file"] != null -> NavSources.file(q["file"]!!, preferTrack)
                r.body.isNotEmpty() -> NavSources.gpx(r.bodyText, preferTrack, q["name"])
                else -> return bad("route=, track=, file= or a GPX body required")
            }
            val mode = q["mode"]?.let { TravelMode.of(it) ?: return bad("mode must be hike, bike or drive") } ?: res.mode ?: TravelMode.HIKE
            val defaults = NavOptions()
            val opts = NavOptions(
                offRouteM = d("offRouteM") ?: defaults.offRouteM,
                offRouteSeconds = d("offRouteS") ?: defaults.offRouteSeconds,
                backOnRouteM = d("backOnRouteM") ?: minOf(defaults.backOnRouteM, d("offRouteM") ?: defaults.offRouteM),
                arriveM = d("arriveM") ?: defaults.arriveM,
                mode = mode,
            )
            val cam = camera(q["camera"] ?: "follow") ?: return bad("camera must be follow, heading or free")
            val zoom = if (q["zoom"] == "keep") null else d("zoom") ?: 15.0
            Response.json(NavSession.start(res.path, res.source, opts, cam, q["sound"] != "0", q["vibrate"] != "0", zoom, res.planTimeS))
        } catch (e: NavSources.NotFound) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "bad request")
        }
    }

    private fun camera(s: String?): CameraMode? = when (s?.lowercase()) {
        "follow" -> CameraMode.FOLLOW
        "heading", "heading-up", "headingup" -> CameraMode.HEADING_UP
        "free", "off" -> CameraMode.FREE
        else -> null
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
