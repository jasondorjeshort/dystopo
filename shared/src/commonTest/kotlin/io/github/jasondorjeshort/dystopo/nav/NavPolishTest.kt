package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.model.Route
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T65: one ascent figure, the pace ratio, the alert snooze and stale fixes. */
class NavPolishTest {
    private val mPerDegLat = 111_195.0

    /** A line north with a noisy climb: +1 m, -1 m wiggles on the way up (what the 3 m hysteresis ignores). */
    private fun noisy(): List<RoutePoint> = (0..200).map { i ->
        RoutePoint(i * 10.0 / mPerDegLat, 0.0, 1000.0 + i * 1.0 + if (i % 2 == 0) 1.5 else -1.5)
    }

    @Test
    fun navigationAndTheTripSummaryShowOneAscentFigure() {
        val pts = noisy()
        val path = RoutePath("hike", listOf(ViaPoint(pts.first().lat, 0.0), ViaPoint(pts.last().lat, 0.0)), listOf(RouteLeg(LegKind.TRAIL, pts)))
        val summary = RouteSummary.of(path, emptyList())
        val nav = NavPath.of(Route("r", "R", pts))
        // The sum of rises would count every wiggle (about 400 m); both say the hysteresis figure.
        assertEquals(summary.ascentM!!, nav.totalAscentM, 1e-9)
        assertEquals(summary.descentM!!, nav.totalDescentM, 1e-9)
        assertTrue(nav.totalAscentM in 195.0..205.0, "ascent ${nav.totalAscentM}")
        assertEquals(TrackStats.of(Route("r", "R", pts)).ascentM!!, nav.totalAscentM, 1e-9)
        // The remaining climb at the start is that figure, half-way about half of it, and it only grows.
        assertEquals(nav.totalAscentM, nav.ascentAt(nav.totalM) - nav.ascentAt(0.0), 1e-9)
        assertTrue(abs(nav.ascentAt(nav.totalM / 2) - nav.totalAscentM / 2) < 5.0)
        assertTrue(nav.cumAscentM.toList().zipWithNext().all { (a, b) -> b >= a })
        // ...so navigation's planned time is the trip card's.
        val n = Navigator(nav)
        assertEquals(summary.timeS, n.plannedTimeS, 1e-6)
    }

    @Test
    fun etaVsPlanIsThePaceRatio() {
        val pts = (0..20).map { RoutePoint(it * 100.0 / mPerDegLat, 0.0, 1000.0) }
        val nav = Navigator(NavPath.of(Route("r", "R", pts)))
        assertNull(nav.etaVsPlan())
        val planned = nav.plannedTimeS
        nav.onFix(0.0, 0.0, 1_000_000)
        assertEquals(1.0, nav.etaVsPlan()!!, 1e-9, "the remaining time at the start is the plan")
        // Half-way in exactly half the planned time: on plan. In the whole planned time: behind, 1.5.
        nav.onFix(1000.0 / mPerDegLat, 0.0, 1_000_000 + (planned * 500).toLong())
        assertEquals(1.0, nav.etaVsPlan()!!, 0.01)
        nav.onFix(1000.0 / mPerDegLat, 0.0, 1_000_000 + (planned * 1000).toLong())
        assertEquals(1.5, nav.etaVsPlan()!!, 0.01)
        assertEquals(0.75, nav.etaVsPlan(planned * 2)!!, 0.01, "against another plan")
        assertNull(nav.etaVsPlan(0.0))
    }

    @Test
    fun snoozeHoldsAlertsForAWhileOrForOneExcursion() {
        val s = AlertSnooze()
        assertTrue(s.allow(NavEvent.OFF_ROUTE, 0))
        s.snooze(1_000, 5.0)
        assertTrue(s.active(1_000))
        assertFalse(s.allow(NavEvent.OFF_ROUTE, 60_000))
        assertFalse(s.allow(NavEvent.BACK_ON_ROUTE, 61_000))
        assertTrue(s.allow(NavEvent.OFF_ROUTE, 1_000 + 5 * 60_000), "the snooze ended")
        assertFalse(s.active(1_000 + 5 * 60_000))
        assertEquals(2, s.suppressed)
        // This excursion: quiet until back on the line, then the next one sounds.
        s.snoozeExcursion()
        assertFalse(s.allow(NavEvent.OFF_ROUTE, 400_000))
        assertFalse(s.allow(NavEvent.BACK_ON_ROUTE, 410_000))
        assertFalse(s.active(410_000))
        assertTrue(s.allow(NavEvent.OFF_ROUTE, 420_000))
        // A second snooze extends, never shortens; clear ends it.
        s.snooze(0, 10.0)
        s.snooze(0, 1.0)
        assertEquals(600_000L, s.untilMs)
        s.clear()
        assertTrue(s.allow(NavEvent.OFF_ROUTE, 1))
        assertFailsWith<IllegalArgumentException> { s.snooze(0, 0.0) }
        assertFailsWith<IllegalArgumentException> { s.snooze(0, 1000.0) }
    }

    @Test
    fun staleAndFarFixesAreDropped() {
        val f = FixFreshness(FixFreshnessOptions(maxAgeS = 30.0, maxStartDistanceM = 2000.0, lostAfterS = 30.0))
        val now = 10_000_000L
        // A GPS fix 45 s old, the last known location from an hour ago: dropped.
        assertEquals(FixFreshness.Verdict.TOO_OLD, f.check(FixOrigin.GPS, now - 45_000, now, 10.0))
        assertEquals(FixFreshness.Verdict.TOO_OLD, f.check(FixOrigin.LAST_KNOWN, now - 3_600_000, now, 10.0))
        // A fresh last known location 1,200 km away (the emulator's default place): too far to begin with.
        assertEquals(FixFreshness.Verdict.TOO_FAR, f.check(FixOrigin.LAST_KNOWN, now - 1_000, now, 1.2e6))
        // A fresh GPS fix far away is used (walking to the trailhead), and so is a replayed fix from last year.
        assertEquals(FixFreshness.Verdict.USE, f.check(FixOrigin.GPS, now - 1_000, now, 1.2e6))
        assertEquals(FixFreshness.Verdict.USE, f.check(FixOrigin.INJECTED, now - 365L * 86_400_000, now, null))
        assertEquals(2, f.tooOld)
        assertEquals(1, f.tooFar)
        // Lost: no used fix for more than 30 s.
        assertEquals(0.0, f.ageS(now))
        assertFalse(f.lost(now + 30_000, now))
        assertTrue(f.lost(now + 31_000, now))
        assertTrue(FixFreshness().lost(now + 31_000, now), "no fix at all since the start")
        assertFalse(FixFreshness().lost(now + 29_000, now))
        assertEquals(FixOrigin.LAST_KNOWN, FixOrigin.of("last"))
        assertEquals(FixOrigin.INJECTED, FixOrigin.of("debug"))
    }
}
