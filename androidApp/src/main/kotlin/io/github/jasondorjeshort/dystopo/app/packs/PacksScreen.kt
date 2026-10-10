package io.github.jasondorjeshort.dystopo.app.packs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

/** Whether the pack list is showing (main thread; `POST /packs/ui` sets it too). */
object PacksUi {
    var visible by mutableStateOf(false)
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)

/**
 * The region-pack entry point on the map: a "Packs" button, and when open, a full-screen list of the
 * packs on the device (tap to show one; the camera jumps to it), with delete and a URL download box
 * showing progress. Drawn over the map by one line in MainActivity's map screen.
 */
@Composable
fun PacksOverlay() {
    Box(Modifier.fillMaxSize()) {
        if (!PacksUi.visible) {
            // T33: the top-right chips in one row (Settings, Packs); track controls sit in the row below (TrackControls).
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Settings") { io.github.jasondorjeshort.dystopo.app.SettingsUi.visible = !io.github.jasondorjeshort.dystopo.app.SettingsUi.visible }
                Chip("Packs") { PacksUi.visible = true }
            }
        } else {
            BackHandler { PacksUi.visible = false }
            PacksScreen(PackManager.get()) { PacksUi.visible = false }
        }
    }
}

@Composable
private fun PacksScreen(manager: PackManager, close: () -> Unit) {
    var packs by remember { mutableStateOf<List<PackManager.Listed>>(emptyList()) }
    var active by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableStateOf<Set<String>>(emptySet()) }
    var jobs by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        while (true) {
            val (p, a, j) = withContext(Dispatchers.IO) { Triple(manager.list(), manager.activeName(), manager.downloads.snapshot()) }
            packs = p
            active = a
            used = manager.inUse().toSet() // T41: every usable pack is on the map
            jobs = j
            delay(if (j.any { it["state"] == "running" || it["state"] == "queued" }) 500 else 2000)
        }
    }

    fun background(label: String, block: () -> Unit) {
        message = "$label..."
        thread(name = "dystopo-packs-ui") {
            val result = runCatching(block)
            message = result.exceptionOrNull()?.let { "$label failed: ${it.message}" }
            refresh++
        }
    }

    Column(
        Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Region packs", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            Chip("Close") { close() }
        }
        message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 13.sp)) }
        if (packs.isEmpty()) {
            BasicText("No packs on this device yet. Download one below.", style = TextStyle(color = Muted, fontSize = 14.sp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs, key = { it.info.name }) { p ->
                PackRow(p, isActive = p.info.name == active, onMap = p.info.name in used,
                    onShow = { background("Opening ${p.info.name}") { manager.activate(p.info.name); close() } },
                    onDelete = { background("Deleting ${p.info.name}") { manager.delete(p.info.name) } })
            }
            items(jobs, key = { "job-" + it["name"] }) { j -> JobRow(j) }
        }
        BasicText("Download a pack (URL of <name>-dem.pmtiles)", style = TextStyle(color = Muted, fontSize = 13.sp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                url, { url = it },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                modifier = Modifier.weight(1f).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Chip("Download") { if (url.isNotBlank()) background("Starting download") { manager.downloads.start(url) } }
        }
    }
}

@Composable
private fun PackRow(p: PackManager.Listed, isActive: Boolean, onMap: Boolean, onShow: () -> Unit, onDelete: () -> Unit) {
    val info = p.info
    val b = info.bounds
    Column(
        Modifier.fillMaxWidth()
            .border(if (isActive) 2.dp else 1.dp, if (isActive) Accent else Line, RoundedCornerShape(8.dp))
            .clickable(enabled = info.usable, onClick = onShow) // T41: tap to go there (and make it primary)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(info.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            if (isActive) BasicText("PRIMARY", style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            else if (onMap) BasicText("ON MAP", style = TextStyle(color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(10.dp))
            Chip("Delete", onClick = onDelete)
        }
        val parts = listOfNotNull(
            (info.dem ?: info.vector)?.let { "z${it.minZoom}-${it.maxZoom}" },
            mb(info.bytes),
            if (info.vector == null) "terrain only" else "terrain + map",
            "incomplete".takeIf { !info.usable },
        )
        BasicText(parts.joinToString("  ·  "), style = TextStyle(color = Ink, fontSize = 14.sp))
        if (b != null) {
            BasicText(
                String.format(Locale.US, "%.3f, %.3f to %.3f, %.3f", b.minLat, b.minLon, b.maxLat, b.maxLon),
                style = TextStyle(color = Muted, fontSize = 12.sp),
            )
        }
        if (info.attribution.isNotEmpty()) BasicText(info.attribution, style = TextStyle(color = Muted, fontSize = 11.sp), maxLines = 2)
        p.errors.forEach { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp)) }
    }
}

@Composable
private fun JobRow(j: Map<String, Any?>) {
    val done = (j["bytesDone"] as? Number)?.toLong() ?: 0L
    val total = (j["bytesTotal"] as? Number)?.toLong()
    val fraction = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Color(0xFFF4EDE0), RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val rate = (j["bytesPerSec"] as? Number)?.toLong()?.let { " · ${it / 1024} KB/s" }.orEmpty()
        BasicText("Download ${j["name"]}: ${j["state"]}", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        BasicText("${mb(done)} of ${total?.let(::mb) ?: "?"}$rate", style = TextStyle(color = Muted, fontSize = 13.sp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Accent, RoundedCornerShape(3.dp)))
        }
        (j["error"] as? String)?.let { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp)) }
    }
}

@Composable
private fun Chip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = modifier
            .background(Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
