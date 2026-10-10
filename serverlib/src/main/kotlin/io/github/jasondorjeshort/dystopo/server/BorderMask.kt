package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * Land on the far side of a national border, as polygon rings in degrees (T66): a national source whose
 * service reaches past the border (Kartverket's DTM continues about 10 km into Sweden, Finland and Russia
 * as coarser bare earth) masks those cells to no-data, so the global fallback takes over at the border.
 *
 * The rings come from Natural Earth's 1:10m admin-0 countries (public domain), clipped to [clip] and
 * written by `devtool borders`; they are tested with the even-odd rule over all rings together (the
 * countries do not overlap, and a country's holes are rings of its own). Natural Earth's borders are
 * accurate to a few hundred metres at that scale, well inside the band the service covers.
 *
 * @param rings each ring as interleaved longitude, latitude pairs (closed or not).
 */
class BorderMask(val name: String, val rings: List<DoubleArray>, val clip: DoubleArray?) {
    // Per ring: west, south, east, north, so most rings are skipped without looking at their edges.
    private val boxes: List<DoubleArray> = rings.map { r ->
        var w = Double.MAX_VALUE; var s = Double.MAX_VALUE; var e = -Double.MAX_VALUE; var n = -Double.MAX_VALUE
        for (k in r.indices step 2) {
            w = minOf(w, r[k]); e = maxOf(e, r[k]); s = minOf(s, r[k + 1]); n = maxOf(n, r[k + 1])
        }
        doubleArrayOf(w, s, e, n)
    }

    /** West, south, east, north of every ring together. */
    val bounds: DoubleArray = doubleArrayOf(
        boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] },
    )

    val vertices: Int get() = rings.sumOf { it.size / 2 }

    /** The point lies inside the mask (beyond the border). */
    fun contains(lon: Double, lat: Double): Boolean {
        var inside = false
        for ((i, r) in rings.withIndex()) {
            val b = boxes[i]
            if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) continue
            val n = r.size / 2
            var j = n - 1
            for (k in 0 until n) {
                val xk = r[2 * k]; val yk = r[2 * k + 1]
                val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yk > lat) != (yj > lat) && lon < xk + (lat - yk) * (xj - xk) / (yj - yk)) inside = !inside
                j = k
            }
        }
        return inside
    }

    /**
     * The mask over [window]'s pixel centres (row-major, north row first), or null when no ring comes near
     * the window. One scanline per row: a Mercator window's rows are parallels and its columns meridians.
     */
    fun rasterize(window: MercatorWindow): BooleanArray? {
        val w = window.width
        val h = window.height
        val west = window.lonAt(0)
        val east = window.lonAt(w - 1)
        val north = window.latAt(0)
        val south = window.latAt(h - 1)
        val near = rings.indices.filter { i -> boxes[i].let { it[2] >= west && it[0] <= east && it[3] >= south && it[1] <= north } }
        if (near.isEmpty()) return null
        val lons = DoubleArray(w) { window.lonAt(it) }
        val out = BooleanArray(w * h)
        val crossings = ArrayList<Double>()
        for (j in 0 until h) {
            val lat = window.latAt(j)
            crossings.clear()
            for (i in near) {
                val b = boxes[i]
                if (lat < b[1] || lat > b[3]) continue
                val r = rings[i]
                val n = r.size / 2
                var p = n - 1
                for (k in 0 until n) {
                    val yk = r[2 * k + 1]; val yp = r[2 * p + 1]
                    if ((yk > lat) != (yp > lat)) {
                        val xk = r[2 * k]; val xp = r[2 * p]
                        crossings += xk + (lat - yk) * (xp - xk) / (yp - yk)
                    }
                    p = k
                }
            }
            if (crossings.isEmpty()) continue
            crossings.sort()
            // Even-odd: pixels whose longitude lies between crossings 0-1, 2-3, ...
            var c = 0
            for (i in 0 until w) {
                while (c < crossings.size && crossings[c] <= lons[i]) c++
                if (c % 2 == 1) out[j * w + i] = true
            }
        }
        return out
    }

    companion object {
        /** Sweden, Finland and Russia near Norway (Natural Earth 1:10m admin-0), for [NorwaySource]. */
        val norwayNeighbours: BorderMask by lazy { resource("borders/norway-neighbours.json") }

        fun resource(path: String): BorderMask {
            val text = BorderMask::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
                ?: error("missing resource $path")
            return parse(text)
        }

        /** `{"name": ..., "clip": [w, s, e, n], "rings": [[lon, lat, lon, lat, ...], ...]}` (other keys ignored). */
        fun parse(text: String): BorderMask {
            val o = MiniJson.parseObject(text)
            val rings = (o["rings"] as? List<*>)?.map { r -> (r as List<*>).map { (it as Number).toDouble() }.toDoubleArray() }
                ?: error("border mask has no rings")
            require(rings.isNotEmpty() && rings.all { it.size >= 6 && it.size % 2 == 0 }) { "border mask rings need at least three lon, lat pairs" }
            val clip = (o["clip"] as? List<*>)?.map { (it as Number).toDouble() }?.toDoubleArray()
            return BorderMask(o["name"]?.toString() ?: "mask", rings, clip)
        }

        /**
         * Sutherland-Hodgman: [ring] (interleaved lon, lat) clipped to the box [w], [s], [e], [n]. The result
         * fills the same area inside the box under the even-odd rule (it may run along the box edge), or is
         * null when nothing is left.
         */
        fun clipRing(ring: DoubleArray, w: Double, s: Double, e: Double, n: Double): DoubleArray? {
            var pts = (ring.indices step 2).map { doubleArrayOf(ring[it], ring[it + 1]) }
            if (pts.size > 1 && pts.first().contentEquals(pts.last())) pts = pts.dropLast(1)
            fun clip(input: List<DoubleArray>, inside: (DoubleArray) -> Boolean, cut: (DoubleArray, DoubleArray) -> DoubleArray): List<DoubleArray> {
                if (input.isEmpty()) return input
                val out = ArrayList<DoubleArray>()
                var prev = input.last()
                for (cur in input) {
                    val ci = inside(cur)
                    val pi = inside(prev)
                    if (ci) {
                        if (!pi) out += cut(prev, cur)
                        out += cur
                    } else if (pi) {
                        out += cut(prev, cur)
                    }
                    prev = cur
                }
                return out
            }
            fun atX(x: Double) = { a: DoubleArray, b: DoubleArray -> doubleArrayOf(x, a[1] + (x - a[0]) * (b[1] - a[1]) / (b[0] - a[0])) }
            fun atY(y: Double) = { a: DoubleArray, b: DoubleArray -> doubleArrayOf(a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]), y) }
            pts = clip(pts, { it[0] >= w }, atX(w))
            pts = clip(pts, { it[0] <= e }, atX(e))
            pts = clip(pts, { it[1] >= s }, atY(s))
            pts = clip(pts, { it[1] <= n }, atY(n))
            if (pts.size < 3) return null
            return DoubleArray(pts.size * 2) { if (it % 2 == 0) pts[it / 2][0] else pts[it / 2][1] }
        }
    }
}
