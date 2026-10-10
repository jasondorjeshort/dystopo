package io.github.jasondorjeshort.dystopo.style

import kotlin.math.hypot

/**
 * Tick hatching along lines (cliff symbols): short strokes perpendicular to the line on one side.
 * Coordinates are image pixels, x right and y down, interleaved x,y.
 */
object LineTicks {
    /**
     * Tick segments along [line], one every [spacing] pixels of arc length (the first half a spacing
     * in), each [length] pixels long, starting on the line and pointing to its right (seen along the
     * line's direction) when [right], else to its left. Each result is `x0, y0, x1, y1`.
     */
    fun ticks(line: DoubleArray, spacing: Double, length: Double, right: Boolean): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        if (spacing <= 0 || length <= 0) return out
        val sign = if (right) 1.0 else -1.0
        forEachStation(line, spacing) { x, y, ux, uy ->
            // Right of direction (ux, uy) with y down is (-uy, ux).
            out += doubleArrayOf(x, y, x - uy * length * sign, y + ux * length * sign)
        }
        return out
    }

    /**
     * Whether the ground falls away to the right of [line]: compares [elevation] at points [offset]
     * pixels to each side, every [step] pixels along it. True when the right side is lower by at least
     * [minDrop] meters on average, false when the left is, null when it cannot tell (flat, no data).
     */
    fun rightIsDownhill(
        line: DoubleArray,
        offset: Double,
        elevation: (Double, Double) -> Float,
        step: Double = 4.0,
        minDrop: Double = 1.0,
    ): Boolean? {
        var sum = 0.0
        var n = 0
        forEachStation(line, step) { x, y, ux, uy ->
            val right = elevation(x - uy * offset, y + ux * offset)
            val left = elevation(x + uy * offset, y - ux * offset)
            if (!right.isNaN() && !left.isNaN()) {
                sum += left - right
                n++
            }
        }
        if (n == 0) return null
        val mean = sum / n
        return when {
            mean >= minDrop -> true
            mean <= -minDrop -> false
            else -> null
        }
    }

    /** Calls [action] with the position and unit direction every [spacing] pixels along [line], from spacing/2. */
    private inline fun forEachStation(line: DoubleArray, spacing: Double, action: (Double, Double, Double, Double) -> Unit) {
        val n = line.size / 2
        var next = spacing / 2
        var s = 0.0
        for (i in 1 until n) {
            val ax = line[2 * i - 2]
            val ay = line[2 * i - 1]
            val dx = line[2 * i] - ax
            val dy = line[2 * i + 1] - ay
            val d = hypot(dx, dy)
            if (d <= 0) continue
            while (next <= s + d) {
                val t = (next - s) / d
                action(ax + dx * t, ay + dy * t, dx / d, dy / d)
                next += spacing
            }
            s += d
        }
    }
}
