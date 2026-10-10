package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.crypto.RelayProtocol
import io.github.jasondorjeshort.dystopo.sync.crypto.RelayTransport
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val A = "00000000-0000-4000-8000-00000000000a"
private const val B = "00000000-0000-4000-8000-00000000000b"
private const val T1 = "10000000-0000-4000-8000-000000000001"
private const val T2 = "10000000-0000-4000-8000-000000000002"

/**
 * The relay (T72) over real HTTP: two replicas sync through it with identical GPX, and what the server
 * stores does not decrypt without the share code. Plus its limits.
 */
class RelayTest {
    private val secure = SecureRandom()
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also(secure::nextBytes) }
    private val servers = ArrayList<HttpServer>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    private fun serve(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        s.createContext("/") { ex -> if (!RelayRoutes.handle(ex)) { ex.sendResponseHeaders(404, -1); ex.close() } }
        s.start()
        servers.add(s)
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun post(url: String, body: ByteArray): ByteArray {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        check(code in 200..299) { "HTTP $code ${bytes.decodeToString()}" }
        return bytes
    }

    private val pts = List(40) { TrackPoint(46.786 + it * 0.0004, -121.736 + it * 0.0002, 1650.0 + it * 3, 1_700_000_000_000L + it * 5000) }

    @Test
    fun twoReplicasSyncThroughTheRelayAndTheStoredBlobNeedsTheCode() {
        RelayRoutes.configure(Relay.Config())
        val base = serve()
        val a = InMemoryTrackStore(A).also { it.insert(Track(T1, "Paradise to Panorama Point", listOf(TrackSegment(pts))), emptyList(), 5, 10) }
        val b = InMemoryTrackStore(B).also { it.insert(Track(T2, "Skyline descent", listOf(TrackSegment(pts.reversed()))), emptyList(), 6, 11) }
        val code = ShareCode.generate(random)
        val ta = RelayTransport(base, code, RelayProtocol.newSide(random), ::post, random, Gzip.compress, Gzip.decompress)
        val tb = RelayTransport(base, ShareCode.format(code), RelayProtocol.newSide(random), ::post, random, Gzip.compress, Gzip.decompress)
        Sync.exchange(a, ta, System::currentTimeMillis)
        Sync.exchange(b, tb, System::currentTimeMillis)
        Sync.exchange(a, ta, System::currentTimeMillis)
        assertEquals(a.snapshot().digest(), b.snapshot().digest())
        for (id in listOf(T1, T2)) assertEquals(TrackExport.gpx(a, id), TrackExport.gpx(b, id))

        val stored = RelayRoutes.relay.stored(ShareCode.slot(code))
        assertEquals(2, stored.size)
        for (blob in stored) {
            // No plaintext: the track names do not appear, and the frame is a sealed one.
            assertFalse("Paradise" in String(blob, Charsets.ISO_8859_1))
            assertTrue(EncryptedBundle.isSealed(blob))
            // Every other code fails authentication, including codes one character away.
            for (wrong in listOf(ShareCode.generate(random), code.dropLast(1) + (if (code.last() == '0') '1' else '0'), "0".repeat(16))) {
                val e = assertFailsWith<EncryptedBundle.SealException> { EncryptedBundle.openBundle(blob, wrong, Gzip.decompress) }
                assertTrue(e.authFailed)
            }
            assertTrue(EncryptedBundle.openBundle(blob, code, Gzip.decompress).ops.isNotEmpty())
        }
        // The status names no slot.
        val status = RelayRoutes.relay.status()
        assertEquals(1, status["slots"])
        assertFalse(ShareCode.slot(code) in status.toString())
    }

    private fun frame(size: Int) = EncryptedBundle.seal(ShareCode.generate(random), EncryptedBundle.Purpose.RELAY, ByteArray(size), random)

    private val slot = "0123456789abcdef0123456789abcdef"

    @Test
    fun limitsAndExpiry() {
        val r = Relay(Relay.Config(ttlSeconds = 60, maxBlobBytes = 1000, maxTotalBytes = 2000, maxSlots = 2, maxSides = 2, requestsPerMinute = 30))
        val q = { side: String -> mapOf("slot" to slot, "side" to side) }
        assertEquals(204, r.handle("POST", "/sync/relay", q("aaaaaaaa"), frame(100), "1.2.3.4", 0).status)
        val got = r.handle("POST", "/sync/relay", q("bbbbbbbb"), frame(200), "1.2.3.4", 0)
        assertEquals(200, got.status)
        assertEquals(1, RelayProtocol.decodeReply(got.body).size)
        // after= skips what was seen.
        assertEquals(204, r.handle("POST", "/sync/relay", q("bbbbbbbb") + ("after" to "1"), ByteArray(0), "1.2.3.4", 0).status)
        assertEquals(200, r.handle("POST", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "1.2.3.4", 0).status)
        // A third side, a big blob, plain bytes, bad ids, a full relay.
        assertEquals(507, r.handle("POST", "/sync/relay", q("cccccccc"), frame(10), "1.2.3.4", 0).status)
        assertEquals(413, r.handle("POST", "/sync/relay", q("aaaaaaaa"), frame(1000), "1.2.3.4", 0).status)
        assertEquals(413, r.handle("POST", "/sync/relay", q("aaaaaaaa"), null, "1.2.3.4", 0).status)
        assertEquals(400, r.handle("POST", "/sync/relay", q("aaaaaaaa"), "DSYNC plain bundle".toByteArray(), "1.2.3.4", 0).status)
        assertEquals(400, r.handle("POST", "/sync/relay", mapOf("slot" to "XYZ", "side" to "aaaaaaaa"), ByteArray(0), "1.2.3.4", 0).status)
        assertEquals(405, r.handle("GET", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "1.2.3.4", 0).status)
        val slot2 = "1123456789abcdef0123456789abcdef"
        assertEquals(204, r.handle("POST", "/sync/relay", mapOf("slot" to slot2, "side" to "aaaaaaaa"), frame(900), "5.6.7.8", 0).status)
        assertEquals(507, r.handle("POST", "/sync/relay", mapOf("slot" to slot2, "side" to "bbbbbbbb"), frame(900), "5.6.7.8", 0).status) // over 2000 bytes held
        assertEquals(507, r.handle("POST", "/sync/relay", mapOf("slot" to "2123456789abcdef0123456789abcdef", "side" to "aaaaaaaa"), frame(1), "5.6.7.8", 0).status) // 2 slots
        // Rate limit: 30 a minute per address; another address is unaffected; tokens come back with time.
        var limited = 0
        repeat(40) { if (r.handle("POST", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "9.9.9.9", 0).status == 429) limited++ }
        assertEquals(10, limited)
        assertEquals(200, r.handle("POST", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "8.8.8.8", 0).status)
        assertEquals(200, r.handle("POST", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "9.9.9.9", 10_000).status)
        // Forget, then expiry: 60 s after the last write everything is gone.
        assertEquals(200, r.handle("POST", "/sync/relay", q("aaaaaaaa") + ("forget" to "1"), ByteArray(0), "7.7.7.7", 20_000).status)
        assertEquals(1, RelayProtocol.decodeReply(r.handle("POST", "/sync/relay", q("cccccccc"), ByteArray(0), "7.7.7.7", 20_000).body).size) // only b is left
        assertEquals(204, r.handle("POST", "/sync/relay", mapOf("slot" to slot2, "side" to "bbbbbbbb"), ByteArray(0), "7.7.7.7", 61_000).status)
        val st = r.status()
        assertEquals(0, st["blobs"])
        assertEquals(0L, st["bytes"])
        @Suppress("UNCHECKED_CAST") val refused = st["refused"] as Map<String, Long>
        assertEquals(10L, refused["rateLimited"])
        assertEquals(3L, refused["full"])
        assertEquals(2L, refused["tooLarge"])
        // Disabled: 404.
        assertEquals(404, Relay(Relay.Config(enabled = false)).handle("POST", "/sync/relay", q("aaaaaaaa"), ByteArray(0), "1.2.3.4", 0).status)
    }
}
