package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TurnAround] over synthetic lines at a known pace (T163): the turn-back time, the return time and the arrival after
 * the turn, within a minute of the hand sums; the amber and red states; the arrival clock past the far point and on a
 * point-to-point line; the deadline setting's values.
 */
class TurnAroundTest {
    private val m = 60_000L
    private val h = 3_600_000L

    /** A straight line north from (46.78, -121.73) out [outM] metres and back; elevations from [ele] of the distance out. */
    private fun outAndBack(outM: Double, steps: Int = 50, ele: (Double) -> Double = { 1600.0 }): NavPath {
        val degPerM = 1 / 111_195.0
        val out = (0..steps).map { i -> val d = outM * i / steps; NavPoint(46.78 + d * degPerM, -121.73, ele(d)) }
        // Back a few metres to the east, so the two legs are distinct points but the same way.
        val back = out.reversed().drop(1).map { NavPoint(it.lat, -121.73 + 0.00002, it.ele) }
        return NavPath("out and back", out + back)
    }

    private val sixPm = Sun.epochDay(2026, 7, 15) * 86_400_000L + 18 * h
    private fun deadline(at: Long = sixPm) = Deadline(DeadlineKind.TIME, at, "your time")

    @Test
    fun flatOutAndBackAtAKnownPace() {
        // 4.5 km/h on the flat, any climb ignored: 5 km out takes 66.7 min, 2.5 km 33.3 min.
        val pace = TurnPace(false) { l, _, _ -> l / (4500.0 / 3600) }
        val path = outAndBack(5000.0)
        val t = TurnAround(path, pace)
        assertTrue(t.loop, "an out-and-back starts and ends together")
        val now = sixPm - 3 * h
        val c = t.clock(2500.0, now, deadline(), 30)
        assertEquals(33.33, c.retraceS / 60, 0.2)
        // 18:00 - 30 min - 33.3 min = 16:56:40.
        val expected = sixPm - 30 * m - (2500 / 1.25 * 1000).toLong()
        assertEquals(expected.toDouble(), c.turnBackMs!!.toDouble(), 60_000.0)
        assertEquals("back", c.way)
        assertEquals(TurnState.AHEAD, c.state)
        assertEquals(TurnAround.NORMAL, c.colour)
        assertEquals(now + (c.retraceS * 1000).roundToLong(), c.arrivalMs, "turning now, out after the retrace")
        // Turning at the turn-back time brings the walker out the margin before the deadline.
        assertEquals((sixPm - 30 * m).toDouble(), (c.turnBackMs!! + (c.retraceS * 1000).toLong()).toDouble(), 1000.0)
        assertEquals(c.turnBackMs!! - 30 * m, c.warnAtMs)
    }

    @Test
    fun naismithTimesTheClimbOutAndNotTheWayDown() {
        // The standard hike model (5 km/h, 600 m an hour up), 600 m of climb over 5 km out: 2 h up, 1 h down.
        val path = outAndBack(5000.0) { d -> 1000 + 600 * d / 5000 }
        val t = TurnAround(path, TurnPace.of(CostModel(TravelMode.HIKE)))
        assertEquals("standard pace", t.pace.label)
        assertEquals(3.0, t.totalS / 3600, 0.02)
        // At 4 km out (480 m up): back 4 km downhill is 48 min; on is 1 km and 120 m up then 5 km down, 84 min.
        val c = t.clock(4000.0, sixPm - 4 * h, deadline(), 30)
        assertEquals(48.0, c.retraceS / 60, 0.5)
        assertEquals(84.0, c.onwardS / 60, 0.5)
        assertEquals((sixPm - 30 * m - 48 * m).toDouble(), c.turnBackMs!!.toDouble(), 60_000.0)
    }

    @Test
    fun amberAtTheMarginRedAtTheTimeThenTheArrivalClock() {
        val pace = TurnPace(false) { l, _, _ -> l / 1.25 }
        val t = TurnAround(outAndBack(5000.0), pace)
        val along = 2500.0
        val turnBack = t.clock(along, 0, deadline(), 30).turnBackMs!!
        assertEquals(TurnState.AHEAD, t.clock(along, turnBack - 31 * m, deadline(), 30).state)
        val amber = t.clock(along, turnBack - 29 * m, deadline(), 30)
        assertEquals(TurnState.MARGIN, amber.state)
        assertEquals(TurnAround.AMBER, amber.colour)
        val red = t.clock(along, turnBack + m, deadline(), 30)
        assertEquals(TurnState.TURN, red.state)
        assertEquals(TurnAround.RED, red.colour)
        assertTrue(red.text { "%t" }.startsWith("Turn back now"))
        // Past the far point going on is the shorter way out: the arrival clock, coloured by the deadline.
        val back = t.clock(7000.0, sixPm - 2 * h, deadline(), 30)
        assertEquals(TurnState.RETURNING, back.state)
        assertEquals("on", back.way)
        assertNull(back.turnBackMs)
        assertEquals((sixPm - 2 * h + (3000 / 1.25 * 1000).toLong()).toDouble(), back.arrivalMs.toDouble(), 10_000.0)
        assertEquals(TurnAround.NORMAL, back.colour)
        assertEquals(TurnAround.AMBER, t.clock(7000.0, sixPm - 50 * m, deadline(), 30).colour, "out inside the margin")
        assertEquals(TurnAround.RED, t.clock(7000.0, sixPm - 20 * m, deadline(), 30).colour, "out after the deadline")
        // No margin: no amber, straight to the turn.
        assertEquals(TurnState.AHEAD, t.clock(along, turnBack + 30 * m - m, deadline(), 0).state)
    }

    @Test
    fun aWalkerWhoTurnedBackGetsTheArrivalClock() {
        // Turned at 2.5 km, now 2 km out on the way back: progress runs back along the way out, the farthest stays.
        val t = TurnAround(outAndBack(5000.0), TurnPace(false) { l, _, _ -> l / 1.25 })
        val now = sixPm - 3 * h
        val back = t.clock(2000.0, now, deadline(), 30, farthestM = 2500.0)
        assertEquals(TurnState.RETURNING, back.state)
        assertEquals("back", back.way)
        assertNull(back.turnBackMs)
        assertEquals((now + 1_600_000).toDouble(), back.arrivalMs.toDouble(), 1000.0)
        assertTrue(back.text { "x" }.startsWith("Out by"))
        // GPS wobble short of the farthest is not a turn.
        assertEquals(TurnState.AHEAD, t.clock(2450.0, now, deadline(), 30, farthestM = 2500.0).state)
    }

    @Test
    fun aPointToPointLineShowsTheArrivalClockOnly() {
        val pts = (0..20).map { i -> NavPoint(46.78 + i * 0.0045, -121.73, 1600.0) } // 10 km north, no way back to time
        val t = TurnAround(NavPath("a to b", pts), TurnPace(false) { l, _, _ -> l / 1.25 })
        assertFalse(t.loop)
        val c = t.clock(2000.0, sixPm - 5 * h, deadline(), 30)
        assertEquals(TurnState.ARRIVAL, c.state)
        assertNull(c.turnBackMs)
        assertTrue(abs(c.returnS - 8000 / 1.25) < 30, "the rest of the line, ${c.returnS}")
        assertTrue(c.text { "18:00" }.startsWith("Out by"))
    }

    @Test
    fun freeTrackingRetracesTheRecordedTrack() {
        // A recorded track 3 km out; the walker at its end; the way out is the track back to its start.
        val pts = (0..30).map { i -> NavPoint(46.78 + i * 0.0009, -121.73, 1600.0) }
        val t = TurnAround(NavPath("recording", pts), TurnPace(false) { l, _, _ -> l / 1.25 }, retraceOnly = true)
        val c = t.clock(t.path.totalM, sixPm - 3 * h, deadline(), 30)
        assertEquals("back", c.way)
        assertEquals(TurnState.AHEAD, c.state)
        assertEquals(40.0, c.retraceS / 60, 0.3)
        assertNotNull(c.turnBackMs)
    }

    @Test
    fun offTheLineAddsTheWayBackToIt() {
        val t = TurnAround(outAndBack(5000.0), TurnPace(false) { l, _, _ -> l / 1.25 })
        val on = t.clock(1000.0, 0, deadline(), 30)
        val off = t.clock(1000.0, 0, deadline(), 30, offLineM = 250.0)
        assertEquals(200.0, off.retraceS - on.retraceS, 0.5)
    }

    @Test
    fun noDeadlineTodayIsSaid() {
        val t = TurnAround(outAndBack(5000.0), TurnPace(false) { l, _, _ -> l / 1.25 })
        val c = t.clock(1000.0, 0, Deadline(DeadlineKind.SUNSET, null, "sunset, flat horizon"), 30)
        assertEquals(TurnState.NO_DEADLINE, c.state)
        assertTrue(c.label { "x" }.startsWith("No sunset today"))
    }

    @Test
    fun labelsAndTheSpokenLine() {
        val t = TurnAround(outAndBack(5000.0), TurnPace(false) { l, _, _ -> l / 1.25 })
        val sunset = Deadline(DeadlineKind.SUNSET, sixPm, "sunset, flat horizon")
        val c = t.clock(2500.0, sixPm - 3 * h, sunset, 30)
        val clock: (Long) -> String = { ms -> if (ms == sixPm) "18:00" else "16:56" }
        assertEquals("Turn back by 16:56", c.text(clock))
        assertEquals("Sunset 18:00, flat horizon, 30 min margin, standard pace", c.label(clock))
        assertEquals("Turn back by 16:56 to be out by sunset.", c.spoken(clock))
        assertEquals("your pace", TurnPace(true) { l, _, _ -> l }.label)
    }

    @Test
    fun aFittedPaceFlowsThroughTheCostModel() { // T164's fit: its time, its label
        val fit = io.github.jasondorjeshort.dystopo.routing.PaceFits.decode("1;hike:1.0734,2.8029,-0.0575,5,22788,0.057")!!.fit(TravelMode.HIKE)!!
        val path = outAndBack(5000.0) { d -> 1000 + 600 * d / 5000 }
        val standard = TurnAround(path, TurnPace.of(CostModel(TravelMode.HIKE, null)))
        val fitted = TurnAround(path, TurnPace.of(CostModel(TravelMode.HIKE, fit)))
        assertEquals("your pace", fitted.pace.label)
        assertEquals("standard pace", standard.pace.label)
        val a = standard.clock(4000.0, sixPm - 4 * h, deadline(), 30)
        val b = fitted.clock(4000.0, sixPm - 4 * h, deadline(), 30)
        assertTrue(abs(a.retraceS - b.retraceS) > 60, "the fit changes the way back: ${a.retraceS} vs ${b.retraceS}")
        assertTrue(a.turnBackMs != b.turnBackMs)
    }

    @Test
    fun eachAlertOnce() {
        val t = TurnAround(outAndBack(5000.0), TurnPace(false) { l, _, _ -> l / 1.25 })
        val tb = t.clock(2500.0, 0, deadline(), 30).turnBackMs!!
        val a = TurnAlerts()
        assertEquals(emptyList(), a.due(t.clock(2500.0, tb - 40 * m, deadline(), 30)))
        assertEquals(listOf(TurnState.MARGIN), a.due(t.clock(2500.0, tb - 20 * m, deadline(), 30)))
        assertEquals(emptyList(), a.due(t.clock(2500.0, tb - 10 * m, deadline(), 30)))
        assertEquals(listOf(TurnState.TURN), a.due(t.clock(2500.0, tb + m, deadline(), 30)))
        assertEquals(emptyList(), a.due(t.clock(2500.0, tb - 10 * m, deadline(), 30)), "wobbling back into the margin says nothing")
        val late = TurnAlerts()
        assertEquals(listOf(TurnState.TURN), late.due(t.clock(2500.0, tb + m, deadline(), 30)), "a late start: the turn alert alone")
        assertTrue(late.marginGiven)
    }

    @Test
    fun theDeadlineSetting() {
        assertEquals(DeadlineSetting.SUNSET, DeadlineSetting.parse("sunset"))
        assertEquals(DeadlineKind.CIVIL, DeadlineSetting.parse("Civil")!!.kind)
        assertEquals(DeadlineSetting(DeadlineKind.TIME, 18 * 60 + 30), DeadlineSetting.parse("18:30"))
        assertEquals("07:05", DeadlineSetting.parse("7:05")!!.value)
        assertNull(DeadlineSetting.parse("24:00"))
        assertNull(DeadlineSetting.parse("off"), "the clock cannot be switched off")
        assertNull(DeadlineSetting.parse(""))
        // A typed time is that clock time on the phone's day; sunset is the point's.
        val midnight = Sun.epochDay(2026, 7, 15) * 86_400_000L + 7 * h // midnight at UTC-7
        val typed = Deadline.resolve(DeadlineSetting.parse("18:30")!!, 46.786, -121.735, midnight + 9 * h, midnight)
        assertEquals(midnight + 18 * h + 30 * m, typed.atMs)
        val sun = Deadline.resolve(DeadlineSetting.SUNSET, 46.786, -121.735, midnight + 9 * h, midnight)
        assertEquals(Sun.day(46.786, -121.735, 2026, 7, 15).sunsetMs, sun.atMs)
        assertEquals("sunset, flat horizon", sun.label)
    }
}
