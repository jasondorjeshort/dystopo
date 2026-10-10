package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lambert Azimuthal Equal Area on an ellipsoid (oblique aspect), following the formulas of IOGP
 * Guidance Note 7-2 (EPSG method 9820). The forward projection is closed-form; the inverse uses the
 * guidance note's series for latitude from authalic latitude, good to well under a millimetre.
 *
 * [EUROPE] is ETRS89-extended / LAEA Europe (EPSG:3035), the grid Austria's ALS DTM tiles are in.
 */
class LambertAzimuthalEqualArea(
    /** Semi-major axis, metres. */
    private val a: Double,
    /** Inverse flattening. */
    inverseFlattening: Double,
    latOriginDeg: Double,
    lonOriginDeg: Double,
    private val falseEasting: Double,
    private val falseNorthing: Double,
) {
    data class Point(val easting: Double, val northing: Double)

    private val e2: Double = (2 - 1 / inverseFlattening) / inverseFlattening
    private val e = sqrt(e2)
    private val lon0 = lonOriginDeg * PI / 180
    private val qP = q(PI / 2)
    private val rq = a * sqrt(qP / 2)
    private val beta0: Double
    private val d: Double

    init {
        val phi0 = latOriginDeg * PI / 180
        beta0 = asin(q(phi0) / qP)
        d = a * (cos(phi0) / sqrt(1 - e2 * sin(phi0) * sin(phi0))) / (rq * cos(beta0))
    }

    private fun q(phi: Double): Double {
        val s = sin(phi)
        return (1 - e2) * (s / (1 - e2 * s * s) - (1 / (2 * e)) * ln((1 - e * s) / (1 + e * s)))
    }

    fun forward(lat: Double, lon: Double): Point {
        val beta = asin((q(lat * PI / 180) / qP).coerceIn(-1.0, 1.0))
        val dl = lon * PI / 180 - lon0
        val b = rq * sqrt(2 / (1 + sin(beta0) * sin(beta) + cos(beta0) * cos(beta) * cos(dl)))
        return Point(
            falseEasting + b * d * cos(beta) * sin(dl),
            falseNorthing + (b / d) * (cos(beta0) * sin(beta) - sin(beta0) * cos(beta) * cos(dl)),
        )
    }

    /** Returns (lat, lon) in degrees. */
    fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        val x = easting - falseEasting
        val y = northing - falseNorthing
        val rho = sqrt((x / d) * (x / d) + (d * y) * (d * y))
        if (rho == 0.0) return latFromAuthalic(beta0) * 180 / PI to lon0 * 180 / PI
        val c = 2 * asin((rho / (2 * rq)).coerceIn(-1.0, 1.0))
        val betaP = asin(cos(c) * sin(beta0) + (d * y * sin(c) * cos(beta0)) / rho)
        val lambda = lon0 + atan2(x * sin(c), d * rho * cos(beta0) * cos(c) - d * d * y * sin(beta0) * sin(c))
        return latFromAuthalic(betaP) * 180 / PI to lambda * 180 / PI
    }

    /** Geodetic latitude from authalic latitude (radians), by the guidance note's series. */
    private fun latFromAuthalic(beta: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        return beta +
            (e2 / 3 + 31 * e4 / 180 + 517 * e6 / 5040) * sin(2 * beta) +
            (23 * e4 / 360 + 251 * e6 / 3780) * sin(4 * beta) +
            (761 * e6 / 45360) * sin(6 * beta)
    }

    companion object {
        /** ETRS89-extended / LAEA Europe (EPSG:3035): GRS80, origin 52N 10E, false origin 4321000 / 3210000. */
        val EUROPE = LambertAzimuthalEqualArea(6_378_137.0, 298.257222101, 52.0, 10.0, 4_321_000.0, 3_210_000.0)
    }
}
