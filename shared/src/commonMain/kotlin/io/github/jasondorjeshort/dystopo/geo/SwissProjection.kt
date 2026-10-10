package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Swiss LV95 projection coordinates (EPSG:2056, CH1903+ / LV95) to and from WGS84 / ETRS89.
 *
 * Rigorous, following swisstopo's "Formulas and constants for the calculation of the Swiss
 * conformal cylindrical projection and for the transformation between coordinate systems"
 * (refsys): the oblique conformal (Gauss double projection + Mercator) projection on the Bessel
 * 1841 ellipsoid, then the defining CH1903+ -> CHTRS95/ETRS89 datum shift, a pure geocentric
 * translation of (674.374, 15.056, 405.346) m, onto GRS80. Matches swisstopo's published
 * REFRAME examples to millimetres (see the tests).
 *
 * ETRS89 is treated as WGS84: they drift apart by about 2.5 cm a year (~0.8 m by 2026), far less
 * than a 2 m DEM pixel. The datum shift depends slightly on ellipsoidal height: the default h = 0
 * moves the result by about 1 cm at 1000 m, irrelevant for elevation grids.
 */
object SwissProjection {
    // Bessel 1841.
    private const val A = 6_377_397.155
    private const val E2 = 0.006674372230614
    private val E = sqrt(E2)

    // GRS80 (ETRS89); WGS84 differs by 0.1 mm in the semi-minor axis.
    private const val A_GRS = 6_378_137.0
    private const val E2_GRS = 0.006694380023011

    /** CH1903+ = CHTRS95 - T (geocentric, metres). */
    private val T = doubleArrayOf(674.374, 15.056, 405.346)

    // Projection centre in Bern, "old values" (still valid for all geodetic purposes).
    private val PHI0 = dms(46, 57, 8.66)
    private val LAMBDA0 = dms(7, 26, 22.50)

    private val R = A * sqrt(1 - E2) / (1 - E2 * sin(PHI0) * sin(PHI0))
    private val ALPHA = sqrt(1 + E2 / (1 - E2) * cos(PHI0).let { it * it * it * it })
    private val B0 = asin(sin(PHI0) / ALPHA)
    private val K = ln(tan(PI / 4 + B0 / 2)) - ALPHA * ln(tan(PI / 4 + PHI0 / 2)) +
        ALPHA * E / 2 * ln((1 + E * sin(PHI0)) / (1 - E * sin(PHI0)))

    const val FALSE_EASTING = 2_600_000.0
    const val FALSE_NORTHING = 1_200_000.0

    data class Point(val easting: Double, val northing: Double)

    private fun dms(d: Int, m: Int, s: Double) = (d + m / 60.0 + s / 3600.0) * PI / 180

    /** Bessel (CH1903+) latitude/longitude in degrees to LV95. */
    fun besselToLv95(lat: Double, lon: Double): Point {
        val phi = lat * PI / 180
        val lambda = lon * PI / 180
        // Ellipsoid -> sphere (Gauss).
        val s = ALPHA * ln(tan(PI / 4 + phi / 2)) - ALPHA * E / 2 * ln((1 + E * sin(phi)) / (1 - E * sin(phi))) + K
        val b = 2 * (atan(exp(s)) - PI / 4)
        val l = ALPHA * (lambda - LAMBDA0)
        // Rotate to the pseudo-equator through Bern.
        val lBar = atan2(sin(l), sin(B0) * tan(b) + cos(B0) * cos(l))
        val bBar = asin(cos(B0) * sin(b) - sin(B0) * cos(b) * cos(l))
        // Mercator.
        val y = R * lBar
        val x = R / 2 * ln((1 + sin(bBar)) / (1 - sin(bBar)))
        return Point(FALSE_EASTING + y, FALSE_NORTHING + x)
    }

    /** LV95 to Bessel (CH1903+) latitude/longitude in degrees. */
    fun lv95ToBessel(easting: Double, northing: Double): Pair<Double, Double> {
        val y = easting - FALSE_EASTING
        val x = northing - FALSE_NORTHING
        val lBar = y / R
        val bBar = 2 * (atan(exp(x / R)) - PI / 4)
        val b = asin(cos(B0) * sin(bBar) + sin(B0) * cos(bBar) * cos(lBar))
        val l = atan2(sin(lBar), cos(B0) * cos(lBar) - sin(B0) * tan(bBar))
        val lambda = LAMBDA0 + l / ALPHA
        var phi = b
        repeat(20) {
            val s = (ln(tan(PI / 4 + b / 2)) - K) / ALPHA + E * ln(tan(PI / 4 + asin(E * sin(phi)) / 2))
            val next = 2 * atan(exp(s)) - PI / 2
            if (abs(next - phi) < 1e-13) return next * 180 / PI to lambda * 180 / PI
            phi = next
        }
        return phi * 180 / PI to lambda * 180 / PI
    }

    /** WGS84 / ETRS89 latitude/longitude in degrees (ellipsoidal height [h] in metres) to LV95. */
    fun forward(lat: Double, lon: Double, h: Double = 0.0): Point {
        val (bLat, bLon) = shiftDatum(lat, lon, h, A_GRS, E2_GRS, -1.0, A, E2)
        return besselToLv95(bLat, bLon)
    }

    /** LV95 (Bessel ellipsoidal height [h] in metres) to WGS84 / ETRS89 (lat, lon) in degrees. */
    fun inverse(easting: Double, northing: Double, h: Double = 0.0): Pair<Double, Double> {
        val (bLat, bLon) = lv95ToBessel(easting, northing)
        return shiftDatum(bLat, bLon, h, A, E2, +1.0, A_GRS, E2_GRS)
    }

    /**
     * Geographic (height [h]) on ellipsoid (a1, e21) -> geocentric, + sign * T, -> geographic on
     * (a2, e22). The new height is dropped.
     */
    private fun shiftDatum(lat: Double, lon: Double, h: Double, a1: Double, e21: Double, sign: Double, a2: Double, e22: Double): Pair<Double, Double> {
        val phi = lat * PI / 180
        val lambda = lon * PI / 180
        val n = a1 / sqrt(1 - e21 * sin(phi) * sin(phi))
        val gx = (n + h) * cos(phi) * cos(lambda) + sign * T[0]
        val gy = (n + h) * cos(phi) * sin(lambda) + sign * T[1]
        val gz = (n * (1 - e21) + h) * sin(phi) + sign * T[2]
        val lambda2 = atan2(gy, gx)
        val p = sqrt(gx * gx + gy * gy)
        var phi2 = atan2(gz, p * (1 - e22))
        repeat(10) {
            val n2 = a2 / sqrt(1 - e22 * sin(phi2) * sin(phi2))
            val h2 = p / cos(phi2) - n2
            phi2 = atan2(gz, p * (1 - e22 * n2 / (n2 + h2)))
        }
        return phi2 * 180 / PI to lambda2 * 180 / PI
    }

    /**
     * swisstopo's approximate formulas (WGS84 -> LV95), stated to be better than 1 m inside
     * Switzerland. Kept as an independent cross-check of [forward] in the tests.
     */
    fun forwardApproximate(lat: Double, lon: Double): Point {
        val p = (lat * 3600 - 169_028.66) / 10_000
        val l = (lon * 3600 - 26_782.5) / 10_000
        val e = 2_600_072.37 + 211_455.93 * l - 10_938.51 * l * p - 0.36 * l * p * p - 44.54 * l * l * l
        val n = 1_200_147.07 + 308_807.95 * p + 3_745.25 * l * l + 76.63 * p * p - 194.56 * l * l * p + 119.79 * p * p * p
        return Point(e, n)
    }
}
