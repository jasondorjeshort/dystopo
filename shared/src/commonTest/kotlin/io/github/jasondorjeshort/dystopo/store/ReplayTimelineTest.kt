package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlin.test.Test
import kotlin.test.assertEquals

/** T31's single-point replay: recording starts, a fix stamped "now" arrives, then an old GPX replays in the same second. */
class ReplayTimelineTest {
    private val now = 1_790_000_000_000L
    private val fileStart = 1_600_000_000_000L // years before now, as replayed GPX files are

    private fun file(): List<Track> = listOf(
        Track(
            id = "t1",
            name = "loop",
            segments = listOf(TrackSegment((0 until 20).map { i -> TrackPoint(46.78 + i * 1e-4, -121.73, 1650.0 + i, fileStart + i * 10_000L) })),
        ),
    )

    private fun recordWith(shift: Boolean): Int {
        val store = InMemoryTrackStore()
        val rec = TrackRecorder(store, clock = { now })
        val id = rec.start("replay").id
        // The fix that raced the replay: a GPS or injected fix taken when recording started.
        rec.onFix(LocationFix(46.78, -121.73, 1650.0, now, 5.0, "injected"))
        var timed = TrackExport.replay(file(), rate = 100.0)
        if (shift) timed = TrackExport.continueAfter(timed, rec.lastAcceptedTime)
        timed.forEach { rec.onFix(it.fix) }
        rec.stop()
        return store.track(id)!!.points.size
    }

    @Test
    fun withoutTheShiftTheReplayIsDropped() {
        assertEquals(1, recordWith(shift = false))
    }

    @Test
    fun theReplayContinuesTheRecordingTimeline() {
        assertEquals(21, recordWith(shift = true))
    }

    @Test
    fun shiftKeepsIntervalsAndLeavesLaterReplaysAlone() {
        val timed = TrackExport.replay(file(), rate = 10.0)
        val shifted = TrackExport.continueAfter(timed, now)
        assertEquals(now + 1000, shifted.first().fix.time)
        assertEquals(timed.zipWithNext { a, b -> b.fix.time - a.fix.time }, shifted.zipWithNext { a, b -> b.fix.time - a.fix.time })
        assertEquals(timed.map { it.atMs }, shifted.map { it.atMs })
        assertEquals(timed, TrackExport.continueAfter(timed, fileStart - 1))
        assertEquals(timed, TrackExport.continueAfter(timed, null))
    }
}
