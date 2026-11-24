package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.sources.elevation.HttpReply
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T355: the public vector source's terms: four requests at once for downloads, views apart, `Retry-After` obeyed, the app named. */
class OfmLimiterTest {
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    @Test
    fun theFifthDownloadRequestWaitsAndAViewDoesNot() {
        val limiter = OfmLimiter()
        assertEquals(4, limiter.download.limit)
        val release = CountDownLatch(1)
        val started = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..5).map {
            Thread { limiter.run(download = true) { started.incrementAndGet(); release.await(5, TimeUnit.SECONDS) } }.apply { start() }
        }
        waitFor("four downloads in flight and one waiting") { limiter.download.inFlight.get() == 4 && limiter.download.waiting.get() == 1 }
        Thread.sleep(100)
        assertEquals(4, started.get(), "the fifth download request waits for a permit")
        // A view request while four downloads hold every download permit: it runs at once.
        val t0 = System.nanoTime()
        val viewRan = limiter.run(download = false) { true }
        assertTrue(viewRan)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000, "a view never waits behind the downloads")
        assertEquals(0, limiter.view.waiting.get())
        release.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(5, started.get())
        assertEquals(4, limiter.download.maxInFlight.get(), "never more than four download requests at once")
        assertEquals(5L, limiter.download.requests.get())
        assertEquals(1L, limiter.view.requests.get())
    }

    @Test
    fun manyDownloadThreadsNeverExceedFour() {
        val limiter = OfmLimiter()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        val jobs = (1..60).map { pool.submit { limiter.run(download = true) { Thread.sleep(3) } } }
        jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(4, limiter.download.maxInFlight.get())
        assertEquals(60L, limiter.download.requests.get())
    }

    /** A fake clock and sleep: [sleep] advances [now]. */
    private class Clock(var now: Long = 1_000_000L) {
        val slept = ArrayList<Long>()
        fun sleep(ms: Long) { slept += ms; now += ms }
    }

    @Test
    fun aDownloadIsNotRetriedBeforeRetryAfter() {
        val c = Clock()
        val limiter = OfmLimiter(clock = { c.now }, sleep = c::sleep)
        val sentAt = ArrayList<Long>()
        val reply = limiter.call(download = true) {
            sentAt += c.now
            if (sentAt.size == 1) HttpReply(429, ByteArray(0), mapOf(OfmLimiter.RETRY_AFTER to "7")) else HttpReply(200, byteArrayOf(1))
        }
        assertEquals(200, reply.status)
        assertEquals(2, sentAt.size)
        assertTrue(sentAt[1] - sentAt[0] >= 7_000, "asked again ${sentAt[1] - sentAt[0]} ms later, before Retry-After's 7 s")
        assertEquals(1L, limiter.holds.get())
        // Another download asking during the hold waits it out too, without a request before it.
        limiter.hold("3")
        val holdEnds = limiter.holdUntil
        var askedAt = 0L
        limiter.call(download = true) { askedAt = c.now; HttpReply(200, ByteArray(0)) }
        assertTrue(askedAt >= holdEnds)
    }

    @Test
    fun aViewDuringAHoldIsAnsweredAtOnceWithoutARequest() {
        val c = Clock()
        val limiter = OfmLimiter(clock = { c.now }, sleep = c::sleep)
        var sends = 0
        val first = limiter.call(download = false) { sends++; HttpReply(429, ByteArray(0), mapOf(OfmLimiter.RETRY_AFTER to "60")) }
        assertEquals(429, first.status)
        assertEquals(1, sends, "a view is not asked again: its stall guard would not outlast the hold")
        c.now += 59_000
        val held = limiter.call(download = false) { sends++; HttpReply(200, ByteArray(0)) }
        assertEquals(429, held.status)
        assertEquals("1", held.headers[OfmLimiter.HELD])
        assertEquals(1, sends, "no request before Retry-After")
        assertEquals(0, c.slept.size, "a view never sleeps")
        c.now += 1_000
        assertEquals(200, limiter.call(download = false) { sends++; HttpReply(200, ByteArray(0)) }.status)
        assertEquals(2, sends)
    }

    @Test
    fun aLongHoldFailsADownloadTileAtOnce() {
        val c = Clock()
        val limiter = OfmLimiter(clock = { c.now }, sleep = c::sleep)
        limiter.hold("600")
        var sends = 0
        assertEquals(429, limiter.call(download = true) { sends++; HttpReply(200, ByteArray(0)) }.status)
        assertEquals(0, sends)
        assertEquals(0, c.slept.size)
    }

    @Test
    fun retryAfterReadsSecondsAndDates() {
        val now = 1_700_000_000_000L
        assertEquals(120_000L, OfmLimiter.retryAfterMs("120", now))
        assertEquals(OfmLimiter.DEFAULT_RETRY_AFTER_MS, OfmLimiter.retryAfterMs(null, now))
        assertEquals(OfmLimiter.DEFAULT_RETRY_AFTER_MS, OfmLimiter.retryAfterMs("soon", now))
        assertEquals(OfmLimiter.MAX_RETRY_AFTER_MS, OfmLimiter.retryAfterMs("999999", now))
        // 2023-11-14T22:13:20Z is `now`; 90 s after it as an HTTP date.
        assertEquals(90_000L, OfmLimiter.retryAfterMs("Tue, 14 Nov 2023 22:14:50 GMT", now))
        assertEquals(0L, OfmLimiter.retryAfterMs("Tue, 14 Nov 2023 22:00:00 GMT", now))
    }

    @Test
    fun aDownloadIsMarkedOnItsThreadOnly() {
        assertEquals(false, DirectSources.isDownload)
        val inside = DirectSources.download { DirectSources.isDownload to DirectSources.download { DirectSources.isDownload } }
        assertEquals(true to true, inside)
        assertEquals(false, DirectSources.isDownload)
        var other: Boolean? = null
        DirectSources.download { Thread { other = DirectSources.isDownload }.apply { start(); join() } }
        assertEquals(false, other, "another thread (a view's) is not a download")
    }

    @Test
    fun theUserAgentNamesTheAppAndItsVersion() {
        assertEquals("dystopo-android/0.1.42-alpha (+https://github.com/jasondorjeshort/dystopo)", DirectSources.userAgent("0.1.42-alpha"))
        assertTrue("unknown" in DirectSources.userAgent(""))
    }
}
