package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.RouteWaypoints
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.DriveOverlay
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.GraphTileArchive
import io.github.jasondorjeshort.dystopo.routing.GraphTileCache
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.GraphTiler
import io.github.jasondorjeshort.dystopo.routing.OverlayCellFile
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.TileRouter
import io.github.jasondorjeshort.dystopo.routing.TiledGraph
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.Delete
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.Hlc
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.PutRoute
import io.github.jasondorjeshort.dystopo.sync.SetField
import io.github.jasondorjeshort.dystopo.sync.SyncFormatException
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Route planning in the browser (T68): the server plans on the trail graph of each pack in `out/`,
 * with the same shared code as the phone (T45's [Router], T60's [RouteEditor] and [RouteSummary]),
 * so a route planned on the desktop has the phone's numbers. Registered by one line in
 * [LiveTiles] (`devtool serve --live` and the standalone server). The routes are stateless: the
 * page holds the route as its encoded `path` value (T60's [RoutePath.encode], the value a trip
 * stores in the sync log) and sends it back with each edit.
 *
 * - `GET /route?from=lat,lon&to=lat,lon[&via=lat,lon;lat,lon][&mode=hike|bike|drive|crow][&pack=<name>][&snap=m]` (T108: crow, straight legs between the points, no graph)
 *   (or `points=lat,lon;lat,lon;...`, 2 to 50): a route through the points (each snapped onto a trail
 *   within `snap` metres, default 300 as on the phone; legs along trails, straight where no trail
 *   connects). The reported pack is `pack=` (a catalog id: `<name>` of `out/<name>-dem.pmtiles` plus
 *   its vector archive; 400 when unknown), else the first pack whose vector archive holds both ends
 *   (then the start). T76: the route runs over that pack and every other pack whose box comes within
 *   [RoutePlanner.NEAR_M] of the points, one tiled network, so it crosses pack borders. Each pack's
 *   graph is built on first use into `<vector archive>-graph.idx` next to it (the phone's and
 *   `devtool route`'s file name, z11 graph tiles read on demand into one LRU), reused while both
 *   archives are unchanged. Straight legs take their elevation every 25 m from the DEM (packs first,
 *   else the live `best` source). T77: drives whose ends are 40 km or more apart search the tiles away from them
 *   through [DriveOverlay] (`overlay=0` to compare without); the reply's `graph.searches` has each leg's search
 *   (T89: `level2Tiles`, the z8 cells it went through). T89: once a pack's graph is open its overlay cells
 *   (z11 and z8) are built in the background and written to `<vector archive>-overlay.idx` next to the graph
 *   file; a restarted server reads them from there instead of building them (`driveOverlay.warm` in the status).
 * - `POST /route/edit` with JSON `{"path": "<encoded>", "op": "move|insert|add|remove|mode|reroute|show",
 *   "index": i, "lat": .., "lon": .., "mode": "bike", "pack": "<name>"}`: T60's editor operations
 *   (move and remove reroute only the legs next to the via point; add goes into the leg it lies
 *   nearest to when within 150 m, else becomes the new end, and answers `index`; show returns the path
 *   unchanged, for a page opening a saved trip).
 *   Both answer `pack`, `mode`, `path` (the encoded value), `via` ([lat, lon] each), `legs` (`kind`
 *   trail/straight/fixed, `points`, `coordinates` as [lon, lat, ele]), `summary` ([RouteSummary]:
 *   distance, ascent, descent, hike-model time, metres on straight legs, 100-sample profile),
 *   `notes` (why legs are straight: `start_off_network`, `not_connected`, `no_graph`, `drive_unsupported`, ...),
 *   `messages` (T73: the router's message for each note), `notice` (for `mode=drive`, `Router.DRIVE_NOTICE`: what drive
 *   mode does and does not do, ADR 0032), `instructions` (T102: the turn directions along the whole path, every leg,
 *   kept or rerouted: `type`, `priority`, `text`, `alongM`, `lat`, `lon`, `name`, `class`, `turnDeg`, `bearingDeg`,
 *   `leadM`, `stretchM`, `via`; see `Directions`), `graph`
 *   (`loaded`, `built` or `cached`, with timings: the reported pack's, every pack used under `graphs`) and `ms`. Bad input is a 400 with `error`.
 * - `POST /route/gpx` with JSON `{"path", "name", "id"}`: the route as GPX ([RouteGpx]: one `rte`,
 *   via marks, our ids), byte for byte what the phone exports for the same trip.
 * - `POST /route/bundle` with JSON `{"device": "<uuid>", "ops": [...]}`: the browser's own replica
 *   (the page keeps a local operation log of its trips) as a gzipped sync bundle, ready for
 *   `POST /sync/push` (with the page's token where the server wants one) or to save as a `.dsync`
 *   file. Ops are route ops of that one device: `{"hlc": "<HLC text>", "type": "put", "id", "name"}`,
 *   `{"hlc", "type": "set", "id", "field": "path|waypoints|name|...", "value"}`, `{"hlc", "type":
 *   "delete", "id"}`; a `path` or `waypoints` value must decode. The bundle's base is empty and it
 *   holds every op the page sends, so a push is idempotent.
 * - `GET /route/packs`: packs with a vector archive (`name`, files, `bounds`, graph state);
 *   `GET /route/status`: request counts, graph builds and timings, `tileCache` (T76: graph tiles in memory, loads, hits, evictions; T89: `corruptTiles`, `corruptHits`), `corruptGraphTiles` (T89: tiles that failed their checksum, skipped without reading them again) and `lastRouteMemory` (the last route's tiles, their bytes, the search's own).
 * - `GET /trips.js` (also `/tools/demo/trips.js`): the page's trip editor script, `tools/demo/trips.js`
 *   next to the served page.
 */
object RouteRoutes {
    private val planners = ConcurrentHashMap<String, RoutePlanner>()

    /** The planner for [live]'s `out/`, with straight-leg elevation from its DEM (packs, then live). */
    fun planner(live: LiveTiles): RoutePlanner = planners.computeIfAbsent(live.outDir.absoluteFile.path) {
        RoutePlanner(live.outDir, liveSampler(live))
    }

    /** Answers `/route*` and the trip script; false for any other path. */
    fun handle(ex: HttpExchange, live: LiveTiles, demoPage: File): Boolean {
        val path = ex.requestURI.path
        if (path == "/trips.js" || path == "/tools/demo/trips.js") {
            val f = listOf(File(demoPage.absoluteFile.parentFile, "trips.js"), File("tools/demo/trips.js")).firstOrNull { it.isFile }
            if (f == null) reply(ex, 404, "text/plain", "no trips.js next to the page\n".toByteArray())
            else reply(ex, 200, "text/javascript; charset=utf-8", f.readBytes())
            return true
        }
        if (path != "/route" && !path.startsWith("/route/")) return false
        val p = planner(live)
        val (status, body) = try {
            val q = query(ex)
            val method = ex.requestMethod
            val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
            if (length > MAX_BODY) return reply(ex, 413, mapOf("error" to "body larger than $MAX_BODY bytes"))
            val text = ex.requestBody.use { it.readNBytes(MAX_BODY.toInt() + 1) }.also {
                if (it.size > MAX_BODY) return reply(ex, 413, mapOf("error" to "body larger than $MAX_BODY bytes"))
            }.decodeToString()
            when {
                path == "/route" && method == "GET" -> 200 to p.plan(points(q), q["mode"] ?: "hike", q["pack"], snapOf(q["snap"]), overlay = q["overlay"] != "0")
                path == "/route/edit" && method == "POST" -> 200 to p.edit(MiniJson.parseObject(text.ifBlank { throw IllegalArgumentException("POST a JSON body") }))
                path == "/route/gpx" && method == "POST" -> {
                    val gpx = p.gpx(MiniJson.parseObject(text.ifBlank { throw IllegalArgumentException("POST a JSON body") }))
                    return reply(ex, 200, "application/gpx+xml", gpx.toByteArray())
                }
                path == "/route/bundle" && method == "POST" -> {
                    val bytes = p.bundle(MiniJson.parseObject(text.ifBlank { throw IllegalArgumentException("POST a JSON body") }))
                    return reply(ex, 200, "application/octet-stream", bytes)
                }
                path == "/route/packs" && method == "GET" -> 200 to mapOf("packs" to p.packsJson())
                path == "/route/status" && method == "GET" -> 200 to p.status()
                path in setOf("/route", "/route/edit", "/route/gpx", "/route/bundle", "/route/packs", "/route/status") ->
                    405 to mapOf("error" to "method $method not allowed on $path")
                else -> 404 to mapOf("error" to "no such route: $path")
            }
        } catch (e: IllegalArgumentException) {
            p.errors.incrementAndGet()
            400 to mapOf("error" to (e.message ?: e.toString()))
        }
        return reply(ex, status, body)
    }

    private const val MAX_BODY = 8L shl 20

    /** `points=`, else `from=` + `via=` + `to=`. */
    fun points(q: Map<String, String>): List<Pair<Double, Double>> {
        q["points"]?.let { s -> return s.split(';').filter { it.isNotBlank() }.map { latLon(it) } }
        val from = q["from"] ?: throw IllegalArgumentException("from=lat,lon and to=lat,lon (or points=lat,lon;lat,lon) required")
        val to = q["to"] ?: throw IllegalArgumentException("to=lat,lon required")
        val via = q["via"]?.split(';', '|')?.filter { it.isNotBlank() }?.map { latLon(it) } ?: emptyList()
        return listOf(latLon(from)) + via + listOf(latLon(to))
    }

    fun latLon(s: String): Pair<Double, Double> {
        val parts = s.split(',').map { it.trim().toDoubleOrNull() }
        val lat = parts.getOrNull(0)
        val lon = parts.getOrNull(1)
        require(parts.size == 2 && lat != null && lon != null && lat in -85.0..85.0 && lon in -180.0..180.0) { "bad point '$s' (lat,lon in degrees)" }
        return lat to lon
    }

    private fun snapOf(s: String?): Double = s?.let {
        val v = it.toDoubleOrNull()
        require(v != null && v in 10.0..2000.0) { "snap must be 10 to 2000 metres" }
        v
    } ?: Router.DEFAULT_SNAP_M

    /** Elevation for straight legs: the DEM tiles `/dem` serves (packs first, else live), sampled at z13 like the phone. */
    private fun liveSampler(live: LiveTiles): ElevationSampler {
        val pyramid = DemPyramid(LiveTiles.LIVE_MIN_ZOOM, 13, demDecoder, 64) { z, x, y -> live.dem(z, x, y)?.bytes }
        val dem = DemElevation(pyramid)
        return ElevationSampler { lat, lon -> synchronized(dem) { dem.elevation(lat, lon) } }
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    private fun reply(ex: HttpExchange, status: Int, body: Map<String, Any?>): Boolean =
        reply(ex, status, "application/json", (JsonWriter.write(body) + "\n").toByteArray())

    private fun reply(ex: HttpExchange, status: Int, type: String, bytes: ByteArray): Boolean {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        return true
    }
}

/**
 * The work behind [RouteRoutes], without HTTP (tests call it directly): packs in [outDir] with a
 * vector archive, their trail graphs (built on demand next to the pack, cached in memory while the
 * archives are unchanged), planning and editing through [RouteEditor], GPX, and sync bundles of the
 * browser's route ops. [elevation] samples straight legs (null: their two ends only).
 */
class RoutePlanner(val outDir: File, private val elevation: ElevationSampler? = null) {
    /** A pack the planner can route on: its vector archive (the graph's source) and DEM. */
    class Pack(val name: String, val vector: File, val dem: File?, val bounds: BoundingBox)

    /** A pack's open graph file (T76: tiles read on demand) and how it was obtained. */
    private class Loaded(val archive: GraphTileArchive, val key: String, val info: Map<String, Any?>)

    private val graphs = ConcurrentHashMap<String, Loaded>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val building = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    private val requests = ConcurrentHashMap<String, AtomicLong>()
    val errors = AtomicLong()
    @Volatile private var lastMs: Long = 0
    @Volatile private var lastMemory: Map<String, Any?>? = null

    /** T76: decoded graph tiles of every pack, shared by all requests (least recently used dropped past the cap). */
    val tileCache = GraphTileCache(GraphTileCache.DEFAULT_MAX_BYTES) { block -> synchronized(this) { block() } }

    /**
     * T77: the long-drive overlay cells of every pack, built as drives reach them and kept (65,536 cells, about
     * the contiguous US at z11). With [warmOverlay] every tile of a pack is built in the background once its
     * graph is open, so the first long drive does not wait for them.
     */
    private val overlayBuild = Any()
    val driveOverlay = DriveOverlay(
        maxTiles = 65_536, lock = { block -> synchronized(tileCache) { block() } }, clock = { System.nanoTime() / 1_000_000 },
        buildLock = { block -> synchronized(overlayBuild) { block() } }, // T102: a route racing the warm-up builds no cell twice
    )
    var warmOverlay = true
    private val warmer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-overlay-warm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val warm = ConcurrentHashMap<String, Map<String, Any?>>()

    /**
     * Builds the overlay cells of every tile of [archive] on the warm-up thread (once per graph build): level 1
     * (z11), then level 2 (z8, T89), then writes them to the pack's cell file ([OverlayCellFile]). T89: when that
     * file already holds this graph's cells it is read instead (registered with [driveOverlay]) and nothing is built.
     */
    private fun warmKey(pack: Pack, archive: GraphTileArchive) = pack.name + "|" + archive.source.builtAtMs

    private fun warm(pack: Pack, archive: GraphTileArchive) {
        if (!warmOverlay || !archive.source.drive) return
        val key = warmKey(pack, archive)
        if (warm.putIfAbsent(key, mapOf("pack" to pack.name, "state" to "queued")) != null) return
        val cellFile = File(pack.vector.absoluteFile.parentFile, OverlayCellFile.fileNameFor(pack.vector.name))
        if (cellFile.isFile) {
            val t0 = System.nanoTime()
            val f = runCatching { val src = FileRangeSource(cellFile); OverlayCellFile.open { o, l -> src.read(o, l) } }.getOrNull()
            if (f != null && f.source == archive.source && f.tileZoom == driveOverlay.tileZoom && f.level2Zoom == driveOverlay.level2Zoom) {
                driveOverlay.register(f)
                warm[key] = linkedMapOf(
                    "pack" to pack.name, "state" to "loaded", "file" to cellFile.name, "cells" to f.cellCount, "bytes" to f.fileBytes,
                    "ms" to (System.nanoTime() - t0) / 1_000_000,
                )
                return
            }
        }
        warmer.execute {
            val t0 = System.nanoTime()
            var cells = 0
            var cells2 = 0
            try {
                for (e in archive.entries) {
                    if (driveOverlay.piece(e.x, e.y, 0, archive.source) { tileCache.get(archive, 0, e.x, e.y) } != null) cells++
                }
                val ms1 = (System.nanoTime() - t0) / 1_000_000
                // T89: the second level, from the first.
                val step = driveOverlay.tileZoom - driveOverlay.level2Zoom
                if (driveOverlay.level2) {
                    for ((x2, y2) in archive.entries.map { (it.x shr step) to (it.y shr step) }.toSet()) {
                        if (driveOverlay.buildLevel2(x2, y2, archive.source, archive::has) { x, y -> tileCache.get(archive, 0, x, y) } != null) cells2++
                    }
                }
                val ms2 = (System.nanoTime() - t0) / 1_000_000 - ms1
                // T89: the cell file, when every cell is still in memory (the cache keeps 65,536).
                val (one, two) = driveOverlay.cellsOf(archive.source)
                var written: Long? = null
                if (one.size == cells && two.size == cells2) {
                    val bytes = OverlayCellFile.encode(archive.source, driveOverlay.tileZoom, driveOverlay.level2Zoom, one, two)
                    val tmp = File(cellFile.path + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(cellFile)) { cellFile.delete(); tmp.renameTo(cellFile) }
                    written = bytes.size.toLong()
                }
                warm[key] = linkedMapOf(
                    "pack" to pack.name, "state" to "done", "tiles" to archive.entries.size, "cells" to cells, "level2Cells" to cells2,
                    "ms" to (System.nanoTime() - t0) / 1_000_000, "level1Ms" to ms1, "level2Ms" to ms2,
                    "file" to cellFile.name.takeIf { written != null }, "bytes" to written,
                )
            } catch (e: Exception) {
                warm[key] = linkedMapOf("pack" to pack.name, "state" to "failed", "error" to (e.message ?: e.toString()))
            }
        }
    }

    /** Packs in [outDir] with a vector archive, by name ([PackNames]: the outdoors archive over `-osm`). */
    fun packs(): List<Pack> {
        val names = (outDir.list()?.toList() ?: emptyList()).filter { !it.startsWith(".") && File(outDir, it).isFile }
        return PackNames.group(names).mapNotNull { set ->
            val v = set.vector ?: return@mapNotNull null
            val file = File(outDir, v)
            val h = runCatching { reader(file).header }.getOrNull() ?: return@mapNotNull null
            Pack(set.name, file, set.dem?.let { File(outDir, it) }, BoundingBox(h.minLon, h.minLat, h.maxLon, h.maxLat))
        }
    }

    /** [name] when given (400 if unknown), else the first pack holding both ends, else the start. */
    fun choosePack(name: String?, points: List<Pair<Double, Double>>): Pack? {
        val all = packs()
        if (!name.isNullOrBlank()) return all.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no pack '$name' with a vector archive in ${outDir.path}")
        if (points.isEmpty()) return null
        fun has(p: Pack, pt: Pair<Double, Double>) = pt.second in p.bounds.west..p.bounds.east && pt.first in p.bounds.south..p.bounds.north
        return all.firstOrNull { has(it, points.first()) && has(it, points.last()) } ?: all.firstOrNull { has(it, points.first()) }
    }

    /**
     * T76: every pack a route through [points] may use: those whose box comes within [NEAR_M] of the
     * points' box, [first] (the reported pack) leading. A route crosses from one pack into the next.
     */
    fun packsNear(first: Pack?, points: List<Pair<Double, Double>>): List<Pack> {
        if (points.isEmpty()) return listOfNotNull(first)
        val lat0 = points.minOf { it.first }; val lat1 = points.maxOf { it.first }
        val lon0 = points.minOf { it.second }; val lon1 = points.maxOf { it.second }
        val dLat = NEAR_M / 111_195.0
        val dLon = NEAR_M / (111_195.0 * Math.cos(Math.toRadians((lat0 + lat1) / 2)).coerceAtLeast(0.01))
        val near = packs().filter { p ->
            p.bounds.east >= lon0 - dLon && p.bounds.west <= lon1 + dLon && p.bounds.north >= lat0 - dLat && p.bounds.south <= lat1 + dLat
        }
        return (listOfNotNull(first) + near).distinctBy { it.vector.absolutePath }
    }

    private fun stamp(f: File?): String = if (f == null) "-" else "${f.length()}:${f.lastModified()}"

    /** The pack's graph file: open in memory, else `<vector archive>-graph.idx` when it matches both archives, else built and written there. */
    fun graph(pack: Pack): Pair<GraphTileArchive, Map<String, Any?>> {
        val key = stamp(pack.vector) + "|" + stamp(pack.dem)
        val id = pack.vector.absolutePath
        graphs[id]?.takeIf { it.key == key }?.let { return it.archive to mapOf("action" to "cached", "file" to it.info["file"]) }
        synchronized(locks.computeIfAbsent(id) { Any() }) {
            graphs[id]?.takeIf { it.key == key }?.let { return it.archive to mapOf("action" to "cached", "file" to it.info["file"]) }
            building += pack.name
            try {
                val (a, info) = openOrBuild(pack)
                graphs.put(id, Loaded(a, key, info))?.let { old ->
                    tileCache.forget(old.archive)
                    driveOverlay.unregister(old.archive.source)
                    // T107: the old open's warm-up entry goes with it, so warm() below runs for this one. A pack whose
                    // file only changed its timestamp reopens the same graph file (the same source and key): without
                    // this, warm() returned early and the cell file unregistered above stayed unregistered until a
                    // restart (new cells built at level 1 only, `cellFiles` empty while `warm` still said loaded).
                    warm.remove(warmKey(pack, old.archive))
                }
                warm(pack, a) // T77
                failures.remove(pack.name)
                return a to info
            } catch (e: IllegalArgumentException) {
                failures[pack.name] = e.message ?: e.toString()
                throw IllegalArgumentException("routing graph for pack ${pack.name} failed: ${e.message}")
            } finally {
                building -= pack.name
            }
        }
    }

    private fun reader(f: File): PmtilesReader = PmtilesReader.open(FileRangeSource(f), Gzip.decompressors)

    /** Opens the graph file through ranged reads (only the header now; tiles as routes reach them). */
    private fun open(file: File): GraphTileArchive {
        val src = FileRangeSource(file)
        return GraphTileFile.open { offset, length -> src.read(offset, length) }
    }

    private fun openOrBuild(pack: Pack): Pair<GraphTileArchive, Map<String, Any?>> {
        val file = File(pack.vector.absoluteFile.parentFile, RoutingGraph.fileNameFor(pack.vector.name))
        val fingerprint = RoutingGraphBuilder.fingerprint(reader(pack.vector).header)
        val demFp = pack.dem?.let { RoutingGraphBuilder.fingerprint(reader(it).header) }.orEmpty()
        var reason = "missing"
        if (file.isFile) {
            val t0 = System.nanoTime()
            val a = runCatching { open(file) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            a.onSuccess {
                if (it.source.fingerprint == fingerprint && it.source.demFingerprint == demFp && it.tileZoom == GraphTiler.ZOOM) {
                    return it to linkedMapOf("action" to "loaded", "file" to file.path, "bytes" to file.length(), "loadMs" to ms, "tiles" to it.entries.size)
                }
            }
            reason = a.exceptionOrNull()?.message ?: "stale (pack changed)"
        }
        val t0 = System.nanoTime()
        // A small cache, as on the phone and in `devtool route`: the builder samples in tile order.
        val pyramid = pack.dem?.let { dem -> val r = reader(dem); val h = r.header; DemPyramid(h.minZoom, h.maxZoom, demDecoder, 32) { z, x, y -> synchronized(r) { r.tile(z, x, y) } } }
        val builder = RoutingGraphBuilder(pyramid?.let { DemElevation(it) }, demFp).withClock { System.nanoTime() / 1_000_000 }
        val graph = builder.build(reader(pack.vector), pack.vector.name, System.currentTimeMillis())
        val bytes = GraphTileFile.encode(graph)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        val archive = open(file)
        return archive to linkedMapOf(
            "action" to "built", "file" to file.path, "bytes" to bytes.size, "buildMs" to (System.nanoTime() - t0) / 1_000_000,
            "rebuildReason" to reason, "nodes" to graph.nodeCount, "tiles" to archive.entries.size, "demTilesDecoded" to pyramid?.tilesDecoded,
        )
    }

    private fun count(op: String) = requests.computeIfAbsent(op) { AtomicLong() }.incrementAndGet()

    /**
     * An editor over the graphs of [pack] and every other pack near [points] (T76: one tiled network,
     * so legs cross pack borders); the reported graph info is [pack]'s, with every pack's under `graphs`.
     */
    private fun editor(pack: Pack?, points: List<Pair<Double, Double>>, snapM: Double, overlay: Boolean = true): Triple<RouteEditor, Map<String, Any?>?, Pack?> {
        val used = packsNear(pack, points)
        val opened = used.map { p -> p to runCatching { graph(p) } }
        // The reported pack's own failure is the request's error, as before; another pack's only drops it from the network.
        opened.firstOrNull { it.first === pack }?.second?.exceptionOrNull()?.let { throw it }
        val ok = opened.mapNotNull { (p, r) -> r.getOrNull()?.let { p to it } }
        if (ok.isEmpty()) return Triple(RouteEditor(null, elevation, snapM), null, pack)
        val legs = java.util.Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val router = MeasuredRouter(TileRouter(TiledGraph(ok.map { it.second.first }, tileCache), if (overlay) driveOverlay else null), legs)
        val info = ok.firstOrNull { it.first === pack }?.second?.second ?: ok.first().second.second
        // T77: `searches` fills in as the editor routes: one entry per leg searched (ms, tiles, overlay tiles, nodes).
        val all = info + ("graphs" to ok.map { (p, r) -> linkedMapOf("pack" to p.name, "action" to r.second["action"]) }) + ("searches" to legs)
        return Triple(RouteEditor(router, elevation, snapM), all, pack)
    }

    /** Remembers the memory figures of the last route found (`GET /route/status` `lastRouteMemory`). */
    private inner class MeasuredRouter(private val inner: Router, private val legs: MutableList<Map<String, Any?>>) : Router by inner {
        override fun route(
            fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
        ): RouteResult {
            val t0 = System.nanoTime()
            val r = inner.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r is RouteResult.Found) lastMemory = r.plan.memory
            legs += linkedMapOf<String, Any?>("ms" to ms, "found" to (r is RouteResult.Found)) + ((r as? RouteResult.Found)?.plan?.let { p -> p.memory + ("expanded" to p.expanded) } ?: emptyMap())
            return r
        }
    }

    /** A new route through [points] in [mode] (T60's `RouteEditor.create`). */
    fun plan(points: List<Pair<Double, Double>>, mode: String, packName: String? = null, snapM: Double = Router.DEFAULT_SNAP_M, overlay: Boolean = true): Map<String, Any?> {
        val t0 = System.nanoTime()
        require(points.size in 2..MAX_POINTS) { "2 to $MAX_POINTS points" }
        requireMode(mode)
        count("plan")
        val (ed, info, pack) = editor(choosePack(packName, points), points, snapM, overlay)
        val path = ed.create(points, mode)
        return reply(pack, path, ed.takeNotes(), info, t0, ed.takeMessages(), ed.directions(path))
    }

    /** One editor operation on an encoded path (see [RouteRoutes]). */
    fun edit(body: Map<String, Any?>): Map<String, Any?> {
        val t0 = System.nanoTime()
        val text = body["path"] as? String ?: throw IllegalArgumentException("path (the encoded route) required")
        val path = runCatching { RoutePath.decode(text) }.getOrElse { throw IllegalArgumentException("path does not decode: ${it.message}") }
        val op = body["op"] as? String ?: throw IllegalArgumentException("op required: move, insert, add, remove, mode, reroute or show")
        fun num(k: String): Double = (body[k] as? Number)?.toDouble() ?: throw IllegalArgumentException("$k required for $op")
        fun index(): Int = num("index").let { v -> require(v == Math.floor(v)) { "index must be an integer" }; v.toInt() }
        fun lat() = num("lat").also { require(it in -85.0..85.0) { "lat out of range" } }
        fun lon() = num("lon").also { require(it in -180.0..180.0) { "lon out of range" } }
        val snap = (body["snap"] as? Number)?.toDouble()?.also { require(it in 10.0..2000.0) { "snap must be 10 to 2000 metres" } } ?: Router.DEFAULT_SNAP_M
        val first = path.via.firstOrNull()?.let { it.lat to it.lon }
        val last = path.via.lastOrNull()?.let { it.lat to it.lon }
        val anchor = listOfNotNull(first ?: (body["lat"] as? Number)?.let { lat() to lon() }, last)
        val (ed, info, pack) = editor(choosePack(body["pack"] as? String, anchor), path.via.map { it.lat to it.lon } + anchor + listOfNotNull((body["lat"] as? Number)?.let { lat() to lon() }), snap)
        count(op)
        var added: Int? = null
        val out = when (op) {
            "move" -> ed.moveVia(path, index(), lat(), lon())
            "insert" -> ed.insertVia(path, index(), lat(), lon())
            "add" -> ed.addVia(path, lat(), lon()).let { (p, i) -> added = i; p }
            "remove" -> {
                require(path.via.size > 2) { "a route keeps at least two via points (clear it instead)" }
                ed.removeVia(path, index())
            }
            "mode" -> (body["mode"] as? String ?: throw IllegalArgumentException("mode required")).let { m -> requireMode(m); ed.setMode(path, m) }
            "reroute" -> ed.reroute(path)
            "show" -> path
            else -> throw IllegalArgumentException("unknown op '$op' (move, insert, add, remove, mode, reroute, show)")
        }
        require(out.via.size <= MAX_POINTS) { "at most $MAX_POINTS via points" }
        return reply(pack, out, ed.takeNotes(), info, t0, ed.takeMessages(), ed.directions(out)) + listOfNotNull(added?.let { "index" to it }).toMap()
    }

    private fun requireMode(mode: String) = require(TravelMode.of(mode) != null) { "mode must be ${TravelMode.labels}" }

    private fun reply(
        pack: Pack?, path: RoutePath, notes: List<String>, graph: Map<String, Any?>?, t0: Long, messages: Map<String, String> = emptyMap(),
        instructions: List<io.github.jasondorjeshort.dystopo.routing.Instruction> = emptyList(),
    ): Map<String, Any?> {
        val ms = (System.nanoTime() - t0) / 1_000_000
        lastMs = ms
        return linkedMapOf(
            "pack" to pack?.name, "mode" to path.mode, "path" to path.encode(),
            "via" to path.via.map { listOf(it.lat, it.lon) },
            "legs" to path.legs.map { leg ->
                linkedMapOf("kind" to leg.kind.label, "points" to leg.points.size, "coordinates" to leg.points.map { p -> listOfNotNull(p.lon, p.lat, p.ele) })
            },
            "summary" to RouteSummary.of(path, emptyList()).json(),
            "notes" to notes.distinct(),
            "messages" to messages, // T73: the router's words for each note (drive_unsupported names the pack to rebuild)
            "notice" to if (path.mode == "drive") Router.DRIVE_NOTICE else null,
            "instructions" to instructions.map { it.json(TravelMode.of(path.mode) ?: TravelMode.HIKE) }, // T102
            "waypointsValue" to EMPTY_WAYPOINTS,
            "graph" to graph, "ms" to ms,
        )
    }

    /** GPX of `{path, name, id}` ([RouteGpx]). */
    fun gpx(body: Map<String, Any?>): String {
        count("gpx")
        val text = body["path"] as? String ?: throw IllegalArgumentException("path required")
        val path = runCatching { RoutePath.decode(text) }.getOrElse { throw IllegalArgumentException("path does not decode: ${it.message}") }
        val id = body["id"] as? String ?: throw IllegalArgumentException("id required")
        require(id.isNotBlank() && id.length <= 64) { "bad id" }
        val name = (body["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Route"
        return RouteGpx.gpx(RoutePlan(id, name, path))
    }

    /** The browser replica's route ops as a gzipped bundle (see [RouteRoutes]). */
    fun bundle(body: Map<String, Any?>): ByteArray {
        count("bundle")
        val device = body["device"] as? String ?: throw IllegalArgumentException("device required")
        require(device.isNotBlank() && device.length <= 64 && device.all { it.isLetterOrDigit() || it == '-' }) { "bad device id" }
        val list = body["ops"] as? List<*> ?: throw IllegalArgumentException("ops (a list) required")
        require(list.size <= MAX_OPS) { "at most $MAX_OPS ops" }
        val ops = list.mapIndexed { i, raw ->
            val o = raw as? Map<*, *> ?: throw IllegalArgumentException("op $i is not an object")
            try {
                op(device, o)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("op $i: ${e.message}")
            }
        }.sortedBy { it.hlc }
        require(ops.zipWithNext().none { (a, b) -> a.hlc == b.hlc }) { "two ops with the same hlc" }
        val vector = if (ops.isEmpty()) VersionVector.EMPTY else VersionVector(mapOf(device to ops.last().hlc))
        return Gzip.compress(Bundle(device, VersionVector.EMPTY, vector, ops).encode())
    }

    private fun op(device: String, o: Map<*, *>): Op {
        val hlc = try {
            Hlc.parse(o["hlc"] as? String ?: throw IllegalArgumentException("hlc required"))
        } catch (e: SyncFormatException) {
            throw IllegalArgumentException(e.message)
        }
        require(hlc.device == device) { "hlc device ${hlc.device} is not the bundle's device" }
        val id = o["id"] as? String ?: throw IllegalArgumentException("id required")
        require(id.isNotBlank() && id.length <= 64) { "bad id" }
        fun str(k: String) = o[k] as? String
        val body = when (o["type"]) {
            "put" -> PutRoute(Route(id, str("name")?.takeIf { it.isNotBlank() } ?: "Route", emptyList(), str("description"), str("comment"), str("routeType"), null))
            "set" -> {
                val field = str("field") ?: throw IllegalArgumentException("field required")
                require(field in EntityKind.ROUTE.fields) { "field '$field' is not a route field" }
                val value = str("value")
                when (field) {
                    "path" -> runCatching { RoutePath.decode(value ?: "") }.getOrElse { throw IllegalArgumentException("path value does not decode: ${it.message}") }
                    "waypoints" -> runCatching { RouteWaypoints.decode(value ?: "") }.getOrElse { throw IllegalArgumentException("waypoints value does not decode: ${it.message}") }
                    "name" -> require(!value.isNullOrBlank()) { "a name cannot be empty" }
                }
                SetField(EntityKind.ROUTE, id, field, value)
            }
            "delete" -> Delete(EntityKind.ROUTE, id)
            else -> throw IllegalArgumentException("type must be put, set or delete")
        }
        return Op(hlc, body)
    }

    fun packsJson(): List<Map<String, Any?>> = packs().map { p ->
        val loaded = graphs[p.vector.absolutePath]
        val file = File(p.vector.absoluteFile.parentFile, RoutingGraph.fileNameFor(p.vector.name))
        linkedMapOf(
            "name" to p.name, "vector" to p.vector.name, "dem" to p.dem?.name,
            "bounds" to listOf(p.bounds.west, p.bounds.south, p.bounds.east, p.bounds.north),
            "graph" to linkedMapOf(
                "state" to when {
                    p.name in building -> "building"
                    loaded != null -> "ready"
                    failures[p.name] != null -> "failed"
                    file.isFile -> "on disk"
                    else -> "none"
                },
                "file" to file.name, "bytes" to file.takeIf { it.isFile }?.length(),
                "nodes" to loaded?.archive?.nodeCount, "tiles" to loaded?.archive?.entries?.size, "error" to failures[p.name], "info" to loaded?.info,
            ),
        )
    }

    fun status(): Map<String, Any?> = linkedMapOf(
        "outDir" to outDir.path, "requests" to requests.mapValues { it.value.get() }.toSortedMap(), "errors" to errors.get(),
        "lastMs" to lastMs, "building" to building.toList(), "graphs" to graphs.values.map { it.info },
        "tileCache" to tileCache.json(), "lastRouteMemory" to lastMemory, // T76
        "corruptGraphTiles" to tileCache.corrupt(), // T89: skipped until the pack's graph is rebuilt
        "driveOverlay" to driveOverlay.json() + ("warm" to warm.values.toList()), // T77
    )

    companion object {
        const val MAX_POINTS = 50
        const val MAX_OPS = 20_000
        /** T76: packs whose box comes this close to a route's points join its network. */
        const val NEAR_M = 5_000.0

        /** The `waypoints` value of a route without waypoints (what the page writes on save). */
        val EMPTY_WAYPOINTS: String = RouteWaypoints.encode(emptyList())
    }
}
