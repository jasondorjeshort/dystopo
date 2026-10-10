package io.github.jasondorjeshort.dystopo.style

import kotlin.math.ceil
import kotlin.math.floor
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
    fun ticks(line: DoubleArray, spacing: Double, length: Double, right: Boolean): List<DoubleArray> =
        ticks(line, spacing, length) { right }

    /** As [ticks], with the side chosen per segment: [rightOf] gets the index of the segment (from point i to i + 1). */
    fun ticks(line: DoubleArray, spacing: Double, length: Double, rightOf: (Int) -> Boolean): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        if (spacing <= 0 || length <= 0) return out
        forEachStation(line, spacing) { seg, x, y, ux, uy ->
            val sign = if (rightOf(seg)) 1.0 else -1.0
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
        val drop = Drop()
        forEachStation(line, step) { _, x, y, ux, uy -> drop.add(x, y, ux, uy, offset, elevation) }
        return drop.decide(minDrop)
    }

    /** Ticks on the downhill side, decided per segment; see [downhillTicks]. */
    class Downhill(
        val ticks: List<DoubleArray>,
        /** Segments of the line (with nonzero length). */
        val segments: Int,
        /** Segments whose ticks went on the left (the DEM says that side is lower). */
        val segmentsLeft: Int,
        /** Segments the DEM could not decide on their own (flat, no data); they took the whole line's side. */
        val segmentsUndecided: Int,
    )

    /**
     * Tick segments on the downhill side of [line], decided segment by segment, so a cliff line that
     * bends around a spur or changes its facing gets its ticks on the low side all along. Each segment
     * samples [elevation] [offset] pixels to each side at least once (every [step] pixels on long
     * segments). A segment that can't tell (drop under [minDrop], or no data) uses the whole line's
     * answer, and if that can't tell either, the right side (the OSM `natural=cliff` convention).
     */
    fun downhillTicks(
        line: DoubleArray,
        spacing: Double,
        length: Double,
        offset: Double,
        elevation: (Double, Double) -> Float,
        step: Double = 4.0,
        minDrop: Double = 1.0,
    ): Downhill {
        val n = line.size / 2
        val sides = arrayOfNulls<Boolean>(maxOf(0, n - 1))
        val counted = ArrayList<Int>()
        val whole = Drop()
        for (i in 0 until n - 1) {
            val ax = line[2 * i]
            val ay = line[2 * i + 1]
            val dx = line[2 * i + 2] - ax
            val dy = line[2 * i + 3] - ay
            val d = hypot(dx, dy)
            if (d <= 0) continue
            counted += i
            val drop = Drop()
            val m = maxOf(1, ceil(d / step).toInt())
            for (k in 0 until m) {
                val t = (k + 0.5) / m
                drop.add(ax + dx * t, ay + dy * t, dx / d, dy / d, offset, elevation)
            }
            whole.addAll(drop)
            sides[i] = drop.decide(minDrop)
        }
        val fallback = whole.decide(minDrop) ?: true
        return Downhill(
            ticks = ticks(line, spacing, length) { seg -> sides[seg] ?: fallback },
            segments = counted.size,
            segmentsLeft = counted.count { (sides[it] ?: fallback) == false },
            segmentsUndecided = counted.count { sides[it] == null },
        )
    }

    /**
     * Bilinear elevation at an image position over a [width] x [height] grid whose cell (i, j) covers
     * pixels [i, i + 1) x [j, j + 1) (so its value sits at the cell center). NaN outside the grid or
     * where a neighbouring cell is NaN; positions within half a cell of the edge use the edge value.
     */
    fun bilinear(width: Int, height: Int, value: (Int, Int) -> Float): (Double, Double) -> Float = { x, y ->
        if (x < 0 || y < 0 || x >= width || y >= height) {
            Float.NaN
        } else {
            val fx = (x - 0.5).coerceIn(0.0, (width - 1).toDouble())
            val fy = (y - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val i0 = floor(fx).toInt()
            val j0 = floor(fy).toInt()
            val tx = (fx - i0).toFloat()
            val ty = (fy - j0).toFloat()
            // On a center line, don't touch the next cell (a NaN there would poison a 0 weight).
            val i1 = if (tx == 0f) i0 else minOf(i0 + 1, width - 1)
            val j1 = if (ty == 0f) j0 else minOf(j0 + 1, height - 1)
            val top = value(i0, j0) * (1 - tx) + value(i1, j0) * tx
            val bottom = value(i0, j1) * (1 - tx) + value(i1, j1) * tx
            top * (1 - ty) + bottom * ty
        }
    }

    /** Running mean of (left - right) elevation over stations; NaN pairs are skipped. */
    private class Drop {
        var sum = 0.0
        var n = 0

        fun add(x: Double, y: Double, ux: Double, uy: Double, offset: Double, elevation: (Double, Double) -> Float) {
            val right = elevation(x - uy * offset, y + ux * offset)
            val left = elevation(x + uy * offset, y - ux * offset)
            if (!right.isNaN() && !left.isNaN()) {
                sum += left - right
                n++
            }
        }

        fun addAll(other: Drop) {
            sum += other.sum
            n += other.n
        }

        fun decide(minDrop: Double): Boolean? {
            if (n == 0) return null
            val mean = sum / n
            return when {
                mean >= minDrop -> true
                mean <= -minDrop -> false
                else -> null
            }
        }
    }

    /**
     * Calls [action] with the segment index, position and unit direction every [spacing] pixels along
     * [line], from spacing/2.
     */
    private inline fun forEachStation(line: DoubleArray, spacing: Double, action: (Int, Double, Double, Double, Double) -> Unit) {
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
                action(i - 1, ax + dx * t, ay + dy * t, dx / d, dy / d)
                next += spacing
            }
            s += d
        }
    }
}
