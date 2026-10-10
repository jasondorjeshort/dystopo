package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServeStaticTest {
    @Test
    fun parsesSingleByteRanges() {
        assertEquals(100L..999L, ServeStatic.parseRange("bytes=100-", 1000))
        assertEquals(0L..9L, ServeStatic.parseRange("bytes=0-9", 1000))
        assertEquals(990L..999L, ServeStatic.parseRange("bytes=-10", 1000))
        assertEquals(0L..999L, ServeStatic.parseRange("bytes=-5000", 1000))
        assertEquals(500L..999L, ServeStatic.parseRange("bytes=500-5000", 1000))
        assertNull(ServeStatic.parseRange("bytes=1000-", 1000))
        assertNull(ServeStatic.parseRange("bytes=9-0", 1000))
        assertNull(ServeStatic.parseRange("bytes=-", 1000))
        assertNull(ServeStatic.parseRange("bytes=0-1,5-6", 1000))
    }

    @Test
    fun servesRangesOverHttpAndStaysInsideTheRoot() {
        val root = createTempDirectory("serve-static").toFile()
        val data = ByteArray(5000) { (it * 7).toByte() }
        File(root, "x-dem.pmtiles").writeBytes(data)
        File(root.parentFile, "outside-${root.name}.txt").apply { writeText("secret"); deleteOnExit() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
        server.createContext("/") { ex -> ServeStatic.serve(ex, root, ex.requestURI.path.removePrefix("/out/")) }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/out/"
            fun get(path: String, range: String? = null): Triple<Int, ByteArray, HttpURLConnection> {
                val c = URI(base + path).toURL().openConnection() as HttpURLConnection
                range?.let { c.setRequestProperty("Range", it) }
                val code = c.responseCode
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
                return Triple(code, body, c)
            }
            val (full, fullBody, _) = get("x-dem.pmtiles")
            assertEquals(200, full)
            assertContentEquals(data, fullBody)
            val (part, partBody, pc) = get("x-dem.pmtiles", "bytes=1200-")
            assertEquals(206, part)
            assertEquals("bytes 1200-4999/5000", pc.getHeaderField("Content-Range"))
            assertContentEquals(data.copyOfRange(1200, 5000), partBody)
            val (past, _, pastConn) = get("x-dem.pmtiles", "bytes=5000-")
            assertEquals(416, past)
            assertEquals("bytes */5000", pastConn.getHeaderField("Content-Range"))
            assertEquals(404, get("../outside-${root.name}.txt").first)
            assertEquals(404, get("missing.pmtiles").first)
            // Throttled: 5000 bytes at 20 KB/s take about 0.25 s.
            val t0 = System.nanoTime()
            val (slow, slowBody, _) = get("x-dem.pmtiles?kbps=20")
            assertEquals(200, slow)
            assertContentEquals(data, slowBody)
            val ms = (System.nanoTime() - t0) / 1e6
            kotlin.test.assertTrue(ms > 150, "throttled download took $ms ms")
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
