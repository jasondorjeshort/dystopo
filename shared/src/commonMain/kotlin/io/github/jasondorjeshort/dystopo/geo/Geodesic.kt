package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * T256: the geodesic on the WGS84 ellipsoid, the shortest line between two points over the earth: its length, its
 * azimuths and points along it. Vincenty's inverse and direct formulae (Survey Review 23(176), 1975), written from the
 * paper. Vincenty's fixed-point iteration on the auxiliary longitude does not converge for some nearly antipodal pairs
 * (an end near the other's antipode); there [inverse] solves the same equation by
 * bracketing instead (every root between |L| and pi bisected, the shortest geodesic kept) and marks the result
 * [Inverse.converged] false. A spherical fallback (the brief's first plan) was off by up to 33 km on those pairs; the
 * bracketed solve agrees with Karney's to 0.08 mm on them. Measured over all 10 000 lines of Karney's test set
 * (GeodTest-short.dat, GeographicLib's test data, doi:10.5281/zenodo.32156; 1739 of them need the bracketed solve):
 * the length within 0.08 mm, the direct problem within 0.09 mm (`GeodesicTest` keeps nine of them).
 *
 * Longitudes in and out are degrees in -180..180; [withSeamPoints] inserts the seam's two points where a line of
 * samples crosses longitude 180, so a GPX file jumps only along the seam, and [splitPieces] cuts it there for a map,
 * which draws two pieces rather than a line across the whole world.
 */
object Geodesic {
    const val A = 6_378_137.0
    const val F = 1 / 298.257223563
    const val B = A * (1 - F)

    /** The inverse problem's answer: [distanceM], the azimuths at both ends (degrees clockwise from north, -180..180). */
    data class Inverse(val distanceM: Double, val azi1Deg: Double, val azi2Deg: Double, val converged: Boolean = true)

    /** The direct problem's answer: the point [distanceM] along the geodesic and the azimuth there. */
    data class Direct(val lat: Double, val lon: Double, val aziDeg: Double)

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    /** [lon] into -180..180 (180 stays 180). */
    fun normalizeLon(lon: Double): Double {
        val x = ((lon + 180) % 360 + 360) % 360 - 180
        return if (x == -180.0 && lon > 0) 180.0 else x
    }

    /** The geodesic from ([lat1], [lon1]) to ([lat2], [lon2]): length and azimuths (Vincenty; see the object). */
    fun inverse(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Inverse {
        val l = rad(normalizeLon(lon2 - lon1))
        val u1 = atan((1 - F) * tan(rad(lat1)))
        val u2 = atan((1 - F) * tan(rad(lat2)))
        val sinU1 = sin(u1); val cosU1 = cos(u1)
        val sinU2 = sin(u2); val cosU2 = cos(u2)
        var lambda = l
        var iter = 0
        var sinSigma: Double; var cosSigma: Double; var sigma: Double
        var cosSqAlpha: Double; var cos2SigmaM: Double
        var sinLambda: Double; var cosLambda: Double
        while (true) {
            sinLambda = sin(lambda); cosLambda = cos(lambda)
            val t1 = cosU2 * sinLambda
            val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            sinSigma = sqrt(t1 * t1 + t2 * t2)
            if (sinSigma == 0.0) return Inverse(0.0, 0.0, 0.0) // the same point
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            cosSqAlpha = 1 - sinAlpha * sinAlpha
            cos2SigmaM = if (cosSqAlpha != 0.0) cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha else 0.0 // on the equator
            val c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha))
            val next = l + (1 - c) * F * sinAlpha * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
            val done = abs(next - lambda) < 1e-12
            lambda = next
            if (done) break
            if (++iter >= 200 || abs(lambda) > PI) return fallback(lat1, lon1, lat2, lon2)
        }
        sinLambda = sin(lambda); cosLambda = cos(lambda)
        val uSq = cosSqAlpha * (A * A - B * B) / (B * B)
        val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
            bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
        val s = B * bigA * (sigma - deltaSigma)
        val a1 = atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
        val a2 = atan2(cosU1 * sinLambda, -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda)
        return Inverse(s, deg(a1), deg(a2))
    }

    /** One evaluation at auxiliary longitude [lambda]: the ellipsoidal longitude it reaches, the length and the azimuths. */
    private class AtLambda(val l: Double, val s: Double, val a1: Double, val a2: Double)

    private fun atLambda(lambda: Double, sinU1: Double, cosU1: Double, sinU2: Double, cosU2: Double): AtLambda {
        val sinLambda = sin(lambda); val cosLambda = cos(lambda)
        val t1 = cosU2 * sinLambda
        val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        val sinSigma = sqrt(t1 * t1 + t2 * t2)
        val cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
        val sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = if (sinSigma == 0.0) 0.0 else cosU1 * cosU2 * sinLambda / sinSigma
        val cosSqAlpha = 1 - sinAlpha * sinAlpha
        val cos2SigmaM = if (cosSqAlpha != 0.0) cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha else 0.0
        val c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha))
        val l = lambda - (1 - c) * F * sinAlpha * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
        val uSq = cosSqAlpha * (A * A - B * B) / (B * B)
        val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
            bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
        val s = B * bigA * (sigma - deltaSigma)
        val a1 = atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
        val a2 = atan2(cosU1 * sinLambda, -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda)
        return AtLambda(l, s, a1, a2)
    }

    /**
     * The nearly antipodal case, where Vincenty's fixed-point iteration on the auxiliary longitude does not settle: the
     * same equation solved by bracketing instead. Every root of L(lambda) = L between |L| and pi is found on a grid and
     * bisected, and the shortest is kept (near the antipode several geodesics join the two points).
     */
    private fun fallback(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Inverse {
        val u1 = atan((1 - F) * tan(rad(lat1)))
        val u2 = atan((1 - F) * tan(rad(lat2)))
        val sinU1 = sin(u1); val cosU1 = cos(u1); val sinU2 = sin(u2); val cosU2 = cos(u2)
        val lSigned = rad(normalizeLon(lon2 - lon1))
        val target = abs(lSigned)
        fun g(lambda: Double) = atLambda(lambda, sinU1, cosU1, sinU2, cosU2).l - target
        var best: AtLambda? = null
        val steps = GRID
        var x0 = target
        var g0 = g(x0)
        for (i in 1..steps) {
            val x1 = target + (PI - target) * i / steps
            val g1 = g(x1)
            if (g0 == 0.0 || g0 * g1 < 0 || (i == steps && g1 == 0.0)) {
                var lo = x0; var hi = x1; var glo = g0
                if (g0 != 0.0) repeat(100) {
                    val mid = (lo + hi) / 2
                    val gm = g(mid)
                    if (gm * glo <= 0) hi = mid else { lo = mid; glo = gm }
                }
                val r = atLambda(if (g0 == 0.0) x0 else (lo + hi) / 2, sinU1, cosU1, sinU2, cosU2)
                if (best == null || r.s < best.s) best = r
            }
            x0 = x1; g0 = g1
        }
        val r = best ?: atLambda(PI, sinU1, cosU1, sinU2, cosU2) // the meridian through the pole
        val sign = if (lSigned < 0) -1 else 1
        return Inverse(r.s, sign * deg(r.a1), sign * deg(r.a2), converged = false)
    }

    private const val GRID = 400

    /** The point [distanceM] from ([lat], [lon]) along the geodesic leaving at [aziDeg] (Vincenty's direct formula). */
    fun direct(lat: Double, lon: Double, aziDeg: Double, distanceM: Double): Direct {
        val alpha1 = rad(aziDeg)
        val sinAlpha1 = sin(alpha1); val cosAlpha1 = cos(alpha1)
        val tanU1 = (1 - F) * tan(rad(lat))
        val cosU1 = 1 / sqrt(1 + tanU1 * tanU1)
        val sinU1 = tanU1 * cosU1
        val sigma1 = atan2(tanU1, cosAlpha1)
        val sinAlpha = cosU1 * sinAlpha1
        val cosSqAlpha = 1 - sinAlpha * sinAlpha
        val uSq = cosSqAlpha * (A * A - B * B) / (B * B)
        val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        var sigma = distanceM / (B * bigA)
        var cos2SigmaM: Double
        var sinSigma: Double
        var cosSigma: Double
        var iter = 0
        while (true) {
            cos2SigmaM = cos(2 * sigma1 + sigma)
            sinSigma = sin(sigma); cosSigma = cos(sigma)
            val deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
                bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
            val next = distanceM / (B * bigA) + deltaSigma
            val done = abs(next - sigma) < 1e-12
            sigma = next
            if (done || ++iter >= 200) break
        }
        cos2SigmaM = cos(2 * sigma1 + sigma)
        sinSigma = sin(sigma); cosSigma = cos(sigma)
        val tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1
        val lat2 = atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1, (1 - F) * sqrt(sinAlpha * sinAlpha + tmp * tmp))
        val lambda = atan2(sinSigma * sinAlpha1, cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1)
        val c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha))
        val l = lambda - (1 - c) * F * sinAlpha * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
        val alpha2 = atan2(sinAlpha, -tmp)
        return Direct(deg(lat2), normalizeLon(lon + deg(l)), deg(alpha2))
    }

    /** The length of the geodesic between the two points in metres ([inverse]). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = inverse(lat1, lon1, lat2, lon2).distanceM

    /** The initial azimuth from the first point to the second, degrees 0 to 360 ([inverse]). */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        (inverse(lat1, lon1, lat2, lon2).azi1Deg % 360 + 360) % 360

    /**
     * [n] + 1 points from the first point to the second along the geodesic, evenly spaced by length, the ends exact.
     */
    fun points(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int): List<Pair<Double, Double>> {
        require(n >= 1) { "n must be at least 1" }
        val inv = inverse(lat1, lon1, lat2, lon2)
        return (0..n).map { i ->
            when (i) {
                0 -> lat1 to normalizeLon(lon1)
                n -> lat2 to normalizeLon(lon2)
                else -> direct(lat1, lon1, inv.azi1Deg, inv.distanceM * i / n).let { it.lat to it.lon }
            }
        }
    }

    /** The sum of the geodesic lengths between consecutive points (each hop by [inverse]). */
    fun <T> lengthM(points: List<T>, lat: (T) -> Double, lon: (T) -> Double): Double =
        points.zipWithNext { a, b -> distanceM(lat(a), lon(a), lat(b), lon(b)) }.sum()

    /**
     * Where consecutive points cross longitude 180 (a step of more than 180 degrees of longitude), the seam's two points
     * are inserted between them: [make] at the crossing's latitude (interpolated in unwrapped longitude) with longitude
     * 180 on the first point's side and -180 on the second's, so drawing [splitPieces] of the result never spans the
     * world. Points that do not cross are returned as they are.
     */
    fun <T> withSeamPoints(points: List<T>, lat: (T) -> Double, lon: (T) -> Double, make: (lat: Double, lon: Double) -> T): List<T> {
        if (points.size < 2) return points
        val out = ArrayList<T>(points.size + 2)
        out += points[0]
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val lp = lon(p)
            val lq = lon(q)
            if (abs(lq - lp) > 180 && abs(lp) != 180.0 && abs(lq) != 180.0) {
                val east = lp > 0 // p is east of the seam's near side when it is at a positive longitude
                val seamP = if (east) 180.0 else -180.0
                val lqUnwrapped = if (east) lq + 360 else lq - 360
                val f = (seamP - lp) / (lqUnwrapped - lp)
                val latSeam = lat(p) + (lat(q) - lat(p)) * f
                out += make(latSeam, seamP)
                out += make(latSeam, -seamP)
            }
            out += q
        }
        return out
    }

    /**
     * [points] cut into pieces wherever consecutive longitudes differ by more than 180 degrees (the antimeridian), for a
     * map's line features; one piece when nothing crosses. A piece of one point is dropped.
     */
    fun <T> splitPieces(points: List<T>, lon: (T) -> Double): List<List<T>> {
        if (points.isEmpty()) return emptyList()
        val pieces = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        cur += points[0]
        for (i in 1 until points.size) {
            val jump = abs(lon(points[i]) - lon(points[i - 1])) > 180
            if (jump) {
                if (cur.size >= 2) pieces += cur
                cur = ArrayList()
            }
            cur += points[i]
        }
        if (cur.size >= 2) pieces += cur
        return pieces
    }

    /** The cap on how many samples a long line gets (helper for callers choosing [points]' n). */
    fun samples(distanceM: Double, stepM: Double, maxPoints: Int): Int = min(maxPoints - 1, max(1, kotlin.math.ceil(distanceM / stepM).toInt()))
}
