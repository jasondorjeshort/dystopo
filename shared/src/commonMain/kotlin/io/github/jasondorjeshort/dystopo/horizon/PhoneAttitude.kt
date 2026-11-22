package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T329: a unit quaternion ([x], [y], [z], [w]) as Android's rotation vector gives it: the rotation from the phone's own
 * frame (x to the right of the screen held in its natural orientation, y up the screen, z out of the screen toward the
 * viewer) to the world's (east, north, up; north is magnetic for the rotation vector and the geomagnetic one).
 */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    infix fun dot(o: Quat) = x * o.x + y * o.y + z * o.z + w * o.w

    /** Whether this can be made a rotation: its length is finite and not zero (a raw sensor value may be neither). */
    val isRotation: Boolean
        get() = (x * x + y * y + z * z + w * w).let { it.isFinite() && it > 1e-18 }

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return Quat(x / l, y / l, z / l, w / l)
    }

    /** The angle of the rotation from this to [o], degrees (0 to 180; q and -q are the same rotation). */
    fun angleDegTo(o: Quat): Double = 2 * acos(abs(this dot o).coerceAtMost(1.0)) * 180 / PI

    /**
     * The rotation [t] of the way from this toward [o] along the shorter arc (spherical interpolation; [o] is negated
     * when it lies on the far hemisphere).
     */
    fun slerpToward(o: Quat, t: Double): Quat {
        var d = this dot o
        val b = if (d < 0) Quat(-o.x, -o.y, -o.z, -o.w).also { d = -d } else o
        if (d > 0.9995) return Quat(x + t * (b.x - x), y + t * (b.y - y), z + t * (b.z - z), w + t * (b.w - w)).normalized()
        val th = acos(d.coerceAtMost(1.0))
        val s = sin(th)
        val ka = sin((1 - t) * th) / s
        val kb = sin(t * th) / s
        return Quat(ka * x + kb * b.x, ka * y + kb * b.y, ka * z + kb * b.z, ka * w + kb * b.w).normalized()
    }
}

/**
 * T329: the phone's attitude for the Panorama's Follow (wave 46's choice (b)): Android hands over the rotation vector's
 * values and the display's rotation, and this turns them into the lens's three axes ([LensAxes]: the back of the phone,
 * the screen's right and up as the screen is drawn) with no Euler angles on the way, so nothing snaps near straight up
 * or down. The bearing, the tilt and the roll are derived only for labels ([LensAxes.angles]). Pure Kotlin.
 *
 * Frames: the world's axes are east, north, up; the rotation vector's north is magnetic, so its axes are [turned] by the
 * declination (the app's `Compass`) and the heading correction ([HeadingCorrection]) before the lens takes them.
 */
object PhoneAttitude {
    private const val DEG = PI / 180

    /** The display's rotations, degrees (Android's `Surface.ROTATION_0` to `ROTATION_270` times 90). */
    val ROTATIONS = listOf(0, 90, 180, 270)

    /**
     * The rotation vector's values (x, y, z and, when given, w; w is computed from the others when absent), normalized;
     * null when they are fewer than three or make no rotation (zero length, or a value not finite: [Quat.isRotation]).
     */
    fun quat(values: DoubleArray): Quat? {
        if (values.size < 3) return null
        val (x, y, z) = Triple(values[0], values[1], values[2])
        val w = if (values.size >= 4) values[3] else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
        return Quat(x, y, z, w).takeIf { it.isRotation }?.normalized()
    }

    /**
     * The lens's axes for the phone at [q] with the display turned [rotationDeg] (0, 90, 180 or 270: the screen's content
     * turned clockwise by that much to stay upright as the phone turns counter-clockwise). The back of the phone (-z) is
     * forward; the screen's right and up as drawn are the phone's x and y at 0; at 90 right is -y and up is x; at 180
     * right is -x and up is -y; at 270 right is y and up is -x. Held upright facing north with the display at 0
     * the axes are north, east and up (bearing 0, tilt 0, roll 0); on its side with the display turned to match, roll 0.
     */
    fun axes(q: Quat, rotationDeg: Int): LensAxes {
        val (x, y, z, w) = q
        // The rotation matrix's columns: the phone's x, y and z in east, north, up.
        val px = Enu(1 - 2 * y * y - 2 * z * z, 2 * x * y + 2 * z * w, 2 * x * z - 2 * y * w)
        val py = Enu(2 * x * y - 2 * z * w, 1 - 2 * x * x - 2 * z * z, 2 * y * z + 2 * x * w)
        val pz = Enu(2 * x * z + 2 * y * w, 2 * y * z - 2 * x * w, 1 - 2 * x * x - 2 * y * y)
        val (right, up) = when (rotationDeg) {
            0 -> px to py
            90 -> -py to px
            180 -> -px to -py
            270 -> py to -px
            else -> throw IllegalArgumentException("rotationDeg must be one of $ROTATIONS")
        }
        return LensAxes(-pz, right, up)
    }

    /** The bearing (in [axes]' north), tilt and roll for labels, the bearing held within [LensAxes.POLE_HOLD_DEG] of the poles. */
    fun angles(axes: LensAxes, heldBearingDeg: Double? = null): AttitudeAngles = axes.angles(heldBearingDeg)

    /**
     * The inverse: the rotation vector of a phone looking toward [bearingDeg] (in the frame's north), tilted [tiltDeg]
     * and rolled [rollDeg] on the screen as drawn with the display turned [rotationDeg]; for a simulated attitude. The
     * result has w >= 0.
     */
    fun quatOf(bearingDeg: Double, tiltDeg: Double, rollDeg: Double, rotationDeg: Int): Quat {
        val a = LensAxes.of(bearingDeg, tiltDeg, rollDeg)
        val (px, py) = when (rotationDeg) {
            0 -> a.right to a.up
            90 -> a.up to -a.right
            180 -> -a.right to -a.up
            270 -> -a.up to a.right
            else -> throw IllegalArgumentException("rotationDeg must be one of $ROTATIONS")
        }
        val pz = -a.forward
        return fromColumns(px, py, pz)
    }

    /**
     * [axes] turned about the vertical by [deg] (positive: toward the east, so the bearing grows by it): the declination
     * (magnetic to true) and the heading correction.
     */
    fun turned(axes: LensAxes, deg: Double): LensAxes {
        if (deg == 0.0) return axes
        val c = cos(deg * DEG)
        val s = sin(deg * DEG)
        fun t(v: Enu) = Enu(v.e * c + v.n * s, -v.e * s + v.n * c, v.u)
        return LensAxes(t(axes.forward), t(axes.right), t(axes.up))
    }

    /** The quaternion of the rotation whose matrix has the columns [c0], [c1], [c2] (Shepperd's method), with w >= 0. */
    private fun fromColumns(c0: Enu, c1: Enu, c2: Enu): Quat {
        val m00 = c0.e; val m10 = c0.n; val m20 = c0.u
        val m01 = c1.e; val m11 = c1.n; val m21 = c1.u
        val m02 = c2.e; val m12 = c2.n; val m22 = c2.u
        val tr = m00 + m11 + m22
        val q = when {
            tr > 0 -> {
                val s = sqrt(tr + 1) * 2
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, s / 4)
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1 + m00 - m11 - m22) * 2
                Quat(s / 4, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            m11 > m22 -> {
                val s = sqrt(1 + m11 - m00 - m22) * 2
                Quat((m01 + m10) / s, s / 4, (m12 + m21) / s, (m02 - m20) / s)
            }
            else -> {
                val s = sqrt(1 + m22 - m00 - m11) * 2
                Quat((m02 + m20) / s, (m12 + m21) / s, s / 4, (m10 - m01) / s)
            }
        }.normalized()
        return if (q.w < 0) Quat(-q.x, -q.y, -q.z, -q.w) else q
    }
}
