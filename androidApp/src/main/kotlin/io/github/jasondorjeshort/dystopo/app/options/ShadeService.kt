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
 * disk-cached (their DEM may still be arriving).
 */
/** T65: [ShadeService]'s `/stats` counters (`shadeGeneration`), kept across tile service reopens like the T57 tile counters. */
class ShadeCounters {
    val served = SourceCounters()
    val cacheHits = AtomicLong()
    val waits = AtomicLong()
    val transparent = AtomicLong()
    val stats = ShadeTileStats(keepRecent = 64)
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
                online.shadeTile(o.key, z, x, y) { generate(it, o, z, x, y) } // T74: disk-cached like online contours
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

    private fun cachedOrGenerate(p: DemPyramid, identity: String, o: ShadeOptions, z: Int, x: Int, y: Int): ByteArray {
        val c = cache()
        val file = ContourCache.shadeKey(ContourCache.shadeDir(o.key, identity), z, x, y)
        c.get(file)?.let {
            cacheHits.incrementAndGet()
            return it
        }
        val key = "${o.key}/$identity/$z/$x/$y"
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
            val bytes = generate(p, o, z, x, y)
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

    private fun generate(p: DemPyramid, o: ShadeOptions, z: Int, x: Int, y: Int): ByteArray {
        val requested = System.nanoTime()
        slots.acquire()
        try {
            val started = System.nanoTime()
            val plan = ShadeTiles.plan(z, x, y, p.maxZoom)
            val grid = ShadeTiles.sample(p, plan)
            val sampled = System.nanoTime()
            val tile = ShadeTiles.render(grid, plan, o)
            val rendered = System.nanoTime()
            val bytes = if (tile.argb.all { it == 0 }) {
                transparent.incrementAndGet()
                emptyPng
            } else {
                encode(tile.argb, tile.size)
            }
            val done = System.nanoTime()
            stats.add(plan, tile, (done - started) / 1e6, (sampled - started) / 1e6, (done - rendered) / 1e6, bytes.size, o, (started - requested) / 1e6)
            return bytes
        } finally {
            slots.release()
        }
    }

    /** `/stats` shadeGeneration. */
    fun json(): Map<String, Any?> = stats.json() + mapOf(
        "permits" to permits,
        "options" to options?.key,
        "served" to served.json(),
        "diskCacheHits" to cacheHits.get(),
        "waitedForSameTile" to waits.get(),
        "transparentTiles" to transparent.get(),
        "staleCacheFilesDeleted" to staleFilesDeleted.get(),
        "demDecoder" to TileService.decoderName, // T49: the shared pure-Kotlin decoder by default (pyramids from TileService)
        // T49: in the contour LRU; per option set and pack.
        "diskCache" to cache().byPrefix().filterKeys { it.startsWith(ContourCache.SHADE_VERSION + "-") }.let { d ->
            mapOf("tiles" to d.values.sumOf { it.first }, "bytes" to d.values.sumOf { it.second }, "dirs" to d.keys.sorted())
        },
    )

    companion object {
        /** [FastPng]: Bitmap.compress took most of a tile's time. */
        private fun encode(argb: IntArray, size: Int): ByteArray = FastPng.encode(argb, size, size)

        /** One fully transparent tile, encoded once. */
        private val emptyPng: ByteArray by lazy { encode(IntArray(ShadeTiles.TILE_SIZE * ShadeTiles.TILE_SIZE), ShadeTiles.TILE_SIZE) }
    }
}
