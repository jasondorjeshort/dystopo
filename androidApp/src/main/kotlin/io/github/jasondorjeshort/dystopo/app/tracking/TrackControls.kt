package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import java.io.File
import java.util.Locale

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val RecordRed = Color(0xFFC62828)
private val Link = Color(0xFF2C7BB6)
private val Magenta = Color(0xFFD01C8B)

/**
 * The track controls on the map (T31, T40): record/pause/resume/stop with the live distance, point
 * count and time; a waypoint button; a "Tracks" list (tap a track to show it, "Profile" for its
 * elevation profile, "GPX" to export it to the files dir, "Import" for a GPX from the files dir or
 * the system file picker); the waypoint sheet (rename or delete; opened by tapping a waypoint on the
 * map); and the elevation profile panel. Also binds the [TrackOverlay] to the map view.
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
    var importOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Thread { importUri(tracking, uri) }.start()
    }

    Column(
        // Below the Packs button (T29), which sits in the top-right corner.
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 64.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.recording) {
                Chip("Waypoint") { tracking.post { tracking.recorder.waypoint(null); tracking.refreshUi(); tracking.refreshOverlay(true) } }
                Chip(if (ui.paused) "Resume" else "Pause") { Thread { if (tracking.ui.paused) tracking.resume() else tracking.pause() }.start() }
            }
            Chip("Tracks") { listOpen = !listOpen }
            Chip(if (ui.recording) "Stop" else "Record", if (ui.recording) RecordRed else Ink) {
                Thread { if (tracking.ui.recording) tracking.stop() else tracking.start(null, Tracking.Mode.ALL) }.start()
            }
        }
        if (ui.recording) {
            Panel {
                BasicText(ui.activeName ?: "", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                BasicText(
                    UnitFormat.distance(ui.distanceM, AppSettings.units) + String.format(Locale.US, "  %d pts  %s", ui.points, duration(ui.durationS)) +
                        if (ui.paused) "  PAUSED" else "",
                    style = TextStyle(color = if (ui.paused) RecordRed else Ink, fontSize = 13.sp),
                )
            }
        }
        if (listOpen) {
            Panel(Modifier.widthIn(max = 300.dp).heightIn(max = 380.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("Import") { importOpen = !importOpen }
                    io.github.jasondorjeshort.dystopo.app.sync.SyncNowAction() // T44
                    if (importOpen) Action("Pick file") { picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")) }
                }
                if (importOpen) ImportList(tracking) { importOpen = false }
                if (ui.tracks.isEmpty()) BasicText("No tracks yet", style = TextStyle(color = Ink, fontSize = 13.sp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.tracks, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val km = ui.distances[t.id]?.let { "  " + UnitFormat.distance(it, AppSettings.units) } ?: ""
                            BasicText(
                                (if (t.id == ui.shownId) "● " else "") + t.name + "\n" + t.points + " pts" + km + if (t.recording) "  recording" else "",
                                style = TextStyle(color = Ink, fontSize = 13.sp),
                                modifier = Modifier.widthIn(max = 170.dp).clickable { Thread { tracking.show(t.id) }.start() },
                            )
                            Action("Profile") { Thread { tracking.showProfile(t.id) }.start() }
                            Action("GPX") { Thread { tracking.export(t.id)?.let { tracking.refreshUi("Saved ${it.name}") } }.start() }
                        }
                    }
                }
                ui.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 11.sp)) }
            }
        }
    }

    Column(
        // T41: above the credit line (its measured top), so the profile panel never covers it.
        Modifier.align(Alignment.BottomCenter).let { m ->
            val b = io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom
            if (b > 0.dp) m.padding(start = 8.dp, end = 8.dp, bottom = b + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp)
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ui.selectedWaypoint?.let { WaypointSheet(tracking, it) }
        ui.profile?.let { ProfilePanel(tracking, it) }
    }
}

@Composable
private fun ImportList(tracking: Tracking, onDone: () -> Unit) {
    var files by remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(Unit) { Thread { files = tracking.importableFiles() }.start() }
    val list = files ?: return
    if (list.isEmpty()) BasicText("No .gpx files in ${tracking.filesDir.path}", style = TextStyle(color = Ink, fontSize = 11.sp))
    for (f in list.take(8)) {
        BasicText(
            f.name,
            style = TextStyle(color = Link, fontSize = 12.sp),
            modifier = Modifier.clickable {
                Thread { runCatching { tracking.importFile(f.name) }.onFailure { tracking.refreshUi("Import failed: ${it.message}") } }.start()
                onDone()
            },
        )
    }
}

/** Reads a picked document and imports it (off the main thread). */
private fun importUri(tracking: Tracking, uri: Uri) {
    val app = DystopoApp.instance
    try {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: "Imported track"
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("cannot open $uri")
        tracking.importGpx(text, name)
    } catch (e: Exception) {
        tracking.refreshUi("Import failed: ${e.message}")
    }
}

@Composable
private fun WaypointSheet(tracking: Tracking, w: io.github.jasondorjeshort.dystopo.store.StoredWaypoint) {
    var name by remember(w.waypoint.id) { mutableStateOf(w.waypoint.name) }
    Panel(Modifier.fillMaxWidth()) {
        BasicText("Waypoint", style = TextStyle(color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        BasicTextField(
            name, { name = it },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Ink.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(8.dp),
        )
        val wp = w.waypoint
        BasicText(
            String.format(Locale.US, "%.5f, %.5f", wp.lat, wp.lon) + (wp.ele?.let { "  " + UnitFormat.elevation(it, AppSettings.units) } ?: ""),
            style = TextStyle(color = Ink, fontSize = 11.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Action("Rename") { val n = name.trim(); if (n.isNotEmpty()) Thread { tracking.renameWaypoint(wp.id, n) }.start() }
            Action("Delete", RecordRed) { Thread { tracking.deleteWaypoint(wp.id) }.start() }
            Action("Close") { Thread { tracking.selectWaypoint(null) }.start() }
        }
    }
}

@Composable
private fun ProfilePanel(tracking: Tracking, p: Tracking.ProfileView) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(p.name, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.widthIn(max = 220.dp))
            Action("Close") { Thread { tracking.showProfile(null) }.start() }
        }
        val s = p.stats
        BasicText(
            UnitFormat.distance(s.distanceM, AppSettings.units) +
                (s.ascentM?.let { "  +" + UnitFormat.elevation(it, AppSettings.units) } ?: "") +
                (s.descentM?.let { " / -" + UnitFormat.elevation(it, AppSettings.units) } ?: "") +
                (if (s.minEleM != null && s.maxEleM != null) "  ${UnitFormat.elevationValue(s.minEleM!!, AppSettings.units)} to ${UnitFormat.elevation(s.maxEleM!!, AppSettings.units)}" else "") +
                (if (s.segments > 1) "  ${s.segments} segments" else ""),
            style = TextStyle(color = Ink, fontSize = 12.sp),
        )
        val eles = p.samples.mapNotNull { it.eleM }
        if (eles.size < 2) {
            BasicText("No elevation in this track", style = TextStyle(color = Ink, fontSize = 12.sp))
            return@Panel
        }
        val lo = eles.min()
        val hi = eles.max()
        val total = p.samples.last().distanceM.takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val span = (hi - lo).takeIf { it > 1.0 } ?: 1.0
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - lo) / span * (size.height * 0.9) - size.height * 0.05).toFloat()
            val line = Path()
            val area = Path()
            // T41: one run per track segment, so a segment break is a gap, not a vertical step.
            var runSegment: Int? = null
            var runEndX = 0f
            for (smp in p.samples) {
                val e = smp.eleM ?: continue
                if (runSegment != smp.segment) {
                    if (runSegment != null) { area.lineTo(runEndX, size.height); area.close() }
                    line.moveTo(x(smp.distanceM), y(e))
                    area.moveTo(x(smp.distanceM), size.height)
                    runSegment = smp.segment
                } else line.lineTo(x(smp.distanceM), y(e))
                area.lineTo(x(smp.distanceM), y(e))
                runEndX = x(smp.distanceM)
            }
            area.lineTo(runEndX, size.height)
            area.close()
            drawPath(area, Magenta.copy(alpha = 0.18f))
            drawPath(line, Magenta, style = Stroke(width = 2.5.dp.toPx()))
            drawLine(Ink.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("0  |  " + UnitFormat.elevation(lo.toDouble(), AppSettings.units) + " low", style = TextStyle(color = Ink, fontSize = 10.sp))
            BasicText(UnitFormat.elevation(hi.toDouble(), AppSettings.units) + " high  |  " + UnitFormat.distance(total.toDouble(), AppSettings.units), style = TextStyle(color = Ink, fontSize = 10.sp))
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
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Paper, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}
