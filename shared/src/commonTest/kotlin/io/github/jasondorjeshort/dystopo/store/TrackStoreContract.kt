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
        assertFalse(rec.onFix(fixesOf(second).first().copy(time = first.last().time!! + 1)), "paused")
        rec.resume()
        fixesOf(second).forEach { assertTrue(rec.onFix(it)); now += 1000 }
        assertEquals(1, rec.skippedPaused)
        assertEquals(0, rec.gapSplits, "the resume started the second segment, not the hour-long gap")
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
        // loopPoints(10) is 314 s between points: no gap splitting here.
        val rec = TrackRecorder(s, RecorderOptions(maxAccuracyM = 20.0, segmentGapMs = null), clock = { 0L })
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

    /** T40 done criterion (shared half): a 10-minute hole in the fix times gives two segments, and stats don't bridge it. */
    @Test
    fun tenMinuteGapStartsNewSegment() {
        val s = newStore()
        val rec = TrackRecorder(s, clock = { 0L })
        val id = rec.start("gap").id
        val first = loopPoints(40)
        // T65: the walker reappears 300 m away (a gap in one place is a stop, see segmentGapMinM).
        val second = loopPoints(40).map { it.copy(lat = it.lat + 0.0027, time = it.time!! + (first.last().time!! - first.first().time!!) + 10 * 60_000) }
        fixesOf(first + second).forEach { assertTrue(rec.onFix(it)) }
        assertEquals(1, rec.gapSplits)
        rec.stop()
        val t = s.track(id)!!
        assertEquals(listOf(first.size, second.size), t.segments.map { it.points.size })
        assertEquals(TrackStats.of(listOf(first, second)).distanceM, s.stats(id)!!.distanceM, 1e-9)
        // A gap of exactly the threshold does not split; disabled splitting never does.
        val r2 = TrackRecorder(s, RecorderOptions(segmentGapMs = 60_000), clock = { 0L })
        val id2 = r2.start("edge").id
        r2.onFix(LocationFix(1.0, 1.0, time = 0))
        r2.onFix(LocationFix(1.0, 1.001, time = 60_000))
        r2.onFix(LocationFix(1.0, 1.002, time = 120_001))
        assertEquals(listOf(2, 1), s.track(id2)!!.segments.map { it.points.size })
        val r3 = TrackRecorder(s, RecorderOptions(segmentGapMs = null), clock = { 0L })
        val id3 = r3.start("never").id
        fixesOf(first + second).forEach { r3.onFix(it) }
        assertEquals(1, s.track(id3)!!.segments.size)
    }

    /** T65: a long stop with no fixes (the Skyline fixture's 15 minutes at Panorama Point) keeps one segment; moving through a gap splits. */
    @Test
    fun aGapSpentInOnePlaceDoesNotSplit() {
        val s = newStore()
        val rec = TrackRecorder(s, clock = { 0L })
        val id = rec.start("stop").id
        rec.onFix(LocationFix(46.8, -121.7, time = 0))
        rec.onFix(LocationFix(46.8001, -121.7, time = 60_000))
        rec.onFix(LocationFix(46.8001, -121.7, time = 60_000 + 15 * 60_000)) // same place, 15 min later
        rec.onFix(LocationFix(46.80013, -121.7, time = 60_000 + 15 * 60_000 + 400_000)) // 6.7 min, 33 m: still a stop
        rec.onFix(LocationFix(46.8030, -121.7, time = 60_000 + 15 * 60_000 + 800_000)) // 6.7 min, 320 m: a hole
        assertEquals(1, rec.gapSplits)
        assertEquals(listOf(4, 1), s.track(id)!!.segments.map { it.points.size })
        val r2 = TrackRecorder(s, RecorderOptions(segmentGapMinM = 0.0), clock = { 0L })
        val id2 = r2.start("strict").id
        r2.onFix(LocationFix(46.8, -121.7, time = 0))
        r2.onFix(LocationFix(46.80001, -121.7, time = 15 * 60_000))
        assertEquals(2, s.track(id2)!!.segments.size, "with no distance threshold a long gap that moved at all splits")
    }

    @Test
    fun pauseHoldsFixesAndResumeStartsSegment() {
        val s = newStore()
        val rec = TrackRecorder(s, clock = { 0L })
        rec.pause()
        assertFalse(rec.paused, "pause without recording is a no-op")
        val id = rec.start("p").id
        val fixes = fixesOf(loopPoints(60))
        fixes.take(20).forEach { rec.onFix(it) }
        rec.pause()
        assertTrue(rec.paused)
        fixes.subList(20, 30).forEach { assertFalse(rec.onFix(it)) }
        assertEquals(10, rec.skippedPaused)
        assertEquals(fixes[29], rec.lastFix, "the position still follows while paused")
        rec.resume()
        rec.resume()
        fixes.drop(30).forEach { assertTrue(rec.onFix(it)) }
        assertEquals(listOf(20, 31), s.track(id)!!.segments.map { it.points.size })
        assertEquals(listOf(20, 31), rec.livePoints().map { it.size })
        rec.pause()
        rec.stop()
        assertFalse(rec.paused)
    }

    @Test
    fun statsAreCachedUntilAppend() {
        val s = newStore()
        val id = s.create("00000000-0000-4000-8000-000000000001", "x", 1).id
        assertNull(s.stats("00000000-0000-4000-8000-0000000000ff"))
        val pts = loopPoints(20)
        pts.take(10).forEach { s.append(id, it) }
        val before = s.statsComputed
        val a = s.stats(id)!!
        assertEquals(TrackStats.of(s.track(id)!!), a)
        assertEquals(a, s.stats(id))
        assertEquals(before + 1, s.statsComputed, "second read is cached")
        s.rename(id, "renamed", 5)
        s.addWaypoint(id, Waypoint("00000000-0000-4000-8000-0000000000f1", "w", 1.0, 2.0))
        assertEquals(a, s.stats(id))
        assertEquals(before + 1, s.statsComputed, "renames and waypoints keep the cache")
        pts.drop(10).forEach { s.append(id, it) }
        val b = s.stats(id)!!
        assertEquals(before + 2, s.statsComputed, "an append invalidates")
        assertEquals(TrackStats.of(s.track(id)!!), b)
        assertEquals(21, b.points)
        // An empty track has stats too (zero points, no bounds).
        val e = s.create("00000000-0000-4000-8000-000000000002", "empty", 2).id
        assertEquals(TrackStats.of(emptyList<List<TrackPoint>>()), s.stats(e))
        assertEquals(TrackStats.of(emptyList<List<TrackPoint>>()), s.stats(e))
    }

    @Test
    fun waypointsRenameAndDelete() {
        val s = newStore()
        val id = s.create("00000000-0000-4000-8000-000000000001", "x", 1).id
        val w1 = Waypoint("00000000-0000-4000-8000-0000000000f1", "Camp", 1.0, 2.0)
        val w2 = Waypoint("00000000-0000-4000-8000-0000000000f2", "Lake", 1.5, 2.5)
        s.addWaypoint(id, w1, 2)
        s.addWaypoint(null, w2, 3)
        assertEquals(StoredWaypoint(id, w1), s.waypoint(w1.id))
        assertEquals(StoredWaypoint(null, w2), s.waypoint(w2.id))
        assertNull(s.waypoint("00000000-0000-4000-8000-0000000000f9"))
        assertTrue(s.renameWaypoint(w1.id, "High camp", 10))
        assertEquals("High camp", s.waypoints(id).single().name)
        assertEquals(10, s.summary(id)!!.updatedAt)
        assertFalse(s.renameWaypoint("00000000-0000-4000-8000-0000000000f9", "?", 11))
        assertTrue(s.deleteWaypoint(w1.id, 12))
        assertFalse(s.deleteWaypoint(w1.id, 13))
        assertEquals(0, s.summary(id)!!.waypoints)
        assertEquals(12, s.summary(id)!!.updatedAt)
        assertTrue(s.renameWaypoint(w2.id, "Tarn", 14))
        assertEquals(listOf("Tarn"), s.waypoints(null).map { it.name })
    }

    @Test
    fun insertKeepsEveryTrackField() {
        val s = newStore()
        val pts = loopPoints(12)
        val t = Track(
            "00000000-0000-4000-8000-000000000001", "Imported",
            listOf(TrackSegment(pts.take(5)), TrackSegment(emptyList()), TrackSegment(pts.drop(5))),
            description = "d", comment = "c", source = "src", type = "hike", extensions = "<x:y>1</x:y>",
        )
        val w = Waypoint("00000000-0000-4000-8000-0000000000f1", "Camp", 1.0, 2.0)
        val sum = s.insert(t, listOf(w), createdAt = 50, now = 60)
        assertEquals(TrackSummary(t.id, "Imported", 50, 60, false, 13, 1), sum)
        assertEquals(t.copy(segments = listOf(TrackSegment(pts.take(5)), TrackSegment(pts.drop(5)))), s.track(t.id))
        assertEquals(listOf(w), s.waypoints(t.id))
        assertFailsWith<IllegalArgumentException> { s.insert(t, emptyList(), 1, 1) }
        // Appending to an inserted track continues its last segment.
        s.append(t.id, pts.last().copy(time = pts.last().time!! + 1000))
        assertEquals(listOf(5, 9), s.track(t.id)!!.segments.map { it.points.size })
    }

    /** T40 done criterion: export, import into another store, export again: same ids, same text; a re-import is a no-op. */
    @Test
    fun gpxRoundTripKeepsIds() {
        val a = newStore()
        var now = 1_700_000_000_000L
        val rec = TrackRecorder(a, ids = UuidGenerator(Random(11)), clock = { now })
        val id = rec.start("Round trip").id
        val first = loopPoints(30)
        fixesOf(first).forEach { rec.onFix(it); now += 1000 }
        rec.pause(); rec.resume()
        fixesOf(loopPoints(12).map { it.copy(time = it.time!! + 7_200_000) }).forEach { rec.onFix(it) }
        val w = rec.waypoint("Top")!!
        rec.stop()
        val gpx = TrackExport.gpx(a, id)!!
        assertTrue("<dystopo:id>$id</dystopo:id>" in gpx)
        assertTrue("<dystopo:id>${w.id}</dystopo:id>" in gpx)

        val b = newStore()
        val r = TrackImport.import(b, gpx, now = 99, ids = UuidGenerator(Random(12)))
        assertEquals(listOf(id), r.imported.map { it.id })
        assertEquals(1, r.waypoints)
        assertEquals(listOf(w), b.waypoints(id))
        assertEquals(a.track(id), b.track(id))
        assertEquals(a.summary(id)!!.createdAt, b.summary(id)!!.createdAt)
        assertEquals(gpx, TrackExport.gpx(b, id))
        assertEquals(a.stats(id), b.stats(id))

        val again = TrackImport.import(b, gpx, now = 100)
        assertEquals(emptyList(), again.imported)
        assertEquals(listOf(id), again.skippedTracks)
        assertEquals(1, again.skippedWaypoints)
        assertEquals(1, b.list().size)
    }

    @Test
    fun importPlainGpx() {
        val s = newStore()
        val pts = loopPoints(8)
        val plain = Gpx.write(
            GpxDocument(
                waypoints = listOf(Waypoint("ignored-a", "A", 1.0, 2.0), Waypoint("ignored-b", "B", 1.1, 2.1)),
                tracks = listOf(Track("ignored-1", "", listOf(TrackSegment(pts))), Track("ignored-2", "Second", listOf(TrackSegment(pts.take(3))))),
                metadata = GpxMetadata(name = "From elsewhere"),
            ),
            ids = false,
        )
        assertFalse("dystopo" in plain)
        val r = TrackImport.import(s, plain, now = 5, ids = UuidGenerator(Random(3)))
        assertEquals(listOf("From elsewhere", "Second"), r.imported.map { it.name })
        assertEquals(listOf(pts.first().time, pts.first().time), r.imported.map { it.createdAt })
        assertTrue(r.imported.all { io.github.jasondorjeshort.dystopo.model.Uuid.isValid(it.id) })
        // Two tracks: the waypoints are free-standing.
        assertEquals(listOf("A", "B"), s.waypoints(null).map { it.name })
        assertEquals(0, r.imported.sumOf { it.waypoints })
        assertFailsWith<IllegalArgumentException> { TrackImport.import(s, "<gpx version=\"1.1\"></gpx>", now = 6) }
        assertFailsWith<IllegalArgumentException> { TrackImport.import(s, "not xml", now = 6) }
    }
}

class InMemoryTrackStoreTest : TrackStoreContract() {
    override fun newStore(): TrackStore = InMemoryTrackStore()
}
