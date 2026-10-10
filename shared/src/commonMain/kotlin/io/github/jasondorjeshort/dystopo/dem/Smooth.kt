package io.github.jasondorjeshort.dystopo.dem

/**
 * Light smoothing of elevation grids. Meant for shading only (hillshade, slope angle): a DEM
 * resampled from a coarser or noisy source has small steps that the 3x3 gradient turns into
 * stripes, and a slope-band edge (say 30 degrees) flickers across them. Contours should stay on the
 * raw grid so their elevations are not pulled toward the neighbours.
 */
object Smooth {
    /** Largest radius [gaussian] accepts; beyond this the result is a blur, not a cleanup. */
    const val MAX_RADIUS = 3

    /**
     * Binomial weights for [radius]: row 2 * radius of Pascal's triangle, e.g. 1 2 1 for radius 1
     * and 1 4 6 4 1 for radius 2. These are the standard small-kernel approximation of a Gaussian
     * with sigma = sqrt(radius / 2) cells.
     */
    fun binomialWeights(radius: Int): DoubleArray {
        require(radius in 0..MAX_RADIUS) { "radius must be in 0..$MAX_RADIUS" }
        val n = 2 * radius
        val w = DoubleArray(n + 1)
        w[0] = 1.0
        for (k in 1..n) w[k] = w[k - 1] * (n - k + 1) / k
        return w
    }

    /**
     * Gaussian-like smoothing with a (2 * [radius] + 1)² binomial kernel, applied as two separable
     * passes. Radius 0 returns [grid] itself.
     *
     * No-data handling: cells that are NaN stay NaN, and they (like positions outside the grid)
     * are left out of their neighbours' weighted means, with the remaining weights renormalized.
     * So a hole or the grid edge never spreads NaN and never drags values toward zero. A plane
     * is preserved exactly away from holes and edges (the kernel is symmetric).
     */
    fun gaussian(grid: ElevationGrid, radius: Int): ElevationGrid {
        val weights = binomialWeights(radius)
        if (radius == 0) return grid
        val w = grid.width
        val h = grid.height
        val rows = pass(grid.values, w, h, weights, radius, dx = 1, dy = 0)
        val out = pass(rows, w, h, weights, radius, dx = 0, dy = 1)
        return ElevationGrid(w, h, grid.cellSizeMeters, out)
    }

    /** One 1-D pass along (dx, dy) with NaN-aware renormalization. */
    private fun pass(src: FloatArray, w: Int, h: Int, weights: DoubleArray, radius: Int, dx: Int, dy: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (src[i].isNaN()) {
                    out[i] = Float.NaN
                    continue
                }
                var sum = 0.0
                var total = 0.0
                for (k in -radius..radius) {
                    val xx = x + k * dx
                    val yy = y + k * dy
                    if (xx < 0 || xx >= w || yy < 0 || yy >= h) continue
                    val v = src[yy * w + xx]
                    if (v.isNaN()) continue
                    val wk = weights[k + radius]
                    sum += wk * v
                    total += wk
                }
                out[i] = (sum / total).toFloat()
            }
        }
        return out
    }
}
