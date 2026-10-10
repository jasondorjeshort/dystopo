package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.nav.NavSources
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * Debug routes for navigation mode (T61; registered with one line in [Routes], before [TrackRoutes] so it
 * sees `POST /location` first):
 * - `POST /nav/start?route=current|<saved route id, id prefix or file>` (T45's plan on the map, or a route
 *   in `files/routes/`), `?track=<id>` (a stored track), `?file=<name>` (a GPX in `files/tracks|routes|exports`),
 *   or a GPX body (its route, or its track with `prefer=track`). Options: `offRouteM` (50), `offRouteS` (10),
 *   `backOnRouteM` (30), `arriveM` (30), `mode=hike|bike` (the plan's mode for `route=current`),
 *   `camera=follow|heading|free` (follow), `zoom` (15; `zoom=keep`), `sound=0`, `vibrate=0`. Answers like `GET /nav`.
 * - `GET /nav`: the path (length, climb, planned time, waypoints), the latest state (progress, distance off
 *   the line, next waypoint and end with distance, climb, time, ETA; the rejoin hint), heading and its
 *   source, compass, camera mode, keep-screen-on, alerts, fix counts per origin.
 * - `GET /nav/status`: `offRoute` now, `counts` per event type and every event (`offRoute` with `afterS`
 *   from the first fix off the line, `backOnRoute` with `maxDistanceM`, `waypoint:<name>`, `arrived`).
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
            val mode = q["mode"]?.let { TravelMode.of(it) ?: return bad("mode must be hike or bike") } ?: res.plan?.mode ?: TravelMode.HIKE
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
            Response.json(NavSession.start(res.path, res.source, opts, cam, q["sound"] != "0", q["vibrate"] != "0", zoom, res.plan))
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
