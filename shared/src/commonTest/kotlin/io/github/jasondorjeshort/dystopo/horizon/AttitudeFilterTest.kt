package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T329: the attitude filter ([AttitudeFilter]): the quarter turn, the still phone's jitter, the resting rule. */
class AttitudeFilterTest {
    private val ms = 1_000_000L
    private val north = PhoneAttitude.quatOf(0.0, 5.0, 0.0, 0)

    /** A filter settled on [q] at time 0 (still for a second). */
    private fun settled(q: Quat): AttitudeFilter.State {
        var s = AttitudeFilter.step(null, q, 0)
        for (k in 1..50) s = AttitudeFilter.step(s, q, k * 20 * ms)
        return s
    }

    /** [q] turned by a small random rotation, each axis Gaussian with [sigmaDeg]. */
    private fun jitter(q: Quat, sigmaDeg: Double, r: Random): Quat {
        fun g() = sqrt(-2 * kotlin.math.ln(1 - r.nextDouble())) * kotlin.math.cos(2 * kotlin.math.PI * r.nextDouble())
        val ax = g() * sigmaDeg
        val ay = g() * sigmaDeg
        val az = g() * sigmaDeg
        val a = sqrt(ax * ax + ay * ay + az * az)
        return if (a < 1e-12) q else Quats.mul(q, Quats.about(ax / a, ay / a, az / a, a))
    }

    @Test
    fun ninetyPercentOfAQuarterTurnWithin200Ms() {
        val start = settled(north)
        val east = PhoneAttitude.quatOf(90.0, 5.0, 0.0, 0)
        assertEquals(90.0, north.angleDegTo(east), 1e-9)
        var s = start
        var at = -1L
        for (k in 1..50) {
            val t = start.timeNanos + k * 20 * ms
            s = AttitudeFilter.step(s, east, t)
            if (at < 0 && s.filtered.angleDegTo(east) <= 9.0) at = (t - start.timeNanos) / ms
        }
        println("T329 filter: 90% of a quarter turn in $at ms at 20 ms samples")
        assertTrue(at in 1..200, "90% after $at ms")
        assertTrue(s.filtered.angleDegTo(east) < 0.5, "it gets there")
    }

    @Test
    fun aStillPhonesJitterIsCutToUnderAQuarter() {
        val r = Random(329)
        for (sigma in listOf(0.05, 0.2, 0.3)) {
            var s = settled(north)
            var rawSq = 0.0
            var outSq = 0.0
            var n = 0
            for (k in 1..1500) {
                val sample = jitter(north, sigma, r)
                s = AttitudeFilter.step(s, sample, s.timeNanos + 20 * ms)
                if (k > 100) {
                    rawSq += north.angleDegTo(sample).let { it * it }
                    outSq += north.angleDegTo(s.filtered).let { it * it }
                    n++
                }
            }
            val ratio = sqrt(outSq / n) / sqrt(rawSq / n)
            println("T329 filter: jitter $sigma deg per axis, spread ${sqrt(rawSq / n)} -> ${sqrt(outSq / n)} (ratio $ratio)")
            assertTrue(ratio < 0.25, "sigma $sigma: ratio $ratio")
        }
    }

    @Test
    fun theDrawnAimRestsAfter150MsWithinAThirdOfADegreeAndLeavesOnALargerMove() {
        var s = AttitudeFilter.step(null, north, 0)
        assertFalse(s.resting)
        // Still: rests once 150 ms have passed within 0.3 degrees, not before.
        for (k in 1..8) {
            s = AttitudeFilter.step(s, north, k * 20 * ms)
            assertEquals(k * 20 >= AttitudeFilter.REST_MS, s.resting, "at ${k * 20} ms")
        }
        assertTrue(s.resting)
        val restAt = s.drawn
        // No creep: a slow drift of 0.25 degrees over two seconds and a jitter leave the drawn aim where it rested.
        val r = Random(1)
        var t = s.timeNanos
        for (k in 1..100) {
            t += 20 * ms
            val drift = Quats.mul(north, Quats.about(0.0, 0.0, 1.0, 0.25 * k / 100))
            s = AttitudeFilter.step(s, jitter(drift, 0.03, r), t)
            assertTrue(s.resting, "still resting at step $k")
            assertEquals(restAt, s.drawn, "the drawn aim does not creep")
        }
        // A larger move: two degrees. It leaves rest, and the drawn aim follows the filter.
        val moved = Quats.mul(north, Quats.about(0.0, 0.0, 1.0, 2.0))
        var left = -1L
        for (k in 1..20) {
            t += 20 * ms
            s = AttitudeFilter.step(s, moved, t)
            if (!s.resting && left < 0) left = k * 20L
        }
        assertTrue(left in 1..200, "left rest after $left ms")
        // And rests again once it has settled there.
        for (k in 1..150) { t += 20 * ms; s = AttitudeFilter.step(s, moved, t) }
        assertTrue(s.resting)
        assertTrue(s.drawn.angleDegTo(moved) < AttitudeFilter.REST_DEG + 0.05)
    }

    @Test
    fun theTimeConstantFollowsTheTurnAndAGapStartsAgain() {
        assertEquals(AttitudeFilter.TAU_STILL_MS, AttitudeFilter.tauMs(0.2))
        assertEquals(AttitudeFilter.TAU_MOVING_MS, AttitudeFilter.tauMs(30.0))
        val mid = AttitudeFilter.tauMs((AttitudeFilter.STILL_GAP_DEG + AttitudeFilter.MOVING_GAP_DEG) / 2)
        assertEquals((AttitudeFilter.TAU_STILL_MS + AttitudeFilter.TAU_MOVING_MS) / 2, mid, 1e-9)
        val s = settled(north)
        val far = PhoneAttitude.quatOf(200.0, -30.0, 10.0, 0)
        val after = AttitudeFilter.step(s, far, s.timeNanos + 600 * ms)
        assertEquals(0.0, after.filtered.angleDegTo(far), 1e-6)
        assertFalse(after.resting)
    }
}
