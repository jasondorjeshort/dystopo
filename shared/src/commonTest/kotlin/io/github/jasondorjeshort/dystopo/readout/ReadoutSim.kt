package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic journeys for the readout tests (T160): a truth sampled once a second near Paradise (a public place), with
 * GNSS noise added to the fixes and a barometer that reads the truth plus an offset. Deterministic for a seed.
 */
internal class ReadoutSim(seed: Int) {
    private val rnd = Random(seed)

    fun gauss(): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        val v = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }

    /** One second of truth. */
    data class Truth(val t: Long, val east: Double, val north: Double, val ele: Double, val speed: Double, val course: Double, val climb: Double)

    /** A journey from legs of (seconds, speed m/s, climb m/s, course function of the leg's second). */
    data class Leg(val seconds: Int, val speed: Double, val climb: Double = 0.0, val course: (Int) -> Double = { 90.0 })

    fun journey(legs: List<Leg>, startEle: Double = 1600.0, t0: Long = T0): List<Truth> {
        val out = ArrayList<Truth>()
        var e = 0.0
        var n = 0.0
        var h = startEle
        var t = t0
        for (leg in legs) for (s in 0 until leg.seconds) {
            val c = leg.course(s)
            out += Truth(t, e, n, h, leg.speed, c, leg.climb)
            e += leg.speed * sin(c * PI / 180)
            n += leg.speed * cos(c * PI / 180)
            h += leg.climb
            t += 1000
        }
        return out
    }

    /**
     * Fixes for [truth]: positions with [posSigma] m per axis, the fix's own speed with [speedSigma] m/s (absolute
     * while still, as chips report it) and course with 10° (random while still) unless [withSpeed] is false,
     * heights with white [eleSigma] plus a slow error of [slowSigma] m (two-minute correlation), [vAcc] reported.
     */
    fun fixes(
        truth: List<Truth>, posSigma: Double = 3.0, speedSigma: Double = 0.3, withSpeed: Boolean = true,
        eleSigma: Double = 5.0, slowSigma: Double = 0.0, vAcc: Double = 6.0, eleBias: Double = 0.0,
    ): List<LocationFix> {
        var slow = 0.0
        val a = exp(-1.0 / 120)
        return truth.map { tr ->
            slow = a * slow + sqrt(1 - a * a) * slowSigma * gauss()
            val (lat, lon) = latLon(tr.east + posSigma * gauss(), tr.north + posSigma * gauss())
            val still = tr.speed < 0.05
            LocationFix(
                lat, lon, tr.ele + eleBias + eleSigma * gauss() + slow, tr.t, accuracyM = posSigma * 1.5,
                speedMps = if (!withSpeed) null else if (still) kotlin.math.abs(0.1 * gauss()) else (tr.speed + speedSigma * gauss()).coerceAtLeast(0.0),
                courseDeg = if (!withSpeed) null else if (still) rnd.nextDouble() * 360 else (tr.course + 10 * gauss() + 360) % 360,
                verticalAccuracyM = vAcc,
            )
        }
    }

    /** Barometer samples once a second: the pressure at the truth's height plus [offset] (m, a function of time) and [sigma] m of noise. */
    fun pressure(truth: List<Truth>, sigma: Double = 0.25, offset: (Long) -> Double = { 30.0 }): List<PressureSample> =
        truth.map { PressureSample(it.t, hPaAt(it.ele + offset(it.t) + sigma * gauss())) }

    companion object {
        const val T0 = 1_786_000_000_000L
        const val LAT0 = 46.786
        const val LON0 = -121.735
        private const val M_PER_DEG = 111_195.0

        fun latLon(east: Double, north: Double): Pair<Double, Double> =
            LAT0 + north / M_PER_DEG to LON0 + east / (M_PER_DEG * cos(LAT0 * PI / 180))

        /** The pressure whose pressure altitude is [altM]. */
        fun hPaAt(altM: Double): Double = Altimeter.STANDARD_HPA * (1 - altM / 44_330.77).pow(1 / 0.190263)
    }
}
