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
import io.github.jasondorjeshort.dystopo.readout.KnownHeights
import io.github.jasondorjeshort.dystopo.readout.PressureSample
import io.github.jasondorjeshort.dystopo.readout.PressureSampleSource
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutEstimator
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.readout.STATION_SOURCE_ID
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
 *   T204: each sample carries its source (`sensor`, `replay`); the estimator takes a change of source (a replay's column
 *   ending and the sensor taking over) or a step past the activity's climb cap as a new barometer offset, never as a
 *   climb, and once the barometer is silent past its staleness window a GNSS or DEM height re-anchors the elevation.
 *   The station's pressure is a prior on the offset ([PressureSource]), not a sample.
 *   The sensor's samples wait for the first fix after a start or a reset: before it the fixes' clock is unknown, and
 *   a sample stamped on the wall clock would put the estimator hours ahead of a replay's file times (the fix round's
 *   stall; the estimator now also starts afresh on a clock that goes back).
 * - **The activity** is T172's remembered one ([AppSettings.trackActivity]) with its recording profile, the options
 *   [AppSettings.readoutOptions]; a change takes effect at the next [refresh] (the estimator's windows restart, the
 *   elevation carries on).
 * - **Along the route**: while navigating, the fix's progress along the navigated line when within its off-route
 *   distance, so the speed on foot and by bike is distance along the route over a long window (T160's `route` source).
 * - **The DEM** is the active DEM pack's ([Trips.demSampler]); with none the elevation runs on GNSS (and the barometer).
 * - **The sea-level pressure** (T173): while the barometer is wanted and present, [PressureSource] fetches the nearest
 *   weather stations' settings for a whole-degree box around the view and [recompute] hands the chosen one to the
 *   estimator as a prior (applied once per report); `GET /readout` › `seaLevelPressure` shows it.
 *
 * The estimator reads on its own clock (the fixes' times): [refresh] reads it at the wall clock moved by the last fix's
 * offset from it, so a replay's file times and a device's GPS times both see gaps in real time. T177: the climb window
 * ([AppSettings.climbWindowS]) is part of the options, so a change takes effect at the next [refresh] with the window's
 * history kept; `GET /readout` › `bar` reports the switch, the window and each field's `state` and text. The strip
 * ([ReadoutStrip]) calls [refresh] twice a second while shown and draws [snapshot]; `GET /readout` ([json]) mirrors it.
 *
 * T178: the barometer's trend ([PressureWatch]) is apart from the estimator (it never reads the fused elevation or its
 * offset); a replay's pressure column goes to both, the bar's elevation carries the trend's arrow ([StripItem.trend]),
 * and `GET /readout` › `trend` is [PressureWatch.json].
 *
 * T197: a known height ([pin]: the long-press card's "I am at <name>, <height>", Readout settings' "Set my height now",
 * `POST /readout/calibrate`) goes to the estimator's [ReadoutEstimator.pin] on the readout's thread; while it holds
 * the bar draws its mark ([Snapshot.markShown], [PIN_GLYPH]) and `GET /readout` › `pin` reports it. [freshFix] is the
 * fix the long-press card measures its 100 m from.
 *
 * T196: the pressure-fall warning's way down ([WayDownWatch], in `PressureCard.kt`) reads [lastFix] as the walker's
 * position; `GET /readout` › `wayDown` is [WayDownWatch.json].
 */
object LiveReadout {
    const val TAG = "DystopoReadout"

    /** A network fix this soon after a GPS one is not used (the map listens to both providers). */
    const val GPS_PRECEDENCE_MS = 10_000L

    /** A step longer than this, faster than the profile's speed bound, is a jump: the estimator starts afresh (as `Heading.JUMP_M`). */
    const val JUMP_M = 2_000.0

    /** The map's own fixes and the sensor's pressure are ignored this long after an injected fix or pressure. */
    const val INJECTED_PRECEDENCE_MS = 10_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-readout").apply { isDaemon = true } }

    // Worker thread only.
    private var est: ReadoutEstimator? = null
    private var routeHint: Double? = null
    private var lastFed: LocationFix? = null
    /** A fix has set [offsetMs] since the start or the last [reset]. */
    private var clockKnown = false

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
        PressureWatch.onReplayPressure(timeMs, hPa) // T178: the trend takes the replay's column too
        post { ensure().onPressure(PressureSample(timeMs, hPa, PressureSampleSource.REPLAY)) } // T204: the source, so a switch is a new offset
    }

    /** A barometer sample ([Barometer], its own thread); [wallMs] on the wall clock. */
    fun onSensorPressure(wallMs: Long, hPa: Double) {
        if (SystemClock.elapsedRealtime() - lastInjectedAt < INJECTED_PRECEDENCE_MS) { bump("sensorPressureIgnored"); return }
        post {
            if (!clockKnown) bump("sensorPressureBeforeClock")
            else { bump("sensorPressure"); ensure().onPressure(PressureSample(wallMs + offsetMs, hPa, PressureSampleSource.SENSOR)) }
        }
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
        // T229: reached in the first composition (ReadoutStrip), so on a thread of its own, which waits for the tracker; on
        // the main thread before it is ready Tracking.of fails, and this runCatching would leave the readout unhooked for good.
        Thread({ runCatching { Tracking.of(DystopoApp.instance).injected.start { onInjectedFix(it) } }.onFailure { android.util.Log.w("DystopoReadout", "injected fixes not hooked", it) } }, "readout-hook").start()
        CoroutineScope(Dispatchers.Default).launch { Foreground.flow.collect { refresh() } }
    }

    /** Takes the settings as they are now, reads the estimator and updates [snapshot] (on the readout's thread). */
    fun refresh() = post { recompute() }

    /** T177: the bar before the first [refresh] answered: every shown entry a dash. */
    fun placeholder(): List<StripItem> =
        StripText.items(null, AppSettings.readoutOptions(), AppSettings.units, io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits, trendSlot = trendSlot())

    /** T178: an arrow of the barometer's trend can appear beside the elevation (the switch on, a barometer present). */
    private fun trendSlot(): Boolean = AppSettings.readoutBarometer && Barometer.present

    /** Forgets every input (`POST /readout/reset`, between replays); the settings stay. */
    fun reset(): Map<String, Any?> = call {
        est = null
        routeHint = null
        lastFed = null
        clockKnown = false
        snapshot = null
        offsetMs = 0L
        lastInjectedAt = 0L
        lastReplayPressureAt = 0L
        lastFix = null // T197
        lastPin = null
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
            // T177: the bar as drawn: its switch, the climb window, both-units mode and each field's state and text.
            "bar" to linkedMapOf(
                "on" to AppSettings.readoutBar,
                "window" to (s?.options?.climbWindowS ?: AppSettings.climbWindowS).let { w -> linkedMapOf("s" to w, "label" to ReadoutOptions.windowLabel(w)) },
                "windowFilledS" to s?.readout?.climbFilledS?.let { Math.round(it * 10) / 10.0 },
                "bothUnits" to (s?.both ?: false),
                "fields" to s?.items?.map { linkedMapOf("entry" to it.entry.code, "state" to it.state, "text" to it.full, "other" to it.other, "caption" to it.caption, "dimmed" to it.dimmed) },
            ),
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
            "seaLevelPressure" to PressureSource.json(s?.readout?.counters?.get("priors")), // T173
            "trend" to PressureWatch.json(), // T178: the barometer's trend and the pressure-fall warning
            "pin" to pinJson(s), // T197: the known height set ("I am at", "Set my height now")
            "wayDown" to WayDownWatch.json(), // T196: the way down after the pressure-fall warning
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
        // A jump (the first fix of a replay far from the last device fix, a network fix replaced by GPS after a long
        // silence) is not travel: start afresh rather than read a speed, a climb and a suspect barometer into it.
        lastFed?.let { l ->
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(l.lat, l.lon, fix.lat, fix.lon)
            val dt = (fix.time - l.time) / 1000.0
            if (d > JUMP_M && (dt <= 0 || d / dt > AppSettings.trackProfile().speedBoundMps)) {
                est = null
                routeHint = null
                bump("jumpResets")
            }
        }
        lastFed = fix
        lastFix = fix // T197
        val e = ensure()
        offsetMs = fix.time - System.currentTimeMillis()
        clockKnown = true
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
        // T173: the weather station prior, fetched and fed only while the barometer serves the shown strip.
        PressureSource.tick(wanted && Barometer.present, lastFed, offsetMs)?.let { e.onStationPressure(it) }
        if (STATION_SOURCE_ID in r.provenance) PressureSource.credited = true
        val both = io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits // T177 (T161's follow-up)
        val items = StripText.items(r, o, units, both, PressureWatch.arrow, PressureWatch.arrowLabel, trendSlot()) // T178: the trend's arrow
        snapshot = Snapshot(r, o, units, items, StripText.gap(r.gapS), wanted, both)
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
        /** T177: the items carry the other unit system's text (the scale bar's both-units mode). */
        val both: Boolean = false,
    ) {
        /** The strip as one line of text (what `GET /readout` `line` and the strip's description say); T197: the mark's words last. */
        val line: String? get() = items.takeIf { it.isNotEmpty() }?.let { l ->
            l.joinToString(" · ") { "${it.caption} ${it.full}" + (it.trendLabel?.let { t -> ", pressure $t" } ?: "") } +
                (if (markShown) " · " + KnownHeights.markLabel(readout.barometer.pinnedName) else "")
        }

        /** T197: the bar draws the mark beside the elevation: a pin holds and the elevation is drawn. */
        val markShown: Boolean get() = readout.barometer.pinned && items.any { it.entry == ReadoutEntry.ELEVATION }
    }

    // T197: the known height ("I am at <name>", "Set my height now"; shared `readout/KnownHeights`): the estimator's pin,
    // the last fix the long-press card measures from, and `GET /readout` › `pin`.

    /** The last fix fed to the estimator (any thread reads it). */
    @Volatile var lastFix: LocationFix? = null
        private set

    /** The last pin's result and words, for `GET /readout` › `pin` › `last`. */
    @Volatile private var lastPin: Map<String, Any?>? = null

    /** The last fix when it is at most [FIX_FRESH_MS] old on the fixes' clock, else null. */
    fun freshFix(): LocationFix? = lastFix?.takeIf { System.currentTimeMillis() + offsetMs - it.time <= FIX_FRESH_MS }

    /** A fix older than this is not "here" for the long-press card's "I am at". */
    const val FIX_FRESH_MS = 120_000L

    /** The DEM height at a point (NaN for none), as the estimator reads it: the long-press card's tag check. */
    fun demAt(lat: Double, lon: Double): Double = dem.elevation(lat, lon)

    /**
     * Sets the height to [eleM] (m) with 1-sigma [sigmaM] from [name] (null: typed) on the readout's thread, at the wall
     * clock moved to the fixes' clock, and refreshes the bar. [via] (`menu`, `settings`, `route`) is reported.
     */
    fun pin(name: String?, eleM: Double, sigmaM: Double, via: String): io.github.jasondorjeshort.dystopo.readout.PinResult = call {
        val r = ensure().pin(name, eleM, sigmaM, System.currentTimeMillis() + offsetMs)
        bump("pins.$via")
        lastPin = linkedMapOf<String, Any?>(
            "via" to via, "name" to name, "eleM" to eleM, "sigmaM" to sigmaM, "atWallMs" to System.currentTimeMillis(),
            "confirmation" to KnownHeights.confirmation(name, eleM, AppSettings.units),
        ) + r.json()
        recompute()
        r
    }

    /** [pin] off the caller's thread; [done] gets the confirmation line on the main thread (null when it failed). */
    fun pinThen(name: String?, eleM: Double, sigmaM: Double, via: String, done: (String?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val line = runCatching { pin(name, eleM, sigmaM, via); KnownHeights.confirmation(name, eleM, AppSettings.units) }
                .onFailure { Log.w(TAG, "pin failed", it) }.getOrNull()
            main.post { done(line) }
        }, "dystopo-pin").apply { isDaemon = true }.start()
    }

    /** `GET /readout` › `pin`: the mark as drawn and the pin behind it. */
    private fun pinJson(s: Snapshot?): Map<String, Any?> {
        val b = s?.readout?.barometer
        val units = s?.units ?: AppSettings.units
        val pinnedAt = b?.pinnedAt
        return linkedMapOf(
            "pinned" to (b?.pinned ?: false),
            "pinnable" to (b?.pinnable ?: false),
            "name" to b?.pinnedName, "eleM" to b?.pinnedEleM, "sigmaM" to b?.pinnedSigmaM, "atMs" to pinnedAt,
            "ageS" to if (s != null && pinnedAt != null) (s.readout.timeMs - pinnedAt) / 1000 else null,
            "offsetSigmaM" to b?.offsetSigmaM?.let { Math.round(it * 100) / 100.0 },
            "holdsUnderM" to b?.pinnedSigmaM?.let { it * 2 },
            "end" to b?.pinEnd,
            "mark" to linkedMapOf(
                "shown" to (s?.markShown ?: false),
                "glyph" to PIN_GLYPH,
                "label" to if (b?.pinned == true) KnownHeights.markLabel(b.pinnedName) else null,
                "detail" to markDetail(s),
                "boundsPx" to StripLayout.markBoundsPx?.map { Math.round(it) },
                "taps" to StripLayout.markTaps,
                "lastTapText" to StripLayout.markLastTap,
            ),
            "fix" to freshFix()?.let { linkedMapOf("lat" to it.lat, "lon" to it.lon, "time" to it.time) },
            "last" to lastPin,
        )
    }

    /** What a tap on the mark says ("Set at Alta Vista, 10:42, 5938 ft"), or null with no pin. */
    fun markDetail(s: Snapshot? = snapshot): String? {
        val b = s?.readout?.barometer ?: return null
        val at = b.pinnedAt ?: return null
        val ele = b.pinnedEleM ?: return null
        return KnownHeights.markDetail(b.pinnedName, clockText(at - offsetMs), ele, s.units)
    }

    /** The phone's clock text for [wallMs] ("10:42", or "10:42 AM" by the phone's setting). */
    fun clockText(wallMs: Long): String =
        android.text.format.DateFormat.getTimeFormat(DystopoApp.instance).format(java.util.Date(wallMs))

    /** The bar's mark: a survey point's triangle, as topographic maps mark a height measured on the ground. */
    const val PIN_GLYPH = "△"
    // end T197
}
