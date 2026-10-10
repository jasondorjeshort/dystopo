package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Lambert Conformal Conic with two standard parallels on an ellipsoid, following IOGP Guidance
 * Note 7-2 (EPSG method 9802). The forward projection is closed-form; the inverse iterates for
 * latitude until it moves less than 1e-12 radians (a few micrometres).
 *
 * [CANADA_ATLAS] is NAD83(CSRS) / Canada Atlas Lambert (EPSG:3979), the grid of NRCan's HRDEM
 * mosaics. NAD83(CSRS) is taken as WGS84 horizontally: they differ by about a metre, under one
 * pixel of the 1 m product and far below what a map at any zoom shows.
 */
class LambertConformalConic(
    /** Semi-major axis, metres. */
    private val a: Double,
    inverseFlattening: Double,
    latOriginDeg: Double,
    lonOriginDeg: Double,
    standardParallel1Deg: Double,
    standardParallel2Deg: Double,
    private val falseEasting: Double,
    private val falseNorthing: Double,
) {
    data class Point(val easting: Double, val northing: Double)

    private val e2 = (2 - 1 / inverseFlattening) / inverseFlattening
    private val e = sqrt(e2)
    private val lon0 = lonOriginDeg * PI / 180
    private val n: Double
    private val bigF: Double
    private val rOrigin: Double

    init {
        val phi1 = standardParallel1Deg * PI / 180
        val phi2 = standardParallel2Deg * PI / 180
        val m1 = m(phi1)
        val m2 = m(phi2)
        val t1 = t(phi1)
        val t2 = t(phi2)
        n = (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
        bigF = m1 / (n * t1.pow(n))
        rOrigin = a * bigF * t(latOriginDeg * PI / 180).pow(n)
    }

    private fun m(phi: Double): Double = cos(phi) / sqrt(1 - e2 * sin(phi) * sin(phi))

    private fun t(phi: Double): Double {
        val s = sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - e * s) / (1 + e * s)).pow(e / 2)
    }

    fun forward(lat: Double, lon: Double): Point {
        val r = a * bigF * t(lat * PI / 180).pow(n)
        var dl = lon * PI / 180 - lon0
        if (dl > PI) dl -= 2 * PI
        if (dl < -PI) dl += 2 * PI
        val theta = n * dl
        return Point(falseEasting + r * sin(theta), falseNorthing + rOrigin - r * cos(theta))
    }

    /** Returns (lat, lon) in degrees. */
    fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        val dx = easting - falseEasting
        val dy = rOrigin - (northing - falseNorthing)
        val sign = if (n < 0) -1.0 else 1.0
        val r = sign * sqrt(dx * dx + dy * dy)
        val tp = (r / (a * bigF)).pow(1 / n)
        val theta = atan2(sign * dx, sign * dy)
        var phi = PI / 2 - 2 * atan(tp)
        for (i in 0 until 20) {
            val s = sin(phi)
            val next = PI / 2 - 2 * atan(tp * ((1 - e * s) / (1 + e * s)).pow(e / 2))
            val done = abs(next - phi) < 1e-12
            phi = next
            if (done) break
        }
        return phi * 180 / PI to (theta / n + lon0) * 180 / PI
    }

    companion object {
        /** NAD83(CSRS) / Canada Atlas Lambert (EPSG:3979): GRS80, origin 49N 95W, parallels 49N and 77N, no false origin. */
        val CANADA_ATLAS = LambertConformalConic(6_378_137.0, 298.257222101, 49.0, -95.0, 49.0, 77.0, 0.0, 0.0)
    }
}
