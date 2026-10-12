package io.github.jasondorjeshort.dystopo.app.routing

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.GraphTileArchive
import io.github.jasondorjeshort.dystopo.routing.GraphTileCache
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.GraphTiler
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.TileRouter
import io.github.jasondorjeshort.dystopo.routing.TiledGraph
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline route planning over every pack on the device (T45; tiled since T76, PLANNING › Routing).
 * Each pack's trail graph lives next to its vector archive as `<archive name>-graph.idx` (T49: e.g.
 * `rainier-outdoors-graph.idx`), a tiled graph file ([GraphTileFile]): [ensure] opens it in the
 * background, or builds it from the vector archive (elevation from the pack's DEM archive) when it is
 * missing, unreadable, from before T76 (version 2) or stale (either archive's fingerprint changed), with
 * progress in [json] (`GET /packs` under `routing`, `GET /route/status`). Nothing is built until the
 * first use: the first long-press on the map, `POST /route` or the trip editor.
 *
 * A route runs on [router]: one [TiledGraph] over the graph files of every pack in use, tiles read on
 * demand into one LRU ([tileCache]), so a route reads only the tiles around it and crosses from one
 * pack into the next where their trails meet. No pack is special: the primary pack only orders the
 * builds (it goes first) and names the state [ensure] returns.
 *
 * Saved routes are GPX files in `files/routes/`, one route each, named after the route and its id.
 */
object RoutePlanner {
    /** Graph state for one pack. Fields are written by the worker thread. */
    class State(val pack: String, val vector: File, val dem: File?, val file: File, private val vectorBytes: Long, private val vectorMtime: Long, val bounds: DoubleArray) {
        @Volatile var status = "queued" // queued, loading, building, ready, error
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var buildMs: Long? = null
        @Volatile var loadMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var rebuildReason: String? = null
        @Volatile var archive: GraphTileArchive? = null
        @Volatile var source: FileRangeSource? = null
        @Volatile var demTilesDecoded: Long? = null
        @Volatile var demDecodeMs: Double? = null
        @Volatile var demDecoder: String? = null
        @Volatile var nodes: Int? = null
        val finished = CountDownLatch(1)

        fun matches(name: String?, f: File?) = name == pack && f != null && f.path == vector.path && f.length() == vectorBytes && f.lastModified() == vectorMtime

        /** True when the pack's box comes within [m] metres of the box around [points]. */
        fun near(points: List<Pair<Double, Double>>, m: Double): Boolean {
            if (points.isEmpty()) return true
            val lat0 = points.minOf { it.first }; val lat1 = points.maxOf { it.first }
            val lon0 = points.minOf { it.second }; val lon1 = points.maxOf { it.second }
            val dLat = m / 111_195.0
            val dLon = m / (111_195.0 * Math.cos(Math.toRadians((lat0 + lat1) / 2)).coerceAtLeast(0.01))
            return bounds[2] >= lon0 - dLon && bounds[0] <= lon1 + dLon && bounds[3] >= lat0 - dLat && bounds[1] <= lat1 + dLat
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "pack" to pack, "status" to status, "file" to file.path, "bytes" to file.takeIf { it.isFile }?.length(),
            "progress" to if (total > 0) linkedMapOf("tiles" to done, "of" to total, "percent" to done * 100 / total) else null,
            "buildMs" to buildMs, "loadMs" to loadMs, "demTilesDecoded" to demTilesDecoded, "demDecodeMs" to demDecodeMs, "demDecoder" to demDecoder, "decoder" to TileService.decoderName, "rebuildReason" to rebuildReason, "error" to error, // T57: decoder
            "nodes" to (archive?.nodeCount ?: nodes), "edges" to archive?.edgeCount,
            "graphTiles" to archive?.entries?.size, "tileZoom" to archive?.tileZoom, // T76
        )
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "dystopo-routing").apply { isDaemon = true }
    }

    /** Graph state per vector archive path. */
    private val states = ConcurrentHashMap<String, State>()

    /** T76: decoded graph tiles of every pack, least recently used dropped past the cap. */
    val tileCache = GraphTileCache(GraphTileCache.DEFAULT_MAX_BYTES) { block -> synchronized(this) { block() } }

    /** The state the worker is loading or building now, else the last one it handled (the progress line). */
    @Volatile var current: State? = null
        private set

    /** Memory figures of the last route found (`GET /route/status` `lastRoute`). */
    @Volatile private var lastRoute: Map<String, Any?>? = null

    /** Every pack with a vector archive, primary first. */
    private fun regions(app: DystopoApp): List<PackSet.Region> =
        app.tiles.packs.regions.filter { it.osm != null }.sortedByDescending { it.name == app.tiles.packs.name }

    /**
     * Starts loading or building the graph of every pack in use (the primary first); returns the
     * primary pack's state (the first pack's without one), null when no pack has a vector archive.
     */
    @Synchronized
    fun ensure(app: DystopoApp = DystopoApp.instance, rebuild: Boolean = false): State? {
        val list = regions(app).map { ensureRegion(it, rebuild) }
        // Packs that left the device (or changed) drop their state and tiles.
        val live = list.map { it.vector.path }.toSet()
        for ((k, s) in states) if (k !in live && s.status !in listOf("queued", "loading", "building")) { states.remove(k); close(s) }
        return list.firstOrNull()
    }

    private fun ensureRegion(region: PackSet.Region, rebuild: Boolean): State {
        val vector = region.osm!!.file
        val cur = states[vector.path]
        if (cur != null && cur.matches(region.name, vector) && (!rebuild || cur.status in listOf("queued", "loading", "building"))) return cur
        cur?.let { close(it) }
        val dir = vector.absoluteFile.parentFile
        val h = region.osm.header
        val state = State(
            region.name, vector, region.dem?.file, File(dir, RoutingGraph.fileNameFor(vector.name)), vector.length(), vector.lastModified(),
            doubleArrayOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
        )
        states[vector.path] = state
        worker.execute { run(state, rebuild) }
        return state
    }

    private fun close(s: State) {
        s.archive?.let { tileCache.forget(it) }
        s.archive = null
        runCatching { s.source?.close() }
        s.source = null
    }

    /** Opens [file] for ranged reads: only the header now, tiles as routes reach them. */
    private fun open(s: State): GraphTileArchive {
        val src = FileRangeSource(s.file)
        return try {
            GraphTileFile.open { offset, length -> synchronized(src) { src.read(offset, length) } }.also { s.source = src }
        } catch (e: IllegalArgumentException) {
            src.close()
            throw e
        }
    }

    private fun run(s: State, rebuild: Boolean) {
        current = s
        try {
            FileRangeSource(s.vector).use { src ->
                val reader = PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip))
                val demSrc = s.dem?.let { FileRangeSource(it) }
                try {
                    val demReader = demSrc?.let { PmtilesReader.open(it, mapOf(Compression.GZIP to gunzip)) }
                    val fingerprint = RoutingGraphBuilder.fingerprint(reader.header)
                    val demFingerprint = demReader?.let { RoutingGraphBuilder.fingerprint(it.header) }.orEmpty()
                    if (!rebuild && s.file.isFile) {
                        s.status = "loading"
                        val t0 = SystemClock.elapsedRealtime()
                        val loaded = runCatching { open(s) }
                        val a = loaded.getOrNull()
                        if (a != null && a.source.fingerprint == fingerprint && a.source.demFingerprint == demFingerprint && a.tileZoom == GraphTiler.ZOOM) {
                            s.loadMs = SystemClock.elapsedRealtime() - t0
                            s.archive = a
                            s.status = "ready"
                            return
                        }
                        if (a != null) close(s)
                        s.rebuildReason = loaded.exceptionOrNull()?.message ?: "stale (pack changed)"
                    } else {
                        s.rebuildReason = if (rebuild) "requested" else "missing"
                    }
                    s.status = "building"
                    val t0 = SystemClock.elapsedRealtime()
                    // Its own small DEM cache: the builder samples in tile order, so each tile is decoded about once.
                    val pyramid = demReader?.let { r ->
                        val lock = Any()
                        // T49: the app's DEM decoder (the shared pure-Kotlin one by default), not BitmapFactory.
                        DemPyramid(r.header.minZoom, r.header.maxZoom, TileService.demDecoder(), cacheTiles = 32) { z, x, y -> synchronized(lock) { r.tile(z, x, y) } }
                    }
                    val builder = RoutingGraphBuilder(pyramid?.let { DemElevation(it) }, demFingerprint) { d, n -> s.done = d; s.total = n }
                        .withClock { SystemClock.elapsedRealtime() }
                    val g = builder.build(reader, s.vector.name, System.currentTimeMillis())
                    val bytes = GraphTileFile.encode(g)
                    val tmp = File(s.file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(s.file)) { s.file.delete(); tmp.renameTo(s.file) }
                    s.archive = open(s)
                    s.nodes = g.nodeCount
                    s.buildMs = SystemClock.elapsedRealtime() - t0
                    s.demTilesDecoded = pyramid?.tilesDecoded
                    s.demDecodeMs = pyramid?.decodeMs?.let { Math.round(it * 10) / 10.0 }
                    s.demDecoder = TileService.decoderName
                    s.status = "ready"
                    Log.i(TAG, "built ${s.file.name}: ${g.nodeCount} nodes, ${g.edgeCount} edges, ${s.archive?.entries?.size} tiles, ${bytes.size} bytes in ${s.buildMs} ms (${builder.stats?.timingsMs})")
                } finally {
                    demSrc?.close()
                }
            }
        } catch (e: Exception) {
            s.error = "${e.javaClass.simpleName}: ${e.message}"
            s.status = "error"
            Log.w(TAG, "routing graph for ${s.pack} failed", e)
        } finally {
            s.finished.countDown()
        }
    }

    /** Thrown when no graph near the route is ready within the wait. */
    class NotReady(val state: State?) : Exception("routing graph not ready")

    /**
     * The trail network as a [Router] over every pack whose box comes within [NEAR_M] of [points] (all
     * packs when there are none), waiting up to [waitMs] for their graphs to load or build. Packs whose
     * graph failed are left out; throws [NotReady] when none is ready.
     */
    fun router(waitMs: Long, points: List<Pair<Double, Double>> = emptyList()): Router {
        val primary = ensure()
        val wanted = states.values.filter { it.near(points, NEAR_M) }.ifEmpty { listOfNotNull(primary) }
        val deadline = SystemClock.elapsedRealtime() + waitMs
        for (s in wanted) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (s.archive == null && left > 0) s.finished.await(left, TimeUnit.MILLISECONDS)
        }
        val ready = wanted.filter { it.archive != null }
        if (ready.isEmpty()) throw NotReady(wanted.firstOrNull { it.status != "error" } ?: wanted.firstOrNull() ?: primary)
        return Measured(TileRouter(TiledGraph(ready.mapNotNull { it.archive }, tileCache)))
    }

    /** Keeps the memory figures of the last route found, for `/route/status`. */
    private class Measured(private val inner: Router) : Router by inner {
        override fun route(
            fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
        ): RouteResult = inner.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids).also { r ->
            if (r is RouteResult.Found) lastRoute = r.plan.memory
        }
    }

    /**
     * Plans a route over the packs near its ends (blocking; call off the main thread). T57: ends snap within
     * [snapM] (the `routeSnapM` setting), and an off-network failure names the nearest trail ([OffNetwork]).
     */
    fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: TravelMode, name: String, waitMs: Long, snapM: Double = AppSettings.routeSnapM.toDouble()): RouteResult {
        val router = router(waitMs, listOf(fromLat to fromLon, toLat to toLon))
        val r = router.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM = snapM, name = name)
        if (r !is RouteResult.Failed) return r
        return OffNetwork.explain(router, r, fromLat to fromLon, toLat to toLon, snapM, AppSettings.ROUTE_SNAP_RANGE.last.toDouble(), AppSettings.units, mode)
    }

    /**
     * Status for `/packs` and `/route/status`: the primary pack's graph state (or what is on disk when no
     * graph was used this session) at the top level, as before T76, plus every pack's under `packs`, the
     * tile cache and the last route's memory figures.
     */
    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?>? {
        val regions = regions(app)
        if (regions.isEmpty()) return null
        val each = regions.map { r ->
            states[r.osm!!.file.path]?.takeIf { it.matches(r.name, r.osm.file) }?.json() ?: run {
                val f = File(r.osm.file.absoluteFile.parentFile, RoutingGraph.fileNameFor(r.osm.file.name))
                linkedMapOf("pack" to r.name, "status" to "idle", "file" to f.path, "bytes" to f.takeIf { it.isFile }?.length(), "decoder" to TileService.decoderName) // T57
            }
        }
        return LinkedHashMap(each.first()) + linkedMapOf("packs" to each, "tileCache" to tileCache.json(), "lastRoute" to lastRoute)
    }

    /**
     * T49: the graph files of a vector archive, for deleting a pack: the current name and the pre-T49 one
     * (`<pack>-graph.idx`), whichever exist.
     */
    fun graphFiles(vector: File): List<File> {
        val dir = vector.absoluteFile.parentFile
        return listOf(RoutingGraph.fileNameFor(vector.name), RoutingGraph.legacyFileNameFor(vector.name)).distinct().map { File(dir, it) }.filter { it.isFile }
    }

    /** T49: drops the loaded graph of pack [name] (being deleted). */
    @Synchronized
    fun forget(name: String) {
        for ((k, s) in states) if (s.pack == name) { states.remove(k); close(s) }
        if (current?.pack == name) current = null
    }

    /** T76: packs whose box comes this close to a route's ends join its network. */
    const val NEAR_M = 5_000.0

    // ---- Saved routes ----

    fun routesDir(app: DystopoApp = DystopoApp.instance): File = File(app.filesDir, "routes").apply { mkdirs() }

    fun gpx(route: Route): String = Gpx.write(GpxDocument(routes = listOf(route)))

    /** Saves [route] as `files/routes/<name>-<id8>.gpx`; returns the file. */
    fun save(route: Route, app: DystopoApp = DystopoApp.instance): File {
        val safe = route.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(40).ifEmpty { "route" }
        return File(routesDir(app), "$safe-${route.id.take(8)}.gpx").apply { writeText(gpx(route), Charsets.UTF_8) }
    }

    /** Saved routes, newest first: file, name, points. */
    fun saved(app: DystopoApp = DystopoApp.instance): List<Map<String, Any?>> =
        routesDir(app).listFiles { f -> f.isFile && f.name.endsWith(".gpx") }.orEmpty().sortedByDescending { it.lastModified() }.map { f ->
            val r = runCatching { Gpx.parse(f.readText(Charsets.UTF_8)).routes.firstOrNull() }.getOrNull()
            linkedMapOf("file" to f.name, "bytes" to f.length(), "id" to r?.id, "name" to r?.name, "points" to r?.points?.size, "description" to r?.description)
        }

    const val TAG = "DystopoRouting"
}
