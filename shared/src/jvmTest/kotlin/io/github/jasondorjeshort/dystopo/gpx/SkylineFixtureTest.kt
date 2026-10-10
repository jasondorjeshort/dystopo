package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-made GPX fixture `commonTest/resources/gpx/skyline-loop.gpx`: a synthetic loop on the
 * public Skyline Trail at Paradise, Mount Rainier (see the comment in the file; not a recording).
 * Read here (JVM) because commonTest has no resource loading; the same file feeds `devtool gpx`.
 */
class SkylineFixtureTest {
    private val text = javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }

    @Test
    fun parsesFixture() {
        val (doc, ignored) = Gpx.read(text)
        assertEquals(emptyMap(), ignored)
        assertEquals("Skyline Trail loop (synthetic)", doc.metadata?.name)
        assertEquals(
            listOf("Paradise Inn & Visitor Center", "Glacier Vista", "Panorama Point", "Golden Gate Trail junction",
                "Stevens-Van Trump Memorial", "Sluiskin Falls", "Myrtle Falls"),
            doc.waypoints.map { it.name },
        )
        assertEquals("Trailhead <start/end>", doc.waypoints[0].description)
        assertEquals("View of the Nisqually Glacier & icefall", doc.waypoints[1].comment)
        assertEquals(5, doc.routes.single().points.size)
        val track = doc.tracks.single()
        assertEquals("Skyline Trail loop", track.name)
        assertEquals(1, track.segments.size)
        assertEquals(107, track.points.size)
        assertTrue(track.points.all { it.ele != null && it.time != null && GpxExtensions.heartRate(it.extensions) != null })
    }

    @Test
    fun statsOfFixture() {
        val s = TrackStats.of(Gpx.parse(text).tracks.single())
        // The real loop is about 5.5 mi (8.9 km) with about 1,700 ft (520 m) of climbing.
        assertTrue(s.distanceM in 7_500.0..9_000.0, "distance ${s.distanceM}")
        assertTrue(s.ascentM!! in 450.0..560.0, "ascent ${s.ascentM}")
        assertTrue(kotlin.math.abs(s.ascentM!! - s.descentM!!) < 5.0, "loop: ascent ${s.ascentM} vs descent ${s.descentM}")
        assertEquals(1656.0, s.minEleM)
        assertEquals(2147.0, s.maxEleM)
        assertEquals(3 * 3600.0 + 17 * 60 + 5, s.durationS) // 16:30:00 to 19:47:05
        // Not moving: the 15 minute stop at Panorama Point, and a few steep pitches where the pace rule
        // gives less than 0.3 m/s over the ground.
        assertTrue(s.movingTimeS!! <= s.durationS!! - 15 * 60 && s.movingTimeS!! > s.durationS!! - 20 * 60, "moving ${s.movingTimeS}")
        assertTrue(s.avgHeartRate!! in 90.0..140.0)
    }

    @Test
    fun fixtureRoundTrips() {
        val doc = Gpx.parse(text, UuidGenerator(Random(24)))
        val again = Gpx.parse(Gpx.write(doc), UuidGenerator(Random(24)))
        assertEquals(doc, again)
    }
}
