package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.TileResult
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.mvt.MvtEncoder
import io.github.jasondorjeshort.dystopo.mvt.MvtOverzoom
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * The online half of [io.github.jasondorjeshort.dystopo.app.TileService] for one opened [PackSet]:
 * every tile the active pack does not cover comes from the server through [fetcher] (disk cache first).
 *
 * - DEM: a tile is the pack's when it lies inside the pack's bounds at or above the pack's lowest
 *   zoom (deeper tiles are synthesized from the pack as before, so a pack area never needs the network);
 *   anywhere else it is fetched (`/dem/z/x/y.png`, zooms [DEM_MIN_ZOOM] to [DEM_MAX_ZOOM]).
 * - OSM: the same rule with the vector pack (`/osm`, up to [OSM_MAX_ZOOM]); vector tiles arrive
 *   gunzipped. Glyphs missing on the device come from `/glyphs`.
 * - Contours are generated on the device exactly like pack contours ([ContourTiles] through a
 *   [DemPyramid]); the pyramid reads pack tiles where the pack covers and fetched tiles elsewhere, so
 *   contour tiles across a pack edge join; such a tile reads at least at the pack's first zoom (T98,
 *   [serverContourOptions]), so the pack answers its part when the server does not. The DEM tiles a contour tile
 *   needs are fetched in parallel first. A contour tile is disk-cached only when every DEM tile it needed was there: one made while a
 *   fetch failed (offline) is served but not kept, and the pyramid is rebuilt when the server comes back.
 */
class OnlineTiles(
    val packs: PackSet,
    val fetcher: OnlineFetcher,
    private val service: OnlineService,
    private val decoder: ImageDecoder,
    /** The contour disk-cache version without the pack identity (online contours do not depend on the pack). */
    private val contourVersion: String,
    private val contourOptions: ContourTileOptions = ContourTileOptions(units = io.github.jasondorjeshort.dystopo.app.AppSettings.units), // T33: units preference
    /**
     * T80: answer within this long or say "not yet" (503). With the phone rendering terrain itself a cold DEM or
     * contour tile can take longer than MapLibre waits; a timed-out request counts as a connection error, which
     * MapLibre retries only when the network changes, so the tile stayed blank. A 503 is a server error, which it
     * retries with backoff, by then from the cache (the work goes on in the background). Null: wait as before.
     */
    private val answerWithinMs: Long? = null,
) {
    /** A tile whose data is still being fetched or rendered ([answerWithinMs]); the loopback server answers 503. */
    class NotReady(message: String) : RuntimeException(message)

    /** Requests answered 503 because their tile was not ready within [answerWithinMs]. */
    val notReady = java.util.concurrent.atomic.AtomicLong()

    private fun notReady(what: String): Nothing {
        notReady.incrementAndGet()
        throw NotReady("$what is still being made; try again")
    }
    // ---- coverage ----

    private fun overlaps(p: Pack, z: Int, x: Int, y: Int): Boolean = tileOverlaps(bounds(p), z, x, y)

    private fun bounds(p: Pack): PackBounds = p.header.let { h -> PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat) }

    private fun inside(p: Pack, z: Int, x: Int, y: Int): Boolean {
        val h = p.header
        return WebMercator.xToLon(x.toDouble(), z) >= h.minLon && WebMercator.xToLon(x + 1.0, z) <= h.maxLon &&
            WebMercator.yToLat(y + 1.0, z) >= h.minLat && WebMercator.yToLat(y.toDouble(), z) <= h.maxLat
    }

    /** True when the DEM pack answers tile z/x/y (stored, or synthesized from its ancestors). */
    fun packCoversDem(z: Int, x: Int, y: Int): Boolean = packs.demFor(z, x, y) != null // T41: any pack

    /** T88: the encoded DEM tile z/x/y from the pack that answers it, or null (direct contour tiles across a pack's edge). */
    fun packDem(z: Int, x: Int, y: Int): ByteArray? = packs.demFor(z, x, y)?.tile(z, x, y)

    fun packCoversOsm(z: Int, x: Int, y: Int): Boolean = packs.osmFor(z, x, y) != null // T41: any pack

    /** True when the contour tile lies wholly inside the DEM pack: the pack path (and its cache) makes it, as without a server. */
    fun packCoversContour(z: Int, x: Int, y: Int): Boolean = packs.demContains(z, x, y) // T41: wholly inside any pack

    /**
     * T98: the plan options of contour tile z/x/y over a configured server's DEM ([DirectSources.contourOptions] for the
     * phone's own sources). A tile a DEM pack's bounds overlap reads its window at least at the shallowest zoom those
     * packs store ([readFrom]). Its plan samples a coarser zoom below z10 (a z8 tile z7, a z7 tile z6) while region
     * packs start at z8, and the pyramid's tiles above a pack's first zoom come from the server alone: with the server
     * unreachable (the post-wave 13 sweep's device tour, the debug build's default server not running) every z7 and z8
     * contour tile over the Rainier pack was all NaN and drew no line, in about 1 ms once the fetcher was in backoff.
     * Now the pack answers its part offline; online the server's tiles of that zoom fill the rest, as at deeper zooms.
     */
    fun serverContourOptions(base: ContourTileOptions, z: Int, x: Int, y: Int): ContourTileOptions =
        serverContourOptions(base, packs.demPacks.map { bounds(it) to it.header.minZoom }, z, x, y)

    // ---- tiles ----

    fun demTile(z: Int, x: Int, y: Int): TileResult {
        if (z !in DEM_MIN_ZOOM..DEM_MAX_ZOOM) return TileResult.notFound()
        val direct = fetcher.server == DirectSources.SERVER
        val askedAt = System.currentTimeMillis()
        if (direct) DirectTrace.mark("d? $z/$x/$y") // T88
        val f = answerWithinMs?.let { ms ->
            // T88: with the sources read here a request waits on a render; its own pool, not the six prefetch threads.
            val job = if (direct) service.direct.request { fetcher.get("dem", "$z/$x/$y.png") } else service.prefetch { fetcher.get("dem", "$z/$x/$y.png") }
            try {
                job.get(ms, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                DirectTrace.mark("d503 $z/$x/$y") // T88
                // T88: a reload once it is made, unless the request is from before the last reload (asked again since).
                val counts = !direct || service.direct.countsForRefresh(askedAt)
                if (direct && counts) service.direct.awaitedTile(z, x, y)
                return TileResult(503, "text/plain", "DEM $z/$x/$y is still being made; try again\n".toByteArray()).also { if (counts) notReady.incrementAndGet() }
            }
        } ?: fetcher.get("dem", "$z/$x/$y.png")
        return when (f) {
            is Fetch.Hit -> {
                if (direct) DirectTrace.mark("d200 $z/$x/$y") // T88
                service.recordDem(z, x, y, if (f.cached) OnlineService.CACHED else OnlineService.ONLINE)
                service.served("dem", f)
                TileResult(200, "image/png", f.bytes)
            }
            else -> {
                service.served("dem", f)
                // T155 (T138's follow-up): a 404 MapLibre never asks for again; recorded, fetched again later, and the
                // elevation source refreshed once it comes ([OnlineService.demFailed], [DemSourceRefresh]).
                if (f is Fetch.Unavailable) {
                    if (direct) DirectTrace.mark("d404 $z/$x/$y")
                    service.demFailed("$z/$x/$y.png")
                }
                widerDem(z, x, y) ?: TileResult.notFound() // T254: from a wider zoom the phone holds, else 404
            }
        }
    }

    /**
     * A vector tile: 200 with the tile, else 404 (MapLibre draws an empty tile). T138 (T123's leftover): a tile whose fetch
     * failed ([Fetch.Unavailable]: OpenFreeMap's stall guard gave up, the server or the network was down) is recorded with
     * [OnlineService.osmFailed], which fetches it again later and refreshes the map's vector source once it comes, so it is
     * drawn without a style reload. Not a 503: MapLibre never asks for an errored tile again and never counts the map as
     * fully loaded while one is in view (tried on the emulator; [StalledOsmTiles]).
     */
    fun osmTile(z: Int, x: Int, y: Int): TileResult {
        if (z !in 0..OSM_MAX_ZOOM) return TileResult.notFound()
        val path = "$z/$x/$y.pbf"
        val f = fetcher.get("osm", path, OnlineFetcher::gunzipIfNeeded)
        service.served("osm", f)
        if (f is Fetch.Unavailable) service.osmFailed(path)
        if (f is Fetch.Hit) return TileResult(200, "application/x-protobuf", f.bytes)
        // T254: not on the phone and not fetched: cut from the nearest ancestor the phone holds; 404 only when none is.
        return widerOsm.vector(z, x, y)?.let { c ->
            service.widerServed("osm", c.fromZoom)
            TileResult(200, "application/x-protobuf", c.bytes, widerZoom = c.fromZoom)
        } ?: TileResult.notFound()
    }

    /**
     * T254: vector tiles cut from the nearest ancestor in the online cache (or a pack, where the ancestor lies in one), for
     * [osmTile]. Cache reads only ([OnlineFetcher.cachedOnly]): nothing is fetched, cached or remembered as missing.
     */
    val widerOsm = WiderZoom { az, ax, ay ->
        (if (packCoversOsm(az, ax, ay)) packs.osmFor(az, ax, ay)?.tile(az, ax, ay)?.let(OnlineFetcher::gunzipIfNeeded) else null)
            ?: fetcher.cachedOnly("osm", "$az/$ax/$ay.pbf")
    }

    /**
     * T254: DEM tile z/x/y the phone lacks, from the nearest ancestor within [WiderZoom.MAX_DEPTH] levels that the online
     * cache or a pack holds, overzoomed bilinearly from that ancestor alone ([widerDemGrid]) as the pack path's
     * synthesis does ([io.github.jasondorjeshort.dystopo.app.TileService.demTile]). Null when no ancestor is on the phone.
     * Not cached; recorded for [viewSource] ([OnlineService.recordWider]).
     */
    private fun widerDem(z: Int, x: Int, y: Int): TileResult? {
        val started = System.nanoTime()
        var found: Triple<Int, Int, Int>? = null
        for (d in 1..WiderZoom.MAX_DEPTH) {
            val az = z - d
            if (az < demMinZoom) break
            val ax = x shr d
            val ay = y shr d
            val has = if (packCoversDem(az, ax, ay)) packs.demFor(az, ax, ay)?.tile(az, ax, ay) != null else fetcher.hasCached("dem", "$az/$ax/$ay.png")
            if (has) {
                found = Triple(az, ax, ay)
                break
            }
        }
        val from = found ?: return null
        // A pyramid of its own over the ancestor's level, read from the phone only: the shared one ([pyramid]) would remember
        // the missing deeper tiles, and a contour or shade tile made from it later would overzoom the ancestor without
        // knowing a tile was missing, and be kept in the cache.
        val grid = widerDemGrid(z, x, y, from.first, decoder) { tz, tx, ty ->
            if (packCoversDem(tz, tx, ty)) packs.demFor(tz, tx, ty)?.tile(tz, tx, ty) else fetcher.cachedOnly("dem", "$tz/$tx/$ty.png")
        } ?: return null
        val png = io.github.jasondorjeshort.dystopo.app.TileService.terrariumPng(grid)
        service.recordWider(z, x, y, from)
        service.widerServed("dem", from.first)
        widerDemTiming.add((System.nanoTime() - started) / 1e6, mapOf("tile" to "$z/$x/$y", "from" to from.first))
        return TileResult(200, "image/png", png, widerZoom = from.first)
    }

    /** T254: [widerDem]'s times (`GET /online` `wider.dem`). */
    val widerDemTiming = io.github.jasondorjeshort.dystopo.app.Timing(keepRecent = 12)

    /**
     * A glyph range the device does not have, from the server; null when the server has none either (the
     * caller answers an empty range). Unreachable is a 503, not an empty range: MapLibre keeps an empty
     * range for the session, so labels would stay missing after the network returns.
     */
    fun glyph(fontstack: String, range: String): TileResult? {
        val font = URLEncoder.encode(URLDecoder.decode(fontstack, "UTF-8"), "UTF-8").replace("+", "%20")
        val f = fetcher.get("glyphs", "$font/$range.pbf")
        service.served("glyphs", f)
        return when (f) {
            is Fetch.Hit -> f.bytes.takeIf { it.isNotEmpty() }?.let { TileResult(200, "application/x-protobuf", it) }
            Fetch.Missing -> null
            is Fetch.Unavailable -> TileResult(503, "text/plain", "glyphs unavailable: ${f.reason}\n".toByteArray())
        }
    }

    // ---- contours ----

    /** Set on the generating thread when a DEM tile could not be fetched (the contour tile is then not cached). */
    private val incomplete = ThreadLocal<Boolean>()

    /** T88: the contour tile being made on this thread missed a DEM tile (offline): served, not kept ([DirectSources.contourPyramid]). */
    internal fun noteIncomplete() = incomplete.set(true)

    /**
     * T87: the shallowest DEM zoom this source has: [DEM_MIN_ZOOM] for a server, [DirectSources.DEM_MIN_ZOOM] when
     * the phone reads the sources itself. The pyramid starts there, so a contour tile that samples a shallower zoom
     * (a z8 tile reads DEM z7) is averaged from this zoom's tiles. Starting the pyramid lower left such a window to
     * the empty shallower levels only: with a DEM pack (min zoom 8) and no server, the z8 contour tiles over the
     * pack's edge came out with no lines at all.
     */
    val demMinZoom: Int get() = if (fetcher.server == DirectSources.SERVER) DirectSources.DEM_MIN_ZOOM else DEM_MIN_ZOOM

    /** Encoded DEM tile for the pyramid: the pack's where it covers, else fetched; null when nobody has it. */
    private fun demBytes(z: Int, x: Int, y: Int): ByteArray? {
        if (packCoversDem(z, x, y)) return packs.demFor(z, x, y)?.tile(z, x, y) // T41
        if (z !in demMinZoom..DEM_MAX_ZOOM) return null
        return when (val f = fetcher.get("dem", "$z/$x/$y.png")) {
            is Fetch.Hit -> f.bytes
            Fetch.Missing -> null
            is Fetch.Unavailable -> {
                incomplete.set(true)
                service.demFailed("$z/$x/$y.png") // T155: the shade or contour tile made without it is drawn again once it comes
                null
            }
        }
    }

    private class Pyramid(val generation: Int, val pyramid: DemPyramid)

    @Volatile private var current: Pyramid? = null

    /** The pyramid for the server's current online generation; a new one after the server comes back (its missing-tile memory is stale). */
    fun pyramid(): DemPyramid {
        val gen = fetcher.generation
        current?.takeIf { it.generation == gen }?.let { return it.pyramid }
        synchronized(this) {
            current?.takeIf { it.generation == gen }?.let { return it.pyramid }
            val p = DemPyramid(demMinZoom, DEM_MAX_ZOOM, decoder, cacheTiles = 96) { tz, tx, ty -> demBytes(tz, tx, ty) }
            current = Pyramid(gen, p)
            return p
        }
    }

    private fun dropPyramid() = synchronized(this) { current = null }

    /** T155: DEM tiles came back after a stall; the pyramid may remember them as missing, so the next shade or contour tile takes a new one. */
    fun forgetMissing() = dropPyramid()

    /** T127: drops the online pyramid's decoded DEM tiles (made again from the online cache); the bytes dropped. */
    fun trimMemory(): Long = synchronized(this) {
        widerOsm.trimMemory() // T254: the decoded ancestors
        val p = current?.pyramid
        current = null
        p?.let { it.cachedTiles.toLong() * it.tileSize * it.tileSize * 4 } ?: 0L
    }

    /**
     * Contour tile z/x/y for an area the pack does not (wholly) cover: from the online cache, else made by
     * [generate] over [pyramid] (TileService's own generation path: slots, stats) after the DEM tiles it
     * samples were fetched in parallel.
     */
    fun contourTile(z: Int, x: Int, y: Int, generate: (DemPyramid) -> ByteArray): ByteArray {
        // Tiles touching the pack mix pack and server DEM; they are not kept (a pack switch would make them stale).
        val keep = packs.demPacks.none { overlaps(it, z, x, y) } // T41: any pack
        // T74: keyed on the source's DEM content version too, so a server whose DEM changed never serves old contours.
        val key = "${fetcher.derivedPrefix("contours-$contourVersion")}/$z/$x/$y.pbf"
        if (keep) service.cache.get(key)?.let {
            service.contourCacheHits.incrementAndGet()
            return it
        }
        // T88: a tile made without some out-of-view halo tiles is kept under a key naming them, valid while they are still missing.
        if (keep) DirectSources.haloSkippedFor(this, contourOptions, z, x, y).takeIf { it.isNotEmpty() }?.let { missing ->
            service.cache.get(contourKey(key, missing))?.let {
                service.contourCacheHits.incrementAndGet()
                return it
            }
        }
        prefetch(z, x, y)
        incomplete.set(false)
        val (bytes, skipped) = DirectSources.collectSkippedHalo { generate(pyramid()) }
        val missed = incomplete.get() == true
        incomplete.remove()
        if (missed) {
            service.contourIncomplete.incrementAndGet()
            // A one-off failure while the server is up: forget the pyramid's missing-tile memory now.
            if (fetcher.state == "online") dropPyramid()
        } else if (keep) {
            val stored = contourKey(key, skipped)
            service.cache.put(stored, bytes)
            // T106 (a T88 follow-up): the variants made earlier with other halo tiles missing are stale now; they stayed until the trim.
            service.contourHaloVariantsDropped.addAndGet(service.cache.clearVariants(key, stored).toLong())
        }
        service.contoursMade.incrementAndGet()
        return bytes
    }

    /**
     * T74: shade tile z/x/y (hillshade, slope; option set [optionsKey]) for an area no pack covers, made by
     * [generate] over [pyramid], disk-cached like online contours: in the online cache under the source and its
     * DEM version (`<source>/shade-<options>@<dem version>/z/x/y.png`), kept only when every DEM tile it needed
     * was there and no pack overlaps it.
     *
     * T104: [generate] also gets the zoom to read the window at ([shadeReadZoom]): a tile a DEM pack's bounds overlap
     * whose zoom is above the pack's first zoom (a z6 or z7 tile over a region pack from z8) reads at that first zoom,
     * box-averaged down, so the pack answers its part when the server does not. Before, such a tile read the
     * pyramid's own z6 or z7 level, which only the server fills: with the server unreachable it came out blank, as
     * T98 found for contour tiles ([serverContourOptions]).
     */
    fun shadeTile(optionsKey: String, z: Int, x: Int, y: Int, generate: (DemPyramid, Int?) -> ByteArray): ByteArray {
        val floor = packFloor(packs.demPacks.map { bounds(it) to it.header.minZoom }, z, x, y)
        val keep = floor == null
        val key = "${fetcher.derivedPrefix("shade-" + optionsKey.replace(Regex("[^A-Za-z0-9]"), "_"))}/$z/$x/$y.png"
        if (keep) service.cache.get(key)?.let {
            service.shadeCacheHits.incrementAndGet()
            return it
        }
        incomplete.set(false)
        val bytes = generate(pyramid(), shadeReadZoom(floor, z))
        val missed = incomplete.get() == true
        incomplete.remove()
        if (missed) {
            service.shadeIncomplete.incrementAndGet()
            if (fetcher.state == "online") dropPyramid()
        } else if (keep) {
            service.cache.put(key, bytes)
        }
        service.shadeMade.incrementAndGet()
        return bytes
    }

    /** Fetches the DEM tiles contour tile z/x/y samples, in parallel (into the disk cache), before it is generated. */
    private fun prefetch(z: Int, x: Int, y: Int) {
        val plan = DirectSources.prefetchPlan(this, contourOptions, z, x, y) // T85: the DEM tiles the tile reads
        val tiles = demTilesFor(plan.sampleZoom, plan.windowX, plan.windowY, plan.windowSize, minZoom = demMinZoom).filter { (tz, tx, ty) -> !packCoversDem(tz, tx, ty) }
        if (tiles.size <= 1) return
        // T88, direct sources (this is the path of contour tiles a DEM pack's bounds overlap): the renders run on
        // their own threads, not the six prefetch threads, and the wait is the contour tile's (MapLibre gives up on
        // a request after 10 s, and tracing comes on top). Tiles still rendering are marked for the reload: without
        // that the reload came at once, before they were made, and every tile was asked for again too early (the
        // device tour's cold z8 view reloaded three times and went idle after a minute).
        val direct = fetcher.server == DirectSources.SERVER
        val askedAt = System.currentTimeMillis()
        val futures = tiles.map { (tz, tx, ty) ->
            val get = { fetcher.get("dem", "$tz/$tx/$ty.png") }
            if (direct) service.direct.request(get) else service.prefetch(get)
        }
        val waitMs = (answerWithinMs ?: 30_000).let { if (direct) minOf(it, DirectSources.CONTOUR_WAIT_MS) else it }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        for (f in futures) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            runCatching { f.get(left, TimeUnit.NANOSECONDS) }
        }
        if (answerWithinMs != null && futures.any { !it.isDone }) {
            if (!direct) notReady("contour tile $z/$x/$y")
            if (!service.direct.countsForRefresh(askedAt)) throw NotReady("contour tile $z/$x/$y is still being made; try again")
            for ((t, f) in tiles.zip(futures)) if (!f.isDone) service.direct.awaitedTile(t.first, t.second, t.third)
            notReady("contour tile $z/$x/$y")
        }
    }

    // ---- indicator ----

    /**
     * Where the view centred at [lat], [lon] at map zoom [zoom] gets its terrain: `pack`, `online`
     * (fetched this session), `cached` (from the online disk cache), `wider` (T254: cut from a wider zoom the phone holds,
     * [WIDER]; the tile returned is that ancestor), `world` (T264: no terrain there, the built-in world overview draws the
     * map at this zoom, [WORLD]) or `none` (nothing loaded there yet).
     * T236 (ADR 0056 B3): with the DEM tile it matched (z, x, y; null for `pack` and `none`), so the chip can ask whether that
     * tile lies in an offline map on the list ([io.github.jasondorjeshort.dystopo.app.areas.Areas.isRecorded]).
     */
    fun viewSource(lat: Double, lon: Double, zoom: Double): Pair<String, Triple<Int, Int, Int>?> {
        // A 256-px DEM tile is requested one zoom deeper than the (512-px) map zoom.
        val zt = (floor(zoom).toInt() + 1).coerceIn(0, DEM_MAX_ZOOM)
        fun tile(z: Int) = Triple(z, WebMercator.lonToX(lon, z).toInt().coerceIn(0, (1 shl z) - 1), WebMercator.latToY(lat, z).toInt().coerceIn(0, (1 shl z) - 1))
        val (z0, x0, y0) = tile(zt)
        if (packCoversDem(z0, x0, y0)) return PACK to null
        for (z in listOf(zt, zt - 1, zt + 1, zt - 2).filter { it in 0..DEM_MAX_ZOOM }) {
            val t = tile(z)
            // T254: a tile cut from a wider zoom names its ancestor (the tile the phone holds, which an offline map may record).
            service.demSource(t.first, t.second, t.third)?.let { return it to (if (it == WIDER) service.widerAncestor(t.first, t.second, t.third) ?: t else t) }
        }
        return (if (worldDraws(lat, lon, zoom)) WORLD else NONE) to null
    }

    /**
     * T264: the view's 512-px vector tile at the map's zoom is answered by the built-in world overview (z0 to z5, where no
     * pack holds the tile). T275: also asked by [OnlineView.resolve] when the DEM tile [viewSource] matched is no longer on
     * the phone, so a stale record of terrain fetched earlier does not hide the overview the map draws.
     */
    fun worldDraws(lat: Double, lon: Double, zoom: Double): Boolean {
        val z = floor(zoom).toInt().coerceAtLeast(0)
        val n = 1 shl z
        return packs.worldAnswers(z, WebMercator.lonToX(lon, z).toInt().coerceIn(0, n - 1), WebMercator.latToY(lat, z).toInt().coerceIn(0, n - 1))
    }

    companion object {
        /**
         * T88: the online cache key of a contour tile made with the DEM tiles [missingHalo] of its halo left out
         * (NaN: out of view and not made, [DirectSources.haloSkipped]): [key] itself when none were, else [key]
         * with them named. Such a tile has lines stopping half a cell short of that seam; it was cached under the
         * plain key before, so the gap stayed after the neighbour was made. Now it is found only while the same
         * tiles are still missing, and made again whole once they exist.
         */
        fun contourKey(key: String, missingHalo: Collection<Triple<Int, Int, Int>>): String {
            if (missingHalo.isEmpty()) return key
            val names = missingHalo.distinct().sortedWith(compareBy({ it.first }, { it.second }, { it.third })).joinToString("_") { "${it.first}-${it.second}-${it.third}" }
            return "${key.removeSuffix(".pbf")}.halo-$names.pbf"
        }

        /**
         * T155: true when [key] is a halo variant ([contourKey]) made without one of the DEM tiles [tiles] (`z/x/y.png`
         * paths). Those tiles came back after a stall, so such a variant is never looked up again (the tile is made whole
         * now) and is deleted with the recovery instead of waiting for the trim. Pure, tested.
         */
        fun namesHaloTile(key: String, tiles: Set<String>): Boolean {
            val at = key.lastIndexOf(TileCache.HALO_INFIX)
            if (at < 0 || tiles.isEmpty()) return false
            val names = key.substring(at + TileCache.HALO_INFIX.length).substringBefore('.').split('_')
            return names.any { n -> n.split('-').takeIf { it.size == 3 }?.joinToString("/", postfix = ".png") in tiles }
        }

        /**
         * T98: [base], or [base] reading the DEM window at [minZoom] ([ContourTileOptions.sampleAtDemZoom], box-averaged
         * down to the plan's grid) when contour tile z/x/y's plan would read a shallower zoom. Never deeper than the
         * tile's spec zoom (one DEM pixel per screen pixel), so a z6 tile still reads z7 over a z8 pack.
         */
        fun readFrom(base: ContourTileOptions, minZoom: Int, z: Int, x: Int, y: Int): ContourTileOptions =
            if (ContourTiles.plan(z, x, y, base).readZoom >= minZoom) base else base.copy(sampleAtDemZoom = minZoom)

        /**
         * T98: [serverContourOptions] over DEM packs given as (bounds, first zoom): [readFrom] the deepest first zoom
         * of the packs whose bounds overlap contour tile z/x/y, so each of them answers its part; [base] when none does.
         */
        fun serverContourOptions(base: ContourTileOptions, packs: List<Pair<PackBounds, Int>>, z: Int, x: Int, y: Int): ContourTileOptions {
            val floor = packs.filter { tileOverlaps(it.first, z, x, y) }.maxOfOrNull { it.second } ?: return base
            return readFrom(base, floor, z, x, y)
        }

        /** T104: the deepest first zoom of the DEM packs (bounds, first zoom) whose bounds overlap tile z/x/y; null when none does. */
        fun packFloor(packs: List<Pair<PackBounds, Int>>, z: Int, x: Int, y: Int): Int? =
            packs.filter { tileOverlaps(it.first, z, x, y) }.maxOfOrNull { it.second }

        /**
         * T104: the zoom shade tile z/x/y reads its window at ([io.github.jasondorjeshort.dystopo.tiles.ShadeTiles.sample]):
         * the pack [floor] ([packFloor]) when it is deeper than [z] by at most
         * [io.github.jasondorjeshort.dystopo.tiles.ShadeTiles.MAX_OVERSAMPLE], else null (the tile's own zoom).
         */
        fun shadeReadZoom(floor: Int?, z: Int): Int? =
            floor?.takeIf { it > z && it - z <= io.github.jasondorjeshort.dystopo.tiles.ShadeTiles.MAX_OVERSAMPLE }

        /**
         * T254: DEM tile z/x/y overzoomed (bilinear) from zoom [fromZoom] alone, through a pyramid of its own whose only level
         * is [fromZoom], read through [read] (the phone's cache and packs): the ancestor and the neighbours its edge needs,
         * nothing deeper and nothing shared. Null when that level has no elevation in the tile. Pure, tested (`WiderZoomTest`).
         */
        fun widerDemGrid(z: Int, x: Int, y: Int, fromZoom: Int, decoder: ImageDecoder, read: (Int, Int, Int) -> ByteArray?): io.github.jasondorjeshort.dystopo.dem.ElevationGrid? {
            val p = DemPyramid(fromZoom, fromZoom, decoder, cacheTiles = 9) { tz, tx, ty -> if (tz == fromZoom) read(tz, tx, ty) else null }
            val ts = p.tileSize
            return p.window(z, x * ts, y * ts, ts, ts).takeUnless { g -> g.values.all { it.isNaN() } }
        }

        /** True when tile z/x/y and box [b] overlap (touching edges do not count). */
        fun tileOverlaps(b: PackBounds, z: Int, x: Int, y: Int): Boolean {
            val west = WebMercator.xToLon(x.toDouble(), z)
            val east = WebMercator.xToLon(x + 1.0, z)
            val north = WebMercator.yToLat(y.toDouble(), z)
            val south = WebMercator.yToLat(y + 1.0, z)
            return east > b.minLon && west < b.maxLon && north > b.minLat && south < b.maxLat
        }

        /** The live server's DEM zooms (`LiveTiles.LIVE_MIN_ZOOM`..`LIVE_MAX_ZOOM`). */
        const val DEM_MIN_ZOOM = 6
        const val DEM_MAX_ZOOM = 15
        /** Online contours start here, as on the server (`LiveTiles.CONTOUR_MIN_ZOOM`). */
        const val CONTOUR_MIN_ZOOM = 8
        /** OpenFreeMap and our packs stop at z14; MapLibre overzooms. */
        const val OSM_MAX_ZOOM = 14
        const val PACK = "pack"
        const val NONE = "none"
        /** T254: the view's terrain was cut from a wider zoom the phone holds ([WiderZoom]); T255 shows "from a wider zoom". */
        const val WIDER = OnlineService.WIDER
        /** T264: the view draws from the built-in world overview ([io.github.jasondorjeshort.dystopo.app.PackSet.world]); the chip says "from the world overview". */
        const val WORLD = "world"

        /**
         * The DEM tiles (z, x, y) a window of [size] global pixels at ([px], [py]) of zoom [z] samples,
         * at the zoom the online pyramid reads: [z] clamped to [minZoom]..[DEM_MAX_ZOOM] (deeper
         * windows overzoom the deepest level, shallower ones average the shallowest; [minZoom] is the
         * pyramid's, [demMinZoom]).
         */
        fun demTilesFor(z: Int, px: Int, py: Int, size: Int, tileSize: Int = 256, minZoom: Int = DEM_MIN_ZOOM): List<Triple<Int, Int, Int>> {
            val tz = z.coerceIn(minZoom, DEM_MAX_ZOOM)
            fun scale(v: Long): Long = if (tz >= z) v shl (tz - z) else Math.floorDiv(v, 1L shl (z - tz))
            val x0 = scale(px.toLong())
            val y0 = scale(py.toLong())
            val x1 = scale(px.toLong() + size) - 1
            val y1 = scale(py.toLong() + size) - 1
            val n = 1L shl tz
            val tx0 = Math.floorDiv(x0, tileSize.toLong()).coerceIn(0, n - 1)
            val tx1 = Math.floorDiv(maxOf(x0, x1), tileSize.toLong()).coerceIn(0, n - 1)
            val ty0 = Math.floorDiv(y0, tileSize.toLong()).coerceIn(0, n - 1)
            val ty1 = Math.floorDiv(maxOf(y0, y1), tileSize.toLong()).coerceIn(0, n - 1)
            val out = ArrayList<Triple<Int, Int, Int>>()
            for (ty in ty0..ty1) for (tx in tx0..tx1) out += Triple(tz, tx.toInt(), ty.toInt())
            return out
        }
    }
}

/**
 * T254: a vector tile the phone lacks, cut from the nearest ancestor it holds. MapLibre Native paints a 404 as an empty
 * tile and never stands the parent in (its `tile_loader_impl.hpp`; seen on the emulator: offline at z12 over a view
 * cached at z9, every vector tile was answered 404 and nothing of them drew), so the loopback server answers such a tile
 * with the ancestor's geometry cut to the child ([MvtOverzoom]) instead. [ancestor] reads tile z/x/y from the phone only
 * (the online cache, a pack), never the network; the answer is never written to the cache and touches no negative cache:
 * the tile itself is fetched as soon as the network allows, as before.
 *
 * Up to [maxDepth] levels up ([MAX_DEPTH], five: a view cached at z9 still has its roads at z14, the vector sources'
 * deepest zoom; past that the geometry is too coarse to draw). The last few ancestors are kept decoded ([KEEP_DECODED]):
 * the children of one ancestor come in a burst. Thread-safe. [json] reports the cuts and their times.
 */
class WiderZoom(private val maxDepth: Int = MAX_DEPTH, private val ancestor: (z: Int, x: Int, y: Int) -> ByteArray?) {
    /** A cut tile: its bytes, the ancestor's zoom, and the ms the answer took (with the ancestor's decode when it was not kept). */
    class Cut(val bytes: ByteArray, val fromZoom: Int, val ms: Double, val decoded: Boolean)

    private val decoded = object : LinkedHashMap<Long, VectorTile>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VectorTile>?) = size > KEEP_DECODED
    }

    val cuts = java.util.concurrent.atomic.AtomicLong()
    val decodes = java.util.concurrent.atomic.AtomicLong()
    val misses = java.util.concurrent.atomic.AtomicLong()
    val failures = java.util.concurrent.atomic.AtomicLong()
    /** Answers that waited for another thread's decode of the same ancestor. */
    val decodeWaits = java.util.concurrent.atomic.AtomicLong()
    private val decoding = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<VectorTile?>>()

    /** The cut and encode of each answer, the ancestor decoded already (`answerMs` in each sample: the whole answer). */
    val timing = io.github.jasondorjeshort.dystopo.app.Timing(keepRecent = 12)

    /** Each ancestor's decode, once per ancestor kept. */
    val decodeTiming = io.github.jasondorjeshort.dystopo.app.Timing(keepRecent = 6)

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** Tile z/x/y cut from the nearest ancestor [ancestor] has within [maxDepth] levels; null when none. */
    fun vector(z: Int, x: Int, y: Int): Cut? {
        val started = System.nanoTime()
        for (d in 1..maxDepth) {
            val az = z - d
            if (az < 0) break
            val ax = x shr d
            val ay = y shr d
            val k = key(az, ax, ay)
            var fresh = false
            val tile = synchronized(decoded) { decoded[k] } ?: run {
                // One decode per ancestor: the children of a view come in a burst, and the others wait for the first.
                val mine = java.util.concurrent.CompletableFuture<VectorTile?>()
                val running = decoding.putIfAbsent(k, mine)
                if (running != null) {
                    decodeWaits.incrementAndGet()
                    return@run running.get()
                }
                try {
                    val t = ancestor(az, ax, ay)?.let { bytes ->
                        val t0 = System.nanoTime()
                        try {
                            MvtDecoder.decode(bytes).also {
                                decodes.incrementAndGet()
                                fresh = true
                                decodeTiming.add((System.nanoTime() - t0) / 1e6, mapOf("tile" to "$az/$ax/$ay", "bytes" to bytes.size))
                            }
                        } catch (e: IllegalArgumentException) {
                            failures.incrementAndGet()
                            null
                        }
                    }
                    if (t != null) synchronized(decoded) { decoded[k] = t }
                    mine.complete(t)
                    t
                } catch (e: Throwable) {
                    mine.complete(null)
                    throw e
                } finally {
                    decoding.remove(k, mine)
                }
            } ?: continue
            val cutStarted = System.nanoTime()
            val bytes = MvtEncoder.encode(MvtOverzoom.cut(tile, d, x - (ax shl d), y - (ay shl d)))
            val now = System.nanoTime()
            val ms = (now - started) / 1e6
            cuts.incrementAndGet()
            timing.add((now - cutStarted) / 1e6, mapOf("tile" to "$z/$x/$y", "from" to az, "bytes" to bytes.size, "answerMs" to Math.round(ms * 10) / 10.0))
            return Cut(bytes, az, ms, fresh)
        }
        misses.incrementAndGet()
        return null
    }

    /** Drops the decoded ancestors (the heap guard's trim); the entries dropped. */
    fun trimMemory(): Int = synchronized(decoded) { decoded.size.also { decoded.clear() } }

    fun json(): Map<String, Any?> = linkedMapOf(
        "maxDepth" to maxDepth, "cuts" to cuts.get(), "decodes" to decodes.get(), "decodeWaits" to decodeWaits.get(),
        "noAncestor" to misses.get(), "undecodable" to failures.get(), "kept" to synchronized(decoded) { decoded.size },
        "cutMs" to timing.json(), "decodeMs" to decodeTiming.json(),
    )

    companion object {
        const val MAX_DEPTH = 5
        const val KEEP_DECODED = 4
    }
}
