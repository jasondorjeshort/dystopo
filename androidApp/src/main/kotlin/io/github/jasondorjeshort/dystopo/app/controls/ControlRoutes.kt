package io.github.jasondorjeshort.dystopo.app.controls

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera debug routes (debug builds only, on the debug port):
 * - `GET /camera`: lat, lon, zoom, bearing, tilt. T93: `fixOffsetPx`, the last fix's distance from the map's
 *   centre in pixels, and `recenter`: the button's `state` (`recenter`, `noFix`, `noPermission`), `enabled`,
 *   `orientation`, `taps`, `lastTap`, and `button`: `shown` or `hiddenBecause`, `boundsPx` in window pixels, the
 *   panel edges it keeps clear of and `clear`. T100: `orientation` ([MapOrientation.json]): `mode` (`north-up`,
 *   `free`, `facing`), `following` (the camera stays on each fix), `centred` (following, or the fix within
 *   `centredTolerancePx` of the centre: what the next tap reads), `cameraMode` (navigation's view: `free`,
 *   `follow`, `heading_up`), `compass` (the phone has one), `heading` (`deg`, `source` `simulated` | `course` |
 *   `compass`, `simulated`, `courseDeg`, `speedMps`, and `compass` with `running`, `fresh`, `trueDeg`),
 *   `lastChange` (from, to, reason: `tap`, `rotateGesture`, `bearingChanged`, `route`, `navCard`, ...),
 *   `lastFollowEnd` (why following stopped: `pan`, `fling`, `cameraMoved`, ...) and counters.
 * - `POST /camera` with query parameters or a JSON body (lat, lon, zoom, bearing, tilt): moves the camera
 *   (in [io.github.jasondorjeshort.dystopo.app.Routes]); like any move that is not ours it stops following, and a
 *   bearing other than north turns north-up into free rotate.
 * - `POST /camera/recenter[?animate=ms][&taps=n]` (T93, T100): what a tap on the recentre button does, `n` times
 *   (1 to 5, default 1), each waiting for its camera move; answers with the camera, `fixOffsetPx`, `orientation`,
 *   the last tap's outcome under `tap` (`result` `recentered`, `action` `recenter` or `advance`, `centredBefore`,
 *   `orientationBefore`, `orientation`, `advanced`, `navFollowOn`) and every tap under `taps`. Without a fix: 409
 *   with `result` `noFix` or `noPermission` (the route does not ask for the permission; the button does).
 * - `POST /camera/orientation?mode=north-up|free|facing` (T100): sets the orientation (facing even without a compass:
 *   the heading then comes from the course or a simulated heading); the camera turns to it. Answers like `GET /camera`.
 * - `POST /camera/heading?deg=` (T100): a simulated heading (degrees true) for facing while fixes are injected
 *   (`POST /location`, `POST /location/replay`); no `deg` clears it. The same one as `POST /nav/heading` and
 *   `POST /location?bearing=`.
 */
object ControlRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/camera" -> Response.json(cameraJson(app))
        "POST" to "/camera/recenter" -> recenter(app, r)
        "POST" to "/camera/orientation" -> {
            val o = Orientation.parse(r.query["mode"])
            if (o == null) {
                Response.json(mapOf("error" to "mode must be north-up, free or facing"), 400)
            } else {
                MapOrientation.set(o, "route")
                Thread.sleep(MapOrientation.FOLLOW_MS + 200L) // let the turn land
                Response.json(cameraJson(app))
            }
        }
        "POST" to "/camera/heading" -> {
            val raw = r.query["deg"]?.takeIf { it.isNotEmpty() }
            val d = raw?.toDoubleOrNull()
            if (raw != null && d == null) {
                Response.json(mapOf("error" to "deg must be a number"), 400)
            } else {
                Heading.simulate(d)
                Thread.sleep(2 * MapOrientation.TICK_MS + MapOrientation.TURN_MS + 100L) // facing turns on its next tick
                Response.json(cameraJson(app))
            }
        }
        else -> null
    }

    private fun cameraJson(app: DystopoApp): Map<String, Any?> =
        app.map.camera() + mapOf(
            "fixOffsetPx" to app.map.fixOffsetPx()?.let(::round2),
            "orientation" to MapOrientation.json(),
            "recenter" to Recenter.json(app),
        )

    private fun recenter(app: DystopoApp, r: Request): Response {
        val animate = r.query["animate"]?.toIntOrNull() ?: 300
        val n = (r.query["taps"]?.toIntOrNull() ?: 1).coerceIn(1, 5)
        val taps = ArrayList<Map<String, Any?>>()
        for (i in 1..n) {
            val latch = CountDownLatch(1)
            val tap = Recenter.tap(app, animateMs = animate, source = "route") { latch.countDown() }
            taps += tap
            if (tap["result"] != "recentered") return Response.json(tap + mapOf("taps" to taps), 409)
            latch.await(animate + 3000L, TimeUnit.MILLISECONDS)
            Thread.sleep(150) // the camera's rest event, so the next tap reads it centred
        }
        return Response.json(cameraJson(app) + mapOf("tap" to taps.last(), "taps" to taps))
    }

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
