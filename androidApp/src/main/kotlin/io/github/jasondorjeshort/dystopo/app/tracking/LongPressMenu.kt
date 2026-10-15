package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import java.util.Locale

/**
 * T91: what a long-press on the map offers. Before, a long-press always started a route (T45); now it
 * opens a small card with "Waypoint here" (a free-standing waypoint, or on the recording track, then the
 * waypoint sheet to name it), "Route from here" (the T45 start, so the next long-press is the end) and
 * Cancel. While a route waits for its end (phase `start`) or is planning, a long-press goes straight to
 * the route as before, so planning stays two long-presses after the first menu tap.
 *
 * The map's long-press reaches us through [RouteSession.overlay]'s `onLongPress` hook ([install]), so the
 * routing files stay as they are. Compose state is written on the main thread.
 */
object LongPressMenu {
    /** The long-pressed point the card is open for, or null. */
    var point by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    @Volatile var presses = 0
        private set
    /** What the last long-press or menu tap did: `menu`, `route` (passed to the route), `waypoint`, `cancel`. */
    @Volatile var lastAction: String? = null
        private set

    /** Takes the map's long-press from the route session (idempotent). */
    fun install() {
        RouteSession.overlay.onLongPress = { lat, lon -> longPress(lat, lon) }
    }

    /** A long-press at ([lat], [lon]); main thread. */
    fun longPress(lat: Double, lon: Double) {
        presses++
        if (RouteSession.phase == "start" || RouteSession.phase == "planning") {
            point = null
            lastAction = "route"
            RouteSession.longPress(lat, lon)
            return
        }
        point = lat to lon
        lastAction = "menu"
    }

    /** "Waypoint here": a waypoint at the pressed point, its sheet opened. Main thread. */
    fun waypoint() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "waypoint"
        Thread { Tracking.of(DystopoApp.instance).dropWaypoint(lat, lon, via = "longpress") }.start()
    }

    /** "Route from here": the route's start, as a long-press did before T91. Main thread. */
    fun route() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "route"
        RouteSession.longPress(lat, lon)
    }

    fun cancel() {
        point = null
        lastAction = "cancel"
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "open" to (point != null), "lat" to point?.first, "lon" to point?.second,
        "presses" to presses, "lastAction" to lastAction, "routePhase" to RouteSession.phase,
    )
}

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val Link = Color(0xFF2C7BB6)

/**
 * The long-press card (bottom of the map, with the waypoint sheet). T106: its actions wrap (at font scale 2.0 "Cancel" was
 * squeezed into a column of letters), and the column it sits in stays above the update offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LongPressCard() {
    val p = LongPressMenu.point ?: return
    Column(
        Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(String.format(Locale.US, "%.5f, %.5f", p.first, p.second), style = TextStyle(color = Ink, fontSize = 12.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuAction("Waypoint here") { LongPressMenu.waypoint() }
            MenuAction("Route from here") { LongPressMenu.route() }
            MenuAction("Cancel") { LongPressMenu.cancel() }
        }
    }
}

@Composable
private fun MenuAction(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
