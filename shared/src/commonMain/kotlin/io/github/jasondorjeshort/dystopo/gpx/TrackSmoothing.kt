package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Tunables for [TrackSmoothing]. The defaults suit a hiker with a phone (T171; the research of 2026-10-30 in the
 * task report: walking accelerations of 0.3 to 0.5 m/s² over 1 to 3 s give a process noise of 0.1 to 0.5 m²/s³,
 * and the vehicle-scale 9 m²/s³ of common snippets would follow the noise; accuracy read as one sigma, Android's
 * 68 % radius).
 */
data class SmoothingOptions(
    /** Horizontal process noise of the constant-velocity model, the acceleration spectral density, m²/s³. */
    val accelerationDensity: Double = 0.25,
    /**
     * Vertical process noise, m²/s³: a hiker's climb rate changes by about 0.3 m/s over a minute or two, and GPS
     * heights drift by metres over a minute, so the height is smoothed much harder than the position (T171's
     * tuning: 0.01 left the synthetic tests' gain 7 to 19 % high, 3e-4 within 0 to 7 %, 1e-4 began to flatten).
     */
    val verticalAccelerationDensity: Double = 3e-4,
    /** Floor of an accuracy, metres, so an over-confident report cannot pin the line to one fix. */
    val minAccuracyM: Double = 3.0,
    /**
     * Accuracy of a point without one in a segment where other points have one (a network fix reporting 0 reads
     * as unknown, T158), metres.
     */
    val unknownAccuracyM: Double = 30.0,
    /**
     * Accuracy given to every point of a segment where no point has one (an imported track, a route), metres;
     * null (the default) leaves such a segment as it is: there is nothing to weight by. `devtool gpx --stats
     * --assume-accuracy` sets it to see the smoother on such a file.
     */
    val assumedAccuracyM: Double? = null,
    /** A point's height accuracy when it has no vertical accuracy: this many times its horizontal one. */
    val verticalFactor: Double = 1.5,
)

/**
 * The track smoother (T171, the recorder idea's item 1 and its tentative answer): a constant-velocity Kalman
 * filter run forward over a segment's raw fixes, each weighted by its accuracy, then a Rauch-Tung-Striebel pass
 * backward, so every position is estimated from the fixes before and after it. Raw points are kept in the store;
 * the smoothed ones are derived whenever stats or a drawing need them, so a better smoother later changes no
 * stored data.
 *
 * - East, north and height are three independent filters of position and velocity, in metres on a local plane
 *   around the segment's first point. A fix's variance is its accuracy squared ([SmoothingOptions.minAccuracyM]
 *   as a floor); a 100 m fix weighs about a thousandth of a 3 m one, so it still bounds where the walker was
 *   without pulling the line.
 * - Only segments whose points all have times (increasing) and at least one an accuracy are smoothed (or every
 *   timed segment with [SmoothingOptions.assumedAccuracyM]); any other segment, a route's for one, comes back as
 *   the same list.
 * - [smooth] returns one point per raw point (same index, same time, accuracy, flags and extensions; latitude,
 *   longitude and elevation replaced), which is what [TrackStops] indexes. [line] is what stats and drawings use:
 *   the smoothed points without the flagged ones ([counts]).
 */
object TrackSmoothing {
    /**
     * Whether a smoothed point is a vertex of the [line] and counts in distance and elevation: false for a fix
     * flagged [TrackPoint.INACCURATE] (over the profile's accuracy bound). Such a fix still bounds the line
     * through the filter; the research's recommendation (T171) is that it not add distance.
     */
    fun counts(p: TrackPoint): Boolean = !p.inaccurate

    fun smooth(track: Track, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> =
        smooth(track.segments.map { it.points }, options)

    /** Each segment through [smoothSegment]. */
    fun smooth(segments: List<List<TrackPoint>>, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> =
        segments.map { smoothSegment(it, options) }

    /**
     * The smoothed line of each segment: [smooth] without the points that do not [count][counts], except in a
     * segment with fewer than two that count, which keeps them all (nothing better is known there). A segment
     * that is not smoothed and has no flagged point comes back as the same list.
     */
    fun line(segments: List<List<TrackPoint>>, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> =
        segments.map { seg ->
            val s = smoothSegment(seg, options)
            val kept = s.count(::counts)
            if (kept == s.size || kept < 2) s else s.filter(::counts)
        }

    fun line(track: Track, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> =
        line(track.segments.map { it.points }, options)

    /** True when [smoothSegment] changes [seg] (see the class rules). */
    fun smoothable(seg: List<TrackPoint>, options: SmoothingOptions = SmoothingOptions()): Boolean {
        if (seg.size < 3) return false
        var prev = Long.MIN_VALUE
        for (p in seg) {
            val t = p.time ?: return false
            if (t < prev) return false
            prev = t
        }
        return options.assumedAccuracyM != null || seg.any { it.accuracyM != null }
    }

    /** One segment smoothed (one point out per point in), or [seg] itself when it is not [smoothable]. */
    fun smoothSegment(seg: List<TrackPoint>, options: SmoothingOptions = SmoothingOptions()): List<TrackPoint> =
        smoothWithSpeed(seg, options).points

    /** A segment's smoothed points and the smoother's ground speed at each, m/s (null when it was not smoothed). */
    class Smoothed(val points: List<TrackPoint>, val speedMps: DoubleArray?)

    /** [smoothSegment] with the speeds of the smoothed velocity, which [TrackStops] reads. */
    fun smoothWithSpeed(seg: List<TrackPoint>, options: SmoothingOptions = SmoothingOptions()): Smoothed {
        if (!smoothable(seg, options)) return Smoothed(seg, null)
        val n = seg.size
        val anyAccuracy = seg.any { it.accuracyM != null }
        val fallback = if (anyAccuracy) options.unknownAccuracyM else options.assumedAccuracyM!!
        val sigma = DoubleArray(n) { max(seg[it].accuracyM?.takeIf { a -> a > 0 } ?: fallback, options.minAccuracyM) }
        val t = DoubleArray(n) { (seg[it].time!! - seg[0].time!!) / 1000.0 }
        val lat0 = seg[0].lat
        val lon0 = seg[0].lon
        val mx = M_PER_DEG * cos(lat0 * DEG)
        val x = DoubleArray(n) { wrapLon(seg[it].lon - lon0) * mx }
        val y = DoubleArray(n) { (seg[it].lat - lat0) * M_PER_DEG }
        val all = BooleanArray(n) { true }
        val (sx, vx) = smoothAxis(t, x, all, sigma, options.accelerationDensity)
        val (sy, vy) = smoothAxis(t, y, all, sigma, options.accelerationDensity)
        val hasEle = BooleanArray(n) { seg[it].ele != null }
        val sz = if (hasEle.any { it }) {
            val vs = DoubleArray(n) { max(seg[it].verticalAccuracyM?.takeIf { a -> a > 0 } ?: (sigma[it] * options.verticalFactor), options.minAccuracyM) }
            smoothAxis(t, DoubleArray(n) { seg[it].ele ?: 0.0 }, hasEle, vs, options.verticalAccelerationDensity).first
        } else null
        val points = List(n) { i ->
            val p = seg[i]
            var lon = lon0 + sx[i] / mx
            if (lon > 180.0) lon -= 360.0 else if (lon < -180.0) lon += 360.0
            p.copy(lat = lat0 + sy[i] / M_PER_DEG, lon = lon, ele = if (p.ele != null) sz!![i] else null)
        }
        return Smoothed(points, DoubleArray(n) { kotlin.math.hypot(vx[it], vy[it]) })
    }

    /**
     * One axis: a forward Kalman filter of (position, velocity) with white-acceleration process noise [q], a
     * measurement of position [z] with standard deviation [sigma] where [has], then the Rauch-Tung-Striebel
     * backward pass. Returns the smoothed positions and velocities. [t] in seconds, non-decreasing.
     */
    internal fun smoothAxis(t: DoubleArray, z: DoubleArray, has: BooleanArray, sigma: DoubleArray, q: Double): Pair<DoubleArray, DoubleArray> {
        val n = t.size
        // Filtered (f) and predicted (p) state and covariance [[a, b], [b, d]] per index.
        val fx = DoubleArray(n); val fv = DoubleArray(n)
        val fa = DoubleArray(n); val fb = DoubleArray(n); val fd = DoubleArray(n)
        val px = DoubleArray(n); val pv = DoubleArray(n)
        val pa = DoubleArray(n); val pb = DoubleArray(n); val pd = DoubleArray(n)
        var xx = 0.0
        var xv = 0.0
        var a = PRIOR_POS_VAR
        var b = 0.0
        var d = PRIOR_VEL_VAR
        for (k in 0 until n) {
            if (k > 0) {
                val dt = t[k] - t[k - 1]
                xx += dt * xv
                val a1 = a + 2 * dt * b + dt * dt * d + q * dt * dt * dt / 3
                val b1 = b + dt * d + q * dt * dt / 2
                val d1 = d + q * dt
                a = a1; b = b1; d = d1
            }
            px[k] = xx; pv[k] = xv; pa[k] = a; pb[k] = b; pd[k] = d
            if (has[k]) {
                val r = sigma[k] * sigma[k]
                val s = a + r
                val k0 = a / s
                val k1 = b / s
                val innov = z[k] - xx
                xx += k0 * innov
                xv += k1 * innov
                val a1 = (1 - k0) * a
                val b1 = (1 - k0) * b
                val d1 = d - k1 * b
                a = a1; b = b1; d = d1
            }
            fx[k] = xx; fv[k] = xv; fa[k] = a; fb[k] = b; fd[k] = d
        }
        val out = DoubleArray(n)
        val vel = DoubleArray(n)
        var sx = fx[n - 1]
        var sv = fv[n - 1]
        out[n - 1] = sx
        vel[n - 1] = sv
        for (k in n - 2 downTo 0) {
            val dt = t[k + 1] - t[k]
            // C = Pf[k] F^T Pp[k+1]^-1
            val m00 = fa[k] + fb[k] * dt
            val m01 = fb[k]
            val m10 = fb[k] + fd[k] * dt
            val m11 = fd[k]
            val det = pa[k + 1] * pd[k + 1] - pb[k + 1] * pb[k + 1]
            if (abs(det) < 1e-12) {
                sx = fx[k]; sv = fv[k]
            } else {
                val i00 = pd[k + 1] / det
                val i01 = -pb[k + 1] / det
                val i11 = pa[k + 1] / det
                val c00 = m00 * i00 + m01 * i01
                val c01 = m00 * i01 + m01 * i11
                val c10 = m10 * i00 + m11 * i01
                val c11 = m10 * i01 + m11 * i11
                val ex = sx - px[k + 1]
                val ev = sv - pv[k + 1]
                val nx = fx[k] + c00 * ex + c01 * ev
                val nv = fv[k] + c10 * ex + c11 * ev
                sx = nx; sv = nv
            }
            out[k] = sx
            vel[k] = sv
        }
        return out to vel
    }

    private fun wrapLon(d: Double): Double = if (d > 180.0) d - 360.0 else if (d < -180.0) d + 360.0 else d

    /** Before the first fix: position and velocity unknown (1000 km and 1000 m/s sigma; the fixes decide both). */
    private const val PRIOR_POS_VAR = 1e12
    private const val PRIOR_VEL_VAR = 1e6
    private const val DEG = PI / 180.0
    private const val M_PER_DEG = TrackStats.EARTH_RADIUS_M * DEG
}
