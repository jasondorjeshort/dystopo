package io.github.jasondorjeshort.dystopo.app.controls

import android.graphics.PointF
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T101, the scale bar's debug routes (debug builds only, on the debug port):
 * - `GET /scalebar`: what the bar shows ([ScaleBarLayout]): `shown` or `hiddenBecause` (`tripCard`, `noCamera`),
 *   `bothUnits`, `units`, `night`, the camera's `zoom` and `lat` it was fitted at, `metersPerDp` (the shared Web
 *   Mercator rule), `density`, and per bar (the user's units first) `label`, `value`, `unit`, `meters`, `widthDp`,
 *   `widthPx` and `drawnPx` (tick to tick as drawn); where it sits (`boundsPx` in window pixels, its bottom edge,
 *   the credit line, route card and track panel edges it keeps above, and `clear`). `readBack` checks each bar
 *   against MapLibre itself on the live camera: `mapLibreMetersPerDp` (its projection at the centre latitude; per
 *   logical pixel, which is a dp), `meters` at that scale over `drawnPx` and `errorPct` against the label, and `measuredMeters`
 *   (the ground distance between two screen points `drawnPx` apart across the map's centre) with its `errorPct`.
 * - `POST /scalebar?both=on|off`: the Map options row "Scale bar" (one unit or both); answers like `GET` once the
 *   bar has redrawn. Anything else for `both` is a 400.
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

    private fun state(app: DystopoApp): Map<String, Any?> = ScaleBarLayout.json() + mapOf("readBack" to readBack(app))

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
            ScaleBarLayout.bars.mapIndexed { i, b ->
                val px = drawn.getOrNull(i) ?: (b.px * ScaleBarLayout.density).toFloat()
                val a = m.projection.fromScreenLocation(PointF(cx - px / 2, cy))
                val c = m.projection.fromScreenLocation(PointF(cx + px / 2, cy))
                val measured = a.distanceTo(c)
                linkedMapOf(
                    "label" to b.label,
                    "drawnPx" to round(px.toDouble(), 2),
                    "mapLibreMetersPerDp" to round(mpp, 5),
                    "meters" to round(px / density * mpp, 2),
                    "errorPct" to round((px / density * mpp - b.meters) / b.meters * 100, 3),
                    "measuredMeters" to round(measured, 2),
                    "measuredErrorPct" to round((measured - b.meters) / b.meters * 100, 3),
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
