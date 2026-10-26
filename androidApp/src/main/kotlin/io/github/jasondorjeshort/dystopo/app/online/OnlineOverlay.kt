package io.github.jasondorjeshort.dystopo.app.online

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

private val Ink = Color(0xFF2B2420)
private val Line = Color(0xFFE2D8C6)
private val Chip = Color(0xEEFFFCF5)
private val Green = Color(0xFF3C8A3F)
private val Grey = Color(0xFF9A8F84)
private val Red = Color(0xFFB0302A)

/**
 * T144: what the overlay shows, a snapshot made off the UI thread once a second ([make]) and only read by the composable:
 * the online state, the view's source, the region download's record, the reading notice. The UI thread never calls into
 * [OnlineService], [RegionDownload] or the pack manager for it. The lines it shows are worked out here; pure, tested
 * (`OnlineOverlaySnapshotTest`).
 */
class OnlineUi(
    val state: String, val view: OnlineView?, val download: Map<String, Any?>, val running: Boolean, val notice: String? = null, val direct: Boolean = false,
) {
    enum class Dot { GREEN, GREY, RED }

    /** Hidden: no server and nothing downloading. */
    val hidden: Boolean get() = state == "none" && !running

    /** The chip's state word and its dot (T85: not green while mobile data is off for it or the session limit is reached). */
    val label: Pair<String, Dot> get() = when (state) {
        "online" -> if (notice != null) "Paused" to Dot.GREY else "Online" to Dot.GREEN
        "offline" -> "Offline" to Dot.RED
        "none" -> "No server" to Dot.GREY
        else -> "Connecting" to Dot.GREY
    }

    val sourceText: String get() = when (view?.source) {
        OnlineTiles.PACK -> "pack"
        OnlineService.ONLINE -> if (direct) "live from public sources" else "live from server" // T80
        OnlineService.CACHED -> "from cache"
        else -> "no data here"
    }

    /** The progress line while a download runs, else null. */
    val progress: String? get() {
        if (!running) return null
        val pct = (download["percent"] as? Number)?.toDouble() ?: 0.0
        val phase = if (download["state"] != "building") "Downloading" else if (direct) "Building on this phone" else "Building on server" // T80
        val kind = download["packLabel"]?.let { " ($it)" } ?: "" // T64: which vector profile the server is making
        return String.format(Locale.US, "%s %s%s: %.0f%%", phase, download["name"], kind, pct)
    }

    /** "Download this region" is offered: online, a view, not all pack, nothing running. */
    val offerDownload: Boolean get() = !running && state == "online" && view != null && view.source != OnlineTiles.PACK

    /** The line after a finished or failed download, or null. */
    val result: Pair<String, Dot?>? get() = when (download["state"]) {
        "done" -> if (view?.source == OnlineTiles.PACK) ("Saved as pack ${download["name"]}" + (download["packLabel"]?.let { " · $it" } ?: "")) to null else null
        "failed" -> "Download failed: ${download["error"]}" to Dot.RED
        else -> null
    }

    companion object {
        /** The snapshot for camera [cam] (read on the UI thread, source unknown): everything else is read here, on a worker. */
        fun make(service: OnlineService, app: DystopoApp, cam: OnlineView?): OnlineUi {
            val view = cam?.let { it.withSource(OnlineView.sourceFor(app, it)) }
            return OnlineUi(service.state(), view, service.region.json(), service.region.running, if (service.isDirect) service.direct.notice() else null, service.isDirect)
        }
    }
}

/**
 * The online indicator over the map (top left): online / offline with the source of the current view
 * (pack, online, cached, none) and "Download this region" with its progress. Hidden when no server is set. Drawn by
 * one line in MainActivity's map screen. T106: no credit line of its own any more: the map's credit line at the bottom
 * has named the online sources in view since T41 (from the provenance of the tiles in view), while this one, under the
 * top chips and over the map's labels, named the server's whole list ("Elevation: Copernicus DEM GLO-30" at Rainier,
 * where 3DEP is the source).
 *
 * T144: the composable reads [OnlineUi] snapshots; each second the camera is read here (the map's own calls, no lock) and
 * the rest is made on [Dispatchers.Default].
 */
@Composable
fun OnlineOverlay() {
    val app = DystopoApp.instance
    val service = remember { OnlineService.get(app) }
    var ui by remember { mutableStateOf<OnlineUi?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val cam = OnlineView.currentOnMain(app)
            ui = withContext(Dispatchers.Default) { runCatching { OnlineUi.make(service, app, cam) }.getOrNull() } ?: ui
            delay(1000)
        }
    }
    val u = ui
    if (u == null || u.hidden) return io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsHidden() // T57
    io.github.jasondorjeshort.dystopo.app.MapChrome { Box(Modifier.fillMaxSize()) { // T91: capped font scale
        Column(
            // Below the search box (T39), which is drawn after this overlay so its results cover it; T91: below its
            // measured bottom, so the "Indexing places" line no longer runs into it.
            Modifier.align(Alignment.TopStart).then(io.github.jasondorjeshort.dystopo.app.TopChips.onlineColumn()).widthIn(max = 260.dp)
                .onGloballyPositioned { io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsAt(it) }, // T57: catalog fits keep clear
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val (label, dot) = u.label
            Row(
                Modifier.background(Chip, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(9.dp).background(color(dot), CircleShape))
                BasicText("$label · ${u.sourceText}", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            // T80: why the phone is not reading the sources (the session's download limit, or mobile data turned off by the user; T83).
            u.notice?.let { Small(it, Red) {} }
            u.progress?.let { Small(it) { message = null; thread { service.region.cancel() } } }
            if (u.offerDownload) {
                Button("Download this region") {
                    val box = u.view?.bounds
                    message = null
                    if (box != null) thread {
                        message = try {
                            service.region.start(box, null, null, null)
                            null
                        } catch (e: Exception) {
                            e.message
                        }
                    }
                }
            }
            u.result?.let { (text, dot) -> Small(text, if (dot == null) Ink else color(dot)) {} }
            message?.let { Small(it, Red) { message = null } }
        }
    } }
}

private fun color(d: OnlineUi.Dot) = when (d) {
    OnlineUi.Dot.GREEN -> Green
    OnlineUi.Dot.GREY -> Grey
    OnlineUi.Dot.RED -> Red
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(Chip, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Small(text: String, color: Color = Ink, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 12.sp),
        modifier = Modifier.background(Chip, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
