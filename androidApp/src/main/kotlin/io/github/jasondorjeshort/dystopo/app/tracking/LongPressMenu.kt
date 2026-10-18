package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
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
 *
 * T114: "Route to here" comes first, the destination-first action of the mainstream navigation apps (finding
 * `docs/findings/2026-10-17-route-to-flows-in-other-apps.md`): it plans at once from the phone's position to the pressed
 * point ([RouteSession.routeTo]). "Waypoint here" keeps its place near the front (T109), and "Route from here" keeps the
 * two long-presses for a trip planned from a trailhead. After the route card's Choose on map (`pickStart`) a long-press
 * goes to the route like the end press does.
 */
object LongPressMenu {
    /** The long-pressed point the card is open for, or null. */
    var point by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    @Volatile var presses = 0
        private set
    /**
     * What the last long-press or menu tap did: `menu`, `route` (passed to the route, or Route from here), `routeTo`
     * (T114: Route to here), `waypoint`, `cancel`.
     */
    @Volatile var lastAction: String? = null
        private set

    /** T114: the card's actions in their order (`GET /map/longpress` `actions`). */
    val ACTIONS = listOf(ROUTE_TO, "Waypoint here", "Route from here", "Cancel")

    /** Takes the map's long-press from the route session (idempotent). */
    fun install() {
        RouteSession.overlay.onLongPress = { lat, lon -> longPress(lat, lon) }
    }

    /** True while a long-press belongs to the route: its end (`start`), its start after Choose on map, or planning. */
    private fun toRoute(): Boolean = RouteSession.phase.let { it == "start" || it == "planning" || it == "pickStart" }

    /** A long-press at ([lat], [lon]); main thread. */
    fun longPress(lat: Double, lon: Double) {
        presses++
        if (toRoute()) {
            point = null
            lastAction = "route"
            RouteSession.longPress(lat, lon)
            return
        }
        point = lat to lon
        lastAction = "menu"
    }

    /** T114, "Route to here": a route from the phone's position to the pressed point, planned at once. Main thread. */
    fun routeTo() {
        val (lat, lon) = point ?: return
        point = null
        lastAction = "routeTo"
        RouteSession.routeTo(lat, lon)
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

    /**
     * T109: opens the card at the map's centre, for `POST /help/open?key=longPress.menu` (main thread); not while a route
     * waits for its end, where a long-press goes to the route.
     */
    fun showAtCentre() {
        if (point != null || toRoute()) return
        val c = DystopoApp.instance.map.map?.cameraPosition?.target ?: return
        point = c.latitude to c.longitude
        lastAction = "menu"
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "open" to (point != null), "lat" to point?.first, "lon" to point?.second,
        "presses" to presses, "lastAction" to lastAction, "routePhase" to RouteSession.phase,
        "actions" to ACTIONS, // T114
    )
}

/** T114: the card's first action. */
internal const val ROUTE_TO = "Route to here"

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(String.format(Locale.US, "%.5f, %.5f", p.first, p.second), Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 12.sp))
            HelpIcon("longPress.menu") // T109: dropping a waypoint, which was hard to find
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MenuAction(ROUTE_TO) { LongPressMenu.routeTo() } // T114: destination first
            MenuAction("Waypoint here") { LongPressMenu.waypoint() }
            MenuAction("Route from here") { LongPressMenu.route() }
            MenuAction("Cancel") { LongPressMenu.cancel() }
        }
    }
}

/**
 * T109: the help pop-up of the card above, by the key its [HelpIcon] names (the owner found dropping a waypoint hard
 * to find); removing the card's icon removes its text. `POST /help/open` opens the card at the map's centre.
 * T114: Route to here first, and where the route card changes its start.
 */
internal val LONG_PRESS_HELP: List<HelpText> = listOf(
    HelpText("longPress.menu", "Long-press menu", "The map: long-press anywhere", show = { LongPressMenu.showAtCentre() }) {
        "A long-press on the map opens this card. Route to here plans a route from where you are to the point; Change " +
            "on the route card starts it elsewhere. Waypoint here marks the point with a waypoint you can name. Route " +
            "from here starts a route at the point: long-press again where it should end."
    },
)

@Composable
private fun MenuAction(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
