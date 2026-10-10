package io.github.jasondorjeshort.dystopo.app.options

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SourceCounters
import io.github.jasondorjeshort.dystopo.app.TileResult
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
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
 * ([ShadeTiles]) with the current [AppSettings.mapOptions], with the same cache rules as contour tiles: a disk
 * cache per DEM pack identity and option key (`shade/<options key>-<pack identity>/z/x/y.png`), one generation
 * per tile however many requests wait for it, at most [permits] at once. Directories of other packs are
 * deleted in the background when the service starts, and only the [KEEP_OPTION_DIRS] most recently used
 * option sets of this pack are kept, so flipping between two looks stays warm.
 *
 * Tiles the pack does not cover come from the online DEM pyramid when a server is set ([OnlineTiles]), not
 * disk-cached (their DEM may still be arriving).
 */
class ShadeService(
    cacheDir: File,
    private val demIdentity: String,
    /** T41: the DEM pyramid of the pack under DEM tile z/x/y (several packs at once), or null. */
    private val pyramidFor: (Int, Int, Int) -> DemPyramid?,
    private val online: OnlineTiles?,
    val permits: Int,
) {
    val served = SourceCounters()
    val cacheHits = AtomicLong()
    val waits = AtomicLong()
    val transparent = AtomicLong()
    val stats = ShadeTileStats(keepRecent = 64)
    private val slots = Semaphore(permits)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val root = File(cacheDir, "shade")
    val staleFilesDeleted = AtomicLong()

    init {
        Thread({
            // Other packs' tiles are stale; of this pack's option sets keep the most recent few.
            val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
            val (mine, others) = dirs.partition { it.name.endsWith("-$demIdentity") }
            val drop = others + mine.sortedByDescending { it.lastModified() }.drop(KEEP_OPTION_DIRS)
            for (dir in drop) dir.walkBottomUp().forEach { val isFile = it.isFile; if (it.delete() && isFile) staleFilesDeleted.incrementAndGet() }
        }, "shade-cache-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** What the tiles draw now; null when the options need no shade tiles. */
    val options: ShadeOptions? get() = AppSettings.mapOptions.shade

    /** The `v` of the style's shade URL: changes whenever the pack or the shade options do, so MapLibre never shows stale tiles. */
    fun urlVersion(): String = "$demIdentity-${options?.key ?: "none"}"

    /**
     * Shade tile z/x/y as PNG: the pack's (cached) where its DEM covers the tile, else the online DEM's, else
     * a 404. Flat ground and areas without data give a transparent tile.
     */
    fun tile(z: Int, x: Int, y: Int, packCovers: Boolean): TileResult {
        val o = options ?: return notFound()
        try {
            val bytes = if (packCovers || online == null) {
                val p = pyramidFor(z, x, y) ?: return notFound()
                cachedOrGenerate(p, o, z, x, y)
            } else {
                generate(online.pyramid(), o, z, x, y, null)
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

    private fun cachedOrGenerate(p: DemPyramid, o: ShadeOptions, z: Int, x: Int, y: Int): ByteArray {
        val dir = File(root, "${o.key}-$demIdentity")
        val file = File(dir, "$z/$x/$y.png")
        if (file.isFile) {
            cacheHits.incrementAndGet()
            return file.readBytes()
        }
        val key = "${o.key}/$z/$x/$y"
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
            val bytes = generate(p, o, z, x, y, file)
            dir.setLastModified(System.currentTimeMillis()) // most recently used option set, for the GC
            mine.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun generate(p: DemPyramid, o: ShadeOptions, z: Int, x: Int, y: Int, file: File?): ByteArray {
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
            if (file != null) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            }
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
        "diskCache" to root.walkTopDown().filter { it.isFile }.toList().let { mapOf("tiles" to it.size, "bytes" to it.sumOf { f -> f.length() }) },
    )

    companion object {
        const val KEEP_OPTION_DIRS = 4

        /** [FastPng]: Bitmap.compress took most of a tile's time. */
        private fun encode(argb: IntArray, size: Int): ByteArray = FastPng.encode(argb, size, size)

        /** One fully transparent tile, encoded once. */
        private val emptyPng: ByteArray by lazy { encode(IntArray(ShadeTiles.TILE_SIZE * ShadeTiles.TILE_SIZE), ShadeTiles.TILE_SIZE) }
    }
}
