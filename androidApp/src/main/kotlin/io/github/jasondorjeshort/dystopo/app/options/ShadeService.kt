package io.github.jasondorjeshort.dystopo.app.options

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.SourceCounters
import io.github.jasondorjeshort.dystopo.app.TileResult
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ShadeOptions
import io.github.jasondorjeshort.dystopo.tiles.ShadeTileStats
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import io.github.jasondorjeshort.dystopo.tiles.SunPointAnswer
import io.github.jasondorjeshort.dystopo.tiles.SunShadeOptions
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * T47: hillshade and slope raster tiles for `/shade/{z}/{x}/{y}.png`, generated on the device from the DEM
 * ([ShadeTiles]) with the current [AppSettings.mapOptions], with the same cache rules as contour tiles: one
 * generation per tile however many requests wait for it, at most [permits] at once.
 *
 * T49: the tiles live in the contour cache's LRU ([cache], [ContourCache.shadeDir]: a directory per option
 * set and DEM archive), so one running byte cap ([AppSettings.contourCacheMb]) bounds contours, cliff tiles
 * and shade together while the app runs, the least recently used option sets go first (flipping between
 * two looks stays warm), and a deleted pack's tiles go with its contours. Before T49 they had their own
 * directory, bounded only when the service started; that directory is removed.
 *
 * Tiles the pack does not cover come from the online DEM pyramid when a server is set ([OnlineTiles]), not
 * disk-cached (their DEM may still be arriving). T104: such a tile over a DEM pack's edge whose zoom is above the
 * pack's first zoom reads at that first zoom ([OnlineTiles.shadeReadZoom]; `packFloorReads`, and `blankAtPackFloor`
 * for one that still came out with no data at all, which should stay 0).
 *
 * T188: also T181's sun-and-shade tiles for `/shade/sun/{z}/{x}/{y}.png?m=<epoch minute>` ([ShadeService.sunTile]; the
 * instant is in the URL, so a tile never answers for another time than its request names), cached the same way under
 * the instant's key ([SunShadeOptions.key]), counted apart (`GET /shade` `sun.generation`, with the sun's altitude, the
 * ray samples and the terrain cells read per tile), and the long-press card's point answer ([ShadeService.pointAnswer]).
 */
/** T65: [ShadeService]'s `/stats` counters (`shadeGeneration`), kept across tile service reopens like the T57 tile counters. */
class ShadeCounters {
    val served = SourceCounters()
    val cacheHits = AtomicLong()
    val waits = AtomicLong()
    val transparent = AtomicLong()
    val stats = ShadeTileStats(keepRecent = 64)

    /** T104: online tiles read at a DEM pack's first zoom ([OnlineTiles.shadeReadZoom]). */
    val packFloorReads = AtomicLong()

    /** T104: such tiles that still had no data in any pixel (should stay 0: the pack under them has elevations). */
    val blankAtPackFloor = AtomicLong()

    /** T188: the sun-and-shade tiles, counted apart from the hillshade and slope ones. */
    val sunServed = SourceCounters()
    val sunCacheHits = AtomicLong()
    val sunTransparent = AtomicLong()
    val sunStats = ShadeTileStats(keepRecent = 64)

    /** T188: the latest sun tiles made, with the sun's altitude, the ray samples and the terrain cells read. */
    val sunRecent = java.util.concurrent.ConcurrentLinkedDeque<Map<String, Any?>>()
}

class ShadeService(
    cacheDir: File,
    private val demIdentity: String,
    /** T41: the DEM pyramid of the pack under DEM tile z/x/y (several packs at once) and (T49) that pack's identity, or null. */
    private val pyramidFor: (Int, Int, Int) -> Pair<DemPyramid, String>?,
    private val online: OnlineTiles?,
    val permits: Int,
    /** T49: where tiles are kept (the contour cache's LRU). */
    private val cache: () -> TileCache = { ContourCache.get() },
    /** T65: the `/stats` counters, owned by the app ([io.github.jasondorjeshort.dystopo.app.TileCounters]) so they stay cumulative across tile service reopens. */
    val counters: ShadeCounters = ShadeCounters(),
) {
    val served = counters.served
    val cacheHits = counters.cacheHits
    val waits = counters.waits
    val transparent = counters.transparent
    val stats = counters.stats
    private val slots = Semaphore(permits)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val legacyRoot = File(cacheDir, "shade")
    val staleFilesDeleted = AtomicLong()

    init {
        // T49: the pre-T49 shade directory (now in the contour LRU).
        if (legacyRoot.isDirectory) {
            Thread({
                legacyRoot.walkBottomUp().forEach { val isFile = it.isFile; if (it.delete() && isFile) staleFilesDeleted.incrementAndGet() }
            }, "shade-cache-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    /** What the tiles draw now; null when the options need no shade tiles. */
    val options: ShadeOptions? get() = AppSettings.mapOptions.shade

    /** The `v` of the style's shade URL: changes whenever the pack or the shade options do, so MapLibre never shows stale tiles. */
    fun urlVersion(): String = "$demIdentity-${options?.key ?: "none"}" + (online?.fetcher?.contentTag?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: "") // T74: the server's content version

    /**
     * Shade tile z/x/y as PNG: the pack's (cached) where its DEM covers the tile, else the online DEM's, else
     * a 404. Flat ground and areas without data give a transparent tile.
     */
    fun tile(z: Int, x: Int, y: Int, packCovers: Boolean): TileResult {
        val o = options ?: return notFound()
        try {
            val bytes = if (packCovers || online == null) {
                val (p, identity) = pyramidFor(z, x, y) ?: return notFound()
                cachedOrGenerate(p, identity, o, z, x, y)
            } else {
                online.shadeTile(o.key, z, x, y) { p, readZoom -> generate(p, o, z, x, y, readZoom) } // T74: disk-cached like online contours; T104: the pack's first zoom
            }
            served.served.incrementAndGet()
            served.bytes.addAndGet(bytes.size.toLong())
            return TileResult(200, "image/png", bytes)
        } catch (e: Throwable) {
            served.errors.incrementAndGet()
            throw e
        }
    }

    private fun notFound(): TileResult {
        served.notFound.incrementAndGet()
        return TileResult.notFound()
    }

    private fun cachedOrGenerate(p: DemPyramid, identity: String, o: ShadeOptions, z: Int, x: Int, y: Int): ByteArray =
        cachedOrGenerate(o.key, identity, z, x, y, cacheHits) { generate(p, o, z, x, y) }

    /** The cached tile of [optionsKey] and [identity] at z/x/y, else [make]'s, made once however many requests wait. */
    private fun cachedOrGenerate(optionsKey: String, identity: String, z: Int, x: Int, y: Int, hits: AtomicLong, make: () -> ByteArray): ByteArray {
        val c = cache()
        val file = ContourCache.shadeKey(ContourCache.shadeDir(optionsKey, identity), z, x, y)
        c.get(file)?.let {
            hits.incrementAndGet()
            return it
        }
        val key = "$optionsKey/$identity/$z/$x/$y"
        val mine = CompletableFuture<ByteArray>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            waits.incrementAndGet()
            return try {
                running.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            val bytes = make()
            c.put(file, bytes)
            mine.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun generate(p: DemPyramid, o: ShadeOptions, z: Int, x: Int, y: Int, readZoom: Int? = null): ByteArray {
        val requested = System.nanoTime()
        slots.acquire()
        try {
            val started = System.nanoTime()
            val plan = ShadeTiles.plan(z, x, y, p.maxZoom)
            val grid = ShadeTiles.sample(p, plan, readZoom)
            val sampled = System.nanoTime()
            val tile = ShadeTiles.render(grid, plan, o)
            val rendered = System.nanoTime()
            if (readZoom != null) {
                counters.packFloorReads.incrementAndGet()
                if (tile.noDataPixels == tile.size * tile.size) counters.blankAtPackFloor.incrementAndGet()
            }
            val bytes = if (tile.argb.all { it == 0 }) {
                transparent.incrementAndGet()
                emptyPng
            } else {
                encode(tile.argb, tile.size)
            }
            val done = System.nanoTime()
            stats.add(plan, tile, (done - started) / 1e6, (sampled - started) / 1e6, (done - rendered) / 1e6, bytes.size, o, (started - requested) / 1e6, readZoom)
            return bytes
        } finally {
            slots.release()
        }
    }

    /** T188: the `v` of the sun tiles' URL: the pack and the server's content version (the instant is `m`). */
    fun sunUrlVersion(): String = demIdentity + (online?.fetcher?.contentTag?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: "")

    /**
     * T188: sun-and-shade tile z/x/y for the epoch minute [minute] as PNG: the pack's (cached) where its DEM covers the
     * tile, else the online DEM's, else a 404; a tile without data, or all in sun, is transparent.
     */
    fun sunTile(z: Int, x: Int, y: Int, minute: Long, packCovers: Boolean): TileResult {
        val o = SunShadeOptions(minute * 60_000L)
        val sv = counters.sunServed
        try {
            val bytes = if (packCovers || online == null) {
                val (p, identity) = pyramidFor(z, x, y) ?: run {
                    sv.notFound.incrementAndGet()
                    return TileResult.notFound()
                }
                cachedOrGenerate(o.key, identity, z, x, y, counters.sunCacheHits) { generateSun(p, o, z, x, y).first }
            } else {
                online.shadeTile(o.key, z, x, y) { p, _ -> generateSun(p, o, z, x, y).first }
            }
            sv.served.incrementAndGet()
            sv.bytes.addAndGet(bytes.size.toLong())
            return TileResult(200, "image/png", bytes)
        } catch (e: Throwable) {
            sv.errors.incrementAndGet()
            throw e
        }
    }

    /** T188: makes sun tile z/x/y (no cache) and says what it took. */
    private fun generateSun(p: DemPyramid, o: SunShadeOptions, z: Int, x: Int, y: Int, record: Boolean = true): Pair<ByteArray, Map<String, Any?>> {
        val requested = System.nanoTime()
        slots.acquire()
        try {
            val started = System.nanoTime()
            val plan = ShadeTiles.sunPlan(z, x, y, p.maxZoom, o)
            val terrain = ShadeTiles.sunSample(p, plan)
            val sampled = System.nanoTime()
            val tile = ShadeTiles.sunRender(terrain, plan, o)
            val rendered = System.nanoTime()
            val blank = tile.argb.all { it == 0 }
            val bytes = if (blank) emptyPng else encode(tile.argb, tile.size)
            val done = System.nanoTime()
            val detail = linkedMapOf<String, Any?>(
                "tile" to "$z/$x/$y", "minute" to o.minuteMs / 60_000L, "sampleZoom" to plan.base.sampleZoom,
                "altitudeDeg" to Math.round(plan.sun.apparentAltitudeDeg * 10) / 10.0, "azimuthDeg" to Math.round(plan.sun.azimuthDeg * 10) / 10.0,
                "ms" to ms(done - started), "waitMs" to ms(started - requested), "sampleMs" to ms(sampled - started),
                "marchMs" to tile.fieldMs, "composeMs" to tile.composeMs, "encodeMs" to ms(done - rendered),
                "cellsRead" to plan.windows.sumOf { it.first.width.toLong() * it.first.height },
                "marchSteps" to tile.marchSteps, "shadowPixels" to tile.shadowPixels, "noDataPixels" to tile.noDataPixels, "bytes" to bytes.size,
            )
            if (record) {
                if (blank) counters.sunTransparent.incrementAndGet()
                counters.sunStats.add(plan.base, tile, (done - started) / 1e6, (sampled - started) / 1e6, (done - rendered) / 1e6, bytes.size, o.key, (started - requested) / 1e6)
                counters.sunRecent.addLast(detail)
                while (counters.sunRecent.size > 32) counters.sunRecent.pollFirst()
            }
            return bytes to detail
        } finally {
            slots.release()
        }
    }

    /**
     * T188, `GET /shade/sun/bench`: makes the sun tile at [lat], [lon], zoom [z] for [epochMs] [repeat] times, bypassing
     * every cache, and reports each run (the phone's tile time); null when nothing covers it.
     */
    fun sunBench(lat: Double, lon: Double, z: Int, epochMs: Long, repeat: Int): Map<String, Any?>? {
        val x = WebMercator.lonToX(lon, z).toInt()
        val y = WebMercator.latToY(lat, z).toInt()
        val p = pyramidFor(z, x, y)?.first ?: online?.pyramid() ?: return null
        val o = SunShadeOptions(epochMs)
        val runs = (1..repeat.coerceIn(1, 10)).map { generateSun(p, o, z, x, y, record = false).second }
        val times = runs.map { it["ms"] as Double }
        return linkedMapOf("z" to z, "x" to x, "y" to y, "runs" to runs, "minMs" to times.min(), "maxMs" to times.max(), "avgMs" to Math.round(times.average() * 10) / 10.0)
    }

    /**
     * T188: the long-press card's point answer at [lat], [lon] for the local day of [epochMs] (T181's
     * [ShadeTiles.pointAnswer] over the pack under the point, else the online DEM); null without elevation data there.
     */
    fun pointAnswer(lat: Double, lon: Double, epochMs: Long): SunPointAnswer? {
        val p = POINT_ZOOMS.firstNotNullOfOrNull { z -> pyramidFor(z, WebMercator.lonToX(lon, z).toInt(), WebMercator.latToY(lat, z).toInt())?.first }
            ?: online?.pyramid() ?: return null
        return ShadeTiles.pointAnswer(p, lat, lon, epochMs).takeUnless { it.horizon.groundM.isNaN() }
    }

    /** T188: `GET /shade` `sun.generation`. */
    fun sunJson(): Map<String, Any?> = counters.sunStats.json() + mapOf(
        "served" to counters.sunServed.json(),
        "diskCacheHits" to counters.sunCacheHits.get(),
        "transparentTiles" to counters.sunTransparent.get(),
        "recentDetail" to counters.sunRecent.toList().takeLast(12),
    )

    private fun ms(nanos: Long) = Math.round(nanos / 1e5) / 10.0

    /** `/stats` shadeGeneration. */
    fun json(): Map<String, Any?> = stats.json() + mapOf(
        "permits" to permits,
        "options" to options?.key,
        "served" to served.json(),
        "diskCacheHits" to cacheHits.get(),
        "waitedForSameTile" to waits.get(),
        "transparentTiles" to transparent.get(),
        "packFloorReads" to counters.packFloorReads.get(), // T104
        "blankAtPackFloor" to counters.blankAtPackFloor.get(), // T104: should stay 0
        "staleCacheFilesDeleted" to staleFilesDeleted.get(),
        "demDecoder" to TileService.decoderName, // T49: the shared pure-Kotlin decoder by default (pyramids from TileService)
        // T49: in the contour LRU; per option set and pack.
        "diskCache" to cache().byPrefix().filterKeys { it.startsWith(ContourCache.SHADE_VERSION + "-") }.let { d ->
            mapOf("tiles" to d.values.sumOf { it.first }, "bytes" to d.values.sumOf { it.second }, "dirs" to d.keys.sorted())
        },
    )

    companion object {
        /** T188: the DEM zooms a point answer looks for a pack at, deepest first. */
        private val POINT_ZOOMS = listOf(ShadeTiles.POINT_ZOOM, 12, 10, 8)

        /** [FastPng]: Bitmap.compress took most of a tile's time. */
        private fun encode(argb: IntArray, size: Int): ByteArray = FastPng.encode(argb, size, size)

        /** One fully transparent tile, encoded once. */
        private val emptyPng: ByteArray by lazy { encode(IntArray(ShadeTiles.TILE_SIZE * ShadeTiles.TILE_SIZE), ShadeTiles.TILE_SIZE) }
    }
}
