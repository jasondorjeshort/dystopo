package io.github.jasondorjeshort.dystopo.app

import android.graphics.Bitmap
import android.os.Process
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTileStats
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.ContourWarmupProgress
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/** Counters for one tile source, reported by `/stats`. */
class SourceCounters {
    val served = AtomicLong()
    val bytes = AtomicLong()
    val notFound = AtomicLong()
    val errors = AtomicLong()

    fun json() = mapOf("served" to served.get(), "bytes" to bytes.get(), "notFound" to notFound.get(), "errors" to errors.get())
}

/** Min/avg/max of a series of millisecond timings, plus the most recent samples. */
class Timing(private val keepRecent: Int = 20) {
    private var count = 0L
    private var total = 0.0
    private var min = Double.POSITIVE_INFINITY
    private var max = 0.0
    private val recent = ArrayDeque<Map<String, Any?>>()

    @Synchronized
    fun add(ms: Double, detail: Map<String, Any?>) {
        count++
        total += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        recent.addLast(detail + ("ms" to round1(ms)))
        while (recent.size > keepRecent) recent.removeFirst()
    }

    @Synchronized
    fun json(): Map<String, Any?> = mapOf(
        "count" to count,
        "minMs" to if (count == 0L) null else round1(min),
        "avgMs" to if (count == 0L) null else round1(total / count),
        "maxMs" to if (count == 0L) null else round1(max),
        "totalMs" to round1(total),
        "recent" to recent.toList(),
    )
}

fun round1(d: Double) = Math.round(d * 10) / 10.0

/** HTTP-ish result of a tile request. */
class TileResult(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun notFound() = TileResult(404, "text/plain", "not found\n".toByteArray())
    }
}

/**
 * Identity of a DEM pack file for cache keys: size, mtime and a CRC32 of the 127-byte PMTiles header
 * (which holds the directory offsets and tile counts), so a replaced pack never matches the old one.
 */
fun packIdentity(file: File): String {
    val crc = CRC32()
    crc.update(file.length().toString().toByteArray())
    crc.update(file.lastModified().toString().toByteArray())
    runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(minOf(127L, raf.length()).toInt())
            raf.readFully(header)
            crc.update(header)
        }
    }
    return "%08x".format(crc.value)
}

/**
 * Serves the region pack to MapLibre: DEM tiles (stored PNGs, or synthesized from an ancestor where
 * the pack has none, like the browser demo's fallback), OSM vector tiles (decompressed), contour
 * tiles generated on the device from the DEM ([ContourTiles], cached on disk), glyphs and the style.
 *
 * Contour tiles generate concurrently: the [DemPyramid] is thread-safe, at most [contourPermits]
 * tiles trace at once (one core is left to the map renderer), and a second request for a tile that
 * is being generated waits for the first instead of generating it again. The disk cache directory
 * is keyed on the DEM pack identity ([packIdentity]), so a pushed pack gets fresh contours without
 * `/cache/clear`; other directories under `contours/` are stale and deleted in the background.
 */
class TileService(val packs: PackSet, private val cacheDir: File, private val port: Int) {
    val dem = SourceCounters()
    val demSynthesized = AtomicLong()
    val osm = SourceCounters()
    val contours = SourceCounters()
    val glyphs = SourceCounters()
    val contourGen = ContourTileStats(keepRecent = 48)
    val contourCacheHits = AtomicLong()
    val contourWaits = AtomicLong()
    val demSynthesis = Timing()

    private val contourOptions = ContourTileOptions()

    /** The DEM pack's identity, part of the contour cache key and of the tile URLs in the style. */
    val demIdentity: String = packs.dem?.file?.let(::packIdentity) ?: "none"

    /** Cache version: bump the `v` when contour generation changes what it writes. */
    val contourCacheVersion = "v2-${contourOptions.units.symbol}-${contourOptions.tileSize}-$demIdentity"
    private val contourRoot = File(cacheDir, "contours")
    private val contourCache = File(contourRoot, contourCacheVersion).apply { mkdirs() }

    /** T38: tiles the pack does not cover come from the configured server ([OnlineTiles]); null when none is set. */
    val online: OnlineTiles? = OnlineService.get().tilesFor(packs, contourCacheVersion.removeSuffix("-$demIdentity"))
    val staleCacheFilesDeleted = AtomicLong()

    /** Tiles generating at once; the server pool is larger, so DEM and OSM requests are still served meanwhile. */
    val contourPermits = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
    private val contourSlots = Semaphore(contourPermits)
    private val contourInFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    /**
     * Decoded DEM tiles shared by contour generation and DEM synthesis; thread-safe. 96 tiles (24 MB) hold
     * a screenful of contour tiles with their halo neighbours: a 512-px tile plus halo touches up to 16 DEM tiles.
     */
    private val pyramid: DemPyramid? = packs.dem?.let { pack ->
        DemPyramid(pack.header.minZoom, pack.header.maxZoom, BitmapImageDecoder, cacheTiles = 96) { z, x, y -> pack.tile(z, x, y) }
    }

    /** Contour requests from the map currently in [contourTile]; the warm-up pauses while any are. */
    private val foregroundContours = AtomicInteger()

    @Volatile
    private var closed = false

    /** Generation timings of the background warm-up, kept apart from the map's own [contourGen]. */
    val warmupGen = ContourTileStats(keepRecent = 8)

    /** Progress of the background warm-up started with this service; null without a DEM pack. */
    val warmup: ContourWarmupProgress? = startWarmup()

    init {
        // Drop caches of other packs and older versions lazily, off the request path.
        Thread({
            contourRoot.listFiles()?.filter { it.isDirectory && it.name != contourCacheVersion }?.forEach { dir ->
                dir.walkBottomUp().forEach { val isFile = it.isFile; if (it.delete() && isFile) staleCacheFilesDeleted.incrementAndGet() }
            }
        }, "contour-cache-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    val demMinZoom get() = packs.dem?.header?.minZoom ?: 8
    val demMaxZoom get() = packs.dem?.header?.maxZoom ?: 14

    fun pyramidJson(): Map<String, Any?> {
        val p = pyramid ?: return mapOf("loaded" to false)
        return mapOf(
            "loaded" to true,
            "cachedTiles" to p.cachedTiles,
            "cachedTileBytes" to p.cachedTiles.toLong() * p.tileSize * p.tileSize * 4,
            "tilesDecoded" to p.tilesDecoded,
            "duplicateDecodes" to p.duplicateDecodes,
            "cacheHits" to p.cacheHits,
        )
    }

    /** `/stats` contourGeneration: per-zoom timings, concurrency and the cache identity. */
    fun contourJson(): Map<String, Any?> = contourGen.json() + mapOf(
        "permits" to contourPermits,
        "waitedForSameTile" to contourWaits.get(),
        "cacheVersion" to contourCacheVersion,
        "demPack" to packs.dem?.file?.let { mapOf("bytes" to it.length(), "mtime" to it.lastModified(), "identity" to demIdentity) },
        "staleCacheFilesDeleted" to staleCacheFilesDeleted.get(),
        "warmup" to (warmup?.json()?.plus("generation" to warmupGen.json()) ?: mapOf("state" to "off")),
    )

    fun style(): String {
        val base = "http://127.0.0.1:$port"
        val osmPack = packs.osm
        val attribution = listOfNotNull(osmPack?.attribution, packs.dem?.attribution).filter { it.isNotEmpty() }.joinToString(" | ")
        // T38: with a server the sources also span the server's zooms (the pack's own range is kept inside it).
        val o = online
        val demLo = if (o == null) demMinZoom else minOf(demMinZoom, OnlineTiles.DEM_MIN_ZOOM)
        val demHi = if (o == null) demMaxZoom else packs.dem?.header?.maxZoom ?: OnlineTiles.DEM_MAX_ZOOM
        // The online generation in the URLs makes MapLibre ask again for tiles it got blank while offline.
        val v = demIdentity + (o?.let { "-${it.fetcher.serverKey}-${it.fetcher.generation}" } ?: "")
        return MapLibreStyle(
            MapLibreSources(
                // The pack identity in the URL keeps MapLibre's own tile cache from serving a replaced pack's tiles.
                demTiles = "$base/dem/{z}/{x}/{y}.png?v=$v",
                demMinZoom = demLo,
                demMaxZoom = demHi,
                contourTiles = "$base/contours/{z}/{x}/{y}.pbf?v=$v",
                // Contour tile z has the levels of DEM zoom z+1 (512-px vector tiles), and overzoomed DEM is fine
                // two levels past the pack: 20 ft contours at z15 from a z14 pack are smooth bilinear lines.
                contourMinZoom = (demMinZoom - 1).coerceAtLeast(0).let { if (o == null) it else minOf(it, OnlineTiles.CONTOUR_MIN_ZOOM) },
                contourMaxZoom = demHi + 1,
                osmTiles = if (osmPack != null || o != null) "$base/osm/{z}/{x}/{y}.pbf?v=$v" else null,
                osmMinZoom = if (o == null) osmPack?.header?.minZoom ?: 0 else 0,
                osmMaxZoom = if (o == null) osmPack?.header?.maxZoom ?: 14 else maxOf(osmPack?.header?.maxZoom ?: 0, OnlineTiles.OSM_MAX_ZOOM),
                glyphs = Glyphs.styleUrl(packs.glyphDir, base).let { if (o != null && it.startsWith("http")) "$it?v=$v" else it }, // T38: refetch after offline
                attribution = attribution,
                sprite = SpriteFiles.styleUrl(packs.glyphDir?.parentFile),
            ),
            textScale = AppSettings.textScale,
        ).json()
    }

    fun demTile(z: Int, x: Int, y: Int): TileResult {
        online?.takeUnless { it.packCoversDem(z, x, y) }?.let { return it.demTile(z, x, y) } // T38: online fallback
        val pack = packs.dem ?: return TileResult.notFound().also { dem.notFound.incrementAndGet() }
        val stored = pack.tile(z, x, y)
        if (stored != null) return ok(dem, "image/png", stored)
        // Not in the pack: synthesize from the nearest ancestor so hillshade continues past a deep level's edge.
        val started = System.nanoTime()
        val p = pyramid ?: return TileResult.notFound()
        val ts = p.tileSize
        val grid = if (!p.covers(z, x * ts, y * ts, ts, ts)) null else p.window(z, x * ts, y * ts, ts, ts)
        if (grid == null || grid.values.all { it.isNaN() }) {
            dem.notFound.incrementAndGet()
            return TileResult.notFound()
        }
        val argb = TileWindows.encodeTerrarium(grid)
        val bitmap = Bitmap.createBitmap(argb, grid.width, grid.height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        demSynthesized.incrementAndGet()
        demSynthesis.add((System.nanoTime() - started) / 1e6, mapOf("tile" to "$z/$x/$y"))
        return ok(dem, "image/png", png)
    }

    fun osmTile(z: Int, x: Int, y: Int): TileResult {
        online?.takeUnless { it.packCoversOsm(z, x, y) }?.let { return it.osmTile(z, x, y) } // T38: online fallback
        val pack = packs.osm ?: return TileResult.notFound().also { osm.notFound.incrementAndGet() }
        val bytes = pack.tile(z, x, y) ?: return TileResult.notFound().also { osm.notFound.incrementAndGet() }
        return ok(osm, "application/x-protobuf", bytes)
    }

    fun contourTile(z: Int, x: Int, y: Int): TileResult {
        foregroundContours.incrementAndGet()
        try {
            // T38: outside the pack, contours come from the online DEM through the same generation path (not the pack's cache).
            online?.takeUnless { it.packCoversContour(z, x, y) }?.let { o ->
                return ok(contours, "application/x-protobuf", o.contourTile(z, x, y) { p -> generateContourTile(p, z, x, y, null, contourGen) })
            }
            val bytes = contourBytes(z, x, y, contourGen) ?: return TileResult.notFound().also { contours.notFound.incrementAndGet() }
            return ok(contours, "application/x-protobuf", bytes)
        } catch (e: Throwable) {
            contours.errors.incrementAndGet()
            throw e
        } finally {
            foregroundContours.decrementAndGet()
        }
    }

    /**
     * Contour tile bytes from the disk cache or generated (timed into [stats]); null without a DEM pack.
     * One generation per tile: a second request for the same tile, from the map or the warm-up, waits
     * for the first.
     */
    private fun contourBytes(z: Int, x: Int, y: Int, stats: ContourTileStats, onCacheHit: () -> Unit = { contourCacheHits.incrementAndGet() }): ByteArray? {
        val file = File(contourCache, "$z/$x/$y.pbf")
        if (file.isFile) {
            onCacheHit()
            return file.readBytes()
        }
        val p = pyramid ?: return null
        val key = "$z/$x/$y"
        val mine = CompletableFuture<ByteArray?>()
        val running = contourInFlight.putIfAbsent(key, mine)
        if (running != null) {
            contourWaits.incrementAndGet()
            return try {
                running.get() ?: ByteArray(0)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            val bytes = generateContourTile(p, z, x, y, file, stats)
            mine.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            contourInFlight.remove(key, mine)
        }
    }

    private fun generateContourTile(p: DemPyramid, z: Int, x: Int, y: Int, file: File?, stats: ContourTileStats): ByteArray {
        val requested = System.nanoTime()
        contourSlots.acquire()
        val concurrent = stats.begin()
        try {
            val started = System.nanoTime()
            val plan = ContourTiles.plan(z, x, y, contourOptions, p.tileSize)
            val grid = ContourTiles.sample(p, plan)
            val sampled = System.nanoTime()
            val tile = ContourTiles.build(grid, plan, contourOptions)
            val done = System.nanoTime()
            if (file != null) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
                tmp.writeBytes(tile.bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            }
            // Generation time excludes waiting for a slot (reported separately as waitMs).
            stats.end(plan, tile, (done - started) / 1e6, (sampled - started) / 1e6, concurrent, (started - requested) / 1e6)
            return tile.bytes
        } catch (e: Throwable) {
            stats.abort()
            throw e
        } finally {
            contourSlots.release()
        }
    }

    /**
     * Background warm-up ([ContourTiles.warmupPlan]): one low-priority thread generates the contour tiles of
     * the pack's two lowest zooms, plus the centre tile of each higher zoom, into the disk cache, pausing
     * whenever the map's own contour requests are generating, so the first views after an install, a pack
     * switch or `/reload` are cached and the JIT and DEM caches are warm. Stops when this service is
     * [close]d. Timings go to [warmupGen], not [contourGen].
     */
    private fun startWarmup(): ContourWarmupProgress? {
        val header = packs.dem?.header ?: return null
        if (pyramid == null || header.minLon >= header.maxLon || header.minLat >= header.maxLat) return null
        val bounds = BoundingBox(header.minLon, header.minLat, header.maxLon, header.maxLat)
        // The style's highest contour zoom is the pack's max + 1 (see style()).
        val plan = ContourTiles.warmupPlan(bounds, header.minZoom, zoomCount = WARMUP_ZOOMS, maxTiles = WARMUP_MAX_TILES, centreUpTo = header.maxZoom + 1)
        val progress = ContourWarmupProgress(plan.map { it.z }.distinct(), plan.size)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            for (t in plan) {
                // Yield to the map: wait while any of its contour tiles is generating.
                val pauseStart = System.nanoTime()
                while (foregroundContours.get() > 0 && !closed) Thread.sleep(WARMUP_POLL_MS)
                if (closed) break
                val paused = System.nanoTime() - pauseStart
                if (paused > WARMUP_POLL_MS * 500_000L) progress.paused(paused / 1e6)
                try {
                    var hit = false
                    contourBytes(t.z, t.x, t.y, warmupGen) { hit = true }
                    if (hit) progress.cached() else progress.generated()
                } catch (e: Throwable) {
                    if (closed) break
                    progress.failed(e)
                }
            }
            progress.finish(stopped = closed)
        }, "contour-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return progress
    }

    /** Stops the warm-up; call when this service is replaced (its packs are about to close). */
    fun close() {
        closed = true
    }

    fun glyph(fontstack: String, range: String): TileResult {
        val dir = packs.glyphDir
        // MapLibre asks for comma-separated font stacks; serve the first font we have.
        val file = fontstack.split(",").map { it.trim() }.firstNotNullOfOrNull { font ->
            dir?.let { File(it, "$font/$range.pbf") }?.takeIf { f -> f.isFile && f.canonicalPath.startsWith(dir.canonicalPath) }
        }
        if (file == null) {
            online?.glyph(fontstack, range)?.let { return if (it.status == 200) ok(glyphs, it.contentType, it.body) else it } // T38: from the server
            // An empty glyph set: the labels in this range draw without these characters instead of erroring.
            glyphs.notFound.incrementAndGet()
            return TileResult(200, "application/x-protobuf", ByteArray(0))
        }
        return ok(glyphs, "application/x-protobuf", file.readBytes())
    }

    /** Deletes this pack's cached contour tiles; returns how many files went. */
    fun clearContourCache(): Int {
        var n = 0
        contourCache.walkBottomUp().forEach { if (it != contourCache && it.delete()) n++ }
        return n
    }

    private fun ok(c: SourceCounters, type: String, bytes: ByteArray): TileResult {
        c.served.incrementAndGet()
        c.bytes.addAndGet(bytes.size.toLong())
        return TileResult(200, type, bytes)
    }

    private companion object {
        /** Lowest pack zooms warmed up: the fitted whole-pack view and the first zoom-in. */
        const val WARMUP_ZOOMS = 2
        const val WARMUP_MAX_TILES = 256
        const val WARMUP_POLL_MS = 50L
    }
}
