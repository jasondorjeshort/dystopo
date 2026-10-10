package io.github.jasondorjeshort.dystopo.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SQLite store as a sync replica: same merge as the in-memory reference, real gzip bundles. */
class SqlSyncTest {
    private val drivers = ArrayList<JdbcSqliteDriver>()

    private fun sql(device: String? = null, url: String = JdbcSqliteDriver.IN_MEMORY, clock: () -> Long = { 0L }): SqlTrackStore {
        val d = JdbcSqliteDriver(url, Properties(), TrackDb.Schema).also { drivers += it }
        return SqlTrackStore(d, clock, newDeviceId = if (device == null) ({ UuidGenerator().next() }) else ({ UuidGenerator(Random(device.hashCode())).next() }))
    }

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
    private val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readAllBytes() } }

    private val skyline: String = javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }

    /** Simulator peers over in-memory SQLite; a restart (T51) reopens the same database with a new store. */
    private inner class SqlPeers(val mixed: Boolean = false) {
        val stores = ArrayList<SyncStore>()
        private val opened = java.util.IdentityHashMap<SyncStore, Pair<JdbcSqliteDriver, () -> Long>>()

        fun open(device: String, clock: () -> Long): SyncStore {
            // Mixed peers: SQLite and the in-memory reference must agree with each other too.
            if (mixed && stores.size % 3 == 2) return InMemoryTrackStore(device, clock).also { stores += it }
            val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).also { drivers += it }
            return SqlTrackStore(d, clock) { device }.also { stores += it; opened[it] = d to clock }
        }

        fun restart(old: SyncStore): SyncStore {
            if (old is InMemoryTrackStore) return old.restarted().also { stores[stores.indexOf(old)] = it }
            val (d, clock) = opened.remove(old)!!
            return SqlTrackStore(d, clock) { old.deviceId }.also { stores[stores.indexOf(old)] = it; opened[it] = d to clock }
        }

        fun sim(config: SyncSimulator.Config) = SyncSimulator(config, ::open, ::restart)
    }

    @Test
    fun simulatorConvergesOnSqlStoresAndTablesMatchTheLog() {
        val day = 86_400_000L
        for ((seed, skew) in listOf(3L to 0L, 11L to 0L, 12L to 2 * day, 13L to -2 * day)) {
            val peers = SqlPeers(mixed = true)
            val r = peers.sim(SyncSimulator.Config(peers = 3, ops = 400, seed = seed, partitions = 3, reorder = true, bigSkewMs = skew, crashes = if (skew != 0L) 2 else 0)).run()
            assertTrue(r.converged, "seed $seed: ${r.json["firstDivergence"]} ${r.json["errors"]}")
            for (s in peers.stores.filterIsInstance<SqlTrackStore>()) assertEquals(s.snapshotFromLog(), s.snapshot(), "tables are the log's view")
        }
    }

    @Test
    fun sameSeedSameRunOnSql() {
        for (config in listOf(
            SyncSimulator.Config(peers = 3, ops = 200, seed = 5, partitions = 2, reorder = true),
            // T51: skew, crash-restarts and pages too.
            SyncSimulator.Config(peers = 3, ops = 300, seed = 6, partitions = 2, reorder = true, bigSkewMs = 2 * 86_400_000L, crashes = 2, pageOps = 15),
        )) {
            fun run() = SqlPeers().sim(config).run().json - "elapsedMs"
            val a = run()
            assertEquals(a, run())
            // The simulator draws its next change from what the stores show, so equal reports mean SQLite and
            // the in-memory reference behaved identically at every step.
            assertEquals(a, SyncSimulator(config).run().json - "elapsedMs")
        }
    }

    @Test
    fun pagesFromSqlMatchTheReference() {
        val a = sql("a", clock = { 1_000L })
        val m = InMemoryTrackStore(a.deviceId) { 1_000L }
        for (s in listOf<SyncStore>(a, m)) {
            val track = "10000000-0000-4000-8000-000000000009"
            s.create(track, "Skyline", 1_000)
            repeat(12) { s.addWaypoint(track, Waypoint("wp-$it", "Mark $it", 46.8, -121.7, null, 1_000L + it), 1_000L + it) }
            repeat(30) { s.setSetting("k$it", "v$it", 1_000L + it) }
        }
        var since = VersionVector.EMPTY
        while (true) {
            val p = a.page(since, 7)
            val q = m.page(since, 7)
            assertEquals(q.ops, p.ops)
            assertEquals(q.vector, p.vector)
            assertEquals(q.more, p.more)
            if (!p.more) break
            since = p.vector
        }
        assertEquals(a.opCount, a.pendingFor(VersionVector.EMPTY))
        assertEquals(0L, a.pendingFor(a.vector()))
    }

    @Test
    fun aCorruptGzipStreamIsAFormatError() {
        val good = Sync.pack(sql("z").also { it.setSetting("units", "m", 1) }.bundle(), gzip)
        // A gzip header followed by garbage (a broken deflate stream).
        val garbage = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0) + ByteArray(40) { (it * 37 + 11).toByte() }
        kotlin.test.assertFailsWith<SyncFormatException> { Sync.unpack(garbage, gunzip) }
        // A valid stream whose stored CRC-32 is wrong.
        val badCrc = good.copyOf().also { it[it.size - 8] = (it[it.size - 8] + 1).toByte() }
        kotlin.test.assertFailsWith<SyncFormatException> { Sync.unpack(badCrc, gunzip) }
        // Valid gzip of bytes that are not a bundle.
        kotlin.test.assertFailsWith<SyncFormatException> { Sync.unpack(gzip("not a bundle".encodeToByteArray()), gunzip) }
        // Cut inside the deflate data.
        kotlin.test.assertFailsWith<SyncFormatException> { Sync.unpack(good.copyOf(good.size / 2), gunzip) }
    }

    @Test
    fun gzipGarbageIsAFormatError() {
        val good = Sync.pack(sql("g").also { it.setSetting("units", "m", 1) }.bundle(), gzip)
        for (n in 0 until good.size) {
            val cut = good.copyOf(n)
            try {
                Sync.unpack(cut, gunzip)
            } catch (e: SyncFormatException) {
                continue
            }
            if (n >= 5) throw AssertionError("a gzip stream cut at $n bytes decoded")
        }
        val rnd = Random(7)
        repeat(500) {
            val bytes = good.copyOf().also { b -> b[2 + rnd.nextInt(b.size - 2)] = rnd.nextInt(256).toByte() }
            try {
                Sync.unpack(bytes, gunzip)
            } catch (e: SyncFormatException) {
                // the one expected failure type
            }
        }
    }

    @Test
    fun skewLogAndClockSurviveReopen() {
        val dir = Files.createTempDirectory("dystopo-skew")
        try {
            val url = "jdbc:sqlite:" + dir.resolve("tracks.db").toString().replace('\\', '/')
            val now = 1_700_000_000_000L
            val ahead = InMemoryTrackStore("00000000-0000-4000-8000-0000000000aa") { now + 2 * 86_400_000L }
            ahead.setSetting("units", "ft", now + 2 * 86_400_000L)
            val first = sql(url = url, clock = { now })
            assertEquals(1, first.apply(ahead.bundle(), now).clamped)
            drivers.removeLast().close()
            val second = sql(url = url, clock = { now })
            assertEquals(1L, second.skewLog.clamped)
            second.setSetting("textScale", "1.2", now)
            assertTrue(second.vector()[second.deviceId].wall < now + 1000, "reopening does not pin the clock to the far-future entry")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun trackExchangedBothWaysThroughAFileGivesIdenticalGpx() {
        val dir = Files.createTempDirectory("dystopo-sync")
        try {
            val file = dir.resolve("phone.dsync").toFile()
            val transport = FileSyncTransport(file.name, { file.takeIf { it.isFile }?.readBytes() }, { file.writeBytes(it) }, gzip, gunzip)
            val a = sql("a", clock = { 1_700_000_000_000 })
            val b = sql("b", clock = { 1_700_000_100_000 })
            val id = TrackImport.import(a, skyline, now = 1_700_000_000_000).imported.single().id
            Sync.exchange(a, transport, { 1_700_000_000_000 })
            assertEquals(31, file.readBytes().let { it[0].toInt() and 0xff }, "gzip magic")
            Sync.exchange(b, transport, { 1_700_000_100_000 })
            assertEquals(TrackExport.gpx(a, id), TrackExport.gpx(b, id))
            // Back the other way: a waypoint and a rename on B.
            b.addWaypoint(id, Waypoint("30000000-0000-4000-8000-000000000001", "Panorama Point", 46.8015, -121.7268, 2070.0), 1_700_000_200_000)
            b.rename(id, "Skyline (renamed on B)", 1_700_000_200_001)
            Sync.exchange(b, transport, { 1_700_000_200_002 })
            Sync.exchange(a, transport, { 1_700_000_300_000 })
            val ga = TrackExport.gpx(a, id)!!
            assertEquals(ga, TrackExport.gpx(b, id))
            assertTrue("Panorama Point" in ga && "Skyline (renamed on B)" in ga)
            assertEquals(Gpx.parse(skyline).tracks.single().segments, Gpx.parse(ga).tracks.single().segments)
            assertEquals(a.snapshot(), b.snapshot())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentRenameOnTwoSqlPeers() {
        val a = sql("a")
        val b = sql("b")
        val id = a.create("10000000-0000-4000-8000-000000000001", "Original", 1_000).id
        syncPair(a, b)
        a.rename(id, "Renamed on A", 2_000)
        b.rename(id, "Renamed on B", 2_500)
        syncPair(b, a)
        assertEquals("Renamed on B", a.summary(id)!!.name)
        assertEquals("Renamed on B", b.summary(id)!!.name)
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun recordingMergesFixesIntoRunsWithOneRowPerFix() {
        val a = sql("a")
        val id = a.create("10000000-0000-4000-8000-000000000001", "rec", 1, recording = true).id
        val before = a.opCount
        repeat(100) { a.append(id, io.github.jasondorjeshort.dystopo.model.TrackPoint(46.0 + it * 1e-4, -121.0, 1000.0, 1_000L + it * 1000), now = 1_000L + it * 1000) }
        // T51: runs of up to 64 fixes per operation while unshipped.
        assertEquals(before + 2, a.opCount)
        assertEquals(98, a.runsExtended)
        assertEquals(100, a.summary(id)!!.points)
        assertTrue(a.summary(id)!!.recording, "recording is local state, kept")
        assertEquals(a.snapshotFromLog(), a.snapshot())
    }

    @Test
    fun deviceIdIsKeptAcrossReopen() {
        val dir = Files.createTempDirectory("dystopo-device")
        try {
            val url = "jdbc:sqlite:" + dir.resolve("tracks.db").toString().replace('\\', '/')
            val first = sql(url = url)
            val device = first.deviceId
            first.create("10000000-0000-4000-8000-000000000001", "x", 5)
            drivers.removeLast().close()
            val second = sql(url = url)
            assertEquals(device, second.deviceId)
            // The clock resumes after the newest operation.
            second.rename("10000000-0000-4000-8000-000000000001", "y", 0)
            assertEquals("y", second.summary("10000000-0000-4000-8000-000000000001")!!.name)
            assertTrue(second.vector()[device] > Hlc(5, 0, device))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
