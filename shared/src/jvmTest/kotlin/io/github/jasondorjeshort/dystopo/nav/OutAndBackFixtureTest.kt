package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T180 on the synthetic Paradise fixtures (`gpx/paradise-out-and-back.gpx`, `gpx/paradise-drive-and-hike.gpx`: the
 * Skyline loop's first leg to Panorama Point and back, 4 m of noise, a 50 m step back, a three-minute stop; the second
 * with a 6-minute drive at 40 km/h before it) and the Skyline loop: the anchor at the end of the drive, one flip on the
 * recorded out-and-back, none on the loop, the navigated out-and-back arriving with one flip.
 */
class OutAndBackFixtureTest {
    private fun gpx(name: String) = Gpx.parse(javaClass.getResourceAsStream("/gpx/$name")!!.use { it.readBytes().decodeToString() })
    private val pace = TurnPace(false) { l, _, _ -> l / 1.25 }
    private fun points(name: String) = gpx(name).tracks.flatMap { it.points }.map { WayPoint(it.lat, it.lon, it.ele, it.time, it.speedMps, it.flags != 0) }

    @Test
    fun theDriveEndsWhereTheWayBackStarts() {
        val pts = points("paradise-drive-and-hike.gpx")
        val i = assertNotNull(WalkStart.find(pts))
        val trailhead = gpx("paradise-out-and-back.gpx").routes.single().points.first()
        val d = TrackStats.distanceM(pts[i].lat, pts[i].lon, trailhead.lat, trailhead.lon)
        assertTrue(d < 40, "the walk's first point is $d m from the trailhead (point $i)")
        assertTrue(pts[i - 1].speedMps!! >= WalkStart.WALK_MPS, "the point before it still drove")
        // Without the per-fix speeds, from the positions alone, within a few tens of metres of the same place.
        val j = assertNotNull(WalkStart.find(pts.map { it.copy(speedMps = null) }))
        assertTrue(TrackStats.distanceM(pts[j].lat, pts[j].lon, trailhead.lat, trailhead.lon) < 80, "from positions: point $j")
        val wb = WayBack(pace)
        pts.forEach { wb.add(it) }
        assertEquals(AnchorSource.WALK, wb.anchorSource)
        assertEquals(1, wb.retrace.flips)
        // Back at the car: the way back is nearly nothing, not the drive.
        val last = pts.last()
        val c = assertNotNull(wb.clock(last.timeMs!!, Deadline(DeadlineKind.TIME, last.timeMs!! + 3_600_000, "your time"), 30))
        assertTrue(c.retraceS < 120, "at the trailhead the way back is ${c.retraceS} s")
        assertEquals(TurnState.RETURNING, c.state)
    }

    @Test
    fun theRecordedOutAndBackFlipsOnceAndTheLoopNever() {
        val wb = WayBack(pace)
        points("paradise-out-and-back.gpx").forEach { wb.add(it) }
        assertEquals(AnchorSource.START, wb.anchorSource)
        assertEquals(1, wb.retrace.flips, "flips at ${wb.retrace.flipsAt}")
        val loop = WayBack(pace)
        points("skyline-loop.gpx").forEach { loop.add(it) }
        assertEquals(0, loop.retrace.flips, "the loop: ${loop.retrace.flipsAt}")
    }

    @Test
    fun theNavigatedOutAndBackArrivesWithOneFlip() {
        val doc = gpx("paradise-out-and-back.gpx")
        val path = NavPath.of(doc.routes.single())
        val nav = Navigator(path)
        for (p in doc.tracks.single().points) nav.onFix(p.lat, p.lon, p.time!!, p.accuracyM)
        assertTrue(nav.state!!.arrived, "arrived at the end of the out-and-back")
        assertEquals(1, nav.events.count { it.type == NavEvent.HEADING_BACK })
        assertEquals(0, nav.tracker.jumps)
        // The loop navigated: no flip.
        val sky = gpx("skyline-loop.gpx")
        val loopNav = Navigator(NavPath.of(sky.tracks.single()))
        for (p in sky.tracks.single().points) loopNav.onFix(p.lat, p.lon, p.time!!, 5.0)
        assertEquals(0, loopNav.events.count { it.type == NavEvent.HEADING_BACK })
        assertTrue(loopNav.state!!.arrived)
    }
}
