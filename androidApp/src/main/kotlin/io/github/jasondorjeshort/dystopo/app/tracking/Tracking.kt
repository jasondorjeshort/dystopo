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
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import io.github.jasondorjeshort.dystopo.store.TrackStore
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Track recording for the app process (T31): the durable [TrackStore] (SQLDelight, `tracks.db` in
 * the app's databases dir), the [TrackRecorder], the location sources that feed it, the map
 * [overlay], GPX replays, and the state the Compose controls show.
 *
 * Every store and recorder call runs on one thread ([call]/[post]), so fixes from the GPS (main
 * looper), the debug server's threads and a replay never interleave. [TrackingService] keeps the
 * process in the foreground while recording and runs the GPS source.
 *
 * Fix sources while recording ([Mode]): `gps`, `injected` (debug `POST /location` and replays), or
 * `all`. In `all`, injected fixes take precedence: GPS fixes are ignored for [INJECTED_PRECEDENCE_MS]
 * after an injected one, so a replay on an emulator (whose GPS reports a default position) doesn't
 * zigzag between the two.
 */
class Tracking private constructor(private val app: DystopoApp) {
    enum class Mode(val gps: Boolean, val injected: Boolean) { ALL(true, true), GPS(true, false), INJECTED(false, true) }

    /** What the Compose controls show; replaced (on the main thread) after each change. */
    data class UiState(
        val recording: Boolean = false,
        val activeName: String? = null,
        val distanceM: Double = 0.0,
        val points: Int = 0,
        val durationS: Double? = null,
        val tracks: List<TrackSummary> = emptyList(),
        val shownId: String? = null,
        val message: String? = null,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-tracks").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    val dbFile: File = app.getDatabasePath(DB_NAME)
    val store: TrackStore = SqlTrackStore(AndroidSqliteDriver(TrackDb.Schema, app, DB_NAME))
    val recorder = TrackRecorder(store, clock = System::currentTimeMillis)
    val injected = InjectedLocationSource()
    val gps = GpsLocationSource(app)
    val overlay = TrackOverlay()
    val replay = Replay(this)

    @Volatile var mode: Mode = Mode.ALL
        private set
    @Volatile var shownId: String? = null
        private set
    @Volatile var serviceState: String = "idle"
    @Volatile var gpsState: String = "off"
    /** Ids of tracks found still "recording" at startup (the process died mid-recording); closed then. */
    val interrupted: List<String>

    var ui by mutableStateOf(UiState())
        private set

    private val statsCache = HashMap<String, Pair<Long, TrackStats>>()
    private var lastOverlayAt = 0L
    @Volatile private var overlayPending = false

    init {
        interrupted = call { recorder.recoverInterrupted() }
        if (interrupted.isNotEmpty()) Log.i(TAG, "closed ${interrupted.size} track(s) interrupted by a process death")
        injected.start { fix -> post { onFix(fix, fromGps = false) } }
        shownId = call { store.list().lastOrNull()?.id }
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
            if (mode.injected && injected.lastPublishedAt != 0L &&
                SystemClock.elapsedRealtime() - injected.lastPublishedAt < INJECTED_PRECEDENCE_MS
            ) return
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
            statsCache.remove(id)
            store.delete(id)
        }
        if (shownId == id) shownId = null
        refreshUi()
        refreshOverlay(force = true)
        return deleted
    }

    /** Stats of a stored track, cached until its point count changes. Call on the tracking thread. */
    fun statsOf(s: TrackSummary): TrackStats? {
        if (recorder.activeId == s.id) return recorder.liveStats()
        statsCache[s.id]?.let { (n, st) -> if (n == s.points.toLong()) return st }
        val t = store.track(s.id) ?: return null
        return TrackStats.of(t).also { statsCache[s.id] = s.points.toLong() to it }
    }

    /** Writes the track as GPX into `files/tracks/`; returns the file. */
    fun export(id: String): File? = call {
        val s = store.summary(id) ?: return@call null
        val gpx = TrackExport.gpx(store, id) ?: return@call null
        val dir = File(app.filesDir, "tracks").apply { mkdirs() }
        File(dir, TrackExport.fileName(s)).apply { writeText(gpx, Charsets.UTF_8) }
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
        val state = UiState(
            recording = recorder.recording,
            activeName = active?.name,
            distanceM = live?.distanceM ?: 0.0,
            points = live?.points ?: 0,
            durationS = live?.durationS,
            tracks = store.list().reversed(),
            shownId = shownId,
            message = message ?: ui.message,
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
        val track: Track? = when {
            id == null -> null
            id == recorder.activeId -> Track(id, "", recorder.livePoints().map { io.github.jasondorjeshort.dystopo.model.TrackSegment(it) })
            else -> store.track(id)
        }
        val waypoints = id?.let { store.waypoints(it) } ?: emptyList()
        overlay.show(track, waypoints, recording = id != null && id == recorder.activeId)
    }

    companion object {
        const val TAG = "DystopoTracks"
        const val DB_NAME = "tracks.db"
        const val INJECTED_PRECEDENCE_MS = 30_000L
        const val OVERLAY_INTERVAL_MS = 500L

        @Volatile private var instance: Tracking? = null

        fun of(context: Context): Tracking = instance ?: synchronized(this) {
            instance ?: Tracking(context.applicationContext as DystopoApp).also { instance = it }
        }
    }
}
