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
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTilePlan
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
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
 * Costs are capped ([PhoneSourceHttp]): at most [MAX_RANGE_REQUESTS] source requests at once, [MAX_TILES_IN_FLIGHT] tiles
 * rendered at once (their own permits, so map tiles never wait behind them), and [sessionCapBytes] downloaded per app session, after which
 * tiles fail with a clear message until the app restarts (a casual pan cannot pull gigabytes).
 *
 * T127: the renders at once are also sized from the free Java heap ([HeapGuard.admitNow]), the synthesis pyramid is
 * dropped on a trim ([trimMemory], registered with [HeapGuard]), the decoded blocks take an eighth of the heap
 * ([decodedCapBytes]) and the geoid offset memo 4 MB ([GEOID_MEMO_BYTES]); a cold Mont Blanc z9 view after the device
 * tour ran the 192 MB heap out (finding 2026-10-21-direct-view-out-of-memory-after-a-tour). T138: a trim empties the
 * decoded blocks and the geoid memo in place too ([trimBlocks], [trimGeoidMemo]).
 * T127 (T112's deferred fold-in, ADR 0034): `cache/sources/`'s own trim follows the one cache total ([sourceCacheCapBytes],
 * [budgetTotalBytes] set by `cachebudget/CacheBudget`), so the total, not a fixed 512 MB, is its ceiling.
 */
class DirectSources(private val context: android.content.Context, private val cacheRoot: File, internal val cache: TileCache) : HttpGet {
    val http = PhoneSourceHttp(this)

    /** Mobile data allowed (setting `directOnMobileData`; on by default since T83: nothing in the app is gated on Wi-Fi, the session limit is the brake). */
    @Volatile var onMobileData: Boolean = true

    /** When the session cap ([sessionCapBytes]) or the user's "mobile data off" choice last stopped a request (0: never); the map shows a message. */
    @Volatile var capReachedAt = 0L
    @Volatile var blockedOnMobileAt = 0L

    /** True when the phone's traffic goes over a cellular network (no Wi-Fi or Ethernet in the active network). */
    fun onCellular(): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /** Reading is held back now: on mobile data with mobile data turned off by the user (Settings › Sources). */
    fun blockedOnMobile(): Boolean = !onMobileData && onCellular()

    /** The message the map and the card show, or null. */
    fun notice(): String? = when {
        blockedOnMobile() -> MOBILE_DATA_OFF
        http.bytes.get() >= sessionCapBytes -> capMessage()
        else -> null
    }

    fun capMessage() = "Online data limit reached for this session (${sessionCapBytes shr 20} MB). Download this area, or raise the limit in " +
        "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SOURCES}."

    /** Bytes per app session the sources may download (setting `directSessionMb`). */
    @Volatile var sessionCapBytes: Long = DEFAULT_SESSION_MB * 1024 * 1024
    /**
     * The ceiling of `cache/sources/`'s own trim. T127 (T112's deferred fold-in, ADR 0034): the source data is a kind of the
     * one cache budget, so the ceiling is the budget's total ([budgetTotalBytes], set by `cachebudget/CacheBudget` as it sets
     * the online and contour caps, `AppSettings.setCapsFromBudget`) and the budget evicts within it; [DEFAULT_SOURCE_CACHE_MB]
     * only until the budget has started.
     */
    val sourceCacheCapBytes: Long get() = budgetTotalBytes.takeIf { it > 0 } ?: (DEFAULT_SOURCE_CACHE_MB * 1024 * 1024)

    /**
     * T106: the render slots and their counters ([RenderSlots]: given back however a render ends, an interrupt included).
     * T127: at most as many as the free heap holds ([HeapGuard.admitNow]), each render in the heap record.
     */
    private val slots = RenderSlots(
        MAX_TILES_IN_FLIGHT, COARSE_FIRST_MS, COARSE_GRACE_MS,
        admit = { running, committed, weight -> HeapGuard.admitNow(running, committed, weight) { runningNowSafe() } },
        weightOf = HeapGuard::weightOf,
        onStart = { HeapGuard.renderStarted { rendering.get() } },
        onEnd = { HeapGuard.renderEnded() },
    )

    /** DEM tiles being rendered or waiting to (OnlineService reloads the map once they are done, see [ANSWER_WITHIN_MS]). */
    val rendering: java.util.concurrent.atomic.AtomicInteger get() = slots.rendering
    val lastRenderDoneAt: Long get() = slots.lastDoneAt

    /**
     * T88, coarse first: a view's coarser DEM tiles (below [COARSER_OVERVIEW_BELOW]: MapLibre's fallback tiles and
     * the fallback contour tile's DEM) read a quarter of the bytes of its own tiles and show terrain and contours
     * long before them. On a cold view both used to share the link, so neither came early. A render at the
     * view's zoom waits for the coarse renders in flight (at most [COARSE_FIRST_MS]); at the start of a burst it
     * gives them [COARSE_GRACE_MS] to be asked for (MapLibre asks for them just after the view's own tiles).
     */
    val coarseHeldMs: AtomicLong get() = slots.coarseHeldMs

    /** T88: cache keys of the DEM renders a map request was answered "not ready" (503) for while waiting on them. */
    private val awaited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** T88: a map request for DEM tile z/x/y (or a contour tile's DEM tile) was answered 503 while it rendered. */
    fun awaitedTile(z: Int, x: Int, y: Int) {
        // Deeper tiles are synthesized from their [maxZoom] ancestor and the neighbours its edge reaches into; the
        // reload waits for those in view (the rest only feed the tile's outer edge and are done by the time it is asked again).
        val sources = if (z > maxZoom) synthesisSources(TileId(z, x, y), maxZoom).filter { it == TileId(maxZoom, x shr (z - maxZoom), y shr (z - maxZoom)) || inView(it) } else listOf(TileId(z, x, y))
        for (t in sources) awaited += "$KEY/dem/${t.z}/${t.x}/${t.y}.png"
    }

    /**
     * T88: map requests for DEM tiles wait on renders: they run here, not on [OnlineService]'s six prefetch threads,
     * where six requests waiting on renders kept the rest of the view's tiles queued past their 8 s.
     */
    private val requestPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "direct-request").apply { isDaemon = true } }

    fun <T> request(block: () -> T): java.util.concurrent.CompletableFuture<T> = java.util.concurrent.CompletableFuture.supplyAsync(block, requestPool)

    /**
     * T88: whether the map should reload now to pick up tiles it was told were not ready (OnlineService). Before
     * T88 that waited until every render had finished and the phone had been idle a second, so contour tiles of
     * the view's first renders waited for the slowest fallback tile (6 to 7 s on a cold Rainier view). Now: as
     * soon as every render a 503 was answered for is done (made, failed or given up), or everything is idle.
     */
    fun readyForRefresh(): Boolean {
        val now = System.currentTimeMillis()
        val ready = when {
            rendering.get() == 0 && now - lastRenderDoneAt >= 1_000 -> true
            now - lastRenderDoneAt < REFRESH_SETTLE_MS -> false
            else -> {
                synchronized(inFlight) { awaited.removeIf { it !in inFlight } }
                awaited.isEmpty()
            }
        }
        if (ready) {
            lastRefreshAt = now
            DirectTrace.mark("refresh") // the caller reloads the style now
        }
        return ready
    }

    /** T88: when the last reload for tiles that were not ready was started (wall clock ms). */
    @Volatile var lastRefreshAt = 0L
        private set

    /**
     * T88: whether a "not ready" answer to a request that arrived at [askedAt] should bring another reload. One
     * asked for before the last reload is the old style's: MapLibre asks again with the new one, and that request
     * counts. Counted, those answers (DEM tiles time out a few seconds after a reload) brought a second reload
     * about a second after the first, blanking the map twice.
     */
    fun countsForRefresh(askedAt: Long): Boolean = askedAt >= lastRefreshAt

    val demRendered = AtomicLong()
    /** T88: per-stage sums over every render: waiting for a render permit, `best` fetching (reads, decodes, resampling, geoid, compositing), encoding. */
    val renderWaitMs = AtomicLong()
    val renderFetchMs = AtomicLong()
    val renderEncodeMs = AtomicLong()
    /** T88: contour tiles' time waiting for their DEM tiles ([contourWindow]). */
    val contourDemWaitMs = AtomicLong()
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
        val cap = decodedCapBytes(Runtime.getRuntime().maxMemory())
        HeapGuard.cacheBytes = cap // T127
        SourceIo.decodedBlocks = DecodedBlocks(cap)
        // T127: the per-window geoid offsets are kept for windows rendered again, which the online cache makes rare here;
        // the server's 32 MB memo held 31 MB of the phone's heap after a tour (a heap dump).
        io.github.jasondorjeshort.dystopo.sources.elevation.Geoids.offsetCacheBytes = GEOID_MEMO_BYTES
        HeapGuard.register("directSources", ::trimMemory) // T127
        HeapGuard.register("decodedBlocks", ::trimBlocks) // T138
        HeapGuard.register("geoidMemo", ::trimGeoidMemo) // T138
        // T88: every cold range read on the timeline (file name, bytes, ms).
        io.github.jasondorjeshort.dystopo.sources.elevation.SourceTiming.onRead = { url, bytes, nanos -> DirectTrace.mark("read ${url.substringAfterLast('/')} ${bytes shr 10} KB ${nanos / 1_000_000} ms") }
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
    /** T85: a render a contour tile asked for, given up when it got its turn because the view had moved on ([contourWindow]). */
    private class StaleRender(t: TileId) : RuntimeException("DEM ${t.z}/${t.x}/${t.y} is not needed for the view any more")

    /**
     * [wanted] (T85, contour tiles only): checked again when the render gets its permit; false gives the render up
     * ([StaleRender]). A request without it (the map's own tile) that joined such a render renders the tile itself.
     */
    private fun rendered(t: TileId, wanted: (() -> Boolean)? = null): ByteArray {
        val key = "$KEY/dem/${t.z}/${t.x}/${t.y}.png"
        cache.get(key)?.let { return it }
        while (true) {
            val job = synchronized(inFlight) { inFlight.getOrPut(key) { lazy { renderAndRecord(t, wanted).also { cache.put(key, it) } } } }
            try {
                return job.value
            } catch (e: StaleRender) {
                if (wanted != null) throw e
            } finally {
                synchronized(inFlight) { if (inFlight[key] === job) inFlight.remove(key) }
            }
        }
    }

    private fun renderAndRecord(t: TileId, wanted: (() -> Boolean)? = null): ByteArray {
        // Renders take their own permits, so OpenFreeMap tiles never queue behind them (MapLibre gives up on a
        // tile after its timeout, and the map then never counts as loaded).
        val coarse = t.z < COARSER_OVERVIEW_BELOW
        DirectTrace.mark("dem? ${t.z}/${t.x}/${t.y}")
        var started = 0L
        // T106: the coarse hold and the permit are inside [RenderSlots.run], so an interrupt there leaks no counter.
        val (png, ids) = slots.run(coarse) { waited ->
            started = System.nanoTime()
            renderWaitMs.addAndGet(waited / 1_000_000)
            if (wanted != null && !wanted()) {
                contourStale.incrementAndGet()
                throw StaleRender(t)
            }
            DirectTrace.mark("dem> ${t.z}/${t.x}/${t.y}")
            // T88: block reads of the coarse tiles first, then the view's own, then out-of-view ones (synthesis edges).
            SourceIo.withPriority(if (coarse) 1 else if (inView(t)) 0 else -1) { renderDem(t) }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        demRendered.incrementAndGet()
        synchronized(renderedByZoom) { renderedByZoom[t.z] = (renderedByZoom[t.z] ?: 0) + 1 }
        demMs.addAndGet(ms)
        demMaxMs.accumulateAndGet(ms, ::maxOf)
        recordProvenance(t, ids)
        DirectTrace.mark("dem. ${t.z}/${t.x}/${t.y} $ms ms")
        if (io.github.jasondorjeshort.dystopo.app.BuildConfig.DEBUG) Log.d(TAG, "rendered ${t.z}/${t.x}/${t.y} in $ms ms on ${Thread.currentThread().name}") // T106: no tile coordinates in a release build's logcat
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
        // T88: the ancestor and the neighbours the edge samples reach into are rendered together, those in view first
        // and then those out of view (only this tile's outer edge needs them). The pyramid asked for them one after
        // another as the window reached them, so a view-edge tile of a cold view waited for its ancestor's render,
        // then for each neighbour's in turn, and answered 503 at 8 s.
        val sources = synthesisSources(t, maxZoom)
        if (sources.size > 1) {
            val parent = TileId(maxZoom, t.x shr (t.z - maxZoom), t.y shr (t.z - maxZoom))
            for (group in sources.partition { it == parent || inView(it) }.toList()) {
                val jobs = group.map { s -> java.util.concurrent.CompletableFuture.supplyAsync({ runCatching { rendered(s) } }, contourRenderPool) }
                for (j in jobs) runCatching { j.get() } // failures surface again below, through the pyramid
            }
        }
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
        // T85: below [COARSER_OVERVIEW_BELOW] the readers pick the overview level for twice the cell size: half the
        // bytes of a zoomed-out view (measured in docs/PLANNING.md), and the hillshade there is drawn 1 dp per DEM pixel.
        val target = if (t.z < COARSER_OVERVIEW_BELOW) cell * 2 else cell
        val f0 = System.nanoTime()
        val result = OverviewLevels.withTarget(target) { BestSource().fetch(tw.window, cell) }
        val f1 = System.nanoTime()
        val grid = tw.interior(result.grid)
        val png = FastPng.encode(TileWindows.encodeTerrarium(grid), grid.width, grid.height)
        renderFetchMs.addAndGet((f1 - f0) / 1_000_000)
        renderEncodeMs.addAndGet((System.nanoTime() - f1) / 1_000_000)
        return png to result.provenance
    }

    /** The registry ids of DEM tile z/x/y when it was rendered here (this session, or kept beside the online cache). */
    fun provenanceOf(z: Int, x: Int, y: Int): List<String>? {
        val key = "$z/$x/$y"
        synchronized(provenance) { provenance[key] }?.let { return it }
        return cache.get("$PROVENANCE_PREFIX/$key.txt")?.decodeToString()?.split(',')?.filter { it in SourceRegistry.builtIn }
    }

    // ---- T85: the DEM tiles a contour tile samples ----

    /** Contour samples: halo tiles skipped (out of view, not made), taken already made, waited for, rendered for a contour tile. */
    val contourHaloSkipped = AtomicLong()
    val contourDemCached = AtomicLong()
    val contourDemWaited = AtomicLong()
    val contourDemRendered = AtomicLong()
    val contourStale = AtomicLong()
    val contourPartial = AtomicLong()
    private val contourRenderPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "direct-contour-dem").apply { isDaemon = true } }

    /**
     * The DEM tiles of [plan]'s read window ([ContourTilePlan.readWindow]) for one contour tile ([contourPyramid]),
     * all waited for together (at most [CONTOUR_WAIT_MS], then the tile is not ready: a 503, asked for again after the reload ([readyForRefresh]),
     * then from the cache). A tile made already (the online cache) or being made (the hillshade asked for it) is
     * taken. Otherwise it is rendered only when the view needs it ([wanted]):
     * - a core tile (under the contour tile itself) when the contour tile is at the view's own zoom or MapLibre's
     *   fallback zoom ([PREFETCH_ZOOM_DELTA] up), or the core tile is in view. Tiles of other zooms (the ones MapLibre
     *   asks for while the camera moves) take only what is made already or in view, else they are not ready (never
     *   cached with a hole);
     * - a halo tile (the ring around the contour tile, four cells of which are traced) when it is in view. Out
     *   of view nobody asked for it, so its halo cells stay NaN and lines stop half a cell short of a seam nobody
     *   sees, instead of a whole tile's COG blocks being read for four pixels.
     * The check runs when the tile is asked for and again when its render gets a permit, so tiles asked for while
     * the camera was still moving are given up once it has settled elsewhere. [lenient] ([staleContour]): a core
     * tile the view does not need is left out (NaN) instead of the whole tile being not ready.
     */
    fun contourWindow(plan: ContourTilePlan, lenient: Boolean = false, packHas: (TileId) -> Boolean = { false }, onWaitedTooLong: () -> Unit = {}): Map<TileId, ByteArray> {
        val askedAt = System.currentTimeMillis()
        val read = plan.readWindow
        val core = OnlineTiles.demTilesFor(read.sampleZoom, read.windowX + read.halo, read.windowY + read.halo, read.gridTileSize).toSet()
        val jobs = LinkedHashMap<TileId, java.util.concurrent.CompletableFuture<ByteArray>>()
        val out = HashMap<TileId, ByteArray>()
        for (tile in OnlineTiles.demTilesFor(read.sampleZoom, read.windowX, read.windowY, read.windowSize)) {
            val (tz, tx, ty) = tile
            val t = TileId(tz, tx, ty)
            if (packHas(t)) continue // T88: the pyramid reads it from the pack
            val key = "$KEY/dem/$tz/$tx/$ty.png"
            cache.get(key)?.let { out[t] = it; contourDemCached.incrementAndGet(); continue }
            val job = synchronized(inFlight) { inFlight[key] }
            val isCore = tile in core
            val want = { wanted(t, plan.z, isCore) }
            jobs[t] = when {
                job != null -> java.util.concurrent.CompletableFuture.supplyAsync({ job.value }, contourRenderPool).also { contourDemWaited.incrementAndGet() }
                want() -> java.util.concurrent.CompletableFuture.supplyAsync({ rendered(t, want) }, contourRenderPool).also { contourDemRendered.incrementAndGet() }
                isCore && !lenient -> { contourStale.incrementAndGet(); throw OnlineTiles.NotReady("contour tile ${plan.z}/${plan.x}/${plan.y} is not at the view's zoom; it takes only tiles already made") }
                isCore -> { contourStale.incrementAndGet(); continue }
                else -> { contourHaloSkipped.incrementAndGet(); skippedHalo.get()?.add(Triple(tz, tx, ty)); continue }
            }
        }
        val waitStart = System.nanoTime()
        val deadline = waitStart + CONTOUR_WAIT_MS * 1_000_000
        try {
            awaitWindow(jobs, out, deadline, lenient) { await -> if (countsForRefresh(askedAt)) { await(); onWaitedTooLong() } }
        } finally {
            contourDemWaitMs.addAndGet((System.nanoTime() - waitStart) / 1_000_000)
        }
        return out
    }

    private fun awaitWindow(jobs: Map<TileId, java.util.concurrent.CompletableFuture<ByteArray>>, out: HashMap<TileId, ByteArray>, deadline: Long, lenient: Boolean, onWaitedTooLong: (await: () -> Unit) -> Unit) {
        for ((t, f) in jobs) {
            try {
                out[t] = f.get(maxOf(0L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                // Every render of the window still going, not only this one: a reload after the first of them
                // finished asked for the tile while the others still rendered, and it answered 503 a second time.
                onWaitedTooLong { for ((pending, job) in jobs) if (!job.isDone) awaitedTile(pending.z, pending.x, pending.y) }
                throw OnlineTiles.NotReady("DEM ${t.z}/${t.x}/${t.y} for contours is still being made; try again")
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                if (cause is StaleRender) {
                    if (lenient) continue
                    throw OnlineTiles.NotReady(cause.message ?: "stale")
                }
                // T88: offline, the session limit, a source that failed: the tile is made without this DEM tile (NaN)
                // and not kept, as the online path does (OnlineTiles: served, not cached). A contour tile across a
                // phone-built pack's edge then shows the pack's part offline; it answered 500 and showed nothing.
                if (cause is IOException || cause is SourceOffline || cause is SourceCapReached) {
                    windowIncomplete.set(true)
                    continue
                }
                throw cause
            }
        }
    }

    /**
     * T88: the halo tiles [contourWindow] would leave out (NaN) for [plan] now: not made, not being made and not
     * wanted by the view. A contour tile made without them has a half-cell gap at that seam, so it is cached
     * under a key naming them ([OnlineTiles.contourKey]) and made again once one of them exists.
     */
    fun haloSkipped(plan: ContourTilePlan, packHas: (TileId) -> Boolean = { false }): List<Triple<Int, Int, Int>> {
        val read = plan.readWindow
        val core = OnlineTiles.demTilesFor(read.sampleZoom, read.windowX + read.halo, read.windowY + read.halo, read.gridTileSize).toSet()
        return OnlineTiles.demTilesFor(read.sampleZoom, read.windowX, read.windowY, read.windowSize).filter { tile ->
            if (tile in core) return@filter false
            val (tz, tx, ty) = tile
            if (packHas(TileId(tz, tx, ty))) return@filter false
            val key = "$KEY/dem/$tz/$tx/$ty.png"
            !cache.contains(key) && synchronized(inFlight) { key !in inFlight } && !wanted(TileId(tz, tx, ty), plan.z, false)
        }
    }

    /** True when contour tile zoom [z] is the view's own zoom or MapLibre's fallback zoom (true while the view is not known). */
    fun atViewZoom(z: Int): Boolean {
        val v = io.github.jasondorjeshort.dystopo.app.MapViewport.bounds ?: return true
        return viewZoom(v).let { z == it || z == it - PREFETCH_ZOOM_DELTA }
    }

    /** Whether the view needs DEM tile [t] for contour tile zoom [contourZoom] (see [contourWindow]); true while the view is not known. */
    private fun wanted(t: TileId, contourZoom: Int, core: Boolean): Boolean {
        val v = io.github.jasondorjeshort.dystopo.app.MapViewport.bounds ?: return true
        // The view's own contour zoom, and the zoom MapLibre loads four levels up as a fallback while the view's tiles come in.
        if (core && atViewZoom(contourZoom)) return true
        return inView(t)
    }

    /** The view's contour tile zoom (512-px tiles: floor of the map zoom), from the view's width in degrees and the screen's in dp. */
    private fun viewZoom(v: io.github.jasondorjeshort.dystopo.packs.PackBounds): Int {
        val m = context.resources.displayMetrics
        val widthDp = m.widthPixels / m.density
        val span = (v.maxLon - v.minLon).let { if (it <= 0) it + 360 else it }
        return kotlin.math.floor(kotlin.math.ln(360.0 * widthDp / (512.0 * span)) / kotlin.math.ln(2.0) + 0.05).toInt()
    }

    /** True when tile [t] overlaps the map's view (true while the view is not known yet). */
    private fun inView(t: TileId): Boolean {
        val v = io.github.jasondorjeshort.dystopo.app.MapViewport.bounds ?: return true
        val west = WebMercator.xToLon(t.x.toDouble(), t.z)
        val east = WebMercator.xToLon(t.x + 1.0, t.z)
        val north = WebMercator.yToLat(t.y.toDouble(), t.z)
        val south = WebMercator.yToLat(t.y + 1.0, t.z)
        return east > v.minLon && west < v.maxLon && north > v.minLat && south < v.maxLat
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
            val reply = ofmGet(OFM_TILEJSON) // T123: the stall guard, as the tiles
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

    /**
     * T123, debug builds only: the scheme and host OpenFreeMap tiles are fetched from instead of the TileJSON's
     * (`POST /firstrun/direct/osm-base?url=`, [DirectPrefs.osmBase]); the TileJSON itself is still read from
     * OpenFreeMap. A test points it at a host that accepts and never answers to see [ofmGet]'s stall guard.
     */
    @Volatile var osmBase: String? = null

    /**
     * T123: an OpenFreeMap request (a tile or the TileJSON) with a stall guard: no connection within
     * [OSM_CONNECT_MS] or no byte for [OSM_STALL_MS] fails the attempt, and a second attempt is made only while it can
     * still end within [OSM_GUARD_MS] ([ofmRetries]), below MapLibre's own 10 s timeout. It waited 30 s an attempt
     * with two attempts before, holding the loopback request and the map's idle state for a minute
     * (finding 2026-10-16-cold-view-gap). Stalls are counted in `osmStalls` (`GET /firstrun/direct`).
     */
    private fun ofmGet(url: String): SourceReply {
        val started = System.nanoTime()
        var attempts = 0
        while (true) {
            try {
                return http.ofm(url)
            } catch (e: IOException) {
                attempts++
                if (!ofmRetries(attempts, (System.nanoTime() - started) / 1_000_000)) throw e
                Thread.sleep(OSM_RETRY_DELAY_MS)
            }
        }
    }

    private fun osm(path: String): HttpReply {
        val t = parseTile(path, ".pbf") ?: return HttpReply(404, ByteArray(0))
        if (t.z > OnlineTiles.OSM_MAX_ZOOM) return HttpReply(404, ByteArray(0))
        val (template, _) = openFreeMap()
        val url = withOsmBase(template, osmBase).replace("{z}", "${t.z}").replace("{x}", "${t.x}").replace("{y}", "${t.y}")
        // T115: in the timeline too (`o?`, then `o<status>` or `oERR` with its ms): a stalled OpenFreeMap fetch held a cold
        // view not idle for over a minute while the DEM's events looked finished (finding 2026-10-16-cold-view-gap).
        DirectTrace.mark("o? ${t.z}/${t.x}/${t.y}")
        val started = System.nanoTime()
        val reply = try {
            ofmGet(url)
        } catch (e: IOException) {
            DirectTrace.mark("oERR ${t.z}/${t.x}/${t.y} ${(System.nanoTime() - started) / 1_000_000} ms ${e.javaClass.simpleName}")
            throw e
        }
        DirectTrace.mark("o${reply.status} ${t.z}/${t.x}/${t.y} ${(System.nanoTime() - started) / 1_000_000} ms")
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

    // ---- T127: the heap ----

    /** Decoded-block and open-file caches dropped by [trimMemory] so far. */
    @Volatile var memoryTrims = 0
        private set

    /** Renders holding a slot now, for [HeapGuard] (which may ask from its own thread while the slots are being made). */
    private fun runningNowSafe(): Int = runCatching { slots.runningNow }.getOrDefault(0)

    /** T127: drops the synthesis pyramid's decoded tiles (made again from the online cache); the bytes dropped. */
    fun trimMemory(): Long {
        val pyr = pyramid?.let { it.cachedTiles.toLong() * it.tileSize * it.tileSize * 4 } ?: 0L
        dropPyramid()
        memoryTrims++
        return pyr
    }

    /**
     * T138: empties the decoded COG blocks ([DecodedBlocks.clear], up to [decodedCapBytes], an eighth of the heap) in
     * place; the bytes dropped. In place, not a new cache: every GeoTIFF opened, the geoid grids' included (`ProjGeoid`,
     * outside the pool), keeps the cache it was opened with, so a new one beside it left the old blocks alive (T127's heap
     * dump: 23 MB of geoid blocks in a replaced cache). A render running now keeps the blocks it holds; the rest are read
     * again from the source range cache on disk.
     */
    fun trimBlocks(): Long = SourceIo.decodedBlocks?.clear() ?: 0L

    /** T138: empties the geoid offset memo ([GEOID_MEMO_BYTES] at most); the bytes dropped. */
    fun trimGeoidMemo(): Long = io.github.jasondorjeshort.dystopo.sources.elevation.Geoids.clearOffsetCache()

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
            "offlineFailures" to http.offline.get(), "requestWaitMs" to http.waitMs.get(), "rangeStalls" to http.stalls.get(), "requestNetMs" to http.netMs.get(),
            "maxZoom" to maxZoom, "demSynthesized" to demSynthesized.get(), "renderedByZoom" to synchronized(renderedByZoom) { renderedByZoom.mapKeys { "z${it.key}" } }, "demRendered" to n, "demMsAvg" to if (n == 0L) null else demMs.get() / n, "demMsMax" to demMaxMs.get(),
            "osmFetched" to osmFetched.get(), "osmBytes" to osmBytes.get(),
            "osmStalls" to http.osmStalls.get(), "osmGuardMs" to OSM_GUARD_MS, "osmBase" to osmBase, // T123
            "stages" to linkedMapOf( // T88: summed over threads
                "renderWaitMs" to renderWaitMs.get(), "coarseFirstHeldMs" to coarseHeldMs.get(), "renderFetchMs" to renderFetchMs.get(), "renderEncodeMs" to renderEncodeMs.get(),
                "contourDemWaitMs" to contourDemWaitMs.get(), "sources" to io.github.jasondorjeshort.dystopo.sources.elevation.SourceTiming.json(),
            ),
            "timeline" to DirectTrace.json(),
            "contourDem" to linkedMapOf("cached" to contourDemCached.get(), "waited" to contourDemWaited.get(), "rendered" to contourDemRendered.get(), "haloSkipped" to contourHaloSkipped.get(), "notAtViewZoom" to contourStale.get(), "partialTiles" to contourPartial.get()), // T85
            "failures" to failures.get(), "lastError" to lastError,
            "tiffPool" to SourceIo.tiffPool?.let { mapOf("opened" to it.opened, "reused" to it.reused) },
            "decodedBlocks" to SourceIo.decodedBlocks?.let { mapOf("bytes" to it.sizeBytes, "hits" to it.hits, "misses" to it.misses) },
            "sourceCache" to linkedMapOf("dir" to SourceIo.cacheRoot, "capBytes" to sourceCacheCapBytes, "capFromBudget" to (budgetTotalBytes > 0), "trims" to trims),
            // T127: the heap against the renders (HeapGuard), the renders that waited for it, the memory trims of these caches.
            "memory" to HeapGuard.json() + linkedMapOf("heapWaits" to slots.heapWaits.get(), "renderingNow" to slots.runningNow, "committedBytes" to slots.committedNow, "memoryTrims" to memoryTrims,
                "decodedCapBytes" to decodedCapBytes(Runtime.getRuntime().maxMemory())),
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

        /** T85: DEM tiles of a lower zoom read the sources' overview level for twice their cell size ([renderDem]). */
        const val COARSER_OVERVIEW_BELOW = 12

        /** DEM tiles rendered at once: each holds a few decoded 4 MB COG blocks while it renders. */
        const val MAX_TILES_IN_FLIGHT = 10

        /**
         * Requests the fetcher lets through at once (renders are limited separately, OpenFreeMap tiles are quick).
         * T88: 48, from 12: DEM requests hold a permit while they wait on their render, so twelve of them made the
         * OpenFreeMap tiles of the same view wait behind the renders.
         */
        const val FETCHER_IN_FLIGHT = 48

        /** Source requests (range reads, index pages) at once over every source. */
        const val MAX_RANGE_REQUESTS = 16

        /** Open GeoTIFFs kept ([TiffPool]) and the decoded blocks' memory cap ([DecodedBlocks]; an eighth of the heap at most, [decodedCapBytes]). */
        const val TIFF_POOL = 96
        const val DECODED_CAP_BYTES = 96L shl 20

        /** T127: the geoid offset memo's cap on the phone ([io.github.jasondorjeshort.dystopo.sources.elevation.Geoids.offsetCacheBytes]; 32 MB on a server). */
        const val GEOID_MEMO_BYTES = 4L shl 20

        /**
         * The decoded blocks' cap on a heap of [maxHeap] bytes: an eighth of it, at most [DECODED_CAP_BYTES]. T127: a
         * quarter (48 MB of a 192 MB heap) beside ten renders ran a cold view out of heap. Pure, tested.
         */
        fun decodedCapBytes(maxHeap: Long): Long = minOf(DECODED_CAP_BYTES, maxHeap / 8)

        /** How long a map request waits for a tile being rendered before it is told to come back (OnlineTiles.answerWithinMs). */
        const val ANSWER_WITHIN_MS = 9_000L

        /**
         * T85: how long a contour tile waits for its DEM tiles before it answers "not yet" (503). Shorter than
         * [ANSWER_WITHIN_MS]: the tracing and the queue for a generation slot come on top, and a request MapLibre
         * times out (10 s, OkHttp's read timeout in MapLibre's client) is never asked for again. T88: 7 s and 9 s,
         * from 6 s and 8 s. A 503 costs a style reload once the renders are done, which blanks the map for 2 to 5 s
         * on the emulator (MapLibre did not ask for a 503 tile again by itself in any T88 trace), while a cold
         * view's renders end 5 to 9 s after the request; with requests no longer queued behind the server's fixed
         * pools ([io.github.jasondorjeshort.dystopo.app.LocalServer] wait pool) the wait starts when the request
         * arrives, and a release build traced a contour tile in about 0.6 s.
         */
        const val CONTOUR_WAIT_MS = 7_000L

        /** T88: a refresh for tiles that were not ready waits this long after the last render finished (renders finish in bursts). */
        const val REFRESH_SETTLE_MS = 300L

        /** T88: a range read with no byte for this long (or no connection for [RANGE_CONNECT_MS]) is dropped and retried. */
        const val RANGE_STALL_MS = 4_000L
        const val RANGE_CONNECT_MS = 5_000

        /**
         * T123: OpenFreeMap's stall guard ([ofmGet]): an attempt fails with no connection in [OSM_CONNECT_MS] or no byte
         * for [OSM_STALL_MS]; a second attempt, [OSM_RETRY_DELAY_MS] later, only when its stall would still end within
         * [OSM_GUARD_MS] of the first (MapLibre's client gives up at 10 s). A host that accepts and never answers, or a
         * dropped connection, gives up after about 8.5 s; a tile answers in well under a second when the link is alive.
         */
        const val OSM_STALL_MS = 4_000L
        const val OSM_CONNECT_MS = 4_000
        const val OSM_ATTEMPTS = 2
        const val OSM_RETRY_DELAY_MS = 500L
        const val OSM_GUARD_MS = 9_000L

        /** T123: whether [ofmGet] makes another attempt after [attempts] failed ones, [elapsedMs] after the first began. Pure, tested. */
        fun ofmRetries(attempts: Int, elapsedMs: Long): Boolean =
            attempts < OSM_ATTEMPTS && elapsedMs + OSM_RETRY_DELAY_MS + OSM_STALL_MS <= OSM_GUARD_MS

        /**
         * T123: [template] (OpenFreeMap's tile URL) with its scheme and host replaced by [base] (`http://10.0.2.2:8753`, a
         * trailing slash or path ignored); [template] itself when [base] is null or blank. Pure, tested.
         */
        fun withOsmBase(template: String, base: String?): String {
            if (base.isNullOrBlank()) return template
            val origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+").find(base.trim())?.value ?: throw IllegalArgumentException("not an http(s) URL: $base")
            val rest = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+").replace(template, "")
            return origin + rest
        }

        /**
         * T88: how long a render at the view's zoom waits for coarse renders in flight, and for them to be asked for.
         * 1.5 s: the coarse reads (a few 0.4 MB overview blocks) get the link to themselves for their first part; with
         * 3 s the view's own renders started late on a fast link, and without any hold the coarse contours came no
         * earlier than the fine ones on a slow one (measurements in docs/PLANNING.md, T88).
         */
        const val COARSE_FIRST_MS = 1_500L
        const val COARSE_GRACE_MS = 250L

        /** MapLibre Native's default prefetch zoom delta: with the view at zoom z it also loads tiles of zoom z - 4. */
        const val PREFETCH_ZOOM_DELTA = 4

        /** T83: shown only when the user turned mobile data off for the online map (the default is on). */
        val MOBILE_DATA_OFF = "Online map paused: mobile data is turned off for it in ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SOURCES}."

        const val DEFAULT_SESSION_MB = 300L
        const val DEFAULT_SOURCE_CACHE_MB = 512L

        /** T127: the one cache budget's total in bytes (0 until `CacheBudget` sets it); [sourceCacheCapBytes] follows it. */
        @Volatile var budgetTotalBytes = 0L
        private const val TRIM_EVERY_BYTES = 32L shl 20

        /** As the live server: the overview level, halo and blend band of a zoom come from this latitude. */
        const val REFERENCE_LAT = 45.0

        const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
        val OSM_IDS = listOf("osm", "openmaptiles", "openfreemap")

        fun gunzip(bytes: ByteArray): ByteArray = OnlineFetcher.gunzipIfNeeded(bytes)

        /**
         * T85: the contour plan options for contour tile z/x/y made over [online]'s DEM. When the phone renders the
         * terrain itself and no pack's DEM overlaps the tile, it reads the DEM at the zoom the hillshade is rendered
         * at (the tile's own DEM zoom, at most [maxZoom]) and averages it down to its grid
         * ([ContourTileOptions.sampleAtDemZoom]): the coarser zooms the plans sampled before shared no COG blocks
         * with the hillshade's tiles. Packs and a configured server keep [base] unchanged. T88: a tile a DEM pack's
         * bounds overlap reads the same way, taking the pack's tiles where the pack has them ([contourPyramid]); it
         * was made as before T85, with whole halo rings of coarse DEM tiles, and on a fresh emulator the device
         * tour's z8 view (24 such renders of 40 to 75 s each in a debug build) never went idle within 30 s.
         * T98: with a configured server, a tile a pack's bounds overlap reads at least at the pack's first zoom
         * ([OnlineTiles.serverContourOptions]); the direct path above already reads at or below the pack's zooms.
         */
        fun contourOptions(base: ContourTileOptions, online: OnlineTiles?, z: Int, x: Int, y: Int): ContourTileOptions {
            if (online == null) return base
            if (online.fetcher.server != SERVER) return online.serverContourOptions(base, z, x, y)
            val d = OnlineService.get().direct
            val options = base.copy(sampleAtDemZoom = d.maxZoom)
            return if (ContourTiles.plan(z, x, y, options).readZoom in DEM_MIN_ZOOM..d.maxZoom) options else base
        }

        /**
         * T88: the tiles of zoom [k] that [DemPyramid.window] reads to synthesize deeper tile [t] (bilinear from zoom
         * [k], as [synthesize] does): the ancestor and, where the edge samples reach past it, the neighbours on that side.
         */
        fun synthesisSources(t: TileId, k: Int): List<TileId> {
            val dz = t.z - k
            if (dz <= 0) return listOf(t)
            val scale = (1 shl dz).toDouble()
            val maxPx = (256L shl k) - 1
            fun range(tile: Int): IntRange {
                val lo = floor((tile * 256L + 0.5) / scale - 0.5).toLong().coerceIn(0L, maxPx)
                val hi = (floor((tile * 256L + 255.5) / scale - 0.5).toLong() + 1).coerceIn(0L, maxPx)
                return (lo / 256).toInt()..(hi / 256).toInt()
            }
            return range(t.y).flatMap { y -> range(t.x).map { x -> TileId(k, x, y) } }
        }

        /** T88: a DEM tile of the window [contourWindow] resolved on this thread failed (offline, ...): the tile must not be kept. */
        private val windowIncomplete = ThreadLocal<Boolean>()

        /** T88: the halo tiles the contour tile made on this thread left out ([haloSkipped]); null while not collecting. */
        private val skippedHalo = ThreadLocal<MutableList<Triple<Int, Int, Int>>?>()

        /** T88: collects, on this thread, the halo tiles [contourWindow] leaves out while [block] makes a contour tile. */
        fun <T> collectSkippedHalo(block: () -> T): Pair<T, List<Triple<Int, Int, Int>>> {
            val list = ArrayList<Triple<Int, Int, Int>>()
            skippedHalo.set(list)
            try {
                return block() to list.distinct()
            } finally {
                skippedHalo.set(null)
            }
        }

        /** T88: what [haloSkipped] says for contour tile z/x/y of [online], or empty when it is not made from the direct sources. */
        fun haloSkippedFor(online: OnlineTiles, base: ContourTileOptions, z: Int, x: Int, y: Int): List<Triple<Int, Int, Int>> {
            val options = contourOptions(base, online, z, x, y)
            if (online.fetcher.server != SERVER || options.sampleAtDemZoom == null) return emptyList()
            return OnlineService.get().direct.haloSkipped(ContourTiles.plan(z, x, y, options)) { t -> online.packCoversDem(t.z, t.x, t.y) }
        }

        /**
         * T85: the DEM window [OnlineTiles] fetches before it generates contour tile z/x/y: the plan's
         * [ContourTilePlan.readWindow]; none when [contourPyramid] reads the direct sources (it decides per tile).
         */
        fun prefetchPlan(online: OnlineTiles, base: ContourTileOptions, z: Int, x: Int, y: Int): ContourTilePlan {
            val options = contourOptions(base, online, z, x, y)
            val read = ContourTiles.plan(z, x, y, options).readWindow
            // One cell: OnlineTiles prefetches nothing for a single tile; [contourPyramid] fetches and waits itself.
            // T98: a server's window read at a pack's first zoom is prefetched like any other.
            return if (online.fetcher.server != SERVER || options.sampleAtDemZoom == null) read else read.copy(gridTileSize = 1, halo = 0)
        }

        /**
         * T85: the pyramid contour tile z/x/y of [online] samples with direct sources ([contourOptions] chose the
         * hillshade's zoom): the tiles of its read window resolved at once ([DirectSources.contourWindow]); ancestors
         * only from the online cache. Null otherwise (the plain online pyramid, as before).
         */
        fun contourPyramid(online: OnlineTiles, options: ContourTileOptions, z: Int, x: Int, y: Int, lenient: Boolean = false): io.github.jasondorjeshort.dystopo.tiles.DemPyramid? {
            if (online.fetcher.server != SERVER || options.sampleAtDemZoom == null) return null
            val d = OnlineService.get().direct
            val plan = ContourTiles.plan(z, x, y, options)
            // Only a tile that waited too long counts toward the reload after rendering (OnlineService), so MapLibre
            // asks for it again; one refused because the view moved on would make every reload refuse it again.
            windowIncomplete.set(false)
            val window = d.contourWindow(plan, lenient, packHas = { t -> online.packCoversDem(t.z, t.x, t.y) }) { online.notReady.incrementAndGet() }
            if (windowIncomplete.get() == true) online.noteIncomplete() // made, served, not kept
            return io.github.jasondorjeshort.dystopo.tiles.DemPyramid(DEM_MIN_ZOOM, d.maxZoom, io.github.jasondorjeshort.dystopo.app.TileService.demDecoder(), cacheTiles = 16) { tz, tx, ty ->
                // T88: a DEM pack's tile where the pack has it (a contour tile across the pack's edge joins its contours).
                window[TileId(tz, tx, ty)] ?: online.packDem(tz, tx, ty) ?: if (tz < plan.readZoom) d.cache.get("$KEY/dem/$tz/$tx/$ty.png") else null
            }
        }

        /**
         * T85: contour tile z/x/y when the view does not need it at its own zoom (neither the view's zoom nor
         * MapLibre's fallback zoom: a tile asked for while the camera moved, or a parent MapLibre keeps): made by
         * [generate] from the DEM tiles already made or in view, the rest NaN, and not kept in the online cache,
         * so a later view at that zoom makes it whole. Never a lasting "not ready", so the map can finish loading.
         * Null when the tile is one the view needs (or not a direct-sources tile): the caller takes the usual path.
         */
        fun staleContour(online: OnlineTiles, base: ContourTileOptions, z: Int, x: Int, y: Int, generate: (io.github.jasondorjeshort.dystopo.tiles.DemPyramid, ContourTileOptions) -> ByteArray): ByteArray? {
            if (online.fetcher.server != SERVER) return null // T98: a server's options can set sampleAtDemZoom too
            val options = contourOptions(base, online, z, x, y)
            if (options.sampleAtDemZoom == null || online.packCoversContour(z, x, y)) return null
            val d = OnlineService.get().direct
            if (d.atViewZoom(z)) return null
            val pyramid = contourPyramid(online, options, z, x, y, lenient = true) ?: return null
            d.contourPartial.incrementAndGet()
            return generate(pyramid, options)
        }
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

    /** T88: range reads that stalled ([DirectSources.RANGE_STALL_MS] with no byte) and were given up for a retry ([SourceIo] retries I/O errors). */
    val stalls = AtomicLong()

    /**
     * T88: a range read fails after [DirectSources.RANGE_STALL_MS] without a byte (the socket's read timeout, which is per
     * read, not for the whole body) and is retried at once. On the emulator one read in five sat for 5 to 10 s while the
     * same size took 0.4 s on another connection; waiting two minutes for those held whole renders up.
     */
    override fun range(url: String, offset: Long, length: Int): SourceReply = try {
        send(url, DirectSources.RANGE_STALL_MS, DirectSources.RANGE_CONNECT_MS) { it.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}") }
    } catch (e: java.net.SocketTimeoutException) {
        stalls.incrementAndGet()
        throw e
    }

    /** T123: OpenFreeMap requests that got no connection or no byte within the stall guard ([DirectSources.OSM_STALL_MS]). */
    val osmStalls = AtomicLong()

    /** T123: one attempt of an OpenFreeMap request under the stall guard ([DirectSources.ofmRetries] decides on another). */
    fun ofm(url: String): SourceReply = try {
        send(url, DirectSources.OSM_STALL_MS, DirectSources.OSM_CONNECT_MS) {}
    } catch (e: java.net.SocketTimeoutException) {
        osmStalls.incrementAndGet()
        throw e
    }

    override fun get(url: String, timeoutMs: Long): SourceReply = send(url, timeoutMs) {}

    private fun send(start: String, timeoutMs: Long, connectTimeoutMs: Int = 15_000, prepare: (HttpURLConnection) -> Unit): SourceReply {
        if (bytes.get() >= owner.sessionCapBytes) {
            owner.capReachedAt = System.currentTimeMillis()
            throw SourceCapReached(owner.capMessage())
        }
        if (owner.blockedOnMobile()) {
            owner.blockedOnMobileAt = System.currentTimeMillis()
            throw SourceOffline(DirectSources.MOBILE_DATA_OFF, null)
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
                var reused = false
                val conn = CleartextPolicy.open(url)
                conn.connectTimeout = connectTimeoutMs
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
                    // HttpURLConnection asks for gzip and undoes it itself. T88: a body read to its end leaves the
                    // connection to the pool (keep-alive): the next range read to the same host skips the TCP and
                    // TLS handshakes and slow start; disconnect() closed it after every request.
                    reused = true
                    return SourceReply(status, body)
                } finally {
                    if (!reused) conn.disconnect()
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

/**
 * T88: the session's timeline in milliseconds from its first event (`GET /firstrun/direct` `timeline`, and logcat tag
 * `DystopoDirect` as `trace <ms> <event>` for release builds): DEM renders queued (`dem?`), started (`dem>`) and done
 * (`dem. <ms>`), cold source reads (`read <file> <KB> <ms>`), the map's DEM tiles asked for (`d?`) and answered (`d200`,
 * `d503`), contour tiles asked for (`c?`) and answered (`c200` with bytes, `c200p` partial, `c503`), and the style
 * reloads for tiles answered 503 (`refresh`). The first [CAP] events, which is the cold first view the 15 s target is about.
 *
 * T106 (the privacy rule: nothing reveals where a user is without need): a release build writes the lines [redact]ed,
 * with the zoom and the timings but no tile column or row and no source file name (a 3DEP cell names a one-degree
 * square), so the release timeline still says where a cold view's seconds go while logcat, which `adb logcat` and a bug
 * report read, says nothing of where the phone looked. Debug builds keep the whole lines, in logcat and in the route.
 */
object DirectTrace {
    const val CAP = 800
    private val events = ArrayList<String>()
    @Volatile private var start = 0L

    fun mark(event: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val line = synchronized(events) {
            if (start == 0L) start = now
            if (events.size >= CAP) return
            "${now - start} $event".also { events += it }
        }
        // Release builds have no debug routes: logcat carries the lines, without the places (T106).
        Log.i(DirectSources.TAG, "trace " + if (io.github.jasondorjeshort.dystopo.app.BuildConfig.DEBUG) line else redact(line))
    }

    fun json(): Map<String, Any?> = synchronized(events) { mapOf("startElapsedMs" to start, "events" to events.size, "lines" to events.toList()) }

    private val tile = Regex("""(?<![\w/])(\d{1,2})/\d+/\d+(?![\w/])""")
    private val read = Regex("""(?<!\w)read \S+""")

    /** T106: [line] without places: `12/661/1442` becomes `12/-/-`, `read <file>` becomes `read -`. Pure, tested. */
    fun redact(line: String): String = read.replace(tile.replace(line) { "${it.groupValues[1]}/-/-" }, "read -")
}

internal fun logDirect(message: String) = Log.i(DirectSources.TAG, message)
