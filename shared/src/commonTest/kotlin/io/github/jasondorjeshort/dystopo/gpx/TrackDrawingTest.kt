package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
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
}
