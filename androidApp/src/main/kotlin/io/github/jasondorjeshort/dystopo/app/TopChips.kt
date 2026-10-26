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
import androidx.compose.ui.unit.offset

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
    fun trackColumn(): Modifier = followNavCard(Chip.TRACKS, TRACKS_TOP)

    /**
     * The Routes chip's place (top-right): just below the track controls once they have been measured. T110: it also
     * measures the chip ([routesChipBottom]; 0 once the chip leaves the screen), the lowest of the top-right chips, which
     * the recentre button must not reach up to.
     */
    fun routesChip(): Modifier =
        Modifier.padding(end = 8.dp).then(followNavCard(Chip.ROUTES, ROUTES_TOP)) // T146: below the track controls in the same pass
            .composed {
                val density = LocalDensity.current
                DisposableEffect(Unit) { onDispose { routesChipBottom = 0.dp } }
                Modifier.onGloballyPositioned { c -> edge(c, density).let { if (it != routesChipBottom) routesChipBottom = it } }
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
            if (f.kind == Chip.TRACKS) trackMeasured(0)
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
        "tracksClearOfNavCard" to (navCardBottom == 0.dp || trackColumnTop >= navCardBottom),
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
    )

    /** T137 (pure): true when two `left, top, right, bottom` rectangles share any area; false when either is null. */
    fun overlap(a: FloatArray?, b: FloatArray?): Boolean =
        a != null && b != null && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private val GAP = 6.dp
    private val SEARCH_WIDTH = 220.dp

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
 */
internal class FollowNode(var kind: TopChips.Chip, var fallbackPx: Int) : Modifier.Node(), LayoutModifierNode {
    override fun onAttach() = TopChips.follower(this, true)
    override fun onDetach() = TopChips.follower(this, false)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val top = TopChips.chipTopPx(kind, TopChips.navCardBottomPx, TopChips.searchBottom.roundToPx(), TopChips.trackBottomPx, fallbackPx, density)
        val p = measurable.measure(constraints.offset(vertical = -top))
        TopChips.followMeasured(kind, top)
        if (kind == TopChips.Chip.TRACKS) TopChips.trackMeasured(top + p.height)
        return layout(constraints.constrainWidth(p.width), constraints.constrainHeight(p.height + top)) { p.place(0, top) }
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
