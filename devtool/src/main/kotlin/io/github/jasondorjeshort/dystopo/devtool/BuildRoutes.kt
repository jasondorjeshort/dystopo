package io.github.jasondorjeshort.dystopo.devtool

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.packs.OsmPackMetadata
import io.github.jasondorjeshort.dystopo.packs.OsmPackWriter
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * Region packs built on the server from the live sources (`serve --live`), so "browse online,
 * download a region" works anywhere:
 *
 * - `POST /build?name=&bbox=w,s,e,n&minZoom=8&maxZoom=13[&overwrite=1]` queues a job (202 + job JSON).
 *   It writes `out/<name>-dem.pmtiles` from the live DEM path ([LiveTiles.liveDemAsync]: the `best`
 *   source rendered per tile, the same disk cache as `/dem`; zooms clamped to 6..15) and
 *   `out/<name>-outdoors.pmtiles` from OpenFreeMap tiles ([LiveTiles.openFreeMap]: fetched once, cached,
 *   at most 4 requests in flight; zooms up to 14) through [OsmPackWriter]. The vector pack is the
 *   OpenMapTiles schema, not the outdoors profile, and its metadata says so (`dystopo:profile`).
 * - `GET /build/{id}`: status (queued, running, done, failed, cancelled), phase, per-pack tile counts,
 *   timings, the finished files' `/out/` URLs, provenance. `GET /build`: every job plus the limits.
 * - `DELETE /build/{id}` or `POST /build/{id}/cancel`: stops the job; no new tile is requested after
 *   that (the few in flight finish into the cache), partial files are deleted.
 *
 * Files are written as `*.building` and renamed when complete, so `/out/` never serves half a pack;
 * a finished pack is also handed to [LiveTiles.addPack], so the viewer then shows it as `pack`.
 * Limits (constructor, or environment): [maxTiles] per job (`DYSTOPO_BUILD_MAX_TILES`, default
 * 20000, DEM and OSM tiles together), [maxSpanDegrees] per box side (`DYSTOPO_BUILD_MAX_SPAN_DEG`,
 * default 3), [concurrentBuilds] at once (`DYSTOPO_BUILD_CONCURRENT`, default 1) and [maxQueued]
 * waiting (`DYSTOPO_BUILD_QUEUE`, default 4). Over a limit the request is refused with a JSON error.
 */
class BuildRoutes(
    private val live: LiveTiles,
    private val outDir: File = File("out"),
    val maxTiles: Int = env("DYSTOPO_BUILD_MAX_TILES")?.toIntOrNull() ?: 20_000,
    val maxSpanDegrees: Double = env("DYSTOPO_BUILD_MAX_SPAN_DEG")?.toDoubleOrNull() ?: 3.0,
    val concurrentBuilds: Int = env("DYSTOPO_BUILD_CONCURRENT")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
    val maxQueued: Int = env("DYSTOPO_BUILD_QUEUE")?.toIntOrNull()?.coerceAtLeast(0) ?: 4,
    /** Tiles requested ahead of the one being written (DEM renders run on the live pool). */
    private val demWindow: Int = 8,
    private val osmWindow: Int = 8,
) {
    enum class Status(val label: String) { QUEUED("queued"), RUNNING("running"), DONE("done"), FAILED("failed"), CANCELLED("cancelled") }

    /** A refused request: HTTP status and a message for the user. */
    class Refused(val status: Int, message: String) : Exception(message)

    private class CancelledBuild : Exception("cancelled")

    class Counts(val total: Int) {
        val done = AtomicInteger()
        val written = AtomicInteger()
        val cached = AtomicInteger()
        val empty = AtomicInteger()

        fun json(zooms: IntRange?) = linkedMapOf(
            "zooms" to zooms?.let { listOf(it.first, it.last) }, "total" to total, "done" to done.get(),
            "written" to written.get(), "cached" to cached.get(), "fetched" to (done.get() - cached.get()), "empty" to empty.get(),
        )
    }

    class Job(
        val id: String,
        val name: String,
        val box: BoundingBox,
        val minZoom: Int,
        val maxZoom: Int,
        val demZooms: IntRange,
        val osmZooms: IntRange?,
        val overwrite: Boolean,
    ) {
        @Volatile var status = Status.QUEUED
        @Volatile var phase = "queued"
        @Volatile var cancelled = false
        @Volatile var error: String? = null
        val dem = Counts(demZooms.sumOf { countTiles(box, it) })
        val osm = Counts(osmZooms?.sumOf { countTiles(box, it) } ?: 0)
        val queuedAt = System.currentTimeMillis()
        @Volatile var startedNs = 0L
        @Volatile var endedNs = 0L
        val timings = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val files = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        @Volatile var demProvenance: List<String> = emptyList()
        @Volatile var osmProvenance: List<String> = emptyList()

        val active: Boolean get() = status == Status.QUEUED || status == Status.RUNNING

        fun json(): Map<String, Any?> {
            val total = dem.total + osm.total
            val done = dem.done.get() + osm.done.get()
            val elapsed = when {
                startedNs == 0L -> 0L
                endedNs == 0L -> (System.nanoTime() - startedNs) / 1_000_000
                else -> (endedNs - startedNs) / 1_000_000
            }
            return linkedMapOf(
                "id" to id, "name" to name, "status" to status.label, "phase" to phase,
                "bbox" to TilesCommand.geoJsonBounds(box), "minZoom" to minZoom, "maxZoom" to maxZoom,
                "percent" to if (total == 0) 100.0 else Math.round(1000.0 * done / total) / 10.0,
                "dem" to dem.json(demZooms), "osm" to osm.json(osmZooms),
                "elapsedMs" to elapsed, "timings" to timings.toSortedMap(),
                "files" to files.toList(),
                "provenance" to mapOf("dem" to demProvenance, "osm" to osmProvenance),
                "error" to error,
            )
        }
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val nextId = AtomicInteger(1)
    private val executor: ExecutorService = Executors.newFixedThreadPool(concurrentBuilds) { r -> Thread(r, "pack-build").apply { isDaemon = true } }
    /** Our OpenFreeMap requests go through [LiveTiles.openFreeMap] (4 in flight at most, shared with the viewer); these threads only wait on them. */
    private val osmPool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "pack-build-osm").apply { isDaemon = true } }

    fun job(id: String): Job? = synchronized(jobs) { jobs[id] }

    fun limits(): Map<String, Any?> = linkedMapOf(
        "maxTiles" to maxTiles, "maxSpanDegrees" to maxSpanDegrees, "concurrentBuilds" to concurrentBuilds, "maxQueued" to maxQueued,
        "demZooms" to listOf(LiveTiles.LIVE_MIN_ZOOM, LiveTiles.LIVE_MAX_ZOOM), "osmMaxZoom" to LiveTiles.OSM_MAX_ZOOM,
    )

    /** Validates a request and queues the job; throws [Refused] with the reason when it can't. */
    fun submit(params: Map<String, String>): Job {
        val name = params["name"]?.trim().orEmpty()
        if (!PackNames.isValidName(name)) throw Refused(400, "name must be 1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit (got '$name')")
        val b = params["bbox"]?.split(',')?.map { it.trim().toDoubleOrNull() }
        if (b == null || b.size != 4 || b.any { it == null }) throw Refused(400, "bbox must be west,south,east,north in degrees")
        val (w, s, e, n) = b.map { it!! }
        if (w < -180 || e > 180 || s < -WebMercator.MAX_LAT || n > WebMercator.MAX_LAT || w >= e || s >= n) {
            throw Refused(400, "bbox $w,$s,$e,$n is not a box: need -180 <= west < east <= 180 and -85.05 <= south < north <= 85.05 (boxes across the antimeridian are not supported)")
        }
        if (e - w > maxSpanDegrees || n - s > maxSpanDegrees) {
            throw Refused(413, "box is ${fmt(e - w)} x ${fmt(n - s)} degrees; the limit is $maxSpanDegrees degrees per side (DYSTOPO_BUILD_MAX_SPAN_DEG): pick a smaller box")
        }
        val minZoom = params["minZoom"]?.let { it.toIntOrNull() ?: throw Refused(400, "minZoom must be an integer") } ?: 8
        val maxZoom = params["maxZoom"]?.let { it.toIntOrNull() ?: throw Refused(400, "maxZoom must be an integer") } ?: 13
        if (minZoom < 0 || minZoom > maxZoom || maxZoom > LiveTiles.LIVE_MAX_ZOOM) {
            throw Refused(400, "need 0 <= minZoom <= maxZoom <= ${LiveTiles.LIVE_MAX_ZOOM} (got $minZoom..$maxZoom)")
        }
        if (maxZoom < LiveTiles.LIVE_MIN_ZOOM) throw Refused(400, "maxZoom must be at least ${LiveTiles.LIVE_MIN_ZOOM}: the live DEM starts there")
        val box = BoundingBox(w, s, e, n)
        val demZooms = maxOf(minZoom, LiveTiles.LIVE_MIN_ZOOM)..maxZoom
        val osmZooms = (minZoom..minOf(maxZoom, LiveTiles.OSM_MAX_ZOOM)).takeIf { !it.isEmpty() }
        val tiles = demZooms.sumOf { countTiles(box, it) } + (osmZooms?.sumOf { countTiles(box, it) } ?: 0)
        if (tiles > maxTiles) {
            val fits = (maxZoom downTo minZoom).firstOrNull { z ->
                (maxOf(minZoom, LiveTiles.LIVE_MIN_ZOOM)..z).sumOf { countTiles(box, it) } + (minZoom..minOf(z, LiveTiles.OSM_MAX_ZOOM)).sumOf { countTiles(box, it) } <= maxTiles
            }
            throw Refused(
                413,
                "box needs $tiles tiles at z$minZoom-$maxZoom (DEM and OSM together); the limit is $maxTiles per job (DYSTOPO_BUILD_MAX_TILES): " +
                    (fits?.let { "maxZoom=$it fits, or pick a smaller box" } ?: "pick a smaller box"),
            )
        }
        val overwrite = params["overwrite"].let { it == "1" || it == "true" }
        synchronized(jobs) {
            if (jobs.values.any { it.active && it.name == name }) throw Refused(409, "a build of '$name' is already queued or running")
            val existing = PackRole.entries.map { File(outDir, PackNames.fileName(name, it)) }.filter { it.exists() }
            if (existing.isNotEmpty() && !overwrite) throw Refused(409, "${existing.joinToString { it.name }} already exist(s) in ${outDir.path}; add overwrite=1 or pick another name")
            val active = jobs.values.count { it.active }
            if (active >= concurrentBuilds + maxQueued) throw Refused(429, "$active builds are queued or running (limit ${concurrentBuilds + maxQueued}); try again later")
            val job = Job("b${nextId.getAndIncrement()}", name, box, minZoom, maxZoom, demZooms, osmZooms, overwrite)
            jobs[job.id] = job
            executor.execute { run(job) }
            return job
        }
    }

    /** Asks [job] to stop; a queued job never starts. Returns false when it had already finished. */
    fun cancel(job: Job): Boolean {
        if (!job.active) return false
        job.cancelled = true
        synchronized(job) { if (job.status == Status.QUEUED) finish(job, Status.CANCELLED) }
        return true
    }

    // ---- the build ----

    private fun run(job: Job) {
        synchronized(job) {
            if (job.cancelled || job.status != Status.QUEUED) return
            job.status = Status.RUNNING
            job.startedNs = System.nanoTime()
        }
        outDir.mkdirs()
        val demOut = File(outDir, PackNames.fileName(job.name, PackRole.DEM))
        val osmOut = File(outDir, PackNames.fileName(job.name, PackRole.VECTOR))
        val demTmp = File(outDir, demOut.name + ".building")
        val osmTmp = File(outDir, osmOut.name + ".building")
        try {
            buildDem(job, demTmp)
            if (job.osmZooms != null) buildOsm(job, osmTmp)
            job.phase = "publish"
            val published = listOfNotNull(demTmp to demOut, osmTmp.takeIf { it.isFile }?.let { it to osmOut })
            for ((tmp, out) in published) {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                job.files += describe(out)
                live.addPack(out)
            }
            finish(job, Status.DONE)
        } catch (e: Throwable) {
            val cause = (e as? CompletionException)?.cause ?: e
            demTmp.delete()
            osmTmp.delete()
            if (cause is CancelledBuild || job.cancelled) finish(job, Status.CANCELLED)
            else {
                job.error = cause.message ?: cause.toString()
                finish(job, Status.FAILED)
            }
        }
    }

    private fun finish(job: Job, status: Status) {
        job.status = status
        job.phase = status.label
        job.endedNs = System.nanoTime()
        if (job.startedNs == 0L) job.startedNs = job.endedNs
    }

    private fun buildDem(job: Job, out: File) {
        job.phase = "dem"
        val started = System.nanoTime()
        val provenance = LinkedHashSet<String>()
        val writtenZooms = java.util.TreeSet<Int>()
        DemPmtilesWriter(out).use { w ->
            for (z in job.demZooms) {
                pump(job, WebMercator.tilesCovering(job.box, z), demWindow, { t -> live.liveDemAsync(t.z, t.x, t.y) }) { t, d ->
                    job.dem.done.incrementAndGet()
                    if (d.cached) job.dem.cached.incrementAndGet()
                    // No contributing source (open ocean): leave the tile out, as `tiles` does; the app overzooms an ancestor.
                    if (d.provenance.isEmpty()) job.dem.empty.incrementAndGet()
                    else {
                        w.add(t, d.png)
                        job.dem.written.incrementAndGet()
                        provenance += d.provenance
                        writtenZooms += t.z
                    }
                }
            }
            if (job.dem.written.get() == 0) error("no elevation data in the box; nothing to put in ${out.name.removeSuffix(".building")}")
            job.timings["demMs"] = (System.nanoTime() - started) / 1_000_000
            val writeStarted = System.nanoTime()
            val ids = ElevationSource.mergeProvenance(provenance.toList())
            job.demProvenance = ids
            val registry = SourceRegistry.builtIn
            val meta = TileSetMetadata(
                name = "${job.name}-dem", tileSize = 256, bounds = TilesCommand.geoJsonBounds(job.box),
                minzoom = writtenZooms.first(), maxzoom = writtenZooms.last(), source = "best",
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids), provenance = ids,
            )
            // The header's zooms are the ones that have tiles (a viewer asks nothing outside them).
            w.finish(meta, job.box, writtenZooms.first(), writtenZooms.last())
            job.timings["demWriteMs"] = (System.nanoTime() - writeStarted) / 1_000_000
        }
    }

    private fun buildOsm(job: Job, out: File) {
        job.phase = "osm"
        val zooms = job.osmZooms ?: return
        val started = System.nanoTime()
        val (build, tileJson) = live.openFreeMapTileJson()
        val staging = FileTileStaging(File(outDir, out.name.removeSuffix(".building") + ".staging"))
        staging.use {
            val writer = OsmPackWriter(Gzip.compress, staging)
            for (z in zooms) {
                pump(job, WebMercator.tilesCovering(job.box, z), osmWindow, { t ->
                    // Checked again on the pool thread: a tile queued before a cancel must not reach OpenFreeMap.
                    CompletableFuture.supplyAsync({ if (job.cancelled) throw CancelledBuild() else live.openFreeMap(t.z, t.x, t.y) }, osmPool)
                }) { t, tile ->
                    job.osm.done.incrementAndGet()
                    if (tile.cached) job.osm.cached.incrementAndGet()
                    if (writer.add(t.z, t.x, t.y, tile.bytes)) job.osm.written.incrementAndGet() else job.osm.empty.incrementAndGet()
                }
            }
            job.timings["osmMs"] = (System.nanoTime() - started) / 1_000_000
            if (writer.tiles == 0) return // all ocean: the pack is DEM-only
            val writeStarted = System.nanoTime()
            val registry = SourceRegistry.builtIn
            val ids = registry.ordered(LiveTiles.OSM_REGISTRY_IDS)
            job.osmProvenance = ids
            val meta = OsmPackMetadata(
                name = "${job.name}-outdoors",
                bounds = TilesCommand.geoJsonBounds(job.box),
                minZoom = zooms.first, maxZoom = zooms.last,
                vectorLayers = OsmPackWriter.vectorLayers(tileJson),
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids),
                provenance = ids, license = registry.getValue("osm").license, sourceBuild = build,
            )
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { os -> writer.finish(ByteSink { os.write(it) }, meta) }
            job.timings["osmWriteMs"] = (System.nanoTime() - writeStarted) / 1_000_000
        }
    }

    /**
     * Requests [tiles] through [start] with at most [window] outstanding and hands each result to
     * [consume] in order, on this thread. Stops requesting as soon as the job is cancelled.
     */
    private fun <T> pump(job: Job, tiles: List<TileId>, window: Int, start: (TileId) -> CompletableFuture<T>, consume: (TileId, T) -> Unit) {
        val queue = ArrayDeque<Pair<TileId, CompletableFuture<T>>>()
        var next = 0
        while (next < tiles.size || queue.isNotEmpty()) {
            if (job.cancelled) throw CancelledBuild()
            while (next < tiles.size && queue.size < window) {
                queue.addLast(tiles[next] to start(tiles[next]))
                next++
            }
            val (t, f) = queue.removeFirst()
            val value = try {
                f.join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
            consume(t, value)
        }
    }

    private fun describe(file: File): Map<String, Any?> {
        val h = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors).header
        return linkedMapOf(
            "url" to "/out/${file.name}", "path" to file.path, "bytes" to file.length(), "tileType" to h.tileType.label,
            "tiles" to h.addressedTiles, "tileContents" to h.tileContents, "minZoom" to h.minZoom, "maxZoom" to h.maxZoom,
            "bounds" to listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
        )
    }

    // ---- HTTP ----

    /** Answers `/build` and `/build/{id}[/cancel]`. */
    fun handle(ex: HttpExchange, path: String) {
        try {
            val method = ex.requestMethod
            val parts = path.removePrefix("/build").trim('/').split('/').filter { it.isNotEmpty() }
            when {
                method == "OPTIONS" -> reply(ex, 204, null)
                parts.isEmpty() && method == "POST" -> reply(ex, 202, submit(query(ex)).json())
                parts.isEmpty() && method == "GET" -> reply(ex, 200, linkedMapOf("jobs" to synchronized(jobs) { jobs.values.toList() }.map { it.json() }, "limits" to limits()))
                parts.isEmpty() -> throw Refused(405, "use POST /build?name=&bbox=w,s,e,n&minZoom=&maxZoom= or GET /build")
                else -> {
                    val job = job(parts[0]) ?: throw Refused(404, "no build job '${parts[0]}'")
                    when {
                        parts.size == 1 && method == "GET" -> reply(ex, 200, job.json())
                        (parts.size == 1 && method == "DELETE") || (parts.size == 2 && parts[1] == "cancel" && method == "POST") -> {
                            cancel(job)
                            reply(ex, 200, job.json())
                        }
                        else -> throw Refused(405, "GET /build/{id}, DELETE /build/{id} or POST /build/{id}/cancel")
                    }
                }
            }
        } catch (e: Refused) {
            reply(ex, e.status, mapOf("error" to e.message, "status" to e.status, "limits" to limits()))
        }
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    private fun reply(ex: HttpExchange, status: Int, body: Map<String, Any?>?) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        if (body == null) {
            ex.sendResponseHeaders(status, -1)
            ex.close()
            return
        }
        val bytes = (JsonWriter.write(body) + "\n").toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun fmt(d: Double) = "%.3f".format(java.util.Locale.ROOT, d)

        /** Tiles of zoom [z] covering [box] (as [WebMercator.tilesCovering] lists them), without listing them. */
        fun countTiles(box: BoundingBox, z: Int): Int {
            val max = (1 shl z) - 1
            val x0 = floor(WebMercator.lonToX(box.west, z)).toInt().coerceIn(0, max)
            val x1 = floor(WebMercator.lonToX(box.east, z)).toInt().coerceIn(0, max)
            val y0 = floor(WebMercator.latToY(box.north, z)).toInt().coerceIn(0, max)
            val y1 = floor(WebMercator.latToY(box.south, z)).toInt().coerceIn(0, max)
            return (x1 - x0 + 1) * (y1 - y0 + 1)
        }
    }
}
