package io.github.jasondorjeshort.dystopo.app.routing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T119: a plan's result reaches the session's state before the planning call returns ([UiPublish.publish]), even while
 * the main thread is busy; a plain post (what `RouteSession.plan` did before) leaves a reader right after it with no
 * route, the post-wave 16 sweep's 404 from `GET /nav/directions?route=current` after `POST /route`.
 */
class UiPublishTest {
    /** A stand-in for the main thread: one thread, blocks run in the order posted. */
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val ui = UiPublish({ Thread.currentThread().name == "test-main" }, { main.execute(it) })

    @Volatile private var phase = "route"
    @Volatile private var plan: String? = "the previous route"

    @AfterTest
    fun stop() {
        main.shutdownNow()
    }

    /** Keeps the main thread busy until the returned latch is counted down. */
    private fun busyMain(): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        main.execute { started.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return release
    }

    @Test
    fun aPostedRouteIsNotThereYetWhileTheMainThreadIsBusy() {
        val release = busyMain()
        ui.post { phase = "planning"; plan = null }
        ui.post { phase = "route"; plan = "Paradise to Panorama Point" } // the result, as before T119
        assertEquals("the previous route", plan) // a request answered now reads the old state (or none once "planning" ran)
        release.countDown()
        ui.publish(5_000) { }
        assertEquals("Paradise to Panorama Point", plan)
    }

    @Test
    fun aPublishedRouteIsThereWhenThePlanReturns() {
        val release = busyMain()
        val timer = Executors.newSingleThreadScheduledExecutor()
        timer.schedule({ release.countDown() }, 200, TimeUnit.MILLISECONDS)
        ui.post { phase = "planning"; plan = null }
        val t0 = System.nanoTime()
        val ran = ui.publish(5_000) { phase = "route"; plan = "Paradise to Panorama Point" }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ran)
        assertTrue(waitedMs >= 100, "waited for the busy main thread ($waitedMs ms)")
        assertEquals("route", phase)
        assertEquals("Paradise to Panorama Point", plan)
        timer.shutdown()
    }

    @Test
    fun onTheMainThreadBothRunAtOnce() {
        val seen = main.submit<List<String?>> {
            ui.post { plan = null }
            val a = plan
            ui.publish(5_000) { plan = "Paradise to Panorama Point" }
            listOf(a, plan)
        }.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(null, "Paradise to Panorama Point"), seen)
    }

    @Test
    fun aStuckMainThreadDoesNotHoldThePlannerForever() {
        val release = busyMain()
        val t0 = System.nanoTime()
        assertFalse(ui.publish(100) { plan = "Paradise to Panorama Point" })
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(waitedMs in 90..2_000, "gave up after about the timeout ($waitedMs ms)")
        assertEquals("the previous route", plan)
        release.countDown()
        ui.publish(5_000) { }
        assertEquals("Paradise to Panorama Point", plan) // it still runs, in order, once the main thread is free
    }
}
