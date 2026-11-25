package io.github.jasondorjeshort.dystopo.app.terrain

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.ElevationSource
import io.github.jasondorjeshort.dystopo.dem.MergedTerrain
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid

/**
 * T340: the one terrain read for a chosen point that the Horizon view (`Gnss.sweep`), the Panorama (`PanoramaData`) and the
 * viewshed (`ViewshedSession`) share: [MergedTerrain]'s rule (the open maps cell by cell, the deepest that holds data first, a
 * coarser one where it ends, then the online source) over the maps [PackSet][io.github.jasondorjeshort.dystopo.app.PackSet]
 * opens, each through a small [DemPyramid] of its own, and the online DEM's own tiles ([onlinePyramid]: the map's fetcher,
 * so the same cache and the same Mobile data and session limit rules as the map's online tiles).
 *
 * A read holds the tile service it was opened on ([DystopoApp.leaseTiles]) until [close], so a map opening or closing
 * meanwhile does not close the archives under it. [epoch] names the maps open and whether the phone has a network, so a
 * view can tell that an answer it keeps was made before either changed.
 */
class TerrainRead private constructor(private val lease: AutoCloseable, val tiles: TileService, withOnline: Boolean) : AutoCloseable {
    private val pyramids = HashMap<String, DemPyramid>()
    @Volatile var tilesRead = 0
        private set
    @Volatile var bytesRead = 0L
        private set
    val tally = MergedTerrain.Tally()

    /** The online DEM, when a source is set and the caller asks for it. */
    val online: OnlineTiles? = if (withOnline) tiles.online else null

    // ---- T368: the areas' held cells, the setting and the network's stored range (ADR 0066 decisions 1 and 2) ----

    /**
     * T368: the online source ranked by its stored depth (`MergedTerrain`'s `onlineByDepth`, T354's input), ahead of a map only
     * where it stores deeper, under "More detail from online" ([io.github.jasondorjeshort.dystopo.app.online.OnlineDetail]); its
     * depth is the stored maximum (`DirectSources.maxZoom`, a server's `demMaxZoom`), not the URL range. Off, or no fetch
     * allowed now (offline, Mobile data, the session limit): last, as before. `GET /holdings` `readZoom` follows the same rule.
     */
    val onlineByDepth: Boolean = runCatching { OnlineTiles.aheadByDepth(online?.onlineMember()) }.getOrDefault(false)

    /**
     * T368: each finished tile area as a member of its own (its box, its elevation zooms), read from its held cells only
     * ([TileService.heldDem]: the cache and the area's own keys, never the network), so a Panorama inside a coarse map reads an
     * area's deeper cells; cells it does not hold are NaN and the next source fills them.
     */
    private fun areaSources(): List<ElevationSource> = runCatching { tiles.heldAreas() }.getOrDefault(emptyList()).mapNotNull { a ->
        val s = a.dem ?: return@mapNotNull null
        val box = s.bounds ?: return@mapNotNull null
        val cells = a.cells
        val name = "area:${a.name}"
        ElevationSource(name, s.maxZoom, box) { w ->
            val p = synchronized(pyramids) {
                pyramids.getOrPut(name) {
                    DemPyramid(s.minZoom, s.maxZoom, TileService.demDecoder(), cacheTiles = 48) { z, x, y ->
                        if (cells != null && !cells(io.github.jasondorjeshort.dystopo.packs.Holdings.Role.DEM, z, x, y)) null
                        else tiles.heldDem(z, x, y)?.also { b -> synchronized(this) { tilesRead++; bytesRead += b.size } }
                    }
                }
            }
            p.window(w.zoom, w.x0, w.y0, w.width, w.height)
        }
    }
    // end T368

    val sources: List<ElevationSource> = MergedTerrain.order(
        tiles.packs.demPacks.map { pk ->
            val h = pk.header
            val box = PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat)
            val name = tiles.packs.packOf(pk) ?: pk.name
            ElevationSource(name, h.maxZoom, box, MergedTerrain.wholeTileReach(box, h.minZoom), chosen = tiles.packs.dem === pk) { w -> // T354: the chosen map is the tie
                val p = synchronized(pyramids) {
                    pyramids.getOrPut(name) {
                        DemPyramid(h.minZoom, h.maxZoom, TileService.demDecoder(), cacheTiles = 48) { z, x, y ->
                            pk.tile(z, x, y)?.also { b -> synchronized(this) { tilesRead++; bytesRead += b.size } }
                        }
                    }
                }
                p.window(w.zoom, w.x0, w.y0, w.width, w.height)
            }
        } + areaSources() + listOfNotNull(online?.let { o -> ElevationSource(MergedTerrain.ONLINE, tiles.networkDemStored ?: OnlineTiles.DEM_MAX_ZOOM, null) { w -> onlinePyramid(o).window(w.zoom, w.x0, w.y0, w.width, w.height) } }),
        onlineByDepth,
    )

    @Volatile private var onlinePyramid: DemPyramid? = null
    @Volatile var onlineTiles = 0
        private set
    @Volatile var onlineBytes = 0L // T368
        private set

    /**
     * The online DEM's tiles alone, from the fetcher (its cache first: what was viewed earlier and the tiles of an offline map
     * kept as tiles, then the network under the fetcher's rules). Not [OnlineTiles.pyramid], which answers a tile a map's box
     * overlaps from that map: the merged read asks the maps itself, and those cells would read as the online source's.
     */
    private fun onlinePyramid(o: OnlineTiles): DemPyramid = onlinePyramid ?: synchronized(this) {
        onlinePyramid ?: DemPyramid(o.demMinZoom, OnlineTiles.DEM_MAX_ZOOM, TileService.demDecoder(), cacheTiles = 48) { z, x, y ->
            if (z !in o.demMinZoom..OnlineTiles.DEM_MAX_ZOOM) null
            else (o.fetcher.get("dem", "$z/$x/$y.png") as? io.github.jasondorjeshort.dystopo.app.online.Fetch.Hit)?.bytes?.also { b -> synchronized(this) { onlineTiles++; onlineBytes += b.size } } // T368: and their bytes
        }.also { onlinePyramid = it }
    }

    /** [w] by the rule, counted in [tally]. */
    fun read(w: MercatorWindow): ElevationGrid = MergedTerrain.read(w, sources, tally, onlineByDepth) // T368: the setting

    /** The zoom to read ([lat], [lon]) at, at most [cap]; null when nothing answers there. */
    fun pointZoom(lat: Double, lon: Double, cap: Int): Int? = MergedTerrain.pointZoom(sources, lat, lon, cap, onlineByDepth) // T368

    /** What answered so far, in the views' words and as JSON. */
    fun answered(): Answered = Answered(tally.maps, tally.online, tally.complete, tally.json() + linkedMapOf("tiles" to tilesRead, "bytes" to bytesRead, "onlineAsked" to (online != null), "onlineByDepth" to onlineByDepth, "onlineTiles" to onlineTiles, "onlineBytes" to onlineBytes, "epoch" to epoch.json()))

    /** The epoch the read was opened in. */
    val epoch: Epoch = Companion.epoch()

    override fun close() {
        synchronized(pyramids) { pyramids.clear() }
        lease.close()
    }

    /**
     * What a read took its cells from: [maps] the offline maps (by name, in the order they first gave cells), [online] the
     * online source, [complete] no cell left without data. [text] is the terrain line's "from the ..." part.
     */
    class Answered(val maps: List<String>, val online: Boolean, val complete: Boolean, val counts: Map<String, Any?>) {
        val text: String? get() = TerrainText.from(maps.size, online)
        fun json(): Map<String, Any?> = linkedMapOf<String, Any?>("text" to text, "maps" to maps, "online" to online, "complete" to complete) + counts
    }

    companion object {
        /** Opens a read on the tile service now published; [online] false reads the offline maps alone (the viewshed). */
        fun open(online: Boolean = true): TerrainRead {
            val app = DystopoApp.instance
            val lease = app.leaseTiles()
            return try {
                TerrainRead(lease, app.tiles, online)
            } catch (e: Throwable) {
                lease.close()
                throw e
            }
        }

        /**
         * The maps open and whether the phone has a network now: a kept answer made under another epoch may be stale (a map
         * opened or closed: any answer; the network came back: an answer with cells missing, or none). Not the fetcher's
         * generation: with the phone reading the sources itself it moved on every offline read (a local answer counts as
         * the source reachable), so a "none" was read again and again with no network.
         */
        fun epoch(): Epoch {
            val app = DystopoApp.instance
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - networkAt > 1_000) { // the views ask on every composition: the binder call at most once a second
                val was = network
                network = hasNetwork(app)
                networkAt = now
                // Back: the fetcher's backoff and failure memo from the offline reads would answer the read again with
                // "unavailable" for up to a minute, and that answer, made with a network, would be kept.
                if (network && !was) runCatching { app.tiles.online?.fetcher?.retryNow() }
            }
            // T358 (T352's follow-up): the source counts as reachable only with a network. Offline the fetcher's state moved to
            // `online` on a local answer (a cached tile, an area's cell), so an incomplete answer made a moment before read as
            // stale (`!reachable && now.reachable`) and the Panorama's place went stale between two reads offline.
            val reachable = network && app.tiles.online?.fetcher?.state == "online"
            return Epoch(System.identityHashCode(app.tiles.packs), network, reachable)
        }

        /** T359: the one rule ([io.github.jasondorjeshort.dystopo.app.online.OnlineUi.hasNetwork]); none to ask reads no network. */
        private fun hasNetwork(app: DystopoApp): Boolean = io.github.jasondorjeshort.dystopo.app.online.OnlineUi.hasNetwork(app, orElse = false)

        @Volatile private var network = false
        @Volatile private var networkAt = Long.MIN_VALUE / 2
    }

    /**
     * [maps] the opened map set's identity, [network] whether the phone had a network, [reachable] whether the online source
     * answered lately (the fetcher's `online`: a read made in the minute after the network came back can meet its backoff).
     */
    data class Epoch(val maps: Int, val network: Boolean, val reachable: Boolean = network) {
        /** Whether an answer made under this epoch, [complete] or not, should be read again now ([now]). */
        fun stale(now: Epoch, complete: Boolean): Boolean =
            maps != now.maps || (!complete && ((!network && now.network) || (!reachable && now.reachable)))

        fun json(): Map<String, Any?> = linkedMapOf("maps" to maps, "network" to network, "reachable" to reachable)
    }
}

/** T340: the words of the merged read (ADR 0031: built in alpha, the owner adjusts them). */
object TerrainText {
    /** The terrain line's source after "from the": "offline map", "2 offline maps", "online source", "offline map and the online source". */
    fun from(maps: Int, online: Boolean): String? {
        val m = when (maps) {
            0 -> null
            1 -> "offline map"
            else -> "$maps offline maps"
        }
        return when {
            m != null && online -> "$m and the online source"
            m != null -> m
            online -> "online source"
            else -> null
        }
    }
}

// ---- T358: one point's elevation, held only or held now with the fetch behind (ADR 0066 build task 4) ----

/**
 * T358 (ADR 0066 decision 3; proposal 0066's Question (a), the owner's answer): the one read of one point's elevation that
 * every point reader takes, by the map's rule. Everything the phone holds is ranked by stored depth at the point, whatever
 * any setting says: the offline maps (deepest first, the map chosen last the tie, T357's blend within
 * [io.github.jasondorjeshort.dystopo.dem.EdgeBlend.BAND_M] inside a finer map's edge, as the map's edge tiles), and the held
 * cells ([TileService.heldDem]: the tile cache and the downloaded areas' cells), ranked by the zoom a cell is held at and
 * ahead of the maps only where that is deeper ([PointRule.floor]). Neither mode waits for the network:
 * - [Mode.HELD]: what the phone holds; nothing is started. The terrain-aided position estimate, the ground under each
 *   aircraft and a dropped waypoint (`Trips.elevationAt`, `Trips.elevation()`).
 * - [Mode.BEHIND]: the held answer at once, and where the network stores deeper than what answered (under "More detail from
 *   online", a fetch allowed now) or nothing is held there, the point's elevation tile at the network's stored depth is
 *   fetched behind ([PointRule.behindZoom]) through T368's queue ([OnlineTiles.fetchBehind]: one fetch per tile while it is
 *   in flight or landed, a failed one asked again by a later read), so a later read has it. The readout, the pressure card,
 *   the long-press card and the route tools.
 *
 * What answers at a point is worked out once per z15 tile and kept until a fetch behind lands, the maps change, or
 * [HERE_MS] passes. [pyramidAt] composes the same sources into a pyramid for the readers that sweep an area around a point
 * (the long-press card's sun and sight line, the way down, the route's exposure, shade and start time).
 */
object PointRead {
    enum class Mode(val code: String) { HELD("held"), BEHIND("behind") }

    /**
     * One read: [elevationM] (NaN for none), [source] the map's name, `cache` (the tile cache: fetched, viewed earlier or an
     * area's pinned version) or `area` (an area's own cell), [zoom] the zoom read at, [heldZoom] the held cell's zoom where
     * one is deeper than the maps, [behind] what was done about the network (`asked z/x/y`, or why not: `held` nothing
     * deeper, `off` the setting, `notAllowed`, `noSource`, `launch`), null in [Mode.HELD]; [ms] the read's time.
     */
    class Answer(val elevationM: Double, val source: String?, val zoom: Int?, val heldZoom: Int?, val behind: String?, val ms: Double) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "elevationM" to elevationM.takeIf { it.isFinite() }?.let { Math.round(it * 10) / 10.0 }, "source" to source, "zoom" to zoom,
            "heldZoom" to heldZoom, "behind" to behind, "ms" to Math.round(ms * 100) / 100.0,
        )
    }

    /** What answers at one point: [sources] in any order, [zoom] the read's, [held] the held cells' zoom ([floor] and up), [label] theirs. */
    private class Here(val sources: List<ElevationSource>, val zoom: Int?, val held: Int?, val floor: Int, val label: String?)

    /** One state of what the phone holds: the tile service, its maps, the fetches landed; the held pyramids and the points worked out. */
    private class Gen(val tiles: TileService, val packs: Any, val landed: Long, val at: Long) {
        val maps: List<ElevationSource> = tiles.packs.demPacks.map { pk ->
            val h = pk.header
            val box = PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat)
            val p = synchronized(mapPyramids) {
                mapPyramids.getOrPut(pk) { DemPyramid(h.minZoom, h.maxZoom, TileService.demDecoder(), cacheTiles = 16) { z, x, y -> pk.tile(z, x, y) } }
            }
            ElevationSource(tiles.packs.packOf(pk) ?: pk.name, h.maxZoom, box, MergedTerrain.wholeTileReach(box, h.minZoom), chosen = tiles.packs.dem === pk) { w ->
                p.window(w.zoom, w.x0, w.y0, w.width, w.height)
            }
        }
        val minZoom: Int? = tiles.packs.demPacks.minOfOrNull { it.header.minZoom }
        val held = HashMap<Int, DemPyramid>()
        val here = object : LinkedHashMap<Long, Here>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Here>?) = size > HERE_KEEP
        }
        val composed = object : LinkedHashMap<String, DemPyramid>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemPyramid>?) = size > COMPOSED_KEEP
        }
    }

    private val mapPyramids = java.util.WeakHashMap<io.github.jasondorjeshort.dystopo.app.Pack, DemPyramid>()
    @Volatile private var gen: Gen? = null

    // Counters for `GET /readout` › `ground` and `GET /readout/ground`.
    private val reads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val asked: MutableSet<String> = java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > ASKED_KEEP
    })
    private val askedTotal = java.util.concurrent.atomic.AtomicLong()
    private val readNs = java.util.concurrent.ConcurrentHashMap<String, LongArray>() // per mode: reads, total ns, max ns
    @Volatile private var gens = 0L

    private fun bump(k: String) = reads.getOrPut(k) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()

    private fun current(g: Gen?, tiles: TileService, landed: Long, now: Long): Gen? =
        g?.takeIf { it.tiles === tiles && it.packs === tiles.packs && it.landed == landed && now - it.at < HERE_MS }

    private fun gen(): Gen {
        val tiles = DystopoApp.instance.tiles
        val landed = io.github.jasondorjeshort.dystopo.app.online.BehindRefresh.dem.landedTotal
        val now = android.os.SystemClock.elapsedRealtime()
        current(gen, tiles, landed, now)?.let { return it }
        return synchronized(this) { current(gen, tiles, landed, now) ?: Gen(tiles, tiles.packs, landed, now).also { gen = it; gens++ } }
    }

    private fun heldPyramid(g: Gen, floor: Int): DemPyramid = synchronized(g.held) {
        g.held.getOrPut(floor) {
            DemPyramid(floor, PointRule.MAX_ZOOM, TileService.demDecoder(), cacheTiles = 16) { z, x, y -> runCatching { g.tiles.heldDem(z, x, y) }.getOrNull() }
        }
    }

    /** What answers at ([lat], [lon]) in [g]: worked out once per z15 tile. */
    private fun here(g: Gen, lat: Double, lon: Double): Here {
        val tz = PointRule.MAX_ZOOM
        val key = (WebMercator.lonToX(lon, tz).toLong() shl 32) or (WebMercator.latToY(lat, tz).toLong() and 0xffffffffL)
        synchronized(g.here) { g.here[key] }?.let { return it }
        val floor = PointRule.floor(MergedTerrain.pointZoom(g.maps, lat, lon, PointRule.MAX_ZOOM))
        var held: Int? = null
        var label: String? = null
        for (hz in PointRule.MAX_ZOOM downTo floor) {
            val x = WebMercator.lonToX(lon, hz).toInt()
            val y = WebMercator.latToY(lat, hz).toInt()
            if (runCatching { g.tiles.heldDem(hz, x, y) }.getOrNull() != null) {
                held = hz
                label = if (g.tiles.online?.fetcher?.hasCached("dem", "$hz/$x/$y.png") == true) "cache" else "area"
                break
            }
        }
        val sources = if (held == null) g.maps else g.maps + ElevationSource(MergedTerrain.ONLINE, held, null) { w ->
            if (w.zoom < floor) null else heldPyramid(g, floor).window(w.zoom, w.x0, w.y0, w.width, w.height)
        }
        val h = Here(sources, MergedTerrain.pointZoom(sources, lat, lon, PointRule.MAX_ZOOM, onlineByDepth = true), held, floor, label)
        synchronized(g.here) { g.here[key] = h }
        return h
    }

    /** ([lat], [lon])'s elevation by the rule; [tag] names the reader in the counters. Never waits for the network. */
    fun at(lat: Double, lon: Double, mode: Mode, tag: String = "other"): Answer {
        val t0 = System.nanoTime()
        val a = try {
            read(lat, lon, mode)
        } catch (e: Exception) {
            Answer(Double.NaN, null, null, null, "error: ${e.javaClass.simpleName}", 0.0)
        }
        val ns = System.nanoTime() - t0
        bump("${mode.code}.$tag")
        readNs.compute(mode.code) { _, v -> (v ?: LongArray(3)).also { it[0]++; it[1] += ns; if (ns > it[2]) it[2] = ns } }
        return Answer(a.elevationM, a.source, a.zoom, a.heldZoom, a.behind, ns / 1e6)
    }

    private fun read(lat: Double, lon: Double, mode: Mode): Answer {
        val g = gen()
        val h = here(g, lat, lon)
        val behind = if (mode == Mode.BEHIND) behind(g, lat, lon, h) else null
        val z = h.zoom ?: return Answer(Double.NaN, null, null, h.held, behind, 0.0)
        val fx = WebMercator.lonToX(lon, z) * 256 - 0.5
        val fy = WebMercator.latToY(lat, z) * 256 - 0.5
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val tally = MergedTerrain.Tally()
        val v = MergedTerrain.read(MercatorWindow(z, x0, y0, 2, 2), h.sources, tally, onlineByDepth = true).values
        val e = PointRule.bilinear(v[0], v[1], v[2], v[3], fx - x0, fy - y0)
        val top = tally.cells.maxByOrNull { it.value }?.key
        return Answer(e, if (top == MergedTerrain.ONLINE) h.label else top, z, h.held, behind, 0.0)
    }

    /** [Mode.BEHIND]: starts the fetch behind the point's tile where [PointRule.behindZoom] says so; what was done. */
    private fun behind(g: Gen, lat: Double, lon: Double, h: Here): String {
        val o = g.tiles.online ?: return "noSource"
        val m = o.onlineMember()
        val z = PointRule.behindZoom(h.zoom, m.demMaxZoom, m.detail, m.allowed)
            ?: return when { !m.allowed -> "notAllowed"; h.zoom != null && !m.detail -> "off"; else -> "held" }
        val refresh = io.github.jasondorjeshort.dystopo.app.online.BehindRefresh
        if (!refresh.launchLoaded && !DystopoApp.instance.map.fullyLoaded) return "launch" // the next read asks again
        val x = WebMercator.lonToX(lon, z).toInt()
        val y = WebMercator.latToY(lat, z).toInt()
        val path = "$z/$x/$y"
        if (synchronized(asked) { asked.add(path) }) askedTotal.incrementAndGet()
        o.fetchBehind(z, x, y, io.github.jasondorjeshort.dystopo.packs.Holdings.Role.DEM) // once per tile while in flight or landed
        return "asked $path"
    }

    /** A sampler over [at] in [mode] ([tag] in the counters): NaN where nothing answers. */
    fun sampler(mode: Mode, tag: String): io.github.jasondorjeshort.dystopo.routing.ElevationSampler =
        io.github.jasondorjeshort.dystopo.routing.ElevationSampler { lat, lon -> at(lat, lon, mode, tag).elevationM }

    /** Whether anything could answer a point read: a map with elevation, a source set, or a finished tile area. */
    fun anything(): Boolean {
        val t = DystopoApp.instance.tiles
        return t.packs.demPacks.isNotEmpty() || t.online != null || runCatching { t.heldAreas() }.getOrDefault(emptyList()).isNotEmpty()
    }

    /**
     * The sources at ([lat], [lon]) composed into one pyramid (the maps with T357's blend, the held cells ahead where deeper),
     * up to the zoom read there, for the readers that sweep an area around the point; null where nothing is held at the
     * point. [Mode.BEHIND] fetches the point's tile behind as [at] does.
     */
    fun pyramidAt(lat: Double, lon: Double, mode: Mode, tag: String = "area"): DemPyramid? {
        val g = gen()
        val h = here(g, lat, lon)
        bump("${mode.code}.$tag")
        if (mode == Mode.BEHIND) runCatching { behind(g, lat, lon, h) }
        val top = h.zoom ?: return null
        val key = "${h.floor}/${h.held}/$top"
        return synchronized(g.composed) {
            g.composed.getOrPut(key) {
                val low = minOf(g.minZoom ?: h.floor, h.floor, top)
                DemPyramid(low, top, io.github.jasondorjeshort.dystopo.dem.RawElevations, cacheTiles = 16) { z, x, y ->
                    MergedTerrain.read(MercatorWindow(z, x * 256, y * 256, 256, 256), h.sources, onlineByDepth = true).values
                        .takeUnless { v -> v.all { it.isNaN() } }?.let(io.github.jasondorjeshort.dystopo.dem.RawElevations::encode)
                }
            }
        }
    }

    /** The source at ([lat], [lon]) as [at] names it, read held (nothing started, not counted as a reader). */
    fun sourceAt(lat: Double, lon: Double): String? = runCatching { read(lat, lon, Mode.HELD).source }.getOrNull()

    /** `GET /readout` › `ground` and `GET /readout/ground`: the counters, and [here] read held (nothing started) when given. */
    fun json(here: Pair<Double, Double>? = null): Map<String, Any?> = linkedMapOf(
        "here" to here?.let { (la, lo) -> at(la, lo, Mode.HELD, "peek").json() },
        "reads" to reads.toSortedMap().mapValues { it.value.get() },
        "readMs" to readNs.mapValues { (_, v) -> linkedMapOf("reads" to v[0], "avg" to if (v[0] > 0) Math.round(v[1] / 1e4 / v[0]) / 100.0 else null, "max" to Math.round(v[2] / 1e4) / 100.0) },
        "tilesAsked" to askedTotal.get(), "generations" to gens,
    )

    /** How long what answers at a point is kept (a cell the cache gained since from the map's own fetches is seen after it). */
    const val HERE_MS = 30_000L
    private const val HERE_KEEP = 256
    private const val COMPOSED_KEEP = 2
    private const val ASKED_KEEP = 4096
}

/** T358: the point read's pure rules (tested, `PointRuleTest`). */
object PointRule {
    /** The deepest elevation zoom a point is read at (`OnlineTiles.DEM_MAX_ZOOM`, `HeldTiles.MAX_DEM`). */
    const val MAX_ZOOM = 15

    /** The shallowest zoom a held cell must be at to rank ahead of the maps at a point they read at [mapsZoom] (null: none there). */
    fun floor(mapsZoom: Int?): Int = mapsZoom?.plus(1) ?: 0

    /**
     * The zoom of the point's tile to fetch behind a read that answered at [heldZoom] (null: nothing held there), or null:
     * none when no fetch may run now ([allowed]) or the source stores nothing ([networkStored]); where nothing is held, the
     * network's tile, as the map's tiles ask the network there whatever the setting; where something is, only under the
     * setting ([detail]) and where the network stores deeper (T368's `NetworkAhead`).
     */
    fun behindZoom(heldZoom: Int?, networkStored: Int?, detail: Boolean, allowed: Boolean): Int? {
        if (!allowed || networkStored == null) return null
        val z = minOf(networkStored, MAX_ZOOM)
        if (heldZoom == null) return z
        return z.takeIf { detail && it > heldZoom }
    }

    /** `DemElevation`'s bilinear sample of four cells ([tx], [ty] the fractions), the nearest one where any is missing. */
    fun bilinear(a: Float, b: Float, c: Float, d: Float, tx: Double, ty: Double): Double {
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) {
            return when {
                tx < 0.5 && ty < 0.5 -> a
                tx >= 0.5 && ty < 0.5 -> b
                tx < 0.5 -> c
                else -> d
            }.toDouble()
        }
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }
}
// end T358
