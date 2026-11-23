package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T348: the way-back cut-off and the stale rule ([RecordingAge]) on a fix an hour, a day and three days old, a recording
 * that is fresh after a restart, the answer "Keep recording", and "Finish it" ending the track at its last fix.
 */
class RecordingAgeTest {
    private val h = 3_600_000L
    private val now = 1_790_000_000_000L

    @Test
    fun anHourOldFixKeepsTheVerdictAndAsksNothing() {
        assertFalse(RecordingAge.wayBackStale(now - h, now))
        assertFalse(RecordingAge.asks(now - h, now))
        assertFalse(RecordingAge.wayBackStale(now - 2 * h, now), "exactly two hours is not past the bound")
        assertTrue(RecordingAge.wayBackStale(now - 2 * h - 1, now))
        assertFalse(RecordingAge.wayBackStale(null, now))
        assertFalse(RecordingAge.asks(null, now))
    }

    @Test
    fun aDayOldFixGivesNoVerdictAndAsks() {
        val at = now - 24 * h - 60_000
        assertTrue(RecordingAge.wayBackStale(at, now))
        assertTrue(RecordingAge.asks(at, now))
        assertFalse(RecordingAge.asks(now - 24 * h, now), "exactly a day is not past it")
        assertEquals("This recording stopped getting location updates 24 hours ago. Finish it where it ended, or keep recording?", RecordingAge.question(now - at))
    }

    @Test
    fun threeDaysOldSaysDays() {
        val at = now - 72 * h - 5 * 60_000
        assertTrue(RecordingAge.asks(at, now))
        assertEquals("This recording stopped getting location updates 3 days ago. Finish it where it ended, or keep recording?", RecordingAge.question(now - at))
        assertEquals("The last location update was 3 days ago.", RecordingAge.wayBackLine(now - at))
        assertEquals("2 hours", RecordingAge.ageText(2 * h + 1))
        assertEquals("1 hour", RecordingAge.ageText(h))
        assertEquals("47 hours", RecordingAge.ageText(48 * h - 1))
        assertEquals("2 days", RecordingAge.ageText(48 * h))
    }

    @Test
    fun keepRecordingIsNotAskedAgainUntilANewerFix() {
        val at = now - 72 * h
        assertFalse(RecordingAge.asks(at, now, keptFor = at))
        assertTrue(RecordingAge.asks(at, now, keptFor = at - 1), "kept for an older last fix: asked again")
    }

    @Test
    fun aRecordingFreshAfterARestartIsNotAsked() {
        val s = InMemoryTrackStore()
        var clock = now
        val first = TrackRecorder(s, ActivityProfile.of(Activity.FOOT), clock = { clock })
        val id = first.start("t").id
        first.onFix(LocationFix(46.78, -121.73, time = now + 1_000))
        clock = now + 10 * 60_000 // a restart ten minutes later
        val fresh = TrackRecorder(s, clock = { clock })
        fresh.recoverInterrupted()
        assertEquals(id, fresh.activeId)
        assertEquals(now + 1_000, fresh.lastFixAt)
        assertFalse(RecordingAge.asks(fresh.lastFixAt, clock))
        assertFalse(RecordingAge.wayBackStale(fresh.lastFixAt, clock))
    }

    @Test
    fun aRecordingLeftThreeDaysIsAskedAndFinishEndsItAtItsLastFix() {
        val s = InMemoryTrackStore()
        var clock = now
        val first = TrackRecorder(s, ActivityProfile.of(Activity.FOOT), clock = { clock })
        val id = first.start("t").id
        first.onFix(LocationFix(46.78, -121.73, time = now + 1_000))
        first.onFix(LocationFix(46.7801, -121.73, time = now + 11_000))
        clock = now + 72 * h // the app opened three days later
        val fresh = TrackRecorder(s, clock = { clock })
        fresh.recoverInterrupted()
        assertEquals(GapReason.STOPPED_BY_SYSTEM, fresh.pendingGap)
        assertTrue(RecordingAge.asks(fresh.lastFixAt, clock))
        val done = fresh.stop(atLastFix = true)!!
        assertFalse(done.recording)
        assertNull(fresh.activeId)
        assertNull(fresh.lastFixAt)
        assertTrue(s.gaps(id).isEmpty(), "no gap runs on to the moment of the answer")
        assertEquals(now + 11_000, s.track(id)!!.points.last().time)
    }

    @Test
    fun aPlainStopStillEndsInThePendingGap() {
        val s = InMemoryTrackStore()
        var clock = now
        val first = TrackRecorder(s, ActivityProfile.of(Activity.FOOT), clock = { clock })
        val id = first.start("t").id
        first.onFix(LocationFix(46.78, -121.73, time = now + 1_000))
        clock = now + 72 * h
        val fresh = TrackRecorder(s, clock = { clock })
        fresh.recoverInterrupted()
        fresh.stop()
        assertEquals(1, s.gaps(id).size)
    }

    @Test
    fun aTerrainEstimateIsNotALocationUpdate() {
        val s = InMemoryTrackStore()
        val rec = TrackRecorder(s, ActivityProfile.of(Activity.FOOT), clock = { now })
        rec.start("t")
        rec.onFix(LocationFix(46.78, -121.73, time = now + 1_000))
        rec.onFix(LocationFix(46.7801, -121.73, time = now + 30 * h, accuracyM = 30.0, source = TrackRecorder.ESTIMATE_SOURCE))
        assertEquals(now + 1_000, rec.lastFixAt)
    }

    @Test
    fun aFixSeenBeforeTheStartDoesNotAgeANewRecording() {
        val s = InMemoryTrackStore()
        var clock = now
        val rec = TrackRecorder(s, ActivityProfile.of(Activity.FOOT), clock = { clock })
        rec.onFix(LocationFix(46.78, -121.73, time = now - 72 * h)) // not recording: only "here"
        assertNull(rec.lastFixAt)
        rec.start("t")
        assertEquals(now, rec.lastFixAt)
        clock = now + 25 * h // no fix since the start
        assertTrue(RecordingAge.asks(rec.lastFixAt, clock))
    }
}
