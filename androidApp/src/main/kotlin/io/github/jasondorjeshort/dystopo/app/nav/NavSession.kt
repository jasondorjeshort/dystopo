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
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.tracking.GpsLocationSource
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.nav.AlertSnooze
import io.github.jasondorjeshort.dystopo.nav.FixFreshness
import io.github.jasondorjeshort.dystopo.nav.FixOrigin
import io.github.jasondorjeshort.dystopo.nav.NavEvent
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import io.github.jasondorjeshort.dystopo.nav.NavState
import io.github.jasondorjeshort.dystopo.nav.Navigator
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineSetting
import io.github.jasondorjeshort.dystopo.nav.TurnAlerts
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnClock
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.nav.TurnState
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WaterGaps
import io.github.jasondorjeshort.dystopo.routing.isSource
import io.github.jasondorjeshort.dystopo.app.routing.RouteWater
import io.github.jasondorjeshort.dystopo.app.routing.WaterText
import io.github.jasondorjeshort.dystopo.store.LocationFix
import java.util.concurrent.Executors

/**
 * Navigation mode (T61): follows a route or track with [Navigator] fed by every fix the app sees
 * (the device GPS while navigating, `POST /location`, GPX replays), moves the camera
 * ([CameraMode.FOLLOW] or [CameraMode.HEADING_UP]), draws the line and the way back ([NavMapLayer]),
 * keeps the screen on, beeps and buzzes on an off-route event ([NavAlerts]) and publishes the large-type
 * stats bar state ([ui], drawn by [NavOverlay]). T100: the camera modes are a view of the app's one
 * orientation state (controls/MapOrientation: the recentre button, this bar's camera actions and the
 * follow-me agree), and the heading is the app's one heading source (controls/Heading: a simulated one
 * while fixes are injected, the course while moving, the compass while facing and slow or still).
 * Debug routes in [io.github.jasondorjeshort.dystopo.app.NavRoutes]. T102: a route's turn directions
 * ([NavSources.Resolved.directions]) ride along; the card shows the next one at the default detail level with its
 * distance ([Directions.next], passed [Directions.passM] after the junction), and `/nav/status` lists them. T126: with
 * the spoken-directions setting on, [NavSpeech] says each one once as the card turns it to the alert colour.
 * T163: the turn-around clock ([TurnAround]) on the card under the end ("Turn back by 14:10", amber at the margin, red at
 * the time, then the arrival clock), read on every fix and every tick of the wall clock; one alert at the margin and one
 * at the time ([NavAlerts.turnAround]) and one spoken line ([NavSpeech.sayTurnAround]); `GET /nav` `turnAround`.
 * T180: progress from the navigator's [io.github.jasondorjeshort.dystopo.nav.PathTracker] (the retrace fix: an
 * out-and-back's way back runs forward and "arrived" fires at its end) and "Heading back" on the card when the direction
 * flips ([NavState.headingBack], which the clock reads as the way back). Without navigation, a recording's clock is
 * [WayBackClock]'s (`GET /nav/turnaround` reads it).
 * T187: the water along the line ([RouteWater], computed when navigation starts, never stored): the card names the next
 * water that is not a dry gully ("Water in 1.2 km: Stream, likely flowing"), and with the `waterSpoken` setting on the
 * last source before a stretch of more than 5 km without one is said once at its lead distance ([WaterText.dueSpoken],
 * [NavSpeech.sayWater]); `GET /nav` `water`.
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
        /** T65: "No GPS fix for 45 s" when the position is old ([FixFreshness.lost]); null when fresh. */
        val fixAge: String? = null,
        /** T65: "Alerts snoozed 9 min" or "Alerts quiet until back on route"; null when alerts sound. */
        val snoozed: String? = null,
        /** T102: the next turn direction ("Turn left onto Wonderland Trail"); null when the route has none left. */
        val turn: String? = null,
        /** T102: how far ahead it is, in the units preference. */
        val turnDistance: String? = null,
        /** T102: its turn, degrees right of straight on (the arrow), and its type code ([io.github.jasondorjeshort.dystopo.routing.Maneuver]). */
        val turnDeg: Int? = null,
        val turnType: String? = null,
        /** T102: within its lead distance ([Instruction.leadM]): the card shows it in the alert colour. */
        val turnSoon: Boolean = false,
        /** T163: the turn-around clock's line ("Turn back by 14:10", "Out by 18:52"); null before a fix and after arriving. */
        val turnBack: String? = null,
        /** T163: what it counts to and on what pace ("Sunset 19:12, flat horizon, 30 min margin, standard pace"). */
        val turnBackLabel: String? = null,
        /** T163: `normal`, `amber` or `red`. */
        val turnBackColour: String = TurnAround.NORMAL,
        /** T180: the direction flipped to the way back of a loop or out-and-back: "Heading back" on the card. */
        val headingBack: Boolean = false,
        /** T187: the next water ahead ("Water in 1.2 km: Stream, likely flowing"); null when none is left or the setting is off. */
        val water: String? = null,
    )

    /** T86: backed by [NavUiState], so touching [NavSession] from a background thread never creates Compose state. */
    var ui: Ui?
        get() = NavUiState.ui
        private set(v) { NavUiState.ui = v }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-nav").apply { isDaemon = true } }
    private val app get() = DystopoApp.instance

    @Volatile var navigator: Navigator? = null
        private set
    @Volatile private var source: Map<String, Any?> = emptyMap()
    /** T65: the followed route's planned total time ([NavSources.Resolved.planTimeS]); null for tracks and files. */
    @Volatile private var planTimeS: Double? = null
    @Volatile private var startedAt = 0L
    /** T65: off-route alert snooze (sound and buzz only; events are still listed). */
    private val snooze = AlertSnooze()
    /** T65: stale-fix rules for device fixes and the last known location. */
    @Volatile private var freshness = FixFreshness()
    @Volatile var sound = true
        private set
    @Volatile var vibrate = true
        private set
    @Volatile private var zoom: Double? = null
    /** T102: the route's directions (every priority) and those shown (the default level), along [navigator]'s path. */
    @Volatile private var directions: List<Instruction> = emptyList()
    @Volatile private var shownDirections: List<Instruction> = emptyList()
    @Volatile private var travel: TravelMode = TravelMode.HIKE
    /** T163: the turn-around clock along the line, its alerts given in this navigation, its last reading and spoken line. */
    @Volatile private var turn: TurnAround? = null
    private val turnAlerts = TurnAlerts()
    @Volatile private var turnClock: TurnClock? = null
    /** T163: the farthest progress on the line so far: well short of it, the walker has turned back. */
    @Volatile private var turnFarthestM = 0.0
    @Volatile private var turnSpoken: String? = null
    private val turnEvents = ArrayList<Map<String, Any?>>()
    /** T187: the water along the line (null until computed, or with the setting off), the items said and the spoken events. */
    @Volatile private var water: RouteWater.Result? = null
    private val waterSaid = HashSet<Int>()
    private val waterEvents = ArrayList<Map<String, Any?>>()

    val layer = NavMapLayer()
    private val alerts by lazy { NavAlerts(app) }

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

    val active: Boolean get() = navigator != null

    /** T180: the turn-around clock's tone and buzz for free tracking ([WayBackClock]), on navigation's alert path. */
    fun turnAroundAlert(kind: String) = alerts.turnAround(kind, sound, vibrate)

    /** T86: creates the Compose state now; call on the main thread from `Application.onCreate`. */
    fun prepare() = NavUiState.prepare()

    /** Starts navigating [path] (replacing any navigation); [src] says what it came from (for `/nav`). */
    fun start(
        path: NavPath,
        src: Map<String, Any?>,
        options: NavOptions = NavOptions(),
        camera: CameraMode = CameraMode.FOLLOW,
        sound: Boolean = true,
        vibrate: Boolean = true,
        zoom: Double? = 15.0,
        planTimeS: Double? = null,
        directions: List<Instruction> = emptyList(),
    ): Map<String, Any?> {
        stop()
        hookInjected()
        synchronized(this) {
            navigator = Navigator(path, options)
            source = src
            this.planTimeS = planTimeS
            this.directions = directions
            shownDirections = Directions.shown(directions)
            travel = options.mode
            turn = TurnAround(path, TurnPace.of(CostModel(options.mode))) // T163: T164's fitted pace reaches it through CostModel
            synchronized(turnAlerts) { turnAlerts.reset(); turnEvents.clear() }
            turnClock = null
            turnSpoken = null
            turnFarthestM = 0.0
            snooze.clear()
            // T91: the thresholds are settings now (navStaleS, navStartM), read at each start.
            val stale = io.github.jasondorjeshort.dystopo.app.AppSettings.navStaleS.toDouble()
            freshness = FixFreshness(io.github.jasondorjeshort.dystopo.nav.FixFreshnessOptions(maxAgeS = stale, maxStartDistanceM = io.github.jasondorjeshort.dystopo.app.AppSettings.navStartM.toDouble(), lostAfterS = stale))
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
        }
        NavSpeech.begin() // T126: a fresh spoken list; the engine is made on the first line
        startWater(path) // T187
        app.map.cameraMode = camera
        layer.show(path.points, null, null)
        // T65: the route editor sheet closes (its card would sit under the stats bar; the trip stays stored).
        io.github.jasondorjeshort.dystopo.app.trips.Trips.close()
        main.post {
            app.map.mapView?.let { layer.bind(it); it.keepScreenOn = true }
            // Again on the main thread, after any cleanup a previous stop() posted (a first start lost its line once).
            if (navigator != null) layer.show(path.points, null, null)
            app.map.activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startGps()
            ui = waitingUi(path)
            tick()
        }
        // Begin where the walker already is, if known.
        app.map.lastLocation?.let { l ->
            worker.execute { onFix(LocationFix(l.latitude, l.longitude, null, l.time, l.accuracy.toDouble(), l.provider ?: "last"), "last") }
        }
        Log.i(TAG, "navigating ${path.name}: ${path.points.size} points, ${path.totalM.toInt()} m, ${shownDirections.size} directions")
        return json()
    }

    /**
     * T187: computes the water along [path] (or takes the route card's: the same line, one cache) and, once it lands,
     * redraws the card on navigation's worker. Nothing with the `waterAlongRoute` setting off.
     */
    private fun startWater(path: NavPath) {
        water = null
        synchronized(waterSaid) { waterSaid.clear(); waterEvents.clear() }
        val nav = navigator ?: return
        val pts = path.points.map { io.github.jasondorjeshort.dystopo.model.RoutePoint(it.lat, it.lon, it.ele) }
        RouteWater.async(pts) { r ->
            if (navigator !== nav) return@async
            water = r
            worker.execute {
                val s = nav.state ?: return@execute
                val u = uiOf(nav, s)
                main.post { if (navigator === nav) ui = u }
                if (lastFixOrigin != null && lastFixOrigin != "last") speakWater(s)
            }
        }
    }

    /** T187: the card's next-water line at [s]. */
    private fun waterLine(s: NavState): String? {
        val r = water ?: return null
        if (s.arrived || !AppSettings.waterAlongRoute) return null
        val it = r.items.getOrNull(WaterText.nextOnCard(r.items, s.progress)) ?: return null
        return WaterText.navLine(it, (it.alongM - s.progress).coerceAtLeast(0.0), AppSettings.units)
    }

    /**
     * T187: says the last source before a long dry stretch once, within its lead distance, with `waterSpoken` on (a due line
     * with the setting off is not remembered, so turning it on before the water still says it). Worker thread.
     */
    private fun speakWater(s: NavState) {
        val r = water ?: return
        if (s.arrived || !AppSettings.waterAlongRoute || !AppSettings.waterSpoken) return
        val k = synchronized(waterSaid) { WaterText.dueSpoken(r.items, r.lengthM, s.progress, WaterText.leadM(travel), waterSaid).also { if (it >= 0) waterSaid += it } }
        if (k < 0) return
        val item = r.items[k]
        val ahead = (item.alongM - s.progress).coerceAtLeast(0.0)
        val gap = WaterGaps.after(r.items, k, r.lengthM)
        val text = WaterText.spoken(item, ahead, gap, WaterGaps.lastSource(r.items, k), AppSettings.units)
        val said = NavSpeech.sayWater(text)
        synchronized(waterSaid) {
            waterEvents += linkedMapOf(
                "n" to k + 1, "atMs" to System.currentTimeMillis(), "progressM" to r1(s.progress), "aheadM" to r1(ahead), "gapM" to r1(gap),
                "text" to text, "speech" to (said?.let { if (it.muted) "muted" else it.engine } ?: "off"),
            )
        }
        Log.i(TAG, "water #${k + 1}: $text")
    }

    /** T187: `GET /nav` `water`. */
    private fun waterJson(s: NavState?): Map<String, Any?> {
        val r = water
        val k = if (r != null && s != null) WaterText.nextOnCard(r.items, s.progress) else -1
        val next = r?.items?.getOrNull(k)
        return linkedMapOf(
            "enabled" to AppSettings.waterAlongRoute, "spokenSetting" to AppSettings.waterSpoken,
            "state" to when { !AppSettings.waterAlongRoute -> "off"; r == null -> "computing"; r.error != null -> "error"; else -> "ready" },
            "count" to r?.items?.size, "lengthM" to r?.lengthM?.let(::r1), "longestGapM" to r?.longest?.let { r1(it.second - it.first) },
            "leadM" to WaterText.leadM(travel), "longM" to WaterGaps.LONG_M,
            "next" to next?.let {
                linkedMapOf(
                    "n" to k + 1, "alongM" to r1(it.alongM), "aheadM" to s?.let { st -> r1((it.alongM - st.progress).coerceAtLeast(0.0)) },
                    "label" to it.label, "kind" to it.kind.key, "class" to it.waterClass?.key, "basis" to it.basis.key, "name" to it.name,
                )
            },
            "card" to ui?.water,
            // The sources with a long dry stretch after them: the spoken line's candidates.
            "beforeLongStretch" to r?.let { rr ->
                rr.items.indices.filter { i -> rr.items[i].isSource && WaterGaps.after(rr.items, i, rr.lengthM) > WaterGaps.LONG_M }
                    .map { i -> linkedMapOf("n" to i + 1, "alongM" to r1(rr.items[i].alongM), "gapM" to r1(WaterGaps.after(rr.items, i, rr.lengthM))) }
            },
            "spoken" to synchronized(waterSaid) { ArrayList(waterEvents) },
        )
    }

    /** "Go" on the route card: navigate the route on the map (background thread). */
    fun startRoute() = startFrom { NavSources.currentRoute() }

    /** "Go" in the tracks list (background thread). */
    fun startTrack(id: String) = startFrom { NavSources.track(id) }

    private fun startFrom(resolve: () -> NavSources.Resolved) {
        runCatching { resolve() }
            .onSuccess { r -> start(r.path, r.source, NavOptions(mode = r.mode ?: io.github.jasondorjeshort.dystopo.routing.TravelMode.HIKE), planTimeS = r.planTimeS, directions = r.directions) }
            .onFailure { Log.w(TAG, "cannot navigate", it) }
    }

    /** T65: "Go" on the trip card: navigate a kept route (background thread). */
    fun startTrip(id: String) = startFrom { NavSources.keptRoute(id) ?: throw NavSources.NotFound("no kept route $id") }

    /**
     * T65: snooze the off-route alert for [minutes], or (null) until back on the route; [off] ends a snooze.
     * Navigation still detects and lists every event.
     */
    fun snooze(minutes: Double?, off: Boolean = false): Map<String, Any?> {
        val now = System.currentTimeMillis()
        when {
            off -> snooze.clear()
            minutes == null -> snooze.snoozeExcursion()
            else -> snooze.snooze(now, minutes)
        }
        main.post { ui = ui?.copy(snoozed = snoozedText(now)) }
        return snooze.json(now)
    }

    private fun snoozedText(now: Long): String? = when {
        snooze.untilBackOnRoute -> "Alerts quiet until back on route"
        snooze.active(now) -> "Alerts snoozed " + ((snooze.untilMs!! - now + 59_999) / 60_000) + " min"
        else -> null
    }

    private fun fixAgeText(now: Long): String? {
        if (!freshness.lost(now, startedAt)) return null
        val age = freshness.ageS(now) ?: return "No GPS fix yet"
        return "No GPS fix for " + if (age < 120) "${age.toInt()} s" else "${(age / 60).toInt()} min"
    }

    fun stop(): Map<String, Any?> {
        val was = navigator != null
        navigator = null
        turn = null // T163
        turnClock = null
        NavSpeech.end() // T126: the text-to-speech engine is released
        app.map.cameraMode = CameraMode.FREE
        main.removeCallbacks(ticker)
        main.post {
            stopGps()
            if (navigator == null) {
                app.map.mapView?.keepScreenOn = false
                app.map.activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                layer.show(emptyList(), null, null)
                ui = null
            }
        }
        return mapOf("stopped" to was)
    }

    /** Camera mode from the debug route (T100: onto the app's one orientation state, controls/MapOrientation). */
    fun setCamera(mode: CameraMode) {
        app.map.cameraMode = mode
        main.post { ui = ui?.copy(camera = app.map.cameraMode) }
    }

    /**
     * A simulated heading (degrees true), used while fixes are injected; null clears it. [apply] re-runs the
     * last fix with it (`/nav/heading`); `POST /location?bearing=` sets it just before its own fix arrives.
     */
    fun simulateHeading(deg: Double?, apply: Boolean = true) {
        io.github.jasondorjeshort.dystopo.app.controls.Heading.simulate(deg) // T100: the app's one simulated heading
        if (apply && active) lastFix?.let { f -> runCatching { worker.submit { onFix(f, lastFixOrigin ?: "injected", repeat = true) }.get(5, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    /** The injected source (POST /location and replays) feeds navigation too; hooked once. */
    private fun hookInjected() {
        if (injectedHooked) return
        injectedHooked = true
        Tracking.of(app).injected.start { f -> if (navigator != null) worker.execute { onFix(f, "injected") } }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListener != null) return
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return
        val l = io.github.jasondorjeshort.dystopo.app.tracking.locationListener { loc ->
            // Injected fixes and replays win (the emulator reports its own position every second).
            if (app.map.injectedLocation || Tracking.of(app).replay.running) {
                ignored++
                return@locationListener
            }
            val fix = GpsLocationSource.toFix(loc)
            io.github.jasondorjeshort.dystopo.app.LastFix.record(fix) // T162's last fix, from navigation's own listener (T163)
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
        // T65: a stale device fix, or an old or far-away last known location, does not move navigation.
        if (!repeat) {
            val o = FixOrigin.of(origin)
            val far = if (o == FixOrigin.LAST_KNOWN) nav.path.project(fix.lat, fix.lon).distanceM else null
            val verdict = synchronized(this) { freshness.check(o, fix.time, System.currentTimeMillis(), far) }
            if (verdict != FixFreshness.Verdict.USE) {
                Log.i(TAG, "dropped a $origin fix: ${verdict.name.lowercase()} (age ${(System.currentTimeMillis() - fix.time) / 1000} s" + (far?.let { ", ${it.toInt()} m from the line" } ?: "") + ")")
                return
            }
        }
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
            // T65: a snoozed alert is listed but silent.
            val loud = (e.type == NavEvent.OFF_ROUTE || e.type == NavEvent.BACK_ON_ROUTE) && synchronized(this) { snooze.allow(e.type, System.currentTimeMillis()) }
            if (loud) when (e.type) {
                NavEvent.OFF_ROUTE -> alerts.offRoute(sound, vibrate)
                NavEvent.BACK_ON_ROUTE -> alerts.backOnRoute(sound)
            }
            Log.i(TAG, "event ${e.json()}" + if (!loud && (e.type == NavEvent.OFF_ROUTE || e.type == NavEvent.BACK_ON_ROUTE)) " (snoozed)" else "")
        }
        val rejoin = s.rejoin
        layer.setRejoin(
            if (rejoin != null && s.offRoute) NavPoint(fix.lat, fix.lon) else null,
            if (rejoin != null && s.offRoute) NavPoint(rejoin.lat, rejoin.lon) else null,
        )
        // T100: the camera follows every fix the puck shows (controls/MapOrientation); navigation sets the zoom once.
        if (nav.fixes == 1 && !repeat) app.map.follow(fix.lat, fix.lon, headingDeg, zoom)
        val u = uiOf(nav, s)
        main.post { if (navigator === nav) ui = u }
        turnClock?.let { turnAlert(it) } // T163
        // T126: not from the last known location at the start (it can be anywhere, the end of the route included, and a
        // direction said there would never be said again); the first live fix speaks.
        if (origin != "last") speak(s)
        if (origin != "last") speakWater(s) // T187
    }

    /** T126: says the card's next direction once it is within its lead distance (the card's alert colour), when the setting is on. */
    private fun speak(s: NavState) {
        if (s.arrived) return
        val k = nextTurn(s.progress)
        val ins = shownDirections.getOrNull(k) ?: return
        NavSpeech.onNext(k, ins, (ins.alongM - s.progress).coerceAtLeast(0.0), travel, AppSettings.units)
    }

    /** T100: the app's one heading (controls/Heading); navigation's own course between its fixes when that has none. */
    private fun chooseHeading(@Suppress("UNUSED_PARAMETER") origin: String) {
        val r = io.github.jasondorjeshort.dystopo.app.controls.Heading.current()
        headingDeg = r?.deg ?: courseDeg
        headingSource = r?.source ?: courseDeg?.let { "course" }
    }

    /** Twice a second: refreshes the heading readout (the compass turns between fixes; T100: the camera turns itself). */
    private val ticker = object : Runnable {
        override fun run() {
            val nav = navigator ?: return
            val f = lastFix
            if (f != null) {
                val before = headingDeg?.let { Math.round(it) } to headingSource
                chooseHeading("tick")
                if ((headingDeg?.let { Math.round(it) } to headingSource) != before) nav.state?.let { s -> ui = uiOf(nav, s) }
            }
            // T65: the fix age and the snooze line follow the clock between fixes.
            val now = System.currentTimeMillis()
            val age = fixAgeText(now)
            val sn = snoozedText(now)
            ui?.let { u -> if (u.fixAge != age || u.snoozed != sn) ui = u.copy(fixAge = age, snoozed = sn) }
            // T163: the turn-around clock follows the wall clock between fixes (a walker standing still still runs out of day).
            nav.state?.let { s ->
                val c = turnReading(s, now)
                ui?.let { u ->
                    val text = c?.text(::clock)
                    if (u.turnBack != text || u.turnBackColour != (c?.colour ?: TurnAround.NORMAL)) ui = u.copy(turnBack = text, turnBackLabel = c?.label(::clock), turnBackColour = c?.colour ?: TurnAround.NORMAL)
                }
                c?.let { turnAlert(it) }
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
        turn = shownDirections.firstOrNull()?.let { DirectionsText.of(it, travel) }, // T102: the first direction until a fix places the walker
    )

    /** T102: the next direction shown at [progressM] along the line, as an index into [shownDirections], -1 when none is left. */
    private fun nextTurn(progressM: Double): Int = Directions.next(shownDirections, progressM, passM = Directions.passM(travel))

    /**
     * T163: the turn-around clock at [s] and wall time [now] (null when not navigating, or after arriving): the deadline
     * setting at the line's end, the margin setting, the way back to the line when off it. Kept as [turnClock].
     */
    private fun turnReading(s: NavState, now: Long): TurnClock? {
        val t = turn ?: return null
        if (s.arrived) return null.also { turnClock = null }
        val setting = DeadlineSetting.parse(AppSettings.turnDeadline) ?: DeadlineSetting.SUNSET
        val end = t.path.points.last()
        val deadline = Deadline.resolve(setting, end.lat, end.lon, now, localMidnight(now))
        val off = if (s.offRoute) s.projection.distanceM else 0.0
        if (!s.offRoute) turnFarthestM = maxOf(turnFarthestM, s.progress)
        return runCatching { t.clock(s.progress, now, deadline, AppSettings.turnMarginMin, off, farthestM = turnFarthestM, headingBack = s.headingBack) }
            .onFailure { Log.w(TAG, "turn-around clock", it) }.getOrNull().also { turnClock = it }
    }

    /** T163: the alerts a reading has made due: a tone and a buzz on the alert path, the spoken line once (at the first). */
    private fun turnAlert(c: TurnClock) {
        val due = synchronized(turnAlerts) { turnAlerts.due(c) }
        for (k in due) {
            alerts.turnAround(k.key, sound, vibrate)
            val line = if (turnSpoken == null) c.spoken(::clock).also { turnSpoken = it } else null
            val said = line?.let { NavSpeech.sayTurnAround(it) }
            synchronized(turnAlerts) {
                turnEvents += linkedMapOf("kind" to k.key, "atMs" to c.nowMs, "text" to c.text(::clock), "colour" to c.colour, "spoken" to line, "speech" to (said?.let { if (it.muted) "muted" else it.engine } ?: line?.let { "off" }))
            }
            Log.i(TAG, "turn-around ${k.key}: ${c.text(::clock)}" + (line?.let { " / said: $it" } ?: ""))
        }
    }

    private fun localMidnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** T163: `GET /nav` `turnAround` and `GET /nav/turnaround` while navigating. */
    fun turnJson(): Map<String, Any?>? {
        val t = turn ?: return null
        val c = turnClock
        return linkedMapOf<String, Any?>(
            "source" to "navigation", "loop" to t.loop, "deadlineSetting" to AppSettings.turnDeadline, "marginMin" to AppSettings.turnMarginMin,
            "pace" to t.pace.label, "totalTimeS" to Math.round(t.totalS), "farthestM" to r1(turnFarthestM),
            "headingBack" to (navigator?.state?.headingBack ?: false), // T180
            "wayBackStretches" to navigator?.wayBackFrom?.map { (a, b) -> linkedMapOf("fromM" to r1(a), "toM" to r1(b)) },
            "text" to c?.text(::clock), "label" to c?.label(::clock), "clock" to c?.json(),
            "turnBack" to c?.turnBackMs?.let(::clock), "arrival" to c?.arrivalMs?.let(::clock),
            "deadline" to c?.deadline?.atMs?.let(::clock),
            "alerts" to synchronized(turnAlerts) { turnAlerts.json() + mapOf("events" to ArrayList(turnEvents)) },
            "spoken" to turnSpoken,
            "ui" to ui?.let { linkedMapOf("text" to it.turnBack, "label" to it.turnBackLabel, "colour" to it.turnBackColour) },
        )
    }

    /**
     * T163, free tracking: the clock along the track being recorded. T180: [WayBackClock]'s (the way back's start and its
     * source, the direction, the alerts, what the card shows). Null when nothing is recording.
     */
    fun freeTrackingTurnJson(): Map<String, Any?>? = WayBackClock.json()

    private fun uiOf(nav: Navigator, s: NavState): Ui {
        val u = AppSettings.units
        val rejoin = s.rejoin
        val mapBearing = runCatching { lastMapBearing() }.getOrDefault(0.0)
        val tc = turnReading(s, System.currentTimeMillis()) // T163
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
            fixAge = fixAgeText(System.currentTimeMillis()),
            snoozed = snoozedText(System.currentTimeMillis()),
            turnBack = tc?.text(::clock), turnBackLabel = tc?.label(::clock), turnBackColour = tc?.colour ?: TurnAround.NORMAL, // T163
            headingBack = s.headingBack, // T180
            water = waterLine(s), // T187
        ).let { ui ->
            // T102: the next turn direction and how far ahead it is.
            val k = if (s.arrived) -1 else nextTurn(s.progress)
            val ins = shownDirections.getOrNull(k) ?: return@let ui
            val ahead = (ins.alongM - s.progress).coerceAtLeast(0.0)
            ui.copy(
                turn = DirectionsText.of(ins, travel), turnDistance = UnitFormat.distance(ahead, u), turnDeg = ins.turnDeg,
                turnType = ins.type.code, turnSoon = ahead <= ins.leadM,
            )
        }
    }

    /** The map's bearing as last moved or seen at rest (T100: also a free-rotated map's). */
    private fun lastMapBearing(): Double = io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.bearing

    private fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    // ---- JSON for the debug routes ----

    fun json(): Map<String, Any?> {
        val nav = navigator ?: return linkedMapOf("active" to false, "alerts" to alerts.json())
        val p = nav.path
        val s = nav.state
        val planTime = planTimeS
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "active" to true,
            "source" to source,
            "startedAt" to startedAt,
            "path" to linkedMapOf(
                "name" to p.name, "points" to p.points.size, "totalM" to r1(p.totalM), "totalAscentM" to r1(p.totalAscentM), "totalDescentM" to r1(p.totalDescentM),
                "hasElevation" to p.hasElevation, "plannedTimeS" to Math.round(nav.plannedTimeS), "planned" to PlannedRoute.hm(nav.plannedTimeS),
                "waypoints" to p.waypoints.map { linkedMapOf("name" to it.name, "alongM" to r1(it.alongM)) },
            ),
            // T65: the route's planned time (its RouteSummary, as the route card and the trip card show it); null for tracks.
            "routePlanTimeS" to planTime?.let { Math.round(it) },
            // T65: Navigator.etaVsPlan, a pace ratio: (elapsed fix time + time still to go) / planned time. 1.0 at the
            // start; above 1 behind plan (slower, or off route), below 1 ahead. Against the route's plan, else the line's model time.
            "etaVsPlan" to nav.etaVsPlan(planTime ?: nav.plannedTimeS)?.let { Math.round(it * 1000) / 1000.0 },
            "pace" to s?.let { st -> linkedMapOf(
                "elapsedS" to nav.firstFixMs?.let { Math.round((st.timeMs - it) / 1000.0) }, "remainingS" to Math.round(st.end.timeS),
                "plannedS" to Math.round(planTime ?: nav.plannedTimeS),
            ) },
            "options" to nav.options.let { o ->
                linkedMapOf("offRouteM" to o.offRouteM, "offRouteSeconds" to o.offRouteSeconds, "backOnRouteM" to o.backOnRouteM, "arriveM" to o.arriveM, "mode" to o.mode.label, "sound" to sound, "vibrate" to vibrate)
            },
            "state" to s?.let { stateJson(it) },
            "heading" to linkedMapOf("deg" to headingDeg?.let(::r1), "source" to headingSource, "simulated" to io.github.jasondorjeshort.dystopo.app.controls.Heading.simulated, "courseDeg" to courseDeg?.let(::r1)),
            "compass" to io.github.jasondorjeshort.dystopo.app.controls.Heading.compassJson(), // T100: runs only while facing
            "camera" to linkedMapOf("mode" to app.map.cameraMode.name.lowercase(), "followMoves" to app.map.followMoves),
            "keepScreenOn" to keepScreenOn(),
            "layer" to linkedMapOf("installed" to layer.installedOnStyle, "updates" to layer.updates) +
                (runCatching { app.map.onMain { app.map.map?.style?.let { layer.order(it) } } }.getOrNull() ?: emptyMap()), // T65: order among the overlays
            "fixes" to synchronized(fixCounts) { HashMap(fixCounts) }, "gpsIgnored" to ignored, "gps" to (gpsListener != null),
            "alerts" to alerts.json(),
            "snooze" to synchronized(this) { snooze.json(now) }, // T65
            "fix" to synchronized(this) { freshness.json(now, startedAt) }, // T65: stale-fix handling
            "ui" to ui?.let { linkedMapOf("next" to it.next, "nextDistance" to it.nextDistance, "endDistance" to it.endDistance, "endTime" to it.endTime, "rejoin" to it.rejoin, "offRoute" to it.offRoute, "heading" to it.heading, "fixAge" to it.fixAge, "snoozed" to it.snoozed, "turn" to it.turn, "turnDistance" to it.turnDistance, "turnSoon" to it.turnSoon, "water" to it.water) }, // T187: water
            "directions" to linkedMapOf("count" to directions.size, "shown" to shownDirections.size, "level" to Directions.DEFAULT_LEVEL), // T102
            "speech" to NavSpeech.navJson(), // T137: the mute and the no-voice notice
            "turnAround" to turnJson(), // T163
            "water" to waterJson(s), // T187
            "layout" to io.github.jasondorjeshort.dystopo.app.TopChips.json() + mapOf("routeCardHidden" to io.github.jasondorjeshort.dystopo.app.routing.RouteSession.json()["cardHidden"], "tripEditorOpen" to (io.github.jasondorjeshort.dystopo.app.trips.Trips.editingId != null)), // T65
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
            "headingBack" to (s?.headingBack ?: false), // T180
            "tracker" to nav.tracker.json(), // T180: the retrace fix's jumps and full searches
            "counts" to ev.groupingBy { it.type.substringBefore(':') }.eachCount(),
            "events" to ev.map { it.json() },
            "alerts" to alerts.json(),
            "snooze" to synchronized(this) { snooze.json(System.currentTimeMillis()) }, // T65
        ) + directionsJson(s?.progress, s?.arrived == true)
    }

    /**
     * T102, `/nav/status`: `instructions`, the directions navigation shows (the default level, each with its `index`,
     * `type`, `priority`, `text`, `alongM`, `leadM`), and `nextInstruction` at the walker's progress (`index`, `type`,
     * `text`, `distanceM` and `distance` ahead, `withinLead`), null when none is left; `passed`, how many lie behind.
     */
    private fun directionsJson(progressM: Double?, arrived: Boolean): Map<String, Any?> {
        val list = shownDirections
        val k = if (progressM == null) 0 else if (arrived) -1 else nextTurn(progressM)
        val next = list.getOrNull(k)
        val ahead = next?.let { (it.alongM - (progressM ?: 0.0)).coerceAtLeast(0.0) } ?: 0.0
        return linkedMapOf(
            "instructions" to list.mapIndexed { i, ins ->
                linkedMapOf("index" to i, "type" to ins.type.code, "priority" to ins.priority, "text" to DirectionsText.of(ins, travel), "alongM" to r1(ins.alongM), "leadM" to r1(ins.leadM))
            },
            "nextInstruction" to next?.let {
                linkedMapOf(
                    "index" to k, "type" to it.type.code, "text" to DirectionsText.of(it, travel), "distanceM" to r1(ahead),
                    "distance" to UnitFormat.distance(ahead, AppSettings.units), "withinLead" to (ahead <= it.leadM),
                )
            },
            "passed" to if (k < 0) list.size else k,
        )
    }

    private fun stateJson(s: NavState): Map<String, Any?> = linkedMapOf(
        "fixes" to s.fixes, "timeMs" to s.timeMs, "lat" to s.lat, "lon" to s.lon,
        "progressM" to r1(s.progress), "distanceM" to r1(s.projection.distanceM), "offRoute" to s.offRoute, "offForS" to r1(s.offForS),
        "arrived" to s.arrived, "next" to s.next?.json(), "end" to s.end.json(), "rejoin" to s.rejoin?.json(),
        "headingBack" to s.headingBack, // T180
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
