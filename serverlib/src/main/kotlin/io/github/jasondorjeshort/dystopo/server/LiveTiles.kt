package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.dem.TileWindow
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitRegions
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ShadeTileStats
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * `devtool serve --live`: the viewer stops being pack-only. Every tile is answered from the first
 * thing that has it:
 * - `/dem/{z}/{x}/{y}.png`: a region pack in `out/` (or `--dem`) that holds that exact tile; else the
 *   `best` source (3DEP where covered, Copernicus elsewhere, EGM2008) rendered on request through the
 *   `tiles` command's per-tile path (a [TileWindow] with a feather halo, cropped back), cached on disk
 *   under `<cache>/live/<CACHE_VERSION>/` so the second view anywhere is warm.
 * - `/contours/{z}/{x}/{y}.pbf`: [ContourTiles] over that same pack-then-live DEM (the DEM tiles a
 *   contour tile needs are produced in parallel first).
 * - `/osm/{z}/{x}/{y}.pbf`: a vector pack that holds the tile; else OpenFreeMap's public planet tiles
 *   (OpenMapTiles schema, `sources.json` id `openfreemap`), fetched once per tile and cached on disk
 *   under `<cache>/openfreemap/<build>/`. Outdoors-only layers simply have no data there.
 *
 * `/` serves the browser demo (`tools/demo/index.html`) in live mode, `/phone` the phone style
 * ([MapLibreStyle] over these routes), `/live/sources` which source each tile in a box came from (the
 * demo's status box), `/stats` per-route, per-source counts and timings.
 */
class LiveTiles(
    demPacks: List<File>,
    osmPacks: List<File>,
    private val cacheRoot: File = Caches.root,
    /** Where `/build` writes packs and `/out/` serves files from. */
    val outDir: File = File("out"),
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 16),
    /** Renders one live DEM tile; the default runs the `best` source. Tests swap in a fake. */
    private val generateDem: (TileId) -> LiveDem = ::generateBest,
    /** GETs one OpenFreeMap tile URL: bytes (maybe gzipped), or null when the server has no such tile. */
    private val fetchOfm: (String) -> ByteArray? = ::httpGet,
    /** The OpenFreeMap TileJSON text (names the current planet build); read lazily, cached on disk for a day. */
    private val ofmTileJson: () -> String = { httpGet(OFM_TILEJSON)?.let { gunzipIfNeeded(it).decodeToString() } ?: error("no TileJSON at $OFM_TILEJSON") },
    /** Makes the `/build` routes; the standalone server passes its configured limits and Overpass endpoint (T52). */
    private val makeBuilds: (LiveTiles) -> BuildRoutes = { BuildRoutes(it, it.outDir, extracts = Extracts(dir = File(cacheRoot, Extracts.DIR_NAME))) }, // T64: extracts in <cache>/extracts
) {
    /** One rendered live DEM tile: Terrarium PNG bytes plus the registry ids that contributed. */
    class LiveDem(val png: ByteArray, val provenance: List<String>)

    /**
     * A tile as served: bytes, where they came from (`pack`, `live-3dep`, `live-swissalti`, `live-copernicus`,
     * `live-nodata`, `openfreemap`, `pack+openfreemap`), whether a cache answered, and the registry ids
     * of the data in it ([provenance], what the view's attribution credits).
     */
    class Tile(val bytes: ByteArray, val source: String, val cached: Boolean, val provenance: List<String> = emptyList())

    /** A PMTiles pack; tile lookups skip the archive when the tile is outside its header zooms and bounds. */
    class Pack(val file: File, private val fallbackIds: List<String> = emptyList()) {
        val reader: PmtilesReader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
        private val h = reader.header

        /** Registry ids the pack's metadata names (its provenance), else [fallbackIds]. */
        val registryIds: List<String> by lazy {
            val named = runCatching { PackAttribution.registryIds(synchronized(this) { reader.metadata() }) }.getOrDefault(emptyList())
                .filter { it in SourceRegistry.builtIn }
            named.ifEmpty { fallbackIds }
        }

        /**
         * Edge tiles are complete anyway: a pack built from OpenFreeMap tiles (`/build`, provenance
         * `openfreemap`) stores whole upstream tiles, unlike a `tilebuild` extract clipped to its box.
         */
        val wholeTiles: Boolean get() = "openfreemap" in registryIds

        /** This pack's copy of tile z/x/y is complete: inside its bounds, or [wholeTiles]. */
        fun complete(z: Int, x: Int, y: Int): Boolean = wholeTiles || contains(z, x, y)

        private fun edges(z: Int, x: Int, y: Int) = doubleArrayOf(
            WebMercator.xToLon(x.toDouble(), z), WebMercator.yToLat(y + 1.0, z),
            WebMercator.xToLon(x + 1.0, z), WebMercator.yToLat(y.toDouble(), z),
        )

        /** The tile overlaps the pack's bounds at a zoom the pack has. */
        fun covers(z: Int, x: Int, y: Int): Boolean {
            if (z < h.minZoom || z > h.maxZoom) return false
            val (west, south, east, north) = edges(z, x, y).toList()
            return east > h.minLon && west < h.maxLon && north > h.minLat && south < h.maxLat
        }

        /**
         * The tile lies wholly inside the pack's bounds, so the pack's copy of it is complete. The
         * header stores bounds in 1e-7 degrees, so edges within [EDGE_EPS_DEG] count as inside.
         */
        fun contains(z: Int, x: Int, y: Int): Boolean {
            if (z < h.minZoom || z > h.maxZoom) return false
            val (west, south, east, north) = edges(z, x, y).toList()
            val e = EDGE_EPS_DEG
            return west >= h.minLon - e && east <= h.maxLon + e && south >= h.minLat - e && north <= h.maxLat + e
        }

        fun tile(z: Int, x: Int, y: Int): ByteArray? = if (!covers(z, x, y)) null else synchronized(this) { reader.tile(z, x, y) }

        fun has(z: Int, x: Int, y: Int): Boolean = covers(z, x, y) && synchronized(this) { reader.findEntry(z, x, y) != null }

        fun describe(): Map<String, Any?> = mapOf(
            "file" to file.path, "minZoom" to h.minZoom, "maxZoom" to h.maxZoom,
            "bounds" to listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
        )
    }

    // Copy-on-write: a pack built by `/build` (BuildRoutes) joins while tiles are being served.
    private val dems = CopyOnWriteArrayList(demPacks.map { Pack(it, BestSource().registryIds) })
    private val osms = CopyOnWriteArrayList(osmPacks.map { Pack(it, OSM_PACK_REGISTRY_IDS) })

    /** Serves [file] (a DEM or vector PMTiles) from now on, replacing a pack already open from the same path. */
    fun addPack(file: File) {
        val probe = Pack(file)
        val mvt = probe.reader.header.tileType == TileType.MVT
        val pack = Pack(file, if (mvt) OSM_PACK_REGISTRY_IDS else BestSource().registryIds)
        val list = if (mvt) osms else dems
        val path = file.canonicalPath
        val at = list.indexOfFirst { it.file.canonicalPath == path }
        if (at >= 0) list[at] = pack else list += pack
        // Tiles remembered from the live path would keep reporting their live source over the new pack.
        synchronized(memory) { memory.clear() }
        sourceOf.clear()
        provenanceOf.clear()
    }
    private val demCacheDir = File(cacheRoot, "live/$CACHE_VERSION")
    /** Stale live DEM cache directories deleted (or being deleted) by [pruneStaleDemCaches]: name to files and bytes removed. */
    private val pruned = LinkedHashMap<String, Map<String, Any?>>()

    /**
     * Deletes the live DEM caches of earlier [CACHE_VERSION]s (`live/best-v1`, ...) on a background
     * thread: nothing reads them any more, and they hold gigabytes after a while. Returns the
     * directories found; progress and totals appear under `staleCachePrune` in `/stats`.
     */
    fun pruneStaleDemCaches(): List<File> {
        val stale = staleDemCaches(File(cacheRoot, "live"))
        if (stale.isEmpty()) return stale
        Thread({
            for (dir in stale) {
                synchronized(pruned) { pruned[dir.name] = mapOf("status" to "deleting") }
                var files = 0L
                var bytes = 0L
                dir.walkBottomUp().forEach { f ->
                    val isFile = f.isFile
                    val n = if (isFile) f.length() else 0L
                    if (f.delete() && isFile) { files++; bytes += n }
                }
                synchronized(pruned) { pruned[dir.name] = mapOf("status" to if (dir.exists()) "partial" else "deleted", "files" to files, "bytes" to bytes) }
            }
        }, "live-cache-prune").apply { isDaemon = true }.start()
        return stale
    }
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r -> Thread(r, "live-dem").apply { isDaemon = true } }
    private val ofmPool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "live-ofm").apply { isDaemon = true } }
    private val demInflight = ConcurrentHashMap<TileId, CompletableFuture<Tile>>()
    private val osmInflight = ConcurrentHashMap<TileId, CompletableFuture<Tile>>()
    /** Politeness: at most this many OpenFreeMap requests at once. */
    private val ofmPermits = Semaphore(4)
    private val options = ContourTileOptions()
    private val decoder = demDecoder

    /** Recently used DEM tiles (pack or live), so a contour tile and its neighbours don't re-read the disk. */
    private val memory = object : LinkedHashMap<TileId, Tile>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, Tile>?) = size > 384
    }

    /** Where each tile came from, per route (`dem/z/x/y` etc.), for `/live/sources`. */
    private val sourceOf = ConcurrentHashMap<String, String>()

    /** The registry ids in each tile served, per route, for the view's attribution. */
    private val provenanceOf = ConcurrentHashMap<String, List<String>>()

    // ---- stats ----

    private class Timing {
        var count = 0L
        var cached = 0L
        var min = Double.MAX_VALUE
        var max = 0.0
        var sum = 0.0

        fun add(ms: Double, hit: Boolean) {
            count++
            if (hit) cached++
            min = min(min, ms)
            max = max(max, ms)
            sum += ms
        }

        fun json(): Map<String, Any?> = mapOf(
            "tiles" to count, "cached" to cached,
            "minMs" to round1(min), "avgMs" to round1(sum / count), "maxMs" to round1(max),
        )
    }

    private val timings = LinkedHashMap<String, LinkedHashMap<String, Timing>>()
    private val recent = ArrayDeque<Map<String, Any?>>()
    private val liveGenerated = AtomicLong()
    private val liveGenerateMs = AtomicLong()
    private val ofmFetches = AtomicLong()
    private val ofmMissing = AtomicLong()
    private val errors = AtomicLong()

    private fun record(kind: String, t: TileId, tile: Tile?, startedNs: Long) {
        val ms = (System.nanoTime() - startedNs) / 1e6
        val source = tile?.source ?: "none"
        sourceOf["$kind/$t"] = source
        if (tile != null && kind != "contours") provenanceOf["$kind/$t"] = tile.provenance
        synchronized(timings) {
            timings.getOrPut(kind) { LinkedHashMap() }.getOrPut(source) { Timing() }.add(ms, tile?.cached == true)
            recent.addLast(mapOf("kind" to kind, "tile" to t.toString(), "source" to source, "ms" to round1(ms), "cached" to (tile?.cached == true)))
            while (recent.size > 60) recent.removeFirst()
        }
    }

    // ---- DEM ----

    private fun valid(z: Int, x: Int, y: Int) = z in 0..22 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z)

    /** The DEM tile z/x/y from the first pack that has it, else live (null below [LIVE_MIN_ZOOM] or past [LIVE_MAX_ZOOM]). */
    fun demAsync(z: Int, x: Int, y: Int): CompletableFuture<Tile?> {
        if (!valid(z, x, y)) return CompletableFuture.completedFuture(null)
        val id = TileId(z, x, y)
        synchronized(memory) { memory[id] }?.let { return CompletableFuture.completedFuture(Tile(it.bytes, it.source, true, it.provenance)) }
        for (p in dems) p.tile(z, x, y)?.let { bytes ->
            val t = Tile(bytes, "pack", false, p.registryIds)
            remember(id, t)
            return CompletableFuture.completedFuture(t)
        }
        if (z < LIVE_MIN_ZOOM || z > LIVE_MAX_ZOOM) return CompletableFuture.completedFuture(null)
        readDemCache(id)?.let { t ->
            remember(id, t)
            return CompletableFuture.completedFuture(t)
        }
        return renderOnce(id).thenApply<Tile?> { it }
    }

    /** One render per tile however many requests (viewer or pack build) want it at once. */
    private fun renderOnce(id: TileId): CompletableFuture<Tile> = demInflight.computeIfAbsent(id) { key ->
        CompletableFuture.supplyAsync({ renderLive(key) }, pool).also { fut -> fut.whenComplete { _, _ -> demInflight.remove(key) } }
    }

    /** A live DEM tile for a pack build: Terrarium PNG, the registry ids that made it (empty: no data there), whether the disk cache had it. */
    class BuildDem(val png: ByteArray, val provenance: List<String>, val cached: Boolean)

    /**
     * DEM tile z/x/y from the live path only (never a pack: a build makes packs from the live sources),
     * through the same disk cache and in-flight dedup as `/dem`. Used by [BuildRoutes].
     */
    fun liveDemAsync(z: Int, x: Int, y: Int): CompletableFuture<BuildDem> {
        require(valid(z, x, y) && z in LIVE_MIN_ZOOM..LIVE_MAX_ZOOM) { "no live DEM tile $z/$x/$y" }
        val id = TileId(z, x, y)
        readDemCache(id)?.let { return CompletableFuture.completedFuture(BuildDem(it.bytes, readProvenance(id), true)) }
        // The sidecar is published before the PNG, so it is there once the render completes.
        return renderOnce(id).thenApply { BuildDem(it.bytes, readProvenance(id), false) }
    }

    fun dem(z: Int, x: Int, y: Int): Tile? = join(demAsync(z, x, y))

    private fun remember(id: TileId, t: Tile) {
        synchronized(memory) { memory[id] = t }
        sourceOf.putIfAbsent("dem/$id", t.source)
        provenanceOf.putIfAbsent("dem/$id", t.provenance)
    }

    private fun demFile(id: TileId) = File(demCacheDir, "${id.z}/${id.x}/${id.y}.png")

    private fun readDemCache(id: TileId): Tile? {
        val png = demFile(id)
        if (!png.isFile) return null
        val side = File(png.path.removeSuffix(".png") + ".src")
        val provenance = readProvenance(id)
        return Tile(png.readBytes(), if (side.isFile) labelFor(provenance) else "live", true, provenance.filter { it in SourceRegistry.builtIn })
    }

    private fun readProvenance(id: TileId): List<String> {
        val side = File(demFile(id).path.removeSuffix(".png") + ".src")
        return if (side.isFile) side.readText().split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    private fun renderLive(id: TileId): Tile {
        val started = System.nanoTime()
        val dem = generateDem(id)
        liveGenerated.incrementAndGet()
        liveGenerateMs.addAndGet((System.nanoTime() - started) / 1_000_000)
        val png = demFile(id)
        png.parentFile.mkdirs()
        // Provenance first, then the PNG: a reader that sees the PNG always finds its sidecar.
        val side = File(png.path.removeSuffix(".png") + ".src")
        Http.partFile(side).also { it.writeText(dem.provenance.joinToString(",")) }.let { Http.publish(it, side) }
        Http.partFile(png).also { it.writeBytes(dem.png) }.let { Http.publish(it, png) }
        val t = Tile(dem.png, labelFor(dem.provenance), false, dem.provenance)
        remember(id, t)
        return t
    }

    // ---- contours ----

    /**
     * Contour tile z/x/y ([ContourTiles.plan]) over the pack-then-live DEM; the DEM tiles it samples are
     * produced in parallel first. Intervals and `ele` are in [units] (the viewer's setting); null takes
     * the default for the tile's centre ([UnitRegions]: feet in the US, metres elsewhere).
     */
    fun contours(z: Int, x: Int, y: Int, units: ContourUnits? = null, query: Map<String, String> = emptyMap()): Tile? {
        if (!valid(z, x, y)) return null
        val u = units ?: UnitRegions.contourUnitsAt(WebMercator.yToLat(y + 0.5, z), WebMercator.xToLon(x + 0.5, z))
        val options = ServeMapOptions.contourOptions(if (u == this.options.units) this.options else this.options.copy(units = u), query) // T50: ?interval=
        val plan = ContourTiles.plan(z, x, y, options)
        val window = intArrayOf(plan.windowX, plan.windowY, plan.windowSize, plan.windowSize)
        val needed = demTilesFor(plan.sampleZoom, window, LIVE_MAX_ZOOM)
        val fetched = needed.map { demAsync(it.z, it.x, it.y) }.map { join(it) }
        val tile = ContourTiles.build(ContourTiles.sample(pyramid, plan), plan, options)
        val sources = fetched.filterNotNull().groupingBy { it.source }.eachCount()
        val source = sources.maxByOrNull { it.value }?.key ?: "none"
        return Tile(tile.bytes, source, fetched.all { it?.cached != false })
    }

    private val shadeStats = ShadeTileStats()

    /**
     * T50: the phone's hillshade/slope raster tile z/x/y ([ServeMapOptions.shadePng]) over the pack-then-live DEM,
     * for the map options in [query]; its DEM tiles are produced in parallel first, as for [contours].
     */
    fun shade(z: Int, x: Int, y: Int, query: Map<String, String>): Tile? {
        if (!valid(z, x, y)) return null
        val options = ServeMapOptions.shadeOptions(query)
        val plan = ShadeTiles.plan(z, x, y, LIVE_MAX_ZOOM)
        val needed = if (plan.sampleZoom < LIVE_MIN_ZOOM) emptyList() else demTilesFor(plan.sampleZoom, intArrayOf(plan.windowX, plan.windowY, plan.windowSize, plan.windowSize), LIVE_MAX_ZOOM)
        val fetched = needed.map { demAsync(it.z, it.x, it.y) }.map { join(it) }
        val png = ServeMapOptions.shadePng(pyramid, z, x, y, options, shadeStats)
        val source = fetched.filterNotNull().groupingBy { it.source }.eachCount().maxByOrNull { it.value }?.key ?: "none"
        return Tile(png, source, fetched.all { it?.cached != false })
    }

    /** Decoded DEM tiles shared by all contour tiles (thread-safe; fetches run outside its lock). */
    private val pyramid = DemPyramid(LIVE_MIN_ZOOM, LIVE_MAX_ZOOM, decoder, cacheTiles = 256) { tz, tx, ty -> dem(tz, tx, ty)?.bytes }

    // ---- OSM ----

    @Volatile private var ofmBuild: String? = null
    private val ofm: Pair<String, String> by lazy { resolveOfm().also { ofmBuild = it.second } }

    /** The OpenFreeMap tile URL template and its build id, from the TileJSON (cached a day on disk; a stale copy is used offline). */
    private fun resolveOfm(): Pair<String, String> {
        val file = File(cacheRoot, "openfreemap/planet.json")
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L
        val text = if (fresh) file.readText() else try {
            ofmTileJson().also { t ->
                file.parentFile.mkdirs()
                file.delete() // a stale copy; publish keeps an existing file
                Http.partFile(file).also { it.writeText(t) }.let { Http.publish(it, file) }
            }
        } catch (e: Exception) {
            if (file.isFile) file.readText() else throw e
        }
        return parseOfmTileJson(text)
    }

    /**
     * OSM tile z/x/y: from a pack whose bounds hold the whole tile; else OpenFreeMap. A pack that covers
     * only part of the tile (its edge) would draw base layers that stop at the pack boundary, so there
     * the OpenFreeMap tile is used and the pack's outdoors-only layers (those OpenFreeMap lacks, such as
     * `outdoor_poi` and `natural_line`) are appended to it ([mergeMvtLayers]); the source is then
     * `pack+openfreemap`.
     */
    fun osm(z: Int, x: Int, y: Int): Tile? {
        if (!valid(z, x, y)) return null
        for (p in osms) if (p.complete(z, x, y)) p.tile(z, x, y)?.let { return Tile(it, "pack", false, p.registryIds) }
        val partial = osms.mapNotNull { p -> p.tile(z, x, y)?.let { it to p.registryIds } }
        if (z > OSM_MAX_ZOOM) return partial.firstOrNull()?.let { (bytes, ids) -> Tile(bytes, "pack", false, ids) }
        val base = openFreeMap(z, x, y)
        if (partial.isEmpty()) return base
        val merged = partial.fold(base.bytes) { acc, (bytes, _) -> mergeMvtLayers(acc, bytes) }
        val ids = SourceRegistry.builtIn.ordered(base.provenance + partial.flatMap { it.second })
        return Tile(merged, "pack+openfreemap", base.cached, ids)
    }

    /** The OpenFreeMap build id and its TileJSON (`vector_layers` etc.), as cached for the live route. */
    fun openFreeMapTileJson(): Pair<String, Map<String, Any?>> {
        val build = ofm.second
        return build to MiniJson.parseObject(File(cacheRoot, "openfreemap/planet.json").readText())
    }

    /**
     * OpenFreeMap tile z/x/y (no pack lookup): from the disk cache, else fetched once (concurrent
     * requests share one fetch) with at most 4 requests in flight. Empty bytes: no tile there.
     * Shared by `/osm` and pack builds ([BuildRoutes]).
     */
    fun openFreeMap(z: Int, x: Int, y: Int): Tile {
        require(valid(z, x, y) && z <= OSM_MAX_ZOOM) { "no OpenFreeMap tile $z/$x/$y" }
        val (template, build) = ofm
        val id = TileId(z, x, y)
        val file = File(cacheRoot, "openfreemap/$build/$z/$x/$y.pbf")
        if (file.isFile) return Tile(file.readBytes(), "openfreemap", true, OSM_REGISTRY_IDS)
        val f = osmInflight.computeIfAbsent(id) { key ->
            CompletableFuture.supplyAsync({
                val url = template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
                ofmPermits.acquire()
                val raw = try { fetchOfm(url) } finally { ofmPermits.release() }
                ofmFetches.incrementAndGet()
                if (raw == null) ofmMissing.incrementAndGet()
                // No tile there (open ocean at high zoom): cache an empty tile, which MapLibre draws as nothing.
                val bytes = raw?.let { gunzipIfNeeded(it) } ?: ByteArray(0)
                file.parentFile.mkdirs()
                Http.partFile(file).also { it.writeBytes(bytes) }.let { Http.publish(it, file) }
                Tile(bytes, "openfreemap", false, OSM_REGISTRY_IDS)
            }, ofmPool).also { fut -> fut.whenComplete { _, _ -> osmInflight.remove(key) } }
        }
        return join(f)
    }

    // ---- what the status box asks ----

    /**
     * Counts of tile sources for the DEM tiles at [demZoom] and OSM tiles at [osmZoom] covering [box],
     * and the attribution for exactly the data those tiles hold (`attribution`, HTML; `registryIds`):
     * a view of the Alps credits Copernicus and EGM2008, not 3DEP.
     */
    fun sourcesIn(box: BoundingBox?, demZoom: Int, osmZoom: Int): Map<String, Any?> {
        val ids = LinkedHashSet<String>()
        fun count(kind: String, z: Int, lookup: (TileId) -> Pair<String, List<String>>): Map<String, Int> {
            if (box == null) return emptyMap()
            val tiles = WebMercator.tilesCovering(box, z).take(MAX_STATUS_TILES)
            return tiles.map { t ->
                val key = "$kind/$t"
                val known = sourceOf[key]
                val (source, provenance) = if (known != null) known to (provenanceOf[key] ?: emptyList()) else lookup(t)
                ids += provenance
                source
            }.groupingBy { it }.eachCount().toSortedMap()
        }
        val dz = demZoom.coerceIn(0, LIVE_MAX_ZOOM)
        val oz = osmZoom.coerceIn(0, OSM_MAX_ZOOM)
        val dem = count("dem", dz) { t ->
            val pack = dems.firstOrNull { it.has(t.z, t.x, t.y) }
            when {
                pack != null -> "pack" to pack.registryIds
                t.z < LIVE_MIN_ZOOM -> "none" to emptyList()
                else -> readDemCache(t)?.let { it.source to it.provenance } ?: ("pending" to emptyList())
            }
        }
        val osm = count("osm", oz) { t ->
            val whole = osms.firstOrNull { it.complete(t.z, t.x, t.y) && it.has(t.z, t.x, t.y) }
            val part = osms.filter { it.has(t.z, t.x, t.y) }
            val ofmCached = ofmBuild?.let { File(cacheRoot, "openfreemap/$it/${t.z}/${t.x}/${t.y}.pbf").isFile } == true
            when {
                whole != null -> "pack" to whole.registryIds
                ofmCached && part.isNotEmpty() -> "pack+openfreemap" to OSM_REGISTRY_IDS + part.flatMap { it.registryIds }
                ofmCached -> "openfreemap" to OSM_REGISTRY_IDS
                else -> "pending" to emptyList()
            }
        }
        val credited = SourceRegistry.builtIn.ordered(ids.filter { it in SourceRegistry.builtIn })
        return linkedMapOf(
            "demZoom" to dz,
            "osmZoom" to oz,
            "dem" to dem,
            "osm" to osm,
            "registryIds" to credited,
            "attribution" to SourceRegistry.builtIn.attributionHtml(credited),
        )
    }

    // ---- HTTP ----

    private val tileRe = Regex("^/(dem|osm|contours|shade)/(\\d+)/(\\d+)/(\\d+)\\.(png|pbf)$")
    private val glyphRe = Regex("^/glyphs/([^/]+)/(\\d+-\\d+)\\.pbf$")

    /** The phone style ([MapLibreStyle]) over these routes at [base], per contour unit; a few bases are cached. */
    private val styles = ConcurrentHashMap<Pair<String, ContourUnits>, String>()

    /**
     * The phone style for contour unit [u] and the map options in the query [q] (T50, [ServeMapOptions]): options
     * needing shade tiles point them at `/shade/` with the same options, a fixed interval rides on the contour URL.
     * Only the default options are cached.
     */
    private fun style(base: String, u: ContourUnits, textScale: Double, q: Map<String, String> = emptyMap()): String {
        val mapOptions = ServeMapOptions.parse(q, u)
        val default = mapOptions == MapOptions()
        if (default) styles[base to u]?.let { return it }
        val json = MapLibreStyle(
            MapLibreSources(
                demTiles = "$base/dem/{z}/{x}/{y}.png", demMinZoom = LIVE_MIN_ZOOM, demMaxZoom = LIVE_MAX_ZOOM,
                contourTiles = "$base/contours/{z}/{x}/{y}.pbf?units=${u.symbol}${ServeMapOptions.contourQuery(mapOptions)}",
                contourMinZoom = CONTOUR_MIN_ZOOM, contourMaxZoom = LIVE_MAX_ZOOM,
                osmTiles = "$base/osm/{z}/{x}/{y}.pbf", osmMinZoom = 0, osmMaxZoom = OSM_MAX_ZOOM,
                glyphs = "$base/glyphs/{fontstack}/{range}.pbf",
                attribution = osmAttributionHtml + " " + demAttributionHtml,
                sprite = "$base/sprite",
                shadeTiles = ServeMapOptions.shadeTiles(base, mapOptions), shadeMinZoom = LIVE_MIN_ZOOM, shadeMaxZoom = LIVE_MAX_ZOOM + 2,
            ),
            units = u,
            textScale = textScale,
            options = mapOptions,
        ).json()
        // Bases come from request headers on the standalone server: keep the cache small whatever they say.
        if (default && styles.size < 16) styles[base to u] = json
        return json
    }

    /**
     * The live routes as one handler. [base] gives the absolute URL prefix the phone style's tile URLs
     * use for a request: fixed for `devtool serve` (`http://127.0.0.1:<port>`), from the configured public
     * URL or the request's `Host` / `X-Forwarded-*` headers on the standalone server ([requestBase]).
     */
    fun handler(glyphDir: File, demoPage: File = File("tools/demo/index.html"), textScale: Double = 1.0, base: (HttpExchange) -> String): HttpHandler = HttpHandler { ex ->
        try {
            handle(ex, { u, q -> style(base(ex), u, textScale, q) }, glyphDir, demoPage)
        } catch (e: IllegalArgumentException) {
            reply(ex, ((e.message ?: e.toString()) + "\n").toByteArray(), "text/plain", status = 400) // T50: a bad map option
        } catch (e: Throwable) {
            errors.incrementAndGet()
            val cause = (e as? CompletionException)?.cause ?: e
            reply(ex, (cause.toString() + "\n").toByteArray(), "text/plain", status = 500)
        }
    }

    /** Starts the live server on 127.0.0.1:[port] and prints one JSON line describing it. */
    fun serve(port: Int, glyphDir: File, demoPage: File = File("tools/demo/index.html"), textScale: Double = 1.0) {
        val base = "http://127.0.0.1:$port"
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)
        server.executor = Executors.newFixedThreadPool(32)
        server.createContext("/", handler(glyphDir, demoPage, textScale) { base })
        server.start()
        val stale = pruneStaleDemCaches() // only once the port is ours: a second server that fails to bind touches nothing
        println(
            JsonWriter.write(
                mapOf(
                    "url" to "$base/", "live" to true, "phone" to "$base/phone", "stats" to "$base/stats",
                    "demPacks" to dems.map { it.file.path }, "osmPacks" to osms.map { it.file.path },
                    "cache" to cacheRoot.path, "glyphs" to glyphDir.path,
                    "staleCachesDeleting" to stale.map { it.name },
                ),
            ),
        )
    }

    /** `/build` jobs: region packs made from these live sources into [outDir], served under `/out/`. */
    private val buildsLazy = lazy { makeBuilds(this) }
    val builds: BuildRoutes by buildsLazy

    /**
     * For a graceful shutdown: cancels queued and running builds (their partial files are deleted) and
     * waits up to [waitMs] for them to stop. Returns the number of jobs cancelled.
     */
    fun cancelBuilds(waitMs: Long): Int = if (buildsLazy.isInitialized()) builds.cancelAll(waitMs) else 0

    /** `GET /catalog` (T52): the packs in [outDir] with sizes and content hashes ([PackCatalog]). */
    val catalog: PackCatalog by lazy { PackCatalog(outDir) }

    private fun handle(ex: HttpExchange, style: (ContourUnits, Map<String, String>) -> String, glyphDir: File, demoPage: File) {
        val path = ex.requestURI.path
        if (SyncRoutes.handle(ex)) return // T44: /sync/* (the always-on sync peer)
        if (path == "/build" || path.startsWith("/build/")) return builds.handle(ex, path)
        catalog.route(path, ex.requestMethod)?.let { (status, body) -> return reply(ex, JsonWriter.write(body).toByteArray(), "application/json", status) }
        if (path.startsWith("/out/")) return ServeStatic.serve(ex, outDir, path.removePrefix("/out/"))
        SpriteSheets.route(path, outDir)?.let { (bytes, type) -> return reply(ex, bytes, type) }
        tileRe.matchEntire(path)?.let { m ->
            val (kind, zs, xs, ys) = m.destructured
            val z = zs.toInt(); val x = xs.toInt(); val y = ys.toInt()
            val started = System.nanoTime()
            val tile = when (kind) {
                "dem" -> dem(z, x, y)
                "osm" -> osm(z, x, y)
                "shade" -> shade(z, x, y, query(ex))
                else -> query(ex).let { q -> contours(z, x, y, parseUnits(q["units"]), q) }
            }
            record(kind, TileId(z, x, y), tile, started)
            val type = if (kind == "dem" || kind == "shade") "image/png" else "application/x-protobuf"
            ex.responseHeaders.add("X-Dystopo-Source", tile?.source ?: "none")
            reply(ex, tile?.bytes, type)
            return
        }
        glyphRe.matchEntire(path)?.let { m ->
            val file = URLDecoder.decode(m.groupValues[1], StandardCharsets.UTF_8).split(",")
                .map { File(glyphDir, "${it.trim()}/${m.groupValues[2]}.pbf") }.firstOrNull { it.isFile }
            reply(ex, file?.readBytes() ?: ByteArray(0), "application/x-protobuf")
            return
        }
        when (path) {
            "/", "/tools/demo/", "/tools/demo/index.html" -> {
                val html = if (demoPage.isFile) injectLive(demoPage.readText()) else null
                reply(ex, html?.toByteArray(), "text/html; charset=utf-8")
            }
            "/phone" -> reply(ex, PHONE_PAGE.toByteArray(), "text/html; charset=utf-8")
            "/style.json" -> query(ex).let { q -> reply(ex, style(unitsFor(q), q).toByteArray(), "application/json") }
            "/live/units" -> {
                val u = unitsFor(query(ex))
                reply(ex, JsonWriter.write(mapOf("units" to u.symbol, "name" to u.name.lowercase())).toByteArray(), "application/json")
            }
            "/live/info" -> reply(ex, JsonWriter.write(info()).toByteArray(), "application/json")
            "/live/sources" -> {
                val q = query(ex)
                val box = q["bbox"]?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.let { clampBox(it) }
                val dz = q["dz"]?.toIntOrNull() ?: 12
                val oz = q["oz"]?.toIntOrNull() ?: dz
                reply(ex, JsonWriter.write(sourcesIn(box, dz, oz)).toByteArray(), "application/json")
            }
            "/stats" -> reply(ex, JsonWriter.write(stats()).toByteArray(), "application/json")
            else -> reply(ex, null, "text/plain")
        }
    }

    /** `units=ft|m` when given, else the default at `lat`/`lon` ([UnitRegions]), else feet (the default view is Rainier). */
    private fun unitsFor(q: Map<String, String>): ContourUnits {
        parseUnits(q["units"])?.let { return it }
        val lat = q["lat"]?.toDoubleOrNull()
        val lon = q["lon"]?.toDoubleOrNull()
        return if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) UnitRegions.contourUnitsAt(lat, lon) else ContourUnits.FEET
    }

    private val demAttributionHtml: String get() = SourceRegistry.builtIn.attributionHtml(BestSource().registryIds)
    private val osmAttributionHtml: String get() = SourceRegistry.builtIn.attributionHtml(OSM_REGISTRY_IDS)

    fun info(): Map<String, Any?> = linkedMapOf(
        "live" to true,
        "demMinZoom" to LIVE_MIN_ZOOM, "demMaxZoom" to LIVE_MAX_ZOOM,
        "contourMinZoom" to CONTOUR_MIN_ZOOM, "contourMaxZoom" to LIVE_MAX_ZOOM,
        "osmMaxZoom" to OSM_MAX_ZOOM,
        "demAttribution" to demAttributionHtml,
        "osmAttribution" to osmAttributionHtml,
        "demPacks" to dems.map { it.describe() },
        "osmPacks" to osms.map { it.describe() },
    )

    /** The demo page with the live settings injected ahead of its module script, so it needs no extra request to know it is live. */
    fun injectLive(html: String): String {
        val tag = "<script type=\"module\">"
        val at = html.indexOf(tag)
        val script = "<script>window.DYSTOPO_LIVE = ${JsonWriter.write(info())};</script>\n"
        return if (at < 0) html else html.substring(0, at) + script + html.substring(at)
    }

    fun stats(): Map<String, Any?> = synchronized(timings) {
        linkedMapOf(
            "live" to true,
            "tiles" to timings.mapValues { (_, bySource) -> bySource.mapValues { it.value.json() } },
            "liveDemRendered" to liveGenerated.get(),
            "liveDemRenderMsAvg" to liveGenerated.get().takeIf { it > 0 }?.let { round1(liveGenerateMs.get().toDouble() / it) },
            "openfreemap" to mapOf(
                "fetches" to ofmFetches.get(), "missing" to ofmMissing.get(),
                "build" to ofmBuild,
            ),
            "errors" to errors.get(),
            "shadeGeneration" to shadeStats.json(),
            "staleCachePrune" to synchronized(pruned) { pruned.toMap() },
            "inflight" to mapOf("dem" to demInflight.size, "osm" to osmInflight.size),
            "recent" to recent.toList(),
            "demPacks" to dems.map { it.describe() },
            "osmPacks" to osms.map { it.describe() },
            "cache" to cacheRoot.path,
        )
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    private fun reply(ex: HttpExchange, body: ByteArray?, type: String, status: Int = 200) {
        val code = if (body == null) 404 else status
        val bytes = body ?: "not found\n".toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    companion object {
        /** Live DEM tiles are rendered from this zoom (a z6 tile already spans ~30 Copernicus cells). */
        const val LIVE_MIN_ZOOM = 6
        /** Deepest live DEM zoom; contour tiles deeper than this overzoom it. */
        const val LIVE_MAX_ZOOM = 15
        const val CONTOUR_MIN_ZOOM = 8
        /** OpenFreeMap (like our packs) stops at z14; MapLibre overzooms beyond. */
        const val OSM_MAX_ZOOM = 14
        /** Bump when the live DEM rendering changes, so old cached tiles are not served. v2: one overview level and blend band per zoom. */
        const val CACHE_VERSION = "best-v2"

        /** Live DEM cache directories under [liveDir] from other versions than [CACHE_VERSION] (`best-v<n>`). */
        fun staleDemCaches(liveDir: File): List<File> =
            liveDir.listFiles { f -> f.isDirectory && f.name != CACHE_VERSION && STALE_CACHE_NAME.matches(f.name) }?.sortedBy { it.name } ?: emptyList()

        private val STALE_CACHE_NAME = Regex("best-v[0-9]+")
        const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
        /** Registry ids credited for OpenFreeMap tiles: the host, the schema and the data. */
        val OSM_REGISTRY_IDS = listOf("osm", "openmaptiles", "openfreemap")
        /** Credited for an OSM pack whose metadata names no provenance (our `tilebuild` packs: OSM data, OpenMapTiles-based schema). */
        val OSM_PACK_REGISTRY_IDS = listOf("osm", "openmaptiles")
        private const val MAX_STATUS_TILES = 400
        /** Tolerance for pack bounds against tile edges (PMTiles headers round to 1e-7 degrees). */
        const val EDGE_EPS_DEG = 1e-6

        /**
         * The latitude whose ground cell size every live DEM tile of a zoom uses to pick its GeoTIFF
         * overview level, halo and blend band. A per-tile choice (each tile's own latitude) can put two
         * neighbouring rows on different overview levels: a resampling seam. One value per zoom, as the
         * `tiles` export does for its area, removes that; 45° keeps the level within one step of each
         * tile's own between the equator and 60°.
         */
        const val LIVE_REFERENCE_LAT = 45.0

        /** Ground metres per pixel shared by every live DEM tile at [zoom] (see [LIVE_REFERENCE_LAT]). */
        fun referenceCellMeters(zoom: Int): Double = WebMercator.metersPerPixel(LIVE_REFERENCE_LAT, zoom, 256)

        /**
         * The URL prefix a client used to reach this server: `X-Forwarded-Proto` and `X-Forwarded-Host`
         * (set by a reverse proxy), else `Host`, else [fallback]. Header values that are not a plain
         * host[:port] are ignored, so a forged header cannot inject markup or paths into the style.
         */
        fun requestBase(ex: HttpExchange, fallback: String): String {
            val h = ex.requestHeaders
            val host = (h.getFirst("X-Forwarded-Host")?.substringBefore(',')?.trim() ?: h.getFirst("Host")?.trim())
                ?.takeIf { HOST_PORT.matches(it) } ?: return fallback
            val proto = h.getFirst("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "http"
            return "$proto://$host"
        }

        private val HOST_PORT = Regex("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?|\\[[0-9A-Fa-f:.]{2,45}](:[0-9]{1,5})?")

        /** `ft` / `m` (or `feet` / `meters` / `metres`) to a unit; null for anything else. */
        fun parseUnits(s: String?): ContourUnits? = when (s?.lowercase()) {
            "ft", "feet" -> ContourUnits.FEET
            "m", "meters", "metres" -> ContourUnits.METERS
            else -> null
        }

        /** One top-level MVT layer: its name and its encoded bytes (field 3: tag, length, message). */
        class MvtLayerBytes(val name: String, val bytes: ByteArray)

        /**
         * Splits an MVT tile into its layers without decoding features. A tile is a protobuf message
         * whose only field is the repeated `layers` (3, length-delimited); each layer's `name` is its
         * field 1. Unknown top-level fields are skipped.
         */
        fun mvtLayers(tile: ByteArray): List<MvtLayerBytes> {
            val out = ArrayList<MvtLayerBytes>()
            var i = 0
            fun varint(): Long {
                var shift = 0
                var r = 0L
                while (true) {
                    val b = tile[i++].toInt() and 0xff
                    r = r or ((b and 0x7f).toLong() shl shift)
                    if (b < 0x80) return r
                    shift += 7
                }
            }
            while (i < tile.size) {
                val start = i
                val key = varint()
                when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> i += 8
                    5 -> i += 4
                    2 -> {
                        val len = varint().toInt()
                        val bodyStart = i
                        i += len
                        if ((key ushr 3) == 3L) {
                            out += MvtLayerBytes(layerName(tile, bodyStart, i), tile.copyOfRange(start, i))
                        }
                    }
                    else -> error("bad wire type in MVT at $start")
                }
            }
            return out
        }

        private fun layerName(tile: ByteArray, from: Int, to: Int): String {
            var i = from
            fun varint(): Long {
                var shift = 0
                var r = 0L
                while (true) {
                    val b = tile[i++].toInt() and 0xff
                    r = r or ((b and 0x7f).toLong() shl shift)
                    if (b < 0x80) return r
                    shift += 7
                }
            }
            while (i < to) {
                val key = varint()
                when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> i += 8
                    5 -> i += 4
                    2 -> {
                        val len = varint().toInt()
                        if ((key ushr 3) == 1L) return tile.decodeToString(i, i + len)
                        i += len
                    }
                    else -> return ""
                }
            }
            return ""
        }

        /**
         * [base] with the layers of [extra] whose names [base] does not have appended. Protobuf
         * repeated fields concatenate, so the result is a valid tile; a layer present in both keeps
         * only [base]'s copy (never two layers with one name).
         */
        fun mergeMvtLayers(base: ByteArray, extra: ByteArray): ByteArray {
            // Something that does not parse as a tile (truncated, not MVT) adds nothing: [base] as it is.
            val have = runCatching { mvtLayers(base).map { it.name }.toSet() }.getOrNull() ?: return base
            val add = (runCatching { mvtLayers(extra) }.getOrNull() ?: return base).filter { it.name !in have }
            if (add.isEmpty()) return base
            val out = ByteArrayOutputStream(base.size + add.sumOf { it.bytes.size })
            out.write(base)
            for (l in add) out.write(l.bytes)
            return out.toByteArray()
        }

        /** The status-box label for a live tile's provenance. */
        fun labelFor(provenance: List<String>): String = when {
            provenance.any { it == "3dep" || it == "3dep-1m" } -> "live-3dep"
            "swissalti3d" in provenance -> "live-swissalti" // T50: swissALTI3D inside `best` (T56)
            "kartverket-dtm" in provenance -> "live-kartverket" // T59
            "bev-als-dtm" in provenance -> "live-bev" // T59
            "copernicus" in provenance -> "live-copernicus"
            else -> "live-nodata"
        }

        /**
         * The DEM tiles at min([demZoom], [maxZoom]) a pixel window at [demZoom] ([window] = x0, y0,
         * width, height in global pixels, as [ContourTiles.windowFor]) reads, clipped to the world.
         */
        fun demTilesFor(demZoom: Int, window: IntArray, maxZoom: Int, tileSize: Int = 256): List<TileId> {
            val az = min(demZoom, maxZoom)
            val shift = demZoom - az
            val n = 1 shl az
            fun tileOf(px: Int) = Math.floorDiv(px, tileSize) shr shift
            val x0 = tileOf(window[0]).coerceIn(0, n - 1)
            val x1 = tileOf(window[0] + window[2] - 1).coerceIn(0, n - 1)
            val y0 = tileOf(window[1]).coerceIn(0, n - 1)
            val y1 = tileOf(window[1] + window[3] - 1).coerceIn(0, n - 1)
            return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileId(az, x, y) } }
        }

        /** The tile URL template and build id (the path segment naming the planet build) from OpenFreeMap's TileJSON. */
        fun parseOfmTileJson(text: String): Pair<String, String> {
            val tiles = MiniJson.parseObject(text)["tiles"] as? List<*> ?: error("TileJSON without tiles")
            val template = tiles.firstOrNull() as? String ?: error("TileJSON without a tile URL")
            require("{z}" in template && "{x}" in template && "{y}" in template) { "unexpected tile URL $template" }
            val build = template.substringBefore("/{z}").substringAfterLast('/').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            require(build.isNotEmpty()) { "no build id in $template" }
            return template to build
        }

        fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).use { it.readAllBytes() }
            else bytes

        /** Clamps a page's [west, south, east, north] (which can run past ±180 on a wrapped map) to one world. */
        fun clampBox(b: List<Double>): BoundingBox? {
            val w = b[0].coerceIn(-180.0, 180.0)
            val e = b[2].coerceIn(-180.0, 180.0)
            val s = b[1].coerceIn(-WebMercator.MAX_LAT, WebMercator.MAX_LAT)
            val n = b[3].coerceIn(-WebMercator.MAX_LAT, WebMercator.MAX_LAT)
            return if (w < e && s < n) BoundingBox(w, s, e, n) else null
        }

        /** Every PMTiles pack to consult, in precedence order: the explicit ones, then the rest of [dir] by name. */
        fun findPacks(explicit: List<File>, dir: File = File("out")): Pair<List<File>, List<File>> {
            val all = (explicit.filter { it.isFile } + (dir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name } ?: emptyList()))
                .distinctBy { it.canonicalPath }
            val dem = ArrayList<File>()
            val osm = ArrayList<File>()
            for (f in all) {
                val type = runCatching { PmtilesReader.open(FileRangeSource(f), Gzip.decompressors).header.tileType }.getOrNull() ?: continue
                when (type) {
                    TileType.PNG, TileType.WEBP -> dem += f
                    TileType.MVT -> osm += f
                    else -> {}
                }
            }
            // The outdoors profile carries more than the OpenMapTiles one: let it win where both cover.
            return dem to osm.sortedBy { if (it.name.contains("outdoors")) 0 else 1 }
        }

        /**
         * Renders one DEM tile from the `best` source the way `tiles` does: haloed window, cropped,
         * Terrarium PNG. The overview level, halo and blend band come from [referenceCellMeters] for
         * the zoom, never from the tile's own latitude, so all tiles of a zoom read the same level.
         */
        fun generateBest(t: TileId): LiveDem {
            val cell = referenceCellMeters(t.z)
            val tw = TileWindow(t, TileWindows.haloCells(BestSource.GLOBAL_FEATHER_METERS, cell))
            val result = OverviewLevels.withTarget(cell) { BestSource().fetch(tw.window, cell) }
            val grid = tw.interior(result.grid)
            val img = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, grid.width, grid.height, TileWindows.encodeTerrarium(grid), 0, grid.width)
            val png = ByteArrayOutputStream(grid.width * grid.height).also { check(ImageIO.write(img, "png", it)) { "no PNG writer" } }.toByteArray()
            return LiveDem(png, result.provenance)
        }

        /** GET [url]: the body on 200, null on 204/404, an exception otherwise. */
        fun httpGet(url: String): ByteArray? {
            val response = Http.get(url, mapOf("User-Agent" to "dystopo-devtool/0.1 (+https://github.com/jasondorjeshort/dystopo)", "Accept-Encoding" to "gzip"))
            return when (response.statusCode()) {
                200 -> response.body()
                204, 404 -> null
                else -> throw HttpStatusException(response.statusCode(), url)
            }
        }

        private fun <T> join(f: CompletableFuture<T>): T = try {
            f.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

        private fun round1(x: Double) = if (x.isFinite()) Math.round(x * 10) / 10.0 else null

        private val PHONE_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dystopo phone style, live</title>
<script src="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js"></script>
<link href="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css" rel="stylesheet">
<style>html,body{margin:0;height:100%;background:#f6f1e4}#map{position:absolute;inset:0}</style></head>
<body><div id="map"></div><script>
// Contour and peak units: ?units=ft|m, else the default for where the view opens (feet in the US, metres elsewhere).
// Map options pass through to the style (T50): /phone?slope=on&night=on&contourInterval=100.
const h = location.hash.slice(1).split("/").map(Number), q = new URLSearchParams(location.search);
if (!q.get("units")) { q.set("lat", h.length >= 3 && h.every(isFinite) ? h[1] : 46.85); q.set("lon", h.length >= 3 && h.every(isFinite) ? h[2] : -121.76); }
window.map = new maplibregl.Map({container: "map", style: "/style.json?" + q, center: [-121.76, 46.85], zoom: 11, hash: true});
</script></body></html>
"""
    }
}
