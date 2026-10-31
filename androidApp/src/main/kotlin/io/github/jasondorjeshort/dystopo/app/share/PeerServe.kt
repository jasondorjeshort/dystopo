package io.github.jasondorjeshort.dystopo.app.share

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.packs.CatalogPack
import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * T78, the sharing side of peer sources: while the Share screen is open this phone is a map source for the phones that
 * have its share code. Every route arrives inside the T72 sealed link ([ShareGate], `serve`), so whoever lacks the code
 * sees nothing (and gets 426 for the plain paths). Routes (inner paths):
 * - `GET /peer/catalog`: this phone's packs in the server's `/catalog` format ([PackCatalog], file URLs
 *   `/peer/file/<file>`, a SHA-256 per archive) plus `publisher`. The hashes are computed once per archive (recorded
 *   like a catalog download's), in the background when sharing starts ([warm]), so the first request is quick.
 * - `GET /peer/areas`: this phone's areas of interest (T75 records, without the tile lists);
 *   `GET /peer/areas/<id>`: one area's manifest (T75's format, `source` = this phone): a corridor's tile lists, or
 *   for a pack-backed box its archives as `pack` items with this link's URLs and SHA-256.
 * - `GET /peer/file/<file>?from=<offset>&len=<bytes>[&sha=<expected sha256>]`: up to [MAX_CHUNK] bytes of one pack
 *   archive (206, content type `application/octet-stream; first=<offset>; total=<size>`); 416 past the end, 404 for a
 *   file that is not one of this phone's archives, 409 when `sha` is not the archive's hash now (it changed: start over).
 * - `GET /dem/{z}/{x}/{y}.png`, `GET /osm/{z}/{x}/{y}.pbf`: the tile as stored in this phone's packs, else from its online
 *   tile cache (corridors it downloaded); 404 otherwise. Nothing is fetched or generated for a peer.
 * - `GET /peer/contours/{z}/{x}/{y}.pbf?key=<derived key>`: a contour tile, only when [derivedKey] here equals `key`
 *   (same contour version, units, tile size, interval and DEM content by SHA-256), else 409. Used by the derived-tile
 *   measurement (`POST /share/peers/bench`), not by the map: see docs/PLANNING.md › Sync, peer sources.
 * - `GET /live/info`: a tiny answer for the fetcher's reachability probe; `GET /version` is 404 (unversioned keys).
 * - `POST /peer/hello` (JSON `{"device", "name"}`): who is fetching, for the counters.
 * Every request names its sender with `by=<device id prefix>`; [json] counts requests, bytes, files and tiles per sender,
 * which the Share screen shows ("who is fetching and how much").
 */
class PeerServe(private val app: DystopoApp, private val publisher: () -> Map<String, Any?>) {
    class Fetcher(val id: String) {
        @Volatile var name: String? = null
        val requests = AtomicLong()
        val bytes = AtomicLong()
        val tiles = AtomicLong()
        val fileBytes = ConcurrentHashMap<String, AtomicLong>()
        @Volatile var firstAt = System.currentTimeMillis()
        @Volatile var lastAt = 0L
        @Volatile var lastWhat: String? = null

        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "requests" to requests.get(), "bytes" to bytes.get(), "tiles" to tiles.get(),
            "files" to fileBytes.mapValues { it.value.get() }.toSortedMap(), "firstAt" to firstAt, "lastAt" to lastAt, "lastWhat" to lastWhat,
        )
    }

    private val fetchers = ConcurrentHashMap<String, Fetcher>()
    val requests = AtomicLong()
    val bytesOut = AtomicLong()
    val catalogs = AtomicLong()
    val refusedFiles = AtomicLong()

    /** The catalog for the current pack files (keyed by their paths, sizes and mtimes). */
    @Volatile private var cached: Pair<String, PackCatalog>? = null
    @Volatile var catalogMs: Long? = null
        private set

    private val manager: PackManager get() = PackManager.get(app)

    fun handle(r: Request): Response? {
        requests.incrementAndGet()
        val by = r.query["by"]?.takeIf { ID.matches(it) } ?: "unknown"
        val f = fetchers.getOrPut(by) { Fetcher(by) }
        f.requests.incrementAndGet()
        f.lastAt = System.currentTimeMillis()
        val reply = try {
            route(r, f)
        } catch (e: Exception) {
            Log.w(TAG, "peer ${r.path} failed", e)
            Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 500)
        } ?: return null
        f.bytes.addAndGet(reply.body.size.toLong())
        bytesOut.addAndGet(reply.body.size.toLong())
        return reply
    }

    private fun route(r: Request, f: Fetcher): Response? {
        val p = r.path
        return when {
            r.method == "POST" && p == "/peer/hello" -> {
                val m = runCatching { MiniJson.parseObject(r.body.decodeToString()) }.getOrNull().orEmpty()
                f.name = (m["name"] as? String)?.take(60)
                Response.json(publisher() + mapOf("ok" to true))
            }
            p == "/peer/catalog" -> {
                catalogs.incrementAndGet()
                f.lastWhat = "catalog"
                Response.json(catalog().json())
            }
            p == "/peer/areas" -> Response.json(mapOf("publisher" to publisher(), "areas" to Areas.all().map { a -> a.json() - "area" + ("hasManifest" to (manifest(a.id) != null)) }))
            p.startsWith("/peer/areas/") -> {
                val m = manifest(p.removePrefix("/peer/areas/")) ?: return Response.json(mapOf("error" to "no such area"), 404)
                f.lastWhat = "area ${m.name}"
                Response.json(m.json())
            }
            p.startsWith(FILE_PREFIX) -> file(p.removePrefix(FILE_PREFIX), r.query, f)
            p.startsWith("/peer/contours/") -> contour(p.removePrefix("/peer/contours/"), r.query["key"], f)
            p == "/live/info" -> Response.json(mapOf("peer" to true) + publisher())
            p.startsWith("/dem/") || p.startsWith("/osm/") -> tile(p, f)
            else -> null
        }
    }

    /** The current catalog (hashes computed the first time; minutes for big packs on a slow phone, so [warm] runs it at start). */
    fun catalog(): PackCatalog {
        val listed = manager.list().filter { it.info.dem != null || it.info.vector != null }
        val key = listed.joinToString("|") { l -> (l.files.files + listOfNotNull(io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs.graphFileOf(l.files)?.takeIf { it.isFile })).joinToString(",") { "${it.path}:${it.length()}:${it.lastModified()}" } } // T189: and its graph
        cached?.let { (k, c) -> if (k == key) return c.copy(publisher = publisher()) }
        val t0 = System.currentTimeMillis()
        val profiles = runCatching { app.tiles.packs.regions.associate { it.name to it.osmCoverage?.profile?.label } }.getOrDefault(emptyMap())
        val packs = listed.mapNotNull { l ->
            if (l.errors.isNotEmpty()) return@mapNotNull null
            val inst = manager.catalogClient.installedNow(l.files)
            val built = listOfNotNull(l.files.dem, l.files.vector).maxOf { it.lastModified() }
            runCatching {
                CatalogPack.of(
                    l.info.name, l.info.dem, inst.sha256[PackRole.DEM], l.info.vector, inst.sha256[PackRole.VECTOR],
                    profiles[l.info.name], emptyList(), java.time.Instant.ofEpochMilli(built).toString(), built, urlPrefix = FILE_PREFIX,
                    derived = listOfNotNull(io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs.peerEntry(l.files, FILE_PREFIX)), // T189: its matching graph
                )
            }.onFailure { Log.w(TAG, "pack ${l.info.name} left out of the catalog: ${it.message}") }.getOrNull()
        }
        val c = PackCatalog(PackCatalog.VERSION, java.time.Instant.now().toString(), packs, publisher())
        cached = key to c
        catalogMs = System.currentTimeMillis() - t0
        return c
    }

    /** Computes the catalog (the archive hashes) now, off the caller's thread. */
    fun warm() {
        Thread({ runCatching { catalog() }.onFailure { Log.w(TAG, "catalog warm-up failed", it) } }, "peer-catalog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** One area's manifest as published to a peer: the saved one, or for a pack-backed area its archives. */
    private fun manifest(id: String): AreaManifest? {
        val rec = Areas.get(id) ?: return null
        val src = publisher()["name"] as? String
        Areas.manifest(rec.id)?.let { return it.copy(source = src) }
        val pack = rec.pack ?: return null
        val entry = catalog().find(pack) ?: return null
        val items = entry.files.map { cf ->
            FileItem(if (cf.role == PackRole.DEM) "dem" else "outdoors", entry.name, cf.file, cf.url, cf.bytes, cf.sha256, cf.tileType, cf.minZoom, cf.maxZoom, cf.bounds?.toList())
        }
        return AreaManifest(rec.id, rec.name, rec.area, AreaManifest.STATUS_READY, items, source = src, generatedAt = java.time.Instant.now().toString())
    }

    private fun file(name: String, q: Map<String, String>, f: Fetcher): Response {
        // T189: a pack's graph listed under `derived` is served like its archives.
        val graph = if ('/' in name || '\\' in name) null else manager.catalog().mapNotNull { io.github.jasondorjeshort.dystopo.app.packs.ShippedGraphs.graphFileOf(it) }.firstOrNull { it.name == name && it.isFile }
        if (graph == null && (PackNames.parse(name) == null || '/' in name || '\\' in name)) return Response.json(mapOf("error" to "not a pack file"), 404)
        val file = graph ?: manager.catalog().flatMap { it.files }.firstOrNull { it.name == name && it.isFile }
        if (file == null) {
            refusedFiles.incrementAndGet()
            return Response.json(mapOf("error" to "no such pack file"), 404)
        }
        val total = file.length()
        q["sha"]?.let { want ->
            val entry = catalog().packs.flatMap { it.files }.firstOrNull { it.file == name }?.sha256 ?: catalog().packs.flatMap { it.derived }.firstOrNull { it.file == name }?.sha256
            if (entry != null && !want.equals(entry, ignoreCase = true)) return Response.json(mapOf("error" to "file changed", "sha256" to entry), 409)
        }
        val from = q["from"]?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
        if (from >= total) return Response(416, "application/octet-stream; first=$from; total=$total", ByteArray(0))
        val len = (q["len"]?.toLongOrNull() ?: MAX_CHUNK.toLong()).coerceIn(1, MAX_CHUNK.toLong()).coerceAtMost(total - from).toInt()
        val bytes = ByteArray(len)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(bytes)
        }
        f.fileBytes.getOrPut(name) { AtomicLong() }.addAndGet(len.toLong())
        f.lastWhat = name
        return Response(206, "application/octet-stream; first=$from; total=$total", bytes)
    }

    private fun tile(path: String, f: Fetcher): Response {
        val parts = path.trim('/').split('/')
        if (parts.size != 4) return Response.json(mapOf("error" to "not a tile"), 404)
        val route = parts[0]
        val z = parts[1].toIntOrNull()
        val x = parts[2].toIntOrNull()
        val y = parts[3].substringBefore('.').toIntOrNull()
        if (z == null || x == null || y == null || z !in 0..20) return Response.json(mapOf("error" to "not a tile"), 404)
        val packs = app.tiles.packs
        val bytes = when (route) {
            "dem" -> packs.demCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) }
            else -> packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) }
        } ?: run {
            val cache = OnlineService.get(app).cache
            Areas.cacheKeys(route, "$z/$x/$y.${if (route == "dem") "png" else "pbf"}").firstNotNullOfOrNull { cache.get(it) }
        } ?: return Response.json(mapOf("error" to "not on this phone"), 404)
        f.tiles.incrementAndGet()
        f.lastWhat = "$route tiles"
        return Response(200, if (route == "dem") "image/png" else "application/x-protobuf", bytes)
    }

    private fun contour(zxy: String, key: String?, f: Fetcher): Response {
        val parts = zxy.substringBefore('.').split('/').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return Response.json(mapOf("error" to "not a tile"), 404)
        val (z, x, y) = parts
        val mine = derivedKey(app, z, x, y) ?: return Response.json(mapOf("error" to "no DEM pack here"), 404)
        if (key != mine) return Response.json(mapOf("error" to "derived key differs", "key" to mine), 409)
        val t0 = System.nanoTime()
        val tile = app.tiles.contourTile(z, x, y)
        val us = (System.nanoTime() - t0) / 1000
        f.tiles.incrementAndGet()
        f.lastWhat = "contour tiles"
        return Response(tile.status, "application/x-protobuf; us=$us", tile.body)
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "requests" to requests.get(), "bytesOut" to bytesOut.get(), "catalogs" to catalogs.get(), "catalogMs" to catalogMs,
        "refusedFiles" to refusedFiles.get(), "packs" to cached?.second?.packs?.map { it.name },
        "fetchers" to fetchers.values.sortedByDescending { it.lastAt }.map { it.json() },
    )

    companion object {
        const val TAG = "DystopoPeer"
        const val FILE_PREFIX = "/peer/file/"

        /** The largest file range one sealed reply carries (the reply is sealed and opened in memory). */
        const val MAX_CHUNK = 4 shl 20

        private val ID = Regex("[0-9a-zA-Z_-]{1,40}")

        /**
         * The full key of contour tile z/x/y on this phone: the contour cache directory (version, units, tile size,
         * interval) with the DEM archive named by its SHA-256 instead of the local file identity (which includes the
         * file's mtime, so it differs between two phones holding the same bytes). Null without a DEM pack there.
         */
        fun derivedKey(app: DystopoApp, z: Int, x: Int, y: Int): String? {
            val pack = app.tiles.packs.demFor(z, x, y, io.github.jasondorjeshort.dystopo.packs.PackRoleZooms.CONTOUR) ?: return null
            val manager = PackManager.get(app)
            val files = manager.catalog().firstOrNull { it.dem?.absolutePath == pack.file.absolutePath } ?: return null
            val sha = manager.catalogClient.installedNow(files).sha256[PackRole.DEM] ?: return null
            return app.tiles.contourDir(pack).substringBeforeLast('-') + "-sha" + sha.take(16)
        }
    }
}
