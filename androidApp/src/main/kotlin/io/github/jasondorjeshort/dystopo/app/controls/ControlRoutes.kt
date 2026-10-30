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
 *   T162: `openView`, what the map opened at (`OpenView` in `MapViewport.kt`): `won` (`fix`, `saved`, `pack`,
 *   `deepLink`, `restored`, `world`), `openedAt`, `view`, the three candidates' times `fixAt` (when the fix last moved),
 *   `savedAt`, `packAt` (epoch ms; 0 for a pack never activated) and the candidates themselves (`fix`, `saved`, `pack`);
 *   `window`: the fix window after open (`ms`, `state` `open`, `recentred`, `gesture`, `cameraSet`, `cameraMoved`,
 *   `expired` or `none`, `endedAfterMs`, `fixes`, `recentredTo`); `lastFix` and `savedNow` as stored now, `savedWrites`.
 * - `POST /camera` with query parameters or a JSON body (lat, lon, zoom, bearing, tilt): moves the camera
 *   (in [io.github.jasondorjeshort.dystopo.app.Routes]); like any move that is not ours it stops following, and a
 *   bearing other than north turns north-up into free rotate.
 * - `POST /camera/recenter[?animate=ms][&taps=n]` (T93, T100): what a tap on the recentre button does, `n` times
 *   (1 to 5, default 1), each waiting for its camera move; answers with the camera, `fixOffsetPx`, `orientation`,
 *   the last tap's outcome under `tap` (`result` `recentered`, `action` `recenter` or `advance`, `centredBefore`,
 *   `orientationBefore`, `orientation`, `advanced`, `navFollowOn`) and every tap under `taps`. Without a fix: 409
 *   with `result` `noFix` or `noPermission` (the route does not ask for the permission; the button does).
 *   T176: `GET /camera` › `recenter` adds `layout` (the position button's buttons as set, `mapControls` 1 to 3),
 *   `shownButtons` (as drawn: the room below the top-right chips drops the extra ones first, 0 when hidden), `lock`
 *   ("Keep north up"), `closeZoom` (`recenterZoom`), `lastTap` with `layout`, `locked`, `closeZoom` (null: navigation
 *   keeps its zoom), `zoomBefore`, `zoomTo` (null: kept) and `zoomAfter` (the camera's zoom once there), `longPresses`,
 *   `lastButton` (the orientation button, the lock or a long press: what it did), and under `button` every button's
 *   `buttons` (`id` `position`, `orientation`, `lock`, with `boundsPx`); `boundsPx` and `clear` cover the whole
 *   column. `orientation` adds `lock`, `lockReturns` (bearings turned back to north at rest under the lock) and
 *   `rotateGestures` (MapLibre's two-finger turn, off under the lock). A tap's `action` is `recenter`, `zoom` (centred
 *   but farther out than the close zoom), `advance` (centred and close, one button, unlocked) or `none`.
 * - `POST /camera/recenter?long=1` (T176): a long press on the position button: toggles the lock on one or two buttons
 *   (answers `lastButton`-style with `lock` and the `toast` shown), `result: ignored` on three.
 *   `POST /camera/recenter?button=orientation|lock` (T176): a tap on the orientation button (the next mode, also under
 *   the lock) or on the lock button (toggles it), whatever the layout. Each answers like `GET /camera` plus `button`.
 * - `POST /camera/gesture?kind=rotate&deg=40[&ms=600]` or `kind=pan&dx=-300&dy=200[&ms=800]` (T176): a two-finger turn
 *   about the map's centre, or a slow one-finger drag (px; no fling), injected as touch events into the map view, so
 *   MapLibre's own gesture detectors see it; answers after the camera rests (about a second) like `GET /camera` plus
 *   `gesture` (`kind`, `events`, `bearingBefore`, `modeBefore`).
 * - `POST /camera/orientation?mode=north-up|free|facing` (T100): sets the orientation (facing even without a compass:
 *   the heading then comes from the course or a simulated heading); the camera turns to it. Answers like `GET /camera`.
 * - `POST /camera/heading?deg=` (T100): a simulated heading (degrees true) for facing while fixes are injected
 *   (`POST /location`, `POST /location/replay`); no `deg` clears it. The same one as `POST /nav/heading` and
 *   `POST /location?bearing=`.
 */
object ControlRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/camera" -> Response.json(cameraJson(app))
        "POST" to "/camera/recenter" -> when {
            r.query["long"] == "1" -> Response.json(cameraJson(app) + mapOf("button" to Recenter.longPress(app, "route")))
            r.query["button"] == "orientation" -> {
                val b = Recenter.orientationButton("route")
                Thread.sleep(MapOrientation.FOLLOW_MS + 200L) // let the turn land
                Response.json(cameraJson(app) + mapOf("button" to b))
            }
            r.query["button"] == "lock" -> Response.json(cameraJson(app) + mapOf("button" to Recenter.toggleLock(app, "route")))
            r.query["button"] != null && r.query["button"] != "position" -> Response.json(mapOf("error" to "button must be position, orientation or lock"), 400)
            else -> recenter(app, r)
        }
        "POST" to "/camera/gesture" -> gesture(app, r)
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
            "openView" to app.map.openViewJson(), // T162
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

    /**
     * T176: a two-finger turn about the map's centre ([deg] clockwise) or a slow one-finger drag, as touch events
     * dispatched to the map view on the main thread, spaced over [ms], so MapLibre's detectors (and the lock) see a real
     * gesture.
     */
    private fun gesture(app: DystopoApp, r: Request): Response {
        val kind = r.query["kind"] ?: "rotate"
        if (kind != "rotate" && kind != "pan") return Response.json(mapOf("error" to "kind must be rotate or pan"), 400)
        val ms = (r.query["ms"]?.toIntOrNull() ?: if (kind == "rotate") 600 else 800).coerceIn(100, 5000)
        val deg = r.query["deg"]?.toDoubleOrNull() ?: 40.0
        val dx = r.query["dx"]?.toFloatOrNull() ?: -300f
        val dy = r.query["dy"]?.toFloatOrNull() ?: 200f
        val before = app.map.camera()
        val modeBefore = MapOrientation.orientation.key
        val view = app.map.mapView ?: return Response.json(mapOf("error" to "map not ready"), 409)
        val w = app.map.onMain { view.width.toFloat() }
        val h = app.map.onMain { view.height.toFloat() }
        val cx = w / 2
        val cy = h / 2
        val steps = 24
        val down = android.os.SystemClock.uptimeMillis()
        // Each event: (time offset, action, pointer positions).
        val events = ArrayList<Triple<Long, Int, List<Pair<Float, Float>>>>()
        if (kind == "rotate") {
            val rad = minOf(w, h) * 0.2f
            fun at(a: Double) = listOf(
                (cx + rad * Math.cos(a)).toFloat() to (cy + rad * Math.sin(a)).toFloat(),
                (cx - rad * Math.cos(a)).toFloat() to (cy - rad * Math.sin(a)).toFloat(),
            )
            val a0 = -Math.PI / 2
            events += Triple(0L, android.view.MotionEvent.ACTION_DOWN, at(a0).take(1))
            events += Triple(20L, android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(a0))
            for (i in 1..steps) events += Triple(40L + ms.toLong() * i / steps, android.view.MotionEvent.ACTION_MOVE, at(a0 + Math.toRadians(deg) * i / steps))
            val end = at(a0 + Math.toRadians(deg))
            events += Triple(60L + ms, android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
            events += Triple(80L + ms, android.view.MotionEvent.ACTION_UP, end.take(1))
        } else {
            fun at(f: Float) = listOf((cx + dx * f) to (cy + dy * f))
            events += Triple(0L, android.view.MotionEvent.ACTION_DOWN, at(0f))
            for (i in 1..steps) events += Triple(ms.toLong() * i / steps, android.view.MotionEvent.ACTION_MOVE, at(i.toFloat() / steps))
            // Held still before the lift, so the velocity at the end is zero: no fling.
            for (i in 1..4) events += Triple(ms + 50L * i, android.view.MotionEvent.ACTION_MOVE, at(1f))
            events += Triple(ms + 250L, android.view.MotionEvent.ACTION_UP, at(1f))
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        for ((dt, action, pts) in events) {
            main.postAtTime({
                val props = Array(pts.size) { i -> android.view.MotionEvent.PointerProperties().apply { id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(pts.size) { i -> android.view.MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f } }
                val e = android.view.MotionEvent.obtain(down, down + dt, action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                view.dispatchTouchEvent(e)
                e.recycle()
            }, down + dt)
        }
        Thread.sleep(events.last().first + 1200L) // the gesture, then the camera's rest (and a lock's turn back to north)
        return Response.json(cameraJson(app) + mapOf("gesture" to linkedMapOf(
            "kind" to kind, "events" to events.size, "ms" to ms, "deg" to if (kind == "rotate") deg else null,
            "dx" to if (kind == "pan") dx else null, "dy" to if (kind == "pan") dy else null,
            "bearingBefore" to before["bearing"], "zoomBefore" to before["zoom"], "modeBefore" to modeBefore,
        )))
    }

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
