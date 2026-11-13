package io.github.jasondorjeshort.dystopo.ui

import kotlin.math.max
import kotlin.math.min

/** T224: a measured size, dp. */
data class OverlaySize(val width: Float, val height: Float)

/** T224: a rectangle from the window's top left, dp. Touching edges do not intersect. */
data class OverlayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun intersects(o: OverlayRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** T224: what became of an element: drawn whole, drawn smaller by its own means (a card scrolls, the strip leaves entries out), or not drawn. */
enum class OverlayState(val code: String) { SHOWN("shown"), COMPACT("compact"), HIDDEN("hidden") }

/**
 * T224: a card's measured heights at [width]: [natural] whole; [least] what it keeps while it scrolls (its header, its
 * actions and two lines); [headline] the part that stays in view (the navigation instruction, a warning's title and
 * advice). At a narrower width the heights are estimated by keeping the area ([at]); the consumers of next wave measure.
 *
 * T239: [drawn], where the card's own code drew it, until the cards are placed by the rule too (Layout C): the rule then
 * keeps the card at that rectangle and lays the chips, the online column and the two lower columns around it (see
 * [OverlayLayout]). With [OverlayInput.cardsDrawn] a card without it (up, not drawn yet) is left out.
 */
data class OverlayCard(val natural: Float, val least: Float, val headline: Float, val width: Float, val drawn: OverlayRect? = null) {
    internal fun at(w: Float): OverlayCard {
        if (drawn != null) return this
        val k = if (w <= 0f || w >= width) 1f else width / w
        return OverlayCard(natural * k, least * k, headline * k, min(w, width))
    }
}

/**
 * T224: the inputs of [OverlayLayout.place], all measured, dp. A null size means the element is not up (not composed,
 * switched off). [search] carries the width it would like. [strip] holds the readout's entries in drawn order, elevation
 * first, without the plate's padding. [fontScale] is carried for the report: every size here was measured at it.
 * [buttons] is the position button's layout as set (1 to 3). [offCentre]: the map is off the fix, so the position button
 * is never hidden.
 *
 * T239: [trackBar] is the bar's chip row; [trackPanel] the height of what opens under it (the activity picker, the
 * recording's lines): the bar fits by its row and the panel takes what is left, at most its height, so an open panel
 * never hides the chips it hangs from (T224's clamp, now the rule's). [panels]: the track controls' panels drawn above
 * the credit line by their own code (the profile, a waypoint's sheet, the long-press card): the scale bar's line and the
 * position column stay above them, as they stay above a card that is [OverlayCard.drawn]. [cardsDrawn]: the cards come
 * drawn by their own code (the app until Layout C), so the rule lays the rest around them ([OverlayLayout]).
 */
data class OverlayInput(
    val windowWidth: Float,
    val windowHeight: Float,
    val insetLeft: Float = 0f,
    val insetTop: Float = 0f,
    val insetRight: Float = 0f,
    val insetBottom: Float = 0f,
    val fontScale: Float = 1f,
    val search: OverlaySize,
    val topRow: OverlaySize,
    val credit: OverlaySize,
    val online: OverlaySize? = null,
    val trackBar: OverlaySize? = null,
    val routes: OverlaySize? = null,
    /** T263: the activity chip, under the Routes chip in the chips' column (the app's current activity; a tap opens the picker). */
    val activity: OverlaySize? = null,
    val compass: OverlaySize? = null,
    val scaleBar: OverlaySize? = null,
    val strip: List<OverlaySize> = emptyList(),
    /**
     * T257: how many of [strip]'s first entries outrank the scale bar (the readout's own four, the course by its point);
     * the rest (the pressure, the extended readout's, the course's degrees) are left out before the scale bar yields,
     * and never come back once it has. Past [strip]'s size: every entry outranks it, as before T257.
     */
    val stripCore: Int = Int.MAX_VALUE,
    val buttons: Int = 1,
    val offCentre: Boolean = false,
    val navigation: OverlayCard? = null,
    val warning: OverlayCard? = null,
    val planning: OverlayCard? = null,
    val trip: OverlayCard? = null,
    val update: OverlayCard? = null,
    val trackPanel: Float = 0f,
    val panels: OverlayRect? = null,
    val cardsDrawn: Boolean = false,
)

/** T224: one element's place: [rect] is null when [state] is hidden, and [reason] says why it is not whole. */
data class OverlayElement(val id: String, val rect: OverlayRect?, val state: OverlayState, val reason: String? = null)

/**
 * T239 (the design note's latch): the two choices that reshape the whole screen, [sideColumn] and [cardColumns], held
 * while the window, the font scale and the set of cards stay as they were, so a re-measure at a new width (a chip that
 * wraps, a card that grows) cannot flip them from one frame to the next. [OverlayLayout.place] takes the last one and
 * returns the one it used.
 */
data class OverlayLatch(val windowWidth: Float, val windowHeight: Float, val fontScale: Float, val cards: Set<String>, val sideColumn: Boolean, val cardColumns: Int) {
    fun holds(i: OverlayInput): Boolean =
        windowWidth == i.windowWidth && windowHeight == i.windowHeight && fontScale == i.fontScale && cards == OverlayLayout.cardIds(i)
}

/**
 * T224: every element of the map screen, with the position column's buttons and the readout's entries drawn. T239:
 * [latch], the choices this placement was made with; [trackRoom], the most height the track bar's column may take (its
 * row and the room left to its panel, dp; 0 while the bar is not placed), the cap its measure takes; [stripRoom], the width
 * the readout strip's entries were wrapped at (its plate's padding in, dp; 0 while it is not placed), the strip's own.
 */
data class OverlayPlacement(
    val elements: List<OverlayElement>, val buttonsDrawn: Int, val entriesDrawn: Int, val sideColumn: Boolean, val cardColumns: Int,
    val latch: OverlayLatch? = null, val trackRoom: Float = 0f, val stripRoom: Float = 0f,
) {
    operator fun get(id: String): OverlayElement? = elements.firstOrNull { it.id == id }

    /** The pairs whose rectangles intersect; empty by construction. */
    fun overlaps(): List<Pair<String, String>> {
        val shown = elements.filter { it.rect != null }
        val out = ArrayList<Pair<String, String>>()
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            if (shown[i].rect!!.intersects(shown[j].rect!!)) out += shown[i].id to shown[j].id
        }
        return out
    }
}

/**
 * T224: the ids of ADR 0053's controls inventory (`docs/design/proposals/0053-controls-inventory.md`, section 4). An
 * element that groups several controls goes under the first of them ([CONTROLS]); the scale bar and the compass take no
 * input, so the inventory has no row for them and they keep a name of their own.
 */
object OverlayIds {
    const val SETTINGS_ROW = "M1"
    const val SEARCH = "M3"
    const val ONLINE = "M8"
    const val TRACK_BAR = "M11"
    const val ROUTES = "M41"
    const val TRIP_CARD = "M45"
    const val PLANNING_CARD = "M59"
    const val NAV_CARD = "M85"
    const val WARNING_CARD = "M92"
    const val LOCK = "M94"
    const val ORIENTATION = "M95"
    const val POSITION = "M96"
    const val STRIP = "M98"
    const val CREDIT = "M99"
    const val UPDATE_CARD = "M108"
    const val SCALE_BAR = "scaleBar"
    const val COMPASS = "compass"

    /** T263: the activity chip; added after the inventory was drawn up, so it has no M-number and keeps a name of its own. */
    const val ACTIVITY = "activityChip"

    /** The inventory's rows each element holds, and the name `GET /layout` prints beside the id. */
    val CONTROLS: Map<String, List<String>> = mapOf(
        SETTINGS_ROW to listOf("M1", "M2"), SEARCH to (3..7).map { "M$it" }, ONLINE to (8..10).map { "M$it" },
        TRACK_BAR to (11..16).map { "M$it" }, ROUTES to listOf("M41"), TRIP_CARD to (45..58).map { "M$it" },
        PLANNING_CARD to (59..73).map { "M$it" }, NAV_CARD to (85..91).map { "M$it" }, WARNING_CARD to listOf("M92", "M93"),
        LOCK to listOf("M94"), ORIENTATION to listOf("M95", "M97"), POSITION to listOf("M96", "M97"), STRIP to listOf("M98"),
        CREDIT to listOf("M99"), UPDATE_CARD to (108..112).map { "M$it" },
    )
    val NAMES: Map<String, String> = mapOf(
        SETTINGS_ROW to "settingsAndPacks", SEARCH to "search", ONLINE to "onlineIndicator", TRACK_BAR to "waypointAndTracks",
        ROUTES to "routesChip", TRIP_CARD to "tripCard", PLANNING_CARD to "planningCard", NAV_CARD to "navigationCard",
        WARNING_CARD to "warningCard", LOCK to "lockButton", ORIENTATION to "orientationButton", POSITION to "positionButton",
        STRIP to "readoutStrip", CREDIT to "creditLine", UPDATE_CARD to "updateCard", SCALE_BAR to "scaleBar", COMPASS to "compass",
        ACTIVITY to "activityChip", // T263
    )
}

/**
 * T224: the map screen's layout as one pure rule, the first half of a layout authority: `GET /layout` reports what it
 * would place beside what the elements place today, and nothing reads it yet (next wave's consumers, by the design note
 * in T224's merge commit).
 *
 * The yield order (the survey `map-screen-controls.md`: no app documents one, so this is the project's own rule, with the
 * survey's recommendation as its source):
 * - Never hidden and never shrunk: the credit line, the navigation instruction, a warning's headline, the position
 *   button while the map is off centre, Search (it narrows for the top row, to no less than [SEARCH_MIN]; its text does not shrink).
 * - Cards scroll and collapse, down to their least, before anything is hidden.
 * - Then hidden in this order, one rung at a time ([rungs]): the lock button, the orientation button, (T257) the
 *   readout's extra entries past [OverlayInput.stripCore] from the end (the course's degrees, the fix age, the accuracy,
 *   the pressure), the scale bar, the readout's entries from the end of their drawn order with the elevation last, and
 *   (centred only) the position button.
 *   The ladder is global: a rung is taken for the whole screen, so the order holds where the two sides are short apart too.
 * - The Waypoint and Tracks bar, the Routes chip, (T263) the activity chip, the compass and the online indicator keep their size while they fit
 *   and hide rather than sit under a card or the credit line; they do not wait for the ladder.
 * - A window under [COMPACT_HEIGHT] dp tall (a phone on its side, Material's compact height) keeps the chips and the
 *   buttons in a column of their own at the right and the cards in the rest, side by side when one stack does not hold
 *   their least. The planning card gives way to the navigation card, as the app already does while navigating.
 *
 * The rectangles never intersect by construction: everything between the top row and the credit line lies in bands.
 *
 * T239 (Layout B, steps 3 to 5 of T224's design note): the app places the compass, the chips, the online column, the
 * position column, the scale bar and the readout strip from this rule (`app/MapLayout.kt`). The cards are still placed by
 * their own code until Layout C, so they come in [OverlayCard.drawn] and the rule keeps them there: the chips start
 * under the navigation card where it reaches their column, the warning follows the chips as it does in the app, the two
 * lower columns stand on the highest drawn card, panel or credit line below them, and the room left between is what the
 * fits and the ladder count. [place] holds [OverlayLatch]'s two choices.
 */
object OverlayLayout {
    const val COMPACT_HEIGHT = 480f
    const val SEARCH_MIN = 96f
    const val SEARCH_WIDTH = 220f
    private const val GAP = 6f
    private const val EDGE = 8f
    private const val MARGIN = 12f
    private const val CREDIT_PAD = 6f
    private const val BUTTON = 48f
    private const val BUTTON_GAP = 8f
    private const val COLUMN = BUTTON + MARGIN
    private const val STRIP_RESERVE = COLUMN + EDGE
    private const val STRIP_MIN_BESIDE = 120f
    private const val STRIP_BESIDE_X = 100f + 2f + 12f + GAP
    private const val PLATE_X = 12f
    private const val PLATE_Y = 6f
    private const val ITEM_GAP = 10f
    private const val ROW_GAP = 2f
    private const val MIN_MAIN = 280f
    private const val MIN_CARD_COLUMN = 180f
    private const val NAV_WIDTH = 520f
    private const val WARNING_WIDTH = 420f
    private const val PLANNING_WIDTH = 420f
    private const val TRIP_WIDTH = 440f
    private const val NO_FIT = 1e9f

    const val REASON_NO_ROOM = "noRoom"
    const val REASON_ORDER = "yieldOrder"
    const val REASON_NARROWED = "narrowed"
    const val REASON_SCROLLS = "scrolls"
    const val REASON_HEADLINE_SCROLLS = "headlineScrolls"
    const val REASON_ENTRIES = "entriesLeftOut"
    const val REASON_NAVIGATING = "navigating"

    /** One step of the ladder: the buttons drawn, whether the scale bar is, how many of the readout's entries are. */
    private data class Rung(val buttons: Int, val bar: Boolean, val entries: Int)

    private fun rungs(i: OverlayInput): List<Rung> {
        val out = ArrayList<Rung>()
        for (b in i.buttons.coerceIn(1, 3) downTo 1) out += Rung(b, i.scaleBar != null, i.strip.size)
        // T257: the strip's extra entries go before the scale bar does; the bar never yields to them.
        val core = if (i.scaleBar == null) i.strip.size else i.stripCore.coerceIn(0, i.strip.size)
        if (i.scaleBar != null) for (e in i.strip.size - 1 downTo core) out += Rung(1, true, e)
        for (e in core downTo 0) out += Rung(1, false, e)
        if (!i.offCentre) out += Rung(0, false, 0)
        return out.distinct()
    }

    /** T239: the cards an input has up, by id (the latch's key). */
    fun cardIds(i: OverlayInput): Set<String> = listOfNotNull(
        i.navigation?.let { OverlayIds.NAV_CARD }, i.warning?.let { OverlayIds.WARNING_CARD }, i.planning?.let { OverlayIds.PLANNING_CARD },
        i.trip?.let { OverlayIds.TRIP_CARD }, i.update?.let { OverlayIds.UPDATE_CARD },
    ).toSet()

    /** The placement of [i]; T239: with [latch]'s choices while it [OverlayLatch.holds], and the latch it used in the answer. */
    fun place(i: OverlayInput, latch: OverlayLatch? = null): OverlayPlacement {
        val held = latch?.takeIf { it.holds(i) }
        val rungs = rungs(i)
        val p = if (i.cardsDrawn) arrangeDrawn(i, rungs, held)
        else rungs.firstNotNullOfOrNull { arrange(i, it, squeeze = false, held) } ?: arrange(i, rungs.last(), squeeze = true, held)!!
        return p.copy(latch = held ?: OverlayLatch(i.windowWidth, i.windowHeight, i.fontScale, cardIds(i), p.sideColumn, p.cardColumns))
    }

    private class CardIn(val id: String, val card: OverlayCard, val top: Boolean) {
        var height = 0f
    }

    /** The scale bar's line at a rung: the bar, the strip's plate, and whether the plate goes [beside] (at that x) or above the bar. */
    private class Line(val height: Float, val bar: OverlaySize?, val strip: OverlaySize?, val beside: Float?, val stripWidth: Float = 0f)

    private fun stripPlate(entries: List<OverlaySize>, width: Float): OverlaySize? {
        if (entries.isEmpty()) return null
        val inner = width - PLATE_X
        var rows = 0f
        var rowW = 0f
        var rowH = 0f
        var widest = 0f
        for (e in entries) {
            if (rowW > 0f && rowW + ITEM_GAP + e.width > inner) {
                rows += rowH + ROW_GAP
                widest = max(widest, rowW)
                rowW = 0f
                rowH = 0f
            }
            rowW += (if (rowW > 0f) ITEM_GAP else 0f) + e.width
            rowH = max(rowH, e.height)
        }
        return OverlaySize(min(max(widest, rowW) + PLATE_X, width), rows + rowH + PLATE_Y)
    }

    private fun line(i: OverlayInput, r: Rung, width: Float): Line {
        val bar = if (r.bar) i.scaleBar else null
        if (bar != null && bar.width > width) return Line(NO_FIT, null, null, null)
        val entries = i.strip.take(r.entries)
        if (entries.isEmpty()) return Line(bar?.height ?: 0f, bar, null, null)
        if (bar == null) return stripPlate(entries, width)!!.let { Line(it.height, null, it, null, width) }
        val x = max(bar.width + GAP, STRIP_BESIDE_X)
        val room = width - x
        val natural = entries.sumOf { it.width.toDouble() }.toFloat() + ITEM_GAP * (entries.size - 1) + PLATE_X
        if (room >= STRIP_MIN_BESIDE && natural <= room) {
            val s = stripPlate(entries, room)!!
            return Line(max(bar.height, s.height), bar, s, x, room)
        }
        val s = stripPlate(entries, width)!!
        return Line(bar.height + GAP + s.height, bar, s, null, width)
    }

    private fun stack(cards: List<CardIn>, f: (CardIn) -> Float): Float =
        if (cards.isEmpty()) 0f else cards.sumOf { f(it).toDouble() }.toFloat() + GAP * (cards.size - 1)

    /** Gives [cards] their least within [room], squeezing the leasts and then the headlines (which then scroll). */
    private fun squeezeTo(cards: List<CardIn>, room: Float) {
        val r = (room - GAP * (cards.size - 1).coerceAtLeast(0)).coerceAtLeast(0f)
        val least = cards.sumOf { it.card.least.toDouble() }.toFloat()
        val heads = cards.sumOf { it.card.headline.toDouble() }.toFloat()
        for (c in cards) c.height = when {
            least <= r -> c.card.least
            heads <= r && least > heads -> c.card.headline + (c.card.least - c.card.headline) * (r - heads) / (least - heads)
            heads > 0f -> c.card.headline * r / heads
            else -> 0f
        }
    }

    private fun grow(cards: List<CardIn>, room: Float) {
        var extra = (room - stack(cards) { it.height }).coerceAtLeast(0f)
        for (id in listOf(OverlayIds.WARNING_CARD, OverlayIds.NAV_CARD, OverlayIds.PLANNING_CARD, OverlayIds.TRIP_CARD, OverlayIds.UPDATE_CARD)) {
            val c = cards.firstOrNull { it.id == id } ?: continue
            val more = min(extra, c.card.natural - c.height).coerceAtLeast(0f)
            c.height += more
            extra -= more
        }
    }

    /** The credit line, Search and the top row (always, in bands nothing else enters), added to [out]; the body between them. */
    private class Frame(val bodyTop: Float, val bodyBottom: Float) {
        val bodyH: Float get() = bodyBottom - bodyTop
    }

    private fun frame(i: OverlayInput, out: MutableList<OverlayElement>): Frame {
        val safeL = i.insetLeft
        val safeR = i.windowWidth - i.insetRight
        val safeT = i.insetTop
        val safeB = i.windowHeight - i.insetBottom
        val creditW = min(i.credit.width, safeR - safeL - CREDIT_PAD * 2)
        val creditTop = safeB - CREDIT_PAD - i.credit.height
        out += OverlayElement(OverlayIds.CREDIT, OverlayRect(safeL + CREDIT_PAD, creditTop, safeL + CREDIT_PAD + creditW, safeB - CREDIT_PAD), OverlayState.SHOWN)
        // The top row beside Search while Search keeps its least, else on a line of its own under it.
        val rowLeft = safeR - MARGIN - i.topRow.width
        val beside = rowLeft - GAP - EDGE - (safeL + EDGE) >= SEARCH_MIN
        val searchW = min(i.search.width, if (beside) rowLeft - GAP - EDGE - (safeL + EDGE) else safeR - safeL - EDGE * 2)
        val searchBottom = safeT + EDGE + i.search.height
        val narrowed = searchW < i.search.width
        out += OverlayElement(
            OverlayIds.SEARCH, OverlayRect(safeL + EDGE, safeT + EDGE, safeL + EDGE + searchW, searchBottom),
            if (narrowed) OverlayState.COMPACT else OverlayState.SHOWN, if (narrowed) REASON_NARROWED else null,
        )
        val rowTop = if (beside) safeT + MARGIN else searchBottom + GAP
        out += OverlayElement(OverlayIds.SETTINGS_ROW, OverlayRect(rowLeft, rowTop, safeR - MARGIN, rowTop + i.topRow.height), OverlayState.SHOWN)
        return Frame(max(searchBottom, rowTop + i.topRow.height) + GAP, creditTop - GAP)
    }

    private fun arrange(i: OverlayInput, r: Rung, squeeze: Boolean, held: OverlayLatch?): OverlayPlacement? {
        val out = ArrayList<OverlayElement>()
        val safeL = i.insetLeft
        val safeR = i.windowWidth - i.insetRight
        val shown = { id: String, rect: OverlayRect -> out += OverlayElement(id, rect, OverlayState.SHOWN) }
        val hidden = { id: String, why: String -> out += OverlayElement(id, null, OverlayState.HIDDEN, why) }
        val f = frame(i, out)
        val bodyTop = f.bodyTop
        val bodyBottom = f.bodyBottom
        val bodyH = f.bodyH

        fun cards(widthOf: (Float) -> Float): List<CardIn> {
            val out2 = ArrayList<CardIn>()
            i.navigation?.let { out2 += CardIn(OverlayIds.NAV_CARD, it.at(widthOf(NAV_WIDTH)), true) }
            i.warning?.let { out2 += CardIn(OverlayIds.WARNING_CARD, it.at(widthOf(WARNING_WIDTH)), true) }
            if (i.navigation == null) i.planning?.let { out2 += CardIn(OverlayIds.PLANNING_CARD, it.at(widthOf(PLANNING_WIDTH)), false) }
            i.trip?.let { out2 += CardIn(OverlayIds.TRIP_CARD, it.at(widthOf(TRIP_WIDTH)), false) }
            i.update?.let { out2 += CardIn(OverlayIds.UPDATE_CARD, it.at(widthOf(TRIP_WIDTH)), false) }
            return out2
        }
        if (i.navigation != null && i.planning != null) hidden(OverlayIds.PLANNING_CARD, REASON_NAVIGATING)
        // A window on its side: a column of its own at the right for the chips and the buttons, and the cards in the rest,
        // side by side when one stack does not hold their least. Headlines that still do not fit take the chips' width too.
        val chipsW = max(max(i.trackBar?.width ?: 0f, max(i.routes?.width ?: 0f, i.activity?.width ?: 0f)), max(i.compass?.width ?: 0f, COLUMN - EDGE)) + EDGE
        val side = held?.sideColumn ?: (i.windowHeight < COMPACT_HEIGHT && safeR - safeL - chipsW >= MIN_MAIN)
        val heldTwo = held?.let { it.cardColumns == 2 } // T239: the latch
        fun twoColumns(all: List<CardIn>, roomW: Float) = side && all.size >= 2 && stack(all) { it.card.least } > bodyH && roomW >= MIN_CARD_COLUMN * 2 + GAP
        fun atColumn(roomW: Float) = cards { min(it, (roomW - GAP) / 2) }
        val roomWide = (if (side) safeR - chipsW else safeR) - safeL - EDGE * 2
        val dropChips = side && (heldTwo ?: twoColumns(cards { min(it, roomWide) }, roomWide)) && atColumn(roomWide).any { it.card.headline > bodyH }
        val rightW = if (dropChips) COLUMN else chipsW
        val mainW = (if (side) safeR - rightW else safeR) - safeL
        val cardRoomW = mainW - EDGE * 2
        val single = cards { min(it, cardRoomW) }
        val two = heldTwo ?: twoColumns(single, cardRoomW)
        val columnW = if (two) (cardRoomW - GAP) / 2 else cardRoomW
        val all = if (two) atColumn(cardRoomW) else single
        val colA = if (two) all.take(1) else all
        val colB = if (two) all.drop(1) else emptyList()

        // Room for the rest of the first column once its cards have their least.
        val free = bodyH - if (colA.isEmpty()) 0f else stack(colA) { it.card.least } + GAP
        val reserve = if (i.offCentre) BUTTON + GAP else 0f
        val rightEdge = safeR - EDGE

        // The chips keep their size while they fit; the position button off centre keeps its place.
        val chips = ArrayList<Pair<String, OverlaySize>>()
        fun chipsH() = if (chips.isEmpty()) 0f else chips.sumOf { it.second.height.toDouble() }.toFloat() + GAP * (chips.size - 1)
        fun collides(o: OverlaySize?) = o != null && chips.isNotEmpty() && safeL + EDGE + o.width + GAP > rightEdge - chips.maxOf { it.second.width }
        fun band(o: OverlaySize?): Float = when {
            side -> o?.height ?: 0f
            collides(o) -> o!!.height + GAP + chipsH()
            else -> max(o?.height ?: 0f, chipsH())
        }
        fun tryChip(id: String, s: OverlaySize?) {
            if (s == null) return
            if (dropChips) {
                hidden(id, REASON_NO_ROOM)
                return
            }
            chips += id to s
            val fits = if (side) chipsH() + GAP + reserve <= bodyH else band(null) + GAP + reserve <= free
            if (!fits) {
                chips.removeAt(chips.size - 1)
                hidden(id, REASON_NO_ROOM)
            }
        }
        tryChip(OverlayIds.TRACK_BAR, i.trackBar)
        // The Routes chip sits under the bar, in the app's own clamp too: without the bar it hides with it.
        if (i.trackBar == null || chips.isNotEmpty()) tryChip(OverlayIds.ROUTES, i.routes)
        else if (i.routes != null) hidden(OverlayIds.ROUTES, REASON_NO_ROOM) // also with the chips' width gone
        tryChip(OverlayIds.ACTIVITY, i.activity) // T263: under the Routes chip, before the compass
        tryChip(OverlayIds.COMPASS, i.compass)
        val online = i.online?.takeIf { o -> o.width <= columnW && band(o) + GAP + (if (side) 0f else reserve) <= free }
        if (i.online != null && online == null) hidden(OverlayIds.ONLINE, REASON_NO_ROOM)
        val chipBand = band(online).let { if (it > 0f) it + GAP else 0f }

        // This rung's scale bar line and position column.
        val lineW = if (side) columnW else safeR - safeL - EDGE - STRIP_RESERVE
        val ln = line(i, r, lineW)
        val columnH = if (r.buttons <= 0) 0f else BUTTON * r.buttons + BUTTON_GAP * (r.buttons - 1)
        val lineNeed = if (side) ln.height else max(ln.height, columnH)
        val lineBand = if (lineNeed > 0f) lineNeed + GAP else 0f
        val rightUsed = if (chips.isEmpty()) 0f else chipsH() + GAP
        val fits = lineBand <= free - chipBand && (!side || rightUsed + columnH <= bodyH)
        if (!fits && !squeeze) return null

        // The cards: their least, then what is left toward their natural height, the warning first.
        val roomA = bodyH - chipBand - lineBand - if (colA.isEmpty()) 0f else GAP
        squeezeTo(colA, roomA)
        if (!squeeze) grow(colA, roomA)
        squeezeTo(colB, bodyH)
        grow(colB, bodyH)

        fun place(c: CardIn, y: Float, x0: Float, w: Float) {
            val cw = min(w, c.card.width)
            val x = x0 + (w - cw) / 2
            val whole = c.height + 0.01f >= c.card.natural
            val why = if (whole) null else if (c.height + 0.01f < c.card.headline) REASON_HEADLINE_SCROLLS else REASON_SCROLLS
            out += OverlayElement(c.id, OverlayRect(x, y, x + cw, y + c.height), if (whole) OverlayState.SHOWN else OverlayState.COMPACT, why)
        }
        val xA = safeL + EDGE

        // Top down: the navigation card, the chips' band, the warning (the app's order), then the first column's rest.
        var y = bodyTop
        val first = colA.filter { it.top }
        for (c in first.filter { it.id == OverlayIds.NAV_CARD }) {
            place(c, y, xA, columnW)
            y += c.height + GAP
        }
        var chipY = if (side) bodyTop else if (collides(online)) y + online!!.height + GAP else y
        for ((id, s) in chips) {
            shown(id, OverlayRect(rightEdge - s.width, chipY, rightEdge, chipY + s.height))
            chipY += s.height + GAP
        }
        online?.let { o -> shown(OverlayIds.ONLINE, OverlayRect(xA, y, xA + o.width, y + o.height)) }
        y += chipBand
        for (c in first.filter { it.id != OverlayIds.NAV_CARD }) {
            place(c, y, xA, columnW)
            y += c.height + GAP
        }
        var yb = bodyBottom
        for (c in colA.filter { !it.top }.reversed()) {
            yb -= c.height
            place(c, yb, xA, columnW)
            yb -= GAP
        }

        // The second column of cards.
        val xB = xA + columnW + GAP
        var yt = bodyTop
        for (c in colB.filter { it.top }) {
            place(c, yt, xB, columnW)
            yt += c.height + GAP
        }
        var yr = bodyBottom
        for (c in colB.filter { !it.top }.reversed()) {
            yr -= c.height
            place(c, yr, xB, columnW)
            yr -= GAP
        }

        // Bottom up: the position column at the right, the scale bar's line at the left.
        val buttonX = safeR - MARGIN - BUTTON
        var by = if (side) bodyBottom else yb
        val ids = listOf(OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK)
        for ((k, id) in ids.take(i.buttons.coerceIn(1, 3)).withIndex()) {
            if (k < r.buttons) {
                by -= BUTTON
                shown(id, OverlayRect(buttonX, by, buttonX + BUTTON, by + BUTTON))
                by -= BUTTON_GAP
            } else hidden(id, if (k == 0) REASON_NO_ROOM else REASON_ORDER)
        }
        ln.bar?.let { b -> shown(OverlayIds.SCALE_BAR, OverlayRect(xA, yb - b.height, xA + b.width, yb)) }
        if (i.scaleBar != null && ln.bar == null) hidden(OverlayIds.SCALE_BAR, REASON_ORDER)
        var entries = 0
        ln.strip?.let { s ->
            val x = xA + (ln.beside ?: 0f)
            val bottom = if (ln.beside != null || ln.bar == null) yb else yb - ln.bar.height - GAP
            val whole = r.entries == i.strip.size
            out += OverlayElement(OverlayIds.STRIP, OverlayRect(x, bottom - s.height, x + s.width, bottom), if (whole) OverlayState.SHOWN else OverlayState.COMPACT, if (whole) null else REASON_ENTRIES)
            entries = r.entries
        }
        if (i.strip.isNotEmpty() && ln.strip == null) hidden(OverlayIds.STRIP, REASON_ORDER)
        return OverlayPlacement(out, r.buttons, entries, side, if (two) 2 else 1, stripRoom = if (entries > 0) ln.stripWidth else 0f)
    }

    /** A lower column's line at a rung: the [rung], the [line] and the top it may reach up to. */
    private class Choice(val rung: Rung, val line: Line, val top: Float)

    /**
     * T239 (Layout B): the placement with the cards [OverlayCard.drawn] where their own code drew them
     * ([OverlayInput.cardsDrawn]). Each card stays where it is; the chips start under the navigation card where it reaches
     * their column (the warning, which the app draws under the chips, keeps its place and does not push them); the online
     * column starts at the main column's top; the two lower columns stand on the highest drawn card, track panel or credit
     * line below them. The fits are column by column, since the cards that leave a column short are not the rule's to
     * shrink: the scale bar's line under the online column and the warning, and under the chips where it reaches their
     * edge (or short of it, beside them); the position column under the chips. The ladder walks as in [arrange], global,
     * for the first rung both columns fit; a column no rung can fit (a drawn card took its room, so walking further would
     * hide the rest and free nothing there) hides whole and the ladder walks for the other. The track bar fits by its row;
     * its panel takes what the chips under it and one position button leave ([OverlayPlacement.trackRoom]).
     */
    private fun arrangeDrawn(i: OverlayInput, rungs: List<Rung>, held: OverlayLatch?): OverlayPlacement {
        val out = ArrayList<OverlayElement>()
        val safeL = i.insetLeft
        val safeR = i.windowWidth - i.insetRight
        val shown = { id: String, rect: OverlayRect -> out += OverlayElement(id, rect, OverlayState.SHOWN) }
        val hidden = { id: String, why: String -> out += OverlayElement(id, null, OverlayState.HIDDEN, why) }
        val f = frame(i, out)

        // The cards where they are drawn; one up and not drawn yet is left out.
        val cards = listOfNotNull(
            i.navigation?.let { OverlayIds.NAV_CARD to it }, i.warning?.let { OverlayIds.WARNING_CARD to it },
            i.planning?.let { OverlayIds.PLANNING_CARD to it }, i.trip?.let { OverlayIds.TRIP_CARD to it }, i.update?.let { OverlayIds.UPDATE_CARD to it },
        ).filter { it.second.drawn != null }
        for ((id, c) in cards) {
            val r = c.drawn!!
            val why = when {
                r.height + 0.01f >= c.natural -> null
                r.height + 0.01f < c.headline -> REASON_HEADLINE_SCROLLS
                else -> REASON_SCROLLS
            }
            out += OverlayElement(id, r, if (why == null) OverlayState.SHOWN else OverlayState.COMPACT, why)
        }
        val nav = cards.firstOrNull { it.first == OverlayIds.NAV_CARD }?.second?.drawn
        val warning = cards.firstOrNull { it.first == OverlayIds.WARNING_CARD }?.second?.drawn
        val below = cards.filter { it.first != OverlayIds.NAV_CARD && it.first != OverlayIds.WARNING_CARD }.map { it.second.drawn!! } + listOfNotNull(i.panels)

        val chipsW = max(max(i.trackBar?.width ?: 0f, max(i.routes?.width ?: 0f, i.activity?.width ?: 0f)), max(i.compass?.width ?: 0f, COLUMN - EDGE)) + EDGE
        val side = held?.sideColumn ?: (i.windowHeight < COMPACT_HEIGHT && safeR - safeL - chipsW >= MIN_MAIN)
        val columnLeft = safeR - chipsW
        fun crossesRight(r: OverlayRect) = r.right > columnLeft && r.left < safeR
        val xA = safeL + EDGE
        val rightEdge = safeR - EDGE
        val buttonX = safeR - MARGIN - BUTTON
        val reserve = if (i.offCentre) BUTTON + GAP else 0f

        // The main column's top (under the navigation card) and bottom; the right column's on a phone on its side.
        val topY = max(f.bodyTop, nav?.let { it.bottom + GAP } ?: 0f)
        val yb = min(f.bodyBottom, below.minOfOrNull { it.top - GAP } ?: f.bodyBottom)
        val colTop = if (side) max(f.bodyTop, nav?.takeIf(::crossesRight)?.let { it.bottom + GAP } ?: 0f) else topY
        val by = if (side) min(f.bodyBottom, below.filter(::crossesRight).minOfOrNull { it.top - GAP } ?: f.bodyBottom) else yb

        // The chips keep their size while they fit (the track bar by its row) and hide rather than reach below [by].
        val chips = ArrayList<Pair<String, OverlaySize>>()
        fun chipsH() = if (chips.isEmpty()) 0f else chips.sumOf { it.second.height.toDouble() }.toFloat() + GAP * (chips.size - 1)
        var chipTop = colTop
        fun tryChip(id: String, s: OverlaySize?): Boolean {
            if (s == null) return false
            chips += id to s
            if (chipTop + chipsH() + reserve <= by) return true
            chips.removeAt(chips.size - 1)
            hidden(id, REASON_NO_ROOM)
            return false
        }
        val row = i.trackBar
        val barShown = tryChip(OverlayIds.TRACK_BAR, row)
        // The Routes chip sits under the bar: without the bar it hides with it.
        if (row == null || barShown) tryChip(OverlayIds.ROUTES, i.routes) else if (i.routes != null) hidden(OverlayIds.ROUTES, REASON_NO_ROOM)
        tryChip(OverlayIds.ACTIVITY, i.activity) // T263: under the Routes chip, before the compass
        tryChip(OverlayIds.COMPASS, i.compass)

        // The online column at the main column's top; where it reaches the chips (upright, narrow), they go under it.
        var online: OverlaySize? = null
        i.online?.let { o ->
            val collide = !side && chips.isNotEmpty() && xA + o.width + GAP > rightEdge - chips.maxOf { it.second.width }
            val ok = o.width <= (if (side) columnLeft else safeR) - safeL - EDGE * 2 && topY + o.height + GAP <= yb &&
                (!collide || topY + o.height + GAP + chipsH() + reserve <= by)
            if (ok) {
                online = o
                if (collide) chipTop = topY + o.height + GAP
            } else hidden(OverlayIds.ONLINE, REASON_NO_ROOM)
        }

        // The track bar's panel: what the chips under it and one position button (or the off-centre one) leave.
        var trackRoom = 0f
        if (barShown && row != null) {
            val keep = max(if (i.buttons >= 1) BUTTON + GAP else 0f, reserve)
            var left = (by - (chipTop + chipsH() + keep)).coerceAtLeast(0f)
            // T263: the activity chip yields to an open panel it would leave short (the picker it opens hangs there).
            val activity = chips.indexOfFirst { it.first == OverlayIds.ACTIVITY }
            if (activity >= 0 && i.trackPanel > left) {
                chips.removeAt(activity)
                hidden(OverlayIds.ACTIVITY, REASON_NO_ROOM)
                left = (by - (chipTop + chipsH() + keep)).coerceAtLeast(0f)
            }
            chips[0] = OverlayIds.TRACK_BAR to OverlaySize(row.width, row.height + min(i.trackPanel, left))
            trackRoom = row.height + left
        }
        val chipsBottom = chipTop + chipsH()
        val chipsLeft = if (chips.isEmpty()) safeR else rightEdge - chips.maxOf { it.second.width }

        // The lower columns at each rung.
        val lineW = if (side) columnLeft - safeL - EDGE * 2 else safeR - safeL - EDGE - STRIP_RESERVE
        fun reach(ln: Line) = xA + max(ln.bar?.width ?: 0f, (ln.beside ?: 0f) + (ln.strip?.width ?: 0f))
        fun leftTop(ln: Line): Float {
            val right = reach(ln)
            var t = max(topY + (online?.let { it.height + GAP } ?: 0f), warning?.takeIf { it.left < right && it.right > xA }?.let { it.bottom + GAP } ?: 0f)
            if (!side && chips.isNotEmpty() && right + GAP > chipsLeft) t = max(t, chipsBottom + GAP)
            return t
        }
        fun lineAt(r: Rung): Choice? {
            val full = line(i, r, lineW)
            if (full.bar == null && full.strip == null && full.height < NO_FIT) return Choice(r, full, yb)
            if (full.height < NO_FIT) leftTop(full).let { t -> if (yb - full.height >= t) return Choice(r, full, t) }
            // Upright with the chips in the way: short of their edge, beside them.
            val shortW = chipsLeft - GAP - xA
            if (!side && chips.isNotEmpty() && shortW > 0f && shortW < lineW) {
                val ln = line(i, r, shortW)
                if (ln.height < NO_FIT) leftTop(ln).let { t -> if (yb - ln.height >= t) return Choice(r, ln, t) }
            }
            return null
        }
        val rightTop = max(
            if (chips.isEmpty()) colTop else chipsBottom + GAP,
            warning?.takeIf { it.right > buttonX && it.left < safeR - MARGIN }?.let { it.bottom + GAP } ?: 0f,
        )
        fun rightFits(r: Rung) = r.buttons <= 0 || BUTTON * r.buttons + BUTTON_GAP * (r.buttons - 1) <= by - rightTop
        val rightPossible = rightFits(Rung(1, false, 0))
        // The left column at its least: the elevation alone; where not even that fits, the scale bar alone, which the ladder
        // would take first but whose going frees nothing there.
        val stripPossible = i.strip.isNotEmpty() && rungs.any { it.entries > 0 && lineAt(it) != null }
        val barAlone = if (!stripPossible && i.scaleBar != null) lineAt(Rung(1, true, 0)) else null
        val leftPossible = stripPossible || barAlone != null
        val none = Choice(Rung(0, false, 0), Line(0f, null, null, null), yb)
        val (buttons, pick) = when {
            stripPossible && rightPossible -> rungs.first { rightFits(it) && lineAt(it) != null }.let { it.buttons to lineAt(it)!! }
            stripPossible -> 0 to lineAt(rungs.first { lineAt(it) != null })!!
            rightPossible -> rungs.first { rightFits(it) }.buttons to (barAlone ?: none)
            else -> 0 to (barAlone ?: none)
        }

        // Top down: the chips at the right, the online column at the left.
        var cy = chipTop
        for ((id, s) in chips) {
            shown(id, OverlayRect(rightEdge - s.width, cy, rightEdge, cy + s.height))
            cy += s.height + GAP
        }
        online?.let { o -> shown(OverlayIds.ONLINE, OverlayRect(xA, topY, xA + o.width, topY + o.height)) }
        // Bottom up: the position column at the right, the scale bar's line at the left.
        var bY = by
        for ((k, id) in listOf(OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK).take(i.buttons.coerceIn(1, 3)).withIndex()) {
            if (k < buttons) {
                bY -= BUTTON
                shown(id, OverlayRect(buttonX, bY, buttonX + BUTTON, bY + BUTTON))
                bY -= BUTTON_GAP
            } else hidden(id, if (k == 0 || !rightPossible) REASON_NO_ROOM else REASON_ORDER)
        }
        val ln = pick.line
        val leftWhy = if (stripPossible) REASON_ORDER else REASON_NO_ROOM
        ln.bar?.let { b -> shown(OverlayIds.SCALE_BAR, OverlayRect(xA, yb - b.height, xA + b.width, yb)) }
        if (i.scaleBar != null && ln.bar == null) hidden(OverlayIds.SCALE_BAR, leftWhy)
        var entries = 0
        ln.strip?.let { s ->
            val x = xA + (ln.beside ?: 0f)
            val bottom = if (ln.beside != null || ln.bar == null) yb else yb - ln.bar.height - GAP
            val whole = pick.rung.entries == i.strip.size
            out += OverlayElement(OverlayIds.STRIP, OverlayRect(x, bottom - s.height, x + s.width, bottom), if (whole) OverlayState.SHOWN else OverlayState.COMPACT, if (whole) null else REASON_ENTRIES)
            entries = pick.rung.entries
        }
        if (i.strip.isNotEmpty() && ln.strip == null) hidden(OverlayIds.STRIP, leftWhy)
        return OverlayPlacement(out, buttons, entries, side, 1, trackRoom = trackRoom, stripRoom = if (entries > 0) ln.stripWidth else 0f)
    }
}
