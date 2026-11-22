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

/** T329: a direction or an axis in the local level frame: [e] east, [n] north, [u] up. */
data class Enu(val e: Double, val n: Double, val u: Double) {
    operator fun plus(o: Enu) = Enu(e + o.e, n + o.n, u + o.u)
    operator fun minus(o: Enu) = Enu(e - o.e, n - o.n, u - o.u)
    operator fun times(k: Double) = Enu(e * k, n * k, u * k)
    operator fun unaryMinus() = Enu(-e, -n, -u)
    infix fun dot(o: Enu) = e * o.e + n * o.n + u * o.u
    infix fun cross(o: Enu) = Enu(n * o.u - u * o.n, u * o.e - e * o.u, e * o.n - n * o.e)
    val length: Double get() = sqrt(e * e + n * n + u * u)
    fun unit(): Enu = length.let { l -> Enu(e / l, n / l, u / l) }
}

/** T329: a bearing, a tilt and a roll in degrees, the lens's conventions ([LensView]); for labels, the tape and the routes. */
data class AttitudeAngles(val bearingDeg: Double, val tiltDeg: Double, val rollDeg: Double)

/**
 * T329: a lens's three axes in east, north, up (choice (b) of wave 46): [forward] the way it looks (the back of the
 * phone), [right] and [up] the screen's right and up. Orthonormal and right-handed (right x up = -forward). The lens
 * takes these themselves, so nothing snaps near straight up or down; [angles] derives a bearing, a tilt and a roll only
 * for what is written.
 */
data class LensAxes(val forward: Enu, val right: Enu, val up: Enu) {
    /**
     * The bearing (0 to 360, in the frame's north: magnetic for the raw rotation vector, true after [PhoneAttitude.turned]
     * by the declination), the tilt (-90 to 90) and the roll ((-180, 180]; positive: the phone turned clockwise) of these
     * axes. Within [POLE_HOLD_DEG] of straight up or down the bearing is [heldBearingDeg] when given (the last one shown),
     * else the way the screen's top points looking down (its bottom looking up), so the label does not spin; the roll is
     * measured against that bearing.
     */
    fun angles(heldBearingDeg: Double? = null): AttitudeAngles {
        val f = forward
        val tilt = asin(f.u.coerceIn(-1.0, 1.0)) / DEG
        val nearPole = abs(tilt) > 90.0 - POLE_HOLD_DEG
        val bearing = when {
            !nearPole -> atan2(f.e, f.n) / DEG
            heldBearingDeg != null -> heldBearingDeg
            f.u < 0 -> atan2(up.e, up.n) / DEG
            else -> atan2(-up.e, -up.n) / DEG
        }.let { ((it % 360) + 360) % 360 }
        // The unrolled right for that bearing, made square to forward; the roll is the turn from it to the screen's right.
        val b = bearing * DEG
        val h = Enu(cos(b), -sin(b), 0.0)
        val r0 = (h - f * (h dot f)).unit()
        val u0 = r0 cross f
        val roll = atan2(-(right dot u0), right dot r0) / DEG
        return AttitudeAngles(bearing, tilt, if (roll <= -180.0) roll + 360 else roll)
    }

    companion object {
        private const val DEG = PI / 180

        /** Within this many degrees of straight up or down the bearing written is held (choice (b)). */
        const val POLE_HOLD_DEG = 2.0

        /**
         * The axes of a lens looking toward [bearingDeg], tilted [tiltDeg], rolled [rollDeg] ([LensView]'s conventions;
         * looking straight down the screen's top points along the bearing, straight up its bottom does).
         */
        fun of(bearingDeg: Double, tiltDeg: Double, rollDeg: Double): LensAxes {
            val b = bearingDeg * DEG
            val t = tiltDeg * DEG
            val r = rollDeg * DEG
            val fwd = Enu(cos(t) * sin(b), cos(t) * cos(b), sin(t))
            val r0 = Enu(cos(b), -sin(b), 0.0)
            val u0 = Enu(-sin(t) * sin(b), -sin(t) * cos(b), cos(t))
            return LensAxes(fwd, r0 * cos(r) - u0 * sin(r), u0 * cos(r) + r0 * sin(r))
        }
    }
}

/**
 * T322: the Panorama's lens, a perspective view [width] by [height] px at [dp] px per dp, looking toward true bearing
 * [bearingDeg] (wrapping), tilted [tiltDeg] from the horizontal (-90 straight down, +90 straight up), rolled [rollDeg]
 * (positive: the phone turned clockwise, so what stands to the right rises on the screen), [fovDeg] across the width.
 * The camera's up is the zenith's side at roll 0, so up on the screen is up in the world; looking straight down, the top
 * of the screen points along [bearingDeg].
 *
 * T329: made from [axes] ([of]) the lens looks along them and the three angles are only their written form
 * ([LensAxes.angles]); every projection reads [axes], so a roll and a view through the zenith are drawn right.
 *
 * As a [LabelProjection]: a mark below the horizon is placed where it falls, never clamped; [away] is toward the zenith
 * as projected (up at the level, unrolled); [separation] is the screen distance between two marks over [REF_DP_PER_DEG]
 * dp, so the density gap keeps a fixed size on the screen and a narrower view, spreading the marks, names more.
 */
class LensView internal constructor(
    val width: Double, val height: Double, override val dp: Double, val axes: LensAxes,
    val bearingDeg: Double, val tiltDeg: Double, val rollDeg: Double, val fovDeg: Double,
) : LabelProjection {
    constructor(width: Double, height: Double, dp: Double, bearingDeg: Double, tiltDeg: Double, rollDeg: Double, fovDeg: Double) :
        this(width, height, dp, LensAxes.of(bearingDeg, tiltDeg, rollDeg), bearingDeg, tiltDeg, rollDeg, fovDeg)

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
    private val fwd = doubleArrayOf(axes.forward.e, axes.forward.n, axes.forward.u)
    private val right = doubleArrayOf(axes.right.e, axes.right.n, axes.right.u)
    private val up = doubleArrayOf(axes.up.e, axes.up.n, axes.up.u)

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

        /**
         * T329: the lens looking along [axes] (choice (b): the phone's attitude, [PhoneAttitude.axes]), its angles
         * written by [LensAxes.angles] with [heldBearingDeg] held near the poles.
         */
        fun of(width: Double, height: Double, dp: Double, axes: LensAxes, fovDeg: Double, heldBearingDeg: Double? = null): LensView {
            val a = axes.angles(heldBearingDeg)
            return LensView(width, height, dp, axes, a.bearingDeg, a.tiltDeg.coerceIn(-90.0, 90.0), a.rollDeg, fovDeg)
        }

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
