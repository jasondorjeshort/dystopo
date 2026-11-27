package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.readout.GpsClockCheck
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T388 (T385's follow-up): a phone clock that runs fast and is set back in mid-recording. The fixes are good (the
 * receiver's own time is right); while the phone is 46 minutes fast they are flagged and kept at the phone's time
 * ([GpsClockCheck.trackTime]); then the phone's clock is corrected and the good fixes carry times 46 minutes earlier than
 * the points already kept. Through [TrackTimes] the recorder drops none of them and the track's times never go
 * backwards; a duplicate is still dropped. Synthetic positions walking north from Paradise (Mount Rainier, public).
 */
class TrackTimesTest {
    private val fastMs = 46 * 60_000L
    private val start = 1_795_000_000_000L // the real time of the first fix

    private fun fix(i: Int, time: Long) = LocationFix(lat = 46.786 + i * 0.00002, lon = -121.735, ele = 1650.0, time = time, accuracyM = 5.0, source = "gps")

    @Test
    fun aPhoneClockSetBackDropsNoGoodFix() {
        val store = InMemoryTrackStore()
        var wall = start + fastMs
        val recorder = TrackRecorder(store, clock = { wall })
        val track = recorder.start("clock set back")
        val times = TrackTimes()
        val handed = ArrayList<Long>()
        var kept = 0
        for (i in 0 until 60) {
            val real = start + i * 1000L // the receiver's own time, right
            val elapsed = 10_000L + i * 1000L // the fix's elapsed-realtime stamp
            val phoneFast = i < 30 // the phone's clock is corrected after 30 s
            wall = real + if (phoneFast) fastMs else 0L
            val phone = GpsClockCheck.phoneTime(elapsed, elapsed, wall)
            val f = times.fix(fix(i, GpsClockCheck.trackTime(real, phone)), elapsed, elapsed)
            handed += f.time
            if (recorder.onFix(f)) kept++
        }
        assertEquals(60, kept, "every good fix kept; rejectedTime ${recorder.rejectedTime}")
        assertEquals(0, recorder.rejectedTime)
        assertTrue(handed.zipWithNext().all { (a, b) -> b > a }, "times never go backwards: $handed")
        assertEquals(1000L, handed[30] - handed[29], "the first corrected fix goes on at the recorded pace")
        assertEquals(30L, times.continued)
        val points = store.track(track.id)!!.segments.flatMap { it.points }
        assertEquals(60, points.size)
        assertTrue(points.zipWithNext().all { (a, b) -> b.time!! > a.time!! }, "the recorded track's times never go backwards")
        // A duplicate (no newer elapsed stamp) keeps its own time, so the recorder still drops it.
        val dup = times.fix(fix(59, start + 59_000L), 10_000L + 59_000L, 10_000L + 60_000L)
        assertEquals(start + 59_000L, dup.time)
        assertTrue(!recorder.onFix(dup))
        // A new recording starts on the fixes' own times.
        times.reset()
        assertEquals(start + 70_000L, times.fix(fix(0, start + 70_000L), 80_000L, 80_000L).time)
    }

    @Test
    fun goodFixesWithARightClockPassUnchanged() {
        val times = TrackTimes()
        for (i in 0 until 10) {
            val f = fix(i, start + i * 1000L)
            assertEquals(f, times.fix(f, 1_000L + i * 1000L, 1_000L + i * 1000L))
        }
        assertEquals(0L, times.continued)
    }
}
