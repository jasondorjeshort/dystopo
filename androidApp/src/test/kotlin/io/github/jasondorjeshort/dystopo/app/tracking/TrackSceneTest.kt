package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T262: the overlay draws every shown track, not one: each track's line, its start and end marks, its own gap bridges (T260),
 * the shown routes' lines and the waypoints; the recording in a scene of its own (drawn on top) with its "current" mark.
 */
class TrackSceneTest {
    private fun p(lat: Double, t: Long) = TrackPoint(lat, -121.73, time = t)

    private val a = Track("a", "First", listOf(TrackSegment(listOf(p(46.78, 0L), p(46.781, 1_000L), p(46.782, 2_000L)))))
    // Two segments ten minutes apart: one bridge on this track only.
    private val b = Track("b", "Second", listOf(TrackSegment(listOf(p(46.79, 0L), p(46.791, 1_000L))), TrackSegment(listOf(p(46.80, 601_000L), p(46.801, 602_000L)))))
    // One point: no line, a start mark only.
    private val c = Track("c", "Third", listOf(TrackSegment(listOf(p(46.81, 0L)))))

    @Test
    fun everyShownTrackIsDrawnWithItsOwnMarksAndBridges() {
        val route = "r" to listOf(46.70 to -121.70, 46.71 to -121.71)
        val w = Waypoint("w", "Camp", 46.785, -121.73)
        val s = TrackScene.of(listOf(a, b, c), listOf(route, "short" to listOf(46.0 to -121.0)), listOf(w))
        assertEquals(listOf("route" to "r", "track" to "a", "track" to "b", "gap" to "b"), s.lines.map { it.kind to it.id }, "a route under the tracks; a one-point route is left out")
        assertEquals(2, s.lines.single { it.kind == "track" && it.id == "b" }.parts.size, "one part per segment")
        assertEquals(1, s.bridges)
        assertEquals(600_000L, s.bridgedMs)
        assertEquals(
            listOf("start" to "a", "end" to "a", "start" to "b", "end" to "b", "start" to "c", "waypoint" to "w"),
            s.marks.map { it.kind to it.id },
        )
        assertEquals("Camp", s.marks.last().name)
        assertEquals(4 + 6, s.size)
    }

    @Test
    fun theRecordingHasItsCurrentMark() {
        val s = TrackScene.of(listOf(a), emptyList(), emptyList(), live = true)
        assertEquals(listOf("start", "current"), s.marks.map { it.kind })
        assertEquals(0, TrackScene.of(emptyList(), emptyList(), emptyList(), live = true).size, "nothing records: nothing on top")
    }
}
