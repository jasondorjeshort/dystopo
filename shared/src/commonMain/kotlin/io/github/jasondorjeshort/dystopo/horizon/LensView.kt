package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * T322: the Panorama's lens, a perspective view [width] by [height] px at [dp] px per dp, looking toward true bearing
 * [bearingDeg] (wrapping), tilted [tiltDeg] from the horizontal (-90 straight down, +90 straight up), rolled [rollDeg]
 * (positive: the phone turned clockwise, so what stands to the right rises on the screen), [fovDeg] across the width.
 * The camera's up is the zenith's side at roll 0, so up on the screen is up in the world; looking straight down, the top
 * of the screen points along [bearingDeg].
 *
 * As a [LabelProjection]: a mark below the horizon is placed where it falls, never clamped; [away] is toward the zenith
 * as projected (up at the level, unrolled); [separation] is the screen distance between two marks over [REF_DP_PER_DEG]
 * dp, so the density gap keeps a fixed size on the screen and a narrower view, spreading the marks, names more.
 */
class LensView(
    val width: Double, val height: Double, override val dp: Double,
    val bearingDeg: Double, val tiltDeg: Double, val rollDeg: Double, val fovDeg: Double,
) : LabelProjection {
    init {
        require(width > 0 && height > 0 && dp > 0) { "the view needs a size" }
        require(fovDeg > 0 && fovDeg < MAX_FOV_DEG) { "fovDeg must be in (0, $MAX_FOV_DEG)" }
        require(tiltDeg in -90.0..90.0) { "tiltDeg must be in [-90, 90]" }
    }

    val cx: Double get() = width / 2
    val cy: Double get() = height / 2

    /** The focal length, px: half the width over the tangent of half the field of view. */
    val focalPx: Double = width / 2 / tan(fovDeg / 2 * DEG)

    /** px per degree at the centre of the view (the edges have more). */
    val pxPerDegAtCentre: Double get() = focalPx * DEG

    // The camera's axes in east, north, up: forward, right and up after the roll.
    private val fwd: DoubleArray
    private val right: DoubleArray
    private val up: DoubleArray

    init {
        val b = bearingDeg * DEG
        val t = tiltDeg * DEG
        val r = rollDeg * DEG
        fwd = doubleArrayOf(cos(t) * sin(b), cos(t) * cos(b), sin(t))
        val r0 = doubleArrayOf(cos(b), -sin(b), 0.0)
        val u0 = doubleArrayOf(-sin(t) * sin(b), -sin(t) * cos(b), cos(t))
        right = DoubleArray(3) { r0[it] * cos(r) - u0[it] * sin(r) }
        up = DoubleArray(3) { u0[it] * cos(r) + r0[it] * sin(r) }
    }

    private fun dot(a: DoubleArray, e: Double, n: Double, u: Double) = a[0] * e + a[1] * n + a[2] * u

    /** Where the direction ([elDeg], [azDeg]) falls on the screen's plane (px), on the view or past its edge; null behind the viewer. */
    fun project(elDeg: Double, azDeg: Double): RimPt? {
        val el = elDeg * DEG
        val az = azDeg * DEG
        val e = cos(el) * sin(az)
        val n = cos(el) * cos(az)
        val u = sin(el)
        val z = dot(fwd, e, n, u)
        if (z <= MIN_Z) return null
        return RimPt(cx + focalPx * dot(right, e, n, u) / z, cy - focalPx * dot(up, e, n, u) / z)
    }

    /** T324: the unit direction ([e] east, [n] north, [u] up) in the camera's axes (right, up, forward) into [out]. */
    fun toCamera(e: Double, n: Double, u: Double, out: DoubleArray) {
        out[0] = dot(right, e, n, u)
        out[1] = dot(up, e, n, u)
        out[2] = dot(fwd, e, n, u)
    }

    /**
     * T324: the x (px) where true bearing [azDeg] crosses the row [y]: the pixel of that row whose ray lies in the bearing's
     * vertical half-plane, or null when no ray of the row has that bearing. Exact, so the bearing tape's ticks need no
     * search.
     */
    fun xOnRow(azDeg: Double, y: Double): Double? {
        val az = azDeg * DEG
        // The bearing's vertical plane has the normal (cos az, -sin az, 0); the ray fwd + right sx + up sy lies in it.
        val ne = cos(az)
        val nn = -sin(az)
        val sy = -(y - cy) / focalPx
        val nr = ne * right[0] + nn * right[1]
        if (abs(nr) < 1e-12) return null
        val sx = -(ne * fwd[0] + nn * fwd[1] + sy * (ne * up[0] + nn * up[1])) / nr
        // The half-plane: the ray's level part points along the bearing, not opposite it.
        val de = fwd[0] + right[0] * sx + up[0] * sy
        val dn = fwd[1] + right[1] * sx + up[1] * sy
        if (de * sin(az) + dn * cos(az) <= 0) return null
        return cx + focalPx * sx
    }

    /** Whether [p] is on the view. */
    fun onView(p: RimPt): Boolean = p.x in 0.0..width && p.y in 0.0..height

    override fun mark(elDeg: Double, azDeg: Double): RimPt? = project(elDeg, azDeg)?.takeIf(::onView)

    /** The direction (elevation, true bearing 0 to 360, degrees) seen at pixel ([x], [y]). */
    fun ray(x: Double, y: Double): Pair<Double, Double> {
        val sx = (x - cx) / focalPx
        val sy = -(y - cy) / focalPx
        val e = fwd[0] + right[0] * sx + up[0] * sy
        val n = fwd[1] + right[1] * sx + up[1] * sy
        val u = fwd[2] + right[2] * sx + up[2] * sy
        val len = sqrt(e * e + n * n + u * u)
        val az = atan2(e, n) / DEG
        return asin((u / len).coerceIn(-1.0, 1.0)) / DEG to (az + 360) % 360
    }

    override fun away(at: RimPt, elDeg: Double, azDeg: Double): RimPt {
        // The screen's motion of the mark as its elevation grows: the projection's derivative along the meridian.
        val el = elDeg * DEG
        val az = azDeg * DEG
        val e = cos(el) * sin(az)
        val n = cos(el) * cos(az)
        val u = sin(el)
        val te = -sin(el) * sin(az)
        val tn = -sin(el) * cos(az)
        val tu = cos(el)
        val z = dot(fwd, e, n, u)
        val dz = dot(fwd, te, tn, tu)
        val x = dot(right, e, n, u)
        val y = dot(up, e, n, u)
        val dx = (dot(right, te, tn, tu) * z - x * dz) / (z * z)
        val dy = -(dot(up, te, tn, tu) * z - y * dz) / (z * z)
        val len = hypot(dx, dy)
        return if (len < 1e-12) RimPt(0.0, -1.0) else RimPt(dx / len, dy / len)
    }

    override fun separation(a: HorizonLabel, b: HorizonLabel): Double {
        val pa = mark(a.angleDeg, a.bearingDeg) ?: return Double.POSITIVE_INFINITY
        val pb = mark(b.angleDeg, b.bearingDeg) ?: return Double.POSITIVE_INFINITY
        return hypot(pa.x - pb.x, pa.y - pb.y) / (REF_DP_PER_DEG * dp)
    }

    companion object {
        private const val DEG = PI / 180
        private const val MIN_Z = 1e-6

        /** The widest field of view a lens takes, degrees (a perspective view's edges stretch without end toward 180). */
        const val MAX_FOV_DEG = 150.0

        /** The Horizon view's plot side at its usual size, dp: a Pixel 6 upright (`docs/reference/android-horizon-view.md`). */
        const val REF_PLOT_DP = 411.0

        /**
         * The reference scale of [separation], dp per degree: the round plot's rim at [REF_PLOT_DP] ([RimGeometry.of]'s
         * radius, the half side less room for the compass letters) per degree of bearing, about 3.17. A lens showing
         * that many dp per degree holds its names as far apart as the rim does.
         */
        val REF_DP_PER_DEG: Double = (REF_PLOT_DP / 2 - RimGeometry.COMPASS_DP * 1.6) * PI / 180
    }
}
