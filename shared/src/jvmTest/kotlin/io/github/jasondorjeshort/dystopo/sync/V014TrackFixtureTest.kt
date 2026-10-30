package io.github.jasondorjeshort.dystopo.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T158 (ADR 0033 Decisions 2 and 3): data written by v0.1.14-alpha keeps working after the format change. The
 * fixtures were written by the v0.1.14 store, recorder and codec (unchanged at the tag) recording the public
 * Skyline loop at Paradise: `store/v0.1.14-tracks.db` (schema 5: a finished track of 90 points in two segments
 * with a waypoint and a rename, a free waypoint, a setting, and a second track left "recording" by a process
 * death), `sync/v0.1.14-tracks.dsync` (its bundle) and `gpx/v0.1.14-export.gpx` (the finished track's export).
 */
class V014TrackFixtureTest {
    private val drivers = ArrayList<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private fun bytes(name: String): ByteArray = javaClass.getResourceAsStream(name)!!.use { it.readBytes() }
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readBytes() }

    private val done = "2caa097c-50d3-4633-9c6b-bd57366763e5"
    private val crashed = "3f7b289f-af57-4b31-a96e-ee8d893635c7"
    private val export014 get() = bytes("/gpx/v0.1.14-export.gpx").decodeToString()

    private fun sql(url: String = JdbcSqliteDriver.IN_MEMORY, device: String = "00000000-0000-4000-8000-0000000000e1"): SqlTrackStore {
        val d = JdbcSqliteDriver(url, Properties(), TrackDb.Schema).also { drivers += it }
        return SqlTrackStore(d, { 1_800_000_000_000 }) { device }
    }

    @Test
    fun theNewReaderReadsAV014Bundle() {
        val raw = gunzip(bytes("/sync/v0.1.14-tracks.dsync"))
        val bundle = Bundle.decode(raw)
        assertContentEquals(raw, bundle.encode(), "decodes and re-encodes byte for byte")
        assertEquals(bundle.ops, V014Codec.decodeBundle(raw).ops, "the fixture is the old format")
        for (store in listOf(sql(), InMemoryTrackStore("00000000-0000-4000-8000-0000000000e2"))) {
            val r = store.apply(bundle, 1_800_000_000_000)
            assertEquals(bundle.ops.size, r.applied)
            assertEquals(listOf("Skyline loop (v0.1.14)", "Interrupted"), store.list().map { it.name })
            assertEquals(90, store.summary(done)!!.points)
            assertEquals(export014, TrackExport.gpx(store, done), "exports what v0.1.14 exported")
            val t = store.track(done)!!
            assertNull(t.activity)
            assertEquals(emptyList(), t.gaps)
            assertTrue(t.points.none { it.hasFixFields })
            assertEquals(mapOf("units" to "metric"), store.settings())
        }
    }

    @Test
    fun aV014GpxImportsUnchanged() {
        val store = sql()
        val r = TrackImport.import(store, export014, now = 1)
        assertEquals(listOf(done), r.imported.map { it.id })
        assertEquals(export014, TrackExport.gpx(store, done))
        assertTrue(store.track(done)!!.points.none { it.hasFixFields })
    }

    /** `TrackDbMigrationTest` checks the schema; this upgrades the v0.1.14 database with its rows, as the phone does on first open. */
    @Test
    fun aV014DatabaseUpgradesAndResumesItsInterruptedTrack() {
        val dir = Files.createTempDirectory("dystopo-v014").toFile()
        try {
            val f = java.io.File(dir, "tracks.db")
            f.writeBytes(bytes("/store/v0.1.14-tracks.db"))
            val url = "jdbc:sqlite:" + f.absolutePath.replace('\\', '/')
            DriverManager.getConnection(url).use { c -> c.createStatement().executeQuery("PRAGMA user_version").use { it.next(); assertEquals(5, it.getLong(1)) } }
            val store = sql(url)
            assertEquals(2, store.tracksRefreshed, "every track row rewritten from the log once")
            assertEquals(listOf(done to false, crashed to true), store.list().map { it.id to it.recording })
            assertEquals(90, store.summary(done)!!.points)
            assertEquals(export014, TrackExport.gpx(store, done))
            assertEquals(store.snapshotFromLog(), store.snapshot())
            // The track left recording by v0.1.14 resumes after a "stopped by the system" gap (T158).
            val last = store.track(crashed)!!.points.last()
            val rec = TrackRecorder(store, clock = { 1_800_000_000_000 })
            assertEquals(listOf(crashed), rec.recoverInterrupted())
            assertEquals(crashed, rec.activeId)
            assertTrue(rec.onFix(LocationFix(last.lat, last.lon, last.ele, last.time!! + 120_000, 7.5, speedMps = 1.1)))
            assertEquals(listOf(TrackGap(last.time!!, last.time!! + 120_000, GapReason.STOPPED_BY_SYSTEM)), store.gaps(crashed))
            drivers.last().close()
            val again = sql(url)
            assertEquals(0, again.tracksRefreshed, "once")
            assertEquals(7.5, again.track(crashed)!!.points.last().accuracyM)
            assertEquals(1, again.gaps(crashed).size)
            assertFalse(again.track(done)!!.points.any { it.hasFixFields })
            assertEquals(again.snapshotFromLog(), again.snapshot())
            DriverManager.getConnection(url).use { c -> c.createStatement().executeQuery("PRAGMA user_version").use { it.next(); assertEquals(TrackDb.Schema.version, it.getLong(1)) } }
        } finally {
            drivers.forEach { runCatching { it.close() } }
            dir.deleteRecursively()
        }
    }

    /** A newer peer's `activity` write that a v0.1.14 phone held in its log shows up after the update (the one refresh). */
    @Test
    fun anActivityHeldByAnOldLogShowsAfterTheUpgrade() {
        val dir = Files.createTempDirectory("dystopo-v014b").toFile()
        try {
            val f = java.io.File(dir, "tracks.db")
            f.writeBytes(bytes("/store/v0.1.14-tracks.db"))
            val url = "jdbc:sqlite:" + f.absolutePath.replace('\\', '/')
            // As v0.1.14 would have stored it: one more op row in sync_op, the table unchanged.
            val op = Op(Hlc(1_790_000_500_000L, 0, "00000000-0000-4000-8000-0000000000e9"), SetField(EntityKind.TRACK, done, "activity", "bike"))
            DriverManager.getConnection(url).use { c ->
                c.prepareStatement("INSERT INTO sync_op(hlc, device, type, kind, entity, parent, digest, body) VALUES (?, ?, 2, 1, ?, NULL, NULL, ?)").use { st ->
                    st.setString(1, op.hlc.text); st.setString(2, op.device); st.setString(3, done); st.setBytes(4, OpCodec.encode(op))
                    st.executeUpdate()
                }
                c.createStatement().use { it.executeUpdate("INSERT INTO sync_vector VALUES ('${op.device}', '${op.hlc.text}')") }
            }
            val store = sql(url)
            assertEquals(io.github.jasondorjeshort.dystopo.model.Activity.BIKE, store.track(done)!!.activity)
            assertEquals(store.snapshotFromLog(), store.snapshot())
        } finally {
            drivers.forEach { runCatching { it.close() } }
            dir.deleteRecursively()
        }
    }
}
