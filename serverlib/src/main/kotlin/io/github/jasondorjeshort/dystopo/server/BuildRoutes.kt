package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.AssetItem
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.areas.LocalItem
import io.github.jasondorjeshort.dystopo.areas.ManifestItem
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
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
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
 * - Larger outdoors packs come from a regional OSM extract on the server's disk (T64, [Extracts]: the
 *   config's `extracts` list plus every `.osm.pbf` in `<cache>/extracts/`, e.g. Geofabrik's Washington file).
 *   With `profile=outdoors` the vector source is chosen per box ([planOsm]): a configured extract that
 *   holds the whole box (tilebuild runs Planetiler on it with `--bbox`, so whole-park boxes get the
 *   outdoors layers), else Overpass when the box is under its area cap, else (T66) a configured
 *   Geofabrik region whose `.poly` holds the box, downloaded on demand ([ExtractDownloads]; the job's
 *   phase is `osm-download` and `download` has the bytes), else OpenFreeMap with `fallback=1`. Without
 *   it the request is refused: 422 when no extract covers the box (the box is fine, this server has no
 *   data for it), 413 only for `source=overpass` over the Overpass area cap. `source=auto|extract|overpass`
 *   forces one (default `auto`). The job says which in `osmSource` and, for an extract,
 *   `provenance.osmExtract` (name, file, date of the data).
 * - `GET /build/sources?bbox=w,s,e,n`: what a `profile=outdoors` build of the box would use, with every
 *   extract and whether it covers the box, the Overpass cap, and whether tilebuild is installed.
 * - `GET /build/{id}`: status (queued, running, done, failed, cancelled), phase, per-pack tile counts,
 *   timings, the finished files' `/out/` URLs, provenance. `GET /build`: every job plus the limits.
 * - `DELETE /build/{id}` or `POST /build/{id}/cancel`: stops the job; no new tile is requested after
 *   that (the few in flight finish into the cache), partial files are deleted.
 *
 * - `POST /area` (T75): the manifest of an area of interest (`io.github.jasondorjeshort.dystopo.areas.AreaManifest`;
 *   field by field in docs/SERVER.md, Areas of interest): the area as a JSON body (`{"kind": "box"|"corridor", ...}`,
 *   or `{"area": {...}, "pack": 1}`) or as query parameters (`bbox=w,s,e,n` or `line=lat,lon;lat,lon`, `bufferM`,
 *   `minZoom`, `maxZoom`, `name`). A box (or `pack=1`) is a pack pair built through the same jobs as `POST /build`
 *   (outdoors profile with fallback when tilebuild is installed): 202 with `status: building` and the job under
 *   `build` while it runs (ask again when `GET /build/{id}` says done), then 200 with each archive's URL, size and
 *   SHA-256. A corridor (or `pack=0`) is the DEM and vector tiles within its buffer, as rows per zoom, with
 *   estimated sizes ([maxAreaTiles] at most, `DYSTOPO_AREA_MAX_TILES`). Both list the glyphs and sprites
 *   (bundled in the app) and what the phone builds itself (trail graph and search index, from a vector pack).
 *   `GET /area` describes the parameters and limits. T140 (ADR 0041): a polygon (`{"kind": "polygon", "rings": ...}`,
 *   or `ring=lat,lon;...`) is tiles like a corridor, as a version 2 manifest; `pack=1` on one is refused (400, a pack
 *   is a box); T152: its tile sets are rows, so only `maxAreaTiles` limits it.
 *   `GET /area` names the `shapes` read and `manifestVersion` 2. T157 (ADR 0041 §5's T157 note): an area's `demMaxZoom`
 *   (body or query; or `detail=full|medium|less` in the query) stops the DEM tiles below `maxZoom` while the vector tiles go
 *   to `min(maxZoom, 14)`: the medium detail is `maxZoom` 14 with `demMaxZoom` 13, a version 3 manifest; a capped area is
 *   tiles (`pack=1` on one is refused, 400). `GET /area` adds `zoomCaps` (`["dem"]`) and `details`, `manifestVersion` 3.
 *
 * A box across the antimeridian (west > east, e.g. `bbox=179.2,-17.2,-179.8,-16.4`) is split at 180°
 * into two jobs, `<name>-e` (west..180) and `<name>-w` (-180..east), each a normal rectangular pack
 * pair; the 202 answer is the first job, and both carry `antimeridian` with the other's id.
 *
 * T189 (ADR 0049's B), the `graph` phase: after `publish`, a pack with a vector archive gets its routing graph built
 * by the server's own route planner ([RouteRoutes.planner]: today's one-pass builder and encoder, the same file a first
 * route writes), `out/<name>-outdoors-graph.idx` with its `.sha256` sidecar, so the catalog lists it under `derived`
 * ([PackCatalog.graph]) and phones download it with the pack instead of building it. The job's `graph` says how it went
 * (`state` `done`, `failed` or `skipped`, the file, bytes, SHA-256, nodes, tiles, `buildMs`, `error`). The phase never
 * fails the pack: the archives are published before it starts, and a failure (an `OutOfMemoryError` on a server too
 * small for the box included) is a note on the job while the catalog lists no graph. The job stays `running` with phase
 * `graph` until the phase ends either way, so a phone polling `POST /area` gets the manifest with the graph. `graph=0`
 * skips the phase (measurement: a build without it).
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
    /** Regional OSM extracts for outdoors packs larger than Overpass serves (T64); none by default (`devtool serve` and the server scan `<cache>/extracts`). */
    val extracts: Extracts = Extracts(dir = null),
    /** Geofabrik regions downloaded on demand when no extract on disk covers a box (T66); null: none. */
    val downloads: ExtractDownloads? = null,
    val maxTiles: Int = env("DYSTOPO_BUILD_MAX_TILES")?.toIntOrNull() ?: 20_000,
    val maxSpanDegrees: Double = env("DYSTOPO_BUILD_MAX_SPAN_DEG")?.toDoubleOrNull() ?: 3.0,
    val concurrentBuilds: Int = env("DYSTOPO_BUILD_CONCURRENT")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
    val maxQueued: Int = env("DYSTOPO_BUILD_QUEUE")?.toIntOrNull()?.coerceAtLeast(0) ?: 4,
    /** Tiles requested ahead of the one being written (DEM renders run on the live pool). */
    private val demWindow: Int = 8,
    private val osmWindow: Int = 8,
    /** T75: tiles a corridor manifest may list, DEM and vector together. */
    val maxAreaTiles: Int = env("DYSTOPO_AREA_MAX_TILES")?.toIntOrNull() ?: 200_000,
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
        /** Where the vector data comes from: [SOURCE_EXTRACT], [SOURCE_OVERPASS] or [SOURCE_OPENFREEMAP]. */
        @Volatile var osmSource: String = if (profile == PROFILE_OUTDOORS) SOURCE_OVERPASS else SOURCE_OPENFREEMAP
        /** The extract an outdoors pack is built from (T64), when [osmSource] is [SOURCE_EXTRACT]. */
        @Volatile var extract: OsmExtract? = null
        /** A region to download first (T66), when no extract on disk holds the box; [extract] is set after. */
        @Volatile var download: ExtractDownloads.Region? = null
        @Volatile var downloadInfo: Map<String, Any?>? = null
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
        /** T189: whether the `graph` phase runs after `publish` (`graph=0` on the request turns it off). */
        @Volatile var graphPhase: Boolean = true
        /** T189: how the `graph` phase went (null until it starts). */
        @Volatile var graph: Map<String, Any?>? = null

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
                "bbox" to TileSets.geoJsonBounds(box), "minZoom" to minZoom, "maxZoom" to maxZoom,
                "percent" to if (total == 0) 100.0 else Math.round(1000.0 * done / total) / 10.0,
                "dem" to dem.json(demZooms), "osm" to osm.json(osmZooms),
                "elapsedMs" to elapsed, "timings" to timings.toSortedMap(),
                "files" to files.toList(),
                "provenance" to linkedMapOf("dem" to demProvenance, "osm" to osmProvenance, "osmExtract" to extract?.let { extractProvenance(it) }),
                "profile" to profile, "osmProfile" to osmProfile, "osmSource" to osmSource, "detail" to detail,
                "overpass" to overpassInfo, "tilebuild" to tilebuildInfo, "download" to downloadInfo,
                "warnings" to warnings.toList(),
                "error" to error,
                "graph" to graph,
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
        "sources" to listOf(SOURCE_AUTO, SOURCE_EXTRACT, SOURCE_OVERPASS),
        "overpass" to overpass.describe(),
        "extracts" to extracts.describe(),
        "extractDownloads" to linkedMapOf("maxMb" to downloads?.maxBytes?.div(1_048_576), "regions" to (downloads?.describe() ?: emptyList())),
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
        val source = params["source"]?.trim()?.lowercase()?.ifEmpty { null } ?: SOURCE_AUTO
        if (source !in setOf(SOURCE_AUTO, SOURCE_EXTRACT, SOURCE_OVERPASS)) {
            throw Refused(400, "source must be $SOURCE_AUTO, $SOURCE_EXTRACT or $SOURCE_OVERPASS (got '$source')")
        }
        if (profile == PROFILE_OUTDOORS) tilebuild.unavailable()?.let { throw Refused(503, "the outdoors profile needs tilebuild: $it") }
        val plans = boxes.map { box -> if (profile == PROFILE_OUTDOORS) planOsm(box, source, fallback) else OsmPlan(SOURCE_OPENFREEMAP, null, null) }
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
        val graphPhase = params["graph"].let { it == null || (it != "0" && it != "false") } // T189
        synchronized(jobs) {
            for ((partName, _) in parts) {
                if (jobs.values.any { it.active && it.name == partName }) throw Refused(409, "a build of '$partName' is already queued or running")
                val existing = PackRole.entries.map { File(outDir, PackNames.fileName(partName, it)) }.filter { it.exists() }
                if (existing.isNotEmpty() && !overwrite) throw Refused(409, "${existing.joinToString { it.name }} already exist(s) in ${outDir.path}; add overwrite=1 or pick another name")
            }
            val active = jobs.values.count { it.active }
            if (active + parts.size > concurrentBuilds + maxQueued) throw Refused(429, "$active builds are queued or running (limit ${concurrentBuilds + maxQueued}); try again later")
            val made = parts.mapIndexed { i, (partName, box) ->
                val plan = plans[i]
                Job("b${nextId.getAndIncrement()}", partName, box, minZoom, maxZoom, demZooms, osmZooms, overwrite, profile).also {
                    it.osmSource = plan.source
                    it.extract = plan.extract
                    it.download = plan.download
                    plan.download?.let { r -> it.downloadInfo = linkedMapOf("name" to r.name, "url" to r.pbfUrl, "bytes" to 0L, "total" to null) }
                    it.osmProfile = if (plan.source == SOURCE_OPENFREEMAP) PROFILE_OPENMAPTILES else PROFILE_OUTDOORS
                    plan.warning?.let { w -> it.warnings += w }
                    it.graphPhase = graphPhase
                }
            }
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

    /**
     * The vector source chosen for one box: [source] and, for [SOURCE_EXTRACT], the [extract] on disk or the
     * region to [download] first; [warning] after a fallback.
     */
    class OsmPlan(val source: String, val extract: OsmExtract?, val warning: String?, val download: ExtractDownloads.Region? = null)

    /**
     * Picks the vector source of a `profile=outdoors` build of [box] (T64): with [requested] `auto`, a
     * configured extract holding the whole box (the smallest), else Overpass under its area cap, else a
     * configured download region whose polygon holds the box (T66); `extract` (on disk or downloadable) or
     * `overpass` force one. When the choice cannot serve the box, OpenFreeMap with [fallback] (and a
     * warning), else a refusal that names the ways out: 413 when `source=overpass` and the box is over its
     * cap, 422 when no extract covers it.
     */
    fun planOsm(box: BoundingBox, requested: String, fallback: Boolean): OsmPlan {
        val extract = if (requested == SOURCE_OVERPASS) null else extracts.covering(box)
        if (extract != null) return OsmPlan(SOURCE_EXTRACT, extract, null)
        val why = if (requested == SOURCE_EXTRACT) {
            "no configured OSM extract covers the box (${extractSummary()})"
        } else {
            try {
                overpass.checkArea(box)
                return OsmPlan(SOURCE_OVERPASS, null, null)
            } catch (e: OverpassSource.TooBig) {
                if (requested == SOURCE_OVERPASS) e.message!! else "${e.message}, and no configured OSM extract covers it (${extractSummary()})"
            }
        }
        if (requested != SOURCE_OVERPASS) downloads?.covering(box)?.let { return OsmPlan(SOURCE_EXTRACT, null, null, download = it) }
        if (!fallback) {
            throw Refused(
                if (requested == SOURCE_OVERPASS) 413 else 422,
                "$why. Pick a smaller box (zoom in), add an extract that covers it (docs/SERVER.md, extracts and extractDownloads), use profile=$PROFILE_OPENMAPTILES, or add fallback=1 to build this box from OpenFreeMap without the outdoors layers",
            )
        }
        return OsmPlan(SOURCE_OPENFREEMAP, null, "outdoors profile not built: $why; built from OpenFreeMap (OpenMapTiles schema) instead")
    }

    private fun extractSummary(): String = extracts.all().let { all ->
        val local = if (all.isEmpty()) "none on disk" else all.joinToString(", ") { "${it.name} ${TileSets.geoJsonBounds(it.bbox).joinToString(",") { d -> fmt(d) }}" }
        val dl = downloads?.regions?.takeIf { it.isNotEmpty() }?.let { r -> "; downloadable: " + r.joinToString(", ") { it.name } } ?: ""
        local + dl
    }

    /** `GET /build/sources?bbox=`: what a `profile=outdoors` build of the box would use, and why. */
    fun sources(params: Map<String, String>): Map<String, Any?> {
        val b = params["bbox"]?.split(',')?.map { it.trim().toDoubleOrNull() }
        if (b == null || b.size != 4 || b.any { it == null }) throw Refused(400, "bbox must be west,south,east,north in degrees")
        val (w, s, e, n) = b.map { it!! }
        if (w >= e || s >= n) throw Refused(400, "bbox $w,$s,$e,$n is not a box (west < east, south < north; ask for each side of the antimeridian separately)")
        val box = BoundingBox(w, s, e, n)
        val all = extracts.all()
        val area = OverpassSource.areaSqDeg(box)
        val plan = planOsm(box, SOURCE_AUTO, fallback = true)
        val tb = tilebuild.unavailable()
        val outdoors = tb == null && plan.source != SOURCE_OPENFREEMAP
        return linkedMapOf(
            "bbox" to listOf(w, s, e, n),
            "areaSqDeg" to area,
            "outdoors" to linkedMapOf(
                "source" to if (tb != null) SOURCE_OPENFREEMAP else plan.source,
                "profile" to if (outdoors) PROFILE_OUTDOORS else PROFILE_OPENMAPTILES,
                "extract" to plan.extract?.json(),
                "download" to plan.download?.let { linkedMapOf("name" to it.name, "url" to it.pbfUrl) },
                "needsFallback" to !outdoors,
                "why" to (tb?.let { "tilebuild is not installed: $it" } ?: plan.warning),
            ),
            "extracts" to all.map { it.json() + ("covers" to it.covers(box)) },
            "extractProblems" to extracts.problems,
            "downloads" to (downloads?.describe(box) ?: emptyList()),
            "overpass" to linkedMapOf("underCap" to (area <= overpass.maxAreaSqDeg), "maxAreaSqDeg" to overpass.maxAreaSqDeg, "endpoint" to overpass.endpoint),
            "tilebuild" to linkedMapOf("available" to (tb == null), "unavailable" to tb),
        )
    }

    /** Asks [job] to stop; a queued job never starts. Returns false when it had already finished. */
    fun cancel(job: Job): Boolean {
        if (!job.active) return false
        job.cancelled = true
        synchronized(job) { if (job.status == Status.QUEUED) finish(job, Status.CANCELLED) }
        return true
    }

    /** Cancels every queued or running job and waits up to [waitMs] for them to stop (graceful shutdown, T52). */
    fun cancelAll(waitMs: Long): Int {
        val active = synchronized(jobs) { jobs.values.filter { it.active } }
        active.forEach { cancel(it) }
        val until = System.currentTimeMillis() + waitMs
        while (active.any { it.active } && System.currentTimeMillis() < until) Thread.sleep(50)
        return active.size
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
        var publishedPack = false
        try {
            buildDem(job, demTmp)
            if (job.osmZooms != null) {
                if (job.osmProfile == PROFILE_OUTDOORS) buildOutdoors(job, osmTmp) else buildOsm(job, osmTmp)
            }
            job.phase = "publish"
            val published = listOfNotNull(demTmp to demOut, osmTmp.takeIf { it.isFile }?.let { it to osmOut })
            for ((tmp, out) in published) {
                PackCatalog.sidecar(out).delete() // T58: the old file's hash must not outlive it
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                job.files += describe(out)
                live.addPack(out)
            }
            publishedPack = true
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
        if (!publishedPack) return
        // T189: outside the catch above, which fails the job and deletes the archives: the pack is published already.
        buildGraph(job, demOut, osmOut)
        finish(job, Status.DONE)
    }

    /** T189: the server's planner for this build's `out/` (the routes' own when they share it, so a route and the phase never build one graph twice). */
    private val graphPlanner: RoutePlanner by lazy {
        if (live.outDir.absoluteFile.normalize() == outDir.absoluteFile.normalize()) RouteRoutes.planner(live) else RoutePlanner(outDir)
    }

    /**
     * T189, the `graph` phase: builds (or finds current) the routing graph of the published pack through [graphPlanner] and
     * hashes it into its sidecar, so the catalog's `derived` list names it. Never throws; a failure is the job's `graph`.
     */
    private fun buildGraph(job: Job, demOut: File, osmOut: File) {
        val graphFile = PackCatalog.graphFile(osmOut)
        val dem = demOut.takeIf { it.isFile }
        fun skipped(why: String) { job.graph = linkedMapOf("state" to "skipped", "reason" to why) }
        when {
            !osmOut.isFile -> return skipped("no vector archive (a terrain-only pack has no trails)")
            !job.graphPhase -> return skipped("graph=0")
            job.cancelled -> return skipped("cancelled")
        }
        job.phase = "graph"
        job.graph = linkedMapOf("state" to "building", "file" to graphFile.name)
        val started = System.nanoTime()
        try {
            val h = PmtilesReader.open(FileRangeSource(osmOut), Gzip.decompressors).header
            val pack = RoutePlanner.Pack(job.name, osmOut, dem, BoundingBox(h.minLon, h.minLat, h.maxLon, h.maxLat))
            val (archive, info) = graphPlanner.graph(pack)
            val sha = live.catalog.sha256(graphFile)
            val listed = live.catalog.graph(osmOut, dem)
            job.timings["graphMs"] = (System.nanoTime() - started) / 1_000_000
            job.graph = linkedMapOf(
                "state" to "done", "action" to info["action"], "file" to graphFile.name, "url" to "/out/${graphFile.name}",
                "bytes" to graphFile.length(), "sha256" to sha, "sidecar" to PackCatalog.sidecar(graphFile).name,
                "version" to GraphTileFile.VERSION, "tileZoom" to archive.tileZoom, "tiles" to archive.entries.size,
                "nodes" to archive.entries.sumOf { it.nodes }, "edges" to archive.entries.sumOf { it.edges }, "buildMs" to info["buildMs"],
                "listed" to (listed != null), "problem" to if (listed == null) live.catalog.graphProblem(osmOut, dem) else null,
            )
        } catch (e: Throwable) {
            // Never a failed pack (ADR 0049): the archives stay published and phones build the graph themselves.
            job.timings["graphMs"] = (System.nanoTime() - started) / 1_000_000
            job.graph = linkedMapOf(
                "state" to "failed", "file" to graphFile.name, "error" to (e.message ?: e.toString()),
                "note" to "the pack is published without a graph: the catalog lists none and phones build their own",
            )
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
                name = "${job.name}-dem", tileSize = 256, bounds = TileSets.geoJsonBounds(job.box),
                minzoom = writtenZooms.first(), maxzoom = writtenZooms.last(), source = "best",
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids), provenance = ids,
                license = TileSets.licenseExpression(ids, registry),
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
                bounds = TileSets.geoJsonBounds(job.box),
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
        job.download?.takeIf { job.extract == null }?.let { region -> job.extract = downloadExtract(job, region) }
        job.extract?.let { return buildFromExtract(job, out, it, zooms) }
        job.phase = "osm-overpass"
        val started = System.nanoTime()
        val extract = try {
            overpass.extract(job.box)
        } catch (e: OverpassSource.Refused) {
            job.warnings += "Overpass refused or failed (${e.message}); the vector pack was built from OpenFreeMap (OpenMapTiles schema, no outdoors layers) instead"
            job.osmProfile = PROFILE_OPENMAPTILES
            job.osmSource = SOURCE_OPENFREEMAP
            job.overpassInfo = linkedMapOf("status" to e.status, "error" to e.message, "requests" to overpass.fetches)
            return buildOsm(job, out)
        }
        job.overpassInfo = linkedMapOf(
            "cached" to extract.cached, "bytes" to extract.bytes, "ms" to extract.ms, "file" to extract.file.name,
            "endpoint" to overpass.endpoint, "requests" to overpass.fetches, "cacheHits" to overpass.cacheHits,
        )
        job.timings["overpassMs"] = (System.nanoTime() - started) / 1_000_000
        if (job.cancelled) throw CancelledBuild()

        runTilebuild(job, out, extract.file, zooms, listOf(OVERPASS_ID), started)
        // "cached" = built from a cached Overpass answer (no network), "fetched" = from a fresh one.
        if (extract.cached) job.osm.cached.set(job.osm.total)
    }

    /**
     * Downloads [region]'s extract for [job] (T66): phase `osm-download`, `download` in the job JSON with
     * the bytes so far, then the extract as [Extracts] reads it (header date, the region polygon).
     */
    private fun downloadExtract(job: Job, region: ExtractDownloads.Region): OsmExtract {
        val dl = downloads ?: error("no extract downloads configured")
        job.phase = "osm-download"
        val started = System.nanoTime()
        val watcher = Thread {
            while (job.active && job.phase == "osm-download") {
                dl.progress[region.name]?.let { p -> job.downloadInfo = linkedMapOf("name" to region.name, "url" to region.pbfUrl, "bytes" to p[0], "total" to p[1].takeIf { it >= 0 }) }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val file = try {
            dl.download(region) { job.cancelled }
        } catch (e: java.io.IOException) {
            if (job.cancelled) throw CancelledBuild()
            throw IllegalStateException("downloading the ${region.name} extract failed: ${e.message}", e)
        } finally {
            watcher.interrupt()
        }
        job.timings["downloadMs"] = (System.nanoTime() - started) / 1_000_000
        job.downloadInfo = linkedMapOf("name" to region.name, "url" to region.pbfUrl, "bytes" to file.length(), "total" to file.length(), "file" to file.name, "sha256" to dl.lastSha256[region.name])
        if (job.cancelled) throw CancelledBuild()
        val target = file.absoluteFile.normalize()
        return extracts.all().firstOrNull { it.file.absoluteFile.normalize() == target }
            ?: error("the downloaded ${region.name} extract is not usable: ${extracts.problems[file.path] ?: "not in the extracts directory ${extracts.dir}"}")
    }

    /**
     * The vector pack with the outdoors profile from a regional extract (T64): tilebuild runs Planetiler on
     * the whole `.osm.pbf` with the job's box as bounds. No network; the extract's name and data date go into
     * the job's provenance (`osmExtract`).
     */
    private fun buildFromExtract(job: Job, out: File, extract: OsmExtract, zooms: IntRange) {
        if (!extract.file.isFile) error("OSM extract ${extract.file} disappeared")
        runTilebuild(job, out, extract.file, zooms, emptyList(), System.nanoTime())
        job.osm.cached.set(job.osm.total)
    }

    /** tilebuild on [osm] (Overpass XML or an extract's PBF) into [out], then the job's counts and provenance from the archive. */
    private fun runTilebuild(job: Job, out: File, osm: File, zooms: IntRange, sourceIds: List<String>, started: Long) {
        if (job.cancelled) throw CancelledBuild()
        job.phase = if (job.extract != null) "osm-extract" else "osm-tilebuild"
        val tbStarted = System.nanoTime()
        // tilebuild picks the output format from the extension, so it writes `<name>-outdoors.pmtiles` in a
        // staging directory; the result is then moved onto the job's `.building` file.
        val stagingDir = File(outDir, ".tilebuild-${job.id}")
        val staged = File(stagingDir, out.name.removeSuffix(".building"))
        try {
            val report = tilebuild.build(
                osm, staged, job.box, zooms, sourceIds,
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

    // ---- areas of interest (T75) ----

    /** `GET /area`: what `POST /area` takes. */
    fun areaHelp(): Map<String, Any?> = linkedMapOf(
        "post" to "POST /area with a JSON body {\"kind\": \"box\", \"bbox\": [w, s, e, n], \"minZoom\": 8, \"maxZoom\": 14}, " +
            "{\"kind\": \"corridor\", \"line\": [[lon, lat], ...], \"bufferM\": 500, \"minZoom\": 8, \"maxZoom\": 15} or " +
            "{\"kind\": \"polygon\", \"rings\": [[[lon, lat], ...], ...], \"bufferM\": 0, \"minZoom\": 8, \"maxZoom\": 15}, or query parameters " +
            "bbox=, line=lat,lon;lat,lon, ring=lat,lon;lat,lon;lat,lon[|...] or around=lat,lon with radiusM, bufferM, minZoom, maxZoom, name; " +
            "options pack=1|0 (a polygon is tiles only), profile, fallback, source, retry=1; " +
            "demMaxZoom (in the body or the query) stops the DEM tiles below maxZoom (tiles only), detail=full|medium|less sets the zooms",
        // T140 (ADR 0041 §3): the newest manifest version and the shapes this server reads; a phone treats a missing `shapes` as box and corridor.
        "manifestVersion" to AreaManifest.VERSION, "shapes" to AreaShape.KINDS, "maxAreaTiles" to maxAreaTiles,
        "maxZoom" to AreaOfInterest.MAX_ZOOM,
        // T157 (ADR 0041 §5's T157 note): the per-kind zoom caps read (`demMaxZoom`) and the details; a phone treats a missing `zoomCaps` as none.
        "zoomCaps" to listOf(AreaManifest.ROUTE_DEM),
        "details" to AreaDetail.entries.associate { d -> d.key to linkedMapOf("minZoom" to AreaDetail.MIN_ZOOM, "maxZoom" to d.maxZoom, "demMaxZoom" to d.demMaxZoom) },
        "bufferM" to listOf(AreaOfInterest.MIN_BUFFER_M, AreaOfInterest.MAX_BUFFER_M), "build" to limits(),
    )

    /**
     * `POST /area`: the manifest of the area in [params] or [body] (see the class comment). Returns the HTTP status
     * (200 ready or failed, 202 building) and the manifest JSON; throws [Refused] for a bad area or one over a limit.
     */
    fun area(params: Map<String, String>, body: String): Pair<Int, Map<String, Any?>> {
        val doc = body.trim().takeIf { it.startsWith("{") }?.let {
            try {
                MiniJson.parseObject(it)
            } catch (e: IllegalArgumentException) {
                throw Refused(400, "body: ${e.message}")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val areaDoc = (doc?.get("area") as? Map<String, Any?>) ?: doc?.takeIf { it["kind"] != null || it["bbox"] != null || it["line"] != null }
        val opts = params + doc.orEmpty().filterKeys { it in AREA_OPTIONS }.mapValues { it.value.toString() }
        val area = try {
            if (areaDoc != null) AreaOfInterest.parse(areaDoc) else AreaOfInterest.fromQuery(params)
        } catch (e: IllegalArgumentException) {
            throw Refused(400, e.message ?: "bad area")
        }
        // T157: a capped area is tiles by default (a pack holds one zoom range), and a pack of one is refused.
        val wantPack = opts["pack"]?.let { it == "1" || it == "true" } ?: (area.shape is AreaShape.Box && area.demMaxZoom == null)
        if (wantPack && area.shape is AreaShape.Polygon) throw Refused(400, POLYGON_NO_PACK) // T140 (ADR 0041 §3)
        if (wantPack && area.demMaxZoom != null) throw Refused(400, CAPPED_NO_PACK)
        val name = opts["name"]?.trim()?.ifEmpty { null } ?: area.name?.takeIf { PackNames.isValidName(it) } ?: ("area-" + area.id.drop(1).take(12))
        val items = ArrayList<ManifestItem>()
        val notes = ArrayList<String>()
        var status = AreaManifest.STATUS_READY
        var build: Map<String, Any?>? = null
        if (wantPack) {
            if (!PackNames.isValidName(name)) throw Refused(400, "name must be 1 to 64 letters, digits, '.', '_' or '-' (got '$name')")
            val (active, last) = synchronized(jobs) { jobs.values.lastOrNull { it.name == name && it.active } to jobs.values.lastOrNull { it.name == name } }
            val files = PackRole.entries.map { File(outDir, PackNames.fileName(name, it)) to it }.filter { it.first.isFile }
            when {
                active != null -> {
                    status = AreaManifest.STATUS_BUILDING
                    build = active.json()
                }
                files.any { it.second == PackRole.DEM } -> items += files.map { (f, role) -> packItem(name, f, role) }
                last != null && last.status == Status.FAILED && opts["retry"] != "1" -> {
                    status = AreaManifest.STATUS_FAILED
                    build = last.json()
                    notes += "the build of $name failed: ${last.error}; ask again with retry=1"
                }
                else -> {
                    val b = area.bounds
                    val outdoors = tilebuild.unavailable() == null
                    val profile = opts["profile"] ?: if (outdoors) PROFILE_OUTDOORS else PROFILE_OPENMAPTILES
                    if (!outdoors && opts["profile"] == null) notes += "tilebuild is not installed here: the vector pack is OpenMapTiles from OpenFreeMap"
                    val job = submit(
                        mapOf(
                            "name" to name, "bbox" to listOf(b.minLon, b.minLat, b.maxLon, b.maxLat).joinToString(",") { String.format(Locale.ROOT, "%.6f", it) },
                            "minZoom" to "${area.minZoom}", "maxZoom" to "${minOf(area.maxZoom, LiveTiles.LIVE_MAX_ZOOM)}",
                            "profile" to profile, "fallback" to (opts["fallback"] ?: "1"), "source" to (opts["source"] ?: SOURCE_AUTO),
                            "overwrite" to "1",
                        ),
                    )
                    status = AreaManifest.STATUS_BUILDING
                    build = job.json()
                }
            }
            val vector = PackNames.fileName(name, PackRole.VECTOR)
            // T189: the graph the build's `graph` phase wrote, when it matches the archives; else the phone builds it.
            val shipped = if (status == AreaManifest.STATUS_READY) File(outDir, vector).takeIf { it.isFile }?.let { v ->
                runCatching { live.catalog.graph(v, File(outDir, PackNames.fileName(name, PackRole.DEM)).takeIf { it.isFile }) }.getOrNull()
            } else null
            items += if (shipped != null) {
                LocalItem(ManifestItem.KIND_GRAPH, vector, true, "the server built the trail graph tiles (z11) from the vector pack; the phone downloads them with the pack and builds its own only when they do not match", shipped)
            } else {
                LocalItem(ManifestItem.KIND_GRAPH, vector, true, "the phone builds the trail graph tiles (z11) from the vector pack on first use")
            }
            items += LocalItem(ManifestItem.KIND_SEARCH, vector, true, "the phone builds the place search index from the vector pack")
        } else {
            // T157: the DEM to the area's demMaxZoom when it has one, the vector to maxZoom (both within the live sources').
            val demZooms = area.demZooms(LiveTiles.LIVE_MIN_ZOOM, LiveTiles.LIVE_MAX_ZOOM)
            val osmZooms = area.osmZooms(LiveTiles.OSM_MAX_ZOOM)
            val polygon = area.shape is AreaShape.Polygon
            // T152 (ADR 0041 §3's T152 note): tile sets are rows, so no heap check before they are filled; maxAreaTiles below is the limit.
            val tiles = AreaManifest.tileItems(area, demZooms, osmZooms)
            val count = tiles.sumOf { it.count }
            if (count > maxAreaTiles) {
                val remedy = if (polygon) "lower maxZoom or draw a smaller area" else "lower maxZoom, narrow the buffer or split the route"
                throw Refused(413, "the area needs $count tiles at z${area.minZoom}-${area.maxZoom}${area.demMaxZoom?.let { " (DEM to z$it)" } ?: ""}; the limit is $maxAreaTiles (DYSTOPO_AREA_MAX_TILES): $remedy")
            }
            val what = if (polygon) "an area" else "a corridor"
            items += tiles
            items += LocalItem(ManifestItem.KIND_CONTOURS, "dem", true, "contour, hillshade and cliff tiles are made on the phone from the DEM tiles")
            items += LocalItem(ManifestItem.KIND_GRAPH, null, false, "$what is tiles in the phone's cache; the trail graph is built from vector packs (download a box for it)")
            notes += "DEM tiles from z${demZooms.first} (contours sample coarser zooms than they show) and one tile of margin around ${if (polygon) "the area" else "the corridor"} at every zoom"
        }
        items += glyphItem()
        items += spriteItem()
        val manifest = AreaManifest(
            id = area.id, name = area.name ?: name, area = area, status = status, items = items,
            generatedAt = java.time.Instant.now().toString(), build = build, notes = notes,
        )
        val json = manifest.json() + (if (wantPack) mapOf("pack" to name) else emptyMap())
        return (if (status == AreaManifest.STATUS_BUILDING) 202 else 200) to json
    }

    private fun packItem(name: String, file: File, role: PackRole): FileItem {
        val d = describe(file)
        return FileItem(
            role = role.label, pack = name, file = file.name, url = "/out/${file.name}", bytes = file.length(),
            sha256 = live.catalog.sha256(file), tileType = d["tileType"]?.toString(),
            minZoom = (d["minZoom"] as? Number)?.toInt(), maxZoom = (d["maxZoom"] as? Number)?.toInt(),
            bounds = (d["bounds"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() },
        )
    }

    private fun glyphItem(): AssetItem {
        val files = Caches.dir("glyphs").walkTopDown().filter { it.isFile && it.name.endsWith(".pbf") }.toList()
        return AssetItem(
            ManifestItem.KIND_GLYPHS, "/glyphs/{fontstack}/{range}.pbf", files.size, files.sumOf { it.length() }, bundled = true,
            names = files.mapNotNull { it.parentFile?.name }.distinct().sorted(),
        )
    }

    private fun spriteItem(): AssetItem {
        val files = SPRITE_FILES.map { File(outDir, it) }
        return AssetItem(ManifestItem.KIND_SPRITE, "/{file}", files.size, files.filter { it.isFile }.sumOf { it.length() }, bundled = true, names = SPRITE_FILES)
    }

    // ---- HTTP ----

    /** Answers `/build` and `/build/{id}[/cancel]`, and (T75) `/area`. */
    fun handle(ex: HttpExchange, path: String) {
        try {
            val method = ex.requestMethod
            if (path == "/area") {
                when (method) {
                    "OPTIONS" -> reply(ex, 204, null)
                    "POST" -> {
                        val body = ex.requestBody.use { it.readNBytes(MAX_AREA_BODY + 1) }
                        if (body.size > MAX_AREA_BODY) throw Refused(413, "the area body is over ${MAX_AREA_BODY / 1024} KiB; simplify the line")
                        val (status, manifest) = area(query(ex), body.decodeToString())
                        reply(ex, status, manifest)
                    }
                    "GET" -> reply(ex, 200, areaHelp())
                    else -> throw Refused(405, "POST /area with a JSON body or bbox= / line=; GET /area for the parameters")
                }
                return
            }
            val parts = path.removePrefix("/build").trim('/').split('/').filter { it.isNotEmpty() }
            when {
                method == "OPTIONS" -> reply(ex, 204, null)
                parts.isEmpty() && method == "POST" -> reply(ex, 202, submit(query(ex)).json())
                parts.isEmpty() && method == "GET" -> reply(ex, 200, linkedMapOf("jobs" to synchronized(jobs) { jobs.values.toList() }.map { it.json() }, "limits" to limits()))
                parts == listOf("sources") && method == "GET" -> reply(ex, 200, sources(query(ex)))
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

        /** `source=` values and the job's `osmSource`. */
        const val SOURCE_AUTO = "auto"
        const val SOURCE_EXTRACT = "extract"
        const val SOURCE_OVERPASS = "overpass"
        const val SOURCE_OPENFREEMAP = "openfreemap"

        /** The job's `provenance.osmExtract`: which extract (registry id `osm`, ODbL) and the date of its data. */
        fun extractProvenance(e: OsmExtract): Map<String, Any?> = linkedMapOf(
            "id" to "osm", "name" to e.name, "file" to e.file.name, "date" to e.date, "dateSource" to e.dateSource,
        )

        /** T75: `POST /area` options read from a JSON body as well as the query. */
        private val AREA_OPTIONS = setOf("pack", "name", "profile", "fallback", "source", "retry")
        const val MAX_AREA_BODY = 2 shl 20
        /** T140 (ADR 0041 §3): `pack=1` on a polygon. */
        const val POLYGON_NO_PACK = "a pack is a box; download a box for the trail graph"
        /** T157: `pack=1` with a `demMaxZoom`. */
        const val CAPPED_NO_PACK = "a pack holds one zoom range; leave out demMaxZoom for a pack, or ask for tiles (pack=0)"
        private val SPRITE_FILES = listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png")

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

/** Builds an outdoors-profile vector pack from OSM data (the tilebuild step of `profile=outdoors`): an Overpass XML answer or an extract's `.osm.pbf`. */
interface OutdoorsTileBuilder {
    /** Null when [build] can run; otherwise why not, with the fix. */
    fun unavailable(): String?

    /** How it runs, for `GET /build`. */
    fun describe(): String

    /**
     * Writes [out] (a `.pmtiles` path) from [osmXml] (`.osm` XML or `.osm.pbf`) clipped to [box] at [zooms], crediting [sourceIds] on
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
