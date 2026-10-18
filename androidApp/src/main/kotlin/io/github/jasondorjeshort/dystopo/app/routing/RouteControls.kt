package io.github.jasondorjeshort.dystopo.app.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * T108: Crow joins the mode chips (they wrap at large font scales) with its one line under them, and a crow route
 * shows its bearing instead of the trails it follows.
 *
 * T114: the card names both ends under its title ("From: Your location", "To: 46.80240, -121.72890"), as the route
 * screens of the mainstream navigation apps do; Change on the From line offers Your location, Choose on map and Swap
 * ([RouteSession.fromChoices]). While a route to here waits for its origin the card says so, and with location off it
 * offers Turn on location (the permission prompt, or the phone's location settings). When no path is found it offers
 * Try Crow (the straight line, with its notice under the chips). Go comes first among the actions.
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
                "waitingFix", "noLocation" -> "Route: waiting" // T114
                "pickStart" -> "Route: choose the start"
                else -> plan?.route?.name ?: "Route"
            }
            BasicText(title, style = TextStyle(color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Ends(phase) // T114: From and To
            if (plan != null && phase == "route") {
                BasicText(RouteSession.summary(plan), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                // T108: a crow line's bearing (it follows no trail).
                if (plan.mode == TravelMode.CROW) {
                    val b = plan.route.points.let { io.github.jasondorjeshort.dystopo.routing.Crow.bearingDeg(it.first().lat, it.first().lon, it.last().lat, it.last().lon) }
                    BasicText("Bearing $b° (${io.github.jasondorjeshort.dystopo.routing.Directions.compass(b.toDouble())})", style = TextStyle(color = Ink, fontSize = 13.sp))
                }
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
            // T73: the mode chips (hike, bike, drive; T108: crow) on a row of their own, so the actions keep room on a phone;
            // T108: they wrap rather than squeeze at large font scales.
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
            // T114: the actions wrap (Turn on location and Try Crow join them), and Go comes first.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (phase == "route") {
                    Action("Go") { Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startRoute() }.start() } // T61: navigate
                    Action("Keep") { plan?.let { p -> RouteSession.clear(); io.github.jasondorjeshort.dystopo.app.trips.Trips.background { io.github.jasondorjeshort.dystopo.app.trips.Trips.keep(p) } } } // T60: keep as a trip
                    Action("Save") { Thread { RouteSession.save() }.start() }
                    Action("GPX") { Thread { RouteSession.exportGpx() }.start() }
                }
                if (phase == "noLocation") {
                    Action(TURN_ON_LOCATION) {
                        when (RouteSession.locationState()) {
                            "noPermission" -> askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            "off" -> runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        }
                    }
                }
                // T114: no path between the two ends: the straight line (its notice shows under the chips once chosen).
                if (phase == "failed" && RouteSession.failure != null && RouteSession.mode != TravelMode.CROW) {
                    Action(TRY_CROW) { RouteSession.changeMode(TravelMode.CROW) }
                }
                Action("Clear") { RouteSession.clear() }
            }
        }
    }
}

/** T114: the card's new action labels. */
internal const val TURN_ON_LOCATION = "Turn on location"
internal const val TRY_CROW = "Try Crow"

/**
 * T114: the From and To lines. From is "Your location" (with the fix's age when it was stale), a placed point's
 * coordinates, or "Choose on map" while the next long-press picks it; Change opens Your location, Choose on map and
 * Swap under it, as far as [RouteSession.fromChoices] allows. To is the destination's coordinates (a name when a later
 * entry brings one). Nothing shows before the first end is known.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Ends(phase: String) {
    val f = RouteSession.from
    val t = RouteSession.to
    if (f == null && t == null) return
    val fromText = when {
        phase == "pickStart" -> CHOOSE_ON_MAP
        f != null -> f.label
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
    if (t != null) BasicText("To: ${t.label}", style = TextStyle(color = Ink, fontSize = 13.sp))
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
