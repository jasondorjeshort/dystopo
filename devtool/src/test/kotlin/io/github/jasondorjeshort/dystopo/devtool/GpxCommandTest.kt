package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.testing.test
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun statsPrintsRawAgainstSmoothedFlaggedAndGaps() {
        // T171: north along a meridian at null island (nothing real), 1 m/s, a fix every 2 s at 4 m with a zigzag of
        // 6 m; a wild flagged fix; a 5 minute hole recorded as a gap, then more of the same in a second segment.
        val t0 = 1_700_000_000_000L
        val m = 1 / 111_195.0
        fun seg(from: Int, start: Long) = (0..150).map { i ->
            val wild = i == 75
            TrackPoint((from + 2 * i) * m, (if (wild) 200.0 else if (i % 2 == 0) 6.0 else -6.0) * m, 100.0, start + i * 2000L,
                accuracyM = if (wild) 150.0 else 4.0, flags = if (wild) TrackPoint.INACCURATE else 0)
        }
        val a = seg(0, t0)
        val b = seg(400, a.last().time!! + 300_000L)
        val track = Track("00000000-0000-4000-8000-000000000001", "zigzag", listOf(TrackSegment(a), TrackSegment(b)),
            gaps = listOf(TrackGap(a.last().time!!, b.first().time!!, GapReason.NO_FIX)))
        val file = File(dir, "zigzag.gpx").apply { writeText(Gpx.write(GpxDocument(tracks = listOf(track)))) }
        val r = GpxCommand().test(listOf("--stats", file.path))
        assertEquals(0, r.statusCode, r.stderr)
        val t = Json.parseToJsonElement(r.stdout).jsonObject["tracks"]!!.jsonArray.single().jsonObject
        fun num(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.double
        assertEquals(2, t["flaggedPoints"]!!.jsonPrimitive.int)
        assertEquals(2, t["smoothedSegments"]!!.jsonPrimitive.int)
        val raw = num(t["raw"]!!.jsonObject, "distanceM")
        val smooth = num(t["smoothed"]!!.jsonObject, "distanceM")
        assertTrue(raw > 1500.0, "raw $raw") // the zigzag: 12 m sideways per 2 m forward
        assertTrue(kotlin.math.abs(smooth - 600.0) < 30.0, "smoothed $smooth") // 2 x 300 m
        val gap = t["gaps"]!!.jsonArray.single().jsonObject
        assertEquals("no-fix", gap["reason"]!!.jsonPrimitive.content)
        assertEquals(300.0, num(gap, "durationS"))
        assertEquals(2, t["stretches"]!!.jsonPrimitive.int)
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
