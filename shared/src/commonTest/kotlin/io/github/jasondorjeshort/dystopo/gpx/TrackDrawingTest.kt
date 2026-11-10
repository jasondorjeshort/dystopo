package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackDrawingTest {
    @Test
    fun arrowsEverySpacingAlongTheLine() {
        // An L: 300 px east, then 200 px south (y down).
        val xy = doubleArrayOf(0.0, 0.0, 300.0, 0.0, 300.0, 200.0)
        val a = TrackDrawing.arrows(xy, 100.0)
        assertEquals(5, a.size) // at 50, 150, 250, 350, 450 px of 500
        assertEquals(LineArrow(50.0, 0.0, 0.0), a[0])
        assertEquals(LineArrow(250.0, 0.0, 0.0), a[2])
        assertEquals(300.0, a[3].x)
        assertEquals(50.0, a[3].y)
        assertTrue(abs(a[3].angle - PI / 2) < 1e-12, "heading south on screen")
        assertEquals(emptyList(), TrackDrawing.arrows(doubleArrayOf(0.0, 0.0, 40.0, 0.0), 100.0))
        assertEquals(1, TrackDrawing.arrows(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 60.0, 0.0), 100.0).size) // zero-length step skipped
    }

    @Test
    fun fitBoundsAddsMarginAndMinimumSize() {
        val t = Track("t", "t", listOf(TrackSegment(listOf(TrackPoint(46.78, -121.74), TrackPoint(46.81, -121.71)))))
        val b = TrackDrawing.fitBounds(listOf(t), emptyList(), emptyList(), marginFraction = 0.1)!!
        assertEquals(-121.743, b.west, 1e-9)
        assertEquals(-121.707, b.east, 1e-9)
        assertEquals(46.777, b.south, 1e-9)
        assertEquals(46.813, b.north, 1e-9)

        // A single waypoint grows to the minimum size.
        val w = Waypoint("w", "w", 0.0, 0.0)
        val small = TrackDrawing.fitBounds(emptyList(), emptyList(), listOf(w), minSizeM = 1000.0)!!
        val kmDeg = 1000.0 / (40_075_016.686 / 360.0)
        assertEquals(kmDeg, small.north - small.south, 1e-9)
        assertEquals(kmDeg, small.east - small.west, 1e-9)
        assertNull(TrackDrawing.fitBounds(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun fitBoundsTakesTheShortWayAcrossTheAntimeridian() {
        // T265: Rainier to Tokyo the short way, across the Pacific: the box runs east from Tokyo past 180 to Rainier.
        val t = Track("t", "t", listOf(TrackSegment(listOf(TrackPoint(46.85, -121.76), TrackPoint(52.0, -170.0), TrackPoint(53.0, 175.0), TrackPoint(35.68, 139.69)))))
        val b = TrackDrawing.fitBounds(listOf(t), emptyList(), emptyList(), marginFraction = 0.0, minSizeM = 0.0)!!
        assertEquals(139.69, b.west, 1e-9)
        assertEquals(-121.76 + 360.0, b.east, 1e-9)
        assertEquals(35.68, b.south, 1e-9)
        assertEquals(53.0, b.north, 1e-9)
        assertTrue(b.east - b.west < 100.0, "the Pacific, not the world: ${b.east - b.west} degrees")

        // Two points 11 degrees apart across the seam: 349 degrees the other way.
        val plain = TrackDrawing.fitBounds(listOf(Track("p", "p", listOf(TrackSegment(listOf(TrackPoint(0.0, -179.0), TrackPoint(1.0, 170.0)))))), emptyList(), emptyList(), marginFraction = 0.0, minSizeM = 0.0)!!
        assertEquals(170.0, plain.west, 1e-9, "the short way from 170 E to 179 W crosses the seam")
        assertEquals(181.0, plain.east, 1e-9)
        // A track that does not cross keeps the plain box.
        val europe = TrackDrawing.fitBounds(listOf(Track("e", "e", listOf(TrackSegment(listOf(TrackPoint(45.8, 6.8), TrackPoint(46.0, 7.0)))))), emptyList(), emptyList(), marginFraction = 0.0, minSizeM = 0.0)!!
        assertEquals(6.8, europe.west, 1e-9)
        assertEquals(7.0, europe.east, 1e-9)
    }

    @Test
    fun shortWayLonPicksTheNarrowerRange() {
        assertNull(TrackDrawing.shortWayLon(DoubleArray(0)))
        assertEquals(10.0 to 10.0, TrackDrawing.shortWayLon(doubleArrayOf(10.0)))
        assertEquals(-10.0 to 20.0, TrackDrawing.shortWayLon(doubleArrayOf(20.0, -10.0, 5.0)))
        assertEquals(179.5 to 180.5, TrackDrawing.shortWayLon(doubleArrayOf(179.5, -179.5)))
        // A tie (two points half the world apart): the range that does not cross the seam.
        assertEquals(-90.0 to 90.0, TrackDrawing.shortWayLon(doubleArrayOf(90.0, -90.0)))
        // 180 and -180 are one meridian.
        assertEquals(-180.0 to -180.0, TrackDrawing.shortWayLon(doubleArrayOf(180.0, -180.0)))
        val box = TrackDrawing.shortWayBox(listOf(46.85 to -121.76, 35.68 to 139.69))!!
        assertEquals(listOf(139.69, 35.68, 238.24, 46.85), box.toList().map { kotlin.math.round(it * 100) / 100 })
    }

    @Test
    fun gapsBridgeSegmentsApartLongerThanTheInterval() {
        fun p(lat: Double, t: Long?) = TrackPoint(lat, -121.73, time = t)
        val segs = listOf(
            listOf(p(46.78, 0L), p(46.781, 1_000L)),
            emptyList(), // skipped
            listOf(p(46.79, 601_000L), p(46.791, 602_000L)), // 10 min later: bridged
            listOf(p(46.792, 602_500L)), // half a second later: within the interval, not bridged
            listOf(p(46.80, 700_000L)),
        )
        val g = TrackDrawing.gaps(segs, intervalMs = 1_000)
        assertEquals(2, g.size)
        assertEquals(p(46.781, 1_000L), g[0].from)
        assertEquals(p(46.79, 601_000L), g[0].to)
        assertEquals(600_000L, g[0].durationMs)
        assertEquals(97_500L, g[1].durationMs)
        assertTrue(abs(g[0].straightM - 0.009 * 111_195.0) < 10.0, "straight distance ${g[0].straightM}")
        assertEquals("2 gaps, 11 min", TrackDrawing.gapText(g))
        // No times (a planned line imported as two segments): nothing to bridge.
        assertEquals(emptyList(), TrackDrawing.gaps(listOf(listOf(p(46.78, null)), listOf(p(46.79, null))), 1_000))
        assertNull(TrackDrawing.gapText(emptyList()))
        assertEquals("40 s", TrackDrawing.gapDuration(40_999))
        assertEquals("1 h", TrackDrawing.gapDuration(3_600_000))
        assertEquals("1 h 5 min", TrackDrawing.gapDuration(3_930_000))
    }

    @Test
    fun aReplayWithAnInjectedTenMinuteGapIsBridgedOnce() {
        // A walk on Rainier's flank, a fix a second, then nothing for ten minutes and 600 m on: the recorder's gap rule
        // (on foot: 5 min and 50 m) starts a new segment, and the drawing bridges exactly that hole.
        val t0 = 1_790_000_000_000L
        val store = InMemoryTrackStore()
        val rec = TrackRecorder(store, clock = { t0 })
        val id = rec.start("gap", ActivityProfile.of(Activity.FOOT)).id
        fun fix(i: Int, northM: Double, at: Long) = LocationFix(46.78 + northM / 111_195.0, -121.73, null, at, 5.0)
        for (i in 0 until 60) assertTrue(rec.onFix(fix(i, i * 1.2, t0 + i * 1_000L)))
        val resume = t0 + 59_000L + 600_000L
        for (i in 0 until 60) assertTrue(rec.onFix(fix(i, 671.0 + i * 1.2, resume + i * 1_000L)))
        assertEquals(1, rec.counts().gapSplits)
        rec.stop()
        val track = store.track(id)!!
        assertEquals(2, track.segments.size)
        val g = TrackDrawing.gaps(track)
        assertEquals(1, g.size)
        assertEquals(600_000L, g.single().durationMs)
        assertTrue(abs(g.single().straightM - 600.2) < 1.0, "straight ${g.single().straightM}")
        assertEquals("1 gap, 10 min", TrackDrawing.gapText(g))
        // The recorder's own gap record says the same hole.
        assertEquals(listOf(600_000L), store.gaps(id).map { it.durationMs })
    }
}
