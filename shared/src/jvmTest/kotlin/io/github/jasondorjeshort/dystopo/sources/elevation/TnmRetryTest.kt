package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TnmRetryTest {
    private val url = "https://tnmaccess.example.invalid/api/v1/products"

    /** Fails with each of [failures] in turn, then returns "ok". */
    private class Flaky(private val failures: List<Exception>) {
        var calls = 0
        fun call(): String {
            calls++
            return if (calls <= failures.size) throw failures[calls - 1] else "ok"
        }
    }

    @Test
    fun transientFailuresAreRetriedUntilSuccess() {
        val flaky = Flaky(
            listOf(
                HttpStatusException(503, url),
                java.net.http.HttpTimeoutException("timed out"),
                IllegalArgumentException("JSON: unexpected character at 0"),
                HttpStatusException(429, url),
            ),
        )
        assertEquals("ok", LidarSource.retryTnm(initialDelayMs = 1) { flaky.call() })
        assertEquals(5, flaky.calls)
    }

    @Test
    fun aClientErrorIsNotRetried() {
        val flaky = Flaky(listOf(HttpStatusException(400, url)))
        val e = assertFailsWith<HttpStatusException> { LidarSource.retryTnm(initialDelayMs = 1) { flaky.call() } }
        assertEquals(400, e.status)
        assertEquals(1, flaky.calls)
    }

    @Test
    fun givesUpAfterTheLastAttempt() {
        val flaky = Flaky(List(10) { HttpStatusException(502, url) })
        assertFailsWith<HttpStatusException> { LidarSource.retryTnm(initialDelayMs = 1) { flaky.call() } }
        assertEquals(LidarSource.TNM_ATTEMPTS, flaky.calls)
    }

    @Test
    fun backoffDoubles() {
        val flaky = Flaky(List(3) { java.io.IOException("connection reset") })
        val started = System.nanoTime()
        SourceIo.retry(4, 20, { it is java.io.IOException }) { flaky.call() }
        val ms = (System.nanoTime() - started) / 1_000_000
        // 20 + 40 + 80 ms of sleeping.
        assertTrue(ms >= 140, "slept only $ms ms")
        assertEquals(4, flaky.calls)
    }
}
