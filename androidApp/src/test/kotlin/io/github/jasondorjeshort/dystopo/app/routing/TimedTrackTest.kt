package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.app.TimedTrack
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T137: `GET /route/gpx?track=1&kmh=` resamples the route at one fix a second at the speed. */
class TimedTrackTest {
    // About 2.2 km north along a meridian near Paradise (public area), in three uneven segments, then a bend east.
    private val pts = listOf(
        RoutePoint(46.786, -121.735, 1600.0),
        RoutePoint(46.790, -121.735, 1640.0),
        RoutePoint(46.800, -121.735, null),
        RoutePoint(46.8001, -121.735, 1700.0),
        RoutePoint(46.8001, -121.725, 1720.0),
    )
    private val lengthM = pts.zipWithNext().sumOf { (a, b) -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }

    private fun dist(a: io.github.jasondorjeshort.dystopo.model.TrackPoint, b: io.github.jasondorjeshort.dystopo.model.TrackPoint) =
        TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)

    @Test
    fun pointCountAndSpacingAtTheSpeed() {
        for (kmh in listOf(4.0, 40.0, 90.0)) {
            val step = kmh / 3.6
            val t = TimedTrack.of(pts, kmh, 1_000_000L)
            // one fix at the start, one per whole step along the line, and the last point
            val expected = 1 + Math.floor(lengthM / step).toInt() + 1
            assertTrue(kotlin.math.abs(t.size - expected) <= 1, "kmh=$kmh: ${t.size} fixes for ${lengthM} m, expected about $expected")
            assertEquals(1_000_000L, t.first().time)
            assertEquals(pts.first().lat, t.first().lat)
            assertEquals(pts.last().lat, t.last().lat)
            assertEquals(pts.last().lon, t.last().lon)
            for (i in 1 until t.size - 1) {
                assertEquals(1000L, t[i].time!! - t[i - 1].time!!, "one fix a second")
                // straight line between fixes in one segment is the step; across a bend it is shorter (the chord)
                val d = dist(t[i - 1], t[i])
                assertTrue(d <= step + 0.01 && d > step * 0.5, "kmh=$kmh fix $i: $d m apart, step $step")
            }
            val lastGap = t.last().time!! - t[t.size - 2].time!!
            assertTrue(lastGap in 1L..1000L, "the last fix within a second: $lastGap ms")
        }
    }

    @Test
    fun spacingAlongAStraightSegmentIsExact() {
        val t = TimedTrack.of(pts.take(3), 36.0, 0L) // 10 m a second
        for (i in 1 until t.size - 1) assertEquals(10.0, dist(t[i - 1], t[i]), 0.01)
        // heights interpolated where both ends have one, none where one end has none
        assertEquals(1600.0 + 40.0 * (10.0 / TrackStats.distanceM(46.786, -121.735, 46.790, -121.735)), t[1].ele!!, 0.01)
        assertEquals(null, t[t.size - 2].ele)
    }

    @Test
    fun writesATrackWithTimes() {
        val gpx = TimedTrack.gpx(Route("r1", "Drive", pts), 40.0, 1_700_000_000_000L)
        val doc = Gpx.parse(gpx)
        assertEquals(0, doc.routes.size)
        val p = doc.tracks.single().points
        assertEquals(TimedTrack.of(pts, 40.0, 1_700_000_000_000L).size, p.size)
        assertEquals(1_700_000_000_000L, p.first().time)
        assertEquals(1_700_000_001_000L, p[1].time)
    }

    @Test
    fun edgeCases() {
        assertFailsWith<IllegalArgumentException> { TimedTrack.of(pts, 0.0, 0L) }
        assertEquals(1, TimedTrack.of(pts.take(1), 40.0, 0L).size)
        assertEquals(0, TimedTrack.of(emptyList(), 40.0, 0L).size)
    }
}
