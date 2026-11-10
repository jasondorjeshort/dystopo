package io.github.jasondorjeshort.dystopo.mvt

import kotlin.math.roundToInt

/**
 * T254: cuts a deeper tile out of an ancestor vector tile, so a tile the phone lacks is drawn from the nearest wider
 * zoom it has (MapLibre Native paints a 404 as an empty tile and never falls back to the parent). The child sits
 * [dz] levels below the ancestor at offset ([dx], [dy]) in child tiles (`0 until 2^dz` each: the child's x minus the
 * ancestor's x shifted left by [dz]).
 *
 * Every point is moved into the child's coordinates exactly (`p * 2^dz - offset * extent`, integers), then clipped to
 * the child's extent plus [buffer] units on each side, the buffer the tile generators leave so lines and fills join
 * across tile edges: points outside are dropped, lines are cut at the box (one line may become several), polygon rings
 * are clipped with Sutherland-Hodgman against the box (a convex window, so a ring's winding is kept); a crossing point
 * is rounded to the nearest unit. Rings and lines left with too few points or no area are dropped, a polygon whose
 * exterior went goes with its holes. Features keep their ids and properties, layers their name, extent and version;
 * every layer is kept, empty where none of its features reach the child. Unknown geometry is dropped.
 *
 * The geometry is the ancestor's, so it is as coarse as the ancestor's zoom: one ancestor unit is `2^dz` child units.
 * Pure, tested (`MvtOverzoomTest`).
 */
object MvtOverzoom {
    /** Buffer in child extent units for a 4096 extent (Planetiler's 4 px of 256); scaled for other extents. */
    const val DEFAULT_BUFFER_4096 = 64

    /** [cut] on encoded bytes: decodes, cuts and encodes again. */
    fun cut(bytes: ByteArray, dz: Int, dx: Int, dy: Int): ByteArray = MvtEncoder.encode(cut(MvtDecoder.decode(bytes), dz, dx, dy))

    fun cut(tile: VectorTile, dz: Int, dx: Int, dy: Int, buffer: Int? = null): VectorTile {
        require(dz in 0..20) { "dz $dz out of range" }
        val n = 1 shl dz
        require(dx in 0 until n && dy in 0 until n) { "child offset $dx,$dy outside 0 until $n" }
        if (dz == 0) return tile
        return VectorTile(tile.layers.map { cutLayer(it, dz, dx, dy, buffer ?: (DEFAULT_BUFFER_4096.toLong() * it.extent / 4096).toInt()) })
    }

    private fun cutLayer(layer: Layer, dz: Int, dx: Int, dy: Int, buffer: Int): Layer {
        val e = layer.extent.toLong()
        val scale = 1L shl dz
        val ox = dx * e
        val oy = dy * e
        val lo = -buffer.toLong()
        val hi = e + buffer
        // The child's box in ancestor units (rounded outward), to skip features that cannot reach it before any work.
        val aLo = (ox + lo).floorDiv(scale)
        val aHi = (ox + hi + scale - 1).floorDiv(scale)
        val bLo = (oy + lo).floorDiv(scale)
        val bHi = (oy + hi + scale - 1).floorDiv(scale)
        val box = Box(lo.toDouble(), lo.toDouble(), hi.toDouble(), hi.toDouble())
        fun tx(p: TilePoint) = p.x * scale - ox
        fun ty(p: TilePoint) = p.y * scale - oy
        val out = ArrayList<Feature>()
        for (f in layer.features) {
            if (!reaches(f.geometry, aLo, bLo, aHi, bHi)) continue
            val g: Geometry? = when (val geom = f.geometry) {
                is Geometry.Points -> geom.points
                    .mapNotNull { p -> val x = tx(p); val y = ty(p); if (x in lo..hi && y in lo..hi) TilePoint(x.toInt(), y.toInt()) else null }
                    .takeIf { it.isNotEmpty() }?.let { Geometry.Points(it) }
                is Geometry.Lines -> geom.lines
                    .flatMap { line -> clipLine(line.map { doubleArrayOf(tx(it).toDouble(), ty(it).toDouble()) }, box) }
                    .takeIf { it.isNotEmpty() }?.let { Geometry.Lines(it) }
                is Geometry.Polygons -> geom.polygons.mapNotNull { poly ->
                    val ext = clipRing(poly.exterior.map { doubleArrayOf(tx(it).toDouble(), ty(it).toDouble()) }, box) ?: return@mapNotNull null
                    Polygon(ext, poly.interiors.mapNotNull { h -> clipRing(h.map { doubleArrayOf(tx(it).toDouble(), ty(it).toDouble()) }, box) })
                }.takeIf { it.isNotEmpty() }?.let { Geometry.Polygons(it) }
                is Geometry.Unknown -> null
            }
            if (g != null) out += Feature(f.id, f.properties, g)
        }
        return Layer(layer.name, out, layer.extent, layer.version)
    }

    private class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /** Whether any vertex box of [g] (ancestor units) overlaps the child's box: a cheap test before transforming. */
    private fun reaches(g: Geometry, x0: Long, y0: Long, x1: Long, y1: Long): Boolean {
        fun bbox(pts: List<TilePoint>): Boolean {
            if (pts.isEmpty()) return false
            var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
            for (p in pts) {
                if (p.x < a) a = p.x
                if (p.y < b) b = p.y
                if (p.x > c) c = p.x
                if (p.y > d) d = p.y
            }
            return c >= x0 && a <= x1 && d >= y0 && b <= y1
        }
        return when (g) {
            is Geometry.Points -> bbox(g.points)
            is Geometry.Lines -> g.lines.any(::bbox)
            is Geometry.Polygons -> g.polygons.any { bbox(it.exterior) }
            is Geometry.Unknown -> false
        }
    }

    private fun pt(x: Double, y: Double) = TilePoint(x.roundToInt(), y.roundToInt())

    /**
     * Cuts a line at [box] (Liang-Barsky per segment): the parts inside, each of at least two distinct points.
     * Consecutive duplicate points (from rounding) are dropped.
     */
    private fun clipLine(line: List<DoubleArray>, box: Box): List<List<TilePoint>> {
        val parts = ArrayList<List<TilePoint>>()
        var cur = ArrayList<TilePoint>()
        fun add(p: TilePoint) { if (cur.isEmpty() || cur.last() != p) cur.add(p) }
        fun flush() {
            if (cur.size >= 2) parts += cur
            cur = ArrayList()
        }
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val seg = clipSegment(a[0], a[1], b[0], b[1], box)
            if (seg == null) {
                flush()
                continue
            }
            val (t0, t1) = seg
            val ddx = b[0] - a[0]
            val ddy = b[1] - a[1]
            if (t0 > 0.0) flush() // entered the box: a new part starts
            add(pt(a[0] + t0 * ddx, a[1] + t0 * ddy))
            add(pt(a[0] + t1 * ddx, a[1] + t1 * ddy))
            if (t1 < 1.0) flush() // left the box
        }
        flush()
        return parts
    }

    /** The parameter range [t0, t1] of segment a-b inside [box], or null when it misses the box. */
    private fun clipSegment(ax: Double, ay: Double, bx: Double, by: Double, box: Box): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx - ax
        val dy = by - ay
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - box.x0, box.x1 - ax, ay - box.y0, box.y1 - ay)
        for (i in 0 until 4) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        return t0 to t1
    }

    /** Sutherland-Hodgman of one ring against [box]; null when fewer than three distinct points or no area remain. */
    private fun clipRing(ring: List<DoubleArray>, box: Box): List<TilePoint>? {
        var pts: List<DoubleArray> = ring
        for (edge in 0 until 4) {
            if (pts.isEmpty()) return null
            val outPts = ArrayList<DoubleArray>(pts.size + 4)
            fun inside(p: DoubleArray) = when (edge) {
                0 -> p[0] >= box.x0
                1 -> p[0] <= box.x1
                2 -> p[1] >= box.y0
                else -> p[1] <= box.y1
            }
            fun cross(a: DoubleArray, b: DoubleArray): DoubleArray {
                val t = when (edge) {
                    0 -> (box.x0 - a[0]) / (b[0] - a[0])
                    1 -> (box.x1 - a[0]) / (b[0] - a[0])
                    2 -> (box.y0 - a[1]) / (b[1] - a[1])
                    else -> (box.y1 - a[1]) / (b[1] - a[1])
                }
                return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
            }
            var prev = pts.last()
            for (cur in pts) {
                val ci = inside(cur)
                val pi = inside(prev)
                if (ci) {
                    if (!pi) outPts += cross(prev, cur)
                    outPts += cur
                } else if (pi) {
                    outPts += cross(prev, cur)
                }
                prev = cur
            }
            pts = outPts
        }
        val out = ArrayList<TilePoint>(pts.size)
        for (p in pts) {
            val q = pt(p[0], p[1])
            if (out.isEmpty() || out.last() != q) out += q
        }
        while (out.size > 1 && out.first() == out.last()) out.removeAt(out.size - 1)
        if (out.size < 3 || signedDoubleArea(out) == 0L) return null
        return out
    }
}

