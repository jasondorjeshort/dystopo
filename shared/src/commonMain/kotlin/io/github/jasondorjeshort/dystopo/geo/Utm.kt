package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Universal Transverse Mercator on the GRS80 ellipsoid (NAD83; WGS84 differs by <1 mm here),
 * using Krüger's series to third order in n: accurate to well under a millimeter within a zone.
 */
object Utm {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257222101
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0

    private val n = F / (2 - F)
    private val rectifyingRadius = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
    private val alpha = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16,
        13 * n * n / 48 - 3 * n * n * n / 5,
        61 * n * n * n / 240,
    )
    private val beta = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96,
        n * n / 48 + n * n * n / 15,
        17 * n * n * n / 480,
    )
    private val delta = doubleArrayOf(
        2 * n - 2 * n * n / 3 - 2 * n * n * n,
        7 * n * n / 3 - 8 * n * n * n / 5,
        56 * n * n * n / 15,
    )
    private val e2n = 2 * sqrt(n) / (1 + n)

    data class Point(val easting: Double, val northing: Double)

    fun zoneFor(lon: Double): Int = (floor((lon + 180) / 6).toInt() % 60) + 1

    /** Zone number for NAD83 (EPSG:269zz) or WGS84 (EPSG:326zz north / 327zz south) UTM codes, else null. */
    fun zoneFromEpsg(epsg: Int): Pair<Int, Boolean>? = when (epsg) {
        in 26901..26923 -> (epsg - 26900) to true
        in 32601..32660 -> (epsg - 32600) to true
        in 32701..32760 -> (epsg - 32700) to false
        else -> null
    }

    private fun centralMeridian(zone: Int) = (zone * 6 - 183) * PI / 180

    fun forward(lat: Double, lon: Double, zone: Int, north: Boolean = true): Point {
        val phi = lat * PI / 180
        val dLambda = lon * PI / 180 - centralMeridian(zone)
        val t = sinh(atanh(sin(phi)) - e2n * atanh(e2n * sin(phi)))
        val xiP = atan(t / cos(dLambda))
        val etaP = atanh(sin(dLambda) / sqrt(1 + t * t))
        var e = etaP
        var nn = xiP
        for (j in 1..3) {
            e += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
            nn += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
        }
        return Point(FALSE_EASTING + K0 * rectifyingRadius * e, (if (north) 0.0 else FALSE_NORTHING_SOUTH) + K0 * rectifyingRadius * nn)
    }

    /** Returns (lat, lon) in degrees. */
    fun inverse(easting: Double, northing: Double, zone: Int, north: Boolean = true): Pair<Double, Double> {
        val xi = (northing - (if (north) 0.0 else FALSE_NORTHING_SOUTH)) / (K0 * rectifyingRadius)
        val eta = (easting - FALSE_EASTING) / (K0 * rectifyingRadius)
        var xiP = xi
        var etaP = eta
        for (j in 1..3) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        var phi = chi
        for (j in 1..3) phi += delta[j - 1] * sin(2 * j * chi)
        val lambda = centralMeridian(zone) + atan(sinh(etaP) / cos(xiP))
        return phi * 180 / PI to lambda * 180 / PI
    }
}
