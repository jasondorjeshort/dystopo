package io.github.jasondorjeshort.dystopo.app.readout

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.tracking.GpsLocationSource
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.ElevationSource
import io.github.jasondorjeshort.dystopo.readout.PressureSample
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutEstimator
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T161: the live readout on the phone, around T160's [ReadoutEstimator] (`shared/.../readout/`). One estimator for the
 * app, fed on a thread of its own (it is not thread-safe, and a fix may read a DEM tile):
 *
 * - **Fixes**: the map's device fixes ([onMapFix], from `controls/Heading.onFix`, every fix the location puck shows; a
 *   network fix within [GPS_PRECEDENCE_MS] of a GPS one is skipped, it would pull a walking speed about) and injected ones
 *   ([onInjectedFix]: `POST /location` and replays, through the recorder's injected source, which carries their
 *   elevation, speed and accuracy; while they flow the map's own fixes are ignored, as the recorder does).
 * - **Pressure**: the [Barometer] ([onSensorPressure]) or a replay's pressure column ([onReplayPressure]); while fixes are
 *   injected the sensor's samples are ignored, so a replay's heights are not fought by the emulator's fixed pressure.
 * - **The activity** is T172's remembered one ([AppSettings.trackActivity]) with its recording profile, the options
 *   [AppSettings.readoutOptions]; a change takes effect at the next [refresh] (the estimator's windows restart, the
 *   elevation carries on).
 * - **Along the route**: while navigating, the fix's progress along the navigated line when within its off-route
 *   distance, so the speed on foot and by bike is distance along the route over a long window (T160's `route` source).
 * - **The DEM** is the active DEM pack's ([Trips.demSampler]); with none the elevation runs on GNSS (and the barometer).
 * - **The sea-level pressure** prior stays "none" until T173.
 *
 * The estimator reads on its own clock (the fixes' times): [refresh] reads it at the wall clock moved by the last fix's
 * offset from it, so a replay's file times and a device's GPS times both see gaps in real time. The strip
 * ([ReadoutStrip]) calls [refresh] twice a second while shown and draws [snapshot]; `GET /readout` ([json]) mirrors it.
 */
object LiveReadout {
    const val TAG = "DystopoReadout"

    /** A network fix this soon after a GPS one is not used (the map listens to both providers). */
    const val GPS_PRECEDENCE_MS = 10_000L

    /** The map's own fixes and the sensor's pressure are ignored this long after an injected fix or pressure. */
    const val INJECTED_PRECEDENCE_MS = 10_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-readout").apply { isDaemon = true } }

    // Worker thread only.
    private var est: ReadoutEstimator? = null
    private var routeHint: Double? = null

    /** What the strip draws and `GET /readout` reports, from the last [refresh]. */
    @Volatile var snapshot: Snapshot? = null
        private set

    /** The fixes' clock minus the wall clock at the last fix. */
    @Volatile private var offsetMs = 0L
    @Volatile private var lastInjectedAt = 0L
    @Volatile private var lastGpsAt = 0L
    @Volatile private var lastReplayPressureAt = 0L
    @Volatile private var route: Map<String, Any?> = mapOf("navigating" to false)

    /** The strip is in the composition (set by [ReadoutStrip]). */
    @Volatile var composed = false
        private set
    @Volatile private var hooked = false

    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun bump(k: String) { counts.merge(k, 1L, Long::plus) }

    /** One line in `Heading.onFix` (main thread): a fix the location puck shows. */
    fun onMapFix(loc: Location) {
        val app = DystopoApp.instance
        if (app.map.injectedLocation || SystemClock.elapsedRealtime() - lastInjectedAt < INJECTED_PRECEDENCE_MS) { bump("mapFixesIgnoredInjected"); return }
        val now = SystemClock.elapsedRealtime()
        val provider = loc.provider ?: LocationManager.GPS_PROVIDER
        if (provider == LocationManager.GPS_PROVIDER) lastGpsAt = now
        else if (lastGpsAt != 0L && now - lastGpsAt < GPS_PRECEDENCE_MS) { bump("networkFixesSkipped"); return }
        val fix = GpsLocationSource.toFix(loc, provider)
        bump("mapFixes")
        post { feedFix(fix) }
    }

    /** An injected fix (`POST /location`, a replay), from the recorder's injected source (any thread). */
    fun onInjectedFix(fix: LocationFix) {
        lastInjectedAt = SystemClock.elapsedRealtime()
        bump("injectedFixes")
        post { feedFix(fix) }
    }

    /** A replay's pressure column (`Replay.kt`): [timeMs] on the fixes' clock. */
    fun onReplayPressure(timeMs: Long, hPa: Double) {
        val now = SystemClock.elapsedRealtime()
        lastInjectedAt = now
        lastReplayPressureAt = now
        bump("replayPressure")
        post { ensure().onPressure(PressureSample(timeMs, hPa)) }
    }

    /** A barometer sample ([Barometer], its own thread); [wallMs] on the wall clock. */
    fun onSensorPressure(wallMs: Long, hPa: Double) {
        if (SystemClock.elapsedRealtime() - lastInjectedAt < INJECTED_PRECEDENCE_MS) { bump("sensorPressureIgnored"); return }
        bump("sensorPressure")
        post { ensure().onPressure(PressureSample(wallMs + offsetMs, hPa)) }
    }

    /** The strip entered ([on]) or left the composition; starts listening to injected fixes and the foreground once. */
    fun setComposed(on: Boolean) {
        composed = on
        hook()
        refresh()
    }

    private fun hook() {
        if (hooked) return
        hooked = true
        runCatching { Tracking.of(DystopoApp.instance).injected.start { onInjectedFix(it) } }
        CoroutineScope(Dispatchers.Default).launch { Foreground.flow.collect { refresh() } }
    }

    /** Takes the settings as they are now, reads the estimator and updates [snapshot] (on the readout's thread). */
    fun refresh() = post { recompute() }

    /** Forgets every input (`POST /readout/reset`, between replays); the settings stay. */
    fun reset(): Map<String, Any?> = call {
        est = null
        routeHint = null
        snapshot = null
        offsetMs = 0L
        lastInjectedAt = 0L
        lastReplayPressureAt = 0L
        counts.clear()
        bump("resets")
        recompute()
        mapOf("reset" to true)
    }

    /** `GET /readout`: a fresh [snapshot] with the inputs, the sensor and the route. */
    fun json(): Map<String, Any?> {
        val s = runCatching { call { recompute(); snapshot } }.getOrNull()
        val b = Barometer.json()
        return linkedMapOf(
            "shown" to StripLayout.shown,
            "hiddenBecause" to StripLayout.hiddenBecause,
            "activity" to (s?.options?.activity ?: AppSettings.trackActivity).code,
            "units" to (s?.units ?: AppSettings.units).symbol,
            "options" to s?.options?.let { o -> ReadoutEntry.entries.associate { it.code to o[it].encode() } + mapOf("barometer" to o.barometer, "knots" to o.knots) },
            "drawn" to s?.items?.map { it.json() },
            "line" to s?.line,
            "gap" to s?.gapText,
            "text" to s?.let { snap -> ReadoutEntry.entries.associate { e -> e.code to ReadoutText.of(snap.readout[e], snap.options, snap.units)?.full } },
            "readout" to s?.readout?.json(),
            "barometerSensor" to b + mapOf(
                "wanted" to (s?.barometerWanted ?: false),
                "source" to when {
                    SystemClock.elapsedRealtime() - lastReplayPressureAt < INJECTED_PRECEDENCE_MS -> "replay"
                    Barometer.listening -> "sensor"
                    else -> null
                },
            ),
            "seaLevelPressure" to "none", // T173 adds the weather station prior
            "route" to route,
            "inputs" to linkedMapOf(
                "counts" to counts.toSortedMap(),
                "clockOffsetMs" to offsetMs,
                "injectedRecently" to (lastInjectedAt != 0L && SystemClock.elapsedRealtime() - lastInjectedAt < INJECTED_PRECEDENCE_MS),
                "composed" to composed, "foreground" to Foreground.started,
            ),
            "layout" to StripLayout.json(),
        )
    }

    private fun post(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "readout task failed", e)
            }
        }
    }

    private fun <T> call(block: () -> T): T = worker.submit(Callable(block)).get(5, TimeUnit.SECONDS)

    private fun feedFix(fix: LocationFix) {
        val e = ensure()
        offsetMs = fix.time - System.currentTimeMillis()
        e.onFix(fix, routeAlong(fix))
    }

    /** Progress along the navigated line when the fix is within its off-route distance of it. */
    private fun routeAlong(fix: LocationFix): Double? {
        val nav = NavSession.navigator
        if (nav == null) {
            routeHint = null
            route = mapOf("navigating" to false)
            return null
        }
        val p = runCatching { nav.path.project(fix.lat, fix.lon, routeHint) }.getOrNull() ?: return null
        val on = p.distanceM <= nav.options.offRouteM
        if (on) routeHint = p.alongM
        route = linkedMapOf("navigating" to true, "alongM" to Math.round(p.alongM * 10) / 10.0, "distanceM" to Math.round(p.distanceM * 10) / 10.0, "onRoute" to on)
        return if (on) p.alongM else null
    }

    private val dem = ElevationSampler { lat, lon -> runCatching { Trips.demSampler()?.elevation(lat, lon) }.getOrNull() ?: Double.NaN }

    /** The estimator on the current activity, profile and options. */
    private fun ensure(): ReadoutEstimator {
        val a = AppSettings.trackActivity
        val profile = AppSettings.trackProfile(a)
        val options = AppSettings.readoutOptions(a)
        val e = est
        if (e == null) return ReadoutEstimator(profile, options, dem).also { est = it }
        if (e.profile != profile) e.setProfile(profile, options) else if (e.options != options) e.options = options
        return e
    }

    private fun recompute() {
        val e = ensure()
        val r = e.readout(System.currentTimeMillis() + offsetMs)
        val units = AppSettings.units
        val o = e.options
        val wanted = composed && StripLayout.shown && Foreground.started && barometerUsed(e.profile, o)
        Barometer.want(wanted)
        snapshot = Snapshot(r, o, units, StripText.items(r, o, units), StripText.gap(r.gapS), wanted)
    }

    /** The barometer is read while it can serve a shown entry: the switch on, an activity that fuses it, climb or elevation shown. */
    fun barometerUsed(p: ActivityProfile, o: ReadoutOptions): Boolean =
        o.barometer && p.elevation == ElevationSource.FUSED && (o.climb.shown || o.elevation.shown)

    /** One [refresh]'s result. */
    class Snapshot(
        val readout: Readout,
        val options: ReadoutOptions,
        val units: ContourUnits,
        val items: List<StripItem>,
        val gapText: String?,
        val barometerWanted: Boolean,
    ) {
        /** The strip as one line of text (what `GET /readout` `line` and the strip's description say). */
        val line: String? get() = items.takeIf { it.isNotEmpty() }?.let { l -> (listOfNotNull(gapText) + l.map { "${it.caption} ${it.full}" }).joinToString(" · ") }
    }
}
