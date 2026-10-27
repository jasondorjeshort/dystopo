package io.github.jasondorjeshort.dystopo.app

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T150: the one foreground flag the polling loops and the map's device fixes follow ([Foreground]). */
class ForegroundTest {
    @BeforeTest fun before() = Foreground.reset()
    @AfterTest fun after() = Foreground.reset()

    @Test
    fun countsStartedActivities() {
        assertFalse(Foreground.started)
        assertTrue(Foreground.activityStarted())
        // A second activity started over the first, then the first stopped: still in the foreground, no transition.
        assertFalse(Foreground.activityStarted())
        assertFalse(Foreground.activityStopped())
        assertTrue(Foreground.started)
        assertTrue(Foreground.activityStopped())
        assertFalse(Foreground.started)
        assertEquals(2L, Foreground.transitions)
        // An unmatched stop does not drive the count below zero.
        assertFalse(Foreground.activityStopped())
        assertTrue(Foreground.activityStarted())
    }

    @Test
    fun aLoopPausesInTheBackgroundAndResumesAtTheNextStart() = runBlocking {
        Foreground.activityStarted()
        val passes = AtomicInteger()
        val loop = async {
            while (passes.get() < 5) {
                passes.incrementAndGet()
                Foreground.delay(1)
            }
        }
        withTimeout(5_000) { while (passes.get() < 2) yield() }
        Foreground.activityStopped()
        // At most the pass under way finishes; then the loop waits.
        val atStop = passes.get()
        withTimeout(5_000) { while (Foreground.json()["pausedLoops"] != 1) yield() }
        Thread.sleep(50)
        yield()
        assertTrue(passes.get() <= atStop + 1, "passes ${passes.get()} after the stop at $atStop")
        assertFalse(loop.isCompleted)
        Foreground.activityStarted()
        withTimeout(5_000) { loop.await() }
        assertEquals(5, passes.get())
        assertEquals(0, Foreground.json()["pausedLoops"])
    }

    @Test
    fun aThreadBlocksUntilTheStartOrItsTimeout() {
        val t0 = System.currentTimeMillis()
        assertFalse(Foreground.block(60))
        assertTrue(System.currentTimeMillis() - t0 >= 50)
        var woke = false
        val t = Thread { woke = Foreground.block() }
        t.start()
        Thread.sleep(50)
        assertTrue(t.isAlive)
        Foreground.activityStarted()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertTrue(woke)
        assertTrue(Foreground.block(1)) // at once in the foreground
    }
}
