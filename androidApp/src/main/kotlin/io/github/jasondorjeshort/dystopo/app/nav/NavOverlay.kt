package io.github.jasondorjeshort.dystopo.app.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.controls.MapOrientation
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.controls.Orientation
import io.github.jasondorjeshort.dystopo.app.navCardEdge

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xF2FFFCF5)
private val Purple = Color(0xFF7B3294)
private val Alert = Color(0xFFD7191C)
private val Link = Color(0xFF2C7BB6)

/**
 * The navigation stats bar (T61), across the top of the map in large type: the next waypoint with
 * distance and time, the end with distance, time left and arrival clock; when off route a red banner
 * with an arrow and "Head W 140 m to rejoin"; camera buttons (Follow, Heading up, Recenter after a
 * pan) and Stop. Nothing shows when not navigating. T65: Snooze 10 min / Quiet until back in the off-route
 * banner (Unsnooze while snoozed), "No GPS fix for N s" when the position is old, and the card's bottom
 * edge goes to [io.github.jasondorjeshort.dystopo.app.TopChips] so the Tracks and Routes chips stack below it.
 * T102: the next turn direction first, in large type with its distance and an arrow for the turn, in the alert
 * colour once it is within its lead distance. T163: the turn-around clock under the end ([TurnBack]). T180: "Heading
 * back" under it once the direction flips; and while a track records with no route navigated, the free-tracking clock
 * in the card's place ([WayBackCard], [WayBackClock]). T187: the next water ahead on one line under them ([WaterNext]).
 * T194: the next terrain shade on one line under the end ([ShadeNext]).
 */
@Composable
fun NavOverlay() {
    val chips = io.github.jasondorjeshort.dystopo.app.TopChips
    val u = NavSession.ui
    if (u == null) {
        val wb = NavUiState.wayBack
        if (wb != null) {
            WayBackCard(wb, chips)
            SideEffect { chips.navHeaderDp = 0f; SpeakerLayout.boundsPx = null }
            return
        }
        SideEffect { if (chips.navCardBottom != 0.dp) chips.navCardBottom = 0.dp; chips.navHeaderDp = 0f; chips.navCardPx = null; SpeakerLayout.boundsPx = null; WayBackLayout.boundsPx = null; NavCardBand.bandDp = 0f }
        return
    }
    // T122 capped the card's font scale at 1.5 (at 2.0 the card with the off-route banner covered the whole screen). T212
    // (the research `cards-at-large-font-scales`: a capped font scale fails WCAG 1.4.4) lifts that cap, departing from
    // T122's choice: the card is drawn at the user's scale, its height capped at the measured band ([navCardCap]) with the
    // header (the next turn, the off-route banner) and the camera actions in view and the other lines scrolling between.
    NavCard(u, chips)
}

/**
 * T212: the navigation card's (and the free-tracking card's) height cap, dp: from its top (under the status bar and the
 * top chip row) down to the bottom chrome (the credit line, the track panels, the trip card and the update offer, as the
 * scale bar reads them), less what stacks under the card: on the left the online indicator and the scale bar's line
 * ([BottomPanels.mapRowDp]), on the right the chips that follow the card (Waypoint, Tracks, Routes; measured from the
 * card's bottom to the lowest of them). Measured, never a fixed height; [NavCardBand] reports the parts.
 */
@Composable
private fun navCardCap(maxHeight: Dp, top: Dp): Dp {
    val density = LocalDensity.current
    val chips = io.github.jasondorjeshort.dystopo.app.TopChips
    val panels = io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
    val shownPanels = if (panels.trackPanelsTop > panels.trackPanelsBottom) panels.trackPanelsTop else 0.dp
    val floor = maxOf(io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom, shownPanels, io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop)
    val bottomPad = if (floor > 0.dp) floor + 6.dp else with(density) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() } + 32.dp
    // T224: the online column's and the right-hand chips' own heights count while they are hidden too: a card that took
    // their room once they hid would keep them hidden for good (the chips hide rather than sit under the credit line).
    val online = maxOf(
        io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChips?.let { with(density) { (it[3] - it[1]).toDp() } } ?: 0.dp,
        with(density) { chips.ownHeightPx(io.github.jasondorjeshort.dystopo.app.TopChips.Chip.ONLINE).toDp() },
    )
    val left = 6.dp + online + panels.mapRowDp(density)
    val lowestRight = maxOf(chips.routesChipBottom, chips.trackColumnBottom)
    val below = if (chips.navCardBottom > 0.dp && lowestRight > chips.navCardBottom) lowestRight - chips.navCardBottom else 0.dp
    val own = with(density) { chips.rightStackPx(density.density).toDp() }
    val right = maxOf(below, own).let { if (it > 0.dp) it + 8.dp else 0.dp }
    val bands = io.github.jasondorjeshort.dystopo.app.routing.CardBands
    val measured = bands.navBand(maxHeight.value, top.value, bottomPad.value, left.value, right.value)
    // The least the card keeps (landscape at font scale 2.0 leaves no band at all under it): its header, actions and two
    // lines, within the window down to the bottom chrome; the chips that follow it then move down with it.
    val l = NavCardBand.layout
    val least = with(density) { (l.headerPx + l.footerPx).toDp() + 36.sp.toDp() } + 4.dp * 2 + 20.dp
    val band = bands.atLeast(measured, least.value, (maxHeight - top - bottomPad).value).dp
    SideEffect {
        NavCardBand.measuredBandDp = measured
        NavCardBand.bandDp = band.value; NavCardBand.belowLeftDp = left.value; NavCardBand.belowRightDp = right.value; NavCardBand.bottomPadDp = bottomPad.value
        NavCardBand.fontScale = density.fontScale
    }
    return band
}

/**
 * T212: the navigation card's band as last computed ([navCardCap]) and the font scale it was drawn at (the system's,
 * since T122's 1.5 cap is lifted), with its [layout] (header, scrolling lines, actions); `GET /nav` `layout.navCard`.
 */
internal object NavCardBand {
    @Volatile var bandDp = 0f
    @Volatile var measuredBandDp = 0f
    @Volatile var topDp = 0f
    @Volatile var searchBottomDp = 0f
    @Volatile var belowLeftDp = 0f
    @Volatile var belowRightDp = 0f
    @Volatile var bottomPadDp = 0f
    @Volatile var fontScale = 1f
    val layout = io.github.jasondorjeshort.dystopo.app.routing.CardLayout()

    fun json(): Map<String, Any?> = linkedMapOf(
        "fontScale" to fontScale, "bandDp" to bandDp, "measuredBandDp" to measuredBandDp,
        // T212: the card's top and the search column's bottom it stays under (dp from the window top).
        "topDp" to topDp, "searchBottomDp" to searchBottomDp, "clearOfSearch" to (searchBottomDp <= 0f || topDp >= searchBottomDp - 0.5f), "belowLeftDp" to belowLeftDp, "belowRightDp" to belowRightDp,
        "bottomPadDp" to bottomPadDp,
    ) + layout.json()
}

/** T212: `GET /nav` `layout.navCard` (also while the free-tracking card shows in its place). */
fun navCardJson(): Map<String, Any?> = NavCardBand.json()

/** T212: the content description of the navigation card's scrolling lines. */
internal const val NAV_DETAILS = "Navigation details"

private val PaperOpaque = Color(0xFFFFFCF5)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NavCard(u: NavSession.Ui, chips: io.github.jasondorjeshort.dystopo.app.TopChips) {
    val density = LocalDensity.current
    androidx.compose.runtime.DisposableEffect(Unit) { chips.navCardShown(); onDispose { } } // T146: the draw check counts from here (before the first draw)
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusBar = with(density) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }
        val padV = 10.dp
        val topPad = navCardTop(statusBar)
        val cap = navCardCap(maxHeight, statusBar + topPad)
        io.github.jasondorjeshort.dystopo.app.routing.CappedCard(
            cap = (cap - padV * 2).coerceAtLeast(0.dp), layout = NavCardBand.layout, scroll = scroll, description = NAV_DETAILS,
            paper = PaperOpaque, spacing = 4.dp,
            // Below the top chip row (search, Settings, Packs), which stays usable while navigating; T65: the Tracks
            // and Routes chips move below this card (TopChips), so they stay reachable.
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = topPad)
                .widthIn(max = 520.dp).fillMaxWidth()
                .navCardEdge() // T146: the chips below it move in the same layout pass (TopChips.followNavCard)
                .control("navigationCard") // T238
                .onGloballyPositioned { c ->
                    chips.edge(c, density).let { if (it != chips.navCardBottom) chips.navCardBottom = it }
                    c.boundsInRoot().let { b -> chips.navCardPx = floatArrayOf(b.left, b.top, b.right, b.bottom) } // T137: `GET /nav` `layout.navCardPx`
                }
                .drawWithContent { drawContent(); chips.navCardDrawn(density.density) } // T146: `GET /nav` `layout.drawCheck`
                .background(Paper, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = padV),
            header = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // T128: the header's height as laid out (`GET /nav` `layout.navHeaderDp`), 48 dp at 1.0 before the flat help icon
                    modifier = Modifier.onGloballyPositioned { c -> chips.navHeaderDp = c.size.height / density.density },
                ) {
                    // T212: the route's name goes under "Navigating" when the row has no room for it, wrapping rather than
                    // being cut at one line.
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(
                            if (u.arrived) "Arrived" else "Navigating",
                            style = TextStyle(color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        )
                        BasicText(u.name, style = TextStyle(color = Ink, fontSize = 13.sp))
                    }
                    u.heading?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
                    // T137: mute this navigation's spoken directions (the setting stays on); hidden while speech is off or the
                    // engine cannot speak (the notice below says why).
                    val speech = NavSpeech.ui
                    if (io.github.jasondorjeshort.dystopo.app.AppSettings.navSpeech && speech.failure == null) SpeakerButton(speech.muted)
                    else SideEffect { SpeakerLayout.boundsPx = null }
                    HelpIcon("nav.card", flat = true) // T122: what the card shows; T128: flat, so the header is its text's height
                }
                // T212: the off-route banner is the primary line while it shows: in the header, above the turn.
                if (u.rejoin != null) {
                    Row(
                        Modifier.fillMaxWidth().background(if (u.offRoute) Alert else Color(0xFFFDAE61), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        u.rejoinArrowDeg?.let { Arrow(it) }
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                if (u.offRoute) "OFF ROUTE" else "Leaving the route",
                                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            )
                            BasicText(u.rejoin, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                            // T65: silence the alert for a while, or until back on the route.
                            if (u.offRoute && u.snoozed == null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { // T122: each action whole, wrapping
                                    Action("Snooze ${io.github.jasondorjeshort.dystopo.app.AppSettings.navSnoozeMin} min", Color.White) { NavSession.snooze(io.github.jasondorjeshort.dystopo.app.AppSettings.navSnoozeMin.toDouble()) } // T91: a setting
                                    Action("Quiet until back", Color.White) { NavSession.snooze(null) }
                                }
                            }
                        }
                        HelpIcon("nav.offRoute") // T122: what the banner means and what Snooze does
                    }
                }
                u.turn?.let { Turn(it, u.turnDistance, u.turnDeg, u.turnType, u.turnSoon) } // T102
            },
            body = {
                // T137: once per navigation, when the phone cannot say the directions (no engine, or no voice for them).
                NavSpeech.ui.notice?.let { text ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        BasicText(text, Modifier.weight(1f), style = TextStyle(color = Alert, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.size(8.dp))
                        Action("Dismiss") { NavSpeech.dismissNotice() }
                    }
                }
                if (u.waiting) {
                    BasicText("Waiting for a location fix", style = TextStyle(color = Ink, fontSize = 18.sp))
                }
                // T65: an old position says so (the numbers below are as of the last fix).
                u.fixAge?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(it, Modifier.weight(1f), style = TextStyle(color = Alert, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        // T128: not flat: its target would overlap the header icon's and take taps on that icon's lower part
                        HelpIcon("nav.fixLost") // T122: why the fix is lost and what the card does meanwhile
                    }
                }
                if (u.next != null && u.nextDistance != null) {
                    Stat("Next", u.next, u.nextDistance, u.nextTime ?: "")
                }
                Stat("End", null, u.endDistance, u.endTime + if (u.endClock.isNotEmpty()) "  (${u.endClock})" else "")
                u.shade?.let { ShadeNext(it) } // T194: above the turn-back block, whose label grows when the terrain sunset lands
                u.turnBack?.let { TurnBack(it, u.turnBackLabel, u.turnBackColour) } // T163
                if (u.headingBack && !u.arrived) HeadingBack() // T180
                u.water?.let { WaterNext(it) } // T187
                u.snoozed?.let { text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(text, style = TextStyle(color = Ink, fontSize = 13.sp))
                        Action("Unsnooze") { NavSession.snooze(null, off = true) }
                    }
                }
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    // T100: the app's one orientation state, as the recentre button shows it: Recenter when the camera
                    // is off the fix (the orientation stays), else the change of orientation (from north-up to heading
                    // up; from heading up or free rotate to north-up).
                    val o = io.github.jasondorjeshort.dystopo.app.controls.OrientationUi.state
                    when {
                        !o.following -> Action("Recenter") { io.github.jasondorjeshort.dystopo.app.controls.Recenter.tap(source = "navCard") }
                        o.orientation == Orientation.NORTH_UP -> Action("Heading up") { MapOrientation.set(Orientation.FACING, "navCard") }
                        else -> Action("North up") { MapOrientation.set(Orientation.NORTH_UP, "navCard") }
                    }
                    Action("Stop", Alert) { NavSession.stop() }
                }
            },
        )
    }
}

/** The navigation card's top under the status bar: below the top chip row (search, Settings, Packs). */
private val NAV_CARD_TOP = 60.dp

/**
 * T212 (the verifier's note): the card's top under the status bar: [NAV_CARD_TOP], or lower when the search box above it
 * wraps to two lines (display size largest at font scale 2.0, where the card at the user's scale reached 35 px over it):
 * 6 dp under the search column's measured bottom (`TopChips.searchBottom`, from the window top).
 */
private fun navCardTop(statusBar: Dp): Dp {
    val search = io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom
    val top = if (search > 0.dp) maxOf(NAV_CARD_TOP, search + 6.dp - statusBar) else NAV_CARD_TOP
    NavCardBand.topDp = (statusBar + top).value
    NavCardBand.searchBottomDp = search.value
    return top
}

/**
 * T180: the free-tracking turn-around clock in the navigation card's place, while a track records and no route is
 * navigated: the clock's line and its label in [TurnBack]'s colours, and "Heading back" once the direction flips. The
 * chips below move under it as under the navigation card ([TopChips]); `GET /nav/turnaround` `ui` and `layout`. T212: at
 * the user's font scale (T122's 1.5 cap lifted), capped at the navigation card's band, "Heading back" scrolling under the clock.
 */
@Composable
private fun WayBackCard(w: WayBackClock.Ui, chips: io.github.jasondorjeshort.dystopo.app.TopChips) {
    val density = LocalDensity.current
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusBar = with(density) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }
        val padV = 8.dp
        val topPad = navCardTop(statusBar)
        val cap = navCardCap(maxHeight, statusBar + topPad)
        io.github.jasondorjeshort.dystopo.app.routing.CappedCard(
            cap = (cap - padV * 2).coerceAtLeast(0.dp), layout = NavCardBand.layout, scroll = scroll, description = NAV_DETAILS,
            paper = PaperOpaque, spacing = 4.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = topPad)
                .widthIn(max = 520.dp).fillMaxWidth()
                .navCardEdge()
                .control("navigationCard") // T238: the way-back card stands in the navigation card's place
                .onGloballyPositioned { c ->
                    chips.edge(c, density).let { if (it != chips.navCardBottom) chips.navCardBottom = it }
                    c.boundsInRoot().let { b -> floatArrayOf(b.left, b.top, b.right, b.bottom).let { px -> chips.navCardPx = px; WayBackLayout.boundsPx = px } }
                }
                .background(Paper, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = padV),
            header = { TurnBack(w.text, w.label, w.colour) },
            body = { if (w.headingBack) HeadingBack() },
            footer = {},
        )
    }
}

/** T180: where the free-tracking card was last laid out (px; `GET /nav/turnaround` `layout`), null while hidden. */
internal object WayBackLayout {
    @Volatile var boundsPx: FloatArray? = null
}

/**
 * T187: the next water ahead ("Water in 1.2 km: stream, likely flowing"), one line at font scale 1.0. T212: it wraps
 * rather than being cut with an ellipsis (the card's lines scroll at large font scales, so nothing is lost).
 */
@Composable
private fun WaterNext(text: String) {
    BasicText(text, style = TextStyle(color = Color(0xFF1F78B4), fontSize = 14.sp, fontWeight = FontWeight.Bold))
}

/**
 * T194: the next terrain shade along the line ("Terrain shade in 400 m for 10 min") in the shade overlay's blue, under the
 * end and above the turn-back block (whose label can run to three lines at font scale 2.0 and changes when the terrain
 * sunset lands), so the line never moves then. T212: it wraps rather than being cut with an ellipsis.
 */
@Composable
private fun ShadeNext(text: String) {
    BasicText(text, style = TextStyle(color = Color(0xFF1E3A6E), fontSize = 14.sp, fontWeight = FontWeight.Bold))
}

/** T180: the direction flipped to the way back (the notice the survey's users ask for). */
@Composable
private fun HeadingBack() {
    BasicText(WayBackClock.HEADING_BACK, style = TextStyle(color = Purple, fontSize = 16.sp, fontWeight = FontWeight.Bold))
}

/**
 * T137: the speaker button in the card's header (ADR 0039 survey: a one-tap sound control on the guidance screen, two
 * states, this navigation only). A 48 dp target laid out at the icon's height and centred on it, like the flat help
 * icon beside it (T128), so the header stays its text's height. Muted: the speaker with a cross, in the alert colour.
 */
@Composable
private fun SpeakerButton(muted: Boolean) {
    val icon = 20.dp
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val h = minOf(p.height, icon.roundToPx()).coerceIn(c.minHeight, c.maxHeight)
                layout(p.width, h) { p.place(0, (h - p.height) / 2) }
            }
            .size(48.dp)
            .onGloballyPositioned { c -> SpeakerLayout.boundsPx = c.boundsInRoot().let { b -> floatArrayOf(b.left, b.top, b.right, b.bottom) } }
            .clickable(role = Role.Button) { NavSpeech.setMuted(!muted) }
            .semantics { contentDescription = if (muted) "Unmute spoken directions" else "Mute spoken directions" },
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (muted) Alert else Purple
        Canvas(Modifier.size(icon)) {
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * 0.08f, h * 0.36f)
                lineTo(w * 0.28f, h * 0.36f)
                lineTo(w * 0.52f, h * 0.14f)
                lineTo(w * 0.52f, h * 0.86f)
                lineTo(w * 0.28f, h * 0.64f)
                lineTo(w * 0.08f, h * 0.64f)
                close()
            }
            drawPath(body, ink)
            val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
            if (muted) {
                drawLine(ink, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.92f, h * 0.64f), stroke.width, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.92f, h * 0.36f), Offset(w * 0.64f, h * 0.64f), stroke.width, StrokeCap.Round)
            } else {
                drawArc(ink, -45f, 90f, false, Offset(w * 0.36f, h * 0.30f), Size(w * 0.40f, h * 0.40f), style = stroke)
                drawArc(ink, -45f, 90f, false, Offset(w * 0.24f, h * 0.12f), Size(w * 0.70f, h * 0.76f), style = stroke)
            }
        }
    }
}

/** T137: where the speaker button was last laid out (px; `GET /nav` `layout.speakerButtonPx`), null while hidden. */
internal object SpeakerLayout {
    @Volatile var boundsPx: FloatArray? = null
}

/**
 * T122: how the turn text was last laid out on the card (`GET /nav` `ui.turnLayout`): its lines, whether it was cut and
 * the size it was drawn at (sp, before the font scale), so a check at font scale 2.0 needs no screenshot to say it is whole.
 * T212: always 18 sp now and never cut (it no longer steps down to 13 sp nor stops at three lines).
 */
internal object TurnText {
    @Volatile var last: Map<String, Any?>? = null
        private set

    fun measured(text: String, lines: Int, cut: Boolean, sp: Float) {
        val m = linkedMapOf<String, Any?>("text" to text, "lines" to lines, "cut" to cut, "fontSp" to sp)
        if (m != last) last = m
    }
}

/**
 * T122: the help texts of the navigation card's icons (T109's follow-up, after T114's card): the card itself, the
 * lost-fix line and the off-route banner. `POST /help/open` cannot bring up a navigation session, so each `needs` one
 * (409 otherwise).
 */
internal val NAV_HELP: List<HelpText> = listOf(
    HelpText("nav.card", "Navigation card", "The map: while navigating", needs = "navigating") {
        "While you follow a route this card shows the next turn and how far away it is, then the next waypoint and the " +
            "end with the distance, time left and arrival time. Heading up turns the map with you, Recenter brings it back " +
            "to you and Stop ends navigation. The speaker mutes spoken directions until this navigation ends."
    },
    HelpText("nav.fixLost", "No GPS fix", "The map: navigation card, when the position is old", needs = "navigating with an old position") {
        "The phone has had no new position for longer than GPS fix stale after (Settings › Navigation and tracking). The " +
            "card's numbers are from the last position until a new one comes; under trees or in a canyon that can take a while."
    },
    // T163: the turn-around clock's line.
    HelpText("nav.turnAround", "Turn back by", "The map: navigation card, turn-back time", needs = "navigating, after a fix") {
        "The latest time to turn back and still be out by sunset, or your deadline, with your margin to spare: amber " +
            "the margin before, red at it, then when you will be out. Recording without a route, it times the way back " +
            "along your track. Sunset is for a level horizon; Terrain sunset adds the ridge's time and counts to it if earlier."
    },
    HelpText("nav.offRoute", "Off route", "The map: navigation card, off the route", needs = "navigating, off the route") {
        "You have left the route. The arrow points the shortest way back to it, with the direction and distance below. " +
            "Snooze silences the warning for a while, for a detour on purpose; Quiet until back silences it until you rejoin."
    },
)

/**
 * One line of numbers: "Next 1.2 mi 0:24 Name", "End 51.0 mi 1:49 (11:46)". T122: the parts wrap whole onto the next line
 * when they do not fit (font scale 2.0), where the time used to break inside itself and sit above the distance.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Stat(label: String, name: String?, distance: String, time: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
        BasicText(label, style = TextStyle(color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 3.dp))
        BasicText(distance, softWrap = false, style = TextStyle(color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold))
        if (time.isNotBlank()) BasicText(time.trim(), softWrap = false, style = TextStyle(color = Ink, fontSize = 22.sp), modifier = Modifier.padding(bottom = 1.dp))
        if (name != null) {
            BasicText(name, style = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * T163: the turn-around clock: "Turn back by 14:10" in large type with what it counts to under it ("Sunset 19:12, flat
 * horizon, 30 min margin, standard pace"); amber within the margin of the time, red from it ("Turn back now, out by
 * 18:52"), and on the way out the arrival clock ("Out by 18:05") coloured by the deadline.
 */
@Composable
private fun TurnBack(text: String, label: String?, colour: String) {
    val (bg, ink) = when (colour) {
        "red" -> Alert to Color.White
        "amber" -> Color(0xFFFDAE61) to Ink
        else -> Color(0x1F7B3294) to Ink
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(text, style = TextStyle(color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            label?.let { BasicText(it, style = TextStyle(color = ink, fontSize = 12.sp)) }
        }
        HelpIcon("nav.turnAround")
    }
}

/**
 * T102: the next turn direction: an arrow bent like the turn (straight up for depart, continue and arrive), the
 * distance to it in large type, the text below; purple with white type within the lead distance ([soon]).
 */
@Composable
private fun Turn(text: String, distance: String?, turnDeg: Int?, type: String?, soon: Boolean) {
    val ink = if (soon) Color.White else Ink
    Row(
        Modifier.fillMaxWidth().background(if (soon) Purple else Color(0x1F7B3294), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val straight = type == null || type in setOf("depart", "continue", "arrive", "via", "off-network")
        Arrow(if (straight) 0f else (turnDeg ?: 0).coerceIn(-150, 150).toFloat(), if (soon) Color.White else Purple)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            distance?.let { BasicText(it, style = TextStyle(color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)) }
            // T122: a cued text ("Head northeast, then bear left", T116) at font scale 2.0 ran past the two lines the card
            // allowed and lost its end. T212: the text keeps the user's size and takes the lines it needs (T122 stepped it
            // down to 13 sp and cut it past three lines; the card's lines now scroll instead, so nothing shrinks or is cut).
            BasicText(
                text,
                style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                onTextLayout = { r -> TurnText.measured(text, r.lineCount, r.hasVisualOverflow, r.layoutInput.style.fontSize.value) },
            )
        }
    }
}

/** An arrow (white unless [color]) pointing [deg] clockwise from the top of the screen. */
@Composable
private fun Arrow(deg: Float, color: Color = Color.White) {
    Canvas(Modifier.size(36.dp).rotate(deg)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w, h * 0.55f)
            lineTo(w * 0.64f, h * 0.55f)
            lineTo(w * 0.64f, h)
            lineTo(w * 0.36f, h)
            lineTo(w * 0.36f, h * 0.55f)
            lineTo(0f, h * 0.55f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        softWrap = false, // T122: "Quiet until back" broke a letter or two per line at font scale 2.0
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
