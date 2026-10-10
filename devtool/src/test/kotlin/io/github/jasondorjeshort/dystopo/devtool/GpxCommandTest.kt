package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GpxCommandTest {
    private val dir: File = Files.createTempDirectory("gpx-command-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun brokenFilesAreOneLineUsageErrors() {
        val broken = File(dir, "broken.gpx").apply { writeText("<gpx version=\"1.1\"><trk><trkseg><trkpt lat=\"1\" lon=\"2\"></trkseg></gpx>") }
        val e = assertFailsWith<UsageError> { GpxCommand.load(broken) }
        assertTrue(e.message!!.startsWith("${broken.path} is not well-formed XML"), e.message)
        assertTrue('\n' !in e.message!!)

        val notGpx = File(dir, "kml.gpx").apply { writeText("<kml><Document/></kml>") }
        val e2 = assertFailsWith<UsageError> { GpxCommand.load(notGpx) }
        assertTrue(e2.message!!.startsWith("${notGpx.path} is not GPX"), e2.message)
    }

    @Test
    fun zoomFollowsTheFittedBounds() {
        // A small trail fits at the deepest automatic zoom.
        assertEquals(GpxCommand.MAX_AUTO_ZOOM, GpxCommand.fitZoom(BoundingBox(-121.76, 46.78, -121.70, 46.82)))
        // A long-distance track (degrees across) gets a shallower zoom inside the pixel budget, not an error.
        val big = BoundingBox(-122.5, 45.5, -120.5, 48.9)
        val z = GpxCommand.fitZoom(big)
        assertTrue(z < GpxCommand.MAX_AUTO_ZOOM)
        val w = MercatorWindow.forBbox(big, z)
        assertTrue(w.width.toLong() * w.height <= GpxCommand.MAX_AUTO_PIXELS)
        val deeper = MercatorWindow.forBbox(big, z + 1)
        assertTrue(deeper.width.toLong() * deeper.height > GpxCommand.MAX_AUTO_PIXELS, "z$z is the deepest that fits")
        // A whole continent still gets a zoom.
        assertTrue(GpxCommand.fitZoom(BoundingBox(-170.0, -60.0, 170.0, 80.0)) >= 1)
    }
}
