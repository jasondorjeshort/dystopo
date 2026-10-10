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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sinh

/** Whether the pack list is showing (main thread; `POST /packs/ui` sets it too). */
object PacksUi {
    var visible by mutableStateOf(false)

    /** T53: the "From server" section is open (the list shrinks to the lower part, the map shows the catalog outlines). */
    var catalogOpen by mutableStateOf(false)

    /** Bumped to make the open screen fit the camera to the catalog again (`POST /packs/ui?catalog=1`). */
    var fitRequest by mutableStateOf(0)

    /** T57: bumped by `POST /packs/catalog/refresh` (and the Refresh chip) to reload the open catalog section. */
    var catalogRefresh by mutableStateOf(0)

    /** T57: the list's first visible row and how many local pack rows there are (for `POST /packs/ui` answers). */
    @Volatile var firstVisibleItem = 0
    @Volatile var localRows = 0

    /** Height of the list while the catalog section is open; the map shows above it. */
    const val CATALOG_SHEET = 0.6f
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
    // T53: catalog outlines on the map while the server section is open.
    val showOutlines = PacksUi.visible && PacksUi.catalogOpen
    LaunchedEffect(showOutlines) {
        DystopoApp.instance.map.mapView?.let { CatalogOverlay.bind(it) }
        if (!showOutlines) CatalogOverlay.show(null)
    }
}

/**
 * T53: moves the camera so [b] fills the part of the map above the pack list ([visible] of the height from
 * the top), or the whole map when [visible] is 1. T57: and below the online indicator at the top left
 * ([io.github.jasondorjeshort.dystopo.app.MapInsets]), so its credit line does not cover a catalog label.
 */
fun fitAbove(b: PackBounds, visible: Double = 1.0 - PacksUi.CATALOG_SHEET): Map<String, Any?>? {
    val app = DystopoApp.instance
    val view = app.map.mapView ?: return null
    return try {
        val (w, h, density) = app.map.onMain { Triple(view.width, view.height, view.resources.displayMetrics.density) }
        if (w <= 0 || h <= 0) return null
        val wd = w / density.toDouble()
        val hd = h / density.toDouble()
        val bottom = hd * visible
        val top = (io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChips?.get(3)?.div(density)?.toDouble() ?: 0.0).coerceIn(0.0, bottom / 2)
        val zoom = b.fitZoom(wd, bottom - top, 512.0, 24.0).coerceIn(3.0, 16.0) // the map stops at z3
        // The box centre goes to the middle of the visible band [top, bottom], hd / 2 - (top + bottom) / 2 above the map centre.
        val worldPx = 512.0 * 2.0.pow(zoom)
        val y = (PackBounds.mercatorY(b.centerLat) + (hd / 2 - (top + bottom) / 2) / worldPx).coerceIn(0.0, 1.0)
        val lat = atan(sinh(PI * (1 - 2 * y))) * 180 / PI
        app.map.setCamera(lat, b.centerLon, zoom, 0.0, 0.0)
    } catch (e: Exception) {
        null
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
    // T53: the server's catalog
    val client = manager.catalogClient
    var rows by remember { mutableStateOf<List<PackCatalogClient.Row>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    val catalogOpen = PacksUi.catalogOpen
    val listState = rememberLazyListState()

    // T57: rows that arrive above the first visible one (the local packs load after the catalog section is
    // already showing) would keep the list anchored below them, so the local packs sat above the visible top:
    // start at the top whenever the section opens or the local list changes size.
    LaunchedEffect(catalogOpen, packs.size) {
        listState.scrollToItem(0)
        PacksUi.localRows = packs.size
    }
    LaunchedEffect(listState.firstVisibleItemIndex) { PacksUi.firstVisibleItem = listState.firstVisibleItemIndex }

    LaunchedEffect(catalogOpen, PacksUi.catalogRefresh, PacksUi.fitRequest) {
        if (!catalogOpen) return@LaunchedEffect
        catalogLoading = true
        catalogError = withContext(Dispatchers.IO) { runCatching { client.refresh() }.exceptionOrNull()?.message }
        catalogLoading = false
        val first = withContext(Dispatchers.IO) { client.rows() }
        rows = first
        CatalogOverlay.show(first)
        withContext(Dispatchers.IO) { CatalogOverlay.framing(first, runCatching { DystopoApp.instance.map.camera() }.getOrNull())?.let { fitAbove(it) } }
        while (true) {
            delay(if (rows.any { it.status == CatalogStatus.DOWNLOADING || it.status == CatalogStatus.CHECKING }) 700 else 2000)
            val r = withContext(Dispatchers.IO) { client.rows() }
            rows = r
            CatalogOverlay.show(r)
        }
    }

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

    // T53: with the server section open the list takes the lower part of the screen and the map, with the
    // catalog outlines, shows above it.
    Box(Modifier.fillMaxSize()) {
    Column(
        (if (catalogOpen) Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(PacksUi.CATALOG_SHEET).background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).border(1.dp, Line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
        else Modifier.fillMaxSize().background(Paper).statusBarsPadding())
            .navigationBarsPadding().padding(16.dp),
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
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs, key = { it.info.name }) { p ->
                PackRow(p, isActive = p.info.name == active, onMap = p.info.name in used,
                    onShow = { background("Opening ${p.info.name}") { manager.activate(p.info.name); close() } },
                    onDelete = { background("Deleting ${p.info.name}") { manager.delete(p.info.name) } })
            }
            // Catalog downloads have their progress in the catalog row.
            items(jobs.filter { j -> !catalogOpen || rows.none { it.pack.name == j["name"] } }, key = { "job-" + it["name"] }) { j -> JobRow(j) }
            // T53: "From server"
            item(key = "server-header") {
                Row(
                    Modifier.fillMaxWidth().clickable { PacksUi.catalogOpen = !catalogOpen }.padding(top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText((if (catalogOpen) "\u25BE " else "\u25B8 ") + "From server", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                    if (catalogOpen) Chip("Refresh") { PacksUi.catalogRefresh++ }
                }
            }
            if (catalogOpen) {
                item(key = "server-status") {
                    val server = client.server ?: client.configuredServer()
                    val text = when {
                        server == null -> "No server set (Settings)."
                        catalogLoading -> "Loading the catalog from $server..."
                        catalogError != null -> "Catalog unavailable: $catalogError"
                        rows.isEmpty() -> "$server has no packs."
                        else -> "${rows.size} packs on $server"
                    }
                    BasicText(text, style = TextStyle(color = if (catalogError != null) Color(0xFFB0302A) else Muted, fontSize = 13.sp))
                }
                items(rows, key = { "cat-" + it.pack.id }) { r ->
                    CatalogRow(
                        r,
                        onShow = { r.pack.bounds?.let { b -> thread { fitAbove(b) } } },
                        onDownload = { background("Downloading ${r.pack.name}") { client.download(r.pack.id) } },
                        onUpdate = { background("Updating ${r.pack.name}") { client.update(r.pack.name) } },
                        onDelete = { background("Deleting ${r.pack.name}") { manager.delete(r.pack.name) } },
                    )
                }
            }
        }
        if (!catalogOpen) {
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
    }
}

private val StatusColors = mapOf(
    CatalogStatus.AVAILABLE to Color(0xFF2C7FB8),
    CatalogStatus.INSTALLED to Color(0xFF1A9850),
    CatalogStatus.UPDATE to Color(0xFFE66101),
    CatalogStatus.DOWNLOADING to Color(0xFF7B3294),
    CatalogStatus.CHECKING to Color(0xFF808080),
)

/** T53: one pack of the server's catalog: name and status, zooms, size, profile, build date, area, and its actions. */
@Composable
private fun CatalogRow(r: PackCatalogClient.Row, onShow: () -> Unit, onDownload: () -> Unit, onUpdate: () -> Unit, onDelete: () -> Unit) {
    val p = r.pack
    val color = StatusColors.getValue(r.status)
    Column(
        Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).clickable(onClick = onShow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(p.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            BasicText(CatalogOverlay.statusText(r.status).uppercase(Locale.US), style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        val parts = listOfNotNull(
            if (p.minZoom != null && p.maxZoom != null) "z${p.minZoom}-${p.maxZoom}" else null,
            mb(p.bytes),
            p.profile ?: "terrain only",
            p.builtDate?.let { "built $it" },
        )
        BasicText(parts.joinToString("  ·  "), style = TextStyle(color = Ink, fontSize = 14.sp))
        p.bounds?.let { b -> BasicText(area(b), style = TextStyle(color = Muted, fontSize = 12.sp)) }
        val job = r.job
        if (r.status == CatalogStatus.DOWNLOADING && job != null) {
            val done = (job["bytesDone"] as? Number)?.toLong() ?: 0L
            val total = (job["bytesTotal"] as? Number)?.toLong()
            val fraction = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            BasicText("${if (job["update"] == true) "Updating" else "Downloading"}: ${mb(done)} of ${total?.let(::mb) ?: "?"}", style = TextStyle(color = Muted, fontSize = 13.sp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            }
        }
        if (job?.get("state") == "failed") (job["error"] as? String)?.let { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp), maxLines = 3) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            when (r.status) {
                CatalogStatus.AVAILABLE -> Chip("Download", onClick = onDownload)
                CatalogStatus.UPDATE -> { Chip("Update", onClick = onUpdate); Chip("Delete", onClick = onDelete) }
                CatalogStatus.INSTALLED, CatalogStatus.CHECKING -> Chip("Delete", onClick = onDelete)
                CatalogStatus.DOWNLOADING -> {}
            }
        }
    }
}

/** A box's rough size and corners, for the catalog list. */
private fun area(b: PackBounds): String {
    val w = (b.maxLon - b.minLon) * 111.32 * cos(b.centerLat * PI / 180)
    val h = (b.maxLat - b.minLat) * 110.57
    return String.format(Locale.US, "%.0f x %.0f km  ·  %.2f, %.2f to %.2f, %.2f", w, h, b.minLat, b.minLon, b.maxLat, b.maxLon)
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
            if (info.vector == null) "terrain only" else if (info.dem == null) "map only" else "terrain + map", // T65: vector-only packs
            "incomplete".takeIf { !info.usable && !(info.dem == null && info.vector != null) },
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
