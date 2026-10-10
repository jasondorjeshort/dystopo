package io.github.jasondorjeshort.dystopo.sync.crypto

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.Purpose
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.SealException
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncFormatException
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "00000000-0000-4000-8000-00000000000a"
private const val B = "00000000-0000-4000-8000-00000000000b"
private const val T1 = "10000000-0000-4000-8000-000000000001"
private const val T2 = "10000000-0000-4000-8000-000000000002"

/** Deterministic stand-in for SecureRandom in tests (the app and server pass a real one). */
fun testRandom(seed: Int): (Int) -> ByteArray {
    val r = Random(seed)
    return { n -> r.nextBytes(n) }
}

private val identity: (ByteArray) -> ByteArray = { it }

class ShareCodeTest {
    @Test
    fun generatesEightyBitCodesInTheAlphabet() {
        val rnd = testRandom(1)
        val codes = List(2000) { ShareCode.generate(rnd) }
        codes.forEach { c ->
            assertEquals(16, c.length)
            assertTrue(c.all { it in ShareCode.ALPHABET }, c)
            assertEquals(c, ShareCode.normalize(c))
        }
        assertEquals(codes.size, codes.toSet().size)
        // Every character position uses the whole alphabet (the bits are spread, not padded).
        for (pos in 0 until 16) assertEquals(32, codes.map { it[pos] }.toSet().size, "position $pos")
        assertEquals("0000000000000000", ShareCode.generate { ByteArray(it) })
        assertEquals("ZZZZZZZZZZZZZZZZ", ShareCode.generate { ByteArray(it) { -1 } })
    }

    @Test
    fun normalizesWhatPeopleType() {
        assertEquals("7K3MQW2D9XHPT4CA", ShareCode.normalize("7k3m-qw2d 9xhp-t4ca"))
        assertEquals("1100000000000000", ShareCode.normalize("il00-0000-0000-0000"))
        assertEquals("1000000000000000", ShareCode.normalize("lOoo oooo oooo oooo"))
        assertNull(ShareCode.normalize("7K3M-QW2D-9XHP-T4C")) // 15
        assertNull(ShareCode.normalize("7K3M-QW2D-9XHP-T4CAA")) // 17
        assertNull(ShareCode.normalize("7K3M-QW2D-9XHP-T4CU")) // U is not in the alphabet
        assertNull(ShareCode.normalize("123456"))
        assertNull(ShareCode.normalize(null))
        assertEquals("7K3M-QW2D-9XHP-T4CA", ShareCode.format("7K3MQW2D9XHPT4CA"))
    }

    @Test
    fun slotsAndKeysDependOnEverythingTheyShould() {
        val code = "7K3MQW2D9XHPT4CA"
        val slot = ShareCode.slot(code)
        assertTrue(RelayProtocol.isSlot(slot), slot)
        assertEquals(slot, ShareCode.slot("7k3m-qw2d-9xhp-t4ca"))
        assertNotEquals(slot, ShareCode.slot("7K3MQW2D9XHPT4CB"))
        val salt = ByteArray(16) { it.toByte() }
        val (k1, n1) = ShareCode.key(code, salt, "relay bundle")
        assertEquals(32, k1.size)
        assertEquals(12, n1.size)
        val (k2, _) = ShareCode.key(code, salt.copyOf().also { it[0] = 9 }, "relay bundle")
        val (k3, _) = ShareCode.key(code, salt, "lan request")
        val (k4, _) = ShareCode.key("7K3MQW2D9XHPT4CB", salt, "relay bundle")
        assertFalse(k1.contentEquals(k2))
        assertFalse(k1.contentEquals(k3))
        assertFalse(k1.contentEquals(k4))
        // The documented construction, spelled out: HKDF-SHA256(salt, ASCII code, "dystopo seal v1 <purpose>", 44).
        val okm = Hkdf.derive(salt, code.encodeToByteArray(), "dystopo seal v1 relay bundle".encodeToByteArray(), 44)
        assertContentEquals(okm.copyOf(32), k1)
        assertContentEquals(okm.copyOfRange(32, 44), n1)
        assertFailsWith<IllegalArgumentException> { ShareCode.key("123456", salt, "relay bundle") }
    }
}

class EncryptedBundleTest {
    private val pts = List(25) { TrackPoint(46.8 + it * 0.001, -121.7 + it * 0.0005, 1600.0 + it, 1_000_000L + it * 1000) }
    private val code = "7K3MQW2D9XHPT4CA"

    private fun storeWithTrack(device: String, id: String, name: String) = InMemoryTrackStore(device).also {
        it.insert(Track(id, name, listOf(TrackSegment(pts))), emptyList(), 5, 10)
    }

    @Test
    fun aBundleSurvivesTheRoundTripAndOnlyOpensWithItsCode() {
        val a = storeWithTrack(A, T1, "Skyline loop")
        val frame = EncryptedBundle.sealBundle(a.bundle(VersionVector.EMPTY), code, testRandom(2), identity)
        assertTrue(EncryptedBundle.isSealed(frame))
        assertEquals(Purpose.RELAY, EncryptedBundle.purpose(frame))
        // Nothing of the plaintext shows: not the name, not the bundle header.
        val text = frame.decodeToString()
        assertFalse("Skyline" in text)
        assertFalse("DSYNC" in text)
        val b = InMemoryTrackStore(B)
        b.apply(EncryptedBundle.openBundle(frame, "7k3m qw2d 9xhp t4ca", identity), 20)
        assertEquals(TrackExport.gpx(a, T1), TrackExport.gpx(b, T1))
        // A wrong code (one character off), the wrong purpose, a changed byte: all refused as authentication failures.
        val wrong = assertFailsWith<SealException> { EncryptedBundle.openBundle(frame, "7K3MQW2D9XHPT4CB", identity) }
        assertTrue(wrong.authFailed)
        assertTrue(assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.LAN_REQUEST, frame) }.authFailed)
        for (i in frame.indices step 13) {
            if (i < 7) continue // magic, version and purpose are format errors, tested below
            val bad = frame.copyOf().also { it[i] = (it[i].toInt() xor 0x20).toByte() }
            assertTrue(assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.RELAY, bad) }.authFailed, "byte $i")
        }
    }

    @Test
    fun freshSaltPerFrame() {
        val pt = "the same plaintext".encodeToByteArray()
        val rnd = testRandom(3)
        val f1 = EncryptedBundle.seal(code, Purpose.RELAY, pt, rnd)
        val f2 = EncryptedBundle.seal(code, Purpose.RELAY, pt, rnd)
        assertFalse(f1.contentEquals(f2))
        assertFalse(EncryptedBundle.salt(f1).contentEquals(EncryptedBundle.salt(f2)))
        assertEquals(pt.size + EncryptedBundle.OVERHEAD, f1.size)
        assertContentEquals(pt, EncryptedBundle.open(code, Purpose.RELAY, f2))
    }

    @Test
    fun contextIsBound() {
        val f = EncryptedBundle.seal(code, Purpose.LAN_RESPONSE, byteArrayOf(1, 2, 3), testRandom(4), byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 2, 3), EncryptedBundle.open(code, Purpose.LAN_RESPONSE, f, byteArrayOf(9)))
        assertTrue(assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.LAN_RESPONSE, f, byteArrayOf(8)) }.authFailed)
        assertTrue(assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.LAN_RESPONSE, f) }.authFailed)
    }

    @Test
    fun malformedInputThrowsOnlySealException() {
        val frame = EncryptedBundle.seal(code, Purpose.RELAY, ByteArray(100) { it.toByte() }, testRandom(5))
        for (n in 0 until frame.size) {
            val e = assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.RELAY, frame.copyOf(n)) }
            if (n < EncryptedBundle.OVERHEAD) assertFalse(e.authFailed, "length $n")
        }
        val r = Random(6)
        repeat(2000) {
            val junk = r.nextBytes(r.nextInt(0, 200))
            if (r.nextBoolean() && junk.size > 7) { "DSEAL".forEachIndexed { i, c -> junk[i] = c.code.toByte() }; junk[5] = 1; junk[6] = 1 }
            assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.RELAY, junk) }
        }
        // Not a frame at all vs. the right frame with a bundle inside that is not a bundle.
        assertFalse(assertFailsWith<SealException> { EncryptedBundle.open(code, Purpose.RELAY, "DSYNC....".encodeToByteArray()) }.authFailed)
        val notABundle = EncryptedBundle.seal(code, Purpose.RELAY, "hello".encodeToByteArray(), testRandom(7))
        assertFailsWith<SyncFormatException> { EncryptedBundle.openBundle(notABundle, code, identity) }
    }
}

class SealedLinkTest {
    private val code = "Q4M8ZT2K7VNB3HXR"

    @Test
    fun requestAndReplyRoundTrip() {
        val rnd = testRandom(8)
        val req = SealedLink.Request("POST", "/sync/pull?since=abc,def&limit=2000", ByteArray(300) { it.toByte() })
        val rf = SealedLink.sealRequest(code, req, rnd)
        val got = SealedLink.openRequest(code, rf)
        assertEquals("POST", got.method)
        assertEquals("/sync/pull", got.path)
        assertEquals(mapOf("since" to "abc,def", "limit" to "2000"), got.query)
        assertContentEquals(req.body, got.body)
        val reply = SealedLink.Response(200, "application/octet-stream", byteArrayOf(5, 6, 7))
        val pf = SealedLink.sealResponse(code, rf, reply, rnd)
        val back = SealedLink.openResponse(code, rf, pf)
        assertEquals(200, back.status)
        assertEquals("application/octet-stream", back.contentType)
        assertContentEquals(byteArrayOf(5, 6, 7), back.body)
        // A reply is bound to its request: it does not open against another one.
        val rf2 = SealedLink.sealRequest(code, req, rnd)
        assertTrue(assertFailsWith<SealException> { SealedLink.openResponse(code, rf2, pf) }.authFailed)
        // A request does not open with another code, nor as a reply.
        assertTrue(assertFailsWith<SealException> { SealedLink.openRequest("Q4M8ZT2K7VNB3HXS", rf) }.authFailed)
        assertTrue(assertFailsWith<SealException> { SealedLink.openResponse(code, rf, rf) }.authFailed)
        assertEquals(404, SealedLink.openResponse(code, rf, SealedLink.sealResponse(code, rf, SealedLink.Response(404, "application/json", ByteArray(0)), rnd)).status)
    }

    @Test
    fun replayGuardRefusesASecondUse() {
        val g = SealedLink.ReplayGuard(capacity = 3)
        val rnd = testRandom(9)
        val frames = List(5) { SealedLink.sealRequest(code, SealedLink.Request("GET", "/share/hello", ByteArray(0)), rnd) }
        assertTrue(g.firstUse(frames[0]))
        assertFalse(g.firstUse(frames[0]))
        frames.drop(1).forEach { assertTrue(g.firstUse(it)) }
        assertEquals(3, g.size)
        assertTrue(g.firstUse(frames[0])) // forgotten after capacity; the code changes per session anyway
    }
}

class RelayTransportTest {
    /** The relay server in a few lines (the real one is serverlib's RelayRoutes): slot -> side -> (seq, blob). */
    private class FakeRelay {
        val slots = HashMap<String, MutableMap<String, Pair<Long, ByteArray>>>()
        var seq = 0L
        fun post(url: String, body: ByteArray): ByteArray {
            val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val slot = q.getValue("slot")
            val side = q.getValue("side")
            val after = q["after"]?.toLong() ?: 0
            val s = slots.getOrPut(slot) { HashMap() }
            if (q["forget"] == "1") { s.remove(side); return ByteArray(0) }
            if (body.isNotEmpty()) s[side] = (++seq) to body
            val others = s.filter { it.key != side && it.value.first > after }.map { RelayProtocol.Blob(it.value.first, it.value.second) }
            return if (others.isEmpty()) ByteArray(0) else RelayProtocol.encodeReply(others)
        }
    }

    private val pts = List(12) { TrackPoint(46.85 + it * 0.001, -121.76, 1700.0 + it, 2_000_000L + it * 1000) }

    @Test
    fun twoPhonesConvergeThroughTheRelayAndItsBlobsAreOpaque() {
        val relay = FakeRelay()
        val a = InMemoryTrackStore(A).also { it.insert(Track(T1, "Paradise loop", listOf(TrackSegment(pts))), emptyList(), 5, 10) }
        val b = InMemoryTrackStore(B).also { it.insert(Track(T2, "Panorama Point", listOf(TrackSegment(pts.reversed()))), emptyList(), 6, 11) }
        val code = ShareCode.generate(testRandom(10))
        val rnd = testRandom(11)
        val ta = RelayTransport("http://relay.test/", code, RelayProtocol.newSide(rnd), relay::post, rnd, identity, identity)
        val tb = RelayTransport("http://relay.test", ShareCode.format(code).lowercase(), RelayProtocol.newSide(rnd), relay::post, rnd, identity, identity)
        assertEquals(ta.slot, tb.slot)

        // A posts first and finds nobody; B then gets A's log and posts its own; A's next sync gets B's.
        val r1 = Sync.exchange(a, ta, { 100 })
        assertEquals(0, r1.pulled)
        val r2 = Sync.exchange(b, tb, { 200 })
        assertTrue(r2.merged.applied > 0)
        assertEquals(1, ta.fetch()) // B's blob is waiting for A
        Sync.exchange(a, ta, { 300 })
        assertEquals(a.snapshot().digest(), b.snapshot().digest())
        assertEquals(TrackExport.gpx(a, T2), TrackExport.gpx(b, T2))
        assertEquals(TrackExport.gpx(a, T1), TrackExport.gpx(b, T1))
        // A did not post again: B's blob already held everything A has. Polling now finds nothing new on either
        // side (two phones must not answer each other's identical logs forever).
        assertEquals(1, ta.posts)
        assertEquals(1, ta.postsSkipped)
        assertEquals(1, tb.posts)
        val seq = relay.seq
        repeat(5) {
            for ((store, t) in listOf(a to ta, b to tb)) if (t.fetch() > 0) Sync.exchange(store, t, { 400 })
        }
        assertEquals(seq, relay.seq)
        // A change on A goes out with A's next sync, and B's poll brings it in.
        a.rename(T2, "Panorama Point (renamed on A)", 500)
        Sync.exchange(a, ta, { 500 })
        assertEquals(1, tb.fetch())
        Sync.exchange(b, tb, { 600 })
        assertEquals("Panorama Point (renamed on A)", b.summary(T2)!!.name)
        assertEquals(1, tb.postsSkipped)

        // What the relay holds does not decrypt without the code, and does not show the track names.
        val blobs = relay.slots.getValue(ta.slot).values.map { it.second }
        assertEquals(2, blobs.size)
        for (blob in blobs) {
            assertFalse("Paradise" in blob.decodeToString() || "Panorama" in blob.decodeToString())
            assertFailsWith<SealException> { EncryptedBundle.openBundle(blob, ShareCode.generate(testRandom(12)), identity) }
            val opened: Bundle = EncryptedBundle.openBundle(blob, code, identity)
            assertTrue(opened.ops.isNotEmpty())
        }
        // A third phone with another code meets nobody.
        val tc = RelayTransport("http://relay.test", ShareCode.generate(testRandom(13)), RelayProtocol.newSide(rnd), relay::post, rnd, identity, identity)
        assertEquals(0, tc.fetch())
        ta.forget()
        assertEquals(1, relay.slots.getValue(ta.slot).size)
    }

    @Test
    fun repliesParseAndRejectJunk() {
        val blobs = listOf(RelayProtocol.Blob(1, byteArrayOf(1, 2)), RelayProtocol.Blob(300, ByteArray(200) { 7 }))
        val back = RelayProtocol.decodeReply(RelayProtocol.encodeReply(blobs))
        assertEquals(listOf(1L, 300L), back.map { it.seq })
        assertContentEquals(blobs[1].bytes, back[1].bytes)
        assertEquals(emptyList(), RelayProtocol.decodeReply(ByteArray(0)).map { it.seq })
        val enc = RelayProtocol.encodeReply(blobs)
        for (n in 1 until enc.size) assertFailsWith<SyncFormatException> { RelayProtocol.decodeReply(enc.copyOf(n)) }
        assertFailsWith<SyncFormatException> { RelayProtocol.decodeReply("hello".encodeToByteArray()) }
    }
}
