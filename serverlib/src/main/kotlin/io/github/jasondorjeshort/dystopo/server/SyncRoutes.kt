package io.github.jasondorjeshort.dystopo.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * The devtool server as the always-on sync peer (T44), in both `serve` modes and in the `server`
 * module. T67: one replica **per user**. The gate in front ([Users], the `server` module's `Gate`) puts the
 * user a request runs as in the exchange attribute [Users.ATTR]; without one (`devtool serve`, an open
 * server, the T52 single token) it is the default replica [Users.DEFAULT], `out/sync/peer.db`
 * (`DYSTOPO_SYNC_DB` or [dbOverride]). User `<id>`'s replica is `<userDir>/<id>.db` (default: the directory
 * of the default replica, so `out/sync/<id>.db`). Each replica is created on its first request, with its
 * own device id, and two users never see each other's operations. Routes:
 * - `POST /sync/pull?since=<vector>`: gzipped bundle of every operation after the vector.
 * - `POST /sync/push` with a gzipped bundle: merges it; JSON counts and the vector afterwards.
 * - `GET /sync/status`: device id, vector, operation count, state summary and digest, request counts,
 *   plus `user`, `db` and `tablesMatchLog`.
 * - `GET /sync/tracks`: the tracks this replica holds; `GET /sync/tracks/{id}.gpx`: one as GPX (to
 *   compare with a device's `GET /tracks/{id}.gpx`). T67: `GET /sync/routes`: the routes it holds; T92: with
 *   `?full=1` each route's `path` value and `waypointList` too (the browser's trip editor pulls with it).
 * - T92: `POST /sync/waypoints/encode`: waypoints as `wpt:` field values for the browser's waypoint edits.
 * - T67 share links ([ShareLinks]): `POST /sync/share?route=<id>`, `GET /sync/share`,
 *   `DELETE /sync/share/<token>`, and the open `GET /s/<token>[.gpx|.json]`.
 * Calls on one replica are serialised on its lock (the store is not thread-safe and a merge is one
 * transaction); different users' replicas run in parallel.
 */
object SyncRoutes {
    /** The standalone server's configured default replica file (T52); set before the first sync request. */
    @Volatile var dbOverride: File? = null

    /** T67: where user replicas live; null: the default replica's directory. */
    @Volatile var userDir: File? = null

    /** T67: base URL for share links (`publicUrl`); null: from the request (`X-Forwarded-*`, else `Host`). */
    @Volatile var publicUrl: String? = null

    private val dbFile: File get() = dbOverride ?: File(System.getenv("DYSTOPO_SYNC_DB")?.takeIf { it.isNotBlank() } ?: "out/sync/peer.db")

    private val usersDir: File get() = userDir ?: dbFile.absoluteFile.parentFile

    /** One replica: a SQLite file, its store and protocol server, and the lock its calls take. */
    class Replica(val user: String, val file: File) {
        private val driverLazy = lazy {
            file.absoluteFile.parentFile.mkdirs()
            JdbcSqliteDriver("jdbc:sqlite:" + file.absolutePath.replace('\\', '/'), Properties(), TrackDb.Schema)
        }
        val store: SqlTrackStore by lazy { SqlTrackStore(driverLazy.value, System::currentTimeMillis) }

        fun close() { if (driverLazy.isInitialized()) runCatching { driverLazy.value.close() } }
        val server: SyncServer by lazy { SyncServer(store, System::currentTimeMillis, Gzip.compress, Gzip.decompress) }
        @Volatile var lastRequestAt = 0L
    }

    /** Open replicas by absolute file path (a changed [dbOverride] or [userDir] opens new ones). */
    private val replicas = ConcurrentHashMap<String, Replica>()

    /** The replica file of user key [user] ([Users.DEFAULT]: the default replica). */
    fun replicaFile(user: String): File = if (user == Users.DEFAULT) dbFile else File(usersDir, "$user.db")

    fun replica(user: String): Replica {
        require(user == Users.DEFAULT || Users.validId(user)) { "bad user id" }
        val file = replicaFile(user).absoluteFile
        return replicas.computeIfAbsent(file.path) { Replica(user, file) }
    }

    /** Runs [block] on [user]'s store under its lock. */
    fun <T> withStore(user: String, block: (SqlTrackStore) -> T): T {
        val r = replica(user)
        return synchronized(r) { block(r.store) }
    }

    /** Replica files on disk (`*.db` in the user directory, plus the default one): for `/healthz` and `/admin/users`. */
    fun replicaFiles(): List<File> {
        val files = usersDir.listFiles { f -> f.isFile && f.name.endsWith(".db") }.orEmpty().toMutableList()
        if (dbFile.isFile && files.none { it.absoluteFile == dbFile.absoluteFile }) files += dbFile
        return files.sortedBy { it.name }
    }

    /** Replicas opened since the process started. */
    fun openReplicas(): Int = replicas.size

    /** Closes every open replica and forgets the share-link file (tests that start several servers). */
    fun reset() {
        synchronized(replicas) {
            replicas.values.forEach { r -> synchronized(r) { r.close() } }
            replicas.clear()
            sharesRef = null
        }
    }

    @Volatile private var sharesRef: ShareLinks? = null

    /** T67: the share links of every user, in `<userDir>/shares.json`. */
    val shares: ShareLinks
        get() {
            val f = File(usersDir, "shares.json").absoluteFile
            sharesRef?.takeIf { it.file == f }?.let { return it }
            return synchronized(replicas) { sharesRef?.takeIf { it.file == f } ?: ShareLinks(f).also { sharesRef = it } }
        }

    /** The route [route] in [user]'s replica, or null (no such user replica on disk, or no such route). */
    fun routeOf(user: String, route: String): RoutePlan? {
        if (user != Users.DEFAULT && !replicaFile(user).isFile) return null
        return withStore(user) { it.routePlan(route) }
    }

    /** One line per user for `/admin/users`: replica size, operations, tracks, routes, links, last request. */
    fun userSummary(user: String): Map<String, Any?> {
        val f = replicaFile(user)
        val base = linkedMapOf<String, Any?>("id" to user, "db" to f.name, "bytes" to f.takeIf { it.isFile }?.length())
        if (f.isFile) withStore(user) { s ->
            base["ops"] = s.opCount
            base["tracks"] = s.list().size
            base["routes"] = s.routeList().size
            base["deviceId"] = s.deviceId
        }
        base["shareLinks"] = shares.list(user).size
        base["lastRequestAt"] = replicas[f.absolutePath]?.lastRequestAt?.takeIf { it > 0 }
        return base
    }

    private const val MAX_BODY = 256L shl 20

    /** T74: the most a pushed bundle may inflate to (a phone pushes pages of 2,000 operations, a few MB at most). */
    const val MAX_PUSH_INFLATED_BYTES = 64L shl 20

    /** T74: the most operations one push may carry (`SyncServer.MAX_PAGE_OPS`, the largest page a pull hands out). */
    const val MAX_PUSH_OPS = 100_000

    sealed class PushCheck {
        /** The bundle uncompressed, ready for the merge. */
        class Ok(val raw: ByteArray, val ops: Int) : PushCheck()
        class Refused(val status: Int, val message: String) : PushCheck()
    }

    /**
     * T74: inflates a pushed body (gzip or raw) without ever holding more than [maxInflated] bytes and counts its
     * operations: 413 past either cap, 400 for something that is not a bundle.
     */
    fun checkPush(body: ByteArray, maxInflated: Long = MAX_PUSH_INFLATED_BYTES, maxOps: Int = MAX_PUSH_OPS): PushCheck {
        val raw = if (io.github.jasondorjeshort.dystopo.sync.Bundle.isRaw(body)) {
            if (body.size > maxInflated) return PushCheck.Refused(413, "bundle larger than $maxInflated bytes; push in pages")
            body
        } else {
            try {
                java.util.zip.GZIPInputStream(body.inputStream()).use { input ->
                    val out = java.io.ByteArrayOutputStream(minOf(maxInflated, body.size.toLong() * 4 + 1024).toInt())
                    val buf = ByteArray(64 shl 10)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxInflated) return PushCheck.Refused(413, "bundle inflates past $maxInflated bytes; push in pages")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } catch (e: java.io.IOException) {
                return PushCheck.Refused(400, "not a sync bundle (gzip: ${e.javaClass.simpleName}: ${e.message})")
            }
        }
        val bundle = try {
            io.github.jasondorjeshort.dystopo.sync.Bundle.decode(raw)
        } catch (e: IllegalArgumentException) {
            return PushCheck.Refused(400, e.message ?: "not a sync bundle")
        }
        if (bundle.ops.size > maxOps) return PushCheck.Refused(413, "bundle has ${bundle.ops.size} operations, more than $maxOps; push in pages")
        return PushCheck.Ok(raw, bundle.ops.size)
    }

    /** Answers a `/sync` or `/s/` request and returns true, or returns false for any other path. */
    fun handle(ex: HttpExchange): Boolean {
        val path = ex.requestURI.path
        if (path.startsWith("/s/")) return shares.serve(ex, ::routeOf)
        if (path != "/sync" && !path.startsWith("/sync/")) return false
        val query = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        if (length > MAX_BODY) return reply(ex, 413, "application/json", JsonWriter.write(mapOf("error" to "bundle larger than $MAX_BODY bytes")).toByteArray())
        val body = ex.requestBody.use { it.readAllBytes() }
        val user = ex.getAttribute(Users.ATTR) as? String ?: Users.DEFAULT
        val rep = replica(user)
        rep.lastRequestAt = System.currentTimeMillis()
        if (path == "/sync/share" || path.startsWith("/sync/share/")) return share(ex, path, query, rep)
        if (path == "/sync/waypoints/encode") return encodeWaypoints(ex, body)
        synchronized(rep) {
            val store = rep.store
            val dbFile = rep.file
            when {
                path == "/sync/tracks" && ex.requestMethod == "GET" -> {
                    val list = store.list().map { mapOf("id" to it.id, "name" to it.name, "createdAt" to it.createdAt, "points" to it.points, "waypoints" to it.waypoints) }
                    return reply(ex, 200, "application/json", JsonWriter.write(mapOf("user" to user, "db" to dbFile.path, "tracks" to list)).toByteArray())
                }
                path.startsWith("/sync/tracks/") && path.endsWith(".gpx") && ex.requestMethod == "GET" -> {
                    val gpx = TrackExport.gpx(store, path.removePrefix("/sync/tracks/").removeSuffix(".gpx"))
                        ?: return reply(ex, 404, "application/json", JsonWriter.write(mapOf("error" to "no such track")).toByteArray())
                    return reply(ex, 200, "application/gpx+xml", gpx.toByteArray())
                }
                path == "/sync/routes" && ex.requestMethod == "GET" -> {
                    // T92: ?full=1 adds each route's path value and waypoints, the pull side of the browser's trip replica.
                    val full = query["full"] == "1"
                    val list = store.routeList().map { item -> if (full) item.json() + (store.routePlan(item.id)?.let(::routeValues) ?: emptyMap()) else item.json() }
                    return reply(ex, 200, "application/json", JsonWriter.write(mapOf("user" to user, "routes" to list)).toByteArray())
                }
            }
            // T74: a push is inflated with a cap and its operations counted before the merge (a 1 MB gzip of zeros
            // inflated to 1 GiB and ran the server out of memory behind a valid token, T82).
            val input = if (path == "/sync/push" && ex.requestMethod == "POST" && body.isNotEmpty()) {
                when (val checked = checkPush(body)) {
                    is PushCheck.Refused -> return reply(ex, checked.status, "application/json", (JsonWriter.write(mapOf("error" to checked.message)) + "\n").toByteArray())
                    is PushCheck.Ok -> checked.raw
                }
            } else body
            val r = rep.server.handle(ex.requestMethod, path, query, input)!!
            val bytes = if (path == "/sync/status" && r.status == 200) {
                // Add whose replica it is, where it lives and whether its tables match its log.
                val extra = ",\"user\":" + JsonWriter.write(user) + ",\"db\":" + JsonWriter.write(dbFile.path) + ",\"tablesMatchLog\":" + (store.snapshot() == store.snapshotFromLog()) + "}"
                (r.body.decodeToString().trimEnd().removeSuffix("}") + extra + "\n").toByteArray()
            } else r.body
            return reply(ex, r.status, r.contentType, bytes)
        }
    }

    /** T92: what `GET /sync/routes?full=1` adds to a route's list item: its `path` value and its waypoints. */
    fun routeValues(plan: RoutePlan): Map<String, Any?> = linkedMapOf(
        "path" to plan.path.quantized().encode(),
        "waypointList" to plan.waypoints.map { w ->
            linkedMapOf("id" to w.id, "name" to w.name, "lat" to w.lat, "lon" to w.lon, "ele" to w.ele, "description" to w.description, "symbol" to w.symbol)
        },
    )

    /** T92: the most waypoints one `POST /sync/waypoints/encode` takes. */
    const val MAX_ENCODE_WAYPOINTS = 1000

    /**
     * T92: `POST /sync/waypoints/encode` with `{"waypoints": [{"id", "name", "lat", "lon", "ele"?, "description"?, "symbol"?}]}`
     * answers `{"values": {"<id>": "<wpt: value>"}}`: each waypoint in the encoding of a route's `wpt:<id>` field
     * (RouteOps.encodeWaypoint), so the browser's trip editor writes per-waypoint operations like the phone's
     * without a copy of the binary format. Ids are lowercase UUIDs; nothing is stored.
     */
    fun encodeWaypoints(ex: HttpExchange, body: ByteArray): Boolean {
        fun json(status: Int, m: Map<String, Any?>) = reply(ex, status, "application/json", (JsonWriter.write(m) + "\n").toByteArray())
        if (ex.requestMethod != "POST") return json(405, mapOf("error" to "POST {\"waypoints\": [...]}"))
        val values = linkedMapOf<String, String>()
        try {
            val root = io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.parseObject(body.decodeToString())
            val list = root["waypoints"] as? List<*> ?: throw IllegalArgumentException("waypoints: [...] required")
            require(list.size <= MAX_ENCODE_WAYPOINTS) { "at most $MAX_ENCODE_WAYPOINTS waypoints" }
            for (item in list) {
                val o = item as? Map<*, *> ?: throw IllegalArgumentException("each waypoint is an object")
                val id = o["id"] as? String ?: throw IllegalArgumentException("id required")
                require(io.github.jasondorjeshort.dystopo.model.Uuid.isValid(id) && id == id.lowercase()) { "id must be a lowercase UUID (got '$id')" }
                val name = (o["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("name required")
                require(name.length <= 200) { "name longer than 200 characters" }
                val lat = (o["lat"] as? Number)?.toDouble() ?: throw IllegalArgumentException("lat required")
                val lon = (o["lon"] as? Number)?.toDouble() ?: throw IllegalArgumentException("lon required")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "lat or lon out of range" }
                val w = io.github.jasondorjeshort.dystopo.model.Waypoint(
                    id, name, lat, lon, (o["ele"] as? Number)?.toDouble(),
                    description = (o["description"] as? String)?.takeIf { it.isNotBlank() }, symbol = (o["symbol"] as? String)?.takeIf { it.isNotBlank() },
                )
                values[id] = io.github.jasondorjeshort.dystopo.sync.RouteOps.encodeWaypoint(w)
            }
        } catch (e: IllegalArgumentException) {
            return json(400, mapOf("error" to (e.message ?: "bad request")))
        }
        return json(200, mapOf("values" to values))
    }

    /** `POST /sync/share?route=`, `GET /sync/share`, `DELETE /sync/share/<token>` for the request's user. */
    private fun share(ex: HttpExchange, path: String, query: Map<String, String>, rep: Replica): Boolean {
        val base = publicUrl?.trimEnd('/') ?: LiveTiles.requestBase(ex, "http://127.0.0.1")
        fun json(status: Int, m: Map<String, Any?>) = reply(ex, status, "application/json", (JsonWriter.write(m) + "\n").toByteArray())
        val user = rep.user
        return when {
            path == "/sync/share" && ex.requestMethod == "POST" -> {
                val route = query["route"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return json(400, mapOf("error" to "route=<id> required"))
                val plan = synchronized(rep) { rep.store.routePlan(route) }
                    ?: return json(404, mapOf("error" to "no route $route in this user's replica on the server; sync the device first"))
                val (link, existing) = shares.create(user, route, System.currentTimeMillis())
                json(200, link.json(base) + mapOf("name" to plan.name, "existing" to existing))
            }
            path == "/sync/share" && ex.requestMethod == "GET" -> {
                val names = synchronized(rep) { rep.store.routeList().associate { it.id to it.name } }
                json(200, mapOf("user" to user, "links" to shares.list(user).map { it.json(base) + mapOf("name" to names[it.route], "routeExists" to (it.route in names)) }))
            }
            path.startsWith("/sync/share/") && ex.requestMethod == "DELETE" -> {
                val token = path.removePrefix("/sync/share/")
                if (shares.revoke(token, user)) json(200, mapOf("revoked" to token)) else json(404, mapOf("error" to "no such link"))
            }
            else -> json(405, mapOf("error" to "POST /sync/share?route=, GET /sync/share, DELETE /sync/share/<token>"))
        }
    }

    private fun reply(ex: HttpExchange, status: Int, type: String, bytes: ByteArray): Boolean {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        return true
    }
}
