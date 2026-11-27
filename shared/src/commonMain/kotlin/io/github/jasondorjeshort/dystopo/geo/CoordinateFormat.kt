package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * T384: a position as text, in the four formats the survey of 2026-11-26 found every outdoor app offering (pure; no
 * `java.*`; `CoordinateFormatTest`). WGS 84 throughout, no datum choice. The decimals are the survey's: decimal degrees to
 * five places (about 1 m), degrees and minutes to three, degrees minutes seconds to one; the hemisphere as N, S, E or W.
 * [key] is what the synced setting `coordinateFormat` stores (T388: one choice for the whole app, read through
 * `AppSettings.coordinateFormat`); [line] is the text for a place that may wrap.
 *
 * - [DECIMAL]: "46.78600° N, 121.73500° W"
 * - [DEGREES_MINUTES]: "46° 47.160' N, 121° 44.100' W"
 * - [DMS]: "46° 47' 09.6\" N, 121° 44' 06.0\" W"
 * - [UTM]: "10T 597xxx 518xxxx" (zone, latitude band, easting and northing in whole metres, [Utm]'s Krüger series); UTM
 *   covers 80° S to 84° N only, so outside it the position reads in decimal degrees. The zone is the plain 6° one (no
 *   Norway or Svalbard exceptions).
 *
 * Each value is rounded once at its last place and carried upwards (59.96" reads 1' 00.0" more, never 60.0"); the
 * longitude is taken into [-180, 180) first, so 180° reads as 180.00000° W and the zone as 1.
 */
enum class CoordinateFormat(val key: String) {
    DECIMAL("dd"),
    DEGREES_MINUTES("dm"),
    DMS("dms"),
    UTM("utm"),
    ;

    /** [lat], [lon] in degrees (WGS 84) as text in this format. */
    fun format(lat: Double, lon: Double): String = CoordinateText.format(lat, lon, this)

    /**
     * T388: [format] for a line that may wrap: every space inside the latitude or the longitude is a no-break space, so a
     * line too narrow for both breaks only at the gap between them ("46° 47' 09.6\" N," over "121° 44' 06.0\" W"), never
     * inside a number; a UTM text keeps its zone with the easting and may break before the northing.
     */
    fun line(lat: Double, lon: Double): String = CoordinateText.wrapping(format(lat, lon))

    companion object {
        val DEFAULT = DECIMAL

        fun of(key: String?): CoordinateFormat? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** T384: the formats' arithmetic ([CoordinateFormat]). */
object CoordinateText {
    /** The latitude bands of UTM, from 80° S, 8° each (X is 12°, to 84° N). */
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    fun format(lat: Double, lon: Double, f: CoordinateFormat): String {
        val la = lat.coerceIn(-90.0, 90.0)
        val lo = normalLon(lon)
        return when (f) {
            CoordinateFormat.DECIMAL -> "${decimal(la, 'N', 'S')}, ${decimal(lo, 'E', 'W')}"
            CoordinateFormat.DEGREES_MINUTES -> "${minutes(la, 'N', 'S')}, ${minutes(lo, 'E', 'W')}"
            CoordinateFormat.DMS -> "${seconds(la, 'N', 'S')}, ${seconds(lo, 'E', 'W')}"
            CoordinateFormat.UTM -> utm(la, lo) ?: format(la, lo, CoordinateFormat.DECIMAL)
        }
    }

    /** The no-break space [CoordinateFormat.line] puts inside each part. */
    val NBSP: Char = Char(0xA0)

    /** T388: [text] with a break allowed only at the ", " between latitude and longitude, or before a UTM northing. */
    fun wrapping(text: String): String {
        val comma = text.indexOf(", ")
        if (comma >= 0) return text.substring(0, comma).replace(' ', NBSP) + ", " + text.substring(comma + 2).replace(' ', NBSP)
        val last = text.lastIndexOf(' ')
        return if (last < 0) text else text.substring(0, last).replace(' ', NBSP) + text.substring(last)
    }

    /** [lon] in [-180, 180). */
    fun normalLon(lon: Double): Double {
        val x = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (x >= 180.0) x - 360.0 else x
    }

    /** "46.78600° N": five decimals. */
    fun decimal(v: Double, pos: Char, neg: Char): String {
        val n = (abs(v) * 100_000).roundToLong()
        return "${n / 100_000}.${pad(n % 100_000, 5)}° ${hemi(v, n, pos, neg)}"
    }

    /** "46° 47.160' N": whole degrees and minutes to three decimals. */
    fun minutes(v: Double, pos: Char, neg: Char): String {
        val n = (abs(v) * 60_000).roundToLong() // thousandths of a minute
        val deg = n / 60_000
        val m = n % 60_000
        return "$deg° ${pad(m / 1000, 2)}.${pad(m % 1000, 3)}' ${hemi(v, n, pos, neg)}"
    }

    /** "46° 47' 09.6\" N": whole degrees, whole minutes and seconds to one decimal. */
    fun seconds(v: Double, pos: Char, neg: Char): String {
        val n = (abs(v) * 36_000).roundToLong() // tenths of a second
        val deg = n / 36_000
        val m = n % 36_000 / 600
        val s = n % 600
        return "$deg° ${pad(m, 2)}' ${pad(s / 10, 2)}.${s % 10}\" ${hemi(v, n, pos, neg)}"
    }

    /** "10T 597271 5181898", or null outside UTM's 80° S to 84° N. */
    fun utm(lat: Double, lon: Double): String? {
        if (lat < -80.0 || lat > 84.0) return null
        val lo = normalLon(lon)
        val zone = Utm.zoneFor(lo)
        val north = lat >= 0
        val p = Utm.forward(lat, lo, zone, north)
        return "$zone${band(lat)} ${p.easting.roundToLong()} ${p.northing.roundToLong()}"
    }

    /** The latitude band letter of [lat] (80° S to 84° N). */
    fun band(lat: Double): Char = BANDS[floor((lat.coerceIn(-80.0, 84.0) + 80.0) / 8.0).toInt().coerceIn(0, BANDS.length - 1)]

    /** The hemisphere letter; a value that rounds to zero reads as the positive one ("0.00000° N"). */
    private fun hemi(v: Double, rounded: Long, pos: Char, neg: Char): Char = if (v < 0 && rounded != 0L) neg else pos

    private fun pad(n: Long, width: Int): String = n.toString().padStart(width, '0')
}
