package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/** Axis-aligned rectangle in image pixels. */
data class PixelRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    fun intersect(o: PixelRect) = PixelRect(maxOf(x0, o.x0), maxOf(y0, o.y0), minOf(x1, o.x1), minOf(y1, o.y1))
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    /** Half-open containment, so a point on the shared edge of two tiles belongs to exactly one of them. */
    fun containsHalfOpen(x: Double, y: Double) = x >= x0 && x < x1 && y >= y0 && y < y1
}

/**
 * Where one source vector tile lands in the output image. The image is a Web Mercator window at
 * [imageZoom] whose top-left pixel is global pixel ([imageX0], [imageY0]) (see `MercatorWindow`).
 * When the image zoom is above the tile's zoom (overzoom), one tile unit covers more pixels; below
 * it, fewer.
 */
class TilePlacement(
    val tile: TileId,
    val extent: Int,
    val imageZoom: Int,
    val imageX0: Int,
    val imageY0: Int,
    val tileSize: Int = 256,
) {
    /** Image pixels covered by the whole tile (without buffer). */
    val tilePixels: Double = tileSize * 2.0.pow(imageZoom - tile.z)

    /** Image pixels per tile unit. */
    val scale: Double = tilePixels / extent
    val offsetX: Double = tile.x * tilePixels - imageX0
    val offsetY: Double = tile.y * tilePixels - imageY0

    /** The tile's own square in image pixels; geometry in the tile buffer outside it is clipped away. */
    val rect: PixelRect get() = PixelRect(offsetX, offsetY, offsetX + tilePixels, offsetY + tilePixels)

    fun x(p: TilePoint): Double = offsetX + p.x * scale
    fun y(p: TilePoint): Double = offsetY + p.y * scale

    /** Points as interleaved image x,y. */
    fun transform(points: List<TilePoint>): DoubleArray {
        val out = DoubleArray(points.size * 2)
        for ((i, p) in points.withIndex()) {
            out[2 * i] = x(p)
            out[2 * i + 1] = y(p)
        }
        return out
    }

    companion object {
        /**
         * The tiles of zoom [tileZoom] covering an image window, i.e. the image's tiles at the
         * archive's zoom; for overzoom several image tiles share one source tile.
         */
        fun tilesFor(tileZoom: Int, imageZoom: Int, imageX0: Int, imageY0: Int, width: Int, height: Int, tileSize: Int = 256): List<TileId> {
            val tilePixels = tileSize * 2.0.pow(imageZoom - tileZoom)
            val max = (1 shl tileZoom) - 1
            val tx0 = floor(imageX0 / tilePixels).toInt().coerceIn(0, max)
            val tx1 = floor((imageX0 + width - 1) / tilePixels).toInt().coerceIn(0, max)
            val ty0 = floor(imageY0 / tilePixels).toInt().coerceIn(0, max)
            val ty1 = floor((imageY0 + height - 1) / tilePixels).toInt().coerceIn(0, max)
            return (ty0..ty1).flatMap { y -> (tx0..tx1).map { x -> TileId(tileZoom, x, y) } }
        }
    }
}

/** Clipping of polylines and rings to a rectangle; all coordinates interleaved x,y. */
object Clip {
    /**
     * Cuts a polyline to the parts inside [r] (Liang-Barsky per segment). Each run that stays inside
     * becomes one output line; crossings are interpolated exactly onto the rectangle's edge, so two
     * tiles that share an edge produce pieces that meet there.
     */
    fun polyline(pts: DoubleArray, r: PixelRect): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val n = pts.size / 2
        if (n < 2 || r.isEmpty) return out
        var run = ArrayList<Double>()
        fun flush() {
            if (run.size >= 4) out += run.toDoubleArray()
            run = ArrayList()
        }
        for (i in 0 until n - 1) {
            val ax = pts[2 * i]
            val ay = pts[2 * i + 1]
            val bx = pts[2 * i + 2]
            val by = pts[2 * i + 3]
            val t = clipSegment(ax, ay, bx, by, r)
            if (t == null) {
                flush()
                continue
            }
            val (t0, t1) = t
            val sx = ax + (bx - ax) * t0
            val sy = ay + (by - ay) * t0
            val ex = ax + (bx - ax) * t1
            val ey = ay + (by - ay) * t1
            if (t0 > 0.0) flush() // entered from outside: start a new run
            if (run.isEmpty()) {
                run += sx
                run += sy
            }
            run += ex
            run += ey
            if (t1 < 1.0) flush() // left the rectangle
        }
        flush()
        // Drop zero-length pieces (a segment touching the rectangle at a corner).
        return out.filter { line -> (1 until line.size / 2).any { line[2 * it] != line[0] || line[2 * it + 1] != line[1] } }
    }

    /** Parameter range [t0, t1] of segment a->b inside [r], or null if it misses. */
    private fun clipSegment(ax: Double, ay: Double, bx: Double, by: Double, r: PixelRect): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx - ax
        val dy = by - ay
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - r.x0, r.x1 - ax, ay - r.y0, r.y1 - ay)
        for (k in 0 until 4) {
            if (p[k] == 0.0) {
                if (q[k] < 0) return null
            } else {
                val t = q[k] / p[k]
                if (p[k] < 0) {
                    if (t > t1) return null
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return null
                    if (t < t1) t1 = t
                }
            }
        }
        return t0 to t1
    }

    /**
     * Clips a closed ring to [r] (Sutherland-Hodgman). The result keeps the ring's orientation and may
     * run along the rectangle's edges; fine for filling, not for stroking. Null if nothing is left.
     */
    fun ring(pts: DoubleArray, r: PixelRect): DoubleArray? {
        var cur = pts
        for (edge in 0 until 4) {
            val n = cur.size / 2
            if (n == 0) return null
            val next = ArrayList<Double>(cur.size + 8)
            for (i in 0 until n) {
                val j = (i + n - 1) % n
                val px = cur[2 * j]
                val py = cur[2 * j + 1]
                val cx = cur[2 * i]
                val cy = cur[2 * i + 1]
                val pIn = inside(px, py, edge, r)
                val cIn = inside(cx, cy, edge, r)
                if (cIn) {
                    if (!pIn) intersect(px, py, cx, cy, edge, r, next)
                    next += cx
                    next += cy
                } else if (pIn) {
                    intersect(px, py, cx, cy, edge, r, next)
                }
            }
            cur = next.toDoubleArray()
        }
        return if (cur.size >= 6) cur else null
    }

    private fun inside(x: Double, y: Double, edge: Int, r: PixelRect) = when (edge) {
        0 -> x >= r.x0
        1 -> x <= r.x1
        2 -> y >= r.y0
        else -> y <= r.y1
    }

    private fun intersect(ax: Double, ay: Double, bx: Double, by: Double, edge: Int, r: PixelRect, out: MutableList<Double>) {
        when (edge) {
            0, 1 -> {
                val x = if (edge == 0) r.x0 else r.x1
                val t = (x - ax) / (bx - ax)
                out += x
                out += ay + (by - ay) * t
            }
            else -> {
                val y = if (edge == 2) r.y0 else r.y1
                val t = (y - ay) / (by - ay)
                out += ax + (bx - ax) * t
                out += y
            }
        }
    }

    /** A closed ring as an open polyline that returns to its first point (for stroking outlines). */
    fun closeRing(ring: DoubleArray): DoubleArray = ring + doubleArrayOf(ring[0], ring[1])
}

/**
 * Joins polylines whose endpoints meet (within [tolerance] pixels), such as the pieces of one road
 * cut at tile edges, so dashes and labels run continuously across tile seams.
 */
object LineMerge {
    fun merge(lines: List<DoubleArray>, tolerance: Double = 0.01): List<DoubleArray> {
        if (lines.size < 2) return lines
        val cell = maxOf(tolerance, 1e-9)
        val ends = HashMap<Long, MutableList<Int>>() // cell key -> endpoint ids (2*line + 0 for start, +1 for end)
        fun key(x: Double, y: Double): Long = ((x / cell).roundToLong() shl 32) xor ((y / cell).roundToLong() and 0xFFFFFFFFL)
        fun ex(e: Int): Double = lines[e / 2].let { if (e % 2 == 0) it[0] else it[it.size - 2] }
        fun ey(e: Int): Double = lines[e / 2].let { if (e % 2 == 0) it[1] else it[it.size - 1] }
        for (i in lines.indices) for (side in 0..1) {
            val e = 2 * i + side
            ends.getOrPut(key(ex(e), ey(e))) { ArrayList() } += e
        }
        val used = BooleanArray(lines.size)

        /** An unused line with an endpoint at (x, y); returns that endpoint id. */
        fun partner(x: Double, y: Double): Int? {
            val kx = (x / cell).roundToLong()
            val ky = (y / cell).roundToLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val k = ((kx + dx) shl 32) xor ((ky + dy) and 0xFFFFFFFFL)
                for (e in ends[k] ?: continue) {
                    if (!used[e / 2] && abs(ex(e) - x) <= tolerance && abs(ey(e) - y) <= tolerance) return e
                }
            }
            return null
        }

        val out = ArrayList<DoubleArray>()
        for (i in lines.indices) {
            if (used[i]) continue
            used[i] = true
            val chain = ArrayDeque<Double>()
            lines[i].forEach { chain.addLast(it) }
            // Extend forward from the end.
            while (true) {
                val e = partner(chain[chain.size - 2], chain[chain.size - 1]) ?: break
                used[e / 2] = true
                val l = lines[e / 2]
                val m = l.size / 2
                // Skip the shared first point; walk the partner in the right direction.
                for (k in 1 until m) {
                    val idx = if (e % 2 == 0) k else m - 1 - k
                    chain.addLast(l[2 * idx])
                    chain.addLast(l[2 * idx + 1])
                }
            }
            // Extend backward from the start.
            while (true) {
                val e = partner(chain[0], chain[1]) ?: break
                used[e / 2] = true
                val l = lines[e / 2]
                val m = l.size / 2
                for (k in 1 until m) {
                    val idx = if (e % 2 == 1) m - 1 - k else k
                    chain.addFirst(l[2 * idx + 1])
                    chain.addFirst(l[2 * idx])
                }
            }
            out += chain.toDoubleArray()
        }
        return out
    }
}
