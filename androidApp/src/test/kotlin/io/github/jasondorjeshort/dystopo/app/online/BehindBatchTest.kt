package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T368: landed fetches behind are announced once per settled batch, and only while the camera rests (proposal 0066's fallback). */
class BehindBatchTest {
    @Test
    fun oneRefreshPerSettledBatchNotOnePerTile() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        repeat(5) { b.started() }
        for (t in listOf(100L, 150L, 200L, 260L)) b.landed(t, 1000)
        assertEquals(0, b.take(700, moving = false), "one still in flight")
        b.landed(800, 1000)
        assertEquals(0, b.take(1000, false), "not quiet yet")
        assertEquals(5, b.take(1200, false))
        assertEquals(0, b.take(1300, false), "nothing new")
        assertEquals(1L, b.json()["refreshes"])
        assertFalse(b.pending)
    }

    @Test
    fun heldWhileTheCameraMovesThenOnceAtRest() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        b.started(); b.started()
        b.landed(0, 10); b.landed(100, 10)
        assertEquals(0, b.take(1_000, moving = true))
        assertEquals(0, b.take(1_250, moving = true))
        b.started(); b.landed(1_400, 10) // the pan asked for more and they landed
        assertEquals(0, b.take(2_000, moving = true))
        assertEquals(3, b.take(2_250, moving = false), "all three in one refresh at the rest")
        assertEquals(1L, b.heldForRest, "one batch held, counted once")
    }

    @Test
    fun aSlowFetchHoldsTheRestBackAtMostHoldMs() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        b.started(); b.started()
        b.landed(0, 10)
        assertEquals(0, b.take(5_000, false))
        assertEquals(1, b.take(8_000, false))
        assertTrue(b.pending, "the slow one still in flight")
        b.failed()
        assertFalse(b.pending)
    }

    @Test
    fun aSkippedRefreshIsAnnouncedAgain() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        b.started(); b.landed(0, 10)
        assertEquals(1, b.take(500, false))
        b.again(600, 1)
        assertEquals(1, b.take(1_000, false))
        assertEquals(1L, b.json()["refreshes"])
    }

    /**
     * T391 (the closing sweep of wave 49, `two-maps` T368:upgraded): between the take and the map's swap nothing waits and
     * nothing flies, yet the refresh has not run; `announcing` says so until its record is kept.
     */
    @Test
    fun aTakenRefreshIsAnnouncingUntilItEnds() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        b.started(); b.landed(0, 10)
        assertEquals(0, b.announcing)
        assertEquals(1, b.take(500, false))
        assertFalse(b.pending, "nothing waits or flies")
        assertEquals(1, b.announcing, "but the refresh is under way")
        assertEquals(1, b.json()["announcing"])
        b.announced()
        assertEquals(0, b.announcing)
        b.announced()
        assertEquals(0, b.announcing, "never below 0")
    }

    @Test
    fun aSkippedRefreshEndsItsAnnouncingOnceAndTheNextTakeStartsAnother() {
        val b = BehindBatch(quietMs = 400, holdMs = 8_000)
        b.started(); b.landed(0, 10)
        assertEquals(1, b.take(500, false))
        b.again(600, 1) // the style was loading: announced again
        b.announced() // its record (the skip) is kept
        assertEquals(0, b.announcing)
        assertEquals(1, b.take(1_100, false))
        assertEquals(1, b.announcing)
    }
}
