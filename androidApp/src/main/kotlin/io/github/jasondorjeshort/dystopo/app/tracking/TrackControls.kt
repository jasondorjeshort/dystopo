package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import java.util.Locale

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val RecordRed = Color(0xFFC62828)

/**
 * The track controls on the map (T31): a record/stop button with the live distance, point count
 * and time while recording, a waypoint button, and a "Tracks" list (tap a track to show it on the
 * map, "GPX" to export it to the files dir). Also binds the [TrackOverlay] to the map view.
 */
@Composable
fun TrackControls() {
    // A full-size box with no input handling of its own: touches outside the controls reach the map.
    Box(Modifier.fillMaxSize()) { Controls() }
}

@Composable
private fun BoxScope.Controls() {
    val app = DystopoApp.instance
    val tracking = remember { Tracking.of(app) }
    val mapView = app.map.mapView
    DisposableEffect(mapView) {
        mapView?.let { tracking.overlay.bind(it) }
        onDispose { tracking.overlay.unbind() }
    }
    val ui = tracking.ui
    var listOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.recording) Chip("Waypoint") { tracking.post { tracking.recorder.waypoint(null); tracking.refreshUi(); tracking.refreshOverlay(true) } }
            Chip("Tracks") { listOpen = !listOpen }
            Chip(if (ui.recording) "Stop" else "Record", if (ui.recording) RecordRed else Ink) {
                Thread { if (tracking.ui.recording) tracking.stop() else tracking.start(null, Tracking.Mode.ALL) }.start()
            }
        }
        if (ui.recording) {
            Panel {
                BasicText(ui.activeName ?: "", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                BasicText(
                    String.format(Locale.US, "%.2f km  %d pts  %s", ui.distanceM / 1000, ui.points, duration(ui.durationS)),
                    style = TextStyle(color = Ink, fontSize = 13.sp),
                )
            }
        }
        if (listOpen) {
            Panel(Modifier.widthIn(max = 280.dp).heightIn(max = 360.dp)) {
                if (ui.tracks.isEmpty()) BasicText("No tracks yet", style = TextStyle(color = Ink, fontSize = 13.sp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.tracks, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BasicText(
                                (if (t.id == ui.shownId) "● " else "") + t.name + "\n" + t.points + " pts" + if (t.recording) "  recording" else "",
                                style = TextStyle(color = Ink, fontSize = 13.sp),
                                modifier = Modifier.widthIn(max = 200.dp).clickable { Thread { tracking.show(t.id) }.start() },
                            )
                            BasicText(
                                "GPX",
                                style = TextStyle(color = Color(0xFF2C7BB6), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable {
                                    Thread { tracking.export(t.id)?.let { tracking.refreshUi("Saved ${it.name}") } }.start()
                                },
                            )
                        }
                    }
                }
                ui.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 11.sp)) }
            }
        }
    }
}

private fun duration(s: Double?): String {
    if (s == null) return ""
    val t = s.toLong()
    return String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
}

@Composable
private fun Chip(text: String, color: Color = Ink, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .background(color, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Paper, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}
