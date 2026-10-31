package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Where a minute's height came from (T178), with the bound on how far its bias can differ between the two ends of a
 * tendency ([biasBoundM]): a bias that stays the same cancels, one that changes (canopy that ends at the treeline, a
 * GNSS bias that drifts with the satellites) is read as weather at 0.12 hPa per metre.
 *
 * - [DEM_BARE_EARTH]: the DEM at the fix where the packs carry a bare-earth model (3DEP in the United States, the
 *   region rule `UnitRegions.isUnitedStates`): a metre or two vertically, plus a few metres of fix error on a slope.
 * - [DEM_SURFACE]: the DEM elsewhere (Copernicus GLO-30, a surface model: tree tops in forest), ±15 m under canopy.
 * - [GNSS]: the fix's own height, a fallback only: σ 10 to 20 m with a bias that wanders, so a tendency reduced with
 *   it never raises the warning; only a walker still at both ends does ([PressureTrend.evaluate]).
 */
enum class HeightSource(val code: String, val biasBoundM: Double) {
    DEM_BARE_EARTH("demBareEarth", 4.0),
    DEM_SURFACE("demSurface", 15.0),
    GNSS("gnss", 15.0);

    val isDem: Boolean get() = this != GNSS

    companion object {
        fun of(code: String): HeightSource? = entries.firstOrNull { it.code.equals(code.trim(), true) || it.name.equals(code.trim(), true) }
    }
}

/**
 * The pressure-fall warning's setting (T178, `pressureFallWarning`; ADR 0044: products and users split on the rate, so a
 * sensitivity, not a safety rule): the fall in 3 hours, sea-level equivalent, that raises the warning. The row shows the
 * [label] in words (ADR 0022 users may read inHg); the help text gives the numbers.
 */
enum class FallWarning(val code: String, val label: String, val thresholdHPa: Double?) {
    OFF("off", "Off", null),
    MORE("more", "More warnings", 3.0),
    STANDARD("standard", "Standard", 4.0),
    FEWER("fewer", "Fewer", 6.0);

    companion object {
        val DEFAULT = STANDARD

        /** A code, a label, or the threshold in hPa (`0` for off). */
        fun parse(raw: String): FallWarning? {
            val s = raw.trim().lowercase()
            return entries.firstOrNull { it.code == s || it.label.lowercase() == s } ?: when (s) {
                "0" -> OFF
                "3" -> MORE
                "4" -> STANDARD
                "6" -> FEWER
                else -> null
            }
        }
    }
}

/**
 * The Met Office tendency bands over 3 hours (T178; the shipping forecast's terms): steady under 0.1 hPa, slowly 0.1 to
 * 1.5, plain falling or rising 1.6 to 3.5, quickly 3.6 to 6.0, very rapidly over 6.0. The arrow steepens with the band
 * and doubles within each pair: ↘ slowly, ⇘ falling, ↓ fast, ⇓ very fast (rising mirrored), → steady.
 */
enum class TrendBand(val code: String, val falling: String, val rising: String, val fallArrow: String, val riseArrow: String) {
    STEADY("steady", "steady", "steady", "→", "→"),
    SLOWLY("slowly", "falling slowly", "rising slowly", "↘", "↗"),
    CHANGING("changing", "falling", "rising", "⇘", "⇗"),
    FAST("fast", "falling fast", "rising fast", "↓", "↑"),
    VERY_FAST("veryFast", "falling very fast", "rising very fast", "⇓", "⇑");

    fun label(change: Double): String = if (this == STEADY) falling else if (change < 0) falling else rising
    fun arrow(change: Double): String = if (this == STEADY) fallArrow else if (change < 0) fallArrow else riseArrow

    companion object {
        /** The band of a change of [hPa] in 3 hours (either sign). */
        fun of(hPa: Double): TrendBand {
            val a = abs(hPa)
            return when {
                a < 0.1 -> STEADY
                a < 1.6 -> SLOWLY
                a < 3.6 -> CHANGING
                a <= 6.0 -> FAST
                else -> VERY_FAST
            }
        }
        /** Every arrow the bar can draw (the widest is its template). */
        val ARROWS: List<String> get() = entries.flatMap { listOf(it.fallArrow, it.riseArrow) }.distinct()
    }
}

/** A weather station's temperature (T178, the report's temperature group) at its [elevationM], observed at [timeMs]. */
data class StationTemperature(val station: String, val tempC: Double, val elevationM: Double, val timeMs: Long)

/**
 * One minute of the series (T178): the median of the minute's pressure samples, paired with the median height of the
 * minute's fixes, its [source], the median speed and position. [n] counts the pressure samples. Kept across restarts
 * as one line of text ([encode]).
 */
data class TrendMinute(
    val startMs: Long,
    val hPa: Double,
    val heightM: Double? = null,
    val source: HeightSource? = null,
    val speedMps: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val n: Int = 1,
) {
    val centreMs: Long get() = startMs + MINUTE_MS / 2

    fun encode(): String = listOf(startMs, r(hPa, 4), heightM?.let { r(it, 2) }, source?.code, speedMps?.let { r(it, 2) }, lat?.let { r(it, 6) }, lon?.let { r(it, 6) }, n)
        .joinToString(",") { it?.toString() ?: "" }

    companion object {
        const val MINUTE_MS = 60_000L

        fun decode(line: String): TrendMinute? {
            val c = line.trim().split(',')
            if (c.size < 2) return null
            val t = c[0].toLongOrNull() ?: return null
            val p = c[1].toDoubleOrNull() ?: return null
            fun d(i: Int) = c.getOrNull(i)?.toDoubleOrNull()
            return TrendMinute(t, p, d(2), c.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { HeightSource.of(it) }, d(4), d(5), d(6), c.getOrNull(7)?.toIntOrNull() ?: 1)
        }

        private fun r(v: Double, digits: Int): Double {
            var s = 1.0
            repeat(digits) { s *= 10 }
            return (v * s).roundToLong() / s
        }
    }
}

/**
 * The interval of one tendency (T178), sea-level equivalent hPa: the random term (twice the standard error of the two
 * end means) and the systematic terms **added**, not averaged, as the critic required: the sensor (0.03 hPa relative,
 * plus about 0.06 for a pocket warm-up at 0.6 Pa/K, rounded to 0.1), the height (0.12 hPa per metre times the height
 * source's bias bound, [HeightSource.biasBoundM]) and the temperature (`p g |Δh| / (R T) × σ_T / T`, about
 * 0.0043 × |net climb in km| × σ_T/10 K × p).
 */
data class TrendInterval(val random: Double, val sensor: Double, val height: Double, val temperature: Double) {
    val halfWidth: Double get() = random + sensor + height + temperature
    fun json(): Map<String, Any?> = linkedMapOf("random" to r3(random), "sensor" to r3(sensor), "height" to r3(height), "temperature" to r3(temperature), "halfWidth" to r3(halfWidth))
}

/**
 * One end-to-end tendency (T178): the 3-hour one over half-hour ends or the 1-hour check over quarter-hour ends.
 * [change] is the station-level change (positive a rise) and [seaLevelChange] the same as at sea level (× p0/p, so it
 * matches a forecast). [mode] `raw` (both ends still at one height: no height used at all) or `reduced` (every minute
 * reduced to [datumM], the window's median height, with one fixed temperature profile). [thresholdHPa] is the bar this
 * tendency answers to (the setting's for 3 h, half of it for 1 h), [passes] whether it raises the warning.
 */
data class Tendency(
    val spanMs: Long,
    val mode: String,
    val change: Double,
    val seaLevelChange: Double,
    val interval: TrendInterval,
    val spacingMin: Double,
    val firstN: Int,
    val lastN: Int,
    val datumM: Double?,
    val netClimbM: Double?,
    val heightBoundM: Double?,
    val gnssHeights: Boolean,
    val temperatureK: Double?,
    val sigmaTK: Double?,
    val thresholdHPa: Double?,
    val passes: Boolean,
    val reason: String,
) {
    /** The fall, sea-level equivalent (negative for a rise). */
    val fall: Double get() = -seaLevelChange
    /** The fall's lower bound: the fall less the interval. */
    val fallLower: Double get() = fall - interval.halfWidth

    fun json(): Map<String, Any?> = linkedMapOf(
        "spanH" to spanMs / 3_600_000.0, "mode" to mode,
        "changeHPa" to r3(change), "seaLevelChangeHPa" to r3(seaLevelChange),
        "fallHPa" to r3(fall), "fallLowerHPa" to r3(fallLower),
        "interval" to interval.json(), "spacingMin" to r3(spacingMin), "endValues" to listOf(firstN, lastN),
        "datumM" to datumM?.let { r3(it) }, "netClimbM" to netClimbM?.let { r3(it) }, "heightBoundM" to heightBoundM,
        "gnssHeights" to gnssHeights, "temperatureK" to temperatureK?.let { r3(it) }, "sigmaTK" to sigmaTK?.let { r3(it) },
        "thresholdHPa" to thresholdHPa, "passes" to passes, "reason" to reason,
    )
}

/**
 * What [PressureTrend.evaluate] found (T178). [status] `ok` (an arrow: at least the 1-hour check), or `notEnoughData`.
 * [threeHour] and [oneHour] are the two tendencies or null with [threeHourMissing] / [oneHourMissing] saying why.
 * [shown] (`3h` or `1h`) is the severer against its own threshold; [equivalent] its 3-hour equivalent change (the 1-hour
 * check counts double, its threshold being half), [band] and [arrow] by the Met Office bands of the change less its
 * interval (what is certain; steady when the interval swallows the value: [significant] false), [alert] whether the warning's rule is met.
 */
data class TrendResult(
    val nowMs: Long,
    val warning: FallWarning,
    val minutes: Int,
    val coveredMin: Int,
    val threeHour: Tendency?,
    val threeHourMissing: String?,
    val oneHour: Tendency?,
    val oneHourMissing: String?,
    val shown: String?,
    val equivalent: Double?,
    val equivalentHalfWidth: Double?,
    val significant: Boolean,
    val band: TrendBand?,
    val alert: Boolean,
    val temperature: String,
    val excluded: Map<String, Int>,
) {
    val status: String get() = if (band != null) "ok" else "notEnoughData"
    val arrow: String? get() = band?.arrow(equivalent ?: 0.0)
    val label: String? get() = band?.label(equivalent ?: 0.0)

    /** The fall the warning clears on: the larger of the 3-hour fall and twice the 1-hour fall, or null with neither. */
    val fallEquivalent: Double? get() = listOfNotNull(threeHour?.fall, oneHour?.fall?.times(2)).maxOrNull()

    /** The tendency the card quotes: the shown one. */
    val quoted: Tendency? get() = if (shown == "1h") oneHour else threeHour

    fun json(): Map<String, Any?> = linkedMapOf(
        "status" to status, "warning" to warning.code, "thresholdHPa" to warning.thresholdHPa,
        "minutes" to minutes, "coveredMin" to coveredMin,
        "shown" to shown, "equivalentHPa" to equivalent?.let { r3(it) }, "equivalentHalfWidth" to equivalentHalfWidth?.let { r3(it) },
        "significant" to significant, "band" to band?.code, "label" to label, "arrow" to arrow, "alert" to alert,
        "temperature" to temperature,
        "threeHour" to threeHour?.json(), "threeHourMissing" to threeHourMissing,
        "oneHour" to oneHour?.json(), "oneHourMissing" to oneHourMissing,
        "excluded" to excluded,
    )
}

/**
 * The barometer's trend and the pressure-fall warning (T178; pure, `PressureTrendTest`). The method is the technical
 * survey's (`out/wave23-research/storm-warning.md`) with the critic's corrections: the owner's point that a hiker climbs
 * thousands of feet, so the weather has to be told apart from the climb, without the readout's fused elevation or its
 * barometer offset (circular: the offset absorbs the weather).
 *
 * - **Series**: one value a minute ([TrendMinute]): the median of the minute's pressure samples with the median height
 *   of its fixes (the DEM at the fix, GNSS only as a fallback), kept [KEEP_MS]. Samples in the first [WARM_UP_MS] after a
 *   listener start are dropped (the sensor's spin-up); a minute whose median jumps more than [SPIKE_HPA] against the mean
 *   of its neighbours is dropped (a squeeze, a gust), and so is one faster than [MAX_SPEED_MPS] (a vehicle, a lift).
 * - **Tendency**: end to end, as the WMO's: the difference of robust (20 % trimmed) means of two half-hours whose
 *   centres are 3 hours apart, the last ending now (so the series reaches back 3.5 h and the figure is 15 min behind);
 *   plus a 1-hour check over two quarter-hours an hour apart at half the threshold (a weighted 3-hour line hides a fast
 *   fall in the last hour); the severer is shown. No extrapolation: a step (a climb, the treeline) is read at its size.
 * - **Heights**: when both ends are still (under [STILL_M] of vertical movement in every [STILL_BLOCK_MS], by the
 *   pressure, the DEM heights or a fix that stays within [SAME_SPOT_M]) and at one height (the same spot, or DEM heights
 *   within [STILL_M]), the raw pressure, no height at all (`raw`). Otherwise every minute is reduced to the window's
 *   median height by the hypsometric equation with one fixed temperature profile (`reduced`): the station's last
 *   temperature lapsed at 6.5 K/km when a report exists ([sigmaT]), else the standard atmosphere with σ_T 10 K; never a
 *   live temperature. A reduced tendency whose ends use GNSS heights never raises the warning.
 * - **Interval and alert** ([TrendInterval]): the warning when the fall reaches the threshold **and** its lower bound
 *   reaches [CONFIDENCE] of it. Coverage: [MIN_COVERED_MIN] minutes of the 3 hours between the end centres and
 *   [MIN_END_VALUES] values in each end, else that tendency is missing; the arrow needs at least the 1-hour check (an hour
 *   and a quarter of data).
 */
class PressureTrend {
    private class Acc {
        val p = ArrayList<Double>(64)
        val h = ArrayList<Double>(8)
        val src = ArrayList<HeightSource>(8)
        val v = ArrayList<Double>(8)
        val lat = ArrayList<Double>(8)
        val lon = ArrayList<Double>(8)
    }

    private val open = LinkedHashMap<Long, Acc>()
    private val closed = ArrayList<TrendMinute>()
    private var warmUntil = Long.MIN_VALUE
    private var latestMs = Long.MIN_VALUE
    private val counts = LinkedHashMap<String, Long>()

    /** The closed minutes, oldest first. */
    fun minutes(): List<TrendMinute> = closed.toList()

    /** Counters (samples taken, dropped by each rule, clock resets). */
    fun counters(): Map<String, Long> = counts.toMap()

    /** The latest sample's time, or null before any. */
    val latest: Long? get() = latestMs.takeIf { it != Long.MIN_VALUE }

    private fun bump(k: String) { counts[k] = (counts[k] ?: 0L) + 1 }

    /** Forgets everything. */
    fun clear() {
        open.clear()
        closed.clear()
        warmUntil = Long.MIN_VALUE
        latestMs = Long.MIN_VALUE
    }

    /** The series kept across a restart (oldest first); replaces what is held. */
    fun restore(list: List<TrendMinute>) {
        clear()
        closed += list.sortedBy { it.startMs }
        latestMs = closed.lastOrNull()?.let { it.startMs + TrendMinute.MINUTE_MS - 1 } ?: Long.MIN_VALUE
    }

    /** The pressure listener (re)started at [tMs]: the next [WARM_UP_MS] of samples are the sensor's spin-up. */
    fun onListenerStart(tMs: Long) {
        warmUntil = tMs + WARM_UP_MS
        bump("listenerStarts")
    }

    /** One barometer sample. Returns whether it was taken. */
    fun onPressure(tMs: Long, hPa: Double): Boolean {
        if (!hPa.isFinite() || hPa !in 300.0..1100.0) { bump("pressureRejected"); return false }
        if (tMs < warmUntil) { bump("warmUpDropped"); return false }
        val a = accFor(tMs) ?: return false
        a.p += hPa
        bump("pressureSamples")
        return true
    }

    /** One fix: its [heightM] from [source] (null when neither the DEM nor the fix has one), [speedMps] and place. */
    fun onFix(tMs: Long, heightM: Double?, source: HeightSource?, speedMps: Double?, lat: Double? = null, lon: Double? = null): Boolean {
        val a = accFor(tMs) ?: return false
        if (heightM != null && heightM.isFinite() && source != null) { a.h += heightM; a.src += source }
        if (speedMps != null && speedMps.isFinite()) a.v += speedMps
        if (lat != null && lon != null) { a.lat += lat; a.lon += lon }
        bump("fixes")
        return true
    }

    private fun accFor(tMs: Long): Acc? {
        if (latestMs != Long.MIN_VALUE && tMs < latestMs - CLOCK_BACK_MS) {
            // The clock went back (a replay's file times after the phone's): start afresh rather than mix two clocks.
            clear()
            bump("clockResets")
        }
        val m = tMs.floorDiv(TrendMinute.MINUTE_MS)
        val lastClosed = closed.lastOrNull()?.startMs?.floorDiv(TrendMinute.MINUTE_MS)
        if (lastClosed != null && m <= lastClosed) { bump("lateDropped"); return null }
        latestMs = max(latestMs, tMs)
        val a = open.getOrPut(m) { Acc() }
        closeBefore(latestMs.floorDiv(TrendMinute.MINUTE_MS) - 1)
        return a
    }

    /** Closes every open minute before [minute] (a minute stays open one more minute for late fixes and batches). */
    private fun closeBefore(minute: Long) {
        val done = open.keys.filter { it < minute }.sorted()
        for (k in done) {
            val a = open.remove(k) ?: continue
            if (a.p.isEmpty()) continue
            val dem = a.src.indices.filter { a.src[it].isDem }
            val (h, s) = when {
                dem.isNotEmpty() -> median(dem.map { a.h[it] }) to dem.map { a.src[it] }.groupingBy { it }.eachCount().maxBy { it.value }.key
                a.h.isNotEmpty() -> median(a.h) to HeightSource.GNSS
                else -> null to null
            }
            closed += TrendMinute(
                k * TrendMinute.MINUTE_MS, median(a.p), h, s, a.v.takeIf { it.isNotEmpty() }?.let { median(it) },
                a.lat.takeIf { it.isNotEmpty() }?.let { median(it) }, a.lon.takeIf { it.isNotEmpty() }?.let { median(it) }, a.p.size,
            )
            bump("minutes")
        }
        val keepFrom = latestMs - KEEP_MS
        while (closed.isNotEmpty() && closed.first().startMs < keepFrom) closed.removeAt(0)
    }

    /**
     * The trend at [nowMs] (the data's clock: the latest sample's time when fed live) under [warning], with [station]'s
     * temperature when one is known. Closes the minutes before the current one first.
     */
    fun evaluate(nowMs: Long, warning: FallWarning, station: StationTemperature? = null): TrendResult {
        if (latestMs != Long.MIN_VALUE) {
            latestMs = max(latestMs, min(nowMs, latestMs + KEEP_MS))
            closeBefore(nowMs.floorDiv(TrendMinute.MINUTE_MS))
        }
        val excluded = LinkedHashMap<String, Int>()
        val usable = usable(closed.filter { it.startMs >= nowMs - THREE_H_MS - HALF_H_MS - TrendMinute.MINUTE_MS && it.startMs < nowMs }, excluded)
        val thr = warning.thresholdHPa
        val in3 = usable.filter { it.startMs >= nowMs - THREE_H_MS - HALF_H_MS }
        val in1 = usable.filter { it.startMs >= nowMs - ONE_H_MS - QUARTER_H_MS }
        val covered3 = in3.count { it.startMs >= nowMs - THREE_H_MS - HALF_H_MS / 2 && it.startMs < nowMs - HALF_H_MS / 2 }
        val covered1 = in1.count { it.startMs >= nowMs - ONE_H_MS - QUARTER_H_MS / 2 && it.startMs < nowMs - QUARTER_H_MS / 2 }
        var t3: Tendency? = null
        var t3Missing: String? = null
        if (covered3 < MIN_COVERED_MIN) t3Missing = "coverage" else {
            when (val r = tendency(in3, nowMs, THREE_H_MS, HALF_H_MS, thr, station)) {
                is Either.Ok -> t3 = r.value
                is Either.Missing -> t3Missing = r.why
            }
        }
        var t1: Tendency? = null
        var t1Missing: String? = null
        if (covered1 < MIN_COVERED_1H_MIN) t1Missing = "coverage" else {
            when (val r = tendency(in1, nowMs, ONE_H_MS, QUARTER_H_MS, thr?.div(2), station)) {
                is Either.Ok -> t1 = r.value
                is Either.Missing -> t1Missing = r.why
            }
        }
        // The severer against its own threshold (4 hPa for the display when the warning is off).
        val bar = thr ?: FallWarning.DEFAULT.thresholdHPa!!
        val r3 = t3?.let { abs(it.seaLevelChange) / bar }
        val r1 = t1?.let { abs(it.seaLevelChange) / (bar / 2) }
        val passing = listOfNotNull(t3?.takeIf { it.passes }?.let { "3h" }, t1?.takeIf { it.passes }?.let { "1h" })
        val shown = when {
            passing.size == 1 -> passing.first()
            r3 == null && r1 == null -> null
            r1 == null -> "3h"
            r3 == null -> "1h"
            r1 > r3 -> "1h"
            else -> "3h"
        }
        val eq = when (shown) { "3h" -> t3!!.seaLevelChange; "1h" -> 2 * t1!!.seaLevelChange; else -> null }
        val eqHw = when (shown) { "3h" -> t3!!.interval.halfWidth; "1h" -> 2 * t1!!.interval.halfWidth; else -> null }
        val significant = eq != null && eqHw != null && abs(eq) > eqHw
        // The band of what the interval leaves for certain (the fall less its interval), so a doubtful step reads gently.
        val band = if (eq == null || eqHw == null) null else if (significant) TrendBand.of(abs(eq) - eqHw) else TrendBand.STEADY
        val temp = if (station != null) "station ${station.station}" else "standard atmosphere"
        return TrendResult(nowMs, warning, usable.size, covered3, t3, t3Missing, t1, t1Missing, shown, eq, eqHw, significant, band,
            passing.isNotEmpty(), temp, excluded)
    }

    private sealed class Either {
        class Ok(val value: Tendency) : Either()
        class Missing(val why: String) : Either()
    }

    /** The minutes left after the speed and spike rules, counting what each dropped into [excluded]. */
    private fun usable(ms: List<TrendMinute>, excluded: MutableMap<String, Int>): List<TrendMinute> {
        val slow = ms.filter { m ->
            val fast = (m.speedMps ?: 0.0) > MAX_SPEED_MPS
            if (fast) excluded["fast"] = (excluded["fast"] ?: 0) + 1
            !fast
        }
        val out = ArrayList<TrendMinute>(slow.size)
        for (i in slow.indices) {
            val a = slow.getOrNull(i - 1)
            val b = slow.getOrNull(i + 1)
            val m = slow[i]
            if (a != null && b != null && m.startMs - a.startMs <= NEIGHBOUR_MS && b.startMs - m.startMs <= NEIGHBOUR_MS &&
                abs(m.hPa - (a.hPa + b.hPa) / 2) > SPIKE_HPA
            ) {
                excluded["spike"] = (excluded["spike"] ?: 0) + 1
                continue
            }
            out += m
        }
        return out
    }

    private fun tendency(window: List<TrendMinute>, nowMs: Long, spanMs: Long, endMs: Long, thr: Double?, station: StationTemperature?): Either {
        val first = window.filter { it.startMs >= nowMs - spanMs - endMs && it.startMs < nowMs - spanMs }
        val last = window.filter { it.startMs >= nowMs - endMs }
        if (first.size < MIN_END_VALUES || last.size < MIN_END_VALUES) return Either.Missing("endValues")
        val stillFirst = still(first)
        val stillLast = still(last)
        val pos1 = medianPos(first)
        val pos2 = medianPos(last)
        val sameSpot = pos1 != null && pos2 != null && TrackStats.distanceM(pos1.first, pos1.second, pos2.first, pos2.second) <= SAME_SPOT_M
        val dem1 = first.filter { it.source?.isDem == true }
        val dem2 = last.filter { it.source?.isDem == true }
        val demDh = if (dem1.size >= MIN_END_VALUES && dem2.size >= MIN_END_VALUES) median(dem2.map { it.heightM!! }) - median(dem1.map { it.heightM!! }) else null
        val demBound = (dem1 + dem2).maxOfOrNull { it.source!!.biasBoundM }
        val raw = stillFirst && stillLast && (sameSpot || (demDh != null && abs(demDh) < STILL_M))

        val heights = window.mapNotNull { it.heightM }
        val datum = if (heights.isNotEmpty()) median(heights) else null
        val endHeights = (first + last).count { it.heightM != null }
        val tK: (Double) -> Double
        val sigmaT: Double
        val tempUsed: Double?
        if (raw) {
            val h0 = datum ?: Altimeter.pressureAltitudeM(median(window.map { it.hPa }))
            tK = { h -> isaK(h) }
            sigmaT = 0.0
            tempUsed = null
            val v1 = first.map { it.hPa }
            val v2 = last.map { it.hPa }
            val heightBound = if (sameSpot) 1.0 else abs(demDh!!) + (demBound ?: HeightSource.DEM_SURFACE.biasBoundM)
            return Either.Ok(build(spanMs, "raw", first, last, v1, v2, h0, null, heightBound, false, tK, null, sigmaT, thr))
        }
        if (datum == null || first.count { it.heightM != null } < MIN_END_VALUES || last.count { it.heightM != null } < MIN_END_VALUES || endHeights == 0) {
            return Either.Missing("heights")
        }
        val f = first.filter { it.heightM != null }
        val l = last.filter { it.heightM != null }
        if (station != null) {
            val tSt = station.tempC + 273.15
            tK = { h -> tSt - LAPSE_K_PER_M * (h - station.elevationM) }
            sigmaT = sigmaT(station, datum, nowMs)
        } else {
            tK = { h -> isaK(h) }
            sigmaT = SIGMA_T_STANDARD_K
        }
        tempUsed = tK(datum)
        fun reduce(m: TrendMinute): Double = m.hPa * exp(G * (m.heightM!! - datum) / (R * tK((m.heightM + datum) / 2)))
        val gnss = (f + l).any { it.source == HeightSource.GNSS }
        val bound = (f + l).maxOf { it.source?.biasBoundM ?: HeightSource.GNSS.biasBoundM }
        val climb = l.map { it.heightM!! }.average() - f.map { it.heightM!! }.average()
        return Either.Ok(build(spanMs, "reduced", f, l, f.map(::reduce), l.map(::reduce), datum, climb, bound, gnss, tK, tempUsed, sigmaT, thr))
    }

    private fun build(
        spanMs: Long, mode: String, first: List<TrendMinute>, last: List<TrendMinute>, v1: List<Double>, v2: List<Double>,
        datum: Double, climb: Double?, heightBound: Double, gnss: Boolean, tK: (Double) -> Double, temperatureK: Double?,
        sigmaT: Double, thr: Double?,
    ): Tendency {
        val m1 = trimmedMean(v1)
        val m2 = trimmedMean(v2)
        val c1 = first.map { it.centreMs.toDouble() }.average()
        val c2 = last.map { it.centreMs.toDouble() }.average()
        val spacingMin = (c2 - c1) / 60_000.0
        val pRef = (m1 + m2) / 2
        val tRef = tK(datum)
        val toSeaLevel = exp(G * datum / (R * tK(datum / 2)))
        val change = m2 - m1
        val dpdh = pRef * G / (R * tRef)
        val random = 2 * sqrt(se(v1) * se(v1) + se(v2) * se(v2))
        val tempTerm = if (climb == null || sigmaT == 0.0) 0.0 else pRef * G * abs(climb) / (R * tRef) * sigmaT / tRef
        val k = toSeaLevel
        val interval = TrendInterval(random * k, SENSOR_HPA * k, dpdh * heightBound * k, tempTerm * k)
        val sl = change * toSeaLevel
        val fall = -sl
        val reason: String
        val passes: Boolean
        when {
            thr == null -> { passes = false; reason = "off" }
            fall < thr -> { passes = false; reason = "underThreshold" }
            fall - interval.halfWidth < CONFIDENCE * thr -> { passes = false; reason = "intervalTooWide" }
            mode == "reduced" && gnss -> { passes = false; reason = "gnssHeightWhileMoving" }
            else -> { passes = true; reason = "alert" }
        }
        return Tendency(spanMs, mode, change, sl, interval, spacingMin, v1.size, v2.size, datum, climb, heightBound, gnss, temperatureK,
            if (mode == "raw") null else sigmaT, thr, passes, reason)
    }

    /** Still: every [STILL_BLOCK_MS] of [ms] moved under [STILL_M] vertically (pressure altitude or DEM) or stayed on one spot. */
    private fun still(ms: List<TrendMinute>): Boolean {
        if (ms.isEmpty()) return false
        val start = ms.first().startMs
        for ((_, block) in ms.groupBy { (it.startMs - start) / STILL_BLOCK_MS }) {
            if (block.size < 2) continue
            val alt = block.map { Altimeter.pressureAltitudeM(it.hPa) }
            if (alt.max() - alt.min() < STILL_M) continue
            val dem = block.filter { it.source?.isDem == true }.map { it.heightM!! }
            if (dem.size == block.size && dem.max() - dem.min() < STILL_M) continue
            val pos = medianPos(block)
            if (pos != null && block.all { it.lat != null && it.lon != null && TrackStats.distanceM(pos.first, pos.second, it.lat, it.lon) <= SAME_SPOT_M / 2 }) continue
            return false
        }
        return true
    }

    private fun medianPos(ms: List<TrendMinute>): Pair<Double, Double>? {
        val p = ms.filter { it.lat != null && it.lon != null }
        if (p.size < ms.size / 2 || p.isEmpty()) return null
        return median(p.map { it.lat!! }) to median(p.map { it.lon!! })
    }

    companion object {
        const val G = 9.80665
        const val R = 287.05
        const val LAPSE_K_PER_M = 0.0065
        const val ISA_SEA_LEVEL_K = 288.15

        const val WARM_UP_MS = 10_000L
        const val SPIKE_HPA = 1.5
        const val NEIGHBOUR_MS = 3 * 60_000L
        const val MAX_SPEED_MPS = 7.0
        const val THREE_H_MS = 3 * 3_600_000L
        const val ONE_H_MS = 3_600_000L
        const val HALF_H_MS = 1_800_000L
        const val QUARTER_H_MS = 900_000L
        const val KEEP_MS = THREE_H_MS + HALF_H_MS + 10 * 60_000L
        const val CLOCK_BACK_MS = 10 * 60_000L

        /** 2.5 h of the 3 h between the end centres covered. */
        const val MIN_COVERED_MIN = 150
        /** 45 minutes of the hour between the end centres covered for the 1-hour check (the arrow's first hour). */
        const val MIN_COVERED_1H_MIN = 45
        const val MIN_END_VALUES = 10

        const val STILL_M = 5.0
        const val STILL_BLOCK_MS = 12 * 60_000L
        const val SAME_SPOT_M = 50.0

        /** The lower bound must reach this share of the threshold. */
        const val CONFIDENCE = 0.75
        /** The sensor between the two ends: 0.03 hPa relative plus a pocket warm-up's 0.06, rounded up. */
        const val SENSOR_HPA = 0.1
        /** The temperature's bound with no station: the standard atmosphere's spread over a year in the mountains. */
        const val SIGMA_T_STANDARD_K = 10.0

        fun isaK(h: Double): Double = ISA_SEA_LEVEL_K - LAPSE_K_PER_M * h

        /**
         * The station temperature's bound at [datumM] and [nowMs]: 2 K for the report itself, 1.5 K per km between the
         * station and the walker (lapse rates run 5 to 8 K/km, not 6.5), and 2 K per hour of age past the first hour
         * (the day's swing), capped at the standard atmosphere's 10 K.
         */
        fun sigmaT(s: StationTemperature, datumM: Double, nowMs: Long): Double {
            val dz = abs(datumM - s.elevationM) / 1000
            val ageH = max(0.0, (nowMs - s.timeMs) / 3_600_000.0 - 1)
            return min(SIGMA_T_STANDARD_K, sqrt(4.0 + 2.25 * dz * dz) + 2 * ageH)
        }

        fun median(v: List<Double>): Double {
            val s = v.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }

        /** The mean of the middle 60 % (20 % trimmed each side). */
        fun trimmedMean(v: List<Double>): Double {
            val s = v.sorted()
            val cut = (s.size * 0.2).toInt()
            return s.subList(cut, s.size - cut).average()
        }

        /** The standard error of [v]'s robust mean: the MAD's σ (at least 0.02 hPa) over the root of the count. */
        fun se(v: List<Double>): Double {
            val m = median(v)
            val mad = median(v.map { abs(it - m) }) * 1.4826
            return max(mad, 0.02) / sqrt(v.size.toDouble())
        }
    }
}

/**
 * The warning's memory (T178; pure, `PressureTrendTest`): raised once when the trend's alert rule is met, cleared when
 * the fall drops under half the threshold, not raised again within [REPEAT_MS] of the last time unless the band worsens.
 * [update] says what changed: [Event.RAISE] (show the card, say the line), [Event.CLEAR] (take the card away) or nothing.
 */
class FallAlert {
    enum class Event { NONE, RAISE, CLEAR }

    var active = false
        private set
    var raisedAtMs: Long? = null
        private set
    var raisedBand: TrendBand? = null
        private set
    var raises = 0
        private set
    var clears = 0
        private set

    fun restore(active: Boolean, raisedAtMs: Long?, raisedBand: TrendBand?) {
        this.active = active
        this.raisedAtMs = raisedAtMs
        this.raisedBand = raisedBand
    }

    fun update(r: TrendResult): Event {
        val thr = r.warning.thresholdHPa
        if (thr == null) return if (active) clear() else Event.NONE
        val band = r.band ?: TrendBand.STEADY
        if (active) {
            val f = r.fallEquivalent
            if (f != null && f < thr / 2) return clear()
            if (r.alert && raisedBand != null && band > raisedBand!!) return raise(r, band)
            return Event.NONE
        }
        if (!r.alert) return Event.NONE
        val recent = raisedAtMs?.let { r.nowMs - it < REPEAT_MS } ?: false
        if (recent && raisedBand != null && band <= raisedBand!!) return Event.NONE
        return raise(r, band)
    }

    private fun raise(r: TrendResult, band: TrendBand): Event {
        active = true
        raisedAtMs = r.nowMs
        raisedBand = band
        raises++
        return Event.RAISE
    }

    private fun clear(): Event {
        active = false
        clears++
        return Event.CLEAR
    }

    fun json(): Map<String, Any?> = linkedMapOf("active" to active, "raisedAtMs" to raisedAtMs, "raisedBand" to raisedBand?.code, "raises" to raises, "clears" to clears)

    companion object {
        const val REPEAT_MS = 3 * 3_600_000L
    }
}

/**
 * The warning's words (T178; pure). The survey's direction: say what was measured, hedge the forecast, never "storm".
 * Pressures in hPa, or inHg with feet ([inHg]).
 */
object PressureText {
    const val TITLE = "Air pressure falling fast"
    const val ADVICE = "Weather may turn worse; check your plans."
    const val SPOKEN = "Air pressure is falling fast. Weather may get worse."
    const val NOT_ENOUGH = "not enough data"

    /** "4.6 hPa" or "0.14 inHg". */
    fun amount(hPa: Double, inHg: Boolean): String =
        if (inHg) "${fmt(abs(hPa) / Altimeter.HPA_PER_INHG, 2)} inHg" else "${fmt(abs(hPa), 1)} hPa"

    private fun span(spanMs: Long) = if (spanMs >= PressureTrend.THREE_H_MS) "3 hours" else "1 hour"

    /** "Down 4.6 hPa in 3 hours (sea-level equivalent). Weather may turn worse; check your plans." */
    fun body(t: Tendency, inHg: Boolean): String =
        "${if (t.seaLevelChange < 0) "Down" else "Up"} ${amount(t.seaLevelChange, inHg)} in ${span(t.spanMs)} (sea-level equivalent). $ADVICE"

    /** The station's own tendency beside the phone's: "Weather station nearby: down 3.1 hPa in 3 hours." */
    fun station(changeHPa: Double, inHg: Boolean): String = when {
        abs(changeHPa) < 0.1 -> "Weather station nearby: steady over 3 hours."
        else -> "Weather station nearby: ${if (changeHPa < 0) "down" else "up"} ${amount(changeHPa, inHg)} in 3 hours."
    }

    private fun fmt(v: Double, digits: Int): String {
        var s = 1.0
        repeat(digits) { s *= 10 }
        val r = (v * s).roundToLong()
        val whole = r / s.toLong()
        val frac = (r % s.toLong()).toString().padStart(digits, '0')
        return "$whole.$frac"
    }
}

/** One row of a pressure series (T178): a barometer sample ([hPa]) and/or a fix (height, source, speed, place). */
data class PressureRow(
    val tMs: Long,
    val hPa: Double?,
    val heightM: Double? = null,
    val source: HeightSource? = null,
    val speedMps: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * A pressure series with its station temperature (T178): what `devtool readout --pressure` reads and `POST /readout/trend`
 * takes, as CSV: `time,hPa,heightM,source,speedMps,lat,lon` (time ISO 8601 or epoch ms, columns after `hPa` optional,
 * an empty `hPa` a fix alone), one optional line `# station,<id>,<tempC>,<elevationM>,<timeMs>`, a header skipped.
 */
data class PressureSeries(val rows: List<PressureRow>, val station: StationTemperature? = null) {
    /** Feeds every row to [trend] in time order. */
    fun feed(trend: PressureTrend) {
        for (r in rows.sortedBy { it.tMs }) {
            if (r.heightM != null || r.speedMps != null || r.lat != null) trend.onFix(r.tMs, r.heightM, r.source, r.speedMps, r.lat, r.lon)
            r.hPa?.let { trend.onPressure(r.tMs, it) }
        }
    }

    val endMs: Long? get() = rows.maxOfOrNull { it.tMs }

    /** The same series moved in time so it ends at [endMs]. */
    fun endingAt(endMs: Long): PressureSeries {
        val d = endMs - (this.endMs ?: return this)
        return PressureSeries(rows.map { it.copy(tMs = it.tMs + d) }, station?.let { it.copy(timeMs = it.timeMs + d) })
    }

    fun toCsv(): String = buildString {
        station?.let { append("# station,${it.station},${it.tempC},${it.elevationM},${it.timeMs}\n") }
        append("time,hPa,heightM,source,speedMps,lat,lon\n")
        for (r in rows) append(listOf(r.tMs, r.hPa, r.heightM, r.source?.code, r.speedMps, r.lat, r.lon).joinToString(",") { it?.toString() ?: "" }).append('\n')
    }

    companion object {
        /** Reads [text]; [parseTime] reads a non-numeric time (ISO 8601). Throws on a malformed row. */
        fun parseCsv(text: String, parseTime: (String) -> Long? = { null }): PressureSeries {
            val rows = ArrayList<PressureRow>()
            var station: StationTemperature? = null
            for ((n, raw) in text.lineSequence().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    val c = line.removePrefix("#").trim().split(',').map { it.trim() }
                    if (c.firstOrNull() == "station" && c.size >= 5) {
                        station = StationTemperature(c[1], c[2].toDouble(), c[3].toDouble(), c[4].toLongOrNull() ?: parseTime(c[4]) ?: 0L)
                    }
                    continue
                }
                val c = line.split(',').map { it.trim() }
                val t = c[0].toLongOrNull() ?: parseTime(c[0])
                if (t == null) {
                    if (rows.isEmpty()) continue // a header
                    throw IllegalArgumentException("line ${n + 1}: expected a time")
                }
                fun d(i: Int) = c.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
                val src = c.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { HeightSource.of(it) ?: throw IllegalArgumentException("line ${n + 1}: unknown height source $it") }
                rows += PressureRow(t, d(1), d(2), src, d(4), d(5), d(6))
            }
            return PressureSeries(rows, station)
        }
    }
}

/**
 * A synthetic series behind an acceptance test (T178). [expectAlert] is the verdict the method must give under the
 * Standard setting.
 */
class PressureFixture(val name: String, val description: String, val series: PressureSeries, val expectAlert: Boolean)

/**
 * The synthetic series behind the acceptance tests (T178), shared by `PressureTrendTest`, `devtool readout
 * --trend-fixture` and `POST /readout/trend?fixture=` on the phone. Each is 3 h 35 min ending at the given time, a
 * sample and a fix every 10 s around Paradise's height; the truth is a standard atmosphere offset by the day's
 * temperature ([truth]) with the weather a factor over the whole column; the sensor's noise ±0.05 hPa, the DEM's as
 * each says.
 */
object PressureFixtures {
    val NAMES = listOf(
        "still-fall", "climb-station", "climb-standard", "climb-1000-standard", "last-hour-fall", "treeline-step", "forest-noise", "recovery",
    )

    private const val STEP_MS = 10_000L
    private const val TOTAL_MIN = 215
    private const val LAT = 46.786
    private const val LON = -121.735
    private const val M_PER_DEG = 111_320.0

    /** The true pressure at [h] on a day [dT] off the standard atmosphere (same lapse rate), under sea-level pressure [pSl]. */
    fun truth(h: Double, dT: Double, pSl: Double = Altimeter.STANDARD_HPA): Double {
        val t0 = PressureTrend.ISA_SEA_LEVEL_K + dT
        val t = t0 - PressureTrend.LAPSE_K_PER_M * h
        return pSl * kotlin.math.exp(PressureTrend.G / (PressureTrend.R * PressureTrend.LAPSE_K_PER_M) * kotlin.math.ln(t / t0))
    }

    /** p0/p at [h] in the standard atmosphere (about 1.2 at 1650 m). */
    fun seaLevelFactor(h: Double): Double = kotlin.math.exp(PressureTrend.G * h / (PressureTrend.R * PressureTrend.isaK(h / 2)))

    fun of(name: String, endMs: Long): PressureFixture? {
        val rnd = kotlin.random.Random(name.hashCode())
        fun noise(a: Double) = (rnd.nextDouble() * 2 - 1) * a
        val start = endMs - TOTAL_MIN * 60_000L
        var station: StationTemperature? = null
        var dT = 0.0
        var source = HeightSource.DEM_BARE_EARTH
        val h: (Double) -> Double
        var demBias: (Double) -> Double = { 0.0 }
        var weather: (Double) -> Double = { 0.0 }
        var speed: (Double) -> Double = { 0.0 }
        var demNoise = 0.5
        var perMinuteNoise = 0.0
        val description: String
        val expect: Boolean
        when (name) {
            "still-fall" -> {
                h = { 1650.0 }
                weather = { m -> -4.0 * (m - 20) / 180 }
                description = "a still phone at 1650 m under a fall of 4 hPa in 3 h at the phone"
                expect = true
            }
            "last-hour-fall" -> {
                h = { 1650.0 }
                // 4 hPa sea-level equivalent (3.3 at the phone): a 3-hour difference alone reads 3, under the threshold.
                weather = { m -> if (m < 155) 0.0 else -4.0 / seaLevelFactor(1650.0) * (m - 155) / 60 }
                description = "two flat hours, then a fall of 4 hPa (sea-level equivalent) in the last hour, still at 1650 m"
                expect = true
            }
            "recovery" -> {
                h = { 1650.0 }
                weather = { m -> 0.3 * m / TOTAL_MIN }
                description = "a still phone at 1650 m, the pressure steady to rising slightly (the warning clears)"
                expect = false
            }
            "climb-station", "climb-standard", "climb-1000-standard" -> {
                val top = if (name == "climb-1000-standard") 2000.0 else 1600.0
                h = { m -> if (m < 55) 1000.0 else if (m < 175) 1000.0 + (top - 1000.0) * (m - 55) / 120 else top }
                speed = { m -> if (m >= 55.0 && m < 175.0) 0.9 else 0.0 }
                dT = -10.0
                if (name == "climb-station") {
                    // The station 500 m up the valley reports the true temperature 2.5 K warm (within 3 K), 30 min old.
                    val tC = PressureTrend.ISA_SEA_LEVEL_K + dT - PressureTrend.LAPSE_K_PER_M * 500 - 273.15 + 2.5
                    station = StationTemperature("TEST", tC, 500.0, endMs - 30 * 60_000L)
                    description = "a 600 m climb in 2 h, steady weather, a day 10 K under the standard atmosphere, the station temperature 2.5 K off"
                } else {
                    description = "a ${(top - 1000).toInt()} m climb in 2 h, steady weather, a day 10 K under the standard atmosphere, no station"
                }
                expect = false
            }
            "treeline-step" -> {
                h = { 1500.0 }
                source = HeightSource.DEM_SURFACE
                demBias = { m -> if (m < 175) 20.0 else if (m < 185) 20.0 * (185 - m) / 10 else 0.0 }
                speed = { 1.2 }
                description = "a level walk out of forest on a surface DEM: 20 m of canopy in the heights vanishing over 10 min, 40 min before the end; steady weather"
                expect = false
            }
            "forest-noise" -> {
                h = { m -> 1500.0 + 30 * kotlin.math.sin(2 * kotlin.math.PI * m / 50) }
                source = HeightSource.DEM_SURFACE
                speed = { 1.2 }
                perMinuteNoise = 15.0
                demNoise = 0.0
                description = "a forest walk on rolling ground, the surface DEM up to 15 m off minute by minute; steady weather"
                expect = false
            }
            else -> return null
        }
        val p0 = truth(1650.0, dT)
        val rows = ArrayList<PressureRow>()
        var minuteNoise = 0.0
        var lastMinute = -1
        var along = 0.0
        var t = start
        while (t <= endMs) {
            val m = (t - start) / 60_000.0
            if (m.toInt() != lastMinute) { lastMinute = m.toInt(); minuteNoise = noise(perMinuteNoise) }
            val hh = h(m)
            val v = speed(m)
            along += v * STEP_MS / 1000.0
            val p = truth(hh, dT) * (1 + weather(m) / p0) + noise(0.05)
            val dem = hh + demBias(m) + minuteNoise + noise(demNoise)
            rows += PressureRow(t, p, dem, source, v, LAT + along / M_PER_DEG, LON)
            t += STEP_MS
        }
        return PressureFixture(name, description, PressureSeries(rows, station), expect)
    }
}
