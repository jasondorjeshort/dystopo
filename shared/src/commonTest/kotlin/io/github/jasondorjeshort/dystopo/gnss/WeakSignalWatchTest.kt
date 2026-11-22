package io.github.jasondorjeshort.dystopo.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T333: the recording's weak-signal watch over [GnssCases]' simulated skies. */
class WeakSignalWatchTest {
    private fun case(name: String, horizon: ((Double) -> Double)? = null) = assertNotNull(GnssCases.of(name, horizon))

    /** Feeds [sats] every [stepMs] for [minutes]; the times (ms) at which it fired. */
    private fun feedFor(w: WeakSignalWatch, sats: List<Satellite>, rim: ((Double) -> Double)?, minutes: Int, stepMs: Long = 5_000, from: Long = 0): List<Long> {
        val fired = ArrayList<Long>()
        var t = from
        while (t <= from + minutes * 60_000L) {
            if (w.feed(t, sats, rim)) fired += t
            t += stepMs
        }
        return fired
    }

    @Test
    fun faultFiresAtFiveMinutesAndOnce() {
        val w = WeakSignalWatch()
        val fired = feedFor(w, case(GnssCases.FAULT), null, minutes = 20)
        assertEquals(listOf(WeakSignalWatch.HOLD_MS), fired)
        assertTrue(w.fired)
        assertEquals(WeakSignalWatch.HOLD_MS, w.firedAt)
        val r = w.last!!
        assertTrue(r.above >= WeakSignalWatch.MIN_ABOVE, "above ${r.above}")
        assertTrue(r.strongest!! < 25.0)
    }

    @Test
    fun notBeforeFiveMinutes() {
        val w = WeakSignalWatch()
        assertEquals(emptyList(), feedFor(w, case(GnssCases.FAULT), null, minutes = 4))
        assertFalse(w.fired)
        assertEquals(240_000L, w.runMs(240_000L))
    }

    @Test
    fun otherCasesNeverFire() {
        for (name in listOf(GnssCases.OPEN, GnssCases.WEAK, GnssCases.INDOORS)) {
            val w = WeakSignalWatch()
            assertEquals(emptyList(), feedFor(w, case(name), null, minutes = 30), name)
            assertFalse(w.last!!.weak, name)
            assertFalse(WeakSignalWatch.raw(case(name)), "raw $name") // no rim is swept for them either
        }
        // The ridge case against its own rim and against no rim.
        for (rim in listOf(GnssCases.SYNTHETIC_RIDGE, null)) {
            val w = WeakSignalWatch()
            assertEquals(emptyList(), feedFor(w, GnssCases.ridge(GnssCases.SYNTHETIC_RIDGE), rim, minutes = 30), "ridge, rim ${rim != null}")
        }
    }

    @Test
    fun indoorsHasTooFewAbove() {
        val r = WeakSignalWatch().read(case(GnssCases.INDOORS), null)
        assertEquals(4, r.above)
        assertTrue(r.strongest!! < 25.0) // every one weak, but four: silent
        assertFalse(r.weak)
        assertFalse(WeakSignalWatch.raw(case(GnssCases.INDOORS)))
    }

    @Test
    fun weakCaseHasAStrongerSatellite() {
        val r = WeakSignalWatch().read(case(GnssCases.WEAK), null)
        assertTrue(r.strongest!! >= 25.0, "strongest ${r.strongest}")
        assertFalse(r.weak)
        assertFalse(WeakSignalWatch.raw(case(GnssCases.WEAK)))
    }

    @Test
    fun aStrongerSatelliteStartsTheCountAgain() {
        val w = WeakSignalWatch()
        val fault = case(GnssCases.FAULT)
        val oneStrong = fault.mapIndexed { i, s -> if (i == 0) s.copy(cn0 = 31.0) else s }
        assertEquals(emptyList(), feedFor(w, fault, null, minutes = 4))
        assertFalse(w.feed(245_000, oneStrong, null))
        assertNull(w.since)
        // Four more minutes of weak: still under five since the strong one.
        assertEquals(emptyList(), feedFor(w, fault, null, minutes = 4, from = 250_000))
        // Then it fires five minutes after the run began again.
        val fired = feedFor(w, fault, null, minutes = 3, from = 495_000)
        assertEquals(listOf(250_000L + WeakSignalWatch.HOLD_MS), fired)
    }

    @Test
    fun aStrongSatelliteBehindTheRimDoesNotCount() {
        val fault = case(GnssCases.FAULT)
        // One strong satellite low in the north, behind the synthetic ridge.
        val sats = fault + Satellite(Constellation.GPS, 31, 40.0, 10.0, 0.0, used = true, carrierHz = 1_575_420_000.0)
        val w = WeakSignalWatch()
        assertFalse(w.read(sats, null).weak) // the flat horizon: the strong one counts
        assertTrue(WeakSignalWatch.raw(sats)) // so the rim is swept
        assertTrue(w.read(sats, GnssCases.SYNTHETIC_RIDGE).weak) // behind the ridge it does not
    }

    @Test
    fun aRimThatLeavesFewerThanEightNeverFires() {
        // A rim of 60 degrees all round: every fault satellite but those above 62 degrees stands behind it.
        val high: (Double) -> Double = { 60.0 }
        val w = WeakSignalWatch()
        val r = w.read(case(GnssCases.FAULT), high)
        assertTrue(r.above < WeakSignalWatch.MIN_ABOVE, "above ${r.above}")
        assertEquals(emptyList(), feedFor(w, case(GnssCases.FAULT), high, minutes = 30))
        assertFalse(w.fired)
    }

    @Test
    fun aDualBandSatelliteCountsOnce() {
        val r = WeakSignalWatch().read(case(GnssCases.FAULT), null)
        val ids = case(GnssCases.FAULT).filter { it.tracked }.map { it.id }.distinct().size
        assertEquals(ids, r.above)
    }

    @Test
    fun resetForgetsTheFiring() {
        val w = WeakSignalWatch(holdMs = 60_000)
        assertEquals(listOf(60_000L), feedFor(w, case(GnssCases.FAULT), null, minutes = 2))
        w.restartRun()
        assertTrue(w.fired)
        w.reset()
        assertFalse(w.fired)
        assertEquals(listOf(60_000L), feedFor(w, case(GnssCases.FAULT), null, minutes = 2))
    }
}
