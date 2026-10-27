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
        // After a tour the caches hold 30 MB more: two renders until a trim lowers the base.
        assertEquals(2, admitted(render, used = 70 * MB, base = 70 * MB))
        // Nothing left at all: the first render still runs.
        assertEquals(1, admitted(render, used = 190 * MB, base = 190 * MB))
    }

    @Test
    fun aShortHeapAsksForATrimAtMostEveryFiveSeconds() {
        val short = Heap(150 * MB, max) // 18 MB spare, under one render's 32 MB
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

    // ---- T149: the weight-only admission for builders ----

    /** A scripted heap: the limit each look sees, in order (the last repeats), with every call recorded. */
    private class Script(vararg limits: Long) {
        val limits = limits.toList()
        val calls = ArrayList<String>()
        var looks = 0
        var clock = 0L
        fun limit(): Long { calls += "look"; return limits[minOf(looks++, limits.size - 1)] }
        fun trim() { calls += "trim" }
        fun sleep(ms: Long) { calls += "sleep"; clock += ms }
    }

    private fun admit(s: Script, weight: Long, waitMs: Long = 1_000, stepMs: Long = 250) =
        HeapGuard.admitWeight("graph", weight, s::limit, s::trim, s::sleep, { s.clock }, waitMs, stepMs)

    @Test
    fun aBuildThatFitsTheFreeHeapStartsAtOnceWithoutATrim() {
        val s = Script(300 * MB)
        val a = admit(s, 200 * MB)
        assertTrue(a.admitted)
        assertEquals("now", a.stage)
        assertEquals(listOf("look"), s.calls)
        assertEquals(300 * MB, a.limitBytes)
    }

    @Test
    fun aShortHeapIsTrimmedFirstAndTheBuildStartsWhenTheTrimFreedEnough() {
        val s = Script(150 * MB, 260 * MB)
        val a = admit(s, 200 * MB)
        assertTrue(a.admitted)
        assertEquals("afterTrim", a.stage)
        assertEquals(listOf("look", "trim", "look"), s.calls) // the trim before any wait
        assertEquals(0L, a.waitedMs)
    }

    @Test
    fun thenItWaitsInStepsAndStartsWhenRoomComes() {
        val s = Script(150 * MB, 150 * MB, 150 * MB, 220 * MB)
        val a = admit(s, 200 * MB)
        assertTrue(a.admitted)
        assertEquals("afterWait", a.stage)
        assertEquals(listOf("look", "trim", "look", "sleep", "look", "sleep", "look"), s.calls)
        assertEquals(500L, a.waitedMs)
    }

    @Test
    fun aBuildThatNeverFitsIsRefusedAfterOneTrimAndTheBoundedWait() {
        val s = Script(150 * MB)
        val a = admit(s, 200 * MB, waitMs = 1_000, stepMs = 250)
        assertFalse(a.admitted)
        assertTrue(a.refused)
        assertEquals("refused", a.stage)
        assertEquals(1, s.calls.count { it == "trim" })
        assertEquals(4, s.calls.count { it == "sleep" }) // 1 s in 250 ms steps, then no more
        assertEquals(1_000L, a.waitedMs)
        assertEquals(200 * MB, a.estimateBytes)
        assertEquals(150 * MB, a.limitBytes)
        assertEquals(true, a.json()["refused"])
    }

    @Test
    fun aTestLimitRefusesWithoutWaiting() {
        // POST /route/guard?maxBytes=: the limit does not move, so nothing waits (admitBuild passes waitMs 0).
        val s = Script(10 * MB)
        val a = HeapGuard.admitWeight("graph", 20 * MB, s::limit, s::trim, s::sleep, { s.clock }, waitMs = 0, testLimit = true)
        assertFalse(a.admitted)
        assertEquals(listOf("look", "trim", "look"), s.calls)
        assertEquals(true, a.json()["testLimit"])
    }
}
