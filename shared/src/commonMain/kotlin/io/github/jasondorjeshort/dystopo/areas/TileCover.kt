package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The XYZ tiles of one zoom [z] that an area touches. Stored as a hash set; written to JSON as rows
 * `[y, x0, x1]` (every x from x0 to x1 inclusive on row y), sorted, which keeps a corridor's list short.
 */
class TileSet(val z: Int) {
    private val set = HashSet<Long>()

    val size: Int get() = set.size

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    fun add(x: Int, y: Int) {
        set += key(x, y)
    }

    fun contains(x: Int, y: Int): Boolean = key(x, y) in set

    /** T140: every tile from [x0] to [x1] (inclusive) on row [y]; nothing when [x1] < [x0]. */
    fun addRow(y: Int, x0: Int, x1: Int) {
        for (x in x0..x1) set += key(x, y)
    }

    /** Every tile as (x, y), row by row. */
    fun tiles(): List<Pair<Int, Int>> = set.map { (it ushr 32).toInt() to (it and 0xffffffffL).toInt() }.sortedWith(compareBy({ it.second }, { it.first }))

    /** Rows `[y, x0, x1]`, sorted by y then x0. */
    fun rows(): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var row = -1
        var x0 = 0
        var x1 = -2
        for ((x, y) in tiles()) {
            if (y == row && x == x1 + 1) {
                x1 = x
                continue
            }
            if (x1 >= x0 && row >= 0) out += listOf(row, x0, x1)
            row = y
            x0 = x
            x1 = x
        }
        if (row >= 0) out += listOf(row, x0, x1)
        return out
    }

    /** This set grown by [n] tiles on every side (clamped to the world). */
    fun dilate(n: Int): TileSet {
        if (n <= 0) return this
        val max = (1 shl z) - 1
        val out = TileSet(z)
        for ((x, y) in tiles()) {
            for (dy in -n..n) for (dx in -n..n) {
                val tx = x + dx
                val ty = y + dy
                if (tx in 0..max && ty in 0..max) out.add(tx, ty)
            }
        }
        return out
    }

    /** The parents at zoom [z] - [levels] of every tile. */
    fun ancestors(levels: Int): TileSet {
        val out = TileSet(maxOf(0, z - levels))
        val s = z - out.z
        for ((x, y) in tiles()) out.add(x shr s, y shr s)
        return out
    }

    companion object {
        fun fromRows(z: Int, rows: List<List<Int>>): TileSet {
            val t = TileSet(z)
            for (r in rows) {
                require(r.size == 3 && r[1] <= r[2]) { "a tile row is [y, x0, x1]" }
                require(r[2] - r[1] < (1 shl z) + 1) { "row too long" }
                for (x in r[1]..r[2]) t.add(x, r[0])
            }
            return t
        }
    }
}

/**
 * Which tiles an [AreaOfInterest] touches at a zoom: every tile of a box, every tile of a polygon ([polygon], T140), or
 * every tile within the buffer of a corridor's line (a tile is in when the distance from the tile's square to some segment of the line is at most
 * the buffer, measured in that zoom's tile units with the segment's poleward latitude, so the cover is never
 * thinner than the buffer). [margin] grows the result by whole tiles: contour tiles sample a little past their
 * edges, so a margin of one keeps them complete offline.
 */
object TileCover {
    private const val EARTH_CIRCUMFERENCE_M = 2 * PI * 6378137.0

    fun of(area: AreaOfInterest, z: Int, margin: Int = 0): TileSet {
        val set = when (val s = area.shape) {
            is AreaShape.Box -> box(s.west, s.south, s.east, s.north, z)
            is AreaShape.Corridor -> corridor(s.points, s.bufferM, z)
            is AreaShape.Polygon -> polygon(s.rings, s.bufferM, z)
        }
        return set.dilate(margin)
    }

    /**
     * T140 (ADR 0041 §1): the tiles of a polygon at zoom [z]: a tile is in when its square is within [bufferM] of a ring
     * edge (with no buffer: when an edge touches it) or its centre is inside the rings by even-odd. Filled row by row:
     * each tile row's centre line crosses the edges at sorted x positions, and the tiles whose centres lie between the
     * first and second crossing, the third and fourth, and so on, are in; the edges' tiles are added per row of the
     * segment, so a long edge costs its own tiles, not its bounding box. Edges are straight in Web Mercator, as drawn.
     */
    fun polygon(rings: List<List<AreaPoint>>, bufferM: Double, z: Int): TileSet {
        val t = TileSet(z)
        val n = (1 shl z) - 1
        val xs = rings.map { r -> DoubleArray(r.size) { WebMercator.lonToX(r[it].lon, z) } }
        val ys = rings.map { r -> DoubleArray(r.size) { WebMercator.latToY(r[it].lat, z) } }
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (y in ys) for (v in y) { minY = min(minY, v); maxY = max(maxY, v) }
        // The inside: tile centres between crossings, row by row.
        val cross = ArrayList<Double>()
        for (ty in floor(minY).toInt().coerceIn(0, n)..floor(maxY).toInt().coerceIn(0, n)) {
            val yc = ty + 0.5
            cross.clear()
            for (k in rings.indices) {
                val rx = xs[k]
                val ry = ys[k]
                var j = rx.size - 1
                for (i in rx.indices) {
                    if ((ry[i] > yc) != (ry[j] > yc)) cross += rx[i] + (yc - ry[i]) / (ry[j] - ry[i]) * (rx[j] - rx[i])
                    j = i
                }
            }
            cross.sort()
            var c = 0
            while (c + 1 < cross.size) {
                val x0 = kotlin.math.ceil(cross[c] - 0.5).toInt().coerceAtLeast(0)
                val x1 = (kotlin.math.ceil(cross[c + 1] - 0.5).toInt() - 1).coerceAtMost(n)
                t.addRow(ty, x0, x1)
                c += 2
            }
        }
        // The edges, grown by the buffer.
        for (k in rings.indices) {
            val ring = rings[k]
            val rx = xs[k]
            val ry = ys[k]
            for (i in ring.indices) {
                val j = (i + 1) % ring.size
                val lat = max(abs(ring[i].lat), abs(ring[j].lat)).coerceAtMost(AreaOfInterest.MAX_LAT)
                val r = bufferM / (EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / (1 shl z))
                edgeTiles(t, rx[i], ry[i], rx[j], ry[j], r, n)
            }
        }
        return t
    }

    /** Adds the tiles within [r] (tile units) of the segment (ax, ay)-(bx, by), visiting only the rows and columns it reaches. */
    private fun edgeTiles(t: TileSet, ax: Double, ay: Double, bx: Double, by: Double, r: Double, n: Int) {
        val dy = by - ay
        for (ty in floor(min(ay, by) - r).toInt().coerceIn(0, n)..floor(max(ay, by) + r).toInt().coerceIn(0, n)) {
            // The part of the segment whose y lies within the row grown by r, and its x span.
            val lo = ty - r
            val hi = ty + 1 + r
            val (sx0, sx1) = if (dy == 0.0) {
                min(ax, bx) to max(ax, bx)
            } else {
                val t0 = ((lo - ay) / dy).coerceIn(0.0, 1.0)
                val t1 = ((hi - ay) / dy).coerceIn(0.0, 1.0)
                val xa = ax + (bx - ax) * t0
                val xb = ax + (bx - ax) * t1
                min(xa, xb) to max(xa, xb)
            }
            for (tx in floor(sx0 - r).toInt().coerceIn(0, n)..floor(sx1 + r).toInt().coerceIn(0, n)) {
                if (!t.contains(tx, ty) && rectSegmentDistance(tx.toDouble(), ty.toDouble(), tx + 1.0, ty + 1.0, ax, ay, bx, by) <= r) t.add(tx, ty)
            }
        }
    }

    /** One [TileSet] per zoom in [zooms]. */
    fun byZoom(area: AreaOfInterest, zooms: IntRange, margin: Int = 0): Map<Int, TileSet> = zooms.associateWith { of(area, it, margin) }

    fun box(west: Double, south: Double, east: Double, north: Double, z: Int): TileSet {
        val n = (1 shl z) - 1
        val x0 = floor(WebMercator.lonToX(west, z)).toInt().coerceIn(0, n)
        val x1 = floor(WebMercator.lonToX(east, z)).toInt().coerceIn(0, n)
        val y0 = floor(WebMercator.latToY(north, z)).toInt().coerceIn(0, n)
        val y1 = floor(WebMercator.latToY(south, z)).toInt().coerceIn(0, n)
        val t = TileSet(z)
        for (y in y0..y1) for (x in x0..x1) t.add(x, y)
        return t
    }

    fun corridor(points: List<AreaPoint>, bufferM: Double, z: Int): TileSet {
        val t = TileSet(z)
        val n = (1 shl z) - 1
        val segs = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
        for ((a, b) in segs) {
            val ax = WebMercator.lonToX(a.lon, z)
            val ay = WebMercator.latToY(a.lat, z)
            val bx = WebMercator.lonToX(b.lon, z)
            val by = WebMercator.latToY(b.lat, z)
            // Tiles are smallest in metres at the poleward end: use that latitude for the buffer in tile units.
            val lat = max(abs(a.lat), abs(b.lat)).coerceAtMost(AreaOfInterest.MAX_LAT)
            val metersPerTile = EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / (1 shl z)
            val r = bufferM / metersPerTile
            val tx0 = floor(min(ax, bx) - r).toInt().coerceIn(0, n)
            val tx1 = floor(max(ax, bx) + r).toInt().coerceIn(0, n)
            val ty0 = floor(min(ay, by) - r).toInt().coerceIn(0, n)
            val ty1 = floor(max(ay, by) + r).toInt().coerceIn(0, n)
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                if (!t.contains(tx, ty) && rectSegmentDistance(tx.toDouble(), ty.toDouble(), tx + 1.0, ty + 1.0, ax, ay, bx, by) <= r) t.add(tx, ty)
            }
        }
        return t
    }

    /** Distance from the rectangle [x0, x1] x [y0, y1] to the segment (ax, ay)-(bx, by); 0 when they meet. */
    fun rectSegmentDistance(x0: Double, y0: Double, x1: Double, y1: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        if (segmentHitsRect(x0, y0, x1, y1, ax, ay, bx, by)) return 0.0
        fun pointRect(px: Double, py: Double): Double {
            val dx = max(max(x0 - px, 0.0), px - x1)
            val dy = max(max(y0 - py, 0.0), py - y1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        var d = min(pointRect(ax, ay), pointRect(bx, by))
        for ((cx, cy) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) d = min(d, Polyline.segmentDistance(cx, cy, ax, ay, bx, by))
        return d
    }

    /** Liang-Barsky: does the segment cross (or lie in) the rectangle? */
    private fun segmentHitsRect(x0: Double, y0: Double, x1: Double, y1: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val dx = bx - ax
        val dy = by - ay
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - x0, x1 - ax, ay - y0, y1 - ay)
        for (i in 0 until 4) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
            }
        }
        return true
    }
}
