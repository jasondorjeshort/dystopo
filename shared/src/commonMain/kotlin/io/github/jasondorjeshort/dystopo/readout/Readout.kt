package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.ElevationSource
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A barometer sample: pressure in hPa at [timeMs] (the sensor's time, on the fixes' clock). */
data class PressureSample(val timeMs: Long, val hPa: Double)

/** How a readout value is drawn. */
enum class ValueState(val code: String) {
    /** A number. */
    SHOWN("shown"),

    /** A dash in place of the number: standing still (speed). */
    DASH("dash"),

    /** Not drawn: switched off, no data yet, or a climb rate near zero ([ReadoutValue.reason] says which). */
    HIDDEN("hidden"),
}

/**
 * One value of the readout (T160). [value] and [sigma] (1-sigma) are SI: speed m/s, climb m/s, course degrees true,
 * elevation metres above mean sea level (EGM2008); [ReadoutText] turns them into text in the user's units.
 * [source] says where the value came from: speed `fix` (the fix's own speed), `route` (distance along the navigated
 * line over a long window) or `positions` (distance over time over at least 30 s, for fixes that carry no speed);
 * course `fix` or `positions`; climb and elevation `barometer` (fused with GNSS and the DEM), `gnss`, `gnss+dem` or
 * `dem`. [dimmed]: in a gap of the fix stream (or, for climb and elevation, with no barometer to carry them through
 * it); [frozen]: the course held from the last movement while standing still.
 */
data class ReadoutValue(
    val entry: ReadoutEntry,
    val state: ValueState,
    val value: Double? = null,
    val sigma: Double? = null,
    val source: String? = null,
    val dimmed: Boolean = false,
    val frozen: Boolean = false,
    val reason: String? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.code, "value" to value?.let { r3(it) }, "sigma" to sigma?.let { r3(it) }, "source" to source,
        "dimmed" to dimmed, "frozen" to frozen, "reason" to reason,
    )
}

/** The barometer's part ([ReadoutEstimator.readout]): `off` (switched off, or road, rail, air), `none` (no samples), `calibrating`, `in use`, `suspect`. */
enum class BarometerState(val code: String) {
    OFF("off"),
    NONE("none"),
    CALIBRATING("calibrating"),
    IN_USE("in use"),
    SUSPECT("suspect"),
}

data class BarometerStatus(
    val state: BarometerState,
    /** Why it is suspect: `speed` (vehicle speed), `disagreement` (its climb and the GNSS climb differ), `jitter`. */
    val reason: String? = null,
    val offsetM: Double? = null,
    val offsetSigmaM: Double? = null,
    val pressureHPa: Double? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.code, "reason" to reason, "offsetM" to offsetM?.let { r3(it) },
        "offsetSigmaM" to offsetSigmaM?.let { r3(it) }, "pressureHPa" to pressureHPa?.let { r3(it) },
    )
}

/**
 * The heights behind the elevation, side by side (what `GET /readout` shows, T161): the last fix's GNSS height, the
 * barometer's (pressure altitude minus the offset), the DEM's at the last fix. Null where there is none.
 */
data class ReadoutHeights(val gnssM: Double?, val baroM: Double?, val demM: Double?) {
    fun json(): Map<String, Any?> = linkedMapOf("gnssM" to gnssM?.let { r3(it) }, "baroM" to baroM?.let { r3(it) }, "demM" to demM?.let { r3(it) })
}

/** The live readout at [timeMs] ([ReadoutEstimator.readout]). [gapS]: seconds since the last fix while that is a gap, else null. */
data class Readout(
    val timeMs: Long,
    val activity: Activity,
    val speed: ReadoutValue,
    val climb: ReadoutValue,
    val course: ReadoutValue,
    val elevation: ReadoutValue,
    val gapS: Double?,
    val heights: ReadoutHeights,
    val barometer: BarometerStatus,
    /** The fix speed and the along-route speed before one was chosen for [speed] (m/s; null when not available). */
    val fixSpeedMps: Double?,
    val routeSpeedMps: Double?,
    val counters: Map<String, Long>,
) {
    operator fun get(entry: ReadoutEntry): ReadoutValue = when (entry) {
        ReadoutEntry.SPEED -> speed
        ReadoutEntry.CLIMB -> climb
        ReadoutEntry.COURSE -> course
        ReadoutEntry.ELEVATION -> elevation
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "timeMs" to timeMs, "activity" to activity.code,
        "speed" to speed.json(), "climb" to climb.json(), "course" to course.json(), "elevation" to elevation.json(),
        "gapS" to gapS?.let { r3(it) }, "heights" to heights.json(), "barometer" to barometer.json(),
        "fixSpeedMps" to fixSpeedMps?.let { r3(it) }, "routeSpeedMps" to routeSpeedMps?.let { r3(it) },
        "counters" to counters,
    )
}

/**
 * The live readout's estimator (T160; the readout idea, `docs/ideas/2026-10-25-live-velocity-readout-by-travel-mode.md`,
 * its "Sketch the research supports"): fixes, barometer samples, weather station pressure and a DEM in; speed (or
 * pace), climb rate, course and elevation out, each with a state, an uncertainty and its source ([readout]). Pure:
 * no Android, no clock (every input carries its time; [readout] takes the time to read at), no thread safety (the
 * caller feeds it from one thread, in time order).
 *
 * - **Speed** is the fix's own speed (Doppler or fused), averaged over the activity's window
 *   ([ReadoutTuning.speedWindowS]), never differenced positions (they read long from noise: 17% at 10 m steps with
 *   3 m noise). Fixes with no speed of their own (a GPX without it) fall back to distance over time over at least
 *   30 s, source `positions`. On foot and on a bike, while navigating ([onFix]'s `routeAlongM`), the speed is the
 *   distance along the route over a long window ([ReadoutTuning.routeWindowS]), source `route`. A speed over the
 *   profile's bound and the first speed after a gap are dropped (the reacquisition spike).
 * - **Standing still** ([ReadoutTuning.still], hysteresis): speed is a dash, the course is frozen at the last moving one.
 * - **Elevation and climb** come from [ElevationFilter]: the barometer when the profile's elevation is `fused` (foot,
 *   bike, boat; never road, rail or air) and [ReadoutOptions.barometer] is on; GNSS gated and decimated; the DEM on
 *   foot (slope under 20°, no structure near, one update per 150 m moved); a weather station's pressure as a prior
 *   ([onStationPressure]). The barometer is dropped while suspect: GNSS speed over 7 m/s; its 60 s climb and the GNSS
 *   one differing by over 1 m/s in two windows in a row; jitter over 1.5 m peak to peak (0.75 m RMS about a line) in
 *   10 s on foot. It reopens 60 s after the last trigger, its offset taken afresh from the height. The climb rate is
 *   lagged 5 to 10 s ([ReadoutTuning.climbLagS]) and hidden near zero ([ReadoutTuning.climbGate]).
 * - **A gap** in the fixes (over 5 s or three intervals) dims the values and [Readout.gapS] counts it.
 *
 * @param dem the terrain height at a point (NaN for none), or null for no DEM.
 * @param nearStructure true where the DEM should not be trusted at a point: a bridge, building, tunnel or cliff
 *   nearby (the app has these in its tiles); null for none known.
 */
class ReadoutEstimator(
    profile: ActivityProfile,
    options: ReadoutOptions = ReadoutOptions.defaults(profile.activity),
    private val dem: ElevationSampler? = null,
    private val nearStructure: ((Double, Double) -> Boolean)? = null,
) {
    /** The estimator with [activity]'s default profile and options. */
    constructor(activity: Activity, dem: ElevationSampler? = null) : this(ActivityProfile.of(activity), dem = dem)

    var profile: ActivityProfile = profile
        private set
    var options: ReadoutOptions = options
    val activity: Activity get() = profile.activity

    private val filter = ElevationFilter(profile.activity)

    private class Sample(
        val t: Long, val lat: Double, val lon: Double, val speed: Double?, val course: Double?, val ele: Double?,
        val hAcc: Double?, val vAcc: Double?, val along: Double?,
    )

    private val fixes = ArrayDeque<Sample>()
    private val baro = ArrayDeque<Pair<Long, Double>>()
    private var moving = false
    private var movingSinceMs = Long.MIN_VALUE
    private var lastCourse: Double? = null
    private var lastCourseSigma: Double? = null
    private var lastCourseSource: String? = null
    private var lastDemAt: Sample? = null
    private var demAtLastFix: Double? = null
    private var lastPressureHPa: Double? = null
    private var pendingPrior: StationPressure? = null
    private var appliedPrior: Pair<String, Long>? = null
    private var suspectUntilMs: Long? = null
    private var suspectReason: String? = null
    private var windowStart: Triple<Long, Double, Double>? = null
    private var disagreeRun = 0
    private var climbShown = false
    private var smoothV = 0.0
    private var smoothAt: Long? = null

    private val counts = linkedMapOf(
        "fixes" to 0L, "pressureSamples" to 0L, "lateInputs" to 0L, "spikesDropped" to 0L,
        "suspects" to 0L, "reopens" to 0L, "demTries" to 0L,
    )

    init {
        applyBarometerSwitch(null)
    }

    /** Switches to [profile]'s activity (and [options], its defaults unless given): windows restart, the elevation carries on. */
    fun setProfile(profile: ActivityProfile, options: ReadoutOptions = ReadoutOptions.defaults(profile.activity)) {
        this.profile = profile
        this.options = options
        filter.activity = profile.activity
        fixes.clear()
        moving = false
        lastCourse = null
        applyBarometerSwitch(null)
    }

    private val baroAllowed: Boolean get() = options.barometer && profile.elevation == ElevationSource.FUSED

    private fun applyBarometerSwitch(tMs: Long?) {
        val until = suspectUntilMs
        if (until != null && tMs != null && tMs > until) {
            suspectUntilMs = null
            suspectReason = null
            bump("reopens")
        }
        if (baroAllowed && suspectUntilMs == null) filter.openBarometer() else filter.closeBarometer()
    }

    private fun suspect(reason: String, tMs: Long) {
        if (!baroAllowed) return
        if (suspectUntilMs == null) bump("suspects")
        suspectUntilMs = max(suspectUntilMs ?: 0L, tMs + SUSPECT_HOLD_MS)
        suspectReason = reason
        filter.closeBarometer()
        disagreeRun = 0
        windowStart = null
    }

    /**
     * One fix. [routeAlongM] is the progress along the navigated line (`Navigator`'s `NavState.progress`) when
     * navigating and on the route, else null. Fixes not newer than the last are ignored.
     */
    fun onFix(fix: LocationFix, routeAlongM: Double? = null) {
        val last = fixes.lastOrNull()
        if (last != null && fix.time <= last.t) { bump("lateInputs"); return }
        bump("fixes")
        val afterGap = last != null && fix.time - last.t > gapMs
        var speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0 }
        if (speed != null && (afterGap || speed > profile.speedBoundMps)) { bump("spikesDropped"); speed = null }
        val ele = fix.ele?.takeIf { it.isFinite() }
        val s = Sample(fix.time, fix.lat, fix.lon, speed, fix.courseDeg?.takeIf { it.isFinite() }, ele, fix.accuracyM, fix.verticalAccuracyM, routeAlongM)
        fixes.addLast(s)
        while (fixes.size > 2 && fix.time - fixes.first().t > KEEP_MS) fixes.removeFirst()

        // Still or moving, with hysteresis; a resumed movement starts the speed windows afresh.
        val st = ReadoutTuning.still(activity)
        val short = shortSpeed(fix.time)
        if (short != null) {
            if (moving && short < st.stopMps) moving = false
            else if (!moving && short > st.goMps) { moving = true; movingSinceMs = startOfMovement(fix.time) }
        }
        if (moving) updateCourse(fix.time)

        // The barometer: vehicle speed makes it suspect.
        val v = speed ?: positionSpeed(fix.time)?.first
        if (v != null && v > BARO_MAX_GROUND_MPS) suspect("speed", fix.time)
        applyBarometerSwitch(fix.time)

        if (ele != null) {
            val vAcc = fix.verticalAccuracyM?.takeIf { it > 0 } ?: fix.accuracyM?.takeIf { it > 0 }?.let { it * VACC_PER_HACC } ?: DEFAULT_VACC_M
            if (vAcc <= MAX_VACC_M) filter.gnss(fix.time, ele, vAcc)
            disagreement(fix.time, ele, vAcc)
        }
        demAtLastFix = dem?.elevation(fix.lat, fix.lon)?.takeIf { it.isFinite() }
        demUpdate(s, v)
        smooth(fix.time)
    }

    /** One barometer sample (hPa). Samples older than the filter's time are applied at its time and counted. */
    fun onPressure(sample: PressureSample) {
        if (!(sample.hPa in 300.0..1100.0)) return
        bump("pressureSamples")
        val lastT = baro.lastOrNull()?.first
        if (lastT != null && sample.timeMs < lastT) bump("lateInputs")
        lastPressureHPa = sample.hPa
        val alt = Altimeter.pressureAltitudeM(sample.hPa)
        baro.addLast(sample.timeMs to alt)
        while (baro.size > 2 && sample.timeMs - baro.first().first > JITTER_WINDOW_MS) baro.removeFirst()
        if (activity == Activity.FOOT) jitter(sample.timeMs)
        applyBarometerSwitch(sample.timeMs)
        applyPrior(sample.timeMs)
        filter.barometer(sample.timeMs, alt)
        smooth(sample.timeMs)
    }

    /** A weather station's altimeter setting (T173 fetches it): a prior on the barometer offset, applied once per report. */
    fun onStationPressure(p: StationPressure) {
        if (appliedPrior == p.station to p.timeMs) return
        pendingPrior = p
        lastPressureHPa?.let { applyPrior(baro.lastOrNull()?.first ?: p.timeMs) }
    }

    private fun applyPrior(nowMs: Long) {
        val p = pendingPrior ?: return
        val hPa = lastPressureHPa ?: return
        if (!baroAllowed) return
        val at = fixes.lastOrNull()
        val dist = at?.let { TrackStats.distanceM(it.lat, it.lon, p.lat, p.lon) } ?: 0.0
        val sigmaM = p.sigmaHPa(dist, nowMs) * Altimeter.metresPerHPa(hPa, p.hPa)
        filter.prior(Altimeter.pressureAltitudeM(hPa) - Altimeter.heightM(hPa, p.hPa), sigmaM)
        appliedPrior = p.station to p.timeMs
        pendingPrior = null
    }

    /** The readout at [nowMs] (normally the latest input's time or the wall clock): pure, it changes nothing. */
    fun readout(nowMs: Long): Readout {
        val last = fixes.lastOrNull()
        val gap = last?.let { nowMs - it.t }?.takeIf { it > gapMs }
        val gapS = gap?.let { it / 1000.0 }
        val dim = last == null || gap != null
        val fixSpeed = last?.let { fixSpeed(it.t) }
        val pos = if (fixSpeed == null) last?.let { positionSpeed(it.t) } else null
        val route = last?.let { routeSpeed(it.t) }
        val chosen = route?.let { Triple(it.first, it.second, "route") }
            ?: fixSpeed?.let { Triple(it.first, it.second, "fix") }
            ?: pos?.let { Triple(it.first, it.second, "positions") }

        val speed = when {
            !options.speed.shown -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, reason = "off")
            last == null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, reason = "no fix")
            !moving && chosen != null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.DASH, source = chosen.third, dimmed = dim, reason = "still")
            chosen == null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, dimmed = dim, reason = "no speed yet")
            else -> ReadoutValue(ReadoutEntry.SPEED, ValueState.SHOWN, chosen.first, chosen.second, chosen.third, dim)
        }
        val course = when {
            !options.course.shown -> ReadoutValue(ReadoutEntry.COURSE, ValueState.HIDDEN, reason = "off")
            lastCourse == null -> ReadoutValue(ReadoutEntry.COURSE, ValueState.HIDDEN, dimmed = dim, reason = if (last == null) "no fix" else "no course yet")
            else -> ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, lastCourse, lastCourseSigma, lastCourseSource, dim, frozen = !moving)
        }

        val fused = filter.fused(nowMs)
        val heightSource = when {
            fused -> "barometer"
            filter.gnssUpdates == 0 && filter.demUpdates > 0 -> "dem"
            filter.lastDemMs?.let { nowMs - it <= DEM_RECENT_MS } == true -> "gnss+dem"
            else -> "gnss"
        }
        val heightDim = dim && !fused
        val elevation = when {
            !options.elevation.shown -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.HIDDEN, reason = "off")
            !filter.heightKnown -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.HIDDEN, reason = "no height yet")
            else -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, filter.heightM, filter.heightSigmaM, heightSource, heightDim)
        }
        val climbSource = if (fused) "barometer" else "gnss"
        val climb = when {
            !options.climb.shown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN, reason = "off")
            !filter.heightKnown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN, reason = "no height yet")
            !climbShown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN, smoothV, filter.verticalSigmaMps, climbSource, heightDim, reason = "near zero")
            else -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.SHOWN, smoothV, filter.verticalSigmaMps, climbSource, heightDim)
        }

        val baroState = when {
            !baroAllowed -> BarometerStatus(BarometerState.OFF)
            suspectUntilMs != null -> BarometerStatus(BarometerState.SUSPECT, suspectReason, pressureHPa = lastPressureHPa)
            filter.lastBaroMs == null -> BarometerStatus(BarometerState.NONE)
            !filter.offsetKnown || filter.offsetSigmaM > CALIBRATED_SIGMA_M ->
                BarometerStatus(BarometerState.CALIBRATING, offsetM = filter.offsetM.takeIf { filter.offsetKnown }, offsetSigmaM = filter.offsetSigmaM.takeIf { filter.offsetKnown }, pressureHPa = lastPressureHPa)
            else -> BarometerStatus(BarometerState.IN_USE, offsetM = filter.offsetM, offsetSigmaM = filter.offsetSigmaM, pressureHPa = lastPressureHPa)
        }
        val heights = ReadoutHeights(
            last?.ele,
            filter.lastBaroAltM?.takeIf { filter.offsetKnown }?.let { it - filter.offsetM },
            demAtLastFix,
        )
        val c = LinkedHashMap(counts)
        c["gnssUpdates"] = filter.gnssUpdates.toLong()
        c["gnssRejected"] = filter.gnssRejected.toLong()
        c["demUpdates"] = filter.demUpdates.toLong()
        c["demRejected"] = filter.demRejected.toLong()
        c["baroUpdates"] = filter.baroUpdates.toLong()
        c["recalibrations"] = filter.recalibrations.toLong()
        c["priors"] = filter.priors.toLong()
        return Readout(nowMs, activity, speed, climb, course, elevation, gapS, heights, baroState, fixSpeed?.first ?: pos?.first, route?.first, c)
    }

    private val gapMs: Long get() = max((ReadoutTuning.GAP_S * 1000).toLong(), 3 * profile.intervalMs)

    private fun bump(k: String) { counts[k] = (counts[k] ?: 0L) + 1 }

    /** Mean fix speed over the last [ReadoutTuning.STILL_WINDOW_S], else the position speed (no fix speeds). */
    private fun shortSpeed(ref: Long): Double? {
        val from = ref - (ReadoutTuning.STILL_WINDOW_S * 1000).toLong()
        val w = fixes.filter { it.t >= from && it.speed != null }
        return if (w.isNotEmpty()) w.sumOf { it.speed!! } / w.size else positionSpeed(ref)?.first
    }

    /** The movement started at the first fix of the latest run over the go threshold (so the windows hold no standing). */
    private fun startOfMovement(ref: Long): Long {
        val go = ReadoutTuning.still(activity).goMps
        var start = ref
        for (s in fixes.reversed()) {
            val sp = s.speed ?: break
            if (sp < go) break
            start = s.t
        }
        return start
    }

    /** Mean and sigma of the fix speeds over the speed window ending at [ref], when at least half the fixes in it carry one. */
    private fun fixSpeed(ref: Long): Pair<Double, Double>? {
        val windowMs = (ReadoutTuning.speedWindowS(activity, options.speed.damping) * 1000).toLong()
        val from = max(ref - windowMs, if (moving) movingSinceMs else Long.MIN_VALUE)
        val inWindow = fixes.filter { it.t >= from }
        val sp = inWindow.mapNotNull { it.speed }
        if (sp.isEmpty() || sp.size * 2 < inWindow.size) return null
        val mean = sp.average()
        val sd = sqrt(sp.sumOf { (it - mean) * (it - mean) } / max(1, sp.size - 1))
        val span = (inWindow.last().t - inWindow.first().t) / 1000.0
        val nEff = max(1.0, span / SPEED_CORRELATION_S)
        return mean to max(MIN_SPEED_SIGMA, sd / sqrt(nEff))
    }

    /** Distance over time between the latest fix at least [ReadoutTuning.POSITION_WINDOW_S] (or the speed window) before [ref] and [ref]. */
    private fun positionSpeed(ref: Long): Pair<Double, Double>? {
        val windowS = max(ReadoutTuning.POSITION_WINDOW_S, ReadoutTuning.speedWindowS(activity, options.speed.damping))
        val end = fixes.lastOrNull { it.t <= ref } ?: return null
        val start = fixes.lastOrNull { it.t <= ref - (windowS * 1000).toLong() } ?: return null
        val span = (end.t - start.t) / 1000.0
        if (span <= 0) return null
        val d = TrackStats.distanceM(start.lat, start.lon, end.lat, end.lon)
        val acc = hypot(start.hAcc ?: DEFAULT_HACC_M, end.hAcc ?: DEFAULT_HACC_M)
        return d / span to acc / span
    }

    /** Along-route speed over [ReadoutTuning.routeWindowS], when every fix in the window was on the route and progress went forward. */
    private fun routeSpeed(ref: Long): Pair<Double, Double>? {
        val windowS = ReadoutTuning.routeWindowS(activity) ?: return null
        val end = fixes.lastOrNull { it.t <= ref } ?: return null
        val endAlong = end.along ?: return null
        val startT = ref - (windowS * 1000).toLong()
        if (startT < movingSinceMs || !moving) return null
        val start = fixes.lastOrNull { it.t <= startT } ?: return null
        if (fixes.any { it.t >= start.t && it.t <= end.t && it.along == null }) return null
        val span = (end.t - start.t) / 1000.0
        val d = endAlong - start.along!!
        if (span <= 0 || d < 0 || d / span > profile.speedBoundMps) return null
        val acc = hypot(start.hAcc ?: DEFAULT_HACC_M, end.hAcc ?: DEFAULT_HACC_M) / 2
        return d / span to acc / span
    }

    private fun updateCourse(ref: Long) {
        val windowMs = (ReadoutTuning.courseWindowS(activity, options.course.damping) * 1000).toLong()
        val from = max(ref - windowMs, movingSinceMs)
        val w = fixes.filter { it.t >= from && it.course != null }
        if (w.isNotEmpty()) {
            var sx = 0.0
            var sy = 0.0
            for (s in w) { sx += sin(s.course!! * DEG); sy += cos(s.course * DEG) }
            val r = hypot(sx, sy) / w.size
            lastCourse = (atan2(sx, sy) / DEG + 360) % 360
            val circSd = sqrt(max(0.0, -2 * ln(r.coerceIn(1e-9, 1.0)))) / DEG
            lastCourseSigma = circSd / sqrt(max(1.0, w.size.toDouble() / SPEED_CORRELATION_S))
            lastCourseSource = "fix"
            return
        }
        // No course on the fixes: the bearing over the window, once the chord is long enough to be a direction.
        val end = fixes.last()
        val start = fixes.firstOrNull { it.t >= from } ?: return
        val d = TrackStats.distanceM(start.lat, start.lon, end.lat, end.lon)
        if (d < COURSE_STEP_M) {
            val older = fixes.lastOrNull { it.t < from } ?: return
            val d2 = TrackStats.distanceM(older.lat, older.lon, end.lat, end.lon)
            if (d2 < COURSE_STEP_M) return
            setPositionCourse(older, end, d2)
            return
        }
        setPositionCourse(start, end, d)
    }

    private fun setPositionCourse(a: Sample, b: Sample, d: Double) {
        lastCourse = TrackStats.bearingDeg(a.lat, a.lon, b.lat, b.lon)
        lastCourseSigma = atan(hypot(a.hAcc ?: DEFAULT_HACC_M, b.hAcc ?: DEFAULT_HACC_M) / d) / DEG
        lastCourseSource = "positions"
    }

    /** The disagreement rule: barometer and GNSS climb over 60 s windows. */
    private fun disagreement(tMs: Long, ele: Double, vAcc: Double) {
        val baroAlt = filter.lastBaroAltM
        if (!filter.baroOpen || baroAlt == null || vAcc > DISAGREE_MAX_VACC_M) return
        val w = windowStart
        if (w == null) { windowStart = Triple(tMs, baroAlt, ele); return }
        val span = (tMs - w.first) / 1000.0
        if (span < DISAGREE_WINDOW_S) return
        val rb = (baroAlt - w.second) / span
        val rg = (ele - w.third) / span
        windowStart = Triple(tMs, baroAlt, ele)
        if (abs(rb - rg) > DISAGREE_MPS) {
            if (++disagreeRun >= 2) suspect("disagreement", tMs)
        } else {
            disagreeRun = 0
        }
    }

    /** The jitter rule on foot: the RMS of the barometer heights about a line over 10 s. */
    private fun jitter(tMs: Long) {
        if (!filter.baroOpen || baro.size < 5) return
        val t0 = baro.first().first
        if (tMs - t0 < JITTER_WINDOW_MS - 1_000) return
        val n = baro.size
        val ts = baro.map { (it.first - t0) / 1000.0 }
        val hs = baro.map { it.second }
        val mt = ts.average()
        val mh = hs.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) { sxy += (ts[i] - mt) * (hs[i] - mh); sxx += (ts[i] - mt) * (ts[i] - mt) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        var ss = 0.0
        for (i in 0 until n) { val r = hs[i] - (mh + slope * (ts[i] - mt)); ss += r * r }
        if (sqrt(ss / n) > JITTER_RMS_M) suspect("jitter", tMs)
    }

    /** The DEM on foot: slow, gentle ground, no structure near, 150 m from the last DEM update. */
    private fun demUpdate(s: Sample, speedMps: Double?) {
        val d = dem ?: return
        if (activity != Activity.FOOT) return
        if (speedMps != null && speedMps > DEM_MAX_MPS) return
        val prev = lastDemAt
        if (prev != null && TrackStats.distanceM(prev.lat, prev.lon, s.lat, s.lon) < DEM_SPACING_M) return
        val hAcc = s.hAcc ?: DEFAULT_HACC_M
        if (hAcc > DEM_MAX_HACC_M) return
        bump("demTries")
        val h = d.elevation(s.lat, s.lon)
        if (!h.isFinite()) return
        val slope = slopeAt(d, s.lat, s.lon) ?: return
        if (slope > DEM_MAX_SLOPE_DEG) return
        if (nearStructure?.invoke(s.lat, s.lon) == true) return
        val sigma = hypot(DEM_SIGMA_M, kotlin.math.tan(slope * DEG) * hAcc)
        if (filter.dem(s.t, h, sigma)) lastDemAt = s
    }

    private fun slopeAt(d: ElevationSampler, lat: Double, lon: Double): Double? {
        val dLat = SLOPE_STEP_M / M_PER_DEG
        val dLon = dLat / cos(lat * DEG)
        val n = d.elevation(lat + dLat, lon)
        val so = d.elevation(lat - dLat, lon)
        val e = d.elevation(lat, lon + dLon)
        val w = d.elevation(lat, lon - dLon)
        if (!(n.isFinite() && so.isFinite() && e.isFinite() && w.isFinite())) return null
        val gx = (e - w) / (2 * SLOPE_STEP_M)
        val gy = (n - so) / (2 * SLOPE_STEP_M)
        return atan(hypot(gx, gy)) / DEG
    }

    /** The climb rate's display lag and its near-zero gate, after each filter step. */
    private fun smooth(tMs: Long) {
        if (!filter.heightKnown) return
        val at = smoothAt
        val v = filter.verticalMps
        if (at == null) smoothV = v
        else if (tMs > at) {
            val tau = ReadoutTuning.climbLagS(options.climb.damping)
            smoothV += (v - smoothV) * (1 - exp(-(tMs - at) / 1000.0 / tau))
        }
        if (at == null || tMs > at) smoothAt = tMs
        val gate = ReadoutTuning.climbGate(activity)
        val a = abs(smoothV)
        val sure = a >= 2 * filter.verticalSigmaMps
        climbShown = if (climbShown) a >= gate.hideMps && sure else a > gate.showMps && sure
    }

    companion object {
        /** Ground speed over which the barometer is suspect (research: about 7 m/s). */
        const val BARO_MAX_GROUND_MPS = 7.0

        /** The barometer reopens this long after the last suspect trigger. */
        const val SUSPECT_HOLD_MS = 60_000L

        const val DISAGREE_WINDOW_S = 60.0
        const val DISAGREE_MPS = 1.0
        const val DISAGREE_MAX_VACC_M = 10.0
        const val JITTER_WINDOW_MS = 10_000L
        const val JITTER_RMS_M = 0.75

        /** Vertical accuracy assumed from the horizontal when a fix gives none (research: 1.3 to 2 times). */
        const val VACC_PER_HACC = 1.5
        const val DEFAULT_VACC_M = 15.0
        const val DEFAULT_HACC_M = 10.0

        /** GNSS heights worse than this are not used. */
        const val MAX_VACC_M = 50.0

        const val DEM_MAX_MPS = 3.0
        const val DEM_SPACING_M = 150.0
        const val DEM_MAX_SLOPE_DEG = 20.0
        const val DEM_MAX_HACC_M = 30.0
        const val DEM_SIGMA_M = 3.0
        const val DEM_RECENT_MS = 300_000L
        private const val SLOPE_STEP_M = 20.0

        /** Course from positions needs a chord this long (as `Heading.kt`'s step). */
        const val COURSE_STEP_M = 5.0

        /** The barometer counts as calibrated once its offset is known to this (m). */
        const val CALIBRATED_SIGMA_M = 10.0

        /** Fix speeds about this far apart (s) are taken as independent for the uncertainty. */
        private const val SPEED_CORRELATION_S = 2.0
        private const val MIN_SPEED_SIGMA = 0.05
        private const val KEEP_MS = 150_000L
        private const val DEG = PI / 180
        private const val M_PER_DEG = TrackStats.EARTH_RADIUS_M * DEG
    }
}

internal fun r3(v: Double): Double = (v * 1000).roundToLong() / 1000.0
