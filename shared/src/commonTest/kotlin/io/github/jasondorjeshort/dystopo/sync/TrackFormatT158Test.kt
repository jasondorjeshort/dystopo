package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T158 (ADR 0033 §3): the new point fields and the gaps travel as two new operation types. The new reader reads
 * every older operation unchanged; a v0.1.14 reader ([V014Codec], the old code kept as a fixture) refuses a bundle
 * holding the new ones by name, so nothing is imported; the new reader refuses a still newer format the same way;
 * [SyncRefusal] turns either into a sentence and [SyncServer] counts it.
 */
class TrackFormatT158Test {
    private val dev = "00000000-0000-4000-8000-0000000000d1"
    private val track = "00000000-0000-4000-8000-0000000000a1"
    private fun hlc(i: Int) = Hlc(1_790_000_000_000L + i, 0, dev)

    private val plain = TrackPoint(46.85, -121.73, 1600.0, 1_790_000_000_000L)
    private val full = TrackPoint(46.85, -121.73, 1600.0, 1_790_000_001_000L, "<x:y>1</x:y>", 12.5, 1.25, 270.0, 8.0, TrackPoint.INACCURATE or TrackPoint.NETWORK)

    private fun roundTrip(op: Op): Op {
        val bytes = OpCodec.encode(op)
        val back = OpCodec.decode(bytes)
        assertEquals(op, back)
        assertContentEquals(bytes, OpCodec.encode(back), "re-encodes byte for byte")
        return back
    }

    @Test
    fun plainAppendsStayInTheOldFormat() {
        val op = Op(hlc(1), AppendPoints(track, 0, listOf(plain, plain.copy(time = null, ele = null))))
        assertEquals(TYPE_APPEND, op.body.type)
        roundTrip(op)
        // Byte for byte what v0.1.14 reads.
        assertEquals(op, V014Codec.readOp(ByteReader(OpCodec.encode(op))))
    }

    @Test
    fun fixFieldsAndGapsRoundTrip() {
        val append = Op(hlc(2), AppendPoints(track, 3, listOf(plain, full, full.copy(accuracyM = null, flags = 0, extensions = null))))
        assertEquals(TYPE_APPEND_V2, append.body.type)
        roundTrip(append)
        for (reason in GapReason.entries) roundTrip(Op(hlc(3), AddGap(track, TrackGap(10, 20, reason))))
        // The digest of a plain append is the old one's; a v2 append's differs from the same points without fields.
        assertTrue(OpCodec.digest(append.body as AppendPoints) != OpCodec.digest(AppendPoints(track, 3, listOf(plain, full.copy(accuracyM = null, speedMps = null, courseDeg = null, verticalAccuracyM = null, flags = 0)))))
    }

    @Test
    fun aV014ReaderRefusesTheNewOperationsByName() {
        val v2 = OpCodec.encode(Op(hlc(4), AppendPoints(track, 0, listOf(full))))
        val e5 = assertFailsWith<SyncFormatException> { V014Codec.readOp(ByteReader(v2)) }
        assertEquals("unknown operation type 5", e5.message)
        val gap = OpCodec.encode(Op(hlc(5), AddGap(track, TrackGap(1, 2, GapReason.NO_FIX))))
        val e6 = assertFailsWith<SyncFormatException> { V014Codec.readOp(ByteReader(gap)) }
        assertEquals("unknown operation type 6", e6.message)

        // A recording made by this build: the old reader refuses the whole bundle, so an old replica imports nothing.
        val now = longArrayOf(1_790_000_000_000L)
        val phone = InMemoryTrackStore(dev) { now[0] }
        val rec = TrackRecorder(phone, clock = { now[0] })
        rec.start("Walk")
        for (i in 0 until 5) { rec.onFix(LocationFix(46.85, -121.73 + i * 1e-5, 1600.0, now[0], 5.0, speedMps = 1.2)); now[0] += 1000 }
        rec.pause(); rec.resume()
        rec.onFix(LocationFix(46.85, -121.7299, 1600.0, now[0] + 60_000, 5.0))
        rec.stop()
        val bytes = phone.bundle().encode()
        val old = InMemoryTrackStore("00000000-0000-4000-8000-0000000000d2")
        val refused = assertFailsWith<SyncFormatException> { old.apply(V014Codec.decodeBundle(bytes), 0) }
        assertTrue(refused.message!!.startsWith("unknown operation type "), refused.message)
        assertEquals(0L, old.opCount, "nothing imported")
        assertEquals(0, old.list().size)
        assertEquals(
            "The other device runs a newer Dystopo whose tracks this version cannot read (${refused.message}). Update this one; nothing was imported.",
            SyncRefusal.reason(refused),
        )

        // A bundle without the new fields (a plain GPX import, a waypoint) still reads on the old release.
        val plainStore = InMemoryTrackStore(dev)
        plainStore.insert(Track(track, "Plain", listOf(TrackSegment(listOf(plain)))), emptyList(), 1, 1)
        val oldBundle = V014Codec.decodeBundle(plainStore.bundle().encode())
        assertEquals(plainStore.bundle().ops, oldBundle.ops)
    }

    /** Hand-built operation bytes: an HLC, a type, a kind, an id, then [body]. */
    private fun opBytes(type: Int, body: (ByteWriter) -> Unit): ByteArray {
        val w = ByteWriter()
        OpCodec.writeHlc(w, hlc(9))
        w.byte(type)
        w.byte(EntityKind.TRACK.code)
        w.id(track)
        body(w)
        return w.toByteArray()
    }

    /** A raw bundle holding [ops] as given (Bundle.encode's layout, version 1). */
    private fun rawBundle(vararg ops: ByteArray): ByteArray {
        val w = ByteWriter()
        Bundle.MAGIC.forEach { w.byte(it.code) }
        w.byte(Bundle.VERSION)
        w.id(dev)
        OpCodec.writeVector(w, VersionVector.EMPTY)
        OpCodec.writeVector(w, VersionVector(mapOf(dev to hlc(9))))
        w.uvarint(ops.size.toLong())
        for (o in ops) { w.uvarint(o.size.toLong()); w.bytes(o) }
        return w.toByteArray()
    }

    private fun refusedWith(message: String, op: ByteArray) {
        val e = assertFailsWith<SyncFormatException> { OpCodec.decode(op) }
        assertTrue(message in e.message!!, "${e.message}")
        val b = assertFailsWith<SyncFormatException> { Bundle.decode(rawBundle(op)) }
        assertNotNull(SyncRefusal.reason(b), "a refusal: ${b.message}")
    }

    @Test
    fun theNewReaderRefusesANewerFormatByName() {
        refusedWith("unknown operation type 7", opBytes(7) { it.uvarint(0) })
        // An append version 2 point with a field bit this build does not know.
        refusedWith("unknown point fields", opBytes(TYPE_APPEND_V2) { w -> w.uvarint(0); w.uvarint(1); w.uvarint(256L or 8L); w.double(1.0); w.double(2.0); w.double(5.0) })
        // ... or a flag bit.
        refusedWith("unknown point flags", opBytes(TYPE_APPEND_V2) { w -> w.uvarint(0); w.uvarint(1); w.uvarint(128); w.double(1.0); w.double(2.0); w.uvarint(4) })
        refusedWith("unknown gap reason \"eclipse\"", opBytes(TYPE_GAP) { w -> w.svarint(1); w.svarint(2); w.string("eclipse") })
        val e = assertFailsWith<SyncFormatException> { OpCodec.decode(opBytes(TYPE_APPEND_V2) { w -> w.uvarint(0); w.uvarint(1); w.uvarint(2); w.double(1.0); w.double(2.0); w.svarint(5) }) }
        assertEquals("append version 2 without fix fields", e.message)
        assertFailsWith<SyncFormatException> { OpCodec.decode(opBytes(TYPE_GAP) { w -> w.svarint(5); w.svarint(2); w.string("no-fix") }) }
    }

    @Test
    fun aServerRefusesANewerPushPlainlyAndCountsIt() {
        val store = InMemoryTrackStore(dev)
        val server = SyncServer(store, { 0L }, { it }, { it })
        val reply = server.handle("POST", "/sync/push", emptyMap(), rawBundle(opBytes(7) { it.uvarint(0) }))!!
        assertEquals(400, reply.status)
        val text = reply.body.decodeToString()
        assertTrue("\"refused\":\"format\"" in text, text)
        assertTrue("newer Dystopo" in text && "unknown operation type 7" in text, text)
        assertEquals(1L, server.refused)
        assertEquals(0L, store.opCount)
        assertTrue("\"refused\":1" in server.handle("GET", "/sync/status", emptyMap(), ByteArray(0))!!.body.decodeToString())
        // The client that sent it reads the older side's refusal from the HTTP error.
        val client = IllegalStateException("POST http://peer/sync/push: HTTP 400 $text")
        assertEquals(
            "The other device runs an older Dystopo that cannot read this one's tracks (unknown operation type 7). Update it; nothing was sent.",
            SyncRefusal.reason(client),
        )
        assertNull(SyncRefusal.reason(IllegalStateException("connection refused")))
    }

    @Test
    fun fieldsActivityAndGapsSyncAndMerge() {
        val now = longArrayOf(1_790_000_000_000L)
        val a = InMemoryTrackStore(dev) { now[0] }
        val rec = TrackRecorder(a, ActivityProfile.of(Activity.AIR), clock = { now[0] })
        val id = rec.start("Flight").id
        for (i in 0 until 10) { rec.onFix(LocationFix(46.85 + i * 0.01, -121.73, 9000.0, now[0], if (i == 3) 300.0 else 20.0, speedMps = 230.0, courseDeg = 0.0)); now[0] += 5000 }
        rec.onFix(LocationFix(47.5, -121.73, 9000.0, now[0] + 600_000, 20.0, speedMps = 230.0)) // 10 min without a fix: time-only gap
        rec.stop()
        val b = InMemoryTrackStore("00000000-0000-4000-8000-0000000000d2")
        b.apply(Bundle.decode(a.bundle().encode()), 0)
        assertEquals(a.track(id), b.track(id))
        assertEquals(a.snapshot(), b.snapshot())
        val t = b.track(id)!!
        assertEquals(Activity.AIR, t.activity)
        assertEquals(ActivityProfile.of(Activity.AIR), t.profile)
        assertEquals(listOf(GapReason.NO_FIX), t.gaps.map { it.reason })
        assertEquals(listOf(10, 1), t.segments.map { it.points.size })
        assertEquals(0, t.points.count { it.inaccurate }, "no accuracy bound in the air")
        assertEquals(300.0, t.points[3].accuracyM)
        // The same gap from two devices counts once; a gap on the other device merges in.
        b.addGap(id, t.gaps.single(), 0)
        b.addGap(id, TrackGap(1, 2, GapReason.RESTARTED), 0)
        a.apply(Bundle.decode(b.bundle(a.vector()).encode()), 0)
        assertEquals(2, a.gaps(id).size)
        assertEquals(a.snapshot(), b.snapshot())
        // A delete hides the gaps with the track.
        a.delete(id)
        b.apply(Bundle.decode(a.bundle(b.vector()).encode()), 0)
        assertEquals(emptyList(), b.gaps(id))
        assertNull(b.track(id))
    }
}
