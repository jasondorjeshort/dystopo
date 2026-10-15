package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * T106 (a T95 follow-up): a route corridor's outline as polygons, everything within the buffer of the line, for the
 * Offline areas sheet. T75 drew the corridor as one translucent line as wide as the buffer; MapLibre extrudes a line
 * at each vertex, and where the width is large next to a segment's length (a 1 km band over a line simplified to a few
 * hundred metres) the inner side of each bend folded over, so the band showed overlapping, darker wedges. A polygon is
 * filled once, however the line turns.
 *
 * How: the line is projected to Web Mercator metres (what the map draws), the distance to it is sampled on a grid
 * around it (a fifth of the buffer per cell, at most [MAX_CELLS] cells a side), and the outline is the grid's
 * marching-squares contour at the buffer distance (in Mercator units the buffer grows as 1 / cos(latitude), so a long
 * north-south corridor stays as wide on the ground at both ends). Rings run with the inside on their left: outer ones
 * counter-clockwise, holes (a loop route's middle) clockwise, each hole under the outer ring that contains it.
 * Pure Kotlin, tested ([CorridorOutlineTest]).
 */
internal object CorridorOutline {
    data class LonLat(val lon: Double, val lat: Double)

    /** A polygon: its outer ring then its holes; each ring closed (the first point repeated last). */
    class Polygon(val outer: List<LonLat>, val holes: List<List<LonLat>>)

    private const val R = 6378137.0
    const val MAX_CELLS = 600

    private fun x(lon: Double) = lon * PI / 180 * R
    private fun y(lat: Double) = R * ln(tan(PI / 4 + lat.coerceIn(-85.0, 85.0) * PI / 360))
    private fun lon(x: Double) = x / R * 180 / PI
    private fun lat(y: Double) = (2 * atan(exp(y / R)) - PI / 2) * 180 / PI

    /** The outline of everything within [bufferM] of [line] (one point: a disc). Empty for no point or no buffer. */
    fun polygons(line: List<AreaPoint>, bufferM: Double, maxCells: Int = MAX_CELLS): List<Polygon> {
        if (line.isEmpty() || bufferM <= 0) return emptyList()
        val px = line.map { x(it.lon) }
        val py = line.map { y(it.lat) }
        val maxLat = line.maxOf { abs(it.lat) }.coerceAtMost(85.0)
        val minLat = line.minOf { abs(it.lat) }.coerceAtMost(85.0)
        val bufMax = bufferM / cos(maxLat * PI / 180)
        val bufMin = bufferM / cos(minLat * PI / 180)
        val spanX = px.max() - px.min() + 2 * bufMax
        val spanY = py.max() - py.min() + 2 * bufMax
        val cell = max(bufMin / 5, max(spanX, spanY) / (maxCells - 6))
        val pad = bufMax + 3 * cell
        val x0 = px.min() - pad
        val y0 = py.min() - pad
        val nx = ceil((px.max() + pad - x0) / cell).toInt() + 1
        val ny = ceil((py.max() + pad - y0) / cell).toInt() + 1
        // The buffer in Mercator units on each grid row (it grows with latitude).
        val rowBuf = DoubleArray(ny) { j -> bufferM / cos(lat(y0 + j * cell) * PI / 180) }
        val far = -1e30
        val v = DoubleArray(nx * ny) { far } // buffer minus the distance to the line: positive inside
        val segments = if (line.size == 1) listOf(0 to 0) else (0 until line.size - 1).map { it to it + 1 }
        for ((a, b) in segments) {
            val ax = px[a]; val ay = py[a]; val bx = px[b]; val by = py[b]
            val reach = max(rowBuf[((min(ay, by) - y0) / cell).toInt().coerceIn(0, ny - 1)], rowBuf[((max(ay, by) - y0) / cell).toInt().coerceIn(0, ny - 1)]) + 2 * cell
            val i0 = floor((min(ax, bx) - reach - x0) / cell).toInt().coerceIn(0, nx - 1)
            val i1 = ceil((max(ax, bx) + reach - x0) / cell).toInt().coerceIn(0, nx - 1)
            val j0 = floor((min(ay, by) - reach - y0) / cell).toInt().coerceIn(0, ny - 1)
            val j1 = ceil((max(ay, by) + reach - y0) / cell).toInt().coerceIn(0, ny - 1)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (j in j0..j1) {
                val gy = y0 + j * cell
                for (i in i0..i1) {
                    val gx = x0 + i * cell
                    val t = if (len2 == 0.0) 0.0 else (((gx - ax) * dx + (gy - ay) * dy) / len2).coerceIn(0.0, 1.0)
                    val ex = gx - (ax + t * dx)
                    val ey = gy - (ay + t * dy)
                    val d = rowBuf[j] - sqrt(ex * ex + ey * ey)
                    val k = j * nx + i
                    if (d > v[k]) v[k] = d
                }
            }
        }
        return assemble(trace(v, nx, ny), x0, y0, cell)
    }

    /**
     * Marching squares over [v] (row-major, [nx] by [ny], positive inside): the closed rings of the zero contour as
     * grid coordinates, each with the inside on its left.
     */
    private fun trace(v: DoubleArray, nx: Int, ny: Int): List<List<Pair<Double, Double>>> {
        fun at(i: Int, j: Int) = v[j * nx + i]
        // Edge ids: the horizontal edge from (i, j) to (i + 1, j) is 2 * (j * nx + i), the vertical one to (i, j + 1) that plus 1.
        fun h(i: Int, j: Int) = 2L * (j.toLong() * nx + i)
        fun vt(i: Int, j: Int) = 2L * (j.toLong() * nx + i) + 1
        val next = HashMap<Long, Long>() // a segment from its start edge to its end edge
        val point = HashMap<Long, Pair<Double, Double>>()
        fun cross(e: Long): Pair<Double, Double> = point.getOrPut(e) {
            val k = (e / 2).toInt()
            val i = k % nx
            val j = k / nx
            val a = v[k]
            val b = if (e % 2 == 0L) at(i + 1, j) else at(i, j + 1)
            val t = (a / (a - b)).coerceIn(0.0, 1.0)
            if (e % 2 == 0L) (i + t) to j.toDouble() else i.toDouble() to (j + t)
        }
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            // Corners counter-clockwise from the lower left; edge k joins corner k and corner k + 1.
            val n = (if (at(i, j) > 0) 1 else 0) + (if (at(i + 1, j) > 0) 1 else 0) + (if (at(i + 1, j + 1) > 0) 1 else 0) + (if (at(i, j + 1) > 0) 1 else 0)
            if (n == 0 || n == 4) continue // most cells: wholly outside or inside
            val c = doubleArrayOf(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
            val inside = BooleanArray(4) { c[it] > 0 }
            val edges = longArrayOf(h(i, j), vt(i + 1, j), h(i, j + 1), vt(i, j))
            val exits = (0 until 4).filter { inside[it] && !inside[(it + 1) % 4] }
            val entries = (0 until 4).filter { !inside[it] && inside[(it + 1) % 4] }
            if (exits.size == 1) {
                next[edges[exits[0]]] = edges[entries[0]]
            } else {
                // A saddle: the inside corners meet through the middle when its value (the corners' mean) is inside.
                val joined = c.average() > 0
                for (k in exits) next[edges[k]] = edges[if (joined) (k + 1) % 4 else (k + 3) % 4]
            }
        }
        val rings = ArrayList<List<Pair<Double, Double>>>()
        while (next.isNotEmpty()) {
            val start = next.keys.first()
            val ring = ArrayList<Pair<Double, Double>>()
            var e = start
            while (true) {
                ring += cross(e)
                e = next.remove(e) ?: break
                if (e == start) break
            }
            if (ring.size >= 3) rings += ring + ring.first()
        }
        return rings
    }

    /** Twice the signed area of a closed ring (positive counter-clockwise). */
    private fun area2(r: List<Pair<Double, Double>>): Double = (0 until r.size - 1).sumOf { r[it].first * r[it + 1].second - r[it + 1].first * r[it].second }

    private fun contains(r: List<Pair<Double, Double>>, p: Pair<Double, Double>): Boolean {
        var inside = false
        for (k in 0 until r.size - 1) {
            val (ax, ay) = r[k]
            val (bx, by) = r[k + 1]
            if ((ay > p.second) != (by > p.second) && p.first < ax + (p.second - ay) / (by - ay) * (bx - ax)) inside = !inside
        }
        return inside
    }

    private fun assemble(rings: List<List<Pair<Double, Double>>>, x0: Double, y0: Double, cell: Double): List<Polygon> {
        val outers = rings.filter { area2(it) > 0 }.sortedBy { area2(it) }
        val holes = rings.filter { area2(it) < 0 }
        val byOuter = outers.associateWith { ArrayList<List<Pair<Double, Double>>>() }
        for (hole in holes) outers.firstOrNull { contains(it, hole.first()) }?.let { byOuter.getValue(it) += hole }
        fun geo(r: List<Pair<Double, Double>>) = r.map { (gx, gy) -> LonLat(lon(x0 + gx * cell), lat(y0 + gy * cell)) }
        return outers.sortedByDescending { area2(it) }.map { o -> Polygon(geo(o), byOuter.getValue(o).map(::geo)) }
    }
}
