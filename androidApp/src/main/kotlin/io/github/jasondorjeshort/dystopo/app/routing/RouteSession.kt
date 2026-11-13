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
import io.github.jasondorjeshort.dystopo.app.search.MapCoverage
import io.github.jasondorjeshort.dystopo.app.search.PlaceSearch
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.RouteFailure
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.ShadeText
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.KindChoice
import io.github.jasondorjeshort.dystopo.routing.KindReason
import io.github.jasondorjeshort.dystopo.routing.RouteChip
import io.github.jasondorjeshort.dystopo.routing.TripKind
import io.github.jasondorjeshort.dystopo.routing.TripKinds
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
 *
 * T276: the route's kind ([tripKind], [TripKinds]) is held beside [mode]: Walk and Hike are both [TravelMode.HIKE]. On foot
 * a new route guesses from where it runs (the share in town); a tapped Walk or Hike chip ([tapChip], `POST /route?kind=`)
 * holds through every re-plan of that route (a mode change, Swap, a new start on the map, Your location) until Clear or
 * a new route; no foot chip is lit while a route on foot is planning. An everyday route's water, terrain, best start and
 * past effort are neither drawn nor computed ([isEveryday], read by [RouteWater], [RouteExposure] and [RouteBestStart]),
 * and navigation of it starts without the turn-back clock, the next water and the next shade
 * ([io.github.jasondorjeshort.dystopo.app.nav.NavSources.currentRoute]). The kind and whether a tap chose it outlive the
 * process with the route ([SessionRoute]).
 */
object RouteSession {
    val overlay = RouteOverlay().apply { onLongPress = { lat, lon -> longPress(lat, lon) } }

    /** T189: shipped trail networks, as the planner sees them ([ShippedGraphWait]); a test may put a fake here. */
    @Volatile var shippedGraphs: ShippedGraphWait = io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs

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

    /** T276: the route's kind; null on foot while nothing is planned or a route is planning (no foot chip is lit). */
    var tripKind by mutableStateOf<TripKind?>(null)
        private set

    /** T276: why the route has [tripKind] (`GET /route` `ui.tripKindReason`). */
    var kindReason by mutableStateOf<KindReason?>(null)
        private set

    /** T276: the planned route's share in town on foot ([TripKinds.townShare]); null when not known. */
    @Volatile var townShare: Double? = null
        private set

    /** T276: the Walk or Hike chip tapped for this route (held through re-plans until Clear or a new route). */
    @Volatile private var tappedKind: TripKind? = null

    /** T276: the points of the session's route while it is everyday ([isEveryday]); null otherwise. */
    @Volatile private var everydayPoints: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>? = null

    /**
     * T276: whether [points] are the session route's and it is everyday: the gate [RouteWater], [RouteExposure] and
     * [RouteBestStart] read before they compute anything (any thread).
     */
    fun isEveryday(points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>?): Boolean =
        points != null && points === everydayPoints && !TripDetails.overrides(mode) // T278: an open Trip details row on an everyday Bike computes them

    /** T276: whether the session's route is everyday (navigation of it starts without the backcountry lines). */
    val everyday: Boolean get() = plan?.let { it.route.points === everydayPoints } ?: false

    /** T276: the chip lit now ([RouteChip.of]); null on foot while the kind is not known. */
    val chip: RouteChip? get() = RouteChip.of(mode, tripKind)

    /** T276: makes [c] the session's kind for plan [p]: its share and the gate. Any thread. */
    private fun kindFor(p: PlannedRoute, c: KindChoice) {
        townShare = c.townShare
        everydayPoints = if (c.kind == TripKind.EVERYDAY) p.route.points else null
    }

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

    /** T217: the card's three-dot overflow is open (Compose state; `POST /route/more?open=` sets it). */
    var moreOpen by mutableStateOf(false)

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
            restored = linkedMapOf("routeId" to r.plan.route.id, "savedAt" to r.savedAt, "points" to r.plan.route.points.size, "directions" to r.plan.instructions.size, "tripKind" to r.kind?.key, "tripKindReason" to r.kindReason?.key)
            // T276: the kind and whether a tap chose it; a file without them reads by the mode (on foot: Hike, unknown).
            val c = r.kind?.let { KindChoice(it, r.kindReason ?: KindReason.UNKNOWN) } ?: TripKinds.choose(r.plan.mode, emptyMap())
            tappedKind = if (c.reason == KindReason.TAP) c.kind else null
            kindFor(r.plan, c)
            ui { if (plan == null && phase == "idle") { phase = "route"; mode = r.plan.mode; plan = r.plan; tripKind = c.kind; kindReason = c.reason; message = null } }
            overlay.show(r.plan.route.points, null, null)
            Log.i(RoutePlanner.TAG, "restored the unsaved route ${r.plan.route.id} (${r.plan.route.points.size} points)")
        }
    }

    /** T108: writes [p] as the unsaved route ([SessionRoute]); null deletes it. */
    private fun persist(p: PlannedRoute?, kind: KindChoice? = null) {
        val s = fromEnd
        val e = toEnd
        runCatching {
            val f = sessionFile()
            if (p == null || s == null || e == null) { f.delete(); return@runCatching }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(SessionRoute.encode(p, s.lat to s.lon, e.lat to e.lon, System.currentTimeMillis(), s, e, kind?.kind, kind?.reason), Charsets.UTF_8)
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
        newRoute(null) // T276: a new route guesses its kind again
        everydayPoints = null
        ui { phase = "start"; plan = null; tripKind = TripKinds.byMode(mode); kindReason = tripKind?.let { KindReason.MODE }; message = "Long-press the end point"; fromOpen = false }
        overlay.show(null, start, null)
        RoutePlanner.ensure() // build or load the graph while the user picks the end
    }

    /**
     * T114, "Route to here": a route from the phone's position ([currentFix] at this moment) to ([lat], [lon]) in the
     * card's mode. With no fix it waits for the first one (see the class comment). Runs on the planning worker, or with
     * [wait] on the calling thread (the debug route; never the main thread), so the answer already holds the plan.
     */
    fun routeTo(lat: Double, lon: Double, wait: Boolean = false, travel: TravelMode? = null, newRoute: Boolean = true) {
        if (newRoute) newRoute(null) // T276: a new route guesses its kind again (Your location re-plans the same one)
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
        routeTo(dest.lat, dest.lon, wait, newRoute = false)
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
        everydayPoints = null
        val byMode = TripKinds.byMode(travel)
        // T276: no foot chip is lit while a route on foot is planning; the other modes' kind is their own.
        ui { phase = "planning"; mode = travel; plan = null; tripKind = byMode; kindReason = byMode?.let { KindReason.MODE }; message = "Planning..."; fromOpen = false }
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
            // T255: an end no offline map with a vector archive covers: no trail network to wait for there; a failure that
            // says why (the Offline maps link, Try Crow), whatever the graph of a pack elsewhere is doing.
            val cov = coverage(fromLat, fromLon, toLat, toLon)
            if (cov != MapCoverage.VECTOR) return failed(RouteResult.Failed(RouteFailure.EMPTY_GRAPH, routeSentence(cov), mapOf("reason" to cov.code)))
            val s = e.state
            val why = when {
                s == null -> "this map has no trail data (no vector archive)"
                s.status == "refused" -> GRAPH_TOO_LARGE // T149: the heap guard refused the build
                s.status == "downloading" -> GRAPH_DOWNLOADING // T189: the graph shipped with the map is still arriving
                s.status == "error" -> "the trail network could not be built: ${s.error}"
                else -> "the trail network is still being built (${s.json()["progress"] ?: s.status}); try again in a moment"
            }
            uiq.publish(PUBLISH_MS) { phase = "failed"; message = why }
            throw e
        }
        when (result) {
            is RouteResult.Found -> {
                failure = null
                val kind = TripKinds.of(result.plan, tappedKind) // T276: the mode's, the tapped chip's, or the guess
                kindFor(result.plan, kind)
                persist(result.plan, kind) // T108: outlives the process
                uiq.publish(PUBLISH_MS) { phase = "route"; plan = result.plan; tripKind = kind.kind; kindReason = kind.reason; message = null } // T119: before the caller answers
                overlay.show(result.plan.route.points, null, null)
                // T49: show the whole route. T114: once the finished card has been measured (it grows by the numbers, the
                // trails and the actions after planning, and the fit used to leave the start, the phone's position, under it).
                main.postDelayed({ if (plan === result.plan) fitCamera(result.plan) }, FIT_AFTER_MS)
            }
            is RouteResult.Failed -> {
                // T255: the reason code; an end with map tiles only (or nothing) says so rather than the far pack's "off the network".
                val cov = coverage(fromLat, fromLon, toLat, toLon)
                return failed(if (cov == MapCoverage.VECTOR) result.copy(details = result.details + ("reason" to cov.code)) else result.copy(message = routeSentence(cov), details = result.details + ("reason" to cov.code)))
            }
        }
        return result
    }

    /** The failure made the session's: no plan, the message, the ends on the map. */
    private fun failed(result: RouteResult.Failed): RouteResult.Failed {
        failure = result
        persist(null)
        // T276: a failed route on foot lights the tapped chip, else Hike (no share to guess from).
        val c = TripKinds.choose(mode, emptyMap(), tapped = tappedKind)
        everydayPoints = null
        townShare = null
        uiq.publish(PUBLISH_MS) { phase = "failed"; plan = null; tripKind = c.kind; kindReason = c.reason; message = result.message }
        overlay.show(null, start, end)
        return result
    }

    /**
     * T255: what the offline maps hold at the two ends ([MapCoverage.ofRoute]), each read at the camera's zoom (the view's
     * source as [io.github.jasondorjeshort.dystopo.app.online.OnlineTiles.viewSource] names it). Off the UI thread.
     */
    private fun coverage(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): MapCoverage = runCatching {
        val app = DystopoApp.instance
        val zoom = runCatching { app.map.onMain { app.map.map?.cameraPosition?.zoom } }.getOrNull() ?: 12.0
        MapCoverage.ofRoute(PlaceSearch.coverage(app, fromLat, fromLon, zoom), PlaceSearch.coverage(app, toLat, toLon, zoom))
    }.getOrDefault(MapCoverage.VECTOR)

    /** T255: the card offers the Offline maps link: the route failed for want of an offline map at an end. */
    val needsOfflineMap: Boolean get() = phase == "failed" && failure?.details?.get("reason").let { it == MapCoverage.TILES_ONLY.code || it == MapCoverage.NO_MAP.code }

    /**
     * Plans the current start and end again in [travel] mode: in the background, or with [wait] (T49, the
     * debug route) on the calling thread, so the answer already holds the new route.
     */
    /**
     * T276: a tap on a chip. Walk or Hike holds its kind for this route ([tappedKind]) until Clear or a new route; on a
     * route already planned on foot, Walk and Hike change only the kind (the same routing: nothing is planned again, the
     * card and the markers follow). Any other chip drops a held foot kind and plans in its mode ([changeMode]).
     */
    fun tapChip(c: RouteChip, wait: Boolean = false) {
        tappedKind = if (c.mode == TravelMode.HIKE) c.kind else null
        val p = plan
        if (c.mode == TravelMode.HIKE && mode == TravelMode.HIKE && p != null && phase == "route") {
            val kind = TripKinds.of(p, c.kind)
            kindFor(p, kind)
            persist(p, kind)
            uiq.publish(PUBLISH_MS) { tripKind = kind.kind; kindReason = kind.reason }
            overlay.refreshWater()
            return
        }
        if (phase != "route" && phase != "planning") {
            ui { tripKind = if (c.mode == TravelMode.HIKE) c.kind else TripKinds.byMode(c.mode); kindReason = if (c.mode == TravelMode.HIKE) KindReason.TAP else KindReason.MODE }
        }
        changeMode(c.mode, wait)
    }

    /** T276: a new route (not a re-plan of this one): [kind] is `POST /route`'s `kind=` (a tap), null to guess again. */
    fun newRoute(kind: TripKind?) {
        tappedKind = kind
    }

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
     * measured top, at least [CARD_MIN_DP]) and, T122, of everything at the top of the map ([io.github.jasondorjeshort.dystopo.app.FitPadding]:
     * the search box, the online indicator and the right-hand chips, where a fixed 96 dp left the route under the Routes
     * chip). T265: the short way round across the antimeridian ([io.github.jasondorjeshort.dystopo.app.CameraFit]); a Line
     * route across the Pacific was framed over another continent. Main thread.
     */
    private fun fitCamera(p: PlannedRoute, via: String = "plan") {
        val pts = p.route.points
        if (pts.size < 2) return
        val cardTop = maxOf(BottomPanels.routeCardTop.value, CARD_MIN_DP)
        val fit = io.github.jasondorjeshort.dystopo.app.CameraFit.fit(pts.map { it.lat to it.lon }, cardTop, FIT_MS) { lastFit = lastFit?.plus("paddingDropped" to true) } ?: return
        lastFit = fit + mapOf("routeId" to p.route.id, "via" to via)
    }

    /** T265: the card's Fit: the camera shows the whole route again (`GET /route` `lastFit.via` `fit`). Main thread. */
    fun fit(): Boolean {
        val p = plan ?: return false
        if (phase != "route") return false
        fitCamera(p, via = "fit")
        return true
    }

    fun clear() {
        waitToken.incrementAndGet() // T114: a route to here stops waiting
        waitingFor = -1
        setEnds(null, null)
        failure = null
        persist(null) // T108: Clear and Keep drop the saved route (a small file delete)
        tappedKind = null // T276: a tapped chip holds until Clear
        everydayPoints = null
        townShare = null
        ui { phase = "idle"; plan = null; tripKind = null; kindReason = null; message = null; directionsOpen = false; fromOpen = false; moreOpen = false }
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

    /** T219: a line on the card ([io.github.jasondorjeshort.dystopo.app.SaveFile]'s "Saved ..."). */
    fun say(text: String) = ui { message = text }

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

    /**
     * T217: `card` in `GET /route` and `POST /route`: the header and the line under it (`headline`, what the settings say now;
     * `drawnHeadline`, what the card last drew), the actions as drawn ([CardActions]), the overflow's state and each computed
     * line's `state` (`working`, `ready`, `none`) with its words ([LineState]). Never waits for an answer.
     */
    fun cardJson(): Map<String, Any?> {
        val p = plan
        val units = AppSettings.units
        // T276: an everyday route has none of the four lines, and nothing is computed for them.
        if (p != null && isEveryday(p.route.points)) return linkedMapOf(
            "headline" to PaceSettings.headline(p), "drawnHeadline" to BottomPanels.routeCardHeadline,
            "basis" to PaceSettings.secondLine(p),
            "actions" to CardActions.of(phase, canTryCrow()).map { it.json() }, "moreOpen" to moreOpen,
            "lines" to linkedMapOf("water" to EVERYDAY_JSON, "exposure" to EVERYDAY_JSON, "bestStart" to EVERYDAY_JSON, "pastEffort" to EVERYDAY_JSON),
        )
        return linkedMapOf(
            "headline" to p?.let { PaceSettings.headline(it) }, "drawnHeadline" to BottomPanels.routeCardHeadline,
            "basis" to p?.let { PaceSettings.secondLine(it) },
            "actions" to CardActions.of(phase, canTryCrow()).map { it.json() }, "moreOpen" to moreOpen,
            "lines" to linkedMapOf(
                "water" to if (p == null) LineState.NONE_JSON else RouteWater.lineJson(p.route.points, units),
                "exposure" to if (p == null) LineState.NONE_JSON else RouteExposure.lineJson(p.route.points, p.mode, units),
                "bestStart" to LineState.of(p?.let { RouteBestStart.start(it) }, ShadeText.BEST_START_WORKING) { it.line },
                "pastEffort" to LineState.of(p?.let { PaceSettings.pastEffortStarted(it.route.points, it.mode) }, null) { PaceSettings.pastEffortLine(it) },
            ),
        )
    }

    /** T114: no path between the two ends, and the card offers the straight line. */
    fun canTryCrow(): Boolean = phase == "failed" && failure != null && mode != TravelMode.CROW

    fun json(): Map<String, Any?> = linkedMapOf(
        "phase" to phase, "message" to message, "mode" to mode.label,
        // T276: the kind beside the mode (`everyday`, `backcountry`; null on foot while planning), why (`mode`, `share`,
        // `tap`, `unknown`), the share in town it was guessed from, the chip lit and the chips drawn, a held tap.
        "tripKind" to tripKind?.key, "tripKindReason" to kindReason?.key, "townShare" to townShare?.let { Math.round(it * 1000) / 1000.0 },
        "townShareThreshold" to TripKinds.threshold(mode), "chip" to chip?.key, "chips" to RouteChip.entries.map { it.key }, // T278: the mode's threshold
        "tapped" to tappedKind?.key,
        // T276: the computations an everyday route must not start (unchanged while one is shown).
        "computed" to linkedMapOf("water" to RouteWater.computed, "exposure" to RouteExposure.computed, "bestStart" to RouteBestStart.computed),
        "start" to start?.let { listOf(it.latitude, it.longitude) }, "end" to end?.let { listOf(it.latitude, it.longitude) },
        "longPresses" to longPresses, "overlayFeatures" to overlay.features, "overlayInstalled" to overlay.installedOnStyle,
        "summary" to plan?.let { PaceSettings.headline(it) }, "lastFile" to lastFile?.path,
        "cardHidden" to (io.github.jasondorjeshort.dystopo.app.nav.NavSession.active && phase != "idle"), // T65: hidden while navigating
        "via" to plan?.via?.map { it.first }, // T49: trail names
        "directions" to plan?.let { shownDirections(it).size }, "directionsOpen" to directionsOpen, // T102
        "lastFit" to lastFit, "layout" to BottomPanels.json(), // T49
        "restored" to restored, // T108: the unsaved route read back after the process restarted
        // T114: the two ends as the card names them, the From choices, the location state, the last route to here.
        "from" to fromEnd?.json(), "to" to toEnd?.json(), "fromOpen" to fromOpen, "fromChoices" to fromChoices(),
        "ends" to EndNames.json(fromEnd, toEnd), // T192: the From and To lines as drawn, the feature that named each
        "location" to locationState(),
        // T122: true only while the card waits for a fix with location on (`waitingFix`); `noLocation` waits for the
        // setting first, and read true here before.
        "waitingForFix" to (phase == "waitingFix" && waitingFor >= 0 && waitingFor == waitToken.get()),
        "routeTo" to lastRouteTo,
        "tripFit" to io.github.jasondorjeshort.dystopo.app.trips.Trips.lastFit, // T122: the trip editor's last fit, as lastFit
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

    /** T276: a card line of an everyday route in `GET /route` `card.lines`: not drawn, never computed. */
    val EVERYDAY_JSON: Map<String, Any?> = linkedMapOf("state" to LineState.NONE, "text" to null, "everyday" to true)

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

/**
 * T189 (ADR 0049's B): what the route planner asks before it builds a pack's trail network, and what `GET /route/status`
 * says of the graph it opened. Implemented by [io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs], read through
 * [RouteSession.shippedGraphs]. The planner's calls ([RoutePlanner], the round after T184): [awaitShipped] in its worker
 * before a build (state `downloading` meanwhile, the card's [GRAPH_DOWNLOADING]), and [source] in each pack's status.
 */
interface ShippedGraphWait {
    /**
     * Blocks the planner's worker while a graph shipped with pack [pack] downloads and progresses, or, when none is
     * downloading and a server is set, while the one its catalog offers for these archives ([vector], [dem]) downloads.
     * True when a graph file matching the archives is then beside [vector]; false when the planner should build (none
     * offered, a version or fingerprint mismatch, a failed download, or one stalled past [RouteSession.WAIT_MS]; the
     * reason is kept for [source]). [onProgress] gets the download's state while it waits.
     */
    fun awaitShipped(pack: String, vector: File, dem: File?, onProgress: (Map<String, Any?>) -> Unit = {}): Boolean

    /**
     * The status's `source` for [graphFile]: `shipped` (with the server's `builtAtMs` and where from) when it is the file
     * a download put there, `built` (with the reason nothing was shipped or taken) when the phone built it; null without
     * a file.
     */
    fun source(pack: String, graphFile: File): Map<String, Any?>?
}

/** T255 (ADR 0031: listed for the owner): the route card's line when an end has map tiles only ([MapCoverage.TILES_ONLY]). */
const val ROUTE_TILES_ONLY = "Routes need an offline map here; this area has map tiles only"

/** T255: the same where nothing on the phone covers an end ([MapCoverage.NO_MAP]). */
const val ROUTE_NO_MAP = "Routes need an offline map here"

/** T255: the card's line for a route an end of which no offline map with a vector archive covers (pure, tested). */
internal fun routeSentence(c: MapCoverage): String = if (c == MapCoverage.TILES_ONLY) ROUTE_TILES_ONLY else ROUTE_NO_MAP

/** T189: the route card's line while the trail network shipped with the map downloads (ADR 0031: listed for the owner). */
internal const val GRAPH_DOWNLOADING = "Downloading the trail network"

/** T189: the route status card's line when the trail network came with the map and nothing was built on the phone. */
internal const val GRAPH_SHIPPED = "Trail network downloaded with the map"
