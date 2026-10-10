package io.github.jasondorjeshort.dystopo.devtool

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.dem.TileWindow
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.math.floor
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
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 16),
    /** Renders one live DEM tile; the default runs the `best` source. Tests swap in a fake. */
    private val generateDem: (TileId) -> LiveDem = ::generateBest,
    /** GETs one OpenFreeMap tile URL: bytes (maybe gzipped), or null when the server has no such tile. */
    private val fetchOfm: (String) -> ByteArray? = ::httpGet,
    /** The OpenFreeMap TileJSON text (names the current planet build); read lazily, cached on disk for a day. */
    private val ofmTileJson: () -> String = { httpGet(OFM_TILEJSON)?.let { gunzipIfNeeded(it).decodeToString() } ?: error("no TileJSON at $OFM_TILEJSON") },
) {
    /** One rendered live DEM tile: Terrarium PNG bytes plus the registry ids that contributed. */
    class LiveDem(val png: ByteArray, val provenance: List<String>)

    /** A tile as served: bytes, where they came from (`pack`, `live-3dep`, `live-copernicus`, `live-nodata`, `openfreemap`), and whether a cache answered. */
    class Tile(val bytes: ByteArray, val source: String, val cached: Boolean)

    /** A PMTiles pack; tile lookups skip the archive when the tile is outside its header zooms and bounds. */
    class Pack(val file: File) {
        val reader: PmtilesReader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
        private val h = reader.header

        fun covers(z: Int, x: Int, y: Int): Boolean {
            if (z < h.minZoom || z > h.maxZoom) return false
            val west = WebMercator.xToLon(x.toDouble(), z)
            val east = WebMercator.xToLon(x + 1.0, z)
            val north = WebMercator.yToLat(y.toDouble(), z)
            val south = WebMercator.yToLat(y + 1.0, z)
            return east > h.minLon && west < h.maxLon && north > h.minLat && south < h.maxLat
        }

        fun tile(z: Int, x: Int, y: Int): ByteArray? = if (!covers(z, x, y)) null else synchronized(this) { reader.tile(z, x, y) }

        fun has(z: Int, x: Int, y: Int): Boolean = covers(z, x, y) && synchronized(this) { reader.findEntry(z, x, y) != null }

        fun describe(): Map<String, Any?> = mapOf(
            "file" to file.path, "minZoom" to h.minZoom, "maxZoom" to h.maxZoom,
            "bounds" to listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
        )
    }

    private val dems = demPacks.map { Pack(it) }
    private val osms = osmPacks.map { Pack(it) }
    private val demCacheDir = File(cacheRoot, "live/$CACHE_VERSION")
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r -> Thread(r, "live-dem").apply { isDaemon = true } }
    private val ofmPool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "live-ofm").apply { isDaemon = true } }
    private val demInflight = ConcurrentHashMap<TileId, CompletableFuture<Tile>>()
    private val osmInflight = ConcurrentHashMap<TileId, CompletableFuture<Tile>>()
    /** Politeness: at most this many OpenFreeMap requests at once. */
    private val ofmPermits = Semaphore(4)
    private val options = ContourTileOptions()
    private val decoder = ImageDecoder { bytes ->
        val img = ImageIO.read(bytes.inputStream()) ?: error("unreadable PNG")
        ArgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    /** Recently used DEM tiles (pack or live), so a contour tile and its neighbours don't re-read the disk. */
    private val memory = object : LinkedHashMap<TileId, Tile>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, Tile>?) = size > 384
    }

    /** Where each tile came from, per route (`dem/z/x/y` etc.), for `/live/sources`. */
    private val sourceOf = ConcurrentHashMap<String, String>()

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
        synchronized(memory) { memory[id] }?.let { return CompletableFuture.completedFuture(Tile(it.bytes, it.source, true)) }
        for (p in dems) p.tile(z, x, y)?.let { bytes ->
            val t = Tile(bytes, "pack", false)
            remember(id, t)
            return CompletableFuture.completedFuture(t)
        }
        if (z < LIVE_MIN_ZOOM || z > LIVE_MAX_ZOOM) return CompletableFuture.completedFuture(null)
        readDemCache(id)?.let { t ->
            remember(id, t)
            return CompletableFuture.completedFuture(t)
        }
        // One render per tile however many requests want it at once.
        val f = demInflight.computeIfAbsent(id) { key ->
            CompletableFuture.supplyAsync({ renderLive(key) }, pool).also { fut -> fut.whenComplete { _, _ -> demInflight.remove(key) } }
        }
        return f.thenApply<Tile?> { it }
    }

    fun dem(z: Int, x: Int, y: Int): Tile? = join(demAsync(z, x, y))

    private fun remember(id: TileId, t: Tile) {
        synchronized(memory) { memory[id] = t }
        sourceOf.putIfAbsent("dem/$id", t.source)
    }

    private fun demFile(id: TileId) = File(demCacheDir, "${id.z}/${id.x}/${id.y}.png")

    private fun readDemCache(id: TileId): Tile? {
        val png = demFile(id)
        if (!png.isFile) return null
        val side = File(png.path.removeSuffix(".png") + ".src")
        val provenance = if (side.isFile) side.readText().split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        return Tile(png.readBytes(), if (side.isFile) labelFor(provenance) else "live", true)
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
        val t = Tile(dem.png, labelFor(dem.provenance), false)
        remember(id, t)
        return t
    }

    // ---- contours ----

    /** Contour tile z/x/y ([ContourTiles.plan]) over the pack-then-live DEM; the DEM tiles it samples are produced in parallel first. */
    fun contours(z: Int, x: Int, y: Int): Tile? {
        if (!valid(z, x, y)) return null
        val plan = ContourTiles.plan(z, x, y, options)
        val window = intArrayOf(plan.windowX, plan.windowY, plan.windowSize, plan.windowSize)
        val needed = demTilesFor(plan.sampleZoom, window, LIVE_MAX_ZOOM)
        val fetched = needed.map { demAsync(it.z, it.x, it.y) }.map { join(it) }
        val tile = ContourTiles.build(ContourTiles.sample(pyramid, plan), plan, options)
        val sources = fetched.filterNotNull().groupingBy { it.source }.eachCount()
        val source = sources.maxByOrNull { it.value }?.key ?: "none"
        return Tile(tile.bytes, source, fetched.all { it?.cached != false })
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

    fun osm(z: Int, x: Int, y: Int): Tile? {
        if (!valid(z, x, y)) return null
        for (p in osms) p.tile(z, x, y)?.let { return Tile(it, "pack", false) }
        if (z > OSM_MAX_ZOOM) return null
        val (template, build) = ofm
        val id = TileId(z, x, y)
        val file = File(cacheRoot, "openfreemap/$build/$z/$x/$y.pbf")
        if (file.isFile) return Tile(file.readBytes(), "openfreemap", true)
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
                Tile(bytes, "openfreemap", false)
            }, ofmPool).also { fut -> fut.whenComplete { _, _ -> osmInflight.remove(key) } }
        }
        return join(f)
    }

    // ---- what the status box asks ----

    /** Counts of tile sources for the DEM tiles at [demZoom] and OSM tiles at [osmZoom] covering [box]. */
    fun sourcesIn(box: BoundingBox?, demZoom: Int, osmZoom: Int): Map<String, Any?> {
        fun count(kind: String, z: Int, lookup: (TileId) -> String): Map<String, Int> {
            if (box == null) return emptyMap()
            val tiles = WebMercator.tilesCovering(box, z).take(MAX_STATUS_TILES)
            return tiles.map { t -> sourceOf["$kind/$t"] ?: lookup(t) }.groupingBy { it }.eachCount().toSortedMap()
        }
        val dz = demZoom.coerceIn(0, LIVE_MAX_ZOOM)
        val oz = osmZoom.coerceIn(0, OSM_MAX_ZOOM)
        return linkedMapOf(
            "demZoom" to dz,
            "osmZoom" to oz,
            "dem" to count("dem", dz) { t ->
                when {
                    dems.any { it.has(t.z, t.x, t.y) } -> "pack"
                    t.z < LIVE_MIN_ZOOM -> "none"
                    else -> readDemCache(t)?.source ?: "pending"
                }
            },
            "osm" to count("osm", oz) { t ->
                when {
                    osms.any { it.has(t.z, t.x, t.y) } -> "pack"
                    ofmBuild?.let { File(cacheRoot, "openfreemap/$it/${t.z}/${t.x}/${t.y}.pbf").isFile } == true -> "openfreemap"
                    else -> "pending"
                }
            },
        )
    }

    // ---- HTTP ----

    private val tileRe = Regex("^/(dem|osm|contours)/(\\d+)/(\\d+)/(\\d+)\\.(png|pbf)$")
    private val glyphRe = Regex("^/glyphs/([^/]+)/(\\d+-\\d+)\\.pbf$")

    /** Starts the live server on 127.0.0.1:[port] and prints one JSON line describing it. */
    fun serve(port: Int, glyphDir: File, demoPage: File = File("tools/demo/index.html"), textScale: Double = 1.0) {
        val base = "http://127.0.0.1:$port"
        val style = MapLibreStyle(
            MapLibreSources(
                demTiles = "$base/dem/{z}/{x}/{y}.png", demMinZoom = LIVE_MIN_ZOOM, demMaxZoom = LIVE_MAX_ZOOM,
                contourTiles = "$base/contours/{z}/{x}/{y}.pbf", contourMinZoom = CONTOUR_MIN_ZOOM, contourMaxZoom = LIVE_MAX_ZOOM,
                osmTiles = "$base/osm/{z}/{x}/{y}.pbf", osmMinZoom = 0, osmMaxZoom = OSM_MAX_ZOOM,
                glyphs = "$base/glyphs/{fontstack}/{range}.pbf",
                attribution = osmAttributionHtml + " " + demAttributionHtml,
            ),
            textScale = textScale,
        ).json()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)
        server.executor = Executors.newFixedThreadPool(32)
        server.createContext("/") { ex ->
            try {
                handle(ex, style, glyphDir, demoPage)
            } catch (e: Throwable) {
                errors.incrementAndGet()
                val cause = (e as? CompletionException)?.cause ?: e
                reply(ex, (cause.toString() + "\n").toByteArray(), "text/plain", status = 500)
            }
        }
        server.start()
        println(
            JsonWriter.write(
                mapOf(
                    "url" to "$base/", "live" to true, "phone" to "$base/phone", "stats" to "$base/stats",
                    "demPacks" to dems.map { it.file.path }, "osmPacks" to osms.map { it.file.path },
                    "cache" to cacheRoot.path, "glyphs" to glyphDir.path,
                ),
            ),
        )
    }

    private fun handle(ex: HttpExchange, style: String, glyphDir: File, demoPage: File) {
        val path = ex.requestURI.path
        tileRe.matchEntire(path)?.let { m ->
            val (kind, zs, xs, ys) = m.destructured
            val z = zs.toInt(); val x = xs.toInt(); val y = ys.toInt()
            val started = System.nanoTime()
            val tile = when (kind) {
                "dem" -> dem(z, x, y)
                "osm" -> osm(z, x, y)
                else -> contours(z, x, y)
            }
            record(kind, TileId(z, x, y), tile, started)
            val type = if (kind == "dem") "image/png" else "application/x-protobuf"
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
            "/style.json" -> reply(ex, style.toByteArray(), "application/json")
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
        /** Bump when the live DEM rendering changes, so old cached tiles are not served. */
        const val CACHE_VERSION = "best-v1"
        const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
        /** Registry ids credited for OpenFreeMap tiles: the host, the schema and the data. */
        val OSM_REGISTRY_IDS = listOf("osm", "openmaptiles", "openfreemap")
        private const val MAX_STATUS_TILES = 400

        /** The status-box label for a live tile's provenance. */
        fun labelFor(provenance: List<String>): String = when {
            provenance.any { it == "3dep" || it == "3dep-1m" } -> "live-3dep"
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

        /** Renders one DEM tile from the `best` source the way `tiles` does: haloed window, cropped, Terrarium PNG. */
        fun generateBest(t: TileId): LiveDem {
            val cell = TileWindow(t, 0).window.cellSizeMeters
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
            val req = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", "dystopo-devtool/0.1 (+https://github.com/jasondorjeshort/dystopo)")
                .header("Accept-Encoding", "gzip")
                .timeout(Duration.ofSeconds(60))
                .build()
            val response = Http.retry(3, 500, { it is java.io.IOException }) { Http.client.send(req, HttpResponse.BodyHandlers.ofByteArray()) }
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
window.map = new maplibregl.Map({container: "map", style: "/style.json", center: [-121.76, 46.85], zoom: 11, hash: true});
</script></body></html>
"""
    }
}
