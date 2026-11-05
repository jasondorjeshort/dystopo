package io.github.jasondorjeshort.dystopo.app.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.routing.PlaceName
import io.github.jasondorjeshort.dystopo.routing.TravelMode

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val Link = Color(0xFF2C7BB6)
private val Orange = Color(0xFFE66101)

/**
 * T49: where the bottom panels sit, measured from the window bottom, so they stack instead of overlapping:
 * the credit line ([AttributionInsets]), the route card above it, the track profile and waypoint panels
 * ([io.github.jasondorjeshort.dystopo.app.tracking.TrackControls]) above the card. Zero when not shown.
 */
object BottomPanels {
    /** Top edge of the route card. */
    var routeCardTop by mutableStateOf(0.dp)

    /** Bottom edge of the track panels column (reported by TrackControls). */
    var trackPanelsBottom by mutableStateOf(0.dp)

    /** Top edge of the track panels column. */
    var trackPanelsTop by mutableStateOf(0.dp)

    /** What the track panels must clear: the route card when it is shown, else the credit line. */
    val trackPanelsFloor: Dp get() = maxOf(routeCardTop, AttributionInsets.bottom)

    /**
     * T192 (T184's follow-up): the route card's height cap (dp), the top chips' lowest edge it stays under (dp from the
     * window top), its top from the window top, and how far its upper part scrolls (px; 0 when everything fits). Reported.
     */
    @Volatile var routeCardCapDp = 0f
    @Volatile var routeCardChipsBottomDp = 0f
    @Volatile var routeCardTopFromWindowTopDp = 0f
    @Volatile var routeCardScrollMaxPx = 0
    @Volatile var routeCardScrollPx = 0

    /** T217: the header line the card last drew (`GET /route` `card.drawnHeadline`), null while it draws none. */
    @Volatile var routeCardHeadline: String? = null

    /**
     * T212: the free band the card had (dp: from under the top chips to its bottom gap), the scale bar's line it leaves
     * above itself on the left ([mapRowDp]: the bar and the readout strip's elevation) and whether that line was kept
     * (false when keeping it would leave the card less than its header, actions and two body lines). The card's parts are
     * in [routeCard].
     */
    @Volatile var routeCardBandDp = 0f
    @Volatile var mapRowReservedDp = 0f
    @Volatile var mapRowKept = true

    /** T212: the pressure warning's card's bottom edge (dp from the window top) the card stayed under; 0 with none. */
    @Volatile var warningCardBottomDp = 0f

    /**
     * T212 (the verifier's note): the planning card's least while the pressure warning's card shows above it: one
     * scrolling 13 sp line pair (the card goes all one scroll) and its padding. The warning card leaves this much and the
     * scale bar's plate ([barRowDp]) above the bottom gap.
     */
    fun warnedLeastDp(density: androidx.compose.ui.unit.Density): Dp = with(density) { 36.sp.toDp() } + 20.dp

    /** T212: the scale bar's plate alone with its gaps (under a card above, over a card below), at the font scale. */
    fun barRowDp(density: androidx.compose.ui.unit.Density): Dp = with(density) {
        val both = io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits
        6.dp + 6.dp + 12.sp.toDp() * LINE * (if (both) 2 else 1) + (if (both) 14.dp else 8.dp) + 8.dp
    }
    internal val routeCard = CardLayout()

    /**
     * T212: the height the scale bar's line takes on the left of the map above a bottom card (or under a top card): the
     * gap under the card above it, the bar's plate (its label lines and ruler) and, with the readout bar on, the strip's
     * elevation entry alone above the bar (caption, value and, with both unit systems, the other system's line) with the
     * gap between them, and the gap the bar keeps under the top-left column; at the font scale of [density] (sp sizes
     * as `controls/ScaleBar.kt` and `readout/ReadoutStrip.kt` draw them, so it follows the system's font scale curve).
     */
    fun mapRowDp(density: androidx.compose.ui.unit.Density): Dp = with(density) {
        val both = io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits
        val bar = 6.dp + 12.sp.toDp() * LINE * (if (both) 2 else 1) + (if (both) 14.dp else 8.dp)
        val strip = if (!io.github.jasondorjeshort.dystopo.app.AppSettings.readoutBar) 0.dp
        else 6.dp + 6.dp + (10.sp.toDp() + (if (both) 11.sp.toDp() else 0.dp)) * LINE + 15.sp.toDp() * VALUE_LINE
        6.dp + bar + strip + 8.dp
    }

    /** T212: a text line's height for its size (the platform font's ascent and descent, rounded up). */
    private const val LINE = 1.2f

    /**
     * T212: the strip's value row's height for its 15 sp type: the value with its unit, its interval and the trend arrow,
     * whose glyph comes from a taller fallback font (the entry's plate measured 72.6 dp at font scale 2.0, display size
     * largest, for a 20 dp caption and 27 dp value).
     */
    private const val VALUE_LINE = 1.6f

    /** T212: the bottom edge of the top-left column (the search box, the online indicator), dp from the window top. */
    fun topLeftBottom(density: androidx.compose.ui.unit.Density): Dp = with(density) {
        maxOf(io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom, io.github.jasondorjeshort.dystopo.app.TopChips.onlineBottomPx.toDp())
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "creditTopDp" to AttributionInsets.bottom.value, "routeCardTopDp" to routeCardTop.value,
        // T192: the card stays under the top chips at any font scale: capped, its upper part scrolling, the actions in view.
        "routeCardCapDp" to routeCardCapDp, "topChipsBottomDp" to routeCardChipsBottomDp,
        "routeCardTopFromWindowTopDp" to routeCardTopFromWindowTopDp,
        "routeCardClearOfTopChips" to (routeCardTop <= 0.dp || routeCardTopFromWindowTopDp >= routeCardChipsBottomDp - 0.5f),
        // T212: and of the pressure warning's card while it shows (the warning above, the two sharing the band).
        "warningCardBottomDp" to warningCardBottomDp,
        "routeCardClearOfWarningCard" to (routeCardTop <= 0.dp || warningCardBottomDp <= 0f || routeCardTopFromWindowTopDp >= warningCardBottomDp - 0.5f),
        "routeCardScrollMaxPx" to routeCardScrollMaxPx, "routeCardScrollPx" to routeCardScrollPx,
        // T212: the band, the scale bar's line left above the card, and the card's fixed header, scrolling body and actions.
        "routeCardBandDp" to routeCardBandDp, "mapRowReservedDp" to mapRowReservedDp, "mapRowKept" to mapRowKept,
        "routeCard" to routeCard.json(),
        "trackPanelsBottomDp" to trackPanelsBottom.value, "trackPanelsTopDp" to trackPanelsTop.value,
        // The panels overlap the card when their bottom edge is below the card's top edge (and both show).
        "overlap" to (routeCardTop > 0.dp && trackPanelsTop > trackPanelsBottom && trackPanelsBottom < routeCardTop),
    )
}

/**
 * T212: how a card capped by [CappedCard] was last laid out, for the debug routes: the cap (dp), its fixed header's and
 * actions' natural heights (px), its [mode] ([CardBands.mode]; `compact` is any mode but `fixed`), the scrolling
 * region's bounds in the window (px) and its scroll.
 */
internal class CardLayout {
    var headerPx by mutableStateOf(0)
    var footerPx by mutableStateOf(0)
    @Volatile var shown = false
    @Volatile var mode = CardBands.FIXED
    @Volatile var capDp = 0f
    @Volatile var density = 1f
    @Volatile var bodyPx: FloatArray? = null
    @Volatile var scrollPx = 0
    @Volatile var scrollMaxPx = 0
    @Volatile var description: String? = null

    fun hidden() {
        shown = false; bodyPx = null; scrollPx = 0; scrollMaxPx = 0; description = null
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "shown" to shown, "capDp" to capDp, "mode" to mode, "compact" to (mode != CardBands.FIXED),
        "headerDp" to Math.round(headerPx / density * 10) / 10.0, "actionsDp" to Math.round(footerPx / density * 10) / 10.0,
        "bodyPx" to bodyPx?.takeIf { shown }?.map { Math.round(it) },
        "scrollPx" to scrollPx, "scrollMaxPx" to scrollMaxPx, "scrolls" to (scrollMaxPx > 0), "description" to description,
    )
}

/**
 * T212 (the research `cards-at-large-font-scales`: WCAG 1.4.4 and 1.4.10, nothing clipped or dropped at 200 percent,
 * scrolling in one dimension): a card at most [cap] tall whose [header] (the primary line) and [footer] (the actions)
 * stay in view while the [body] between them scrolls, with a thumb at its right edge and a fade where more lies above or
 * below, and [description] as the scroll region's content description (", scroll for more" added while it scrolls).
 * When the header and the actions would leave the body less than two lines (landscape at font scale 2.0), the header
 * scrolls with the body above the actions, and when even the actions leave less than that, all three are one scroll
 * ([CardBands.mode]), so nothing is ever cut. With [pinned] the header and the actions always stay (the warning card,
 * whose caller makes the cap at least their height). Type is never shrunk: the user's scale stands.
 */
@Composable
internal fun CappedCard(
    cap: Dp, layout: CardLayout, scroll: androidx.compose.foundation.ScrollState, description: String, paper: Color,
    spacing: Dp, modifier: Modifier, pinned: Boolean = false,
    header: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    footer: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val mode = if (pinned) CardBands.FIXED else with(density) { CardBands.mode(layout.headerPx, layout.footerPx, MIN_BODY.toPx(), spacing.toPx(), cap.toPx()) }
    val desc = if (scroll.maxValue in 1 until Int.MAX_VALUE) "$description, scroll for more" else description
    SideEffect {
        layout.shown = true; layout.mode = mode; layout.capDp = cap.value; layout.density = density.density; layout.description = desc
    }
    DisposableEffect(layout) { onDispose { layout.hidden() } }
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { scroll.value to scroll.maxValue }.collect { (v, m) -> layout.scrollPx = v; layout.scrollMaxPx = m }
    }
    val region = Modifier
        .onGloballyPositioned { c -> layout.bodyPx = c.boundsInRoot().let { floatArrayOf(it.left, it.top, it.right, it.bottom) } }
        .semantics { contentDescription = desc }
        .fadeEdges(scroll, paper).scrollbar(scroll).verticalScroll(scroll)
    val measuredHeader = Modifier.onSizeChanged { if (it.height != layout.headerPx) layout.headerPx = it.height }
    val measuredFooter = Modifier.onSizeChanged { if (it.height != layout.footerPx) layout.footerPx = it.height }
    if (mode == CardBands.FIXED) {
        // The header and the actions at their whole height (a wrapping row of actions is never clipped to the room left,
        // as a Column's later children are), the body given what remains of the cap.
        androidx.compose.ui.layout.Layout(
            content = {
                Column(measuredHeader, verticalArrangement = Arrangement.spacedBy(spacing), content = header)
                Column(region, verticalArrangement = Arrangement.spacedBy(spacing), content = body)
                Column(measuredFooter, verticalArrangement = Arrangement.spacedBy(spacing), content = footer)
            },
            modifier = modifier,
        ) { ms, c ->
            val loose = androidx.compose.ui.unit.Constraints(maxWidth = c.maxWidth)
            val gap = spacing.roundToPx()
            val h = ms[0].measure(loose)
            val f = ms[2].measure(loose)
            val b = ms[1].measure(loose.copy(maxHeight = (cap.roundToPx() - h.height - f.height - gap * 2).coerceAtLeast(0)))
            val width = maxOf(h.width, b.width, f.width).coerceIn(c.minWidth, c.maxWidth)
            val height = (h.height + gap + b.height + gap + f.height).coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                h.place(0, 0)
                b.place(0, h.height + gap)
                f.place(0, h.height + gap + b.height + gap)
            }
        }
        return
    }
    Column(modifier.heightIn(max = cap), verticalArrangement = Arrangement.spacedBy(spacing)) {
        when (mode) {
            CardBands.HEADER_SCROLLS -> {
                Column(Modifier.weight(1f, fill = false).then(region), verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Column(measuredHeader, verticalArrangement = Arrangement.spacedBy(spacing), content = header)
                    body()
                }
                Column(measuredFooter, verticalArrangement = Arrangement.spacedBy(spacing), content = footer)
            }
            else -> Column(Modifier.weight(1f, fill = false).then(region), verticalArrangement = Arrangement.spacedBy(spacing)) {
                Column(measuredHeader, verticalArrangement = Arrangement.spacedBy(spacing), content = header)
                body()
                Column(measuredFooter, verticalArrangement = Arrangement.spacedBy(spacing), content = footer)
            }
        }
    }
}

/**
 * T212 (pure, `CardBandsTest`): the arithmetic of the cards' caps at large font scales, in dp unless named px. The
 * composables measure the inputs (the window, the chips, the bottom chrome, the cards' own header and actions).
 */
internal object CardBands {
    /**
     * The planning card's cap: the free [band] less the scale bar's line [reserve] while what is left still holds [needs]
     * (its header, actions and two body lines, with its padding); else the whole band, the line yielding. Second: kept.
     */
    fun planningCap(band: Float, reserve: Float, needs: Float): Pair<Float, Boolean> =
        if (band - reserve >= needs) (band - reserve) to true else band to false

    /** A cap of at least [least] (the card's own minimum, measured), but never past [room] (the whole window's height for it). */
    fun atLeast(cap: Float, least: Float, room: Float): Float = maxOf(cap, minOf(least, room)).coerceAtLeast(0f)

    /** [mode]: the header and the actions fixed, the body scrolling between them. */
    const val FIXED = "fixed"

    /** [mode]: the header scrolls with the body, the actions fixed under them. */
    const val HEADER_SCROLLS = "headerScrolls"

    /** [mode]: the header, the body and the actions are one scroll. */
    const val ALL_SCROLLS = "allScrolls"

    /**
     * How a card with a header and actions is laid out in its cap ([capPx]): [FIXED] while both leave the body at least
     * [minBodyPx], else [HEADER_SCROLLS] while the actions alone do, else [ALL_SCROLLS].
     */
    fun mode(headerPx: Int, footerPx: Int, minBodyPx: Float, spacingPx: Float, capPx: Float): String = when {
        headerPx + footerPx + minBodyPx + spacingPx * 2 <= capPx -> FIXED
        footerPx + minBodyPx + spacingPx <= capPx -> HEADER_SCROLLS
        else -> ALL_SCROLLS
    }

    /** The navigation card's band: from its [top] down to the bottom chrome's pad, less the taller stack under the card. */
    fun navBand(maxHeight: Float, top: Float, bottomPad: Float, belowLeft: Float, belowRight: Float): Float =
        (maxHeight - top - bottomPad - maxOf(belowLeft, belowRight)).coerceAtLeast(0f)

    /**
     * The warning card's top: [chromeTop] (under the top chrome), or under the [compass] (left, top, right, bottom) when
     * it reaches into the card's band (across [cardLeft] to [cardRight], down to [bottomLimit]) below that top.
     */
    fun warningTop(chromeTop: Float, compass: FloatArray?, cardLeft: Float, cardRight: Float, bottomLimit: Float, gap: Float = 8f): Float {
        val c = compass ?: return chromeTop
        val inBand = c[0] < cardRight && c[2] > cardLeft && c[3] > chromeTop && c[1] < bottomLimit
        return if (inBand) c[3] + gap else chromeTop
    }

    /** Whether the whole warning card fits its [room]: null before its [detail] was measured (it is drawn whole first). */
    fun warningFits(detail: Float?, header: Float, actions: Float, chrome: Float, room: Float): Boolean? =
        detail?.let { header + it + actions + chrome <= room }
}

/** T212: the least body a [CappedCard] keeps between its header and actions before it goes compact: two 13 sp lines. */
private val MIN_BODY = 36.sp

/**
 * T212: the scroll's visible cue besides [scrollbar]'s thumb: the region's edge fades into the card's [paper] where more
 * content lies beyond it (the bottom while it can scroll down, the top once scrolled). Before `verticalScroll`.
 */
internal fun Modifier.fadeEdges(state: androidx.compose.foundation.ScrollState, paper: Color): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val h = minOf(FADE.toPx(), size.height / 4)
    val clear = paper.copy(alpha = 0f)
    if (state.value < max) drawRect(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(clear, paper), startY = size.height - h, endY = size.height),
        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h),
    )
    if (state.value > 0) drawRect(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(paper, clear), startY = 0f, endY = h),
        size = androidx.compose.ui.geometry.Size(size.width, h),
    )
}

private val FADE = 18.dp

/** Distance of [c]'s top (or bottom) edge from the bottom of the window. */
fun fromWindowBottom(c: LayoutCoordinates, density: androidx.compose.ui.unit.Density, top: Boolean = true): Dp {
    var r = c
    while (true) r = r.parentLayoutCoordinates ?: break
    val y = c.positionInRoot().y + if (top) 0f else c.size.height.toFloat()
    return with(density) { (r.size.height - y).toDp() }
}

/**
 * The route card (T45): binds [RouteSession.overlay] to the map (long-press start, long-press end),
 * then shows what is happening: waiting for the end point, planning (with the trail-network build
 * progress on first use), the route's distance, climb and time with the mode chips and the actions,
 * or why no route was found. Nothing shows until the first long-press, nor while navigating (T65). The
 * distance, climb and time are [RouteSession.summaryOf], the figures the kept route shows (T65). T102: "Show N
 * directions" opens the turn directions (the default detail level), each with the distance it then runs.
 * T108: Crow joins the mode chips (they wrap at large font scales) with its one line under them, and a crow route
 * shows its bearing instead of the trails it follows.
 *
 * T114: the card names both ends under its title ("From: Your location", "To: Panorama Point"; T192: by the pack's
 * nearest named feature, no longer the coordinates), as the route
 * screens of the mainstream navigation apps do; Change on the From line offers Your location, Choose on map and Swap
 * ([RouteSession.fromChoices]). While a route to here waits for its origin the card says so, and with location off it
 * offers Turn on location (the permission prompt, or the phone's location settings). When no path is found it offers
 * Try Crow (the straight line, with its notice under the chips). Go comes first among the actions.
 *
 * T187: under the directions, the water along the route ([WaterAlongLine]): "Water: 3 crossings, the next is likely flowing in
 * 1.2 km" and the longest stretch without water, the first line opening the list ([WaterText.row]); the next card line
 * (T184) sits under that one composable. T195: under the water line, the route's terrain ([ExposureLine]): "1.2 km on
 * slopes over 30 degrees, facing north and east; 0.4 km over 35 degrees", opening its sheet.
 *
 * T184: the time in the range form (the `timeEstimateForm` setting, default): the headline's time "about 4:30" and the
 * line under it "4:10 to 4:50, based on your last 5 hikes" ([PaceSettings.headline], [PaceSettings.secondLine]), or
 * "typical 4:30" without a range; under the water line, [PastEffortLine]: "You did this trail in 3:52, June".
 *
 * T212: a [CappedCard]: the title and the headline stay in view at the top and the actions at the bottom, everything else
 * (the range line, From and To, the trails, directions, water, terrain, past effort, messages, the mode chips) scrolls
 * between them, capped at the measured band that leaves the scale bar's line above the card ([BottomPanels.mapRowDp]).
 *
 * T217 (the audit's finding 1, the survey `route-card-and-lists`): with a route the header is its one line of figures
 * ([PaceSettings.headline]; the title stays for the other phases), and the line under it says what the time rests on
 * ([RouteFigures.basis]). The actions ([CardActions]): a filled Go, an outlined Save (the route stored as a trip, T60's
 * Keep), Clear as a text action and a three-dot overflow holding Export GPX, whose items open in a row just above the
 * actions, inside the card: the app has no popup windows, and `/screenshot?mode=window` and the tools' taps see this one.
 * The file-only Save of `files/routes/` left the card (`POST /route/save`, `GET /route/saved` and the navigation key stay).
 * The water and terrain lines say they are working until their answer ([WorkingLine]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RouteControls() {
    val app = DystopoApp.instance
    val mapView = app.map.mapView
    DisposableEffect(mapView) {
        mapView?.let { RouteSession.overlay.bind(it) }
        onDispose { RouteSession.overlay.unbind() }
    }
    LaunchedEffect(Unit) { RouteSession.restoreOnce() } // T108: the unsaved route after a process restart
    // T114: Turn on location asks for the permission here (registered before the card's early return, so a result
    // that comes back after a recomposition still lands); a fix that follows plans the waiting route.
    val context = androidx.compose.ui.platform.LocalContext.current
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        app.map.onLocationPermissionChanged()
        // Refused for good (the system no longer shows the prompt): the app's own settings page, where it can be allowed.
        val activity = context as? android.app.Activity
        if (granted.values.none { it } && activity != null && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    }
    val phase = RouteSession.phase
    // T65: hidden while navigating (the stats bar has what matters; the card returns when navigation stops).
    if (phase == "idle" || io.github.jasondorjeshort.dystopo.app.nav.NavSession.ui != null) {
        SideEffect {
            if (BottomPanels.routeCardTop != 0.dp) BottomPanels.routeCardTop = 0.dp
            BottomPanels.routeCardTopFromWindowTopDp = 0f; BottomPanels.routeCardCapDp = 0f; BottomPanels.routeCardScrollMaxPx = 0; BottomPanels.routeCardScrollPx = 0 // T192
            BottomPanels.routeCardBandDp = 0f; BottomPanels.mapRowReservedDp = 0f // T212
            BottomPanels.routeCardHeadline = null // T217
        }
        return
    }
    // T212 (T187's follow-up): a setting changed outside the sheet (`POST /settings`, a sync) bumps no revision, so the card
    // kept the old figures until its next update: while the card shows, the settings are compared each tick and a change
    // recomposes everything that reads them (the card's lines, the water and terrain lines, the sheet).
    LaunchedEffect(Unit) {
        var last = runCatching { io.github.jasondorjeshort.dystopo.app.AppSettings.json() }.getOrNull()
        while (true) {
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(SETTINGS_TICK_MS)
            val now = runCatching { io.github.jasondorjeshort.dystopo.app.AppSettings.json() }.getOrNull()
            if (now != last) {
                last = now
                io.github.jasondorjeshort.dystopo.app.SettingsUi.revision++
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val settingsRev = io.github.jasondorjeshort.dystopo.app.SettingsUi.revision // T212: the whole card recomposes
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // T192 (T184's follow-up): the card stays under the top chips (the search box, the online indicator, the Routes
        // chip) at any font scale, its height capped at the room between them and its bottom gap. T212: the cap is that
        // measured band less the scale bar's line on the left ([BottomPanels.mapRowDp]: the bar and the readout strip's
        // elevation, which at font scale 2.0 had no room left above the card), unless that would leave the card less than
        // its header, actions and two body lines; no fixed least height. The title and the headline (distance, climb,
        // time) stay in view at the top, the actions at the bottom, and every other line scrolls between them.
        val b = AttributionInsets.bottom
        val navBar = with(density) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() }
        val statusBar = with(density) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }
        val bottomGap = if (b > 0.dp) b + 6.dp else navBar + 28.dp
        val warnBottom = io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.cardBottomPx?.let { with(density) { it.toDp() } } ?: 0.dp
        val chipsBottom = maxOf(
            maxOf(io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom, io.github.jasondorjeshort.dystopo.app.TopChips.routesChipBottom),
            with(density) { io.github.jasondorjeshort.dystopo.app.TopChips.onlineBottomPx.toDp() },
            statusBar,
            warnBottom, // T212 (the verifier's note): the pressure warning's card above this one, the two sharing the band
        )
        val band = (maxHeight - bottomGap - chipsBottom - CARD_CHIPS_GAP).coerceAtLeast(0.dp)
        // The card's top must stay this far down for the scale bar's line to fit between it and the top-left column (or
        // the warning's card).
        val rowTop = maxOf(BottomPanels.topLeftBottom(density), statusBar, warnBottom) + BottomPanels.mapRowDp(density)
        val reserve = (rowTop - (chipsBottom + CARD_CHIPS_GAP)).coerceAtLeast(0.dp)
        val card = BottomPanels.routeCard
        val padV = 10.dp
        val needs = with(density) { (card.headerPx + card.footerPx).toDp() + 36.sp.toDp() } + 6.dp * 2 + padV * 2
        val (capValue, keepRow) = CardBands.planningCap(band.value, reserve.value, needs.value)
        // The least the card keeps, measured (T192's fixed 200 dp is gone): its actions and two lines, within the window
        // under the status bar (landscape at font scale 2.0 leaves a band of about 50 dp under the chips; the card then
        // reaches over the chips' lower edge rather than shrinking to one line, `routeCardClearOfTopChips` false).
        // While the warning's card shows, the least is one scrolling line ([BottomPanels.warnedLeastDp], what the warning
        // leaves it) and the card never reaches up over the warning.
        val least = if (warnBottom > 0.dp) BottomPanels.warnedLeastDp(density) else with(density) { card.footerPx.toDp() + 36.sp.toDp() } + 6.dp + padV * 2
        val roomTop = if (warnBottom > 0.dp) warnBottom + CARD_CHIPS_GAP else statusBar
        val cap = CardBands.atLeast(capValue, least.value, (maxHeight - bottomGap - roomTop).coerceAtLeast(padV * 2).value).dp
        val upper = rememberScrollState()
        SideEffect {
            BottomPanels.routeCardCapDp = cap.value
            BottomPanels.routeCardChipsBottomDp = chipsBottom.value
            BottomPanels.routeCardBandDp = band.value
            BottomPanels.mapRowReservedDp = if (keepRow) reserve.value else 0f
            BottomPanels.mapRowKept = keepRow
            BottomPanels.warningCardBottomDp = warnBottom.value
        }
        LaunchedEffect(upper) {
            androidx.compose.runtime.snapshotFlow { upper.value to upper.maxValue }.collect { (v, m) -> BottomPanels.routeCardScrollPx = v; BottomPanels.routeCardScrollMaxPx = m }
        }
        val plan = RouteSession.plan
        CappedCard(
            cap = (cap - padV * 2).coerceAtLeast(0.dp), layout = card, scroll = upper, description = ROUTE_DETAILS, paper = PaperOpaque, spacing = 6.dp,
            // T49: above the credit line (its measured top); the track panels stack above this card.
            modifier = Modifier.align(Alignment.BottomCenter).let { m ->
                if (b > 0.dp) m.padding(start = 8.dp, end = 8.dp, bottom = b + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp)
            }
                .widthIn(max = 420.dp)
                .onGloballyPositioned { c ->
                    fromWindowBottom(c, density).let { if (it != BottomPanels.routeCardTop) BottomPanels.routeCardTop = it }
                    BottomPanels.routeCardTopFromWindowTopDp = with(density) { c.positionInRoot().y.toDp().value }
                }
                .background(Paper, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = padV),
            header = {
                val title = when (phase) {
                    "start" -> "Route: start set"
                    "planning" -> "Route: planning"
                    "failed" -> "No route"
                    "waitingFix", "noLocation" -> "Route: waiting" // T114
                    "pickStart" -> "Route: choose the start"
                    else -> plan?.route?.name ?: "Route"
                }
                // T184: "about 4:30" in the range form (PaceSettings.headline), the single number in the other. T212: the
                // card's primary line (distance, climb, time), kept in view above the scrolling lines. T217: alone with a route.
                if (plan != null && phase == "route") {
                    val figures = PaceSettings.headline(plan)
                    SideEffect { BottomPanels.routeCardHeadline = figures }
                    BasicText(figures, style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                } else {
                    SideEffect { BottomPanels.routeCardHeadline = null }
                    BasicText(title, style = TextStyle(color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            },
            body = {
                if (plan != null && phase == "route") {
                    // T164: whose pace the time is ("Based on your last 5 hikes", or a typical pace); a refit when the tracks changed.
                    // T184: with a range, the range first ("4:10 to 4:50, based on your last 5 hikes").
                    PaceSettings.secondLine(plan)?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 12.sp)) }
                    LaunchedEffect(plan) { PaceSettings.refitSoon(app, "route") }
                }
                Ends(phase) // T114: From and To
                if (plan != null && phase == "route") {
                    // T108: a crow line's bearing (it follows no trail).
                    if (plan.mode == TravelMode.CROW) {
                        val b = plan.route.points.let { io.github.jasondorjeshort.dystopo.routing.Crow.bearingDeg(it.first().lat, it.first().lon, it.last().lat, it.last().lon) }
                        BasicText("Bearing $b° (${io.github.jasondorjeshort.dystopo.routing.Directions.compass(b.toDouble())})", style = TextStyle(color = Ink, fontSize = 13.sp))
                    }
                    // T49: the trails it follows, by name. T212: whole, wrapping (it was cut at two lines).
                    // T217: each name once (a trail the route leaves and rejoins was named twice).
                    val trails = plan.via.map { it.first }.distinct()
                    if (trails.isNotEmpty()) {
                        BasicText(
                            "via " + trails.take(4).joinToString(", ") + if (trails.size > 4) ", ..." else "",
                            style = TextStyle(color = Ink, fontSize = 13.sp),
                        )
                    }
                    Directions(plan)
                    WaterAlongLine(plan.route.points, WaterUi.routeOpen) { WaterUi.routeOpen = !WaterUi.routeOpen } // T187
                    ExposureLine(plan.route.points, plan.mode) // T195
                    BestStartLine(plan) // T209: the start time with the most terrain shade on a hot day, else sun (RouteBestStart.kt)
                    PastEffortLine(plan) // T184
                }
                if (phase == "planning") BuildProgress()
                RouteSession.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp)) }
                // T73: the mode chips (hike, bike, drive; T108: crow) on a row of their own; T108: they wrap rather than
                // squeeze at large font scales. T212: in the scrolling part, under the lines, so the actions keep their room.
                ModeChips {
                    for (m in TravelMode.entries) {
                        val on = RouteSession.mode == m
                        BasicText(
                            m.label.replaceFirstChar { it.uppercase() },
                            style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.background(if (on) Ink else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { RouteSession.changeMode(m) }.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                if (RouteSession.mode == TravelMode.DRIVE) {
                    BasicText(io.github.jasondorjeshort.dystopo.routing.Router.DRIVE_NOTICE, style = TextStyle(color = Ink, fontSize = 11.sp))
                }
                if (RouteSession.mode == TravelMode.CROW) BasicText(TravelMode.CROW_NOTICE, style = TextStyle(color = Ink, fontSize = 11.sp)) // T108
            },
            footer = {
                // T114: the actions wrap (Turn on location and Try Crow join them), and Go comes first. T217: one list
                // ([CardActions]) for what is drawn here and what `GET /route` `card.actions` lists.
                val actions = CardActions.of(phase, RouteSession.canTryCrow())
                val more = actions.firstOrNull { it.id == CardActions.MORE.id }
                if (more != null && RouteSession.moreOpen) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        for (item in more.items) OverflowItem(item) {
                            RouteSession.moreOpen = false
                            when (item.id) {
                                CardActions.EXPORT_GPX.id -> plan?.let { p -> io.github.jasondorjeshort.dystopo.app.SaveFile.route(p, RouteSession.from, RouteSession.to) } // T219: the system's save dialog
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val row = Modifier.align(Alignment.CenterVertically)
                    for (a in actions) when (a.id) {
                        CardActions.GO.id -> CardAction(a, row) { Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startRoute() }.start() } // T61: navigate
                        // T60's Keep: stored as a trip (synced, its corridor offline), clearing the plan.
                        CardActions.SAVE.id -> CardAction(a, row) { plan?.let { p -> val f = RouteSession.from; val t = RouteSession.to; RouteSession.clear(); io.github.jasondorjeshort.dystopo.app.trips.Trips.background { io.github.jasondorjeshort.dystopo.app.trips.Trips.keep(p, from = f, to = t) } } } // T219: the ends name it
                        CardActions.TURN_ON.id -> CardAction(a, row) {
                            when (RouteSession.locationState()) {
                                "noPermission" -> askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                                "off" -> runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                            }
                        }
                        // T114: no path between the two ends: the straight line (its notice shows under the chips once chosen).
                        CardActions.TRY_CROW_ACTION.id -> CardAction(a, row) { RouteSession.changeMode(TravelMode.CROW) }
                        CardActions.CLEAR.id -> CardAction(a, row) { RouteSession.clear() }
                        CardActions.MORE.id -> Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterEnd) {
                            OverflowDots(a.label) { RouteSession.moreOpen = !RouteSession.moreOpen }
                        }
                    }
                }
            },
        )
    }
}

/** T212: how often the card compares the settings with the ones it drew (`POST /settings` reaches it within this). */
private const val SETTINGS_TICK_MS = 500L

/** T212: the content description of the card's scrolling lines (`uiautomator dump`; ", scroll for more" while it scrolls). */
internal const val ROUTE_DETAILS = "Route details"

/** T212: the card's paper without its slight transparency, for the scroll region's fade. */
private val PaperOpaque = Color(0xFFFFFCF5)

/**
 * T149: the card's line when the heap guard refused the trail network's build ([RoutePlanner]: the build's estimated
 * peak did not fit the heap free after a trim and a wait), and the progress line while the build waits for room.
 */
internal const val GRAPH_TOO_LARGE = "This map is too large to route across on this phone; download a smaller area for routing"
internal const val GRAPH_WAITING = "Waiting for memory to build the trail network"

/**
 * T192: the gap between the route card's top and the top chips' lowest edge. T212: T192's fixed least height (200 dp)
 * is gone: the cap is the measured band, and a band too small for the header and actions makes the card one scroll.
 */
private val CARD_CHIPS_GAP = 8.dp

/**
 * T192: a thin thumb at the right edge of a box that scrolls ([state]), so a list cut by its height reads as one that
 * scrolls (the water list, the card's upper part). Drawn in the box's own frame: put it before `verticalScroll`.
 */
internal fun Modifier.scrollbar(state: androidx.compose.foundation.ScrollState): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max > 0 && max != Int.MAX_VALUE) {
        val h = size.height
        val thumb = (h * h / (h + max)).coerceAtLeast(16.dp.toPx())
        val y = (h - thumb) * state.value / max
        val w = 3.dp.toPx()
        drawRoundRect(ScrollThumb, androidx.compose.ui.geometry.Offset(size.width - w, y), androidx.compose.ui.geometry.Size(w, thumb), androidx.compose.ui.geometry.CornerRadius(w / 2))
    }
}

private val ScrollThumb = Color(0x802B2420)

/** T114: the card's new action labels. */
internal const val TURN_ON_LOCATION = "Turn on location"
internal const val TRY_CROW = "Try Crow"

/**
 * T217 (pure, `RouteFiguresTest`): the planning card's actions for its phase, one list for what the card draws and what
 * `GET /route` `card.actions` lists. One primary, as the survey `route-card-and-lists` found on every app (Go, Start, Start
 * Navigation) and as Material's button hierarchy has it: filled, then outlined, then text; export in an overflow, as on
 * Organic Maps, Gaia, OsmAnd, Locus and CalTopo.
 */
internal object CardActions {
    const val FILLED = "filled"
    const val OUTLINED = "outlined"
    const val TEXT = "text"
    const val OVERFLOW = "overflow"

    class Item(val id: String, val label: String, val style: String, val items: List<Item> = emptyList()) {
        val primary: Boolean get() = style == FILLED

        fun json(): Map<String, Any?> = linkedMapOf<String, Any?>("id" to id, "label" to label, "primary" to primary, "style" to style) +
            (if (items.isEmpty()) emptyMap() else mapOf("items" to items.map { it.json() }))
    }

    val GO = Item("go", "Go", FILLED)
    val SAVE = Item("save", "Save", OUTLINED)
    val CLEAR = Item("clear", "Clear", TEXT)
    val EXPORT_GPX = Item("exportGpx", "Export GPX", TEXT)
    val MORE = Item("more", "More actions", OVERFLOW, listOf(EXPORT_GPX))
    val TURN_ON = Item("turnOnLocation", TURN_ON_LOCATION, TEXT)
    val TRY_CROW_ACTION = Item("tryCrow", TRY_CROW, TEXT)

    fun of(phase: String, canTryCrow: Boolean): List<Item> = when (phase) {
        "idle" -> emptyList()
        "route" -> listOf(GO, SAVE, CLEAR, MORE)
        else -> listOfNotNull(TURN_ON.takeIf { phase == "noLocation" }, TRY_CROW_ACTION.takeIf { canTryCrow }, CLEAR)
    }
}

/**
 * T217: a computed line's state in `GET /route` `card.lines` (`working` until the answer, `ready` with a line to show, `none`
 * when there is none: the setting off, the mode, no data, or a failed computation), read from its [future] without waiting.
 */
internal object LineState {
    const val WORKING = "working"
    const val READY = "ready"
    const val NONE = "none"
    val NONE_JSON: Map<String, Any?> = linkedMapOf("state" to NONE, "text" to null)

    fun <T> of(future: java.util.concurrent.Future<T>?, working: String?, text: (T) -> String?): Map<String, Any?> {
        val f = future ?: return NONE_JSON
        if (!f.isDone) return linkedMapOf("state" to WORKING, "text" to working)
        val line = runCatching { f.get() }.getOrNull()?.let(text) ?: return NONE_JSON
        return linkedMapOf("state" to READY, "text" to line)
    }

    /** Debug only (`POST /route/lines?holdMs=`): the water and terrain computations wait this long first, so a test can read and view their working state. */
    @Volatile var holdMs = 0L

    fun hold() {
        val ms = holdMs
        if (ms > 0) runCatching { Thread.sleep(ms) }
    }

    /** How long a line waits for its answer (one worker per kind, possibly busy with an earlier route) before showing nothing. */
    const val WAIT_MS = 300_000L
}

/**
 * T114: the From and To lines. From is "Your location" (with the fix's age when it was stale), a placed point's
 * name, or "Choose on map" while the next long-press picks it; Change opens Your location, Choose on map and
 * Swap under it, as far as [RouteSession.fromChoices] allows. To is the destination's name. Nothing shows before the
 * first end is known.
 *
 * T192: a placed end is named by the nearest named feature of the pack within 100 m ([PlaceName], [EndNames]: "To:
 * Skyline Trailhead"), else "a point on the map"; "..." while the lookup runs. The coordinates stay in `GET /route`
 * (`from`, `to`; `ends` has the lines as drawn).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Ends(phase: String) {
    val f = RouteSession.from
    val t = RouteSession.to
    if (f == null && t == null) return
    val fromName = endName(f)
    val toName = endName(t)
    val fromText = when {
        phase == "pickStart" -> CHOOSE_ON_MAP
        f != null -> fromName
        else -> RouteOrigin.HERE // a route to here waiting for its origin
    }
    val choices = if (phase == "planning" || phase == "start") emptyList() else RouteSession.fromChoices()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText("From: $fromText", style = TextStyle(color = Ink, fontSize = 13.sp), modifier = Modifier.padding(vertical = 4.dp)) // level with Change
        if (choices.isNotEmpty()) Action(CHANGE) { RouteSession.fromOpen = !RouteSession.fromOpen }
    }
    if (RouteSession.fromOpen && choices.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if ("here" in choices) Action(RouteOrigin.HERE) { RouteSession.fromHere() }
            if ("map" in choices) Action(CHOOSE_ON_MAP) { RouteSession.chooseOnMap() }
            if ("swap" in choices) Action(SWAP) { RouteSession.swap() }
        }
    }
    if (t != null) BasicText("To: $toName", style = TextStyle(color = Ink, fontSize = 13.sp))
}

/** T192: what the card calls [end]: "Your location" for a fix, else the name [EndNames] finds (looked up off the main thread). */
@Composable
private fun endName(end: RouteOrigin.End?): String {
    if (end == null) return ""
    if (end.isFix) return end.label
    var text by remember(end.lat, end.lon) { mutableStateOf(EndNames.cachedText(end)) }
    LaunchedEffect(end.lat, end.lon) {
        if (text == null) text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EndNames.text(end, 10_000) }
    }
    return text ?: EndNames.PENDING
}

/**
 * T192: the names of the route's placed ends ([PlaceName.nearest] over the packs on the device, as the map reads them),
 * on one worker thread, the last few kept by the point and the pack set, so the card and `GET /route` share one lookup.
 */
internal object EndNames {
    /** The card's words while the lookup runs. */
    const val PENDING = "..."

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-end-names").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<Triple<Double, Double, Int>, java.util.concurrent.Future<PlaceName.Found?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Double, Double, Int>, java.util.concurrent.Future<PlaceName.Found?>>?) = size > 8
    }

    private fun start(end: RouteOrigin.End): java.util.concurrent.Future<PlaceName.Found?>? {
        val packs = runCatching { DystopoApp.instance.tiles.packs }.getOrNull() ?: return null
        val key = Triple(end.lat, end.lon, System.identityHashCode(packs))
        synchronized(cache) {
            cache[key]?.let { return it }
            val f = worker.submit(java.util.concurrent.Callable {
                PlaceName.nearest({ z, x, y -> packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) } }, end.lat, end.lon)
            })
            cache[key] = f
            return f
        }
    }

    /** The feature found for [end], waiting up to [ms]; null when none, on a timeout, or with no packs. */
    fun found(end: RouteOrigin.End, ms: Long): PlaceName.Found? = runCatching { start(end)?.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()

    /** The card's words for [end] once the lookup is done, else null (and the lookup runs). */
    fun cachedText(end: RouteOrigin.End): String? {
        val f = start(end) ?: return PlaceName.NO_NAME
        return if (f.isDone) PlaceName.label(runCatching { f.get() }.getOrNull()) else null
    }

    /** The card's words for [end], waiting up to [ms] for the lookup. */
    fun text(end: RouteOrigin.End, ms: Long): String = PlaceName.label(found(end, ms))

    /** `GET /route` `ends`: each end's line as the card draws it and the feature that named it (coordinates in `from`, `to`). */
    fun json(from: RouteOrigin.End?, to: RouteOrigin.End?, waitMs: Long = 3_000): Map<String, Any?> {
        fun one(prefix: String, e: RouteOrigin.End?): Map<String, Any?>? = e?.let {
            if (it.isFix) linkedMapOf("line" to "$prefix: ${it.label}", "named" to null)
            else found(it, waitMs).let { n -> linkedMapOf("line" to "$prefix: ${PlaceName.label(n)}", "named" to n?.json()) }
        }
        return linkedMapOf("from" to one("From", from), "to" to one("To", to), "radiusM" to PlaceName.RADIUS_M)
    }
}

internal const val CHANGE = "Change"
internal const val CHOOSE_ON_MAP = "Choose on map"
internal const val SWAP = "Swap"

/** T102: the count of turn directions, opening their list (scrolls past a few lines). */
@Composable
private fun Directions(plan: io.github.jasondorjeshort.dystopo.routing.PlannedRoute) {
    val list = RouteSession.shownDirections(plan)
    if (list.isEmpty()) return
    val open = RouteSession.directionsOpen
    Action(if (open) "Hide directions" else io.github.jasondorjeshort.dystopo.app.trips.showDirectionsLabel(list.size)) { RouteSession.directionsOpen = !open }
    if (!open) return
    val units = io.github.jasondorjeshort.dystopo.app.AppSettings.units
    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((k, ins) in list.withIndex()) {
            val text = io.github.jasondorjeshort.dystopo.routing.DirectionsText.of(ins, plan.mode)
            val then = if (ins.stretchM > 0) "  " + io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(ins.stretchM, units) else ""
            BasicText("${k + 1}. $text$then", style = TextStyle(color = Ink, fontSize = 13.sp))
        }
    }
}

/**
 * T187: whether the water lists are open (the route card's and the trip card's; Compose state, touched only from the UI and
 * through `app.map.onMain` by `POST /route/water`).
 */
internal object WaterUi {
    var routeOpen by mutableStateOf(false)
    var tripOpen by mutableStateOf(false)
}

/**
 * T187: water along a route on a card ([RouteWater], [WaterText]): the line "Water: 3 crossings, the next is likely flowing in
 * 1.2 km", which opens and closes the list ([onToggle]), and "Longest stretch without water: 6.2 km" under it; open, each
 * item in route order with its distance along the route, its class and basis, and the gap to the next (scrolls past a
 * few rows), then where the words come from. T217: "Looking for water along the route" until the water is computed. Nothing
 * with the `waterAlongRoute` setting off. One composable, shared by the route card and the trip card.
 */
@Composable
internal fun WaterAlongLine(points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>, open: Boolean, onToggle: () -> Unit) {
    val listScroll = rememberScrollState()
    val rev = io.github.jasondorjeshort.dystopo.app.SettingsUi.revision // the setting's row recomposes the card
    if (!io.github.jasondorjeshort.dystopo.app.AppSettings.waterAlongRoute) return
    var water by remember(points, rev) { mutableStateOf(RouteWater.cached(points)) }
    var settled by remember(points, rev) { mutableStateOf(water != null || RouteWater.start(points) == null) } // T217
    LaunchedEffect(points, rev) {
        if (!settled) {
            water = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RouteWater.await(points, LineState.WAIT_MS) }
            settled = true
        }
    }
    if (!settled) return WorkingLine(RouteWater.WORKING)
    val r = water ?: return
    val units = io.github.jasondorjeshort.dystopo.app.AppSettings.units
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            WaterText.cardLine(r.items, units),
            style = TextStyle(color = if (r.items.isEmpty()) Ink else Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = if (r.items.isEmpty()) Modifier else Modifier.clickable(onClick = onToggle).padding(vertical = 2.dp),
        )
        WaterText.longestLine(r.items, r.lengthM, units)?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 12.sp)) }
        if (open && r.items.isNotEmpty()) {
            // T192: every item is a row; the box scrolls past about eight of them, with a thumb that says so.
            val rows = remember(r) { WaterListLayout.Rows(points, r.items.size) }
            Column(
                Modifier.heightIn(max = 160.dp).onGloballyPositioned { c -> rows.box = c.boundsInRoot().let { floatArrayOf(it.left, it.top, it.right, it.bottom) } }
                    .scrollbar(listScroll).verticalScroll(listScroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (i in r.items.indices) BasicText(
                    WaterText.row(r.items, i, r.lengthM, units), style = TextStyle(color = Ink, fontSize = 12.sp),
                    modifier = Modifier.onGloballyPositioned { c -> rows.rowTops[i] = c.boundsInRoot().let { floatArrayOf(it.top, it.bottom) } },
                )
            }
            SideEffect { WaterListLayout.last = rows }
            LaunchedEffect(rows) {
                androidx.compose.runtime.snapshotFlow { listScroll.value to listScroll.maxValue }.collect { (v, m) -> rows.scrollPx = v; rows.scrollMaxPx = m }
            }
            BasicText(WaterText.HINT, style = TextStyle(color = Ink.copy(alpha = 0.75f), fontSize = 11.sp))
        }
    }
}

/** T195: whether the terrain sheet under the card's line is open (Compose state; `POST /route/exposure?open=` sets it). */
internal object ExposureUi {
    var routeOpen by mutableStateOf(false)
}

/**
 * T195: the route's terrain on the card ([RouteExposure], [io.github.jasondorjeshort.dystopo.routing.ExposureText]): the
 * line "1.2 km on slopes over 30 degrees, facing north and east; 0.4 km over 35 degrees" (or "No slopes over 30 degrees
 * along the route"), which opens and closes the sheet behind it: the times on that ground, the distance and time along
 * ridges, each stretch in route order (scrolls past a few rows), then what the figures measure. T217: "Checking exposure"
 * until it is computed. Nothing for drive routes or without a DEM under the route.
 */
@Composable
internal fun ExposureLine(points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>, mode: TravelMode) {
    val listScroll = rememberScrollState()
    if (!RouteExposure.applies(mode)) return
    var res by remember(points, mode) { mutableStateOf(RouteExposure.cached(points, mode)) }
    var settled by remember(points, mode) { mutableStateOf(res != null || RouteExposure.start(points, mode) == null) } // T217
    LaunchedEffect(points, mode) {
        if (!settled) {
            res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RouteExposure.await(points, mode, LineState.WAIT_MS) }
            settled = true
        }
    }
    if (!settled) return WorkingLine(RouteExposure.WORKING)
    val e = res?.exposure ?: return
    val units = io.github.jasondorjeshort.dystopo.app.AppSettings.units
    val line = io.github.jasondorjeshort.dystopo.routing.ExposureText.cardLine(e, units) ?: return
    val open = ExposureUi.routeOpen
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            line, style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clickable { ExposureUi.routeOpen = !ExposureUi.routeOpen }.padding(vertical = 2.dp),
        )
        if (open) {
            val small = TextStyle(color = Ink, fontSize = 12.sp)
            io.github.jasondorjeshort.dystopo.routing.ExposureText.timeLine(e)?.let { BasicText(it, style = small) }
            io.github.jasondorjeshort.dystopo.routing.ExposureText.ridgeLine(e, units)?.let { BasicText(it, style = small) }
            if (e.stretches.isNotEmpty()) {
                Column(Modifier.heightIn(max = 140.dp).scrollbar(listScroll).verticalScroll(listScroll), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((i, st) in e.stretches.withIndex()) BasicText("${i + 1}. " + io.github.jasondorjeshort.dystopo.routing.ExposureText.row(st, units), style = small)
                }
            }
            BasicText(io.github.jasondorjeshort.dystopo.routing.ExposureText.HELP, style = TextStyle(color = Ink.copy(alpha = 0.75f), fontSize = 11.sp))
        }
    }
}

/**
 * T192: the water list as drawn last ([WaterAlongLine]), for `GET /route` and `GET /route/water` `water.list`: the rows
 * composed, which of them are inside the box now, the box's bounds (px in the window, for a swipe) and its scroll.
 */
internal object WaterListLayout {
    class Rows(val points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>, val count: Int) {
        @Volatile var box: FloatArray? = null
        val rowTops = arrayOfNulls<FloatArray>(count)
        @Volatile var scrollPx = 0
        @Volatile var scrollMaxPx = 0

        fun json(): Map<String, Any?> {
            val b = box
            val inside = if (b == null) emptyList() else rowTops.indices.filter { i -> rowTops[i]?.let { it[0] >= b[1] - 0.5f && it[1] <= b[3] + 0.5f } == true }
            return linkedMapOf(
                "rows" to rowTops.count { it != null }, "of" to count, "boxPx" to b?.map { Math.round(it) },
                "rowsInBox" to inside.size, "firstInBox" to inside.firstOrNull()?.plus(1), "lastInBox" to inside.lastOrNull()?.plus(1),
                "scrollPx" to scrollPx, "scrollMaxPx" to scrollMaxPx, "scrolls" to (scrollMaxPx > 0),
            )
        }
    }

    @Volatile var last: Rows? = null

    /** The list of [points] as drawn last, or null when it is not open for them. */
    fun json(points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>?): Map<String, Any?>? = last?.takeIf { it.points == points }?.json()
}

/**
 * T184: what the recorded tracks say about the route ([PaceSettings.pastEffort], off the main thread): "You did this
 * trail in 3:52, June" (the best and the latest), "(other direction)", or "Part of this trail was recorded"; nothing
 * until it is computed, nor for drive and crow.
 */
@Composable
private fun PastEffortLine(plan: io.github.jasondorjeshort.dystopo.routing.PlannedRoute) {
    val points = plan.route.points
    var r by remember(points, plan.mode) { mutableStateOf<PaceSettings.EffortResult?>(null) }
    LaunchedEffect(points, plan.mode) {
        r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PaceSettings.pastEffort(points, plan.mode, 60_000) }
    }
    val text = PaceSettings.pastEffortLine(r) ?: return
    BasicText(text, style = TextStyle(color = Ink, fontSize = 12.sp))
}

/** T108: the mode chips' row, wrapping onto a second line when they do not fit (font scale 2.0, four modes). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** The graph build progress while a route waits for it (polled; the build runs on its own thread). */
@Composable
private fun BuildProgress() {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val s = RoutePlanner.current
            text = when (s?.status) {
                "building" -> "Building the trail network" + (if (s.total > 0) " ${s.done * 100 / s.total}%" else "") + " (first use of this pack)"
                // T189: a graph that came with the map says so while it opens.
                "loading" -> if (runCatching { RouteSession.shippedGraphs.source(s.pack, s.file)?.get("source") == "shipped" }.getOrDefault(false)) GRAPH_SHIPPED else "Loading the trail network"
                "downloading" -> GRAPH_DOWNLOADING // T189: the graph shipped with the map is arriving
                "waiting" -> GRAPH_WAITING // T149: the heap guard's trim and wait before the build
                else -> null
            }
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(400) // T150: paused in the background
        }
    }
    text?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 12.sp)) }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

/** T217: a computed line's words until its answer, at the answer's height so the lines under it do not jump. */
@Composable
private fun WorkingLine(text: String) {
    BasicText(text, style = TextStyle(color = Ink.copy(alpha = 0.75f), fontSize = 13.sp), modifier = Modifier.padding(vertical = 2.dp))
}

/** T217: the least height of the card's action row, the touch target of each action (the drawn button is a little smaller). */
private val ACTION_TARGET = 48.dp
private val ButtonShape = RoundedCornerShape(10.dp)

/** T217: one of the card's actions, by its [CardActions.Item.style]: filled, outlined or text. */
@Composable
private fun CardAction(item: CardActions.Item, modifier: Modifier, onClick: () -> Unit) {
    val weight = FontWeight.Bold
    Box(modifier.heightIn(min = ACTION_TARGET).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        when (item.style) {
            CardActions.FILLED -> BasicText(
                item.label, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = weight),
                modifier = Modifier.background(Link, ButtonShape).padding(horizontal = 26.dp, vertical = 9.dp),
            )
            CardActions.OUTLINED -> BasicText(
                item.label, style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = weight),
                modifier = Modifier.border(1.dp, Link, ButtonShape).padding(horizontal = 18.dp, vertical = 9.dp),
            )
            else -> BasicText(item.label, style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = weight), modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

/** T217: the overflow's three dots, named for a screen reader and `uiautomator dump` by [label]. */
@Composable
internal fun OverflowDots(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(ACTION_TARGET).semantics { contentDescription = label }.clickable(onClick = onClick).drawBehind {
            val r = 2.dp.toPx()
            for (k in -1..1) drawCircle(Link, r, center.copy(y = center.y + k * 6.dp.toPx()))
        },
    )
}

/** T217: an item of the overflow, in the row it opens just above the actions (right-aligned, under the dots' column). */
@Composable
private fun OverflowItem(item: CardActions.Item, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = ACTION_TARGET).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(
            item.label, style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.background(Color.White, ButtonShape).border(1.dp, Link.copy(alpha = 0.4f), ButtonShape).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
