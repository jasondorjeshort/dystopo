package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.Source
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

/**
 * T155 (T138's follow-up for DEM tiles): replaces the map's elevation source and the sources made from it in place, so
 * MapLibre asks for their tiles again without a style reload (the vector map and the overlays stay; `GET /health`
 * `map.styleLoads` does not move). `OnlineService` asks for it once a DEM tile that was answered 404 after a failed fetch
 * ([StalledOsmTiles], `stalledDem`) has come back.
 *
 * The raster-dem source [DEM] (the native hillshade) is swapped, not re-added layer by layer as vector data would be, and
 * with it the sources derived from the same DEM tiles: the contours ([CONTOURS], vector) and the generated hillshade and
 * slope ([SHADE], raster, present only when a map option draws it). A contour or shade tile made while its DEM tile was
 * missing was served with a hole and not kept (`OnlineTiles`), but MapLibre kept it; the swap asks for it again. Cliff
 * ticks are oriented from the DEM packs only (`TileService.cliffPyramid`), never from an online DEM tile, so the vector
 * source is not touched.
 *
 * Each new source has a new tile URL (the style's, whose version carries `OnlineService.demSourceRefreshes` as `-d<n>`):
 * MapLibre keeps a source's tiles while its tile set is unchanged. A source cannot be removed while layers use it, so
 * those layers are removed (the references stay usable), the sources replaced and the layers put back at their indexes,
 * all on the main thread in one go, as [OsmSourceRefresh] does for the vector source.
 */
object DemSourceRefresh {
    const val DEM = "dem"
    const val CONTOURS = "contours"
    const val SHADE = "shade"
    val SOURCE_IDS = listOf(DEM, CONTOURS, SHADE)

    /** The definitions of [SOURCE_IDS] in [styleJson] (`type`, `tiles`, `minzoom`, `maxzoom`, `encoding`, ...), those present only. Pure, tested. */
    fun sources(styleJson: String): Map<String, Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val all = MiniJson.parseObject(styleJson)["sources"] as? Map<String, Any?> ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, Any?>>()
        @Suppress("UNCHECKED_CAST")
        for (id in SOURCE_IDS) (all[id] as? Map<String, Any?>)?.let { out[id] = it }
        return out
    }

    /** The first tile URL of [def] without its loopback token, for the record. Pure, tested. */
    fun urlOf(def: Map<String, Any?>): String? =
        ((def["tiles"] as? List<*>)?.firstOrNull() as? String)?.substringBefore("&t=")?.substringBefore("?t=")

    private fun sourceFor(id: String, def: Map<String, Any?>): Source? {
        val url = (def["tiles"] as? List<*>)?.firstOrNull() as? String ?: return null
        val set = TileSet("2.2.0", url).apply {
            (def["minzoom"] as? Number)?.let { setMinZoom(it.toFloat()) }
            (def["maxzoom"] as? Number)?.let { setMaxZoom(it.toFloat()) }
            (def["encoding"] as? String)?.let { encoding = it }
            (def["attribution"] as? String)?.takeIf { it.isNotEmpty() }?.let { attribution = it }
        }
        val tileSize = (def["tileSize"] as? Number)?.toInt() ?: 256
        return when (def["type"]) {
            "raster-dem" -> RasterDemSource(id, set, tileSize)
            "raster" -> RasterSource(id, set, tileSize)
            "vector" -> VectorSource(id, set)
            else -> null
        }
    }

    /**
     * Replaces the elevation source and its derived sources of [style] with the ones [styleJson] defines; main thread.
     * Returns what was done for the record (`sources` swapped, `layers` moved, the new DEM `url` without its token), or a
     * `skipped` reason ([OsmSourceRefresh.STYLE_LOADING] while the style loads: `OnlineService` asks again).
     */
    fun refresh(style: Style, styleJson: String): Map<String, Any?> {
        if (!style.isFullyLoaded) return mapOf("skipped" to OsmSourceRefresh.STYLE_LOADING)
        val defs = sources(styleJson).filterKeys { style.getSource(it) != null }
        if (DEM !in defs) return mapOf("skipped" to "no elevation source")
        val made = defs.mapNotNull { (id, def) -> sourceFor(id, def)?.let { id to it } }.toMap()
        if (made.size != defs.size) return mapOf("skipped" to "a source without a tile URL or of an unknown type")
        val users = style.layers.withIndex().filter { OsmSourceRefresh.sourceOf(it.value) in made.keys }
        for ((_, layer) in users) style.removeLayer(layer)
        for (id in made.keys) style.removeSource(id)
        for (source in made.values) style.addSource(source)
        for ((index, layer) in users) style.addLayerAt(layer, index)
        return mapOf("sources" to made.keys.toList(), "layers" to users.size, "url" to defs[DEM]?.let(::urlOf))
    }
}

// ---- T368: the fetches behind what the phone holds, announced once per settled batch at a camera rest ----

/**
 * T368 (ADR 0066 decision 2; proposal 0066, "Answering without a wait"): one role's fetches behind a tile the phone answered
 * at once (`OnlineTiles.fetchBehind`: the elevation where the network stores deeper than the map, the vector tile past a
 * map's or the overview's zooms). Landed tiles are announced by one source refresh per settled batch, never one per tile:
 * [take] gives the tiles landed since the last refresh once none has landed for [quietMs] and none is in flight (or the
 * first has waited [holdMs]: a slow render does not hold the rest back for ever), **and the camera is at rest** (the
 * proposal's fallback, taken: a refresh swaps the elevation source and changes the contour and shade addresses, so every
 * visible tile is asked again; during a pan that would be one reload of the screen per batch, and nothing else keeps it to
 * one per rest). Thread-safe; pure, tested (`BehindBatchTest`).
 */
class BehindBatch(private val quietMs: Long = QUIET_MS, private val holdMs: Long = HOLD_MS) {
    private var inFlight = 0
    private var landed = 0
    private var firstLandedAt = 0L
    private var lastLandedAt = 0L
    private var heldNoted = false

    /** Fetches started, landed, failed; bytes landed; refreshes asked; batches that waited for the camera to rest. */
    var started = 0L
        private set
    var landedTotal = 0L
        private set
    var failed = 0L
        private set
    var bytes = 0L
        private set
    var refreshes = 0L
        private set
    var heldForRest = 0L
        private set

    @Synchronized
    fun started() {
        inFlight++
        started++
    }

    /** A fetch landed at [now] with [size] bytes. */
    @Synchronized
    fun landed(now: Long, size: Int) {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (landed == 0) firstLandedAt = now
        landed++
        lastLandedAt = now
        landedTotal++
        bytes += size
    }

    @Synchronized
    fun failed() {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        failed++
    }

    /** True while a fetch is in flight or landed tiles wait for their refresh (the ticker keeps looking). */
    val pending: Boolean @Synchronized get() = inFlight > 0 || landed > 0

    /** The tiles to announce now (the batch is then empty) when the batch has settled and the camera rests ([moving] false); else 0. */
    @Synchronized
    fun take(now: Long, moving: Boolean): Int {
        if (landed == 0 || now - lastLandedAt < quietMs) return 0
        if (inFlight > 0 && now - firstLandedAt < holdMs) return 0
        if (moving) {
            if (!heldNoted) heldForRest++
            heldNoted = true
            return 0
        }
        val n = landed
        landed = 0
        heldNoted = false
        refreshes++
        return n
    }

    /** The refresh of [n] tiles was skipped by the map (its style was loading): they are announced again at the next look. */
    @Synchronized
    fun again(now: Long, n: Int) {
        if (n <= 0) return
        if (landed == 0) firstLandedAt = now
        landed += n
        refreshes--
    }

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "started" to started, "inFlight" to inFlight, "landed" to landedTotal, "failed" to failed, "bytesLanded" to bytes,
        "waiting" to landed, "refreshes" to refreshes, "heldForRest" to heldForRest, "quietMs" to quietMs, "holdMs" to holdMs,
    )

    companion object {
        /** No landing for this long: the batch has settled (MapLibre's requests for one view arrive within a few hundred ms). */
        const val QUIET_MS = 400L

        /** A batch waits at most this long after its first landing for the fetches still in flight. */
        const val HOLD_MS = 8_000L
    }
}

/**
 * T368: the two batches ([dem], [vector]) and their refreshes. While anything is pending a ticker looks every [TICK_MS]
 * (only then: it stops when nothing is in flight or waiting); when a batch is due it swaps the map's source in place, the
 * elevation source with its contour and shade sources ([DemSourceRefresh], `-d<n>` in their URLs) or the vector source
 * ([OsmSourceRefresh], `-o<n>`), as the stall recovery does, on the UI thread with no style reload. Before an elevation swap
 * the online pyramid's memory of missing tiles is dropped (it read the landed tiles' places from the map). Nothing here
 * fetches. `GET /online` `behind`.
 */
object BehindRefresh {
    val dem = BehindBatch()
    val vector = BehindBatch()

    /** Each swap's record: when, the role, the tiles it announced, and what the map did (sources, layers, or why skipped). */
    private val recent = ArrayDeque<Map<String, Any?>>()

    private val ticking = java.util.concurrent.atomic.AtomicBoolean()
    private val ticker by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "behind-refresh").apply { isDaemon = true } }
    }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** A fetch started or landed: the ticker looks until nothing is pending. */
    fun poke() {
        if (ticking.compareAndSet(false, true)) ticker.schedule(::tick, TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        try {
            val app = io.github.jasondorjeshort.dystopo.app.DystopoApp.instance
            val now = System.currentTimeMillis()
            val moving = app.map.animating
            dem.take(now, moving).takeIf { it > 0 }?.let { swap(app, true, it) }
            vector.take(now, moving).takeIf { it > 0 }?.let { swap(app, false, it) }
        } catch (e: Throwable) {
            android.util.Log.w("BehindRefresh", "refresh after fetches behind failed", e)
        } finally {
            ticking.set(false)
            if (dem.pending || vector.pending) poke()
        }
    }

    private fun swap(app: io.github.jasondorjeshort.dystopo.app.DystopoApp, elevation: Boolean, n: Int) {
        val service = OnlineService.get()
        if (elevation) {
            app.tiles.online?.forgetMissing()
            service.demSourceRefreshes.incrementAndGet() // first: the new style URLs carry it
        } else {
            service.osmSourceRefreshes.incrementAndGet()
        }
        val json = app.tiles.style()
        val asked = System.currentTimeMillis()
        main.post {
            val result = runCatching {
                app.map.map?.style?.let { if (elevation) DemSourceRefresh.refresh(it, json) else OsmSourceRefresh.refresh(it, json) } ?: mapOf("skipped" to "no map")
            }.getOrElse { mapOf("skipped" to "error: ${it.message}") }
            if (result["skipped"] == OsmSourceRefresh.STYLE_LOADING) (if (elevation) dem else vector).again(System.currentTimeMillis(), n)
            synchronized(recent) {
                recent.addLast(linkedMapOf<String, Any?>("at" to asked, "role" to if (elevation) "dem" else "vector", "tiles" to n) + result.filterKeys { it != "url" })
                while (recent.size > KEEP) recent.removeFirst()
            }
            if (result.containsKey("skipped")) poke()
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf("dem" to dem.json(), "vector" to vector.json(), "recent" to synchronized(recent) { recent.toList() })

    const val TICK_MS = 250L
    private const val KEEP = 12
}
// end T368
