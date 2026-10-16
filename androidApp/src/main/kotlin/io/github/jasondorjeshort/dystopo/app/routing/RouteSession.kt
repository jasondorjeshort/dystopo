package io.github.jasondorjeshort.dystopo.app.routing

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * The route being planned on the map: long-press once for the start, again for the end; the route is
 * planned in the background and drawn by [overlay]; a third long-press starts over. `POST /route` goes
 * through [plan] too, so the debug route and a user's long-presses end in the same state.
 * The Compose fields are written on the main thread only.
 *
 * T108: the route not yet kept also outlives the process ([SessionRoute], `files/route-session.json`): written after
 * every plan, deleted by Clear and Keep, read back once when the card first composes ([restoreOnce]), so a font size
 * change made in the system settings while Android stops the app in the background does not lose it. (A recreated
 * activity in the same process already found it here.)
 */
object RouteSession {
    val overlay = RouteOverlay().apply { onLongPress = { lat, lon -> longPress(lat, lon) } }

    /** idle, start (waiting for the end), planning, route, failed. */
    var phase by mutableStateOf("idle")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(TravelMode.HIKE)
        private set
    var plan by mutableStateOf<PlannedRoute?>(null)
        private set

    /** T102: the route card's list of turn directions is open (the "Directions" action, `POST /nav/directions?open=1`). */
    var directionsOpen by mutableStateOf(false)

    /** T102: the directions the card counts and lists: the plan's at the default detail level. */
    fun shownDirections(p: PlannedRoute): List<io.github.jasondorjeshort.dystopo.routing.Instruction> =
        io.github.jasondorjeshort.dystopo.routing.Directions.shown(p.instructions)

    @Volatile var start: LatLng? = null
        private set
    @Volatile var end: LatLng? = null
        private set
    @Volatile var failure: RouteResult.Failed? = null
        private set
    @Volatile var lastFile: File? = null
        private set
    @Volatile var longPresses = 0
        private set

    /** T49: the camera fits each new route; the last fit (bounds and padding) for `GET /route`. */
    @Volatile var lastFit: Map<String, Any?>? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-route-plan").apply { isDaemon = true } }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** T108: the saved unsaved route (see the class comment). */
    private fun sessionFile(): File = File(DystopoApp.instance.filesDir, SessionRoute.FILE_NAME)

    /** T108: whether this process restored a route from [SessionRoute], and when it had been saved (`GET /route` `ui.restored`). */
    @Volatile var restored: Map<String, Any?>? = null
        private set
    private val restoreStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** T108: reads the saved route back once per process (on the planning worker), unless something was planned meanwhile. */
    fun restoreOnce() {
        if (!restoreStarted.compareAndSet(false, true)) return
        worker.execute {
            val f = sessionFile()
            val r = runCatching { if (f.isFile) SessionRoute.decode(f.readText(Charsets.UTF_8)) else null }.getOrNull() ?: return@execute
            if (plan != null || start != null) return@execute
            start = LatLng(r.start.first, r.start.second)
            end = LatLng(r.end.first, r.end.second)
            restored = linkedMapOf("routeId" to r.plan.route.id, "savedAt" to r.savedAt, "points" to r.plan.route.points.size, "directions" to r.plan.instructions.size)
            ui { if (plan == null && phase == "idle") { phase = "route"; mode = r.plan.mode; plan = r.plan; message = null } }
            overlay.show(r.plan.route.points, null, null)
            Log.i(RoutePlanner.TAG, "restored the unsaved route ${r.plan.route.id} (${r.plan.route.points.size} points)")
        }
    }

    /** T108: writes [p] as the unsaved route ([SessionRoute]); null deletes it. */
    private fun persist(p: PlannedRoute?) {
        val s = start
        val e = end
        runCatching {
            val f = sessionFile()
            if (p == null || s == null || e == null) { f.delete(); return@runCatching }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(SessionRoute.encode(p, s.latitude to s.longitude, e.latitude to e.longitude, System.currentTimeMillis()), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(RoutePlanner.TAG, "could not save the unsaved route", it) }
    }

    /** A long-press at ([lat], [lon]): the start, then the end (which plans the route), then a new start. */
    fun longPress(lat: Double, lon: Double) {
        longPresses++
        if (phase == "planning") return
        if (phase == "start" && start != null) {
            end = LatLng(lat, lon)
            val s = start!!
            val travel = mode
            worker.execute { runCatching { plan(s.latitude, s.longitude, lat, lon, travel, "Route", WAIT_MS) } }
            return
        }
        start = LatLng(lat, lon)
        end = null
        failure = null
        ui { phase = "start"; plan = null; message = "Long-press the end point" }
        overlay.show(null, start, null)
        RoutePlanner.ensure() // build or load the graph while the user picks the end
    }

    /** Plans from start to end and shows the result (blocking; never on the main thread). */
    fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, travel: TravelMode, name: String, waitMs: Long): RouteResult {
        start = LatLng(fromLat, fromLon)
        end = LatLng(toLat, toLon)
        ui { phase = "planning"; mode = travel; plan = null; message = "Planning..." }
        overlay.show(null, start, end)
        val result = try {
            RoutePlanner.plan(fromLat, fromLon, toLat, toLon, travel, name, waitMs)
        } catch (e: RoutePlanner.NotReady) {
            val s = e.state
            val why = when {
                s == null -> "this pack has no trail data (no vector archive)"
                s.status == "error" -> "the trail network could not be built: ${s.error}"
                else -> "the trail network is still being built (${s.json()["progress"] ?: s.status}); try again in a moment"
            }
            ui { phase = "failed"; message = why }
            throw e
        }
        when (result) {
            is RouteResult.Found -> {
                failure = null
                persist(result.plan) // T108: outlives the process
                ui { phase = "route"; plan = result.plan; message = null }
                overlay.show(result.plan.route.points, null, null)
                ui { fitCamera(result.plan) } // T49: show the whole route
            }
            is RouteResult.Failed -> {
                failure = result
                persist(null)
                ui { phase = "failed"; plan = null; message = result.message }
                overlay.show(null, start, end)
            }
        }
        return result
    }

    /**
     * Plans the current start and end again in [travel] mode: in the background, or with [wait] (T49, the
     * debug route) on the calling thread, so the answer already holds the new route.
     */
    fun changeMode(travel: TravelMode, wait: Boolean = false) {
        val s = start
        val e = end
        ui { mode = travel }
        if (s != null && e != null && phase != "planning") {
            val replan = { runCatching { plan(s.latitude, s.longitude, e.latitude, e.longitude, travel, plan?.route?.name ?: "Route", WAIT_MS) } }
            if (wait) replan() else worker.execute { replan() }
        }
    }

    /**
     * T49: moves the camera so the whole route shows, clear of the route card at the bottom (its last
     * measured top, at least [CARD_MIN_DP]) and the search box at the top. Main thread.
     */
    private fun fitCamera(p: PlannedRoute) {
        val m = DystopoApp.instance.map.map ?: return
        val pts = p.route.points
        if (pts.size < 2) return
        val b = LatLngBounds.Builder().apply { for (pt in pts) include(LatLng(pt.lat, pt.lon)) }.build()
        val d = DystopoApp.instance.resources.displayMetrics.density
        val bottomDp = maxOf(BottomPanels.routeCardTop.value, CARD_MIN_DP) + 16
        val pad = intArrayOf((40 * d).toInt(), (96 * d).toInt(), (40 * d).toInt(), (bottomDp * d).toInt())
        // Degenerate (all points the same): just centre on it.
        val update = if (b.latitudeSpan < 1e-6 && b.longitudeSpan < 1e-6) CameraUpdateFactory.newLatLng(b.center)
        else CameraUpdateFactory.newLatLngBounds(b, pad[0], pad[1], pad[2], pad[3])
        m.animateCamera(update, FIT_MS)
        lastFit = linkedMapOf(
            "bounds" to listOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth),
            "paddingPx" to pad.toList(), "routeId" to p.route.id,
        )
    }

    fun clear() {
        start = null
        end = null
        failure = null
        persist(null) // T108: Clear and Keep drop the saved route (a small file delete)
        ui { phase = "idle"; plan = null; message = null; directionsOpen = false }
        overlay.show(null, null, null)
    }

    /** Saves the current route in `files/routes/`; null when there is none. */
    fun save(name: String? = null): File? {
        val p = plan ?: return null
        val route = if (name.isNullOrBlank()) p.route else p.route.copy(name = name)
        return RoutePlanner.save(route).also { f ->
            lastFile = f
            ui { message = "Saved to routes/${f.name}" }
            Log.i(RoutePlanner.TAG, "saved route ${route.id} to ${f.path}")
        }
    }

    /** Writes the current route as GPX into `files/exports/`; null when there is none. */
    fun exportGpx(): File? {
        val p = plan ?: return null
        val dir = File(DystopoApp.instance.filesDir, "exports").apply { mkdirs() }
        val safe = p.route.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "route" }
        return File(dir, "route-$safe-${p.route.id.take(8)}.gpx").apply { writeText(RoutePlanner.gpx(p.route), Charsets.UTF_8) }.also { f ->
            lastFile = f
            ui { message = "GPX written to exports/${f.name}" }
        }
    }

    /**
     * T65: the route as Keep stores it (its points quantised like a trip's path, [RoutePath.fixed]), so the
     * card, the kept route, the trip card and navigation all measure the same points.
     */
    fun unifiedRoute(p: PlannedRoute): Route = p.route.copy(points = RoutePath.fixed(p.route.points, p.mode.label).points)

    @Volatile private var summaryCache: Pair<PlannedRoute, RouteSummary>? = null

    /**
     * T65: one ascent and descent figure per route. The router's own climb comes from the graph's DEM
     * samples per edge and differed from the kept route's by up to 50 m (wave 10 sweep); everything now
     * shows [RouteSummary] of the route's points (T24's 3 m hysteresis, the T45 hike model), computed once
     * per plan. The graph's numbers stay in `GET /route` under `graph`.
     */
    fun summaryOf(p: PlannedRoute): RouteSummary {
        summaryCache?.let { (k, v) -> if (k === p) return v }
        // T89: as Keep stores it (one trail leg; a drive keeps the router's time), so the card and the trip agree.
        val s = RouteSummary.of(RoutePath.planned(p.route.points, p.mode.label, p.timeS), emptyList(), samples = 0)
        summaryCache = p to s
        return s
    }

    /** `route` in `GET /route` and `POST /route`: the plan with [summaryOf]'s distance, climb and time, the graph's beside them. */
    fun routeJson(p: PlannedRoute, profile: Boolean): Map<String, Any?> {
        val s = summaryOf(p)
        return p.json(profile) + linkedMapOf(
            "distanceM" to RouteSummary.r1(s.distanceM), "ascentM" to s.ascentM?.let { RouteSummary.r1(it) }, "descentM" to s.descentM?.let { RouteSummary.r1(it) },
            "timeS" to s.timeS.roundToLong(), "time" to RouteSummary.hm(s.timeS),
            "graph" to linkedMapOf("ascentM" to p.ascentM?.let { RouteSummary.r1(it) }, "descentM" to p.descentM?.let { RouteSummary.r1(it) }, "timeS" to p.timeS.roundToLong()),
        )
    }

    /** One line for the card: distance, climb, time, in the user's units ([summaryOf]: the numbers the kept route shows too). */
    fun summary(p: PlannedRoute): String {
        val u = AppSettings.units
        val s = summaryOf(p)
        val climb = s.ascentM?.let { " · +${UnitFormat.elevation(it, u)} −${UnitFormat.elevation(s.descentM ?: 0.0, u)}" } ?: ""
        return "${UnitFormat.distance(s.distanceM, u)}$climb · ${RouteSummary.hm(s.timeS)} ${p.mode.label}"
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "phase" to phase, "message" to message, "mode" to mode.label,
        "start" to start?.let { listOf(it.latitude, it.longitude) }, "end" to end?.let { listOf(it.latitude, it.longitude) },
        "longPresses" to longPresses, "overlayFeatures" to overlay.features, "overlayInstalled" to overlay.installedOnStyle,
        "summary" to plan?.let { summary(it) }, "lastFile" to lastFile?.path,
        "cardHidden" to (io.github.jasondorjeshort.dystopo.app.nav.NavSession.active && phase != "idle"), // T65: hidden while navigating
        "via" to plan?.via?.map { it.first }, // T49: trail names
        "directions" to plan?.let { shownDirections(it).size }, "directionsOpen" to directionsOpen, // T102
        "lastFit" to lastFit, "layout" to BottomPanels.json(), // T49
        "restored" to restored, // T108: the unsaved route read back after the process restarted
    )

    /** How long a long-press waits for the graph to be built on first use. */
    const val WAIT_MS = 120_000L

    /** T49: camera fit animation, and the card height assumed before it has been measured. */
    const val FIT_MS = 600
    const val CARD_MIN_DP = 150f
}
