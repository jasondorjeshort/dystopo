package io.github.jasondorjeshort.dystopo.render

import kotlin.math.sqrt

/**
 * Polyline simplification and smoothing. Points are interleaved x,y pairs, in whatever unit the
 * caller draws in (pixels for the renderer); tolerances are in the same unit.
 */
object ContourSimplify {
    /**
     * Simplifies every line with [simplify]. Open lines that collapse below two points are dropped,
     * and so are closed rings that collapse below three: a two-point "ring" is a doubled-back
     * segment, not a contour, and would draw as a stray dash.
     */
    fun simplifyAll(lines: List<ContourLine>, tolerance: Double): List<ContourLine> =
        lines.mapNotNull { line ->
            val pts = simplify(line.points, line.closed, tolerance)
            val minPoints = if (line.closed) 3 else 2
            if (pts.size < 2 * minPoints) null else ContourLine(line.level, pts, line.closed)
        }

    /**
     * Douglas-Peucker: keeps the end points and, recursively, the point farthest from the chord
     * whenever it is more than [tolerance] away. Every original point ends up within [tolerance]
     * of the result. A closed line is treated as the ring from its first point back to itself, so
     * the first point is always kept and the result stays a valid ring.
     */
    fun simplify(points: DoubleArray, closed: Boolean, tolerance: Double): DoubleArray {
        require(tolerance >= 0) { "tolerance must be >= 0" }
        val n = points.size / 2
        if (n <= 2 || tolerance == 0.0) return points
        // For a ring, append the start point so the chord logic sees a path that returns home.
        val pts = if (closed) DoubleArray(points.size + 2).also {
            points.copyInto(it)
            it[points.size] = points[0]
            it[points.size + 1] = points[1]
        } else points
        val m = pts.size / 2
        val keep = BooleanArray(m)
        keep[0] = true
        keep[m - 1] = true
        val tol2 = tolerance * tolerance

        // Explicit stack instead of recursion: long contours would otherwise overflow it.
        val stack = ArrayList<Int>()
        stack += 0
        stack += m - 1
        while (stack.isNotEmpty()) {
            val last = stack.removeAt(stack.size - 1)
            val first = stack.removeAt(stack.size - 1)
            if (last - first < 2) continue
            var worst = -1
            var worstDist = tol2
            val ax = pts[2 * first]
            val ay = pts[2 * first + 1]
            val bx = pts[2 * last]
            val by = pts[2 * last + 1]
            for (i in first + 1 until last) {
                val d = segmentDistance2(pts[2 * i], pts[2 * i + 1], ax, ay, bx, by)
                if (d > worstDist) {
                    worstDist = d
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack += first
                stack += worst
                stack += worst
                stack += last
            }
        }

        val outCount = keep.count { it } - (if (closed) 1 else 0)
        val out = DoubleArray(outCount * 2)
        var k = 0
        val limit = if (closed) m - 1 else m
        for (i in 0 until limit) {
            if (keep[i]) {
                out[k++] = pts[2 * i]
                out[k++] = pts[2 * i + 1]
            }
        }
        return out
    }

    /**
     * Largest distance from any point of [original] to the polyline [simplified] (same closedness).
     * Used by tests to verify the Douglas-Peucker guarantee; O(n*m), not for production paths.
     */
    fun maxDeviation(original: DoubleArray, simplified: DoubleArray, closed: Boolean): Double {
        val m = simplified.size / 2
        if (m == 0) return Double.POSITIVE_INFINITY
        var worst = 0.0
        for (i in 0 until original.size / 2) {
            val px = original[2 * i]
            val py = original[2 * i + 1]
            var best = Double.POSITIVE_INFINITY
            val segments = if (closed) m else m - 1
            if (segments == 0) best = segmentDistance2(px, py, simplified[0], simplified[1], simplified[0], simplified[1])
            for (s in 0 until segments) {
                val t = (s + 1) % m
                val d = segmentDistance2(px, py, simplified[2 * s], simplified[2 * s + 1], simplified[2 * t], simplified[2 * t + 1])
                if (d < best) best = d
            }
            if (best > worst) worst = best
        }
        return sqrt(worst)
    }

    /** Smooths every line with [smooth]. */
    fun smoothAll(lines: List<ContourLine>, iterations: Int): List<ContourLine> =
        if (iterations <= 0) lines else lines.map { ContourLine(it.level, smooth(it.points, it.closed, iterations), it.closed) }

    /**
     * Chaikin corner cutting: each segment is replaced by the points at 1/4 and 3/4 along it, which
     * rounds every corner and roughly doubles the point count per iteration. The end points of an
     * open line are kept so lines still meet the grid edge.
     */
    fun smooth(points: DoubleArray, closed: Boolean, iterations: Int): DoubleArray {
        var pts = points
        repeat(iterations) { pts = chaikin(pts, closed) }
        return pts
    }

    private fun chaikin(pts: DoubleArray, closed: Boolean): DoubleArray {
        val n = pts.size / 2
        if (n < 3) return pts
        val out = ArrayList<Double>(pts.size * 2)
        val segments = if (closed) n else n - 1
        if (!closed) {
            out += pts[0]
            out += pts[1]
        }
        for (s in 0 until segments) {
            val t = (s + 1) % n
            val ax = pts[2 * s]
            val ay = pts[2 * s + 1]
            val bx = pts[2 * t]
            val by = pts[2 * t + 1]
            out += ax + (bx - ax) * 0.25
            out += ay + (by - ay) * 0.25
            out += ax + (bx - ax) * 0.75
            out += ay + (by - ay) * 0.75
        }
        if (!closed) {
            out += pts[2 * n - 2]
            out += pts[2 * n - 1]
        }
        return out.toDoubleArray()
    }

    /** Squared distance from (px,py) to segment (ax,ay)-(bx,by). */
    internal fun segmentDistance2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val qx = ax + t * dx - px
        val qy = ay + t * dy - py
        return qx * qx + qy * qy
    }
}
