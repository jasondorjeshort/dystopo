package io.github.jasondorjeshort.dystopo.app.controls

import android.graphics.PointF
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T101, the scale bar's debug routes (debug builds only, on the debug port):
 * - `GET /scalebar`: what the bar shows ([ScaleBarLayout]): `shown` or `hiddenBecause` (`noCamera`, `noScale`, T110 `notComposed` while the no-pack line shows; T106: no longer `tripCard`, it rides above the trip card and the update offer, `bottomCardsTopDp`;
 *   T115 `noRoomBelowTopLeft` when a tall bottom panel would push it up to the search box and the online indicator, `topLeftBottomDp`, and its own `topDp`),
 *   `bothUnits`, `units`, `night`, the camera's `zoom` and `lat` it was fitted at, `metersPerDp` (the shared Web
 *   Mercator rule), `density`, and per bar (the user's units first) `label`, `value`, `unit`, `meters`, `widthDp`,
 *   `widthPx` and `drawnPx` (tick to tick as drawn); where it sits (`boundsPx` in window pixels, its bottom edge,
 *   the credit line, route card and track panel edges it keeps above, and `clear`). `readBack` checks each bar
 *   against MapLibre itself on the live camera: `mapLibreMetersPerDp` (its projection at the centre latitude; per
 *   logical pixel, which is a dp), `meters` at that scale over `drawnPx` and `errorPct` against the label, and `measuredMeters`
 *   (the ground distance between two screen points `drawnPx` apart, T128: on the ruler's own row `measuredRowPx`) with its
 *   `measuredErrorPct`, and the same across the centre row (`centreRowMeters`, `centreRowErrorPct`). T128: under tilt
 *   (`tilt` at least 0.5 degrees) the bar is fitted to the scale on its own row (`measuredAt: row`, `rowPx`,
 *   `metersPerDp`), not the centre's Web Mercator rule (`centreMetersPerDp`), so the label is right where the bar is;
 *   `errorPct` (MapLibre's centre-latitude scale) then differs by the tilt's foreshortening and is meaningful untilted
 *   only. It also stays below the navigation card (`navCardBottomDp`, `clearOfNavCard`; hidden as
 *   `noRoomBelowNavCard` when no room is left).
 *   T110: only for a bar that is shown and fitted at the live camera (it refits every 500 ms; the route waits up to
 *   [FIT_WAIT_MS] for that); otherwise `readBack` is null and `readBackSkipped` says why (`hidden`, `fitBehindCamera`).
 *   Before, a hidden bar kept its last lengths and the read-back compared them with a camera that had moved.
 * - `POST /scalebar?both=on|off`: the Map options row "Scale bar" (one unit or both; T110: synced, `app.scaleBar.both`
 *   in `GET /sync/status` › `settingsSync`); answers like `GET` once the bar has redrawn. Anything else for `both` is a 400.
 */
object ScaleBarRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/scalebar" -> Response.json(state(app))
        "POST" to "/scalebar" -> set(app, r)
        else -> null
    }

    private fun set(app: DystopoApp, r: Request): Response {
        val raw = r.query["both"] ?: return Response.json(mapOf("error" to "both=on|off is required"), 400)
        val on = when (raw.trim().lowercase()) {
            "on", "1", "true", "yes" -> true
            "off", "0", "false", "no" -> false
            else -> return Response.json(mapOf("error" to "both must be on or off (got $raw)"), 400)
        }
        app.map.onMain { ScaleBarSettings.setBothUnits(on) }
        // Wait for the bar to recompose with one or two lengths (it polls the camera every 500 ms at most).
        val want = if (on) 2 else 1
        val until = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < until && ScaleBarLayout.shown && (ScaleBarLayout.bars.size != want || ScaleBarLayout.drawnPx.size != want)) Thread.sleep(50)
        Thread.sleep(100) // one more frame, so drawnPx belongs to the new bars
        return Response.json(state(app))
    }

    private fun state(app: DystopoApp): Map<String, Any?> {
        val why = awaitFit(app)
        return ScaleBarLayout.json() + mapOf("readBack" to if (why == null) readBack(app) else null, "readBackSkipped" to why)
    }

    /** T110: null once the bar is shown and fitted at the live camera, else why not after [FIT_WAIT_MS]. */
    private fun awaitFit(app: DystopoApp): String? {
        val until = System.currentTimeMillis() + FIT_WAIT_MS
        while (true) {
            val live = runCatching { app.map.onMain { app.map.map?.cameraPosition?.let { c -> c.target?.let { doubleArrayOf(c.zoom, it.latitude, c.tilt) } } } }.getOrNull()
            if (live != null && ScaleBarLayout.fittedAt(live[0], live[1], live[2])) return null
            if (System.currentTimeMillis() >= until) return if (!ScaleBarLayout.shown) "hidden" else "fitBehindCamera"
            Thread.sleep(50)
        }
    }

    /** T110: how long `GET /scalebar` waits for the bar to catch up with the camera (it refits every 500 ms). */
    private const val FIT_WAIT_MS = 1500L

    private fun readBack(app: DystopoApp): List<Map<String, Any?>>? = runCatching {
        app.map.onMain {
            val m = app.map.map ?: return@onMain null
            val view = app.map.mapView ?: return@onMain null
            val lat = m.cameraPosition.target?.latitude ?: return@onMain null
            val mpp = m.projection.getMetersPerPixelAtLatitude(lat) // per logical pixel (dp), not per device pixel
            val density = view.resources.displayMetrics.density
            val drawn = ScaleBarLayout.drawnPx
            val cx = view.width / 2f
            val cy = view.height / 2f
            val row = ScaleBarLayout.rowPx.takeIf { it.isFinite() } ?: cy // T128: the ruler's own row
            ScaleBarLayout.bars.mapIndexed { i, b ->
                val px = drawn.getOrNull(i) ?: (b.px * ScaleBarLayout.density).toFloat()
                fun across(y: Float) = m.projection.fromScreenLocation(PointF(cx - px / 2, y)).distanceTo(m.projection.fromScreenLocation(PointF(cx + px / 2, y)))
                val measured = across(row)
                val atCentre = across(cy)
                linkedMapOf(
                    "label" to b.label,
                    "drawnPx" to round(px.toDouble(), 2),
                    "mapLibreMetersPerDp" to round(mpp, 5),
                    "meters" to round(px / density * mpp, 2),
                    "errorPct" to round((px / density * mpp - b.meters) / b.meters * 100, 3),
                    // T128: measured on the bar's own row (`rowPx`; under tilt the scale differs from the centre's)
                    "measuredRowPx" to Math.round(row),
                    "measuredMeters" to round(measured, 2),
                    "measuredErrorPct" to round((measured - b.meters) / b.meters * 100, 3),
                    "centreRowMeters" to round(atCentre, 2), // the same length across the centre row, for comparison
                    "centreRowErrorPct" to round((atCentre - b.meters) / b.meters * 100, 3),
                )
            }
        }
    }.getOrNull()

    private fun round(v: Double, digits: Int): Double {
        var s = 1.0
        repeat(digits) { s *= 10 }
        return Math.round(v * s) / s
    }
}
