package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.nav.Wmm
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A readout value as text ([ReadoutText.of]): [text] is the number (or a dash), [unit] its unit, [plusMinus] the "± 3"
 * part when asked for. T257: [compact] is the course's shorter form, the compass point without its degrees ("SSE" for
 * "SSE 163°"), which the bar draws when its room is short; null for every other entry and a course without degrees.
 */
data class ReadoutLabel(val text: String, val unit: String, val plusMinus: String?, val compact: String? = null) {
    /** "4 km/h", "± 1" appended when present: "1650 m ± 4". */
    val full: String get() = (if (unit.isEmpty()) text else "$text $unit") + (plusMinus?.let { " $it" } ?: "")
}

/**
 * The readout's units and rounding (T160), for T161's strip and `GET /readout`. Units follow the user's preference
 * (ADR 0022: [ContourUnits.METERS] reads km/h and metres, [ContourUnits.FEET] mph and feet), knots where
 * [ReadoutOptions.knots]. Rounding is to a step that matches the noise (the readout idea's research, 2026-10-25):
 * speed in half units below 10 (T161, after driving the emulator: whole mph on foot jumped between 1 and 2 at a steady
 * walk, a step of 0.45 mph or 0.14 m/s where the speed's own noise is about 0.1 to 0.2 m/s) and whole units from 10, pace
 * to 10 s, climb to tens per hour on the ground and tens of feet (or whole metres) per minute in the air, whole degrees,
 * whole metres or feet.
 * Plain ASCII digits as [UnitFormat]; no product names.
 *
 * T257: the course reads as a compass point of sixteen and whole degrees, "SSE 163°" (the owner, 2026-11-07; the survey
 * of 2026-11-09 found degrees the instrument form and the point the glance form, and nothing against both), degrees true
 * unmarked; with [EntryOptions.magnetic] degrees magnetic marked "M" where the WMM gives a declination ([declination]),
 * else true marked "T", so the two are never mixed silently. The pressure in whole hPa, or inHg to two decimals in feet
 * units; the GNSS accuracy in whole metres or feet; the fix age in seconds, minutes or hours. A climb past four digits
 * reads in thousands ("↑12k ft/h", a replayed jump's figure; T238's note), so it never outgrows the bar's template.
 */
object ReadoutText {
    const val DASH = "–"

    /** T257: the sixteen compass points, from north clockwise, 22.5° apart. */
    val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** T257: the compass point of a bearing in degrees: "SSE" for 163°. */
    fun point(deg: Double): String = POINTS[((((deg % 360) + 360) % 360 + 11.25) / 22.5).toInt() % 16]

    /** T257: the marks after a course's degrees when magnetic was chosen: magnetic, or true where no declination is known. */
    const val MAGNETIC_MARK = "M"
    const val TRUE_MARK = "T"

    /** T257: below this horizontal field strength (nT) the WMM gives no usable declination (its blackout zone near the magnetic poles). */
    const val BLACKOUT_NT = 2000.0

    /**
     * T257: the declination (degrees, east positive) at [latDeg], [lonDeg] at [epochMs] from [Wmm], or null where it gives
     * none: outside the model's validity years or in the blackout zone around the magnetic poles.
     */
    fun declination(latDeg: Double, lonDeg: Double, epochMs: Long): Double? {
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return null
        val f = Wmm.field(latDeg, lonDeg, Wmm.decimalYear(epochMs))
        return f.declinationDeg.takeIf { f.inValidity && f.horizontalNt >= BLACKOUT_NT && it.isFinite() }
    }

    /** T257: hPa in one inch of mercury. */
    const val HPA_PER_INHG = 33.8638866667

    /** T257: the pressure unit: "hPa" in metric units, "inHg" in feet units (ADR 0022). */
    fun pressureUnit(units: ContourUnits): String = if (units == ContourUnits.FEET) "inHg" else "hPa"

    /** T250: what a value reads when the data is insufficient (the climb's window filling, too uncertain to tell, no fix). */
    const val UNKNOWN = "?"
    private const val MPS_PER_KMH = 1 / 3.6
    private const val MPS_PER_MPH = 0.44704
    private const val MPS_PER_KN = 1852.0 / 3600

    /** The speed unit: "km/h", "mph" or "kn"; with pace "/km" or "/mi" (pace is per kilometre or mile, also with knots). */
    fun speedUnit(options: ReadoutOptions, units: ContourUnits): String = when {
        options.speed.pace -> if (units == ContourUnits.FEET) "/mi" else "/km"
        options.knots -> "kn"
        units == ContourUnits.FEET -> "mph"
        else -> "km/h"
    }

    /** The climb unit: per minute in the air (ft/min, m/min), per hour elsewhere (m/h, ft/h). */
    fun climbUnit(activity: Activity, units: ContourUnits): String = when {
        activity == Activity.AIR -> if (units == ContourUnits.FEET) "ft/min" else "m/min"
        else -> if (units == ContourUnits.FEET) "ft/h" else "m/h"
    }

    /** [v]'s value in the entry's unit (speed in its unit, pace in seconds per km or mi, climb per hour or minute, metres or feet). */
    fun convert(entry: ReadoutEntry, v: Double, options: ReadoutOptions, units: ContourUnits): Double = when (entry) {
        ReadoutEntry.SPEED -> when {
            options.speed.pace -> if (v <= 0) Double.POSITIVE_INFINITY else (if (units == ContourUnits.FEET) UnitFormat.METERS_PER_MILE else 1000.0) / v
            options.knots -> v / MPS_PER_KN
            units == ContourUnits.FEET -> v / MPS_PER_MPH
            else -> v / MPS_PER_KMH
        }
        ReadoutEntry.CLIMB -> {
            val perUnit = if (options.activity == Activity.AIR) 60.0 else 3600.0
            v * perUnit * (if (units == ContourUnits.FEET) UnitFormat.FEET_PER_METER else 1.0)
        }
        ReadoutEntry.COURSE -> v
        ReadoutEntry.ELEVATION, ReadoutEntry.ACCURACY -> if (units == ContourUnits.FEET) v * UnitFormat.FEET_PER_METER else v
        ReadoutEntry.PRESSURE -> if (units == ContourUnits.FEET) v / HPA_PER_INHG else v
        ReadoutEntry.FIX_AGE -> v
    }

    /**
     * T177 (T161's follow-up, the scale bar's both-units mode): the other unit system's text for [value] ("5414 ft"
     * under "1650 m"), or null where there is none: course, speed in knots, a hidden value. A dash or a zero reads so in
     * the other unit too.
     */
    fun other(value: ReadoutValue, options: ReadoutOptions, units: ContourUnits): ReadoutLabel? {
        if (value.entry == ReadoutEntry.COURSE || value.entry == ReadoutEntry.FIX_AGE) return null
        if (value.entry == ReadoutEntry.SPEED && options.knots && !options.speed.pace) return null
        return of(value, options, if (units == ContourUnits.FEET) ContourUnits.METERS else ContourUnits.FEET)
    }

    /**
     * [value] as text, or null when it is [ValueState.HIDDEN]. T250: [ValueState.UNKNOWN] reads [UNKNOWN] ("? m/h").
     * T257: [declinationDeg] (from [declination] at the fix) turns a course into magnetic when its options ask for it.
     */
    fun of(value: ReadoutValue, options: ReadoutOptions, units: ContourUnits, declinationDeg: Double? = null): ReadoutLabel? {
        val e = value.entry
        val unit = when (e) {
            ReadoutEntry.SPEED -> speedUnit(options, units)
            ReadoutEntry.CLIMB -> climbUnit(options.activity, units)
            ReadoutEntry.COURSE -> "°"
            ReadoutEntry.ELEVATION, ReadoutEntry.ACCURACY -> if (units == ContourUnits.FEET) "ft" else "m"
            ReadoutEntry.PRESSURE -> pressureUnit(units)
            ReadoutEntry.FIX_AGE -> "s"
        }
        if (value.state == ValueState.HIDDEN) return null
        if (value.state == ValueState.ZERO) return ReadoutLabel("0", unit, null) // T177: a climb near zero (T250: one the estimate could tell)
        // T257: the course's dash and "?" keep the degree sign only while degrees are shown.
        val courseSign = if (options.course.degrees) unit else ""
        // T250: "?" for too little data (the course's sign on it, as its dash).
        if (value.state == ValueState.UNKNOWN) return if (e == ReadoutEntry.COURSE) ReadoutLabel(UNKNOWN + courseSign, "", null) else ReadoutLabel(UNKNOWN, unit, null)
        val v = value.value
        // T204 (T177's follow-up): the course's degree sign sits on the number, so its dash reads "–°", not "– °".
        if (value.state == ValueState.DASH || v == null) return if (e == ReadoutEntry.COURSE) ReadoutLabel(DASH + courseSign, "", null) else ReadoutLabel(DASH, unit, null)
        if (e == ReadoutEntry.COURSE) return course(v, value.sigma, options, declinationDeg)
        if (e == ReadoutEntry.FIX_AGE) return age(v)
        val x = convert(e, v, options, units)
        val text = when {
            e == ReadoutEntry.SPEED && options.speed.pace -> pace(x)
            e == ReadoutEntry.SPEED && x < HALF_STEPS_BELOW -> halves(x)
            e == ReadoutEntry.PRESSURE && units == ContourUnits.FEET -> hundredths(x)
            e == ReadoutEntry.CLIMB -> climbText(step(x, stepOf(e, options, units)))
            else -> step(x, stepOf(e, options, units)).let { if (it == 0L) "0" else it.toString() }
        }
        val pm = value.sigma?.takeIf { options[e].plusMinus && it.isFinite() }?.let { s ->
            when {
                e == ReadoutEntry.SPEED && options.speed.pace -> null
                e == ReadoutEntry.PRESSURE || e == ReadoutEntry.ACCURACY -> null
                else -> "± ${maxOf(stepOf(e, options, units), step(abs(convert(e, s, options, units)), stepOf(e, options, units)))}"
            }
        }
        return ReadoutLabel(text, unit, pm)
    }

    /**
     * T257: a course of [trueDeg] as its compass point and degrees ("SSE 163°"), the point alone without degrees, with
     * the "M" (magnetic, [declinationDeg] known) or "T" (magnetic asked, no declination) mark when magnetic was chosen;
     * [ReadoutLabel.compact] the point (and mark) alone while the degrees are shown.
     */
    fun course(trueDeg: Double, sigma: Double?, options: ReadoutOptions, declinationDeg: Double?): ReadoutLabel {
        val o = options.course
        val mark = when {
            !o.magnetic -> ""
            declinationDeg != null -> " $MAGNETIC_MARK"
            else -> " $TRUE_MARK"
        }
        val deg = if (o.magnetic && declinationDeg != null) trueDeg - declinationDeg else trueDeg
        val whole = ((deg.roundToLong() % 360) + 360) % 360
        val p = point(whole.toDouble())
        val pm = sigma?.takeIf { o.plusMinus && o.degrees && it.isFinite() }?.let { "± ${maxOf(1, it.roundToLong())}" }
        return if (o.degrees) ReadoutLabel("$p $whole°$mark", "", pm, compact = "$p$mark") else ReadoutLabel("$p$mark", "", null)
    }

    /** T257: a fix's age as value and unit: "8 s", "3 min", "2 h", from two days "3 d" (at most "99 d"). */
    fun age(seconds: Double): ReadoutLabel {
        val s = seconds.coerceAtLeast(0.0).toLong()
        return when {
            s < 60 -> ReadoutLabel("$s", "s", null)
            s < 3600 -> ReadoutLabel("${s / 60}", "min", null)
            s < 48 * 3600 -> ReadoutLabel("${s / 3600}", "h", null)
            else -> ReadoutLabel("${minOf(99L, s / 86_400)}", "d", null)
        }
    }

    /** T257: a climb's figure, in thousands with "k" past four digits ("12k", "-12k"), at most "999k". */
    fun climbText(n: Long): String = when {
        n == 0L -> "0"
        abs(n) < CLIMB_THOUSANDS_FROM -> n.toString()
        else -> (if (n < 0) "-" else "") + "${minOf(999L, (abs(n) + 500) / 1000)}k"
    }

    /** T257: climbs from this figure on read in thousands ([climbText]). */
    const val CLIMB_THOUSANDS_FROM = 10_000L

    private fun hundredths(x: Double): String {
        val h = (x * 100).roundToLong()
        return "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }

    private fun stepOf(e: ReadoutEntry, options: ReadoutOptions, units: ContourUnits): Long = when {
        e != ReadoutEntry.CLIMB -> 1
        options.activity == Activity.AIR && units == ContourUnits.METERS -> 1
        else -> 10
    }

    private fun step(x: Double, step: Long): Long = (x / step).roundToLong() * step

    /** T161: speeds below this (in the shown unit) read to half a unit with one decimal ("4.5 km/h", "3.0 mph"). */
    const val HALF_STEPS_BELOW = 9.75

    private fun halves(x: Double): String {
        val h = (x * 2).roundToLong()
        return "${h / 2}.${if (h % 2 == 0L) 0 else 5}"
    }

    /** Seconds per unit distance as m:ss to 10 s; a dash past 99 minutes (standing). */
    fun pace(secondsPerUnit: Double): String {
        if (!secondsPerUnit.isFinite() || secondsPerUnit >= 99 * 60) return DASH
        val s = (secondsPerUnit / 10).roundToLong() * 10
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
