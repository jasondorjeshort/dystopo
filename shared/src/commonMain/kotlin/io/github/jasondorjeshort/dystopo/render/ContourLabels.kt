package io.github.jasondorjeshort.dystopo.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Ink size of a piece of text, in the same unit as the points (pixels). */
data class TextSize(val width: Double, val height: Double)

/** Platform hook for measuring text; the renderer implements it with its font engine. */
fun interface TextMeasurer {
    fun measure(text: String, fontPx: Double): TextSize
}

/**
 * A label placed on a contour: [text] centered at ([x], [y]), rotated by [angle] radians
 * (counter-clockwise positive in a y-down image, so the text runs along the line) and always within
 * (-90, 90] degrees so it reads upright. [width]x[height] is the unrotated ink box.
 */
data class ContourLabel(
    val text: String,
    val level: Double,
    val x: Double,
    val y: Double,
    val angle: Double,
    val width: Double,
    val height: Double,
    val fontPx: Double,
) {
    /** Corners of the rotated ink box, interleaved x,y. */
    fun corners(padding: Double = 0.0): DoubleArray = orientedBox(x, y, angle, width + 2 * padding, height + 2 * padding)
}

/** Tunables for [ContourLabels.place]. All distances in the unit of the points. */
data class LabelParams(
    /** Text height in pixels before [textScale]. */
    val baseFontPx: Double = 12.0,
    /** User text scale; defaults are generous for phone screens. */
    val textScale: Double = 1.4,
    /** Target distance between labels along one line. */
    val repeatDistance: Double = 320.0,
    /** Points of the line under a label may deviate from the chord by at most this many text heights. */
    val maxDeviationHeights: Double = 0.5,
    /** The label chord must be at least this fraction of the arc it spans (rejects hairpins). */
    val minStraightness: Double = 0.96,
    /** Gap kept between labels, and from the image edge. */
    val padding: Double = 4.0,
    /** Step along the line between candidate positions, in text heights. */
    val stepHeights: Double = 1.0,
) {
    val fontPx: Double get() = baseFontPx * textScale
}

/** Places elevation labels along index contours. */
object ContourLabels {
    /**
     * Picks label positions on [lines] that are index contours per [spec]. Each line gets about
     * one label per [LabelParams.repeatDistance] of length, placed on the straightest stretch of
     * that section that fits inside the [width]x[height] image and does not overlap an earlier
     * label. Longer lines are handled first so they win the collisions.
     */
    fun place(
        lines: List<ContourLine>,
        spec: ContourSpec,
        measurer: TextMeasurer,
        params: LabelParams,
        width: Int,
        height: Int,
    ): List<ContourLabel> {
        val fontPx = params.fontPx
        val placed = ArrayList<ContourLabel>()
        val boxes = ArrayList<DoubleArray>()
        val sizes = HashMap<String, TextSize>()
        val index = lines.filter { spec.isIndex(it.level) }.sortedByDescending { it.pointCount }
        for (line in index) {
            val text = spec.label(line.level)
            val size = sizes.getOrPut(text) { measurer.measure(text, fontPx) }
            for (label in placeOnLine(line, text, size, fontPx, params, width, height, boxes)) {
                placed += label
                boxes += label.corners(params.padding)
            }
        }
        return placed
    }

    private fun placeOnLine(
        line: ContourLine,
        text: String,
        size: TextSize,
        fontPx: Double,
        params: LabelParams,
        width: Int,
        height: Int,
        boxes: MutableList<DoubleArray>,
    ): List<ContourLabel> {
        val pts = line.points
        val n = line.pointCount
        if (n < 2) return emptyList()
        // Cumulative arc length. A closed ring also gets its closing segment.
        val segs = if (line.closed) n else n - 1
        val cum = DoubleArray(segs + 1)
        for (s in 0 until segs) {
            val t = (s + 1) % n
            cum[s + 1] = cum[s] + hypot(pts[2 * t] - pts[2 * s], pts[2 * t + 1] - pts[2 * s + 1])
        }
        val total = cum[segs]
        val labelLen = size.width + 2 * params.padding
        if (total < labelLen * 1.5) return emptyList()

        val result = ArrayList<ContourLabel>()
        val step = maxOf(1.0, size.height * params.stepHeights)
        val maxDev = size.height * params.maxDeviationHeights
        // Split the line into sections of about repeatDistance and pick the best candidate in each.
        val sections = maxOf(1, (total / params.repeatDistance).toInt())
        val sectionLen = total / sections
        for (sec in 0 until sections) {
            val from = sec * sectionLen
            val to = minOf(total - labelLen, (sec + 1) * sectionLen - labelLen)
            var best: ContourLabel? = null
            var bestScore = Double.POSITIVE_INFINITY
            var s = from
            while (s <= to) {
                val candidate = candidate(pts, n, cum, s, s + labelLen, text, line.level, size, fontPx, params, maxDev)
                if (candidate != null) {
                    val (label, score) = candidate
                    if (score < bestScore && insideImage(label, params.padding, width, height) && !collides(label, params.padding, boxes)) {
                        best = label
                        bestScore = score
                    }
                }
                s += step
            }
            if (best != null) {
                result += best
                boxes += best.corners(params.padding)
            }
        }
        return result
    }

    /**
     * Evaluates the stretch of the line between arc positions [s0] and [s1]. Returns the label and
     * a score (lower is straighter), or null when the stretch bends too much.
     */
    private fun candidate(
        pts: DoubleArray, n: Int, cum: DoubleArray, s0: Double, s1: Double,
        text: String, level: Double, size: TextSize, fontPx: Double, params: LabelParams, maxDev: Double,
    ): Pair<ContourLabel, Double>? {
        val i0 = segmentAt(cum, s0)
        val i1 = segmentAt(cum, s1)
        val ax = lerpX(pts, n, cum, i0, s0)
        val ay = lerpY(pts, n, cum, i0, s0)
        val bx = lerpX(pts, n, cum, i1, s1)
        val by = lerpY(pts, n, cum, i1, s1)
        val chord = hypot(bx - ax, by - ay)
        val arc = s1 - s0
        if (chord < arc * params.minStraightness) return null
        // Every vertex between the two ends must stay close to the chord.
        var worst = 0.0
        for (i in i0 + 1..i1) {
            val k = i % n
            val d = sqrt(ContourSimplify.segmentDistance2(pts[2 * k], pts[2 * k + 1], ax, ay, bx, by))
            if (d > worst) worst = d
            if (worst > maxDev) return null
        }
        var angle = atan2(by - ay, bx - ax)
        // Upright: never read right-to-left.
        if (angle > PI / 2) angle -= PI
        if (angle <= -PI / 2) angle += PI
        val label = ContourLabel(text, level, (ax + bx) / 2, (ay + by) / 2, angle, size.width, size.height, fontPx)
        // Straighter stretches score better; a small bias prefers near-horizontal text.
        val score = worst / maxDev + (1.0 - chord / arc) * 50 + abs(angle) / PI * 0.1
        return label to score
    }

    /** Index of the segment containing arc position [s]. */
    private fun segmentAt(cum: DoubleArray, s: Double): Int {
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun lerpX(pts: DoubleArray, n: Int, cum: DoubleArray, seg: Int, s: Double): Double {
        val t = frac(cum, seg, s)
        val a = seg % n
        val b = (seg + 1) % n
        return pts[2 * a] + (pts[2 * b] - pts[2 * a]) * t
    }

    private fun lerpY(pts: DoubleArray, n: Int, cum: DoubleArray, seg: Int, s: Double): Double {
        val t = frac(cum, seg, s)
        val a = seg % n
        val b = (seg + 1) % n
        return pts[2 * a + 1] + (pts[2 * b + 1] - pts[2 * a + 1]) * t
    }

    private fun frac(cum: DoubleArray, seg: Int, s: Double): Double {
        val len = cum[seg + 1] - cum[seg]
        return if (len <= 0) 0.0 else ((s - cum[seg]) / len).coerceIn(0.0, 1.0)
    }

    private fun insideImage(label: ContourLabel, padding: Double, width: Int, height: Int): Boolean {
        val c = label.corners(padding)
        for (i in 0 until 4) {
            if (c[2 * i] < 0 || c[2 * i] > width || c[2 * i + 1] < 0 || c[2 * i + 1] > height) return false
        }
        return true
    }

    private fun collides(label: ContourLabel, padding: Double, boxes: List<DoubleArray>): Boolean {
        val box = label.corners(padding)
        return boxes.any { boxesOverlap(box, it) }
    }

    /** Separating-axis test for two convex quads given as interleaved corners. */
    internal fun boxesOverlap(a: DoubleArray, b: DoubleArray): Boolean {
        // Cheap axis-aligned reject first.
        if (maxX(a) < minX(b) || maxX(b) < minX(a) || maxY(a) < minY(b) || maxY(b) < minY(a)) return false
        return !separated(a, b) && !separated(b, a)
    }

    private fun separated(edgesOf: DoubleArray, other: DoubleArray): Boolean {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            // Edge normal.
            val nx = edgesOf[2 * j + 1] - edgesOf[2 * i + 1]
            val ny = edgesOf[2 * i] - edgesOf[2 * j]
            var minA = Double.POSITIVE_INFINITY
            var maxA = Double.NEGATIVE_INFINITY
            var minB = Double.POSITIVE_INFINITY
            var maxB = Double.NEGATIVE_INFINITY
            for (k in 0 until 4) {
                val pa = edgesOf[2 * k] * nx + edgesOf[2 * k + 1] * ny
                val pb = other[2 * k] * nx + other[2 * k + 1] * ny
                if (pa < minA) minA = pa
                if (pa > maxA) maxA = pa
                if (pb < minB) minB = pb
                if (pb > maxB) maxB = pb
            }
            if (maxA < minB || maxB < minA) return true
        }
        return false
    }

    private fun minX(c: DoubleArray) = minOf(minOf(c[0], c[2]), minOf(c[4], c[6]))
    private fun maxX(c: DoubleArray) = maxOf(maxOf(c[0], c[2]), maxOf(c[4], c[6]))
    private fun minY(c: DoubleArray) = minOf(minOf(c[1], c[3]), minOf(c[5], c[7]))
    private fun maxY(c: DoubleArray) = maxOf(maxOf(c[1], c[3]), maxOf(c[5], c[7]))
}

/** Corners of a [w]x[h] box centered at ([cx],[cy]) rotated by [angle], interleaved x,y. */
internal fun orientedBox(cx: Double, cy: Double, angle: Double, w: Double, h: Double): DoubleArray {
    val c = cos(angle)
    val s = sin(angle)
    val hw = w / 2
    val hh = h / 2
    val out = DoubleArray(8)
    val xs = doubleArrayOf(-hw, hw, hw, -hw)
    val ys = doubleArrayOf(-hh, -hh, hh, hh)
    for (i in 0 until 4) {
        out[2 * i] = cx + xs[i] * c - ys[i] * s
        out[2 * i + 1] = cy + xs[i] * s + ys[i] * c
    }
    return out
}
