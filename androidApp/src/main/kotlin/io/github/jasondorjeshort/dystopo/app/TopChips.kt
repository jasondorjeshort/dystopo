package io.github.jasondorjeshort.dystopo.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.ui.OverlayIds

/**
 * T65: the chips under the top row (search, Settings, Offline maps): the online indicator column at the left, the track
 * controls (the Waypoint and Tracks chips and, when open, their panel) and the Routes chip at the right. Edges are
 * measured from the window top (status bar included), 0 when not shown.
 *
 * T239 (Layout B): where they go is the layout rule's (`app/MapLayout.kt`, [MapLayout.RULED]): each column's
 * `Modifier.control` node draws it at the rule's rectangle (under the navigation card while it shows, the Routes chip
 * under the track bar) or not at all when the rule hides it (T146: in the layout pass that measured the card). What is
 * left here is what other code reads of them: the edges below (the warning card, the camera fits, the navigation card's
 * cap), the columns' own sizes and why one is hidden.
 */
object TopChips {
    /** Bottom edge of the navigation stats card. */
    var navCardBottom by mutableStateOf(0.dp)

    /** T128: the navigation card's header row height (0 when not navigating); not Compose state, only reported. */
    @Volatile var navHeaderDp = 0f

    /** Top and bottom edges of the track controls column. */
    var trackColumnTop by mutableStateOf(0.dp)
    var trackColumnBottom by mutableStateOf(0.dp)

    /** T239: the track column's rectangle as drawn (px from the window's top left; null while hidden), for the checks below. */
    @Volatile var trackColumnPx: FloatArray? = null
        private set

    /** T239: the track column was drawn at [c] (inside its control node, so where the rule put it). */
    fun trackColumnAt(c: LayoutCoordinates, density: Density) {
        edge(c, density, top = true).let { if (it != trackColumnTop) trackColumnTop = it }
        edge(c, density).let { if (it != trackColumnBottom) trackColumnBottom = it }
        trackColumnPx = c.boundsInRoot().let { floatArrayOf(it.left, it.top, it.right, it.bottom) }
    }

    /** Bottom edge of [c] (or its top with [top]) from the window top. */
    fun edge(c: LayoutCoordinates, density: Density, top: Boolean = false): Dp =
        with(density) { (c.positionInRoot().y + if (top) 0f else c.size.height.toFloat()).toDp() }

    /**
     * T110: the Routes chip's bottom edge as drawn ([routesChipBottom]; 0 once the chip leaves the screen), the lowest of
     * the top-right chips. T239: on the chip inside its `Modifier.control` node, so the edge is where the rule drew it.
     */
    fun routesChipEdges(): Modifier = Modifier.composed {
        val density = LocalDensity.current
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { routesChipBottom = 0.dp } }
        Modifier.onGloballyPositioned { c -> edge(c, density).let { if (it != routesChipBottom) routesChipBottom = it } }
    }

    /** T110: bottom edge of the Routes chip from the window top (0 while it is not shown). */
    var routesChipBottom by mutableStateOf(0.dp)

    /**
     * T110: the lowest bottom edge of what sits at the top right (the track controls with their open list, the Routes
     * chip, the navigation card), from the window top; 0 before anything was measured.
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

    /** T146: the chip columns the layout places. T239: by the rule; the names are the columns' manifest ids. */
    enum class Chip(val id: String) {
        ONLINE(OverlayIds.ONLINE), TRACKS(OverlayIds.TRACK_BAR), ROUTES(OverlayIds.ROUTES);

        val control: String get() = OverlayIds.NAMES.getValue(id)
    }

    /** T224 (pure, `TopChipsTest`): the columns under the card: the track bar and the Routes chip with their gaps (px; 0 when neither is up). */
    fun stackPx(tracksPx: Int, routesPx: Int, gapPx: Int): Int =
        (if (tracksPx > 0) gapPx + tracksPx else 0) + (if (routesPx > 0) gapPx + routesPx else 0)

    /** T224: why each column is hidden right now (`GET /nav` `layout.chipsHidden`); absent when drawn. T239: the rule's reason. */
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

    private val ownWidths = java.util.EnumMap<Chip, Int>(Chip::class.java)
    fun ownWidthPx(kind: Chip): Int = ownWidths[kind] ?: 0

    private fun kindOf(control: String): Chip? = Chip.entries.firstOrNull { it.control == control }

    /** T239: a column's `Modifier.control` node measured it ([MapLayout.controls]); null: it left the composition. Main thread. */
    internal fun measured(control: String, size: IntSize?) {
        val kind = kindOf(control) ?: return
        val h = size?.height ?: 0
        own(kind).let { if (it.value != h) it.value = h }
        if (size == null) {
            ownWidths.remove(kind)
            hiddenBecause.remove(kind)
            when (kind) {
                Chip.ONLINE -> MapInsets.onlineChipsHidden()
                Chip.TRACKS -> clearTrackEdges()
                Chip.ROUTES -> if (routesChipBottom != 0.dp) routesChipBottom = 0.dp
            }
        } else ownWidths[kind] = size.width
    }

    private fun clearTrackEdges() {
        if (trackColumnTop != 0.dp) trackColumnTop = 0.dp
        if (trackColumnBottom != 0.dp) trackColumnBottom = 0.dp
        trackColumnPx = null
    }

    /** T239: the rule hides the column [control] ([reason]): what its position callbacks published goes, since they do not run unplaced. */
    internal fun ruledHidden(control: String, reason: String?) {
        val kind = kindOf(control) ?: return
        hiddenBecause[kind] = reason ?: "noRoom"
        when (kind) {
            Chip.ONLINE -> MapInsets.onlineChipsHidden()
            Chip.TRACKS -> clearTrackEdges()
            Chip.ROUTES -> if (routesChipBottom != 0.dp) routesChipBottom = 0.dp
        }
    }

    internal fun ruledShown(control: String) {
        kindOf(control)?.let { hiddenBecause.remove(it) }
    }

    /**
     * T146: the navigation card's bottom edge (px from the window top, 0 while hidden) as placed in the current layout
     * pass, for `GET /nav` `layout.follow`. T239: the chips follow the card through the rule, which reads the card's own node.
     */
    var navCardBottomPx = 0
        private set

    internal fun navCardPlaced(bottomPx: Int) {
        navCardBottomPx = bottomPx
    }

    /** T137: the navigation card's rectangle in px from the window's top left (`left, top, right, bottom`); null when hidden. */
    @Volatile var navCardPx: FloatArray? = null

    /** T146, T239: the edge the chips follow and the tops the rule drew the columns at (px; absent when hidden). */
    private fun followJson(): Map<String, Any?> = linkedMapOf<String, Any?>("navCardBottomPx" to navCardBottomPx) +
        Chip.entries.mapNotNull { k -> MapLayout.ruledTopsPx[k.control]?.let { k.name.lowercase() + "TopPx" to it } }

    fun json(): Map<String, Any?> = linkedMapOf(
        "topRowLeftDp" to topRowLeft.value, "searchBottomDp" to searchBottom.value, "searchWidthDp" to searchWidth().value,
        "onlineBottomPx" to onlineBottomPx, // T115
        "navCardBottomDp" to navCardBottom.value, "navHeaderDp" to navHeaderDp /* T128 */, "trackColumnTopDp" to trackColumnTop.value, "trackColumnBottomDp" to trackColumnBottom.value,
        "routesChipTopDp" to if (trackColumnBottom > 0.dp) (trackColumnBottom + GAP).value else null,
        "routesChipBottomDp" to routesChipBottom.value, "topRightBottomDp" to topRightBottom.value, // T110
        // The track controls clear the navigation card when they do not reach under it (T239: beside it on a phone on its side
        // counts, the layout rule's side column; before, only below its bottom edge did). T224: hidden is clear.
        "tracksClearOfNavCard" to (navCardBottom == 0.dp || !overlapBy(navCardPx, trackColumnPx, 1f) || hiddenReason(Chip.TRACKS) != null),
        // T137: the navigation card's and the online indicator column's rectangles (px, left, top, right, bottom; null when
        // hidden) and whether they overlap; the online column starts below the card while navigating.
        "navCardPx" to navCardPx?.map { Math.round(it) },
        "onlineChipsPx" to MapInsets.onlineChips?.map { Math.round(it) },
        "onlineClearOfNavCard" to !overlap(navCardPx, MapInsets.onlineChips),
        "speakerButtonPx" to io.github.jasondorjeshort.dystopo.app.nav.SpeakerLayout.boundsPx?.map { Math.round(it) }, // T137: the mute
        // T122: the navigation card's turn text as last laid out (lines, cut, fontSp), for checks at font scale 2.0.
        "navTurnText" to io.github.jasondorjeshort.dystopo.app.nav.TurnText.last,
        "follow" to followJson(),
        "chipsHidden" to hiddenBecause.entries.associate { (k, v) -> k.name.lowercase() to v }, // T224: why a column is not drawn
        "drawCheck" to drawCheck(),
    )

    /**
     * T146: counted as the navigation card is drawn, after the frame's layout and its position callbacks, so a frame drawn
     * with the card over a chip column is counted even when the next frame fixes it (a `GET /nav` sample catches such a
     * frame only by chance): `frames` drawn, `onlineOverlapFrames` with the online indicator under the card,
     * `tracksOverlapFrames` with the track controls reaching under it (1 px tolerance; T239: by rectangle, so the side
     * column beside the card on a phone on its side is clear). Since navigation started.
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
        if (overlapBy(card, trackColumnPx, 1f)) tracksOverlapFrames++ // T239: under the card, not beside it
    }

    /** T239 (pure): [a] and [b] share more than [slackPx] in both directions. */
    fun overlapBy(a: FloatArray?, b: FloatArray?, slackPx: Float): Boolean =
        a != null && b != null && a[0] < b[2] - slackPx && b[0] < a[2] - slackPx && a[1] < b[3] - slackPx && b[1] < a[3] - slackPx

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

    /**
     * T91: the font scale the floating map chips follow at most. They sit in fixed places around the map, and at
     * the system's 2.0 the Settings chip covered the search box and the online indicator the Waypoint chip; the
     * sheets, cards and lists keep the full scale.
     */
    const val MAX_FONT_SCALE = 1.3f
}

/**
 * T146: on the navigation card (its outer bounds): tells [TopChips] its bottom edge as it is placed, for `GET /nav`; 0 when
 * the card leaves. T239: the chips under it follow the card's own `Modifier.control` node through the rule.
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
