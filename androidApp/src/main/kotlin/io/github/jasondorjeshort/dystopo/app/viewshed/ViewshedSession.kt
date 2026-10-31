package io.github.jasondorjeshort.dystopo.app.viewshed

import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.PeakSort
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.dem.ViewshedResult
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T182: "What can I see from here" on the phone: [start] from a long-pressed point or [startAtPosition] from the fix,
 * the sweep ([Viewshed.compute]) and the peaks ([Viewshed.readPeaks], [Viewshed.peaks]) on one background thread at
 * background priority, never the UI thread; the result drawn by [overlay] and listed by the sheet ([ViewshedSheet]).
 *
 * The DEM comes from the offline packs only: each window is read from every DEM pack that overlaps it, the first with
 * data winning per cell (the packs' precedence order), each through its own small [DemPyramid] made for the sweep and
 * dropped after it. Past the packs' edge the overlay hatches the ground (no data, nothing known beyond). The peaks come
 * from the vector packs' `mountain_peak` layer at [Viewshed.peakZoom]. The range is [AppSettings.viewRangeKm].
 *
 * Compose state is written on the main thread; a newer [start] supersedes a running one (its result is dropped).
 */
object ViewshedSession {
    /** One finished viewshed: the inputs, the result, the peaks (all tested; [inView] those seen) and the timings. */
    class Done(
        val lat: Double, val lon: Double, val rangeKm: Int, val source: String, val result: ViewshedResult,
        val peaks: List<PeakView>, val peakZoom: Int, val peakTiles: Int, val timingsMs: Map<String, Double>, val demPacks: List<String>,
    ) {
        val inView: List<PeakView> get() = peaks.filter { it.visible }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "viewshed").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()

    val overlay = ViewshedOverlay()

    /** The sheet is up (a viewshed asked for and not closed). */
    var open by mutableStateOf(false)
        private set

    /** A sweep is running. */
    var running by mutableStateOf(false)
        private set

    /** The range the running or last sweep looks, km. */
    var rangeKm by mutableStateOf(AppSettings.viewRangeKm)
        private set

    var done by mutableStateOf<Done?>(null)
        private set

    /** What went wrong with the last request (no DEM there, no fix), shown in the sheet; null when fine. */
    var error by mutableStateOf<String?>(null)
        private set

    var sort by mutableStateOf(PeakSort.DISTANCE)
        private set

    /** The peak the sheet's tap showed on the map, or null. */
    var selected by mutableStateOf<PeakView?>(null)
        private set

    @Volatile var requests = 0
        private set
    @Volatile var lastSource: String? = null
        private set

    /** Starts a viewshed from ([lat], [lon]); [source] is `longpress`, `position` or `route`. Any thread. */
    fun start(lat: Double, lon: Double, source: String, km: Int = AppSettings.viewRangeKm) {
        requests++
        lastSource = source
        val gen = generation.incrementAndGet()
        ui {
            open = true
            running = true
            rangeKm = km
            error = null
            selected = null
            done = null
            overlay.clear()
            DystopoApp.instance.map.mapView?.let { overlay.bind(it) }
        }
        worker.execute {
            val out = runCatching { compute(lat, lon, km, source) }
            ui {
                if (gen != generation.get()) return@ui
                running = false
                out.onSuccess { d ->
                    done = d
                    overlay.show(d)
                }.onFailure { e -> error = e.message ?: e.toString() }
            }
        }
    }

    /** From the phone's position (the last fix); false (and the sheet says so) without one. */
    fun startAtPosition(source: String = "position"): Boolean {
        val app = DystopoApp.instance
        val loc = app.map.lastLocation
        val fix = loc?.let { it.latitude to it.longitude } ?: runCatching { io.github.jasondorjeshort.dystopo.app.LastFix.get() }.getOrNull()?.let { it.lat to it.lon }
        if (fix == null) {
            ui { open = true; running = false; done = null; error = ViewshedText.NO_FIX }
            return false
        }
        start(fix.first, fix.second, source)
        return true
    }

    fun close() {
        generation.incrementAndGet()
        ui {
            open = false
            running = false
            done = null
            error = null
            selected = null
            overlay.clear()
        }
    }

    fun sortBy(s: PeakSort) = ui { sort = s }

    /** Shows [p] on the map: a marker with its name, the camera moved onto it (the zoom kept, at least 11). Main thread. */
    fun select(p: PeakView) {
        selected = p
        overlay.mark(p)
        val map = DystopoApp.instance.map.map ?: return
        val zoom = maxOf(map.cameraPosition.zoom, 11.0)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.peak.lat, p.peak.lon), zoom), 600)
    }

    /** The peaks in view in the sheet's order. */
    fun listed(): List<PeakView> = done?.let { sort.sort(it.inView) } ?: emptyList()

    /** The sweep itself (the worker, or a debug route's thread). Throws with [ViewshedText.NO_DEM] where no pack has elevation. */
    fun compute(lat: Double, lon: Double, km: Int, source: String = "bench"): Done {
        val app = DystopoApp.instance
        val t = LinkedHashMap<String, Double>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val n = System.nanoTime(); t[name] = Math.round((n - t0) / 1e5) / 10.0; t0 = n }
        val rangeM = km * 1000.0
        val plan = Viewshed.plan(lat, lon, rangeM)
        val packs = app.tiles.packs.demPacks
        val pyramids = HashMap<Pack, DemPyramid>()
        val used = LinkedHashSet<String>()
        val read: (MercatorWindow) -> ElevationGrid = { w -> readMerged(w, packs, pyramids, used) }
        val terrain = Viewshed.terrain(plan, read)
        lap("demMs")
        if (terrain.elevationAt(lat, lon).isNaN()) throw IllegalStateException(ViewshedText.NO_DEM)
        val result = Viewshed.compute(terrain, plan)
        lap("sweepMs")
        val peakZoom = Viewshed.peakZoom(lat, rangeM)
        var tiles = 0
        val found = Viewshed.readPeaks({ z, x, y ->
            app.tiles.packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) }?.also { tiles++ }
        }, lat, lon, rangeM, peakZoom)
        lap("peakReadMs")
        val peaks = Viewshed.peaks(terrain, result, found)
        lap("peakTestMs")
        pyramids.clear()
        return Done(lat, lon, km, source, result, peaks, peakZoom, tiles, t, used.toList())
    }

    /** [w] from every DEM pack overlapping it, the first with data winning per cell; NaN where none has data. */
    private fun readMerged(w: MercatorWindow, packs: List<Pack>, pyramids: HashMap<Pack, DemPyramid>, used: MutableSet<String>): ElevationGrid {
        val west = WebMercator.xToLon(w.x0.toDouble() / w.tileSize, w.zoom)
        val east = WebMercator.xToLon((w.x0 + w.width).toDouble() / w.tileSize, w.zoom)
        val north = WebMercator.yToLat(w.y0.toDouble() / w.tileSize, w.zoom)
        val south = WebMercator.yToLat((w.y0 + w.height).toDouble() / w.tileSize, w.zoom)
        val values = FloatArray(w.width * w.height) { Float.NaN }
        var missing = values.size
        for (p in packs) {
            if (missing == 0) break
            val h = p.header
            if (h.maxLon < west || h.minLon > east || h.maxLat < south || h.minLat > north) continue
            val pyr = pyramids.getOrPut(p) { DemPyramid(h.minZoom, h.maxZoom, TileService.demDecoder(), cacheTiles = 48) { z, x, y -> p.tile(z, x, y) } }
            val g = pyr.window(w.zoom, w.x0, w.y0, w.width, w.height)
            var took = false
            for (k in values.indices) {
                if (values[k].isNaN() && !g.values[k].isNaN()) { values[k] = g.values[k]; missing--; took = true }
            }
            if (took) used += DystopoApp.instance.tiles.packs.packOf(p) ?: p.name
        }
        return ElevationGrid(w.width, w.height, w.cellSizeMeters, values)
    }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** `GET /viewshed`. */
    fun json(): Map<String, Any?> {
        val d = done
        return linkedMapOf(
            "open" to open, "running" to running, "rangeKm" to rangeKm, "setting" to AppSettings.viewRangeKm,
            "error" to error, "requests" to requests, "lastSource" to lastSource, "sort" to sort.key,
            "selected" to selected?.peak?.name,
            "result" to d?.let { r ->
                linkedMapOf(
                    "lat" to r.lat, "lon" to r.lon, "rangeKm" to r.rangeKm, "source" to r.source,
                    "viewshed" to r.result.json(), "timingsMs" to r.timingsMs, "demPacks" to r.demPacks,
                    "peakZoom" to r.peakZoom, "peakTiles" to r.peakTiles,
                    "peaksConsidered" to r.peaks.size, "peaksInView" to r.inView.size,
                    "listed" to listed().map { it.json() },
                )
            },
            "overlay" to overlay.json(),
            "strings" to linkedMapOf(
                "title" to ViewshedText.TITLE, "legend" to ViewshedText.LEGEND, "dataStopped" to ViewshedText.DATA_STOPPED,
                "status" to d?.let { ViewshedText.done(it.rangeKm, AppSettings.units, it.result.ms, it.inView.size) },
                "rows" to listed().map { "${it.peak.name}: ${ViewshedText.peakLine(it, AppSettings.units)}" },
            ),
        )
    }
}
