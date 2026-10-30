package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.nextUp
import kotlin.math.sqrt

/**
 * T165: where water goes on an [ElevationGrid] of a whole area, built in one pass (a catchment crosses tile
 * edges, and no per-tile margin can promise the whole upslope area, so the grid is the area and tiles are cut
 * after; [Drainage] turns this into stream lines).
 *
 * Three steps, each from the published method (papers only; the GPL implementations are not read):
 * 1. **Pits:** Priority-Flood with epsilon (Barnes, Lehman and Mulla 2014, "Priority-Flood: An Optimal
 *    Depression-Filling and Watershed-Labeling Algorithm", algorithm 3). The edge cells and the cells next to
 *    no data seed a min-heap; cells are taken lowest first, and a neighbour no higher than the cell it was
 *    reached from is raised to one float step above it and goes on a plain queue. Every depression ends up
 *    draining to the edge, and flats get a tiny gradient toward their outlet, so no separate flat step is needed.
 * 2. **Direction:** D8 (O'Callaghan and Mark 1984): each cell drains to the neighbour with the steepest drop on
 *    the filled surface (diagonals divided by sqrt 2). On the filled surface every cell that was not a seed has
 *    a strictly lower neighbour, so the network has no cycles; a seed with no lower neighbour is an outlet.
 * 3. **Accumulation:** the contributing area of each cell (its own area plus everything draining into it),
 *    in an upstream-before-downstream order ([order]) that [Drainage] reuses for its own downstream passes.
 *
 * Memory is about 17 bytes a cell (filled surface, receiver, area, order and a flag), on top of the input grid.
 */
class FlowGrid private constructor(
    val width: Int,
    val height: Int,
    /** The pit-filled surface; NaN where the input had no data. */
    val filled: FloatArray,
    /** Index of the D8 downstream cell, or -1 for an outlet and for no data. */
    val receiver: IntArray,
    /** Contributing area in square metres, the cell's own area included; 0 where there is no data. */
    val areaM2: FloatArray,
    /**
     * True where the upslope area reaches a cell on the grid edge (or next to no data) that drains inward, so the
     * area beyond the grid may also drain here and [areaM2] is a lower bound.
     */
    val open: BooleanArray,
    /** Every cell with data, each after all the cells that drain into it. */
    val order: IntArray,
    /** Cells raised by the fill (depressions and flats). */
    val raisedCells: Int,
    /** The largest raise, metres. */
    val maxRaiseMeters: Double,
    /** Cells with data and no downstream cell. */
    val outlets: Int,
    /** Ground area of one cell per row, square metres. */
    val rowCellAreaM2: DoubleArray,
) {
    val size: Int get() = width * height

    /** Ground area of cell [index], square metres. */
    fun cellAreaM2(index: Int): Float = rowCellAreaM2[index / width].toFloat()

    companion object {
        /** Neighbour offsets in a fixed order (E, SE, S, SW, W, NW, N, NE), so results are the same everywhere. */
        internal val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        internal val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        private val INV_DIST = DoubleArray(8) { if (DX[it] != 0 && DY[it] != 0) 1.0 / sqrt(2.0) else 1.0 }

        /**
         * Fills, routes and accumulates [grid]. [rowCellAreaM2] gives the ground area of one cell per row (a Web
         * Mercator grid's cells shrink toward the poles); by default every cell is `cellSizeMeters` squared.
         */
        fun build(grid: ElevationGrid, rowCellAreaM2: DoubleArray? = null): FlowGrid {
            val w = grid.width
            val h = grid.height
            val n = w * h
            require(rowCellAreaM2 == null || rowCellAreaM2.size == h) { "rowCellAreaM2 needs one value per row ($h)" }
            val z = grid.values.copyOf()
            val closed = BooleanArray(n)
            val seed = BooleanArray(n)
            val heap = CellHeap()
            for (i in 0 until n) if (z[i].isNaN()) closed[i] = true
            // Seeds: cells with data on the edge or next to a no-data cell.
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (closed[i]) continue
                var edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                if (!edge) for (k in 0 until 8) if (z[i + DY[k] * w + DX[k]].isNaN()) {
                    edge = true
                    break
                }
                if (edge) {
                    seed[i] = true
                    closed[i] = true
                    heap.push(z[i], i)
                }
            }
            // Priority-Flood + epsilon. Each cell enters the pit queue at most once, so a flat array serves as it.
            val pit = IntArray(n)
            var pitHead = 0
            var pitTail = 0
            var raised = 0
            var maxRaise = 0.0
            while (pitHead < pitTail || heap.size > 0) {
                val c = if (pitHead < pitTail) pit[pitHead++] else heap.pop()
                val cx = c % w
                val cy = c / w
                val step = z[c].nextUp()
                for (k in 0 until 8) {
                    val nx = cx + DX[k]
                    val ny = cy + DY[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (closed[j]) continue
                    closed[j] = true
                    if (z[j] <= step) {
                        if (z[j] < step) {
                            raised++
                            val r = (step - z[j]).toDouble()
                            if (r > maxRaise) maxRaise = r
                            z[j] = step
                        }
                        pit[pitTail++] = j
                    } else {
                        heap.push(z[j], j)
                    }
                }
            }

            // D8 on the filled surface.
            val receiver = IntArray(n) { -1 }
            var outlets = 0
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val zi = z[i]
                if (zi.isNaN()) continue
                var best = -1
                var bestDrop = 0.0
                for (k in 0 until 8) {
                    val nx = x + DX[k]
                    val ny = y + DY[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    val zj = z[j]
                    if (zj.isNaN() || zj >= zi) continue
                    val drop = (zi - zj) * INV_DIST[k]
                    if (drop > bestDrop) {
                        bestDrop = drop
                        best = j
                    }
                }
                receiver[i] = best
                if (best < 0) outlets++
            }

            // Accumulation in topological order (Kahn): sources first, each cell after all its donors.
            val donors = IntArray(n)
            var dataCells = 0
            for (i in 0 until n) {
                if (z[i].isNaN()) continue
                dataCells++
                val r = receiver[i]
                if (r >= 0) donors[r]++
            }
            val order = IntArray(dataCells)
            var head = 0
            var tail = 0
            for (i in 0 until n) if (!z[i].isNaN() && donors[i] == 0) order[tail++] = i
            val area = FloatArray(n)
            val open = BooleanArray(n)
            val rowArea = rowCellAreaM2 ?: DoubleArray(h) { grid.cellSizeMeters * grid.cellSizeMeters }
            while (head < tail) {
                val c = order[head++]
                area[c] += rowArea[c / w].toFloat()
                val r = receiver[c]
                // A boundary cell that drains inward may be fed from beyond the grid.
                if (seed[c] && r >= 0) open[c] = true
                if (r < 0) continue
                area[r] += area[c]
                if (open[c]) open[r] = true
                if (--donors[r] == 0) order[tail++] = r
            }
            check(tail == dataCells) { "flow network has a cycle" }
            return FlowGrid(w, h, z, receiver, area, open, order, raised, maxRaise, outlets, rowArea)
        }
    }
}

/** A binary min-heap of cells keyed by elevation, in primitive arrays. */
private class CellHeap {
    private var keys = FloatArray(1024)
    private var ids = IntArray(1024)
    var size = 0
        private set

    fun push(key: Float, id: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            ids = ids.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) shr 1
            if (keys[p] <= key) break
            keys[i] = keys[p]
            ids[i] = ids[p]
            i = p
        }
        keys[i] = key
        ids[i] = id
    }

    fun pop(): Int {
        val top = ids[0]
        val lastKey = keys[--size]
        val lastId = ids[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && keys[c + 1] < keys[c]) c++
            if (keys[c] >= lastKey) break
            keys[i] = keys[c]
            ids[i] = ids[c]
            i = c
        }
        keys[i] = lastKey
        ids[i] = lastId
        return top
    }
}
