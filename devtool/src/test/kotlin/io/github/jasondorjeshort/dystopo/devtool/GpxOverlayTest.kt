package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The GPX overlay draws where the track is, in the track colour, with arrows and markers counted (synthetic, null island). */
class GpxOverlayTest {
    @Test
    fun drawsTrackWaypointsAndProfile() {
        val window = MercatorWindow.forBbox(BoundingBox(-0.01, -0.005, 0.01, 0.005), 14)
        val map = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
        val pts = (0..20).map { TrackPoint(0.0, -0.008 + 0.0008 * it, 100.0 + it * 5) }
        val doc = GpxDocument(
            waypoints = listOf(Waypoint("w", "Camp", 0.002, 0.0), Waypoint("x", "Far away", 10.0, 10.0)),
            tracks = listOf(Track("t", "east", listOf(TrackSegment(pts)))),
        )
        val overlay = GpxOverlay(window, 1.4, 50.0)
        val image = overlay.draw(map, doc, 100)
        assertEquals(window.height + 100, image.height)
        val s = overlay.stats
        assertEquals(21, s.trackPoints)
        assertEquals(1, s.waypointsDrawn)
        assertEquals(1, s.waypointsOutside)
        assertEquals(0, s.pointsOutside)
        assertEquals(4, s.arrows) // 186 px of line, one every 50 px from 25 px
        assertTrue(s.profileSamples > 100)
        // Default units: metres and kilometres (100 to 200 m over 1.8 km).
        assertEquals("m", s.profileUnits)
        assertEquals(listOf("200 m", "100 m", "0 km", "1 km"), s.profileLabels)
        // The map in feet: the profile in feet and miles (T34 follow-up: it used to stay in metres).
        val feet = GpxOverlay(window, 1.4, 50.0, ContourUnits.FEET)
        feet.draw(map, doc, 100)
        assertEquals("ft", feet.stats.profileUnits)
        assertEquals(listOf("656 ft", "328 ft", "0 mi", "1 mi"), feet.stats.profileLabels)

        // On the line between two arrows (arrows at 25, 75, ... px from the start): the track violet.
        val y = overlay.y(0.0).toInt()
        val x = (overlay.x(-0.008) + 50).toInt()
        val c = image.getRGB(x, y)
        val r = c shr 16 and 0xff
        val g = c shr 8 and 0xff
        val b = c and 0xff
        assertTrue(b > 180 && r > 100 && g < 80, "track colour at ($x, $y): r=$r g=$g b=$b")
        // Off the line the map is untouched (black here).
        assertEquals(0, image.getRGB(x, y + 40) and 0xffffff)
    }
}
