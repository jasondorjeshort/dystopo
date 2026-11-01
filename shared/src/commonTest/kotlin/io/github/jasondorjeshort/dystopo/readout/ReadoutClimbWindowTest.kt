package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T177: the climb rate's window and its three states on the ground: a dash while standing still, 0 while walking on
 * the flat, a value on the climb; a dash while the window fills; the 5 min window smoother than the 1 min one over
 * undulating ground; the band scaled with the window; the air untouched.
 */
class ReadoutClimbWindowTest {
    private fun options(windowS: Int, a: Activity = Activity.FOOT) = ReadoutOptions.defaults(a).copy(climbWindowS = windowS)

    /** Feeds [truth] with a barometer, returns the readout at every second. */
    private fun run(windowS: Int, truth: List<ReadoutSim.Truth>, sim: ReadoutSim, a: Activity = Activity.FOOT): List<Readout> {
        val est = ReadoutEstimator(ActivityProfile.of(a), options(windowS, a))
        val byT = sim.pressure(truth, sigma = 0.3).associateBy { it.timeMs }
        val out = ArrayList<Readout>()
        for (f in sim.fixes(truth, eleSigma = 6.0, vAcc = 8.0)) {
            byT[f.time]?.let(est::onPressure)
            est.onFix(f)
            out += est.readout(f.time)
        }
        return out
    }

    private val climb = 500.0 / 3600

    @Test
    fun aDashWhileStillZeroOnTheFlatAndAValueOnTheClimb() {
        for (w in ReadoutOptions.CLIMB_WINDOWS_S) {
            val sim = ReadoutSim(11)
            // 3 min standing, 3 min walking on the flat, then 2 min plus the window climbing at 500 m/h.
            val legs = listOf(ReadoutSim.Leg(180, 0.0), ReadoutSim.Leg(180, 1.2), ReadoutSim.Leg(120 + w, 1.0, climb = climb))
            val r = run(w, ReadoutSim(11).journey(legs, startEle = 1600.0), sim)
            val states = r.map { it.climb.state }
            println("T177 window $w s: climb states ${states.groupingBy { it.code }.eachCount()}, at 20 s ${r[20].climb.state.code} (${r[20].climb.reason}), end ${(r.last().climb.value!! * 3600).toLong()} m/h")
            assertEquals(ValueState.DASH, r[20].climb.state, "window $w: filling at 20 s")
            assertEquals("filling", r[20].climb.reason)
            for (i in 60 until 175) assertEquals(ValueState.DASH, r[i].climb.state, "window $w: standing at $i s (${r[i].climb.reason})")
            assertEquals("still", r[170].climb.reason)
            // On the flat: 0 once the window holds the walk (a mean over a window that still holds standing is still 0).
            for (i in 200 until 355) assertEquals(ValueState.ZERO, r[i].climb.state, "window $w: flat walk at $i s (${r[i].climb.value})")
            val end = r.last().climb
            assertEquals(ValueState.SHOWN, end.state, "window $w: on the climb")
            assertTrue(abs(end.value!! - climb) < 0.03, "window $w: climb ${end.value!! * 3600} m/h")
            assertEquals(w, r.last().climbWindowS)
            assertEquals(w.toDouble(), r.last().climbFilledS)
            // The text: "0 m/h" on the flat, a dash while standing.
            val o = options(w)
            assertEquals("0 m/h", ReadoutText.of(r[300].climb, o, ContourUnits.METERS)!!.full)
            assertEquals("– m/h", ReadoutText.of(r[100].climb, o, ContourUnits.METERS)!!.full)
        }
    }

    @Test
    fun aLongerWindowFillsAndThenShowsWhatItHolds() {
        val sim = ReadoutSim(12)
        val truth = sim.journey(listOf(ReadoutSim.Leg(200, 1.0, climb = climb)), startEle = 1600.0)
        val r = run(600, truth, ReadoutSim(12))
        assertEquals(ValueState.DASH, r[25].climb.state)
        assertEquals(ValueState.SHOWN, r[60].climb.state, "10 min window: a value after 30 s of data, over what is held")
        assertTrue(r[60].climbFilledS!! in 30.0..60.0, "filled ${r[60].climbFilledS}")
    }

    @Test
    fun theFiveMinuteWindowSmoothsUndulatingGround() {
        // Up and down 30 m every 4 min on a gentle 300 m/h net climb: the 1 min figure swings, the 5 min one holds.
        val period = 240
        val legs = (0 until 1800).map { s -> ReadoutSim.Leg(1, 1.1, climb = 300.0 / 3600 + 30 * PI / period * sin(2 * PI * s / period)) }
        val series = LinkedHashMap<Int, List<Double>>()
        for (w in listOf(60, 300)) {
            val sim = ReadoutSim(13)
            val truth = sim.journey(legs, startEle = 1600.0)
            val r = run(w, truth, ReadoutSim(13))
            series[w] = r.drop(600).map { (it.climb.value ?: 0.0) * 3600 }
        }
        fun sd(x: List<Double>): Double { val m = x.average(); return sqrt(x.sumOf { (it - m) * (it - m) } / x.size) }
        val sd1 = sd(series.getValue(60))
        val sd5 = sd(series.getValue(300))
        println("T177 undulating: climb sd 1 min ${sd1.toLong()} m/h, 5 min ${sd5.toLong()} m/h; means ${series.getValue(60).average().toLong()} and ${series.getValue(300).average().toLong()} m/h")
        println("T177 undulating series (every 30 s, m/h): 1 min ${series.getValue(60).filterIndexed { i, _ -> i % 30 == 0 }.map { it.toLong() }}")
        println("T177 undulating series (every 30 s, m/h): 5 min ${series.getValue(300).filterIndexed { i, _ -> i % 30 == 0 }.map { it.toLong() }}")
        assertTrue(sd5 < sd1 / 3, "5 min sd $sd5 against 1 min $sd1")
        assertTrue(abs(series.getValue(300).average() - 300) < 30, "5 min mean ${series.getValue(300).average()}")
    }

    @Test
    fun theBandScalesWithTheWindowAndTheAirKeepsItsLag() {
        val g60 = ReadoutTuning.climbGate(Activity.FOOT, 60)
        assertEquals(50.0, g60.hideMps * 3600, 1e-9)
        assertEquals(100.0, g60.showMps * 3600, 1e-9)
        assertEquals(ReadoutTuning.climbGate(Activity.FOOT), g60, "the 1 min band is the old one")
        assertTrue(ReadoutTuning.climbGate(Activity.FOOT, 30).hideMps > g60.hideMps)
        assertEquals(22.36, ReadoutTuning.climbGate(Activity.FOOT, 300).hideMps * 3600, 0.01)
        assertEquals(15.81, ReadoutTuning.climbGate(Activity.FOOT, 600).hideMps * 3600, 0.01)
        assertEquals(ReadoutTuning.climbGate(Activity.AIR), ReadoutTuning.climbGate(Activity.AIR, 600))
        // The air has no window: climbWindowS null in the readout, whatever the option says.
        val sim = ReadoutSim(14)
        val truth = sim.journey(listOf(ReadoutSim.Leg(120, 60.0, climb = 5.0)), startEle = 300.0)
        val r = run(600, truth, ReadoutSim(14), Activity.AIR)
        assertEquals(null, r.last().climbWindowS)
        assertEquals(ValueState.SHOWN, r.last().climb.state)
    }

    /**
     * T192: the fixes stop on a climb. With the barometer still reading (a level phone, the emulator's fixed pressure)
     * and without one, the climb holds what it read at the last fix, dimmed and frozen, through the window, then is a
     * dash: never a decay toward zero.
     */
    @Test
    fun theClimbHoldsThroughAGapOfTheFixesThenDashes() {
        for (withBaro in listOf(true, false)) for (w in listOf(60, 300)) {
            val sim = ReadoutSim(23)
            val climbing = sim.journey(listOf(ReadoutSim.Leg(240 + w, 1.0, climb = climb)), startEle = 1600.0)
            val fixes = sim.fixes(climbing, eleSigma = 6.0, vAcc = 8.0)
            val est = ReadoutEstimator(ActivityProfile.of(Activity.FOOT), options(w))
            val byT = if (withBaro) sim.pressure(climbing, sigma = 0.3).associateBy { it.timeMs } else emptyMap()
            for (f in fixes) {
                byT[f.time]?.let(est::onPressure)
                est.onFix(f)
            }
            val lastT = fixes.last().time
            val atFix = est.readout(lastT).climb
            // GNSS alone over 1 min can read the climb as 0 (the band); either way the state and figure are held.
            if (withBaro) assertEquals(ValueState.SHOWN, atFix.state, "window $w: climbing at the last fix")
            assertTrue(atFix.state == ValueState.SHOWN || atFix.state == ValueState.ZERO, "baro $withBaro window $w: ${atFix.state}")
            // The fixes stop; the barometer goes on reading a level phone for twice the window.
            val level = climbing.last().ele
            val series = ArrayList<ReadoutValue>()
            for (s in 1..2 * w) {
                val t = lastT + s * 1000L
                if (withBaro) est.onPressure(PressureSample(t, ReadoutSim.hPaAt(level + 30.0)))
                series += est.readout(t).climb
            }
            val gapStart = 30 // past the gap rule (5 s, or three fix intervals)
            for (s in gapStart until w) {
                val c = series[s - 1]
                assertEquals(atFix.state, c.state, "baro $withBaro window $w: held at $s s (${c.reason})")
                assertEquals(atFix.value!!, c.value!!, 1e-9, "baro $withBaro window $w: the last fix's value at $s s")
                assertTrue(c.dimmed && c.frozen, "baro $withBaro window $w: dimmed and frozen at $s s")
            }
            for (s in w + 1..2 * w) {
                val c = series[s - 1]
                assertEquals(ValueState.DASH, c.state, "baro $withBaro window $w: a dash at $s s")
                assertEquals("no fix", c.reason)
                assertTrue(c.dimmed)
            }
            println("T192 baro $withBaro window $w s: last fix ${atFix.state.code} ${(atFix.value!! * 3600).toLong()} m/h, held to ${w} s, then ${series.last().state.code} (${series.last().reason})")
        }
    }
}
