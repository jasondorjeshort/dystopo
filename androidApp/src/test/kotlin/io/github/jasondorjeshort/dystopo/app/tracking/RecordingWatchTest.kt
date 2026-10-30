package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T159: the recording's state, the watchdog and fallback rules ([RecordingWatch]) and what the notification says ([RecordingStatus]). */
class RecordingWatchTest {
    private val timeout = 60_000L

    @Test
    fun waitingStartsAfterTheWindowAndEndsWithAFix() {
        val w = RecordingWatch()
        w.begin(1_000)
        assertFalse(w.waiting(1_000 + RecordingWatch.WAITING_AFTER_MS - 1))
        assertTrue(w.waiting(1_000 + RecordingWatch.WAITING_AFTER_MS))
        w.primary(20_000, 120.0)
        assertFalse(w.waiting(21_000))
        assertEquals(120.0, w.lastPrimaryAccuracyM)
        w.primary(22_000, 0.0) // a network-style 0: unknown, the last real accuracy stays
        assertEquals(120.0, w.lastPrimaryAccuracyM)
    }

    @Test
    fun theWatchdogFiresOncePerTimeoutOfSilence() {
        val w = RecordingWatch()
        w.begin(0)
        w.primary(10_000, 5.0)
        assertFalse(w.watchdogDue(10_000 + timeout - 1, timeout))
        assertTrue(w.watchdogDue(10_000 + timeout, timeout))
        assertTrue(w.watchdogFired(10_000 + timeout, timeout), "no fix of any kind: a gap")
        assertEquals(1, w.watchdogRestarts)
        // Not again until another timeout has passed since its own firing.
        assertFalse(w.watchdogDue(10_000 + timeout + 5_000, timeout))
        assertTrue(w.watchdogDue(10_000 + 2 * timeout, timeout))
        // A GPS fix resets it.
        w.primary(10_000 + 2 * timeout, 5.0)
        assertFalse(w.watchdogDue(10_000 + 3 * timeout - 1, timeout))
    }

    @Test
    fun fallbackFixesKeepTheTrackGoingButNotTheGps() {
        val w = RecordingWatch()
        w.begin(0)
        w.fallback(30_000, 150.0)
        assertTrue(w.fallbackRecent(31_000))
        assertTrue(w.waiting(31_000), "a network fix does not end the wait for GPS")
        assertTrue(w.watchdogDue(timeout, timeout), "the watchdog keys on GPS fixes")
        assertFalse(w.watchdogFired(timeout, timeout), "fallback fixes within the timeout: no gap")
        assertEquals(1, w.fallbackFixes)
        assertFalse(w.fallbackRecent(30_000 + RecordingWatch.WAITING_AFTER_MS))
    }

    @Test
    fun aPauseDoesNotCountAsSilence() {
        val w = RecordingWatch()
        w.begin(0)
        w.resumed(500_000)
        assertFalse(w.waiting(505_000))
        assertFalse(w.watchdogDue(505_000, timeout))
    }

    @Test
    fun theOneState() {
        fun st(rec: Boolean = true, paused: Boolean = false, auto: Boolean = false, gap: GapReason? = null, down: Boolean = false, waiting: Boolean = false) =
            RecordingWatch.stateOf(rec, paused, auto, gap, down, waiting)
        assertEquals(RecState.IDLE, st(rec = false, paused = true, waiting = true))
        assertEquals(RecState.RECORDING, st())
        assertEquals(RecState.WAITING_FOR_GPS, st(waiting = true))
        assertEquals(RecState.WAITING_FOR_GPS, st(auto = true, waiting = true), "a silent GPS matters more than auto-pause")
        assertEquals(RecState.AUTO_PAUSED, st(auto = true))
        assertEquals(RecState.PAUSED, st(paused = true, waiting = true))
        assertEquals(RecState.STOPPED_BY_SYSTEM, st(gap = GapReason.STOPPED_BY_SYSTEM, waiting = true))
        assertEquals(RecState.STOPPED_BY_SYSTEM, st(down = true, paused = true))
        assertEquals(RecState.RECORDING, st(gap = GapReason.RESTARTED), "a restarted gap pending is still recording")
    }

    @Test
    fun theNotificationWords() {
        val m = ContourUnits.METERS
        val rec = RecordingStatus(RecState.RECORDING, "Skyline", 2410.0, m, startedAt = "14:02")
        assertEquals("Recording Skyline", rec.title)
        assertEquals("2.41 km since 14:02", rec.text)
        assertNull(rec.controlsLine)

        val wait = RecordingStatus(RecState.WAITING_FOR_GPS, "Skyline", 2410.0, m, silentS = 44, lastAccuracyM = 120.0)
        assertEquals("Waiting for GPS: Skyline", wait.title)
        assertEquals("No fix for 40 s, last accuracy 120 m", wait.text)
        assertEquals("Waiting for GPS: no fix for 40 s, last accuracy 120 m", wait.controlsLine)
        assertEquals(
            "No fix for 2 min, last accuracy 394 ft; using network location, 492 ft",
            wait.copy(silentS = 130, units = ContourUnits.FEET, fallbackAccuracyM = 150.0).text,
        )
        assertEquals("No fix for 10 s", wait.copy(silentS = 3, lastAccuracyM = null).text)

        assertEquals("Paused: Skyline", rec.copy(state = RecState.PAUSED).title)
        assertEquals("2.41 km so far. Resume to go on recording.", rec.copy(state = RecState.PAUSED).text)
        assertEquals("Auto-paused: Skyline", rec.copy(state = RecState.AUTO_PAUSED).title)

        val resumed = RecordingStatus(RecState.STOPPED_BY_SYSTEM, "Skyline", 2410.0, m, stoppedAt = "14:10")
        assertEquals("Resumed: Skyline", resumed.title)
        assertEquals("The system stopped recording at 14:10; it goes on with the next fix.", resumed.text)
        val down = resumed.copy(serviceDown = true)
        assertEquals("Recording stopped by the system", down.title)
        assertEquals("Skyline stopped at 14:10. Tap to go on recording it.", down.text)
        assertEquals("Recording track", RecordingStatus(RecState.RECORDING, null, 0.0, m).title)
    }
}
