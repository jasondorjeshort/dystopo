package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * T351: where the Panorama's overlays stand on a lens that fills the window (the owner's Answer of 2026-11-22, step one's
 * second half; the layout of the survey `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`:
 * the lens the whole screen, small controls at its edges, the layers behind one control). One rule for every overlay, as
 * boxes on the plot (px, the plot's top left at 0, 0) that never overlap and that the scene's names keep off
 * ([Layout.keepOff]):
 *
 * - the **tape** along the top edge ([PanoramaScene.TAPE_DP]);
 * - the **view switch** where its anchor puts it (the window's lower left, given);
 * - at the **upper right**, under the tape, "Layers" and "List view" side by side (one above the other when the two do
 *   not fit in the width);
 * - at the **upper left**, under the tape, a column of the place line with "Back to here" (at a chosen point), a picked
 *   peak's line and the weak-compass line, in that order, as wide as the room beside the upper right buttons, or the
 *   whole width under them when that room is under [MIN_COLUMN_DP]; a plate that would run into a lower overlay goes
 *   under the upper right buttons instead when it fits there;
 * - at the **lower right**, the zoom buttons ([BUTTON_DP] square, [BUTTON_MARGIN_DP] from the edges and apart) and
 *   "Follow phone" above them, lifted above the switch when the switch reaches under them;
 * - at the **lower left**, the compass correction's line and then the flights' credit: in the band beside the switch
 *   while each fits whole there (as wide as it stands above it), else stacked above the switch, clear of the lower right
 *   column;
 * - the **edge arrow** at the point [OffView] gives, else the nearest place clear of every box above, a move along its
 *   edge counting a third of one across (T331's follow-up: names lay under its plate, its box not given to the scene).
 *
 * [Layout.free] is the band between the upper and the lower overlays, where the plot's waiting line and a flight's card
 * stand. Each plate says its size for the width it may take ([Plate]); the rule asks, so a line that wraps at large text
 * takes the height it needs. What it promises (`PanoramaOverlaysTest`): no two boxes overlap at font 1.0 on any window;
 * at font 2.0 on a Pixel 6's window everything upright and all but everything at once on its side (a chosen point's line,
 * a pick, the weak-compass line and a correction together are taller than the window there). On a smaller window at 2.0
 * a long place line beside "Back to here" can still run into the lower right column.
 */
object PanoramaOverlays {
    /** The overlays, by the key `GET /panorama` `overlays` names them with. */
    enum class Item(val key: String) {
        TAPE("tape"), SWITCH("switch"), LAYERS("layers"), LIST("list"), PLACE("place"), PICKED("picked"), HINT("hint"),
        ZOOM_IN("zoomIn"), ZOOM_OUT("zoomOut"), FOLLOW("follow"), CREDIT("credit"), CORRECTION("correction"), ARROW("arrow"),
    }

    /** A plate's size, px. */
    data class Size(val width: Double, val height: Double)

    /** A plate: its size when it may take at most [maxWidth] px (its text wrapping to fit). */
    fun interface Plate {
        fun size(maxWidth: Double): Size
    }

    /** The edge arrow: [side] and the edge point ([x], [y]) [OffView] gives, and its plate (its line wraps at [ARROW_WIDTH] of the width). */
    class Arrow(val side: OffSide, val x: Double, val y: Double, val plate: Plate)

    /**
     * What stands on a plot [width] by [height] px at [dp] px per dp: the switch's box ([switchBox], plot px; null: none),
     * the two upper right buttons, "Follow phone"'s width ([followWidth] px; null: absent), and the plates shown now.
     */
    class Input(
        val width: Double, val height: Double, val dp: Double,
        val switchBox: RimBox?, val layers: Plate, val list: Plate,
        val followWidth: Double? = null, val place: Plate? = null, val picked: Plate? = null, val hint: Plate? = null,
        val credit: Plate? = null, val correction: Plate? = null, val arrow: Arrow? = null,
    )

    /** The boxes placed (plot px) and the free band between the upper and lower overlays. */
    data class Layout(val boxes: Map<Item, RimBox>, val free: RimBox) {
        operator fun get(i: Item): RimBox? = boxes[i]

        /** The boxes the scene's names keep off: all but the tape, which the scene blocks itself. */
        val keepOff: List<RimBox> get() = boxes.filterKeys { it != Item.TAPE }.values.toList()

        /** The first two boxes that overlap, or null (the rule's promise; its test). */
        fun overlap(): Pair<Item, Item>? {
            val l = boxes.entries.toList()
            for (i in l.indices) for (j in i + 1 until l.size) if (l[i].value.overlaps(l[j].value)) return l[i].key to l[j].key
            return null
        }
    }

    /** An overlay stands this far inside the plot's edges and under the tape, dp; two overlays keep this gap, dp. */
    const val MARGIN_DP = 6.0
    const val GAP_DP = 6.0

    /** The zoom buttons and "Follow phone": [BUTTON_DP] tall, [BUTTON_MARGIN_DP] from the edges and apart (T326's corner). */
    const val BUTTON_DP = PanoramaScene.ZOOM_BUTTON_DP
    const val BUTTON_MARGIN_DP = PanoramaScene.ZOOM_MARGIN_DP

    /** The upper left column stands beside the upper right buttons while it has at least this much room, dp; else under them. */
    const val MIN_COLUMN_DP = 280.0

    /** The lower left plates take at most this share of the plot's width; the arrow's plate at most [ARROW_WIDTH]. */
    const val LOWER_LEFT_WIDTH = 0.75
    const val ARROW_WIDTH = 0.7

    fun place(i: Input): Layout {
        val w = i.width
        val h = i.height
        val m = MARGIN_DP * i.dp
        val g = GAP_DP * i.dp
        val bm = BUTTON_MARGIN_DP * i.dp
        val bs = BUTTON_DP * i.dp
        val boxes = LinkedHashMap<Item, RimBox>()
        val tape = RimBox(0.0, 0.0, w, PanoramaScene.TAPE_DP * i.dp)
        boxes[Item.TAPE] = tape
        val sw = i.switchBox
        if (sw != null) boxes[Item.SWITCH] = sw

        // The lower right column: the zoom buttons, "Follow phone" above them; above the switch when it reaches under them.
        val right = w - bm
        val colLeft = right - max(bs, i.followWidth ?: 0.0)
        val bottom = if (sw != null && sw.right + bm > colLeft && sw.top < h) min(h, sw.top) - bm else h - bm
        val zoomOut = RimBox(right - bs, bottom - bs, right, bottom)
        val zoomIn = RimBox(right - bs, zoomOut.top - bm - bs, right, zoomOut.top - bm)
        boxes[Item.ZOOM_IN] = zoomIn
        boxes[Item.ZOOM_OUT] = zoomOut
        i.followWidth?.let { fw -> boxes[Item.FOLLOW] = RimBox(right - fw, zoomIn.top - bm - bs, right, zoomIn.top - bm) }
        val lowerRightLeft = boxes.filterKeys { it == Item.ZOOM_IN || it == Item.ZOOM_OUT || it == Item.FOLLOW }.values.minOf { it.left }

        // The upper right buttons, under the tape: side by side, or one above the other when the two do not fit.
        val top = tape.bottom + m
        val inner = w - 2 * m
        val ls = i.layers.size(inner)
        val ts = i.list.size(inner)
        if (ls.width + g + ts.width <= inner) {
            val list = RimBox(w - m - ts.width, top, w - m, top + ts.height)
            boxes[Item.LIST] = list
            boxes[Item.LAYERS] = RimBox(list.left - g - ls.width, top, list.left - g, top + ls.height)
        } else {
            val layers = RimBox(w - m - ls.width, top, w - m, top + ls.height)
            boxes[Item.LAYERS] = layers
            boxes[Item.LIST] = RimBox(w - m - ts.width, layers.bottom + g, w - m, layers.bottom + g + ts.height)
        }
        val cluster = listOf(boxes[Item.LAYERS]!!, boxes[Item.LIST]!!)
        val clusterLeft = cluster.minOf { it.left }
        val clusterBottom = cluster.maxOf { it.bottom }

        // The lower left plates: beside the switch while each fits whole there, else stacked above it.
        val floor = sw?.top ?: h
        var stackTop = floor - (if (sw != null) g else m)
        val bandRight = boxes.filterKeys { it == Item.ZOOM_IN || it == Item.ZOOM_OUT || it == Item.FOLLOW }.values
            .filter { sw != null && it.top < sw.bottom && it.bottom > sw.top }.minOfOrNull { it.left - g } ?: (w - m)
        var bandLeft = (sw?.right ?: 0.0) + g
        val stackWidth = min(w * LOWER_LEFT_WIDTH, min(zoomIn.left, zoomOut.left) - g - m)
        val narrowWidth = min(w * LOWER_LEFT_WIDTH, lowerRightLeft - g - m)
        for ((item, plate) in listOf(Item.CORRECTION to i.correction, Item.CREDIT to i.credit)) {
            if (plate == null) continue
            if (sw != null && bandRight - bandLeft > 0) {
                // Whole there: as wide as it is in the stack's width (a plate squeezed narrower wraps or cuts its line).
                val s = plate.size(stackWidth)
                if (s.width <= bandRight - bandLeft + 0.5 && s.height <= sw.bottom - tape.bottom) {
                    val b = RimBox(bandLeft, sw.bottom - s.height, bandLeft + s.width, sw.bottom)
                    if (boxes.values.none { it.overlaps(b) }) {
                        boxes[item] = b
                        bandLeft = b.right + g
                        continue
                    }
                }
            }
            // Clear of the zoom buttons; as narrow as Follow phone's edge only when it would reach up beside it.
            var s = plate.size(stackWidth)
            var b = RimBox(m, stackTop - s.height, m + min(s.width, stackWidth), stackTop)
            if (boxes.values.any { it.overlaps(b) }) {
                s = plate.size(narrowWidth)
                b = RimBox(m, stackTop - s.height, m + min(s.width, narrowWidth), stackTop)
            }
            boxes[item] = b
            stackTop = b.top - g
        }

        // The upper left column: beside the buttons while there is room, else the whole width under them. A plate that would
        // run into a lower one goes under the upper right buttons instead, where the lower right column leaves room.
        val beside = clusterLeft - g - m
        val (colWidth, colTop) = if (beside >= MIN_COLUMN_DP * i.dp) beside to top else inner to clusterBottom + g
        var y = colTop
        var yRight = clusterBottom + g
        val rightWidth = w - m - clusterLeft
        for ((item, plate) in listOf(Item.PLACE to i.place, Item.PICKED to i.picked, Item.HINT to i.hint)) {
            if (plate == null) continue
            val s = plate.size(colWidth)
            val b = RimBox(m, y, m + min(s.width, colWidth), y + s.height)
            if (boxes.values.none { it.overlaps(b) } || rightWidth <= 0) {
                boxes[item] = b
                y += s.height + g
                continue
            }
            val r = plate.size(rightWidth)
            val rb = RimBox(w - m - min(r.width, rightWidth), yRight, w - m, yRight + r.height)
            if (boxes.values.none { it.overlaps(rb) }) {
                boxes[item] = rb
                yRight += r.height + g
            } else {
                boxes[item] = b // no room anywhere: the test of the rule says so
                y += s.height + g
            }
        }

        // The free band: between the lowest upper overlay and the highest lower one.
        val upper = boxes.filterKeys { it in UPPER }.values
        val lower = boxes.filterKeys { it in LOWER }.values
        val freeTop = upper.maxOf { it.bottom } + g
        val freeBottom = max(freeTop, (lower.minOfOrNull { it.top } ?: h) - g)

        // The edge arrow last: at its edge point, then moved to the nearest place clear of everything above.
        i.arrow?.let { a -> boxes[Item.ARROW] = arrowBox(a, w, h, m, g, tape.bottom + m, boxes.values.filter { it !== tape }) }
        return Layout(boxes, RimBox(0.0, freeTop, w, freeBottom))
    }

    private val UPPER = setOf(Item.TAPE, Item.LAYERS, Item.LIST, Item.PLACE, Item.PICKED, Item.HINT)
    private val LOWER = setOf(Item.SWITCH, Item.ZOOM_IN, Item.ZOOM_OUT, Item.FOLLOW, Item.CREDIT, Item.CORRECTION)

    /**
     * The arrow's plate where its edge point puts it (beside the point at the left or right edge, under or over it at the
     * top or bottom; inside the plot's margins and under the tape), else the nearest place clear of [taken]: moved along
     * its edge first (a move across the edge counts three times its length), then across it.
     */
    private fun arrowBox(a: Arrow, w: Double, h: Double, m: Double, g: Double, minTop: Double, taken: Collection<RimBox>): RimBox {
        val s = a.plate.size(w * ARROW_WIDTH)
        val pw = s.width
        val ph = s.height
        val maxX = max(m, w - m - pw)
        val maxY = max(minTop, h - m - ph)
        val x0 = when (a.side) {
            OffSide.RIGHT -> a.x - pw
            OffSide.LEFT -> a.x
            else -> a.x - pw / 2
        }.coerceIn(m, maxX)
        val y0 = when (a.side) {
            OffSide.UP -> a.y
            OffSide.DOWN -> a.y - ph
            else -> a.y - ph / 2
        }.coerceIn(minTop, maxY)
        fun at(x: Double, y: Double) = RimBox(x, y, x + pw, y + ph)
        fun clear(b: RimBox) = taken.none { it.overlaps(b) }
        val first = at(x0, y0)
        if (clear(first)) return first
        val sideways = a.side == OffSide.LEFT || a.side == OffSide.RIGHT
        fun cost(x: Double, y: Double) = if (sideways) 3 * abs(x - x0) + abs(y - y0) else abs(x - x0) + 3 * abs(y - y0)
        // Candidates: each column and row where the plate stands flush beside a box taken (a gap away) or at an edge.
        val xs = (listOf(x0, m, maxX) + taken.flatMap { listOf(it.left - g - pw, it.right + g) }).map { it.coerceIn(m, maxX) }.distinct()
        val ys = (listOf(y0, minTop, maxY) + taken.flatMap { listOf(it.top - g - ph, it.bottom + g) }).map { it.coerceIn(minTop, maxY) }.distinct()
        var best: Pair<Double, Double>? = null
        for (x in xs) for (y in ys) {
            if (best != null && cost(x, y) >= cost(best.first, best.second)) continue
            if (clear(at(x, y))) best = x to y
        }
        return best?.let { at(it.first, it.second) } ?: first
    }
}
