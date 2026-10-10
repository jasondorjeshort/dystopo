package io.github.jasondorjeshort.dystopo.app.routing

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline route planning for the active pack (T45). The trail graph lives next to the pack's vector
 * archive as `<name>-graph.idx`; [ensure] loads it in the background, or builds it from the vector
 * archive (elevation from the DEM archive) when it is missing, unreadable or stale (either archive's
 * fingerprint changed), with progress in [json] (`GET /packs` under `routing`, `GET /route/status`).
 * Nothing is built until the first use: the first long-press on the map or `POST /route`.
 *
 * Saved routes are GPX files in `files/routes/`, one route each, named after the route and its id.
 */
object RoutePlanner {
    /** Graph state for one pack. Fields are written by the worker thread. */
    class State(val pack: String, val vector: File, val dem: File?, val file: File, private val vectorBytes: Long, private val vectorMtime: Long) {
        @Volatile var status = "queued" // queued, loading, building, ready, error
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var buildMs: Long? = null
        @Volatile var loadMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var rebuildReason: String? = null
        @Volatile var graph: RoutingGraph? = null
        @Volatile var demTilesDecoded: Long? = null
        val finished = CountDownLatch(1)

        fun matches(name: String?, f: File?) = name == pack && f != null && f.path == vector.path && f.length() == vectorBytes && f.lastModified() == vectorMtime

        fun json(): Map<String, Any?> = linkedMapOf(
            "pack" to pack, "status" to status, "file" to file.path, "bytes" to file.takeIf { it.isFile }?.length(),
            "progress" to if (total > 0) linkedMapOf("tiles" to done, "of" to total, "percent" to done * 100 / total) else null,
            "buildMs" to buildMs, "loadMs" to loadMs, "demTilesDecoded" to demTilesDecoded, "rebuildReason" to rebuildReason, "error" to error,
            "nodes" to graph?.nodeCount, "edges" to graph?.edgeCount, "networks" to graph?.componentLengthM?.size,
        )
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "dystopo-routing").apply { isDaemon = true }
    }

    @Volatile var current: State? = null
        private set

    /** The active pack's graph state; starts loading or building it. Null when the pack has no vector archive. */
    @Synchronized
    fun ensure(app: DystopoApp = DystopoApp.instance, rebuild: Boolean = false): State? {
        val packs = app.tiles.packs
        val vector = packs.osm?.file ?: return null.also { current = null }
        val name = packs.name ?: "default"
        val cur = current
        if (cur != null && cur.matches(name, vector) && (!rebuild || cur.status in listOf("queued", "loading", "building"))) return cur
        val dir = vector.absoluteFile.parentFile
        val state = State(name, vector, packs.dem?.file, File(dir, RoutingGraph.fileNameFor(vector.name)), vector.length(), vector.lastModified())
        current = state
        worker.execute { run(state, rebuild) }
        return state
    }

    private fun run(s: State, rebuild: Boolean) {
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
                        val loaded = runCatching { RoutingGraph.decode(s.file.readBytes()) }
                        val g = loaded.getOrNull()
                        if (g != null && g.source.fingerprint == fingerprint && g.source.demFingerprint == demFingerprint) {
                            g.adjStart; g.component; g.nearest(0.0, 0.0, 1.0) // derive the indexes off the UI path
                            s.loadMs = SystemClock.elapsedRealtime() - t0
                            s.graph = g
                            s.status = "ready"
                            return
                        }
                        s.rebuildReason = loaded.exceptionOrNull()?.message ?: "stale (pack changed)"
                    } else {
                        s.rebuildReason = if (rebuild) "requested" else "missing"
                    }
                    s.status = "building"
                    val t0 = SystemClock.elapsedRealtime()
                    // Its own small DEM cache: the builder samples in tile order, so each tile is decoded about once.
                    val pyramid = demReader?.let { r ->
                        val lock = Any()
                        DemPyramid(r.header.minZoom, r.header.maxZoom, BitmapImageDecoder, cacheTiles = 32) { z, x, y -> synchronized(lock) { r.tile(z, x, y) } }
                    }
                    val builder = RoutingGraphBuilder(pyramid?.let { DemElevation(it) }, demFingerprint) { d, n -> s.done = d; s.total = n }
                        .withClock { SystemClock.elapsedRealtime() }
                    val g = builder.build(reader, s.vector.name, System.currentTimeMillis())
                    val bytes = g.encode()
                    val tmp = File(s.file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(s.file)) { s.file.delete(); tmp.renameTo(s.file) }
                    g.adjStart; g.component; g.nearest(0.0, 0.0, 1.0)
                    s.buildMs = SystemClock.elapsedRealtime() - t0
                    s.demTilesDecoded = pyramid?.tilesDecoded
                    s.graph = g
                    s.status = "ready"
                    Log.i(TAG, "built ${s.file.name}: ${g.nodeCount} nodes, ${g.edgeCount} edges, ${bytes.size} bytes in ${s.buildMs} ms (${builder.stats?.timingsMs})")
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

    /** Thrown when the graph is not ready within the wait. */
    class NotReady(val state: State?) : Exception("routing graph not ready")

    /** The ready graph, waiting up to [waitMs] for a load or build in progress. */
    fun graph(waitMs: Long): RoutingGraph {
        val s = ensure() ?: throw NotReady(null)
        s.graph?.let { return it }
        if (waitMs > 0) s.finished.await(waitMs, TimeUnit.MILLISECONDS)
        return s.graph ?: throw NotReady(s)
    }

    /** Plans a route on the active pack's graph (blocking; call off the main thread). */
    fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: TravelMode, name: String, waitMs: Long): RouteResult {
        val g = graph(waitMs)
        return Router(g).route(fromLat, fromLon, toLat, toLon, mode, name = name)
    }

    /** Status for `/packs` and `/route/status`: the state when a graph was used this session, else what is on disk. */
    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?>? {
        current?.takeIf { it.matches(app.tiles.packs.name ?: "default", app.tiles.packs.osm?.file) }?.let { return it.json() }
        val vector = app.tiles.packs.osm?.file ?: return null
        val f = File(vector.absoluteFile.parentFile, RoutingGraph.fileNameFor(vector.name))
        return linkedMapOf("pack" to (app.tiles.packs.name ?: "default"), "status" to "idle", "file" to f.path, "bytes" to f.takeIf { it.isFile }?.length())
    }

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
