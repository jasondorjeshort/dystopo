package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/** A readout value as text ([ReadoutText.of]): [text] is the number (or a dash), [unit] its unit, [plusMinus] the "± 3" part when asked for. */
data class ReadoutLabel(val text: String, val unit: String, val plusMinus: String?) {
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
 */
object ReadoutText {
    const val DASH = "–"
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
        ReadoutEntry.ELEVATION -> if (units == ContourUnits.FEET) v * UnitFormat.FEET_PER_METER else v
    }

    /** [value] as text, or null when it is [ValueState.HIDDEN]. */
    fun of(value: ReadoutValue, options: ReadoutOptions, units: ContourUnits): ReadoutLabel? {
        val e = value.entry
        val unit = when (e) {
            ReadoutEntry.SPEED -> speedUnit(options, units)
            ReadoutEntry.CLIMB -> climbUnit(options.activity, units)
            ReadoutEntry.COURSE -> "°"
            ReadoutEntry.ELEVATION -> if (units == ContourUnits.FEET) "ft" else "m"
        }
        if (value.state == ValueState.HIDDEN) return null
        val v = value.value
        if (value.state == ValueState.DASH || v == null) return ReadoutLabel(DASH, unit, null)
        val x = convert(e, v, options, units)
        val text = when {
            e == ReadoutEntry.SPEED && options.speed.pace -> pace(x)
            e == ReadoutEntry.SPEED && x < HALF_STEPS_BELOW -> halves(x)
            e == ReadoutEntry.COURSE -> "${((x.roundToLong() % 360) + 360) % 360}"
            else -> step(x, stepOf(e, options, units)).let { if (it == 0L) "0" else it.toString() }
        }
        val pm = value.sigma?.takeIf { options[e].plusMinus && it.isFinite() }?.let { s ->
            when {
                e == ReadoutEntry.SPEED && options.speed.pace -> null
                e == ReadoutEntry.COURSE -> "± ${maxOf(1, s.roundToLong())}"
                else -> "± ${maxOf(stepOf(e, options, units), step(abs(convert(e, s, options, units)), stepOf(e, options, units)))}"
            }
        }
        return ReadoutLabel(text, if (e == ReadoutEntry.COURSE) "" else unit, pm).let { if (e == ReadoutEntry.COURSE) it.copy(text = it.text + unit) else it }
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
