package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.sources.elevation.SourceIo
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T92: the byte budget on the elevation readers' own downloads, per request and per hour. */
class SourceBudgetTest {
    private val body = ByteArray(300_000) { (it % 251).toByte() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/f") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val bytes = if (range != null) {
                val (a, b) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                body.copyOfRange(a, minOf(b + 1, body.size)).also { ex.sendResponseHeaders(206, it.size.toLong()) }
            } else body.also { ex.sendResponseHeaders(200, it.size.toLong()) }
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/f"
    private val before = SourceIo.http

    @AfterTest
    fun cleanup() {
        server.stop(0)
        SourceIo.http = before
        SourceBudget.current = null
    }

    @Test
    fun perRequestCap() {
        val b = SourceBudget(maxRequestBytes = 100_000, maxHourBytes = 0)
        val c = b.Client()
        assertEquals(50_000, c.range(url, 1000, 50_000).body.size)
        // A range asking for more than the cap is refused before it is sent.
        assertFailsWith<SourceBudget.SourceBudgetException> { c.range(url, 0, 100_001) }
        // A whole file larger than the cap is cut off while it streams.
        val e = assertFailsWith<SourceBudget.SourceBudgetException> { c.get(url, 10_000) }
        assertTrue("per-request cap" in e.message!!, e.message)
        assertEquals(2, b.refusedRequestCap.get())
        assertEquals(0, b.refusedHourCap.get())
        // Uncapped, the same file comes whole and byte-exact.
        assertTrue(SourceBudget(0, 0).Client().get(url, 10_000).body.contentEquals(body))
    }

    @Test
    fun perHourCapSlidesWithTheClock() {
        var now = 1_000_000_000L
        val b = SourceBudget(maxRequestBytes = 0, maxHourBytes = 250_000, clock = { now })
        val c = b.Client()
        repeat(2) { c.range(url, 0, 100_000) } // 200 kB
        now += 10 * 60_000
        c.range(url, 0, 100_000) // 300 kB: over the budget once it lands
        assertEquals(300_000, b.lastHourBytes())
        assertFailsWith<SourceBudget.SourceBudgetException> { c.range(url, 0, 10) }
        assertFailsWith<SourceBudget.SourceBudgetException> { c.get(url, 10_000) }
        assertEquals(2, b.refusedHourCap.get())
        // 55 minutes later the first two requests have left the window: 100 kB counted, requests go through.
        now += 55 * 60_000
        assertEquals(100_000, b.lastHourBytes())
        assertEquals(10, c.range(url, 0, 10).body.size)
        val j = b.json()
        assertEquals(4L, j["requests"])
        assertEquals(300_010L, j["bytes"])
        assertEquals(mapOf("requestCap" to 0L, "hourCap" to 2L), j["refused"])
    }

    @Test
    fun installedUnderTheReadersAndReportedInStats() {
        val b = SourceBudget.install(SourceBudget(maxRequestBytes = 1000, maxHourBytes = 0))
        assertEquals(1000, SourceIo.range(url, 0, 1000).size)
        assertFailsWith<SourceBudget.SourceBudgetException> { SourceIo.download(url) } // not retried: one refusal
        assertEquals(1, b.refusedRequestCap.get())
        assertEquals(b, SourceBudget.current)
    }

    @Test
    fun capsFromTheEnvironment() {
        assertEquals(256L shl 20, SourceBudget.fromEnv(null).maxRequestBytes)
        assertEquals(8192L shl 20, SourceBudget.fromEnv("").maxHourBytes)
        val b = SourceBudget.fromEnv("request=16, hour=0")
        assertEquals(16L shl 20, b.maxRequestBytes)
        assertEquals(0L, b.maxHourBytes)
        assertFailsWith<IllegalArgumentException> { SourceBudget.fromEnv("daily=5") }
        assertFailsWith<IllegalArgumentException> { SourceBudget.fromEnv("hour=-1") }
    }
}
