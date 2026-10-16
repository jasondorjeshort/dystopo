package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone-to-phone link on the JVM (T54): [ShareGate] in front of a [SyncServer], served over a real
 * socket by a stand-in for the app's LocalServer, and [ShareClient] on the other side. Two in-memory
 * stores sync both ways; a wrong code moves nothing; unknown routes are refused. T72: the link is
 * encrypted (nothing readable on the wire), plain clients get 426, replays 409.
 */
class ShareLinkTest {
    private val servers = ArrayList<ServerSocket>()

    /** T72: every request and reply body that crossed the stand-in server's socket. */
    private val wire = java.util.Collections.synchronizedList(ArrayList<ByteArray>())

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
                    wire += body
                    val r = handler(Request(method, path, query, body))
                    wire += r.body
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

    private val code = "7K3MQW2D9XHPT4CA"

    @Test
    fun syncsBothWaysWithTheCode() {
        val a = InMemoryTrackStore("aaaaaaaa-0000-4000-8000-000000000001")
        val b = InMemoryTrackStore("bbbbbbbb-0000-4000-8000-000000000002")
        track(a, "11111111-0000-4000-8000-00000000000a", "From A", 46.85)
        track(b, "22222222-0000-4000-8000-00000000000b", "From B", 46.80)
        val (gate, port) = sharing(a, code)

        // Typed the way people type it: lowercase, grouped.
        val (peer, report) = ShareClient.exchange(b, ShareClient.parse("127.0.0.1:$port"), "7k3m-qw2d-9xhp-t4ca", { 3_000L })
        assertEquals("A", peer["name"])
        assertEquals(1 + 1, report.merged.applied) // A's put and its 5 points as one run (T51 merges unshipped fixes into one append)
        assertTrue(report.pushed > 0)
        assertEquals(a.snapshot().digest(), b.snapshot().digest())
        assertEquals(2, a.list().size)
        assertEquals(a.track("22222222-0000-4000-8000-00000000000b"), b.track("22222222-0000-4000-8000-00000000000b"))
        assertEquals(0L, gate.refused)
        assertTrue(gate.served >= 3) // hello, pull, push

        // T72: what crossed the wire was encrypted: no track name, no bundle header, no code, in either direction.
        assertTrue(wire.isNotEmpty())
        for (bytes in wire) {
            val text = String(bytes, Charsets.ISO_8859_1)
            for (secret in listOf("From A", "From B", "DSYNC", code, "/sync/pull", "/share/hello")) assertFalse(secret in text, "$secret on the wire")
        }
    }

    @Test
    fun wrongCodeMovesNothingAndPlainClientsAreRefused() {
        val a = InMemoryTrackStore("aaaaaaaa-0000-4000-8000-000000000001")
        val b = InMemoryTrackStore("bbbbbbbb-0000-4000-8000-000000000002")
        track(a, "11111111-0000-4000-8000-00000000000a", "From A", 46.85)
        track(b, "22222222-0000-4000-8000-00000000000b", "From B", 46.80)
        val (gate, port) = sharing(a, code)
        assertFailsWith<ShareClient.WrongCodeException> { ShareClient.exchange(b, ShareClient.parse("127.0.0.1:$port"), "7K3MQW2D9XHPT4CB", { 3_000L }) }
        assertEquals(1, a.list().size)
        assertEquals(1, b.list().size)
        assertEquals(1L, gate.refused)
        // T72: an unencrypted client (a pre-T72 build, curl) is refused even with the right code: 426.
        assertEquals(426, LanHttp.request("POST", "http://127.0.0.1:$port/sync/pull?since=").status)
        assertEquals(426, LanHttp.request("GET", "http://127.0.0.1:$port/sync/status?code=$code").status)
        assertEquals(426, LanHttp.request("GET", "http://127.0.0.1:$port/share/hello?code=$code").status)
        assertEquals(426, LanHttp.request("POST", "http://127.0.0.1:$port/sync/push?code=$code", Sync.pack(b.bundle(VersionVector.EMPTY), ShareClient.gzip)).status)
        assertEquals(1, a.list().size)
        assertEquals(4L, gate.plainRefused)
        // Not a frame, a GET, an unknown route: 400, 405, 404; none of them count as a wrong code.
        assertEquals(400, LanHttp.request("POST", "http://127.0.0.1:$port/share/sealed", "plain text".toByteArray()).status)
        assertEquals(405, LanHttp.request("GET", "http://127.0.0.1:$port/share/sealed").status)
        assertEquals(404, LanHttp.request("GET", "http://127.0.0.1:$port/health?code=$code").status)
        assertEquals(1L, gate.refused)
        // A recorded request replayed is refused.
        val frame = SealedLink.sealRequest(code, SealedLink.Request("GET", "/sync/status", ByteArray(0)), ShareGate::randomBytes)
        assertEquals(200, LanHttp.request("POST", "http://127.0.0.1:$port/share/sealed", frame).status)
        assertEquals(409, LanHttp.request("POST", "http://127.0.0.1:$port/share/sealed", frame).status)
        assertEquals(1L, gate.replays)
        // A sealed request for a route the link does not have is a sealed 404.
        val inner = ShareClient.sealed(ShareClient.parse("127.0.0.1:$port"), code, "GET", "/tracks")
        assertEquals(404, inner.status)
    }

    @Test
    fun repeatedFailuresLockOutTheAddressAndFromManyAddressesReplaceTheCode() {
        val codes = listOf("1111111111111111", "2222222222222222", "3333333333333333")
        var n = 0
        val gate = ShareGate(hello = { emptyMap() }, sync = { null }, maxFailures = 3, newCode = { codes[n++] }, globalFailures = 5)
        fun hello(c: String, peer: String) = gate.handle(
            Request("POST", "/share/sealed", emptyMap(), SealedLink.sealRequest(c, SealedLink.Request("GET", "/share/hello", ByteArray(0)), ShareGate::randomBytes), loopback = false, peer = peer),
        ).status
        assertEquals(codes[0], gate.code)
        // T110: three wrong codes from one address lock that address out, even with the right code; the code stays.
        repeat(3) { assertEquals(403, hello("9999999999999999", "10.0.0.5")) }
        assertEquals(codes[0], gate.code)
        assertEquals(429, hello(codes[0], "10.0.0.5"))
        assertEquals(200, hello(codes[0], "10.0.0.6"))
        // Five in all from any addresses (globalFailures here) replace the code, which lifts the lockout.
        repeat(2) { assertEquals(403, hello("9999999999999999", "10.0.0.7")) }
        assertEquals(codes[1], gate.code)
        assertEquals(1, gate.rotations)
        assertEquals(403, hello(codes[0], "10.0.0.6"))
        assertEquals(200, hello(codes[1], "10.0.0.5"))
        assertTrue(ShareCode.normalize(ShareGate.randomCode()) != null)
        assertNotEquals(ShareGate.randomCode(), ShareGate.randomCode())
    }

    @Test
    fun parsesAddressesAndShareLinks() {
        assertEquals(ShareClient.Target("http://192.168.1.23:8841", null), ShareClient.parse("192.168.1.23"))
        assertEquals(ShareClient.Target("http://192.168.1.23:9000", null), ShareClient.parse("http://192.168.1.23:9000/"))
        assertEquals(ShareClient.Target("http://10.0.2.2:8841", code), ShareClient.parse(ShareClient.shareUri("10.0.2.2:8841", code)))
        assertFailsWith<IllegalArgumentException> { ShareClient.parse("http://x/evil?y") }
        assertFailsWith<IllegalArgumentException> { ShareClient.parse(" ") }
        assertFailsWith<IllegalArgumentException> { ShareClient.requireCode("123456") }
        assertEquals(code, ShareClient.requireCode("7k3m qw2d 9xhp t4ca"))
        // The QR holds the link: version 6 at most for the longest LAN address and port, and it decodes back.
        val longest = ShareClient.shareUri("192.168.100.200:65535", "ZZZZZZZZZZZZZZZZ")
        val qr = QrCode.encode(longest)
        assertTrue(qr.version in 5..6, "version ${qr.version}")
        val matrix = Array(qr.size) { y -> BooleanArray(qr.size) { x -> qr[x, y] } }
        assertEquals(longest, QrMatrixDecoder.decode(matrix).text)
        val typical = QrCode.encode(ShareClient.shareUri("10.0.2.16:8841", code))
        assertEquals(ShareClient.shareUri("10.0.2.16:8841", code), QrMatrixDecoder.decode(Array(typical.size) { y -> BooleanArray(typical.size) { x -> typical[x, y] } }).text)
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
