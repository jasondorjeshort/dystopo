package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.render.ContourLabels
import io.github.jasondorjeshort.dystopo.render.ContourSimplify
import io.github.jasondorjeshort.dystopo.render.TextMeasurer
import io.github.jasondorjeshort.dystopo.render.orientedBox
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.time.TimeSource

/** One character of a curved line label, centered (by advance) at ([x], [y]) and rotated by [angle]. */
data class PlacedGlyph(val text: String, val x: Double, val y: Double, val angle: Double)

/** Platform hook: the horizontal advance of each character of [text], in pixels (for curved labels). */
fun interface TextAdvances {
    fun advances(text: String, fontPx: Double): DoubleArray
}

/**
 * A placed map label. A point label is [lines] of text stacked and centered at ([x], [y]), each
 * line [lineAdvance] below the previous, with an optional [marker] at ([markerX], [markerY]) of
 * diameter [markerPx]. A line label has [glyphs] following its line; [x], [y], [angle] are then
 * its middle and overall direction. [width]x[height] is the unrotated text block.
 */
data class PlacedLabel(
    val style: StyleLayer.Symbol,
    val lines: List<String>,
    val x: Double,
    val y: Double,
    val angle: Double,
    val width: Double,
    val height: Double,
    val fontPx: Double,
    val lineAdvance: Double,
    val marker: Marker? = null,
    val markerX: Double = 0.0,
    val markerY: Double = 0.0,
    val markerPx: Double = 0.0,
    val glyphs: List<PlacedGlyph>? = null,
) {
    fun corners(padding: Double = 0.0): DoubleArray = orientedBox(x, y, angle, width + 2 * padding, height + 2 * padding)
}

/** Per style layer label counters; [micros] is the time spent placing them. */
data class LabelCounts(var placed: Int = 0, var dropped: Int = 0, var micros: Long = 0)

/**
 * Collision-free placement of map labels into a [width]x[height] image, in request order (so
 * earlier requests win). Sizes come from the style times the user's [textScale], measured with
 * the renderer's own font through [TextMeasurer] (like contour labels).
 *
 * Point labels sit beside their marker (right, left, above, below; the first that fits), or
 * centered on the point when there is none. Line labels follow their line glyph by glyph, upright,
 * where the line bends gently (at most [maxGlyphTurn] radians between neighboring glyphs and
 * [maxTotalTurn] over the label), about one per style spacing along the line.
 */
class LabelPlacer(
    private val measurer: TextMeasurer,
    private val width: Int,
    private val height: Int,
    private val zoom: Double,
    private val textScale: Double,
    /** Measures italic text (water names); defaults to [measurer]. */
    private val italicMeasurer: TextMeasurer = measurer,
    /** Per-character advances; when null they are estimated from [measurer] (even spacing). */
    private val advances: TextAdvances? = null,
    private val italicAdvances: TextAdvances? = advances,
    /** Clear space around every label, and from the image edge. */
    private val padding: Double = 3.0,
    private val maxGlyphTurn: Double = 0.5,
    private val maxTotalTurn: Double = 1.4,
) {
    private val boxes = ArrayList<DoubleArray>()
    private val sameText = HashMap<String, MutableList<DoubleArray>>()
    val counts = LinkedHashMap<String, LabelCounts>()

    /** Places [requests] in order; returns the labels that fit. */
    fun placeAll(requests: List<LabelRequest>): List<PlacedLabel> {
        val out = ArrayList<PlacedLabel>()
        for (r in requests) {
            val c = counts.getOrPut(r.layer.id) { LabelCounts() }
            val mark = TimeSource.Monotonic.markNow()
            val placed = if (r.path == null) listOfNotNull(placePoint(r)) else placeLine(r, r.path)
            c.micros += mark.elapsedNow().inWholeMicroseconds
            if (placed.isEmpty()) c.dropped++ else c.placed += placed.size
            out += placed
        }
        return out
    }

    /** True when a box (four corners, interleaved x,y) overlaps a placed label. */
    fun collides(corners: DoubleArray): Boolean = boxes.any { ContourLabels.boxesOverlap(corners, it) }

    private fun fontPx(style: StyleLayer.Symbol) = style.size.at(zoom) * textScale

    private fun measurer(style: StyleLayer.Symbol) = if (style.italic) italicMeasurer else measurer

    private fun advancesFor(style: StyleLayer.Symbol, text: String, font: Double): DoubleArray {
        val a = if (style.italic) italicAdvances else advances
        if (a != null) return a.advances(text, font)
        val w = measurer(style).measure(text, font).width
        return DoubleArray(text.length) { w / text.length }
    }

    private fun sameKey(style: StyleLayer.Symbol, text: String) = style.id + "\u0000" + text

    private fun nearSameText(style: StyleLayer.Symbol, text: String, x: Double, y: Double): Boolean =
        sameText[sameKey(style, text)]?.any { hypot(it[0] - x, it[1] - y) < style.spacing } == true

    private fun accept(label: PlacedLabel, text: String, labelBoxes: List<DoubleArray>) {
        boxes += labelBoxes
        sameText.getOrPut(sameKey(label.style, text)) { ArrayList() } += doubleArrayOf(label.x, label.y)
    }

    private fun inside(box: DoubleArray): Boolean {
        for (i in 0 until 4) {
            val x = box[2 * i]
            val y = box[2 * i + 1]
            if (x < 0 || x > width || y < 0 || y > height) return false
        }
        return true
    }

    private fun fits(box: DoubleArray): Boolean = inside(box) && !collides(box)

    private fun placePoint(r: LabelRequest): PlacedLabel? {
        val style = r.layer
        val font = fontPx(style)
        val m = measurer(style)
        val advance = font * 1.2
        val w = r.lines.maxOf { m.measure(it, font).width }
        val h = font + advance * (r.lines.size - 1)
        if (nearSameText(style, r.text, r.x, r.y)) return null
        val marker = style.marker
        if (marker == null) {
            val label = PlacedLabel(style, r.lines, r.x, r.y, 0.0, w, h, font, advance)
            val box = label.corners(padding)
            if (!fits(box)) return null
            accept(label, r.text, listOf(box))
            return label
        }
        val mp = marker.size * textScale
        val markerBox = orientedBox(r.x, r.y, 0.0, mp + 2, mp + 2)
        if (!fits(markerBox)) return null
        val gap = font * 0.3
        val dx = mp / 2 + gap + w / 2
        val dy = mp / 2 + gap + h / 2
        val offsets = listOf(dx to 0.0, -dx to 0.0, 0.0 to -dy, 0.0 to dy)
        for ((ox, oy) in offsets) {
            val label = PlacedLabel(style, r.lines, r.x + ox, r.y + oy, 0.0, w, h, font, advance, marker, r.x, r.y, mp)
            val box = label.corners(padding)
            if (fits(box)) {
                accept(label, r.text, listOf(box, markerBox))
                return label
            }
        }
        return null
    }

    private fun placeLine(r: LabelRequest, pts: DoubleArray): List<PlacedLabel> {
        val style = r.layer
        val text = r.lines.joinToString(" ")
        if (text.isEmpty()) return emptyList()
        val font = fontPx(style)
        val adv = advancesFor(style, text, font)
        val len = adv.sum()
        // Glyphs follow a simplified, smoothed copy of the line: raw trail geometry zig-zags at
        // the pixel scale, which would scatter the letters. The text stays within ~0.4 font heights of the line.
        val smooth = ContourSimplify.smooth(ContourSimplify.simplify(pts, false, font * 0.4), false, 2)
        val path = Path(smooth)
        if (path.total < len + 2 * padding) return emptyList()
        val h = font * 0.85
        val step = maxOf(2.0, font * 0.4)
        val sections = maxOf(1, (path.total / style.spacing).toInt())
        val sectionLen = path.total / sections
        val result = ArrayList<PlacedLabel>()
        for (sec in 0 until sections) {
            val from = sec * sectionLen + padding
            val to = minOf(path.total - len - padding, (sec + 1) * sectionLen - len)
            var best: Pair<PlacedLabel, List<DoubleArray>>? = null
            var bestScore = Double.POSITIVE_INFINITY
            var s = from
            while (s <= to) {
                val c = curved(path, s, text, adv, len, font, h)
                if (c != null && c.score < bestScore && !nearSameText(style, text, c.x, c.y) && c.boxes.all { fits(it) }) {
                    best = PlacedLabel(style, listOf(text), c.x, c.y, c.angle, len, h, font, font * 1.2, glyphs = c.glyphs) to c.boxes
                    bestScore = c.score
                }
                s += step
            }
            if (best != null) {
                accept(best.first, text, best.second)
                result += best.first
            }
        }
        return result
    }

    private class Curved(val glyphs: List<PlacedGlyph>, val boxes: List<DoubleArray>, val x: Double, val y: Double, val angle: Double, val score: Double)

    /** Lays [text] along [path] from arc position [s]; null where the line bends too much. */
    private fun curved(path: Path, s: Double, text: String, adv: DoubleArray, len: Double, font: Double, h: Double): Curved? {
        val (ax, ay) = path.at(s)
        val (bx, by) = path.at(s + len)
        // Upright: if the stretch runs right to left, lay the glyphs from its far end backwards.
        val reversed = bx < ax
        val glyphs = ArrayList<PlacedGlyph>(text.length)
        val boxes = ArrayList<DoubleArray>(text.length)
        var prev = Double.NaN
        var turning = 0.0
        var net = 0.0
        var off = 0.0
        for (i in text.indices) {
            val c = off + adv[i] / 2
            off += adv[i]
            val sc = if (reversed) s + len - c else s + c
            val half = maxOf(adv[i] / 2, font * 0.35)
            val (x0, y0) = path.at(sc - half)
            val (x1, y1) = path.at(sc + half)
            var angle = atan2(y1 - y0, x1 - x0)
            if (reversed) angle = normalize(angle + PI)
            if (!prev.isNaN()) {
                val d = normalize(angle - prev)
                if (abs(d) > maxGlyphTurn) return null
                turning += abs(d)
                net += d
                if (abs(net) > maxTotalTurn) return null
            }
            prev = angle
            val (px, py) = path.at(sc)
            glyphs += PlacedGlyph(text[i].toString(), px, py, angle)
            boxes += orientedBox(px, py, angle, adv[i] + 1.0, h + 2 * padding)
        }
        if (turning > maxTotalTurn * 1.5) return null
        var chordAngle = atan2(by - ay, bx - ax)
        if (chordAngle > PI / 2) chordAngle -= PI
        if (chordAngle <= -PI / 2) chordAngle += PI
        val mid = path.at(s + len / 2)
        // Straight stretches first; slight preferences for level text and the middle of the line.
        val center = abs(s + len / 2 - path.total / 2) / path.total
        val score = turning + abs(chordAngle) / PI * 0.15 + center * 0.15
        return Curved(glyphs, boxes, mid.first, mid.second, chordAngle, score)
    }

    private fun normalize(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x <= -PI) x += 2 * PI
        return x
    }

    /** A polyline with cumulative arc length, for positions along it. */
    private class Path(val pts: DoubleArray) {
        val n = pts.size / 2
        val cum = DoubleArray(n).also { c ->
            for (i in 1 until n) c[i] = c[i - 1] + hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
        }
        val total: Double get() = cum[n - 1]

        fun at(s: Double): Pair<Double, Double> {
            if (n == 1) return pts[0] to pts[1]
            val t = s.coerceIn(0.0, total)
            var lo = 0
            var hi = n - 2
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (cum[mid] <= t) lo = mid else hi = mid - 1
            }
            val segLen = cum[lo + 1] - cum[lo]
            val f = if (segLen <= 0) 0.0 else (t - cum[lo]) / segLen
            return (pts[2 * lo] + (pts[2 * lo + 2] - pts[2 * lo]) * f) to (pts[2 * lo + 1] + (pts[2 * lo + 3] - pts[2 * lo + 1]) * f)
        }
    }
}
