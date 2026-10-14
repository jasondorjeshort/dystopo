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
import io.github.jasondorjeshort.dystopo.store.RecorderOptions
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.StoredWaypoint
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import io.github.jasondorjeshort.dystopo.store.TrackStore
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Track recording for the app process (T31, T40): the durable [TrackStore] (SQLDelight, `tracks.db`
 * in the app's databases dir), the [TrackRecorder], the location sources that feed it, the map
 * [overlay], GPX replays and imports, and the state the Compose controls show (live stats, pause,
 * the waypoint sheet, the elevation profile).
 *
 * Every store and recorder call runs on one thread ([call]/[post]), so fixes from the GPS (main
 * looper), the debug server's threads and a replay never interleave. [TrackingService] keeps the
 * process in the foreground while recording and runs the GPS source.
 *
 * Fix sources while recording ([Mode]): `gps`, `injected` (debug `POST /location` and replays), or
 * `all`. In `all`, injected fixes take precedence: GPS fixes are ignored while a replay runs and for
 * [INJECTED_PRECEDENCE_MS] after an injected fix, so a replay on an emulator (whose GPS reports a
 * default position) doesn't zigzag between the two.
 *
 * The segment gap ([RecorderOptions.segmentGapMs]) is a setting kept in the `dystopo-tracking`
 * preferences (`gapS`, 0 = never split); `POST /tracks/settings?gapS=` changes it.
 */
class Tracking private constructor(private val app: DystopoApp) {
    enum class Mode(val gps: Boolean, val injected: Boolean) { ALL(true, true), GPS(true, false), INJECTED(false, true) }

    /** The elevation profile panel: [samples] along the track, plus its stats. */
    data class ProfileView(val trackId: String, val name: String, val samples: List<ProfileSample>, val stats: TrackStats)

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
        val shownId: String? = null,
        val message: String? = null,
        /** The waypoint the rename/delete sheet is open for. */
        val selectedWaypoint: StoredWaypoint? = null,
        val profile: ProfileView? = null,
        val gapS: Long = RecorderOptions.DEFAULT_SEGMENT_GAP_MS / 1000,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-tracks").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val dbFile: File = app.getDatabasePath(DB_NAME)
    val store: TrackStore = SqlTrackStore(AndroidSqliteDriver(TrackDb.Schema, app, DB_NAME), System::currentTimeMillis) // T44: wall clock under the sync HLC
    val recorder = TrackRecorder(store, RecorderOptions(segmentGapMs = gapMsOf(prefs.getLong(KEY_GAP_S, RecorderOptions.DEFAULT_SEGMENT_GAP_MS / 1000))), clock = System::currentTimeMillis)
    val injected = InjectedLocationSource()
    val gps = GpsLocationSource(app)
    val overlay = TrackOverlay()
    val replay = Replay(this)
    /** Where GPX exports go and imports are listed from: `files/tracks/`. */
    val filesDir: File get() = File(app.filesDir, "tracks").apply { mkdirs() }

    @Volatile var mode: Mode = Mode.ALL
        private set
    @Volatile var shownId: String? = null
        private set
    @Volatile var selectedWaypointId: String? = null
        private set
    @Volatile var profileId: String? = null
        private set
    @Volatile var serviceState: String = "idle"
    @Volatile var gpsState: String = "off"
    /** GPS fixes dropped because injected fixes had precedence (see [Mode.ALL]). */
    @Volatile var gpsIgnored = 0L
        private set
    /** Ids of tracks found still "recording" at startup (the process died mid-recording); closed then. */
    val interrupted: List<String>

    var ui by mutableStateOf(UiState())
        private set

    private var lastOverlayAt = 0L
    @Volatile private var overlayPending = false
    private var profileCache: Pair<Pair<String, Int>, List<ProfileSample>>? = null

    init {
        interrupted = call { recorder.recoverInterrupted() }
        if (interrupted.isNotEmpty()) Log.i(TAG, "closed ${interrupted.size} track(s) interrupted by a process death")
        injected.start { fix -> post { onFix(fix, fromGps = false) } }
        shownId = call { store.list().lastOrNull()?.id }
        // Taps arrive on the main thread; selectWaypoint waits on the tracking thread, so hop off main.
        overlay.onWaypointTap = { id -> Thread { selectWaypoint(id) }.start() }
        refreshUi()
        refreshOverlay(force = true)
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
    fun onGpsFix(fix: LocationFix): Unit = post { onFix(fix, fromGps = true) }

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
        if (recorder.onFix(fix)) {
            if (recorder.accepted % 10 == 1L) refreshUi() else refreshUiThrottled()
            refreshOverlay()
        }
    }

    fun start(name: String?, mode: Mode): TrackSummary {
        val s = call {
            this.mode = mode
            val n = name?.takeIf { it.isNotBlank() } ?: "Track ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}"
            recorder.start(n).also { shownId = it.id }
        }
        startService()
        refreshUi()
        refreshOverlay(force = true)
        return s
    }

    fun stop(): TrackSummary? {
        val s = call { recorder.stop() }
        stopService()
        refreshUi()
        refreshOverlay(force = true)
        return s
    }

    /** Holds recording (fixes are ignored until [resume]); false when not recording. */
    fun pause(): Boolean {
        val ok = call { if (recorder.recording) { recorder.pause(); true } else false }
        refreshUi()
        return ok
    }

    /** Continues a paused recording in a new segment; false when not recording. */
    fun resume(): Boolean {
        val ok = call { if (recorder.recording) { recorder.resume(); true } else false }
        refreshUi()
        return ok
    }

    /** Sets and persists the segment gap in seconds (0 or less: never split on time). */
    fun setGapSeconds(seconds: Long) {
        val s = seconds.coerceAtLeast(0)
        prefs.edit().putLong(KEY_GAP_S, s).apply()
        call { recorder.options = recorder.options.copy(segmentGapMs = gapMsOf(s)) }
        refreshUi()
    }

    val gapSeconds: Long get() = (recorder.options.segmentGapMs ?: 0L) / 1000

    fun show(id: String?): Boolean {
        if (id != null && call { store.summary(id) } == null) return false
        shownId = id
        refreshUi()
        refreshOverlay(force = true)
        return true
    }

    fun delete(id: String): Boolean {
        val deleted = call {
            if (recorder.activeId == id) recorder.stop()
            store.delete(id)
        }
        if (shownId == id) shownId = null
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

    /** Opens the elevation profile for [id] (and shows the track); null closes it. False for an unknown track. */
    fun showProfile(id: String?): Boolean {
        if (id != null) {
            if (call { store.summary(id) } == null) return false
            shownId = id
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
        if (id == recorder.activeId) Track(id, "", recorder.livePoints().map { TrackSegment(it) }) else store.track(id)

    /** Imports GPX text into the store and shows the first new track. Throws IllegalArgumentException for a bad file. */
    fun importGpx(text: String, fallbackName: String): TrackImport.Result {
        val r = call { TrackImport.import(store, text, System.currentTimeMillis(), fallbackName) }
        r.imported.firstOrNull()?.let { shownId = it.id }
        val msg = "Imported ${r.imported.size} track(s), ${r.waypoints} waypoint(s)" +
            if (r.skippedTracks.isNotEmpty()) "; ${r.skippedTracks.size} already here" else ""
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
        return importGpx(f.readText(Charsets.UTF_8), f.nameWithoutExtension)
    }

    /** Writes the track as GPX into `files/tracks/`; returns the file. */
    fun export(id: String): File? = call {
        val s = store.summary(id) ?: return@call null
        val gpx = TrackExport.gpx(store, id) ?: return@call null
        File(filesDir, TrackExport.fileName(s)).apply { writeText(gpx, Charsets.UTF_8) }
    }

    private fun startService() {
        serviceState = try {
            app.startForegroundService(Intent(app, TrackingService::class.java))
            "starting"
        } catch (e: Exception) {
            // Android 12+ refuses to start a foreground service from the background; recording still works while the process lives.
            Log.w(TAG, "foreground service not started", e)
            "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun stopService() {
        app.stopService(Intent(app, TrackingService::class.java))
        serviceState = "stopped"
    }

    private var lastUiAt = 0L

    private fun refreshUiThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiAt >= 1000) refreshUi()
    }

    /** Recomputes [ui] on the tracking thread and publishes it on the main thread. */
    fun refreshUi(message: String? = null): Unit = post {
        lastUiAt = SystemClock.elapsedRealtime()
        val live = recorder.liveStats()
        val active = recorder.activeId?.let { store.summary(it) }
        val tracks = store.list().reversed()
        val profile = profileId?.let { id ->
            val s = tracks.firstOrNull { it.id == id } ?: return@let null
            val key = id to (if (id == recorder.activeId) recorder.accepted.toInt() else s.points)
            val samples = profileCache?.takeIf { it.first == key }?.second ?: (profileOf(id) ?: emptyList()).also { profileCache = key to it }
            ProfileView(id, s.name, samples, statsOf(s) ?: return@let null)
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
            shownId = shownId,
            message = message ?: ui.message,
            selectedWaypoint = selectedWaypointId?.let { store.waypoint(it) },
            profile = profile,
            gapS = gapSeconds,
        )
        main.post { ui = state }
        TrackingService.instance?.update(state)
    }

    /**
     * Redraws the overlay with the shown track (the active one while recording), at most twice a
     * second unless [force]d. Runs the store read on the tracking thread.
     */
    fun refreshOverlay(force: Boolean = false): Unit = post {
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
        val id = shownId
        val track: Track? = id?.let { trackFor(it) }
        // T91: free-standing waypoints (dropped with no recording, or synced) show whatever track is shown.
        val waypoints = (id?.let { store.waypoints(it) } ?: emptyList()) + store.waypoints(null)
        overlay.show(track, waypoints, recording = id != null && id == recorder.activeId)
    }

    companion object {
        const val TAG = "DystopoTracks"
        const val DB_NAME = "tracks.db"
        const val INJECTED_PRECEDENCE_MS = 30_000L
        const val OVERLAY_INTERVAL_MS = 500L
        const val PROFILE_SAMPLES = 200
        private const val PREFS = "dystopo-tracking"
        private const val KEY_GAP_S = "gapS"

        private fun gapMsOf(seconds: Long): Long? = if (seconds <= 0) null else seconds * 1000

        @Volatile private var instance: Tracking? = null

        fun of(context: Context): Tracking = instance ?: synchronized(this) {
            instance ?: Tracking(context.applicationContext as DystopoApp).also { instance = it }
        }
    }
}
