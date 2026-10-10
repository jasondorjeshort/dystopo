package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Navigation along the synthetic Skyline loop fixture (`gpx/skyline-loop.gpx`, public trails at
 * Paradise, Mount Rainier) with the synthetic off-route walk (`gpx/skyline-offroute.gpx`: the same
 * loop, resampled to a fix every 10 s, with a stretch of the descent moved up to 150 m east).
 * The same check the device runs through `POST /nav/start` and `POST /location/replay`.
 */
class SkylineNavTest {
    private fun gpx(name: String) = Gpx.parse(javaClass.getResourceAsStream("/gpx/$name")!!.use { it.readBytes().decodeToString() })

    private val loop = gpx("skyline-loop.gpx")
    private val path = NavPath.of(loop.tracks.single(), loop.waypoints)

    @Test
    fun offRouteWalkRaisesOneEventAndClears() {
        val walk = gpx("skyline-offroute.gpx").tracks.single().points
        assertTrue(walk.size > 1000, "fixture resampled to 10 s")
        val nav = Navigator(path) // defaults: 50 m for 10 s, back within 30 m
        var maxOff = 0.0
        var offFixes = 0
        for (p in walk) {
            val s = nav.onFix(p.lat, p.lon, p.time!!)
            maxOff = maxOf(maxOff, s.projection.distanceM)
            if (s.offRoute) offFixes++
        }
        val off = nav.events.filter { it.type == NavEvent.OFF_ROUTE }
        val back = nav.events.filter { it.type == NavEvent.BACK_ON_ROUTE }
        assertEquals(1, off.size, "${nav.events.map { it.json() }}")
        assertEquals(1, back.size, "${nav.events.map { it.json() }}")
        // Detected 10 s after the first fix beyond 50 m (fixes are 10 s apart), cleared after it.
        assertTrue(off[0].afterS!! in 10.0..20.0, "afterS ${off[0].afterS}")
        assertTrue(off[0].distanceM > 50.0)
        assertTrue(back[0].timeMs > off[0].timeMs)
        assertTrue(back[0].maxDistanceM!! in 120.0..160.0, "max ${back[0].maxDistanceM}")
        assertTrue(maxOff in 120.0..160.0, "max distance $maxOff")
        // ...and the walk finishes the loop.
        assertTrue(nav.state!!.arrived)
        assertEquals(1, nav.events.count { it.type == NavEvent.ARRIVED })
        assertTrue(offFixes in 30..400, "off-route fixes $offFixes")
    }

    @Test
    fun theOriginalLoopNeverLeavesTheRoute() {
        val nav = Navigator(path)
        for (p in loop.tracks.single().points) nav.onFix(p.lat, p.lon, p.time!!)
        assertTrue(nav.events.none { it.type == NavEvent.OFF_ROUTE }, "${nav.events.map { it.json() }}")
        assertTrue(nav.state!!.arrived)
        assertTrue(nav.events.any { it.type == "waypoint:Glacier Vista" }, "${nav.events.map { it.type }}")
    }

    @Test
    fun etaAtTheStartIsTheHikeModelTime() {
        // The model on the fixture's own stats (thresholded ascent, haversine distance): within 10%.
        val stats = TrackStats.of(loop.tracks.single())
        val expected = CostModel(TravelMode.HIKE).timeS(stats.distanceM, stats.ascentM!!)
        val first = loop.tracks.single().points.first()
        val s = Navigator(path).onFix(first.lat, first.lon, first.time!!)
        val err = abs(s.end.timeS - expected) / expected
        assertTrue(err < 0.10, "ETA ${s.end.timeS} s vs model $expected s (${err * 100} %)")
        assertEquals(stats.distanceM, s.end.distanceM, 30.0)
    }

    @Test
    fun theRouteInTheFixtureNavigatesToo() {
        val route = loop.routes.single()
        val nav = Navigator(NavPath.of(route))
        val s = nav.onFix(route.points.first().lat, route.points.first().lon, 0L)
        assertEquals("Alta Vista Trail junction", s.next!!.name)
        assertTrue(s.end.distanceM > 1000)
    }
}
