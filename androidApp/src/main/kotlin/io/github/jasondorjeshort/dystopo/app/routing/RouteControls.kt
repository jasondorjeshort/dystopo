package io.github.jasondorjeshort.dystopo.app.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlinx.coroutines.delay

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val Link = Color(0xFF2C7BB6)
private val Orange = Color(0xFFE66101)

/**
 * The route card (T45): binds [RouteSession.overlay] to the map (long-press start, long-press end),
 * then shows what is happening: waiting for the end point, planning (with the trail-network build
 * progress on first use), the route's distance, climb and time with Hike/Bike, Save, GPX and Clear,
 * or why no route was found. Nothing shows until the first long-press.
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
    if (phase == "idle") return
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp)
                .widthIn(max = 420.dp)
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
            }
            if (phase == "planning") BuildProgress()
            RouteSession.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in TravelMode.entries) {
                    val on = RouteSession.mode == m
                    BasicText(
                        m.label.replaceFirstChar { it.uppercase() },
                        style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.background(if (on) Ink else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { RouteSession.changeMode(m) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (phase == "route") {
                    Action("Save") { Thread { RouteSession.save() }.start() }
                    Action("GPX") { Thread { RouteSession.exportGpx() }.start() }
                }
                Action("Clear") { RouteSession.clear() }
            }
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
