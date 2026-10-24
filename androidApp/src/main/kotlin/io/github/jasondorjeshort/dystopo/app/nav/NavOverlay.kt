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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.controls.MapOrientation
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.controls.Orientation

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
 * colour once it is within its lead distance.
 */
@Composable
fun NavOverlay() {
    val chips = io.github.jasondorjeshort.dystopo.app.TopChips
    val u = NavSession.ui
    if (u == null) {
        SideEffect { if (chips.navCardBottom != 0.dp) chips.navCardBottom = 0.dp; chips.navHeaderDp = 0f; chips.navCardPx = null; SpeakerLayout.boundsPx = null }
        return
    }
    // T122: the card's type is large already (28 sp numbers); at the system's font scale 2.0 the card with the off-route
    // banner covered the whole screen, so it follows the font scale up to NAV_MAX_FONT_SCALE, as the map chips cap theirs.
    val sys = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides if (sys.fontScale <= NAV_MAX_FONT_SCALE) sys else Density(sys.density, NAV_MAX_FONT_SCALE)) {
        NavCard(u, chips)
    }
}

/** T122: the font scale the navigation card follows at most (the map chips stop at 1.3, `TopChips.MAX_FONT_SCALE`). */
const val NAV_MAX_FONT_SCALE = 1.5f

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NavCard(u: NavSession.Ui, chips: io.github.jasondorjeshort.dystopo.app.TopChips) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Column(
            // Below the top chip row (search, Settings, Packs), which stays usable while navigating; T65: the Tracks
            // and Routes chips move below this card (TopChips), so they stay reachable.
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 60.dp)
                .widthIn(max = 520.dp).fillMaxWidth()
                .onGloballyPositioned { c ->
                    chips.edge(c, density).let { if (it != chips.navCardBottom) chips.navCardBottom = it }
                    c.boundsInRoot().let { b -> chips.navCardPx = floatArrayOf(b.left, b.top, b.right, b.bottom) } // T137: `GET /nav` `layout.navCardPx`
                }
                .background(Paper, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // T128: the header's height as laid out (`GET /nav` `layout.navHeaderDp`), 48 dp at 1.0 before the flat help icon
                modifier = Modifier.onGloballyPositioned { c -> chips.navHeaderDp = c.size.height / density.density },
            ) {
                BasicText(
                    if (u.arrived) "Arrived" else "Navigating",
                    style = TextStyle(color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(
                    "  " + u.name, maxLines = 1,
                    style = TextStyle(color = Ink, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                u.heading?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
                // T137: mute this navigation's spoken directions (the setting stays on); hidden while speech is off or the
                // engine cannot speak (the notice below says why).
                val speech = NavSpeech.ui
                if (io.github.jasondorjeshort.dystopo.app.AppSettings.navSpeech && speech.failure == null) SpeakerButton(speech.muted)
                else SideEffect { SpeakerLayout.boundsPx = null }
                HelpIcon("nav.card", flat = true) // T122: what the card shows; T128: flat, so the header is its text's height
            }
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
            u.turn?.let { Turn(it, u.turnDistance, u.turnDeg, u.turnType, u.turnSoon) } // T102
            if (u.next != null && u.nextDistance != null) {
                Stat("Next", u.next, u.nextDistance, u.nextTime ?: "")
            }
            Stat("End", null, u.endDistance, u.endTime + if (u.endClock.isNotEmpty()) "  (${u.endClock})" else "")
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
            u.snoozed?.let { text ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(text, style = TextStyle(color = Ink, fontSize = 13.sp))
                    Action("Unsnooze") { NavSession.snooze(null, off = true) }
                }
            }
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
        }
    }
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

/** T122: the turn text's line cap, and the smallest size (sp) it steps down to before it takes the last line or is cut. */
private const val TURN_MAX_LINES = 3
private const val TURN_MIN_SP = 13

/**
 * T122: how the turn text was last laid out on the card (`GET /nav` `ui.turnLayout`): its lines, whether it was cut and
 * the size it was drawn at (sp, before the font scale), so a check at font scale 2.0 needs no screenshot to say it is whole.
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
            "to you and Stop ends navigation."
    },
    HelpText("nav.fixLost", "No GPS fix", "The map: navigation card, when the position is old", needs = "navigating with an old position") {
        "The phone has had no new position for longer than GPS fix stale after (Settings › Navigation and tracking). The " +
            "card's numbers are from the last position until a new one comes; under trees or in a canyon that can take a while."
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
            BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.padding(bottom = 3.dp))
        }
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
            // allowed and lost its end; the text now steps down to fit, and takes a third line before it is ever cut.
            BasicText(
                text, maxLines = TURN_MAX_LINES, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                autoSize = TextAutoSize.StepBased(minFontSize = TURN_MIN_SP.sp, maxFontSize = 18.sp, stepSize = 1.sp),
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
