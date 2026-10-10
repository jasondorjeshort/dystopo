package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiff.GeoTiff
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val deflate: Decompressor = { input, size ->
    val inflater = Inflater()
    inflater.setInput(input)
    val out = ByteArray(size)
    var n = 0
    while (n < size && !inflater.finished()) n += inflater.inflate(out, n, size - n)
    inflater.end()
    out
}

/**
 * A Cloud-Optimized GeoTIFF read over HTTP range requests, with raw bytes cached on disk and
 * decoded tiles cached in memory per resolution level. [extraDecompressors] add codecs by TIFF
 * compression code (LERC for New Zealand's DEM).
 */
class RemoteTiff(url: String, cacheDir: File, extraDecompressors: Map<Int, Decompressor> = emptyMap()) {
    val source = CachedHttpRangeSource(url, cacheDir)
    val tiff: GeoTiff = GeoTiff.open(source, mapOf(8 to deflate, 32946 to deflate) + extraDecompressors)
    private val levels = tiff.levels
    private val caches = levels.map { ConcurrentHashMap<Int, FloatArray>() }

    val tilesDecoded: Int get() = caches.sumOf { it.size }

    fun level(index: Int): GeoTiff = levels[index]

    /**
     * Coarsest level whose pixels are no bigger than [targetMeters], so we never read more detail
     * than the output can show. [metersPerUnit] converts CRS units to meters (1 for UTM).
     */
    fun levelFor(targetMeters: Double, metersPerUnit: Double): Int {
        var best = 0
        for ((i, l) in levels.withIndex()) if (l.pixelScaleY * metersPerUnit <= targetMeters) best = i
        return best
    }

    private fun tile(levelIndex: Int, tx: Int, ty: Int): FloatArray {
        val l = levels[levelIndex]
        return caches[levelIndex].getOrPut(ty * l.tilesAcross + tx) { l.readTile(tx, ty) }
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
        needed.parallelStream().forEach { (tx, ty) -> tile(levelIndex, tx, ty) }
    }

    /** True if CRS point (x, y) is inside the image's pixel area. */
    fun contains(x: Double, y: Double): Boolean {
        val e = tiff.extent
        return x >= e[0] && x < e[2] && y > e[1] && y <= e[3]
    }

    /**
     * Bilinear sample at CRS (x, y). NaN if any neighbor is no-data, or more than one pixel
     * beyond the outermost pixel centers. Within that pixel the edge value is extended, which
     * closes the sliver between adjacent pixel-is-point files (e.g. Copernicus 1° cells).
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
