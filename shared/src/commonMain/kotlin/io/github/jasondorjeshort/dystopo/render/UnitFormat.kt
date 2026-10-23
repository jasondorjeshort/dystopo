package io.github.jasondorjeshort.dystopo.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
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

/** A unit a distance reads in; [meters] is the size of one. The distance rule ([UnitFormat.distanceUnit]) picks one. */
enum class DistanceUnit(val meters: Double, val symbol: String) {
    METERS(1.0, "m"),
    KILOMETERS(1000.0, "km"),
    FEET(0.3048, "ft"),
    MILES(1609.344, "mi"),
}

/**
 * T101: a scale bar's length: a "nice" [value] (1, 2 or 5 times a power of ten) in [unit], [meters] long and
 * [px] pixels wide at the metres per pixel it was fitted to. [label] is the value without trailing zeros and
 * the unit ("500 ft", "0.2 mi", "1 km").
 */
data class ScaleLength(val value: Double, val unit: DistanceUnit, val meters: Double, val px: Double, val label: String)

/**
 * Elevations and distances as text in one unit system, for every place the app shows them (track stats,
 * profiles, search results, route and trip cards, navigation, the scale bar), so all of them follow the one
 * setting. Plain ASCII digits, no grouping separator ("13200 ft", not "13,200 ft" or a locale's "13.200 ft").
 *
 * The distance rule (T101; PLANNING › Units): metric reads in metres below 1 km, then in kilometres with two
 * decimals below 10, one below 100 and none above; imperial reads in feet below a tenth of a mile (528 ft),
 * then in miles with one decimal below 100 and none above. The unit is judged on the value as shown, so
 * 999.6 m reads "1.00 km" and 9.996 km "10.0 km". Elevations stay in feet or metres at any size.
 *
 * No yards and no inches (the owner, 2026-10-23, answering the yards-and-inches idea): short imperial distances
 * stay in feet below 528 ft, as US trail signs and the common map apps read; inches wait for a paper-scale
 * readout, if one is ever built.
 */
object UnitFormat {
    const val FEET_PER_METER = 1.0 / 0.3048
    const val METERS_PER_MILE = 1609.344

    /** Below this many miles an imperial distance reads in feet (528 ft). */
    const val FEET_BELOW_MILES = 0.1

    /** The Web Mercator world's circumference at the equator, in metres (2π × 6378137). */
    const val EARTH_CIRCUMFERENCE_M = 2 * PI * 6378137.0

    /** An elevation or height difference in metres: "4392 m" or "14410 ft". */
    fun elevation(meters: Double, units: ContourUnits): String =
        if (units == ContourUnits.FEET) "${(meters * FEET_PER_METER).roundToLong()} ft" else "${meters.roundToLong()} m"

    /** Just the number of [elevation] in [units], rounded. */
    fun elevationValue(meters: Double, units: ContourUnits): Long =
        if (units == ContourUnits.FEET) (meters * FEET_PER_METER).roundToLong() else meters.roundToLong()

    /** The unit [meters] reads in under [units], by the distance rule: metres below 1 km, feet below a tenth of a mile. */
    fun distanceUnit(meters: Double, units: ContourUnits): DistanceUnit =
        if (units == ContourUnits.FEET) {
            // 527.5 ft shows as 528 ft, which is a tenth of a mile: from there on it reads in miles.
            if (abs(meters) * FEET_PER_METER < FEET_BELOW_MILES * METERS_PER_MILE * FEET_PER_METER - 0.5) DistanceUnit.FEET else DistanceUnit.MILES
        } else {
            if (abs(meters) < 999.5) DistanceUnit.METERS else DistanceUnit.KILOMETERS
        }

    /**
     * A distance in metres as text, by the distance rule: "850 m", "8.14 km", "23.5 km", "312 km"; "450 ft",
     * "0.6 mi", "5.1 mi", "50.9 mi", "312 mi". Before T101 miles had two decimals below 10 ("5.06 mi") and
     * 999.6 m read "1000 m"; the feet-to-miles switch at a tenth of a mile is T33's.
     */
    fun distance(meters: Double, units: ContourUnits): String {
        val unit = distanceUnit(meters, units)
        val v = meters / unit.meters
        return when (unit) {
            DistanceUnit.METERS, DistanceUnit.FEET -> "${v.roundToLong()} ${unit.symbol}"
            DistanceUnit.KILOMETERS -> "${fixed(v, digitsFor(v, 2))} ${unit.symbol}"
            DistanceUnit.MILES -> "${fixed(v, digitsFor(v, 1))} ${unit.symbol}"
        }
    }

    /**
     * A box's rough size, both sides in the unit the distance rule picks for the longer one, one decimal below
     * 10 of it and none above: "120 x 85 km", "75 x 53 mi", "4.2 x 3.1 km", "800 x 650 m".
     */
    fun size(widthM: Double, heightM: Double, units: ContourUnits): String {
        val longer = maxOf(abs(widthM), abs(heightM))
        val unit = distanceUnit(longer, units)
        val digits = when (unit) {
            DistanceUnit.METERS, DistanceUnit.FEET -> 0
            else -> digitsFor(longer / unit.meters, 1, firstStep = 10.0)
        }
        return "${fixed(widthM / unit.meters, digits)} x ${fixed(heightM / unit.meters, digits)} ${unit.symbol}"
    }

    /**
     * Metres per pixel at [zoom] and latitude [lat] on a Web Mercator map whose world is [tileSize] pixels wide at
     * zoom 0 (512 for MapLibre's logical pixels, which are dp on Android; divide by the density for device pixels).
     */
    fun metersPerPixel(zoom: Double, lat: Double, tileSize: Double = 512.0): Double =
        EARTH_CIRCUMFERENCE_M * cos(lat.coerceIn(-85.051129, 85.051129) * PI / 180) / (tileSize * 2.0.pow(zoom))

    /**
     * T101, the nice-length rule for a scale bar: the longest length that is 1, 2 or 5 times a power of ten in the
     * unit the distance rule would choose for it, and that fits in [maxPx] pixels at [metersPerPixel] (from
     * [metersPerPixel] at the map's zoom and latitude). A metric bar so reads in metres up to 500 m and in
     * kilometres from 1 km, an imperial one in feet up to 500 ft and in miles from 0.1 mi, and the bar is 40 to
     * 100% of [maxPx] wide. Null when either input is not a positive number.
     */
    fun scaleBar(metersPerPixel: Double, maxPx: Double, units: ContourUnits): ScaleLength? {
        if (!(metersPerPixel > 0) || !(maxPx > 0) || !metersPerPixel.isFinite() || !maxPx.isFinite()) return null
        val maxM = metersPerPixel * maxPx
        val big = if (units == ContourUnits.FEET) DistanceUnit.MILES else DistanceUnit.KILOMETERS
        val small = if (units == ContourUnits.FEET) DistanceUnit.FEET else DistanceUnit.METERS
        val threshold = if (units == ContourUnits.FEET) FEET_BELOW_MILES else 1.0
        val inBig = niceFloor(maxM / big.meters)
        val unit = if (inBig >= threshold * (1 - 1e-9)) big else small
        val value = if (unit == big) inBig else niceFloor(maxM / small.meters)
        val meters = value * unit.meters
        return ScaleLength(value, unit, meters, meters / metersPerPixel, "${trimmed(value)} ${unit.symbol}")
    }

    /** The largest 1, 2 or 5 times a power of ten that is at most [x] (positive), allowing for rounding in log10. */
    fun niceFloor(x: Double): Double {
        val p = 10.0.pow(floor(log10(x)))
        val f = x / p
        val m = when {
            f >= 10 * (1 - 1e-9) -> 10.0
            f >= 5 * (1 - 1e-9) -> 5.0
            f >= 2 * (1 - 1e-9) -> 2.0
            else -> 1.0
        }
        return m * p
    }

    /** A nice value as text: whole numbers without decimals ("500"), fractions with the decimals they need ("0.2", "0.05"). */
    private fun trimmed(v: Double): String {
        if (v >= 1 - 1e-9) return v.roundToLong().toString()
        val digits = (-floor(log10(v) + 1e-9)).toInt().coerceIn(1, 9)
        return fixed(v, digits)
    }

    /**
     * Decimals for [v]: [most] below [firstStep], one fewer below ten times that, and so on to none, judged on the
     * rounded value. Kilometres (2, from 10) read "8.14", "23.5", "312"; miles (1, from 100) "5.1", "50.9", "312".
     */
    private fun digitsFor(v: Double, most: Int, firstStep: Double = if (most >= 2) 10.0 else 100.0): Int {
        var digits = most
        var step = firstStep
        while (digits > 0 && abs(v) >= step - 0.5 / pow10(digits)) {
            digits--
            step *= 10
        }
        return digits
    }

    private fun pow10(n: Int): Double {
        var p = 1.0
        repeat(n) { p *= 10 }
        return p
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
