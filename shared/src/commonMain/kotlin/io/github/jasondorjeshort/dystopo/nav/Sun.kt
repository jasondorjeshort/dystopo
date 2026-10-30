package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * The sun for a point on the ground (T163): its position at an instant (declination, equation of time, hour angle,
 * azimuth and altitude) and the day's sunset and end of civil twilight, from the published low-precision solar
 * equations (Meeus, *Astronomical Algorithms*, chapters 25 and 28, in the form of NOAA's solar calculator: the sun's
 * mean longitude and anomaly, the equation of centre, the apparent longitude with nutation and aberration, the
 * obliquity of the ecliptic). Good to about a minute in the event times between 1900 and 2100; `SunTest` checks it
 * against the US Naval Observatory's published values for Paradise, Mount Rainier.
 *
 * The events are for a flat horizon at sea level (the almanac's convention): sunset when the sun's upper limb meets a
 * level horizon under standard refraction (the centre 0.833 degrees below it), civil twilight's end when the centre is
 * 6 degrees below. A valley's own horizon is earlier; that terrain sunset is the sun-and-shade idea's, which builds on
 * [position]. Pure Kotlin, no dependency; times are epoch milliseconds (UTC).
 */
object Sun {
    /** The sun's centre below a flat horizon at the published sunset: refraction 34' plus the semi-diameter 16'. */
    const val SUNSET_ZENITH_DEG = 90.833

    /** Civil twilight ends with the centre this far below the horizon (6 degrees). */
    const val CIVIL_ZENITH_DEG = 96.0

    private const val DEG = PI / 180
    private const val MS_PER_DAY = 86_400_000L

    /** The sun at an instant: everything the position needs and the position itself (degrees; azimuth clockwise from true north). */
    data class Position(
        val timeMs: Long,
        val declinationDeg: Double,
        /** Apparent minus mean solar time, minutes. */
        val equationOfTimeMin: Double,
        /** West of the meridian positive, -180..180. */
        val hourAngleDeg: Double,
        val azimuthDeg: Double,
        /** Geometric altitude of the centre (no refraction). */
        val altitudeDeg: Double,
        /** [altitudeDeg] with standard atmospheric refraction added (what the eye sees near the horizon). */
        val apparentAltitudeDeg: Double,
    )

    /** One day's events at a point; null when the sun does not reach that depression that day (polar summer). */
    data class Day(val year: Int, val month: Int, val day: Int, val noonMs: Long, val sunsetMs: Long?, val civilEndMs: Long?)

    /** Declination (degrees) and equation of time (minutes) at [epochMs]. */
    fun declinationAndEot(epochMs: Long): Pair<Double, Double> {
        val jd = epochMs / MS_PER_DAY.toDouble() + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = m * DEG
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = (125.04 - 1934.136 * t) * DEG
        val lambda = (trueLong - 0.00569 - 0.00478 * sin(omega)) * DEG
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = (eps0 + 0.00256 * cos(omega)) * DEG
        val decl = asin(sin(eps) * sin(lambda)) / DEG
        val y = tan(eps / 2).let { it * it }
        val l0r = l0 * DEG
        val eot = y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
            0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr)
        return decl to 4 * eot / DEG
    }

    /** Where the sun is seen from [latDeg], [lonDeg] (east positive) at [epochMs]. */
    fun position(latDeg: Double, lonDeg: Double, epochMs: Long): Position {
        val (decl, eot) = declinationAndEot(epochMs)
        val minutesUtc = ((epochMs % MS_PER_DAY + MS_PER_DAY) % MS_PER_DAY) / 60_000.0
        val trueSolarMin = ((minutesUtc + eot + 4 * lonDeg) % 1440 + 1440) % 1440
        var ha = trueSolarMin / 4 - 180
        if (ha < -180) ha += 360
        val phi = latDeg * DEG
        val d = decl * DEG
        val h = ha * DEG
        val cosZ = (sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZ)
        val alt = 90 - zenith / DEG
        // Azimuth clockwise from north: atan2 of the east and north components of the direction to the sun.
        val az = norm360(atan2(-sin(h) * cos(d), cos(phi) * sin(d) - sin(phi) * cos(d) * cos(h)) / DEG)
        return Position(epochMs, decl, eot, ha, az, alt, alt + refractionDeg(alt))
    }

    /**
     * Standard refraction (degrees) at geometric altitude [altDeg] (Saemundsson's formula, 10 C and 1010 hPa); 0 well
     * below the horizon, where it no longer means anything.
     */
    fun refractionDeg(altDeg: Double): Double {
        if (altDeg < -2) return 0.0
        val a = altDeg + 10.3 / (altDeg + 5.11)
        return 1.02 / tan(a * DEG) / 60.0
    }

    /**
     * Sunset and the end of civil twilight on the civil date [year]-[month]-[day] at [latDeg], [lonDeg]: the events after
     * that date's solar noon, the local date of a place being the one its noon falls on (so at Paradise the
     * 21 June sunset is at 04:05 UTC on the 22nd, 21:05 local).
     */
    fun day(latDeg: Double, lonDeg: Double, year: Int, month: Int, day: Int): Day {
        val midnight = epochDay(year, month, day) * MS_PER_DAY
        var noon = midnight + ((720 - 4 * lonDeg) * 60_000).roundToLong()
        repeat(2) { noon = midnight + ((720 - 4 * lonDeg - declinationAndEot(noon).second) * 60_000).roundToLong() }
        return Day(year, month, day, noon, after(latDeg, lonDeg, midnight, noon, SUNSET_ZENITH_DEG), after(latDeg, lonDeg, midnight, noon, CIVIL_ZENITH_DEG))
    }

    /**
     * The day whose events a hike at [epochMs] meets: the local date at the point by its mean solar time (longitude / 15
     * hours from UTC; within half an hour or so of the zone's own date, and the same at any hour of daylight).
     */
    fun dayAt(latDeg: Double, lonDeg: Double, epochMs: Long): Day {
        val local = epochMs + (lonDeg / 15 * 3_600_000).roundToLong()
        val (y, m, d) = civil(floorDiv(local, MS_PER_DAY))
        return day(latDeg, lonDeg, y, m, d)
    }

    /** The time after [noonMs] when the sun's centre reaches [zenithDeg], by iterating on the hour angle; null if it never does. */
    private fun after(latDeg: Double, lonDeg: Double, midnight: Long, noonMs: Long, zenithDeg: Double): Long? {
        var t = noonMs + 6 * 3_600_000L
        val phi = latDeg * DEG
        repeat(4) {
            val (decl, eot) = declinationAndEot(t)
            val d = decl * DEG
            val cosH = (cos(zenithDeg * DEG) - sin(phi) * sin(d)) / (cos(phi) * cos(d))
            if (cosH < -1 || cosH > 1) return null
            val ha = acos(cosH) / DEG
            // Solar noon at this declination's equation of time, then the hour angle in minutes after it.
            val noonHere = midnight + ((720 - 4 * lonDeg - eot) * 60_000)
            val next = (noonHere + 4 * ha * 60_000).roundToLong()
            if (abs(next - t) < 1000) return next
            t = next
        }
        return t
    }

    /** Days from 1970-01-01 to a proleptic Gregorian date (Howard Hinnant's days-from-civil). */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** The civil date of an epoch day. */
    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    private fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)

    private fun norm360(x: Double): Double = (x % 360 + 360) % 360
}
