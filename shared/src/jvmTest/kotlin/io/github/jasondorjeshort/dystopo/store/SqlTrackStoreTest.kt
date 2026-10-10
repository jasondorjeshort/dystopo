package io.github.jasondorjeshort.dystopo.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [TrackStoreContract] against SQLDelight over JDBC SQLite, plus persistence across a reopen. */
class SqlTrackStoreTest : TrackStoreContract() {
    private val drivers = ArrayList<JdbcSqliteDriver>()

    private fun driver(url: String) = JdbcSqliteDriver(url, Properties(), TrackDb.Schema).also { drivers += it }

    override fun newStore(): TrackStore = SqlTrackStore(driver(JdbcSqliteDriver.IN_MEMORY))

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }

    @Test
    fun survivesReopen() {
        val dir = Files.createTempDirectory("dystopo-tracks")
        val url = "jdbc:sqlite:" + dir.resolve("tracks.db").toString().replace('\\', '/')
        try {
            val first = driver(url)
            val rec = TrackRecorder(SqlTrackStore(first), clock = { 1_000L })
            val id = rec.start("Before restart").id
            fixesOf(loopPoints(30)).forEach { rec.onFix(it) }
            rec.waypoint("Mark")
            // No stop(): the process dies while recording.
            val before = TrackExport.gpx(SqlTrackStore(first), id)
            first.close()

            val second = SqlTrackStore(driver(url))
            val fresh = TrackRecorder(second, clock = { 2_000L })
            assertEquals(listOf(id), fresh.recoverInterrupted())
            val s = second.summary(id)!!
            assertFalse(s.recording)
            assertEquals(31, s.points)
            assertEquals(1, s.waypoints)
            assertEquals(before, TrackExport.gpx(second, id))
            // Appending after the reopen continues the sequence.
            second.append(id, loopPoints(2).last().copy(time = 9_999_999_999_999L), newSegment = true)
            assertEquals(listOf(31, 1), second.track(id)!!.segments.map { it.points.size })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The Skyline fixture replayed through the recorder: the export's distance matches the file's (the device test, on the JVM). */
    @Test
    fun skylineReplayMatchesFixture() {
        val text = javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }
        val original = Gpx.parse(text).tracks.single()
        val store = newStore()
        val rec = TrackRecorder(store, clock = { 0L })
        val id = rec.start("Skyline replay").id
        val timed = TrackExport.replay(listOf(original), rate = 20.0)
        assertTrue(timed.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
        timed.forEach { assertTrue(rec.onFix(it.fix)) }
        rec.stop()
        val exported = Gpx.parse(TrackExport.gpx(store, id)!!).tracks.single()
        val a = TrackStats.of(original)
        val b = TrackStats.of(exported)
        assertEquals(a.distanceM, b.distanceM, 1e-6)
        assertEquals(a.durationS, b.durationS)
        assertEquals(a.ascentM, b.ascentM)
        assertEquals(original.points.map { listOf(it.lat, it.lon, it.ele, it.time) }, exported.points.map { listOf(it.lat, it.lon, it.ele, it.time) })
        // The replay schedule: the last fix is due (duration / rate) after the first.
        assertEquals((a.durationS!! * 1000 / 20.0).toLong(), timed.last().atMs)
    }
}
