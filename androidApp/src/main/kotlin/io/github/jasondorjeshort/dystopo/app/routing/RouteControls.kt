package io.github.jasondorjeshort.dystopo.app.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlinx.coroutines.delay

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

    fun json(): Map<String, Any?> = linkedMapOf(
        "creditTopDp" to AttributionInsets.bottom.value, "routeCardTopDp" to routeCardTop.value,
        "trackPanelsBottomDp" to trackPanelsBottom.value, "trackPanelsTopDp" to trackPanelsTop.value,
        // The panels overlap the card when their bottom edge is below the card's top edge (and both show).
        "overlap" to (routeCardTop > 0.dp && trackPanelsTop > trackPanelsBottom && trackPanelsBottom < routeCardTop),
    )
}

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
 * progress on first use), the route's distance, climb and time with Hike/Bike, Save, GPX and Clear,
 * or why no route was found. Nothing shows until the first long-press, nor while navigating (T65). The
 * distance, climb and time are [RouteSession.summaryOf], the figures the kept route shows (T65). T102: "Show N
 * directions" opens the turn directions (the default detail level), each with the distance it then runs.
 */
@Composable
fun RouteControls() {
    val app = DystopoApp.instance
    val mapView = app.map.mapView
    DisposableEffect(mapView) {
        mapView?.let { RouteSession.overlay.bind(it) }
        onDispose { RouteSession.overlay.unbind() }
    }
    val phase = RouteSession.phase
    // T65: hidden while navigating (the stats bar has what matters; the card returns when navigation stops).
    if (phase == "idle" || io.github.jasondorjeshort.dystopo.app.nav.NavSession.ui != null) {
        SideEffect { if (BottomPanels.routeCardTop != 0.dp) BottomPanels.routeCardTop = 0.dp }
        return
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Column(
            // T49: above the credit line (its measured top); the track panels stack above this card.
            Modifier.align(Alignment.BottomCenter).let { m ->
                val b = AttributionInsets.bottom
                if (b > 0.dp) m.padding(start = 8.dp, end = 8.dp, bottom = b + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp)
            }
                .widthIn(max = 420.dp)
                .onGloballyPositioned { c -> fromWindowBottom(c, density).let { if (it != BottomPanels.routeCardTop) BottomPanels.routeCardTop = it } }
                .background(Paper, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val plan = RouteSession.plan
            val title = when (phase) {
                "start" -> "Route: start set"
                "planning" -> "Route: planning"
                "failed" -> "No route"
                else -> plan?.route?.name ?: "Route"
            }
            BasicText(title, style = TextStyle(color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            if (plan != null && phase == "route") {
                BasicText(RouteSession.summary(plan), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                // T49: the trails it follows, by name.
                if (plan.via.isNotEmpty()) {
                    BasicText(
                        "via " + plan.via.take(4).joinToString(", ") { it.first } + if (plan.via.size > 4) ", ..." else "",
                        style = TextStyle(color = Ink, fontSize = 13.sp), maxLines = 2,
                    )
                }
                Directions(plan)
            }
            if (phase == "planning") BuildProgress()
            RouteSession.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp)) }
            // T73: the mode chips (hike, bike, drive) on a row of their own, so the actions keep room on a phone.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (phase == "route") {
                    Action("Save") { Thread { RouteSession.save() }.start() }
                    Action("GPX") { Thread { RouteSession.exportGpx() }.start() }
                    Action("Keep") { plan?.let { p -> RouteSession.clear(); io.github.jasondorjeshort.dystopo.app.trips.Trips.background { io.github.jasondorjeshort.dystopo.app.trips.Trips.keep(p) } } } // T60: keep as a trip
                    Action("Go") { Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startRoute() }.start() } // T61: navigate
                }
                Action("Clear") { RouteSession.clear() }
            }
        }
    }
}

/** T102: the count of turn directions, opening their list (scrolls past a few lines). */
@Composable
private fun Directions(plan: io.github.jasondorjeshort.dystopo.routing.PlannedRoute) {
    val list = RouteSession.shownDirections(plan)
    if (list.isEmpty()) return
    val open = RouteSession.directionsOpen
    Action(if (open) "Hide directions" else "Show ${list.size} directions") { RouteSession.directionsOpen = !open }
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

/** The graph build progress while a route waits for it (polled; the build runs on its own thread). */
@Composable
private fun BuildProgress() {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val s = RoutePlanner.current
            text = when (s?.status) {
                "building" -> "Building the trail network" + (if (s.total > 0) " ${s.done * 100 / s.total}%" else "") + " (first use of this pack)"
                "loading" -> "Loading the trail network"
                else -> null
            }
            delay(400)
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
