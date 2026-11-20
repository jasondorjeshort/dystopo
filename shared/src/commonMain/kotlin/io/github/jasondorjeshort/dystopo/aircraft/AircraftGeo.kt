package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T311: the sphere geometry the layer needs (a mean earth radius; the errors are far below what an aircraft's position
 * report carries): great-circle distance and bearing, a point moved along a bearing, cross-track and along-track
 * distances to a leg, points along a great circle, and the elevation angle of a point at a height seen from another,
 * with the earth's curvature (no refraction).
 */
object AircraftGeo {
    /** The mean earth radius, metres (IUGG). */
    const val R = 6371008.8

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Great-circle distance, metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = R * angle(lat1, lon1, lat2, lon2)

    /** Central angle between two points, radians. */
    fun angle(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dp = p2 - p1
        val dl = rad(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from the first point to the second, degrees true, 0 to 360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [distanceM] from (lat, lon) along [bearingDeg]: (lat, lon). */
    fun move(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / R
        val b = rad(bearingDeg)
        val p1 = rad(lat)
        val l1 = rad(lon)
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b)).coerceIn(-1.0, 1.0))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return deg(p2) to ((deg(l2) + 540.0) % 360.0 - 180.0)
    }

    /** Signed distance (metres) of P from the great circle through A then B (positive to the right of the way A to B). */
    fun crossTrackM(aLat: Double, aLon: Double, bLat: Double, bLon: Double, pLat: Double, pLon: Double): Double {
        val d13 = angle(aLat, aLon, pLat, pLon)
        val t13 = rad(bearingDeg(aLat, aLon, pLat, pLon))
        val t12 = rad(bearingDeg(aLat, aLon, bLat, bLon))
        return asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0)) * R
    }

    /** Distance (metres) from A along the great circle A to B to the point nearest P; negative behind A. */
    fun alongTrackM(aLat: Double, aLon: Double, bLat: Double, bLon: Double, pLat: Double, pLon: Double): Double {
        val d13 = angle(aLat, aLon, pLat, pLon)
        val xt = crossTrackM(aLat, aLon, bLat, bLon, pLat, pLon) / R
        val c = cos(xt)
        val at = if (c == 0.0) 0.0 else kotlin.math.acos((cos(d13) / c).coerceIn(-1.0, 1.0))
        val t13 = rad(bearingDeg(aLat, aLon, pLat, pLon))
        val t12 = rad(bearingDeg(aLat, aLon, bLat, bLon))
        val sign = if (cos(t13 - t12) < 0) -1.0 else 1.0
        return sign * at * R
    }

    /** [n] + 1 points along the great circle from A to B, both ends included: (lat, lon) pairs. */
    fun greatCircle(aLat: Double, aLon: Double, bLat: Double, bLon: Double, n: Int): List<Pair<Double, Double>> {
        val d = angle(aLat, aLon, bLat, bLon)
        if (d < 1e-9 || n < 1) return listOf(aLat to aLon, bLat to bLon)
        val p1 = rad(aLat)
        val l1 = rad(aLon)
        val p2 = rad(bLat)
        val l2 = rad(bLon)
        return (0..n).map { i ->
            val f = i.toDouble() / n
            val a = sin((1 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
            val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
            val z = a * sin(p1) + b * sin(p2)
            deg(atan2(z, sqrt(x * x + y * y))) to deg(atan2(y, x))
        }
    }

    /**
     * The elevation angle (degrees above the observer's horizontal) of a target at height [targetM] seen from an observer
     * at [observerM] (both above the same datum), [groundM] apart along the surface: on a sphere of radius [R], with the
     * earth's curvature and no refraction. Below the horizontal is negative.
     */
    fun elevationAngleDeg(groundM: Double, observerM: Double, targetM: Double): Double {
        val theta = groundM / R
        val ro = R + observerM
        val rt = R + targetM
        // The target in the observer's frame: up along the observer's radius, forward along the surface.
        val up = rt * cos(theta) - ro
        val forward = rt * sin(theta)
        return deg(atan2(up, forward))
    }

    /** Straight-line distance between observer and target, metres (the same sphere). */
    fun slantM(groundM: Double, observerM: Double, targetM: Double): Double {
        val theta = groundM / R
        val ro = R + observerM
        val rt = R + targetM
        return sqrt(ro * ro + rt * rt - 2 * ro * rt * cos(theta))
    }

    /** Smallest difference between two bearings, degrees, 0 to 180. */
    fun turnDeg(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
        return d
    }
}
