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

/**
 * A barometer sample: pressure in hPa at [timeMs] (the sensor's time, on the fixes' clock). T204: [source] is where it
 * came from; a change of source between samples is a new barometer offset ([ReadoutEstimator.onPressure]).
 */
data class PressureSample(val timeMs: Long, val hPa: Double, val source: PressureSampleSource = PressureSampleSource.SENSOR)

/**
 * T204: where a pressure sample came from: the phone's own sensor, a replay's pressure column, or a weather station's
 * pressure fed as samples (the phone feeds the station as a prior instead, [ReadoutEstimator.onStationPressure]).
 * Two sources read different pressures for the same place (the emulator's sensor is a fixed sea-level pressure), so
 * the estimator never reads a change of source as a climb.
 */
enum class PressureSampleSource(val code: String) {
    SENSOR("sensor"),
    REPLAY("replay"),
    STATION("station"),
}

/** How a readout value is drawn. */
enum class ValueState(val code: String) {
    /** A number. */
    SHOWN("shown"),

    /**
     * A dash in place of the number: standing still (speed). T250: the climb's dash means only that the sources
     * contradict and the barometer is out for it (`contradiction`); before T250 it also stood for standing still and
     * the window filling, which read [UNKNOWN] or [ZERO] now.
     */
    DASH("dash"),

    /**
     * T177: "0" in place of the number: a climb rate inside the near-zero band ([ReadoutValue.value] keeps the small
     * figure). T250: only when the estimate could have told a climb at the band ([ReadoutTuning.CLIMB_RESOLVE_SIGMAS]),
     * moving (`near zero`) or standing (`still`); a climb too uncertain to tell is [UNKNOWN].
     */
    ZERO("zero"),

    /**
     * T250: "?" in place of the number: the data is insufficient, the climb's window filling (`filling`), its
     * uncertainty too large to tell a climb from level (`uncertain`), or no fix for longer than the hold (`no fix`).
     */
    UNKNOWN("unknown"),

    /** Not drawn by the estimator's own rule: switched off or no data yet ([ReadoutValue.reason] says which; the phone's bar draws a dash unless off). */
    HIDDEN("hidden"),
}

/**
 * T363: how a reason bears on the value it explains, for the bar's "?" mark ([ReadoutReasons.marked]): [NONE] a figure
 * or off, nothing to say; [AT_REST] a value at rest (a speed standing still, the course held from the last movement),
 * explained in the glance view but not marked; [MISSING] the data is not there; [STALE] it is there but old, held or
 * in doubt.
 */
enum class ReasonKind { NONE, AT_REST, MISSING, STALE }

/**
 * T363: why a readout value is not a plain figure: the closed set of [ReadoutValue.reason] and [Readout.dimReason]
 * ([code] is what `GET /readout` reports; before T363 these were free strings). The phone's glance view gives each a
 * sentence (`app/help/ReasonText.kt`, its test fails on a reason with none) and [ReadoutReasons] says which one a value
 * shows.
 */
enum class ReadoutReason(val code: String, val kind: ReasonKind) {
    /** Switched off in the readout's settings: not drawn. */
    OFF("off", ReasonKind.NONE),

    /** No fix for the gap, or none yet. */
    NO_FIX("no fix", ReasonKind.MISSING),

    /** T362: a fix came after a gap; dimmed until a second within the gap of it ([DimLatch]). */
    RESUMING("resuming", ReasonKind.STALE),

    /** T362: the activity changed and no fix came since. */
    ACTIVITY("activity", ReasonKind.STALE),

    /** T362: the last height held across a clock going back ([HeldHeight]); T192: the climb held through a gap. */
    HELD("held", ReasonKind.STALE),

    NO_HEIGHT_YET("no height yet", ReasonKind.MISSING),
    NO_SPEED_YET("no speed yet", ReasonKind.MISSING),

    /** A fix but no movement since the start: no course to show; at rest. */
    NO_COURSE_YET("no course yet", ReasonKind.AT_REST),

    /** Standing still: the speed's dash, the climb's 0 standing, the course held from the last movement (T363). */
    STILL("still", ReasonKind.AT_REST),

    /** The climb's 0 while moving: a figure. */
    NEAR_ZERO("near zero", ReasonKind.NONE),

    /** T250: the barometer and the GNSS disagree; the climb a dash until they agree. */
    CONTRADICTION("contradiction", ReasonKind.STALE),

    /** T250: the climb's window filling. */
    FILLING("filling", ReasonKind.MISSING),

    /** T250: the climb too uncertain to tell from level. */
    UNCERTAIN("uncertain", ReasonKind.MISSING),

    /** T257: no pressure sample yet. */
    NO_SAMPLES("no samples", ReasonKind.MISSING),

    /** T257: no pressure sample for the staleness window. */
    SILENT("silent", ReasonKind.STALE),

    /** T257: the fix gave no accuracy. */
    NOT_GIVEN("not given", ReasonKind.MISSING),

    /**
     * T384: no GPS fix and the GPS is searching for one (the phone's own receiver, read beside the estimator, which does
     * not know it): [ReadoutReasons.searching] puts it in place of [NO_FIX]; the estimator never gives it.
     */
    SEARCHING("searching", ReasonKind.MISSING),
    ;

    companion object {
        fun of(code: String?): ReadoutReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * T363: what a drawn readout value says beyond its figure (pure; `ReadoutReasonsTest`): the reason it shows ([of]), and
 * whether the bar marks it with a small "?" ([marked]). The owner's answer of 2026-11-23: the reason shows in the glance
 * view under the value, and the bar's value carries a "?" while it is not normal (missing, held or stale, never a value
 * simply at rest, such as a speed standing still).
 */
object ReadoutReasons {
    /** The dims' own reasons: a dimmed value shows the dim's reason, not the one its state had before the gap. */
    val DIM: Set<ReadoutReason> = setOf(ReadoutReason.NO_FIX, ReadoutReason.RESUMING, ReadoutReason.ACTIVITY, ReadoutReason.HELD)

    /**
     * The reason [v] shows, [dimReason] the readout's ([Readout.dimReason]), or null for a plain figure and a value
     * switched off: null [v] (before the first readout) reads as no fix; a dimmed value its dim's reason (a held climb or
     * height `held`); the course held from the last movement `still`; a dash, a "?" or a value not drawn its own reason.
     */
    fun of(v: ReadoutValue?, dimReason: ReadoutReason?): ReadoutReason? {
        if (v == null) return ReadoutReason.NO_FIX
        if (v.reason == ReadoutReason.OFF) return null
        if (v.dimmed) return if (v.reason == ReadoutReason.HELD) ReadoutReason.HELD else dimReason ?: v.reason?.takeIf { it in DIM } ?: ReadoutReason.NO_FIX
        return when (v.state) {
            ValueState.SHOWN -> if (v.entry == ReadoutEntry.COURSE && v.frozen) ReadoutReason.STILL else null
            ValueState.ZERO -> null
            else -> v.reason ?: ReadoutReason.NO_FIX
        }
    }

    /**
     * T384: the reason [reason] reads while the GPS [searching] for a fix: [ReadoutReason.SEARCHING] in place of
     * [ReadoutReason.NO_FIX] (the same "no fix", with what is being done about it) and of [ReadoutReason.RESUMING] (a
     * network fix after the gap is not GPS coming back: the emulator showed "GPS just came back" beside "GPS searching"),
     * every other reason as it is.
     */
    fun searching(reason: ReadoutReason?, searching: Boolean): ReadoutReason? =
        if (searching && reason in SEARCH_REPLACES) ReadoutReason.SEARCHING else reason

    /** T384: the reasons [searching] replaces. */
    val SEARCH_REPLACES: Set<ReadoutReason> = setOf(ReadoutReason.NO_FIX, ReadoutReason.RESUMING)

    /** T384: every (entry, reason) a drawn value can show while the GPS searches: [POSSIBLE] with "no fix" and "resuming" read as [ReadoutReason.SEARCHING]. */
    val WHILE_SEARCHING: Set<Pair<ReadoutEntry, ReadoutReason>> get() =
        POSSIBLE.filter { it.second in SEARCH_REPLACES }.map { it.first to ReadoutReason.SEARCHING }.toSet()

    /** The bar marks the value: its reason says the data is missing or stale, and the value does not read "?" already. */
    fun marked(reason: ReadoutReason?, readsUnknown: Boolean): Boolean =
        reason != null && !readsUnknown && (reason.kind == ReasonKind.MISSING || reason.kind == ReasonKind.STALE)

    /**
     * Every (entry, reason) a drawn value can show ([of]), for the sentence table's tests: `ReadoutReasonsTest` reaches
     * each with a fixture (and every state in [STATES]), and the phone's `ReasonTextTest` finds a sentence for each. The
     * sentence depends on the entry and the reason, whatever the state (a dimmed figure, a held "?" and a held dash in a
     * gap all say "no fix").
     */
    val POSSIBLE: Set<Pair<ReadoutEntry, ReadoutReason>> = buildSet {
        val r = ReadoutReason.entries.filter { it.kind != ReasonKind.NONE }
        fun on(e: ReadoutEntry, vararg rs: ReadoutReason) { for (x in rs) { require(x in r); add(e to x) } }
        val dims = arrayOf(ReadoutReason.NO_FIX, ReadoutReason.RESUMING, ReadoutReason.ACTIVITY)
        on(ReadoutEntry.ELEVATION, *dims, ReadoutReason.HELD, ReadoutReason.NO_HEIGHT_YET)
        on(ReadoutEntry.CLIMB, *dims, ReadoutReason.HELD, ReadoutReason.NO_HEIGHT_YET, ReadoutReason.FILLING, ReadoutReason.UNCERTAIN, ReadoutReason.CONTRADICTION)
        // An activity change clears the fixes, so the speed and the accuracy read "no fix" then, not dimmed.
        on(ReadoutEntry.SPEED, ReadoutReason.NO_FIX, ReadoutReason.RESUMING, ReadoutReason.NO_SPEED_YET, ReadoutReason.STILL)
        on(ReadoutEntry.COURSE, *dims, ReadoutReason.NO_COURSE_YET, ReadoutReason.STILL)
        on(ReadoutEntry.PRESSURE, ReadoutReason.NO_SAMPLES, ReadoutReason.SILENT)
        on(ReadoutEntry.ACCURACY, ReadoutReason.NO_FIX, ReadoutReason.RESUMING, ReadoutReason.NOT_GIVEN)
        on(ReadoutEntry.FIX_AGE, ReadoutReason.NO_FIX)
    }

    /** Every state each entry can be in as the estimator gives it (`ReadoutReasonsTest` reaches each with a fixture). */
    val STATES: Map<ReadoutEntry, Set<ValueState>> = mapOf(
        ReadoutEntry.ELEVATION to setOf(ValueState.SHOWN, ValueState.HIDDEN),
        ReadoutEntry.CLIMB to setOf(ValueState.SHOWN, ValueState.ZERO, ValueState.UNKNOWN, ValueState.DASH, ValueState.HIDDEN),
        ReadoutEntry.SPEED to setOf(ValueState.SHOWN, ValueState.DASH, ValueState.HIDDEN),
        ReadoutEntry.COURSE to setOf(ValueState.SHOWN, ValueState.HIDDEN),
        ReadoutEntry.PRESSURE to setOf(ValueState.SHOWN, ValueState.DASH, ValueState.HIDDEN),
        ReadoutEntry.ACCURACY to setOf(ValueState.SHOWN, ValueState.DASH, ValueState.HIDDEN),
        ReadoutEntry.FIX_AGE to setOf(ValueState.SHOWN, ValueState.HIDDEN),
    )
}

/**
 * One value of the readout (T160). [value] and [sigma] (1-sigma) are SI: speed m/s, climb m/s, course degrees true,
 * elevation metres above mean sea level (EGM2008); [ReadoutText] turns them into text in the user's units.
 * [source] says where the value came from: speed `fix` (the fix's own speed), `route` (distance along the navigated
 * line over a long window) or `positions` (distance over time over at least 30 s, for fixes that carry no speed);
 * course `fix` or `positions`; climb and elevation `barometer` (fused with GNSS and the DEM), `gnss`, `gnss+dem` or
 * `dem`. [dimmed]: in a gap of the fix stream (or, for climb and elevation, with no barometer to carry them through
 * it); [frozen]: the course held from the last movement while standing still, or (T192) the climb held from the last
 * fix through a gap of the fixes.
 */
data class ReadoutValue(
    val entry: ReadoutEntry,
    val state: ValueState,
    val value: Double? = null,
    val sigma: Double? = null,
    val source: String? = null,
    val dimmed: Boolean = false,
    val frozen: Boolean = false,
    val reason: ReadoutReason? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.code, "value" to value?.let { r3(it) }, "sigma" to sigma?.let { r3(it) }, "source" to source,
        "dimmed" to dimmed, "frozen" to frozen, "reason" to reason?.code,
    )
}

/**
 * The barometer's part ([ReadoutEstimator.readout]): `off` (the mode Never, or Automatic in road, rail, air), `none` (no
 * samples; T250: or silent past [ElevationFilter.BARO_FRESH_MS], the height from the GNSS and the DEM), `calibrating`,
 * `in use`, `suspect` (out for a while: [BarometerStatus.reason]).
 */
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
    // T197: the known height the user set ([ReadoutEstimator.pin]) while it holds; "pinned", not "calibrated", which
    // is the station prior's state ([BarometerState.CALIBRATING]).
    /** When the height was set (the inputs' clock), null while no pin holds. */
    val pinnedAt: Long? = null,
    /** The place it was set from; null for a typed height (or no pin). */
    val pinnedName: String? = null,
    val pinnedSigmaM: Double? = null,
    val pinnedEleM: Double? = null,
    /** A pin would be kept now (the barometer fused): what the phone offers "I am at" on. */
    val pinnable: Boolean = false,
    /** Why the last pin ended (`sigma`, `barometer`, `disagreement`, `replaced`), null before any or while one holds. */
    val pinEnd: String? = null,
    /** T250: the mode ([BarometerMode.code]: `auto`, `always`, `never`). */
    val mode: String = BarometerMode.AUTO.code,
    /**
     * T250: why the barometer does not feed the elevation and the climb now, null while it does (or calibrates):
     * `setting` (Never), `activity` (Automatic and an activity whose air does not follow the height: flying, road,
     * rail), `disagreement`, `speed`, `jitter` (out for a while, [reason]), `no samples`, `silent`.
     */
    val out: String? = null,
) {
    val pinned: Boolean get() = pinnedAt != null

    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.code, "mode" to mode, "out" to out, "reason" to reason, "offsetM" to offsetM?.let { r3(it) },
        "offsetSigmaM" to offsetSigmaM?.let { r3(it) }, "pressureHPa" to pressureHPa?.let { r3(it) },
        // T197
        "pinned" to pinned, "pinnedAt" to pinnedAt, "pinnedName" to pinnedName, "pinnedSigmaM" to pinnedSigmaM?.let { r3(it) },
        "pinnedEleM" to pinnedEleM?.let { r3(it) }, "pinnable" to pinnable, "pinEnd" to pinEnd,
    )
}

/**
 * T197: what [ReadoutEstimator.pin] did: [held] when the barometer keeps it (else applied once as a height, [reason]
 * says why not kept); [movedM] how far the height moved (the known height minus the estimate before it).
 */
data class PinResult(val held: Boolean, val movedM: Double?, val reason: String?) {
    fun json(): Map<String, Any?> = linkedMapOf("held" to held, "movedM" to movedM?.let { r3(it) }, "reason" to reason)
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
    /** Registry ids (`sources.json`) of the data sources that contributed: [STATION_SOURCE_ID] once a weather station's pressure was used. */
    val provenance: List<String> = emptyList(),
    /** T177: the climb window in seconds and how much of it the mean covers so far; null in the air (a lag, no window). */
    val climbWindowS: Int? = null,
    val climbFilledS: Double? = null,
    /**
     * T257: the last barometer sample (hPa, [ReadoutValue.source] `sensor` or `replay`; a dash once it is older than the
     * barometer's staleness window), the last fix's horizontal accuracy (m) and its age (s). Hidden while switched off or
     * with nothing to show; the phone's bar draws the pressure from its own last sample ([PressureSample]s reach the
     * estimator only once a fix has set the clock).
     */
    val pressure: ReadoutValue = ReadoutValue(ReadoutEntry.PRESSURE, ValueState.HIDDEN, reason = ReadoutReason.NO_SAMPLES),
    val accuracy: ReadoutValue = ReadoutValue(ReadoutEntry.ACCURACY, ValueState.HIDDEN, reason = ReadoutReason.NO_FIX),
    val fixAge: ReadoutValue = ReadoutValue(ReadoutEntry.FIX_AGE, ValueState.HIDDEN, reason = ReadoutReason.NO_FIX),
    /**
     * T362: the elapsed time the readout was read at ([ReadoutEstimator.readout]'s `elapsedNowMs`; the input clock when
     * none is given), why the values are dimmed for the fix stream ([dimReason]: `no fix`, `resuming`, `activity`; null
     * when they are not), what went wrong lately ([recent], [RecentFaults]) and the last restart ([lastRestart]).
     */
    val elapsedMs: Long = timeMs,
    val dimReason: ReadoutReason? = null,
    val recent: List<ReadoutFault> = emptyList(),
    val lastRestart: ReadoutFault? = null,
) {
    operator fun get(entry: ReadoutEntry): ReadoutValue = when (entry) {
        ReadoutEntry.SPEED -> speed
        ReadoutEntry.CLIMB -> climb
        ReadoutEntry.COURSE -> course
        ReadoutEntry.ELEVATION -> elevation
        ReadoutEntry.PRESSURE -> pressure
        ReadoutEntry.ACCURACY -> accuracy
        ReadoutEntry.FIX_AGE -> fixAge
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "timeMs" to timeMs, "activity" to activity.code,
        "speed" to speed.json(), "climb" to climb.json(), "course" to course.json(), "elevation" to elevation.json(),
        "pressure" to pressure.json(), "accuracy" to accuracy.json(), "fixAge" to fixAge.json(), // T257
        "gapS" to gapS?.let { r3(it) }, "heights" to heights.json(), "barometer" to barometer.json(),
        "fixSpeedMps" to fixSpeedMps?.let { r3(it) }, "routeSpeedMps" to routeSpeedMps?.let { r3(it) },
        "counters" to counters, "provenance" to provenance,
        "climbWindowS" to climbWindowS, "climbFilledS" to climbFilledS?.let { r3(it) },
        // T362
        "dimReason" to dimReason?.code, "recent" to recent.map { it.json(elapsedMs) }, "lastRestart" to lastRestart?.json(elapsedMs),
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
 * - **Elevation and climb** come from [ElevationFilter]: the barometer by [ReadoutOptions.barometer] (T250's
 *   [BarometerMode]: Automatic when the profile's elevation is `fused`, foot, bike, boat, never road, rail or air;
 *   Always whatever the activity and with none of the rules below; Never not at all); GNSS gated and decimated; the DEM
 *   on foot (slope under 20°, no structure near, one update per 150 m moved); a weather station's pressure as a prior
 *   ([onStationPressure]). In Automatic the barometer is dropped while suspect: GNSS speed over 7 m/s or jitter over
 *   1.5 m peak to peak (0.75 m RMS about a line) in 10 s on foot, reopening 60 s after the last trigger; T250: its
 *   60 s climb and the GNSS one differing by over 1 m/s in one window ([ReadoutTuning.DISAGREE_MPS]) puts it out
 *   until the two agree within [ReadoutTuning.AGREE_MPS] for [ReadoutTuning.AGREE_WINDOWS] windows in a row, the
 *   vertical speed loosened so the GNSS takes it over at once and the climb a dash (`contradiction`) meanwhile. It
 *   reopens with its offset taken afresh from the height.
 * - **A new barometer offset** (T204): a pressure step between consecutive samples larger than the activity's climb
 *   cap allows ([ReadoutTuning.climbCapMps] times the interval, at most [ReadoutTuning.STEP_MAX_DT_S], plus
 *   [ReadoutTuning.STEP_MARGIN_M]), or a change of the sample's [PressureSample.source] (a replay's column ending and the
 *   sensor taking over, the sensor back after a silence), is not a climb: the offset is taken afresh from the height
 *   (the GNSS and DEM heights the filter holds), the climb window restarts, a pin ends, and the counters
 *   `newOffsets`, `newOffsetsSource` and `newOffsetsStep` say so. The bar never shows the step as a rate.
 * - **A silent barometer** (T204): once it has been silent past its staleness window ([ElevationFilter.BARO_FRESH_MS])
 *   the height it carried no longer gates the GNSS and the DEM: a height the gate would reject re-anchors the filter
 *   on it (`baroFallbacks`), so a height the barometer had carried away from the fixes recovers within one fix.
 * - **The climb rate** (T177) on the ground is the filter's vertical speed averaged over the climb window
 *   ([ReadoutOptions.climbWindowS], 30 s to 10 min, 1 min by default; a dash `filling` until
 *   [ReadoutTuning.CLIMB_MIN_FILL_S] or the window is covered, then the mean over what is held); in the air it is
 *   lagged 5 to 10 s ([ReadoutTuning.climbLagS]). T250, what it says it knows: a figure when the estimate is sure of
 *   one; near zero ([ReadoutTuning.climbGate], its band and the uncertainty test scaled with the window) 0
 *   ([ValueState.ZERO]) only when the estimate could have told a climb at the band, moving or standing, else "?"
 *   ([ValueState.UNKNOWN], `uncertain`); "?" while the window fills; a dash only while the sources contradict and the
 *   barometer is out for it. In a gap of the fixes (T192) it holds the value of the last fix, dimmed and `frozen`, and
 *   is "?" (`no fix`) once the climb window (the air: the gap rule) has passed since that fix, whatever the barometer
 *   reads meanwhile.
 * - **A gap** in the fixes (over 5 s or three intervals) dims the values and [Readout.gapS] counts it. T362: gaps are
 *   measured on elapsed time ([onFix]'s `elapsedMs`, [readout]'s `elapsedNowMs`; the input clock when not given, as in
 *   replays and tests), never the fixes' wall-clock times, and the dim leaves only on the second fix of a run whose
 *   fixes came within the gap of each other ([DimLatch]), so fixes in bursts give one steady dim, not a blink per gap.
 *   Every dimmed elevation carries a reason ([Readout.dimReason]: `no fix`, `resuming`, `activity`; or `held`).
 * - **A jump** (T362, moved here from the phone's `LiveReadout`): a step over [ReadoutTuning.JUMP_M] faster than the
 *   profile's speed bound ([isJump]) restarts the position, speed, course and climb window, and keeps the elevation
 *   filter and the barometer offset: the height is not lost to a network fix far off. The first GNSS or DEM height
 *   after it more than [ReadoutTuning.JUMP_REANCHOR_M] from the kept one re-anchors the height on it (`jumpReanchors`).
 * - **A clock going back** (an input over [CLOCK_BACK_MS] older than the latest) restarts everything, the elevation
 *   filter too (it cannot step back in time); T362: the last height is held dimmed, reason `held` ([HeldHeight]), until
 *   the new filter has one or [ReadoutTuning.HELD_HEIGHT_MS] pass, then "no height yet".
 * - **What went wrong lately** (T362, [RecentFaults], [Readout.recent]): each gap with its length, each restart with its
 *   cause, the barometer going silent, a fix skipped as late; each with its elapsed time.
 * - **A known height** (T197, [pin]): a summit, a saddle or a sign the user stands at replaces the elevation; with the
 *   barometer fused it holds as a pin ([BarometerStatus.pinnedAt]) until the offset's own random walk has doubled its
 *   sigma, GNSS and DEM heights set aside meanwhile ([ElevationFilter]).
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

    /** The options; T250: a change of the barometer's mode takes effect at once (a disagreement's hold ends with it). */
    var options: ReadoutOptions = options
        set(v) {
            val modeChanged = v.barometer != field.barometer
            field = v
            if (modeChanged) {
                endContradiction()
                suspectUntilMs = null
                suspectReason = null
                windowStart = null
                disagreeRun = 0
                applyBarometerSwitch(null)
            }
        }
    val activity: Activity get() = profile.activity

    private var filter = ElevationFilter(profile.activity)

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
    // T250: out for a disagreement with the GNSS (Automatic): held until AGREE_WINDOWS windows agree in a row.
    private var contradicted = false
    private var agreeRun = 0
    private var climbShown = false
    private var climbResolves = false // T250: the estimate could tell a climb at the band (0 is a value, not "?")
    private var climbAtFix: ReadoutValue? = null // T192: the climb at the last fix, held through a gap
    // T204: the last pressure sample (its time, pressure altitude and source), for the new-offset rule.
    private var lastSampleMs: Long? = null
    private var lastSampleAltM = 0.0
    private var lastSampleSource: PressureSampleSource? = null
    private var smoothV = 0.0
    private var smoothAt: Long? = null

    // T177: the climb window: the running integral of the vertical speed (m), sampled at most once a second, the last
    // ReadoutTuning.CLIMB_KEEP_MS of it whatever the window, so a change of the window takes effect at once.
    private val climbHist = ArrayDeque<Pair<Long, Double>>()
    private var climbIntegralM = 0.0
    private var lastVerticalMps = 0.0
    private var climbMeanMps = 0.0
    private var climbSpanS = 0.0
    private var climbSigmaMps: Double? = null

    /** Called on every clock-back restart with the input's source ("fix" or "pressure:sensor"), its time, the latest time held and the elapsed time; the app logs it. */
    var onClockBack: ((source: String, tMs: Long, latestMs: Long, elapsedMs: Long) -> Unit)? = null

    private val counts = linkedMapOf(
        "fixes" to 0L, "pressureSamples" to 0L, "lateInputs" to 0L, "spikesDropped" to 0L,
        "suspects" to 0L, "reopens" to 0L, "demTries" to 0L, "clockResets" to 0L,
        "newOffsets" to 0L, "newOffsetsSource" to 0L, "newOffsetsStep" to 0L, // T204
        "disagreements" to 0L, "agreements" to 0L, // T250: out for a disagreement, back after agreeing
        "jumpResets" to 0L, "jumpReanchors" to 0L, "heldHeights" to 0L, // T362
    )

    // T362: the dim on elapsed time, the height held across a clock going back, the jump's last position, the record.
    private val latch = DimLatch()
    private var held: HeldHeight? = null
    private var lastPos: Triple<Double, Double, Long>? = null
    /** The elapsed time of the latest input (fix or sample), for a restart's time. */
    private var lastElapsedIn: Long? = null
    /** A jump happened and no GNSS or DEM height came since: the next one far from the kept height re-anchors it. */
    private var jumpPending = false
    /** [setProfile] cleared the fixes and no fix came since: the values dim with reason `activity`. */
    private var profileRestart = false
    private val faults = RecentFaults()
    private var lastRestart: ReadoutFault? = null
    /** The barometer sample time a silence was last noted for, so one silence is one fault. */
    private var silenceNotedFor: Long? = null

    /** T362: how many jumps restarted the position (the phone's `LiveReadout` reads it to drop its route hint). */
    val jumpResets: Long get() = counts["jumpResets"] ?: 0L

    private fun restart(cause: String, elapsedMs: Long?, detail: Double? = null) {
        val f = ReadoutFault(ReadoutFault.RESTART, elapsedMs ?: lastElapsedIn ?: 0L, cause = cause, detail = detail)
        faults.add(f)
        lastRestart = f
    }

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
        climbAtFix = null
        profileRestart = true // T362: dims with a reason until the next fix
        restart(ReadoutFault.CAUSE_ACTIVITY, null)
        endContradiction() // T250: the windows restart with the activity
        windowStart = null
        disagreeRun = 0
        applyBarometerSwitch(null)
    }

    /** T250: the mode and the activity let the barometer feed the estimate (Automatic: the profile fuses it). */
    private val baroAllowed: Boolean get() = when (options.barometer) {
        BarometerMode.NEVER -> false
        BarometerMode.ALWAYS -> true
        BarometerMode.AUTO -> profile.elevation == ElevationSource.FUSED
    }

    /** T250: the suspect rules (speed, jitter, disagreement) apply: Automatic only; Always uses it whatever they say. */
    private val baroRules: Boolean get() = options.barometer == BarometerMode.AUTO

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
        if (!baroAllowed || !baroRules) return
        if (contradicted) return // T250: already out until the sources agree; the agreement windows carry on
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
     * T362: [elapsedMs] is the elapsed realtime the fix came at (the phone's `SystemClock.elapsedRealtime`), passed beside
     * the fix, whose own time is the wall clock's; gaps and the dim are measured on it. Replays and tests may leave it
     * out: the fix's time stands in.
     */
    fun onFix(fix: LocationFix, routeAlongM: Double? = null, elapsedMs: Long = fix.time) {
        clockCheck(fix.time, elapsedMs, "fix")
        lastElapsedIn = elapsedMs
        if (isJump(fix)) {
            val l = lastPos!!
            restartPosition()
            jumpPending = true
            bump("jumpResets")
            restart(ReadoutFault.CAUSE_JUMP, elapsedMs, TrackStats.distanceM(l.first, l.second, fix.lat, fix.lon))
        }
        val last = fixes.lastOrNull()
        if (last != null && fix.time <= last.t) {
            bump("lateInputs")
            faults.add(ReadoutFault(ReadoutFault.LATE_FIX, elapsedMs))
            return
        }
        bump("fixes")
        lastPos = Triple(fix.lat, fix.lon, fix.time)
        profileRestart = false
        val interval = latch.onFix(elapsedMs, gapMs)
        if (interval != null && interval > gapMs) faults.add(ReadoutFault(ReadoutFault.GAP, elapsedMs - interval, lengthMs = interval))
        val sampleMs = lastSampleMs
        if (baroAllowed && sampleMs != null && fix.time - sampleMs > ElevationFilter.BARO_FRESH_MS && silenceNotedFor != sampleMs) {
            silenceNotedFor = sampleMs
            faults.add(ReadoutFault(ReadoutFault.BAROMETER_SILENT, elapsedMs, lengthMs = fix.time - sampleMs))
        }
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
            if (vAcc <= MAX_VACC_M) { afterJump(ele); filter.gnss(fix.time, ele, vAcc) }
            disagreement(fix.time, ele, vAcc)
        }
        demAtLastFix = dem?.elevation(fix.lat, fix.lon)?.takeIf { it.isFinite() }
        demUpdate(s, v)
        smooth(fix.time)
        climbAtFix = climbValue(fix.time, heightDim = false)
        if (filter.heightKnown) held = null // T362: the new filter has a height: the held one goes
    }

    /**
     * T362: [fix] is a jump from the last fix: over [ReadoutTuning.JUMP_M] away, faster than the profile's speed bound
     * (or not later on the fixes' clock). Pure; the phone asks it before projecting the fix on a route.
     */
    fun isJump(fix: LocationFix): Boolean {
        val l = lastPos ?: return false
        val d = TrackStats.distanceM(l.first, l.second, fix.lat, fix.lon)
        val dt = (fix.time - l.third) / 1000.0
        return d > ReadoutTuning.JUMP_M && (dt <= 0 || d / dt > profile.speedBoundMps)
    }

    /** T362: a jump restarts the horizontal state (fixes, speed, course, the climb window); the elevation filter stays. */
    private fun restartPosition() {
        fixes.clear()
        moving = false
        lastCourse = null
        lastDemAt = null
        windowStart = null
        disagreeRun = 0
        climbAtFix = null
        smoothAt = null
        smoothV = 0.0
        climbShown = false
        climbResolves = false
        clearClimbWindow()
    }

    /**
     * T362: the first GNSS or DEM height [eleM] after a jump: one over [ReadoutTuning.JUMP_REANCHOR_M] from the kept
     * height re-anchors the height on it (a fresh filter, the barometer's offset taken afresh at its next sample), as the
     * gate would otherwise reject it for a long while; a nearer one is used as any other.
     */
    private fun afterJump(eleM: Double) {
        if (!jumpPending) return
        jumpPending = false
        if (!filter.heightKnown || abs(eleM - filter.heightM) <= ReadoutTuning.JUMP_REANCHOR_M) return
        bump("jumpReanchors")
        filter = ElevationFilter(activity)
        applyBarometerSwitch(null)
        smoothAt = null
        smoothV = 0.0
        climbShown = false
        climbResolves = false
        clearClimbWindow()
    }

    /**
     * One barometer sample (hPa). Samples older than the filter's time are applied at its time and counted. T204: a
     * step past the activity's climb cap or a change of [PressureSample.source] is a new offset ([newOffset]).
     */
    fun onPressure(sample: PressureSample, elapsedMs: Long = sample.timeMs) {
        if (!(sample.hPa in 300.0..1100.0)) return
        bump("pressureSamples")
        clockCheck(sample.timeMs, elapsedMs, "pressure:" + sample.source.name.lowercase())
        lastElapsedIn = elapsedMs
        val lastT = baro.lastOrNull()?.first
        if (lastT != null && sample.timeMs < lastT) bump("lateInputs")
        lastPressureHPa = sample.hPa
        val alt = Altimeter.pressureAltitudeM(sample.hPa)
        newOffsetReason(sample, alt)?.let { newOffset(it) }
        lastSampleMs = sample.timeMs
        lastSampleAltM = alt
        lastSampleSource = sample.source
        baro.addLast(sample.timeMs to alt)
        while (baro.size > 2 && sample.timeMs - baro.first().first > JITTER_WINDOW_MS) baro.removeFirst()
        if (activity == Activity.FOOT) jitter(sample.timeMs)
        applyBarometerSwitch(sample.timeMs)
        applyPrior(sample.timeMs)
        filter.barometer(sample.timeMs, alt)
        smooth(sample.timeMs)
    }

    /**
     * T204: why [sample] (at pressure altitude [altM]) starts a new offset: `source` when its source differs from the
     * last sample's, `step` when the pressure altitude moved further than the activity's climb cap allows over the
     * interval (capped at [ReadoutTuning.STEP_MAX_DT_S], so a sample after a long silence is judged as after 10 s) plus
     * the sensor's noise margin; null otherwise.
     */
    private fun newOffsetReason(sample: PressureSample, altM: Double): String? {
        val prevMs = lastSampleMs ?: return null
        if (sample.source != lastSampleSource) return "source"
        val dtS = ((sample.timeMs - prevMs) / 1000.0).coerceIn(0.0, ReadoutTuning.STEP_MAX_DT_S)
        val allowed = ReadoutTuning.climbCapMps(activity) * dtS + ReadoutTuning.STEP_MARGIN_M
        return if (abs(altM - lastSampleAltM) > allowed) "step" else null
    }

    /**
     * T204: a new barometer offset ([why] `source` or `step`): the offset is taken afresh from the height at the next
     * barometer update (this sample), so the height does not move; the climb window, the held climb, the jitter and
     * disagreement windows restart (the step is never read as a rate), and a pin ends.
     */
    private fun newOffset(why: String) {
        bump("newOffsets")
        bump(if (why == "source") "newOffsetsSource" else "newOffsetsStep")
        filter.newOffset()
        baro.clear()
        windowStart = null
        disagreeRun = 0
        agreeRun = 0 // T250: the agreement windows restart too
        climbShown = false
        climbResolves = false
        // T192's gap rule still holds: in a gap the climb is held from here, not the new offset's live value (T250: "?").
        climbAtFix = climbAtFix?.takeIf { it.state != ValueState.HIDDEN && !contradicted }?.let { ReadoutValue(ReadoutEntry.CLIMB, ValueState.UNKNOWN, source = it.source, reason = ReadoutReason.FILLING) } ?: climbAtFix
        clearClimbWindow()
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

    /**
     * T197: the user stands at a known height: [eleM] (m, EGM2008) with 1-sigma [sigmaM] ([KnownHeights.sigmaFor]: 3 m
     * for a tagged summit, 5 m for a saddle, a pass or a typed sign), named [name] (null for a typed height), at [tMs]
     * on the inputs' clock. The height is replaced by it; with the barometer fused it is kept as a pin
     * ([BarometerStatus.pinnedAt]) that holds while the offset's sigma stays under twice [sigmaM] ([ElevationFilter]).
     * Without the barometer it is applied once and the next fixes take over ([PinResult.held] false).
     */
    fun pin(name: String?, eleM: Double, sigmaM: Double, tMs: Long): PinResult {
        require(eleM.isFinite() && eleM in KnownHeights.MIN_ELE_M..KnownHeights.MAX_ELE_M) { "height $eleM m is out of range" }
        require(sigmaM.isFinite() && sigmaM > 0) { "sigma must be positive" }
        val before = filter.heightM.takeIf { filter.heightKnown }
        val held = filter.knownHeight(tMs, eleM, sigmaM, name?.takeIf { it.isNotBlank() })
        val reason = when {
            held -> null
            !baroAllowed -> "barometer off"
            suspectUntilMs != null -> "barometer suspect"
            filter.lastBaroMs == null -> "no barometer"
            else -> "barometer not in use"
        }
        return PinResult(held, before?.let { eleM - it }, reason)
    }

    /**
     * The readout at [nowMs] (normally the latest input's time or the wall clock moved to the fixes' clock): pure, it
     * changes nothing. T362: [elapsedNowMs] is the elapsed realtime now, on the clock of [onFix]'s `elapsedMs` (the input
     * clock when left out); the gap, the dim, the fix's age and the climb's hold are measured on it.
     */
    fun readout(nowMs: Long, elapsedNowMs: Long = nowMs): Readout {
        val last = fixes.lastOrNull()
        val gap = if (last == null) null else latch.gapMs(elapsedNowMs, gapMs)
        val gapS = gap?.let { it / 1000.0 }
        // T362: dimmed in a gap and after one until a second fix of a run ([DimLatch]); after an activity change until a fix.
        val dimReason = when {
            last != null -> latch.reason(elapsedNowMs, gapMs)
            profileRestart && latch.lastFixElapsedMs != null -> ReadoutReason.ACTIVITY
            else -> DimLatch.NO_FIX
        }
        val dim = dimReason != null
        val sinceFixMs = latch.lastFixElapsedMs?.let { elapsedNowMs - it }
        val fixSpeed = last?.let { fixSpeed(it.t) }
        val pos = if (fixSpeed == null) last?.let { positionSpeed(it.t) } else null
        val route = last?.let { routeSpeed(it.t) }
        val chosen = route?.let { Triple(it.first, it.second, "route") }
            ?: fixSpeed?.let { Triple(it.first, it.second, "fix") }
            ?: pos?.let { Triple(it.first, it.second, "positions") }

        val speed = when {
            !options.speed.shown -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            last == null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, reason = ReadoutReason.NO_FIX)
            !moving && chosen != null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.DASH, source = chosen.third, dimmed = dim, reason = ReadoutReason.STILL)
            chosen == null -> ReadoutValue(ReadoutEntry.SPEED, ValueState.HIDDEN, dimmed = dim, reason = ReadoutReason.NO_SPEED_YET)
            else -> ReadoutValue(ReadoutEntry.SPEED, ValueState.SHOWN, chosen.first, chosen.second, chosen.third, dim)
        }
        val course = when {
            !options.course.shown -> ReadoutValue(ReadoutEntry.COURSE, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            lastCourse == null -> ReadoutValue(ReadoutEntry.COURSE, ValueState.HIDDEN, dimmed = dim, reason = if (last == null) ReadoutReason.NO_FIX else ReadoutReason.NO_COURSE_YET)
            else -> ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, lastCourse, lastCourseSigma, lastCourseSource, dim, frozen = !moving)
        }

        val fused = filter.fused(nowMs)
        val heightSource = heightSource(nowMs)
        val heightDim = dim && !fused
        val heldHeight = held?.takeIf { !filter.heightKnown && it.holds(elapsedNowMs) }
        val elevation = when {
            !options.elevation.shown -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            heldHeight != null -> heldHeight.value() // T362: held across a clock going back, dimmed, `held`
            !filter.heightKnown -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.HIDDEN, reason = ReadoutReason.NO_HEIGHT_YET)
            else -> ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, filter.heightM, filter.heightSigmaM, heightSource, heightDim, reason = if (heightDim) dimReason else null)
        }
        val windowed = climbWindowed
        val held = climbAtFix
        // T192: in a gap of the fixes the climb holds what it read at the last fix (dimmed, frozen) and is a dash once
        // the climb window has passed since that fix; it never decays toward zero on inputs that are not fixes.
        val climb = when {
            gap == null || last == null || held == null || !options.climb.shown || held.state == ValueState.HIDDEN -> climbValue(nowMs, heightDim)
            (sinceFixMs ?: 0L) > climbHoldMs -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.UNKNOWN, source = held.source, dimmed = true, reason = ReadoutReason.NO_FIX) // T250: "?", not a dash
            else -> held.copy(dimmed = true, frozen = true, reason = held.reason ?: ReadoutReason.HELD)
        }

        val mode = options.barometer.code
        val baroState = when {
            !baroAllowed -> BarometerStatus(BarometerState.OFF, out = if (options.barometer == BarometerMode.NEVER) "setting" else "activity")
            suspectUntilMs != null -> BarometerStatus(BarometerState.SUSPECT, suspectReason, pressureHPa = lastPressureHPa, out = suspectReason)
            filter.lastBaroMs == null -> BarometerStatus(BarometerState.NONE, out = "no samples")
            // T250 (T204's follow-up): silent past its staleness window, the height comes from the GNSS and the DEM: not "in use".
            filter.lastBaroMs?.let { nowMs - it > ElevationFilter.BARO_FRESH_MS } == true ->
                BarometerStatus(BarometerState.NONE, pressureHPa = lastPressureHPa, out = "silent")
            !filter.offsetKnown || filter.offsetSigmaM > CALIBRATED_SIGMA_M ->
                BarometerStatus(BarometerState.CALIBRATING, offsetM = filter.offsetM.takeIf { filter.offsetKnown }, offsetSigmaM = filter.offsetSigmaM.takeIf { filter.offsetKnown }, pressureHPa = lastPressureHPa)
            else -> BarometerStatus(BarometerState.IN_USE, offsetM = filter.offsetM, offsetSigmaM = filter.offsetSigmaM, pressureHPa = lastPressureHPa)
        }.let { b ->
            // T197: the pin while it holds at nowMs (the offset's sigma grown to now), else why the last one ended.
            val pn = filter.pin?.takeIf { filter.pinHolds(nowMs) }
            b.copy(mode = mode, pinnedAt = pn?.tMs, pinnedName = pn?.name, pinnedSigmaM = pn?.sigmaM, pinnedEleM = pn?.eleM, pinnable = fused,
                pinEnd = if (pn != null) null else if (filter.pin != null) (if (fused) "sigma" else "barometer") else filter.pinEnd)
        }
        // T257: the pressure as last read, the fix's accuracy and its age.
        val sampleMs = lastSampleMs
        val pressure = when {
            !options.pressure.shown -> ReadoutValue(ReadoutEntry.PRESSURE, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            lastPressureHPa == null || sampleMs == null -> ReadoutValue(ReadoutEntry.PRESSURE, ValueState.HIDDEN, reason = ReadoutReason.NO_SAMPLES)
            nowMs - sampleMs > ElevationFilter.BARO_FRESH_MS -> ReadoutValue(ReadoutEntry.PRESSURE, ValueState.DASH, source = lastSampleSource?.code, reason = ReadoutReason.SILENT)
            else -> ReadoutValue(ReadoutEntry.PRESSURE, ValueState.SHOWN, lastPressureHPa, source = lastSampleSource?.code)
        }
        val accuracy = when {
            !options.accuracy.shown -> ReadoutValue(ReadoutEntry.ACCURACY, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            last == null -> ReadoutValue(ReadoutEntry.ACCURACY, ValueState.HIDDEN, reason = ReadoutReason.NO_FIX)
            last.hAcc == null || !last.hAcc.isFinite() || last.hAcc <= 0 -> ReadoutValue(ReadoutEntry.ACCURACY, ValueState.DASH, dimmed = dim, reason = ReadoutReason.NOT_GIVEN)
            else -> ReadoutValue(ReadoutEntry.ACCURACY, ValueState.SHOWN, last.hAcc, source = "fix", dimmed = dim)
        }
        val fixAge = when {
            !options.fixAge.shown -> ReadoutValue(ReadoutEntry.FIX_AGE, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            last == null -> ReadoutValue(ReadoutEntry.FIX_AGE, ValueState.HIDDEN, reason = ReadoutReason.NO_FIX)
            else -> ReadoutValue(ReadoutEntry.FIX_AGE, ValueState.SHOWN, ((sinceFixMs ?: (nowMs - last.t)) / 1000.0).coerceAtLeast(0.0), source = "fix") // T362: elapsed
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
        c["pins"] = filter.pins.toLong() // T197
        c["pinsCleared"] = filter.pinsCleared.toLong()
        c["pinReleases"] = filter.pinReleases.toLong()
        c["gnssHeldByPin"] = filter.gnssHeldByPin.toLong()
        c["demHeldByPin"] = filter.demHeldByPin.toLong()
        c["baroFallbacks"] = filter.fallbacks.toLong() // T204
        return Readout(nowMs, activity, speed, climb, course, elevation, gapS, heights, baroState, fixSpeed?.first ?: pos?.first, route?.first, c,
            if (filter.priors > 0) listOf(STATION_SOURCE_ID) else emptyList(),
            climbWindowS = if (windowed) options.climbWindowS else null,
            climbFilledS = if (windowed) minOf(climbSpanS, options.climbWindowS.toDouble()) else null,
            pressure = pressure, accuracy = accuracy, fixAge = fixAge,
            // T362: an ongoing gap is the record's last entry until the fix that ends it records it whole.
            elapsedMs = elapsedNowMs, dimReason = dimReason, lastRestart = lastRestart,
            recent = faults.at(elapsedNowMs, if (gap != null && sinceFixMs != null) ReadoutFault(ReadoutFault.GAP, elapsedNowMs - sinceFixMs, lengthMs = sinceFixMs, ongoing = true) else null))
    }

    /** Where the height comes from at [nowMs]: `barometer` (fused), `dem` (the DEM alone so far), `gnss+dem`, `gnss`. */
    private fun heightSource(nowMs: Long): String = when {
        filter.fused(nowMs) -> "barometer"
        filter.gnssUpdates == 0 && filter.demUpdates > 0 -> "dem"
        filter.lastDemMs?.let { nowMs - it <= DEM_RECENT_MS } == true -> "gnss+dem"
        else -> "gnss"
    }

    /** The climb as the filter and the window give it at [nowMs] (also snapshotted at each fix for a gap, T192). */
    private fun climbValue(nowMs: Long, heightDim: Boolean): ReadoutValue {
        val climbSource = if (filter.fused(nowMs)) "barometer" else "gnss"
        val windowed = climbWindowed
        val climbV = if (windowed) climbMeanMps else smoothV
        val climbSigma = if (windowed) climbSigmaMps ?: filter.verticalSigmaMps else filter.verticalSigmaMps
        // T250: a figure when sure, 0 only for a near zero the estimate could tell, "?" for too little data, a dash
        // only while the barometer and the GNSS contradict and the barometer is out for it.
        return when {
            !options.climb.shown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN, reason = ReadoutReason.OFF)
            !filter.heightKnown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN, reason = ReadoutReason.NO_HEIGHT_YET)
            contradicted -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.DASH, source = climbSource, dimmed = heightDim, reason = ReadoutReason.CONTRADICTION)
            windowed && climbSpanS < minOf(ReadoutTuning.CLIMB_MIN_FILL_S, options.climbWindowS.toDouble()) ->
                ReadoutValue(ReadoutEntry.CLIMB, ValueState.UNKNOWN, climbV, climbSigma, climbSource, heightDim, reason = ReadoutReason.FILLING)
            climbShown -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.SHOWN, climbV, climbSigma, climbSource, heightDim)
            !climbResolves -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.UNKNOWN, climbV, climbSigma, climbSource, heightDim, reason = ReadoutReason.UNCERTAIN)
            moving -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.ZERO, climbV, climbSigma, climbSource, heightDim, reason = ReadoutReason.NEAR_ZERO)
            else -> ReadoutValue(ReadoutEntry.CLIMB, ValueState.ZERO, climbV, climbSigma, climbSource, heightDim, reason = ReadoutReason.STILL)
        }
    }

    /** T192: how long after the last fix a held climb stays: the climb window on the ground, the gap rule in the air. */
    private val climbHoldMs: Long get() = if (climbWindowed) max(options.climbWindowS * 1000L, gapMs) else gapMs

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

    /**
     * The disagreement rule: barometer and GNSS climb over 60 s windows ([ReadoutTuning.DISAGREE_WINDOW_S]). T250, in
     * Automatic only: one window apart by more than [ReadoutTuning.DISAGREE_MPS] puts the barometer out at once
     * ([contradict]); while out the pressure altitude (still read, not applied) and the GNSS keep being compared, and
     * [ReadoutTuning.AGREE_WINDOWS] windows in a row within [ReadoutTuning.AGREE_MPS] bring it back ([agree]). A
     * barometer silent past its staleness window is not compared (its frozen altitude is not a rate).
     */
    private fun disagreement(tMs: Long, ele: Double, vAcc: Double) {
        val baroAlt = filter.lastBaroAltM
        val fresh = lastSampleMs?.let { tMs - it <= ElevationFilter.BARO_FRESH_MS } == true
        if (!baroAllowed || !baroRules || !(filter.baroOpen || contradicted) || baroAlt == null || !fresh || vAcc > ReadoutTuning.DISAGREE_MAX_VACC_M) return
        val w = windowStart
        if (w == null) { windowStart = Triple(tMs, baroAlt, ele); return }
        val span = (tMs - w.first) / 1000.0
        if (span < ReadoutTuning.DISAGREE_WINDOW_S) return
        val rb = (baroAlt - w.second) / span
        val rg = (ele - w.third) / span
        windowStart = Triple(tMs, baroAlt, ele)
        val d = abs(rb - rg)
        if (!contradicted) {
            if (d > ReadoutTuning.DISAGREE_MPS) contradict(tMs) else disagreeRun = 0
        } else if (d <= ReadoutTuning.AGREE_MPS) {
            if (++agreeRun >= ReadoutTuning.AGREE_WINDOWS) agree(tMs)
        } else {
            agreeRun = 0
        }
    }

    /**
     * T250: the barometer and the GNSS disagree: the barometer is out at once (no blending; the owner, 2026-11-07),
     * the vertical speed it carried loosened so the GNSS sets it, the climb's window restarted and the climb a dash
     * while they contradict.
     */
    private fun contradict(tMs: Long) {
        if (suspectUntilMs == null) bump("suspects")
        bump("disagreements")
        contradicted = true
        agreeRun = 0
        disagreeRun = 0
        suspectUntilMs = Long.MAX_VALUE
        suspectReason = "disagreement"
        filter.closeBarometer()
        filter.loosenVertical()
        climbShown = false
        climbResolves = false
        clearClimbWindow()
    }

    /** T250: the two have agreed long enough: the barometer returns, its offset taken afresh from the height. */
    private fun agree(tMs: Long) {
        bump("agreements")
        endContradiction()
        suspectUntilMs = null
        suspectReason = null
        bump("reopens")
        windowStart = null
        clearClimbWindow()
        applyBarometerSwitch(tMs)
    }

    private fun endContradiction() {
        if (contradicted && suspectReason == "disagreement") { suspectUntilMs = null; suspectReason = null }
        contradicted = false
        agreeRun = 0
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
        if (filter.pin != null) return // T197: a set height outranks the DEM while it holds (no tries, no tile reads)
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
        afterJump(h) // T362
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

    /**
     * An input more than [CLOCK_BACK_MS] before the latest one is a clock that went back (a replay run again, a sample
     * stamped on another clock): a jump, so the windows and the elevation filter start afresh at the new time (T161).
     * Without this every later input counts as late, the filter never predicts, the climb stays at zero and the
     * elevation lags.
     */
    private fun clockCheck(tMs: Long, elapsedMs: Long, source: String) {
        val latest = listOfNotNull(fixes.lastOrNull()?.t, baro.lastOrNull()?.first, filter.timeMs).maxOrNull() ?: return
        if (tMs >= latest - CLOCK_BACK_MS) return
        bump("clockResets")
        onClockBack?.invoke(source, tMs, latest, elapsedMs)
        // T362: the last height is held, dimmed, until the new filter has one or the hold passes.
        if (filter.heightKnown) {
            held = HeldHeight(filter.heightM, filter.heightSigmaM, heightSource(latest), elapsedMs)
            bump("heldHeights")
        }
        restart(ReadoutFault.CAUSE_CLOCK, elapsedMs, (latest - tMs) / 1000.0)
        jumpPending = false
        lastPos = null
        fixes.clear()
        baro.clear()
        moving = false
        lastCourse = null
        windowStart = null
        disagreeRun = 0
        lastDemAt = null
        smoothAt = null
        smoothV = 0.0
        climbShown = false
        clearClimbWindow()
        lastSampleMs = null // T204
        lastSampleSource = null
        suspectUntilMs = null
        suspectReason = null
        contradicted = false // T250
        agreeRun = 0
        climbResolves = false
        filter = ElevationFilter(activity)
        applyBarometerSwitch(tMs)
    }

    /** T177: the climb is a window mean on the ground, a lagged value in the air. */
    private val climbWindowed: Boolean get() = activity != Activity.AIR

    private fun clearClimbWindow() {
        climbHist.clear()
        climbIntegralM = 0.0
        lastVerticalMps = 0.0
        climbMeanMps = 0.0
        climbSpanS = 0.0
        climbSigmaMps = null
    }

    /** The climb rate's display lag (the air), its window mean (T177, the ground) and its near-zero gate, after each filter step. */
    private fun smooth(tMs: Long) {
        if (!filter.heightKnown) return
        val at = smoothAt
        val v = filter.verticalMps
        if (at == null) smoothV = v
        else if (tMs > at) {
            val tau = ReadoutTuning.climbLagS(options.climb.damping)
            smoothV += (v - smoothV) * (1 - exp(-(tMs - at) / 1000.0 / tau))
            climbIntegralM += (v + lastVerticalMps) / 2 * ((tMs - at) / 1000.0)
        }
        if (at == null || tMs > at) {
            smoothAt = tMs
            lastVerticalMps = v
            if (climbHist.isEmpty() || tMs - climbHist.last().first >= 1_000) climbHist.addLast(tMs to climbIntegralM)
            while (climbHist.size > 2 && tMs - climbHist[1].first >= ReadoutTuning.CLIMB_KEEP_MS) climbHist.removeFirst()
        }
        if (climbHist.isEmpty()) climbHist.addLast(tMs to climbIntegralM) // T250: a window cleared at this input's own time
        // The window mean: the integral now less the integral at the last entry at least the window back (else the first).
        val windowMs = options.climbWindowS * 1000L
        var start = climbHist.first()
        for (e in climbHist) if (e.first <= tMs - windowMs) start = e else break
        climbSpanS = (tMs - start.first) / 1000.0
        climbMeanMps = if (climbSpanS > 0) (climbIntegralM - start.second) / climbSpanS else v
        // A mean over the window is surer than one value: the filter's errors decorrelate over its own time constant.
        val tc = if (filter.fused(tMs)) ReadoutTuning.CLIMB_CORRELATION_FUSED_S else ReadoutTuning.gnssCorrelationS(activity)
        val sigmaW = filter.verticalSigmaMps * sqrt(minOf(1.0, tc / maxOf(climbSpanS, 1.0)))
        climbSigmaMps = sigmaW
        val windowed = climbWindowed
        val gate = ReadoutTuning.climbGate(activity, if (windowed) options.climbWindowS else null)
        val a = abs(if (windowed) climbMeanMps else smoothV)
        val sigma = if (windowed) sigmaW else filter.verticalSigmaMps
        val sure = a >= 2 * sigma
        climbShown = if (climbShown) a >= gate.hideMps && sure else a > gate.showMps && sure
        // T250: 0 is a value only when a climb at the band could have been told; on the ground the band of the span the
        // mean covers so far (a long window partly filled is judged by what it holds, as its mean is).
        val resolveGate = if (windowed) ReadoutTuning.climbGate(activity, minOf(options.climbWindowS, maxOf(climbSpanS, ReadoutTuning.CLIMB_MIN_FILL_S).toInt())) else gate
        climbResolves = ReadoutTuning.CLIMB_RESOLVE_SIGMAS * sigma <= resolveGate.showMps
    }

    companion object {
        /** Ground speed over which the barometer is suspect (research: about 7 m/s). */
        const val BARO_MAX_GROUND_MPS = 7.0

        /** An input this far before the latest is a clock gone back, not a late sample (batched barometer samples lag a few seconds). */
        const val CLOCK_BACK_MS = 60_000L

        /** The barometer reopens this long after the last suspect trigger. */
        const val SUSPECT_HOLD_MS = 60_000L

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
