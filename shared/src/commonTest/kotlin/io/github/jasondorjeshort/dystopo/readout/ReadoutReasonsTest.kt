package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T363: the readout's reasons are a closed set ([ReadoutReason]) and every one a drawn value can show is reached: each
 * (entry, reason) of [ReadoutReasons.POSSIBLE] and each entry's every state ([ReadoutReasons.STATES]) by a fixture run
 * through the estimator, and nothing reached outside them (a new state or reason fails here until it is declared, and the
 * phone's `ReasonTextTest` then fails until it has a sentence). Journeys near Paradise (a public place), [ReadoutSim].
 */
class ReadoutReasonsTest {
    private val all = ReadoutOptions.defaults(Activity.FOOT).let { o ->
        o.copy(accuracy = EntryOptions(shown = true), fixAge = EntryOptions(shown = true))
    }

    private fun est(mode: BarometerMode) = ReadoutEstimator(ActivityProfile.of(Activity.FOOT), all.copy(barometer = mode))

    /** What the fixtures reached: (entry, reason) and (entry, state). */
    private class Seen {
        val reasons = LinkedHashSet<Pair<ReadoutEntry, ReadoutReason>>()
        val states = LinkedHashSet<Pair<ReadoutEntry, ValueState>>()
        val where = LinkedHashMap<Pair<ReadoutEntry, ReadoutReason>, String>()

        fun see(r: Readout, fixture: String) {
            for (e in ReadoutEntry.entries) {
                val v = r[e]
                states += e to v.state
                val why = ReadoutReasons.of(v, r.dimReason) ?: continue
                reasons += e to why
                where.getOrPut(e to why) { fixture }
            }
        }
    }

    /** Feeds [fixes] and [pressure] in time order, reading once a second from the first input to [endMs] (or the last input). */
    private fun replay(e: ReadoutEstimator, fixes: List<LocationFix>, pressure: List<PressureSample>, seen: Seen, name: String, endMs: Long? = null, skip: (Long) -> Boolean = { false }) {
        val start = minOf(fixes.firstOrNull()?.time ?: Long.MAX_VALUE, pressure.firstOrNull()?.timeMs ?: Long.MAX_VALUE)
        val end = endMs ?: maxOf(fixes.lastOrNull()?.time ?: 0L, pressure.lastOrNull()?.timeMs ?: 0L)
        var i = 0
        var j = 0
        var t = start
        while (t <= end) {
            while (i < fixes.size && fixes[i].time <= t) { if (!skip(fixes[i].time)) e.onFix(fixes[i]); i++ }
            while (j < pressure.size && pressure[j].timeMs <= t) { if (!skip(pressure[j].timeMs)) e.onPressure(pressure[j]); j++ }
            seen.see(e.readout(t), name)
            t += 1000
        }
    }

    @Test
    fun everyReasonAndEveryStateIsReachedAndNothingElse() {
        val seen = Seen()
        val t0 = ReadoutSim.T0

        // Before any input: no fix, no height, no pressure.
        seen.see(est(BarometerMode.AUTO).readout(t0), "start")

        // With the barometer: the climb's window filling, then 0 on the flat and standing; pressure shown, then silent.
        run {
            val sim = ReadoutSim(44)
            val truth = sim.journey(listOf(ReadoutSim.Leg(240, 1.2), ReadoutSim.Leg(180, 0.0)))
            val e = est(BarometerMode.AUTO)
            replay(e, sim.fixes(truth), sim.pressure(truth).filter { it.timeMs < truth.last().t - 60_000 }, seen, "barometer walk")
        }

        // GNSS alone: a climb that reads a figure, then a stop (the course held from the last movement, the speed's dash),
        // a 20 s gap (no fix: every figure dimmed, the climb held), the fixes back (resuming), a 90 s gap (the climb "?",
        // no fix), then the flat (too uncertain).
        run {
            val sim = ReadoutSim(45)
            val truth = sim.journey(listOf(ReadoutSim.Leg(180, 1.0, 0.3), ReadoutSim.Leg(60, 0.0), ReadoutSim.Leg(400, 1.2)))
            val gaps = listOf(t0 + 260_000 until t0 + 280_000, t0 + 300_000 until t0 + 390_000)
            replay(est(BarometerMode.NEVER), sim.fixes(truth), emptyList(), seen, "gnss walk with gaps") { t -> gaps.any { t in it } }
        }

        // Fixes with no speed, no course and no accuracy, standing from the start: no speed yet, no course yet, not given.
        run {
            val e = est(BarometerMode.NEVER)
            for (s in 0 until 10) {
                val (lat, lon) = ReadoutSim.latLon(0.0, 0.0)
                e.onFix(LocationFix(lat, lon, 1650.0, t0 + s * 1000L, accuracyM = null, verticalAccuracyM = 6.0))
                seen.see(e.readout(t0 + s * 1000L), "standing, bare fixes")
            }
        }

        // The activity changed: dimmed until the next fix.
        run {
            val sim = ReadoutSim(46)
            val truth = sim.journey(listOf(ReadoutSim.Leg(60, 1.2)))
            val e = est(BarometerMode.NEVER)
            replay(e, sim.fixes(truth), emptyList(), seen, "before the activity changed")
            e.setProfile(ActivityProfile.of(Activity.FOOT), all.copy(barometer = BarometerMode.NEVER))
            seen.see(e.readout(truth.last().t + 500), "activity changed")
        }

        // The clock went back: the last height held.
        run {
            val sim = ReadoutSim(47)
            val truth = sim.journey(listOf(ReadoutSim.Leg(60, 1.2)))
            val e = est(BarometerMode.NEVER)
            replay(e, sim.fixes(truth), emptyList(), seen, "before the clock went back")
            val back = truth.last().t - 3_600_000
            val (lat, lon) = ReadoutSim.latLon(0.0, 0.0)
            e.onFix(LocationFix(lat, lon, null, back, accuracyM = 5.0), elapsedMs = truth.last().t + 1000)
            seen.see(e.readout(back, truth.last().t + 1500), "clock went back")
        }

        // The barometer and the GNSS disagree: the climb's dash (ReadoutBarometerModeTest's ramp).
        run {
            val sim = ReadoutSim(43)
            val truth = sim.journey(listOf(ReadoutSim.Leg(420, 1.2)))
            val start = truth.first().t
            replay(est(BarometerMode.AUTO), sim.fixes(truth, eleSigma = 3.0, vAcc = 5.0), sim.pressure(truth) { t -> ramp((t - start) / 1000.0) }, seen, "disagreement")
        }

        println("T363 reasons reached: ${seen.where.entries.joinToString { "${it.key.first.code}/${it.key.second.code} (${it.value})" }}")
        val missing = ReadoutReasons.POSSIBLE - seen.reasons
        val undeclared = seen.reasons - ReadoutReasons.POSSIBLE
        assertTrue(missing.isEmpty(), "declared but no fixture reaches: $missing")
        assertTrue(undeclared.isEmpty(), "reached but not declared in ReadoutReasons.POSSIBLE (give it a sentence): $undeclared")
        val wantStates = ReadoutReasons.STATES.flatMap { (e, ss) -> ss.map { e to it } }.toSet()
        assertEquals(wantStates - seen.states, emptySet(), "states no fixture reaches")
        assertEquals(seen.states - wantStates, emptySet(), "states reached but not declared in ReadoutReasons.STATES")
    }

    @Test
    fun whichReasonAValueShowsAndWhichTheBarMarks() {
        fun v(e: ReadoutEntry, s: ValueState, r: ReadoutReason? = null, dimmed: Boolean = false, frozen: Boolean = false) =
            ReadoutValue(e, s, if (s == ValueState.SHOWN) 1.0 else null, dimmed = dimmed, frozen = frozen, reason = r)
        // A plain figure, a 0 and a value switched off say nothing.
        assertNull(ReadoutReasons.of(v(ReadoutEntry.ELEVATION, ValueState.SHOWN), null))
        assertNull(ReadoutReasons.of(v(ReadoutEntry.CLIMB, ValueState.ZERO, ReadoutReason.STILL), null))
        assertNull(ReadoutReasons.of(v(ReadoutEntry.SPEED, ValueState.HIDDEN, ReadoutReason.OFF), null))
        // Before the first readout: no fix.
        assertEquals(ReadoutReason.NO_FIX, ReadoutReasons.of(null, null))
        // A dimmed value shows the dim's reason, a held one "held".
        assertEquals(ReadoutReason.RESUMING, ReadoutReasons.of(v(ReadoutEntry.SPEED, ValueState.SHOWN, dimmed = true), ReadoutReason.RESUMING))
        assertEquals(ReadoutReason.NO_FIX, ReadoutReasons.of(v(ReadoutEntry.CLIMB, ValueState.UNKNOWN, ReadoutReason.FILLING, dimmed = true), ReadoutReason.NO_FIX))
        assertEquals(ReadoutReason.HELD, ReadoutReasons.of(v(ReadoutEntry.CLIMB, ValueState.SHOWN, ReadoutReason.HELD, dimmed = true), ReadoutReason.NO_FIX))
        // The course held from the last movement.
        assertEquals(ReadoutReason.STILL, ReadoutReasons.of(v(ReadoutEntry.COURSE, ValueState.SHOWN, frozen = true), null))
        // The mark: missing or stale, never at rest, never on a value that reads "?" already.
        assertTrue(ReadoutReasons.marked(ReadoutReason.NO_FIX, readsUnknown = false))
        assertTrue(ReadoutReasons.marked(ReadoutReason.SILENT, readsUnknown = false))
        assertFalse(ReadoutReasons.marked(ReadoutReason.STILL, readsUnknown = false))
        assertFalse(ReadoutReasons.marked(ReadoutReason.NO_COURSE_YET, readsUnknown = false))
        assertFalse(ReadoutReasons.marked(ReadoutReason.FILLING, readsUnknown = true))
        assertFalse(ReadoutReasons.marked(null, readsUnknown = false))
        // The codes are what GET /readout reported before T363.
        assertEquals(ReadoutReason.entries.size, ReadoutReason.entries.map { it.code }.toSet().size)
        for (r in ReadoutReason.entries) assertEquals(r, ReadoutReason.of(r.code))
        assertEquals("no height yet", ReadoutReason.NO_HEIGHT_YET.code)
    }

    /** The pressure altitude's offset (m) at [s] seconds: 30, then a lift the GNSS does not see from 180 s (ReadoutBarometerModeTest's ramp). */
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
}
