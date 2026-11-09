package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T251: the recorder's counts as one value ([TrackRecorder.counts]) and the point it kept last ([TrackRecorder.lastKept]),
 * replayed as a flight and a walk would arrive: the counts tell a lost fix (nothing arrived) from fixes that arrived and
 * were flagged or dropped, and the last kept point is what the puck draws.
 */
class RecorderCountsTest {
    private val t0 = 1_790_000_000_000L

    private fun recorder(a: Activity): TrackRecorder {
        val rec = TrackRecorder(InMemoryTrackStore(), clock = { t0 })
        rec.start("t", ActivityProfile.of(a))
        return rec
    }

    /** A fix [i] seconds in, [i] * 10 m north (a 36 km/h walk the bound's speeds allow), at [acc] metres. */
    private fun fix(i: Int, acc: Double, source: String = "gps", stepM: Double = 10.0) =
        LocationFix(46.78 + i * stepM / 111_195.0, -121.73, null, t0 + i * 1_000L, acc, source)

    @Test
    fun aWalkWithPoorFixesCountsThemFlaggedAndKeepsThem() {
        val rec = recorder(Activity.FOOT) // bound 50 m
        for (i in 0 until 10) assertTrue(rec.onFix(fix(i, 5.0)))
        for (i in 10 until 40) assertTrue(rec.onFix(fix(i, 120.0))) // poor: kept, flagged
        val c = rec.counts()
        assertEquals(40, c.accepted)
        assertEquals(30, c.flagged)
        assertEquals(0, c.dropped)
        assertEquals(120.0, c.lastFlaggedAccuracyM)
        assertEquals(TrackPoint.INACCURATE, rec.lastKept!!.flags and TrackPoint.INACCURATE, "the puck draws the last kept point flagged")
        rec.onFix(fix(40, 5.0))
        assertEquals(0, rec.lastKept!!.flags, "a good fix after them: the puck's normal look again")
        assertEquals(120.0, rec.counts().lastFlaggedAccuracyM, "the last flagged accuracy stays for the card")
    }

    @Test
    fun aFlightFlagsNothingAndCountsJumpsAndRepeatsApart() {
        val rec = recorder(Activity.AIR) // no bound; 350 m/s
        for (i in 0 until 10) rec.onFix(fix(i, 400.0, stepM = 200.0)) // 200 m/s, poor but no bound in the air
        rec.onFix(fix(9, 400.0, stepM = 200.0)) // a repeat of the last time
        val stray = LocationFix(10.0, 10.0, null, t0 + 10_500L, 10.0) // thousands of km in half a second
        rec.onFix(stray)
        val c = rec.counts()
        assertEquals(10, c.accepted)
        assertEquals(0, c.flagged)
        assertNull(c.lastFlaggedAccuracyM)
        assertEquals(1, c.rejectedTime)
        assertEquals(1, c.rejectedSpeed)
        assertEquals(1, c.dropped, "a repeat is not data lost; the jump is")
    }

    @Test
    fun aSilenceLongerThanTheGapSplitsAndIsCounted() {
        val rec = recorder(Activity.AIR) // gap 5 min, time only
        rec.onFix(fix(0, 20.0))
        rec.onFix(fix(1, 20.0))
        // 18 minutes of nothing (the second flight's card), then the fix comes back 18 min on at airliner speed.
        val back = LocationFix(46.78 + 18 * 60 * 200.0 / 111_195.0, -121.73, null, t0 + 1_000L + 18 * 60_000L, 30.0)
        assertTrue(rec.onFix(back))
        val c = rec.counts()
        assertEquals(1, c.gapSplits)
        assertEquals(0, c.flagged)
        assertEquals(0, c.dropped, "nothing dropped: the hole was a lost fix")
        assertEquals(2, rec.livePoints().size)
    }

    @Test
    fun aNewRecordingStartsTheCountsAfresh() {
        val rec = recorder(Activity.FOOT)
        rec.onFix(fix(0, 200.0))
        assertEquals(1, rec.counts().flagged)
        rec.start("again", ActivityProfile.of(Activity.FOOT))
        assertEquals(RecorderCounts(0, 0, 0, 0, 0, null), rec.counts())
        assertNull(rec.lastKept)
    }

    @Test
    fun anEstimateIsFlaggedButLeavesTheLastPoorAccuracyAlone() {
        val rec = recorder(Activity.FOOT)
        rec.onFix(fix(0, 5.0))
        rec.onFix(fix(1, 90.0, source = TrackRecorder.ESTIMATE_SOURCE))
        assertEquals(1, rec.counts().flagged)
        assertNull(rec.counts().lastFlaggedAccuracyM)
        assertTrue(rec.lastKept!!.estimated)
    }
}
