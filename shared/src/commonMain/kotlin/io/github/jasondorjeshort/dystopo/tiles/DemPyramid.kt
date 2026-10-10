package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/** A decoded image: [argb] pixels, row-major, [width] x [height]. */
class ArgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "expected ${width * height} pixels, got ${argb.size}" }
    }
}

/**
 * Decodes an encoded image (PNG for Terrarium tiles) to ARGB pixels. Image codecs are platform
 * code (ImageIO on the JVM, BitmapFactory on Android), so they are injected. Implementations must
 * not apply colour-space conversion or premultiplied alpha: the pixels are data, not colour.
 */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): ArgbImage
}

/**
 * A Terrarium DEM tile pyramid (a region pack) read tile by tile through [fetch], with decoded
 * tiles kept in a small LRU cache. [window] samples any Web Mercator pixel window at any zoom:
 * pixels at a zoom the pack has are copied exactly; where the pack has no tile (beyond [maxZoom],
 * or past the edge of a deeper level), the nearest ancestor that has one is sampled bilinearly
 * (overzoom), so contour tiles can be generated for every zoom a viewer asks for and the tiles
 * around the edge of a deep level fade to a softer ancestor instead of stopping.
 *
 * Not thread-safe: callers serialize access (the readers behind [fetch] usually aren't either).
 */
class DemPyramid(
    val minZoom: Int,
    val maxZoom: Int,
    private val decoder: ImageDecoder,
    private val cacheTiles: Int = 64,
    val tileSize: Int = 256,
    /** Encoded tile bytes for z/x/y, or null when the pack has no such tile. */
    private val fetch: (z: Int, x: Int, y: Int) -> ByteArray?,
) {
    init {
        require(minZoom in 0..maxZoom) { "bad zoom range $minZoom..$maxZoom" }
        require(cacheTiles >= 4) { "cacheTiles must be >= 4" }
    }

    /** Tiles fetched and decoded (cache misses that found a tile). */
    var tilesDecoded: Long = 0
        private set

    /** Lookups that found a decoded tile, or a known-missing one, in the cache. */
    var cacheHits: Long = 0
        private set


    // LinkedHashMap keeps insertion order; re-inserting on a hit makes that order least-recently-used first.
    private val cache = LinkedHashMap<Long, FloatArray>()
    private val missing = HashSet<Long>()

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** Elevations of one tile (row-major, [tileSize] square), or null when the pack has no such tile. */
    fun tile(z: Int, x: Int, y: Int): FloatArray? {
        if (z < minZoom || z > maxZoom) return null
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val k = key(z, x, y)
        cache.remove(k)?.let {
            cache[k] = it
            cacheHits++
            return it
        }
        if (k in missing) {
            cacheHits++
            return null
        }
        val bytes = fetch(z, x, y)
        if (bytes == null) {
            if (missing.size > 4096) missing.clear()
            missing += k
            return null
        }
        val img = decoder.decode(bytes)
        require(img.width == tileSize && img.height == tileSize) {
            "tile $z/$x/$y is ${img.width}x${img.height}, expected ${tileSize}x$tileSize"
        }
        val values = FloatArray(img.argb.size) { Terrarium.elevation(img.argb[it]) }
        tilesDecoded++
        cache[k] = values
        if (cache.size > cacheTiles) cache.remove(cache.keys.first())
        return values
    }

    /** Number of decoded tiles currently held. */
    val cachedTiles: Int get() = cache.size

    /** Elevation of global pixel ([px], [py]) at zoom [z] from that zoom's tile only; NaN if absent. */
    private fun exact(z: Int, px: Int, py: Int, lookup: Lookup): Float {
        val ts = tileSize
        val t = lookup.get(z, floorDiv(px, ts), floorDiv(py, ts)) ?: return Float.NaN
        return t[floorMod(py, ts) * ts + floorMod(px, ts)]
    }

    /**
     * Bilinear sample at fractional global pixel coordinates ([sx], [sy]) (pixel centers at
     * integers) at zoom [z], or NaN when any of the four samples is missing.
     */
    private fun bilinear(z: Int, sx: Double, sy: Double, lookup: Lookup): Float {
        val worldPx = tileSize.toLong() shl z
        val maxPx = (worldPx - 1).toDouble()
        val cx = sx.coerceIn(0.0, maxPx)
        val cy = sy.coerceIn(0.0, maxPx)
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = minOf(x0 + 1, maxPx.toInt())
        val y1 = minOf(y0 + 1, maxPx.toInt())
        val fx = (cx - x0).toFloat()
        val fy = (cy - y0).toFloat()
        val a = exact(z, x0, y0, lookup)
        val b = exact(z, x1, y0, lookup)
        val c = exact(z, x0, y1, lookup)
        val d = exact(z, x1, y1, lookup)
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) return Float.NaN
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /**
     * Elevation at global pixel ([px], [py]) of zoom [z]: exact when the pack has that tile,
     * otherwise bilinear from the deepest ancestor that has all four samples; NaN when none does.
     */
    fun elevation(z: Int, px: Int, py: Int): Float = elevation(z, px, py, Lookup())

    private fun elevation(z: Int, px: Int, py: Int, lookup: Lookup): Float {
        if (z in minZoom..maxZoom) {
            val v = exact(z, px, py, lookup)
            if (!v.isNaN()) return v
        }
        var az = minOf(z - 1, maxZoom)
        while (az >= minZoom) {
            val scale = (1L shl (z - az)).toDouble()
            val v = bilinear(az, (px + 0.5) / scale - 0.5, (py + 0.5) / scale - 0.5, lookup)
            if (!v.isNaN()) return v
            az--
        }
        return Float.NaN
    }

    /**
     * An elevation grid for the global pixel window ([x0], [y0], [width] x [height]) at zoom [z].
     * Cells nobody covers are NaN. Below [minZoom] everything is NaN (no downsampling).
     */
    fun window(z: Int, x0: Int, y0: Int, width: Int, height: Int): ElevationGrid {
        val values = FloatArray(width * height)
        if (!covers(z, x0, y0, width, height)) {
            values.fill(Float.NaN)
        } else {
            // One lookup table per window: each tile goes through the LRU cache once, not once per pixel.
            val lookup = Lookup()
            for (j in 0 until height) {
                val py = y0 + j
                for (i in 0 until width) values[j * width + i] = elevation(z, x0 + i, py, lookup)
            }
        }
        val worldPx = (tileSize.toLong() shl z).toDouble()
        val midLat = WebMercator.yToLat((y0 + height / 2.0) / tileSize, z).coerceIn(-85.0, 85.0)
        val cell = WebMercator.metersPerPixel(midLat, z, tileSize).takeIf { it > 0 && worldPx > 0 } ?: 1.0
        return ElevationGrid(width, height, cell, values)
    }

    /**
     * True when some tile of the pack, at [z] or an ancestor zoom, overlaps the window: a cheap test
     * (a few tile lookups) that saves the per-pixel ancestor search for windows outside the pack.
     */
    fun covers(z: Int, x0: Int, y0: Int, width: Int, height: Int): Boolean {
        var az = minOf(z, maxZoom)
        while (az >= minZoom) {
            val shift = z - az
            val n = 1 shl az
            val tx0 = (floorDiv(x0, tileSize) shr shift).coerceIn(0, n - 1)
            val tx1 = (floorDiv(x0 + width - 1, tileSize) shr shift).coerceIn(0, n - 1)
            val ty0 = (floorDiv(y0, tileSize) shr shift).coerceIn(0, n - 1)
            val ty1 = (floorDiv(y0 + height - 1, tileSize) shr shift).coerceIn(0, n - 1)
            for (ty in ty0..ty1) for (tx in tx0..tx1) if (tile(az, tx, ty) != null) return true
            az--
        }
        return false
    }

    /** Tiles resolved during one call, with a one-entry fast path (neighbouring pixels share a tile). */
    private inner class Lookup {
        private val seen = HashMap<Long, FloatArray?>()
        private var lastKey = -1L
        private var last: FloatArray? = null

        fun get(z: Int, x: Int, y: Int): FloatArray? {
            if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
            val k = key(z, x, y)
            if (k == lastKey) return last
            val t = if (seen.containsKey(k)) seen[k] else tile(z, x, y).also { seen[k] = it }
            lastKey = k
            last = t
            return t
        }
    }

    private companion object {
        fun floorDiv(a: Int, b: Int): Int = if (a >= 0) a / b else -((-a + b - 1) / b)
        fun floorMod(a: Int, b: Int): Int = a - floorDiv(a, b) * b
    }
}
