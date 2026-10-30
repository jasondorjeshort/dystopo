package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.Glyphs
import io.github.jasondorjeshort.dystopo.app.SpriteFiles
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sources.SourceKind
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.style.AssetManifest
import io.github.jasondorjeshort.dystopo.style.Sprites
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.floor
import io.github.jasondorjeshort.dystopo.app.TileService
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Online mode, process-wide (T38): the server from [AppSettings.server], one [OnlineFetcher] for it over
 * a size-capped [TileCache] in the app's cache dir (shared by every server; keys start with the
 * server), the bookkeeping behind the map's online indicator, and the "Download this region" job
 * ([RegionDownload]). [io.github.jasondorjeshort.dystopo.app.TileService] asks [tilesFor] for its
 * online half when it opens the packs.
 *
 * A background check (every [PROBE_ONLINE_MS], or [PROBE_OFFLINE_MS] while offline) asks the server
 * for `/live/info`, so the indicator notices a lost or returned network without tile traffic. When the
 * server comes back after tiles failed, the style reloads so MapLibre asks again for the tiles it was
 * left without.
 */
class OnlineService private constructor(private val app: DystopoApp) {
    val cache = TileCache(File(app.cacheDir, "online/tiles"), AppSettings.onlineCacheMb * 1024 * 1024) // T41: the cap is a setting

    @Volatile var fetcher: OnlineFetcher? = null
        private set

    /** T78: the peer phones in use as sources (`peer://<host:port>`, [io.github.jasondorjeshort.dystopo.app.share.PeerSources]); declared before the init block that configures the fetcher. */
    @Volatile var peers: List<String> = emptyList()
        private set

    val region = RegionDownload(this, app)

    /**
     * T80: the phone's own readers of the elevation sources and OpenFreeMap, used when no server is set
     * ([DirectSources]); made on first use; declared before the init block that may use it (it points the shared readers' cache and HTTP at the app).
     */
    val direct: DirectSources by lazy { DirectSources(app, app.cacheDir, cache).also { it.sessionCapBytes = DirectPrefs.sessionMb(app) * 1024 * 1024; it.maxZoom = DirectPrefs.maxZoom(app); it.onMobileData = DirectPrefs.onMobileData(app); it.osmBase = DirectPrefs.osmBase(app); it.onSourcesAnswered = ::sourcesAnswered } } // T123: osmBase, debug only; T155: the stall recovery's wake

    /** `/live/info` of the server (zooms, attribution), from the last successful check. */
    @Volatile var info: Map<String, Any?>? = null
        private set

    val contourCacheHits = AtomicLong()
    val contoursMade = AtomicLong()
    val contourIncomplete = AtomicLong()
    /** T106: contour tiles' older halo variants deleted when the tile was made again (`<y>.halo-...pbf`, [TileCache.clearVariants]). */
    val contourHaloVariantsDropped = AtomicLong()
    /** T74: online shade tiles (hillshade, slope) made, answered from the disk cache, and made while a DEM tile was missing (not kept). */
    val shadeCacheHits = AtomicLong()
    val shadeMade = AtomicLong()
    val shadeIncomplete = AtomicLong()
    /** T74: style reloads because a source's content version changed. */
    val versionReloads = AtomicLong()
    /** Tile requests answered 404 because the server could not be reached. */
    val unavailableServed = AtomicLong()
    @Volatile private var refreshedAtUnavailable = 0L
    @Volatile private var refreshedGeneration = 0
    val refreshes = AtomicLong()

    /** T173: the live readout's weather station requests ([io.github.jasondorjeshort.dystopo.app.readout.PressureSource]). */
    val stationFetches = AtomicLong()
    val stationFetchFailures = AtomicLong()
    val stationBytes = AtomicLong()

    private class RouteCounters {
        val hits = AtomicLong()
        val cached = AtomicLong()
        val missing = AtomicLong()
        val unavailable = AtomicLong()
        fun json() = mapOf("served" to hits.get(), "fromCache" to cached.get(), "missing" to missing.get(), "unavailable" to unavailable.get())
    }

    private val routes = mapOf("dem" to RouteCounters(), "osm" to RouteCounters(), "glyphs" to RouteCounters())

    /** Where recently served online DEM tiles came from (network or cache), for [OnlineTiles.viewSource]. */
    private val demSources = object : LinkedHashMap<Long, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 4096
    }

    private val prefetchPool = Executors.newFixedThreadPool(6) { r -> Thread(r, "online-prefetch").apply { isDaemon = true } }
    private val probeThread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "online-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    init {
        configure(AppSettings.server)
        probeThread.schedule(::probeLoop, 1, TimeUnit.SECONDS)
        probeThread.scheduleWithFixedDelay(::refreshWhenRendered, 2, 1, TimeUnit.SECONDS) // T80
        probeThread.scheduleWithFixedDelay(::retryStalled, 2, 2, TimeUnit.SECONDS) // T138; T155: and DEM tiles
        app.map.onCameraIdle = ::onViewChanged // T41
        app.map.onMapIdle = ::onMapIdle // T110
    }


    /**
     * T80: the two user settings of the phone's own source reading (`directSessionMb`, `directOnMobileData`, from
     * `POST /settings` or Settings › Sources): stored and applied at once. Returns an error message or null.
     */
    fun setDirect(sessionMb: Long?, onMobileData: Boolean?): String? {
        if (sessionMb != null && sessionMb !in DirectPrefs.SESSION_MB_RANGE) return "directSessionMb must be ${DirectPrefs.SESSION_MB_RANGE.first}..${DirectPrefs.SESSION_MB_RANGE.last} (got $sessionMb)"
        DirectPrefs.set(app, sessionMb = sessionMb)
        onMobileData?.let { DirectPrefs.setOnMobileData(app, it) }
        if (isDirect) {
            sessionMb?.let { direct.sessionCapBytes = it * 1024 * 1024 }
            onMobileData?.let { direct.onMobileData = it }
            fetcher?.retryNow() // a raised limit or allowed mobile data takes effect for the next tile
        }
        return null
    }

    /** True when tiles no pack covers come from the phone's own readers rather than a server. */
    val isDirect: Boolean get() = fetcher?.server == DirectSources.SERVER

    /**
     * Uses [server] from now on; "" means the phone reads the sources itself (T80, unless turned off with
     * `POST /firstrun/direct?on=0`, then offline only). The caller reloads the tiles and style.
     */
    @Synchronized
    fun configure(server: String) {
        val target = server.ifEmpty { if (DirectPrefs.enabled(app)) DirectSources.SERVER else "" }
        val want = listOf(target).filter { it.isNotEmpty() } + peers
        if ((fetcher?.sources ?: emptyList<String>()) == want) return
        val primary = fetcher?.server
        // T78: peer phones (`peer://`) join whichever source is configured; their requests go over the sealed LAN link.
        val peerGet = { d: HttpGet? -> io.github.jasondorjeshort.dystopo.app.share.PeerHttpGet(d) }
        fetcher = when {
            want.isEmpty() -> null
            target == DirectSources.SERVER -> OnlineFetcher(want, cache, http = if (peers.isEmpty()) direct else peerGet(direct), maxInFlight = DirectSources.FETCHER_IN_FLIGHT)
            // T74: a list of sources (the server, then T78's peer phones), content versions kept across restarts.
            target.isNotEmpty() -> OnlineFetcher(want, cache, http = if (peers.isEmpty()) UrlConnectionGet() else peerGet(UrlConnectionGet()), versionFile = File(app.cacheDir, VERSIONS_FILE))
            else -> OnlineFetcher(want, cache, http = peerGet(null)) // T78: no server and no direct reading: the peers alone
        }?.also { f -> f.onVersionChange = { onVersionChange(f, it) } }
        if (fetcher?.server != primary) {
            info = null
            assetsVersion = null
        }
        Log.i(TAG, "server: ${target.ifEmpty { "(none)" }}${if (peers.isEmpty()) "" else ", peers: $peers"}")
    }

    /** T78: uses [urls] as the peer sources from now on (rebuilds the fetcher, reloads the map when it changed). */
    fun setPeers(urls: List<String>) {
        val sorted = urls.distinct().sorted()
        synchronized(this) {
            if (sorted == peers) return
            peers = sorted
            configure(AppSettings.server)
        }
        runCatching { app.reload() }
    }

    /** The online half of a tile service over [packs], or null without a server. */
    fun tilesFor(packs: PackSet, contourVersion: String): OnlineTiles? =
        fetcher?.let { OnlineTiles(packs, it, this, TileService.demDecoder(), contourVersion, answerWithinMs = if (it.server == DirectSources.SERVER) DirectSources.ANSWER_WITHIN_MS else null) } // T42: decoder choice

    fun prefetch(block: () -> Fetch): CompletableFuture<Fetch> = CompletableFuture.supplyAsync(block, prefetchPool)

    fun served(route: String, f: Fetch) {
        val c = routes[route] ?: return
        when (f) {
            is Fetch.Hit -> { c.hits.incrementAndGet(); if (f.cached) c.cached.incrementAndGet() }
            Fetch.Missing -> c.missing.incrementAndGet()
            is Fetch.Unavailable -> { c.unavailable.incrementAndGet(); unavailableServed.incrementAndGet() }
        }
    }

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    fun recordDem(z: Int, x: Int, y: Int, source: String) = synchronized(demSources) { demSources[key(z, x, y)] = source }

    fun demSource(z: Int, x: Int, y: Int): String? = synchronized(demSources) { demSources[key(z, x, y)] }

    /** Indicator state: `none` (no server), `online`, `offline` or `unknown` (not checked yet). */
    fun state(): String = fetcher?.state ?: "none"

    private fun probeLoop() {
        var next = PROBE_ONLINE_MS
        try {
            val f = fetcher
            if (f != null) {
                val stale = f.lastOkAt == 0L || System.currentTimeMillis() - f.lastOkAt > PROBE_ONLINE_MS
                if (f.state != "online" || stale || info == null) {
                    f.probe()?.let { body -> info = runCatching { MiniJson.parseObject(body.decodeToString()) }.getOrNull() }
                }
                if (f.state != "online") next = PROBE_OFFLINE_MS
                refreshAfterReconnect(f)
                if (f.state == "online" && f.server != DirectSources.SERVER) f.checkVersions() // T74: a changed server is seen without tile traffic
                if (f.state == "online") installAssetsOnce(f) // T41: glyphs and sprites when the device has none; T74: and when the server's changed
            }
        } catch (e: Throwable) {
            Log.w(TAG, "probe failed", e)
        } finally {
            probeThread.schedule(::probeLoop, next, TimeUnit.MILLISECONDS)
        }
    }

    @Volatile private var reloadPending = false

    /**
     * T74: a source's content version changed (seen by `/version` or a tile's header): its old tiles are gone from
     * the cache; reload the style (its tile URLs carry the versions, so MapLibre asks again) once, shortly after,
     * and fetch the style assets again when those changed.
     */
    private fun onVersionChange(f: OnlineFetcher, source: String) {
        Log.i(TAG, "content version of $source changed: ${f.json()["versions"]}")
        if (reloadPending) return
        reloadPending = true
        probeThread.schedule({
            reloadPending = false
            try {
                if (fetcher === f) {
                    versionReloads.incrementAndGet()
                    installAssetsOnce(f)
                    app.showStyle(app.tiles) // T144: posted, not awaited
                }
            } catch (e: Throwable) {
                Log.w(TAG, "reload after a version change failed", e)
            }
        }, 300, TimeUnit.MILLISECONDS)
    }

    @Volatile private var notReadySeen = 0L
    val renderRefreshes = AtomicLong()
    /** T155: reloads after rendering left out because every render waited on failed ([DirectSources.onlyFailuresAwaited]). */
    val renderRefreshesSkipped = AtomicLong()

    /**
     * T80: tiles answered "not ready" (503, [OnlineTiles.answerWithinMs]) are not asked for again by MapLibre until
     * the map moves; once the phone has finished rendering (nothing in flight for a second), reload the style so
     * they load, now from the cache.
     */
    private fun refreshWhenRendered() {
        try {
            if (!isDirect) return
            val online = app.tiles.online ?: return
            val n = online.notReady.get()
            if (n == notReadySeen) return
            if (!direct.readyForRefresh()) return // T88: once the renders that were waited on are done, not every render
            notReadySeen = n
            if (direct.onlyFailuresAwaited()) { // T155: every awaited render failed (a stalled source): no reload, the stall recovery draws them
                renderRefreshesSkipped.incrementAndGet()
                DirectTrace.mark("refresh skipped: every awaited render failed")
                return
            }
            renderRefreshes.incrementAndGet()
            app.showStyle(app.tiles) // T144: posted, not awaited
        } catch (e: Throwable) {
            Log.w(TAG, "refresh after rendering failed", e)
        }
    }

    // ---- T138: vector tiles left empty after a failed fetch ----

    /** Vector tiles answered empty because their fetch failed, fetched again later ([retryStalledOsm]). */
    val stalledOsm = StalledOsmTiles()

    /** Times the map's vector source was replaced to draw recovered tiles ([OsmSourceRefresh]; in the style's tile URL version). */
    val osmSourceRefreshes = AtomicLong()
    @Volatile private var lastOsmRefresh: Map<String, Any?>? = null
    /** T144: recoveries coalesced into one source refresh per settle window; a refresh skipped mid-style-load stays wanted. */
    val osmRefresh = OsmRefreshCoalescer()
    @Volatile private var styleLoadsSeen = -1L
    private val osmRetrying = java.util.concurrent.atomic.AtomicBoolean()

    /** [OnlineTiles.osmTile]: vector tile [path] (`z/x/y.pbf`) was answered empty because its fetch failed. */
    fun osmFailed(path: String) {
        stalledOsm.failed(path, System.currentTimeMillis())
        if (isDirect) DirectTrace.mark("oStall $path")
    }

    /** T155: when the last look of [retryStalled] was (wall clock ms); a style load seen now came after it. */
    @Volatile private var lastStalledLookAt = 0L

    /**
     * T138, every 2 s on the probe thread: a style load since the last look clears [stalledOsm] (MapLibre asked for every
     * tile again); the due tiles are fetched again on the prefetch pool, and once one came back the vector source is
     * replaced ([OsmSourceRefresh]) so the tile is drawn without a style reload. T144: the recoveries are coalesced
     * ([osmRefresh]: one refresh per settle window while they trickle in), and a refresh the map skipped because its style
     * was loading is asked for again. T155: the same for DEM tiles ([stalledDem], [retryStalledDem]); a style load drops
     * only the entries recorded before the previous look, which certainly came before it (the load came between the two
     * looks; T138 dropped every entry up to the look that saw it, so a tile asked for again after the load and failed
     * again was dropped too, and a DEM tile, asked for again within a second of a reload, often was).
     */
    private fun retryStalled() {
        try {
            val now = System.currentTimeMillis()
            val loads = app.map.styleLoads.get()
            if (loads != styleLoadsSeen) {
                if (styleLoadsSeen >= 0) {
                    stalledOsm.styleLoaded(lastStalledLookAt)
                    stalledDem.styleLoaded(lastStalledLookAt)
                }
                styleLoadsSeen = loads
            }
            lastStalledLookAt = now
            if (osmRefresh.claim(now)) refreshOsmSource()
            if (demRefresh.claim(now)) refreshDemSource()
            val f = fetcher ?: return
            retryStalledDem(f, now)
            val due = stalledOsm.due(now)
            if (due.isEmpty() || !osmRetrying.compareAndSet(false, true)) return
            prefetchPool.execute {
                try {
                    var back = 0
                    for (path in due) {
                        val ok = f.get("osm", path, OnlineFetcher::gunzipIfNeeded) is Fetch.Hit
                        stalledOsm.result(path, ok, System.currentTimeMillis())
                        if (ok) back++
                    }
                    if (back > 0) {
                        if (isDirect) DirectTrace.mark("oBack $back of ${due.size}")
                        osmRefresh.recovered(System.currentTimeMillis(), back)
                    }
                } finally {
                    osmRetrying.set(false)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "retrying stalled vector tiles failed", e)
        }
    }

    /** T144: the style JSON is made here; the swap is posted to the UI thread, not awaited. A swap skipped mid-style-load is wanted again. */
    private fun refreshOsmSource() {
        osmSourceRefreshes.incrementAndGet() // first: the new style URL carries it, so MapLibre takes it as a new tile set
        val json = app.tiles.style()
        main.post {
            val result = runCatching { app.map.map?.style?.let { OsmSourceRefresh.refresh(it, json) } ?: mapOf("skipped" to "no map") }
                .getOrElse { mapOf("skipped" to "error: ${it.message}") }
            if (result["skipped"] == OsmSourceRefresh.STYLE_LOADING) osmRefresh.skipped()
            lastOsmRefresh = result + ("at" to System.currentTimeMillis())
            if (isDirect) DirectTrace.mark("osmRefresh ${result["layers"] ?: result["skipped"]}")
            Log.i(TAG, "vector source refreshed for recovered tiles: $result")
        }
    }

    // ---- T155: DEM tiles left empty after a failed fetch ----

    /**
     * DEM tiles answered 404 because their fetch failed (the map's own requests, [OnlineTiles.demTile]) or missing from a
     * shade or contour tile made without them ([OnlineTiles] and [DirectSources.contourWindow]): fetched again later
     * ([retryStalledDem]), and the elevation source and its derived sources replaced once they come ([DemSourceRefresh]).
     */
    val stalledDem = StalledOsmTiles()

    /** Times the elevation source was replaced to draw recovered DEM tiles (in the style's DEM, contour and shade URLs, `-d<n>`). */
    val demSourceRefreshes = AtomicLong()
    @Volatile private var lastDemRefresh: Map<String, Any?>? = null
    /** One refresh per settle window; quiet gap 0, since a retry reports its recoveries as one batch ([OsmRefreshCoalescer]). */
    val demRefresh = OsmRefreshCoalescer(quietMs = 0L)
    private val demRetrying = java.util.concurrent.atomic.AtomicBoolean()
    /** Tries of the oldest recorded tile while none was due (direct mode), and when the next may start. */
    val demCanaries = AtomicLong()
    @Volatile private var nextCanaryAt = 0L
    @Volatile private var canaryGapMs = CANARY_MIN_GAP_MS
    /** Source reads that answered after a stall ([DirectSources.onSourcesAnswered]); halo variants deleted with a recovery. */
    val demSourcesAnswered = AtomicLong()
    val demHaloVariantsDropped = AtomicLong()

    /** A DEM tile [path] (`z/x/y.png`) was answered 404, or left out of a derived tile, because its fetch failed. */
    fun demFailed(path: String) {
        if (stalledDem.failed(path, System.currentTimeMillis()) && isDirect) DirectTrace.mark("dStall $path")
    }

    /** [DirectSources.onSourcesAnswered]: a source read answered after a stall; every recorded tile is due now. */
    private fun sourcesAnswered() {
        demSourcesAnswered.incrementAndGet()
        if (stalledDem.wake(System.currentTimeMillis()) == 0) return
        if (isDirect) {
            DirectTrace.mark("dAnswered")
            direct.forgetFailures() // the map's requests in the meantime render again rather than fail from the memo
        }
        fetcher?.retryNow() // and the fetcher's 30 s per-tile failure memo, as for a raised limit
        nextCanaryAt = 0L
        canaryGapMs = CANARY_MIN_GAP_MS
        probeThread.execute(::retryStalled)
    }

    /**
     * The due DEM tiles fetched again, all at once (a DEM render waits on range reads; one after another a dead host's
     * 18 s per tile would add up). In direct mode, while none is due the oldest is tried anyway, one at a time (a canary:
     * at once again after a try that stalled, which paced itself; doubling to [CANARY_MAX_GAP_MS] after one that failed
     * quickly, offline or on a broken file), so a source that answers again is seen within seconds rather than after the
     * backoff; a canary's failure does not move the tile's own schedule. With a server the tiles follow the schedule
     * (the online fetcher's 30 s failure memo).
     */
    private fun retryStalledDem(f: OnlineFetcher, now: Long) {
        if (stalledDem.size == 0 || !demRetrying.compareAndSet(false, true)) return
        val direct = f.server == DirectSources.SERVER
        val due = stalledDem.due(now)
        val canary = due.isEmpty()
        val batch = when {
            !canary -> due
            direct && now >= nextCanaryAt -> listOfNotNull(stalledDem.oldest())
            else -> emptyList()
        }
        if (batch.isEmpty()) {
            demRetrying.set(false)
            return
        }
        if (canary) demCanaries.incrementAndGet()
        runDemBatch(f, batch, canary, emptyList(), now)
    }

    private fun runDemBatch(f: OnlineFetcher, batch: List<String>, canary: Boolean, carried: List<String>, startedAt: Long) {
        val direct = f.server == DirectSources.SERVER
        val jobs = batch.map { path ->
            val get = { path to runCatching { if (direct) this.direct.retryDem(path) else f.get("dem", path) is Fetch.Hit }.getOrDefault(false) }
            if (direct) this.direct.request(get) else CompletableFuture.supplyAsync(get, prefetchPool)
        }
        CompletableFuture.allOf(*jobs.toTypedArray()).whenComplete { _, _ ->
            var chained = false
            try {
                val t = System.currentTimeMillis()
                val back = ArrayList(carried)
                for (job in jobs) {
                    val (path, ok) = job.join()
                    if (canary && !ok) continue
                    stalledDem.result(path, ok, t)
                    if (ok) back += path
                }
                if (canary) {
                    // A quick failure while a host's reads stall is the stall breaker's (its probe is the real try): no backoff then.
                    val quick = back.isEmpty() && t - startedAt < DirectSources.RANGE_STALL_MS && this.direct.http.breaker.openHosts().isEmpty()
                    canaryGapMs = if (quick) minOf(canaryGapMs * 2, CANARY_MAX_GAP_MS) else CANARY_MIN_GAP_MS
                    nextCanaryAt = t + canaryGapMs
                }
                if (back.size > carried.size && stalledDem.wake(t) > 0) {
                    // The source answers again: every tile still recorded is fetched now, in this same run, and the
                    // refresh waits for them (one refresh for the view, not one per tile).
                    if (direct) this.direct.forgetFailures()
                    val rest = stalledDem.due(t)
                    if (rest.isNotEmpty()) {
                        chained = true
                        runDemBatch(f, rest, false, back, t)
                        return@whenComplete
                    }
                }
                if (back.isNotEmpty()) demBack(back, t)
            } catch (e: Throwable) {
                Log.w(TAG, "retrying stalled DEM tiles failed", e)
            } finally {
                if (!chained) demRetrying.set(false)
            }
        }
    }

    /** DEM tiles [back] came again at [t]: what remembers them as missing is forgotten, and the elevation source refreshed. */
    private fun demBack(back: List<String>, t: Long) {
        if (isDirect) {
            direct.forgetFailures()
            DirectTrace.mark("dBack ${back.size}")
        }
        app.tiles.online?.forgetMissing()
        fetcher?.retryNow() // the map's next requests for tiles that failed in the meantime are not answered from the fetcher's failure memo
        val names = back.toSet()
        demHaloVariantsDropped.addAndGet(cache.clearWhere { OnlineTiles.namesHaloTile(it, names) }.toLong())
        demRefresh.recovered(t, back.size)
        if (demRefresh.claim(t)) refreshDemSource()
    }

    /** The style JSON is made here; the swap is posted to the UI thread, not awaited. A swap skipped mid-style-load is wanted again. */
    private fun refreshDemSource() {
        demSourceRefreshes.incrementAndGet() // first: the new style URLs carry it, so MapLibre takes them as new tile sets
        val json = app.tiles.style()
        main.post {
            val result = runCatching { app.map.map?.style?.let { DemSourceRefresh.refresh(it, json) } ?: mapOf("skipped" to "no map") }
                .getOrElse { mapOf("skipped" to "error: ${it.message}") }
            if (result["skipped"] == OsmSourceRefresh.STYLE_LOADING) demRefresh.skipped()
            lastDemRefresh = result + ("at" to System.currentTimeMillis())
            if (isDirect) DirectTrace.mark("demRefresh ${result["layers"] ?: result["skipped"]}")
            Log.i(TAG, "elevation source refreshed for recovered DEM tiles: $result")
        }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** After an offline spell that left tiles blank, reload the style once the server answers again. */
    private fun refreshAfterReconnect(f: OnlineFetcher) {
        if (f.state != "online" || f.generation == refreshedGeneration) return
        refreshedGeneration = f.generation
        val failed = unavailableServed.get()
        if (failed == refreshedAtUnavailable) return
        refreshedAtUnavailable = failed
        refreshes.incrementAndGet()
        Log.i(TAG, "server back (generation ${f.generation}); reloading the style for tiles left blank")
        app.showStyle(app.tiles) // T144: posted, not awaited
    }

    // ---- T41: view changes, online credits, glyphs and sprites ----

    /** Registry ids the server reports for the last online view (`/live/sources`), or null before the first. */
    @Volatile var viewSourceIds: List<String>? = null
        private set
    val viewChanges = AtomicLong()
    val sourceRefreshes = AtomicLong()
    @Volatile private var lastAssets: Map<String, Any?>? = null
    /** T74: the server's asset version the files dir was last brought up to (null: not this session). */
    @Volatile private var assetsVersion: String? = null
    @Volatile private var assetsTried = false
    /** Where the last view at rest got its terrain ([OnlineView.source]); online credits show only when it is not all pack. */
    @Volatile var lastViewSource: String? = null
        private set

    /**
     * The camera came to rest (main thread): negative-cached tiles older than a few seconds are tried again
     * ([OnlineFetcher.retryNegative]), and when the view is not all pack, the server is asked which sources
     * it used there (for [credits]).
     */
    fun onViewChanged() {
        val f = fetcher ?: return
        val cam = OnlineView.currentOnMain(app) ?: return
        viewChanges.incrementAndGet()
        prefetchPool.execute { // T144: the view's source is worked out here, not on the UI thread (the online tiles' records take locks)
            val view = cam.withSource(OnlineView.sourceFor(app, cam))
            lastViewSource = view.source
            f.retryNegative()
            if (view.source != OnlineTiles.PACK) runCatching { refreshSources(f, view) }
        }
    }

    /** T110: when the last idle refresh of the sources was asked (elapsed ms). */
    @Volatile private var idleRefreshAt = 0L
    val idleSourceRefreshes = AtomicLong()

    /**
     * T110: the map went idle (main thread). Right after the camera comes to rest the view's tiles are not made yet, so the
     * sources the server (or the phone's own reader) reports for the view are the previous view's or the defaults, and the
     * credit line kept them until the next move (T106's follow-up: it lagged a view behind). Asked again here, once the
     * view's tiles are in, at most once a second; the line recomposes when the answer changes ([CreditLine]).
     */
    fun onMapIdle() {
        val f = fetcher ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - idleRefreshAt < 1000) return
        val cam = OnlineView.currentOnMain(app) ?: return
        idleRefreshAt = now
        prefetchPool.execute { // T144: the source off the UI thread
            val view = cam.withSource(OnlineView.sourceFor(app, cam))
            if (view.source == OnlineTiles.PACK) return@execute
            idleSourceRefreshes.incrementAndGet()
            runCatching { refreshSources(f, view) }
        }
    }

    private fun refreshSources(f: OnlineFetcher, view: OnlineView) {
        val b = view.bounds ?: return
        val z = floor(view.zoom).toInt()
        val dz = (z + 1).coerceIn(OnlineTiles.DEM_MIN_ZOOM, OnlineTiles.DEM_MAX_ZOOM)
        val oz = z.coerceIn(0, OnlineTiles.OSM_MAX_ZOOM)
        val bbox = listOf(b.minLon, b.minLat, b.maxLon, b.maxLat).joinToString(",") { String.format(Locale.ROOT, "%.5f", it) }
        val body = f.probe("live/sources?bbox=$bbox&dz=$dz&oz=$oz") ?: return
        @Suppress("UNCHECKED_CAST")
        val ids = (MiniJson.parseObject(body.decodeToString())["registryIds"] as? List<Any?>)?.mapNotNull { it as? String } ?: return
        // The report names only tiles the server has already served for the box: right after a move it may
        // list the DEM and not yet the map (Kilimanjaro: only copernicus). Fill a missing kind with the defaults.
        val next = withDefaults(ids)
        val changed = next != viewSourceIds
        viewSourceIds = next
        sourceRefreshes.incrementAndGet()
        // T110: the credit line reads these; it recomposed at the camera's rest, before this answer came.
        if (changed) main.post { io.github.jasondorjeshort.dystopo.app.about.CreditLine.revision++ } // T144: posted, not awaited
    }

    /**
     * Credits for what the map shows from the server (T38 left them out of `/attribution`): one entry for
     * online elevation and one for online map data, each naming the registry sources the server reported for
     * the last online view (before the first report: what the live server uses by default). Empty when no
     * server is set or nothing has been shown from it this session.
     */
    fun credits(all: Boolean = false): List<PackMetadata> {
        val f = fetcher ?: return emptyList()
        if (all && f.server == DirectSources.SERVER) {
            // T80: the About screen credits every source the phone can read on its own, with its licence.
            val ids = io.github.jasondorjeshort.dystopo.sources.elevation.BestSource().registryIds + DirectSources.OSM_IDS
            return creditsFor("this phone", ids, 1, 1, info?.get("osmAttribution") as? String)
        }
        if (lastViewSource == OnlineTiles.PACK) return emptyList() // the view is all pack: its own credits apply
        return creditsFor(
            f.server, viewSourceIds, routes.getValue("dem").hits.get(), routes.getValue("osm").hits.get(),
            info?.get("osmAttribution") as? String,
        )
    }

    /**
     * Once per session, and again whenever the server's `assets` version changes (T74), brings the files dir's glyphs
     * and sprites up to the server's ([installAssets]); reloads the map when anything was written.
     */
    @Synchronized
    private fun installAssetsOnce(f: OnlineFetcher) {
        if (f.server == DirectSources.SERVER) return // T80: the APK brings them (BundledAssets)
        val v = f.version("assets")
        if (assetsTried && (v == null || v == assetsVersion)) return
        assetsTried = true
        val r = installAssets(f.server)
        if (r["failed"].let { it is List<*> && it.isEmpty() }) assetsVersion = (r["version"] as? String) ?: v
        if ((r["installed"] as? Int ?: 0) > 0) app.reload() // the style now points at the files (file:// glyphs, sprite sheet)
    }

    /**
     * Copies the label glyphs ([MapLibreStyle.FONTS] x [Glyphs.RANGES]) and the marker sprite sheet
     * ([Sprites.FILES]) the device lacks from [server] into the files dir, where the style finds them as it
     * finds pushed ones. Called when a pack download finishes (so a downloaded pack comes with them) and once
     * per session when online without them. Returns what happened (`/online` reports it as `assets`).
     */
    fun installAssets(server: String): Map<String, Any?> {
        if (server == DirectSources.SERVER) return linkedMapOf("server" to server, "installed" to 0, "bundled" to true) // T80
        val http = UrlConnectionGet()
        // T74: a server with an asset manifest: every file whose hash differs is fetched again (a changed sprite sheet or glyph range).
        val manifest = runCatching { http.get(server.trimEnd('/') + AssetManifest.ROUTE) }.getOrNull()
            ?.takeIf { it.status == 200 }?.let { runCatching { AssetManifest.parse(it.body.decodeToString()) }.getOrNull() }
        if (manifest != null) return installFromManifest(server, manifest, http, app.filesDir).also { lastAssets = it; Log.i(TAG, "assets from $server: $it") }
        val root = app.filesDir
        var installed = 0
        var present = 0
        val failed = ArrayList<String>()
        fun fetch(url: String, target: File) {
            if (target.isFile && target.length() > 0) { present++; return }
            try {
                val reply = http.get(url)
                if (reply.status != 200 || reply.body.isEmpty()) { failed += "${target.name}: HTTP ${reply.status}"; return }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(reply.body)
                if (!tmp.renameTo(target)) { tmp.delete(); failed += "${target.name}: rename"; return }
                installed++
            } catch (e: Exception) {
                failed += "${target.name}: ${e.javaClass.simpleName}"
            }
        }
        val base = server.trimEnd('/')
        for (font in MapLibreStyle.FONTS) for (range in Glyphs.RANGES) {
            val enc = URLEncoder.encode(font, "UTF-8").replace("+", "%20")
            fetch("$base/glyphs/$enc/$range.pbf", File(root, "glyphs/$font/$range.pbf"))
        }
        for (file in Sprites.FILES) fetch("$base/$file", File(root, "${SpriteFiles.DIR}/$file"))
        val r = linkedMapOf<String, Any?>("server" to server, "installed" to installed, "present" to present, "failed" to failed.take(8))
        lastAssets = r
        Log.i(TAG, "assets from $server: $r")
        return r
    }

    fun json(view: Map<String, Any?>?): Map<String, Any?> = linkedMapOf(
        "server" to AppSettings.server.ifEmpty { null },
        "state" to state(),
        "view" to view,
        "fetcher" to fetcher?.json(),
        "cache" to cache.json(),
        "served" to routes.mapValues { it.value.json() },
        "contours" to mapOf(
            "made" to contoursMade.get(), "cacheHits" to contourCacheHits.get(), "incompleteNotCached" to contourIncomplete.get(),
            "haloVariantsDropped" to contourHaloVariantsDropped.get(), // T106
        ),
        "shade" to mapOf("made" to shadeMade.get(), "cacheHits" to shadeCacheHits.get(), "incompleteNotCached" to shadeIncomplete.get()), // T74
        "versionReloads" to versionReloads.get(),
        "assetsVersion" to assetsVersion,
        "unavailableServed" to unavailableServed.get(),
        "stalledOsm" to stalledOsm.json() + osmRefresh.json() + linkedMapOf("sourceRefreshes" to osmSourceRefreshes.get(), "lastSourceRefresh" to lastOsmRefresh), // T138; T144: settleMs, refreshes
        "stalledDem" to stalledDem.json() + demRefresh.json() + linkedMapOf( // T155
            "sourceRefreshes" to demSourceRefreshes.get(), "lastSourceRefresh" to lastDemRefresh, "retrying" to demRetrying.get(),
            "canaries" to demCanaries.get(), "canaryGapMs" to canaryGapMs, "sourcesAnswered" to demSourcesAnswered.get(),
            "haloVariantsDropped" to demHaloVariantsDropped.get(),
        ),
        "styleRefreshesAfterReconnect" to refreshes.get(),
        "serverInfo" to info?.let { mapOf("demMinZoom" to it["demMinZoom"], "demMaxZoom" to it["demMaxZoom"], "osmMaxZoom" to it["osmMaxZoom"]) },
        "download" to region.json(),
        "viewChanges" to viewChanges.get(), // T41
        "viewSourceIds" to viewSourceIds,
        "sourceRefreshes" to sourceRefreshes.get(), "idleSourceRefreshes" to idleSourceRefreshes.get(), // T110
        "credits" to credits().map { mapOf("role" to it.role, "provenance" to it.metadata["provenance"]) },
        "assets" to lastAssets,
        "direct" to directJson(), // T80
        "stationPressure" to linkedMapOf("fetches" to stationFetches.get(), "failures" to stationFetchFailures.get(), "bytes" to stationBytes.get()), // T173
    )

    /**
     * T80: the no-server path's record (T135: `direct: true` first); T135: with a server configured only `direct: false` and the heap record
     * ([HeapGuard.json], application-wide: the application's `onTrimMemory` trims whatever the server setting, and
     * `tools/android-check.py` reads its `recentTrims`; finding 2026-10-23-direct-step-live-heap-unread-with-a-server).
     */
    private fun directJson(): Map<String, Any?> =
        if (isDirect) linkedMapOf<String, Any?>("direct" to true) + direct.json() + mapOf("notReadyAnswered" to app.tiles.online?.notReady?.get(), "refreshesAfterRendering" to renderRefreshes.get(), "refreshesAfterRenderingSkipped" to renderRefreshesSkipped.get())
        else linkedMapOf("direct" to false, "memory" to HeapGuard.json())

    companion object {
        const val TAG = "DystopoOnline"
        const val ONLINE = "online"
        const val CACHED = "cached"
        const val PROBE_ONLINE_MS = 30_000L
        const val PROBE_OFFLINE_MS = 5_000L
        /** T155: the gap before the next canary DEM try ([retryStalledDem]): after one that stalled, and the most after quick failures. */
        const val CANARY_MIN_GAP_MS = 2_000L
        const val CANARY_MAX_GAP_MS = 30_000L
        /** T74: the content versions of every source, kept across restarts (outside the tile cache directory). */
        const val VERSIONS_FILE = "online/versions.json"

        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /**
         * T74: brings [root] (the files dir) up to [manifest]: a file that is missing or whose SHA-256 differs is
         * downloaded from [server], checked against the manifest's hash and moved into place; nothing else is touched.
         * Paths were checked by [AssetManifest.parse] (only under `glyphs/` and `sprites/`).
         */
        fun installFromManifest(server: String, manifest: AssetManifest.Manifest, http: HttpGet, root: File): Map<String, Any?> {
            var installed = 0
            var present = 0
            val replaced = ArrayList<String>()
            val failed = ArrayList<String>()
            val base = server.trimEnd('/')
            for (e in manifest.files) {
                if (!AssetManifest.safePath(e.path)) { failed += "${e.path}: unsafe path"; continue }
                val target = File(root, e.path)
                val had = target.isFile && target.length() > 0
                if (had && runCatching { sha256(target.readBytes()) }.getOrNull() == e.sha256) { present++; continue }
                try {
                    val reply = http.get(base + e.url)
                    if (reply.status != 200 || reply.body.isEmpty()) { failed += "${e.path}: HTTP ${reply.status}"; continue }
                    if (sha256(reply.body) != e.sha256) { failed += "${e.path}: hash differs from the manifest"; continue }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeBytes(reply.body)
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) { tmp.delete(); failed += "${e.path}: rename"; continue }
                    }
                    installed++
                    if (had) replaced += e.path
                } catch (ex: Exception) {
                    failed += "${e.path}: ${ex.javaClass.simpleName}"
                }
            }
            return linkedMapOf(
                "server" to server, "manifest" to true, "version" to manifest.version, "files" to manifest.files.size,
                "installed" to installed, "replaced" to replaced.size, "replacedFiles" to replaced.take(8), "present" to present, "failed" to failed.take(8),
            )
        }
        /** What the live server serves by default (`best` DEM, OpenFreeMap base map), until it reports a view's sources. */
        val DEFAULT_ONLINE_IDS = listOf("copernicus", "3dep", "osm", "openfreemap", "openmaptiles")

        private fun isElevation(id: String) = SourceRegistry.builtIn.getValue(id).kind in setOf(SourceKind.DEM, SourceKind.GEOID)

        /**
         * Known registry ids from a server report, with the default ids of a kind (elevation, map data) the
         * report lacks added; [DEFAULT_ONLINE_IDS] when there is no report.
         */
        fun withDefaults(ids: List<String>?): List<String> {
            val known = ids.orEmpty().filter { it in SourceRegistry.builtIn }.distinct()
            val (elevation, map) = known.partition(::isElevation)
            val defaults = DEFAULT_ONLINE_IDS.partition(::isElevation)
            return SourceRegistry.builtIn.ordered(elevation.ifEmpty { defaults.first } + map.ifEmpty { defaults.second })
        }

        /**
         * The online credits (pure): `online-dem` when DEM tiles were served from the server ([demServed]),
         * `online-osm` when map tiles were ([osmServed]), each naming its kind of [ids] ([withDefaults]).
         */
        fun creditsFor(server: String, ids: List<String>?, demServed: Long, osmServed: Long, osmAttributionHtml: String?): List<PackMetadata> {
            val all = withDefaults(ids)
            val (elevation, map) = all.partition(::isElevation)
            val out = ArrayList<PackMetadata>()
            // T49: the elevation credit carries the registry texts (Copernicus, 3DEP), like a DEM pack's own attribution.
            val demCredit = elevation.mapNotNull { SourceRegistry.builtIn[it]?.attribution }.joinToString(" ")
            if (demServed > 0) out += PackMetadata("online-dem", server, mapOf("name" to "Online elevation", "provenance" to elevation, "attribution" to demCredit))
            if (osmServed > 0) {
                val credit = osmAttributionHtml ?: "&copy; OpenStreetMap contributors"
                out += PackMetadata("online-osm", server, mapOf("name" to "Online map", "provenance" to map, "attribution" to credit))
            }
            return out
        }

        @Volatile private var instance: OnlineService? = null

        fun get(app: DystopoApp = DystopoApp.instance): OnlineService =
            instance ?: synchronized(this) { instance ?: OnlineService(app).also { instance = it } }
    }
}
