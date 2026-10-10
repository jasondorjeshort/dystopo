package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone-to-phone link on the JVM (T54): [ShareGate] in front of a [SyncServer], served over a real
 * socket by a stand-in for the app's LocalServer, and [ShareClient] on the other side. Two in-memory
 * stores sync both ways; a wrong code moves nothing; unknown routes and missing codes are refused.
 */
class ShareLinkTest {
    private val servers = ArrayList<ServerSocket>()

    @AfterTest
    fun close() = servers.forEach { it.close() }

    /** A one-request-per-connection HTTP server on 127.0.0.1 (what LocalServer does on the phone). */
    private fun serve(handler: (Request) -> Response): Int {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                c.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    val (method, target) = line(input)!!.split(' ').let { it[0] to it[1] }
                    var length = 0
                    while (true) {
                        val h = line(input) ?: break
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length)
                    var n = 0
                    while (n < length) n += input.read(body, n, length - n)
                    val path = target.substringBefore('?')
                    val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val r = handler(Request(method, path, query, body))
                    s.getOutputStream().write("HTTP/1.1 ${r.status} X\r\nContent-Type: ${r.contentType}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n\r\n".toByteArray() + r.body)
                }
            }
        }
        return ss.localPort
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun sharing(store: InMemoryTrackStore, code: String): Pair<ShareGate, Int> {
        val sync = SyncServer(store, { 2_000L }, ShareClient.gzip, ShareClient.gunzip)
        val gate = ShareGate(hello = { mapOf("device" to store.deviceId, "name" to "A") }, sync = { r ->
            sync.handle(r.method, r.path, r.query, r.body)?.let { Response(it.status, it.contentType, it.body) }
        }, newCode = { code })
        return gate to serve(gate::handle)
    }

    private fun track(store: InMemoryTrackStore, id: String, name: String, lat: Double) {
        store.create(id, name, 1_000L)
        for (i in 0 until 5) store.append(id, TrackPoint(lat + i * 0.001, -121.76, 1600.0 + i, 1_000L + i * 1000))
    }

    @Test
    fun syncsBothWaysWithTheCode() {
        val a = InMemoryTrackStore("aaaaaaaa-0000-4000-8000-000000000001")
        val b = InMemoryTrackStore("bbbbbbbb-0000-4000-8000-000000000002")
        track(a, "11111111-0000-4000-8000-00000000000a", "From A", 46.85)
        track(b, "22222222-0000-4000-8000-00000000000b", "From B", 46.80)
        val (gate, port) = sharing(a, "123456")

        val (peer, report) = ShareClient.exchange(b, ShareClient.parse("127.0.0.1:$port"), "123456", { 3_000L })
        assertEquals("A", peer["name"])
        assertEquals(1 + 5, report.merged.applied) // A's put and its points (one append per point)
        assertTrue(report.pushed > 0)
        assertEquals(a.snapshot().digest(), b.snapshot().digest())
        assertEquals(2, a.list().size)
        assertEquals(a.track("22222222-0000-4000-8000-00000000000b"), b.track("22222222-0000-4000-8000-00000000000b"))
        assertEquals(0L, gate.refused)
    }

    @Test
    fun wrongCodeMovesNothing() {
        val a = InMemoryTrackStore("aaaaaaaa-0000-4000-8000-000000000001")
        val b = InMemoryTrackStore("bbbbbbbb-0000-4000-8000-000000000002")
        track(a, "11111111-0000-4000-8000-00000000000a", "From A", 46.85)
        track(b, "22222222-0000-4000-8000-00000000000b", "From B", 46.80)
        val (gate, port) = sharing(a, "123456")
        assertFailsWith<ShareClient.WrongCodeException> { ShareClient.exchange(b, ShareClient.parse("127.0.0.1:$port"), "654321", { 3_000L }) }
        assertEquals(1, a.list().size)
        assertEquals(1, b.list().size)
        assertEquals(1L, gate.refused)
        // Straight to the sync routes without the code: 403, and an unknown route is 404 even with it.
        assertEquals(403, LanHttp.request("POST", "http://127.0.0.1:$port/sync/pull?since=").status)
        assertEquals(403, LanHttp.request("GET", "http://127.0.0.1:$port/sync/status?code=000000").status)
        assertEquals(404, LanHttp.request("GET", "http://127.0.0.1:$port/health?code=123456").status)
        assertEquals(404, LanHttp.request("GET", "http://127.0.0.1:$port/tracks?code=123456").status)
        assertEquals(200, LanHttp.request("GET", "http://127.0.0.1:$port/sync/status?code=123456").status)
    }

    @Test
    fun codeRotatesAfterRepeatedFailures() {
        var n = 0
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, maxFailures = 3, newCode = { (111111 * ++n).toString() })
        assertEquals("111111", gate.code)
        repeat(3) { assertEquals(403, gate.handle(Request("GET", "/share/hello", mapOf("code" to "999999"), ByteArray(0))).status) }
        assertEquals("222222", gate.code)
        assertEquals(1, gate.rotations)
        assertEquals(403, gate.handle(Request("GET", "/share/hello", mapOf("code" to "111111"), ByteArray(0))).status)
        assertEquals(200, gate.handle(Request("GET", "/share/hello", mapOf("code" to "222222"), ByteArray(0))).status)
        assertTrue(ShareGate.randomCode().matches(Regex("\\d{6}")))
        assertNotEquals(true, ShareGate.constantTimeEquals("12345", "123456"))
    }

    @Test
    fun parsesAddressesAndShareLinks() {
        assertEquals(ShareClient.Target("http://192.168.1.23:8841", null), ShareClient.parse("192.168.1.23"))
        assertEquals(ShareClient.Target("http://192.168.1.23:9000", null), ShareClient.parse("http://192.168.1.23:9000/"))
        assertEquals(ShareClient.Target("http://10.0.2.2:8841", "042042"), ShareClient.parse(ShareClient.shareUri("10.0.2.2:8841", "042042")))
        assertFailsWith<IllegalArgumentException> { ShareClient.parse("http://x/evil?y") }
        assertFailsWith<IllegalArgumentException> { ShareClient.parse(" ") }
        assertEquals("http://h/sync/pull?since=a,b&code=000001", ShareClient.withCode("http://h/sync/pull?since=a,b", "000001"))
        assertEquals("http://h/sync/push?code=000001", ShareClient.withCode("http://h/sync/push", "000001"))
        // The QR holds the link: version 4 at most for a LAN address and port.
        assertTrue(QrCode.encode(ShareClient.shareUri("192.168.100.200:65535", "999999")).version <= 4)
    }

    @Test
    fun peerListTracksDiscovery() {
        var t = 0L
        val peers = PeerList { ++t }
        peers.found("Dystopo Pixel 7 ab12")
        assertTrue(peers.peers().isEmpty(), "unresolved peers are not offered")
        peers.resolved("Dystopo Pixel 7 ab12", "192.168.1.30", 8841)
        peers.resolved("Dystopo sdk cd34", "192.168.1.31", 40000)
        peers.selfName = "Dystopo sdk cd34"
        assertEquals(listOf("192.168.1.30:8841"), peers.peers().map { it.hostPort })
        assertEquals(2, peers.all().size)
        peers.resolved("Dystopo Pixel 7 ab12", "192.168.1.32", 8841) // roamed
        assertEquals(listOf("192.168.1.32:8841"), peers.peers().map { it.hostPort })
        peers.resolved("v6", "fe80::1", 8841)
        assertEquals("[fe80::1]:8841", peers.peers().first().hostPort)
        peers.lost("Dystopo Pixel 7 ab12")
        peers.lost("v6")
        assertTrue(peers.peers().isEmpty())
        peers.clear()
        assertNull(peers.all().firstOrNull())
    }
}
