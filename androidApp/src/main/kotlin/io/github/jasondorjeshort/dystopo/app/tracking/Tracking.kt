package io.github.jasondorjeshort.dystopo.app.tracking

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.store.ShownKind
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.StoredWaypoint
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import io.github.jasondorjeshort.dystopo.store.TrackStore
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.File
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.LastFix
import io.github.jasondorjeshort.dystopo.model.GapReason
import android.location.LocationManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Track recording for the app process (T31, T40): the durable [TrackStore] (SQLDelight, `tracks.db`
 * in the app's databases dir), the [TrackRecorder], the location sources that feed it, the map
 * [overlay], GPX replays and imports, and the state the Compose controls show (live stats, pause,
 * the waypoint sheet, the elevation profile).
 *
 * Every store and recorder call runs on one thread ([call]/[post]), so fixes from the GPS (its own
 * looper), the debug server's threads and a replay never interleave. [TrackingService] keeps the
 * process in the foreground while recording; this class runs the GPS source once the service is in the foreground.
 *
 * T159, recording on the phone:
 * - **One state** ([RecState], [state]) for the controls, the notification ([TrackNotification]) and `GET /tracks`,
 *   worked out on a tick every [TICK_MS] while a track records ([RecordingWatch]).
 * - **The watchdog**: no GPS fix for the profile's [ActivityProfile.watchdogMs] registers the GPS listener again
 *   (counted; when no fix of any source came in that time the recorder gets a [GapReason.RESTARTED] gap).
 * - **The network fallback** ([ActivityProfile.networkFallback]): after [RecordingWatch.WAITING_AFTER_MS] without a GPS
 *   fix the network provider (else the passive one) is listened to; its fixes are kept and flagged by the recorder, and
 *   the listener is removed at the next GPS fix. Injected fixes with source `network` (`POST /location?provider=network`)
 *   pass the same gate.
 * - **The restart**: the track stays marked as recording in the store (T158 keeps it open after a process death with
 *   a pending "stopped by the system" gap) and the fix source is kept in the `dystopo-tracking` preferences
 *   ([KEY_REC_MODE]); the service is `START_STICKY`, and a new process that finds a recording (the system restarting
 *   the service, or the user opening the app) starts the service again and goes on into the same track. When the
 *   service cannot start (from the background), the notification says the system stopped the recording and the app
 *   coming to the foreground ([Foreground]) starts it.
 * - **The last fix**: every fix that reaches the recorder goes to [LastFix] too (T162's open view), so a recording in the
 *   background, with the map's own listener off (T150), still moves the place the map opens at.
 * - T180, **the way back**: every point the recorder keeps goes to the free-tracking turn-around clock
 *   ([io.github.jasondorjeshort.dystopo.app.nav.WayBackClock]), which starts and stops with the recording; [wayBackHere] is
 *   the recording card's "The way back starts here" (per-device state, [WayBackAnchor]).
 *
 * Fix sources while recording ([Mode]): `gps`, `injected` (debug `POST /location` and replays), or
 * `all`. In `all`, injected fixes take precedence: GPS fixes are ignored while a replay runs and for
 * [INJECTED_PRECEDENCE_MS] after an injected fix, so a replay on an emulator (whose GPS reports a
 * default position) doesn't zigzag between the two.
 *
 * The segment gap (the recorder profile's [ActivityProfile.gap] time, T158) is a setting kept in the `dystopo-tracking`
 * preferences (`gapS`, 0 = never split); `POST /tracks/settings?gapS=` changes it.
 *
 * T203, **the terrain estimate** ([TerrainSource], one more [LocationSource]): every GPS and injected fix also goes to it,
 * and its estimates (source `terrain`) are recorded in every mode while recording and not paused: the recorder marks them
 * estimated and flagged and leaves them out of the distance until the next good fix. They are not the GPS: the silence,
 * the watchdog and the network fallback still count from the last GPS fix, and an estimate goes neither to the pressure
 * trend nor to the readout. The state line and the notification add the estimate's line ([RecordingStatus.estimate]).
 *
 * T262, **what the map shows**: every track and route is shown on this device's map until hidden ([setShown], the store's
 * per-device flag, never synced); [refreshOverlay] draws them all, the recording on top. Before, one transient field held
 * the one shown track, and a new recording, an import, a profile or a relaunch replaced it: the earlier track left the
 * map without being hidden or deleted. [shownId] remains for a reader of one track (the print sheet).
 */
class Tracking private constructor(private val app: DystopoApp) {
    enum class Mode(val gps: Boolean, val injected: Boolean) { ALL(true, true), GPS(true, false), INJECTED(false, true) }

    /** The elevation profile panel: [samples] along the track, plus its stats. */
    data class ProfileView(val trackId: String, val name: String, val samples: List<ProfileSample>, val stats: TrackStats, val gapText: String? = null)

    /** What the Compose controls show; replaced (on the main thread) after each change. */
    data class UiState(
        val recording: Boolean = false,
        val paused: Boolean = false,
        val activeName: String? = null,
        val distanceM: Double = 0.0,
        val points: Int = 0,
        val durationS: Double? = null,
        val tracks: List<TrackSummary> = emptyList(),
        /** Distance per stored track (from the store's stats cache) for the list. */
        val distances: Map<String, Double> = emptyMap(),
        /** T262: the tracks hidden on this device's map (every other track is shown). */
        val hidden: Set<String> = emptySet(),
        val message: String? = null,
        /** The waypoint the rename/delete sheet is open for. */
        val selectedWaypoint: StoredWaypoint? = null,
        val profile: ProfileView? = null,
        val gapS: Long = ActivityProfile.of(Activity.FOOT).gap.timeMs!! / 1000,
        /** T159: the recording's state, and the line the controls show under the live stats (null while recording normally). */
        val state: RecState = RecState.IDLE,
        val stateLine: String? = null,
        /** T172: the recording track and its activity (the recording panel's activity line). */
        val activeId: String? = null,
        val activeActivity: Activity? = null,
        /** T260: the recording's gaps bridged on the map, for the panel's statistics line ("1 gap, 10 min"); null with none. */
        val gapText: String? = null,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-tracks").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val dbFile: File = app.getDatabasePath(DB_NAME)
    val store: TrackStore = SqlTrackStore(AndroidSqliteDriver(TrackDb.Schema, app, DB_NAME), System::currentTimeMillis) // T44: wall clock under the sync HLC
    val recorder = TrackRecorder(store, clock = System::currentTimeMillis).apply { if (prefs.contains(KEY_GAP_S)) profile = profile.copy(gap = profile.gap.copy(timeMs = gapMsOf(prefs.getLong(KEY_GAP_S, 0)))) }
    val injected = InjectedLocationSource()
    val gps = GpsLocationSource(app)
    /** T159: the fallback while the GPS is silent: the network provider, else the passive one. */
    val fallback: GpsLocationSource = GpsLocationSource(app, LocationManager.NETWORK_PROVIDER, "network").takeIf { it.exists() }
        ?: GpsLocationSource(app, LocationManager.PASSIVE_PROVIDER, "passive")
    /** T159: the times and counters behind [state] (tracking thread). */
    val watch = RecordingWatch()
    val notification = TrackNotification(app)
    val overlay = TrackOverlay()
    val replay = Replay(this)
    /** Where GPX exports go and imports are listed from: `files/tracks/`. */
    val filesDir: File get() = File(app.filesDir, "tracks").apply { mkdirs() }

    @Volatile var mode: Mode = Mode.ALL
        private set
    /**
     * T262: the one track a reader of one track draws (the print sheet): the recording while it records and is shown, else
     * the newest shown track; null when every track is hidden. Worked out with the list ([refreshUi]).
     */
    @Volatile var shownId: String? = null
        private set
    @Volatile var selectedWaypointId: String? = null
        private set
    @Volatile var profileId: String? = null
        private set
    @Volatile var serviceState: String = "idle"
    @Volatile var gpsState: String = "off"
    /** T159: the recording's state (see the class comment). */
    @Volatile var state: RecState = RecState.IDLE
        private set
    /** T159: true while the service runs in the foreground (the GPS may run). */
    @Volatile var serviceForeground = false
        private set
    /** T159: true while a track records but the service is not running (refused or stopped by the system). */
    @Volatile var serviceDown = false
        private set
    /** T159: the service restarted by the system with no intent (`START_STICKY`), and `onTaskRemoved` calls, since the process started. */
    @Volatile var serviceRestarts = 0L
        private set
    @Volatile var tasksRemoved = 0L
        private set
    /** T159: fallback fixes refused (no recording, paused, the GPS not silent, or the profile's fallback off). */
    @Volatile var fallbackIgnored = 0L
        private set
    /** T159: when the system stopped the resumed track's recording (its last point, wall clock), or null. */
    @Volatile var stoppedBySystemAt: Long? = null
        private set
    private var ticker: ScheduledFuture<*>? = null
    /** GPS fixes dropped because injected fixes had precedence (see [Mode.ALL]). */
    @Volatile var gpsIgnored = 0L
        private set
    /**
     * Ids of tracks found still "recording" at startup (the process died mid-recording). T158, T159: the newest is
     * resumed (recording goes on into it after a "stopped by the system" gap), any older ones are closed.
     */
    val interrupted: List<String>

    /** T226: the state itself is [TrackingUiState]'s, made before any composition; see there. */
    var ui: UiState
        get() = TrackingUiState.ui
        private set(value) { TrackingUiState.ui = value }

    private var lastOverlayAt = 0L
    @Volatile private var overlayPending = false
    private var profileCache: Pair<Pair<String, Int>, List<ProfileSample>>? = null

    init {
        interrupted = call { recorder.recoverInterrupted() }
        val resumed = call { recorder.activeId }
        if (resumed != null) {
            mode = runCatching { Mode.valueOf(prefs.getString(KEY_REC_MODE, null) ?: "ALL") }.getOrDefault(Mode.ALL)
            stoppedBySystemAt = call { recorder.lastAcceptedTime }
            Log.i(TAG, "resuming track $resumed after a process death (a stopped-by-the-system gap is pending, mode ${mode.name.lowercase()})" +
                if (interrupted.size > 1) "; closed ${interrupted.size - 1} older track(s) left recording" else "")
        } else if (interrupted.isNotEmpty()) {
            Log.i(TAG, "closed ${interrupted.size} track(s) left recording by a process death")
        }
        injected.start { fix ->
            post { if (fix.source == "network" || fix.source == "passive") onFallbackFix(fix, device = false) else onFix(fix, fromGps = false) }
        }
        injected.start { TerrainSource.onFix(it) } // T203: replays and POST /location feed the terrain source too
        TerrainSource.start { fix -> post { onTerrainFix(fix) } } // T203
        // Taps arrive on the main thread; selectWaypoint waits on the tracking thread, so hop off main.
        overlay.onWaypointTap = { id -> Thread { selectWaypoint(id) }.start() }
        refreshUi()
        refreshOverlay(force = true)
        if (resumed != null) {
            post { watch.begin(SystemClock.elapsedRealtime()); startTicker(); puck(null) } // T251
            // T180: the way back of the resumed recording, from what it holds (its override is per device, kept).
            post { runCatching { io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.rebuild(resumed, recordedPoints()) } }
            // The system restarting the service goes on by itself from its onStartCommand. T229: this object is built on its own
            // thread from Application.onCreate, which can finish before the restarted service's onCreate runs; asked on the
            // main thread, the question comes after the service's create message, which the system queued with the process start.
            main.post { if (!TrackingService.creating && TrackingService.instance == null) startService() }
        }
        // T159: back in the foreground, a recording whose service is down starts it again (allowed now), and the overlay
        // and the controls catch up with what was recorded in the background.
        CoroutineScope(Dispatchers.Default).launch {
            Foreground.flow.collect { fg ->
                if (!fg) return@collect
                if (recorder.recording && serviceDown) startService()
                refreshUi()
                refreshOverlay(force = true)
            }
        }
    }

    /** Runs [block] on the tracking thread and waits for it. */
    fun <T> call(block: () -> T): T = worker.submit(Callable(block)).get(30, TimeUnit.SECONDS)

    fun post(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "tracking task failed", e)
            }
        }
    }

    /** Called with every GPS fix while the service runs. */
    fun onGpsFix(fix: LocationFix) {
        post { onFix(fix, fromGps = true) }
        TerrainSource.onFix(fix) // T203: after the recorder's post, so an estimate stamped after this fix is recorded after it
    }

    /** T203: the terrain source's estimates kept by the recorder (see the class comment); counted in [terrainRecorded]. */
    @Volatile var terrainRecorded = 0L
        private set

    private fun onTerrainFix(fix: LocationFix) {
        if (!recorder.recording || recorder.paused) return
        if (recorder.onFix(fix)) {
            terrainRecorded++
            wayBackPoint(fix) // T180: flagged
            refreshUiThrottled()
            refreshOverlay()
        }
    }

    /** T159: called with every fallback (network or passive) fix while its listener runs. */
    fun onFallbackDeviceFix(fix: LocationFix): Unit = post { onFallbackFix(fix, device = true) }

    /** T180: the recording's points in order (tracking thread). */
    private fun recordedPoints(): List<io.github.jasondorjeshort.dystopo.model.TrackPoint> = recorder.livePoints().flatten()

    /** T180: a fix the recorder kept goes to the way back; flagged as the recorder flags it (over the bound, or a network fix). */
    private fun wayBackPoint(fix: LocationFix) {
        val id = recorder.activeId ?: return
        val bound = recorder.profile.accuracyBoundM
        val flagged = fix.source == "network" || fix.source == "passive" || fix.source == TerrainSource.SOURCE || (bound != null && (fix.accuracyM ?: 0.0) > bound)
        runCatching { io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.onPoint(id, fix, flagged) { recordedPoints() } }
            .onFailure { Log.w(TAG, "way back", it) }
    }

    /** T180: "The way back starts here": the recording's way back starts at its last point. False when nothing records. */
    fun wayBackHere(): Boolean = call {
        val id = recorder.activeId ?: return@call false
        io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.here(id, recordedPoints())
    }

    /** T272: the waypoint [waypointId] as the way back's return point (`POST /tracks/wayback?waypoint=`). False when nothing records or no such waypoint. */
    fun wayBackToWaypoint(waypointId: String): Boolean = call {
        val id = recorder.activeId ?: return@call false
        io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.toWaypoint(id, waypointId, recordedPoints())
    }

    /** T180: the way back's start back to the rule (`POST /tracks/wayback?reset=1`). False when nothing records. */
    fun wayBackReset(): Boolean = call {
        val id = recorder.activeId ?: return@call false
        io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.reset(id, recordedPoints())
        true
    }

    private fun onFix(fix: LocationFix, fromGps: Boolean) {
        if (fromGps) {
            if (!mode.gps) return
            if (mode.injected && (replay.running ||
                    (injected.lastPublishedAt != 0L && SystemClock.elapsedRealtime() - injected.lastPublishedAt < INJECTED_PRECEDENCE_MS))
            ) {
                gpsIgnored++
                return
            }
        } else if (recorder.recording && !mode.injected) {
            return
        }
        runCatching { LastFix.record(fix) } // T159 for T162: the open view's last fix, also with the map's listener off in the background
        if (recorder.recording) {
            watch.primary(SystemClock.elapsedRealtime(), fix.accuracyM)
            if (fallback.listening) stopFallback("a GPS fix")
        }
        val before = state
        val kept = recorder.onFix(fix)
        puck(if (kept) fix else null) // T251
        if (kept) {
            wayBackPoint(fix) // T180
            io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.onFix(fix) // T178: the pressure trend's heights
            if (recorder.accepted % 10 == 1L || stateNow() != before) refreshUi() else refreshUiThrottled()
            refreshOverlay()
        } else if (recorder.recording && stateNow() != before) refreshUi()
    }

    /**
     * T159: a network or passive fix, from the fallback listener ([device]) or injected. Taken only while recording,
     * not paused, with the profile's fallback on and the GPS silent ([RecordingWatch.waiting]); the recorder flags it.
     */
    private fun onFallbackFix(fix: LocationFix, device: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!recorder.recording || recorder.paused || !recorder.profile.networkFallback || !watch.waiting(now) ||
            (device && !mode.gps) || (!device && !mode.injected)
        ) {
            fallbackIgnored++
            return
        }
        runCatching { LastFix.record(fix) }
        watch.fallback(now, fix.accuracyM)
        val kept = recorder.onFix(fix)
        puck(if (kept) fix else null) // T251
        if (kept) {
            wayBackPoint(fix) // T180
            refreshUi()
            refreshOverlay()
        }
    }

    /**
     * T251 (tracking thread): one position on the map. While a recording takes fixes (not paused, not auto-paused) the puck
     * follows the fixes the recorder keeps ([kept], drawn flagged as the recorder flagged it) and the map's own listener does
     * not move it ([io.github.jasondorjeshort.dystopo.app.MapController.onRecorder]); otherwise the map's listener serves.
     */
    private fun puck(kept: LocationFix?) {
        val drives = io.github.jasondorjeshort.dystopo.app.PuckRule.recorderDrives(recorder.recording, recorder.paused, recorder.autoPaused)
        runCatching { app.map.onRecorder(drives, kept, recorder.lastKept?.flags ?: 0) }
    }

    /** The state now (tracking thread). */
    fun stateNow(now: Long = SystemClock.elapsedRealtime()): RecState = RecordingWatch.stateOf(
        recorder.recording, recorder.paused, recorder.autoPaused, recorder.pendingGap, serviceDown,
        !recorder.paused && watch.waiting(now),
    ).also { state = it }

    /** What the notification and the controls say (tracking thread). */
    private fun statusNow(now: Long = SystemClock.elapsedRealtime()): RecordingStatus {
        val st = stateNow(now)
        val active = recorder.activeId?.let { store.summary(it) }
        return RecordingStatus(
            st, active?.name, recorder.liveStats()?.distanceM ?: 0.0, AppSettings.units,
            silentS = watch.silentMs(now) / 1000, lastAccuracyM = watch.lastPrimaryAccuracyM,
            fallbackAccuracyM = if (watch.fallbackRecent(now)) watch.lastFallbackAccuracyM else null,
            startedAt = active?.createdAt?.let(::clock), stoppedAt = stoppedBySystemAt?.let(::clock), serviceDown = serviceDown,
            estimate = TerrainSource.statusLine(AppSettings.units), // T203
            counts = recorder.counts(), // T251
        )
    }

    private fun clock(t: Long): String = android.text.format.DateFormat.getTimeFormat(app).format(java.util.Date(t))

    private fun startedAtWall(): Long? = recorder.activeId?.let { store.summary(it)?.createdAt }

    /** The notification for [TrackingService]'s `startForeground` (any thread but the tracking one). */
    fun foregroundNotification(): android.app.Notification = call {
        val s = statusNow()
        notification.posted(s)
        notification.build(s, recorder.activeId, startedAtWall())
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = worker.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                Log.w(TAG, "tracking tick failed", e)
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopTicker() {
        ticker?.cancel(false)
        ticker = null
    }

    /** T159: every [TICK_MS] while recording (tracking thread): the watchdog, the fallback, the state and the notification. */
    private fun tick() {
        if (!recorder.recording) {
            stopTicker()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val p = recorder.profile
        val active = !recorder.paused
        if (active && watch.watchdogDue(now, p.watchdogMs)) {
            val hole = watch.watchdogFired(now, p.watchdogMs)
            if (hole) recorder.interrupt(GapReason.RESTARTED)
            val again = mode.gps && serviceForeground
            if (again) startGps()
            Log.i(TAG, "watchdog: no GPS fix for ${watch.silentMs(now) / 1000} s; " +
                (if (again) "GPS listener registered again" else "no GPS listener in mode ${mode.name.lowercase()}") +
                " (${watch.watchdogRestarts} so far)" + if (hole) "; a restarted gap is pending" else "")
        }
        val wantFallback = active && p.networkFallback && watch.waiting(now) && mode.gps && serviceForeground
        if (wantFallback && !fallback.listening) startFallback()
        else if (!wantFallback && fallback.listening) stopFallback("not needed")
        val before = state
        val s = statusNow(now)
        notification.update(s, recorder.activeId, startedAtWall())
        if (s.state != before || s.state == RecState.WAITING_FOR_GPS) refreshUi()
    }

    private fun startGps() {
        val p = recorder.profile
        gps.intervalMs = p.intervalMs
        gps.distanceM = p.distanceFilterM.toFloat()
        gpsState = if (gps.start { onGpsFix(it) }) "on" else "unavailable (permission or provider)"
    }

    private fun startFallback() {
        fallback.intervalMs = recorder.profile.intervalMs
        val ok = fallback.start { onFallbackDeviceFix(it) }
        Log.i(TAG, "GPS silent for ${watch.silentMs(SystemClock.elapsedRealtime()) / 1000} s: " +
            if (ok) "listening to the ${fallback.name} provider" else "no ${fallback.name} provider to fall back on")
    }

    private fun stopFallback(why: String) {
        fallback.stop()
        Log.i(TAG, "${fallback.name} fallback stopped: $why")
    }

    /** T159: [TrackingService] is in the foreground: start the GPS (when the mode uses it) and the tick. */
    fun onServiceForeground(): Unit = post {
        serviceForeground = true
        serviceDown = false
        serviceState = "foreground"
        if (!recorder.recording) return@post
        if (mode.gps) startGps() else gpsState = "off (mode ${mode.name.lowercase()})"
        startTicker()
        refreshUi()
    }

    /** T159: the service could not go into the foreground ([e]); the recording waits for the app to come to the foreground. */
    fun onServiceFailed(e: Exception): Unit = post {
        serviceForeground = false
        serviceState = "failed: ${e.javaClass.simpleName}: ${e.message}"
        if (!recorder.recording) return@post
        serviceDown = true
        startTicker()
        notification.update(statusNow(), recorder.activeId, null, force = true)
        refreshUi()
    }

    /** T159: the service was destroyed; while a track still records, the system stopped it (the recording waits for the app). */
    fun onServiceDestroyed(): Unit = post {
        gps.stop()
        fallback.stop()
        gpsState = "off"
        serviceForeground = false
        if (!recorder.recording) {
            serviceState = "stopped"
            return@post
        }
        Log.w(TAG, "the recording service was destroyed while recording; the recording goes on when the app is opened")
        serviceState = "destroyed while recording"
        serviceDown = true
        stoppedBySystemAt = recorder.lastAcceptedTime
        recorder.interrupt(GapReason.STOPPED_BY_SYSTEM)
        notification.update(statusNow(), recorder.activeId, null, force = true)
        refreshUi()
    }

    /** T159: the system restarted the service with no intent (`START_STICKY` after the process died). */
    fun onServiceRestarted() {
        serviceRestarts++
        Log.i(TAG, "the recording service was restarted by the system ($serviceRestarts)")
    }

    /** T159: the user swiped the app away from the recent apps; the service and the recording go on. */
    fun onTaskRemoved(): Unit = post {
        tasksRemoved++
        Log.i(TAG, "task removed while ${if (recorder.recording) "recording" else "idle"}; the service goes on")
        if (recorder.recording) notification.update(statusNow(), recorder.activeId, startedAtWall(), force = true)
    }

    /** T159: the notification was swiped away (Android 14 allows it); it is posted again at the next tick. */
    fun notificationDismissed() {
        notification.dismissed()
    }

    /** T159: posts the notification again now (the notification permission was just granted). */
    fun repostNotification(): Unit = post {
        if (recorder.recording) notification.update(statusNow(), recorder.activeId, startedAtWall(), force = true)
    }

    /** Starts recording; T172: with [activity]'s profile from the settings rows (null: the remembered activity, [AppSettings.trackActivity]). */
    fun start(name: String?, mode: Mode, activity: Activity? = null): TrackSummary {
        val profile = profileFor(activity ?: AppSettings.trackActivity)
        val s = call {
            this.mode = mode
            val n = name?.takeIf { it.isNotBlank() } ?: "Track ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}"
            prefs.edit().putString(KEY_REC_MODE, mode.name).apply()
            stoppedBySystemAt = null
            watch.begin(SystemClock.elapsedRealtime())
            recorder.start(n, profile).also { io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.onStart(it.id) } // T180; T262: a new track is shown
                .also { puck(null) } // T251
        }
        startService()
        TerrainSource.update() // T203: recording keeps it watching in the background
        refreshUi()
        refreshOverlay(force = true)
        return s
    }

    fun stop(): TrackSummary? {
        val s = call {
            recorder.stop().also {
                prefs.edit().remove(KEY_REC_MODE).apply()
                stopTicker()
                fallback.stop()
                serviceDown = false
                stoppedBySystemAt = null
                state = RecState.IDLE
                notification.cancel()
                puck(null) // T251: the map's listener again
            }
        }
        io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.onStop() // T180
        TerrainSource.update() // T203
        stopService()
        refreshUi()
        refreshOverlay(force = true)
        runCatching { io.github.jasondorjeshort.dystopo.app.divergence.DivergenceStore.runSoon(app, "recordingStopped") } // T213: where this track left the trails
        return s
    }

    /** Holds recording (fixes are ignored until [resume]); false when not recording. */
    fun pause(): Boolean {
        val ok = call { if (recorder.recording) { recorder.pause(); puck(null); true } else false } // T251
        refreshUi()
        return ok
    }

    /** Continues a paused recording in a new segment; false when not recording. */
    fun resume(): Boolean {
        val ok = call { if (recorder.recording) { recorder.resume(); watch.resumed(SystemClock.elapsedRealtime()); puck(null); true } else false } // T251
        refreshUi()
        return ok
    }

    /** Sets and persists the segment gap in seconds (0 or less: never split on time). */
    fun setGapSeconds(seconds: Long) {
        val s = seconds.coerceAtLeast(0)
        prefs.edit().putLong(KEY_GAP_S, s).apply()
        call { recorder.profile = recorder.profile.copy(gap = recorder.profile.gap.copy(timeMs = gapMsOf(s))) }
        refreshUi()
    }

    val gapSeconds: Long get() = (recorder.profile.gap.timeMs ?: 0L) / 1000

    /** T172: [a]'s profile as the settings rows set it, with this device's segment gap (`POST /tracks/settings?gapS=`) when one is set. */
    private fun profileFor(a: Activity): ActivityProfile {
        val p = AppSettings.trackProfile(a)
        return if (prefs.contains(KEY_GAP_S)) p.copy(gap = p.gap.copy(timeMs = gapMsOf(prefs.getLong(KEY_GAP_S, 0)))) else p
    }

    /** T172: the settings rows changed [a]'s profile: a recording of that activity takes it from the next fix (the GPS re-registered for a new interval). */
    fun applyProfile(a: Activity): Unit = post {
        if (!recorder.recording || recorder.profile.activity != a) return@post
        val p = profileFor(a)
        val interval = p.intervalMs != recorder.profile.intervalMs
        recorder.profile = p
        recorder.activeId?.let { store.setActivity(it, a, p, System.currentTimeMillis()) }
        if (interval && serviceForeground && mode.gps) startGps()
    }

    /**
     * T172: changes a track's activity afterwards. The recording one takes [a]'s profile from the next fix; another keeps
     * the profile it was recorded with. False for an unknown track.
     */
    fun setActivity(id: String, a: Activity): Boolean {
        val now = System.currentTimeMillis()
        val ok = call {
            if (store.summary(id) == null) return@call false
            if (id == recorder.activeId) {
                val p = profileFor(a)
                val interval = p.intervalMs != recorder.profile.intervalMs
                recorder.profile = p
                store.setActivity(id, a, p, now)
                if (interval && serviceForeground && mode.gps) startGps()
            } else store.setActivity(id, a, store.track(id)?.profile, now)
            true
        }
        refreshUi()
        return ok
    }

    /** T262: shows track [id] on the map if it is hidden (a notification tap, a waypoint selected); false for an unknown track. */
    fun show(id: String): Boolean = setShown(id, true)

    /**
     * T262: shows or hides track [id] on this device's map (the list's eye, `POST /tracks/{id}/show?on=`); kept in the store,
     * never synced. False for an unknown track.
     */
    fun setShown(id: String, shown: Boolean): Boolean {
        val ok = call {
            if (store.summary(id) == null) return@call false
            store.setShown(ShownKind.TRACK, id, shown)
            true
        }
        if (ok) {
            refreshUi()
            refreshOverlay(force = true)
        }
        return ok
    }

    /** T262: the hidden tracks, as the store has them (tracking thread). */
    fun hiddenTracks(): Set<String> = store.hidden(ShownKind.TRACK)

    /** T262: renames track [id] (the list's Rename; a synced field). False for an unknown track or a blank name. */
    fun rename(id: String, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val ok = call {
            if (store.summary(id) == null) return@call false
            store.rename(id, n, System.currentTimeMillis())
            true
        }
        refreshUi()
        return ok
    }

    fun delete(id: String): Boolean {
        val deleted = call {
            if (recorder.activeId == id) { recorder.stop(); io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.onStop() } // T180
            store.delete(id)
        }
        if (profileId == id) profileId = null
        if (selectedWaypointId != null && call { store.waypoint(selectedWaypointId!!) } == null) selectedWaypointId = null
        refreshUi()
        refreshOverlay(force = true)
        return deleted
    }

    /** Stats of a track: live while it records, else the store's cached stats. Call on the tracking thread. */
    fun statsOf(s: TrackSummary): TrackStats? = if (recorder.activeId == s.id) recorder.liveStats() else store.stats(s.id)

    /** Opens the waypoint sheet for [id] (null closes it); false for an unknown waypoint. */
    fun selectWaypoint(id: String?): Boolean {
        if (id != null && call { store.waypoint(id) } == null) return false
        selectedWaypointId = id
        refreshUi()
        return true
    }

    /** Where [dropWaypoint] put the last waypoint and from what position (`GET /tracks` `lastDrop`). */
    @Volatile var lastDrop: Map<String, Any?>? = null
        private set

    /**
     * T91: drops a waypoint at any time (the owner's bug: before, only the Waypoint chip of a running
     * recording could). On the recording track while one records, else free-standing (synced like any
     * waypoint, drawn whatever track is shown). At ([lat], [lon]) when given (a long-pressed point), else at
     * "here": the recorder's last fix (injected or GPS while recording) or the map's last device fix,
     * whichever is newer. Opens the waypoint sheet on it so it can be named. Null when there is no position.
     */
    fun dropWaypoint(lat: Double? = null, lon: Double? = null, name: String? = null, via: String = "here"): StoredWaypoint? {
        val device = app.map.lastLocation
        // A given point's elevation from the DEM pack (off the tracking thread).
        val pressedEle = if (lat != null && lon != null) runCatching { io.github.jasondorjeshort.dystopo.app.trips.Trips.elevationAt(lat, lon) }.getOrNull() else null
        val w = call {
            val trackId = recorder.activeId
            val fix = recorder.lastFix
            val pos: Triple<Double, Double, Double?>? = when {
                lat != null && lon != null -> Triple(lat, lon, pressedEle)
                fix != null && (device == null || fix.time >= device.time) -> null // the recorder's own "here"
                device != null -> Triple(device.latitude, device.longitude, if (device.hasAltitude()) device.altitude else null)
                else -> null
            }
            if (pos == null && fix == null) return@call null
            val placed = recorder.waypoint(name?.takeIf { it.isNotBlank() }, trackId, pos?.first, pos?.second, pos?.third) ?: return@call null
            lastDrop = linkedMapOf(
                "id" to placed.id, "track" to trackId, "via" to via,
                "position" to when { lat != null -> "given"; pos == null -> "recorderFix"; else -> "deviceFix" },
            )
            StoredWaypoint(trackId, placed)
        } ?: return null
        selectedWaypointId = w.waypoint.id
        refreshUi()
        refreshOverlay(force = true)
        return w
    }

    fun renameWaypoint(id: String, name: String): Boolean {
        val ok = call { store.renameWaypoint(id, name.trim(), System.currentTimeMillis()) }
        refreshUi()
        refreshOverlay(force = true)
        return ok
    }

    fun deleteWaypoint(id: String): Boolean {
        val ok = call { store.deleteWaypoint(id, System.currentTimeMillis()) }
        if (selectedWaypointId == id) selectedWaypointId = null
        refreshUi()
        refreshOverlay(force = true)
        return ok
    }

    /** Opens the elevation profile for [id] (and shows the track; T262: on the map, if it was hidden); null closes it. False for an unknown track. */
    fun showProfile(id: String?): Boolean {
        if (id != null) {
            if (call { store.summary(id)?.also { store.setShown(ShownKind.TRACK, id, true) } } == null) return false
        }
        profileId = id
        refreshUi()
        refreshOverlay(force = true)
        return true
    }

    /** Profile samples of a track ([PROFILE_SAMPLES] by default). Call on the tracking thread. */
    fun profileOf(id: String, n: Int = PROFILE_SAMPLES): List<ProfileSample>? {
        val track = trackFor(id) ?: return null
        return TrackStats.profile(track, n)
    }

    private fun trackFor(id: String): Track? =
        if (id == recorder.activeId) Track(id, "", recorder.livePoints().map { TrackSegment(it) }, profile = recorder.profile) else store.track(id) // T260: its interval

    /** Imports GPX text into the store; T262: every new track is shown, beside the others. Throws IllegalArgumentException for a bad file. */
    fun importGpx(text: String, fallbackName: String, file: String = fallbackName): TrackImport.Result {
        val r = call { TrackImport.import(store, text, System.currentTimeMillis(), fallbackName) }
        val msg = io.github.jasondorjeshort.dystopo.app.routing.ImportText.tracks(r.imported.size, r.skippedTracks.size, r.waypoints, file) // T219
        refreshUi(msg)
        refreshOverlay(force = true)
        return r
    }

    /** GPX files in [filesDir], newest first. */
    fun importableFiles(): List<File> = filesDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
        .orEmpty().sortedByDescending { it.lastModified() }

    /** Imports `files/tracks/<name>` (a plain file name, no directories). */
    fun importFile(name: String): TrackImport.Result {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "file must be a name in ${filesDir.path}" }
        val f = File(filesDir, name)
        require(f.isFile) { "no file $name in ${filesDir.path}" }
        return importGpx(f.readText(Charsets.UTF_8), f.nameWithoutExtension, f.name)
    }

    /** Writes the track as GPX into `files/tracks/`; returns the file. */
    fun export(id: String): File? = call {
        val s = store.summary(id) ?: return@call null
        val gpx = TrackExport.gpx(store, id) ?: return@call null
        File(filesDir, TrackExport.fileName(s)).apply { writeText(gpx, Charsets.UTF_8) }
    }

    private fun startService() {
        try {
            app.startForegroundService(Intent(app, TrackingService::class.java))
            serviceState = "starting"
        } catch (e: Exception) {
            // Android 12+ refuses to start a foreground service from the background: the notification asks for a tap.
            Log.w(TAG, "foreground service not started", e)
            onServiceFailed(e)
        }
    }

    private fun stopService() {
        app.stopService(Intent(app, TrackingService::class.java))
        serviceState = "stopped"
        serviceForeground = false
    }

    private var lastUiAt = 0L

    /** At most once a second in the foreground; T159: every [TICK_MS] in the background, where only the notification reads it. */
    private fun refreshUiThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiAt >= if (Foreground.started) 1000 else TICK_MS) refreshUi()
    }

    /** Recomputes [ui] on the tracking thread and publishes it on the main thread. */
    fun refreshUi(message: String? = null): Unit = post {
        lastUiAt = SystemClock.elapsedRealtime()
        val live = recorder.liveStats()
        val active = recorder.activeId?.let { store.summary(it) }
        val tracks = store.list().reversed()
        val hidden = store.hidden(ShownKind.TRACK) // T262
        shownId = recorder.activeId?.takeIf { it !in hidden } ?: tracks.firstOrNull { it.id !in hidden }?.id
        val profile = profileId?.let { id ->
            val s = tracks.firstOrNull { it.id == id } ?: return@let null
            val key = id to (if (id == recorder.activeId) recorder.accepted.toInt() else s.points)
            val samples = profileCache?.takeIf { it.first == key }?.second ?: (profileOf(id) ?: emptyList()).also { profileCache = key to it }
            ProfileView(id, s.name, samples, statsOf(s) ?: return@let null, trackFor(id)?.let { io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gapText(io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gaps(it)) }) // T260
        }
        val state = UiState(
            recording = recorder.recording,
            paused = recorder.paused,
            activeName = active?.name,
            distanceM = live?.distanceM ?: 0.0,
            points = live?.points ?: 0,
            durationS = live?.durationS,
            tracks = tracks,
            distances = tracks.filter { it.id != recorder.activeId }.associate { it.id to (store.stats(it.id)?.distanceM ?: 0.0) },
            hidden = hidden,
            message = message ?: ui.message,
            selectedWaypoint = selectedWaypointId?.let { store.waypoint(it) },
            profile = profile,
            gapS = gapSeconds,
            activeId = recorder.activeId, // T172
            activeActivity = active?.activity,
            gapText = recorder.activeId?.let { trackFor(it) }?.let { io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gapText(io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gaps(it)) }, // T260
        )
        val status = if (recorder.recording) statusNow() else null
        val withState = state.copy(state = status?.state ?: RecState.IDLE, stateLine = status?.controlsLine)
        main.post { ui = withState }
        if (status != null) notification.update(status, recorder.activeId, active?.createdAt)
    }

    /** T262: stored tracks read for the overlay, by id, with the (updated, points) they were read at. Tracking thread. */
    private val overlayTracks = HashMap<String, Pair<Pair<Long, Int>, Track>>()
    private val overlayRoutes = HashMap<String, Pair<Long, List<Pair<Double, Double>>>>()

    /**
     * Redraws the overlay (T262: every shown track and route, the recording on top; the waypoints of the shown tracks and
     * the free-standing ones), at most twice a second unless [force]d. Runs the store reads on the tracking thread; a stored
     * track is read again only when it changed, and the map gets the stored ones again only when the set changed.
     */
    fun refreshOverlay(force: Boolean = false): Unit = post {
        // T159: nothing to draw on a stopped map; the Foreground collector redraws when the app comes back.
        if (!Foreground.started) return@post
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOverlayAt < OVERLAY_INTERVAL_MS) {
            if (!overlayPending) {
                overlayPending = true
                main.postDelayed({
                    overlayPending = false
                    refreshOverlay(force = true)
                }, OVERLAY_INTERVAL_MS)
            }
            return@post
        }
        lastOverlayAt = now
        val activeId = recorder.activeId
        val hidden = store.hidden(ShownKind.TRACK)
        val shown = store.list().filter { it.id !in hidden }
        val stored = shown.filter { it.id != activeId }.mapNotNull { s ->
            val key = s.updatedAt to s.points
            overlayTracks[s.id]?.takeIf { it.first == key }?.second ?: store.track(s.id)?.also { overlayTracks[s.id] = key to it }
        }
        overlayTracks.keys.retainAll(stored.map { it.id }.toSet())
        val live = activeId?.takeIf { id -> shown.any { it.id == id } }?.let { trackFor(it) }
        // T91: free-standing waypoints (dropped with no recording, or synced) show whatever is shown.
        val waypoints = shown.flatMap { store.waypoints(it.id) } + store.waypoints(null)
        val routes = shownRoutes()
        val key = listOf(stored.map { it.id to overlayTracks[it.id]?.first }, routes.map { it.first to overlayRoutes[it.first]?.first }, waypoints.map { it.id to it.name to it.lat to it.lon })
        overlay.show(stored, routes, waypoints, live, key)
    }

    /** T262: the shown routes' lines, but the one open in the trip editor, which draws it ([io.github.jasondorjeshort.dystopo.app.trips.TripOverlay]). */
    private fun shownRoutes(): List<Pair<String, List<Pair<Double, Double>>>> {
        val sync = store as? io.github.jasondorjeshort.dystopo.sync.SyncStore ?: return emptyList()
        val hidden = store.hidden(ShownKind.ROUTE)
        val editing = runCatching { io.github.jasondorjeshort.dystopo.app.trips.Trips.editingId }.getOrNull()
        val items = sync.routeList().filter { it.id !in hidden && it.id != editing }
        val out = items.mapNotNull { r ->
            overlayRoutes[r.id]?.takeIf { it.first == r.updatedAt }?.second?.let { r.id to it }
                ?: sync.routePlan(r.id)?.path?.points?.map { it.lat to it.lon }?.also { overlayRoutes[r.id] = r.updatedAt to it }?.let { r.id to it }
        }
        overlayRoutes.keys.retainAll(out.map { it.first }.toSet())
        return out
    }

    companion object {
        const val TAG = "DystopoTracks"
        const val DB_NAME = "tracks.db"
        const val INJECTED_PRECEDENCE_MS = 30_000L
        const val OVERLAY_INTERVAL_MS = 500L
        const val PROFILE_SAMPLES = 200
        private const val PREFS = "dystopo-tracking"
        private const val KEY_GAP_S = "gapS"
        /** T159: the period of the recording's tick (the watchdog, the fallback, the state, the notification). */
        const val TICK_MS = 5_000L
        /** T159: the recording's fix source ([Mode]), written at start and removed at stop: the persisted flag beside the store's. */
        private const val KEY_REC_MODE = "recordingMode"

        private fun gapMsOf(seconds: Long): Long? = if (seconds <= 0) null else seconds * 1000

        /** T229: how long a caller off the main thread (and a service callback, [awaitReady]) waits for the tracker, as [call] does. */
        const val WAIT_MS = 30_000L

        /** T229: the debug builds' file holding the next launch's construction ([delayNextStart]). */
        private const val DELAY_FILE = ".tracking-delay"

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** T229: the one [Tracking] of the process; see [start]. */
        private val ready = TrackerReady<Tracking>({ Looper.myLooper() == Looper.getMainLooper() }) { t ->
            mainHandler.post { TrackingUiState.tracking = t }
        }

        /** T229: when the tracker was ready (ms after the process started), how long its construction took, and the debug delay it waited. */
        @Volatile private var readyAtMs: Long? = null
        @Volatile private var buildMs: Long? = null
        @Volatile private var delayedMs = 0L
        @Volatile private var builder: Thread? = null

        /**
         * T226: makes, on the main thread from `Application.onCreate`, the Compose state that the first thread to reach this
         * class would otherwise make, without opening the store: [TrackingUiState]; [TerrainSource]'s, which this class's
         * constructor starts, and through it the pressure trend's (`TerrainSource.update`, `Barometer.want`,
         * `PressureWatch.onListenerStart`); [Trips]' and [TripCorridors]', whose threads reach this class at launch
         * (`TripCorridors.reconcile`, `Trips.store`); and this package's objects, which `TrackRoutes` touches on a
         * debug thread. T229: since then nobody but [start]'s thread builds the tracker; the corridors' thread waits for it.
         */
        fun prepare() {
            TrackingUiState.prepare()
            TerrainSource.display
            io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.card
            io.github.jasondorjeshort.dystopo.app.trips.Trips.listOpen
            io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.queued
            ActivityPicker.target
            BatteryExemption.cardOpen
            TrackBar.open
            io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.open // T262
            LongPressMenu.sun
        }

        /**
         * T229: builds the tracker on a thread of its own (`dystopo-tracking-start`), from `Application.onCreate` after
         * [prepare] and the settings' load, so no other thread builds it and the main thread never waits for it: the
         * constructor opens the track database (migrating it after an update), scans the sync log and makes worker round
         * trips. Once built it is [of]'s answer and, posted to the main thread, [TrackingUiState.tracking]. In debug builds
         * a delay set by `POST /debug/tracking/delay` ([delayNextStart]) holds the construction first, once.
         */
        fun start(app: DystopoApp, debug: Boolean) {
            val t0 = SystemClock.elapsedRealtime()
            Thread({
                if (debug) runCatching { takeDelay(app) }.getOrDefault(0L).takeIf { it > 0 }?.let { delayedMs = it; Thread.sleep(it) }
                try {
                    build(app)
                    Log.i(TAG, "tracker ready ${SystemClock.elapsedRealtime() - t0} ms after its start (built in $buildMs ms)")
                } catch (e: Throwable) {
                    // The next caller off the main thread builds it again, as every caller did before T229.
                    Log.e(TAG, "the tracker could not be built", e)
                    ready.failed(e)
                }
            }, "dystopo-tracking-start").apply { isDaemon = true }.start()
        }

        private fun build(app: DystopoApp): Tracking = synchronized(this) {
            ready.value ?: run {
                builder = Thread.currentThread()
                val t0 = SystemClock.elapsedRealtime()
                try {
                    Tracking(app).also {
                        buildMs = SystemClock.elapsedRealtime() - t0
                        readyAtMs = SystemClock.elapsedRealtime() - app.startedAt
                        ready.set(it)
                    }
                } finally {
                    builder = null
                }
            }
        }

        /**
         * The tracker. T229: off the main thread it waits for [start]'s thread, at most [WAIT_MS] (after a failed start it
         * builds it itself); on the main thread it answers once the tracker is ready, and before that it is a programming
         * error that names its caller (never a silent null): the main thread reads [TrackingUiState.tracking], or calls this
         * from a thread it starts.
         */
        fun of(context: Context): Tracking {
            ready.value?.let { return it }
            check(Thread.currentThread() !== builder) { "Tracking.of inside the tracker's own construction, from ${callerOf()}" }
            return ready.await(WAIT_MS, ::callerOf) ?: build(context.applicationContext as DystopoApp)
        }

        /**
         * T229: the one main-thread wait for the tracker: [TrackingService]'s callbacks, which the system calls on the main
         * thread, also in a process it restarted for a recording with no screen, before the tracker is built.
         */
        fun awaitReady(context: Context): Tracking =
            ready.value ?: ready.await(WAIT_MS, ::callerOf, allowMain = true) ?: build(context.applicationContext as DystopoApp)

        /** T229: the tracker if it is ready, else null; never waits (`GET /tracks`, the controls). */
        val readyOrNull: Tracking? get() = ready.value

        /**
         * T229, the task's own rule for a recording asked for before the tracker is ready (no setting, ADR 0044: readiness
         * is not a preference): the Record chip says "Starting" ([TrackingUiState.starting]) while this waits, then [action]
         * runs once; a second ask while one waits is refused (null). Off the main thread. Ready already: [action] runs now.
         */
        fun <R> once(context: Context, action: (Tracking) -> R): R? =
            ready.once(WAIT_MS, ::callerOf, { build(context.applicationContext as DystopoApp) }, { waiting ->
                if (waiting) mainHandler.post { TrackingUiState.starting = true }
                // After the action's own refreshUi: its posted state reaches the main thread first, so "Record" never flashes.
                else ready.value?.post { mainHandler.post { TrackingUiState.starting = false } } ?: mainHandler.post { TrackingUiState.starting = false }
            }, action)

        /** T229: `GET /tracks`' readiness fields (also its whole answer before the tracker is ready). */
        fun readiness(): Map<String, Any?> = linkedMapOf(
            "ready" to (ready.value != null), "readyAtMs" to readyAtMs, "buildMs" to buildMs, "delayedMs" to delayedMs,
            "startWaiting" to ready.waiting, "startError" to ready.failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
        )

        /** T229, debug builds: holds the next launch's construction [ms] (0 forgets it), once. A file, so a force-stop keeps it. */
        fun delayNextStart(app: DystopoApp, ms: Long) {
            val f = File(app.filesDir, DELAY_FILE)
            if (ms <= 0) f.delete() else f.writeText(ms.toString())
        }

        fun pendingDelayMs(app: DystopoApp): Long = File(app.filesDir, DELAY_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L

        private fun takeDelay(app: DystopoApp): Long = pendingDelayMs(app).also { if (it > 0) File(app.filesDir, DELAY_FILE).delete() }

        /** The first frame outside this file: the caller a failed [check] names. */
        private fun callerOf(): String = Throwable().stackTrace.firstOrNull {
            !it.className.startsWith(Tracking::class.java.name) && !it.className.startsWith(TrackerReady::class.java.name)
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: "unknown"
    }
}

/**
 * T226: the Compose state behind [Tracking.ui], in an object of its own, as [io.github.jasondorjeshort.dystopo.app.nav.NavUiState]
 * does (T86). It was a field of [Tracking], so the thread that built [Tracking] made it, in the global snapshot; when the
 * first composition's snapshot opened before the state was made, the read threw "Reading a state that was created after
 * the snapshot was taken". [Tracking.prepare] makes it before any composition. T229: the tracker itself is built on its
 * own thread ([Tracking.start]), never by the first composition or the corridors' thread (which waits for it now), and is
 * published here as [tracking] once built; the controls draw from [ui] from the first frame and bind the overlay when
 * [tracking] arrives.
 */
internal object TrackingUiState {
    var ui by mutableStateOf(Tracking.UiState())

    /** T229: the tracker once built (main thread); null until then. */
    var tracking by mutableStateOf<Tracking?>(null)

    /** T229: a recording asked for before [tracking] was ready waits ([Tracking.once]): the Record chip says "Starting". */
    var starting by mutableStateOf(false)

    /** Forces creation now (call from the main thread, before the first composition). */
    fun prepare() {}
}

/**
 * T229: a value built once on another thread, with the wait rules of [Tracking.of]; pure, so `TrackingUiStateTest` runs it.
 * [isMain] says whether the calling thread is the UI thread; [onReady] gets the value on the thread that [set] it.
 */
internal class TrackerReady<T : Any>(private val isMain: () -> Boolean, private val onReady: (T) -> Unit = {}) {
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile var value: T? = null
        private set
    @Volatile var failure: Throwable? = null
        private set

    /** An action of [once] is waiting. */
    val waiting: Boolean get() = pending.get()

    fun set(v: T) {
        value = v
        failure = null
        latch.countDown()
        onReady(v)
    }

    fun failed(e: Throwable) {
        failure = e
        latch.countDown()
    }

    /**
     * The value, waiting at most [waitMs] for it; null when the build failed (the caller may build it). On the main thread
     * before it is set, a failed check naming [caller], unless [allowMain].
     */
    fun await(waitMs: Long, caller: () -> String, allowMain: Boolean = false): T? {
        value?.let { return it }
        check(allowMain || !isMain()) { "Tracking.of on the main thread before the tracker is ready, from ${caller()}: read TrackingUiState.tracking, or call it on a thread" }
        if (!latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException("the tracker was not ready in $waitMs ms (${caller()})")
        }
        return value
    }

    /** See [Tracking.once]: [onWaiting] true when an action starts to wait, false once it ran (or failed). */
    fun <R> once(waitMs: Long, caller: () -> String, build: () -> T, onWaiting: (Boolean) -> Unit, action: (T) -> R): R? {
        value?.let { return action(it) }
        if (!pending.compareAndSet(false, true)) return null
        onWaiting(true)
        try {
            return action(await(waitMs, caller) ?: build())
        } finally {
            pending.set(false)
            onWaiting(false)
        }
    }
}
