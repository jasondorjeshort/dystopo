package io.github.jasondorjeshort.dystopo.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration verification for the track database (T40), in the test JVM so it runs in `check` on
 * every OS (SQLDelight's own verifyCommonMainTrackDbMigration task cannot load sqlite-jdbc in a Gradle
 * worker on Windows; see shared/build.gradle.kts). For every frozen schema `databases/<n>.sql`:
 * create a database from it, apply the generated migrations n..current and compare tables, columns (name, type, not-null,
 * default, primary key, in order) and indexes with a database created fresh from Track.sq. Also
 * upgrades a version-1 database holding a track, as the app's Android driver does on first open
 * after an update, and checks nothing is lost.
 *
 * It also writes the current schema to `build/trackdb/<version>.sql`: before changing Track.sq,
 * copy that file to `src/commonMain/sqldelight/databases/` (see .claude/rules/trackdb.md).
 */
class TrackDbMigrationTest {
    private val frozenDir = File("src/commonMain/sqldelight/databases")

    private fun url(f: File) = "jdbc:sqlite:" + f.absolutePath.replace('\\', '/')

    /** Tables and indexes of a database as comparable text, one line per column or index. */
    private fun schemaOf(f: File): List<String> = DriverManager.getConnection(url(f)).use { c ->
        val out = ArrayList<String>()
        val objects = c.createStatement().executeQuery(
            "SELECT type, name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
        ).use { rs -> generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }.toList() }
        for ((type, name, table) in objects) {
            when (type) {
                "table" -> c.createStatement().executeQuery("PRAGMA table_info('$name')").use { rs ->
                    while (rs.next()) {
                        out += "table $name col ${rs.getInt("cid")} ${rs.getString("name")} ${rs.getString("type")} " +
                            "notnull=${rs.getInt("notnull")} default=${rs.getString("dflt_value")} pk=${rs.getInt("pk")}"
                    }
                }
                "index" -> c.createStatement().executeQuery("PRAGMA index_info('$name')").use { rs ->
                    val cols = ArrayList<String>()
                    while (rs.next()) cols += rs.getString("name")
                    out += "index $name on $table(${cols.joinToString()})"
                }
                else -> out += "$type $name on $table"
            }
        }
        out
    }

    private fun fresh(dir: File): File {
        val f = File(dir, "fresh.db")
        JdbcSqliteDriver(url(f), Properties()).use { TrackDb.Schema.create(it) }
        return f
    }

    /** A database file created by a frozen schema's statements (comments stripped, split on `;`). */
    private fun frozen(version: Long, dir: File): File {
        val sql = File(frozenDir, "$version.sql").readText()
        val f = File(dir, "v$version.db")
        DriverManager.getConnection(url(f)).use { c ->
            val statements = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').map { it.trim() }.filter { it.isNotEmpty() }
            c.createStatement().use { st -> statements.forEach { st.executeUpdate(it) } }
        }
        return f
    }

    /**
     * T278: a file of the frozen schema [version] holding what [write] stores: written by this build's store into a fresh
     * file, then every table's rows copied into the frozen one by the columns that version has (a newer column is left
     * behind, as an older build never wrote it), with sync_meta `routes` as a build of that version left it ([routesMeta]).
     */
    private fun olderFile(version: Long, dir: File, routesMeta: String, write: (SqlTrackStore) -> Unit): File {
        val written = File(dir, "written-v$version.db")
        JdbcSqliteDriver(url(written), Properties(), TrackDb.Schema).use { d -> write(SqlTrackStore(d, wallClock = { 1_000L })) }
        val f = frozen(version, dir)
        DriverManager.getConnection(url(f)).use { c ->
            val tables = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
                .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            c.createStatement().use { st ->
                st.executeUpdate("ATTACH DATABASE '${written.invariantSeparatorsPath}' AS w")
                for (t in tables) {
                    val cols = st.executeQuery("PRAGMA main.table_info('$t')").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.toList() }
                    st.executeUpdate("INSERT OR REPLACE INTO main.$t(${cols.joinToString()}) SELECT ${cols.joinToString()} FROM w.$t")
                }
                st.executeUpdate("UPDATE main.sync_meta SET value = '$routesMeta' WHERE key = 'routes'")
                st.executeUpdate("PRAGMA user_version = $version")
            }
        }
        return f
    }

    /** The current schema as SQL text (what a new frozen `<n>.sql` holds), read back from a fresh database. */
    private fun schemaSql(f: File): String = DriverManager.getConnection(url(f)).use { c ->
        c.createStatement().executeQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid").use { rs ->
            buildString { while (rs.next()) append(rs.getString(1)).append(";\n\n") }
        }
    }

    @Test
    fun writesCurrentSchema() {
        val dir = File("build/trackdb").apply { mkdirs() }
        val db = File(dir, "current.db").apply { delete() }
        JdbcSqliteDriver(url(db), Properties()).use { TrackDb.Schema.create(it) }
        File(dir, "${TrackDb.Schema.version}.sql").writeText(schemaSql(db))
        assertTrue(File(dir, "${TrackDb.Schema.version}.sql").readText().startsWith("CREATE TABLE track"))
    }

    @Test
    fun everyFrozenSchemaMigratesToTrackSq() {
        val versions = frozenDir.listFiles { f -> f.name.matches(Regex("\\d+\\.sql")) }.orEmpty().map { it.nameWithoutExtension.toLong() }.sorted()
        // One frozen schema per released version: 1 up to (but not including) the current one.
        assertEquals((1 until TrackDb.Schema.version).toList(), versions, "frozen schemas in $frozenDir")
        val dir = Files.createTempDirectory("dystopo-migrate").toFile()
        try {
            val expected = schemaOf(fresh(dir))
            for (v in versions) {
                val db = frozen(v, dir)
                JdbcSqliteDriver(url(db), Properties()).use { TrackDb.Schema.migrate(it, v, TrackDb.Schema.version) }
                assertEquals(expected, schemaOf(db), "version $v migrated to version ${TrackDb.Schema.version}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T44: a version-2 database (tracks recorded before the operation log existed) opened with the
     * current schema keeps every id and row, gets its log seeded once, and syncs to a fresh store
     * that then exports identical GPX.
     */
    @Test
    fun versionTwoDataSurvivesAndSyncs() {
        val dir = Files.createTempDirectory("dystopo-upgrade2").toFile()
        val t1 = "00000000-0000-4000-8000-000000000001"
        val t2 = "00000000-0000-4000-8000-000000000002"
        try {
            val f = frozen(2, dir)
            DriverManager.getConnection(url(f)).use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO track(id, name, created_at, updated_at, recording, description, stats_points, stats_segments, stats_distance_m) VALUES ('$t1', 'Old', 10, 20, 0, 'desc', 99, 1, 1.5)")
                    st.executeUpdate("INSERT INTO track(id, name, created_at, updated_at, recording) VALUES ('$t2', 'Still recording', 30, 40, 1)")
                    for (i in 0 until 6) {
                        st.executeUpdate("INSERT INTO track_point VALUES ('$t1', $i, ${i / 3}, ${46.85 + i * 0.001}, -121.76, ${1600 + i * 10}, ${1_000_000 + i * 60_000L}, NULL)")
                    }
                    st.executeUpdate("INSERT INTO track_point VALUES ('$t2', 0, 0, 46.9, -121.7, NULL, NULL, '<x:y>1</x:y>')")
                    st.executeUpdate("INSERT INTO waypoint(id, track_id, seq, name, lat, lon) VALUES ('00000000-0000-4000-8000-0000000000f1', '$t1', 0, 'Mark', 46.85, -121.76)")
                    st.executeUpdate("INSERT INTO waypoint(id, track_id, seq, name, lat, lon, symbol) VALUES ('00000000-0000-4000-8000-0000000000f2', NULL, 1, 'Free', 46.86, -121.75, 'Flag')")
                    st.executeUpdate("INSERT INTO waypoint(id, track_id, seq, name, lat, lon) VALUES ('00000000-0000-4000-8000-0000000000f3', '$t1', 2, 'Later', 46.87, -121.74)")
                    st.executeUpdate("PRAGMA user_version = 2")
                }
            }
            val gpxBefore = InMemoryTrackStore().let { ref ->
                // What the version-2 rows mean, built through the public API.
                ref.insert(
                    io.github.jasondorjeshort.dystopo.model.Track(t1, "Old", listOf(0, 1).map { s ->
                        io.github.jasondorjeshort.dystopo.model.TrackSegment((s * 3 until s * 3 + 3).map { i ->
                            io.github.jasondorjeshort.dystopo.model.TrackPoint(46.85 + i * 0.001, -121.76, (1600 + i * 10).toDouble(), 1_000_000 + i * 60_000L)
                        })
                    }, description = "desc"),
                    listOf(
                        io.github.jasondorjeshort.dystopo.model.Waypoint("00000000-0000-4000-8000-0000000000f1", "Mark", 46.85, -121.76),
                        io.github.jasondorjeshort.dystopo.model.Waypoint("00000000-0000-4000-8000-0000000000f3", "Later", 46.87, -121.74),
                    ),
                    10, 20,
                )
                TrackExport.gpx(ref, t1)
            }
            val opsAfterFirstOpen: Long
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d)
                assertTrue(store.seededFromTables)
                assertEquals(listOf(TrackSummary(t1, "Old", 10, 20, false, 6, 2), TrackSummary(t2, "Still recording", 30, 40, true, 1, 0)), store.list())
                assertEquals(gpxBefore, TrackExport.gpx(store, t1))
                assertEquals(listOf("Mark", "Later"), store.waypoints(t1).map { it.name })
                assertEquals(listOf("Free"), store.waypoints(null).map { it.name })
                // 2 tracks + 3 segments + 3 waypoints.
                assertEquals(8, store.opCount)
                assertEquals(store.snapshotFromLog(), store.snapshot())
                opsAfterFirstOpen = store.opCount

                // Everything reaches another replica through a bundle, ids and all.
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).use { d2 ->
                    val other = SqlTrackStore(d2)
                    other.apply(io.github.jasondorjeshort.dystopo.sync.Bundle.decode(store.bundle().encode()), 0)
                    assertEquals(TrackExport.gpx(store, t1), TrackExport.gpx(other, t1))
                    assertEquals(TrackExport.gpx(store, t2), TrackExport.gpx(other, t2))
                    assertEquals(store.snapshot(), other.snapshot())
                }
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val again = SqlTrackStore(d)
                assertEquals(false, again.seededFromTables)
                assertEquals(opsAfterFirstOpen, again.opCount, "seeded once")
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T60: a version-3 database whose log holds routes (a pre-T60 put and a planned route with a path
     * and waypoints) gets its route table filled from the log once on the first open, and the rows equal
     * what the log materialises.
     */
    @Test
    fun versionThreeRoutesAreMaterialised() {
        val dir = Files.createTempDirectory("dystopo-upgrade3").toFile()
        val dev = "00000000-0000-4000-8000-0000000000dd"
        val r1 = "00000000-0000-4000-8000-0000000000a1"
        val r2 = "00000000-0000-4000-8000-0000000000a2"
        try {
            val f = frozen(3, dir)
            val path = io.github.jasondorjeshort.dystopo.model.RouteEditor(null).create(listOf(46.7865 to -121.7365, 46.8025 to -121.7290))
            val wps = listOf(io.github.jasondorjeshort.dystopo.model.Waypoint("00000000-0000-4000-8000-0000000000f9", "Panorama", 46.8025, -121.729, 2058.0))
            val bodies = listOf(
                io.github.jasondorjeshort.dystopo.sync.PutRoute(io.github.jasondorjeshort.dystopo.model.Route(r1, "Old route", listOf(io.github.jasondorjeshort.dystopo.model.RoutePoint(46.79, -121.73), io.github.jasondorjeshort.dystopo.model.RoutePoint(46.80, -121.72, 1900.0)))),
                io.github.jasondorjeshort.dystopo.sync.PutRoute(io.github.jasondorjeshort.dystopo.model.Route(r2, "Planned", emptyList(), type = "hike")),
                io.github.jasondorjeshort.dystopo.sync.SetField(io.github.jasondorjeshort.dystopo.sync.EntityKind.ROUTE, r2, "path", path.encode()),
                io.github.jasondorjeshort.dystopo.sync.SetField(io.github.jasondorjeshort.dystopo.sync.EntityKind.ROUTE, r2, "waypoints", io.github.jasondorjeshort.dystopo.model.RouteWaypoints.encode(wps)),
            )
            val ops = bodies.mapIndexed { i, b -> io.github.jasondorjeshort.dystopo.sync.Op(io.github.jasondorjeshort.dystopo.sync.Hlc(1_700_000_000_000L + i, 0, dev), b) }
            DriverManager.getConnection(url(f)).use { c ->
                c.prepareStatement("INSERT INTO sync_op(hlc, device, type, kind, entity, parent, digest, body) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)").use { st ->
                    for (op in ops) {
                        st.setString(1, op.hlc.text); st.setString(2, dev); st.setLong(3, op.body.type.toLong()); st.setLong(4, op.body.kind.code.toLong())
                        st.setString(5, op.body.id); st.setBytes(6, io.github.jasondorjeshort.dystopo.sync.OpCodec.encode(op))
                        st.executeUpdate()
                    }
                }
                c.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO sync_vector VALUES ('$dev', '${ops.last().hlc.text}')")
                    st.executeUpdate("INSERT INTO sync_meta VALUES ('device', '$dev')")
                    st.executeUpdate("INSERT INTO sync_meta VALUES ('log', '1')")
                    st.executeUpdate("PRAGMA user_version = 3")
                }
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d)
                assertEquals(2, store.routesMaterialized)
                assertEquals(listOf(r1, r2), store.routePlans().map { it.id })
                assertEquals(store.snapshotFromLog(), store.snapshot())
                val planned = store.routePlan(r2)!!
                assertEquals(path, planned.path)
                assertEquals(wps, planned.waypoints)
                assertEquals(listOf("Old route", "Planned"), store.routeList().map { it.name })
                assertEquals(1, store.routePlan(r1)!!.path.legs.size)
                // Edits after the upgrade go through the table too.
                assertTrue(store.setRoutePath(r1, io.github.jasondorjeshort.dystopo.model.RouteEditor(null).moveVia(store.routePlan(r1)!!.path, 0, 46.785, -121.735), 1_700_000_001_000L))
                assertEquals(store.snapshotFromLog(), store.snapshot())
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val again = SqlTrackStore(d)
                assertEquals(0, again.routesMaterialized, "filled once")
                assertEquals(2, again.routeList().size)
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T158: a version-4 file with rows (a track recorded before T108 and T158) upgrades to the current schema: its
     * points read back with no accuracy, speed, course or flags, no activity, no gaps; the version-5 file of
     * v0.1.14 is `sync/V014TrackFixtureTest`'s.
     */
    @Test
    fun versionFourDataSurvivesTheUpgrade() {
        val dir = Files.createTempDirectory("dystopo-upgrade4").toFile()
        val t1 = "00000000-0000-4000-8000-000000000001"
        try {
            val f = frozen(4, dir)
            DriverManager.getConnection(url(f)).use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO track(id, name, created_at, updated_at, recording) VALUES ('$t1', 'Before T158', 10, 20, 0)")
                    for (i in 0 until 4) {
                        st.executeUpdate("INSERT INTO track_point VALUES ('$t1', $i, 0, ${46.85 + i * 0.001}, -121.76, ${1600 + i * 10}, ${1_000_000 + i * 60_000L}, NULL)")
                    }
                    st.executeUpdate("PRAGMA user_version = 4")
                }
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d)
                assertEquals(TrackSummary(t1, "Before T158", 10, 20, false, 4, 0), store.summary(t1))
                val t = store.track(t1)!!
                assertEquals(4, t.points.size)
                assertTrue(t.points.none { it.hasFixFields })
                assertEquals(null, t.activity)
                assertEquals(emptyList(), store.gaps(t1))
                assertEquals(store.snapshotFromLog(), store.snapshot())
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T262: a version-6 file (v0.1.26-alpha's schema) holding a track and a route, written by the store before the shown flag
     * existed, upgrades to the current schema with both shown; a hide is kept across a reopen and across the rows' rebuild
     * from the log (a rename), and is forgotten with the item.
     */
    @Test
    fun versionSixItemsAreShownAfterTheUpgrade() {
        val dir = Files.createTempDirectory("dystopo-upgrade6").toFile()
        val t1 = "00000000-0000-4000-8000-000000000061"
        val r1 = "00000000-0000-4000-8000-000000000062"
        try {
            // What a version-6 build stored (T278: written by this build and copied into the frozen schema, since this
            // build's store writes the route table's version-8 column).
            val f = olderFile(6, dir, "2") { store ->
                store.create(t1, "Paradise loop", 1_000L)
                store.append(t1, io.github.jasondorjeshort.dystopo.model.TrackPoint(46.785, -121.735, 1650.0, 1_000L), newSegment = true, now = 1_000L)
                store.append(t1, io.github.jasondorjeshort.dystopo.model.TrackPoint(46.786, -121.734, 1660.0, 61_000L), now = 61_000L)
                store.putRoute(io.github.jasondorjeshort.dystopo.model.Route(r1, "To Panorama Point", listOf(
                    io.github.jasondorjeshort.dystopo.model.RoutePoint(46.785, -121.735), io.github.jasondorjeshort.dystopo.model.RoutePoint(46.800, -121.730),
                )), 2_000L)
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d, wallClock = { 3_000L })
                assertEquals(listOf(t1), store.list().map { it.id })
                assertEquals(listOf(r1), store.routeList().map { it.id })
                assertEquals(emptySet(), store.hidden(ShownKind.TRACK))
                assertEquals(emptySet(), store.hidden(ShownKind.ROUTE))
                store.setShown(ShownKind.TRACK, t1, false)
                store.setShown(ShownKind.ROUTE, r1, false)
                store.rename(t1, "Paradise loop, renamed", 4_000L)
                store.setField(io.github.jasondorjeshort.dystopo.sync.EntityKind.ROUTE, r1, "name", "Renamed route", 4_000L)
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d, wallClock = { 5_000L })
                assertEquals(setOf(t1), store.hidden(ShownKind.TRACK))
                assertEquals(setOf(r1), store.hidden(ShownKind.ROUTE))
                store.setShown(ShownKind.TRACK, t1, true)
                assertEquals(emptySet(), store.hidden(ShownKind.TRACK))
                store.deleteRoute(r1, 6_000L)
                assertEquals(emptySet(), store.hidden(ShownKind.ROUTE))
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T278: a version-7 file (v0.1.28-alpha's schema) holding two trips upgrades with every trip as it was: one kept before
     * T278 (no kind: it reads as by its mode, a Hike) and one whose log holds a `tripKind` write a newer phone synced in
     * (a version-7 build keeps it in the log without a column for it), which the route table's rebuild after the migration
     * brings into the new column. The kind then travels in a sync bundle into a second store.
     */
    @Test
    fun versionSevenTripsKeepTheirKindAfterTheUpgrade() {
        val dir = Files.createTempDirectory("dystopo-upgrade7").toFile()
        val before = "00000000-0000-4000-8000-000000000071"
        val walk = "00000000-0000-4000-8000-000000000072"
        fun plan(id: String, name: String, kind: io.github.jasondorjeshort.dystopo.routing.TripKind?) = io.github.jasondorjeshort.dystopo.model.RoutePlan(
            id, name, io.github.jasondorjeshort.dystopo.model.RoutePath(
                "hike", listOf(io.github.jasondorjeshort.dystopo.model.ViaPoint(45.9172, 6.8668), io.github.jasondorjeshort.dystopo.model.ViaPoint(45.9080, 6.8530)),
                listOf(io.github.jasondorjeshort.dystopo.model.RouteLeg(io.github.jasondorjeshort.dystopo.model.LegKind.TRAIL, List(4) { io.github.jasondorjeshort.dystopo.model.RoutePoint(45.9172 - 0.003 * it, 6.8668 - 0.0046 * it, 1040.0) })),
            ).quantized(), type = "hike", tripKind = kind,
        )
        try {
            // What a version-7 phone holds: the log with both writes (a version-7 build keeps a field it does not know in the
            // log) and the route rows without the new column.
            val f = olderFile(7, dir, "2") { store ->
                store.putRoutePlan(plan(before, "Kept before T278", null), 1_000L)
                store.putRoutePlan(plan(walk, "Walk from a newer phone", io.github.jasondorjeshort.dystopo.routing.TripKind.EVERYDAY), 2_000L)
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d, wallClock = { 3_000L })
                assertEquals(2, store.routesMaterialized, "every route row rebuilt from the log once")
                assertEquals(listOf(before, walk), store.routeList().map { it.id })
                assertEquals(null, store.routePlan(before)!!.tripKind)
                assertEquals(io.github.jasondorjeshort.dystopo.routing.TripKind.BACKCOUNTRY, io.github.jasondorjeshort.dystopo.routing.TripKinds.kept(io.github.jasondorjeshort.dystopo.routing.TravelMode.HIKE, store.routePlan(before)!!.tripKind), "a Hike, as before")
                assertEquals(io.github.jasondorjeshort.dystopo.routing.TripKind.EVERYDAY, store.routePlan(walk)!!.tripKind)
                assertEquals(listOf(null, "everyday"), store.routeList().map { it.tripKind })
                assertEquals(store.snapshotFromLog(), store.snapshot())
                // Into a second store through a sync bundle.
                val other = SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()).also { TrackDb.Schema.create(it) }, wallClock = { 4_000L })
                io.github.jasondorjeshort.dystopo.sync.syncPair(store, other, 4_000L)
                assertEquals(io.github.jasondorjeshort.dystopo.routing.TripKind.EVERYDAY, other.routePlan(walk)!!.tripKind)
                assertEquals(null, other.routePlan(before)!!.tripKind)
                assertEquals(store.snapshot(), other.snapshot())
            }
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d, wallClock = { 5_000L })
                assertEquals(0, store.routesMaterialized, "once only")
                assertEquals(io.github.jasondorjeshort.dystopo.routing.TripKind.EVERYDAY, store.routePlan(walk)!!.tripKind)
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** What the app does on first open after an update: a version-1 file with a track, opened with the current schema. */
    @Test
    fun versionOneDataSurvivesTheUpgrade() {
        val dir = Files.createTempDirectory("dystopo-upgrade").toFile()
        try {
            val f = frozen(1, dir)
            DriverManager.getConnection(url(f)).use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO track(id, name, created_at, updated_at, recording) VALUES ('00000000-0000-4000-8000-000000000001', 'Old', 10, 20, 0)")
                    for (i in 0 until 5) {
                        st.executeUpdate("INSERT INTO track_point VALUES ('00000000-0000-4000-8000-000000000001', $i, 0, ${46.85 + i * 0.001}, -121.76, ${1600 + i * 10}, ${1_000_000 + i * 60_000L}, NULL)")
                    }
                    st.executeUpdate("INSERT INTO waypoint(id, track_id, seq, name, lat, lon) VALUES ('00000000-0000-4000-8000-0000000000f1', '00000000-0000-4000-8000-000000000001', 0, 'Mark', 46.85, -121.76)")
                    st.executeUpdate("PRAGMA user_version = 1")
                }
            }
            // The JDBC driver, like AndroidSqliteDriver, migrates from user_version to Schema.version.
            JdbcSqliteDriver(url(f), Properties(), TrackDb.Schema).use { d ->
                val store = SqlTrackStore(d)
                val s = store.summary("00000000-0000-4000-8000-000000000001")!!
                assertEquals(TrackSummary(s.id, "Old", 10, 20, false, 5, 1), s)
                val stats = store.stats(s.id)!!
                assertEquals(TrackStats.of(store.track(s.id)!!), stats)
                assertEquals(40.0, stats.ascentM)
                assertEquals(1, store.statsComputed)
                assertEquals(stats, store.stats(s.id))
                assertEquals(1, store.statsComputed)
            }
            DriverManager.getConnection(url(f)).use { c ->
                c.createStatement().executeQuery("PRAGMA user_version").use { rs -> rs.next(); assertEquals(TrackDb.Schema.version, rs.getLong(1)) }
            }
            assertEquals(schemaOf(fresh(dir)), schemaOf(f))
        } finally {
            dir.deleteRecursively()
        }
    }
}
