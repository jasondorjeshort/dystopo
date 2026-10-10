package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.math.floor
import kotlin.time.TimeSource

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
 * An [ImageDecoder] that also decodes a Terrarium tile straight to elevations (metres, row-major,
 * [width] x [height]), skipping the ARGB array; [DemPyramid] uses that path when its decoder has it
 * (`png.TerrariumPngDecoder`).
 */
interface ElevationImageDecoder : ImageDecoder {
    fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray
}

/**
 * A Terrarium DEM tile pyramid (a region pack) read tile by tile through [fetch], with decoded
 * tiles kept in a small LRU cache. [window] samples any Web Mercator pixel window at any zoom:
 * pixels at a zoom the pack has are copied exactly; where the pack has no tile (beyond [maxZoom],
 * or past the edge of a deeper level), the nearest ancestor that has one is sampled bilinearly
 * (overzoom), so contour tiles can be generated for every zoom a viewer asks for and the tiles
 * around the edge of a deep level fade to a softer ancestor instead of stopping.
 *
 * Thread-safe: several contour tiles sample one pyramid concurrently. The cache is guarded by this
 * object's monitor, while fetching and decoding run outside it. T63: a tile being fetched and decoded
 * is in an in-flight map, so a second thread that misses the same tile waits for the first one's
 * result instead of decoding it again ([inFlightWaits] counts those; [duplicateDecodes] should stay 0).
 * The wait is the in-flight entry's own monitor (`@Synchronized`), so this stays pure Kotlin. [fetch]
 * and the decoder must be thread-safe (pack readers serialize their own reads).
 *
 * Below [minZoom], windows are box-averaged from [minZoom] (a contour tile of a low zoom samples a
 * coarse grid; see [ContourTiles.plan]).
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
    @Volatile
    var tilesDecoded: Long = 0
        private set

    /** Lookups that found a decoded tile, or a known-missing one, in the cache. */
    @Volatile
    var cacheHits: Long = 0
        private set

    /** Decodes thrown away because another thread decoded the same tile first (0 since T63's in-flight map). */
    @Volatile
    var duplicateDecodes: Long = 0
        private set

    /** T63: lookups that found the tile being fetched and decoded by another thread and waited for it. */
    @Volatile
    var inFlightWaits: Long = 0
        private set

    private var fetchNanos = 0L
    private var decodeNanos = 0L
    private var maxDecodeNanos = 0L

    // LinkedHashMap keeps insertion order; re-inserting on a hit makes that order least-recently-used first.
    private val cache = LinkedHashMap<Long, FloatArray>()
    private val missing = HashSet<Long>()
    private val inFlight = HashMap<Long, Load>()

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** Elevations of one tile (row-major, [tileSize] square), or null when the pack has no such tile. */
    fun tile(z: Int, x: Int, y: Int): FloatArray? {
        if (z < minZoom || z > maxZoom) return null
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val k = key(z, x, y)
        return when (val found = cachedOrClaim(k, z, x, y)) {
            is FloatArray -> if (found === MISSING) null else found
            is Load -> found.get()
            else -> null
        }
    }

    /**
     * One fetch and decode of a tile (T63). The first thread to call [get] loads it while holding this
     * object's monitor; a thread that found the same entry in [inFlight] blocks on that monitor and then
     * returns the result (or rethrows the failure) without decoding again.
     */
    private inner class Load(val k: Long, val z: Int, val x: Int, val y: Int) {
        private var done = false
        private var result: FloatArray? = null
        private var failure: Throwable? = null

        @Synchronized
        fun get(): FloatArray? {
            if (done) {
                failure?.let { throw it }
                return result
            }
            try {
                result = load()
                return result
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                done = true
                finished(this)
            }
        }

        // Fetch and decode outside the pyramid's lock, so other threads keep sampling cached tiles meanwhile.
        private fun load(): FloatArray? {
            val t0 = TimeSource.Monotonic.markNow()
            val bytes = fetch(z, x, y)
            if (bytes == null) {
                rememberMissing(k)
                return null
            }
            val t1 = TimeSource.Monotonic.markNow()
            val values = decodeElevations(bytes, z, x, y)
            val t2 = TimeSource.Monotonic.markNow()
            return store(k, values, (t1 - t0).inWholeNanoseconds, (t2 - t1).inWholeNanoseconds)
        }
    }

    /** The cached tile for [k], [MISSING], or the in-flight [Load] for it (made here when there is none). */
    @Synchronized
    private fun cachedOrClaim(k: Long, z: Int, x: Int, y: Int): Any {
        cached(k)?.let { return it }
        inFlight[k]?.let {
            inFlightWaits++
            return it
        }
        return Load(k, z, x, y).also { inFlight[k] = it }
    }

    /** Drops a finished load from [inFlight]; its result is in the cache (or [missing]) by then. */
    @Synchronized
    private fun finished(load: Load) {
        if (inFlight[load.k] === load) inFlight.remove(load.k)
    }

    /** Tiles being fetched and decoded right now. */
    val loadsInFlight: Int
        @Synchronized get() = inFlight.size

    /**
     * Elevations of one encoded tile: straight from the PNG when the decoder can ([ElevationImageDecoder]),
     * else through ARGB pixels.
     */
    private fun decodeElevations(bytes: ByteArray, z: Int, x: Int, y: Int): FloatArray {
        if (decoder is ElevationImageDecoder) return decoder.elevations(bytes, tileSize, tileSize)
        val img = decoder.decode(bytes)
        require(img.width == tileSize && img.height == tileSize) {
            "tile $z/$x/$y is ${img.width}x${img.height}, expected ${tileSize}x$tileSize"
        }
        return FloatArray(img.argb.size) { Terrarium.elevation(img.argb[it]) }
    }

    /** Total time spent fetching encoded tiles (pack reads) for [tilesDecoded], in milliseconds. */
    val fetchMs: Double @Synchronized get() = fetchNanos / 1e6

    /** Total time spent decoding tiles to elevations for [tilesDecoded], in milliseconds. */
    val decodeMs: Double @Synchronized get() = decodeNanos / 1e6

    /** The slowest single decode so far, in milliseconds. */
    val maxDecodeMs: Double @Synchronized get() = maxDecodeNanos / 1e6

    /** The cached tile for [k] (most recently used now), [MISSING] for a known-absent tile, or null. */
    @Synchronized
    private fun cached(k: Long): FloatArray? {
        cache.remove(k)?.let {
            cache[k] = it
            cacheHits++
            return it
        }
        if (k in missing) {
            cacheHits++
            return MISSING
        }
        return null
    }

    @Synchronized
    private fun rememberMissing(k: Long) {
        if (missing.size > 4096) missing.clear()
        missing += k
    }

    /** Caches [values] for [k] and returns them, or the copy another thread stored first. */
    @Synchronized
    private fun store(k: Long, values: FloatArray, fetchNs: Long, decodeNs: Long): FloatArray {
        tilesDecoded++
        fetchNanos += fetchNs
        decodeNanos += decodeNs
        if (decodeNs > maxDecodeNanos) maxDecodeNanos = decodeNs
        cache[k]?.let {
            duplicateDecodes++
            return it
        }
        cache[k] = values
        if (cache.size > cacheTiles) cache.remove(cache.keys.first())
        return values
    }

    /** Number of decoded tiles currently held. */
    val cachedTiles: Int
        @Synchronized get() = cache.size

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
     * Cells nobody covers are NaN. Below [minZoom], each cell is the mean of the 2^k x 2^k cells of
     * [minZoom] under it (NaN cells ignored), k = [minZoom] - [z].
     */
    fun window(z: Int, x0: Int, y0: Int, width: Int, height: Int): ElevationGrid {
        val values = FloatArray(width * height)
        if (!covers(z, x0, y0, width, height)) {
            values.fill(Float.NaN)
        } else if (z < minZoom) {
            downsample(z, x0, y0, width, height, values)
        } else {
            values.fill(Float.NaN)
            // One lookup table per window: each tile goes through the LRU cache once, not once per pixel.
            val lookup = Lookup()
            // The same answer as elevation() for every pixel, a level at a time: rows of the tiles this zoom
            // has are copied, then each ancestor zoom (deepest first) fills the cells still NaN bilinearly.
            if (z in minZoom..maxZoom) copyExact(z, x0, y0, width, height, values, lookup)
            var az = minOf(z - 1, maxZoom)
            while (az >= minZoom && values.any { it.isNaN() }) {
                fillFromAncestor(z, az, x0, y0, width, height, values, lookup)
                az--
            }
        }
        val worldPx = (tileSize.toLong() shl z).toDouble()
        val midLat = WebMercator.yToLat((y0 + height / 2.0) / tileSize, z).coerceIn(-85.0, 85.0)
        val cell = WebMercator.metersPerPixel(midLat, z, tileSize).takeIf { it > 0 && worldPx > 0 } ?: 1.0
        return ElevationGrid(width, height, cell, values)
    }

    /** Copies the window's pixels from the tiles zoom [z] has (absent tiles leave their cells untouched). */
    private fun copyExact(z: Int, x0: Int, y0: Int, width: Int, height: Int, values: FloatArray, lookup: Lookup) {
        val ts = tileSize
        val worldPx = ts.toLong() shl z
        val cx0 = maxOf(x0.toLong(), 0L).toInt()
        val cy0 = maxOf(y0.toLong(), 0L).toInt()
        val cx1 = minOf(x0.toLong() + width, worldPx).toInt() // exclusive
        val cy1 = minOf(y0.toLong() + height, worldPx).toInt()
        if (cx0 >= cx1 || cy0 >= cy1) return
        for (ty in floorDiv(cy0, ts)..floorDiv(cy1 - 1, ts)) {
            for (tx in floorDiv(cx0, ts)..floorDiv(cx1 - 1, ts)) {
                val t = lookup.get(z, tx, ty) ?: continue
                val px0 = maxOf(cx0, tx * ts)
                val px1 = minOf(cx1, (tx + 1) * ts)
                val py0 = maxOf(cy0, ty * ts)
                val py1 = minOf(cy1, (ty + 1) * ts)
                for (py in py0 until py1) {
                    val src = (py - ty * ts) * ts - tx * ts
                    t.copyInto(values, (py - y0) * width + (px0 - x0), src + px0, src + px1)
                }
            }
        }
    }

    /**
     * Fills the NaN cells of a window at zoom [z] by bilinear sampling of ancestor zoom [az], exactly as
     * [bilinear] does per pixel, from one copied block of [az] pixels; cells whose four samples are not
     * all present stay NaN.
     */
    private fun fillFromAncestor(z: Int, az: Int, x0: Int, y0: Int, width: Int, height: Int, values: FloatArray, lookup: Lookup) {
        val scale = (1L shl (z - az)).toDouble()
        val maxPx = ((tileSize.toLong() shl az) - 1).toDouble()
        val maxI = maxPx.toInt()
        // Per column and row: the left/top ancestor pixel, its neighbour, and the weight (as in bilinear()).
        fun axis(origin: Int, n: Int): Triple<IntArray, IntArray, FloatArray> {
            val lo = IntArray(n)
            val hi = IntArray(n)
            val f = FloatArray(n)
            for (i in 0 until n) {
                val c = ((origin + i + 0.5) / scale - 0.5).coerceIn(0.0, maxPx)
                val a = floor(c).toInt()
                lo[i] = a
                hi[i] = minOf(a + 1, maxI)
                f[i] = (c - a).toFloat()
            }
            return Triple(lo, hi, f)
        }
        val (xa, xb, fxs) = axis(x0, width)
        val (ya, yb, fys) = axis(y0, height)
        val bx0 = xa.min()
        val by0 = ya.min()
        val bw = xb.max() - bx0 + 1
        val bh = yb.max() - by0 + 1
        val block = FloatArray(bw * bh)
        block.fill(Float.NaN)
        copyExact(az, bx0, by0, bw, bh, block, lookup)
        for (j in 0 until height) {
            val ra = (ya[j] - by0) * bw - bx0
            val rb = (yb[j] - by0) * bw - bx0
            val fy = fys[j]
            for (i in 0 until width) {
                val idx = j * width + i
                if (!values[idx].isNaN()) continue
                val a = block[ra + xa[i]]
                val b = block[ra + xb[i]]
                val c = block[rb + xa[i]]
                val d = block[rb + xb[i]]
                if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) continue
                val fx = fxs[i]
                values[idx] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
        }
    }

    /** Box-averages the window at [z] < [minZoom] from the [minZoom] window under it. */
    private fun downsample(z: Int, x0: Int, y0: Int, width: Int, height: Int, values: FloatArray) {
        val k = minZoom - z
        val f = 1 shl k
        val fw = width shl k
        val fine = window(minZoom, x0 shl k, y0 shl k, fw, height shl k).values
        for (j in 0 until height) {
            for (i in 0 until width) {
                var sum = 0.0
                var n = 0
                for (dj in 0 until f) {
                    val row = (j * f + dj) * fw + i * f
                    for (di in 0 until f) {
                        val v = fine[row + di]
                        if (!v.isNaN()) {
                            sum += v
                            n++
                        }
                    }
                }
                values[j * width + i] = if (n == 0) Float.NaN else (sum / n).toFloat()
            }
        }
    }

    /**
     * True when some tile of the pack, at [z] or an ancestor zoom, overlaps the window: a cheap test
     * (a few tile lookups) that saves the per-pixel ancestor search for windows outside the pack.
     * Below [minZoom] the test runs on the [minZoom] window under it.
     */
    fun covers(z: Int, x0: Int, y0: Int, width: Int, height: Int): Boolean {
        if (z < minZoom) {
            val k = minZoom - z
            return covers(minZoom, x0 shl k, y0 shl k, width shl k, height shl k)
        }
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
        /** Cache marker for a tile the pack does not have. */
        val MISSING = FloatArray(0)

        fun floorDiv(a: Int, b: Int): Int = if (a >= 0) a / b else -((-a + b - 1) / b)
        fun floorMod(a: Int, b: Int): Int = a - floorDiv(a, b) * b
    }
}
