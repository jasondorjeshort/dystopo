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
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
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
 * - `POST /build?...&profile=outdoors[&fallback=1]` makes the vector pack with tilebuild's outdoors
 *   profile instead (wilderness, cliffs with ticks, alpine and climbing routes, huts): one Overpass
 *   request for the box ([OverpassSource]: area cap, timeout, cached by box), converted to `.osm.pbf`
 *   and built by tilebuild in a subprocess ([TilebuildProcess]); the archive says
 *   `dystopo:profile=outdoors` and credits `overpass` in its provenance. A box over the Overpass area cap
 *   is a 413 (with `fallback=1` it is built from OpenFreeMap instead, with a warning in the job); when
 *   Overpass refuses at build time (429, 504, a timeout, no network) the job falls back to OpenFreeMap
 *   and says so in `warnings`.
 * - `GET /build/{id}`: status (queued, running, done, failed, cancelled), phase, per-pack tile counts,
 *   timings, the finished files' `/out/` URLs, provenance. `GET /build`: every job plus the limits.
 * - `DELETE /build/{id}` or `POST /build/{id}/cancel`: stops the job; no new tile is requested after
 *   that (the few in flight finish into the cache), partial files are deleted.
 *
 * A box across the antimeridian (west > east, e.g. `bbox=179.2,-17.2,-179.8,-16.4`) is split at 180°
 * into two jobs, `<name>-e` (west..180) and `<name>-w` (-180..east), each a normal rectangular pack
 * pair; the 202 answer is the first job, and both carry `antimeridian` with the other's id.
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
    /** Raw OSM for `profile=outdoors` builds. */
    val overpass: OverpassSource = OverpassSource(),
    /** Runs tilebuild's outdoors profile on an Overpass extract; [TilebuildProcess] by default. */
    private val tilebuild: OutdoorsTileBuilder = TilebuildProcess(),
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
        /** The vector profile asked for: [PROFILE_OPENMAPTILES] (OpenFreeMap) or [PROFILE_OUTDOORS] (Overpass + tilebuild). */
        val profile: String = PROFILE_OPENMAPTILES,
    ) {
        /** The profile the vector pack was actually built with (differs from [profile] after a fallback). */
        @Volatile var osmProfile: String = profile
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var overpassInfo: Map<String, Any?>? = null
        @Volatile var tilebuildInfo: Map<String, Any?>? = null
        /** The last log line of a running tilebuild, as a progress hint. */
        @Volatile var detail: String? = null
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
        /** For half of a box split at the antimeridian: which half and the other job's id. */
        @Volatile var antimeridian: Map<String, String>? = null

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
                "profile" to profile, "osmProfile" to osmProfile, "detail" to detail,
                "overpass" to overpassInfo, "tilebuild" to tilebuildInfo,
                "warnings" to warnings.toList(),
                "error" to error,
            ).also { m -> antimeridian?.let { m["antimeridian"] = it } }
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
        "profiles" to listOf(PROFILE_OPENMAPTILES, PROFILE_OUTDOORS),
        "overpass" to overpass.describe(),
        "tilebuild" to linkedMapOf("available" to (tilebuild.unavailable() == null), "unavailable" to tilebuild.unavailable(), "how" to tilebuild.describe()),
    )

    /**
     * Validates a request and queues the job; throws [Refused] with the reason when it can't. A box
     * across the antimeridian queues two jobs (see the class comment) and returns the first.
     */
    fun submit(params: Map<String, String>): Job {
        val name = params["name"]?.trim().orEmpty()
        if (!PackNames.isValidName(name)) throw Refused(400, "name must be 1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit (got '$name')")
        val b = params["bbox"]?.split(',')?.map { it.trim().toDoubleOrNull() }
        if (b == null || b.size != 4 || b.any { it == null }) throw Refused(400, "bbox must be west,south,east,north in degrees")
        val (w, s, e, n) = b.map { it!! }
        if (w < -180 || w > 180 || e < -180 || e > 180 || s < -WebMercator.MAX_LAT || n > WebMercator.MAX_LAT || w == e || s >= n) {
            throw Refused(400, "bbox $w,$s,$e,$n is not a box: need west != east within -180..180 (west > east crosses the antimeridian) and -85.05 <= south < north <= 85.05")
        }
        val parts = antimeridianParts(name, w, s, e, n)
        val width = if (w < e) e - w else (180 - w) + (e + 180)
        if (width > maxSpanDegrees || n - s > maxSpanDegrees) {
            throw Refused(413, "box is ${fmt(width)} x ${fmt(n - s)} degrees; the limit is $maxSpanDegrees degrees per side (DYSTOPO_BUILD_MAX_SPAN_DEG): pick a smaller box")
        }
        for ((partName, _) in parts) {
            if (!PackNames.isValidName(partName)) throw Refused(400, "name '$name' is too long for the antimeridian halves ('$partName'): use at most 62 characters")
        }
        val minZoom = params["minZoom"]?.let { it.toIntOrNull() ?: throw Refused(400, "minZoom must be an integer") } ?: 8
        val maxZoom = params["maxZoom"]?.let { it.toIntOrNull() ?: throw Refused(400, "maxZoom must be an integer") } ?: 13
        if (maxZoom > LiveTiles.LIVE_MAX_ZOOM) {
            throw Refused(
                413,
                "maxZoom=$maxZoom is past z${LiveTiles.LIVE_MAX_ZOOM}, the deepest zoom of the live DEM; the app and MapLibre overzoom " +
                    "z${LiveTiles.LIVE_MAX_ZOOM} tiles, so use maxZoom=${LiveTiles.LIVE_MAX_ZOOM}",
            )
        }
        if (minZoom < 0 || minZoom > maxZoom) {
            throw Refused(400, "need 0 <= minZoom <= maxZoom <= ${LiveTiles.LIVE_MAX_ZOOM} (got $minZoom..$maxZoom)")
        }
        if (maxZoom < LiveTiles.LIVE_MIN_ZOOM) throw Refused(400, "maxZoom must be at least ${LiveTiles.LIVE_MIN_ZOOM}: the live DEM starts there")
        val boxes = parts.map { it.second }
        val profile = params["profile"]?.trim()?.lowercase()?.ifEmpty { null } ?: PROFILE_OPENMAPTILES
        if (profile != PROFILE_OPENMAPTILES && profile != PROFILE_OUTDOORS) {
            throw Refused(400, "profile must be $PROFILE_OPENMAPTILES (OpenFreeMap tiles) or $PROFILE_OUTDOORS (Overpass + tilebuild), got '$profile'")
        }
        val fallback = params["fallback"].let { it == "1" || it == "true" }
        val warnings = ArrayList<String>()
        var osmProfile = profile
        if (profile == PROFILE_OUTDOORS) {
            try {
                boxes.forEach { overpass.checkArea(it) }
                tilebuild.unavailable()?.let { throw Refused(503, "the outdoors profile needs tilebuild: $it") }
            } catch (e: OverpassSource.TooBig) {
                if (!fallback) {
                    throw Refused(413, "${e.message}. Pick a smaller box (zoom in), use profile=$PROFILE_OPENMAPTILES, or add fallback=1 to build this box from OpenFreeMap without the outdoors layers")
                }
                warnings += "outdoors profile not built: ${e.message}; built from OpenFreeMap (OpenMapTiles schema) instead"
                osmProfile = PROFILE_OPENMAPTILES
            }
        }
        val demZooms = maxOf(minZoom, LiveTiles.LIVE_MIN_ZOOM)..maxZoom
        val osmZooms = (minZoom..minOf(maxZoom, LiveTiles.OSM_MAX_ZOOM)).takeIf { !it.isEmpty() }
        fun tilesUpTo(z: Int) = boxes.sumOf { box ->
            (maxOf(minZoom, LiveTiles.LIVE_MIN_ZOOM)..z).sumOf { countTiles(box, it) } + (minZoom..minOf(z, LiveTiles.OSM_MAX_ZOOM)).sumOf { countTiles(box, it) }
        }
        val tiles = tilesUpTo(maxZoom)
        if (tiles > maxTiles) {
            val fits = (maxZoom downTo minZoom).firstOrNull { z -> tilesUpTo(z) <= maxTiles }
            throw Refused(
                413,
                "box needs $tiles tiles at z$minZoom-$maxZoom (DEM and OSM together); the limit is $maxTiles per job (DYSTOPO_BUILD_MAX_TILES): " +
                    (fits?.let { "maxZoom=$it fits, or pick a smaller box" } ?: "pick a smaller box"),
            )
        }
        val overwrite = params["overwrite"].let { it == "1" || it == "true" }
        synchronized(jobs) {
            for ((partName, _) in parts) {
                if (jobs.values.any { it.active && it.name == partName }) throw Refused(409, "a build of '$partName' is already queued or running")
                val existing = PackRole.entries.map { File(outDir, PackNames.fileName(partName, it)) }.filter { it.exists() }
                if (existing.isNotEmpty() && !overwrite) throw Refused(409, "${existing.joinToString { it.name }} already exist(s) in ${outDir.path}; add overwrite=1 or pick another name")
            }
            val active = jobs.values.count { it.active }
            if (active + parts.size > concurrentBuilds + maxQueued) throw Refused(429, "$active builds are queued or running (limit ${concurrentBuilds + maxQueued}); try again later")
            val made = parts.map { (partName, box) -> Job("b${nextId.getAndIncrement()}", partName, box, minZoom, maxZoom, demZooms, osmZooms, overwrite, profile).also { it.osmProfile = osmProfile; it.warnings += warnings } }
            if (made.size == 2) {
                made[0].antimeridian = mapOf("part" to "east", "other" to made[1].id)
                made[1].antimeridian = mapOf("part" to "west", "other" to made[0].id)
            }
            for (job in made) {
                jobs[job.id] = job
                executor.execute { run(job) }
            }
            return made.first()
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
            if (job.osmZooms != null) {
                if (job.osmProfile == PROFILE_OUTDOORS) buildOutdoors(job, osmTmp) else buildOsm(job, osmTmp)
            }
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
                license = TilesCommand.licenseExpression(ids, registry),
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
     * The vector pack with tilebuild's outdoors profile: one Overpass extract for the box (cached), then
     * tilebuild in a subprocess. When Overpass refuses or fails, the pack is built from OpenFreeMap
     * instead ([buildOsm]) and the job carries a warning; a tilebuild failure fails the job.
     */
    private fun buildOutdoors(job: Job, out: File) {
        val zooms = job.osmZooms ?: return
        job.phase = "osm-overpass"
        val started = System.nanoTime()
        val extract = try {
            overpass.extract(job.box)
        } catch (e: OverpassSource.Refused) {
            job.warnings += "Overpass refused or failed (${e.message}); the vector pack was built from OpenFreeMap (OpenMapTiles schema, no outdoors layers) instead"
            job.osmProfile = PROFILE_OPENMAPTILES
            job.overpassInfo = linkedMapOf("status" to e.status, "error" to e.message, "requests" to overpass.fetches)
            return buildOsm(job, out)
        }
        job.overpassInfo = linkedMapOf(
            "cached" to extract.cached, "bytes" to extract.bytes, "ms" to extract.ms, "file" to extract.file.name,
            "endpoint" to overpass.endpoint, "requests" to overpass.fetches, "cacheHits" to overpass.cacheHits,
        )
        job.timings["overpassMs"] = (System.nanoTime() - started) / 1_000_000
        if (job.cancelled) throw CancelledBuild()

        job.phase = "osm-tilebuild"
        val tbStarted = System.nanoTime()
        // tilebuild picks the output format from the extension, so it writes `<name>-outdoors.pmtiles` in a
        // staging directory; the result is then moved onto the job's `.building` file.
        val stagingDir = File(outDir, ".tilebuild-${job.id}")
        val staged = File(stagingDir, out.name.removeSuffix(".building"))
        try {
            val report = tilebuild.build(
                extract.file, staged, job.box, zooms, listOf(OVERPASS_ID),
                cancelled = { job.cancelled },
                log = { line -> job.detail = line.take(200) },
            )
            if (job.cancelled) throw CancelledBuild()
            if (!staged.isFile) error("tilebuild finished without writing ${staged.name}")
            Files.move(staged.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            job.tilebuildInfo = linkedMapOf(
                "totalSeconds" to report["totalSeconds"], "stageSeconds" to report["stageSeconds"],
                "xmlConversion" to report["xmlConversion"], "schema" to report["profile"],
            )
        } finally {
            job.detail = null
            stagingDir.deleteRecursively()
        }
        job.timings["tilebuildMs"] = (System.nanoTime() - tbStarted) / 1_000_000
        job.timings["osmMs"] = (System.nanoTime() - started) / 1_000_000

        val reader = PmtilesReader.open(FileRangeSource(out), Gzip.decompressors)
        val meta = reader.metadata()
        job.osmProvenance = io.github.jasondorjeshort.dystopo.attribution.PackAttribution.registryIds(meta)
        val written = reader.header.addressedTiles.toInt()
        job.osm.written.set(written)
        job.osm.done.set(job.osm.total)
        // "cached" = built from a cached Overpass answer (no network), "fetched" = from a fresh one.
        if (extract.cached) job.osm.cached.set(job.osm.total)
        job.osm.empty.set((job.osm.total - written).coerceAtLeast(0))
        if (meta["dystopo:profile"] != PROFILE_OUTDOORS) error("tilebuild wrote ${out.name} with dystopo:profile=${meta["dystopo:profile"]}, expected $PROFILE_OUTDOORS")
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
        /** `profile=` values; also the packs' `dystopo:profile`. */
        const val PROFILE_OPENMAPTILES = OsmPackWriter.PROFILE_OPENMAPTILES
        const val PROFILE_OUTDOORS = "outdoors"

        /** Registry id credited for an extract fetched from the Overpass API. */
        const val OVERPASS_ID = "overpass"

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun fmt(d: Double) = "%.3f".format(java.util.Locale.ROOT, d)

        /**
         * The pack name and box of each job for a request box: one, or for a box across the antimeridian
         * (west > east) two, `<name>-e` from west to 180 and `<name>-w` from -180 to east.
         */
        fun antimeridianParts(name: String, w: Double, s: Double, e: Double, n: Double): List<Pair<String, BoundingBox>> =
            if (w < e) listOf(name to BoundingBox(w, s, e, n))
            else listOf("$name-e" to BoundingBox(w, s, 180.0, n), "$name-w" to BoundingBox(-180.0, s, e, n))

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

/** Builds an outdoors-profile vector pack from an OSM XML extract (the tilebuild step of `profile=outdoors`). */
interface OutdoorsTileBuilder {
    /** Null when [build] can run; otherwise why not, with the fix. */
    fun unavailable(): String?

    /** How it runs, for `GET /build`. */
    fun describe(): String

    /**
     * Writes [out] (a `.pmtiles` path) from [osmXml] clipped to [box] at [zooms], crediting [sourceIds] on
     * top of the profile's own sources. Stops early when [cancelled] turns true. Returns tilebuild's JSON
     * report; throws with the reason on failure.
     */
    fun build(
        osmXml: File, out: File, box: BoundingBox, zooms: IntRange, sourceIds: List<String>,
        cancelled: () -> Boolean, log: (String) -> Unit,
    ): Map<String, Any?>
}

/**
 * Runs tilebuild as a subprocess, so Planetiler and GeoTools (LGPL, build-side only) stay out of devtool's
 * classpath. It runs from the installed distribution (`./gradlew :tilebuild:installDist`, once; set
 * `DYSTOPO_TILEBUILD_HOME` to use another install) as `java -cp <install>/lib/(every jar) ...MainKt`: the same
 * classpath the start script builds, without the start script (no .bat quoting, no CLASSPATH length
 * limit), and without the Gradle configuration time `:tilebuild:run` would add to every build. The JVM is
 * the one running devtool; the heap is `DYSTOPO_TILEBUILD_HEAP` (default 3g, plenty for the small boxes
 * Overpass serves). The child gets `DYSTOPO_CACHE`, so Planetiler's 1.45 GB of aux data is shared.
 */
class TilebuildProcess(
    private val home: File = File(System.getenv("DYSTOPO_TILEBUILD_HOME")?.takeIf { it.isNotBlank() } ?: "tilebuild/build/install/tilebuild"),
    private val heap: String = System.getenv("DYSTOPO_TILEBUILD_HEAP")?.takeIf { it.isNotBlank() } ?: "3g",
) : OutdoorsTileBuilder {
    private val lib get() = File(home, "lib")

    override fun unavailable(): String? =
        if (lib.listFiles { f -> f.name.startsWith("tilebuild") && f.name.endsWith(".jar") }?.isNotEmpty() == true) null
        else "no tilebuild install at ${home.path}: run ./gradlew :tilebuild:installDist once (or set DYSTOPO_TILEBUILD_HOME)"

    override fun describe(): String = "java -Xmx$heap -cp ${File(lib, "*").path} $MAIN (subprocess)"

    override fun build(
        osmXml: File, out: File, box: BoundingBox, zooms: IntRange, sourceIds: List<String>,
        cancelled: () -> Boolean, log: (String) -> Unit,
    ): Map<String, Any?> {
        unavailable()?.let { error(it) }
        out.absoluteFile.parentFile.mkdirs()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val bbox = listOf(box.west, box.south, box.east, box.north).joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }
        val cmd = mutableListOf(
            java, "-Xmx$heap", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
            "-cp", File(lib, "*").absolutePath, MAIN,
            "--extract", osmXml.absolutePath, "--out", out.absolutePath, "--bbox", bbox,
            "--profile", "outdoors", "--min-zoom", "${zooms.first}", "--max-zoom", "${zooms.last}",
        )
        for (id in sourceIds) cmd += listOf("--source-id", id)
        val pb = ProcessBuilder(cmd)
        pb.environment()["DYSTOPO_CACHE"] = Caches.root.absolutePath
        val process = pb.start()
        val tail = ConcurrentLinkedDeque<String>()
        val stderr = Thread({
            process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                tail.addLast(line)
                while (tail.size > 40) tail.pollFirst()
                if (line.isNotBlank()) log(line.trim())
            }
        }, "tilebuild-stderr").apply { isDaemon = true; start() }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    process.waitFor(10, TimeUnit.SECONDS)
                    out.delete()
                    return emptyMap()
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        stderr.join(5_000)
        val text = stdout.get(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            val why = tail.filter { "ERROR" in it || "Exception" in it || "Error:" in it }.takeLast(3).ifEmpty { tail.toList().takeLast(3) }
            error("tilebuild exited with ${process.exitValue()}: ${why.joinToString(" | ").take(600)}")
        }
        return MiniJson.parseObject(text.substring(text.indexOf('{').coerceAtLeast(0)))
    }

    companion object {
        const val MAIN = "io.github.jasondorjeshort.dystopo.tilebuild.MainKt"
    }
}
