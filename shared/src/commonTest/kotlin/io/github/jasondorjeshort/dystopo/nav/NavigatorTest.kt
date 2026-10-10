package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [NavPath] and [Navigator] on synthetic lines (no real places needed: a line due north from 0,0). */
class NavigatorTest {
    private val mPerDegLat = 111_195.0

    /** [km] due north from 0,0 in 100 m steps, climbing [climbM] evenly; named waypoints at 1 km and the end. */
    private fun northLine(km: Double = 2.0, climbM: Double = 200.0): NavPath {
        val n = (km * 10).toInt()
        val pts = (0..n).map { i -> NavPoint(i * 100.0 / mPerDegLat, 0.0, 1000.0 + climbM * i / n) }
        return NavPath("north", pts, listOf("Half" to NavPoint(1000.0 / mPerDegLat, 0.0), "Top" to pts.last()))
    }

    private fun east(m: Double, lat: Double) = m / (mPerDegLat * cos(lat * PI / 180))

    @Test
    fun projectionAlongAndAcross() {
        val path = northLine()
        assertEquals(2000.0, path.totalM, 2.0)
        assertEquals(200.0, path.totalAscentM, 1e-9)
        val lat = 750.0 / mPerDegLat
        val p = path.project(lat, east(40.0, lat))
        assertEquals(750.0, p.alongM, 1.0)
        assertEquals(40.0, p.distanceM, 0.5)
        assertEquals(listOf("Half", "Top"), path.waypoints.map { it.name })
        assertEquals(1000.0, path.waypoints[0].alongM, 1.0)
    }

    @Test
    fun offRouteAfterThresholdSecondsAndBack() {
        val nav = Navigator(northLine(), NavOptions(offRouteM = 50.0, offRouteSeconds = 10.0, backOnRouteM = 30.0))
        var t = 0L
        fun walk(alongM: Double, eastM: Double): NavState {
            val lat = alongM / mPerDegLat
            t += 1000
            return nav.onFix(lat, east(eastM, lat), t)
        }
        for (i in 0 until 20) assertFalse(walk(i * 1.2, 0.0).offRoute)
        // 60 m east: off the line, but not off route until 10 s of it.
        var firstOffAt = 0L
        var st: NavState? = null
        for (i in 0 until 15) {
            st = walk(30.0 + i, 60.0)
            if (i == 0) firstOffAt = t
            if (i < 10) assertFalse(st.offRoute, "off route too early at $i") else assertTrue(st.offRoute, "not off route at $i")
            assertNotNull(st.rejoin)
        }
        val off = nav.events.single { it.type == NavEvent.OFF_ROUTE }
        assertEquals(10.0, off.afterS!!, 1e-9)
        assertEquals(firstOffAt + 10_000, off.timeMs)
        // The way back points west (the line is west of the walker), 60 m.
        assertEquals("W", st!!.rejoin!!.compass)
        assertEquals(60.0, st.rejoin!!.distanceM, 0.5)
        // 40 m is still off route (between the back-on and off thresholds); 25 m is back.
        assertTrue(walk(46.0, 40.0).offRoute)
        val back = walk(47.0, 25.0)
        assertFalse(back.offRoute)
        val ev = nav.events.single { it.type == NavEvent.BACK_ON_ROUTE }
        assertEquals(60.0, ev.maxDistanceM!!, 0.5)
        assertNull(walk(48.0, 0.0).rejoin)
    }

    @Test
    fun edgeWalkingDoesNotFlap() {
        val nav = Navigator(northLine(), NavOptions(offRouteM = 50.0, offRouteSeconds = 10.0))
        for (i in 0 until 120) {
            val lat = i / mPerDegLat
            nav.onFix(lat, east(if (i % 6 < 5) 55.0 else 45.0, lat), i * 1000L)
        }
        assertTrue(nav.events.none { it.type == NavEvent.OFF_ROUTE }, "${nav.events}")
    }

    @Test
    fun etaFollowsTheHikeModel() {
        val path = northLine(km = 4.0, climbM = 600.0)
        val nav = Navigator(path)
        val model = CostModel(TravelMode.HIKE)
        val s0 = nav.onFix(0.0, 0.0, 0L)
        // Naismith: 4 km at 5 km/h plus 600 m at 600 m/h = 48 + 60 minutes.
        assertEquals(108 * 60.0, s0.end.timeS, 1.0)
        assertEquals(model.timeS(4000.0, 600.0), nav.plannedTimeS, 1.0)
        assertTrue(abs(s0.end.etaMs - 108 * 60_000L) <= 2000, "eta ${s0.end.etaMs}")
        assertEquals("Half", s0.next!!.name)
        assertEquals(1000.0, s0.next!!.distanceM, 1.0)
        val s1 = nav.onFix(3000.0 / mPerDegLat, 0.0, 3_600_000L)
        assertEquals(1000.0, s1.end.distanceM, 1.0)
        assertEquals(150.0, s1.end.ascentM, 0.5)
        assertEquals(model.timeS(1000.0, 150.0), s1.end.timeS, 1.0)
        assertEquals("Top", s1.next!!.name)
        assertTrue(nav.events.any { it.type == "waypoint:Half" })
    }

    @Test
    fun loopKeepsProgressOnTheRightLeg() {
        // A square loop 1 km a side starting and ending at 0,0, walked anticlockwise from the south-west corner.
        val d = 1000.0 / mPerDegLat
        val e = east(1000.0, 0.0)
        val corners = listOf(0.0 to 0.0, 0.0 to e, d to e, d to 0.0, 0.0 to 0.0)
        val pts = ArrayList<NavPoint>()
        for (k in 0 until 4) for (i in 0 until 10) {
            val (a, b) = corners[k]
            val (c, f) = corners[k + 1]
            pts += NavPoint(a + (c - a) * i / 10, b + (f - b) * i / 10)
        }
        pts += NavPoint(0.0, 0.0)
        val nav = Navigator(NavPath("loop", pts))
        assertEquals(0.0, nav.onFix(0.0, east(5.0, 0.0), 0).progress, 6.0)
        var last = 0.0
        var t = 0L
        for (i in 1..39) {
            val p = pts[i]
            val s = nav.onFix(p.lat, p.lon, ++t * 60_000)
            assertTrue(s.progress >= last, "progress went back at $i: ${s.progress} < $last")
            last = s.progress
        }
        val end = nav.onFix(0.0, east(5.0, 0.0), ++t * 60_000)
        assertEquals(4000.0, end.progress, 10.0)
        assertTrue(end.arrived)
        assertEquals(1, nav.events.count { it.type == NavEvent.ARRIVED })
    }

    @Test
    fun aLoopStartsAtItsStartNotItsEnd() {
        val d = 1000.0 / mPerDegLat
        val e = east(1000.0, 0.0)
        val pts = listOf(NavPoint(0.0, 0.0), NavPoint(0.0, e), NavPoint(d, e), NavPoint(d, 0.0), NavPoint(0.0, 0.0))
        val nav = Navigator(NavPath("loop", pts))
        val s = nav.onFix(0.0, 0.0, 0L)
        assertEquals(0.0, s.progress, 1.0)
        assertFalse(s.arrived)
        assertTrue(nav.events.none { it.type == NavEvent.ARRIVED })
    }

    @Test
    fun aFixFromTheFutureDoesNotStartTheOffRouteClockEarly() {
        // A live fix stamped now, then a replay with older times: the clock starts over at the replay.
        val nav = Navigator(northLine(), NavOptions(offRouteM = 50.0, offRouteSeconds = 10.0))
        val lat = 500.0 / mPerDegLat
        nav.onFix(lat, east(80.0, lat), 2_000_000_000_000L)
        for (i in 0 until 5) nav.onFix(lat, east(80.0, lat), 1_000_000_000_000L + i * 1000)
        assertTrue(nav.events.none { it.type == NavEvent.OFF_ROUTE }, "${nav.events}")
        for (i in 5..12) nav.onFix(lat, east(80.0, lat), 1_000_000_000_000L + i * 1000)
        assertEquals(10.0, nav.events.single { it.type == NavEvent.OFF_ROUTE }.afterS!!, 1e-9)
    }

    @Test
    fun relativeAndCompassWords() {
        assertEquals("N", RejoinHint.compassWord(359.0))
        assertEquals("NE", RejoinHint.compassWord(40.0))
        assertEquals("W", RejoinHint.compassWord(-90.0))
        assertEquals("ahead", RejoinHint.relativeWord(10.0))
        assertEquals("ahead-left", RejoinHint.relativeWord(-45.0))
        assertEquals("behind-right", RejoinHint.relativeWord(130.0))
        assertEquals(-170.0, Navigator.rel(190.0), 1e-9)
        val hint = RejoinHint(0.0, 0.0, 270.0, 120.0, Navigator.rel(270.0 - 0.0))
        assertEquals("Head W 120 m to rejoin (left)", hint.text("120 m"))
    }

    @Test
    fun compassAddsDeclinationAndWraps() {
        val c = Compass(smoothing = 0.5)
        c.locate(46.786, -121.736, 1_791_590_400_000L) // Paradise, Mount Rainier, 2026-10-10
        val decl = c.declinationDeg!!
        assertTrue(decl in 14.0..16.0, "declination $decl")
        assertEquals(Compass.norm(350.0 + decl), c.onMagnetic(350.0), 1e-9)
        // Readings either side of north average across the wrap, not to 180.
        val c2 = Compass(smoothing = 0.5)
        c2.onMagnetic(350.0)
        val h = c2.onMagnetic(10.0)
        assertTrue(h > 355.0 || h < 5.0, "wrapped heading $h")
        assertTrue(abs(Navigator.rel(h)) < 1.0)
    }
}
