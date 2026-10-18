package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

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
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.FileSyncTransport
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Materializer
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
 * T116: `--bundle F` merges a sync bundle (a `.dsync` file: the browser's trips as `demo-check.py --route` writes them,
 * `<out>-route.dsync`) into the first peer before the run, so its trips travel the faulty network to every other peer
 * (with `--store sql`, the phone's store: a phone-shaped replica with no graph); the report's `bundle` lists each of
 * its trips on every peer at the end, with the turn directions saved for the trip's path ([BundleCheck]), and a run
 * where a peer lost them, or holds others for the bundle's path, fails.
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
    private val bigSkewMs by option("--big-skew-ms", help = "T51: extra clock offset of the last peer, ms (negative: behind)").long()
    private val skewDays by option("--skew-days", help = "T51: the same in days (2 = two days ahead, -2 behind)").double()
    private val crashes by option("--crashes", help = "T51: crash-restarts of peer 1 during the change phase").int().default(0)
    private val page by option("--page", help = "T51: pull and push in pages of this many operations").int().default(0)
    private val bundleFile by option("--bundle", help = "T116: a sync bundle (.dsync, e.g. the browser's trips as demo-check.py --route writes them) merged into the first peer before the run; `bundle` in the report checks each trip and its saved directions on every peer").file(mustExist = true, canBeDir = false)

    override fun run() {
        val seeded = bundleFile?.let { f -> BundleCheck.read(f.readBytes()) }
        val sql = SqlPeers()
        val factory: (String, () -> Long) -> SyncStore = when (storeKind) {
            "sql" -> sql::open
            else -> { device, clock -> io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore(device, clock) }
        }
        val restart: (SyncStore) -> SyncStore = when (storeKind) {
            "sql" -> sql::reopen
            else -> { s -> (s as io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore).restarted() }
        }
        val skew = bigSkewMs ?: skewDays?.let { (it * 86_400_000).toLong() } ?: 0L
        val drivers = sql.drivers
        val sqlStores = sql.stores
        val reports = (0 until seeds).map { i ->
            val config = SyncSimulator.Config(
                peers = peers, ops = ops, seed = seed + i, partitions = partitions, reorder = reorder,
                bigSkewMs = skew, crashes = crashes, pageOps = page,
            )
            // T116: the run's peers in order (a restart takes its peer's place); the first gets --bundle.
            val live = ArrayList<SyncStore>()
            val seedFactory: (String, () -> Long) -> SyncStore = { device, clock ->
                factory(device, clock).also { s -> if (live.isEmpty() && seeded != null) s.apply(seeded, clock()); live += s }
            }
            val seedRestart: (SyncStore) -> SyncStore = { s -> restart(s).also { n -> live[live.indexOf(s)] = n } }
            val run = SyncSimulator(config, seedFactory, seedRestart).run { System.nanoTime() / 1_000_000 }
            // SQL peers: the tables must also be exactly the log's view.
            val tablesMatch = sqlStores.all { it.snapshot() == it.snapshotFromLog() }
            val bundle = seeded?.let { BundleCheck.json(bundleFile!!.path, it, live, changesRan = ops > 0) }
            drivers.forEach { it.close() }
            drivers.clear()
            sqlStores.clear()
            val bundleOk = bundle == null || bundle["ok"] == true
            val json = run.json + mapOf("store" to storeKind, "tablesMatchLog" to (if (storeKind == "sql") tablesMatch else null)) +
                (if (bundle != null) mapOf("bundle" to bundle) else emptyMap())
            SyncSimulator.Report(json, run.converged && tablesMatch && bundleOk) to tablesMatch
        }
        val allConverged = reports.all { it.first.converged }
        if (seeds == 1) {
            println(JsonWriter.write(reports.single().first.json))
        } else {
            println(
                JsonWriter.write(
                    linkedMapOf(
                        "command" to "sync-sim", "store" to storeKind, "peers" to peers, "ops" to ops, "partitions" to partitions, "reorder" to reorder,
                        "bigSkewMs" to skew, "crashes" to crashes, "page" to page,
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
                                "clamped" to merge["clamped"], "maxAheadMs" to merge["maxAheadMs"], "conflicts" to merge["conflicts"],
                                "crashes" to j["crashes"], "droppedByCrash" to msgs["droppedByCrash"], "pagesFollowed" to msgs["pagesFollowed"],
                                "ownClockLeadMs" to j["ownClockLeadMs"],
                                "appendFixes" to (j["changes"] as Map<*, *>)["appendFix"], "appendOpsInLog" to state["appendOpsInLog"],
                                "firstDivergence" to j["firstDivergence"],
                                "bundleOk" to (j["bundle"] as? Map<*, *>)?.get("ok"), // T116
                            )
                        },
                    ),
                ),
            )
        }
        if (!allConverged) throw ProgramResult(1)
    }

    /**
     * T116: a `--bundle` file's trips on the simulator's peers. [read] takes a `.dsync` file (gzipped, as
     * `POST /route/bundle` and the phone write it, or a raw bundle). [json]: per trip of the bundle (a route it puts
     * or sets a field of), its directions as the bundle alone materialises them ([Materializer.routePlan]: saved and
     * made for the trip's path) and, on every peer at the end, whether it holds the trip with the bundle's path and
     * how many saved directions count for it there; `ok` when every peer holding the bundle's path has the bundle's
     * directions for it, and some peer holds that path unless the run made changes of its own ([changesRan]: a trip
     * the simulator edited or deleted on every peer is listed as `changedInRun`, not counted).
     */
    internal object BundleCheck {
        fun read(bytes: ByteArray): Bundle =
            Bundle.decode(if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) Gzip.decompress(bytes) else bytes)

        fun json(file: String, bundle: Bundle, peers: List<SyncStore>, changesRan: Boolean = false): Map<String, Any?> {
            val ids = bundle.ops.filter { it.body.kind == EntityKind.ROUTE }.map { it.body.id }.distinct()
            var ok = true
            val trips = ids.map { id ->
                val want = Materializer.routePlan(id, bundle.ops.filter { it.body.kind == EntityKind.ROUTE && it.body.id == id })
                val dirs = want?.savedDirections
                val on = peers.map { p ->
                    val got = p.routePlan(id)
                    val samePath = got != null && want != null && got.path == want.path
                    val sameDirs = samePath && got!!.savedDirections == dirs
                    if (samePath && !sameDirs) ok = false
                    linkedMapOf("held" to (got != null), "samePath" to samePath, "directions" to got?.savedDirections?.size, "sameDirections" to sameDirs)
                }
                val changed = want != null && on.none { it["samePath"] == true }
                if (changed && !changesRan) ok = false // the bundle's version reached no peer, the first included
                linkedMapOf("id" to id, "name" to want?.name, "mode" to want?.path?.mode, "directions" to dirs?.size, "changedInRun" to changed.takeIf { changesRan }, "peers" to on)
            }
            return linkedMapOf(
                "file" to file, "from" to bundle.from, "ops" to bundle.ops.size, "trips" to trips.size,
                "withDirections" to trips.count { it["directions"] != null }, "list" to trips, "ok" to ok,
            )
        }
    }

    /** SQLite peers over in-memory databases; a restart reopens the same database with a new store (T51). */
    private class SqlPeers {
        val drivers = ArrayList<JdbcSqliteDriver>()
        val stores = ArrayList<SqlTrackStore>()
        private val clocks = java.util.IdentityHashMap<SyncStore, Pair<JdbcSqliteDriver, () -> Long>>()

        fun open(device: String, clock: () -> Long): SyncStore {
            val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema).also { drivers += it }
            return SqlTrackStore(d, clock) { device }.also { stores += it; clocks[it] = d to clock }
        }

        fun reopen(old: SyncStore): SyncStore {
            val (d, clock) = clocks.remove(old) ?: error("not a simulator store")
            val s = SqlTrackStore(d, clock) { old.deviceId }
            stores[stores.indexOf(old)] = s
            clocks[s] = d to clock
            return s
        }
    }
}

/**
 * `devtool sync ...` (T44): a JVM replica in a SQLite file (default the server peer's
 * `out/sync/peer.db`), for checking a device against the JVM store or moving `.dsync` files by hand:
 * - `sync status --db F`: device id, vector, counts, digest, whether the tables match the log.
 * - `sync with --db F --peer http://host:port | file.dsync [--page N]`: one sync (pull, merge, push),
 *   in pages of N operations each way (T51; default [Sync.DEFAULT_PAGE_OPS], 0 = one bundle each way).
 * - `sync gen --db F --ops N` (T51): adds a synthetic N-operation log from another device (for paging numbers).
 * - `sync export --db F --out x.dsync [--since <vector>]`, `sync import --db F --file x.dsync`.
 * - `sync import-gpx --db F --file x.gpx`, `sync rename --db F --track ID --name N`,
 *   `sync gpx --db F --track ID [--out file]` (writes the GPX, prints its size and SHA-256).
 * - T67: `sync import-route --db F --file x.gpx` (a route, as the phone's route import);
 *   `sync share --peer URL --route ID`, `sync links --peer URL`, `sync unshare --peer URL --link T`
 *   (trip share links on the server, as the token's user).
 * Against the `server` module each user has their own replica: `--token` (or `DYSTOPO_SYNC_TOKEN`) picks
 * the user, so `with --token <alice's>` syncs with alice's replica only.
 */
class SyncCommand : CliktCommand(name = "sync") {
    init {
        subcommands(Status(), With(), Export(), Import(), ImportGpx(), Rename(), GpxOut(), Gen(), ImportRoute(), Share(), Links(), Unshare())
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
        private val page by option("--page", help = "T51: operations per page each way (0: one bundle each way)").int().default(Sync.DEFAULT_PAGE_OPS)
        private val pageBytes by option("--page-bytes", help = "T51: about this many raw bytes per page at most").int().default(Sync.DEFAULT_PAGE_BYTES)
        private val token by option("--token", help = "T51: bearer token for a gated /sync (the server module; T67: the user's token picks their replica); default DYSTOPO_SYNC_TOKEN")

        override fun run() {
            val s = open()
            val heap0 = usedHeapMb()
            val r = Sync.exchange(s, transport(peer, token ?: System.getenv("DYSTOPO_SYNC_TOKEN").orEmpty()), System::currentTimeMillis, { System.nanoTime() / 1_000_000 }, page, pageBytes)
            print(linkedMapOf<String, Any?>("sync" to r.json(), "usedHeapMb" to listOf(heap0, usedHeapMb())) + mapOf("status" to status(s)))
        }

        private fun usedHeapMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
    }

    /**
     * `sync gen --db F --ops N` (T51): adds a synthetic log of about N operations from another device
     * to the replica, the shape a long T44-era recording has (one operation per fix): tracks of 1,000
     * fixes at 1 s near Paradise, each with a rename and two waypoints. For measuring paged syncs.
     */
    class Gen : DbCommand("gen") {
        private val ops by option("--ops").int().default(50_000)
        private val seed by option("--seed").long().default(51)

        override fun run() {
            val s = open()
            val rnd = kotlin.random.Random(seed)
            val ids = io.github.jasondorjeshort.dystopo.model.UuidGenerator(rnd)
            val device = ids.next()
            val clock = io.github.jasondorjeshort.dystopo.sync.HlcClock(device)
            var t = System.currentTimeMillis() - 30L * 86_400_000
            val all = ArrayList<io.github.jasondorjeshort.dystopo.sync.Op>(ops)
            fun op(b: io.github.jasondorjeshort.dystopo.sync.OpBody) { all += io.github.jasondorjeshort.dystopo.sync.Op(clock.tick(t), b) }
            while (all.size < ops) {
                val track = ids.next()
                op(io.github.jasondorjeshort.dystopo.sync.PutTrack(track, t, "Generated ${all.size}"))
                var lat = 46.78 + rnd.nextDouble() * 0.05
                var lon = -121.78 + rnd.nextDouble() * 0.05
                for (i in 0 until 1000) {
                    if (all.size >= ops) break
                    t += 1000
                    lat += (rnd.nextDouble() - 0.5) * 1e-4; lon += (rnd.nextDouble() - 0.5) * 1e-4
                    val p = io.github.jasondorjeshort.dystopo.model.TrackPoint(lat, lon, 1600.0 + rnd.nextDouble() * 400, t)
                    op(io.github.jasondorjeshort.dystopo.sync.AppendPoints(track, 0, listOf(p)))
                    if (i == 500 || i == 900) {
                        op(io.github.jasondorjeshort.dystopo.sync.PutWaypoint(track, io.github.jasondorjeshort.dystopo.model.Waypoint(ids.next(), "Mark $i", lat, lon, null, t)))
                    }
                }
                op(io.github.jasondorjeshort.dystopo.sync.SetField(io.github.jasondorjeshort.dystopo.sync.EntityKind.TRACK, track, "name", "Generated walk ${all.size}"))
            }
            val started = System.nanoTime()
            var base = VersionVector.EMPTY
            for (chunk in all.chunked(5000)) {
                val v = VersionVector(mapOf(device to chunk.last().hlc))
                s.apply(io.github.jasondorjeshort.dystopo.sync.Bundle(device, base, v, chunk), System.currentTimeMillis())
                base = v
            }
            print(linkedMapOf("generated" to all.size, "device" to device, "applyMs" to (System.nanoTime() - started) / 1_000_000) + mapOf("status" to status(s)))
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

    /** T67: a route GPX into the replica (ids kept, like the phone's `POST /routes/import`). */
    class ImportRoute : DbCommand("import-route") {
        private val file by option("--file").required()

        override fun run() {
            val s = open()
            val r = io.github.jasondorjeshort.dystopo.store.RouteImport.import(s, File(file).readText(), System.currentTimeMillis(), File(file).nameWithoutExtension)
            print(linkedMapOf("imported" to r.imported.map { it.id }, "skipped" to r.skipped, "waypoints" to r.waypoints))
        }
    }

    /** T67: share-link commands against a server peer, as the user whose token is given. */
    abstract class PeerCommand(name: String) : CliktCommand(name = name) {
        protected val peer by option("--peer", help = "http(s)://host:port of the server").required()
        protected val token by option("--token", help = "the user's bearer token; default DYSTOPO_SYNC_TOKEN")

        protected fun call(method: String, path: String): Pair<Int, String> {
            val c = URI(peer.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            (token ?: System.getenv("DYSTOPO_SYNC_TOKEN"))?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (method == "POST") { c.doOutput = true; c.outputStream.use { } }
            val code = c.responseCode
            val text = ((if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readAllBytes() } ?: ByteArray(0)).decodeToString()
            return code to text.trim()
        }

        protected fun answer(r: Pair<Int, String>) {
            println(JsonWriter.write(mapOf("status" to r.first, "body" to runCatching { io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.parse(r.second) }.getOrElse { r.second })))
            if (r.first !in 200..299) throw ProgramResult(1)
        }
    }

    class Share : PeerCommand("share") {
        private val route by option("--route", help = "route id (as in GET /sync/routes)").required()
        override fun run() = answer(call("POST", "/sync/share?route=" + java.net.URLEncoder.encode(route, Charsets.UTF_8)))
    }

    class Links : PeerCommand("links") {
        override fun run() = answer(call("GET", "/sync/share"))
    }

    class Unshare : PeerCommand("unshare") {
        private val link by option("--link", help = "the link's token (the part after /s/)").required()
        override fun run() = answer(call("DELETE", "/sync/share/" + link.substringAfterLast("/s/")))
    }

    companion object {
        /** HTTP for a URL, else a `.dsync` file. */
        fun transport(peer: String, token: String = ""): SyncTransport =
            if (peer.startsWith("http://") || peer.startsWith("https://")) HttpSyncTransport(peer, { u, b -> post(u, b, token) }, Gzip.compress, Gzip.decompress)
            else File(peer).absoluteFile.let { f ->
                f.parentFile.mkdirs()
                FileSyncTransport(f.path, { f.takeIf { it.isFile }?.readBytes() }, { f.writeBytes(it) }, Gzip.compress, Gzip.decompress)
            }

        fun post(url: String, body: ByteArray, token: String = ""): ByteArray {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readAllBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("POST $url: HTTP $code ${bytes.decodeToString().take(300)}")
            return bytes
        }
    }
}
