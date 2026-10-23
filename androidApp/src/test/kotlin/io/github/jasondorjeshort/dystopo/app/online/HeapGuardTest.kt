package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.online.HeapGuard.Heap
import io.github.jasondorjeshort.dystopo.app.online.HeapGuard.MB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T127: the direct renders' heap policy (finding 2026-10-21-direct-view-out-of-memory-after-a-tour). */
class HeapGuardTest {
    private val max = 192 * MB // the emulator's and many phones' Java heap
    private val render = HeapGuard.weightOf(true)

    /** How many renders of [weight] a heap admits one after another, before any has allocated (used stays at [used]). */
    private fun admitted(weight: Long, used: Long, base: Long, cache: Long = 24 * MB, maxHeap: Long = max): Int {
        var n = 0
        while (n < 50 && HeapGuard.admits(Heap(used, maxHeap), running = n, committed = n * weight, weight = weight, baseBytes = base, cacheBytes = cache)) n++
        return n
    }

    @Test
    fun theReserveIsAnEighthOfTheHeapAndAtLeast24Mb() {
        assertEquals(24 * MB, Heap(0, 192 * MB).reserveBytes)
        assertEquals(64 * MB, Heap(0, 512 * MB).reserveBytes)
        assertEquals(24 * MB, Heap(0, 64 * MB).reserveBytes)
    }

    @Test
    fun aFreshProcessCountsWhatTheRendersWillTakeBeforeTheyTakeIt() {
        // 40 MB without renders, 24 MB of blocks, 24 MB reserve: 104 MB for renders, before any has allocated.
        assertEquals(3, admitted(render, used = 40 * MB, base = 40 * MB)) // 32 MB each, at every zoom
        assertEquals(render, HeapGuard.weightOf(false))
        // A big heap admits more than the ten permits would let run.
        assertTrue(admitted(render, used = 40 * MB, base = 40 * MB, cache = 64 * MB, maxHeap = 512 * MB) >= 10)
    }

    @Test
    fun aShortHeapLetsNoMoreStartButOneAlwaysRuns() {
        // The renders grew past their share: 150 MB used, 18 MB spare: the third does not start although the budget has room.
        assertFalse(HeapGuard.admits(Heap(150 * MB, max), running = 2, committed = 2 * render, weight = render, baseBytes = 40 * MB, cacheBytes = 24 * MB))
        assertTrue(HeapGuard.admits(Heap(100 * MB, max), running = 2, committed = 2 * render, weight = render, baseBytes = 40 * MB, cacheBytes = 24 * MB))
        // After a tour the caches hold 30 MB more: two coarse renders until a trim lowers the base.
        assertEquals(2, admitted(render, used = 70 * MB, base = 70 * MB))
        // Nothing left at all: the first render still runs.
        assertEquals(1, admitted(render, used = 190 * MB, base = 190 * MB))
    }

    @Test
    fun aShortHeapAsksForATrimAtMostEveryFiveSeconds() {
        val short = Heap(150 * MB, max) // 18 MB spare, under one render's 32
        val roomy = Heap(80 * MB, max)
        assertTrue(HeapGuard.wantsTrim(short, now = 100_000, lastTrimAt = 0))
        assertFalse(HeapGuard.wantsTrim(short, now = 100_000, lastTrimAt = 96_000))
        assertTrue(HeapGuard.wantsTrim(short, now = 101_000, lastTrimAt = 96_000))
        assertFalse(HeapGuard.wantsTrim(roomy, now = 100_000, lastTrimAt = 0))
    }

    @Test
    fun theDecodedBlocksTakeAnEighthOfTheHeapUpTo96Mb() {
        assertEquals(24 * MB, DirectSources.decodedCapBytes(192 * MB))
        assertEquals(96 * MB, DirectSources.decodedCapBytes(1024 * MB))
    }
}
