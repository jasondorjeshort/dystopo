package io.github.jasondorjeshort.dystopo.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.DirectSources
import io.github.jasondorjeshort.dystopo.app.online.DirectTrace
import io.github.jasondorjeshort.dystopo.app.options.ShadeService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.packs.PackRoleZooms
import io.github.jasondorjeshort.dystopo.png.Inflate
import io.github.jasondorjeshort.dystopo.png.TerrariumPngDecoder
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.ContourBuildCancelled
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTileStats
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.ContourWarmupProgress
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
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
import java.util.zip.Inflater

/**
 * T57: the tile counters `/stats` reports, kept by [DystopoApp] across tile service reopens (a pack install,
 * a sync that changes settings, `/reload`), so they count since the app started, not since the last reopen.
 * T65: the shade (`shadeGeneration`) and cliff (`cliffs`) counters too.
 */
class TileCounters {
    val dem = SourceCounters()
    val demSynthesized = AtomicLong()
    val osm = SourceCounters()
    val contours = SourceCounters()
    val glyphs = SourceCounters()
    val contourGen = ContourTileStats(keepRecent = 48)
    val contourCacheHits = AtomicLong()
    val contourWaits = AtomicLong()
    val demSynthesis = Timing()

    /** Tile services opened with these counters (1 + reloads). */
    val serviceOpens = AtomicLong()

    /** T65: hillshade and slope tiles (`shadeGeneration`) and cliff rewrites (`cliffs`), cumulative like the rest. */
    val shade = io.github.jasondorjeshort.dystopo.app.options.ShadeCounters()
    val cliffs = CliffCounters()

    /**
     * T98: contour tiles whose sampled DEM was all NaN although a DEM pack's bounds overlap them
     * (`contourGeneration.noDemOverPack`). The pack has elevations there, so such a tile is a bug: the z8 tiles over the
     * Rainier pack with the server unreachable were exactly that, served as empty tiles the map then kept. T106: only
     * when the pack itself has an elevation in the tile's window ([noDemWherePackHasData]); a tile inside a pack's box
     * where the pack has none either (the sea off a coast) is no defect and is not counted.
     */
    val contourNoDemOverPack = TileTally()
}

/**
 * T106 (a T98 follow-up): whether a contour tile sampled with no elevation at all ([grid] all NaN) over the DEM packs whose
 * bounds overlap it is the T98 defect: true when one of [packWindows] (each such pack's own elevations in the tile's
 * window, read from its first zoom up) has a value. A pack's box is a box: off a coast the pack has no data either, and
 * an empty contour tile there is right. [packWindows] is read only when the grid is all NaN. Pure, tested.
 */
internal fun noDemWherePackHasData(grid: FloatArray, packWindows: Sequence<FloatArray>): Boolean =
    grid.all { it.isNaN() } && packWindows.any { w -> w.any { !it.isNaN() } }

/** T98: how often something that should not happen happened, and the last few tiles it happened to (`/stats`). */
class TileTally(private val keep: Int = 8) {
    private val count = AtomicLong()
    private val recent = ArrayDeque<String>()

    fun add(tile: String) {
        count.incrementAndGet()
        synchronized(recent) {
            recent.addLast(tile)
            while (recent.size > keep) recent.removeFirst()
        }
    }

    fun json(): Map<String, Any?> = mapOf("count" to count.get(), "recent" to synchronized(recent) { recent.toList() })
}

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
 * `/cache/clear`.
 *
 * T41: every usable pack is served at once. Each tile goes to the archive [PackSet.demFor] /
 * [PackSet.osmFor] picks ([io.github.jasondorjeshort.dystopo.packs.PackPrecedence]); contour tiles are
 * generated from the chosen pack's own [DemPyramid] (the last few used are kept) into that pack's
 * directory of the shared [ContourCache] (kept across switches, one LRU cap). Cliff lines in vector tiles
 * are turned so the ground falls to their right ([CliffSides]), the side the style draws ticks on.
 */
class TileService(
    val packs: PackSet, private val cacheDir: File, private val port: Int, private val token: LoopbackToken,
    /** T57: the `/stats` counters, owned by the app so they stay cumulative when a reload replaces the service. */
    val counters: TileCounters = TileCounters(),
) {
    val dem = counters.dem
    val demSynthesized = counters.demSynthesized
    val osm = counters.osm
    val contours = counters.contours
    val glyphs = counters.glyphs
    val contourGen = counters.contourGen
    val contourCacheHits = counters.contourCacheHits
    val contourWaits = counters.contourWaits
    val demSynthesis = counters.demSynthesis

    private val contourOptions = ContourTileOptions(
        units = AppSettings.units, // T33: the units preference
        interval = AppSettings.mapOptions.contourInterval, intervalFromZoom = MapOptions.FIXED_INTERVAL_FROM_ZOOM, // T47: fixed interval
    )
    private val intervalTag = contourOptions.interval?.let { "-i" + MapOptions.fmt(it) } ?: "" // T47

    /** T41: each DEM pack's identity, part of its contour cache directory. */
    private val identities: Map<Pack, String> = packs.demPacks.associateWith { packIdentity(it.file) }

    /** All DEM packs' identities together, in the style's tile URLs (MapLibre's own cache never serves a replaced pack's tiles). */
    val demIdentity: String = when (identities.size) {
        0 -> "none"
        1 -> identities.values.first()
        else -> "%08x".format(CRC32().apply { update(identities.values.sorted().joinToString(",").toByteArray()) }.value)
    }

    /** The contour cache directory of [p]'s tiles in the current unit (see [ContourCache]). */
    fun contourDir(p: Pack): String = ContourCache.dirName(contourOptions.units.symbol, contourOptions.tileSize, identities[p] ?: packIdentity(p.file), intervalTag)

    /** The primary pack's contour cache directory (reported in `/stats`). */
    val contourCacheVersion: String = packs.dem?.let(::contourDir) ?: ContourCache.dirName(contourOptions.units.symbol, contourOptions.tileSize, "none", intervalTag)
    private val contourCache get() = ContourCache.get()

    /** T38: tiles no pack covers come from the configured server ([OnlineTiles]); null when none is set. */
    val online: OnlineTiles? = OnlineService.get().tilesFor(packs, "${ContourCache.VERSION}-${contourOptions.units.symbol}-${contourOptions.tileSize}$intervalTag")

    /** Tiles generating at once (T63: also the server's generation pool size, [GEN_THREADS]). */
    val contourPermits = GEN_THREADS
    private val contourSlots = Semaphore(contourPermits)
    private val contourInFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    /**
     * Decoded DEM tiles per DEM pack, shared by contour generation, DEM synthesis and cliff sides; thread-safe.
     * 96 tiles (24 MB) hold a screenful of contour tiles with their halo neighbours: a 512-px tile plus halo
     * touches up to 16 DEM tiles. T41: one pyramid per pack, made on first use; only the [MAX_PYRAMIDS] most
     * recently used are kept (a view rarely spans more packs than that).
     */
    private val pyramids = object : LinkedHashMap<Pack, DemPyramid>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pack, DemPyramid>?) = size > MAX_PYRAMIDS
    }
    private val pyramidsMade = AtomicInteger()

    private fun pyramid(pack: Pack): DemPyramid = synchronized(pyramids) {
        pyramids.getOrPut(pack) {
            pyramidsMade.incrementAndGet()
            DemPyramid(pack.header.minZoom, pack.header.maxZoom, demDecoder(), cacheTiles = 96) { z, x, y -> pack.tile(z, x, y) }
        }
    }

    /** The primary pack's pyramid (warm-up); null without a DEM pack. */
    private val pyramid: DemPyramid? get() = packs.dem?.let(::pyramid)

    /** T47: hillshade and slope raster tiles from the DEM for the map options ([ShadeService]). */
    val shade = ShadeService(cacheDir, demIdentity, { z, x, y -> packs.demFor(z, x, y)?.let { pyramid(it) to (identities[it] ?: packIdentity(it.file)) } }, online, contourPermits, counters = counters.shade) // T41: the pack under each tile; T49: its identity; T65: cumulative counters

    /** Contour requests from the map currently in [contourTile]; the warm-up pauses while any are. */
    private val foregroundContours = AtomicInteger()

    /** T63: when the map's last contour request finished (System.nanoTime); the warm-up stays paused a little after it. */
    @Volatile
    private var lastForegroundDone = 0L

    @Volatile
    private var closed = false

    /** Generation timings of the background warm-up, kept apart from the map's own [contourGen]. */
    val warmupGen = ContourTileStats(keepRecent = 8)

    /** Progress of the background warm-up started with this service; null without a DEM pack. */
    val warmup: ContourWarmupProgress? = startWarmup()

    /** T41: the zooms over every DEM pack (the style's DEM and contour sources span them all). */
    val demMinZoom get() = packs.demPacks.minOfOrNull { it.header.minZoom } ?: 8
    val demMaxZoom get() = packs.demPacks.maxOfOrNull { it.header.maxZoom } ?: 14

    /**
     * T127: drops the decoded DEM tiles of every pack's pyramid (made again on first use, from the packs), the online
     * pyramid's ([OnlineTiles.trimMemory]) and the cliff memo; [HeapGuard] calls it on the application's trim and when the heap is short before a direct render. The bytes dropped.
     */
    fun trimMemory(): Long {
        val dropped = synchronized(pyramids) {
            pyramids.values.sumOf { p -> p.cachedTiles.toLong() * p.tileSize * p.tileSize * 4 }.also { pyramids.clear() }
        }
        return dropped + cliffs.trimMemory() + (online?.trimMemory() ?: 0L)
    }

    init {
        io.github.jasondorjeshort.dystopo.app.online.HeapGuard.register("tileService", ::trimMemory) // T127: the newest service's caches
    }

    fun pyramidJson(): Map<String, Any?> {
        val ps = synchronized(pyramids) { pyramids.entries.map { it.key to it.value } }
        if (ps.isEmpty()) return mapOf("loaded" to false, "pyramidsMade" to pyramidsMade.get())
        return mapOf(
            "loaded" to true,
            "pyramids" to ps.size,
            "pyramidsMade" to pyramidsMade.get(),
            "cachedTiles" to ps.sumOf { it.second.cachedTiles },
            "cachedTileBytes" to ps.sumOf { (_, p) -> p.cachedTiles.toLong() * p.tileSize * p.tileSize * 4 },
            "tilesDecoded" to ps.sumOf { it.second.tilesDecoded },
            "duplicateDecodes" to ps.sumOf { it.second.duplicateDecodes },
            "inFlightWaits" to ps.sumOf { it.second.inFlightWaits }, // T63: a thread waited for another's decode of the same tile
            "cacheHits" to ps.sumOf { it.second.cacheHits },
            // T42: which decoder, and what decoding costs (fetch is the pack read).
            "decoder" to decoderName,
            "decodeMs" to round1(ps.sumOf { it.second.decodeMs }),
            "avgDecodeMs" to ps.sumOf { it.second.tilesDecoded }.let { n -> if (n == 0L) null else Math.round(ps.sumOf { it.second.decodeMs } / n * 100) / 100.0 },
            "maxDecodeMs" to round1(ps.maxOf { it.second.maxDecodeMs }),
            "fetchMs" to round1(ps.sumOf { it.second.fetchMs }),
            "decoderFallbacks" to kotlinDecoder.fallbacks + kotlinZipDecoder.fallbacks,
            "packs" to ps.map { (k, p) -> mapOf("pack" to packs.packOf(k), "cachedTiles" to p.cachedTiles, "tilesDecoded" to p.tilesDecoded) },
            "kotlinPhases" to (if (decoderName == "kotlin-zip") kotlinZipDecoder else kotlinDecoder).phases(),
        )
    }

    /**
     * `GET /dem/decode-bench?tiles=16&runs=5&threads=1&zoom=` (T42): decodes [tiles] pack DEM tiles around the
     * pack centre at [zoom] (default the pack's max) to elevations with every decoder, after one untimed pass
     * each, on `threads` threads at once (more than one imitates concurrent contour tiles): per decoder the
     * average, median and max ms per tile, plus the inflate step alone (pure Kotlin and java.util.zip) and
     * BitmapFactory alone; and whether the decoders agree bit for bit.
     */
    fun decodeBench(query: Map<String, String>): Map<String, Any?> {
        val pack = packs.dem ?: return mapOf("error" to "no DEM pack")
        val h = pack.header
        val n = (query["tiles"]?.toIntOrNull() ?: 16).coerceIn(1, 64)
        val runs = (query["runs"]?.toIntOrNull() ?: 5).coerceIn(1, 50)
        val threads = (query["threads"]?.toIntOrNull() ?: 1).coerceIn(1, 8)
        val z = (query["zoom"]?.toIntOrNull() ?: h.maxZoom).coerceIn(h.minZoom, h.maxZoom)
        val cx = WebMercator.lonToX((h.minLon + h.maxLon) / 2, z).toInt()
        val cy = WebMercator.latToY((h.minLat + h.maxLat) / 2, z).toInt()
        val side = Math.ceil(Math.sqrt(n.toDouble())).toInt()
        val tiles = ArrayList<ByteArray>()
        for (dy in 0 until side) for (dx in 0 until side) if (tiles.size < n) pack.tile(z, cx + dx - side / 2, cy + dy - side / 2)?.let { tiles += it }
        if (tiles.isEmpty()) return mapOf("error" to "no tiles at z$z")
        val ts = 256
        val rawSize = ts * (ts * 3 + 1)
        val bitmapOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val variants = linkedMapOf<String, (ByteArray) -> Any>(
            "bitmap" to { b -> BitmapImageDecoder.decode(b).argb.let { a -> FloatArray(a.size) { Terrarium.elevation(a[it]) } } },
            "kotlin" to { b -> kotlinDecoder.elevations(b, ts, ts) },
            "kotlin-zip" to { b -> kotlinZipDecoder.elevations(b, ts, ts) },
            "inflateKotlinOnly" to { b -> Inflate.zlib(idatOf(b), rawSize) },
            "inflateZipOnly" to { b -> zipInflate(idatOf(b), rawSize) },
            "bitmapFactoryOnly" to { b -> BitmapFactory.decodeByteArray(b, 0, b.size, bitmapOptions).also { it.recycle() } },
        )
        val report = linkedMapOf<String, Any?>(
            "zoom" to z, "tiles" to tiles.size, "runs" to runs, "threads" to threads, "avgPngBytes" to tiles.sumOf { it.size } / tiles.size,
        )
        val outputs = HashMap<String, List<Any>>()
        for ((name, f) in variants) {
            outputs[name] = tiles.map(f) // untimed pass (JIT, caches)
            val per = java.util.Collections.synchronizedList(ArrayList<Double>())
            val workers = (0 until threads).map {
                Thread {
                    repeat(runs) {
                        for (b in tiles) {
                            val t0 = System.nanoTime()
                            f(b)
                            per += (System.nanoTime() - t0) / 1e6
                        }
                    }
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            val sorted = per.sorted()
            report[name] = mapOf(
                "avgMs" to Math.round(sorted.average() * 100) / 100.0,
                "medianMs" to Math.round(sorted[sorted.size / 2] * 100) / 100.0,
                "maxMs" to round1(sorted.last()),
            )
        }
        val ref = outputs.getValue("bitmap")
        report["mismatchedTiles"] = listOf("kotlin", "kotlin-zip").associateWith { d ->
            val o = outputs.getValue(d)
            ref.indices.count { !(ref[it] as FloatArray).contentEquals(o[it] as FloatArray) }
        }
        return report
    }

    /** The concatenated IDAT data of a PNG (for [decodeBench]'s inflate-only timings). */
    private fun idatOf(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(png.size)
        var pos = 8
        while (pos + 8 <= png.size) {
            val len = java.nio.ByteBuffer.wrap(png, pos, 4).int
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT") out.write(png, pos + 8, len)
            if (type == "IEND") break
            pos += 12 + len
        }
        return out.toByteArray()
    }

    /** `/stats` contourGeneration: per-zoom timings, concurrency and the cache identity. */
    fun contourJson(): Map<String, Any?> = contourGen.json() + mapOf(
        "permits" to contourPermits,
        "waitedForSameTile" to contourWaits.get(),
        "cacheVersion" to contourCacheVersion,
        "demPack" to packs.dem?.let { p -> mapOf("bytes" to p.file.length(), "mtime" to p.file.lastModified(), "identity" to identities[p]) },
        "demPacks" to packs.demPacks.map { mapOf("pack" to packs.packOf(it), "identity" to identities[it], "cacheDir" to contourDir(it)) }, // T41
        "staleCacheFilesDeleted" to ContourCache.staleFilesDeleted.get(),
        "noDemOverPack" to counters.contourNoDemOverPack.json(), // T98: should stay 0
        "warmup" to (warmup?.json()?.plus("generation" to warmupGen.json()) ?: mapOf("state" to "off", "enabled" to AppSettings.warmup)),
    )

    fun style(): String {
        val base = "http://127.0.0.1:$port"
        // T41: every pack's archives (vector credits first, as before); the zooms span all of them.
        val osmPacks = packs.osmPacks
        val attribution = (osmPacks + packs.demPacks).map { it.attribution }.filter { it.isNotEmpty() }.distinct().joinToString(" | ")
        // T38: with a server the sources also span the server's zooms (the pack's own range is kept inside it).
        val o = online
        val demLo = if (o == null) demMinZoom else minOf(demMinZoom, OnlineTiles.DEM_MIN_ZOOM)
        val demHi = if (o == null) demMaxZoom else if (packs.demPacks.isNotEmpty()) demMaxZoom else OnlineTiles.DEM_MAX_ZOOM
        val osmLo = osmPacks.minOfOrNull { it.header.minZoom } ?: 0
        val osmHi = osmPacks.maxOfOrNull { it.header.maxZoom }
        // The online generation in the URLs makes MapLibre ask again for tiles it got blank while offline.
        val v = demIdentity + (o?.let { "-${it.fetcher.serverKey}-${it.fetcher.generation}" + it.fetcher.contentTag.let { c -> if (c.isEmpty()) "" else "-$c" } + OnlineService.get().renderRefreshes.get().let { r -> if (r > 0) "-r$r" else "" } } ?: "") // T74: and its content versions; T85: and the reloads after rendering (MapLibre keeps a tile it got a 503 for when the style is the same)
        return MapLibreStyle(
            MapLibreSources(
                // The pack identity in the URL keeps MapLibre's own tile cache from serving a replaced pack's tiles.
                demTiles = token.url("$base/dem/{z}/{x}/{y}.png?v=$v"), // T49: every URL carries the loopback token
                demMinZoom = demLo,
                demMaxZoom = demHi,
                contourTiles = token.url("$base/contours/{z}/{x}/{y}.pbf?v=$v-${contourOptions.units.symbol}$intervalTag"), // T33: unit in the URL
                // Contour tile z has the levels of DEM zoom z+1 (512-px vector tiles), and overzoomed DEM is fine
                // two levels past the pack: 20 ft contours at z15 from a z14 pack are smooth bilinear lines.
                contourMinZoom = (demMinZoom - 1).coerceAtLeast(0).let { if (o == null) it else minOf(it, OnlineTiles.CONTOUR_MIN_ZOOM) },
                contourMaxZoom = demHi + 1,
                // `c1`: cliff lines turned downhill-right (T41), so tiles cached before that are not reused.
                osmTiles = if (osmPacks.isNotEmpty() || o != null) token.url("$base/osm/{z}/{x}/{y}.pbf?v=$v-c1") else null,
                osmMinZoom = if (o == null) osmLo else 0,
                osmMaxZoom = if (o == null) osmHi ?: 14 else maxOf(osmHi ?: 0, OnlineTiles.OSM_MAX_ZOOM),
                glyphs = Glyphs.styleUrl(packs.glyphDir, base).let { if (o != null && it.startsWith("http")) "$it?v=$v" else it }.let { if (it.startsWith("http")) token.url(it) else it }, // T38: refetch after offline
                attribution = attribution,
                sprite = SpriteFiles.styleUrl(packs.glyphDir?.parentFile),
                shadeTiles = token.url("$base/shade/{z}/{x}/{y}.png?v=${shade.urlVersion()}"), // T47
                shadeMinZoom = demLo,
                shadeMaxZoom = demHi + 2,
            ),
            units = contourOptions.units, // T33: labels and peak elevations in the contour unit
            textScale = AppSettings.textScale,
            options = AppSettings.mapOptions, // T47
        ).json()
    }

    fun demTile(z: Int, x: Int, y: Int): TileResult {
        online?.takeUnless { it.packCoversDem(z, x, y) }?.let { return it.demTile(z, x, y) } // T38: online fallback
        // T41: the packs that overlap the tile, best first; the first one storing it answers.
        val candidates = packs.demCandidates(z, x, y)
        if (candidates.isEmpty()) return TileResult.notFound().also { dem.notFound.incrementAndGet() }
        for (pack in candidates) pack.tile(z, x, y)?.let { return ok(dem, "image/png", it) }
        // Not stored: synthesize from the nearest ancestor so hillshade continues past a deep level's edge.
        val started = System.nanoTime()
        val grid = candidates.firstNotNullOfOrNull { pack ->
            val p = pyramid(pack)
            val ts = p.tileSize
            if (!p.covers(z, x * ts, y * ts, ts, ts)) null else p.window(z, x * ts, y * ts, ts, ts).takeUnless { g -> g.values.all { it.isNaN() } }
        }
        if (grid == null) {
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

    /** T47: a hillshade/slope tile; the pack's where its DEM covers the tile, else the online DEM's. */
    fun shadeTile(z: Int, x: Int, y: Int): TileResult = shade.tile(z, x, y, packCovers = online?.packCoversDem(z, x, y) ?: true)

    fun osmTile(z: Int, x: Int, y: Int): TileResult {
        online?.takeUnless { it.packCoversOsm(z, x, y) }?.let { return it.osmTile(z, x, y) } // T38: online fallback
        // T41: the best vector archive holding the tile (outdoors profile first, see PackPrecedence).
        for (pack in packs.osmCandidates(z, x, y)) {
            val bytes = pack.tile(z, x, y) ?: continue
            return ok(osm, "application/x-protobuf", atGenerationPriority { cliffs.orient(z, x, y, bytes, cliffDir(pack, z, x, y)) })
        }
        return TileResult.notFound().also { osm.notFound.incrementAndGet() }
    }

    /**
     * T63: runs [block] at the generation pool's niceness ([GEN_NICE]) and restores the thread's own after: cliff
     * rewriting decodes DEM tiles inside an OSM request on the tile pool, and is generation like the contours.
     */
    private inline fun <T> atGenerationPriority(block: () -> T): T {
        val tid = Process.myTid()
        val before = runCatching { Process.getThreadPriority(tid) }.getOrNull()
        if (before == null || before >= GEN_NICE) return block()
        runCatching { Process.setThreadPriority(GEN_NICE) }
        try {
            return block()
        } finally {
            runCatching { Process.setThreadPriority(before) }
        }
    }

    /** T41: cliff ticks on the downhill side, from the DEM pack under the tile; T49: cached on disk with the contours. */
    val cliffs = CliffTiles(this::cliffPyramid, counters.cliffs) { contourCache } // T65: cumulative counters

    /** T49: each vector archive's identity, part of its cliff cache directory. */
    private val osmIdentities: Map<Pack, String> = packs.osmPacks.associateWith { packIdentity(it.file) }

    /** The cliff cache directory of vector tile z/x/y from [osmPack]; null without a DEM pack there. */
    private fun cliffDir(osmPack: Pack, z: Int, x: Int, y: Int): String? {
        val demPack = packs.demFor(z, x, y) ?: return null
        return ContourCache.cliffDir(osmIdentities[osmPack] ?: return null, identities[demPack] ?: return null)
    }

    /** The pyramid of the DEM pack that answers DEM tile z/x/y, for [cliffs]; null outside every pack. */
    private fun cliffPyramid(z: Int, x: Int, y: Int): DemPyramid? = packs.demFor(z, x, y)?.let(::pyramid)

    fun contourTile(z: Int, x: Int, y: Int): TileResult {
        foregroundContours.incrementAndGet()
        try {
            // T38: outside the pack, contours come from the online DEM through the same generation path (not the pack's cache).
            online?.takeUnless { it.packCoversContour(z, x, y) }?.let { o ->
                if (o.fetcher.server == DirectSources.SERVER) DirectTrace.mark("c? $z/$x/$y") // T88
                DirectSources.staleContour(o, contourOptions, z, x, y) { p, d -> generateContourTile(p, z, x, y, null, contourGen, options = d) }?.let { DirectTrace.mark("c200p $z/$x/$y ${it.size}"); return ok(contours, "application/x-protobuf", it) } // T85: not kept
                val made = o.contourTile(z, x, y) { p -> DirectSources.contourOptions(contourOptions, o, z, x, y).let { d -> generateContourTile(DirectSources.contourPyramid(o, d, z, x, y) ?: p, z, x, y, null, contourGen, options = d) } } // T85: direct sources read the hillshade zoom; T98: a server's tile over a pack at least the pack's first zoom
                if (o.fetcher.server == DirectSources.SERVER) DirectTrace.mark("c200 $z/$x/$y ${made.size}") // T88
                return ok(contours, "application/x-protobuf", made)
            }
            // T41: the DEM pack that holds the tile (else the best one overlapping it) makes it, into its own cache directory.
            val pack = packs.demFor(z, x, y, PackRoleZooms.CONTOUR) ?: return TileResult.notFound().also { contours.notFound.incrementAndGet() }
            val bytes = contourBytes(pack, z, x, y, contourGen)
            return ok(contours, "application/x-protobuf", bytes)
        } catch (e: io.github.jasondorjeshort.dystopo.app.online.OnlineTiles.NotReady) {
            DirectTrace.mark("c503 $z/$x/$y") // T88
            return TileResult(503, "text/plain", "${e.message}\n".toByteArray()) // T80: MapLibre retries a 503 with backoff
        } catch (e: Throwable) {
            contours.errors.incrementAndGet()
            throw e
        } finally {
            foregroundContours.decrementAndGet()
            lastForegroundDone = System.nanoTime()
        }
    }

    /**
     * Contour tile bytes of [pack] from the disk cache or generated (timed into [stats]).
     * One generation per tile: a second request for the same tile, from the map or the warm-up, waits
     * for the first.
     */
    private fun contourBytes(
        pack: Pack,
        z: Int,
        x: Int,
        y: Int,
        stats: ContourTileStats,
        cancel: (() -> Boolean)? = null,
        onCacheHit: () -> Unit = { contourCacheHits.incrementAndGet() },
    ): ByteArray {
        val key = ContourCache.key(contourDir(pack), z, x, y)
        contourCache.get(key)?.let {
            onCacheHit()
            return it
        }
        val p = pyramid(pack)
        val mine = CompletableFuture<ByteArray?>()
        val running = contourInFlight.putIfAbsent(key, mine)
        if (running != null) {
            contourWaits.incrementAndGet()
            return try {
                running.get() ?: ByteArray(0)
            } catch (e: ExecutionException) {
                // T42: the warm-up gave this tile up for a map request (maybe this one): generate it here.
                if (e.cause is ContourBuildCancelled) return contourBytes(pack, z, x, y, stats, cancel, onCacheHit)
                throw e.cause ?: e
            }
        }
        try {
            val bytes = generateContourTile(p, z, x, y, key, stats, cancel)
            mine.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            contourInFlight.remove(key, mine)
        }
    }

    /**
     * T106: [pack]'s own elevations in contour tile z/x/y's window, read at least at the pack's first zoom
     * ([io.github.jasondorjeshort.dystopo.app.online.OnlineTiles.readFrom]) from the pack alone; empty when that fails.
     */
    private fun packWindow(pack: Pack, z: Int, x: Int, y: Int, options: ContourTileOptions, tileSize: Int): FloatArray = runCatching {
        val o = io.github.jasondorjeshort.dystopo.app.online.OnlineTiles.readFrom(options, pack.header.minZoom, z, x, y)
        ContourTiles.sample(pyramid(pack), ContourTiles.plan(z, x, y, o, tileSize)).values
    }.getOrDefault(FloatArray(0))

    /** [cancel] (T42, the warm-up's): polled between steps; true abandons the tile with [ContourBuildCancelled]. */
    private fun generateContourTile(p: DemPyramid, z: Int, x: Int, y: Int, cacheKey: String?, stats: ContourTileStats, cancel: (() -> Boolean)? = null, options: ContourTileOptions = contourOptions): ByteArray {
        val requested = System.nanoTime()
        contourSlots.acquire()
        val concurrent = stats.begin()
        try {
            val started = System.nanoTime()
            val plan = ContourTiles.plan(z, x, y, options, p.tileSize) // T85: direct sources read the hillshade's zoom
            if (cancel != null && cancel()) throw ContourBuildCancelled("contour tile $z/$x/$y cancelled before sampling")
            val grid = ContourTiles.sample(p, plan)
            val sampled = System.nanoTime()
            // T98: a pack's bounds overlap the tile but the window has no elevation at all: an empty tile where the pack has data
            // (T106: the pack's own window has some; a coast inside the pack's box has none and is not counted).
            if (grid.values.all { it.isNaN() }) {
                val overlapping = packs.demCandidates(z, x, y, PackRoleZooms.CONTOUR)
                if (overlapping.isNotEmpty() && noDemWherePackHasData(grid.values, overlapping.asSequence().map { packWindow(it, z, x, y, options, p.tileSize) })) {
                    counters.contourNoDemOverPack.add("$z/$x/$y")
                }
            }
            val tile = ContourTiles.build(grid, plan, contourOptions, cancel)
            val done = System.nanoTime()
            if (cacheKey != null) contourCache.put(cacheKey, tile.bytes) // T41: the shared LRU cache (atomic rename inside)
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
     * switch or `/reload` are cached and the JIT and DEM caches are warm. A map request that arrives while a
     * warm-up tile is generating pre-empts it (T42): the tile's cancel check sees the request between steps
     * and level groups, the tile is dropped (`preempted` in `/stats`) and retried after the pause. Stops when
     * this service is [close]d. Timings go to [warmupGen], not [contourGen].
     *
     * T63: the map's visible tiles come first: the warm-up also stays paused until [WARMUP_QUIET_MS] after the
     * map's last contour tile, so it does not take a generation slot between two of MapLibre's request bursts.
     * It does start at once with the service, though: holding it back until the first view had settled made
     * that view slower (on a cold start it warms the JIT and the DEM decoder while the activity launches).
     */
    private fun startWarmup(): ContourWarmupProgress? {
        if (!AppSettings.warmup) return null // T33: settings switch
        val warmPack = packs.dem ?: return null // T41: the primary pack
        val header = warmPack.header
        if (pyramid == null || header.minLon >= header.maxLon || header.minLat >= header.maxLat) return null
        val bounds = BoundingBox(header.minLon, header.minLat, header.maxLon, header.maxLat)
        // The style's highest contour zoom is the pack's max + 1 (see style()).
        val plan = ContourTiles.warmupPlan(bounds, header.minZoom, zoomCount = WARMUP_ZOOMS, maxTiles = WARMUP_MAX_TILES, centreUpTo = header.maxZoom + 1)
        val progress = ContourWarmupProgress(plan.map { it.z }.distinct(), plan.size)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val yieldToMap = { foregroundContours.get() > 0 || closed }
            var i = 0
            while (i < plan.size) {
                val t = plan[i]
                // Yield to the map: wait while any of its contour tiles is generating.
                val pauseStart = System.nanoTime()
                while ((foregroundContours.get() > 0 || System.nanoTime() - lastForegroundDone < WARMUP_QUIET_MS * 1_000_000L) && !closed) Thread.sleep(WARMUP_POLL_MS)
                if (closed) break
                val paused = System.nanoTime() - pauseStart
                if (paused > WARMUP_POLL_MS * 500_000L) progress.paused(paused / 1e6)
                try {
                    var hit = false
                    contourBytes(packs.demFor(t.z, t.x, t.y, PackRoleZooms.CONTOUR) ?: warmPack, t.z, t.x, t.y, warmupGen, yieldToMap) { hit = true }
                    if (hit) progress.cached() else progress.generated()
                } catch (e: ContourBuildCancelled) {
                    if (closed) break
                    progress.preempted()
                    continue // the same tile again, after the map's requests
                } catch (e: Throwable) {
                    if (closed) break
                    progress.failed(e)
                }
                i++
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
            dir?.let { File(it, "$font/$range.pbf") }?.takeIf { f -> f.isFile && f.length() > 0 && f.canonicalPath.startsWith(dir.canonicalPath) } // T80: an empty file is a miss
        }
        if (file == null) {
            online?.glyph(fontstack, range)?.let { return if (it.status == 200) ok(glyphs, it.contentType, it.body) else it } // T38: from the server
            // An empty glyph set: the labels in this range draw without these characters instead of erroring.
            // T80: a valid PBF with no glyphs, not zero bytes (MapLibre fails an empty 200 and never reports loaded).
            glyphs.notFound.incrementAndGet()
            val font = fontstack.split(",").first().trim()
            return TileResult(200, "application/x-protobuf", io.github.jasondorjeshort.dystopo.app.firstrun.EmptyGlyphs.pbf(font, range))
        }
        return ok(glyphs, "application/x-protobuf", file.readBytes())
    }

    /** Deletes the cached contour tiles of every pack; returns how many files went. */
    fun clearContourCache(): Int = ContourCache.clear() // T41: every pack's directory

    private fun ok(c: SourceCounters, type: String, bytes: ByteArray): TileResult {
        c.served.incrementAndGet()
        c.bytes.addAndGet(bytes.size.toLong())
        return TileResult(200, type, bytes)
    }

    companion object {
        /**
         * DEM tile decoder (T42): `kotlin` (default), the shared pure-Kotlin [TerrariumPngDecoder] with the
         * pure-Kotlin [Inflate], straight to elevations, no Bitmap; `kotlin-zip`, the same decoder with zlib
         * from java.util.zip (slower on the emulator, see [Inflate]); or `bitmap`, the old
         * [BitmapImageDecoder] through BitmapFactory, kept for comparison. Not persisted:
         * `POST /settings?decoder=kotlin|kotlin-zip|bitmap` switches it for this process and reopens the tiles.
         */
        @Volatile
        var decoderName: String = "kotlin"
            private set

        val DECODERS = listOf("kotlin", "kotlin-zip", "bitmap")

        /** One [Inflater] per thread, reset between tiles (one per tile would churn native memory). */
        private val inflaters = ThreadLocal.withInitial { Inflater() }

        private val zipInflate: Decompressor = { input, size ->
            val inflater = inflaters.get()!!
            inflater.reset()
            inflater.setInput(input)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) {
                val k = inflater.inflate(out, n, size - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            require(n == size) { "zlib stream ended after $n of $size bytes" }
            out
        }

        /** The shared decoder; BitmapFactory takes the PNGs it does not read (palette, grey, 16-bit). */
        val kotlinDecoder = TerrariumPngDecoder(Inflate.zlib, BitmapImageDecoder)

        /** [kotlinDecoder] with java.util.zip's inflate, for comparison. */
        val kotlinZipDecoder = TerrariumPngDecoder(zipInflate, BitmapImageDecoder)

        /** The DEM decoder [decoderName] selects, for every [DemPyramid] of the app (pack and online). */
        fun demDecoder(): ImageDecoder = when (decoderName) {
            "bitmap" -> BitmapImageDecoder
            "kotlin-zip" -> kotlinZipDecoder
            else -> kotlinDecoder
        }

        /** Sets the decoder from a `/settings` value and reopens the tiles; returns an error message for a bad value. */
        fun setDecoder(app: DystopoApp, raw: String): String? {
            val name = raw.trim().lowercase()
            if (name !in DECODERS) return "decoder must be one of $DECODERS (got $raw)"
            if (name != decoderName) {
                decoderName = name
                app.reload()
            }
            return null
        }

        /** T63: generation requests (contours, shade) run on the server's generation pool, below the tile pool. */
        fun isGenerationRequest(path: String): Boolean = path.startsWith("/contours/") || path.startsWith("/shade/")

        /**
         * T88: requests that may wait seconds on the network: DEM, contour and shade tiles while the phone reads the
         * sources itself (no server; [OnlineService.isDirect]). They run on the server's wait pool ([LocalServer]), so
         * the tile and generation workers stay free for OpenFreeMap tiles and for tiles whose DEM is there.
         */
        fun isWaitingRequest(path: String): Boolean =
            (path.startsWith("/dem/") || path.startsWith("/contours/") || path.startsWith("/shade/")) &&
                runCatching { OnlineService.get().isDirect }.getOrDefault(false)

        /** T88: the wait pool's most workers: MapLibre asks for a few dozen tiles of a view at once. */
        const val WAIT_THREADS = 48

        /** T63: tiles generating at once and the server's generation pool size; one core is left to the map renderer. */
        val GEN_THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)

        /**
         * T63: niceness of the generation pool's threads: the default, below the pack reads ([TILE_NICE]) and
         * above the warm-up (background, 10). Nice 4 was tried: on the loaded emulator (MapLibre plus the release
         * script's `screencap`) contour tiles then took about three times as long.
         */
        const val GEN_NICE = 0

        /** T63: niceness of the server's tile pool (pack reads, glyphs, the style): slightly ahead of generation. */
        const val TILE_NICE = -2

        /** T63: the warm-up resumes this long after the map's last contour tile. */
        const val WARMUP_QUIET_MS = 1_000L

        /** Lowest pack zooms warmed up: the fitted whole-pack view and the first zoom-in. */
        const val WARMUP_ZOOMS = 2
        const val WARMUP_MAX_TILES = 256
        const val WARMUP_POLL_MS = 50L
        /** DEM pyramids kept at once (T41): 96 decoded tiles, 24 MB, each. */
        const val MAX_PYRAMIDS = 2
    }
}
