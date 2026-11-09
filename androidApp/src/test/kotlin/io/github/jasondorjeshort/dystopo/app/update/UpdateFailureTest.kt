package io.github.jasondorjeshort.dystopo.app.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T247: a failed update check says one sentence per kind, keeps the code and the raw text, and retries a 5xx or a timeout once. */
class UpdateFailureTest {
    @Test
    fun a5xxIsGitHubNotAnswering() {
        val f = UpdateFailure.of(UpdateFailure.HttpStatus(504, "HTTP 504 from api.github.com"))
        assertEquals(UpdateFailure.Kind.SERVER, f.kind)
        assertEquals(504, f.code)
        assertEquals("GitHub did not answer. Try again later.", f.sentence)
        assertEquals("HTTP 504 from api.github.com", f.detail)
        assertTrue(f.retryable)
        assertEquals(listOf("Kind: server, HTTP 504", "HTTP 504 from api.github.com", "Tried twice, 60 s apart"), f.technical(2))
        assertEquals(UpdateFailure.Kind.SERVER, UpdateFailure.of(UpdateFailure.HttpStatus(500, "HTTP 500 from x")).kind)
    }

    @Test
    fun aTimeoutSaysTheSameAndRetries() {
        val f = UpdateFailure.of(SocketTimeoutException("Read timed out"))
        assertEquals(UpdateFailure.Kind.TIMEOUT, f.kind)
        assertNull(f.code)
        assertEquals("GitHub did not answer. Try again later.", f.sentence)
        assertEquals("Read timed out (SocketTimeoutException)", f.detail)
        assertTrue(f.retryable)
        assertEquals(listOf("Kind: timeout", "Read timed out (SocketTimeoutException)"), f.technical(1))
    }

    @Test
    fun aNetworkErrorIsNoConnection() {
        for (e in listOf(UnknownHostException("Unable to resolve host \"api.github.com\""), ConnectException("failed to connect to /10.0.2.2 (port 8691)"))) {
            val f = UpdateFailure.of(e)
            assertEquals(UpdateFailure.Kind.NETWORK, f.kind, e.toString())
            assertEquals("No connection.", f.sentence)
            assertFalse(f.retryable)
            assertTrue(f.detail.startsWith(e.message!!))
        }
    }

    @Test
    fun anUnreadableAnswerIsAParseError() {
        val e = runCatching { UpdateFeed.parseReleases("<html>not a list</html>") }.exceptionOrNull()!!
        val f = UpdateFailure.of(e)
        assertEquals(UpdateFailure.Kind.PARSE, f.kind)
        assertEquals("Could not read the release list.", f.sentence)
        assertFalse(f.retryable)
        val g = UpdateFailure.of(runCatching { UpdateFeed.parseReleases("{\"message\": \"Not Found\"}") }.exceptionOrNull()!!)
        assertEquals(UpdateFailure.Kind.PARSE, g.kind)
    }

    @Test
    fun a4xxAndTheRestAreOther() {
        val f = UpdateFailure.of(UpdateFailure.HttpStatus(403, "HTTP 403 from api.github.com (rate limited; try later)"))
        assertEquals(UpdateFailure.Kind.OTHER, f.kind)
        assertEquals(403, f.code)
        assertFalse(f.retryable)
        assertEquals("The update check did not work. Try again later.", f.sentence)
        assertEquals(UpdateFailure.Kind.OTHER, UpdateFailure.of(IOException("too many redirects from x")).kind)
        assertEquals("IOException", UpdateFailure.of(IOException()).detail)
    }

    @Test
    fun oneQuietRetryForA5xxOrATimeout() {
        val server = UpdateFailure.of(UpdateFailure.HttpStatus(504, "HTTP 504"))
        val slept = mutableListOf<Long>()
        val tries = mutableListOf<Int>()
        // Both fail: two attempts a minute apart, the second's failure is the result.
        val r = UpdateFailure.withOneRetry(60_000, { slept += it }) { tries += it; server }
        assertEquals(listOf(1, 2), tries)
        assertEquals(listOf(60_000L), slept)
        assertEquals(server, r.failure)
        assertEquals(2, r.attempts)
        // The second succeeds: no failure shown.
        val ok = UpdateFailure.withOneRetry(60_000, { slept += it }) { if (it == 1) UpdateFailure.of(SocketTimeoutException("t")) else null }
        assertNull(ok.failure)
        assertEquals(2, ok.attempts)
    }

    @Test
    fun noRetryForANetworkOrParseErrorOrASuccess() {
        for (f in listOf(UpdateFailure.of(UnknownHostException("h")), UpdateFailure.of(IllegalArgumentException("JSON: x")), null)) {
            var calls = 0
            val r = UpdateFailure.withOneRetry(60_000, { error("no sleep for $f") }) { calls++; f }
            assertEquals(1, calls)
            assertEquals(1, r.attempts)
            assertEquals(f, r.failure)
        }
    }

    @Test
    fun theDelayIsAMinute() {
        assertEquals(60_000L, UpdateFailure.RETRY_DELAY_MS)
    }
}
