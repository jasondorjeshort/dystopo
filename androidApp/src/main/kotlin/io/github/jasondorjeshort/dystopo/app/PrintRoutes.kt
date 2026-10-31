package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.print.PhoneSheet
import io.github.jasondorjeshort.dystopo.app.print.SheetPrint
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import io.github.jasondorjeshort.dystopo.render.SheetQuery

/**
 * T183, the phone's printed sheet ([PhoneSheet]; `android-app.md` › The printed sheet). Debug builds only (devtool
 * has a `PrintRoutes` of its own for `devtool serve --live`):
 * - `GET /print`: what `POST /print` takes (`scales`, `papers`, `grids`, `defaultScale`, `dpi`), `made` (sheets
 *   since start), `lastError`, `last` (the last sheet's report, as below) and `ui` (the last print dialog: `scale`,
 *   `paper`, `landscape`, `state` `dialog|written|failed`, `pdfBytes`).
 * - `POST /print` with `scale=25000` (default; 10000, 50000, any denominator from 500, or `fit` for the map's view),
 *   `paper=a4|letter|a3` (default by region: Letter in the US, A4 elsewhere), `landscape=1`,
 *   `grid=utm|latlon|both|none` (default utm), `title=` (default "Map sheet" and the centre), `dpi=` (100 to 300,
 *   default 200), `lat=&lon=` (default the map's centre; the camera does not move) and `format=pdf|json` (default
 *   pdf): the sheet of the current view, as the Settings row makes it. `pdf` answers the one-page PDF
 *   (`application/pdf`; `devtool`'s rasteriser views it: `./gradlew :devtool:rasterisePdf`); `json` the report:
 *   `title`, `scale`, `ratio`, `fit`, `paper`, `landscape`, `dpi` (the nominal 200 moved by under 0.1 percent so the
 *   scale is exact), `pagePoints`, `pageWidthPx`, `pageHeightPx`, `frameMm`, `framePx`, `extent`, `groundMeters`,
 *   `center`, `metersPerPixel`, `snapshot` (`zoom`, `pixelRatio`, `logical` and `bitmap` sizes, `crop`,
 *   `placementErrorPx`: MapLibre's pixel of the frame's corners against the layout's, `attributions`, `ms`),
 *   `grid`, `utmZone`, `gridSpacingM`, `gridLines`, `graticuleArcSec`, `declinationDeg`, `convergenceDeg`,
 *   `magneticFromGridDeg`, `date`, `units`, `contourInterval`, `attribution`, `overlay` (`routePoints`,
 *   `trackSegments`, `trackPoints`, `waypointsDrawn`, `waypointsOutside`), `margin` (every margin text),
 *   `textsDrawn`, `pdfBytes`, `pages`, `timingsMs` (`plan`, `snapshot`, `draw`, `writePdf`, `total`). A bad option is
 *   a 400; a snapshot that fails or takes over 90 s a 500. One sheet at a time; a second request waits.
 * - `POST /print/ui?scale=25000|10000|50000|fit`: what a tap on the Settings row's scale does: the system print
 *   dialog for the current view (503 while the activity is stopped).
 */
object PrintRoutes {
    private val lock = Any()

    fun handle(app: DystopoApp, r: Request): Response? {
        if (!BuildConfig.DEBUG_ENDPOINTS) return null
        return when (r.method to r.path) {
            "GET" to "/print" -> Response.json(status())
            "POST" to "/print" -> make(app, r)
            "POST" to "/print/ui" -> ui(app, r)
            else -> if (r.path == "/print" || r.path == "/print/ui") Response.json(mapOf("error" to "method"), 405) else null
        }
    }

    private fun status(): Map<String, Any?> = linkedMapOf(
        "scales" to PageLayout.SCALES + listOf("fit"),
        "defaultScale" to PageLayout.DEFAULT_SCALE.toLong(),
        "papers" to Paper.entries.map { it.key },
        "grids" to SheetGrid.entries.map { it.key },
        "dpi" to PhoneSheet.DPI,
        "made" to PhoneSheet.made,
        "lastError" to PhoneSheet.lastError,
        "last" to PhoneSheet.last,
        "ui" to SheetPrint.lastUi,
    )

    private fun make(app: DystopoApp, r: Request): Response {
        val q = r.query
        val spec = try {
            val opts = SheetQuery.parse(q)
            val dpi = q["dpi"]?.let { d -> d.toDoubleOrNull()?.takeIf { it in 100.0..300.0 } ?: throw IllegalArgumentException("dpi must be 100 to 300") } ?: PhoneSheet.DPI
            val title = q["title"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            val lat = q["lat"]?.let { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: throw IllegalArgumentException("lat must be a number") }
            val lon = q["lon"]?.let { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: throw IllegalArgumentException("lon must be a number") }
            require((lat == null) == (lon == null)) { "lat and lon go together" }
            require(lat == null || opts.scale != null) { "scale=fit fits the map's view; leave out lat and lon" }
            val view = PhoneSheet.currentView(app, opts.scale, opts.paper, opts.landscape, opts.grid, title)
            (if (lat != null && lon != null) view.copy(centerLat = lat, centerLon = lon) else view).copy(dpi = dpi)
        } catch (e: IllegalArgumentException) {
            return Response.json(mapOf("error" to (e.message ?: e.toString())), 400)
        }
        val format = q["format"] ?: "pdf"
        if (format != "pdf" && format != "json") return Response.json(mapOf("error" to "format must be pdf or json"), 400)
        val sheet = try {
            synchronized(lock) { PhoneSheet.make(app, spec) }
        } catch (e: IllegalArgumentException) {
            return Response.json(mapOf("error" to (e.message ?: e.toString())), 400)
        } catch (e: Exception) {
            return Response.json(mapOf("error" to e.toString()), 500)
        }
        return if (format == "pdf") Response(200, "application/pdf", sheet.pdf) else Response.json(sheet.report)
    }

    private fun ui(app: DystopoApp, r: Request): Response {
        app.map.backgroundReason()?.let { return Response.json(mapOf("error" to "the app is not on screen", "reason" to it, "route" to "/print/ui"), 503) }
        val scaleText = (r.query["scale"] ?: PageLayout.DEFAULT_SCALE.toLong().toString()).trim().lowercase()
        val scale = if (scaleText == "fit") null else scaleText.toDoubleOrNull()?.takeIf { it >= 500 }
            ?: return Response.json(mapOf("error" to "scale must be a denominator of at least 500 or fit"), 400)
        val why = app.map.onMain { SheetPrint.printCurrentView(app, scale) }
        return if (why == null) Response.json(mapOf("opened" to true, "ui" to SheetPrint.lastUi)) else Response.json(mapOf("opened" to false, "error" to why), 409)
    }
}
