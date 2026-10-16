package io.github.jasondorjeshort.dystopo.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.model.RouteDirections
import io.github.jasondorjeshort.dystopo.model.SavedDirections
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.Maneuver
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T108 (ADR 0033 Decisions 1 and 2): user data written by v0.1.7-alpha keeps working. The fixture
 * `sync/v0.1.7-trips.dsync` was written by v0.1.7-alpha itself (the release's server, `POST /route/bundle` with two
 * trips it had planned on the public Rainier pack, as the browser's Trips box saves them): Paradise to Panorama Point
 * (hike, version 1 path) and Paradise to Sunrise (drive, version 2 path with the router's leg times). It decodes and
 * re-encodes byte for byte, merges into this build's stores with no directions (none were saved then), gains them
 * (made for its path, as opening a trip does) and carries them to a second phone; and a v0.1.7 database whose log
 * holds a newer peer's directions fills the new column on its first open (schema 5).
 */
class V017TripFixtureTest {
    private val drivers = ArrayList<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private fun sql(device: String, url: String = JdbcSqliteDriver.IN_MEMORY): SqlTrackStore {
        val d = JdbcSqliteDriver(url, Properties(), TrackDb.Schema).also { drivers += it }
        return SqlTrackStore(d, { 1_800_000_000_000 }) { device }
    }

    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/sync/v0.1.7-trips.dsync")!!.use { it.readBytes() }
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readBytes() }
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private val hike = "a0170000-0000-4000-8000-0000000000a1"
    private val drive = "b0170000-0000-4000-8000-0000000000b2"

    @Test
    fun aBundleWrittenByV017RoundTripsAndGainsDirections() {
        val raw = gunzip(fixture())
        val bundle = Bundle.decode(raw)
        assertEquals(6, bundle.ops.size)
        assertContentEquals(raw, bundle.encode(), "this build writes the v0.1.7 bundle byte for byte")
        assertTrue(bundle.ops.none { (it.body as? SetField)?.field == "directions" })

        val phone = sql("00000000-0000-4000-8000-00000000a108")
        val r = phone.apply(Bundle.decode(gunzip(fixture())), 1_800_000_000_000)
        assertEquals(6, r.applied)
        val plans = phone.routePlans().associateBy { it.id }
        val h = plans.getValue(hike)
        val d = plans.getValue(drive)
        assertEquals("hike", h.path.mode)
        assertEquals("drive", d.path.mode)
        assertTrue(d.path.legs.all { it.timeS != null }, "the drive keeps v0.1.7's router times")
        assertNull(h.directions, "saved before directions were")
        assertNull(d.savedDirections)
        val inMemory = InMemoryTrackStore("dev-m").also { it.apply(bundle, 1_800_000_000_000) }
        assertEquals(phone.snapshot(), inMemory.snapshot(), "the SQL store and the reference agree")

        // Opening it where a graph is gives it its directions (here without one: the trip's frame).
        for (p in listOf(h, d)) {
            val list = Directions.ofPath(p.path, null)
            assertTrue(phone.setRouteDirections(p.id, list, p.path, 1_800_000_000_100))
            assertEquals(SavedDirections.of(list, p.path).list, phone.routePlan(p.id)!!.savedDirections)
        }
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), phone.routePlan(hike)!!.savedDirections!!.map { it.type })

        // ...and they reach a phone that never had the trip, through a gzipped bundle as a push carries it.
        val other = sql("00000000-0000-4000-8000-00000000b108")
        other.apply(Bundle.decode(gunzip(gzip(phone.bundle().encode()))), 1_800_000_000_200)
        assertEquals(phone.snapshot(), other.snapshot())
        assertEquals(phone.routePlan(drive)!!.savedDirections, other.routePlan(drive)!!.savedDirections)
        assertEquals(other.snapshot(), other.snapshotFromLog(), "the route table holds what the log says")
    }

    @Test
    fun aV017DatabaseFillsTheDirectionsColumnFromItsLogOnFirstOpen() {
        val dir = Files.createTempDirectory("t108").toFile()
        val url = "jdbc:sqlite:" + java.io.File(dir, "phone.db").absolutePath.replace('\\', '/')
        val device = "00000000-0000-4000-8000-00000000c108"
        val first = sql(device, url)
        first.apply(Bundle.decode(gunzip(fixture())), 1_800_000_000_000)
        val p = first.routePlan(hike)!!
        assertTrue(first.setRouteDirections(hike, Directions.ofPath(p.path, null), p.path, 1_800_000_000_100))
        val want = first.routePlan(hike)!!.savedDirections
        assertNotNull(want)
        drivers.forEach { it.close() }
        drivers.clear()
        // As a v0.1.7 phone left it: the directions op in its log (from a newer peer), the column empty, the route table
        // marked as filled by T60's rule only.
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("UPDATE route SET directions = NULL")
                st.executeUpdate("UPDATE sync_meta SET value = '1' WHERE key = 'routes'")
            }
        }
        val reopened = sql(device, url)
        assertEquals(2, reopened.routesMaterialized)
        assertEquals(want, reopened.routePlan(hike)!!.savedDirections)
        assertEquals(reopened.snapshot(), reopened.snapshotFromLog())
        drivers.forEach { it.close() }
        drivers.clear()
        // Once filled, a later open does not rebuild again.
        assertEquals(0, sql(device, url).routesMaterialized)
        assertEquals(RouteDirections.VERSION, 1)
        dir.deleteRecursively()
    }
}
