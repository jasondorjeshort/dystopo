package io.github.jasondorjeshort.dystopo.sources.elevation

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T88: [parallelForEach] tasks queue by the priority of the thread that fanned them out ([SourceIo.withPriority]),
 * higher first, then in order. The phone puts a view's coarse fallback tiles first so their block reads do not
 * wait behind the view's own.
 */
class SourcePriorityTest {
    @Test
    fun queuedTasksRunHigherPriorityFirst() {
        val busy = 16 // every source-io thread
        val releaseOne = CountDownLatch(1) // then one thread works through the queue, in its order
        val releaseRest = CountDownLatch(1)
        val started = CountDownLatch(busy)
        val blockers = Thread {
            parallelForEach((1..busy).toList()) { i ->
                started.countDown()
                (if (i == 1) releaseOne else releaseRest).await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the pool's threads never all started")

        // With every thread busy, a low then a high priority fan-out queue up; the high one must run first.
        val order = Collections.synchronizedList(ArrayList<String>())
        fun fanOut(name: String, p: Int) = Thread { SourceIo.withPriority(p) { parallelForEach(listOf(1, 2)) { order += "$name$it" } } }.apply { start() }
        val low = fanOut("low", -1)
        Thread.sleep(200)
        val normal = fanOut("normal", 0)
        Thread.sleep(200)
        val high = fanOut("high", 1)
        Thread.sleep(200)
        releaseOne.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (order.size < 6 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        releaseRest.countDown()
        listOf(blockers, low, normal, high).forEach { it.join(10_000) }

        assertEquals(listOf("high", "high", "normal", "normal", "low", "low"), order.map { it.dropLast(1) }, "order: $order")
    }

    @Test
    fun priorityIsRestoredAfterTheBlock() {
        assertEquals(0, SourceIo.currentPriority())
        SourceIo.withPriority(2) {
            assertEquals(2, SourceIo.currentPriority())
            SourceIo.withPriority(-1) { assertEquals(-1, SourceIo.currentPriority()) }
            assertEquals(2, SourceIo.currentPriority())
        }
        assertEquals(0, SourceIo.currentPriority())
    }
}
