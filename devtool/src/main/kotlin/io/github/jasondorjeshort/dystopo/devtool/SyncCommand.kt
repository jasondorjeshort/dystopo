package io.github.jasondorjeshort.dystopo.devtool

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.FileSyncTransport
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import io.github.jasondorjeshort.dystopo.sync.SyncSimulator
import io.github.jasondorjeshort.dystopo.sync.SyncStore
import io.github.jasondorjeshort.dystopo.sync.SyncTransport
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

/**
 * `devtool sync-sim` (T44): the sync simulator ([SyncSimulator]) from the command line. One JSON
 * report on stdout; exit code 1 when a run does not converge. `--seeds N` runs seeds
 * `seed..seed+N-1` and prints a summary per seed; any failing seed replays alone with its `replay` line.
 */
class SyncSimCommand : CliktCommand(name = "sync-sim") {
    override fun help(context: Context) =
        "Simulate peers syncing over a faulty network (latency, loss, duplicates, --reorder, --partitions) and check they converge. " +
            "Deterministic from --seed; a failing run replays exactly."

    private val peers by option("--peers").int().default(3)
    private val ops by option("--ops", help = "local changes across all peers").int().default(2000)
    private val seed by option("--seed").long().default(42)
    private val seeds by option("--seeds", help = "run this many seeds, from --seed up").int().default(1)
    private val partitions by option("--partitions", help = "partition episodes during the change phase").int().default(5)
    private val reorder by option("--reorder", help = "deliver messages out of order (and duplicate some)").flag()
    private val storeKind by option("--store", help = "peer stores: memory (reference) or sql (SqlTrackStore over in-memory SQLite)").choice("memory", "sql").default("memory")

    override fun run() {
        val drivers = ArrayList<JdbcSqliteDriver>()
        val sqlStores = ArrayList<SqlTrackStore>()
        val factory: (String, () -> Long) -> SyncStore = when (storeKind) {
            "sql" -> { device, clock ->
                SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).also { drivers += it }, clock) { device }.also { sqlStores += it }
            }
            else -> { device, clock -> io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore(device, clock) }
        }
        val reports = (0 until seeds).map { i ->
            val config = SyncSimulator.Config(peers = peers, ops = ops, seed = seed + i, partitions = partitions, reorder = reorder)
            val run = SyncSimulator(config, factory).run { System.nanoTime() / 1_000_000 }
            // SQL peers: the tables must also be exactly the log's view.
            val tablesMatch = sqlStores.all { it.snapshot() == it.snapshotFromLog() }
            drivers.forEach { it.close() }
            drivers.clear()
            sqlStores.clear()
            val json = run.json + mapOf("store" to storeKind, "tablesMatchLog" to (if (storeKind == "sql") tablesMatch else null))
            SyncSimulator.Report(json, run.converged && tablesMatch) to tablesMatch
        }
        val allConverged = reports.all { it.first.converged }
        if (seeds == 1) {
            println(JsonWriter.write(reports.single().first.json))
        } else {
            println(
                JsonWriter.write(
                    linkedMapOf(
                        "command" to "sync-sim", "store" to storeKind, "peers" to peers, "ops" to ops, "partitions" to partitions, "reorder" to reorder,
                        "seeds" to listOf(seed, seed + seeds - 1), "converged" to allConverged,
                        "failures" to reports.filter { !it.first.converged }.map { it.first.json["replay"] },
                        "runs" to reports.map { (r, _) ->
                            val j = r.json
                            @Suppress("UNCHECKED_CAST") val state = j["state"] as Map<String, Any?>
                            @Suppress("UNCHECKED_CAST") val msgs = j["messages"] as Map<String, Any?>
                            @Suppress("UNCHECKED_CAST") val merge = j["merge"] as Map<String, Any?>
                            linkedMapOf(
                                "seed" to j["seed"], "converged" to j["converged"], "digest" to j["digest"], "convergedAtMs" to j["convergedAtMs"],
                                "quiesceRounds" to j["quiesceRounds"], "tracks" to state["tracks"], "points" to state["points"], "waypoints" to state["waypoints"],
                                "routes" to state["routes"], "settings" to state["settings"], "opsEver" to state["opsEver"],
                                "tracksRenamedOnSeveralPeers" to state["tracksRenamedOnSeveralPeers"],
                                "sent" to msgs["sent"], "dropped" to msgs["dropped"], "droppedByPartition" to msgs["droppedByPartition"],
                                "duplicated" to msgs["duplicated"], "reorderedOnLink" to msgs["reorderedOnLink"],
                                "dead" to merge["dead"], "gapSkips" to merge["gapSkips"], "tablesMatchLog" to j["tablesMatchLog"], "elapsedMs" to j["elapsedMs"],
                                "firstDivergence" to j["firstDivergence"],
                            )
                        },
                    ),
                ),
            )
        }
        if (!allConverged) throw ProgramResult(1)
    }
}

/**
 * `devtool sync ...` (T44): a JVM replica in a SQLite file (default the server peer's
 * `out/sync/peer.db`), for checking a device against the JVM store or moving `.dsync` files by hand:
 * - `sync status --db F`: device id, vector, counts, digest, whether the tables match the log.
 * - `sync with --db F --peer http://host:port | file.dsync`: one sync (pull, merge, push).
 * - `sync export --db F --out x.dsync [--since <vector>]`, `sync import --db F --file x.dsync`.
 * - `sync import-gpx --db F --file x.gpx`, `sync rename --db F --track ID --name N`,
 *   `sync gpx --db F --track ID [--out file]` (writes the GPX, prints its size and SHA-256).
 */
class SyncCommand : CliktCommand(name = "sync") {
    init {
        subcommands(Status(), With(), Export(), Import(), ImportGpx(), Rename(), GpxOut())
    }

    override fun help(context: Context) = "A sync replica in a SQLite file: status, sync with a peer or a .dsync file, export/import bundles, GPX in and out."

    override fun run() = Unit

    abstract class DbCommand(name: String) : CliktCommand(name = name) {
        private val db by option("--db", help = "replica database (default: the server peer's)").default(System.getenv("DYSTOPO_SYNC_DB") ?: "out/sync/peer.db")

        protected fun open(): SqlTrackStore {
            val f = File(db).absoluteFile
            f.parentFile.mkdirs()
            return SqlTrackStore(JdbcSqliteDriver("jdbc:sqlite:" + f.path.replace('\\', '/'), Properties(), TrackDb.Schema), System::currentTimeMillis)
        }

        protected fun status(s: SqlTrackStore): Map<String, Any?> = SyncServer.status(s) + mapOf("db" to db, "tablesMatchLog" to (s.snapshot() == s.snapshotFromLog()))

        protected fun print(m: Map<String, Any?>) = println(JsonWriter.write(m))
    }

    class Status : DbCommand("status") {
        override fun run() = print(status(open()) + mapOf("tracks" to open().list().map { mapOf("id" to it.id, "name" to it.name, "points" to it.points, "waypoints" to it.waypoints) }))
    }

    class With : DbCommand("with") {
        private val peer by option("--peer", help = "http(s)://host:port of a sync peer, or a .dsync file").required()

        override fun run() {
            val s = open()
            val r = Sync.exchange(s, transport(peer), System::currentTimeMillis) { System.nanoTime() / 1_000_000 }
            print(linkedMapOf<String, Any?>("sync" to r.json()) + mapOf("status" to status(s)))
        }
    }

    class Export : DbCommand("export") {
        private val out by option("--out").required()
        private val since by option("--since", help = "only operations after this vector (comma-separated HLCs)")

        override fun run() {
            val s = open()
            val b = s.bundle(VersionVector.parse(since))
            val bytes = Sync.pack(b, Gzip.compress)
            File(out).absoluteFile.parentFile.mkdirs()
            File(out).writeBytes(bytes)
            print(linkedMapOf("file" to out, "ops" to b.ops.size, "bytes" to bytes.size, "rawBytes" to b.encode().size, "vector" to b.vector.text, "base" to b.base.text))
        }
    }

    class Import : DbCommand("import") {
        private val file by option("--file").required()

        override fun run() {
            val s = open()
            val r = s.apply(Sync.unpack(File(file).readBytes(), Gzip.decompress), System.currentTimeMillis())
            print(linkedMapOf<String, Any?>("merged" to r.json()) + mapOf("status" to status(s)))
        }
    }

    class ImportGpx : DbCommand("import-gpx") {
        private val file by option("--file").required()

        override fun run() {
            val s = open()
            val r = TrackImport.import(s, File(file).readText(), System.currentTimeMillis(), File(file).nameWithoutExtension)
            print(linkedMapOf("imported" to r.imported.map { it.id }, "skipped" to r.skippedTracks, "waypoints" to r.waypoints))
        }
    }

    class Rename : DbCommand("rename") {
        private val track by option("--track").required()
        private val name by option("--name").required()

        override fun run() {
            val s = open()
            if (s.summary(track) == null) throw UsageError("no track $track")
            s.rename(track, name, System.currentTimeMillis())
            print(mapOf("id" to track, "name" to s.summary(track)!!.name))
        }
    }

    class GpxOut : DbCommand("gpx") {
        private val track by option("--track").required()
        private val out by option("--out")

        override fun run() {
            val s = open()
            val gpx = TrackExport.gpx(s, track) ?: throw UsageError("no track $track")
            val f = File(out ?: "out/sync/$track.gpx").absoluteFile
            f.parentFile.mkdirs()
            f.writeText(gpx)
            val sha = MessageDigest.getInstance("SHA-256").digest(gpx.toByteArray()).joinToString("") { "%02x".format(it) }
            print(linkedMapOf("file" to f.path, "bytes" to f.length(), "sha256" to sha, "name" to s.summary(track)!!.name))
        }
    }

    companion object {
        /** HTTP for a URL, else a `.dsync` file. */
        fun transport(peer: String): SyncTransport =
            if (peer.startsWith("http://") || peer.startsWith("https://")) HttpSyncTransport(peer, ::post, Gzip.compress, Gzip.decompress)
            else File(peer).absoluteFile.let { f ->
                f.parentFile.mkdirs()
                FileSyncTransport(f.path, { f.takeIf { it.isFile }?.readBytes() }, { f.writeBytes(it) }, Gzip.compress, Gzip.decompress)
            }

        fun post(url: String, body: ByteArray): ByteArray {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readAllBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("POST $url: HTTP $code ${bytes.decodeToString().take(300)}")
            return bytes
        }
    }
}
