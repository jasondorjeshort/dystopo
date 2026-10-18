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

    /** Top and bottom edges of the track controls column. */
    var trackColumnTop by mutableStateOf(0.dp)
    var trackColumnBottom by mutableStateOf(0.dp)

    /** Bottom edge of [c] (or its top with [top]) from the window top. */
    fun edge(c: LayoutCoordinates, density: Density, top: Boolean = false): Dp =
        with(density) { (c.positionInRoot().y + if (top) 0f else c.size.height.toFloat()).toDp() }

    /** Where the track controls start: below the Packs button, or below the navigation card while it shows. */
    fun trackColumn(): Modifier =
        if (navCardBottom > 0.dp) Modifier.padding(top = navCardBottom + GAP) else Modifier.statusBarsPadding().padding(top = TRACKS_TOP)

    /**
     * The Routes chip's place (top-right): just below the track controls once they have been measured. T110: it also
     * measures the chip ([routesChipBottom]; 0 once the chip leaves the screen), the lowest of the top-right chips, which
     * the recentre button must not reach up to.
     */
    fun routesChip(): Modifier =
        (if (trackColumnBottom > 0.dp) Modifier.padding(end = 8.dp, top = trackColumnBottom + GAP) else Modifier.statusBarsPadding().padding(end = 8.dp, top = ROUTES_TOP))
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

    /** T91: where the online indicator starts: just below the search column (and its "Indexing places" line). */
    fun onlineColumn(): Modifier =
        if (searchBottom > 0.dp) Modifier.padding(start = 8.dp, top = searchBottom + 4.dp) else Modifier.statusBarsPadding().padding(start = 8.dp, top = 60.dp)

    fun json(): Map<String, Any?> = linkedMapOf(
        "topRowLeftDp" to topRowLeft.value, "searchBottomDp" to searchBottom.value, "searchWidthDp" to searchWidth().value,
        "onlineBottomPx" to onlineBottomPx, // T115
        "navCardBottomDp" to navCardBottom.value, "trackColumnTopDp" to trackColumnTop.value, "trackColumnBottomDp" to trackColumnBottom.value,
        "routesChipTopDp" to if (trackColumnBottom > 0.dp) (trackColumnBottom + GAP).value else null,
        "routesChipBottomDp" to routesChipBottom.value, "topRightBottomDp" to topRightBottom.value, // T110
        // The track controls clear the navigation card when they start below its bottom edge.
        "tracksClearOfNavCard" to (navCardBottom == 0.dp || trackColumnTop >= navCardBottom),
    )

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
}

/** T91: draws [content] (floating map chips) with the font scale capped at [TopChips.MAX_FONT_SCALE]. */
@Composable
fun MapChrome(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= TopChips.MAX_FONT_SCALE) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, TopChips.MAX_FONT_SCALE)) { content() }
}
