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
import kotlin.test.assertTrue
import kotlin.test.fail

private const val DA = "00000000-0000-4000-8000-0000000000a1"
private const val DB = "00000000-0000-4000-8000-0000000000b1"
private const val TR = "10000000-0000-4000-8000-0000000000f1"
private const val WP = "20000000-0000-4000-8000-0000000000f1"
private const val DAY = 24L * 3600 * 1000
private const val NOW = 1_700_000_000_000L

private fun fix(i: Int) = TrackPoint(46.85 + i * 1e-5, -121.76 + i * 1e-5, 1600.0 + i, NOW + i * 1000L)

/** T51: bundle parsing never throws anything but [SyncFormatException], whatever the bytes. */
class BundleFuzzTest {
    private fun sample(): Bundle {
        val bodies = listOf(
            PutTrack(TR, NOW, "Skyline loop", "d", null, "src", "hike", "<x:y>1</x:y>"),
            PutWaypoint(TR, Waypoint(WP, "Camp Muir", 46.83, -121.73, 3100.0, NOW, "hut", null, "Summit", null, null)),
            PutRoute(Route(TR, "R", listOf(RoutePoint(1.0, 2.0), RoutePoint(3.0, 4.0, 5.0, 6, "n", "d", "s", "e")))),
            PutSetting("units", "m"),
            SetField(EntityKind.TRACK, TR, "name", "Renamed"),
            AppendPoints(TR, 2, List(5) { fix(it) }),
            Delete(EntityKind.ROUTE, "route-id"),
        )
        val ops = bodies.mapIndexed { i, b -> Op(Hlc(NOW + i, i, if (i % 2 == 0) DA else DB), b) }
        val vector = VersionVector(ops.groupBy { it.device }.mapValues { (_, l) -> l.maxOf { it.hlc } })
        return Bundle(DA, VersionVector(mapOf(DB to Hlc(NOW - 5, 0, DB))), vector, ops)
    }

    private fun decodeOrFormatError(bytes: ByteArray): Bundle? = try {
        Bundle.decode(bytes)
    } catch (e: SyncFormatException) {
        null
    } catch (e: Throwable) {
        fail("${e::class.simpleName} instead of SyncFormatException: ${e.message}")
    }

    @Test
    fun everyTruncationIsAFormatError() {
        for (b in listOf(sample(), Bundle(DA, VersionVector.EMPTY, sample().vector, sample().ops, more = true))) {
            val bytes = b.encode()
            for (n in 0 until bytes.size) {
                assertFailsWith<SyncFormatException>("prefix of $n bytes") { Bundle.decode(bytes.copyOf(n)) }
            }
            assertEquals(b.ops, Bundle.decode(bytes).ops)
        }
    }

    @Test
    fun randomBytesAndBitFlipsGiveOneExceptionType() {
        val rnd = Random(51)
        val good = sample().encode()
        var decoded = 0
        repeat(5000) {
            val bytes = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte() }
            if (decodeOrFormatError(bytes) != null) decoded++
        }
        repeat(3000) {
            // Random tails after a valid header, both versions.
            val tail = rnd.nextBytes(rnd.nextInt(0, 200))
            val bytes = "DSYNC".encodeToByteArray() + byteArrayOf((1 + rnd.nextInt(2)).toByte()) + tail
            decodeOrFormatError(bytes)
        }
        repeat(1000) { decodeOrFormatError(rnd.nextBytes(rnd.nextInt(0, 64))) }
        assertTrue(decoded > 0, "some single-byte changes still decode (inside strings and doubles)")
    }

    @Test
    fun opsVectorsAndHlcTextFailTheSameWay() {
        assertFailsWith<SyncFormatException> { OpCodec.decode(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte())) }
        assertFailsWith<SyncFormatException> { Hlc.parse("zzzzzzzzzzzz.000000.$DA") }
        assertFailsWith<SyncFormatException> { Hlc.parse("short") }
        assertFailsWith<SyncFormatException> { VersionVector.parse("000000000001.000000.$DA,junk") }
        // An HLC with a wall past 48 bits, written by hand: a format error, not an IllegalArgumentException from Hlc.
        val w = ByteWriter()
        w.uvarint(1L shl 50); w.uvarint(0); w.id(DA); w.byte(TYPE_DELETE); w.byte(EntityKind.TRACK.code); w.id(TR)
        assertFailsWith<SyncFormatException> { OpCodec.decode(w.toByteArray()) }
    }

    @Test
    fun unpagedBundlesStayVersionOne() {
        assertEquals(Bundle.VERSION, sample().encode()[5].toInt())
        val paged = Bundle(DA, VersionVector.EMPTY, VersionVector.EMPTY, emptyList(), more = true)
        assertEquals(Bundle.VERSION_PAGED, paged.encode()[5].toInt())
        assertTrue(Bundle.decode(paged.encode()).more)
    }
}

/** T51: the clock-skew guard and the entity-level receive rule. */
class SkewGuardTest {
    @Test
    fun aFarFutureOperationIsMergedButDoesNotMoveTheClock() {
        var nowA = NOW + 2 * DAY // A's clock is two days ahead
        val a = InMemoryTrackStore(DA) { nowA }
        val b = InMemoryTrackStore(DB) { NOW }
        a.create(TR, "From the future", nowA)
        val r = b.apply(a.bundle(b.vector()), NOW)
        assertEquals(1, r.clamped)
        assertTrue(r.maxAheadMs >= 2 * DAY - 1)
        assertEquals("From the future", b.summary(TR)!!.name, "merged all the same")
        assertEquals(1, b.skewLog.clamped)
        assertEquals(DA, b.skewLog.lastDevice)
        // B's own changes elsewhere keep B's time.
        b.setSetting("units", "m", NOW)
        assertTrue(b.vector()[DB].wall < NOW + 1000, "the clock was not pinned: ${b.vector()[DB]}")
        // A change to the entity A touched is stamped after A's operation (B saw it), so it wins.
        b.rename(TR, "Renamed on B", NOW)
        assertEquals("Renamed on B", b.summary(TR)!!.name)
        syncPair(a, b, NOW)
        assertEquals("Renamed on B", a.summary(TR)!!.name)
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun withinADayTheReceiveRuleIsUnchanged() {
        val a = InMemoryTrackStore(DA) { NOW + 3_600_000 }
        val b = InMemoryTrackStore(DB) { NOW }
        a.create(TR, "x", NOW + 3_600_000)
        val r = b.apply(a.bundle(b.vector()), NOW)
        assertEquals(0, r.clamped)
        b.setSetting("units", "m", NOW)
        assertTrue(b.vector()[DB] > a.vector()[DA], "observed: B now stamps after A")
    }

    @Test
    fun aFullCounterMovesTheWallInsteadOfFailing() {
        val c = HlcClock(DA, Hlc(100, Hlc.MAX_COUNTER, DA))
        assertEquals(Hlc(101, 0, DA), c.tick(50))
    }

    @Test
    fun skewLogRoundTrips() {
        val s = SkewLog().plus(3, 5 * DAY, "dev,ice", NOW)
        assertEquals(SkewLog(3, 5 * DAY, "dev_ice", NOW), SkewLog.decode(s.encode()))
        assertEquals(SkewLog(), SkewLog.decode("garbage"))
    }
}

/** T51: recorded fixes merge into runs while unshipped. */
class RunMergingTest {
    @Test
    fun fixesGrowARunUntilShippedOrFull() {
        val a = InMemoryTrackStore(DA) { NOW }
        a.create(TR, "rec", NOW, recording = true)
        repeat(100) { a.append(TR, fix(it), now = NOW + it * 1000L) }
        // 1 put + runs of 64 + 36 points.
        assertEquals(3, a.opCount)
        assertEquals(98, a.runsExtended)
        assertEquals(100, a.summary(TR)!!.points)
        // Shipping freezes the run: the next fix starts a new operation.
        val b = InMemoryTrackStore(DB) { NOW }
        syncPair(a, b, NOW)
        a.append(TR, fix(100), now = NOW + 100_000)
        a.append(TR, fix(101), now = NOW + 101_000)
        assertEquals(4, a.opCount)
        syncPair(a, b, NOW)
        assertEquals(102, b.summary(TR)!!.points)
        assertEquals(TrackExport.gpx(a, TR), TrackExport.gpx(b, TR))
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun aRunDoesNotGrowPastAnotherDevicesAppend() {
        val a = InMemoryTrackStore(DA) { NOW }
        val b = InMemoryTrackStore(DB) { NOW }
        a.create(TR, "shared", NOW, recording = true)
        syncPair(a, b, NOW)
        a.append(TR, fix(0), now = NOW + 1000)
        b.append(TR, fix(50), now = NOW + 1500)
        // B's append reaches A without A shipping anything since its own fix.
        a.apply(b.bundle(VersionVector(mapOf(DA to a.vector()[DA]))), NOW + 2000)
        a.append(TR, fix(1), now = NOW + 3000) // must not extend the run that is no longer the newest on the track
        syncPair(a, b, NOW + 4000)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(3, a.summary(TR)!!.points)
    }

    @Test
    fun runsMakeTheLogSmaller() {
        val a = InMemoryTrackStore(DA) { NOW }
        a.create(TR, "rec", NOW, recording = true)
        repeat(640) { a.append(TR, fix(it), now = NOW + it * 1000L) }
        val bytes = a.bundle().encode().size
        // T44 wrote 76 bytes per fix; a run carries a fix in its 33 point bytes plus a share of one header.
        assertTrue(bytes < 640 * 40, "$bytes bytes for 640 fixes")
    }
}

/** T51: concurrent field writes are reported, the same way on both sides. */
class ConflictTest {
    @Test
    fun concurrentRenamesAreReportedOnBothPeers() {
        val a = InMemoryTrackStore(DA) { NOW }
        val b = InMemoryTrackStore(DB) { NOW }
        a.create(TR, "Skyline", NOW)
        syncPair(a, b, NOW)
        a.rename(TR, "Skyline loop", NOW + 1000)
        b.rename(TR, "Paradise loop", NOW + 2000)
        val toB = b.apply(a.bundle(b.vector()), NOW + 3000)
        val toA = a.apply(b.bundle(a.vector()), NOW + 3000)
        for (r in listOf(toA, toB)) {
            val c = r.conflicts.single()
            assertEquals("name", c.field)
            assertEquals("Paradise loop", c.kept)
            assertEquals("Skyline loop", c.lost)
            assertEquals(DB, c.keptDevice)
        }
        // B merged first and saw two writes that did not know each other; A learns its value was replaced.
        assertTrue(toB.conflicts.single().concurrent)
        assertFalse(toA.conflicts.single().concurrent)
        assertEquals("Paradise loop", a.summary(TR)!!.name)
    }

    @Test
    fun aRenameThatSawTheOtherIsReportedOnlyWhereItReplacedALocalValue() {
        val a = InMemoryTrackStore(DA) { NOW }
        val b = InMemoryTrackStore(DB) { NOW }
        val c = InMemoryTrackStore("00000000-0000-4000-8000-0000000000c1") { NOW }
        a.create(TR, "Skyline", NOW)
        a.rename(TR, "Skyline loop", NOW + 1000)
        syncPair(a, b, NOW)
        syncPair(a, c, NOW)
        b.rename(TR, "Paradise loop", NOW + 2000)
        val onA = a.apply(b.bundle(a.vector()), NOW + 3000).conflicts.single()
        assertFalse(onA.concurrent)
        assertEquals("Skyline loop", onA.lost)
        assertTrue(c.apply(b.bundle(c.vector()), NOW + 3000).conflicts.isEmpty(), "C had no value of its own there")
    }

    @Test
    fun theSameValueOnTwoDevicesIsNotAConflict() {
        val a = InMemoryTrackStore(DA) { NOW }
        val b = InMemoryTrackStore(DB) { NOW }
        a.setSetting("units", "m", NOW)
        b.setSetting("units", "m", NOW + 5)
        assertTrue(a.apply(b.bundle(a.vector()), NOW).conflicts.isEmpty())
        b.setSetting("textScale", "1.2", NOW + 10)
        a.setSetting("textScale", "1.4", NOW + 20)
        val c = b.apply(a.bundle(b.vector()), NOW).conflicts.single()
        assertEquals(EntityKind.SETTING, c.kind)
        assertEquals("1.4", c.kept)
    }
}

/** T51: paged pulls and pushes, over the in-process HTTP peer. */
class PagingTest {
    private fun gzipless(b: ByteArray) = b

    private fun filled(device: String, tracks: Int, clock: Long): InMemoryTrackStore {
        val s = InMemoryTrackStore(device) { clock }
        repeat(tracks) { t ->
            val id = "30000000-0000-4000-8000-${(t + 1).toString().padStart(12, '0')}".let { if (device == DA) it else it.replaceFirst('3', '4') }
            s.insert(Track(id, "T$t", listOf(TrackSegment(List(3) { fix(it) }), TrackSegment(List(2) { fix(it + 10) }))), emptyList(), NOW, clock)
            s.rename(id, "Track $t", clock)
        }
        return s
    }

    @Test
    fun pagesCoverEverythingAndContinueFromTheVector() {
        val a = filled(DA, 40, NOW) // 40 x (put + 2 appends + rename) = 160 ops
        val b = InMemoryTrackStore(DB) { NOW }
        var since = b.vector()
        var pages = 0
        while (true) {
            val page = a.page(since, 25)
            assertTrue(page.ops.size <= 25)
            b.apply(Bundle.decode(page.encode()), NOW)
            pages++
            since = b.vector()
            if (!page.more) {
                assertEquals(a.vector(), page.vector, "the last page carries the full vector")
                break
            }
        }
        assertEquals(7, pages)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.vector(), b.vector())
    }

    @Test
    fun byteBudgetLimitsAPage() {
        val a = filled(DA, 10, NOW)
        val page = a.page(VersionVector.EMPTY, 1000, maxBytes = 300)
        assertTrue(page.more)
        assertTrue(page.ops.size in 1..6, "${page.ops.size}")
        assertTrue(a.page(VersionVector.EMPTY, 1000, maxBytes = 1).ops.size == 1, "always at least one")
    }

    @Test
    fun exchangeInPagesBothWaysThroughTheServer() {
        val server = filled(DA, 30, NOW)
        val phone = filled(DB, 20, NOW)
        val handler = SyncServer(server, { NOW }, ::gzipless, ::gzipless)
        val transport = HttpSyncTransport("http://peer", { url, body ->
            val path = url.removePrefix("http://peer").substringBefore('?')
            val query = url.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val r = handler.handle("POST", path, query, body)!!
            check(r.status == 200) { r.body.decodeToString() }
            r.body
        }, ::gzipless, ::gzipless)
        val report = Sync.exchange(phone, transport, { NOW }, pageOps = 16)
        assertEquals(120, report.pulled)
        assertEquals(8, report.pullPages)
        assertEquals(80, report.pushed)
        assertEquals(5, report.pushPages)
        assertEquals(server.snapshot(), phone.snapshot())
        assertEquals(server.vector(), phone.vector())
        assertEquals(0, phone.pendingFor(report.peerVector!!))
        assertTrue(handler.partialPages >= 7)
        // An unpaged request to the same server still gets one whole bundle.
        val again = Sync.exchange(InMemoryTrackStore("fresh") { NOW }, transport, { NOW })
        assertEquals(1, again.pullPages)
        assertEquals(server.opCount.toInt(), again.pulled)
    }

    @Test
    fun aBadPageSizeIsA400() {
        val handler = SyncServer(InMemoryTrackStore(DA), { NOW }, ::gzipless, ::gzipless)
        assertEquals(400, handler.handle("POST", "/sync/pull", mapOf("limit" to "0"), ByteArray(0))!!.status)
        assertEquals(400, handler.handle("POST", "/sync/pull", mapOf("limit" to "x"), ByteArray(0))!!.status)
        assertEquals(400, handler.handle("POST", "/sync/pull", mapOf("since" to "nonsense"), ByteArray(0))!!.status)
    }
}

/** T51: a crash-restarted replica comes back with the same state and a clock past its own operations. */
class RestartTest {
    @Test
    fun restartKeepsTheLogAndTheClock() {
        var now = NOW
        val a = InMemoryTrackStore(DA) { now }
        a.create(TR, "rec", now, recording = true)
        repeat(10) { a.append(TR, fix(it), now = now + it) }
        a.addWaypoint(TR, Waypoint(WP, "Camp", 46.8, -121.7, null, now), now)
        val before = a.snapshot()
        val last = a.vector()[DA]
        now -= 60_000 // the clock went back across the restart
        val b = a.restarted()
        assertEquals(before, b.snapshot())
        b.rename(TR, "after restart", now)
        assertTrue(b.vector()[DA] > last)
        assertFalse(b.summary(TR)!!.recording, "recording is local state; a crash ends it")
    }

    @Test
    fun simulatorConvergesWithSkewCrashesAndPages() {
        for (seed in 1L..3L) {
            for (skew in listOf(2 * DAY, -2 * DAY)) {
                val c = SyncSimulator.Config(peers = 3, ops = 400, seed = seed, partitions = 3, reorder = true, bigSkewMs = skew, crashes = 2, pageOps = if (seed == 2L) 20 else 0)
                val r = SyncSimulator(c).run()
                assertTrue(r.converged, "${c.commandLine()}: ${r.json["firstDivergence"]} ${r.json["errors"]}")
                @Suppress("UNCHECKED_CAST") val merge = r.json["merge"] as Map<String, Any?>
                assertTrue((merge["clamped"] as Long) > 0, "the guard saw the skewed peer")
                assertEquals(2, r.json["crashes"])
            }
        }
    }
}
