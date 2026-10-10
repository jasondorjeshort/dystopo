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
        return SqlTrackStore(d, clock, if (device == null) ({ UuidGenerator().next() }) else ({ UuidGenerator(Random(device.hashCode())).next() }))
    }

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
    private val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readAllBytes() } }

    private val skyline: String = javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }

    @Test
    fun simulatorConvergesOnSqlStoresAndTablesMatchTheLog() {
        for (seed in listOf(3L, 11L)) {
            val stores = ArrayList<SyncStore>()
            val sim = SyncSimulator(SyncSimulator.Config(peers = 3, ops = 400, seed = seed, partitions = 3, reorder = true)) { device, clock ->
                // Mixed peers: SQLite and the in-memory reference must agree with each other too.
                (if (stores.size % 3 == 2) InMemoryTrackStore(device, clock) else SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).also { drivers += it }, clock) { device })
                    .also { stores += it }
            }
            val r = sim.run()
            assertTrue(r.converged, "seed $seed: ${r.json["firstDivergence"]} ${r.json["errors"]}")
            for (s in stores.filterIsInstance<SqlTrackStore>()) assertEquals(s.snapshotFromLog(), s.snapshot(), "tables are the log's view")
        }
    }

    @Test
    fun sameSeedSameRunOnSql() {
        fun run() = SyncSimulator(SyncSimulator.Config(peers = 3, ops = 200, seed = 5, partitions = 2, reorder = true)) { d, c ->
            SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).also { drivers += it }, c) { d }
        }.run().json - "elapsedMs"
        val a = run()
        assertEquals(a, run())
        // The simulator draws its next change from what the stores show, so equal reports mean SQLite and
        // the in-memory reference behaved identically at every step.
        assertEquals(a, SyncSimulator(SyncSimulator.Config(peers = 3, ops = 200, seed = 5, partitions = 2, reorder = true)).run().json - "elapsedMs")
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
    fun recordingIsOneOpAndOneRowPerFix() {
        val a = sql("a")
        val id = a.create("10000000-0000-4000-8000-000000000001", "rec", 1, recording = true).id
        val before = a.opCount
        repeat(100) { a.append(id, io.github.jasondorjeshort.dystopo.model.TrackPoint(46.0 + it * 1e-4, -121.0, 1000.0, 1_000L + it * 1000), now = 1_000L + it * 1000) }
        assertEquals(before + 100, a.opCount)
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
