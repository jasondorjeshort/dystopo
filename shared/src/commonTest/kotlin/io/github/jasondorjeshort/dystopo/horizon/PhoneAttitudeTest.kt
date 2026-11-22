package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quaternion helpers for the attitude tests: the Hamilton product and a rotation about an axis. */
internal object Quats {
    fun mul(a: Quat, b: Quat) = Quat(
        a.w * b.x + b.w * a.x + (a.y * b.z - a.z * b.y),
        a.w * b.y + b.w * a.y + (a.z * b.x - a.x * b.z),
        a.w * b.z + b.w * a.z + (a.x * b.y - a.y * b.x),
        a.w * b.w - (a.x * b.x + a.y * b.y + a.z * b.z),
    )

    /** The rotation by [deg] about the unit axis ([x], [y], [z]) (right-handed). */
    fun about(x: Double, y: Double, z: Double, deg: Double): Quat {
        val h = deg * PI / 360
        return Quat(x * sin(h), y * sin(h), z * sin(h), cos(h))
    }

    /** A phone held upright facing north (the back of the phone north, its top up). */
    val UPRIGHT_NORTH = about(1.0, 0.0, 0.0, 90.0)
}

/** T329: the phone's attitude ([PhoneAttitude]): the axes, the angles, the inverse, the turn about the vertical. */
class PhoneAttitudeTest {
    private fun near(a: Double, b: Double, tol: Double, what: String = "") = assertTrue(abs(a - b) <= tol, "$what $a against $b")
    private fun nearDeg(a: Double, b: Double, tol: Double, what: String = "") = assertTrue(abs(PanoramaAim.signedDeg(a - b)) <= tol, "$what $a against $b")
    private fun near(a: Enu, b: Enu, tol: Double, what: String) = assertTrue((a - b).length <= tol, "$what $a against $b")

    private fun angles(q: Quat, rot: Int = 0, held: Double? = null) = PhoneAttitude.angles(PhoneAttitude.axes(q, rot), held)

    @Test
    fun uprightFacingNorthEastAndSouth() {
        val n = PhoneAttitude.axes(Quats.UPRIGHT_NORTH, 0)
        near(n.forward, Enu(0.0, 1.0, 0.0), 1e-12, "forward")
        near(n.right, Enu(1.0, 0.0, 0.0), 1e-12, "right")
        near(n.up, Enu(0.0, 0.0, 1.0), 1e-12, "up")
        for ((turn, want) in listOf(0.0 to 0.0, -90.0 to 90.0, 180.0 to 180.0)) {
            // Turned about the world's up (a turn of -90 takes north to east).
            val q = Quats.mul(Quats.about(0.0, 0.0, 1.0, turn), Quats.UPRIGHT_NORTH)
            val a = angles(q)
            nearDeg(a.bearingDeg, want, 1e-9, "bearing")
            near(a.tiltDeg, 0.0, 1e-9, "tilt"); near(a.rollDeg, 0.0, 1e-9, "roll")
        }
    }

    @Test
    fun onItsSideBothWaysWithTheDisplayTurnedIsRollZero() {
        // Turned counter-clockwise as the viewer sees it (about the phone's z, toward the viewer): its right edge up.
        val ccw = Quats.mul(Quats.UPRIGHT_NORTH, Quats.about(0.0, 0.0, 1.0, 90.0))
        val cw = Quats.mul(Quats.UPRIGHT_NORTH, Quats.about(0.0, 0.0, 1.0, -90.0))
        for ((q, rot) in listOf(ccw to 90, cw to 270)) {
            val a = angles(q, rot)
            nearDeg(a.bearingDeg, 0.0, 1e-9, "bearing at $rot"); near(a.tiltDeg, 0.0, 1e-9, "tilt at $rot"); near(a.rollDeg, 0.0, 1e-9, "roll at $rot")
            near(PhoneAttitude.axes(q, rot).up, Enu(0.0, 0.0, 1.0), 1e-12, "the screen's up is up at $rot")
        }
        // Without the display turned the same phones are rolled: counter-clockwise is -90, clockwise +90.
        near(angles(ccw, 0).rollDeg, -90.0, 1e-9, "ccw at 0")
        near(angles(cw, 0).rollDeg, 90.0, 1e-9, "cw at 0")
        // Upside down with the display at 180: roll 0.
        near(angles(Quats.mul(Quats.UPRIGHT_NORTH, Quats.about(0.0, 0.0, 1.0, 180.0)), 180).rollDeg, 0.0, 1e-9, "upside down at 180")
    }

    @Test
    fun lyingFlatFaceUpLooksStraightDownAndFaceDownStraightUp() {
        val up = angles(Quat(0.0, 0.0, 0.0, 1.0))
        near(up.tiltDeg, -90.0, 1e-9, "face up")
        nearDeg(up.bearingDeg, 0.0, 1e-9, "the screen's top points north"); near(up.rollDeg, 0.0, 1e-9)
        val axes = PhoneAttitude.axes(Quat(0.0, 0.0, 0.0, 1.0), 0)
        near(axes.forward, Enu(0.0, 0.0, -1.0), 1e-12, "forward is down")
        val down = angles(Quat(0.0, 1.0, 0.0, 0.0))
        near(down.tiltDeg, 90.0, 1e-9, "face down")
        nearDeg(down.bearingDeg, 180.0, 1e-9, "its bottom points south"); near(down.rollDeg, 0.0, 1e-9)
        // Held near the poles: the bearing given stands, and the roll is measured against it.
        val held = angles(Quat(0.0, 0.0, 0.0, 1.0), held = 30.0)
        assertEquals(30.0, held.bearingDeg)
        near(held.rollDeg, -30.0, 1e-9, "the top points north, 30 degrees anticlockwise of the held bearing (seen from above): roll -30")
    }

    @Test
    fun theInverseRoundTrips() {
        for (rot in PhoneAttitude.ROTATIONS) for (b in listOf(0.0, 47.0, 181.0, 359.5)) for (t in listOf(-80.0, -30.0, 0.0, 12.0, 85.0)) for (r in listOf(-170.0, -45.0, 0.0, 25.0, 90.0)) {
            val q = PhoneAttitude.quatOf(b, t, r, rot)
            near(hypot(hypot(q.x, q.y), hypot(q.z, q.w)), 1.0, 1e-12, "unit")
            assertTrue(q.w >= 0)
            val axes = PhoneAttitude.axes(q, rot)
            val want = LensAxes.of(b, t, r)
            near(axes.forward, want.forward, 1e-9, "forward"); near(axes.right, want.right, 1e-9, "right"); near(axes.up, want.up, 1e-9, "up")
            val a = PhoneAttitude.angles(axes)
            nearDeg(a.bearingDeg, b, 1e-6, "bearing $b $t $r $rot"); near(a.tiltDeg, t, 1e-6, "tilt"); nearDeg(a.rollDeg, r, 1e-6, "roll")
        }
        // The values as the sensor gives them, three or four.
        val q = PhoneAttitude.quatOf(30.0, 10.0, 5.0, 0)
        val three = PhoneAttitude.quat(doubleArrayOf(q.x, q.y, q.z))!!
        near(three.w, q.w, 1e-12, "w from the other three")
    }

    @Test
    fun valuesThatMakeNoRotationGiveNone() {
        kotlin.test.assertNull(PhoneAttitude.quat(doubleArrayOf(0.0, 0.0, 0.0, 0.0)))
        kotlin.test.assertNull(PhoneAttitude.quat(doubleArrayOf(Double.NaN, 0.0, 0.0, 1.0)))
        kotlin.test.assertNull(PhoneAttitude.quat(doubleArrayOf(0.0, Double.POSITIVE_INFINITY, 0.0)))
        kotlin.test.assertNull(PhoneAttitude.quat(doubleArrayOf(0.1, 0.2)))
        // Three values of length 1 or more leave w at 0: still a rotation (half a turn), made unit.
        val half = PhoneAttitude.quat(doubleArrayOf(0.0, 0.0, 2.0))!!
        assertEquals(Quat(0.0, 0.0, 1.0, 0.0), half)
        assertTrue(!Quat(0.0, 0.0, 0.0, 0.0).isRotation && Quat(0.0, 0.0, 0.0, 1.0).isRotation)
    }

    @Test
    fun aTurnAboutTheVerticalAddsToTheBearingAndLeavesTiltAndRoll() {
        val axes = LensAxes.of(350.0, 20.0, 15.0)
        val a = PhoneAttitude.angles(PhoneAttitude.turned(axes, 15.5))
        nearDeg(a.bearingDeg, 5.5, 1e-9, "east by the declination"); near(a.tiltDeg, 20.0, 1e-9); near(a.rollDeg, 15.0, 1e-9)
        nearDeg(PhoneAttitude.angles(PhoneAttitude.turned(axes, -20.0)).bearingDeg, 330.0, 1e-9)
    }

    @Test
    fun aFixedDirectionMovesSmoothlyAsThePhoneTurnsThroughTheZenithAndTheNadir() {
        // The phone pitched about its own x (the screen's right) all the way round: its back passes straight up and straight down.
        val w = 1080.0
        val h = 810.0
        val dirs = listOf(85.0 to 20.0, 89.5 to 200.0, -86.0 to 300.0, -89.9 to 10.0)
        for (rot in listOf(0, 90)) {
            val start = PhoneAttitude.quatOf(0.0, 0.0, 0.0, rot)
            var prevAim: PanoramaAim? = null
            val prev = HashMap<Int, RimPt>()
            var passedUp = false
            var passedDown = false
            var k = 0
            val axis = if (rot == 0) Triple(1.0, 0.0, 0.0) else Triple(0.0, -1.0, 0.0)
            while (k <= 3600) {
                val q = Quats.mul(start, Quats.about(axis.first, axis.second, axis.third, k * 0.1))
                val aim = PanoramaAim.follow(PhoneAttitude.axes(q, rot), 60.0, prevAim?.bearingDeg)
                passedUp = passedUp || aim.tiltDeg > 89.95
                passedDown = passedDown || aim.tiltDeg < -89.95
                val lens = aim.lens(w, h, 2.625)
                val px1Deg = lens.pxPerDegAtCentre
                for ((i, d) in dirs.withIndex()) {
                    val p = lens.project(d.first, d.second)?.takeIf { abs(it.x - w / 2) < 2 * w && abs(it.y - h / 2) < 2 * w }
                    val q0 = prev[i]
                    if (p != null && q0 != null) {
                        val jump = hypot(p.x - q0.x, p.y - q0.y)
                        assertTrue(jump < px1Deg, "rotation $rot, step $k (tilt ${aim.tiltDeg}): $d jumped $jump px")
                    }
                    if (p != null) prev[i] = p else prev.remove(i)
                }
                // The written bearing is held near the poles: it never moves while the tilt is within 2 degrees of them.
                prevAim?.let { pa -> if (abs(aim.tiltDeg) > 88.0 && abs(pa.tiltDeg) > 88.0) assertEquals(pa.bearingDeg, aim.bearingDeg) }
                prevAim = aim
                k++
            }
            assertTrue(passedUp && passedDown, "rotation $rot: through the zenith $passedUp, the nadir $passedDown")
        }
    }
}
