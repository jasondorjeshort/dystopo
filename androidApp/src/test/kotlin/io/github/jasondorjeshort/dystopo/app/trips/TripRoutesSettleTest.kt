package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.app.routing.UiPublish
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T124: a trip route's change is the editor's for the next request ([answerSettled]). [Trips] posts the open trip and the
 * offline offer to the main thread and returns; before T124 `POST /routes/{id}/offline` could answer while that post still
 * waited behind a long frame, and `?go=1` right after it found no offer.
 */
class TripRoutesSettleTest {
    /** A stand-in for the main thread: one thread, blocks run in the order posted. */
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val ui = UiPublish({ Thread.currentThread().name == "test-main" }, { main.execute(it) })

    @Volatile private var editing: String? = "trip-a"
    @Volatile private var offer: String? = null

    @AfterTest
    fun stop() {
        main.shutdownNow()
    }

    /** Keeps the main thread busy for [ms] (a long frame). */
    private fun busyMain(ms: Long) {
        val started = CountDownLatch(1)
        main.execute { started.countDown(); Thread.sleep(ms) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
    }

    /** What `Trips.open` and `Trips.planOffline` do: post the state and return. */
    private fun openAndOffer(id: String): String {
        ui.post { editing = id }
        ui.post { offer = "offer for $id" }
        return "200"
    }

    @Test
    fun aPostAnswersAfterItsStateIsTheEditors() {
        busyMain(200)
        val t0 = System.nanoTime()
        val code = answerSettled("POST", ui, 5_000) { openAndOffer("trip-b") }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("200", code)
        assertTrue(waitedMs >= 100, "waited for the busy main thread ($waitedMs ms)")
        assertEquals("trip-b", editing) // GET /routes right after names it
        assertEquals("offer for trip-b", offer) // and ?go=1 right after finds the offer
    }

    @Test
    fun withoutTheWaitTheNextRequestReadsTheOldState() {
        busyMain(200)
        openAndOffer("trip-b") // the route before T124: posted, answered
        assertEquals("trip-a", editing)
        assertEquals(null, offer)
        ui.publish(5_000) { }
        assertEquals("trip-b", editing)
    }

    @Test
    fun aGetDoesNotWaitForTheMainThread() {
        busyMain(1_000)
        val t0 = System.nanoTime()
        assertEquals("trip-a", answerSettled("GET", ui, 5_000) { editing })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
    }

    @Test
    fun anErrorAnswerWaitsToo() {
        busyMain(200)
        // A refused change (409) may still have posted a message: it is there when the answer is.
        val code = answerSettled("POST", ui, 5_000) { ui.post { offer = "refused" }; "409" }
        assertEquals("409", code)
        assertEquals("refused", offer)
    }

    @Test
    fun aStuckMainThreadDelaysTheAnswerOnlyByTheTimeout() {
        busyMain(3_000)
        val t0 = System.nanoTime()
        assertEquals("200", answerSettled("POST", ui, 100) { openAndOffer("trip-b") })
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(waitedMs in 90..2_000, "answered after about the timeout ($waitedMs ms)")
    }
}
