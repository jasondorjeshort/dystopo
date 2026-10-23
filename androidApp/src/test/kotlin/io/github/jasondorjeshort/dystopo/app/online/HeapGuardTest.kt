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

    @Test
    fun theReserveIsAnEighthOfTheHeapAndAtLeast24Mb() {
        assertEquals(24 * MB, Heap(0, 192 * MB).reserveBytes)
        assertEquals(64 * MB, Heap(0, 512 * MB).reserveBytes)
        assertEquals(24 * MB, Heap(0, 64 * MB).reserveBytes)
    }

    @Test
    fun aFreshProcessRunsWhatItsHeapHoldsBesidesTheBlocks() {
        // 40 MB without renders, 24 MB of blocks, 24 MB reserve: 104 MB for renders, three of 32 MB, before any has allocated.
        assertEquals(3, HeapGuard.renderLimit(Heap(40 * MB, max), running = 0, permits = 10, baseBytes = 40 * MB, cacheBytes = 24 * MB))
        // The same with two running and 110 MB used: still three (the running ones are counted at 32 MB each).
        assertEquals(3, HeapGuard.renderLimit(Heap(110 * MB, max), running = 2, permits = 10, baseBytes = 40 * MB, cacheBytes = 24 * MB))
        // A big heap: the permits cap it.
        assertEquals(10, HeapGuard.renderLimit(Heap(40 * MB, 512 * MB), running = 0, permits = 10, baseBytes = 40 * MB, cacheBytes = 64 * MB))
    }

    @Test
    fun aShortHeapLetsNoMoreStartButNeverStopsAtZero() {
        // The renders grew past their share: 150 MB used, 18 MB spare: the two running keep going, none more.
        assertEquals(2, HeapGuard.renderLimit(Heap(150 * MB, max), running = 2, permits = 10, baseBytes = 40 * MB, cacheBytes = 24 * MB))
        // After a tour the caches hold 30 MB more: two at most until a trim lowers the base.
        assertEquals(2, HeapGuard.renderLimit(Heap(70 * MB, max), running = 0, permits = 10, baseBytes = 70 * MB, cacheBytes = 24 * MB))
        assertEquals(1, HeapGuard.renderLimit(Heap(190 * MB, max), running = 0, permits = 10, baseBytes = 190 * MB, cacheBytes = 24 * MB))
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
