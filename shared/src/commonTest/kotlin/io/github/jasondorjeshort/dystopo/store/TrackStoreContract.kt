package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.GpxMetadata
import io.github.jasondorjeshort.dystopo.gpx.SyntheticTracks
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour every [TrackStore] must have; run against each implementation by a subclass. */
abstract class TrackStoreContract {
    abstract fun newStore(): TrackStore

    private val terrain = SyntheticTerrain.CONE

    /** A synthetic loop over the synthetic terrain (null island), without the heart-rate extensions a recorder can't produce. */
    protected fun loopPoints(n: Int = 60): List<TrackPoint> =
        SyntheticTracks.loop(terrain, 300.0, -200.0, 600.0, n).map { it.copy(extensions = null) }

    protected fun fixesOf(points: List<TrackPoint>) = points.map { LocationFix(it.lat, it.lon, it.ele, it.time!!, 4.0, "test") }

    @Test
    fun createAppendAndRead() {
        val s = newStore()
        val a = s.create("00000000-0000-4000-8000-00000000000a", "A", 100, recording = true)
        assertEquals(TrackSummary(a.id, "A", 100, 100, true, 0, 0), a)
        val pts = loopPoints(10)
        pts.take(4).forEach { s.append(a.id, it, now = 200) }
        pts.drop(4).forEachIndexed { i, p -> s.append(a.id, p, newSegment = i == 0, now = 300) }
        val t = s.track(a.id)!!
        assertEquals(listOf(4, 7), t.segments.map { it.points.size })
        assertEquals(pts, t.points)
        assertEquals("A", t.name)
        val sum = s.summary(a.id)!!
        assertEquals(11, sum.points)
        assertEquals(300, sum.updatedAt)
        assertTrue(sum.recording)
        s.setRecording(a.id, false, 400)
        s.rename(a.id, "Renamed", 500)
        assertEquals(TrackSummary(a.id, "Renamed", 100, 500, false, 11, 0), s.summary(a.id))
    }

    @Test
    fun listsInCreationOrderAndDeletes() {
        val s = newStore()
        s.create("00000000-0000-4000-8000-000000000002", "second", 20)
        s.create("00000000-0000-4000-8000-000000000001", "first", 10)
        s.append("00000000-0000-4000-8000-000000000001", TrackPoint(1.0, 2.0), now = 11)
        s.addWaypoint("00000000-0000-4000-8000-000000000001", Waypoint("00000000-0000-4000-8000-0000000000f1", "w", 1.0, 2.0), now = 12)
        s.addWaypoint(null, Waypoint("00000000-0000-4000-8000-0000000000f2", "free", 3.0, 4.0))
        assertEquals(listOf("first", "second"), s.list().map { it.name })
        assertEquals(1, s.list().first().waypoints)
        assertTrue(s.delete("00000000-0000-4000-8000-000000000001"))
        assertFalse(s.delete("00000000-0000-4000-8000-000000000001"))
        assertNull(s.track("00000000-0000-4000-8000-000000000001"))
        assertEquals(listOf("second"), s.list().map { it.name })
        assertEquals(emptyList(), s.waypoints("00000000-0000-4000-8000-000000000001"))
        assertEquals(listOf("free"), s.waypoints(null).map { it.name })
    }

    @Test
    fun unknownTrackAndDuplicateId() {
        val s = newStore()
        assertFailsWith<TrackNotFoundException> { s.append("nope", TrackPoint(0.0, 0.0)) }
        s.create("00000000-0000-4000-8000-000000000001", "x", 1)
        assertFailsWith<IllegalArgumentException> { s.create("00000000-0000-4000-8000-000000000001", "y", 2) }
        assertEquals(emptyList(), s.track("00000000-0000-4000-8000-000000000001")!!.segments)
    }

    @Test
    fun waypointsKeepEveryField() {
        val s = newStore()
        val id = s.create("00000000-0000-4000-8000-000000000001", "x", 1).id
        val w = Waypoint("00000000-0000-4000-8000-0000000000f1", "Camp", 46.8, -121.7, 1700.5, 123456, "desc", "cmt", "Campground", "camp", "<x:y>1</x:y>")
        s.addWaypoint(id, w)
        s.addWaypoint(id, w.copy(id = "00000000-0000-4000-8000-0000000000f2", name = "Second", ele = null, time = null))
        assertEquals(listOf(w, w.copy(id = "00000000-0000-4000-8000-0000000000f2", name = "Second", ele = null, time = null)), s.waypoints(id))
    }

    /**
     * The T31 done criterion: a synthetic fix sequence recorded into the store exports GPX equal to
     * T24's round trip of the same track (write, parse, write).
     */
    @Test
    fun recordedFixesExportLikeT24RoundTrip() {
        val s = newStore()
        var now = 1_700_000_000_000L
        val rec = TrackRecorder(s, ids = UuidGenerator(Random(7)), clock = { now })
        val first = loopPoints(40)
        val second = loopPoints(20).map { it.copy(time = it.time!! + 3_600_000) }
        val started = rec.start("Synthetic loop")
        fixesOf(first).forEach { assertTrue(rec.onFix(it)); now += 1000 }
        rec.pause()
        fixesOf(second).forEach { assertTrue(rec.onFix(it)); now += 1000 }
        val wp = rec.waypoint("End")!!
        val stopped = rec.stop()!!
        assertFalse(stopped.recording)
        assertEquals(first.size + second.size, stopped.points)

        val expectedTrack = Track(started.id, "Synthetic loop", listOf(TrackSegment(first), TrackSegment(second)))
        val original = GpxDocument(
            waypoints = listOf(wp),
            tracks = listOf(expectedTrack),
            metadata = GpxMetadata(name = "Synthetic loop", time = started.createdAt),
        )
        val t24RoundTrip = Gpx.write(Gpx.parse(Gpx.write(original)))
        val exported = TrackExport.gpx(s, started.id)!!
        assertEquals(t24RoundTrip, exported)
        // And the parsed export has the original's points and stats.
        val back = Gpx.parse(exported).tracks.single()
        assertEquals(expectedTrack.segments, back.segments)
        assertEquals(TrackStats.of(expectedTrack), TrackStats.of(back))
        assertEquals(rec.recording, false)
    }

    @Test
    fun recorderRejectsInaccurateAndStaleFixesAndKeepsLiveStats() {
        val s = newStore()
        val rec = TrackRecorder(s, RecorderOptions(maxAccuracyM = 20.0), clock = { 0L })
        assertFalse(rec.onFix(LocationFix(1.0, 1.0, time = 1)), "not recording")
        assertNull(rec.liveStats())
        val id = rec.start("t").id
        val fixes = fixesOf(loopPoints(10))
        assertTrue(rec.onFix(fixes[0]))
        assertFalse(rec.onFix(fixes[1].copy(accuracyM = 80.0)))
        assertTrue(rec.onFix(fixes[1]))
        assertFalse(rec.onFix(fixes[1]), "duplicate")
        assertFalse(rec.onFix(fixes[0]), "older")
        fixes.drop(2).forEach { rec.onFix(it) }
        assertEquals(1, rec.rejectedAccuracy)
        assertEquals(2, rec.rejectedTime)
        assertEquals(11, rec.accepted)
        val live = assertNotNull(rec.liveStats())
        assertEquals(TrackStats.of(s.track(id)!!), live)
        assertEquals(11, live.points)
    }

    @Test
    fun interruptedRecordingIsClosedOnRecovery() {
        val s = newStore()
        val crashed = TrackRecorder(s, clock = { 5L })
        val id = crashed.start("t").id
        crashed.onFix(LocationFix(1.0, 1.0, time = 10))
        // A new process: a fresh recorder over the same store.
        val fresh = TrackRecorder(s, clock = { 9L })
        assertEquals(listOf(id), fresh.recoverInterrupted())
        assertFalse(s.summary(id)!!.recording)
        assertEquals(1, s.summary(id)!!.points)
        assertEquals(emptyList(), fresh.recoverInterrupted())
    }

    @Test
    fun waypointWithoutTrackOrFix() {
        val s = newStore()
        val rec = TrackRecorder(s, clock = { 1L })
        assertNull(rec.waypoint("nowhere"))
        rec.onFix(LocationFix(46.8, -121.7, 1600.0, time = 50))
        val w = rec.waypoint(null)!!
        assertEquals("Waypoint 1", w.name)
        assertEquals(1600.0, w.ele)
        assertEquals(50, w.time)
        assertEquals(listOf(w), s.waypoints(null))
    }
}

class InMemoryTrackStoreTest : TrackStoreContract() {
    override fun newStore(): TrackStore = InMemoryTrackStore()
}
