package io.github.jasondorjeshort.dystopo.app.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.controls.MapOrientation
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
        SideEffect { if (chips.navCardBottom != 0.dp) chips.navCardBottom = 0.dp }
        return
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Column(
            // Below the top chip row (search, Settings, Packs), which stays usable while navigating; T65: the Tracks
            // and Routes chips move below this card (TopChips), so they stay reachable.
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 60.dp)
                .onGloballyPositioned { c -> chips.edge(c, density).let { if (it != chips.navCardBottom) chips.navCardBottom = it } }
                .widthIn(max = 520.dp).fillMaxWidth()
                .background(Paper, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            }
            if (u.waiting) {
                BasicText("Waiting for a location fix", style = TextStyle(color = Ink, fontSize = 18.sp))
            }
            // T65: an old position says so (the numbers below are as of the last fix).
            u.fixAge?.let { BasicText(it, style = TextStyle(color = Alert, fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
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
                    Column {
                        BasicText(
                            if (u.offRoute) "OFF ROUTE" else "Leaving the route",
                            style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        )
                        BasicText(u.rejoin, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        // T65: silence the alert for a while, or until back on the route.
                        if (u.offRoute && u.snoozed == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Action("Snooze ${io.github.jasondorjeshort.dystopo.app.AppSettings.navSnoozeMin} min", Color.White) { NavSession.snooze(io.github.jasondorjeshort.dystopo.app.AppSettings.navSnoozeMin.toDouble()) } // T91: a setting
                                Action("Quiet until back", Color.White) { NavSession.snooze(null) }
                            }
                        }
                    }
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

@Composable
private fun Stat(label: String, name: String?, distance: String, time: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(label, style = TextStyle(color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(end = 8.dp, bottom = 3.dp))
        BasicText(distance, style = TextStyle(color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold))
        BasicText("  $time", style = TextStyle(color = Ink, fontSize = 22.sp), modifier = Modifier.padding(bottom = 1.dp))
        if (name != null) {
            BasicText("  $name", maxLines = 1, style = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.padding(bottom = 3.dp))
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
            BasicText(text, maxLines = 2, style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
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
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
