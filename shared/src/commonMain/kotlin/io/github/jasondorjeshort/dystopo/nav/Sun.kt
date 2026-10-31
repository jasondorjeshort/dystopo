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
 * The events are for a flat horizon at sea level (the almanac's convention): sunrise and sunset when the sun's upper
 * limb meets a level horizon under standard refraction (the centre 0.833 degrees below it), civil twilight's end when
 * the centre is 6 degrees below. T181: the terrain's own horizon is [onTerrain]: the day's sun path crossed with a
 * horizon profile (`dem/Horizon.kt`), giving the terrain sunrise and sunset and the gaps when a peak blocks the sun.
 * Pure Kotlin, no dependency; times are epoch milliseconds (UTC).
 */
object Sun {
    /** The sun's centre below a flat horizon at the published sunset: refraction 34' plus the semi-diameter 16'. */
    const val SUNSET_ZENITH_DEG = 90.833

    /** Civil twilight ends with the centre this far below the horizon (6 degrees). */
    const val CIVIL_ZENITH_DEG = 96.0

    /** The sun's mean semi-diameter, degrees (16'): the upper limb is this far above the centre. */
    const val SEMI_DIAMETER_DEG = 0.2667

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

    /**
     * One day's events at a point; null when the sun does not reach that depression that day (polar summer).
     * T181: [sunriseMs] is the flat-horizon sunrise before [noonMs], the mirror of [sunsetMs].
     */
    data class Day(val year: Int, val month: Int, val day: Int, val noonMs: Long, val sunsetMs: Long?, val civilEndMs: Long?, val sunriseMs: Long? = null)

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
        return Day(
            year, month, day, noon, event(latDeg, lonDeg, midnight, noon, SUNSET_ZENITH_DEG, 1),
            event(latDeg, lonDeg, midnight, noon, CIVIL_ZENITH_DEG, 1), event(latDeg, lonDeg, midnight, noon, SUNSET_ZENITH_DEG, -1),
        )
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

    /**
     * The time after ([side] 1) or before ([side] -1) [noonMs] when the sun's centre reaches [zenithDeg], by iterating on
     * the hour angle; null if it never does.
     */
    private fun event(latDeg: Double, lonDeg: Double, midnight: Long, noonMs: Long, zenithDeg: Double, side: Int): Long? {
        var t = noonMs + side * 6 * 3_600_000L
        val phi = latDeg * DEG
        repeat(4) {
            val (decl, eot) = declinationAndEot(t)
            val d = decl * DEG
            val cosH = (cos(zenithDeg * DEG) - sin(phi) * sin(d)) / (cos(phi) * cos(d))
            if (cosH < -1 || cosH > 1) return null
            val ha = acos(cosH) / DEG
            // Solar noon at this declination's equation of time, then the hour angle in minutes after it.
            val noonHere = midnight + ((720 - 4 * lonDeg - eot) * 60_000)
            val next = (noonHere + side * 4 * ha * 60_000).roundToLong()
            if (abs(next - t) < 1000) return next
            t = next
        }
        return t
    }

    /** A stretch of the day: from [startMs] to [endMs]. */
    data class Span(val startMs: Long, val endMs: Long) {
        val minutes: Double get() = (endMs - startMs) / 60_000.0
    }

    /**
     * A gap in the sunshine between terrain sunrise and sunset: the sun went behind the terrain at [span]'s start and
     * came back at its end; [bearingDeg] and [horizonDeg] are the horizon where it went (the peak that blocked it).
     */
    data class Gap(val span: Span, val bearingDeg: Double, val horizonDeg: Double)

    /**
     * T181, the point answer: one local day's sun at a point on the terrain.
     *
     * [sunriseMs] is when the sun's upper limb first clears the terrain horizon that day and [sunsetMs] when it last
     * drops behind it (null when the sun never shows: a deep north-facing hollow in winter, or polar night); [sunlit]
     * lists every stretch of sunshine in between and [gaps] the stretches when a peak blocks it. [flat] is the almanac's
     * day at the same point, for comparison: a valley's terrain sunrise is later than [Day.sunriseMs], a summit's can be
     * earlier (its horizon dips below level). [alwaysUp] is true when the sun stays clear of the horizon the whole day.
     */
    data class TerrainDay(
        val flat: Day,
        val sunriseMs: Long?,
        val sunsetMs: Long?,
        val sunlit: List<Span>,
        val gaps: List<Gap>,
        val alwaysUp: Boolean,
        /** Sun positions evaluated (the minute scan plus the bisections). */
        val evaluations: Int,
    ) {
        /** Minutes of sunshine on the terrain that day. */
        val sunMinutes: Double get() = sunlit.sumOf { it.minutes }
    }

    /**
     * The terrain day at [latDeg], [lonDeg] on the civil date [year]-[month]-[day] (the date whose solar noon it is, as
     * [day]): the sun's path scanned every [stepMs] over the 24 hours around that noon, the sun counted up while its
     * upper limb's apparent altitude ([Position.apparentAltitudeDeg] plus [SEMI_DIAMETER_DEG]) is above
     * [horizonDeg] at its azimuth, each crossing refined by bisection to a second. [horizonDeg] is the apparent
     * elevation of the terrain horizon by azimuth (degrees, clockwise from true north): `HorizonProfile::angleAt`
     * (`dem/Horizon.kt`, refraction included), or `{ 0.0 }` for the flat horizon. A gap shorter than [stepMs] can be
     * missed (the step is a minute by default: a summit blocks the sun for many minutes).
     */
    fun onTerrain(latDeg: Double, lonDeg: Double, year: Int, month: Int, day: Int, horizonDeg: (Double) -> Double, stepMs: Long = 60_000L): TerrainDay {
        require(stepMs in 1_000L..3_600_000L) { "stepMs must be 1 s to 1 h" }
        val flat = day(latDeg, lonDeg, year, month, day)
        var evaluations = 0
        fun clearance(t: Long): Double {
            evaluations++
            val p = position(latDeg, lonDeg, t)
            return p.apparentAltitudeDeg + SEMI_DIAMETER_DEG - horizonDeg(p.azimuthDeg)
        }
        fun crossing(a: Long, b: Long, upAtA: Boolean): Long {
            var lo = a
            var hi = b
            while (hi - lo > 1000) {
                val mid = (lo + hi) / 2
                if ((clearance(mid) > 0) == upAtA) lo = mid else hi = mid
            }
            return if (upAtA) lo else hi
        }
        val start = flat.noonMs - MS_PER_DAY / 2
        val end = flat.noonMs + MS_PER_DAY / 2
        val spans = ArrayList<Span>()
        var t = start
        var up = clearance(t) > 0
        var spanStart = if (up) t else 0L
        while (t < end) {
            val next = minOf(t + stepMs, end)
            val nextUp = clearance(next) > 0
            if (nextUp != up) {
                val c = crossing(t, next, up)
                if (nextUp) spanStart = c else spans += Span(spanStart, c)
                up = nextUp
            }
            t = next
        }
        if (up) spans += Span(spanStart, end)
        val alwaysUp = spans.size == 1 && spans[0].startMs == start && spans[0].endMs == end
        val gaps = spans.zipWithNext { a, b ->
            // The blocking peak: the horizon where the sun is midway through the gap (where it went and came back
            // can both be on a ridge's flanks).
            val p = position(latDeg, lonDeg, (a.endMs + b.startMs) / 2)
            Gap(Span(a.endMs, b.startMs), p.azimuthDeg, horizonDeg(p.azimuthDeg))
        }
        return TerrainDay(
            flat = flat,
            sunriseMs = spans.firstOrNull()?.startMs?.takeIf { it != start },
            sunsetMs = spans.lastOrNull()?.endMs?.takeIf { it != end },
            sunlit = spans,
            gaps = gaps,
            alwaysUp = alwaysUp,
            evaluations = evaluations,
        )
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
