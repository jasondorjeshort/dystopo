package io.github.jasondorjeshort.dystopo.app.areas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T136 (T130's follow-up): a trip edited while its corridor downloads cancels the old corridor's download and starts the
 * new one; the old one used to finish first. A fake download slot: a job runs for a number of pauses unless cancelled,
 * when it ends at the next pause.
 */
class CorridorSwapTest {
    private class Slot {
        val log = ArrayList<String>()
        var id: String? = null
        var left = 0
        var cancelled = false
        var pending = false
        fun start(area: String, pauses: Int) { id = area; left = pauses; cancelled = false; log += "start $area" }
        fun pause() {
            val j = id ?: return
            if (cancelled) { log += "cancelled $j"; id = null; return }
            if (--left <= 0) { log += "done $j"; id = null }
        }
        fun swap() = CorridorSwap(running = { id }, cancel = { a -> (id == a).also { if (it) cancelled = true } }, pending = { pending }, pause = ::pause)
    }

    @Test
    fun anEditDuringTheDownloadCancelsTheOldOneAndStartsTheNew() {
        val s = Slot()
        val swap = s.swap()
        // The first run started the old corridor and waits for it; the trip is edited meanwhile (a newer ask).
        s.start("old", pauses = 100)
        s.pause()
        s.pending = true
        assertTrue(swap.waitIdle(orPending = true), "the wait ends at the newer ask, not when the old one is done")
        // The next run: the old corridor is stale and still running, so it is cancelled before the wait.
        s.pending = false
        assertEquals(listOf("old"), swap.cancelStale(listOf("old", "older")))
        assertFalse(swap.waitIdle())
        s.start("new", pauses = 3)
        assertFalse(swap.waitIdle(orPending = true))
        assertEquals(listOf("start old", "cancelled old", "start new", "done new"), s.log)
    }

    @Test
    fun anotherTripsDownloadIsNotCancelled() {
        val s = Slot()
        val swap = s.swap()
        s.start("other trip", pauses = 2)
        assertEquals(emptyList(), swap.cancelStale(listOf("old")))
        assertFalse(swap.waitIdle())
        assertEquals(listOf("start other trip", "done other trip"), s.log)
    }

    @Test
    fun theWaitGivesUpAtItsTimeout() {
        var now = 0L
        val swap = CorridorSwap(running = { "stuck" }, cancel = { false }, pending = { false }, pause = { now += 250 }, timeoutMs = 1_000, clock = { now })
        assertFalse(swap.waitIdle())
        assertEquals(1_000L, now)
    }
}
