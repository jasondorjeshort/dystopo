package io.github.jasondorjeshort.dystopo.render

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The user's feet/metres preference (docs/PLANNING.md › Units): [units] is null until it is set,
 * either by the user or seeded once from the home area ([seeded]); once set it never changes on its
 * own, so travelling keeps your units. [source] says where the value came from (`user`, `pack:<name>`,
 * `fix`).
 */
data class UnitPreference(val units: ContourUnits? = null, val source: String? = null) {
    /** This preference, or, when nothing is set yet, the regional default at ([lat], [lon]) from [UnitRegions]. */
    fun seeded(lat: Double, lon: Double, source: String): UnitPreference =
        if (units != null || lat.isNaN() || lon.isNaN()) this else UnitPreference(UnitRegions.contourUnitsAt(lat, lon), source)

    /** The units to show: the preference, or [fallback] while it is unset. */
    fun effective(fallback: ContourUnits): ContourUnits = units ?: fallback
}

/**
 * Elevations and distances as text in one unit system, for every place the app shows them (track stats,
 * profiles, search results), so all of them follow the one setting. Plain ASCII digits, no grouping
 * separator ("13200 ft", not "13,200 ft" or a locale's "13.200 ft").
 */
object UnitFormat {
    const val FEET_PER_METER = 1.0 / 0.3048
    const val METERS_PER_MILE = 1609.344

    /** An elevation or height difference in metres: "4392 m" or "14410 ft". */
    fun elevation(meters: Double, units: ContourUnits): String =
        if (units == ContourUnits.FEET) "${(meters * FEET_PER_METER).roundToLong()} ft" else "${meters.roundToLong()} m"

    /** Just the number of [elevation] in [units], rounded. */
    fun elevationValue(meters: Double, units: ContourUnits): Long =
        if (units == ContourUnits.FEET) (meters * FEET_PER_METER).roundToLong() else meters.roundToLong()

    /**
     * A distance in metres: metres below 1 km ("850 m"), then km with two decimals below 10, one below 100
     * ("8.14 km", "23.5 km", "312 km"); in feet mode feet below a tenth of a mile ("450 ft"), then miles the
     * same way ("5.06 mi").
     */
    fun distance(meters: Double, units: ContourUnits): String {
        if (units == ContourUnits.FEET) {
            val miles = meters / METERS_PER_MILE
            return if (miles < 0.1) "${(meters * FEET_PER_METER).roundToLong()} ft" else "${decimals(miles)} mi"
        }
        return if (meters < 1000) "${meters.roundToLong()} m" else "${decimals(meters / 1000)} km"
    }

    /** Two decimals below 10, one below 100, none above. */
    private fun decimals(v: Double): String = when {
        abs(v) < 10 -> fixed(v, 2)
        abs(v) < 100 -> fixed(v, 1)
        else -> v.roundToLong().toString()
    }

    /** [v] with exactly [digits] decimals and a '.' separator, in pure Kotlin (common code has no String.format). */
    fun fixed(v: Double, digits: Int): String {
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val n = (abs(v) * scale).roundToLong()
        val whole = n / scale
        val frac = (n % scale).toString().padStart(digits, '0')
        val sign = if (v < 0 && n != 0L) "-" else ""
        return if (digits == 0) "$sign$whole" else "$sign$whole.$frac"
    }
}
