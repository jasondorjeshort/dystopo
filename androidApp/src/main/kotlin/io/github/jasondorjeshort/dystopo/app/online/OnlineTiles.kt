package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.TileResult
import io.github.jasondorjeshort.dystopo.geo.WebMercator
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
 *   contour tiles across a pack edge join. The DEM tiles a contour tile needs are fetched in parallel
 *   first. A contour tile is disk-cached only when every DEM tile it needed was there: one made while a
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
) {
    // ---- coverage ----

    private fun overlaps(p: Pack, z: Int, x: Int, y: Int): Boolean {
        val h = p.header
        val west = WebMercator.xToLon(x.toDouble(), z)
        val east = WebMercator.xToLon(x + 1.0, z)
        val north = WebMercator.yToLat(y.toDouble(), z)
        val south = WebMercator.yToLat(y + 1.0, z)
        return east > h.minLon && west < h.maxLon && north > h.minLat && south < h.maxLat
    }

    private fun inside(p: Pack, z: Int, x: Int, y: Int): Boolean {
        val h = p.header
        return WebMercator.xToLon(x.toDouble(), z) >= h.minLon && WebMercator.xToLon(x + 1.0, z) <= h.maxLon &&
            WebMercator.yToLat(y + 1.0, z) >= h.minLat && WebMercator.yToLat(y.toDouble(), z) <= h.maxLat
    }

    /** True when the DEM pack answers tile z/x/y (stored, or synthesized from its ancestors). */
    fun packCoversDem(z: Int, x: Int, y: Int): Boolean = packs.dem?.let { z >= it.header.minZoom && overlaps(it, z, x, y) } ?: false

    fun packCoversOsm(z: Int, x: Int, y: Int): Boolean = packs.osm?.let { z >= it.header.minZoom && overlaps(it, z, x, y) } ?: false

    /** True when the contour tile lies wholly inside the DEM pack: the pack path (and its cache) makes it, as without a server. */
    fun packCoversContour(z: Int, x: Int, y: Int): Boolean = packs.dem?.let { z >= it.header.minZoom - 1 && inside(it, z, x, y) } ?: false

    // ---- tiles ----

    fun demTile(z: Int, x: Int, y: Int): TileResult {
        if (z !in DEM_MIN_ZOOM..DEM_MAX_ZOOM) return TileResult.notFound()
        return when (val f = fetcher.get("dem", "$z/$x/$y.png")) {
            is Fetch.Hit -> {
                service.recordDem(z, x, y, if (f.cached) OnlineService.CACHED else OnlineService.ONLINE)
                service.served("dem", f)
                TileResult(200, "image/png", f.bytes)
            }
            else -> service.served("dem", f).let { TileResult.notFound() }
        }
    }

    fun osmTile(z: Int, x: Int, y: Int): TileResult {
        if (z !in 0..OSM_MAX_ZOOM) return TileResult.notFound()
        val f = fetcher.get("osm", "$z/$x/$y.pbf", OnlineFetcher::gunzipIfNeeded)
        service.served("osm", f)
        return if (f is Fetch.Hit) TileResult(200, "application/x-protobuf", f.bytes) else TileResult.notFound()
    }

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

    /** Encoded DEM tile for the pyramid: the pack's where it covers, else fetched; null when nobody has it. */
    private fun demBytes(z: Int, x: Int, y: Int): ByteArray? {
        if (packCoversDem(z, x, y)) return packs.dem?.tile(z, x, y)
        if (z !in DEM_MIN_ZOOM..DEM_MAX_ZOOM) return null
        return when (val f = fetcher.get("dem", "$z/$x/$y.png")) {
            is Fetch.Hit -> f.bytes
            Fetch.Missing -> null
            is Fetch.Unavailable -> {
                incomplete.set(true)
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
            val p = DemPyramid(DEM_MIN_ZOOM, DEM_MAX_ZOOM, decoder, cacheTiles = 96) { tz, tx, ty -> demBytes(tz, tx, ty) }
            current = Pyramid(gen, p)
            return p
        }
    }

    private fun dropPyramid() = synchronized(this) { current = null }

    /**
     * Contour tile z/x/y for an area the pack does not (wholly) cover: from the online cache, else made by
     * [generate] over [pyramid] (TileService's own generation path: slots, stats) after the DEM tiles it
     * samples were fetched in parallel.
     */
    fun contourTile(z: Int, x: Int, y: Int, generate: (DemPyramid) -> ByteArray): ByteArray {
        // Tiles touching the pack mix pack and server DEM; they are not kept (a pack switch would make them stale).
        val keep = packs.dem?.let { !overlaps(it, z, x, y) } ?: true
        val key = "${fetcher.serverKey}/contours-$contourVersion/$z/$x/$y.pbf"
        if (keep) service.cache.get(key)?.let {
            service.contourCacheHits.incrementAndGet()
            return it
        }
        prefetch(z, x, y)
        incomplete.set(false)
        val bytes = generate(pyramid())
        val missed = incomplete.get() == true
        incomplete.remove()
        if (missed) {
            service.contourIncomplete.incrementAndGet()
            // A one-off failure while the server is up: forget the pyramid's missing-tile memory now.
            if (fetcher.state == "online") dropPyramid()
        } else if (keep) {
            service.cache.put(key, bytes)
        }
        service.contoursMade.incrementAndGet()
        return bytes
    }

    /** Fetches the DEM tiles contour tile z/x/y samples, in parallel (into the disk cache), before it is generated. */
    private fun prefetch(z: Int, x: Int, y: Int) {
        val plan = ContourTiles.plan(z, x, y, contourOptions)
        val tiles = demTilesFor(plan.sampleZoom, plan.windowX, plan.windowY, plan.windowSize).filter { (tz, tx, ty) -> !packCoversDem(tz, tx, ty) }
        if (tiles.size <= 1) return
        val futures = tiles.map { (tz, tx, ty) -> service.prefetch { fetcher.get("dem", "$tz/$tx/$ty.png") } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        for (f in futures) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            runCatching { f.get(left, TimeUnit.NANOSECONDS) }
        }
    }

    // ---- indicator ----

    /**
     * Where the view centred at [lat], [lon] at map zoom [zoom] gets its terrain: `pack`, `online`
     * (fetched this session), `cached` (from the online disk cache) or `none` (nothing loaded there yet).
     */
    fun viewSource(lat: Double, lon: Double, zoom: Double): String {
        // A 256-px DEM tile is requested one zoom deeper than the (512-px) map zoom.
        val zt = (floor(zoom).toInt() + 1).coerceIn(0, DEM_MAX_ZOOM)
        fun tile(z: Int) = Triple(z, WebMercator.lonToX(lon, z).toInt().coerceIn(0, (1 shl z) - 1), WebMercator.latToY(lat, z).toInt().coerceIn(0, (1 shl z) - 1))
        val (z0, x0, y0) = tile(zt)
        if (packCoversDem(z0, x0, y0)) return PACK
        for (z in listOf(zt, zt - 1, zt + 1, zt - 2).filter { it in 0..DEM_MAX_ZOOM }) {
            val (tz, tx, ty) = tile(z)
            service.demSource(tz, tx, ty)?.let { return it }
        }
        return NONE
    }

    companion object {
        /** The live server's DEM zooms (`LiveTiles.LIVE_MIN_ZOOM`..`LIVE_MAX_ZOOM`). */
        const val DEM_MIN_ZOOM = 6
        const val DEM_MAX_ZOOM = 15
        /** Online contours start here, as on the server (`LiveTiles.CONTOUR_MIN_ZOOM`). */
        const val CONTOUR_MIN_ZOOM = 8
        /** OpenFreeMap and our packs stop at z14; MapLibre overzooms. */
        const val OSM_MAX_ZOOM = 14
        const val PACK = "pack"
        const val NONE = "none"

        /**
         * The DEM tiles (z, x, y) a window of [size] global pixels at ([px], [py]) of zoom [z] samples,
         * at the zoom the online pyramid reads: [z] clamped to [DEM_MIN_ZOOM]..[DEM_MAX_ZOOM] (deeper
         * windows overzoom the deepest level, shallower ones average the shallowest).
         */
        fun demTilesFor(z: Int, px: Int, py: Int, size: Int, tileSize: Int = 256): List<Triple<Int, Int, Int>> {
            val tz = z.coerceIn(DEM_MIN_ZOOM, DEM_MAX_ZOOM)
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
