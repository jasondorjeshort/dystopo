package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T250: the barometer's three modes and Automatic's reasons, and the climb's three states (a figure, "?" for too little
 * data, a dash for sources that contradict). The replay the task names: a fixed cabin pressure and a descending GNSS
 * profile read a negative climb within seconds.
 */
class ReadoutBarometerModeTest {
    private fun est(a: Activity, mode: BarometerMode, windowS: Int = 60) =
        ReadoutEstimator(ActivityProfile.of(a), ReadoutOptions.defaults(a).copy(barometer = mode, climbWindowS = windowS))

    /** A descent from [startM] at [rate] m/s at [speed] m/s ground speed for [seconds], level for [levelS] before it. */
    private fun descent(sim: ReadoutSim, startM: Double, rate: Double, speed: Double, levelS: Int, seconds: Int) =
        sim.journey(listOf(ReadoutSim.Leg(levelS, speed), ReadoutSim.Leg(seconds, speed, climb = -rate)), startEle = startM)

    /** The cabin's pressure: fixed at a cabin altitude of [cabinM] (pressure altitude), with sensor noise. */
    private fun cabin(sim: ReadoutSim, truth: List<ReadoutSim.Truth>, cabinM: Double = 2000.0) =
        truth.map { PressureSample(it.t, ReadoutSim.hPaAt(cabinM + 0.3 * sim.gauss())) }

    private fun replay(e: ReadoutEstimator, fixes: List<io.github.jasondorjeshort.dystopo.store.LocationFix>, pressure: List<PressureSample>?): List<Readout> {
        val byT = pressure?.associateBy { it.timeMs }
        return fixes.map { f -> byT?.get(f.time)?.let(e::onPressure); e.onFix(f); e.readout(f.time) }
    }

    @Test
    fun aFixedCabinPressureAndADescentReadANegativeClimbWithinSecondsWhenFlying() {
        // An airliner's descent: 1500 ft/min (7.6 m/s) from 3000 m at 120 m/s, level for 2 min before it; the cabin
        // pressure fixed. Automatic with the flying activity: GNSS only, the climb negative within seconds.
        val sim = ReadoutSim(41)
        val truth = descent(sim, 3000.0, 7.6, 120.0, 120, 240)
        val fixes = sim.fixes(truth, posSigma = 5.0, eleSigma = 6.0, vAcc = 8.0)
        val r = replay(est(Activity.AIR, BarometerMode.AUTO), fixes, cabin(sim, truth))
        val firstNegative = (120 until r.size).firstOrNull { r[it].climb.state == ValueState.SHOWN && r[it].climb.value!! < 0 }
        val shownNeg = (150 until r.size).count { r[it].climb.state == ValueState.SHOWN && r[it].climb.value!! < 0 }
        println("T250 flight replay: first negative climb ${firstNegative?.minus(120)} s into the descent; shown negative ${shownNeg} of ${r.size - 150} s after 30 s; " +
            "barometer ${r.last().barometer.state.code} out ${r.last().barometer.out}; end climb ${r.last().climb.value?.let { it * 60 / 0.3048 }} ft/min; " +
            "level climb states ${(30 until 120).map { r[it].climb.state.code }.groupingBy { it }.eachCount()}")
        assertEquals(BarometerState.OFF, r.last().barometer.state)
        assertEquals("activity", r.last().barometer.out)
        assertEquals("auto", r.last().barometer.mode)
        assertEquals(0L, r.last().counters["baroUpdates"])
        assertTrue(firstNegative != null && firstNegative - 120 <= 20, "negative within 20 s (was ${firstNegative?.minus(120)})")
        assertTrue(shownNeg >= (r.size - 150) * 9 / 10, "negative most of the descent ($shownNeg)")
        assertTrue(abs(r.last().climb.value!! + 7.6) < 1.5, "the rate (${r.last().climb.value})")
        assertTrue(r.last().elevation.value!! < 3000 - 7.6 * 200, "the elevation follows the GNSS down (${r.last().elevation.value})")
    }

    @Test
    fun theAirOnGnssAloneShowsAModerateDescent() {
        // T161's follow-up (climb hidden most of the time in the air on GNSS alone): 500 and 1000 ft/min descents.
        for (fpm in listOf(500.0, 1000.0)) {
            val sim = ReadoutSim(47)
            val truth = descent(sim, 3000.0, fpm * 0.3048 / 60, 100.0, 120, 300)
            val r = replay(est(Activity.AIR, BarometerMode.AUTO), sim.fixes(truth, posSigma = 5.0, eleSigma = 6.0, vAcc = 8.0), null)
            val after = (180 until r.size).map { r[it].climb }
            val shown = after.count { it.state == ValueState.SHOWN && it.value!! < 0 }
            println("T250 (T161's note) $fpm ft/min descent on GNSS alone: shown negative $shown of ${after.size} s after the first minute; " +
                "states ${after.groupingBy { it.state.code }.eachCount()}; sigma ${r.last().climb.sigma?.let { it * 60 / 0.3048 }} ft/min; level ${(30 until 120).map { r[it].climb.state.code }.groupingBy { it }.eachCount()}")
            assertTrue(after.none { it.state == ValueState.ZERO }, "never 0 while descending at $fpm ft/min")
            if (fpm >= 1000) assertTrue(shown >= after.size * 9 / 10, "1000 ft/min shown ($shown)")
        }
    }

    @Test
    fun alwaysUsesTheCabinPressureWrongByDesign() {
        val sim = ReadoutSim(41)
        val truth = descent(sim, 3000.0, 7.6, 120.0, 120, 240)
        val r = replay(est(Activity.AIR, BarometerMode.ALWAYS), sim.fixes(truth, posSigma = 5.0, eleSigma = 6.0, vAcc = 8.0), cabin(sim, truth))
        val end = r.last()
        println("T250 Always in a cabin: barometer ${end.barometer.state.code}, climb ${end.climb.state.code} ${end.climb.value}, suspects ${end.counters["suspects"]}")
        assertTrue(end.barometer.state == BarometerState.IN_USE || end.barometer.state == BarometerState.CALIBRATING, "${end.barometer.state}")
        assertNull(end.barometer.out)
        assertEquals(0L, end.counters["suspects"], "Always: no rule puts it out")
        assertTrue(end.counters.getValue("baroUpdates") > 100)
        assertTrue(end.climb.value == null || end.climb.value!! > -7.6 / 2, "the fixed cabin pressure holds the climb far from the descent (${end.climb.value})")
    }

    @Test
    fun neverAndTheActivityAreOffWithTheirReasons() {
        val sim = ReadoutSim(42)
        val truth = sim.journey(listOf(ReadoutSim.Leg(120, 1.2)))
        val fixes = sim.fixes(truth)
        val p = sim.pressure(truth)
        val never = replay(est(Activity.FOOT, BarometerMode.NEVER), fixes, p).last().barometer
        assertEquals(BarometerState.OFF, never.state)
        assertEquals("setting", never.out)
        assertEquals("never", never.mode)
        val road = replay(est(Activity.ROAD, BarometerMode.AUTO), fixes, p).last().barometer
        assertEquals("activity", road.out)
        val foot = replay(est(Activity.FOOT, BarometerMode.AUTO), fixes, p).last().barometer
        assertEquals(BarometerState.IN_USE, foot.state)
        assertNull(foot.out)
        // A change of mode takes effect at once.
        val e = est(Activity.FOOT, BarometerMode.AUTO)
        replay(e, fixes, p)
        e.options = e.options.copy(barometer = BarometerMode.NEVER)
        assertEquals("setting", e.readout(truth.last().t).barometer.out)
        e.options = e.options.copy(barometer = BarometerMode.AUTO)
        assertNull(e.readout(truth.last().t).barometer.out)
    }

    @Test
    fun aDisagreementPutsTheBarometerOutAtOnceAndItReturnsAfterAgreeing() {
        // On foot on the flat; from 3 min the pressure altitude's rate eases up to 1.5 m/s over a minute, holds it to 6 min
        // and eases back to 0 by 7 min (a lift or a cabin the GNSS does not see; eased, so the jitter rule stays quiet).
        // Automatic: out at the first disagreeing window, the climb a dash meanwhile; back after two agreeing ones.
        val sim = ReadoutSim(43)
        val truth = sim.journey(listOf(ReadoutSim.Leg(900, 1.2)))
        val fixes = sim.fixes(truth, eleSigma = 3.0, vAcc = 5.0)
        val t0 = truth.first().t
        val p = sim.pressure(truth) { t -> ramp((t - t0) / 1000.0) }
        val r = replay(est(Activity.FOOT, BarometerMode.AUTO), fixes, p)
        val outAt = r.indexOfFirst { it.barometer.out == "disagreement" }
        val backAt = (outAt until r.size).firstOrNull { r[it].barometer.out == null }
        val dashes = r.count { it.climb.state == ValueState.DASH }
        println("T250 disagreement: out at $outAt s (the ramp from 180 s), back at $backAt s (the ramp ended at 360 s); climb dashes $dashes s; " +
            "counters disagreements ${r.last().counters["disagreements"]} agreements ${r.last().counters["agreements"]}; end ${r.last().barometer.state.code}")
        assertTrue(outAt in 241..310, "out within the first window at the full rate ($outAt)")
        assertTrue(r.subList(0, outAt).none { it.barometer.out != null }, "nothing else put it out first")
        assertEquals("disagreement", r[outAt].barometer.reason)
        assertEquals(ValueState.DASH, r[outAt].climb.state)
        assertEquals("contradiction", r[outAt].climb.reason)
        assertTrue(backAt != null && backAt >= 420 + 60 * (ReadoutTuning.AGREE_WINDOWS - 1) && backAt <= 420 + 60 * (ReadoutTuning.AGREE_WINDOWS + 1), "back after agreeing ($backAt)")
        assertEquals(1L, r.last().counters["disagreements"])
        assertEquals(1L, r.last().counters["agreements"])
        assertTrue(r.last().barometer.state == BarometerState.IN_USE || r.last().barometer.state == BarometerState.CALIBRATING)
        // While out the elevation followed the GNSS, not the climbing pressure.
        assertTrue(abs(r[backAt!! - 1].elevation.value!! - 1600) < 20, "elevation while out ${r[backAt - 1].elevation.value}")
        // The text: a dash with the unit while they contradict.
        assertEquals("– m/h", ReadoutText.of(r[outAt].climb, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.METERS)!!.full)
    }

    /** The pressure altitude's offset (m) at [s] seconds: 30, then the eased ramp of the disagreement tests. */
    private fun ramp(s: Double): Double {
        fun rate(x: Double) = 1.5 * when {
            x < 180 -> 0.0
            x < 240 -> (x - 180) / 60
            x < 360 -> 1.0
            x < 420 -> (420 - x) / 60
            else -> 0.0
        }
        var o = 30.0
        var x = 0.0
        while (x < s) { o += rate(x + 0.05) * minOf(0.1, s - x); x += 0.1 }
        return o
    }

    @Test
    fun alwaysKeepsTheBarometerThroughADisagreement() {
        val sim = ReadoutSim(43)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 1.2)))
        val t0 = truth.first().t
        val p = sim.pressure(truth) { t -> ramp((t - t0) / 1000.0) }
        val r = replay(est(Activity.FOOT, BarometerMode.ALWAYS), sim.fixes(truth, eleSigma = 3.0, vAcc = 5.0), p)
        assertTrue(r.none { it.barometer.out != null && it.barometer.out != "no samples" }, "never out: ${r.mapNotNull { it.barometer.out }.toSet()}")
        assertEquals(0L, r.last().counters["disagreements"])
    }

    @Test
    fun theClimbSaysWhatItKnows() {
        // With the barometer on the flat: "?" while the window fills, then 0 (the estimate can tell), standing too.
        val sim = ReadoutSim(44)
        val truth = sim.journey(listOf(ReadoutSim.Leg(240, 1.2), ReadoutSim.Leg(180, 0.0)))
        val fixes = sim.fixes(truth)
        val withBaro = replay(est(Activity.FOOT, BarometerMode.AUTO), fixes, sim.pressure(truth))
        assertEquals(ValueState.UNKNOWN, withBaro[10].climb.state)
        assertEquals("filling", withBaro[10].climb.reason)
        assertEquals("? m/h", ReadoutText.of(withBaro[10].climb, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.METERS)!!.full)
        for (i in 120 until 235) assertEquals(ValueState.ZERO, withBaro[i].climb.state, "flat walk with the barometer at $i s (${withBaro[i].climb.reason})")
        for (i in 330 until 420) assertEquals(ValueState.ZERO, withBaro[i].climb.state, "standing with the barometer at $i s (${withBaro[i].climb.reason})")
        assertEquals("still", withBaro[400].climb.reason)
        // Without it, on GNSS heights 6 m noisy, the flat walk's mean cannot tell 100 m/h from level: "?", not 0.
        val gnss = replay(est(Activity.FOOT, BarometerMode.NEVER), fixes, null)
        val states = (120 until 240).map { gnss[it].climb.state }
        println("T250 climb on GNSS alone, flat walk: ${states.groupingBy { it.code }.eachCount()}, sigma ${gnss[200].climb.sigma?.let { it * 3600 }} m/h, " +
            "its actual RMS ${kotlin.math.sqrt((120 until 240).sumOf { gnss[it].climb.value!!.let { v -> v * v } } / 120) * 3600} m/h")
        val slowFixes = ReadoutSim(44).let { sm -> sm.fixes(sm.journey(listOf(ReadoutSim.Leg(240, 1.2), ReadoutSim.Leg(180, 0.0))), slowSigma = 4.0) }
        val slow = replay(est(Activity.FOOT, BarometerMode.NEVER), slowFixes, null)
        println("T250 climb on GNSS alone with a 4 m slow error: RMS ${kotlin.math.sqrt((120 until 240).sumOf { slow[it].climb.value!!.let { v -> v * v } } / 120) * 3600} m/h, sigma ${slow[200].climb.sigma?.let { it * 3600 }} m/h")
        assertTrue(states.none { it == ValueState.ZERO }, "never 0 when it cannot tell")
        assertTrue(states.count { it == ValueState.UNKNOWN } > states.size * 9 / 10, "\"?\" on the flat on GNSS alone")
        assertEquals("uncertain", gnss[200].climb.reason)
        // A gap past the hold: "?" (no fix), not a dash.
        val e = est(Activity.FOOT, BarometerMode.AUTO)
        replay(e, fixes, sim.pressure(truth))
        val late = e.readout(truth.last().t + 120_000)
        assertEquals(ValueState.UNKNOWN, late.climb.state)
        assertEquals("no fix", late.climb.reason)
    }

    @Test
    fun aSilentBarometerIsNotInUse() {
        // T204's follow-up: the barometer falls silent; past its staleness window the height comes from the GNSS, and the
        // state no longer reads "in use".
        val sim = ReadoutSim(45)
        val truth = sim.journey(listOf(ReadoutSim.Leg(400, 1.2)))
        val fixes = sim.fixes(truth)
        val p = sim.pressure(truth).filter { it.timeMs < truth[200].t }
        val r = replay(est(Activity.FOOT, BarometerMode.AUTO), fixes, p)
        assertEquals(BarometerState.IN_USE, r[190].barometer.state)
        assertEquals(BarometerState.NONE, r[300].barometer.state)
        assertEquals("silent", r[300].barometer.out)
        assertEquals("gnss", r[300].elevation.source)
    }

    @Test
    fun gnssHeightsAreNotGatedAfterTheBarometerGoesStale() {
        // T197's follow-up: the barometer carried the height, falls silent, and the GNSS heights move 80 m away (a replay's
        // heights after the emulator's sensor): the elevation follows the GNSS within a few fixes.
        val sim = ReadoutSim(46)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 1.2)))
        val fixes = sim.fixes(truth).mapIndexed { i, f -> if (i >= 300) f.copy(ele = f.ele!! + 80) else f }
        val p = sim.pressure(truth).filter { it.timeMs < truth[290].t }
        val r = replay(est(Activity.FOOT, BarometerMode.AUTO), fixes, p)
        val followed = (300 until r.size).firstOrNull { abs(r[it].elevation.value!! - (1680.0)) < 15 }
        println("T250 (T197's note): elevation within 15 m of the moved GNSS ${followed?.minus(300)} s after the move; rejected ${r.last().counters["gnssRejected"]}, fallbacks ${r.last().counters["baroFallbacks"]}")
        assertTrue(followed != null && followed - 300 <= 10, "follows within 10 s (${followed?.minus(300)})")
    }
}
