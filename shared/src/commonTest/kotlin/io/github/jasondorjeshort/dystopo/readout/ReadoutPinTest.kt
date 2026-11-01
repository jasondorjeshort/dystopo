package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T197's replays: a known height (a summit, a typed sign) pins the barometer's offset ([ReadoutEstimator.pin]); the
 * mark ([BarometerStatus.pinnedAt]) holds while the offset's sigma stays under twice the pin's and clears as the walk
 * grows it. Each prints its figures (the report's).
 */
class ReadoutPinTest {
    private val winding = { s: Int -> 90 + 40 * sin(s / 40.0) }

    private fun i(f: LocationFix) = ((f.time - ReadoutSim.T0) / 1000).toInt()

    /** Feeds [fixes] and [pressure] (one a second each), calling [at] before each readout (where a pin goes in). */
    private fun feed(est: ReadoutEstimator, fixes: List<LocationFix>, pressure: List<PressureSample>, at: (Int) -> Unit = {}, each: (Int, Readout) -> Unit) {
        val byT = pressure.associateBy { it.timeMs }
        for (f in fixes) {
            byT[f.time]?.let(est::onPressure)
            est.onFix(f)
            at(i(f))
            each(i(f), est.readout(f.time))
        }
    }

    /**
     * 20 min up to a summit with the GNSS heights 40 m high (a biased fix, as in a valley), so the fused estimate and
     * its offset sit 40 m off; a pin at the summit's height; then 2 h 40 min on, half standing and half walking down.
     */
    @Test
    fun aPinAtASummitPullsAFortyMetreOffsetInAtOnceAndTheMarkClearsAsSigmaGrows() {
        val sim = ReadoutSim(7)
        val legs = listOf(
            ReadoutSim.Leg(1200, 1.0, climb = 0.1, course = winding),
            ReadoutSim.Leg(4800, 0.0),
            ReadoutSim.Leg(4800, 1.0, climb = -0.08, course = winding),
        )
        val truth = sim.journey(legs, startEle = 1656.0)
        val pinAt = 1200
        val est = ReadoutEstimator(Activity.FOOT)
        var before = 0.0
        var after = 0.0
        var offsetAfter = 0.0
        var result: PinResult? = null
        var clearedAt: Int? = null
        var worstPinned = 0.0
        var sigmaAtClear = 0.0
        var lastPinned: Readout? = null
        feed(est, sim.fixes(truth, eleBias = 40.0), sim.pressure(truth), at = { s ->
            if (s == pinAt) result = est.pin("Test Summit", truth[s].ele, KnownHeights.SUMMIT_SIGMA_M, truth[s].t)
        }) { s, r ->
            val err = r.elevation.value!! - truth[s].ele
            if (s == pinAt - 1) before = err
            if (s == pinAt) {
                after = err
                offsetAfter = r.barometer.offsetM!! - 30.0
                assertTrue(r.barometer.pinned, "pinned at once")
                assertEquals("Test Summit", r.barometer.pinnedName)
                assertEquals(3.0, r.barometer.pinnedSigmaM)
            }
            if (s >= pinAt && r.barometer.pinned) {
                worstPinned = maxOf(worstPinned, abs(err))
                assertTrue(r.barometer.offsetSigmaM!! < 2 * 3.0 + 1e-9, "sigma under the rule while pinned at $s")
                lastPinned = r
            }
            if (s > pinAt && !r.barometer.pinned && clearedAt == null) {
                clearedAt = s
                sigmaAtClear = r.barometer.offsetSigmaM!!
                assertEquals("sigma", r.barometer.pinEnd)
            }
        }
        val res = assertNotNull(result)
        val heldMin = (clearedAt!! - pinAt) / 60.0
        println("T197 summit pin: elevation error ${r1(before)} m before, ${r1(after)} m after (moved ${r1(res.movedM!!)} m), offset error ${r1(offsetAfter)} m; worst error while pinned ${r1(worstPinned)} m; mark cleared ${r1(heldMin)} min after the pin at offset sigma ${r1(sigmaAtClear)} m; counters ${lastPinned?.counters}")
        assertTrue(res.held)
        assertTrue(abs(before) > 30, "the estimate sat 40 m off before the pin: $before")
        assertTrue(abs(after) < 3, "pulled in at once: $after")
        assertTrue(abs(offsetAfter) < 3, "the offset with it: $offsetAfter")
        assertTrue(worstPinned < 8, "the barometer holds the set height: worst $worstPinned")
        // The walk alone takes 3 m to 6 m in (36 - 9) / 25 h = 65 min; the pin's own variance after the update is a
        // little under 9 m², so a little over.
        assertTrue(heldMin in 60.0..72.0, "held $heldMin min")
        assertTrue(lastPinned!!.counters.getValue("gnssHeldByPin") > 100)
        assertEquals(0L, lastPinned!!.counters.getValue("pinReleases"))
    }

    /** A sign's 5 m holds three times as long on the walk alone: (100 - 25) / 25 h. */
    @Test
    fun aTypedSignHoldsAboutThreeHours() {
        val sim = ReadoutSim(8)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 0.0), ReadoutSim.Leg(12_000, 0.0)), startEle = 1656.0)
        val est = ReadoutEstimator(Activity.FOOT)
        var cleared: Int? = null
        var typed: BarometerStatus? = null
        feed(est, sim.fixes(truth), sim.pressure(truth), at = { s -> if (s == 600) est.pin(null, truth[s].ele, KnownHeights.SIGN_SIGMA_M, truth[s].t) }) { s, r ->
            if (s == 601) typed = r.barometer
            if (s > 600 && !r.barometer.pinned && cleared == null) cleared = s
        }
        val h = (cleared!! - 600) / 3600.0
        println("T197 typed sign: held ${r1(h * 60)} min; pinned status ${typed?.json()}")
        assertNull(typed!!.pinnedName, "a typed height has no name")
        assertTrue(typed!!.pinned)
        assertTrue(h in 2.8..3.2, "held $h h")
    }

    /** No barometer: the height is set once and the fixes take over; nothing is kept. */
    @Test
    fun withoutABarometerTheHeightIsSetOnceAndNotKept() {
        val sim = ReadoutSim(9)
        val truth = sim.journey(listOf(ReadoutSim.Leg(900, 0.0)), startEle = 1656.0)
        val est = ReadoutEstimator(Activity.FOOT)
        var result: PinResult? = null
        var right = 0.0
        feed(est, sim.fixes(truth, eleBias = 30.0), emptyList(), at = { s -> if (s == 300) result = est.pin("Test Summit", truth[s].ele, 3.0, truth[s].t) }) { s, r ->
            if (s == 300) right = r.elevation.value!! - truth[s].ele
            assertFalse(r.barometer.pinned)
        }
        val res = assertNotNull(result)
        println("T197 no barometer: held ${res.held}, reason ${res.reason}, error right after ${r1(right)} m, at the end ${r1(est.readout(truth.last().t).elevation.value!! - truth.last().ele)} m")
        assertFalse(res.held)
        assertEquals("no barometer", res.reason)
        assertTrue(abs(right) < 3)
    }

    /** A wrong pin (a tag 120 m off) is released by four GNSS heights in a row beyond 5 sigma: about two minutes. */
    @Test
    fun aWrongPinIsReleasedByTheGnss() {
        val sim = ReadoutSim(10)
        val truth = sim.journey(listOf(ReadoutSim.Leg(900, 0.0), ReadoutSim.Leg(900, 0.0)), startEle = 1656.0)
        val est = ReadoutEstimator(Activity.FOOT)
        var released: Int? = null
        feed(est, sim.fixes(truth), sim.pressure(truth), at = { s -> if (s == 900) est.pin("Wrong Summit", truth[s].ele + 120, 3.0, truth[s].t) }) { s, r ->
            if (s > 900 && !r.barometer.pinned && released == null) {
                released = s
                assertEquals("disagreement", r.barometer.pinEnd)
            }
        }
        val end = est.readout(truth.last().t)
        println("T197 wrong pin: released ${released?.minus(900)} s after; error at the end ${r1(end.elevation.value!! - truth.last().ele)} m; counters ${end.counters}")
        assertTrue(released != null && released!! - 900 in 60..200, "released after ${released?.minus(900)} s")
        assertEquals(1L, end.counters.getValue("pinReleases"))
        assertTrue(abs(end.elevation.value!! - truth.last().ele) < 15)
    }

    /** The barometer turned suspect (a car's speed) ends the pin at once. */
    @Test
    fun aSuspectBarometerEndsThePin() {
        val sim = ReadoutSim(11)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 0.0), ReadoutSim.Leg(120, 0.0), ReadoutSim.Leg(120, 20.0)), startEle = 1656.0)
        val est = ReadoutEstimator(Activity.FOOT)
        var endedAt: Int? = null
        feed(est, sim.fixes(truth), sim.pressure(truth), at = { s -> if (s == 600) est.pin("Test Summit", truth[s].ele, 3.0, truth[s].t) }) { s, r ->
            if (s > 600 && !r.barometer.pinned && endedAt == null) {
                endedAt = s
                assertEquals("barometer", r.barometer.pinEnd)
            }
        }
        println("T197 suspect barometer: the pin ended at ${endedAt} s (the drive from 720 s)")
        assertTrue(endedAt != null && endedAt!! in 720..740)
    }

    @Test
    fun aHeightOutOfRangeIsRefused() {
        val est = ReadoutEstimator(Activity.FOOT)
        assertFailsWith<IllegalArgumentException> { est.pin(null, 12_000.0, 5.0, ReadoutSim.T0) }
        assertFailsWith<IllegalArgumentException> { est.pin(null, 1000.0, 0.0, ReadoutSim.T0) }
        // Before any input the height is simply set.
        val r = est.pin(null, 1656.0, 5.0, ReadoutSim.T0)
        assertFalse(r.held)
        assertNull(r.movedM)
    }

    private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
}
