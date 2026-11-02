package io.github.jasondorjeshort.dystopo.app.tracking

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.controls.Heading
import io.github.jasondorjeshort.dystopo.app.readout.Barometer
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.nav.TerrainLive
import io.github.jasondorjeshort.dystopo.nav.TerrainLiveEstimate
import io.github.jasondorjeshort.dystopo.nav.TerrainLiveOptions
import io.github.jasondorjeshort.dystopo.nav.TerrainState
import io.github.jasondorjeshort.dystopo.readout.Altimeter
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.GraphTileArchive
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.sinh

/**
 * T203: a position from the terrain when the GPS is gone (pass 1; the design is `docs/PLANNING.md` › Position from the terrain;
 * the measurement is finding `docs/findings/2026-10-30-terrain-matched-position-measurement.md`). One more [LocationSource]
 * whose fixes carry `source` [SOURCE] and the filter's spread as their accuracy; the rules are the shared
 * [TerrainLive] (`devtool terrain-fix --live` runs the same), fed on a thread of its own:
 *
 * - **Watching** while the setting ([AppSettings.estimatePosition], "Estimate position when GPS is poor") is on, the phone
 *   has a barometer, the activity is on foot, and a track records or the app is in the foreground (the T150 rule: nothing
 *   in the background unless recording). Watching holds the barometer ([Barometer.Reason.TERRAIN]) and keeps the good
 *   fixes, the pressure and (from the first poor fix) the compass, so the minute before an estimate can be caught up.
 * - **Fixes** come from the recorder's sources ([Tracking]: the GPS while recording, injected fixes and replays) and the
 *   map's device listener ([onMapFix]); network, passive and terrain fixes are left out. Good means within the activity's
 *   accuracy bound. The sensor's samples are put on the fixes' clock by the last fix's offset from the wall clock; while a
 *   replay's pressure column flows the sensor is ignored, as the readout does.
 * - **The heading** is the compass ([Heading], asked for from the first poor fix: [Heading.setTerrainWanted]); a debug
 *   run (`POST /location/terrain`, `compass=course`) takes it from the replay's own bearing instead, as the measurement's
 *   compass, since the emulator's does not turn.
 * - **The trails** are the pack graph's tiles within [TerrainLiveOptions.graphRadiusM] of the seed, stitched into one graph
 *   ([GraphTileArchive.whole]); the graph is asked for (`RoutePlanner.ensure`) when the GPS turns poor.
 * - **Out**: each step's estimate to the recorder (recorded with its source, flagged, out of the distance until the next
 *   good fix: `TrackRecorder`), and to the map's puck while it is in the foreground (`MapController.showEstimate`: a hollow
 *   dot with a dashed ring, then a grey dot on the last estimate after the give-up). An estimate never reaches the readout
 *   nor [Heading]'s course: the readout would read the DEM at its own guess, and the heading term its own output.
 *
 * `GET /location/terrain` is [json]; `POST /location/terrain` runs a replay through it ([debugRun], `TrackRoutes`).
 */
object TerrainSource : LocationSource {
    const val TAG = "DystopoTerrain"
    /** The fix source of an estimate (`LocationFix.source`, the puck's `Location.provider`); "estimated" in what users read. */
    const val SOURCE = "terrain"

    override val name = SOURCE

    /** The sensor's samples are ignored this long after a replay's pressure (as the readout's `INJECTED_PRECEDENCE_MS`). */
    private const val REPLAY_PRECEDENCE_MS = 10_000L
    /** Estimates kept for `GET /location/terrain`. */
    private const val LOG_SIZE = 400

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-terrain").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(LocationFix) -> Unit>()
    private val options = TerrainLiveOptions()

    private val dem = ElevationSampler { lat, lon -> runCatching { io.github.jasondorjeshort.dystopo.app.trips.Trips.demSampler()?.elevation(lat, lon) }.getOrNull() ?: Double.NaN }

    // Worker thread only.
    private val live = TerrainLive(options, ::graphAt, dem)
    private var offsetMs = 0L
    private var lastReplayPressureAt = 0L
    private var lastInjected: LocationFix? = null
    private var debugHeading: Double? = null
    private var stateBefore = TerrainState.IDLE
    private val log = ArrayDeque<Map<String, Any?>>()
    private var graphKey: Pair<GraphTileArchive, Set<Long>>? = null
    private var graph: RoutingGraph? = null
    private var graphLoadMs: Long? = null
    private var graphAsked = 0L

    @Volatile var watching = false
        private set
    /** Why it is not watching (null while it watches). */
    @Volatile var off: String? = "not started"
        private set
    /** True while a debug run takes the compass from the replay's bearing (`compass=course`). */
    @Volatile var debugCompass = false
        private set
    @Volatile private var started = false
    @Volatile private var compassAsked = false

    /** What the puck and the chip show, published on the main thread. */
    data class Display(val state: TerrainState, val spreadM: Double?, val atWallMs: Long)

    var display by mutableStateOf<Display?>(null)
        private set

    /** The state the map gates its poor fixes on: true while the estimate (or its grey give-up) holds the puck. */
    @Volatile var holdsPuck = false
        private set

    @Volatile var emitted = 0L
        private set
    @Volatile var sensorSamples = 0L
        private set
    @Volatile var replaySamples = 0L
        private set
    @Volatile var sensorIgnored = 0L
        private set

    override fun start(listener: (LocationFix) -> Unit): Boolean {
        listeners += listener
        init()
        return true
    }

    override fun stop() = listeners.clear()

    private fun init() {
        if (started) return
        started = true
        // Off the caller's thread: [Tracking] starts this source from its constructor, and [update] asks it whether it records.
        CoroutineScope(Dispatchers.Default).launch { Foreground.flow.collect { update() } }
    }

    // ---- inputs (any thread) ----

    /** A fix from the recorder's sources or the replay (its own time). */
    fun onFix(fix: LocationFix) {
        if (!watching) return
        if (fix.source == SOURCE || fix.source == "network" || fix.source == "passive") return
        val receivedWall = System.currentTimeMillis()
        val injected = fix.source != "gps"
        worker.execute {
            safely {
                offsetMs = fix.time - receivedWall
                if (injected) {
                    val prev = lastInjected
                    if (debugCompass && prev != null && TrackStats.distanceM(prev.lat, prev.lon, fix.lat, fix.lon) > 0.5) {
                        // The measurement's compass: the bearing of travel plus 5 deg of bias and 15 of noise.
                        debugHeading = (TrackStats.bearingDeg(prev.lat, prev.lon, fix.lat, fix.lon) + 5.0 + noise.nextGaussian() * 15.0 + 360) % 360
                    }
                    lastInjected = fix
                    debugHeading?.let { if (debugCompass) live.onHeading(fix.time, it) }
                }
                after(live.onFix(fix.time, fix.lat, fix.lon, fix.accuracyM, boundM()))
            }
        }
    }

    private val noise = java.util.Random(203)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** One device fix the map's own listener heard (main thread; not while fixes are injected). */
    fun onMapFix(loc: Location) {
        if (!watching || loc.provider == SOURCE) return
        val provider = loc.provider ?: LocationManager.GPS_PROVIDER
        if (provider != LocationManager.GPS_PROVIDER) return
        onFix(GpsLocationSource.toFix(loc, "gps"))
    }

    /** The barometer (its thread): [wallMs] on the wall clock. */
    fun onSensorPressure(wallMs: Long, hPa: Double) {
        sensorSamples++
        worker.execute {
            safely {
                if (SystemClock.elapsedRealtime() - lastReplayPressureAt < REPLAY_PRECEDENCE_MS) { sensorIgnored++; return@safely }
                val t = wallMs + offsetMs
                compassSample(t)
                after(live.onPressure(t, Altimeter.pressureAltitudeM(hPa)))
            }
        }
    }

    /** A replay's pressure column (`Replay.kt`), [timeMs] on the fixes' clock. */
    fun onReplayPressure(timeMs: Long, hPa: Double) {
        if (!watching) return
        replaySamples++
        worker.execute {
            safely {
                lastReplayPressureAt = SystemClock.elapsedRealtime()
                compassSample(timeMs)
                after(live.onPressure(timeMs, Altimeter.pressureAltitudeM(hPa)))
            }
        }
    }

    /** The phone's compass, read at each pressure sample while it runs for the estimate. */
    private fun compassSample(t: Long) {
        if (debugCompass || !compassAsked) return
        if (Heading.compassFresh) Heading.compass.trueHeadingDeg?.let { live.onHeading(t, it) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "terrain source", e)
        }
    }

    // ---- watching ----

    /** The activity's accuracy bound: the recording's profile, else the remembered activity's (50 m on foot). */
    fun boundM(): Double = runCatching {
        val t = Tracking.of(DystopoApp.instance)
        (if (t.recorder.recording) t.recorder.profile else AppSettings.trackProfile()).accuracyBoundM
    }.getOrNull() ?: 50.0

    private fun activity(): Activity = runCatching {
        val t = Tracking.of(DystopoApp.instance)
        if (t.recorder.recording) t.recorder.profile.activity else AppSettings.trackActivity
    }.getOrDefault(Activity.FOOT)

    /** Works out whether it watches (the setting, the barometer, the activity, recording or the foreground). Any thread. */
    fun update() {
        val recording = runCatching { Tracking.of(DystopoApp.instance).recorder.recording }.getOrDefault(false)
        val why = whyOff(AppSettings.estimatePosition, Barometer.present, activity(), recording, Foreground.started)
        val on = why == null
        off = why
        if (on == watching) return
        watching = on
        Barometer.want(Barometer.Reason.TERRAIN, on)
        worker.execute {
            safely {
                if (!on) {
                    live.reset()
                    askCompass(false)
                    publish(TerrainState.IDLE, null)
                }
            }
        }
    }

    /** Asks for the compass from the first poor fix while estimating; lets it go after. */
    private fun askCompass(on: Boolean) {
        val want = on && !debugCompass
        if (want == compassAsked) return
        compassAsked = want
        Heading.setTerrainWanted(want)
    }

    // ---- outputs (worker thread) ----

    private fun after(made: List<TerrainLiveEstimate>) {
        val st = live.state
        askCompass(st == TerrainState.WAITING || st == TerrainState.ESTIMATING)
        if (st == TerrainState.WAITING && stateBefore != TerrainState.WAITING) prepareGraph()
        for (e in made) {
            val truth = lastInjected?.takeIf { kotlin.math.abs(it.time - e.timeMs) <= 2_000 }
            log.addLast(
                linkedMapOf(
                    "timeMs" to e.timeMs, "lat" to e.lat, "lon" to e.lon, "spreadM" to r1(e.spreadM), "rawSpreadM" to r1(e.rawSpreadM),
                    "headingUsed" to e.headingUsed, "catchUp" to e.catchUp,
                    "replayErrorM" to truth?.let { r1(TrackStats.distanceM(it.lat, it.lon, e.lat, e.lon)) },
                ),
            )
            while (log.size > LOG_SIZE) log.removeFirst()
        }
        val e = made.lastOrNull()
        if (e != null && st == TerrainState.ESTIMATING) {
            val fix = LocationFix(e.lat, e.lon, null, e.timeMs, e.spreadM, SOURCE)
            emitted++
            listeners.forEach { runCatching { it(fix) } }
            publish(st, e.spreadM)
            if (Foreground.started) runCatching { DystopoApp.instance.map.showEstimate(e.lat, e.lon, e.spreadM, e.timeMs) }
        } else if (st != stateBefore) {
            when (st) {
                TerrainState.GAVE_UP -> {
                    publish(st, null, keepTime = true)
                    if (Foreground.started) runCatching { DystopoApp.instance.map.showLost() }
                }
                TerrainState.IDLE, TerrainState.WAITING, TerrainState.NO_FIX -> publish(st, null)
                else -> {}
            }
        }
        if (st != stateBefore) Log.i(TAG, "${stateBefore.code} -> ${st.code}" + (live.reason?.let { " ($it)" } ?: ""))
        stateBefore = st
    }

    private fun publish(st: TerrainState, spreadM: Double?, keepTime: Boolean = false) {
        holdsPuck = st == TerrainState.ESTIMATING || st == TerrainState.GAVE_UP
        val at = if (keepTime) display?.atWallMs ?: System.currentTimeMillis() else System.currentTimeMillis()
        val d = if (st == TerrainState.ESTIMATING || st == TerrainState.GAVE_UP) Display(st, spreadM, at) else null
        mainHandler.post { display = d }
    }

    // ---- the trails ----

    private fun prepareGraph() {
        graphAsked++
        Thread({ runCatching { io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.ensure() } }, "terrain-graph").start()
    }

    /** The trails within [radiusM] of ([lat], [lon]) from the ready graph of the pack there, or null (the source asks again). */
    private fun graphAt(lat: Double, lon: Double, radiusM: Double): RoutingGraph? {
        val archive = io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.archiveAt(lat, lon) ?: run { prepareGraph(); return null }
        val box = io.github.jasondorjeshort.dystopo.routing.TiledGraph.around(lat, lon, radiusM)
        val z = archive.tileZoom
        val keys = archive.entries.filter { e -> tileMeets(e.x, e.y, z, box) }.map { GraphTileArchive.key(it.x, it.y) }.toSet()
        if (keys.isEmpty()) return null
        val key = archive to keys
        graph?.takeIf { graphKey == key }?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val g = archive.whole { e -> GraphTileArchive.key(e.x, e.y) in keys }
        graphLoadMs = SystemClock.elapsedRealtime() - t0
        graphKey = key
        graph = g
        Log.i(TAG, "trails for the estimate: ${keys.size} tile(s), ${g.edgeCount} edges in ${graphLoadMs} ms")
        return g
    }

    private fun tileMeets(x: Int, y: Int, z: Int, box: DoubleArray): Boolean {
        val n = (1 shl z).toDouble()
        val west = x / n * 360 - 180
        val east = (x + 1) / n * 360 - 180
        fun lat(yy: Double) = atan(sinh(PI * (1 - 2 * yy / n))) * 180 / PI
        val north = lat(y.toDouble())
        val south = lat(y + 1.0)
        return east >= box[0] && west <= box[2] && north >= box[1] && south <= box[3]
    }

    /** The recording's line while estimating or after the give-up (the controls' state line, the notification), else null. */
    fun statusLine(units: ContourUnits): String? {
        val d = display ?: return null
        return when (d.state) {
            TerrainState.ESTIMATING -> d.spreadM?.let { TerrainText.estimating(it, units) }
            TerrainState.GAVE_UP -> TerrainText.lost(System.currentTimeMillis() - d.atWallMs)
            else -> null
        }
    }

    // ---- debug ----

    /**
     * `POST /location/terrain`: forgets what it holds and takes the compass from the replay ([compassFromReplay]) for the
     * run the caller starts next. False when it does not watch ([off] says why).
     */
    fun debugRun(compassFromReplay: Boolean): Boolean {
        update()
        if (!watching) return false
        debugCompass = compassFromReplay
        worker.submit {
            safely {
                live.reset()
                log.clear()
                lastInjected = null
                debugHeading = null
                stateBefore = TerrainState.IDLE
                askCompass(false)
                publish(TerrainState.IDLE, null)
            }
        }.get()
        return true
    }

    /** `GET /location/terrain`. */
    fun json(): Map<String, Any?> = worker.submit<Map<String, Any?>> {
        linkedMapOf(
            "source" to SOURCE, "setting" to AppSettings.estimatePosition, "barometer" to Barometer.present,
            "watching" to watching, "off" to off,
        ) + live.json() + linkedMapOf(
            "spreadM" to live.last?.spreadM?.let(::r1),
            "noFixReason" to if (live.state == TerrainState.GAVE_UP || live.state == TerrainState.NO_FIX) live.reason else if (!watching) off else null,
            "holdsPuck" to holdsPuck, "emitted" to emitted, "debugCompass" to debugCompass, "compassAsked" to compassAsked,
            "samples" to linkedMapOf("sensor" to sensorSamples, "replay" to replaySamples, "sensorIgnored" to sensorIgnored),
            "graph" to linkedMapOf("edges" to graph?.edgeCount, "tiles" to graphKey?.second?.size, "loadMs" to graphLoadMs, "asked" to graphAsked),
            "options" to options.json(),
            "estimates" to log.toList(),
        )
    }.get()

    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    /** Why the source does not watch, or null when it does (pure, `TerrainSourceTest`). */
    fun whyOff(setting: Boolean, barometer: Boolean, activity: Activity, recording: Boolean, foreground: Boolean): String? = when {
        !setting -> "setting-off"
        !barometer -> "no-barometer"
        activity != Activity.FOOT -> "activity"
        !recording && !foreground -> "background"
        else -> null
    }
}

/** T203: what users read about the estimate (ADR 0031: the strings are in the task report). Pure, `TerrainSourceTest`. */
object TerrainText {
    /** The chip while estimating: "Estimated position, about 80 m" (the spread rounded up). */
    fun estimating(spreadM: Double, units: ContourUnits): String = "Estimated position, about " + about(spreadM, units)

    /** The chip after the give-up: "No position, last known 12 min ago". */
    fun lost(ageMs: Long): String = "No position, last known " + RecordingStatus.silence((ageMs / 1000).coerceAtLeast(0)) + " ago"

    fun about(m: Double, units: ContourUnits): String =
        if (units == ContourUnits.FEET) "${(ceil(m * 3.28084 / 10) * 10).toInt()} ft" else "${(ceil(m / 10) * 10).toInt()} m"

    const val DIAGNOSTICS = "Estimated position"

    /** The diagnostics line's value. */
    fun diagnostics(barometer: Boolean, setting: Boolean): String = when {
        !barometer -> "not available: this phone has no barometer"
        !setting -> "off in settings (barometer present)"
        else -> "on (barometer present)"
    }
}
