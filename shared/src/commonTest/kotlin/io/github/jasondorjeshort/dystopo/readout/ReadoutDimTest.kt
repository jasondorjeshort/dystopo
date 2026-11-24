package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T362: the readout's rule for the dim, a jump and a clock going back (`docs/findings/2026-11-23-the-readouts-dash-and-
 * blink-read-from-the-code.md`, its test list). Fixes near Paradise (a public place); elapsed time made up beside them.
 */
class ReadoutDimTest {
    private val t0 = ReadoutSim.T0
    private val e0 = 5_000_000L // an elapsed realtime unrelated to the wall clock

    private fun fix(sAt: Double, eastM: Double = 0.0, ele: Double? = 1650.0, wallShiftMs: Long = 0, acc: Double = 5.0, source: String = "gps"): LocationFix {
        val (lat, lon) = ReadoutSim.latLon(eastM, 0.0)
        return LocationFix(lat, lon, ele, t0 + (sAt * 1000).toLong() + wallShiftMs, accuracyM = acc, source = source, speedMps = 0.0, verticalAccuracyM = if (ele != null) 6.0 else null)
    }

    private fun el(sAt: Double) = e0 + (sAt * 1000).toLong()

    /** Feeds fixes at the elapsed seconds [at] (wall clock = elapsed + [wallShiftMs] from [shiftFrom]), reading every 0.5 s; returns the reads. */
    private fun run(est: ReadoutEstimator, at: List<Double>, endS: Double, ele: Double? = 1650.0, shiftFrom: Double = Double.MAX_VALUE, wallShiftMs: Long = 0): List<Pair<Double, Readout>> {
        val reads = ArrayList<Pair<Double, Readout>>()
        var i = 0
        var s = 0.0
        while (s <= endS) {
            while (i < at.size && at[i] <= s) {
                val shift = if (at[i] >= shiftFrom) wallShiftMs else 0L
                est.onFix(fix(at[i], ele = ele, wallShiftMs = shift), elapsedMs = el(at[i]))
                i++
            }
            val shift = if (s >= shiftFrom) wallShiftMs else 0L
            reads += s to est.readout(t0 + (s * 1000).toLong() + shift, el(s))
            s += 0.5
        }
        return reads
    }

    private fun toggles(reads: List<Pair<Double, Readout>>): Int =
        reads.map { it.second.elevation.dimmed }.zipWithNext().count { (a, b) -> a != b }

    @Test
    fun fixesInBurstsToggleTheDimOncePerRun() {
        // Steady, then three silences of 6 s with one fix between (the blink: dim, a fix, dim again), then steady.
        val at = (0..10).map { it.toDouble() } + listOf(16.0, 22.0, 28.0) + (29..40).map { it.toDouble() }
        val reads = run(ReadoutEstimator(Activity.FOOT), at, 40.0)
        val t = toggles(reads)
        println("T362 bursts: ${t} dim toggles; timeline ${reads.joinToString("") { if (it.second.elevation.dimmed) "D" else "." }}")
        assertEquals(2, t, "one dim, one release")
        // The fix after each silence keeps the dim, with its reason; the second of the run releases it.
        val at16 = reads.first { it.first == 16.0 }.second
        assertTrue(at16.elevation.dimmed)
        assertEquals(DimLatch.RESUMING, at16.elevation.reason)
        assertFalse(reads.first { it.first == 29.0 }.second.elevation.dimmed)
        assertEquals(3, reads.last().second.recent.count { it.kind == ReadoutFault.GAP })
    }

    @Test
    fun steadyFixesAreNeverDimmed() {
        val reads = run(ReadoutEstimator(Activity.FOOT), (0..300).map { it.toDouble() }, 300.0)
        for ((s, r) in reads.drop(1)) {
            assertFalse(r.elevation.dimmed || r.speed.dimmed || r.course.dimmed, "dimmed at $s s")
            assertNull(r.dimReason, "at $s s")
        }
        assertTrue(reads.last().second.recent.isEmpty())
    }

    @Test
    fun aGapOf21sIsDimmedWithNoFix() {
        val est = ReadoutEstimator(Activity.FOOT)
        val reads = run(est, (0..60).map { it.toDouble() } + listOf(81.0, 82.0), 83.0)
        val inGap = reads.first { it.first == 80.5 }.second
        assertTrue(inGap.elevation.dimmed)
        assertEquals(DimLatch.NO_FIX, inGap.elevation.reason)
        assertEquals(20.5, inGap.gapS)
        val ongoing = inGap.recent.last()
        assertTrue(ongoing.ongoing && ongoing.kind == ReadoutFault.GAP)
        val after = reads.first { it.first == 81.0 }.second
        assertNull(after.gapS)
        assertEquals(DimLatch.RESUMING, after.elevation.reason)
        val gap = after.recent.single { it.kind == ReadoutFault.GAP }
        assertEquals(21_000L, gap.lengthMs)
        assertEquals(21_000L, after.elapsedMs - gap.atElapsedMs)
        assertFalse(reads.first { it.first == 82.0 }.second.elevation.dimmed)
    }

    @Test
    fun aFusedBarometerIsNotDimmed() {
        val sim = ReadoutSim(3)
        val truth = sim.journey(listOf(ReadoutSim.Leg(300, 1.0, climb = 0.1)))
        val fixes = sim.fixes(truth)
        val pressure = sim.pressure(truth).associateBy { it.timeMs }
        val est = ReadoutEstimator(Activity.FOOT)
        for (f in fixes) {
            pressure[f.time]?.let { est.onPressure(it) }
            if ((f.time - t0) / 1000 >= 230) break
            if ((f.time - t0) / 1000 >= 200) continue // a 30 s gap of the fixes; the barometer carries on
            est.onFix(f)
        }
        val inGap = est.readout(t0 + 229_000)
        assertNotNull(inGap.gapS)
        assertEquals(BarometerState.IN_USE, inGap.barometer.state)
        assertTrue(inGap.speed.dimmed)
        assertFalse(inGap.elevation.dimmed, "the barometer carries the height")
        assertNull(inGap.elevation.reason)
    }

    @Test
    fun aJumpOntoFixesWithNoAltitudeKeepsTheHeight() {
        val est = ReadoutEstimator(Activity.FOOT)
        run(est, (0..120).map { it.toDouble() }, 120.0)
        val before = est.readout(t0 + 120_000, el(120.0)).elevation.value!!
        // A network fix 3 km away a second later, no altitude, then more of them.
        for (s in 121..130) est.onFix(fix(s.toDouble(), eastM = 3_000.0 + s, ele = null, acc = 30.0, source = "network"), elapsedMs = el(s.toDouble()))
        val r = est.readout(t0 + 130_000, el(130.0))
        assertEquals(1L, r.counters["jumpResets"])
        assertEquals(ValueState.SHOWN, r.elevation.state)
        assertFalse(r.elevation.dimmed)
        assertTrue(abs(r.elevation.value!! - before) < 1, "height ${r.elevation.value} kept from $before")
        val restart = assertNotNull(r.lastRestart)
        assertEquals(ReadoutFault.CAUSE_JUMP, restart.cause)
        assertTrue(abs(restart.detail!! - 3_121) < 5, "jump ${restart.detail} m")
        assertEquals(0L, r.counters["clockResets"])
    }

    @Test
    fun aGnssHeightFarFromTheKeptOneAfterAJumpReanchors() {
        val est = ReadoutEstimator(Activity.FOOT)
        run(est, (0..120).map { it.toDouble() }, 120.0)
        // A replay's first fix 3 km away and 250 m higher: the gate would reject it; the jump re-anchors on it.
        for (s in 121..125) est.onFix(fix(s.toDouble(), eastM = 3_000.0 + s, ele = 1900.0), elapsedMs = el(s.toDouble()))
        val r = est.readout(t0 + 125_000, el(125.0))
        assertEquals(1L, r.counters["jumpReanchors"])
        assertTrue(abs(r.elevation.value!! - 1900) < 15, "height ${r.elevation.value}")
        // Within 100 m the kept height is used as any other, no re-anchor.
        val near = ReadoutEstimator(Activity.FOOT)
        run(near, (0..120).map { it.toDouble() }, 120.0)
        near.onFix(fix(121.0, eastM = 3_000.0, ele = 1680.0), elapsedMs = el(121.0))
        assertEquals(0L, near.readout(t0 + 121_000, el(121.0)).counters["jumpReanchors"])
    }

    @Test
    fun aClockGoingBackHoldsTheHeight60sThenNoHeight() {
        val est = ReadoutEstimator(Activity.FOOT)
        run(est, (0..120).map { it.toDouble() }, 120.0)
        val before = est.readout(t0 + 120_000, el(120.0)).elevation.value!!
        // The fixes' clock goes back an hour (a replay run again from its start); no altitude on them.
        val back = -3_600_000L
        val reads = ArrayList<Pair<Double, Readout>>()
        for (s in 121..200) {
            est.onFix(fix(s.toDouble(), ele = null, wallShiftMs = back), elapsedMs = el(s.toDouble()))
            reads += s.toDouble() to est.readout(t0 + s * 1000L + back, el(s.toDouble()))
        }
        val r = reads.first { it.first == 150.0 }.second
        assertEquals(1L, r.counters["clockResets"])
        assertEquals(ValueState.SHOWN, r.elevation.state)
        assertTrue(r.elevation.dimmed)
        assertEquals(HeldHeight.REASON, r.elevation.reason)
        assertTrue(abs(r.elevation.value!! - before) < 0.01)
        assertEquals(ReadoutFault.CAUSE_CLOCK, r.lastRestart?.cause)
        assertTrue(reads.first { it.first == 181.0 }.second.elevation.state == ValueState.SHOWN, "held through 60 s")
        val late = reads.first { it.first == 182.0 }.second.elevation
        assertEquals(ValueState.HIDDEN, late.state)
        assertEquals("no height yet", late.reason)
        // A fix with a height ends the hold at once.
        est.onFix(fix(201.0, ele = 1700.0, wallShiftMs = back), elapsedMs = el(201.0))
        val got = est.readout(t0 + 201_000 + back, el(201.0)).elevation
        assertEquals(1700.0, got.value!!, 0.01)
        assertNull(got.reason)
    }

    @Test
    fun theWallClockSetBackAnHourWhileElapsedRunsSteadilyIsNoGap() {
        val est = ReadoutEstimator(Activity.FOOT)
        val reads = run(est, (0..200).map { it.toDouble() }, 200.0, shiftFrom = 100.0, wallShiftMs = -3_600_000L)
        for ((s, r) in reads.drop(1)) {
            assertNull(r.gapS, "gap at $s s")
            assertTrue(r.dimReason == null, "dim ${r.dimReason} at $s s")
            assertFalse(r.elevation.dimmed, "elevation dimmed at $s s")
            assertEquals(ValueState.SHOWN, r.elevation.state, "at $s s")
        }
        assertEquals(1L, reads.last().second.counters["clockResets"])
        assertTrue(reads.last().second.recent.none { it.kind == ReadoutFault.GAP })
    }

    @Test
    fun anActivityChangeDimsWithAReason() {
        val est = ReadoutEstimator(Activity.FOOT)
        run(est, (0..60).map { it.toDouble() }, 60.0)
        est.setProfile(ActivityProfile.of(Activity.BIKE))
        val r = est.readout(t0 + 60_500, el(60.5))
        assertTrue(r.elevation.dimmed)
        assertEquals(ReadoutFault.CAUSE_ACTIVITY, r.elevation.reason)
        assertEquals(ReadoutFault.CAUSE_ACTIVITY, r.lastRestart?.cause)
        est.onFix(fix(61.0), elapsedMs = el(61.0))
        val after = est.readout(t0 + 61_000, el(61.0))
        assertFalse(after.elevation.dimmed)
        assertNull(after.elevation.reason)
    }

    @Test
    fun theRecordKeepsTheBarometerGoingSilentAndALateFix() {
        val est = ReadoutEstimator(Activity.FOOT)
        for (s in 0..30) {
            if (s <= 10) est.onPressure(PressureSample(t0 + s * 1000L, 840.0), el(s.toDouble()))
            est.onFix(fix(s.toDouble()), elapsedMs = el(s.toDouble()))
        }
        est.onFix(fix(29.0), elapsedMs = el(30.5)) // not newer than the last: skipped
        val r = est.readout(t0 + 31_000, el(31.0))
        val silent = r.recent.single { it.kind == ReadoutFault.BAROMETER_SILENT }
        assertTrue(silent.lengthMs!! > ElevationFilter.BARO_FRESH_MS)
        assertEquals(1, r.recent.count { it.kind == ReadoutFault.LATE_FIX })
        val json = r.json()["recent"] as List<*>
        assertEquals(r.recent.size, json.size)
    }
}
