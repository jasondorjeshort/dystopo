package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.GpxMetadata
import io.github.jasondorjeshort.dystopo.gpx.SyntheticTracks
import io.github.jasondorjeshort.dystopo.gpx.StatsOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.GapRule
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackGap
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

    /** What the recorder stores for [fixesOf] (T158: the fix's accuracy is kept). */
    protected fun recorded(points: List<TrackPoint>) = points.map { it.copy(accuracyM = 4.0) }

    private val foot = ActivityProfile.of(Activity.FOOT)

    private fun withGap(timeMs: Long?, minDistanceM: Double? = 50.0) = foot.copy(gap = GapRule(timeMs, minDistanceM))

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

        val expectedTrack = Track(
            started.id, "Synthetic loop", listOf(TrackSegment(recorded(first)), TrackSegment(recorded(second))),
            activity = Activity.FOOT, profile = foot, gaps = listOf(TrackGap(first.last().time!!, second.first().time!!, GapReason.PAUSED)),
        )
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
        assertEquals(expectedTrack.copy(name = back.name), back)
        assertEquals(TrackStats.of(expectedTrack), TrackStats.of(back))
        assertEquals(rec.recording, false)
    }

    @Test
    fun recorderFlagsInaccurateFixesRejectsStaleOnesAndKeepsLiveStats() {
        val s = newStore()
        // loopPoints(10) is 314 s between points: no gap splitting here.
        val rec = TrackRecorder(s, foot.copy(accuracyBoundM = 20.0, gap = GapRule(null, null)), clock = { 0L })
        assertFalse(rec.onFix(LocationFix(1.0, 1.0, time = 1)), "not recording")
        assertNull(rec.liveStats())
        val id = rec.start("t").id
        val fixes = fixesOf(loopPoints(10))
        assertTrue(rec.onFix(fixes[0]))
        assertTrue(rec.onFix(fixes[1].copy(accuracyM = 80.0)), "T158: kept, flagged")
        assertFalse(rec.onFix(fixes[1]), "duplicate")
        assertFalse(rec.onFix(fixes[0]), "older")
        fixes.drop(2).forEach { rec.onFix(it) }
        assertEquals(1, rec.flagged)
        assertEquals(2, rec.rejectedTime)
        assertEquals(11, rec.accepted)
        val stored = s.track(id)!!.points
        assertEquals(listOf(false, true) + List(9) { false }, stored.map { it.inaccurate })
        assertEquals(80.0, stored[1].accuracyM)
        val live = assertNotNull(rec.liveStats())
        assertEquals(TrackStats.of(s.track(id)!!), live)
        assertEquals(11, live.points)
    }

    @Test
    fun interruptedRecordingStaysOpenWithAGap() {
        val s = newStore()
        val crashed = TrackRecorder(s, ActivityProfile.of(Activity.BIKE), clock = { 5L })
        val id = crashed.start("t").id
        crashed.onFix(LocationFix(1.0, 1.0, time = 10))
        // A new process: a fresh recorder over the same store.
        var now = 9L
        val fresh = TrackRecorder(s, clock = { now })
        assertEquals(Activity.BIKE, fresh.profile.activity, "the remembered profile is the newest track's")
        assertEquals(listOf(id), fresh.recoverInterrupted())
        // T158: the track stays open, the recorder continues it after a "stopped by the system" gap.
        assertTrue(s.summary(id)!!.recording)
        assertEquals(id, fresh.activeId)
        assertEquals(GapReason.STOPPED_BY_SYSTEM, fresh.pendingGap)
        assertEquals(emptyList(), fresh.recoverInterrupted())
        assertTrue(fresh.onFix(LocationFix(1.0, 1.0001, time = 20_000)))
        assertEquals(listOf(TrackGap(10, 20_000, GapReason.STOPPED_BY_SYSTEM)), s.gaps(id))
        assertEquals(listOf(1, 1), s.track(id)!!.segments.map { it.points.size })
        assertEquals(listOf(1, 1), fresh.livePoints().map { it.size })
        assertNull(fresh.pendingGap)
        // Killed again; this time the user stops it before any fix: the track ends in the gap.
        val third = TrackRecorder(s, clock = { now })
        assertEquals(listOf(id), third.recoverInterrupted())
        now = 50_000
        third.stop()
        assertFalse(s.summary(id)!!.recording)
        assertEquals(TrackGap(20_000, 50_000, GapReason.STOPPED_BY_SYSTEM), s.gaps(id).last())
        assertEquals(emptyList(), TrackRecorder(s, clock = { now }).recoverInterrupted())
    }

    @Test
    fun onlyTheNewestInterruptedTrackIsResumed() {
        val s = newStore()
        val a = s.create("00000000-0000-4000-8000-00000000000a", "a", 1, recording = true).id
        val b = s.create("00000000-0000-4000-8000-00000000000b", "b", 2, recording = true).id
        val rec = TrackRecorder(s, clock = { 3L })
        assertEquals(listOf(a, b), rec.recoverInterrupted())
        assertFalse(s.summary(a)!!.recording)
        assertTrue(s.summary(b)!!.recording)
        assertEquals(b, rec.activeId)
        assertTrue(rec.onFix(LocationFix(1.0, 1.0, time = 10)))
        assertEquals(emptyList(), s.gaps(b), "an empty track has no hole to record")
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
        assertEquals(listOf(TrackGap(first.last().time!!, second.first().time!!, GapReason.NO_FIX)), s.gaps(id))
        rec.stop()
        val t = s.track(id)!!
        assertEquals(listOf(first.size, second.size), t.segments.map { it.points.size })
        // T171: the stored points are the fixes; the cached stats are of the smoothed line.
        assertEquals(TrackStats.of(listOf(first, second)).distanceM, TrackStats.of(t, StatsOptions(smoothing = null)).distanceM, 1e-9)
        assertEquals(TrackStats.of(t).distanceM, s.stats(id)!!.distanceM, 1e-9)
        // A gap of exactly the threshold does not split; disabled splitting never does.
        val r2 = TrackRecorder(s, withGap(60_000), clock = { 0L })
        val id2 = r2.start("edge").id
        r2.onFix(LocationFix(1.0, 1.0, time = 0))
        r2.onFix(LocationFix(1.0, 1.001, time = 60_000))
        r2.onFix(LocationFix(1.0, 1.002, time = 120_001))
        assertEquals(listOf(2, 1), s.track(id2)!!.segments.map { it.points.size })
        val r3 = TrackRecorder(s, withGap(null), clock = { 0L })
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
        val r2 = TrackRecorder(s, withGap(5 * 60_000L, 0.0), clock = { 0L })
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
            listOf(TrackSegment(pts.take(5)), TrackSegment(emptyList()), TrackSegment(pts.drop(5).map { it.copy(accuracyM = 12.5, speedMps = 1.25, courseDeg = 270.0, verticalAccuracyM = 8.0, flags = TrackPoint.INACCURATE) })),
            description = "d", comment = "c", source = "src", type = "hike", extensions = "<x:y>1</x:y>",
            activity = Activity.BOAT, profile = ActivityProfile.of(Activity.BOAT).copy(autoPause = true),
            gaps = listOf(TrackGap(pts[4].time!!, pts[5].time!!, GapReason.NO_FIX)),
        )
        val w = Waypoint("00000000-0000-4000-8000-0000000000f1", "Camp", 1.0, 2.0)
        val sum = s.insert(t, listOf(w), createdAt = 50, now = 60)
        assertEquals(TrackSummary(t.id, "Imported", 50, 60, false, 13, 1), sum)
        assertEquals(t.copy(segments = listOf(t.segments[0], t.segments[2])), s.track(t.id))
        assertEquals(t.gaps, s.gaps(t.id))
        assertEquals(t.profile, s.lastProfile())
        assertEquals(listOf(w), s.waypoints(t.id))
        assertFailsWith<IllegalArgumentException> { s.insert(t, emptyList(), 1, 1) }
        // Appending to an inserted track continues its last segment.
        s.append(t.id, pts.last().copy(time = pts.last().time!! + 1000))
        // T158: the activity can change afterwards; null clears it.
        s.setActivity(t.id, Activity.FOOT, null, 70)
        assertEquals(Activity.FOOT, s.track(t.id)!!.activity)
        assertNull(s.track(t.id)!!.profile)
        assertFailsWith<TrackNotFoundException> { s.setActivity("nope", null, null, 1) }
        assertFailsWith<TrackNotFoundException> { s.addGap("nope", TrackGap(1, 2, GapReason.PAUSED), 1) }
        // The same gap twice counts once.
        s.addGap(t.id, t.gaps.single(), 80)
        assertEquals(t.gaps, s.gaps(t.id))
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
    /**
     * T158 done criterion: a replay over a synthetic walk (1 Hz fixes along a line near Paradise, a public area)
     * with a minute of 100 m fixes injected, a 3-minute hole while walking and a 200 s stop keeps every fix but
     * the auto-paused ones, each with its accuracy, flags the 100 m ones, and records one "no fix" gap and one
     * "auto-paused" gap. The profile is the foot defaults with auto-pause on and a 2-minute gap rule (the default
     * is 5 minutes, ActivityProfile.of; a 3-minute hole is under it).
     */
    @Test
    fun replayKeepsPoorFixesAndRecordsGapsAndAutoPause() {
        val s = newStore()
        val profile = foot.copy(autoPause = true, gap = GapRule(2 * 60_000L, 50.0))
        val rec = TrackRecorder(s, profile, clock = { 0L })
        val id = rec.start("Replay").id
        val t0 = 1_790_000_000_000L
        val degPerM = 1.0 / (111_320.0 * kotlin.math.cos(46.85 * kotlin.math.PI / 180))
        var x = 0.0 // metres east along the line
        val noise = Random(158)
        val fixes = ArrayList<LocationFix>()
        for (sec in 0 until 1100) {
            val walking = sec !in 700 until 900
            if (walking) x += 1.3
            if (sec in 300 until 480) continue // the 3-minute hole: no fixes, the walker moves 234 m
            val poor = sec in 120 until 180
            val offset = if (poor) noise.nextDouble(-80.0, 80.0) else noise.nextDouble(-2.0, 2.0)
            fixes += LocationFix(
                46.85 + offset * degPerM * kotlin.math.cos(46.85 * kotlin.math.PI / 180), -121.73 + x * degPerM, 1600.0, t0 + sec * 1000L,
                if (poor) 100.0 else 5.0, "replay", speedMps = if (walking) 1.3 else 0.0, courseDeg = 90.0, verticalAccuracyM = 8.0,
            )
        }
        val kept = fixes.filter { rec.onFix(it) }
        rec.stop()
        val track = s.track(id)!!
        // Every fix kept but those held by auto-pause (710 s to 901 s: 10 s still to pause, 2 s moving to resume).
        assertEquals(192L, rec.skippedAutoPaused)
        assertEquals(fixes.size - 192, kept.size)
        assertEquals(kept.map { it.time }, track.points.map { it.time })
        assertEquals(kept.map { it.accuracyM }, track.points.map { it.accuracyM })
        assertTrue(track.points.all { it.speedMps != null && it.courseDeg == 90.0 && it.verticalAccuracyM == 8.0 })
        // The 100 m fixes are kept and flagged, nothing else is.
        assertEquals(60L, rec.flagged)
        assertEquals((120 until 180).map { t0 + it * 1000L }, track.points.filter { it.inaccurate }.map { it.time })
        assertEquals(0L, rec.rejectedSpeed, "100 m fixes 160 m apart are not teleports: their accuracy is allowed for")
        // One gap per hole, with its reason, and a segment after each.
        assertEquals(
            listOf(
                TrackGap(t0 + 299_000, t0 + 480_000, GapReason.NO_FIX),
                TrackGap(t0 + 709_000, t0 + 902_000, GapReason.AUTO_PAUSED),
            ),
            s.gaps(id),
        )
        assertEquals(track.gaps, s.gaps(id))
        assertEquals(listOf(300, 230, 198), track.segments.map { it.points.size })
        assertEquals(1L, rec.gapSplits)
        assertEquals(Activity.FOOT, track.activity)
        assertEquals(profile, track.profile)
        // A teleport (5 km in a second) is the one thing dropped.
        val r2 = TrackRecorder(s, foot, clock = { 0L })
        val id2 = r2.start("teleport").id
        assertTrue(r2.onFix(LocationFix(46.85, -121.73, time = t0, accuracyM = 5.0)))
        assertFalse(r2.onFix(LocationFix(46.90, -121.73, time = t0 + 1000, accuracyM = 5.0)))
        assertTrue(r2.onFix(LocationFix(46.85, -121.72999, time = t0 + 2000, accuracyM = 5.0)))
        assertEquals(1L, r2.rejectedSpeed)
        assertEquals(2, s.summary(id2)!!.points)
        // A network fix reporting accuracy 0 reads as unknown and is flagged as network.
        assertTrue(r2.onFix(LocationFix(46.85, -121.72998, time = t0 + 3000, accuracyM = 0.0, source = "network")))
        val last = s.track(id2)!!.points.last()
        assertNull(last.accuracyM)
        assertEquals(TrackPoint.NETWORK, last.flags)
    }

    /** After [TrackRecorder.REANCHOR_AFTER] teleports in a row the last kept fix was the stray one: the stream is re-anchored after a "rejected" gap. */
    @Test
    fun aRunOfTeleportsReanchors() {
        val s = newStore()
        val rec = TrackRecorder(s, foot, clock = { 0L })
        val id = rec.start("stray").id
        assertTrue(rec.onFix(LocationFix(46.95, -121.73, time = 0, accuracyM = 5.0))) // a stray first fix, 11 km off
        for (i in 1..4) assertFalse(rec.onFix(LocationFix(46.85, -121.73 + i * 1e-5, time = i * 1000L, accuracyM = 5.0)))
        assertTrue(rec.onFix(LocationFix(46.85, -121.73 + 5e-5, time = 5000, accuracyM = 5.0)))
        assertEquals(4L, rec.rejectedSpeed, "the fifth is kept, not counted as dropped")
        assertEquals(listOf(TrackGap(0, 5000, GapReason.REJECTED)), s.gaps(id))
        assertEquals(listOf(1, 1), s.track(id)!!.segments.map { it.points.size })
    }

    /** T158: the profile is data on the track: encoded, decoded, with the defaults of the research of 2026-10-30. */
    @Test
    fun profilesRoundTripAndDefaults() {
        for (a in Activity.entries) {
            val p = ActivityProfile.of(a)
            assertEquals(p, ActivityProfile.decode(p.encode()), a.code)
            assertEquals(a, Activity.of(a.code))
            assertFalse(p.autoPause, "auto-pause is off everywhere")
        }
        assertEquals(50.0, ActivityProfile.of(Activity.FOOT).accuracyBoundM)
        assertEquals(50.0, ActivityProfile.of(Activity.BIKE).accuracyBoundM)
        assertEquals(100.0, ActivityProfile.of(Activity.ROAD).accuracyBoundM)
        assertNull(ActivityProfile.of(Activity.AIR).accuracyBoundM)
        assertNull(ActivityProfile.of(Activity.AIR).gap.minDistanceM, "flying: time-only gaps")
        assertEquals(listOf(Activity.ROAD), Activity.entries.filter { it.suggestsAutoPause })
        // A newer build's keys are ignored, missing ones take the activity's defaults, an unknown activity reads as none.
        assertEquals(ActivityProfile.of(Activity.BIKE).copy(accuracyBoundM = 20.0), ActivityProfile.decode("v=2;activity=bike;acc=20;future=1"))
        assertEquals(ActivityProfile.of(Activity.AIR).copy(accuracyBoundM = 200.0), ActivityProfile.decode("activity=air;acc=200"))
        assertNull(ActivityProfile.decode("activity=hovercraft"))
        assertNull(ActivityProfile.decode(null))
        assertEquals(ActivityProfile.of(Activity.FOOT).copy(accuracyBoundM = null, gap = GapRule(null, null)), ActivityProfile.decode("activity=foot;acc=none;gap=none;gapm=none"))
        // The remembered profile: the one the last start used.
        val s = newStore()
        val rec = TrackRecorder(s, clock = { 0L })
        assertEquals(Activity.FOOT, rec.profile.activity)
        rec.start("car", ActivityProfile.of(Activity.ROAD))
        rec.stop()
        assertEquals(ActivityProfile.of(Activity.ROAD), TrackRecorder(s, clock = { 0L }).profile)
    }
}

class InMemoryTrackStoreTest : TrackStoreContract() {
    override fun newStore(): TrackStore = InMemoryTrackStore()
}
