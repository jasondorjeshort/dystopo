package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T161's fix round: a steady 500 m/h climb on foot at 1 Hz with a pressure column (the walk replay's shape) shows its
 * elevation within 5 m of the truth and its climb rate, also when an input on another clock came first (a phone's
 * barometer sample stamped on the wall clock before a replay's first fix: the verifier's stall) and when the same
 * replay is run again without a reset.
 */
class ReadoutClimbTest {
    private val climb = 500.0 / 3600

    private class Run(val errAt: Map<Int, Double>, val climbAt: Map<Int, Readout>, val end: Readout)

    private fun run(est: ReadoutEstimator, sim: ReadoutSim, truth: List<ReadoutSim.Truth>): Run {
        val byT = sim.pressure(truth, sigma = 0.3).associateBy { it.timeMs }
        val err = linkedMapOf<Int, Double>()
        val at = linkedMapOf<Int, Readout>()
        for (f in sim.fixes(truth, eleSigma = 6.0, vAcc = 8.0)) {
            byT[f.time]?.let(est::onPressure)
            est.onFix(f)
            val i = ((f.time - truth[0].t) / 1000).toInt()
            if (i in CHECKS) {
                val r = est.readout(f.time)
                err[i] = r.elevation.value!! - truth[i].ele
                at[i] = r
            }
        }
        return Run(err, at, est.readout(truth.last().t))
    }

    private fun assertFollows(label: String, r: Run) {
        println("T161 $label: elevation error ${r.errAt.map { (i, e) -> "$i s ${(e * 10).toLong() / 10.0} m" }}, climb ${r.climbAt.map { (i, x) -> "$i s ${x.climb.state.code} ${x.climb.value?.let { (it * 3600).toLong() }} m/h" }}, counters ${r.end.counters}")
        for (i in listOf(60, 180)) {
            assertTrue(abs(r.errAt.getValue(i)) < 5, "$label: elevation error ${r.errAt[i]} m at $i s")
            val c = r.climbAt.getValue(i).climb
            assertEquals(ValueState.SHOWN, c.state, "$label: climb at $i s")
            assertTrue(abs(c.value!! - climb) < 0.03, "$label: climb ${c.value} m/s at $i s")
            assertEquals(BarometerState.IN_USE, r.climbAt.getValue(i).barometer.state, "$label: barometer at $i s")
        }
    }

    @Test
    fun steadyClimbOnFootFollowsTheTruthAndShowsTheClimb() {
        for (seed in 1..3) {
            val sim = ReadoutSim(seed)
            val truth = sim.journey(listOf(ReadoutSim.Leg(300, 1.0, climb = climb)), startEle = 1656.0)
            assertFollows("climb, seed $seed", run(ReadoutEstimator(Activity.FOOT), sim, truth))
        }
    }

    @Test
    fun aSampleOnAnotherClockDoesNotStallTheClimb() {
        for (seed in 1..3) {
            val sim = ReadoutSim(seed)
            val truth = sim.journey(listOf(ReadoutSim.Leg(300, 1.0, climb = climb)), startEle = 1656.0)
            val est = ReadoutEstimator(Activity.FOOT)
            // The phone's own barometer, stamped on the wall clock hours after the replay's file times.
            est.onPressure(PressureSample(ReadoutSim.T0 + 4 * 3_600_000L, 1013.25))
            val r = run(est, sim, truth)
            assertFollows("climb after a sample 4 h ahead, seed $seed", r)
            assertEquals(1L, r.end.counters["clockResets"])
        }
    }

    @Test
    fun aReplayRunAgainWithoutAResetStartsTheClockAfresh() {
        val sim = ReadoutSim(7)
        val truth = sim.journey(listOf(ReadoutSim.Leg(300, 1.0, climb = climb)), startEle = 1656.0)
        val est = ReadoutEstimator(Activity.FOOT)
        run(est, sim, truth)
        val again = run(est, sim, truth)
        assertFollows("the same replay run again", again)
        assertEquals(1L, again.end.counters["clockResets"])
        assertEquals(600L, again.end.counters["fixes"])
    }

    private companion object {
        val CHECKS = setOf(10, 25, 52, 60, 133, 169, 180, 299)
    }
}
