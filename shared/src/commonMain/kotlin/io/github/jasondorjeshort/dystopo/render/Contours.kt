package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One contour polyline. [points] holds interleaved x,y pairs in grid coordinates
 * (sample (i, j) is at x=i, y=j). For a closed line the last point connects back to the first.
 */
class ContourLine(val level: Double, val points: DoubleArray, val closed: Boolean) {
    val pointCount: Int get() = points.size / 2
}

/** Contour extraction with marching squares. */
object Contours {
    /** Multiples of [interval] strictly inside the elevation range [min]..[max]. */
    fun levels(min: Double, max: Double, interval: Double): List<Double> {
        require(interval > 0) { "interval must be positive" }
        val first = floor(min / interval).toLong() + 1
        val last = ceil(max / interval).toLong() - 1
        return (first..last).map { it * interval }
    }

    /**
     * Traces all [levels] in one pass over the grid. Returns lines grouped by level, in the
     * order of [levels]. A sample counts as "above" a level when it is >= the level.
     */
    fun trace(grid: ElevationGrid, levels: List<Double>): List<ContourLine> {
        if (levels.isEmpty() || grid.width < 2 || grid.height < 2) return emptyList()
        val sorted = levels.sorted()
        val tracers = sorted.map { LevelTracer(grid, it) }

        for (y in 0 until grid.height - 1) {
            for (x in 0 until grid.width - 1) {
                val tl = grid[x, y]
                val tr = grid[x + 1, y]
                val br = grid[x + 1, y + 1]
                val bl = grid[x, y + 1]
                val lo = minOf(minOf(tl, tr), minOf(br, bl)).toDouble()
                val hi = maxOf(maxOf(tl, tr), maxOf(br, bl)).toDouble()
                // A level crosses this cell when lo < level <= hi.
                var k = firstAbove(sorted, lo)
                while (k < sorted.size && sorted[k] <= hi) {
                    tracers[k].addCell(x, y, tl, tr, br, bl)
                    k++
                }
            }
        }
        return tracers.flatMap { it.lines() }
    }

    /** Index of the first level strictly greater than [value]. */
    private fun firstAbove(sorted: List<Double>, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Collects marching-squares segments for one level and joins them into polylines.
 * Segment endpoints are identified by the grid edge they lie on, so neighboring cells
 * share endpoints exactly and joining is a graph walk.
 */
private class LevelTracer(private val grid: ElevationGrid, private val level: Double) {
    private class Node(var a: Long = NONE, var b: Long = NONE)

    private val nodes = HashMap<Long, Node>()

    // Edge keys: horizontal edge from sample (x,y) to (x+1,y), or vertical edge from (x,y) to (x,y+1).
    private fun hEdge(x: Int, y: Int): Long = (y.toLong() * grid.width + x) * 2
    private fun vEdge(x: Int, y: Int): Long = (y.toLong() * grid.width + x) * 2 + 1

    fun addCell(x: Int, y: Int, tl: Float, tr: Float, br: Float, bl: Float) {
        val top = hEdge(x, y)
        val right = vEdge(x + 1, y)
        val bottom = hEdge(x, y + 1)
        val left = vEdge(x, y)
        val case = (if (tl >= level) 8 else 0) or (if (tr >= level) 4 else 0) or
            (if (br >= level) 2 else 0) or (if (bl >= level) 1 else 0)
        when (case) {
            1, 14 -> link(left, bottom)
            2, 13 -> link(bottom, right)
            3, 12 -> link(left, right)
            4, 11 -> link(top, right)
            6, 9 -> link(top, bottom)
            7, 8 -> link(left, top)
            5, 10 -> {
                // Saddle: use the cell-center average to decide which corners connect.
                val centerAbove = (tl + tr + br + bl) / 4.0 >= level
                if ((case == 5) == centerAbove) {
                    link(left, top); link(bottom, right)
                } else {
                    link(left, bottom); link(top, right)
                }
            }
        }
    }

    private fun link(p: Long, q: Long) {
        attach(p, q)
        attach(q, p)
    }

    private fun attach(from: Long, to: Long) {
        val node = nodes.getOrPut(from) { Node() }
        if (node.a == NONE) node.a = to else node.b = to
    }

    fun lines(): List<ContourLine> {
        val visited = HashSet<Long>()
        val result = ArrayList<ContourLine>()
        // Open lines start at an endpoint (one neighbor); whatever is left afterwards is closed loops.
        for ((key, node) in nodes) {
            if (node.b == NONE && key !in visited) result += walk(key, visited)
        }
        for (key in nodes.keys) {
            if (key !in visited) result += walk(key, visited)
        }
        return result
    }

    private fun walk(start: Long, visited: HashSet<Long>): ContourLine {
        val coords = ArrayList<Double>()
        var prev = NONE
        var cur = start
        var closed = false
        while (true) {
            visited += cur
            addPoint(cur, coords)
            val node = nodes.getValue(cur)
            val next = if (node.a != prev) node.a else node.b
            if (next == NONE) break
            if (next == start) {
                closed = true
                break
            }
            if (next in visited) break
            prev = cur
            cur = next
        }
        return ContourLine(level, coords.toDoubleArray(), closed)
    }

    private fun addPoint(edge: Long, out: MutableList<Double>) {
        val index = edge / 2
        val x = (index % grid.width).toInt()
        val y = (index / grid.width).toInt()
        val v0 = grid[x, y]
        if (edge % 2 == 0L) {
            val t = (level - v0) / (grid[x + 1, y] - v0)
            out += x + t
            out += y.toDouble()
        } else {
            val t = (level - v0) / (grid[x, y + 1] - v0)
            out += x.toDouble()
            out += y + t
        }
    }

    companion object {
        const val NONE = -1L
    }
}
