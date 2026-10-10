package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.RequestChecks
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
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
    val cache = TileCache(File(app.cacheDir, "online/tiles"), MAX_CACHE_BYTES)

    @Volatile var fetcher: OnlineFetcher? = null
        private set

    val region = RegionDownload(this, app)

    /** `/live/info` of the server (zooms, attribution), from the last successful check. */
    @Volatile var info: Map<String, Any?>? = null
        private set

    val contourCacheHits = AtomicLong()
    val contoursMade = AtomicLong()
    val contourIncomplete = AtomicLong()
    /** Tile requests answered 404 because the server could not be reached. */
    val unavailableServed = AtomicLong()
    @Volatile private var refreshedAtUnavailable = 0L
    @Volatile private var refreshedGeneration = 0
    val refreshes = AtomicLong()

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
    }

    /** Uses [server] ("" = offline only) from now on; the caller reloads the tiles and style. */
    @Synchronized
    fun configure(server: String) {
        if (fetcher?.server == server) return
        fetcher = if (server.isEmpty()) null else OnlineFetcher(server, cache)
        info = null
        Log.i(TAG, "server: ${server.ifEmpty { "(none)" }}")
    }

    /** The online half of a tile service over [packs], or null without a server. */
    fun tilesFor(packs: PackSet, contourVersion: String): OnlineTiles? =
        fetcher?.let { OnlineTiles(packs, it, this, BitmapImageDecoder, contourVersion) }

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
            }
        } catch (e: Throwable) {
            Log.w(TAG, "probe failed", e)
        } finally {
            probeThread.schedule(::probeLoop, next, TimeUnit.MILLISECONDS)
        }
    }

    /** After an offline spell that left tiles blank, reload the style once the server answers again. */
    private fun refreshAfterReconnect(f: OnlineFetcher) {
        if (f.state != "online" || f.generation == refreshedGeneration) return
        refreshedGeneration = f.generation
        val failed = unavailableServed.get()
        if (failed == refreshedAtUnavailable) return
        refreshedAtUnavailable = failed
        refreshes.incrementAndGet()
        Log.i(TAG, "server back (generation ${f.generation}); reloading the style for tiles left blank")
        val style = app.tiles.style()
        app.map.onMain { app.map.loadStyle(style) }
    }

    /** A short credit for online data in view, from the server's `/live/info` (plain text). */
    fun attribution(): String {
        val i = info ?: return "© OpenStreetMap contributors, OpenFreeMap"
        val osm = (i["osmAttribution"] as? String)?.let(RequestChecks::htmlToText)?.trim().orEmpty()
        // The DEM credit is long (every source of `best` plus geoids); the overlay names the elevation sources only.
        val dem = (i["demAttribution"] as? String)?.let(RequestChecks::htmlToText).orEmpty()
        val sources = Regex("Elevation: ([^,.(]+?)(?= \\d|,|\\.| \\()").findAll(dem).map { it.groupValues[1].trim() }.distinct().toList()
        return listOfNotNull(osm.ifEmpty { null }, sources.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Elevation: ")).joinToString(" | ")
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
        ),
        "unavailableServed" to unavailableServed.get(),
        "styleRefreshesAfterReconnect" to refreshes.get(),
        "serverInfo" to info?.let { mapOf("demMinZoom" to it["demMinZoom"], "demMaxZoom" to it["demMaxZoom"], "osmMaxZoom" to it["osmMaxZoom"]) },
        "download" to region.json(),
    )

    companion object {
        const val TAG = "DystopoOnline"
        const val ONLINE = "online"
        const val CACHED = "cached"
        /** The on-device tile cache cap (DEM, OSM, glyphs and generated contours together). */
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val PROBE_ONLINE_MS = 30_000L
        const val PROBE_OFFLINE_MS = 5_000L

        @Volatile private var instance: OnlineService? = null

        fun get(app: DystopoApp = DystopoApp.instance): OnlineService =
            instance ?: synchronized(this) { instance ?: OnlineService(app).also { instance = it } }
    }
}
