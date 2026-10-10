package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns the phone's magnetic heading (rotation-vector azimuth) into a smoothed true heading (T61):
 * `true = magnetic + declination`, the declination from [Wmm] at the last known position and date
 * (recomputed when the position moves more than about 10 km or a day passes; it changes slowly).
 * Smoothing is a low-pass filter on the unit vector, so it handles the 359 to 0 wrap.
 */
class Compass(private val smoothing: Double = 0.25) {
    private var x = 0.0
    private var y = 0.0
    private var primed = false

    var declinationDeg: Double? = null
        private set
    private var declAt: Triple<Double, Double, Long>? = null

    /** The last smoothed true heading, degrees 0..360; null before the first reading. */
    var trueHeadingDeg: Double? = null
        private set
    var magneticHeadingDeg: Double? = null
        private set
    var readings = 0L
        private set

    /** Updates the declination for a position and time (cheap to call per fix: recomputes only when needed). */
    fun locate(lat: Double, lon: Double, timeMs: Long) {
        val last = declAt
        if (last != null && kotlin.math.abs(last.first - lat) < 0.1 && kotlin.math.abs(last.second - lon) < 0.1 && kotlin.math.abs(timeMs - last.third) < DAY_MS) return
        declinationDeg = Wmm.declination(lat, lon, Wmm.decimalYear(timeMs))
        declAt = Triple(lat, lon, timeMs)
    }

    /** A magnetic azimuth reading (degrees clockwise from magnetic north); returns the smoothed true heading. */
    fun onMagnetic(azimuthDeg: Double): Double {
        readings++
        magneticHeadingDeg = norm(azimuthDeg)
        val t = norm(azimuthDeg + (declinationDeg ?: 0.0)) * DEG
        if (!primed) {
            x = cos(t)
            y = sin(t)
            primed = true
        } else {
            x += (cos(t) - x) * smoothing
            y += (sin(t) - y) * smoothing
        }
        return norm(atan2(y, x) / DEG).also { trueHeadingDeg = it }
    }

    companion object {
        private const val DEG = PI / 180
        private const val DAY_MS = 86_400_000L

        fun norm(deg: Double): Double = ((deg % 360) + 360) % 360
    }
}
