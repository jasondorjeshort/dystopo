package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun aTrackAcrossTheAntimeridianIsCutAtTheSeam() {
        // T265: a flight from Rainier west across the Pacific: one step from 179 W to 179 E is two pieces meeting at 180,
        // never one line back across the whole map; its gap bridge across the seam is cut the same way.
        fun q(lat: Double, lon: Double, t: Long) = TrackPoint(lat, lon, time = t)
        val flight = Track(
            "f", "Flight",
            listOf(
                TrackSegment(listOf(q(46.85, -121.76, 0L), q(52.0, -160.0, 1_000L), q(53.0, -179.0, 2_000L), q(53.0, 179.0, 3_000L), q(50.0, 160.0, 4_000L))),
                // An hour later, on the other side again: the bridge from 160 E to 175 W crosses too.
                TrackSegment(listOf(q(51.0, -175.0, 3_604_000L), q(51.0, -170.0, 3_605_000L))),
            ),
        )
        val s = TrackScene.of(listOf(flight), emptyList(), emptyList())
        val line = s.lines.single { it.kind == "track" }
        assertEquals(3, line.parts.size, "the first segment in two pieces, the second whole")
        val (east, west) = line.parts[0] to line.parts[1]
        assertEquals(-180.0, east.last().second)
        assertEquals(180.0, west.first().second)
        assertEquals(east.last().first, west.first().first, "both pieces meet at the seam's latitude")
        assertEquals(53.0, east.last().first, 1e-9)
        for (part in s.lines.flatMap { it.parts }) {
            for ((a, b) in part.zipWithNext()) assertTrue(kotlin.math.abs(b.second - a.second) <= 180.0, "no step across the map in $part")
        }
        val gap = s.lines.single { it.kind == "gap" }
        assertEquals(2, gap.parts.size, "the bridge across the seam in two pieces")
        assertEquals(2, s.seamCuts)
        assertEquals(5, s.parts)
        // A track that does not cross stays one part per segment.
        assertEquals(0, TrackScene.of(listOf(a), emptyList(), emptyList()).seamCuts)
    }
}
