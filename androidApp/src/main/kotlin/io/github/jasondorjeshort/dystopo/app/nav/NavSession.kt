package io.github.jasondorjeshort.dystopo.app.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.tracking.GpsLocationSource
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.nav.Compass
import io.github.jasondorjeshort.dystopo.nav.NavEvent
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import io.github.jasondorjeshort.dystopo.nav.NavState
import io.github.jasondorjeshort.dystopo.nav.Navigator
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.store.LocationFix
import java.util.concurrent.Executors

/**
 * Navigation mode (T61): follows a route or track with [Navigator] fed by every fix the app sees
 * (the device GPS while navigating, `POST /location`, GPX replays), moves the camera
 * ([CameraMode.FOLLOW] or [CameraMode.HEADING_UP]), draws the line and the way back ([NavMapLayer]),
 * keeps the screen on, beeps and buzzes on an off-route event ([NavAlerts]) and publishes the large-type
 * stats bar state ([ui], drawn by [NavOverlay]). The heading for heading-up comes from, in order: a
 * simulated one (`POST /location?bearing=` or `/nav/heading`, while fixes are injected), the compass
 * (rotation vector + WMM declination, [HeadingSensor]) for device fixes, the course between fixes.
 * Debug routes in [io.github.jasondorjeshort.dystopo.app.NavRoutes].
 */
object NavSession {
    const val TAG = "DystopoNav"

    /** What the stats bar shows; null when not navigating. Main thread. */
    data class Ui(
        val name: String,
        val next: String?,
        val nextDistance: String?,
        val nextTime: String?,
        val endDistance: String,
        val endTime: String,
        val endClock: String,
        val offRoute: Boolean,
        val offEdge: Boolean,
        val rejoin: String?,
        /** Arrow angle for the way back, relative to the screen's up (degrees). */
        val rejoinArrowDeg: Float?,
        val heading: String?,
        val camera: CameraMode,
        val arrived: Boolean,
        val waiting: Boolean,
    )

    var ui by mutableStateOf<Ui?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-nav").apply { isDaemon = true } }
    private val app get() = DystopoApp.instance

    @Volatile var navigator: Navigator? = null
        private set
    @Volatile private var source: Map<String, Any?> = emptyMap()
    @Volatile private var plan: PlannedRoute? = null
    @Volatile private var startedAt = 0L
    @Volatile var sound = true
        private set
    @Volatile var vibrate = true
        private set
    @Volatile private var zoom: Double? = null

    val layer = NavMapLayer()
    private val compass = Compass()
    private val sensor by lazy { HeadingSensor(app, compass) }
    private val alerts by lazy { NavAlerts(app) }

    @Volatile private var simulatedHeading: Double? = null
    @Volatile private var lastFix: LocationFix? = null
    @Volatile private var lastFixOrigin: String? = null
    @Volatile private var courseFrom: LocationFix? = null
    @Volatile private var courseDeg: Double? = null
    @Volatile var headingDeg: Double? = null
        private set
    @Volatile var headingSource: String? = null
        private set
    @Volatile private var alerted = 0
    private val fixCounts = HashMap<String, Int>()
    @Volatile private var ignored = 0
    @Volatile private var injectedHooked = false
    private var gpsListener: LocationListener? = null
    @Volatile private var lastCameraHeading: Double? = null
    @Volatile private var hookedMap: Any? = null

    val active: Boolean get() = navigator != null

    /** Starts navigating [path] (replacing any navigation); [src] says what it came from (for `/nav`). */
    fun start(
        path: NavPath,
        src: Map<String, Any?>,
        options: NavOptions = NavOptions(),
        camera: CameraMode = CameraMode.FOLLOW,
        sound: Boolean = true,
        vibrate: Boolean = true,
        zoom: Double? = 15.0,
        planned: PlannedRoute? = null,
    ): Map<String, Any?> {
        stop()
        hookInjected()
        synchronized(this) {
            navigator = Navigator(path, options)
            source = src
            plan = planned
            this.sound = sound
            this.vibrate = vibrate
            this.zoom = zoom
            startedAt = System.currentTimeMillis()
            alerted = 0
            ignored = 0
            fixCounts.clear()
            lastFix = null
            courseFrom = null
            courseDeg = null
            headingDeg = null
            headingSource = null
            lastCameraHeading = null
        }
        app.map.cameraMode = camera
        layer.show(path.points, null, null)
        main.post {
            app.map.mapView?.let { layer.bind(it); it.keepScreenOn = true }
            // Again on the main thread, after any cleanup a previous stop() posted (a first start lost its line once).
            if (navigator != null) layer.show(path.points, null, null)
            app.map.activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hookGestures()
            sensor.start()
            startGps()
            ui = waitingUi(path)
            tick()
        }
        // Begin where the walker already is, if known.
        app.map.lastLocation?.let { l ->
            worker.execute { onFix(LocationFix(l.latitude, l.longitude, null, l.time, l.accuracy.toDouble(), l.provider ?: "last"), "last") }
        }
        Log.i(TAG, "navigating ${path.name}: ${path.points.size} points, ${path.totalM.toInt()} m")
        return json()
    }

    /** "Go" on the route card: navigate the route on the map (background thread). */
    fun startRoute() = startFrom { NavSources.currentRoute() }

    /** "Go" in the tracks list (background thread). */
    fun startTrack(id: String) = startFrom { NavSources.track(id) }

    private fun startFrom(resolve: () -> NavSources.Resolved) {
        runCatching { resolve() }
            .onSuccess { r -> start(r.path, r.source, NavOptions(mode = r.plan?.mode ?: io.github.jasondorjeshort.dystopo.routing.TravelMode.HIKE), planned = r.plan) }
            .onFailure { Log.w(TAG, "cannot navigate", it) }
    }

    fun stop(): Map<String, Any?> {
        val was = navigator != null
        navigator = null
        app.map.cameraMode = CameraMode.FREE
        main.removeCallbacks(ticker)
        main.post {
            stopGps()
            if (navigator == null) {
                sensor.stop()
                app.map.mapView?.keepScreenOn = false
                app.map.activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                layer.show(emptyList(), null, null)
                ui = null
            }
        }
        return mapOf("stopped" to was)
    }

    /** Camera mode from the debug route or the stats bar buttons. */
    fun setCamera(mode: CameraMode) {
        app.map.cameraMode = mode
        lastCameraHeading = null
        lastFix?.let { f -> app.map.follow(f.lat, f.lon, headingDeg, null) }
        main.post { ui = ui?.copy(camera = mode) }
    }

    /**
     * A simulated heading (degrees true), used while fixes are injected; null clears it. [apply] re-runs the
     * last fix with it (`/nav/heading`); `POST /location?bearing=` sets it just before its own fix arrives.
     */
    fun simulateHeading(deg: Double?, apply: Boolean = true) {
        simulatedHeading = deg?.let { Compass.norm(it) }
        if (apply && active) lastFix?.let { f -> runCatching { worker.submit { onFix(f, lastFixOrigin ?: "injected", repeat = true) }.get(5, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    /** The injected source (POST /location and replays) feeds navigation too; hooked once. */
    private fun hookInjected() {
        if (injectedHooked) return
        injectedHooked = true
        Tracking.of(app).injected.start { f -> if (navigator != null) worker.execute { onFix(f, "injected") } }
    }

    /** A pan or rotate by hand stops following, as in every map app; the stats bar offers Recenter. */
    private fun hookGestures() {
        val m = app.map.map ?: return
        if (hookedMap === m) return
        hookedMap = m
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && active && app.map.cameraMode != CameraMode.FREE) {
                app.map.cameraMode = CameraMode.FREE
                ui = ui?.copy(camera = CameraMode.FREE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListener != null) return
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return
        val l = LocationListener { loc ->
            // Injected fixes and replays win (the emulator reports its own position every second).
            if (app.map.injectedLocation || Tracking.of(app).replay.running) {
                ignored++
                return@LocationListener
            }
            val fix = GpsLocationSource.toFix(loc)
            val course = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) loc.bearing.toDouble() else null
            worker.execute { onFix(fix, "gps", gpsCourse = course) }
        }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
            .onSuccess { gpsListener = l }
            .onFailure { Log.w(TAG, "GPS for navigation", it) }
    }

    private fun stopGps() {
        val l = gpsListener ?: return
        (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l)
        gpsListener = null
    }

    /** One fix (worker thread). */
    private fun onFix(fix: LocationFix, origin: String, gpsCourse: Double? = null, repeat: Boolean = false) {
        val nav = navigator ?: return
        if (!repeat) synchronized(fixCounts) { fixCounts[origin] = (fixCounts[origin] ?: 0) + 1 }
        compass.locate(fix.lat, fix.lon, if (fix.time > 0) fix.time else System.currentTimeMillis())
        // Course over ground: the GPS's own, else between fixes at least 5 m apart.
        if (!repeat) {
            val from = courseFrom
            if (gpsCourse != null) courseDeg = gpsCourse
            if (from == null) courseFrom = fix
            else if (TrackStats.distanceM(from.lat, from.lon, fix.lat, fix.lon) >= 5.0) {
                if (gpsCourse == null) courseDeg = TrackStats.bearingDeg(from.lat, from.lon, fix.lat, fix.lon)
                courseFrom = fix
            }
        }
        chooseHeading(origin)
        val s = synchronized(this) { if (repeat) nav.state ?: return else nav.onFix(fix.lat, fix.lon, fix.time, fix.accuracyM, headingDeg) }
        if (navigator !== nav) return
        lastFix = fix
        lastFixOrigin = origin
        // Alerts for new events, in order.
        val events = nav.events
        while (alerted < events.size) {
            val e = events[alerted++]
            when (e.type) {
                NavEvent.OFF_ROUTE -> alerts.offRoute(sound, vibrate)
                NavEvent.BACK_ON_ROUTE -> alerts.backOnRoute(sound)
            }
            Log.i(TAG, "event ${e.json()}")
        }
        val rejoin = s.rejoin
        layer.setRejoin(
            if (rejoin != null && s.offRoute) NavPoint(fix.lat, fix.lon) else null,
            if (rejoin != null && s.offRoute) NavPoint(rejoin.lat, rejoin.lon) else null,
        )
        lastCameraHeading = headingDeg
        app.map.follow(fix.lat, fix.lon, headingDeg, if (nav.fixes == 1) zoom else null)
        val u = uiOf(nav, s)
        main.post { if (navigator === nav) ui = u }
    }

    private fun chooseHeading(origin: String) {
        val sim = simulatedHeading
        val (h, src) = when {
            sim != null && origin != "gps" -> sim to "simulated"
            origin == "gps" && sensor.fresh -> compass.trueHeadingDeg to "compass"
            courseDeg != null -> courseDeg to "course"
            sensor.fresh -> compass.trueHeadingDeg to "compass"
            else -> null to null
        }
        headingDeg = h
        headingSource = src
    }

    /** Twice a second: turns a heading-up camera with the compass between fixes, refreshes the compass readout. */
    private val ticker = object : Runnable {
        override fun run() {
            val nav = navigator ?: return
            val f = lastFix
            if (f != null && headingSource == "compass") {
                val h = compass.trueHeadingDeg
                headingDeg = h
                val last = lastCameraHeading
                if (h != null && app.map.cameraMode == CameraMode.HEADING_UP && (last == null || kotlin.math.abs(Navigator.rel(h - last)) >= 3.0)) {
                    lastCameraHeading = h
                    app.map.follow(f.lat, f.lon, h)
                }
                nav.state?.let { s -> ui = uiOf(nav, s) }
            }
            main.postDelayed(this, 500)
        }
    }

    private fun tick() {
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 500)
    }

    private fun waitingUi(path: NavPath) = Ui(
        name = path.name, next = null, nextDistance = null, nextTime = null,
        endDistance = UnitFormat.distance(path.totalM, AppSettings.units), endTime = PlannedRoute.hm(navigator?.plannedTimeS ?: 0.0), endClock = "",
        offRoute = false, offEdge = false, rejoin = null, rejoinArrowDeg = null, heading = null, camera = app.map.cameraMode, arrived = false, waiting = true,
    )

    private fun uiOf(nav: Navigator, s: NavState): Ui {
        val u = AppSettings.units
        val rejoin = s.rejoin
        val mapBearing = runCatching { lastMapBearing() }.getOrDefault(0.0)
        return Ui(
            name = nav.path.name,
            next = s.next?.name,
            nextDistance = s.next?.let { UnitFormat.distance(it.distanceM, u) },
            nextTime = s.next?.let { PlannedRoute.hm(it.timeS) },
            endDistance = UnitFormat.distance(s.end.distanceM, u),
            endTime = PlannedRoute.hm(s.end.timeS),
            endClock = clock(System.currentTimeMillis() + (s.end.timeS * 1000).toLong()),
            offRoute = s.offRoute,
            offEdge = !s.offRoute && rejoin != null,
            rejoin = rejoin?.let { r -> r.text(UnitFormat.distance(r.distanceM, u)) },
            rejoinArrowDeg = rejoin?.let { (it.bearingDeg - mapBearing).toFloat() },
            heading = headingDeg?.let { "${Math.round(it) % 360}° ${io.github.jasondorjeshort.dystopo.nav.RejoinHint.compassWord(it)}" },
            camera = app.map.cameraMode,
            arrived = s.arrived,
            waiting = false,
        )
    }

    /** The map's bearing as last eased to (the camera follows the heading in heading-up), else north. */
    private fun lastMapBearing(): Double = if (app.map.cameraMode == CameraMode.HEADING_UP) (lastCameraHeading ?: 0.0) else 0.0

    private fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    // ---- JSON for the debug routes ----

    fun json(): Map<String, Any?> {
        val nav = navigator ?: return linkedMapOf("active" to false, "alerts" to alerts.json())
        val p = nav.path
        val s = nav.state
        val planTime = plan?.timeS
        return linkedMapOf(
            "active" to true,
            "source" to source,
            "startedAt" to startedAt,
            "path" to linkedMapOf(
                "name" to p.name, "points" to p.points.size, "totalM" to r1(p.totalM), "totalAscentM" to r1(p.totalAscentM),
                "hasElevation" to p.hasElevation, "plannedTimeS" to Math.round(nav.plannedTimeS), "planned" to PlannedRoute.hm(nav.plannedTimeS),
                "waypoints" to p.waypoints.map { linkedMapOf("name" to it.name, "alongM" to r1(it.alongM)) },
            ),
            // T45 routes: the router's own time (DEM ascent from the graph) beside navigation's (the route points' elevations).
            "routePlanTimeS" to planTime?.let { Math.round(it) },
            "etaVsPlan" to if (planTime != null && s != null && s.fixes > 0) Math.round(s.end.timeS / planTime * 1000) / 1000.0 else null,
            "options" to nav.options.let { o ->
                linkedMapOf("offRouteM" to o.offRouteM, "offRouteSeconds" to o.offRouteSeconds, "backOnRouteM" to o.backOnRouteM, "arriveM" to o.arriveM, "mode" to o.mode.label, "sound" to sound, "vibrate" to vibrate)
            },
            "state" to s?.let { stateJson(it) },
            "heading" to linkedMapOf("deg" to headingDeg?.let(::r1), "source" to headingSource, "simulated" to simulatedHeading, "courseDeg" to courseDeg?.let(::r1)),
            "compass" to sensor.json(),
            "camera" to linkedMapOf("mode" to app.map.cameraMode.name.lowercase(), "followMoves" to app.map.followMoves),
            "keepScreenOn" to keepScreenOn(),
            "layer" to linkedMapOf("installed" to layer.installedOnStyle, "updates" to layer.updates),
            "fixes" to synchronized(fixCounts) { HashMap(fixCounts) }, "gpsIgnored" to ignored, "gps" to (gpsListener != null),
            "alerts" to alerts.json(),
            "ui" to ui?.let { linkedMapOf("next" to it.next, "nextDistance" to it.nextDistance, "endDistance" to it.endDistance, "endTime" to it.endTime, "rejoin" to it.rejoin, "offRoute" to it.offRoute, "heading" to it.heading) },
        )
    }

    /** `GET /nav/status`: whether off route now, and every event so far. */
    fun status(): Map<String, Any?> {
        val nav = navigator ?: return linkedMapOf("active" to false)
        val ev = nav.events
        val s = nav.state
        return linkedMapOf(
            "active" to true,
            "fixes" to nav.fixes,
            "offRoute" to (s?.offRoute ?: false),
            "distanceM" to s?.projection?.distanceM?.let(::r1),
            "progressM" to s?.progress?.let(::r1),
            "arrived" to (s?.arrived ?: false),
            "counts" to ev.groupingBy { it.type.substringBefore(':') }.eachCount(),
            "events" to ev.map { it.json() },
            "alerts" to alerts.json(),
        )
    }

    private fun stateJson(s: NavState): Map<String, Any?> = linkedMapOf(
        "fixes" to s.fixes, "timeMs" to s.timeMs, "lat" to s.lat, "lon" to s.lon,
        "progressM" to r1(s.progress), "distanceM" to r1(s.projection.distanceM), "offRoute" to s.offRoute, "offForS" to r1(s.offForS),
        "arrived" to s.arrived, "next" to s.next?.json(), "end" to s.end.json(), "rejoin" to s.rejoin?.json(),
    )

    private fun keepScreenOn(): Map<String, Any?> = runCatching {
        app.map.onMain {
            val w = app.map.activity?.window
            linkedMapOf(
                "window" to ((w?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0),
                "mapView" to (app.map.mapView?.keepScreenOn ?: false),
            )
        }
    }.getOrElse { mapOf("error" to it.message) }
}
