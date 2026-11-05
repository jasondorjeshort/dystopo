package io.github.jasondorjeshort.dystopo.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets

/**
 * T65: the right-hand chip column under the top row (search, Settings, Packs) stacks instead of hiding:
 * the track controls (the Tracks chip and, when open, the tracks list) start below the navigation card
 * while navigating, and the Routes chip ([routesChip], used by the trips UI) sits just below the track
 * controls, so neither the open tracks list nor the navigation card covers it. Edges are measured from
 * the window top (status bar included), 0 when not shown.
 */
object TopChips {
    /** Bottom edge of the navigation stats card. */
    var navCardBottom by mutableStateOf(0.dp)

    /** T128: the navigation card's header row height (0 when not navigating); not Compose state, only reported. */
    @Volatile var navHeaderDp = 0f

    /** Top and bottom edges of the track controls column. */
    var trackColumnTop by mutableStateOf(0.dp)
    var trackColumnBottom by mutableStateOf(0.dp)

    /** Bottom edge of [c] (or its top with [top]) from the window top. */
    fun edge(c: LayoutCoordinates, density: Density, top: Boolean = false): Dp =
        with(density) { (c.positionInRoot().y + if (top) 0f else c.size.height.toFloat()).toDp() }

    /**
     * Where the track controls start: below the Packs button, or below the navigation card while it shows. T146: placed in
     * the layout pass that measured the card ([followNavCard]), so a turn text that wraps moves the Waypoint and Tracks
     * chips down in the same frame (T137 saw them a layout pass late in about 2 of 15 samples).
     */
    fun trackColumn(): Modifier = followNavCard(Chip.TRACKS, TRACKS_TOP).composed {
        val inset = with(LocalDensity.current) { COLUMN_PADDING.toPx() }
        DisposableEffect(Unit) { onDispose { trackColumnPx = null; publishRightLeft() } }
        // T224: the column's own rectangle (its caller adds [COLUMN_PADDING] on both sides, inside this modifier).
        Modifier.onGloballyPositioned { c ->
            trackColumnPx = px(c).also { it[0] += inset; it[2] -= inset }
            publishRightLeft()
        }
    }

    /**
     * The Routes chip's place (top-right): just below the track controls once they have been measured. T110: it also
     * measures the chip ([routesChipBottom]; 0 once the chip leaves the screen), the lowest of the top-right chips, which
     * the recentre button must not reach up to.
     */
    fun routesChip(): Modifier =
        Modifier.padding(end = 8.dp).then(followNavCard(Chip.ROUTES, ROUTES_TOP)) // T146: below the track controls in the same pass
            .composed {
                val density = LocalDensity.current
                DisposableEffect(Unit) { onDispose { routesChipBottom = 0.dp; routesChipPx = null; publishRightLeft() } }
                Modifier.onGloballyPositioned { c ->
                    edge(c, density).let { if (it != routesChipBottom) routesChipBottom = it }
                    routesChipPx = px(c) // T224
                    publishRightLeft()
                }
            }

    /** T110: bottom edge of the Routes chip from the window top (0 while it is not shown). */
    var routesChipBottom by mutableStateOf(0.dp)

    /**
     * T110: the lowest bottom edge of what sits at the top right (the track controls with their open list, the Routes
     * chip, the navigation card), from the window top; 0 before anything was measured. The recentre button stays below it.
     */
    val topRightBottom: Dp get() = maxOf(trackColumnBottom, routesChipBottom, navCardBottom)

    /** T91: left edge of the Settings and Packs row, and bottom edge of the search column (0 until measured). */
    var topRowLeft by mutableStateOf(0.dp)
    /** T91: right edge of the online indicator in px (0 when it is hidden). */
    var onlineRightPx by mutableStateOf(0f)
    /** T115: bottom edge of the online indicator column in px from the window top (0 when it is hidden). */
    var onlineBottomPx by mutableStateOf(0f)
    var searchBottom by mutableStateOf(0.dp)

    /** T91: the search box's width: 220 dp, or less so it ends before the Settings chip. */
    fun searchWidth(): Dp = if (topRowLeft > 0.dp) minOf(SEARCH_WIDTH, topRowLeft - 8.dp - GAP).coerceAtLeast(96.dp) else SEARCH_WIDTH

    /**
     * T91: where the online indicator starts: just below the search column (and its "Indexing places" line). T137 (T128's
     * observation): while navigating, below the navigation card, which spans the top of the map from the same height and
     * covered the indicator's chip; the scale bar's T128 rule (below the card and below this column) is unchanged.
     */
    fun onlineColumn(): Modifier = Modifier.padding(start = 8.dp).then(followNavCard(Chip.ONLINE, ONLINE_TOP)) // T146: same pass as the card

    /** T137 (pure, `TopChipsTest`): the online column's top from the window top, or null before anything was measured. */
    fun onlineTop(searchBottom: Dp, navCardBottom: Dp): Dp? = when {
        navCardBottom > 0.dp -> maxOf(searchBottom + 4.dp, navCardBottom + GAP)
        searchBottom > 0.dp -> searchBottom + 4.dp
        else -> null
    }

    /**
     * T146 (pure, `TopChipsTest`): the top in px from the window top of a chip column ([Chip]) that follows the
     * navigation card: [onlineTop]'s rule for the online indicator, the card's bottom plus the gap for the track
     * controls, the track controls' bottom plus the gap for the Routes chip, and [fallbackPx] (under the status bar)
     * before what it follows was measured. Edges are px from the window top, 0 when not shown.
     */
    fun chipTopPx(kind: Chip, navCardBottomPx: Int, searchBottomPx: Int, trackBottomPx: Int, fallbackPx: Int, density: Float): Int {
        fun px(d: Dp) = Math.round(d.value * density)
        return when (kind) {
            Chip.ONLINE -> onlineTop((searchBottomPx / density).dp, (navCardBottomPx / density).dp)?.let { px(it) } ?: fallbackPx
            Chip.TRACKS -> if (navCardBottomPx > 0) navCardBottomPx + px(GAP) else fallbackPx
            Chip.ROUTES -> if (trackBottomPx > 0) trackBottomPx + px(GAP) else fallbackPx
        }
    }

    /** T146: the chip columns that follow the navigation card in the same layout pass. */
    enum class Chip { ONLINE, TRACKS, ROUTES }

    /** T224: why a chip column is not drawn: the card above it left it no room above the credit line. */
    const val NO_ROOM_ABOVE_CREDIT = "noRoomAboveCredit"

    /**
     * T224 (pure, `TopChipsTest`; the clamp of [chipTopPx]'s columns against the credit line): the height left to a column
     * that starts at [topPx], down to [gapPx] above the credit line ([creditFromBottomPx] from the bottom of a window
     * [windowPx] tall; with none measured yet, down to the window's bottom).
     */
    fun chipRoomPx(windowPx: Int, creditFromBottomPx: Int, topPx: Int, gapPx: Int): Int =
        windowPx - (if (creditFromBottomPx > 0) creditFromBottomPx + gapPx else 0) - topPx

    /** T224 (pure): whether a column's own height fits its room. A chip that does not fit hides; it never shrinks and never goes under the line. */
    fun chipFits(roomPx: Int, ownPx: Int): Boolean = roomPx > 0 && ownPx <= roomPx

    /** T224 (pure): the columns under the card: the track bar and the Routes chip with their gaps (px; 0 when neither is up). */
    fun stackPx(tracksPx: Int, routesPx: Int, gapPx: Int): Int =
        (if (tracksPx > 0) gapPx + tracksPx else 0) + (if (routesPx > 0) gapPx + routesPx else 0)

    /** T224: why each column is hidden right now (`GET /layout`, `GET /nav` `layout.chipsHidden`); absent when drawn. */
    private val hiddenBecause = java.util.concurrent.ConcurrentHashMap<Chip, String>()

    fun hiddenReason(kind: Chip): String? = hiddenBecause[kind]

    private val onlineOwn = mutableStateOf(0)
    private val tracksOwn = mutableStateOf(0)
    private val routesOwn = mutableStateOf(0)
    private fun own(kind: Chip) = when (kind) { Chip.ONLINE -> onlineOwn; Chip.TRACKS -> tracksOwn; Chip.ROUTES -> routesOwn }

    /** T224: a column's own height as last measured (px), kept while it is hidden, 0 once it leaves the composition. Compose state. */
    fun ownHeightPx(kind: Chip): Int = own(kind).value

    /** T224: the height the right-hand chips want under the navigation card ([stackPx] over [ownHeightPx]). */
    fun rightStackPx(density: Float): Int = stackPx(ownHeightPx(Chip.TRACKS), ownHeightPx(Chip.ROUTES), Math.round(GAP.value * density))

    /** T224: a column's own width as last measured (px), for `GET /layout`'s function. */
    private val ownWidths = java.util.EnumMap<Chip, Int>(Chip::class.java)
    fun ownWidthPx(kind: Chip): Int = ownWidths[kind] ?: 0

    internal fun chipMeasured(kind: Chip, ownPx: Int, widthPx: Int) {
        own(kind).let { if (it.value != ownPx) it.value = ownPx }
        ownWidths[kind] = widthPx
    }

    internal fun chipShown(kind: Chip) {
        if (hiddenBecause.remove(kind) != null && kind == Chip.TRACKS) for (f in followers.toList()) if (f.kind == Chip.ROUTES) f.invalidateMeasurement()
    }

    /** T224: [kind] is not placed this pass: what it published goes, since its position callbacks do not run unplaced. */
    internal fun chipHidden(kind: Chip, reason: String) {
        hiddenBecause[kind] = reason
        when (kind) {
            Chip.ONLINE -> MapInsets.onlineChipsHidden()
            Chip.TRACKS -> {
                if (trackColumnTop != 0.dp) trackColumnTop = 0.dp
                if (trackColumnBottom != 0.dp) trackColumnBottom = 0.dp
                trackColumnPx = null
                trackMeasured(0)
            }
            Chip.ROUTES -> {
                if (routesChipBottom != 0.dp) routesChipBottom = 0.dp
                routesChipPx = null
            }
        }
        publishRightLeft()
    }

    /** T224: the track bar's chip row's height as measured in this pass (px): the clamp is on the row, and an open panel under it gets what is left. */
    var trackRowPx = 0
        private set

    internal fun trackRowMeasured(px: Int) { trackRowPx = px }

    /** T224: on the track bar's chip row; see [trackRowPx]. */
    fun trackRow(): Modifier = Modifier.then(TrackRowElement)

    /** T224: rectangles in px from the window's top left, for `GET /layout`; null while not drawn. */
    @Volatile var trackColumnPx: FloatArray? = null
    @Volatile var routesChipPx: FloatArray? = null
    @Volatile var topRowPx: FloatArray? = null

    /** T224: the left edge of the right-hand chips (px; 0 when none shows), which bounds the readout strip. Compose state. */
    var rightColumnLeftPx by mutableStateOf(0f)
        private set

    private fun publishRightLeft() {
        val left = listOfNotNull(trackColumnPx?.get(0), routesChipPx?.get(0)).minOrNull() ?: 0f
        if (left != rightColumnLeftPx) rightColumnLeftPx = left
    }

    /** T224: [c]'s bounds as `left, top, right, bottom` in px from the window's top left. */
    fun px(c: LayoutCoordinates): FloatArray = c.boundsInRoot().let { floatArrayOf(it.left, it.top, it.right, it.bottom) }

    /**
     * T146: the navigation card's bottom edge (px from the window top, 0 while hidden) as placed in the current layout
     * pass, and the track controls' bottom as measured in it. Not Compose state: a change remeasures the chip columns
     * that follow ([followers]) inside the same `measureAndLayout`, where Compose state written during layout reaches
     * them only in a later frame (T137's lag). Main thread.
     */
    var navCardBottomPx = 0
        private set
    var trackBottomPx = 0
        private set
    private val followers = LinkedHashSet<FollowNode>()
    /** T146: the last top each follower was measured at (px), for `GET /nav` `layout.follow`. */
    private val followTops = java.util.EnumMap<Chip, Int>(Chip::class.java)
    @Volatile private var followJson: Map<String, Any?> = emptyMap()

    internal fun navCardPlaced(bottomPx: Int) {
        if (bottomPx == navCardBottomPx) return
        navCardBottomPx = bottomPx
        for (f in followers.toList()) if (f.kind != Chip.ROUTES) f.invalidateMeasurement()
        publishFollow()
    }

    internal fun trackMeasured(bottomPx: Int) {
        if (bottomPx == trackBottomPx) return
        trackBottomPx = bottomPx
        for (f in followers.toList()) if (f.kind == Chip.ROUTES) f.invalidateMeasurement()
        publishFollow()
    }

    internal fun follower(f: FollowNode, attached: Boolean) {
        if (attached) followers += f
        else {
            followers -= f
            followTops.remove(f.kind)
            hiddenBecause.remove(f.kind) // T224
            own(f.kind).value = 0
            if (f.kind == Chip.TRACKS) {
                trackRowPx = 0
                trackMeasured(0)
            }
        }
    }

    internal fun followMeasured(kind: Chip, topPx: Int) {
        if (followTops.put(kind, topPx) != topPx) publishFollow()
    }

    private fun publishFollow() {
        followJson = linkedMapOf<String, Any?>("navCardBottomPx" to navCardBottomPx, "trackBottomPx" to trackBottomPx) +
            followTops.entries.associate { (k, v) -> k.name.lowercase() + "TopPx" to v }
    }

    /**
     * T146: places the content of [chip] below what it follows, measured in the same layout pass ([chipTopPx]);
     * [fallbackTop] under the status bar before that was measured.
     */
    private fun followNavCard(chip: Chip, fallbackTop: Dp): Modifier = Modifier.composed {
        val density = LocalDensity.current
        val fallback = WindowInsets.statusBars.getTop(density) + with(density) { fallbackTop.roundToPx() }
        FollowElement(chip, fallback)
    }

    /** T137: the navigation card's rectangle in px from the window's top left (`left, top, right, bottom`); null when hidden. */
    @Volatile var navCardPx: FloatArray? = null

    fun json(): Map<String, Any?> = linkedMapOf(
        "topRowLeftDp" to topRowLeft.value, "searchBottomDp" to searchBottom.value, "searchWidthDp" to searchWidth().value,
        "onlineBottomPx" to onlineBottomPx, // T115
        "navCardBottomDp" to navCardBottom.value, "navHeaderDp" to navHeaderDp /* T128 */, "trackColumnTopDp" to trackColumnTop.value, "trackColumnBottomDp" to trackColumnBottom.value,
        "routesChipTopDp" to if (trackColumnBottom > 0.dp) (trackColumnBottom + GAP).value else null,
        "routesChipBottomDp" to routesChipBottom.value, "topRightBottomDp" to topRightBottom.value, // T110
        // The track controls clear the navigation card when they start below its bottom edge.
        "tracksClearOfNavCard" to (navCardBottom == 0.dp || trackColumnTop >= navCardBottom || hiddenReason(Chip.TRACKS) != null), // T224: hidden is clear
        // T137: the navigation card's and the online indicator column's rectangles (px, left, top, right, bottom; null when
        // hidden) and whether they overlap; the online column starts below the card while navigating (onlineColumn()).
        "navCardPx" to navCardPx?.map { Math.round(it) },
        "onlineChipsPx" to MapInsets.onlineChips?.map { Math.round(it) },
        "onlineClearOfNavCard" to !overlap(navCardPx, MapInsets.onlineChips),
        "speakerButtonPx" to io.github.jasondorjeshort.dystopo.app.nav.SpeakerLayout.boundsPx?.map { Math.round(it) }, // T137: the mute
        // T122: the navigation card's turn text as last laid out (lines, cut, fontSp), for checks at font scale 2.0.
        "navTurnText" to io.github.jasondorjeshort.dystopo.app.nav.TurnText.last,
        // T146: the edges the chip columns followed in the last layout pass and the tops they were measured at (px).
        "follow" to followJson,
        "chipsHidden" to hiddenBecause.entries.associate { (k, v) -> k.name.lowercase() to v }, // T224: why a column is not drawn
        "drawCheck" to drawCheck(),
    )

    /**
     * T146: counted as the navigation card is drawn, after the frame's layout and its position callbacks, so a frame drawn
     * with the card over a chip column is counted even when the next frame fixes it (a `GET /nav` sample catches such a
     * frame only by chance): `frames` drawn, `onlineOverlapFrames` with the online indicator under the card,
     * `tracksOverlapFrames` with the track controls starting above its bottom edge (1 px tolerance). Since navigation started.
     */
    private var drawFrames = 0
    private var onlineOverlapFrames = 0
    private var tracksOverlapFrames = 0
    @Volatile private var lastOverlap: Map<String, Any?>? = null

    internal fun navCardDrawn(density: Float) {
        val card = navCardPx ?: return
        drawFrames++
        if (overlap(card, MapInsets.onlineChips)) {
            onlineOverlapFrames++
            lastOverlap = mapOf("frame" to drawFrames, "navCardPx" to card.map { Math.round(it) }, "onlineChipsPx" to MapInsets.onlineChips?.map { Math.round(it) })
        }
        val tracksTop = trackColumnTop.value * density
        if (tracksTop > 0f && tracksTop < card[3] - 1f) tracksOverlapFrames++
    }

    /** T146: the card came up (a navigation started): the counts start again. */
    internal fun navCardShown() {
        drawFrames = 0; onlineOverlapFrames = 0; tracksOverlapFrames = 0; lastOverlap = null
    }

    private fun drawCheck(): Map<String, Any?> = linkedMapOf(
        "frames" to drawFrames, "onlineOverlapFrames" to onlineOverlapFrames, "tracksOverlapFrames" to tracksOverlapFrames, "lastOverlap" to lastOverlap,
    )

    /** T137 (pure): true when two `left, top, right, bottom` rectangles share any area; false when either is null. */
    fun overlap(a: FloatArray?, b: FloatArray?): Boolean =
        a != null && b != null && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    internal val GAP = 6.dp
    private val SEARCH_WIDTH = 220.dp

    /** T224: the padding `tracking/TrackControls.kt` gives the track column on both sides. */
    private val COLUMN_PADDING = 8.dp

    /**
     * T91: the font scale the floating map chips follow at most. They sit in fixed places around the map, and at
     * the system's 2.0 the Settings chip covered the search box and the online indicator the Waypoint chip; the
     * sheets, cards and lists keep the full scale.
     */
    const val MAX_FONT_SCALE = 1.3f
    private val TRACKS_TOP = 64.dp
    private val ROUTES_TOP = 112.dp
    private val ONLINE_TOP = 60.dp
}

/** T146: [TopChips.followNavCard]'s element. */
private class FollowElement(val kind: TopChips.Chip, val fallbackPx: Int) : ModifierNodeElement<FollowNode>() {
    override fun create() = FollowNode(kind, fallbackPx)
    override fun update(node: FollowNode) {
        if (node.kind != kind || node.fallbackPx != fallbackPx) {
            node.kind = kind
            node.fallbackPx = fallbackPx
            node.invalidateMeasurement()
        }
    }
    override fun equals(other: Any?) = other is FollowElement && other.kind == kind && other.fallbackPx == fallbackPx
    override fun hashCode() = kind.hashCode() * 31 + fallbackPx
}

/**
 * T146: a chip column's top offset, read in measure from [TopChips.navCardBottomPx] (and [TopChips.trackBottomPx] for
 * the Routes chip), which the card's [navCardEdge] writes while it is placed; the writer remeasures this node in the
 * same pass. The track controls report their bottom as they are measured, for the Routes chip below them. The column's
 * parent spans the window from its top (the map screen's boxes), so a top in the parent is a top in the window.
 *
 * T224: a column with no room above the credit line hides (nothing placed; [TopChips.chipFits]) rather than sit under it,
 * read from [AttributionInsets.bottom] in this measure. The Routes chip hides with the track bar it sits under. The track
 * column is measured within its room, so an open panel under the chips (the activity picker, the recording's lines) gets
 * what is left; the clamp itself is on its chip row.
 */
internal class FollowNode(var kind: TopChips.Chip, var fallbackPx: Int) : Modifier.Node(), LayoutModifierNode {
    override fun onAttach() = TopChips.follower(this, true)
    override fun onDetach() = TopChips.follower(this, false)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val top = TopChips.chipTopPx(kind, TopChips.navCardBottomPx, TopChips.searchBottom.roundToPx(), TopChips.trackBottomPx, fallbackPx, density)
        val room = TopChips.chipRoomPx(constraints.maxHeight, AttributionInsets.bottom.roundToPx(), top, TopChips.GAP.roundToPx())
        val maxHeight = if (kind == TopChips.Chip.TRACKS) room.coerceAtLeast(0) else Constraints.Infinity
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = maxHeight))
        val own = if (kind == TopChips.Chip.TRACKS && TopChips.trackRowPx > 0) TopChips.trackRowPx else p.height
        TopChips.followMeasured(kind, top)
        val fits = TopChips.chipFits(room, own) && !(kind == TopChips.Chip.ROUTES && TopChips.hiddenReason(TopChips.Chip.TRACKS) != null)
        TopChips.chipMeasured(kind, if (fits) maxOf(own, p.height) else own, p.width)
        if (!fits) {
            TopChips.chipHidden(kind, TopChips.NO_ROOM_ABOVE_CREDIT)
            return layout(0, 0) {}
        }
        TopChips.chipShown(kind)
        if (kind == TopChips.Chip.TRACKS) TopChips.trackMeasured(top + p.height)
        return layout(constraints.constrainWidth(p.width), constraints.constrainHeight(p.height + top)) { p.place(0, top) }
    }
}

/** T224: [TopChips.trackRow]'s element. */
private object TrackRowElement : ModifierNodeElement<TrackRowNode>() {
    override fun create() = TrackRowNode()
    override fun update(node: TrackRowNode) {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7224
}

/** T224: measures the track bar's chip row free of the height left to it, for [FollowNode] above it in the same pass. */
private class TrackRowNode : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        TopChips.trackRowMeasured(p.height)
        return layout(p.width, constraints.constrainHeight(p.height)) { p.place(0, 0) }
    }
}

/**
 * T146: on the navigation card (its outer bounds): tells [TopChips] its bottom edge as it is placed, so the chip columns
 * below it are measured against this pass's card; 0 when the card leaves.
 */
fun Modifier.navCardEdge(): Modifier = this.then(NavCardEdgeElement)

private object NavCardEdgeElement : ModifierNodeElement<NavCardEdgeNode>() {
    override fun create() = NavCardEdgeNode()
    override fun update(node: NavCardEdgeNode) {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7146
}

private class NavCardEdgeNode : Modifier.Node(), LayoutModifierNode {
    override fun onDetach() = TopChips.navCardPlaced(0)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) {
            p.place(0, 0)
            coordinates?.let { c -> TopChips.navCardPlaced(Math.round(c.positionInRoot().y) + p.height) }
        }
    }
}

/** T91: draws [content] (floating map chips) with the font scale capped at [TopChips.MAX_FONT_SCALE]. */
@Composable
fun MapChrome(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= TopChips.MAX_FONT_SCALE) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, TopChips.MAX_FONT_SCALE)) { content() }
}
