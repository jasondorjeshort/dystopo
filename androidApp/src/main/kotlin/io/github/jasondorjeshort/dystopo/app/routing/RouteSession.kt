package io.github.jasondorjeshort.dystopo.app.routing

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
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
 *
 * T114, route to here: [routeTo] plans from the phone's position ([RouteOrigin]) to a pressed point at once, the
 * long-press card's first action. With no fix it waits (`waitingFix`, or `noLocation` while location is off or not
 * permitted), looking for one every [WAIT_POLL_MS] and planning on the first. The card names both ends ([from], [to]:
 * "From: Your location", "To: 46.80240, -121.72890"), and From offers [fromHere], [chooseOnMap] (`pickStart`: the next
 * long-press is the start) and [swap]. "Route from here" keeps T45's two long-presses (`start`).
 */
object RouteSession {
    val overlay = RouteOverlay().apply { onLongPress = { lat, lon -> longPress(lat, lon) } }

    /**
     * idle, start (waiting for the end), planning, route, failed; T114: waitingFix (a route to here waits for the first
     * fix), noLocation (the same while location is off), pickStart (Choose on map: the next long-press is the start).
     */
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

    /** T114: the route's ends as the card names them (a fix or a placed point); Compose state mirrored from [fromEnd], [toEnd]. */
    var from by mutableStateOf<RouteOrigin.End?>(null)
        private set
    var to by mutableStateOf<RouteOrigin.End?>(null)
        private set

    /** T114: the From choices (Your location, Choose on map, Swap) are open under the From line. Main thread. */
    var fromOpen by mutableStateOf(false)

    @Volatile private var fromEnd: RouteOrigin.End? = null
    @Volatile private var toEnd: RouteOrigin.End? = null
    val start: LatLng? get() = fromEnd?.let { LatLng(it.lat, it.lon) }
    val end: LatLng? get() = toEnd?.let { LatLng(it.lat, it.lon) }

    /** T114: bumped by everything that ends a wait for the fix (a new route, Clear, a long-press start), so a stale poll stops. */
    private val waitToken = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var waitingFor = -1L

    /** T114: the last route to here (`GET /route` `ui.routeTo`): when, the origin it found or that it waited, the polls. */
    @Volatile var lastRouteTo: Map<String, Any?>? = null
        private set
    @Volatile private var waitPolls = 0
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

    /** T119: the Compose fields are written on the main thread; a plan's result is published before [plan] returns. */
    private val uiq = UiPublish({ Looper.myLooper() == Looper.getMainLooper() }, { main.post(it) })

    private fun ui(block: () -> Unit) = uiq.post(block)

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
            setEnds(r.from ?: RouteOrigin.End(r.start.first, r.start.second), r.to ?: RouteOrigin.End(r.end.first, r.end.second))
            restored = linkedMapOf("routeId" to r.plan.route.id, "savedAt" to r.savedAt, "points" to r.plan.route.points.size, "directions" to r.plan.instructions.size)
            ui { if (plan == null && phase == "idle") { phase = "route"; mode = r.plan.mode; plan = r.plan; message = null } }
            overlay.show(r.plan.route.points, null, null)
            Log.i(RoutePlanner.TAG, "restored the unsaved route ${r.plan.route.id} (${r.plan.route.points.size} points)")
        }
    }

    /** T108: writes [p] as the unsaved route ([SessionRoute]); null deletes it. */
    private fun persist(p: PlannedRoute?) {
        val s = fromEnd
        val e = toEnd
        runCatching {
            val f = sessionFile()
            if (p == null || s == null || e == null) { f.delete(); return@runCatching }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(SessionRoute.encode(p, s.lat to s.lon, e.lat to e.lon, System.currentTimeMillis(), s, e), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(RoutePlanner.TAG, "could not save the unsaved route", it) }
    }

    /** Sets both ends: the volatile pair the planner reads, and the card's Compose mirror. */
    private fun setEnds(f: RouteOrigin.End?, t: RouteOrigin.End?) {
        fromEnd = f
        toEnd = t
        ui { from = f; to = t }
    }

    /**
     * A long-press at ([lat], [lon]): the start, then the end (which plans the route), then a new start. T114: after
     * Choose on map (`pickStart`) it is the start of a route to the end already chosen.
     */
    fun longPress(lat: Double, lon: Double) {
        longPresses++
        if (phase == "planning") return
        val chosenEnd = toEnd
        if (phase == "pickStart" && chosenEnd != null) {
            val travel = mode
            waitToken.incrementAndGet()
            worker.execute { runCatching { plan(lat, lon, chosenEnd.lat, chosenEnd.lon, travel, "Route", WAIT_MS, RouteOrigin.End(lat, lon), chosenEnd) } }
            return
        }
        val s = fromEnd
        if (phase == "start" && s != null) {
            val travel = mode
            worker.execute { runCatching { plan(s.lat, s.lon, lat, lon, travel, "Route", WAIT_MS, s, RouteOrigin.End(lat, lon)) } }
            return
        }
        waitToken.incrementAndGet()
        waitingFor = -1
        setEnds(RouteOrigin.End(lat, lon), null)
        failure = null
        ui { phase = "start"; plan = null; message = "Long-press the end point"; fromOpen = false }
        overlay.show(null, start, null)
        RoutePlanner.ensure() // build or load the graph while the user picks the end
    }

    /**
     * T114, "Route to here": a route from the phone's position ([currentFix] at this moment) to ([lat], [lon]) in the
     * card's mode. With no fix it waits for the first one (see the class comment). Runs on the planning worker, or with
     * [wait] on the calling thread (the debug route; never the main thread), so the answer already holds the plan.
     */
    fun routeTo(lat: Double, lon: Double, wait: Boolean = false, travel: TravelMode? = null) {
        val token = waitToken.incrementAndGet()
        val t = travel ?: mode
        if (travel != null) ui { mode = travel }
        val job = { routeToNow(token, RouteOrigin.End(lat, lon), t) }
        if (wait) job() else worker.execute(job)
    }

    private fun routeToNow(token: Long, dest: RouteOrigin.End, travel: TravelMode) {
        if (token != waitToken.get()) return
        val now = System.currentTimeMillis()
        val fix = currentFix()
        if (fix != null) {
            val origin = RouteOrigin.end(fix, now)
            lastRouteTo = linkedMapOf("at" to now, "origin" to origin.json(), "waited" to false)
            runCatching { plan(fix.lat, fix.lon, dest.lat, dest.lon, travel, "Route", WAIT_MS, origin, dest) }
            return
        }
        val loc = locationState()
        lastRouteTo = linkedMapOf("at" to now, "origin" to null, "waited" to true, "location" to loc)
        failure = null
        waitPolls = 0
        waitingFor = token
        setEnds(null, dest)
        persist(null)
        ui {
            phase = if (loc == "on") "waitingFix" else "noLocation"
            plan = null; directionsOpen = false; fromOpen = false
            message = if (loc == "on") RouteOrigin.WAITING else RouteOrigin.LOCATION_OFF
        }
        overlay.show(null, null, LatLng(dest.lat, dest.lon))
        RoutePlanner.ensure() // build or load the graph while the fix comes
        main.postDelayed({ worker.execute { pollFix(token) } }, WAIT_POLL_MS)
    }

    /** T114: one look for the origin of a route to here that waits for it (planning worker); plans on the first fix. */
    private fun pollFix(token: Long) {
        if (token != waitToken.get() || waitingFor != token) return
        val dest = toEnd ?: return
        waitPolls++
        val fix = currentFix()
        if (fix == null) {
            val loc = locationState()
            ui {
                if (phase == "waitingFix" || phase == "noLocation") {
                    phase = if (loc == "on") "waitingFix" else "noLocation"
                    message = if (loc == "on") RouteOrigin.WAITING else RouteOrigin.LOCATION_OFF
                }
            }
            main.postDelayed({ worker.execute { pollFix(token) } }, WAIT_POLL_MS)
            return
        }
        val now = System.currentTimeMillis()
        val origin = RouteOrigin.end(fix, now)
        val since = (lastRouteTo?.get("at") as? Long) ?: now
        lastRouteTo = lastRouteTo.orEmpty() + linkedMapOf("origin" to origin.json(), "fixAfterMs" to now - since, "polls" to waitPolls)
        runCatching { plan(fix.lat, fix.lon, dest.lat, dest.lon, mode, "Route", WAIT_MS, origin, dest) }
    }

    /**
     * T114: the phone's position now: the newer of the recorder's last fix and the map's device fix (the recorder's on a
     * tie), as `Tracking.dropWaypoint` chooses "here"; null with neither. Off the main thread (it waits for the tracking thread).
     */
    fun currentFix(): RouteOrigin.Fix? {
        val app = DystopoApp.instance
        val device = app.map.lastLocation?.let { RouteOrigin.Fix(it.latitude, it.longitude, it.time, "device") }
        val t = Tracking.of(app)
        val rec = runCatching { t.call { t.recorder.lastFix } }.getOrNull()?.let { RouteOrigin.Fix(it.lat, it.lon, it.time, "recorder") }
        return RouteOrigin.choose(rec, device)
    }

    /** T114: `on`, `noPermission` (the app may not read the location) or `off` (the phone's location setting is off). */
    fun locationState(): String {
        val app = DystopoApp.instance
        if (!io.github.jasondorjeshort.dystopo.app.controls.Recenter.permitted(app)) return "noPermission"
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return "on"
        val on = runCatching {
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { lm.isProviderEnabled(it) }
        }.getOrDefault(true)
        return if (on) "on" else "off"
    }

    /**
     * T114, From › "Your location": the same destination from the phone's position (after a Swap, the end that is not
     * the fix). False when there is no placed end to route to.
     */
    fun fromHere(wait: Boolean = false): Boolean {
        val t = toEnd
        val f = fromEnd
        val dest = when {
            t != null && !t.isFix -> t
            f != null && !f.isFix -> f
            else -> return false
        }
        ui { fromOpen = false }
        routeTo(dest.lat, dest.lon, wait)
        return true
    }

    /** T114, From › "Choose on map": the next long-press is the start of a route to the same end. Main thread. */
    fun chooseOnMap(): Boolean {
        val t = toEnd ?: return false
        if (phase == "planning") return false
        waitToken.incrementAndGet()
        waitingFor = -1
        failure = null
        persist(null)
        setEnds(null, t)
        ui { phase = "pickStart"; plan = null; message = RouteOrigin.PICK_START; fromOpen = false; directionsOpen = false }
        overlay.show(null, null, LatLng(t.lat, t.lon))
        return true
    }

    /** T114, From › "Swap": the same two ends the other way round, planned again (on the calling thread with [wait]). */
    fun swap(wait: Boolean = false): Boolean {
        val f = fromEnd ?: return false
        val t = toEnd ?: return false
        if (phase == "planning") return false
        waitToken.incrementAndGet()
        ui { fromOpen = false }
        val travel = mode
        val name = plan?.route?.name ?: "Route"
        val job = { runCatching { plan(t.lat, t.lon, f.lat, f.lon, travel, name, WAIT_MS, t, f) }; Unit }
        if (wait) job() else worker.execute(job)
        return true
    }

    /**
     * Plans from start to end and shows the result (blocking; never on the main thread). T114: [fromAs] and [toAs] say
     * what each end is (the phone's fix or a placed point); placed points when null, as `POST /route` plans. T119: returns
     * once the main thread has made the result the session's ([UiPublish.publish], at most [PUBLISH_MS]), so whatever
     * the caller does next (`POST /route` answering, then `GET /nav/directions?route=current`) sees it.
     */
    fun plan(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, travel: TravelMode, name: String, waitMs: Long,
        fromAs: RouteOrigin.End? = null, toAs: RouteOrigin.End? = null,
    ): RouteResult {
        waitingFor = -1
        setEnds(fromAs ?: RouteOrigin.End(fromLat, fromLon), toAs ?: RouteOrigin.End(toLat, toLon))
        ui { phase = "planning"; mode = travel; plan = null; message = "Planning..."; fromOpen = false }
        overlay.show(null, start, end)
        val result = try {
            RoutePlanner.plan(fromLat, fromLon, toLat, toLon, travel, name, waitMs).let { r ->
                // T114: an end that is the phone's position, off the network, is said so ("of your location").
                if (r !is RouteResult.Failed) r
                else {
                    val drive = travel == TravelMode.DRIVE
                    var f: RouteResult.Failed = r
                    if (fromAs?.isFix == true) f = OffNetwork.atFix(f, start = true, AppSettings.units, drive)
                    if (toAs?.isFix == true) f = OffNetwork.atFix(f, start = false, AppSettings.units, drive)
                    f
                }
            }
        } catch (e: RoutePlanner.NotReady) {
            val s = e.state
            val why = when {
                s == null -> "this pack has no trail data (no vector archive)"
                s.status == "error" -> "the trail network could not be built: ${s.error}"
                else -> "the trail network is still being built (${s.json()["progress"] ?: s.status}); try again in a moment"
            }
            uiq.publish(PUBLISH_MS) { phase = "failed"; message = why }
            throw e
        }
        when (result) {
            is RouteResult.Found -> {
                failure = null
                persist(result.plan) // T108: outlives the process
                uiq.publish(PUBLISH_MS) { phase = "route"; plan = result.plan; message = null } // T119: before the caller answers
                overlay.show(result.plan.route.points, null, null)
                // T49: show the whole route. T114: once the finished card has been measured (it grows by the numbers, the
                // trails and the actions after planning, and the fit used to leave the start, the phone's position, under it).
                main.postDelayed({ if (plan === result.plan) fitCamera(result.plan) }, FIT_AFTER_MS)
            }
            is RouteResult.Failed -> {
                failure = result
                persist(null)
                uiq.publish(PUBLISH_MS) { phase = "failed"; plan = null; message = result.message }
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
        val s = fromEnd
        val e = toEnd
        ui { mode = travel }
        // T114: a route to here that still waits for its fix, or for a start on the map, plans in the new mode once it has one.
        if (s != null && e != null && phase != "planning" && phase != "pickStart") {
            val replan = { runCatching { plan(s.lat, s.lon, e.lat, e.lon, travel, plan?.route?.name ?: "Route", WAIT_MS, s, e) } }
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
        m.animateCamera(update, FIT_MS, object : org.maplibre.android.maps.MapLibreMap.CancelableCallback {
            override fun onCancel() = dropPadding(m)
            override fun onFinish() = dropPadding(m)
        })
        lastFit = linkedMapOf(
            "bounds" to listOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth),
            "paddingPx" to pad.toList(), "routeId" to p.route.id,
        )
    }

    /**
     * T114: MapLibre keeps a bounds fit's padding on the camera, so every later move (a map link, the recentre button)
     * centred in the padded box, 100 dp or more above the screen's centre; the same view without it. Main thread.
     */
    private fun dropPadding(m: org.maplibre.android.maps.MapLibreMap) {
        val p = m.cameraPosition
        if (p.padding?.any { it != 0.0 } != true) return
        val v = DystopoApp.instance.map.mapView ?: return
        val centre = m.projection.fromScreenLocation(android.graphics.PointF(v.width / 2f, v.height / 2f))
        m.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(p).target(centre).padding(0.0, 0.0, 0.0, 0.0).build()))
        lastFit = lastFit?.plus("paddingDropped" to true)
    }

    fun clear() {
        waitToken.incrementAndGet() // T114: a route to here stops waiting
        waitingFor = -1
        setEnds(null, null)
        failure = null
        persist(null) // T108: Clear and Keep drop the saved route (a small file delete)
        ui { phase = "idle"; plan = null; message = null; directionsOpen = false; fromOpen = false }
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
        // T114: the two ends as the card names them, the From choices, the location state, the last route to here.
        "from" to fromEnd?.json(), "to" to toEnd?.json(), "fromOpen" to fromOpen, "fromChoices" to fromChoices(),
        "location" to locationState(), "waitingForFix" to (waitingFor >= 0 && waitingFor == waitToken.get()),
        "routeTo" to lastRouteTo,
    )

    /** T114: what the From line's Change offers now (`here`, `map`, `swap`); empty while nothing can change. */
    fun fromChoices(): List<String> {
        val f = fromEnd
        val t = toEnd ?: return emptyList()
        if (phase == "planning" || phase == "start" || phase == "idle") return emptyList()
        return buildList {
            // Your location: not while the card already waits for it (waitingFix, noLocation).
            if (phase == "pickStart" || (f != null && (!f.isFix || t.isFix))) add("here")
            if (phase != "pickStart") add("map")
            if (f != null && (phase == "route" || phase == "failed")) add("swap")
        }
    }

    /** How long a long-press waits for the graph to be built on first use. */
    const val WAIT_MS = 120_000L

    /** T119: how long [plan] waits for the main thread to take its result (MapController.onMain's bound). */
    const val PUBLISH_MS = 5_000L

    /** T114: how often a route to here that waits for its origin looks for a fix. */
    const val WAIT_POLL_MS = 500L

    /** T49: camera fit animation, and the card height assumed before it has been measured. */
    const val FIT_MS = 600

    /** T114: how long after a plan the camera fits it, so the route card has recomposed and been measured. */
    const val FIT_AFTER_MS = 250L
    const val CARD_MIN_DP = 150f
}
