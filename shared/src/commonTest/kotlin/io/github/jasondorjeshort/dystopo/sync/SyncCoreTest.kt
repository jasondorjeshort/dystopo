package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "00000000-0000-4000-8000-00000000000a"
private const val B = "00000000-0000-4000-8000-00000000000b"
private const val C = "00000000-0000-4000-8000-00000000000c"
private const val T1 = "10000000-0000-4000-8000-000000000001"
private const val W1 = "20000000-0000-4000-8000-000000000001"

/** Full two-way sync between two replicas, as [Sync.exchange] does over a transport. */
fun syncPair(a: SyncReplica, b: SyncReplica, now: Long = 0) {
    a.apply(b.bundle(a.vector()), now)
    b.apply(a.bundle(b.vector()), now)
}

class HlcTest {
    @Test
    fun tickIsMonotonicAndObserveMovesPastRemote() {
        val c = HlcClock(A)
        val h1 = c.tick(100)
        val h2 = c.tick(100)
        val h3 = c.tick(50) // the wall clock went back
        assertTrue(h1 < h2 && h2 < h3)
        assertEquals(Hlc(100, 2, A), h3)
        c.observe(Hlc(5_000, 7, B))
        val h4 = c.tick(200)
        assertEquals(Hlc(5_000, 8, A), h4)
        assertTrue(h4 > Hlc(5_000, 7, B))
        assertEquals(Hlc(6_000, 0, A), c.tick(6_000))
    }

    @Test
    fun textSortsLikeHlcAndParses() {
        val r = Random(1)
        val hs = List(500) { Hlc(r.nextLong(0, 1L shl 40), r.nextInt(0, 300), listOf(A, B, C)[r.nextInt(3)]) }
        assertEquals(hs.sorted(), hs.sortedBy { it.text })
        hs.forEach { assertEquals(it, Hlc.parse(it.text)) }
        assertEquals("000000000064.000002.$A", Hlc(100, 2, A).text)
        assertFailsWith<IllegalArgumentException> { Hlc.parse("nonsense") }
    }

    @Test
    fun versionVectorTextRoundTrip() {
        val v = VersionVector(mapOf(B to Hlc(7, 1, B), A to Hlc(9, 0, A)))
        assertEquals(v, VersionVector.parse(v.text))
        assertTrue(v.text.startsWith("000000000009.000000.$A,"), "entries sorted by device")
        assertTrue(v.covers(Hlc(9, 0, A)))
        assertFalse(v.covers(Hlc(9, 1, A)))
        assertFalse(v.covers(Hlc(1, 0, C)))
        assertEquals(VersionVector.EMPTY, VersionVector.parse(""))
        assertEquals(Hlc.zero(C), v[C])
    }
}

class OpCodecTest {
    private val point = TrackPoint(46.852_123_456_789, -121.760_000_000_1, 1645.25, 1_700_000_000_123, "<gpxtpx:hr>140</gpxtpx:hr>")

    private val samples: List<OpBody> = listOf(
        PutTrack(T1, 1_700_000_000_000, "Skyline loop", "d", null, "src", "hike", "<x:y>1</x:y>"),
        PutTrack("not-a-uuid id", -5, ""),
        PutWaypoint(T1, Waypoint(W1, "Camp", 46.8, -121.7, null, null, null, "c", "Summit", null, null)),
        PutWaypoint(null, Waypoint(W1, "Ünïcødé ⛰", -0.0, 180.0, -12.5, -1, "x", null, null, "t", "e")),
        PutRoute(Route(T1, "R", listOf(RoutePoint(1.0, 2.0), RoutePoint(3.0, 4.0, 5.0, 6, "n", "d", "s", "e")), "d", "c", "t", "x")),
        PutSetting("units", "m"),
        SetField(EntityKind.TRACK, T1, "name", "Renamed"),
        SetField(EntityKind.WAYPOINT, W1, "description", null),
        AppendPoints(T1, 3, listOf(point, TrackPoint(0.0, 0.0), point.copy(ele = null, extensions = null))),
        AppendPoints(T1, 0, emptyList()),
        Delete(EntityKind.ROUTE, T1),
    )

    @Test
    fun everyOperationRoundTrips() {
        for ((i, b) in samples.withIndex()) {
            val op = Op(Hlc(1_700_000_000_000L + i, i * 1000, if (i % 2 == 0) A else "device-$i"), b)
            assertEquals(op, OpCodec.decode(OpCodec.encode(op)), "$b")
        }
    }

    @Test
    fun uuidsAreSixteenBytes() {
        val op = Op(Hlc(1, 0, A), Delete(EntityKind.TRACK, T1))
        // hlc (1 + 1 + 17) + type + kind + id (17)
        assertEquals(38, OpCodec.encode(op).size)
        val upper = Op(Hlc(1, 0, A), Delete(EntityKind.TRACK, T1.uppercase()))
        assertEquals(upper, OpCodec.decode(OpCodec.encode(upper)), "non-canonical ids keep their spelling")
    }

    @Test
    fun aRecordedFixIsSmall() {
        val op = Op(Hlc(1_700_000_000_000, 0, A), AppendPoints(T1, 0, listOf(point.copy(extensions = null))))
        // 43 bytes of HLC, type and ids, 33 for the point: 76 bytes per recorded fix.
        assertEquals(76, OpCodec.encode(op).size)
    }

    @Test
    fun bundleRoundTripsAndRejectsGarbage() {
        val ops = samples.mapIndexed { i, b -> Op(Hlc(10L + i, 0, A), b) }
        val bundle = Bundle(A, VersionVector(mapOf(B to Hlc(3, 0, B))), VersionVector(mapOf(A to Hlc(20, 0, A), B to Hlc(3, 0, B))), ops)
        val back = Bundle.decode(bundle.encode())
        assertEquals(bundle.ops, back.ops)
        assertEquals(bundle.base, back.base)
        assertEquals(bundle.vector, back.vector)
        assertEquals(A, back.from)
        val bytes = bundle.encode()
        assertFailsWith<SyncFormatException> { Bundle.decode(bytes.copyOf(bytes.size - 3)) }
        assertFailsWith<SyncFormatException> { Bundle.decode("hello".encodeToByteArray()) }
        assertFailsWith<SyncFormatException> { Bundle.decode(bytes.copyOf().also { it[5] = 9 }) }
        assertFailsWith<SyncFormatException> { Bundle.decode(bytes + byteArrayOf(0)) }
    }

    @Test
    fun digestDependsOnSegmentAndPoints() {
        val a = AppendPoints(T1, 0, listOf(point))
        assertEquals(OpCodec.digest(a), OpCodec.digest(a.copy(id = "other")))
        assertFalse(OpCodec.digest(a) == OpCodec.digest(a.copy(segment = 1)))
        assertFalse(OpCodec.digest(a) == OpCodec.digest(a.copy(points = listOf(point.copy(time = 1)))))
        assertEquals(16, OpCodec.digest(a).length)
    }
}

class MergeTest {
    private fun store(device: String) = InMemoryTrackStore(device)

    private val pts = List(10) { TrackPoint(46.8 + it * 0.001, -121.7, 1600.0 + it, 1_000_000L + it * 1000) }

    @Test
    fun concurrentRenameResolvesTheSameWayOnBothPeers() {
        val a = store(A)
        val b = store(B)
        a.create(T1, "Original", 100)
        syncPair(a, b)
        // Both rename while apart; B's rename is later on the wall clock.
        a.rename(T1, "Name from A", 200)
        b.rename(T1, "Name from B", 300)
        syncPair(a, b)
        assertEquals("Name from B", a.summary(T1)!!.name)
        assertEquals("Name from B", b.summary(T1)!!.name)
        // Same wall time: the counter, then the device id breaks the tie, the same way everywhere.
        a.rename(T1, "A again", 1_000)
        b.rename(T1, "B again", 1_000)
        syncPair(b, a)
        assertEquals(a.summary(T1)!!.name, b.summary(T1)!!.name)
        assertEquals("B again", a.summary(T1)!!.name, "equal (wall, counter): the greater device id wins")
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun aRenameAfterSeeingTheOtherWinsEvenWithASlowClock() {
        val a = store(A)
        val b = store(B)
        a.create(T1, "x", 10_000)
        a.rename(T1, "from A", 10_000)
        syncPair(a, b)
        // B's wall clock is far behind, but it has seen A's rename: its rename is causally later.
        b.rename(T1, "from B", 5)
        syncPair(a, b)
        assertEquals("from B", a.summary(T1)!!.name)
    }

    @Test
    fun pointsFromBothPeersAreUnitedInOrder() {
        val a = store(A)
        val b = store(B)
        a.create(T1, "t", 1)
        pts.take(5).forEach { a.append(T1, it, now = it.time!!) }
        syncPair(a, b)
        pts.drop(5).forEach { b.append(T1, it, now = it.time!!) }
        a.append(T1, pts.last().copy(time = 9_999_999), newSegment = true, now = 9_999_999)
        syncPair(a, b)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.track(T1), b.track(T1))
        assertEquals(11, a.summary(T1)!!.points)
    }

    @Test
    fun theSameImportOnTwoPeersCountsOnce() {
        val t = Track(T1, "Imported", listOf(TrackSegment(pts.take(4)), TrackSegment(pts.drop(4))))
        val w = Waypoint(W1, "Top", 46.8, -121.7)
        val a = store(A)
        val b = store(B)
        a.insert(t, listOf(w), 5, 10)
        b.insert(t, listOf(w), 5, 20)
        syncPair(a, b)
        assertEquals(t, a.track(T1))
        assertEquals(t, b.track(T1))
        assertEquals(listOf(w), b.waypoints(T1))
        assertEquals(TrackExport.gpx(a, T1), TrackExport.gpx(b, T1))
    }

    @Test
    fun deleteWinsOverOlderChangesAndAReimportRevives() {
        val a = InMemoryTrackStore(A, wallClock = { 100 })
        val b = store(B)
        val t = Track(T1, "Imported", listOf(TrackSegment(pts)))
        a.insert(t, listOf(Waypoint(W1, "w", 1.0, 2.0)), 5, 10)
        syncPair(a, b)
        b.append(T1, pts.last().copy(time = 5_000_000), now = 50) // concurrent with the delete, older HLC
        a.delete(T1) // at A's wall clock, 100
        syncPair(a, b)
        assertNull(b.summary(T1))
        assertNull(b.waypoint(W1))
        assertEquals(a.snapshot(), b.snapshot())
        // Re-importing the same file (same id) after the delete brings the track back, as imported.
        b.insert(t, emptyList(), 5, 1_000)
        syncPair(a, b)
        assertEquals(t, a.track(T1))
        assertEquals(a.snapshot(), b.snapshot())
        // Dead operations were dropped from the log on both; the tombstone stays.
        for (s in listOf(a, b)) {
            val ops = s.opsSince(VersionVector.EMPTY).filter { it.body.id == T1 }
            val t = assertNotNull(Materializer.tombstone(ops))
            assertTrue(ops.none { it.body !is Delete && it.hlc < t })
        }
    }

    @Test
    fun aWaypointAddedToATrackDeletedElsewhereStaysHidden() {
        val a = store(A)
        val b = store(B)
        a.create(T1, "t", 1)
        syncPair(a, b)
        a.delete(T1)
        b.addWaypoint(T1, Waypoint(W1, "late", 1.0, 2.0), 5)
        syncPair(a, b)
        assertNull(a.waypoint(W1))
        assertNull(b.waypoint(W1))
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun routesAndSettingsMergePerField() {
        val a = store(A)
        val b = store(B)
        a.putRoute(Route(T1, "R", listOf(RoutePoint(1.0, 2.0))), 10)
        a.setSetting("units", "m", 10)
        syncPair(a, b)
        b.setField(EntityKind.ROUTE, T1, "name", "R2", 20)
        a.setField(EntityKind.ROUTE, T1, "description", "desc", 15)
        b.setSetting("units", "ft", 30)
        a.setSetting("textScale", "1.4", 31)
        syncPair(a, b)
        assertEquals(Route(T1, "R2", listOf(RoutePoint(1.0, 2.0)), description = "desc"), a.route(T1))
        assertEquals(mapOf("textScale" to "1.4", "units" to "ft"), b.settings())
        assertEquals(a.snapshot(), b.snapshot())
        b.setSetting("units", null, 40)
        b.deleteRoute(T1, 40)
        syncPair(a, b)
        assertEquals(mapOf("textScale" to "1.4"), a.settings())
        assertEquals(emptyList(), a.routes())
    }

    @Test
    fun gapsAreSkippedAndFilledLater() {
        val a = store(A)
        val b = store(B)
        a.create(T1, "t", 1)
        val first = a.bundle()
        pts.forEach { a.append(T1, it, now = it.time!!) }
        val second = a.bundle(first.vector) // only the appends, relative to `first`
        val r = b.apply(second, 0)
        assertEquals(listOf(A), r.gapDevices)
        assertEquals(0, r.applied)
        assertNull(b.summary(T1))
        b.apply(first, 0)
        b.apply(second, 0)
        assertEquals(a.snapshot(), b.snapshot())
        // Replays and duplicates change nothing.
        val again = b.apply(second, 0)
        assertEquals(0, again.applied)
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun anyArrivalOrderGivesTheSameState() {
        val r = Random(9)
        val stores = listOf(store(A), store(B), store(C))
        // Concurrent work on three peers with occasional syncs.
        for (step in 0 until 300) {
            val s = stores[r.nextInt(3)]
            val tracks = s.list()
            when (r.nextInt(6)) {
                0 -> s.create("10000000-0000-4000-8000-${(1000 + step).toString().padStart(12, '0')}", "t$step", step.toLong())
                1, 2 -> tracks.randomOrNull(r)?.let { s.append(it.id, pts[step % pts.size].copy(time = step * 1000L), newSegment = r.nextInt(5) == 0, now = step.toLong()) }
                3 -> tracks.randomOrNull(r)?.let { s.rename(it.id, "n$step", step.toLong()) }
                4 -> tracks.randomOrNull(r)?.let { if (r.nextInt(4) == 0) s.delete(it.id) }
                else -> { val x = stores[r.nextInt(3)]; if (x !== s) syncPair(s, x, step.toLong()) }
            }
        }
        val all = LinkedHashMap<Hlc, Op>()
        stores.forEach { s -> s.opsSince(VersionVector.EMPTY).forEach { all[it.hlc] = it } }
        val byDevice = all.values.groupBy { it.device }.mapValues { (_, o) -> o.sortedBy { it.hlc } }
        // Fresh replicas fed the same operations in different chunkings and device interleavings
        // (each device's operations in prefix order, as the protocol guarantees), with replays.
        val snapshots = List(5) { i ->
            val rr = Random(100 + i)
            val fresh = store("ffffffff-0000-4000-8000-00000000000$i")
            val pos = byDevice.keys.associateWith { 0 }.toMutableMap()
            while (true) {
                val open = pos.filter { (d, p) -> p < byDevice.getValue(d).size }.keys.toList()
                if (open.isEmpty()) break
                val d = open[rr.nextInt(open.size)]
                val list = byDevice.getValue(d)
                val from = pos.getValue(d)
                val chunk = list.subList(from, minOf(list.size, from + 1 + rr.nextInt(25)))
                val base = if (from == 0) VersionVector.EMPTY else VersionVector(mapOf(d to list[from - 1].hlc))
                val bundle = Bundle("x", base, VersionVector(mapOf(d to chunk.last().hlc)), chunk.shuffled(rr))
                fresh.apply(bundle, 0)
                if (rr.nextInt(4) == 0) fresh.apply(bundle, 0)
                pos[d] = from + chunk.size
            }
            fresh.snapshot()
        }
        snapshots.forEach { assertEquals(snapshots[0], it) }
        stores.forEach { s -> stores.forEach { o -> if (s !== o) syncPair(s, o) } }
        stores.forEach { assertEquals(snapshots[0], it.snapshot()) }
    }

    @Test
    fun compactionDropsOnlyExpiredTombstones() {
        val a = store(A)
        a.create(T1, "t", 1_000)
        a.delete(T1)
        a.create("10000000-0000-4000-8000-000000000002", "u", 10_000_000)
        val ops = a.opCount
        assertEquals(CompactResult(0, 0), a.compact(now = 2_000, retentionMs = 10_000))
        assertEquals(CompactResult(1, 0), a.compact(now = 100_000, retentionMs = 10_000))
        assertEquals(ops - 1, a.opCount)
        assertNotNull(a.summary("10000000-0000-4000-8000-000000000002"))
    }
}

class TransportTest {
    private val identity: (ByteArray) -> ByteArray = { it }

    @Test
    fun fileBundlePassedBackAndForthSyncsBothWays() {
        var file: ByteArray? = null
        val transport = FileSyncTransport("test.dsync", { file }, { file = it }, identity, identity)
        val a = InMemoryTrackStore(A)
        val b = InMemoryTrackStore(B)
        a.create(T1, "From A", 10)
        a.append(T1, TrackPoint(1.0, 2.0, 3.0, 4), now = 11)
        val r1 = Sync.exchange(a, transport, { 20 })
        assertEquals(0, r1.pulled)
        assertEquals(2, r1.pushed)
        Sync.exchange(b, transport, { 30 })
        assertEquals(a.snapshot(), b.snapshot())
        b.rename(T1, "Renamed on B", 40)
        Sync.exchange(b, transport, { 41 })
        Sync.exchange(a, transport, { 50 })
        assertEquals("Renamed on B", a.summary(T1)!!.name)
        assertEquals(TrackExport.gpx(a, T1), TrackExport.gpx(b, T1))
    }

    @Test
    fun httpTransportAgainstSyncServer() {
        val server = InMemoryTrackStore(C)
        val handler = SyncServer(server, { 0 }, identity, identity)
        val post: (String, ByteArray) -> ByteArray = { url, body ->
            val path = url.substringAfter("http://peer").substringBefore('?')
            val query = url.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val reply = handler.handle("POST", path, query, body)!!
            check(reply.status == 200) { reply.body.decodeToString() }
            reply.body
        }
        val a = InMemoryTrackStore(A)
        val b = InMemoryTrackStore(B)
        a.create(T1, "Track", 1)
        a.addWaypoint(T1, Waypoint(W1, "w", 1.0, 2.0), 2)
        val ra = Sync.exchange(a, HttpSyncTransport("http://peer", post, identity, identity), { 3 })
        assertEquals(2, ra.pushed)
        assertEquals(a.vector(), ra.peerVector)
        val rb = Sync.exchange(b, HttpSyncTransport("http://peer", post, identity, identity), { 4 })
        assertEquals(2, rb.pulled)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.snapshot(), server.snapshot())
        val status = handler.handle("GET", "/sync/status", emptyMap(), ByteArray(0))!!
        assertTrue(status.body.decodeToString().contains("\"tracks\":1"))
        assertEquals(400, handler.handle("POST", "/sync/push", emptyMap(), "junk".encodeToByteArray())!!.status)
        assertNull(handler.handle("GET", "/tracks", emptyMap(), ByteArray(0)))
    }
}

class SimulatorTest {
    @Test
    fun convergesWithPartitionsAndReordering() {
        for (seed in 1L..4L) {
            val r = SyncSimulator(SyncSimulator.Config(peers = 3, ops = 400, seed = seed, partitions = 3, reorder = true)).run()
            assertTrue(r.converged, "seed $seed: ${r.json["firstDivergence"]} ${r.json["errors"]}")
        }
    }

    @Test
    fun sameSeedSameRun() {
        val c = SyncSimulator.Config(peers = 4, ops = 300, seed = 7, partitions = 2, reorder = true)
        val a = SyncSimulator(c).run().json - "elapsedMs"
        val b = SyncSimulator(c).run().json - "elapsedMs"
        assertEquals(a, b)
        assertEquals("sync-sim --peers 4 --ops 300 --seed 7 --partitions 2 --reorder", a["replay"])
    }
}
