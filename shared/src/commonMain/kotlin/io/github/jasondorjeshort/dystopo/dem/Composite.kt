package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Seamless merging of elevation grids that share one pixel grid. NaN means "no data".
 *
 * A higher-quality layer is laid over a fallback. Near the edges of the higher-quality
 * layer's coverage, the two are blended over a band of [featherCells] cells so there's no
 * visible step or kink in hillshade or contours where one source ends.
 */
object Composite {
    fun over(top: ElevationGrid, fallback: ElevationGrid, featherCells: Double): ElevationGrid {
        require(top.width == fallback.width && top.height == fallback.height) { "grids must share a pixel grid" }
        val dist = distanceToNoData(top)
        val out = FloatArray(top.values.size) { i ->
            val t = top.values[i]
            val f = fallback.values[i]
            when {
                t.isNaN() -> f
                f.isNaN() || featherCells <= 0 -> t
                else -> {
                    // Weight ramps from 0 at the coverage edge to 1 at featherCells inside it. The edge
                    // lies half a cell outside the outermost covered cell's centre (distance 1 to the
                    // first no-data cell), so the ramp starts at 0.5 (T58): that cell then gets
                    // smoothstep(0.5 / featherCells), a quarter of the old smoothstep(1 / featherCells)
                    // when the band is several cells wide, and the step to the fallback shrinks with it.
                    val w = ((dist[i] - 0.5) / featherCells).coerceIn(0.0, 1.0).toFloat()
                    // Smoothstep avoids a slope discontinuity at either end of the band.
                    val s = w * w * (3 - 2 * w)
                    f + (t - f) * s
                }
            }
        }
        return ElevationGrid(top.width, top.height, top.cellSizeMeters, out)
    }

    /** Fraction of cells with data. */
    fun coverage(grid: ElevationGrid): Double = grid.values.count { !it.isNaN() }.toDouble() / grid.values.size

    /**
     * Approximate Euclidean distance (in cells) from each data cell to the nearest no-data cell,
     * via a two-pass chamfer transform. Cells with data and no no-data anywhere get +infinity.
     * Data cells adjacent to no-data have distance 1; no-data cells have 0.
     */
    internal fun distanceToNoData(grid: ElevationGrid): DoubleArray {
        val w = grid.width
        val h = grid.height
        val inf = Double.POSITIVE_INFINITY
        val d = DoubleArray(w * h) { if (grid.values[it].isNaN()) 0.0 else inf }
        val diag = sqrt(2.0)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0.0) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1)
            if (y > 0) {
                v = min(v, d[i - w] + 1)
                if (x > 0) v = min(v, d[i - w - 1] + diag)
                if (x < w - 1) v = min(v, d[i - w + 1] + diag)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0.0) continue
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1)
                if (x < w - 1) v = min(v, d[i + w + 1] + diag)
                if (x > 0) v = min(v, d[i + w - 1] + diag)
            }
            d[i] = v
        }
        return d
    }
}
