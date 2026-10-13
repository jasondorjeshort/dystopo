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
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.concurrent.thread

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Line = Color(0xFFE2D8C6)
private val Chip = Color(0xEEFFFCF5)
private val Green = Color(0xFF3C8A3F)
private val Grey = Color(0xFF9A8F84)
private val Red = Color(0xFFB0302A)

/** What the overlay shows, refreshed once a second on the main thread. */
private class OnlineUi(val state: String, val view: OnlineView?, val download: Map<String, Any?>, val running: Boolean, val credit: String, val notice: String? = null)

/**
 * The online indicator over the map (top left): online / offline with the source of the current view
 * (pack, online, cached, none), a credit line while online data is in view, and "Download this region"
 * with its progress. Hidden when no server is set. Drawn by one line in MainActivity's map screen.
 */
@Composable
fun OnlineOverlay() {
    val app = DystopoApp.instance
    val service = remember { OnlineService.get(app) }
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val ui = remember(tick) {
        OnlineUi(service.state(), OnlineView.currentOnMain(app), service.region.json(), service.region.running, service.attribution(), if (service.isDirect) service.direct.notice() else null)
    }
    if (ui.state == "none" && !ui.running) return io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsHidden() // T57
    Box(Modifier.fillMaxSize()) {
        Column(
            // Below the search box (T39), which is drawn after this overlay so its results cover it.
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 60.dp).widthIn(max = 260.dp)
                .onGloballyPositioned { io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsAt(it) }, // T57: catalog fits keep clear
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val (label, dot) = when (ui.state) {
                "online" -> if (ui.notice != null) "Paused" to Grey else "Online" to Green // T85: not green while mobile data is off for it or the session limit is reached
                "offline" -> "Offline" to Red
                "none" -> "No server" to Grey
                else -> "Connecting" to Grey
            }
            val source = when (ui.view?.source) {
                OnlineTiles.PACK -> "pack"
                OnlineService.ONLINE -> if (OnlineService.get().isDirect) "live from public sources" else "live from server" // T80
                OnlineService.CACHED -> "from cache"
                else -> "no data here"
            }
            Row(
                Modifier.background(Chip, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(9.dp).background(dot, CircleShape))
                BasicText("$label · $source", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            // T80: why the phone is not reading the sources (the session's download limit, or mobile data turned off by the user; T83).
            ui.notice?.let { Small(it, Red) {} }
            val d = ui.download
            when {
                ui.running -> {
                    val pct = (d["percent"] as? Number)?.toDouble() ?: 0.0
                    val phase = if (d["state"] != "building") "Downloading" else if (OnlineService.get().isDirect) "Building on this phone" else "Building on server" // T80
                    // T64: which vector profile the server is making (outdoors from an extract or Overpass, or the fallback).
                    val kind = d["packLabel"]?.let { " ($it)" } ?: ""
                    Small(String.format(Locale.US, "%s %s%s: %.0f%%", phase, d["name"], kind, pct)) { message = null; thread { service.region.cancel() } }
                }
                ui.state == "online" && ui.view != null && ui.view.source != OnlineTiles.PACK -> {
                    Button("Download this region") {
                        val box = ui.view.bounds
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
            }
            when (d["state"]) {
                "done" -> if (ui.view?.source == OnlineTiles.PACK) Small("Saved as pack ${d["name"]}" + (d["packLabel"]?.let { " · $it" } ?: "")) {}
                "failed" -> Small("Download failed: ${d["error"]}", Red) {}
            }
            message?.let { Small(it, Red) { message = null } }
            if (ui.view?.source == OnlineService.ONLINE || ui.view?.source == OnlineService.CACHED) {
                BasicText(ui.credit, style = TextStyle(color = Muted, fontSize = 10.sp), maxLines = 2, modifier = Modifier.background(Chip, RoundedCornerShape(6.dp)).padding(4.dp))
            }
        }
    }
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
