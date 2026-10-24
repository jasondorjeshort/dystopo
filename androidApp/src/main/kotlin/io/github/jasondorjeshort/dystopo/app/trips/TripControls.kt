package io.github.jasondorjeshort.dystopo.app.trips

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.WaypointAt
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import kotlinx.coroutines.delay

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xF2FFFCF5)
private val Link = Color(0xFF2C7BB6)
private val Violet = Color(0xFF7B3294)
private val Flag = Color(0xFFC79A00)

/**
 * Trips UI (T60), drawn over the map by one line in MainActivity: a "Routes" chip (third row of the
 * top-right chips), the Routes list (name, distance, climb, time by the hike model, a small profile),
 * and, while a route is open, the trip card at the bottom: totals, the elevation profile with waypoint
 * markers, and the editor's tools (drag the numbered via points on the map; Via adds a via point where
 * you tap, Waypoint adds a waypoint; Hike/Bike reroutes; the via chips remove one). T67: "Share link"
 * asks the sync server for a read-only link to the trip and copies it. T108: the card shows the directions saved with
 * the trip ("Show N directions", the default detail level) or says they were not saved; Crow joins the modes with its
 * one line; a mode this build does not know is named as it is, not as hike, and the trip is not edited.
 */
@Composable
fun TripControls() {
    val app = DystopoApp.instance
    val mapView = app.map.mapView
    DisposableEffect(mapView) {
        mapView?.let { Trips.overlay.bind(it) }
        onDispose { Trips.overlay.unbind() }
    }
    // The list and the open route follow the store (syncs, other screens) while either is shown.
    val watching = Trips.listOpen || Trips.editing != null
    LaunchedEffect(watching) {
        while (watching) {
            Trips.background { Trips.refresh() }
            delay(3000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (Trips.listOpen) {
            BackHandler { Trips.listOpen = false }
            RoutesList()
            return@Box
        }
        if (Trips.editing == null) {
            io.github.jasondorjeshort.dystopo.app.MapChrome { // T91: capped font scale
                Box(Modifier.fillMaxSize()) {
                    Chip("Routes", Modifier.align(Alignment.TopEnd).then(io.github.jasondorjeshort.dystopo.app.TopChips.routesChip())) { // T65: below the track controls
                        Trips.listOpen = true
                        Trips.background { Trips.refresh() }
                    }
                }
            }
        } else if (!io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen) {
            // T136: the Offline areas sheet stands in for the card while open, as the Routes list does (the editor stays open behind it).
            BackHandler { Trips.close() }
            TripCard(Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BoxScope.RoutesList() {
    // A sheet over the lower part of the map, clear of the search box and the top-right chips.
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.7f)
            .background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).navigationBarsPadding().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("Routes", style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                io.github.jasondorjeshort.dystopo.app.routing.RouteImportAction() // T65: Import from the system file picker
                Action("Close") { Trips.listOpen = false }
            }
        }
        BasicText(
            "Plan a route with a long-press start and end, then Keep it; or POST /routes. Routes sync with your other devices.",
            style = TextStyle(color = Ink, fontSize = 12.sp),
        )
        val items = Trips.list
        if (items.isEmpty()) BasicText("No routes yet.", style = TextStyle(color = Ink, fontSize = 14.sp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in items) RouteRow(r)
        }
    }
}

@Composable
private fun RouteRow(r: RouteListItem) {
    val u = AppSettings.units
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).clickable { Trips.background { Trips.open(r.id) } }.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(r.name.ifBlank { "Route" }, style = TextStyle(color = Violet, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                UnitFormat.distance(r.distanceM, u) + (r.ascentM?.let { " · +" + UnitFormat.elevation(it, u) } ?: "") + " · " + RouteSummary.hm(r.timeS) + " " + TravelMode.labelOf(r.mode), // T108
                style = TextStyle(color = Ink, fontSize = 13.sp),
            )
            BasicText(
                "${r.viaPoints} via points · ${r.waypoints} waypoints" + if (r.straightM > 0) " · " + UnitFormat.distance(r.straightM, u) + " off trail" else "",
                style = TextStyle(color = Ink.copy(alpha = 0.7f), fontSize = 11.sp),
            )
        }
        Trips.listProfiles[r.id]?.let { Profile(it, emptyList(), Modifier.width(96.dp).height(36.dp), thin = true) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripCard(modifier: Modifier) {
    val p = Trips.editing ?: return
    val s = Trips.summary ?: return
    val u = AppSettings.units
    val b = AttributionInsets.bottom // T106: the update offer waits while this card is open (UpdateOverlay)
    val density = LocalDensity.current
    // T106 (a T93 follow-up): the card reports its top edge, so the recentre button rides above it instead of hiding.
    DisposableEffect(Unit) { onDispose { io.github.jasondorjeshort.dystopo.app.MapInsets.tripCardTop = 0.dp } }
    Column(
        modifier.let { m -> if (b > 0.dp) m.padding(start = 8.dp, end = 8.dp, bottom = b + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp) }
            .widthIn(max = 440.dp)
            .onGloballyPositioned { c -> io.github.jasondorjeshort.dystopo.app.MapInsets.let { i -> i.topFromWindowBottom(c, density).let { if (it != i.tripCardTop) i.tripCardTop = it } } }
            .background(Paper, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText(p.name.ifBlank { "Route" }, style = TextStyle(color = Violet, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Action("Done") { Trips.close() }
        }
        BasicText(
            UnitFormat.distance(s.distanceM, u) + (s.ascentM?.let { " · +" + UnitFormat.elevation(it, u) + " −" + UnitFormat.elevation(s.descentM ?: 0.0, u) } ?: "") +
                " · " + RouteSummary.hm(s.timeS) + " " + TravelMode.labelOf(s.mode), // T108: an unknown mode by its own name
            style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        val known = TravelMode.known(s.mode)
        if (!known) BasicText(TravelMode.unknownModeMessage(s.mode).replaceFirstChar { it.uppercase() } + ".", style = TextStyle(color = Ink, fontSize = 12.sp)) // T108
        if (s.mode == "crow") {
            // T108: the line's bearing (each leg's, for a trip with via points) and what a crow route is.
            BasicText(crowBearings(s), style = TextStyle(color = Ink, fontSize = 12.sp))
            BasicText(TravelMode.CROW_NOTICE, style = TextStyle(color = Ink, fontSize = 11.sp))
        } else if (s.straightM > 0) BasicText(UnitFormat.distance(s.straightM, u) + (if (s.mode == "drive") " off road (dashed)" else " off trail (dashed)"), style = TextStyle(color = Ink, fontSize = 12.sp))
        if (s.mode == "drive") BasicText(io.github.jasondorjeshort.dystopo.routing.Router.DRIVE_NOTICE, style = TextStyle(color = Ink, fontSize = 11.sp)) // T73
        // T106 (a T89 follow-up): a drive planned before road speeds says its time is the flat estimate, and offers Re-plan.
        if (untimedDriveLegs(p.path) > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(driveTimeEstimated(u), style = TextStyle(color = Ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
                Action("Re-plan") { Trips.background { Trips.replan(p.id) } }
            }
        }
        Profile(s.profile, s.waypoints, Modifier.fillMaxWidth().height(84.dp))
        if (s.waypoints.isNotEmpty()) {
            BasicText(
                s.waypoints.sortedBy { it.alongM }.joinToString("  ") { "⚑ ${it.name} " + UnitFormat.distance(it.alongM, u) },
                style = TextStyle(color = Ink, fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        TripDirections(p)
        TripCorridorLine(p) // T130: the corridor kept offline around the trip
        Trips.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 12.sp)) }
        // T95: the Offline button's offer: the size first, then Download and Cancel, or Close when it does not fit.
        Trips.offline?.takeIf { it.tripId == p.id }?.let { o ->
            BasicText(o.text, style = TextStyle(color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (o.canDownload) Action("Download") { Trips.background { Trips.confirmOffline(p.id) } }
                Action(if (o.canDownload) "Cancel" else "Close") { Trips.cancelOffline() }
            }
        }
        // T106: the tools and the actions wrap (at font scale 2.0 they were squeezed into columns of letters).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((t, label) in listOf("none" to "Drag", "via" to "+Via", "waypoint" to "+Waypoint")) {
                if (known || t != "via") Toggle(label, Trips.tool == t) { Trips.tool = t } // T108: no via edits in an unknown mode
            }
            for (m in TravelMode.entries.map { it.label }) Toggle(m.replaceFirstChar { it.uppercase() }, s.mode == m) { if (s.mode != m) Trips.background { Trips.setMode(p.id, m) } } // T108: crow too
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p.path.via.size > 2) {
                BasicText("Remove via:", style = TextStyle(color = Ink, fontSize = 12.sp))
                for (i in 1 until p.path.via.size - 1) Action("${i + 1}") { Trips.background { Trips.removeVia(p.id, i) } }
            }
            Action("Go") { Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startTrip(p.id) }.start() } // T65: navigate this route
            Action("GPX") { Trips.background { Trips.export(p.id) } }
            Action("Share link") { Trips.background { Trips.shareLink(p.id) } } // T67: a read-only link from the sync server
            Action("Delete", Color(0xFFD73027)) { Trips.background { Trips.delete(p.id) } }
        }
    }
}

/**
 * T130: the trip's corridor kept offline ([io.github.jasondorjeshort.dystopo.app.areas.TripCorridors]): one line saying
 * whether it is kept, with its width and size or progress, or why not, and its action (Stop keeping, Keep offline,
 * Retry, Offline areas). It replaces T75's Offline button: a trip is kept without being asked (T95's size-first offer
 * stays behind `POST /routes/{id}/offline`). Redrawn every 1.5 s for a running download's progress.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripCorridorLine(p: io.github.jasondorjeshort.dystopo.model.RoutePlan) {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(p.id) { while (true) { delay(1500); tick++ } }
    tick.let { } // read, so the line follows the download
    val c = io.github.jasondorjeshort.dystopo.app.areas.TripCorridors
    val st = c.status(p)
    // The text over its own row (the budget message runs to three lines), the actions after it, wrapping.
    BasicText(
        st.text,
        style = TextStyle(color = if (st.state == "kept" || st.state == "downloading") Color(0xFF00796B) else Ink, fontSize = 12.sp, fontWeight = if (st.state == "kept") FontWeight.Bold else FontWeight.Normal),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (a in st.actions) when (a) {
            "stop" -> Action(CORRIDOR_STOP) { Trips.background { c.keep(p.id, false) } }
            "keep" -> Action(CORRIDOR_KEEP) { Trips.background { c.keep(p.id, true) } }
            "retry" -> Action(CORRIDOR_RETRY) { Trips.background { c.keep(p.id, true); io.github.jasondorjeshort.dystopo.app.areas.Areas.list.firstOrNull { it.tripId == p.id }?.let { r -> io.github.jasondorjeshort.dystopo.app.areas.AreaDownload.start(r.area, r.name, r.tripId) } } }
            "areas" -> Action(CORRIDOR_AREAS) { io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen = true }
        }
    }
}

/** T130: the corridor line's actions. */
internal const val CORRIDOR_STOP = "Stop keeping"
internal const val CORRIDOR_KEEP = "Keep offline"
internal const val CORRIDOR_RETRY = "Retry"
internal const val CORRIDOR_AREAS = "Offline areas"

/**
 * T108: the directions saved with the trip: "Show N directions" opening their list (the default detail level, each with
 * the distance it then runs), or the line that they were not saved (a trip from before T108 or the browser, with no
 * graph here to make them).
 */
@Composable
private fun TripDirections(p: io.github.jasondorjeshort.dystopo.model.RoutePlan) {
    val saved = p.savedDirections
    if (saved == null) {
        BasicText(if (Trips.directionsWorking == p.id) DIRECTIONS_WORKING else DIRECTIONS_NOT_SAVED, style = TextStyle(color = Ink, fontSize = 12.sp))
        return
    }
    val list = io.github.jasondorjeshort.dystopo.routing.Directions.shown(saved)
    if (list.isEmpty()) return
    val open = Trips.directionsOpen
    Action(if (open) "Hide directions" else showDirectionsLabel(list.size)) { Trips.directionsOpen = !open }
    if (!open) return
    val units = AppSettings.units
    val mode = TravelMode.of(p.path.mode) ?: TravelMode.HIKE
    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((k, ins) in list.withIndex()) {
            val then = if (ins.stretchM > 0) "  " + UnitFormat.distance(ins.stretchM, units) else ""
            BasicText("${k + 1}. ${io.github.jasondorjeshort.dystopo.routing.DirectionsText.of(ins, mode)}$then", style = TextStyle(color = Ink, fontSize = 13.sp))
        }
    }
}

/** T108: a crow trip's bearings: "Bearing 47° (northeast)", or one per leg for a trip with via points. */
internal fun crowBearings(s: RouteSummary): String {
    val b = s.bearings
    fun one(x: io.github.jasondorjeshort.dystopo.model.LegBearing) = "${x.bearingDeg}° (${io.github.jasondorjeshort.dystopo.routing.Directions.compass(x.bearingDeg.toDouble())})"
    return when (b.size) {
        0 -> "Straight line"
        1 -> "Bearing " + one(b[0])
        else -> "Bearings " + b.joinToString(", ") { one(it) }
    }
}

/** An elevation profile with waypoint markers (flags at their distance along the route). */
@Composable
private fun Profile(samples: List<ProfileSample>, marks: List<WaypointAt>, modifier: Modifier, thin: Boolean = false) {
    val eles = samples.mapNotNull { it.eleM }
    if (eles.size < 2) {
        if (!thin) BasicText("No elevation along this route", style = TextStyle(color = Ink, fontSize = 12.sp))
        return
    }
    val lo = eles.min()
    val hi = eles.max()
    val total = samples.last().distanceM.takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(modifier) {
            val span = (hi - lo).takeIf { it > 1.0 } ?: 1.0
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - lo) / span * (size.height * 0.85) - size.height * 0.05).toFloat()
            val line = Path()
            val area = Path()
            var started = false
            for (smp in samples) {
                val e = smp.eleM ?: continue
                if (!started) { line.moveTo(x(smp.distanceM), y(e)); area.moveTo(x(smp.distanceM), size.height); started = true } else line.lineTo(x(smp.distanceM), y(e))
                area.lineTo(x(smp.distanceM), y(e))
            }
            area.lineTo(x(samples.last().distanceM), size.height)
            area.close()
            drawPath(area, Violet.copy(alpha = 0.16f))
            drawPath(line, Violet, style = Stroke(width = (if (thin) 1.5 else 2.5).dp.toPx()))
            for (m in marks) {
                val mx = x(m.alongM)
                drawLine(Flag, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 2.dp.toPx())
                m.eleM?.let { drawCircle(Flag, 4.dp.toPx(), Offset(mx, y(it))) }
            }
            drawLine(Ink.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        if (!thin) {
            val u = AppSettings.units
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(UnitFormat.elevation(lo, u) + " low", style = TextStyle(color = Ink, fontSize = 10.sp))
                BasicText(UnitFormat.elevation(hi, u) + " high  |  " + UnitFormat.distance(total, u), style = TextStyle(color = Ink, fontSize = 10.sp))
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.background(Violet, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.background(if (on) Ink else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
