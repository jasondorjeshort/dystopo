package io.github.jasondorjeshort.dystopo.app.viewshed

import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.dem.PeakSort
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T182 viewshed debug routes (debug builds only, behind the loopback token; registered from
 * [io.github.jasondorjeshort.dystopo.app.Routes]):
 * - `GET /viewshed`: the sheet's state ([ViewshedSession.json]): `open`, `running`, `rangeKm` (the sweep's) and
 *   `setting` (`viewRange`), `error`, `requests`, `lastSource` (`longpress`, `position`, `route`), `sort`, `selected`;
 *   `result` with the point, `viewshed` (the shared report: visible, hidden and no-data km², `visibleFraction`, `rays`,
 *   `samples`, `sweepMs`, `dataStopped` with `bearingsShort`, the levels and the horizon's highest point), `timingsMs`
 *   (`demMs` reading the packs, `sweepMs`, `peakReadMs`, `peakTestMs`), `demPacks`, `peakZoom`, `peakTiles`,
 *   `peaksConsidered`, `peaksInView` and `listed` (the sheet's rows in its order: name, kind, elevation and where it
 *   came from, distance, true bearing, `compass`, `angleDeg`, `clearanceDeg`, `skyline`); `overlay` (images per level
 *   with their visible and no-data cells, `installedOnStyle`, `renderMs`, `marked`); `strings` (title, legend, the
 *   data-stopped line, the status line and each row as drawn).
 * - `POST /viewshed?lat=&lon=[&km=10|30|100][&wait=1|0]` or `?from=position`: what the long-press action (or the
 *   position's) does: opens the sheet and starts the sweep (km defaults to the `viewRange` setting); waits for it (up to
 *   two minutes) unless `wait=0`, then answers like `GET /viewshed`. 409 `noFix` from the position without a fix.
 * - `POST /viewshed/close`: closes the sheet and clears the overlay.
 * - `POST /viewshed/sort?by=distance|elevation|bearing`: the sheet's sort.
 * - `POST /viewshed/select?i=<row>` or `?name=`: a tap on a row: the peak marked and the camera moved onto it.
 * - `POST /viewshed/bench?lat=&lon=[&ranges=10,30,100][&repeat=1]`: the sweep at each range on the request's thread
 *   (nothing drawn): per range and run the timings, rays, samples, km² and the peaks in view; for the task report's
 *   phone measurement.
 */
object ViewshedRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (!r.path.startsWith("/viewshed") || !BuildConfig.DEBUG_ENDPOINTS) return null
        return when (r.method to r.path) {
            "GET" to "/viewshed" -> Response.json(ViewshedSession.json())
            "POST" to "/viewshed" -> start(r)
            "POST" to "/viewshed/close" -> { ViewshedSession.close(); settle(); Response.json(ViewshedSession.json()) }
            "POST" to "/viewshed/sort" -> {
                val by = r.query["by"]
                if (by == null || PeakSort.entries.none { it.key == by }) return Response.json(mapOf("error" to "by must be distance, elevation or bearing"), 400)
                ViewshedSession.sortBy(PeakSort.of(by)); settle()
                Response.json(ViewshedSession.json())
            }
            "POST" to "/viewshed/select" -> select(r)
            "POST" to "/viewshed/bench" -> bench(r)
            else -> Response.json(mapOf("error" to "unknown viewshed route"), 404)
        }
    }

    private fun km(r: Request): Pair<Int?, String?> {
        val raw = r.query["km"] ?: return null to null
        val v = raw.toIntOrNull()?.takeIf { it in Viewshed.RANGES_KM } ?: return null to "km must be one of ${Viewshed.RANGES_KM.joinToString(", ")}"
        return v to null
    }

    private fun start(r: Request): Response {
        val (km, err) = km(r)
        if (err != null) return Response.json(mapOf("error" to err), 400)
        if (r.query["from"] == "position") {
            if (!ViewshedSession.startAtPosition("route")) return Response.json(mapOf("error" to "noFix") + ViewshedSession.json(), 409)
        } else {
            val lat = r.query["lat"]?.toDoubleOrNull()
            val lon = r.query["lon"]?.toDoubleOrNull()
            if (lat == null || lon == null || lat !in -85.0..85.0 || lon !in -180.0..180.0) return Response.json(mapOf("error" to "lat and lon (or from=position) are required"), 400)
            if (km != null) ViewshedSession.start(lat, lon, "route", km) else ViewshedSession.start(lat, lon, "route")
        }
        if (r.query["wait"] != "0") {
            settle()
            val until = System.currentTimeMillis() + 120_000
            while (ViewshedSession.running && System.currentTimeMillis() < until) Thread.sleep(50)
            settle()
        }
        return Response.json(ViewshedSession.json())
    }

    private fun select(r: Request): Response {
        val rows = ViewshedSession.listed()
        val p = r.query["i"]?.toIntOrNull()?.let { rows.getOrNull(it) } ?: r.query["name"]?.let { n -> rows.firstOrNull { it.peak.name == n } }
            ?: return Response.json(mapOf("error" to "no such row (i or name)", "rows" to rows.size), 400)
        onMain { ViewshedSession.select(p) }
        Thread.sleep(800) // the camera's move
        return Response.json(ViewshedSession.json())
    }

    private fun bench(r: Request): Response {
        val lat = r.query["lat"]?.toDoubleOrNull()
        val lon = r.query["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) return Response.json(mapOf("error" to "lat and lon are required"), 400)
        val ranges = (r.query["ranges"] ?: Viewshed.RANGES_KM.joinToString(",")).split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in Viewshed.RANGES_KM }
        val repeat = (r.query["repeat"]?.toIntOrNull() ?: 1).coerceIn(1, 5)
        val runs = ArrayList<Map<String, Any?>>()
        for (km in ranges) for (k in 1..repeat) {
            val t0 = System.nanoTime()
            val d = runCatching { ViewshedSession.compute(lat, lon, km) }
            val totalMs = Math.round((System.nanoTime() - t0) / 1e5) / 10.0
            runs += d.fold({ v ->
                linkedMapOf(
                    "km" to km, "run" to k, "totalMs" to totalMs, "timingsMs" to v.timingsMs,
                    "rays" to v.result.rays, "samples" to v.result.samples,
                    "visibleKm2" to Math.round(v.result.visibleKm2 * 10) / 10.0, "noDataKm2" to Math.round(v.result.noDataKm2 * 10) / 10.0,
                    "levels" to v.result.levels.map { "z${it.window.zoom} ${it.window.width}x${it.window.height}" },
                    "peaksInView" to v.inView.size, "peaksConsidered" to v.peaks.size,
                )
            }, { e -> linkedMapOf("km" to km, "run" to k, "error" to (e.message ?: e.toString())) })
        }
        return Response.json(mapOf("lat" to lat, "lon" to lon, "runs" to runs, "cpus" to Runtime.getRuntime().availableProcessors()))
    }

    /** Lets posted Compose writes land. */
    private fun settle() = onMain { }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { try { block() } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
    }
}
