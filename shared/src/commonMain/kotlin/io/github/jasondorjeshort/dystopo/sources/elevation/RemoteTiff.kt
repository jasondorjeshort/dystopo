package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiff.GeoTiff
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * HTTP range reads with an on-disk cache per (offset, length) under [cacheDir], so re-running a render doesn't
 * re-download. [fetches] counts network requests and [fetchedBytes] their bytes. [fetchRange] is the network
 * layer (url, offset, length); tests swap in a fake.
 */
class CachedRangeSource(
    private val url: String,
    private val cacheDir: String,
    private val fetchRange: (String, Long, Int) -> ByteArray = SourceIo::range,
) : ByteRangeSource {
    @Volatile var fetches = 0L
        private set
    @Volatile var fetchedBytes = 0L
        private set

    @Synchronized
    private fun count(bytes: Int) {
        fetches++
        fetchedBytes += bytes
    }

    override fun read(offset: Long, length: Int): ByteArray {
        val path = "$cacheDir/$offset-$length.bin"
        SourceFiles.read(path)?.let { return it }
        val bytes = fetchRange(url, offset, length)
        count(bytes.size)
        // Parallel tile renders can fetch the same cold range at once: each writes its own temp file, the first
        // to land wins, and the others return the winner's bytes so every caller agrees.
        return if (SourceFiles.write(path, bytes)) bytes else SourceFiles.read(path) ?: bytes
    }
}

/**
 * A Cloud-Optimized GeoTIFF read over HTTP range requests, with raw bytes cached on disk and decoded tiles
 * cached in memory per resolution level. [extraDecompressors] add codecs by TIFF compression code (LERC for New
 * Zealand's DEM).
 */
class RemoteTiff(url: String, cacheDir: String, extraDecompressors: Map<Int, Decompressor> = emptyMap()) {
    companion object {
        /** A [RemoteTiff] for [url]: from [SourceIo.tiffPool] when one is set (the phone), else a new one (the server, as before). */
        fun open(url: String, cacheDir: String, extraDecompressors: Map<Int, Decompressor> = emptyMap()): RemoteTiff =
            SourceIo.tiffPool?.get(url) { RemoteTiff(url, cacheDir, extraDecompressors) } ?: RemoteTiff(url, cacheDir, extraDecompressors)

        /** [RemoteTiff.levelFor] over a file's level pixel sizes ([scales], CRS units, finest first). */
        fun levelFor(scales: List<Double>, targetMeters: Double, metersPerUnit: Double): Int {
            var best = 0
            for ((i, scale) in scales.withIndex()) if (scale * metersPerUnit <= targetMeters) best = i
            return best
        }

        /**
         * T87: one level for a window read from several files ([cells], each its level pixel sizes): the finest of
         * their own [levelFor] choices, which every file has. Files differ in how many overviews they carry (3DEP's
         * mostly-ocean Hawaii cells have fewer), so one file's choice can be past another's last level.
         */
        fun commonLevel(cells: List<List<Double>>, targetMeters: Double, metersPerUnit: Double): Int =
            cells.minOfOrNull { levelFor(it, targetMeters, metersPerUnit) } ?: 0
    }

    val source = CachedRangeSource(url, cacheDir)
    val tiff: GeoTiff = GeoTiff.open(source, mapOf(8 to zlibDecompressor, 32946 to zlibDecompressor) + extraDecompressors)
    private val levels = tiff.levels
    private val caches = levels.map { SyncMap<Int, FloatArray>() }
    private val shared = SourceIo.decodedBlocks
    private val id = nextId()
    private var decoded = 0

    @Synchronized
    private fun countDecoded() {
        decoded++
    }

    /** Tiles decoded by this instance (each once, unless [SourceIo.decodedBlocks] evicted it in between). */
    val tilesDecoded: Int get() = if (shared == null) caches.sumOf { it.size } else decodedCount()

    @Synchronized
    private fun decodedCount() = decoded

    fun level(index: Int): GeoTiff = levels[index]

    /** The level pixel sizes in CRS units, finest first ([commonLevel]). */
    val levelScales: List<Double> get() = levels.map { it.pixelScaleY }

    /**
     * Coarsest level whose pixels are no bigger than [targetMeters], so we never read more detail than the
     * output can show. [metersPerUnit] converts CRS units to meters (1 for UTM).
     */
    fun levelFor(targetMeters: Double, metersPerUnit: Double): Int = levelFor(levels.map { it.pixelScaleY }, targetMeters, metersPerUnit)

    private fun tile(levelIndex: Int, tx: Int, ty: Int): FloatArray {
        val l = levels[levelIndex]
        val key = ty * l.tilesAcross + tx
        if (shared != null) {
            val k = "$id/$levelIndex/$key"
            shared[k]?.let { return it }
            val decoded = l.readTile(tx, ty)
            countDecoded()
            return shared.put(k, decoded)
        }
        val cache = caches[levelIndex]
        cache[key]?.let { return it }
        // Decoded outside the lock (two threads may decode the same tile once each; the bytes come from the disk cache).
        val decoded = l.readTile(tx, ty)
        return cache.getOrPut(key) { decoded }
    }

    /** Decodes, in parallel, every tile of [levelIndex] overlapping the CRS rectangle. */
    fun prefetch(levelIndex: Int, minX: Double, minY: Double, maxX: Double, maxY: Double) {
        val l = levels[levelIndex]
        val c0 = floor(l.colOf(minX)).toInt().coerceIn(0, l.width - 1)
        val c1 = ceil(l.colOf(maxX)).toInt().coerceIn(0, l.width - 1)
        val r0 = floor(l.rowOf(maxY)).toInt().coerceIn(0, l.height - 1)
        val r1 = ceil(l.rowOf(minY)).toInt().coerceIn(0, l.height - 1)
        val needed = (r0 / l.tileHeight..r1 / l.tileHeight).flatMap { ty ->
            (c0 / l.tileWidth..c1 / l.tileWidth).map { tx -> tx to ty }
        }
        parallelForEach(needed) { (tx, ty) -> tile(levelIndex, tx, ty) }
    }

    /** True if CRS point (x, y) is inside the image's pixel area. */
    fun contains(x: Double, y: Double): Boolean {
        val e = tiff.extent
        return x >= e[0] && x < e[2] && y > e[1] && y <= e[3]
    }

    /**
     * Bilinear sample at CRS (x, y). NaN if any neighbor is no-data, or more than one pixel beyond the outermost
     * pixel centers. Within that pixel the edge value is extended, which closes the sliver between adjacent
     * pixel-is-point files (e.g. Copernicus 1° cells).
     */
    fun sample(levelIndex: Int, x: Double, y: Double): Float {
        val l = levels[levelIndex]
        val col = l.colOf(x)
        val row = l.rowOf(y)
        if (col < -1.0 || row < -1.0 || col > l.width.toDouble() || row > l.height.toDouble()) return Float.NaN
        val c0 = floor(col).toInt()
        val r0 = floor(row).toInt()
        val fx = (col - c0).toFloat()
        val fy = (row - r0).toFloat()
        val a = at(levelIndex, c0, r0)
        val b = at(levelIndex, c0 + 1, r0)
        val c = at(levelIndex, c0, r0 + 1)
        val d = at(levelIndex, c0 + 1, r0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Value of pixel ([col], [row]) at [levelIndex], clamped to the image; NaN for no-data. */
    fun at(levelIndex: Int, col: Int, row: Int): Float {
        val l = levels[levelIndex]
        val c = col.coerceIn(0, l.width - 1)
        val r = row.coerceIn(0, l.height - 1)
        val v = tile(levelIndex, c / l.tileWidth, r / l.tileHeight)[(r % l.tileHeight) * l.tileWidth + c % l.tileWidth]
        val nd = l.noData
        return if (v.isNaN() || (nd != null && abs(v - nd) < 1e-3f)) Float.NaN else v
    }
}

/** Pooled [RemoteTiff]s and decoded blocks for long-running readers (the phone): see [SourceIo.tiffPool]. */
private var lastId = 0

@Synchronized
private fun nextId(): Int = ++lastId

/**
 * The most recently used open [RemoteTiff]s, by URL, so tile after tile reuses one file's header and decoded
 * blocks instead of reopening it per window. Opening happens outside the lock (it may read the header over the
 * network); two threads opening the same file at once keep the first.
 */
class TiffPool(private val size: Int) {
    private val open = LinkedHashMap<String, RemoteTiff>(16, 0.75f, true)
    var opened = 0L
        private set
    var reused = 0L
        private set

    @Synchronized
    private fun lookup(url: String): RemoteTiff? = open[url]?.also { reused++ }

    @Synchronized
    private fun store(url: String, tiff: RemoteTiff): RemoteTiff {
        open[url]?.let { return it }
        open[url] = tiff
        opened++
        while (open.size > size) open.remove(open.keys.first())
        return tiff
    }

    fun get(url: String, make: () -> RemoteTiff): RemoteTiff = lookup(url) ?: store(url, make())
}

/** Decoded GeoTIFF blocks shared by every pooled [RemoteTiff], least recently used out first, at most [capBytes]. */
class DecodedBlocks(private val capBytes: Long) {
    private val blocks = LinkedHashMap<String, FloatArray>(64, 0.75f, true)
    private var bytes = 0L
    var hits = 0L
        private set
    var misses = 0L
        private set

    @Synchronized
    operator fun get(key: String): FloatArray? = blocks[key].also { if (it == null) misses++ else hits++ }

    @Synchronized
    fun put(key: String, values: FloatArray): FloatArray {
        blocks[key]?.let { return it }
        blocks[key] = values
        bytes += values.size * 4L
        while (bytes > capBytes && blocks.size > 1) {
            val eldest = blocks.keys.first()
            bytes -= (blocks.remove(eldest)?.size ?: 0) * 4L
        }
        return values
    }

    val sizeBytes: Long @Synchronized get() = bytes
}
