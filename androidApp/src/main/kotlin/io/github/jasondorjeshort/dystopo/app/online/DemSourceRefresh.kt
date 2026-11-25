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

    internal fun sourceFor(id: String, def: Map<String, Any?>): Source? { // T368: also for the copies' sources
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
 * one per rest), each made without a blank frame ([SeamlessDemRefresh]). Thread-safe; pure, tested (`BehindBatchTest`).
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
 * T368: the elevation batch ([dem]) and its refresh. While anything is pending a ticker looks every [TICK_MS] (only then: it
 * stops when nothing is in flight or waiting); when the batch is due the elevation source is refreshed by
 * [SeamlessDemRefresh] (the swap above, behind copies that keep the view drawn). Before it the online pyramid's memory of
 * missing tiles is dropped (it read the landed tiles' places from the map). Nothing here fetches. `GET /online` `behind`.
 */
object BehindRefresh {
    val dem = BehindBatch()

    /** Each refresh's record: when it was asked, the tiles it announced, and what the map did (its phases and times, or why skipped). */
    private val recent = ArrayDeque<Map<String, Any?>>()

    private val ticking = java.util.concurrent.atomic.AtomicBoolean()
    private val ticker by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "behind-refresh").apply { isDaemon = true } }
    }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Whether the map has been fully loaded once since the app started. Until then the elevation tiles to fetch behind are
     * only noted ([defer]) and started once it is ([TileService.startDeferredBehind]): fetched during the launch they held its
     * fully-loaded figure back by about 1.5 to 2.5 s on the emulator (a saved view at z14 inside a map a server stores deeper).
     */
    @Volatile var launchLoaded = false
    private val deferred = java.util.concurrent.ConcurrentLinkedQueue<Triple<Int, Int, Int>>()

    /** Elevation tile z/x/y to fetch behind once the launch's first view is loaded. */
    fun defer(z: Int, x: Int, y: Int) {
        if (deferred.size < MAX_DEFERRED) deferred.add(Triple(z, x, y))
        poke()
    }

    /** A fetch started or landed: the ticker looks until nothing is pending. */
    fun poke() {
        if (ticking.compareAndSet(false, true)) ticker.schedule(::tick, TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        try {
            val app = io.github.jasondorjeshort.dystopo.app.DystopoApp.instance
            val now = System.currentTimeMillis()
            if (!deferred.isEmpty() && app.map.fullyLoaded) {
                launchLoaded = true
                val tiles = generateSequence { deferred.poll() }.toList()
                app.tiles.startDeferredBehind(tiles)
            }
            // At rest: the camera still and the view it shows loaded (a refresh started while the first view loads held the
            // launch's fully-loaded figure back by about 2.6 s on the emulator).
            if (!SeamlessDemRefresh.running) dem.take(now, app.map.animating || !app.map.fullyLoaded).takeIf { it > 0 }?.let { swap(app, it) }
        } catch (e: Throwable) {
            android.util.Log.w("BehindRefresh", "refresh after fetches behind failed", e)
        } finally {
            ticking.set(false)
            if (dem.pending || SeamlessDemRefresh.running || !deferred.isEmpty()) poke()
        }
    }

    private fun swap(app: io.github.jasondorjeshort.dystopo.app.DystopoApp, n: Int) {
        app.tiles.online?.forgetMissing()
        OnlineService.get().demSourceRefreshes.incrementAndGet() // first: the new style URLs carry it
        val json = app.tiles.style()
        val asked = System.currentTimeMillis()
        main.post {
            val style = app.map.map?.style
            val started = if (style == null) mapOf("skipped" to "no map") else SeamlessDemRefresh.start(app, style, json) { done -> note(asked, n, done) }
            if (started["skipped"] != null) {
                if (started["skipped"] == OsmSourceRefresh.STYLE_LOADING) dem.again(System.currentTimeMillis(), n)
                note(asked, n, started)
                poke()
            }
        }
    }

    private fun note(asked: Long, n: Int, result: Map<String, Any?>) = synchronized(recent) {
        recent.addLast(linkedMapOf<String, Any?>("at" to asked, "tiles" to n) + result)
        while (recent.size > KEEP) recent.removeFirst()
    }

    fun json(): Map<String, Any?> = linkedMapOf("dem" to dem.json(), "refreshing" to SeamlessDemRefresh.running, "launchLoaded" to launchLoaded, "deferred" to deferred.size, "recent" to synchronized(recent) { recent.toList() })

    const val TICK_MS = 250L
    private const val KEEP = 12
    private const val MAX_DEFERRED = 512
}

/**
 * T368: the elevation source and its contour and shade sources swapped with no blank frame. [DemSourceRefresh.refresh]
 * removes and re-adds the sources, and a new source starts with no tiles: on the API 35 emulator the hillshade and contour
 * lines were gone for about 1.5 s after each swap (frames about every 115 ms). So, on the UI thread: (1) the new sources go
 * in beside the old under ids ending [NEXT], each layer that reads them copied from the style JSON above its original with
 * its opacity (the hillshade's exaggeration) all but 0, so their tiles load while nothing of them shows; (2) once the map is idle
 * (its tiles loaded; at most [WAIT_MS]) the copies show and the originals hide, in one step; (3) the originals' sources are
 * swapped ([DemSourceRefresh.refresh]) while hidden; (4) once idle again the originals show and the copies and their sources
 * go, in one step. Each step changes what is drawn only from one loaded set of tiles to another. A style load in between
 * ends it (the new style has the new URLs). One at a time ([running]).
 */
object SeamlessDemRefresh {
    const val NEXT = "~next"
    const val WAIT_MS = 10_000L
    private const val LOOK_MS = 100L
    private const val SETTLE_MS = 300L

    /**
     * The paint property that hides a layer of each type, and its value when the style leaves it out. Hidden is [HIDDEN] (the
     * hillshade's exaggeration [HIDDEN_SHADE]), not 0: MapLibre neither draws nor loads the tiles of a layer at 0, so a copy
     * at 0 loaded nothing and showed blank (seen on the emulator). A hidden symbol layer also places its labels over
     * anything and blocks none ([hideLabels]), so the copies' labels never push the originals' aside.
     */
    private val fade = mapOf(
        "hillshade" to ("hillshade-exaggeration" to 0.5f), "line" to ("line-opacity" to 1f), "symbol" to ("text-opacity" to 1f),
        "raster" to ("raster-opacity" to 1f), "fill" to ("fill-opacity" to 1f),
    )

    private const val HIDDEN = 0.004f
    private const val HIDDEN_SHADE = 0.001f

    private fun hiddenOf(prop: String) = if (prop == "hillshade-exaggeration") HIDDEN_SHADE else HIDDEN

    /** Labels of a hidden symbol layer: drawn over anything, blocking nothing ([on]), or as the style says (its [l] JSON). */
    private fun hideLabels(layer: org.maplibre.android.style.layers.Layer?, on: Boolean, l: org.json.JSONObject?) {
        if (layer !is org.maplibre.android.style.layers.SymbolLayer) return
        val layout = l?.optJSONObject("layout")
        layer.setProperties(*listOf("text-allow-overlap", "text-ignore-placement", "icon-allow-overlap", "icon-ignore-placement").map { k ->
            org.maplibre.android.style.layers.LayoutPropertyValue(k, if (on) true else layout?.optBoolean(k, false) ?: false)
        }.toTypedArray())
    }

    @Volatile var running = false
        private set

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Starts the refresh on the UI thread; [done] gets the record (phases' ms, layers) at the end. The answer now: `started` or `skipped`. */
    fun start(app: io.github.jasondorjeshort.dystopo.app.DystopoApp, style: Style, styleJson: String, done: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        if (running) return mapOf("skipped" to "a refresh is running")
        if (!style.isFullyLoaded) return mapOf("skipped" to OsmSourceRefresh.STYLE_LOADING)
        val defs = DemSourceRefresh.sources(styleJson).filterKeys { style.getSource(it) != null }
        if (DemSourceRefresh.DEM !in defs) return mapOf("skipped" to "no elevation source")
        val layers = runCatching { org.json.JSONObject(styleJson).getJSONArray("layers") }.getOrNull() ?: return mapOf("skipped" to "no layers")
        val t0 = android.os.SystemClock.elapsedRealtime()
        // (1) the new sources beside the old, the copies above their originals, showing nothing.
        val made = defs.mapNotNull { (id, def) -> DemSourceRefresh.sourceFor(id + NEXT, def) }
        if (made.size != defs.size) return mapOf("skipped" to "a source without a tile URL or of an unknown type")
        for (s in made) {
            style.getSource(s.id)?.let { runCatching { style.removeSource(it) } }
            style.addSource(s)
        }
        val copies = ArrayList<Triple<String, String, Float>>() // copy id, original id, the value it shows with
        val json = HashMap<String, org.json.JSONObject>() // original id -> its JSON
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val src = l.optString("source")
            val lid = l.optString("id")
            if (src !in defs.keys || style.getLayer(lid) == null) continue
            val (prop, dflt) = fade[l.optString("type")] ?: continue
            val copy = layerOf(l, lid + NEXT, src + NEXT) ?: continue
            val shown = l.optJSONObject("paint")?.optDouble(prop, Double.NaN)?.takeIf { !it.isNaN() }?.toFloat() ?: dflt
            copy.setProperties(org.maplibre.android.style.layers.PaintPropertyValue(prop, hiddenOf(prop)))
            hideLabels(copy, true, l)
            instant(copy)
            instant(style.getLayer(lid))
            style.addLayerAbove(copy, lid)
            json[lid] = l
            copies += Triple(lid + NEXT, lid, shown)
        }
        running = true
        val record = linkedMapOf<String, Any?>("copies" to copies.size)
        fun prop(id: String): String? = style.getLayer(id)?.let { typeOf(it) }?.let { fade[it]?.first }
        fun end(why: String?) {
            // However it ends (a style load in between included, the same Style object still loading): the originals are shown
            // again, as the style JSON has them, before the copies go; on a newer Style object these layers are gone anyway.
            for ((_, lid, shown) in copies) runCatching {
                val layer = style.getLayer(lid) ?: return@runCatching
                fade[typeOf(layer)]?.first?.let { p -> layer.setProperties(org.maplibre.android.style.layers.PaintPropertyValue(p, shown)) }
                hideLabels(layer, false, json[lid])
            }
            for ((cid, _, _) in copies) runCatching { style.getLayer(cid)?.let { style.removeLayer(it) } }
            for (s in made) runCatching { style.getSource(s.id)?.let { style.removeSource(it) } }
            running = false
            record["totalMs"] = android.os.SystemClock.elapsedRealtime() - t0
            why?.let { record["ended"] = it }
            done(record)
        }
        whenIdle(app, style) { ok1 ->
            if (!ok1) return@whenIdle end("style changed")
            record["loadMs"] = android.os.SystemClock.elapsedRealtime() - t0
            // (2) the copies show, the originals hide (a failure here ends the refresh, the originals shown again).
            val shownStep = runCatching { for ((cid, lid, shown) in copies) {
                val p = prop(cid) ?: continue
                style.getLayer(cid)?.setProperties(org.maplibre.android.style.layers.PaintPropertyValue(p, shown))
                hideLabels(style.getLayer(cid), false, json[lid])
                style.getLayer(lid)?.setProperties(org.maplibre.android.style.layers.PaintPropertyValue(p, hiddenOf(p)))
                hideLabels(style.getLayer(lid), true, json[lid])
            } }
            if (shownStep.isFailure) return@whenIdle end("error: ${shownStep.exceptionOrNull()?.message}")
            // (3) the originals' sources swapped while hidden (their layers keep what hides them).
            val swapped = runCatching { DemSourceRefresh.refresh(style, styleJson) }.getOrElse { mapOf("skipped" to "error: ${it.message}") }
            record["swap"] = swapped.filterKeys { it != "url" }
            whenIdle(app, style) { ok2 ->
                if (ok2) record["reloadMs"] = android.os.SystemClock.elapsedRealtime() - t0
                // (4) the originals show again and the copies go ([end] does both, whichever way the refresh ends).
                end(if (ok2) null else "style changed")
            }
        }
        return mapOf("started" to copies.size)
    }

    /** Calls [then] on the UI thread once the map has rendered and gone idle (true), or false when the style changed; at most [WAIT_MS]. */
    private fun whenIdle(app: io.github.jasondorjeshort.dystopo.app.DystopoApp, style: Style, then: (Boolean) -> Unit) {
        val from = android.os.SystemClock.elapsedRealtime()
        val look = object : Runnable {
            override fun run() {
                if (app.map.map?.style !== style || !style.isFullyLoaded) return then(false)
                val waited = android.os.SystemClock.elapsedRealtime() - from
                if ((waited >= SETTLE_MS && app.map.mapLibreIdle) || waited >= WAIT_MS) then(true) else main.postDelayed(this, LOOK_MS)
            }
        }
        main.postDelayed(look, SETTLE_MS)
    }

    /**
     * No transition on [l]'s hiding property: a paint change is animated over 300 ms by default, and a copy shown while its
     * original hides crossfaded through a frame with neither drawn (seen on the emulator as a blank frame at each step).
     */
    private fun instant(l: org.maplibre.android.style.layers.Layer?) {
        val none = org.maplibre.android.style.layers.TransitionOptions(0, 0, false)
        when (l) {
            is org.maplibre.android.style.layers.HillshadeLayer -> l.hillshadeExaggerationTransition = none
            is org.maplibre.android.style.layers.LineLayer -> l.lineOpacityTransition = none
            is org.maplibre.android.style.layers.SymbolLayer -> l.textOpacityTransition = none
            is org.maplibre.android.style.layers.RasterLayer -> l.rasterOpacityTransition = none
            is org.maplibre.android.style.layers.FillLayer -> l.fillOpacityTransition = none
            else -> Unit
        }
    }

    private fun typeOf(l: org.maplibre.android.style.layers.Layer): String? = when (l) {
        is org.maplibre.android.style.layers.HillshadeLayer -> "hillshade"
        is org.maplibre.android.style.layers.LineLayer -> "line"
        is org.maplibre.android.style.layers.SymbolLayer -> "symbol"
        is org.maplibre.android.style.layers.RasterLayer -> "raster"
        is org.maplibre.android.style.layers.FillLayer -> "fill"
        else -> null
    }

    /** A layer as the style JSON [l] defines it, under [id] reading [source]; null for a type it does not copy. */
    private fun layerOf(l: org.json.JSONObject, id: String, source: String): org.maplibre.android.style.layers.Layer? {
        val filter = l.optJSONArray("filter")?.let { org.maplibre.android.style.expressions.Expression.raw(it.toString()) }
        val sourceLayer = l.optString("source-layer").takeIf { it.isNotEmpty() }
        val layer: org.maplibre.android.style.layers.Layer = when (l.optString("type")) {
            "hillshade" -> org.maplibre.android.style.layers.HillshadeLayer(id, source)
            "raster" -> org.maplibre.android.style.layers.RasterLayer(id, source)
            "line" -> org.maplibre.android.style.layers.LineLayer(id, source).apply { sourceLayer?.let { setSourceLayer(it) }; filter?.let { setFilter(it) } }
            "symbol" -> org.maplibre.android.style.layers.SymbolLayer(id, source).apply { sourceLayer?.let { setSourceLayer(it) }; filter?.let { setFilter(it) } }
            "fill" -> org.maplibre.android.style.layers.FillLayer(id, source).apply { sourceLayer?.let { setSourceLayer(it) }; filter?.let { setFilter(it) } }
            else -> return null
        }
        if (l.has("minzoom")) layer.minZoom = l.getDouble("minzoom").toFloat()
        if (l.has("maxzoom")) layer.maxZoom = l.getDouble("maxzoom").toFloat()
        val props = ArrayList<org.maplibre.android.style.layers.PropertyValue<*>>()
        for ((group, paint) in listOf("layout" to false, "paint" to true)) {
            val o = l.optJSONObject(group) ?: continue
            for (k in o.keys()) {
                val v = valueOf(k, o.get(k)) ?: continue
                props += if (paint) org.maplibre.android.style.layers.PaintPropertyValue(k, v) else org.maplibre.android.style.layers.LayoutPropertyValue(k, v)
            }
        }
        layer.setProperties(*props.toTypedArray())
        return layer
    }

    /** A style JSON value as MapLibre takes it: an expression, a font list, a number, a string or a boolean. */
    private fun valueOf(key: String, v: Any?): Any? = when (v) {
        is org.json.JSONArray -> if (key == "text-font") Array(v.length()) { v.getString(it) } else org.maplibre.android.style.expressions.Expression.raw(v.toString())
        is Number -> v.toFloat()
        is String, is Boolean -> v
        else -> null
    }
}
// end T368
