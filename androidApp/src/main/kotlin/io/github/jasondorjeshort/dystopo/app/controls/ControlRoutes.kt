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
 *   `orientation` (NORTH_UP until the three-state button), `taps`, `lastTap`, and `button`: `shown` or
 *   `hiddenBecause`, `boundsPx` in window pixels, the panel edges it keeps clear of and `clear`.
 * - `POST /camera` with query parameters or a JSON body (lat, lon, zoom, bearing, tilt): moves the camera
 *   (in [io.github.jasondorjeshort.dystopo.app.Routes]).
 * - `POST /camera/recenter[?animate=ms]` (T93): what a tap on the recentre button does; answers once the camera
 *   is there with the camera, `fixOffsetPx` and the tap's outcome under `tap` (`result` `recentered`;
 *   `navFollowOn` when navigation's follow came back on). Without a fix: 409 with `result` `noFix` or
 *   `noPermission` (the route does not ask for the permission; the button does).
 */
object ControlRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/camera" -> Response.json(app.map.camera() + mapOf("fixOffsetPx" to app.map.fixOffsetPx()?.let(::round2), "recenter" to Recenter.json(app)))
        "POST" to "/camera/recenter" -> recenter(app, r)
        else -> null
    }

    private fun recenter(app: DystopoApp, r: Request): Response {
        val animate = r.query["animate"]?.toIntOrNull() ?: 300
        val latch = CountDownLatch(1)
        val tap = Recenter.tap(app, animateMs = animate, source = "route") { latch.countDown() }
        if (tap["result"] != "recentered") return Response.json(tap, 409)
        latch.await(animate + 3000L, TimeUnit.MILLISECONDS)
        // Navigation's follow eases to the same fix (400 ms) when it came back on; let it land.
        if (tap["navFollowOn"] == true) Thread.sleep(600)
        return Response.json(app.map.camera() + mapOf("fixOffsetPx" to app.map.fixOffsetPx()?.let(::round2), "tap" to tap))
    }

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
