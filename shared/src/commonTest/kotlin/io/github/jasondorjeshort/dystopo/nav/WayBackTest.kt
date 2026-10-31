package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T180, the way back: [PathTracker] on an out-and-back route (progress forward on both legs, one flip, "arrived"; the
 * hysteresis, the near-start guard, the jump after an early turn), [Retrace] on a recording (the flip, the shorter way
 * back after it, no flip for a step back or a stop), [WalkStart]'s speed rule and [WayBack]'s override.
 */
class WayBackTest {
    private val mLat = 111_195.0
    private val mLon = 111_195.0 * cos(46.79 * PI / 180)

    /**
     * A bending line from (46.7865, -121.7365): [lengthM] at a heading that swings 30 degrees either side of north; with
     * [switchbacksAtM], from there three switchback legs of 120 m, north, south and north, 20 m apart (each walked leg
     * runs back beside the one before it), then on at 60 degrees.
     */
    private fun outLine(lengthM: Double = 2500.0, stepM: Double = 50.0, switchbacksAtM: Double? = null): List<NavPoint> {
        val pts = ArrayList<NavPoint>()
        var lat = 46.7865
        var lon = -121.7365
        pts += NavPoint(lat, lon, 1650.0)
        var s = 0.0
        fun step(d: Double, h: Double) {
            lat += d * cos(h) / mLat
            lon += d * sin(h) / mLon
            s += d
            pts += NavPoint(lat, lon, 1650.0 + s * 0.1)
        }
        while (s < (switchbacksAtM ?: lengthM)) step(stepM, 30 * sin(s / 400.0) * PI / 180)
        if (switchbacksAtM != null) {
            for (leg in listOf(0.0, PI, 0.0)) {
                repeat(12) { step(10.0, leg) }
                repeat(2) { step(10.0, PI / 2) }
            }
            while (s < lengthM) step(stepM, PI / 3)
        }
        return pts
    }

    private fun outAndBack(out: List<NavPoint>) = NavPath("out and back", out + out.reversed().drop(1).map { NavPoint(it.lat, it.lon + 2 / mLon, it.ele) })

    private class Walk(val rng: Random?, val sigmaM: Double) {
        val fixes = ArrayList<Triple<Double, Double, Long>>()
        var t = 1_784_131_200_000L // 2026-07-15 16:00 UTC
        fun gauss(): Double {
            val r = rng ?: return 0.0
            return sqrt(-2 * ln(1 - r.nextDouble())) * cos(2 * PI * r.nextDouble())
        }
    }

    /** Fixes every 2 s at 1.3 m/s along [line] from [from] to [to] (metres along it), with Gaussian noise. */
    private fun Walk.go(line: NavPath, from: Double, to: Double, stepM: Double = 2.6) {
        val n = (kotlin.math.abs(to - from) / stepM).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            val p = line.pointAt(from + (to - from) * i / n)
            fixes += Triple(p.lat + gauss() * sigmaM / mLat, p.lon + gauss() * sigmaM / mLon, t)
            t += 2000
        }
    }

    private fun Walk.stay(lat: Double, lon: Double, seconds: Int) {
        repeat(seconds / 2) {
            fixes += Triple(lat + gauss() * sigmaM / mLat, lon + gauss() * sigmaM / mLon, t)
            t += 2000
        }
    }

    private fun navigate(path: NavPath, w: Walk): Pair<Navigator, List<NavState>> {
        val nav = Navigator(path)
        val states = w.fixes.map { (lat, lon, t) -> nav.onFix(lat, lon, t, 5.0) }
        return nav to states
    }

    private fun flips(nav: Navigator) = nav.events.filter { it.type == NavEvent.HEADING_BACK }

    @Test
    fun anOutAndBackRouteRunsForwardOnBothLegsFlipsOnceAndArrives() {
        val out = outLine()
        val path = outAndBack(out)
        val L = path.totalM / 2
        val w = Walk(null, 0.0)
        val outLeg = NavPath("out", out)
        w.go(outLeg, 0.0, outLeg.totalM)
        val turnAt = w.fixes.size
        w.go(outLeg, outLeg.totalM, 0.0)
        val (nav, states) = navigate(path, w)
        val prog = states.map { it.progress }
        for (i in 1 until turnAt) assertTrue(prog[i] >= prog[i - 1] - 0.5, "out: progress ${prog[i - 1]} then ${prog[i]} at fix $i")
        for (i in turnAt + 1 until prog.size) assertTrue(prog[i] >= prog[i - 1] - 0.5, "back: progress ${prog[i - 1]} then ${prog[i]} at fix $i")
        assertTrue(states.last().arrived, "arrived at the end of the out-and-back")
        assertEquals(1, nav.events.count { it.type == NavEvent.ARRIVED })
        val f = flips(nav)
        assertEquals(1, f.size, "one flip: ${f.map { it.alongM }}")
        // The flip comes the hysteresis into the way back, not at the far point.
        assertTrue(f[0].alongM >= L + TurnAround.TURNED_BACK_M - 30 && f[0].alongM <= L + TurnAround.TURNED_BACK_M + 80, "flip at ${f[0].alongM} of $L")
        assertEquals(0, nav.tracker.jumps, "no jump: the far end leads onto the way back")
    }

    @Test
    fun withGpsNoiseStillOneFlipAndArrival() {
        val out = outLine()
        val path = outAndBack(out)
        val outLeg = NavPath("out", out)
        for (seed in 1..10) {
            val w = Walk(Random(seed), 4.0)
            w.go(outLeg, 0.0, outLeg.totalM)
            val turnAt = w.fixes.size
            w.go(outLeg, outLeg.totalM, 0.0)
            val (nav, states) = navigate(path, w)
            val prog = states.map { it.progress }
            val worst = (1 until prog.size).filter { it != turnAt }.maxOf { prog[it - 1] - prog[it] }
            assertTrue(worst < 25, "seed $seed: progress fell $worst m on a leg (4 m of noise a fix)")
            assertTrue(states.last().arrived, "seed $seed arrived")
            assertEquals(1, flips(nav).size, "seed $seed one flip")
        }
    }

    @Test
    fun aTurnFiftyMetresPastTheFarPointFlipsOnlyAfterTheSustainedStretch() {
        val out = outLine()
        val path = outAndBack(out)
        val L = path.totalM / 2
        // The walker goes on 50 m past the line's far point (a viewpoint), then back.
        val beyond = out.last().let { p -> NavPoint(p.lat + 50 / mLat, p.lon, p.ele) }
        val walkLine = NavPath("walk", out + beyond)
        val w = Walk(null, 0.0)
        w.go(walkLine, 0.0, walkLine.totalM)
        val backAt = w.fixes.size
        w.go(walkLine, walkLine.totalM, 0.0)
        val (nav, states) = navigate(path, w)
        val f = flips(nav)
        assertEquals(1, f.size, "one flip")
        val flipFix = states.indexOfFirst { it.headingBack }
        // Not while beyond the far point or on the first stretch back.
        val walkedBack = (flipFix - backAt) * 2.6
        assertTrue(walkedBack >= 50 + TurnAround.TURNED_BACK_M - 20, "flipped after $walkedBack m back (50 m of them beyond the line)")
        assertTrue(f[0].alongM >= L + TurnAround.TURNED_BACK_M - 30, "flip at ${f[0].alongM} of $L")
        assertTrue(states.last().arrived)
    }

    @Test
    fun aShortStepBackDoesNotFlipAndAnEarlyTurnJumpsToTheWayBack() {
        val out = outLine()
        val path = outAndBack(out)
        val outLeg = NavPath("out", out)
        // 50 m back at 1000 m (a dropped glove), then on to the end and back: one flip, at the far end.
        val w = Walk(Random(7), 4.0)
        w.go(outLeg, 0.0, 1000.0)
        w.go(outLeg, 1000.0, 950.0)
        w.go(outLeg, 950.0, outLeg.totalM)
        w.go(outLeg, outLeg.totalM, 0.0)
        val (nav, states) = navigate(path, w)
        assertEquals(1, flips(nav).size, "the step back is no flip")
        assertTrue(flips(nav)[0].alongM > path.totalM / 2)
        assertTrue(states.last().arrived)
        // Turned back at 1200 m of 2500: progress runs back on the leg out for the stretch, then jumps to the way back.
        val w2 = Walk(Random(8), 4.0)
        w2.go(outLeg, 0.0, 1200.0)
        w2.go(outLeg, 1200.0, 0.0)
        val (nav2, states2) = navigate(path, w2)
        assertEquals(1, nav2.tracker.jumps, "one jump to the way back")
        val j = assertNotNull(nav2.tracker.lastJump)
        assertTrue(j.first - j.second >= TurnAround.TURNED_BACK_M && j.first - j.second < TurnAround.TURNED_BACK_M + 30, "jumped after ${j.first - j.second} m back")
        assertTrue(j.first > 1000, "the backward run began near the turn at 1200 m: ${j.first}")
        assertEquals(path.totalM - j.second, j.third, 30.0, "onto the mirror of the place on the leg out")
        assertEquals(1, flips(nav2).size)
        assertTrue(states2.last().arrived, "arrived at the start, the end of the out-and-back")
    }

    @Test
    fun theNearStartGuardKeepsAShortOutAndBackFromFlipping() {
        val out = outLine()
        val path = outAndBack(out)
        val outLeg = NavPath("out", out)
        val w = Walk(Random(3), 4.0)
        w.go(outLeg, 0.0, 240.0)
        w.go(outLeg, 240.0, 0.0)
        val (nav, _) = navigate(path, w)
        assertEquals(0, nav.tracker.jumps, "no jump within ${nav.tracker.nearStartM} m of the start")
        // The recording's way back likewise.
        val r = Retrace()
        w.fixes.forEach { (lat, lon, _) -> r.add(lat, lon) }
        assertEquals(0, r.flips)
    }

    private val pace = TurnPace(false) { l, _, _ -> l / 1.25 }
    private val deadline = Deadline(DeadlineKind.TIME, 1_784_131_200_000L + 10 * 3_600_000L, "your time")

    @Test
    fun aRecordedOutAndBackFlipsOnceAndTheWayBackShortensAfterIt() {
        val outLeg = NavPath("out", outLine())
        val w = Walk(Random(11), 4.0)
        w.go(outLeg, 0.0, 900.0)
        w.go(outLeg, 900.0, 850.0)
        w.go(outLeg, 850.0, outLeg.totalM)
        val far = outLeg.points.last()
        w.stay(far.lat, far.lon, 180)
        val turnAt = w.fixes.size
        w.go(outLeg, outLeg.totalM, 0.0)
        val wb = WayBack(pace)
        val retrace = ArrayList<Double>()
        val back = ArrayList<Boolean>()
        for ((lat, lon, t) in w.fixes) {
            wb.add(WayPoint(lat, lon, null, t, 1.3))
            val c = assertNotNull(wb.clock(t, deadline, 30))
            retrace += c.retraceS
            back += c.state == TurnState.RETURNING
        }
        assertEquals(1, wb.retrace.flips, "one flip: ${wb.retrace.flipsAt}")
        val flipFix = back.indexOfFirst { it }
        assertTrue(flipFix > turnAt, "no flip before the turn (the step back and the stop are none)")
        // Out: the way back grows with the line; after the flip it shrinks to nothing at the start.
        assertTrue(retrace[turnAt - 1] > 0.9 * outLeg.totalM / 1.25, "at the far point the whole way back: ${retrace[turnAt - 1]}")
        for (i in flipFix + 1 until retrace.size) assertTrue(retrace[i] <= retrace[i - 1] + 20, "after the flip it shrinks: ${retrace[i - 1]} then ${retrace[i]}")
        assertTrue(retrace.last() < 60, "back at the start: ${retrace.last()} s")
        assertTrue(back.last(), "the arrival clock on the way back")
    }

    @Test
    fun aLoopRecordingNeverFlips() {
        // A square loop of 1.2 km.
        val sq = listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0, 0.0 to 300.0, 0.0 to 0.0).map { (n, e) -> NavPoint(46.7865 + n / mLat, -121.7365 + e / mLon) }
        val loop = NavPath("loop", sq)
        val w = Walk(Random(5), 4.0)
        w.go(loop, 0.0, loop.totalM)
        val r = Retrace()
        w.fixes.forEach { (lat, lon, _) -> r.add(lat, lon) }
        assertEquals(0, r.flips)
        val (nav, states) = navigate(loop, w)
        assertEquals(0, flips(nav).size)
        assertTrue(states.last().arrived)
    }

    /**
     * The hysteresis measured (T180): the walk out with a 50 m step back, a three-minute stop and three switchback legs
     * of 120 m 20 m apart, at 5 m of GPS noise, over 40 seeds; any flip on the way out is false. The report has the
     * counts; [TurnAround.TURNED_BACK_M] must give none.
     */
    @Test
    fun theHysteresisOnTheFixtures() {
        val outLeg = NavPath("out", outLine(lengthM = 2500.0, switchbacksAtM = 1200.0))
        val counts = linkedMapOf<Double, Int>()
        var routeFlips = 0
        for (h in listOf(100.0, 150.0, 200.0)) {
            var falseFlips = 0
            for (seed in 1..40) {
                val w = Walk(Random(seed), 5.0)
                w.go(outLeg, 0.0, 700.0)
                w.go(outLeg, 700.0, 650.0)
                w.go(outLeg, 650.0, 900.0)
                val p = outLeg.pointAt(900.0)
                w.stay(p.lat, p.lon, 180)
                w.go(outLeg, 900.0, outLeg.totalM)
                val r = Retrace(turnBackM = h)
                w.fixes.forEach { (lat, lon, _) -> r.add(lat, lon) }
                falseFlips += r.flips
                if (h == TurnAround.TURNED_BACK_M) {
                    val nav = Navigator(NavPath("route", outLeg.points + outLeg.points.reversed().drop(1)))
                    w.fixes.forEach { (lat, lon, t) -> nav.onFix(lat, lon, t, 5.0) }
                    routeFlips += flips(nav).size + nav.tracker.jumps
                }
            }
            counts[h] = falseFlips
        }
        println("T180 hysteresis: false flips over 40 seeds by turn-back metres: $counts; navigated at the chosen value: $routeFlips")
        assertEquals(0, counts[TurnAround.TURNED_BACK_M], "no false flip at the chosen value: $counts")
        assertEquals(0, routeFlips, "no false flip or jump navigating")
    }

    private fun drive(): List<WayPoint> {
        val pts = ArrayList<WayPoint>()
        var t = 1_784_131_200_000L
        var lat = 46.70
        // 6 min at 11 m/s (40 km/h) north, then 20 min walking at 1.3 m/s.
        repeat(360) {
            pts += WayPoint(lat, -121.7365, null, t, 11.0)
            lat += 11 / mLat
            t += 1000
        }
        val walkFrom = pts.size
        repeat(600) {
            pts += WayPoint(lat, -121.7365, null, t, 1.3)
            lat += 2.6 / mLat
            t += 2000
        }
        assertEquals(360, walkFrom)
        return pts
    }

    @Test
    fun aDriveThenAWalkPutsTheWayBackAtTheWalksFirstPoint() {
        val pts = drive()
        assertEquals(360, WalkStart.find(pts), "the walk's first point")
        // Without the per-fix speeds the straight-line speed over 30 s finds the same within a few fixes.
        val noSpeed = pts.map { it.copy(speedMps = null) }
        val i = assertNotNull(WalkStart.find(noSpeed))
        assertTrue(i in 360..380, "found $i")
        // A walk alone, or a drive with no walk after, moves nothing.
        assertEquals(null, WalkStart.find(pts.drop(360)))
        assertEquals(null, WalkStart.find(pts.take(360)))
        // A 3-minute queue in the drive counts as a walk, but the drive after it passes it.
        val queued = pts.take(180) + List(90) { k -> pts[180].copy(timeMs = pts[180].timeMs!! + 2000L * (k + 1), speedMps = 0.0) } +
            pts.drop(180).map { it.copy(timeMs = it.timeMs!! + 240_000) }
        assertEquals(360 + 90, WalkStart.find(queued), "the walk after the drive, not the queue")
        // The way back: the start, the walk, the override.
        val wb = WayBack(pace)
        pts.forEach { wb.add(it) }
        assertEquals(AnchorSource.WALK, wb.anchorSource)
        assertEquals(360, wb.anchorIndex)
        val t = pts.last().timeMs!!
        val fromWalk = assertNotNull(wb.clock(t, deadline, 30)).retraceS
        assertEquals(599 * 2.6 / 1.25, fromWalk, 60.0)
        val here = WayBack(pace, override = 800)
        pts.forEach { here.add(it) }
        assertEquals(AnchorSource.HERE, here.anchorSource)
        val fromHere = assertNotNull(here.clock(t, deadline, 30)).retraceS
        assertEquals(159 * 2.6 / 1.25, fromHere, 30.0)
        assertTrue(fromHere < fromWalk, "the override shortens the retrace")
        val atHere = WayBack(pace, override = pts.size - 1)
        pts.forEach { atHere.add(it) }
        assertEquals(0.0, assertNotNull(atHere.clock(t, deadline, 30)).retraceS, 0.5, "anchored at the last fix: nothing to walk back")
    }

    @Test
    fun theFirstGoodFixIsTheStartWithoutADrive() {
        val pts = listOf(
            WayPoint(46.70, -121.73, null, 0, 1.0, flagged = true),
            WayPoint(46.7001, -121.73, null, 2000, 1.0),
            WayPoint(46.7002, -121.73, null, 4000, 1.0),
        )
        assertEquals(1, WalkStart.firstGood(pts))
        val wb = WayBack(pace)
        pts.forEach { wb.add(it) }
        assertEquals(AnchorSource.START, wb.anchorSource)
        assertEquals(1, wb.anchorIndex)
        assertTrue(TrackStats.distanceM(pts[1].lat, pts[1].lon, wb.anchor!!.lat, wb.anchor!!.lon) < 0.1)
    }
}
