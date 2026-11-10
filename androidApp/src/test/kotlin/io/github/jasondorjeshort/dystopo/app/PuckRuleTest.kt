package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapRule
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T251: the puck's source switch, replayed. The first flight's bug was two listeners: the map's own stalled while the
 * recorder's kept recording, so the puck froze behind the track's head. Here a recording's fixes go to the recorder and the
 * puck by [PuckRule] the way `Tracking` and `MapController` apply it, while a second listener (the map's) keeps reporting
 * a stale position; the puck must sit on the track's head at every step, flagged fixes in the hollow look.
 */
class PuckRuleTest {
    private val t0 = 1_790_000_000_000L

    /** The puck as the rules move it: its position and look. */
    private class Puck {
        var lat = Double.NaN
        var lon = Double.NaN
        var mode = LocationPuck.Mode.GPS
        var held = 0
    }

    private fun mapFix(p: Puck, drives: Boolean, lat: Double, lon: Double) {
        if (!PuckRule.takes(fromRecorder = false, estimate = false, recorderDrives = drives, holdsPuck = false, poor = false)) { p.held++; return }
        p.lat = lat; p.lon = lon; p.mode = LocationPuck.Mode.GPS
    }

    @Test
    fun aRecordingDrivesThePuckOntoTheHeadWhateverTheMapListenerSays() {
        val rec = TrackRecorder(InMemoryTrackStore(), clock = { t0 })
        rec.start("walk", ActivityProfile.of(Activity.FOOT)) // bound 50 m
        val puck = Puck()
        // The replay: 60 fixes, 20 to 40 at 120 m (flagged, as `accuracy=5,120@20-40` gives on the phone).
        for (i in 0 until 60) {
            val acc = if (i in 20..40) 120.0 else 5.0
            val fix = LocationFix(46.85 + i * 1.5e-5, -121.76, null, t0 + i * 1_000L, acc, "injected")
            val kept = rec.onFix(fix)
            val drives = PuckRule.recorderDrives(rec.recording, rec.paused, rec.autoPaused)
            assertTrue(drives)
            if (kept && PuckRule.takes(fromRecorder = true, estimate = false, recorderDrives = drives, holdsPuck = false, poor = PuckRule.look(false, rec.lastKept!!.flags) != LocationPuck.Mode.GPS)) {
                puck.lat = fix.lat; puck.lon = fix.lon; puck.mode = PuckRule.look(false, rec.lastKept!!.flags)
            }
            // The map's own listener, stuck at the start (the frozen puck of the first flight): held off the puck.
            mapFix(puck, drives, 46.85, -121.76)
            val head = rec.lastKept!!
            assertEquals(0.0, TrackStats.distanceM(puck.lat, puck.lon, head.lat, head.lon), 0.01, "fix $i: the puck is on the head")
            assertEquals(if (i in 20..40) LocationPuck.Mode.FLAGGED else LocationPuck.Mode.GPS, puck.mode, "fix $i")
        }
        assertEquals(60, puck.held)
        assertEquals(21, rec.counts().flagged)

        // Paused, the map's listener serves again; stopped, too.
        rec.pause()
        assertFalse(PuckRule.recorderDrives(rec.recording, rec.paused, rec.autoPaused))
        mapFix(puck, PuckRule.recorderDrives(rec.recording, rec.paused, rec.autoPaused), 46.9, -121.7)
        assertEquals(46.9, puck.lat)
        rec.resume()
        assertTrue(PuckRule.recorderDrives(rec.recording, rec.paused, rec.autoPaused))
        rec.stop()
        assertFalse(PuckRule.recorderDrives(rec.recording, rec.paused, rec.autoPaused))
    }

    @Test
    fun theTerrainEstimateKeepsThePuckFromFlaggedFixes() {
        // T203 kept: while the estimate holds the puck a flagged recorder fix does not move it; a good one, and an estimate, do.
        assertFalse(PuckRule.takes(fromRecorder = true, estimate = false, recorderDrives = true, holdsPuck = true, poor = true))
        assertTrue(PuckRule.takes(fromRecorder = true, estimate = false, recorderDrives = true, holdsPuck = true, poor = false))
        assertTrue(PuckRule.takes(fromRecorder = false, estimate = true, recorderDrives = true, holdsPuck = true, poor = false))
        // Without a recording the map's listener under the hold is gated as before (T203).
        assertFalse(PuckRule.takes(fromRecorder = false, estimate = false, recorderDrives = false, holdsPuck = true, poor = true))
        assertTrue(PuckRule.takes(fromRecorder = false, estimate = false, recorderDrives = false, holdsPuck = false, poor = true))
        assertEquals(LocationPuck.Mode.ESTIMATE, PuckRule.look(true, 0))
    }

    @Test
    fun thePuckGreysAfterTheActivitysGapRule() {
        assertEquals(5 * 60_000L, PuckRule.staleAfterMs(ActivityProfile.of(Activity.AIR)))
        assertEquals(2 * 60_000L, PuckRule.staleAfterMs(ActivityProfile.of(Activity.ROAD)))
        val custom = ActivityProfile.of(Activity.FOOT).let { it.copy(gap = GapRule(60_000L, it.gap.minDistanceM)) }
        assertEquals(60_000L, PuckRule.staleAfterMs(custom))
        val never = ActivityProfile.of(Activity.BIKE).copy(gap = GapRule(null, null))
        assertEquals(3 * 60_000L, PuckRule.staleAfterMs(never), "no time gap set: the activity's default rule")
    }
}
