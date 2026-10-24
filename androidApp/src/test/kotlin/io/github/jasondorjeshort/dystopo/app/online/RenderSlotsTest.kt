package io.github.jasondorjeshort.dystopo.app.online

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T106 (a T88 follow-up): a DEM render interrupted while it waits (for the coarse renders, or for a permit) gives its
 * counters back. Before, `rendering` and `coarseActive` were raised outside the `try`: one interrupt left the map never
 * counting its renders done and every later render at the view's zoom waiting the whole coarse hold.
 */
class RenderSlotsTest {
    private fun interruptWhileWaiting(slots: RenderSlots, coarse: Boolean): Throwable? {
        val failure = AtomicReference<Throwable?>()
        val t = thread { runCatching { slots.run(coarse) { "rendered" } }.onFailure { failure.set(it) } }
        Thread.sleep(150) // it is waiting now (for a permit, or for the coarse render in flight)
        t.interrupt()
        t.join(5_000)
        assertTrue(!t.isAlive)
        return failure.get()
    }

    @Test
    fun anInterruptWhileWaitingForAPermitLeaksNothing() {
        val slots = RenderSlots(permits = 1, coarseFirstMs = 1_500, coarseGraceMs = 0)
        val hold = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val busy = thread { slots.run(coarse = true) { inside.countDown(); hold.await(5, TimeUnit.SECONDS) } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertEquals(1, slots.coarseActive.get())
        // A second coarse render waits for the only permit, and is interrupted there.
        assertTrue(interruptWhileWaiting(slots, coarse = true) is InterruptedException)
        assertEquals(1, slots.rendering.get(), "only the render that runs")
        assertEquals(1, slots.coarseActive.get())
        assertEquals(0, slots.available, "the permit is the running render's")
        hold.countDown()
        busy.join(5_000)
        assertEquals(0, slots.rendering.get())
        assertEquals(0, slots.coarseActive.get())
        assertEquals(1, slots.available, "no permit given back twice, none kept")
    }

    @Test
    fun anInterruptDuringTheCoarseHoldLeaksNothing() {
        val slots = RenderSlots(permits = 4, coarseFirstMs = 10_000, coarseGraceMs = 0)
        val hold = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val coarse = thread { slots.run(coarse = true) { inside.countDown(); hold.await(5, TimeUnit.SECONDS) } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // A render at the view's zoom holds for the coarse one (up to 10 s), and is interrupted there.
        assertTrue(interruptWhileWaiting(slots, coarse = false) is InterruptedException)
        assertEquals(1, slots.rendering.get())
        assertEquals(3, slots.available, "the interrupted render never took a permit")
        hold.countDown()
        coarse.join(5_000)
        assertEquals(0, slots.rendering.get())
        assertEquals(0, slots.coarseActive.get())
        assertEquals(4, slots.available)
        // Afterwards a view render does not wait for a coarse render that is not there.
        val t0 = System.nanoTime()
        assertEquals("rendered", slots.run(coarse = false) { "rendered" })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    @Test
    fun aRenderThatThrowsGivesEverythingBack() {
        val slots = RenderSlots(permits = 2, coarseFirstMs = 1_500, coarseGraceMs = 0)
        repeat(3) { runCatching { slots.run(coarse = it % 2 == 0) { error("a source failed") } } }
        assertEquals(0, slots.rendering.get())
        assertEquals(0, slots.coarseActive.get())
        assertEquals(2, slots.available)
        assertTrue(slots.lastDoneAt > 0)
    }

    @Test
    fun theHeapLimitHoldsRendersBackUntilItRises() {
        // T127: four permits, but the heap allows two; the other two wait, counted, until the limit rises.
        val allowed = java.util.concurrent.atomic.AtomicInteger(2)
        val slots = RenderSlots(permits = 4, coarseFirstMs = 0, coarseGraceMs = 0, admit = { running, _, _ -> running < allowed.get() })
        val hold = CountDownLatch(1)
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(4) {
            thread {
                slots.run(coarse = true) {
                    most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    hold.await(5, TimeUnit.SECONDS)
                    inside.decrementAndGet()
                }
            }
        }
        Thread.sleep(300)
        assertEquals(2, slots.runningNow, "two run, two wait for the heap")
        assertEquals(2L, slots.heapWaits.get())
        allowed.set(4)
        Thread.sleep(300) // the waiting renders ask again every 50 ms
        assertEquals(4, slots.runningNow)
        hold.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(4, most.get())
        assertEquals(0, slots.rendering.get())
        assertEquals(4, slots.available)
    }

    @Test
    fun aLimitOfNothingStillLetsOneRenderRun() {
        val slots = RenderSlots(permits = 4, coarseFirstMs = 0, coarseGraceMs = 0, admit = { _, _, _ -> false })
        assertEquals("rendered", slots.run(coarse = false) { "rendered" })
        assertEquals(4, slots.available)
    }
}
