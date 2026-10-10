package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One contour polyline. [points] holds interleaved x,y pairs in grid coordinates
 * (sample (i, j) is at x=i, y=j). For a closed line the last point connects back to the first.
 */
class ContourLine(val level: Double, val points: DoubleArray, val closed: Boolean) {
    val pointCount: Int get() = points.size / 2
}

/** Contour extraction with marching squares. */
object Contours {
    /**
     * Multiples of [interval] strictly inside the elevation range [min]..[max]. Empty for a grid with
     * no data, whose min/max are +/-infinity (without this the Long range overflowed to "everything").
     */
    fun levels(min: Double, max: Double, interval: Double): List<Double> {
        require(interval > 0) { "interval must be positive" }
        if (!min.isFinite() || !max.isFinite() || min > max) return emptyList()
        val first = floor(min / interval).toLong() + 1
        val last = ceil(max / interval).toLong() - 1
        return (first..last).map { it * interval }
    }

    /**
     * Traces all [levels]. Returns lines grouped by level in ascending level order; within a level,
     * open lines first, then closed rings, each in the grid scan order of its first crossing (so the
     * output is deterministic on every platform). A sample counts as "above" a level when it is >=
     * the level; a cell with a NaN corner draws nothing.
     *
     * One pass over the cells finds, per level, the cells it crosses (`lo < level <= hi`; cells whose
     * min/max exclude every level cost one comparison); then each level is linked and walked with
     * primitive arrays only. Line endpoints sit on cell edges and neighbouring cells share them
     * exactly, so joining needs no hashing: in scan order a cell's top and left edges were created by
     * the cell above and the cell to the left.
     */
    fun trace(grid: ElevationGrid, levels: List<Double>): List<ContourLine> {
        val w = grid.width
        val h = grid.height
        if (levels.isEmpty() || w < 2 || h < 2) return emptyList()
        val sorted = levels.sorted().toDoubleArray()
        val n = sorted.size
        val v = grid.values
        val cells = arrayOfNulls<IntList>(n)
        val minLevel = sorted[0]
        val maxLevel = sorted[n - 1]
        var hasNaN = false
        for (f in v) if (f.isNaN()) {
            hasNaN = true
            break
        }
        var k0 = 0
        for (y in 0 until h - 1) {
            val row = y * w
            // Min/max of the cell's left column pair, carried over from the previous cell's right one.
            var a = v[row]
            var b = v[row + w]
            var leftLo = if (a < b) a else b
            var leftHi = if (a < b) b else a
            for (x in 0 until w - 1) {
                a = v[row + x + 1]
                b = v[row + w + x + 1]
                val rightLo = if (a < b) a else b
                val rightHi = if (a < b) b else a
                val lo = (if (leftLo < rightLo) leftLo else rightLo).toDouble()
                val hi = (if (leftHi > rightHi) leftHi else rightHi).toDouble()
                leftLo = rightLo
                leftHi = rightHi
                // A cell entirely above or below every level; then cells with a NaN corner (no data).
                if (!(hi >= minLevel && lo < maxLevel)) continue
                val c = row + x
                if (hasNaN && (v[c].isNaN() || v[c + 1].isNaN() || v[c + w].isNaN() || v[c + w + 1].isNaN())) continue
                // First level strictly above lo; neighbouring cells usually share it.
                if (!((k0 == 0 || sorted[k0 - 1] <= lo) && sorted[k0] > lo)) k0 = firstAbove(sorted, lo)
                var k = k0
                while (k < n && sorted[k] <= hi) {
                    (cells[k] ?: IntList(64).also { cells[k] = it }).add(c)
                    k++
                }
            }
        }
        val out = ArrayList<ContourLine>()
        val linker = LevelLinker(grid)
        for (k in 0 until n) {
            val c = cells[k] ?: continue
            linker.link(sorted[k], c)
            linker.walk(out)
        }
        return out
    }

    /**
     * Cuts non-index contours where the ground is so steep that neighboring lines would be closer
     * than [minSpacing] grid cells (pixels, for a 1:1 render) and merge into a solid mass. The
     * spacing of adjacent lines is `interval / |gradient|`; index contours are always kept, so
     * cliffs still show their elevation. Pieces shorter than [minPieceLength] are dropped, and
     * steep stretches shorter than [minRun] points are ignored so noise does not perforate lines.
     * Returns the surviving lines (cut pieces are open lines) in the original order.
     */
    fun cutSteep(
        lines: List<ContourLine>,
        grid: ElevationGrid,
        spec: ContourSpec,
        minSpacing: Double,
        minPieceLength: Double = 12.0,
        minRun: Int = 4,
    ): List<ContourLine> {
        if (minSpacing <= 0) return lines
        // Steep where interval / |grad| < minSpacing, i.e. |grad| > interval / minSpacing (meters per cell).
        val gradLimit = spec.intervalMeters / minSpacing
        val gradLimit2 = gradLimit * gradLimit
        val out = ArrayList<ContourLine>(lines.size)
        for (line in lines) {
            if (spec.isIndex(line.level)) {
                out += line
                continue
            }
            val n = line.pointCount
            val steep = BooleanArray(n) { i -> gradient2At(grid, line.points[2 * i], line.points[2 * i + 1]) > gradLimit2 }
            closeShortRuns(steep, minRun)
            if (steep.none { it }) {
                out += line
                continue
            }
            // Rotate a ring so it starts on a steep point; the pieces then never wrap around.
            var pts = line.points
            if (line.closed) {
                val k = steep.indexOfFirst { it }
                pts = DoubleArray(pts.size)
                for (i in 0 until n) {
                    val j = (i + k) % n
                    pts[2 * i] = line.points[2 * j]
                    pts[2 * i + 1] = line.points[2 * j + 1]
                }
                val rotated = BooleanArray(n) { steep[(it + k) % n] }
                rotated.copyInto(steep)
            }
            var start = -1
            for (i in 0..n) {
                val keep = i < n && !steep[i]
                if (keep && start < 0) start = i
                if (!keep && start >= 0) {
                    emitPiece(pts, start, i, line.level, minPieceLength, out)
                    start = -1
                }
            }
        }
        return out
    }

    /**
     * Squared gradient magnitude (meters per cell) at a point: a Sobel (3x3-smoothed central
     * difference) around the nearest sample, so single noisy cells do not perforate lines. NaN counts as flat.
     */
    private fun gradient2At(grid: ElevationGrid, x: Double, y: Double): Double {
        val cx = (x + 0.5).toInt().coerceIn(0, grid.width - 1)
        val cy = (y + 0.5).toInt().coerceIn(0, grid.height - 1)
        var gx = 0.0
        var gy = 0.0
        val w = grid.width
        if (cx > 0 && cy > 0 && cx < w - 1 && cy < grid.height - 1) {
            // Interior: the same sums as below, without clamping.
            val v = grid.values
            val i = cy * w + cx
            for (d in -1..1) {
                val k = if (d == 0) 2.0 else 1.0
                gx += k * (v[i + d * w + 1] - v[i + d * w - 1])
                gy += k * (v[i + w + d] - v[i - w + d])
            }
            gx /= 8.0
            gy /= 8.0
            val g2 = gx * gx + gy * gy
            return if (g2.isNaN()) 0.0 else g2
        }
        for (d in -1..1) {
            val w = if (d == 0) 2.0 else 1.0
            gx += w * (grid.clamped(cx + 1, cy + d) - grid.clamped(cx - 1, cy + d))
            gy += w * (grid.clamped(cx + d, cy + 1) - grid.clamped(cx + d, cy - 1))
        }
        gx /= 8.0
        gy /= 8.0
        val g2 = gx * gx + gy * gy
        return if (g2.isNaN()) 0.0 else g2
    }

    /** Flips steep runs shorter than [minRun] back to not steep. */
    private fun closeShortRuns(steep: BooleanArray, minRun: Int) {
        var i = 0
        while (i < steep.size) {
            if (!steep[i]) {
                i++
                continue
            }
            var j = i
            while (j < steep.size && steep[j]) j++
            if (j - i < minRun) for (k in i until j) steep[k] = false
            i = j
        }
    }

    private fun emitPiece(pts: DoubleArray, from: Int, to: Int, level: Double, minLength: Double, out: MutableList<ContourLine>) {
        if (to - from < 2) return
        var length = 0.0
        for (i in from + 1 until to) {
            val dx = pts[2 * i] - pts[2 * i - 2]
            val dy = pts[2 * i + 1] - pts[2 * i - 1]
            length += sqrt(dx * dx + dy * dy)
        }
        if (length < minLength) return
        out += ContourLine(level, pts.copyOfRange(2 * from, 2 * to), closed = false)
    }

    /** Index of the first level strictly greater than [value]. */
    private fun firstAbove(sorted: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}


/** A growable IntArray. */
internal class IntList(capacity: Int = 16) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
}

/**
 * Links the marching-squares segments of one level into a graph whose nodes are the crossed cell
 * edges (each with at most two neighbours), then walks it into polylines. All storage is primitive
 * arrays that grow as needed and are reused from level to level.
 */
private class LevelLinker(private val grid: ElevationGrid) {
    private val w = grid.width
    private val v = grid.values
    private var level = 0.0
    private var count = 0
    private var nbA = IntArray(256)
    private var nbB = IntArray(256)
    private var px = DoubleArray(256)
    private var py = DoubleArray(256)
    private var visited = BooleanArray(256)
    private var coords = DoubleArray(512)

    // The node on the bottom edge of the cell above (by column), valid when belowCell[x] is that cell.
    private val belowCell = IntArray(w)
    private val belowNode = IntArray(w)

    /** Builds the graph of [level] from its crossed cells, given in scan order. */
    fun link(level: Double, cells: IntList) {
        this.level = level
        count = 0
        belowCell.fill(NO_CELL)
        var rightCell = NO_CELL
        var rightNode = -1
        val list = cells.data
        for (i in 0 until cells.size) {
            val c = list[i]
            val x = c % w
            val y = c / w
            val tl = v[c]
            val tr = v[c + 1]
            val bl = v[c + w]
            val br = v[c + w + 1]
            val aTl = tl >= level
            val aTr = tr >= level
            val aBr = br >= level
            val aBl = bl >= level
            val top = if (aTl == aTr) -1 else if (belowCell[x] == c - w) belowNode[x] else hNode(x, y, tl, tr)
            val left = if (aTl == aBl) -1 else if (rightCell == c - 1) rightNode else vNode(x, y, tl, bl)
            val right = if (aTr == aBr) -1 else vNode(x + 1, y, tr, br).also { rightCell = c; rightNode = it }
            val bottom = if (aBl == aBr) -1 else hNode(x, y + 1, bl, br).also { belowCell[x] = c; belowNode[x] = it }
            val case = (if (aTl) 8 else 0) or (if (aTr) 4 else 0) or (if (aBr) 2 else 0) or (if (aBl) 1 else 0)
            when (case) {
                1, 14 -> join(left, bottom)
                2, 13 -> join(bottom, right)
                3, 12 -> join(left, right)
                4, 11 -> join(top, right)
                6, 9 -> join(top, bottom)
                7, 8 -> join(left, top)
                5, 10 -> {
                    // Saddle: use the cell-center average to decide which corners connect.
                    val centerAbove = (tl + tr + br + bl) / 4.0 >= level
                    if ((case == 5) == centerAbove) {
                        join(left, top); join(bottom, right)
                    } else {
                        join(left, bottom); join(top, right)
                    }
                }
            }
        }
    }

    /** Walks the graph: open lines from their first-created end, then the remaining closed rings. */
    fun walk(out: MutableList<ContourLine>) {
        visited.fill(false, 0, count)
        for (i in 0 until count) if (!visited[i] && nbB[i] < 0) out += walkFrom(i)
        for (i in 0 until count) if (!visited[i]) out += walkFrom(i)
    }

    private fun walkFrom(start: Int): ContourLine {
        var len = 0
        var prev = -1
        var cur = start
        var closed = false
        while (true) {
            visited[cur] = true
            if (len + 2 > coords.size) coords = coords.copyOf(coords.size * 2)
            coords[len++] = px[cur]
            coords[len++] = py[cur]
            val a = nbA[cur]
            val next = if (a != prev) a else nbB[cur]
            if (next < 0) break
            if (next == start) {
                closed = true
                break
            }
            if (visited[next]) break
            prev = cur
            cur = next
        }
        return ContourLine(level, coords.copyOf(len), closed)
    }

    private fun join(p: Int, q: Int) {
        attach(p, q)
        attach(q, p)
    }

    private fun attach(from: Int, to: Int) {
        if (nbA[from] < 0) nbA[from] = to else nbB[from] = to
    }

    /** Node on the horizontal edge from sample (x, y) to (x+1, y), whose values are [v0] and [v1]. */
    private fun hNode(x: Int, y: Int, v0: Float, v1: Float): Int {
        val t = (level - v0) / (v1 - v0)
        return newNode(x + t, y.toDouble())
    }

    /** Node on the vertical edge from sample (x, y) to (x, y+1), whose values are [v0] and [v1]. */
    private fun vNode(x: Int, y: Int, v0: Float, v1: Float): Int {
        val t = (level - v0) / (v1 - v0)
        return newNode(x.toDouble(), y + t)
    }

    private fun newNode(x: Double, y: Double): Int {
        if (count == nbA.size) {
            val size = count * 2
            nbA = nbA.copyOf(size)
            nbB = nbB.copyOf(size)
            px = px.copyOf(size)
            py = py.copyOf(size)
            visited = visited.copyOf(size)
        }
        nbA[count] = -1
        nbB[count] = -1
        px[count] = x
        py[count] = y
        return count++
    }

    private companion object {
        /** Never c - 1 or c - w of a real cell c (cell 0 minus one is -1). */
        const val NO_CELL = Int.MIN_VALUE
    }
}
