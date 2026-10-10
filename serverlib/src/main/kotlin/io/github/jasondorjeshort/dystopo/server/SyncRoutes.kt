package io.github.jasondorjeshort.dystopo.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * The devtool server as the always-on sync peer (T44), in both `serve` modes. Its replica is a
 * [SqlTrackStore] in a SQLite file, `out/sync/peer.db` by default (`DYSTOPO_SYNC_DB` overrides; the
 * file and its device id are created on the first sync request). Routes:
 * - `POST /sync/pull?since=<vector>`: gzipped bundle of every operation after the vector.
 * - `POST /sync/push` with a gzipped bundle: merges it; JSON counts and the vector afterwards.
 * - `GET /sync/status`: device id, vector, operation count, state summary and digest, request counts.
 * - `GET /sync/tracks`: the tracks this peer holds; `GET /sync/tracks/{id}.gpx`: one as GPX (to
 *   compare with a device's `GET /tracks/{id}.gpx`).
 * All calls are serialised on one lock: the store is not thread-safe and a merge is one transaction.
 */
object SyncRoutes {
    /** The standalone server's configured replica file (T52); set before the first sync request. */
    @Volatile var dbOverride: File? = null

    private val dbFile: File get() = dbOverride ?: File(System.getenv("DYSTOPO_SYNC_DB")?.takeIf { it.isNotBlank() } ?: "out/sync/peer.db")

    private val store: SqlTrackStore by lazy {
        dbFile.absoluteFile.parentFile.mkdirs()
        SqlTrackStore(JdbcSqliteDriver("jdbc:sqlite:" + dbFile.absolutePath.replace('\\', '/'), Properties(), TrackDb.Schema), System::currentTimeMillis)
    }

    private val server: SyncServer by lazy { SyncServer(store, System::currentTimeMillis, Gzip.compress, Gzip.decompress) }

    private const val MAX_BODY = 256L shl 20

    /** Answers a `/sync` request and returns true, or returns false for any other path. */
    fun handle(ex: HttpExchange): Boolean {
        val path = ex.requestURI.path
        if (path != "/sync" && !path.startsWith("/sync/")) return false
        val query = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        if (length > MAX_BODY) return reply(ex, 413, "application/json", JsonWriter.write(mapOf("error" to "bundle larger than $MAX_BODY bytes")).toByteArray())
        val body = ex.requestBody.use { it.readAllBytes() }
        synchronized(this) {
            when {
                path == "/sync/tracks" && ex.requestMethod == "GET" -> {
                    val list = store.list().map { mapOf("id" to it.id, "name" to it.name, "createdAt" to it.createdAt, "points" to it.points, "waypoints" to it.waypoints) }
                    return reply(ex, 200, "application/json", JsonWriter.write(mapOf("db" to dbFile.path, "tracks" to list)).toByteArray())
                }
                path.startsWith("/sync/tracks/") && path.endsWith(".gpx") && ex.requestMethod == "GET" -> {
                    val gpx = TrackExport.gpx(store, path.removePrefix("/sync/tracks/").removeSuffix(".gpx"))
                        ?: return reply(ex, 404, "application/json", JsonWriter.write(mapOf("error" to "no such track")).toByteArray())
                    return reply(ex, 200, "application/gpx+xml", gpx.toByteArray())
                }
            }
            val r = server.handle(ex.requestMethod, path, query, body)!!
            val bytes = if (path == "/sync/status" && r.status == 200) {
                // Add where the replica lives and whether its tables match its log.
                val extra = ",\"db\":" + JsonWriter.write(dbFile.path) + ",\"tablesMatchLog\":" + (store.snapshot() == store.snapshotFromLog()) + "}"
                (r.body.decodeToString().trimEnd().removeSuffix("}") + extra + "\n").toByteArray()
            } else r.body
            return reply(ex, r.status, r.contentType, bytes)
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
