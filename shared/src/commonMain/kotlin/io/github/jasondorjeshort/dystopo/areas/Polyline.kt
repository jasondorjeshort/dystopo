package io.github.jasondorjeshort.dystopo.areas

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Line helpers for corridors: length, the piece between two distances along the line, simplification. */
object Polyline {
    private const val R = 6371008.8

    /** Great-circle distance in metres. */
    fun distanceM(a: AreaPoint, b: AreaPoint): Double {
        val p1 = a.lat * PI / 180
        val p2 = b.lat * PI / 180
        val dp = p2 - p1
        val dl = (b.lon - a.lon) * PI / 180
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Metres from the start to each point. */
    fun cumulative(points: List<AreaPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distanceM(points[i - 1], points[i])
        return out
    }

    fun lengthM(points: List<AreaPoint>): Double = if (points.size < 2) 0.0 else cumulative(points).last()

    /**
     * The part of the line from [fromM] to [toM] metres along it (clamped to the line), with interpolated end
     * points: what "the next segment ahead" means for prefetch. At least one point.
     */
    fun slice(points: List<AreaPoint>, fromM: Double, toM: Double, cum: DoubleArray = cumulative(points)): List<AreaPoint> {
        if (points.size < 2) return points.toList()
        val total = cum.last()
        val a = fromM.coerceIn(0.0, total)
        val b = toM.coerceIn(a, total)
        fun at(d: Double): AreaPoint {
            var i = 0
            while (i < cum.size - 2 && cum[i + 1] < d) i++
            val len = cum[i + 1] - cum[i]
            val f = if (len <= 0) 0.0 else ((d - cum[i]) / len).coerceIn(0.0, 1.0)
            val p = points[i]
            val q = points[i + 1]
            return AreaPoint(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)
        }
        val out = ArrayList<AreaPoint>()
        out += at(a)
        for (i in points.indices) if (cum[i] > a && cum[i] < b) out += points[i]
        if (b > a) out += at(b)
        return out
    }

    /**
     * Douglas-Peucker in a local metric frame: drops vertices closer than [toleranceM] to the simplified line. A
     * corridor built from the result with a buffer grown by [toleranceM] still holds the original line's corridor.
     */
    fun simplify(points: List<AreaPoint>, toleranceM: Double): List<AreaPoint> {
        if (points.size < 3 || toleranceM <= 0) return points.toList()
        val lat0 = points.sumOf { it.lat } / points.size
        val kx = AreaOfInterest.M_PER_DEG * cos(lat0 * PI / 180)
        val ky = AreaOfInterest.M_PER_DEG
        val xs = DoubleArray(points.size) { points[it].lon * kx }
        val ys = DoubleArray(points.size) { points[it].lat * ky }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var worst = -1.0
            var at = -1
            for (i in s + 1 until e) {
                val d = segmentDistance(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
                if (d > worst) { worst = d; at = i }
            }
            if (at >= 0 && worst > toleranceM) {
                keep[at] = true
                stack.addLast(s to at)
                stack.addLast(at to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance from (px, py) to the segment (ax, ay)-(bx, by), in the frame's units. */
    fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }
}
