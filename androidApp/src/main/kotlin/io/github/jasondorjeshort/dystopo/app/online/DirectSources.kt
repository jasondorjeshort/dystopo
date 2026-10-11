package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import io.github.jasondorjeshort.dystopo.app.options.FastPng
import io.github.jasondorjeshort.dystopo.dem.TileWindow
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.sources.elevation.BestSource
import io.github.jasondorjeshort.dystopo.sources.elevation.DecodedBlocks
import io.github.jasondorjeshort.dystopo.sources.elevation.TiffPool
import io.github.jasondorjeshort.dystopo.sources.elevation.HttpReply as SourceReply
import io.github.jasondorjeshort.dystopo.sources.elevation.OverviewLevels
import io.github.jasondorjeshort.dystopo.sources.elevation.SourceHttp
import io.github.jasondorjeshort.dystopo.sources.elevation.SourceIo
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/** The network is down (no route, no DNS): fail the tile at once instead of retrying ([SourceIo] retries only IOExceptions). */
class SourceOffline(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** The session's download budget for the sources is spent ([DirectSources.sessionCapBytes]). */
class SourceCapReached(message: String) : RuntimeException(message)

/**
 * T80: the phone reads the elevation sources and OpenFreeMap itself when no Dystopo server is set. This is a
 * second target behind the existing [OnlineFetcher] (an [HttpGet] for the pseudo-server [SERVER]), so the disk
 * cache, negative cache, backoff, contour generation and the pack-first resolver of [OnlineTiles] all apply
 * unchanged; a configured server takes precedence ([OnlineService.configure]).
 *
 * - `dem/z/x/y.png`: rendered on the phone with the shared `best` source (Copernicus, 3DEP 10 m and 1 m, the
 *   national 1 m models) exactly as the live server renders it (`LiveTiles.generateBest`: haloed window, one
 *   overview level and blend band per zoom, Terrarium PNG), from [DEM_MIN_ZOOM] (lower zooms span dozens of
 *   Copernicus cells per tile). The COG ranges are cached under `cache/sources/` ([SourceIo.cacheRoot]),
 *   trimmed to [sourceCacheCapBytes].
 * - `osm/z/x/y.pbf`: OpenFreeMap's public planet tiles (template from its TileJSON, cached a day).
 * - `live/info`, `live/sources`: what the fetcher's probe and the credits ask a server.
 *
 * Costs are capped ([PhoneSourceHttp]): at most [MAX_RANGE_REQUESTS] source requests at once, six tiles
 * rendered at once (their own permits, so map tiles never wait behind them), and [sessionCapBytes] downloaded per app session, after which
 * tiles fail with a clear message until the app restarts (a casual pan cannot pull gigabytes).
 */
class DirectSources(private val context: android.content.Context, private val cacheRoot: File, private val cache: TileCache) : HttpGet {
    val http = PhoneSourceHttp(this)

    /** Mobile data allowed (setting `directOnMobileData`, default off). */
    @Volatile var onMobileData: Boolean = false

    /** When the session cap ([sessionCapBytes]) or the Wi-Fi-only rule last stopped a request (0: never); the map shows a message. */
    @Volatile var capReachedAt = 0L
    @Volatile var blockedOnMobileAt = 0L

    /** True when the phone's traffic goes over a cellular network (no Wi-Fi or Ethernet in the active network). */
    fun onCellular(): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /** Reading is held back now: on mobile data without permission. */
    fun blockedOnMobile(): Boolean = !onMobileData && onCellular()

    /** The message the map and the card show, or null. */
    fun notice(): String? = when {
        blockedOnMobile() -> NEEDS_WIFI
        http.bytes.get() >= sessionCapBytes -> capMessage()
        else -> null
    }

    fun capMessage() = "Online data limit reached for this session (${sessionCapBytes shr 20} MB). Download this area, or raise the limit in " +
        "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SOURCES}."

    /** Bytes per app session the sources may download (setting `directSessionMb`). */
    @Volatile var sessionCapBytes: Long = DEFAULT_SESSION_MB * 1024 * 1024
    @Volatile var sourceCacheCapBytes: Long = DEFAULT_SOURCE_CACHE_MB * 1024 * 1024

    private val renderPermits = Semaphore(MAX_TILES_IN_FLIGHT)

    /** DEM tiles being rendered or waiting to (OnlineService reloads the map once they are done, see [ANSWER_WITHIN_MS]). */
    val rendering = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var lastRenderDoneAt = 0L
        private set

    val demRendered = AtomicLong()
    val demMs = AtomicLong()
    val demMaxMs = AtomicLong()
    val osmFetched = AtomicLong()
    val osmBytes = AtomicLong()
    val failures = AtomicLong()
    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastOkAt = 0L
    @Volatile private var bytesAtLastTrim = 0L
    @Volatile var trims = 0
        private set

    /** Registry ids per recently rendered DEM tile, for `live/sources` (the view's credits) and pack metadata. */
    private val provenance = object : LinkedHashMap<String, List<String>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 8192
    }

    init {
        SourceIo.cacheRoot = File(cacheRoot, "sources").path
        SourceIo.http = http
        // Neighbouring tiles share COG blocks: keep files open and their decoded blocks under a heap-sized cap.
        SourceIo.tiffPool = TiffPool(TIFF_POOL)
        SourceIo.decodedBlocks = DecodedBlocks(minOf(DECODED_CAP_BYTES, Runtime.getRuntime().maxMemory() / 4))
        Thread({ trimSourceCache() }, "direct-trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    override fun get(url: String): HttpReply {
        require(url.startsWith("$SERVER/")) { "not a direct-source URL: $url" }
        val path = url.removePrefix("$SERVER/")
        return try {
            when {
                path.startsWith("dem/") -> dem(path.removePrefix("dem/"))
                path.startsWith("osm/") -> osm(path.removePrefix("osm/"))
                path == "live/info" -> info()
                path.startsWith("live/sources") -> sources(path)
                else -> HttpReply(404, ByteArray(0)) // glyphs and sprites are bundled in the APK (BundledAssets)
            }.also { if (it.status in 200..299) lastOkAt = System.currentTimeMillis() }
        } catch (e: SourceOffline) {
            note(e)
            throw ConnectFailed(e.message ?: "offline", e)
        } catch (e: ConnectFailed) {
            note(e)
            throw e
        } catch (e: IOException) {
            note(e)
            throw e
        } catch (e: RuntimeException) {
            // A reader failed on this tile (cap reached, a garbled answer from a source): this tile only.
            note(e)
            throw IOException("${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun note(e: Throwable) {
        failures.incrementAndGet()
        lastError = "${e.javaClass.simpleName}: ${e.message}"
    }

    // ---- DEM ----

    private fun parseTile(path: String, ext: String): TileId? {
        val parts = path.removeSuffix(ext).split('/')
        if (parts.size != 3) return null
        val (z, x, y) = parts.map { it.toIntOrNull() ?: return null }
        val n = 1 shl z.coerceIn(0, 30)
        return if (z in 0..22 && x in 0 until n && y in 0 until n) TileId(z, x, y) else null
    }

    /**
     * Deepest DEM zoom rendered from the sources (setting `directMaxZoom`, default [DEFAULT_MAX_ZOOM]); deeper
     * tiles are synthesized from it ([synthesize]), as a pack's deeper tiles are from its last level.
     */
    @Volatile var maxZoom: Int = DEFAULT_MAX_ZOOM
        set(value) {
            field = value.coerceIn(DEM_MIN_ZOOM, OnlineTiles.DEM_MAX_ZOOM)
            dropPyramid()
        }

    val demSynthesized = AtomicLong()
    private val renderedByZoom = java.util.TreeMap<Int, Int>()
    private val inFlight = HashMap<String, Lazy<ByteArray>>()

    private fun dem(path: String): HttpReply {
        val t = parseTile(path, ".png") ?: return HttpReply(404, ByteArray(0))
        if (t.z < DEM_MIN_ZOOM || t.z > OnlineTiles.DEM_MAX_ZOOM) return HttpReply(404, ByteArray(0))
        val png = if (t.z > maxZoom) synthesize(t) else rendered(t)
        maybeTrim()
        return HttpReply(200, png)
    }

    /** Tile [t] (at most [maxZoom]): from the online cache, where the fetcher keeps what this returned, else rendered once. */
    private fun rendered(t: TileId): ByteArray {
        val key = "$KEY/dem/${t.z}/${t.x}/${t.y}.png"
        cache.get(key)?.let { return it }
        val job = synchronized(inFlight) { inFlight.getOrPut(key) { lazy { renderAndRecord(t).also { cache.put(key, it) } } } }
        try {
            return job.value
        } finally {
            synchronized(inFlight) { if (inFlight[key] === job) inFlight.remove(key) }
        }
    }

    private fun renderAndRecord(t: TileId): ByteArray {
        // Renders take their own permits, so OpenFreeMap tiles never queue behind them (MapLibre gives up on a
        // tile after its timeout, and the map then never counts as loaded).
        rendering.incrementAndGet()
        renderPermits.acquire()
        val started = System.nanoTime()
        val (png, ids) = try {
            renderDem(t)
        } finally {
            renderPermits.release()
            rendering.decrementAndGet()
            lastRenderDoneAt = System.currentTimeMillis()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        demRendered.incrementAndGet()
        synchronized(renderedByZoom) { renderedByZoom[t.z] = (renderedByZoom[t.z] ?: 0) + 1 }
        demMs.addAndGet(ms)
        demMaxMs.accumulateAndGet(ms, ::maxOf)
        recordProvenance(t, ids)
        Log.d(TAG, "rendered ${t.z}/${t.x}/${t.y} in $ms ms on ${Thread.currentThread().name}")
        return png
    }

    private fun recordProvenance(t: TileId, ids: List<String>) {
        val key = "${t.z}/${t.x}/${t.y}"
        synchronized(provenance) { provenance[key] = ids }
        runCatching { cache.put("$PROVENANCE_PREFIX/$key.txt", ids.joinToString(",").encodeToByteArray()) }
    }

    @Volatile private var pyramid: io.github.jasondorjeshort.dystopo.tiles.DemPyramid? = null
    private val failure = ThreadLocal<Exception?>()

    private fun dropPyramid() {
        pyramid = null
    }

    private fun pyramid(): io.github.jasondorjeshort.dystopo.tiles.DemPyramid {
        pyramid?.let { return it }
        synchronized(this) {
            pyramid?.let { return it }
            return io.github.jasondorjeshort.dystopo.tiles.DemPyramid(DEM_MIN_ZOOM, maxZoom, io.github.jasondorjeshort.dystopo.app.TileService.demDecoder(), cacheTiles = 32) { z, x, y ->
                try {
                    rendered(TileId(z, x, y))
                } catch (e: Exception) {
                    failure.set(e)
                    null
                }
            }.also { pyramid = it }
        }
    }

    /** Tile [t] deeper than [maxZoom]: its [maxZoom] ancestor rendered (once) and sampled bilinearly, as packs overzoom. */
    private fun synthesize(t: TileId): ByteArray {
        failure.set(null)
        val p = pyramid()
        val grid = p.window(t.z, t.x * 256, t.y * 256, 256, 256)
        failure.get()?.let { e ->
            failure.set(null)
            dropPyramid() // it may remember the failed ancestor as missing
            throw e
        }
        // Every ancestor missing (another thread's render failed while this one waited for it): not a sea-level tile.
        if (grid.values.all { it.isNaN() }) throw IOException("elevation for ${t.z}/${t.x}/${t.y} is not available yet")
        demSynthesized.incrementAndGet()
        val k = maxZoom
        val dz = t.z - k
        provenanceOf(k, t.x shr dz, t.y shr dz)?.let { recordProvenance(t, it) }
        return FastPng.encode(TileWindows.encodeTerrarium(grid), grid.width, grid.height)
    }

    /** One DEM tile from `best`, the way the live server renders it: Terrarium PNG and the registry ids that contributed. */
    fun renderDem(t: TileId): Pair<ByteArray, List<String>> {
        val cell = WebMercator.metersPerPixel(REFERENCE_LAT, t.z, 256)
        val tw = TileWindow(t, TileWindows.haloCells(BestSource.GLOBAL_FEATHER_METERS, cell))
        val result = OverviewLevels.withTarget(cell) { BestSource().fetch(tw.window, cell) }
        val grid = tw.interior(result.grid)
        return FastPng.encode(TileWindows.encodeTerrarium(grid), grid.width, grid.height) to result.provenance
    }

    /** The registry ids of DEM tile z/x/y when it was rendered here (this session, or kept beside the online cache). */
    fun provenanceOf(z: Int, x: Int, y: Int): List<String>? {
        val key = "$z/$x/$y"
        synchronized(provenance) { provenance[key] }?.let { return it }
        return cache.get("$PROVENANCE_PREFIX/$key.txt")?.decodeToString()?.split(',')?.filter { it in SourceRegistry.builtIn }
    }

    // ---- OpenFreeMap ----

    @Volatile private var ofm: Pair<String, String>? = null
    @Volatile private var ofmLayers: List<Any?> = emptyList()

    /** The OpenFreeMap tile URL template and build id from its TileJSON (cached a day on disk; a stale copy offline). */
    fun openFreeMap(): Pair<String, String> {
        ofm?.let { return it }
        val file = File(cacheRoot, "sources/openfreemap/planet.json")
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L
        val text = if (fresh) file.readText() else try {
            val reply = SourceIo.get(OFM_TILEJSON, 30_000, 2)
            if (reply.status != 200) throw IOException("OpenFreeMap TileJSON: HTTP ${reply.status}")
            gunzip(reply.body).decodeToString().also { t ->
                file.parentFile?.mkdirs()
                File(file.parentFile, "planet.json.tmp").also { it.writeText(t) }.renameTo(file)
            }
        } catch (e: Exception) {
            if (file.isFile) file.readText() else throw e
        }
        val json = MiniJson.parseObject(text)
        val template = (json["tiles"] as? List<*>)?.firstOrNull() as? String ?: throw IOException("OpenFreeMap TileJSON without tiles")
        require("{z}" in template && "{x}" in template && "{y}" in template) { "unexpected OpenFreeMap tile URL $template" }
        val build = template.substringBefore("/{z}").substringAfterLast('/').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        ofmLayers = io.github.jasondorjeshort.dystopo.packs.OsmPackWriter.vectorLayers(json)
        return (template to build).also { ofm = it }
    }

    /** `vector_layers` of the OpenFreeMap TileJSON (trimmed), for a vector pack's metadata. */
    fun openFreeMapLayers(): List<Any?> {
        openFreeMap()
        return ofmLayers
    }

    private fun osm(path: String): HttpReply {
        val t = parseTile(path, ".pbf") ?: return HttpReply(404, ByteArray(0))
        if (t.z > OnlineTiles.OSM_MAX_ZOOM) return HttpReply(404, ByteArray(0))
        val (template, _) = openFreeMap()
        val url = template.replace("{z}", "${t.z}").replace("{x}", "${t.x}").replace("{y}", "${t.y}")
        val reply = SourceIo.get(url, 30_000, 2)
        return when (reply.status) {
            200 -> {
                osmFetched.incrementAndGet()
                osmBytes.addAndGet(reply.body.size.toLong())
                HttpReply(200, reply.body)
            }
            204, 404 -> HttpReply(200, ByteArray(0)) // no tile there (open ocean): an empty tile, as the live server serves it
            else -> HttpReply(reply.status, ByteArray(0))
        }
    }

    // ---- what a server's probe and credits routes say ----

    private fun info(): HttpReply {
        // Recently fine: no request. Otherwise one real request (the TileJSON), so the indicator notices the network.
        if (System.currentTimeMillis() - lastOkAt > 60_000) {
            ofm = null
            val reply = try {
                SourceIo.get(OFM_TILEJSON, 10_000, 1)
            } catch (e: IOException) {
                throw ConnectFailed("${e.javaClass.simpleName}: ${e.message}", e)
            }
            if (reply.status != 200) throw IOException("OpenFreeMap TileJSON: HTTP ${reply.status}")
        }
        val registry = SourceRegistry.builtIn
        val body = linkedMapOf(
            "direct" to true, "demMinZoom" to DEM_MIN_ZOOM, "demMaxZoom" to OnlineTiles.DEM_MAX_ZOOM, "osmMaxZoom" to OnlineTiles.OSM_MAX_ZOOM,
            "osmAttribution" to registry.attributionHtml(OSM_IDS),
            "demAttribution" to registry.attributionHtml(BestSource().registryIds),
        )
        return HttpReply(200, MiniJson.encode(body).encodeToByteArray())
    }

    private fun sources(path: String): HttpReply {
        val q = path.substringAfter('?', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val b = q["bbox"]?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
        val dz = q["dz"]?.toIntOrNull()
        val ids = LinkedHashSet<String>()
        if (b != null && dz != null) {
            val n = 1 shl dz
            val x0 = floor(WebMercator.lonToX(b[0], dz)).toInt().coerceIn(0, n - 1)
            val x1 = floor(WebMercator.lonToX(b[2], dz)).toInt().coerceIn(0, n - 1)
            val y0 = floor(WebMercator.latToY(b[3], dz)).toInt().coerceIn(0, n - 1)
            val y1 = floor(WebMercator.latToY(b[1], dz)).toInt().coerceIn(0, n - 1)
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) <= 400) {
                for (y in y0..y1) for (x in x0..x1) synchronized(provenance) { provenance["$dz/$x/$y"] }?.let { ids += it }
            }
        }
        if (osmFetched.get() > 0) ids += OSM_IDS
        return HttpReply(200, MiniJson.encode(mapOf("registryIds" to SourceRegistry.builtIn.ordered(ids.toList()))).encodeToByteArray())
    }

    // ---- the source cache's cap ----

    private fun maybeTrim() {
        val fetched = http.bytes.get()
        if (fetched - bytesAtLastTrim < TRIM_EVERY_BYTES) return
        bytesAtLastTrim = fetched
        Thread({ trimSourceCache() }, "direct-trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Deletes the oldest cached source ranges until `cache/sources/` is under [sourceCacheCapBytes]. */
    @Synchronized
    fun trimSourceCache(): Map<String, Any?> {
        val root = File(cacheRoot, "sources")
        val files = root.walkTopDown().filter { it.isFile }.map { Triple(it, it.length(), it.lastModified()) }.toList()
        var total = files.sumOf { it.second }
        val before = total
        var deleted = 0
        if (total > sourceCacheCapBytes) {
            for ((f, len, _) in files.sortedBy { it.third }) {
                if (total <= sourceCacheCapBytes * 9 / 10) break
                if (f.name == "planet.json") continue
                if (f.delete()) {
                    total -= len
                    deleted++
                }
            }
        }
        trims++
        return linkedMapOf("bytesBefore" to before, "bytesAfter" to total, "filesDeleted" to deleted, "capBytes" to sourceCacheCapBytes)
    }

    fun json(): Map<String, Any?> {
        val n = demRendered.get()
        return linkedMapOf(
            "sessionBytes" to http.bytes.get(), "sessionCapBytes" to sessionCapBytes, "capReached" to (http.bytes.get() >= sessionCapBytes),
            "onMobileDataAllowed" to onMobileData, "onCellular" to onCellular(), "blockedOnMobileData" to blockedOnMobile(), "notice" to notice(),
            "requests" to http.requests.get(), "inFlight" to http.inFlight.get(), "maxInFlight" to MAX_RANGE_REQUESTS,
            "offlineFailures" to http.offline.get(), "requestWaitMs" to http.waitMs.get(), "requestNetMs" to http.netMs.get(),
            "maxZoom" to maxZoom, "demSynthesized" to demSynthesized.get(), "renderedByZoom" to synchronized(renderedByZoom) { renderedByZoom.mapKeys { "z${it.key}" } }, "demRendered" to n, "demMsAvg" to if (n == 0L) null else demMs.get() / n, "demMsMax" to demMaxMs.get(),
            "osmFetched" to osmFetched.get(), "osmBytes" to osmBytes.get(),
            "failures" to failures.get(), "lastError" to lastError,
            "tiffPool" to SourceIo.tiffPool?.let { mapOf("opened" to it.opened, "reused" to it.reused) },
            "decodedBlocks" to SourceIo.decodedBlocks?.let { mapOf("bytes" to it.sizeBytes, "hits" to it.hits, "misses" to it.misses) },
            "sourceCache" to linkedMapOf("dir" to SourceIo.cacheRoot, "capBytes" to sourceCacheCapBytes, "trims" to trims),
            "openFreeMapBuild" to ofm?.second,
        )
    }

    companion object {
        const val TAG = "DystopoDirect"

        /** The pseudo-server the [OnlineFetcher] asks; its cache key is `sources`. */
        const val SERVER = "direct://sources"
        val KEY: String = OnlineFetcher.keyFor(SERVER)
        private val PROVENANCE_PREFIX = "$KEY/demprov"

        /** Direct DEM tiles start here: a z6 tile spans about 30 Copernicus cells (one header and one overview block each). */
        const val DEM_MIN_ZOOM = 8

        /**
         * Deepest DEM zoom rendered from the sources by default (26 m cells: 3DEP read one overview down, no 1 m
         * lidar); deeper tiles are synthesized from it. 15 renders every zoom as the server does, at about four
         * times the bytes per view (numbers in docs/PLANNING.md, T80).
         */
        const val DEFAULT_MAX_ZOOM = 12

        /** DEM tiles rendered at once: each holds a few decoded 4 MB COG blocks while it renders. */
        const val MAX_TILES_IN_FLIGHT = 6

        /** Requests the fetcher lets through at once (renders are limited separately, OpenFreeMap tiles are quick). */
        const val FETCHER_IN_FLIGHT = 12

        /** Source requests (range reads, index pages) at once over every source. */
        const val MAX_RANGE_REQUESTS = 16

        /** Open GeoTIFFs kept ([TiffPool]) and the decoded blocks' memory cap ([DecodedBlocks]; a quarter of the heap at most). */
        const val TIFF_POOL = 96
        const val DECODED_CAP_BYTES = 96L shl 20

        /** How long a map request waits for a tile being rendered before it is told to come back (OnlineTiles.answerWithinMs). */
        const val ANSWER_WITHIN_MS = 8_000L

        val NEEDS_WIFI = "Online map needs Wi-Fi, or allow mobile data in ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SOURCES}."

        const val DEFAULT_SESSION_MB = 300L
        const val DEFAULT_SOURCE_CACHE_MB = 512L
        private const val TRIM_EVERY_BYTES = 32L shl 20

        /** As the live server: the overview level, halo and blend band of a zoom come from this latitude. */
        const val REFERENCE_LAT = 45.0

        const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
        val OSM_IDS = listOf("osm", "openmaptiles", "openfreemap")

        fun gunzip(bytes: ByteArray): ByteArray = OnlineFetcher.gunzipIfNeeded(bytes)
    }
}

/**
 * The sources' HTTP on the phone ([SourceIo.http]): every request through [CleartextPolicy] (redirects checked
 * hop by hop), at most [DirectSources.MAX_RANGE_REQUESTS] at once, counted against the session cap. No route
 * or no DNS is a [SourceOffline] (not retried); the cap a [SourceCapReached].
 */
class PhoneSourceHttp(private val owner: DirectSources) : SourceHttp {
    val bytes = AtomicLong()
    val requests = AtomicLong()
    val inFlight = AtomicLong()
    val offline = AtomicLong()
    /** Milliseconds requests spent waiting for a permit, and on the network (summed over threads). */
    val waitMs = AtomicLong()
    val netMs = AtomicLong()
    private val permits = Semaphore(DirectSources.MAX_RANGE_REQUESTS)

    override fun range(url: String, offset: Long, length: Int): SourceReply =
        send(url, 120_000) { it.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}") }

    override fun get(url: String, timeoutMs: Long): SourceReply = send(url, timeoutMs) {}

    private fun send(start: String, timeoutMs: Long, prepare: (HttpURLConnection) -> Unit): SourceReply {
        if (bytes.get() >= owner.sessionCapBytes) {
            owner.capReachedAt = System.currentTimeMillis()
            throw SourceCapReached(owner.capMessage())
        }
        if (owner.blockedOnMobile()) {
            owner.blockedOnMobileAt = System.currentTimeMillis()
            throw SourceOffline(DirectSources.NEEDS_WIFI, null)
        }
        val queued = System.nanoTime()
        permits.acquire()
        val started = System.nanoTime()
        waitMs.addAndGet((started - queued) / 1_000_000)
        inFlight.incrementAndGet()
        try {
            var url = start
            repeat(5) {
                requests.incrementAndGet()
                val conn = CleartextPolicy.open(url)
                conn.connectTimeout = 15_000
                conn.readTimeout = timeoutMs.toInt()
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "dystopo-android/0.1 (+https://github.com/jasondorjeshort/dystopo)")
                prepare(conn)
                try {
                    try {
                        conn.connect()
                    } catch (e: IOException) {
                        if (e is UnknownHostException || e is ConnectException || e is NoRouteToHostException) {
                            offline.incrementAndGet()
                            throw SourceOffline("${e.javaClass.simpleName}: ${e.message}", e)
                        }
                        throw e
                    }
                    CleartextPolicy.redirectTarget(conn)?.let { next ->
                        url = next
                        return@repeat
                    }
                    val status = conn.responseCode
                    val body = if (status in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
                    bytes.addAndGet(body.size.toLong())
                    // HttpURLConnection asks for gzip and undoes it itself.
                    return SourceReply(status, body)
                } finally {
                    conn.disconnect()
                }
            }
            throw IOException("too many redirects from $start")
        } finally {
            netMs.addAndGet((System.nanoTime() - started) / 1_000_000)
            inFlight.decrementAndGet()
            permits.release()
        }
    }
}

internal fun logDirect(message: String) = Log.i(DirectSources.TAG, message)
