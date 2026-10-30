package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.settings.HeapMark.Rule
import io.github.jasondorjeshort.dystopo.reports.Scrub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T143: the persisted mark's write rule and the Diagnostics row's text, on synthetic numbers. */
class DiagnosticsTest {
    private val MB = 1L shl 20

    @Test
    fun theMarkIsWrittenOnlyWhenThePeakRisesFiveMbAndAtMostEveryTenSeconds() {
        assertEquals(0L, Rule.waitMs(peak = 40 * MB, writtenPeak = 0, now = 1_000_000, lastWriteAt = 0), "the first peak of a session is written at once")
        assertNull(Rule.waitMs(44 * MB, 40 * MB, 2_000_000, 1_000_000), "a rise under 5 MB is never written")
        assertEquals(0L, Rule.waitMs(45 * MB, 40 * MB, 1_010_000, 1_000_000), "5 MB up after 10 s is written at once")
        assertEquals(1L, Rule.waitMs(90 * MB, 40 * MB, 1_009_999, 1_000_000), "a rise within 10 s of the last write waits for the gap")
        assertEquals(10_000L, Rule.waitMs(90 * MB, 40 * MB, 1_000_000, 1_000_000))
        assertNull(Rule.waitMs(30 * MB, 40 * MB, 9_000_000, 1_000_000), "a lower heap is never written")
    }

    private fun mark(peak: Long, native: Long? = null, pss: Long? = null, level: Int? = null, at: Long? = null, session: Long = 1) =
        HeapMark.Mark(peak, native, pss, level, at, 512 * MB, session, 2_000, "0.1.12-dev")

    @Test
    fun aWriteNeverLowersWhatTheSessionWrote() {
        val old = mark(200 * MB, native = 90 * MB, pss = 300_000, level = 20, at = 1_500)
        val raised = Rule.raise(old, mark(150 * MB, native = 60 * MB, pss = null, level = null))
        assertEquals(200 * MB, raised.peakHeapBytes)
        assertEquals(90 * MB, raised.peakNativeBytes)
        assertEquals(300_000L, raised.peakPssKb, "a PSS not read since is kept")
        assertEquals(20, raised.lastSignalLevel, "the last level is kept until a newer one")
        val newer = Rule.raise(old, mark(250 * MB, native = 95 * MB, pss = 310_000, level = 40, at = 1_900))
        assertEquals(listOf(250 * MB, 95 * MB, 310_000L), listOf(newer.peakHeapBytes, newer.peakNativeBytes, newer.peakPssKb))
        assertEquals(40, newer.lastSignalLevel)
        // A mark of another session (the file's, from before this launch) is not this session's floor.
        assertEquals(100 * MB, Rule.raise(old, mark(100 * MB, session = 2)).peakHeapBytes)
    }

    private fun snapshot(exit: Diagnostics.Exit? = null, previous: HeapMark.Mark? = null, sdk: Int = 35) = Diagnostics.Snapshot(
        at = 1_700_000_000_000, heapMaxBytes = 576 * MB, heapUsedBytes = 42 * MB + 300 * 1024, peakHeapBytes = 120 * MB,
        nativeAllocatedBytes = 80 * MB, pssKb = 215_000, pssReadMs = 31, availMemBytes = 1234 * MB, lowMemory = false,
        rendersAdmitted = 120, rendersWaited = 4, trims = 3, droppedBytes = 45 * MB, lastTrimAt = 1_700_000_000_000,
        signals = 2, lastSignalLevel = 80, lastSignalAt = 1_700_000_000_000,
        exit = exit, exitSupported = sdk >= 30, previous = previous, current = null,
        version = "0.1.12-dev", versionCode = 1234, buildType = "debug", android = "15", sdk = sdk, device = "Google sdk_gphone64_x86_64",
        memoryClassMb = 192, largeMemoryClassMb = 512, largeHeap = true,
    )

    @Test
    fun theRowSaysTheNumbersInMb() {
        val lines = Diagnostics.nowLines(snapshot()).map { it.toString() }
        assertEquals("Max heap: 576.0 MB", lines[0])
        assertEquals("Heap used now: 42.3 MB", lines[1])
        assertEquals("Peak since launch: 120.0 MB", lines[2])
        assertEquals("PSS: 210.0 MB", lines[4])
        assertEquals("Map renders: 120 started, 4 waited for memory", lines[6])
        assertTrue(lines[7].startsWith("Trims: 3, 45.0 MB dropped, last at "), lines[7])
        assertTrue(lines[8].matches(Regex("Last memory signal: level 80 \\(complete\\) at \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d")), lines[8])
    }

    @Test
    fun theLastSessionNamesHowItEndedAndItsMark() {
        val s = snapshot(Diagnostics.Exit(10, Diagnostics.reasonName(10), 1_699_999_000_000, 300 * 1024, 250 * 1024, null), mark(150 * MB, native = 70 * MB, pss = 280 * 1024, level = 20, at = 1_699_998_000_000))
        val lines = Diagnostics.lastSessionLines(s).map { it.toString() }
        assertTrue(lines[0].startsWith("Ended: force-stopped at "), lines[0])
        assertEquals("Memory at the last system sample: RSS 300.0 MB, PSS 250.0 MB", lines[1])
        assertEquals("Peak heap: 150.0 MB of 512.0 MB", lines[2])
        assertEquals("Peak native heap: 70.0 MB", lines[3])
        assertEquals("Peak PSS: 280.0 MB", lines[4])
        assertTrue(lines[5].startsWith("Last memory signal: level 20 (app hidden) at "), lines[5])
        assertEquals(listOf("Ended: not reported before Android 11", "Peak heap: no record"), Diagnostics.lastSessionLines(snapshot(sdk = 29)).map { it.toString() })
    }

    @Test
    fun theTrackLinesCountMarkedPointsAndGapsByReason() { // T172
        val gaps = listOf(
            io.github.jasondorjeshort.dystopo.model.TrackGap(1_000, 181_000, io.github.jasondorjeshort.dystopo.model.GapReason.NO_FIX),
            io.github.jasondorjeshort.dystopo.model.TrackGap(200_000, 250_000, io.github.jasondorjeshort.dystopo.model.GapReason.AUTO_PAUSED),
            io.github.jasondorjeshort.dystopo.model.TrackGap(300_000, 310_000, io.github.jasondorjeshort.dystopo.model.GapReason.NO_FIX),
        )
        val live = snapshot().copy(track = Diagnostics.TrackDiag(true, 1_700_000_000_000, null, io.github.jasondorjeshort.dystopo.model.Activity.FOOT, 340, 12, 3, gaps, 1))
        val lines = Diagnostics.trackLines(live).map { it.toString() }
        assertEquals("Recording now", Diagnostics.trackHeading(live))
        assertEquals("Activity: On foot", lines[1])
        assertEquals("Points: 340, 12 less accurate than the limit, 3 from network location", lines[2])
        assertEquals("Gaps: 3, 4 min in all: 2 no fix, 1 auto-paused", lines[3])
        assertEquals("GPS restarts by the watchdog: 1", lines[4])
        val stopped = snapshot().copy(track = Diagnostics.TrackDiag(false, 1_700_000_000_000, 1_700_000_600_000, null, 10, 0, 0, emptyList(), null))
        assertEquals("Last track", Diagnostics.trackHeading(stopped))
        assertEquals(listOf("Activity: not set", "Points: 10, 0 less accurate than the limit", "Gaps: none"), Diagnostics.trackLines(stopped).drop(1).map { it.toString() })
        assertTrue(Diagnostics.trackLines(stopped)[0].toString().contains(", ended "))
        assertEquals(listOf("Tracks: none recorded"), Diagnostics.trackLines(snapshot()).map { it.toString() })
        assertTrue("Points: 340, 12 less accurate than the limit, 3 from network location" in Diagnostics.report(live))
        assertEquals(listOf("35 s", "4 min 10 s", "1 h 5 min", "1 h"), listOf(35_000L, 250_000L, 3_900_000L, 3_600_000L).map { Diagnostics.span(it) })
    }

    @Test
    fun theReportCarriesTheRowAndThePhoneAndTheScrubLeavesItAlone() {
        val s = snapshot(previous = mark(150 * MB))
        val r = Diagnostics.report(s)
        for (l in Diagnostics.nowLines(s) + Diagnostics.lastSessionLines(s)) assertTrue(l.toString() in r, "the report lacks \"$l\"")
        assertTrue("App: 0.1.12-dev (1234), debug" in r, r)
        assertTrue("Android 15 (API 35), Google sdk_gphone64_x86_64" in r, r)
        assertTrue("Memory class: 192 MB, large memory class: 512 MB, large heap: yes" in r, r)
        assertEquals(r, Scrub.text(r), "the scrubber must not touch a diagnostics number")
    }
}
