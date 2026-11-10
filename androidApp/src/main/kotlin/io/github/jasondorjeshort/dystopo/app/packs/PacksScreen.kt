package io.github.jasondorjeshort.dystopo.app.packs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.areas.IntroLine
import io.github.jasondorjeshort.dystopo.app.areas.OfflineMapsHelpIcon
import io.github.jasondorjeshort.dystopo.app.areas.AreaOutlines
import io.github.jasondorjeshort.dystopo.app.areas.AreaPicker
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.app.areas.CHOOSE_COUNTRY
import io.github.jasondorjeshort.dystopo.app.areas.CorridorWidthRow
import io.github.jasondorjeshort.dystopo.app.areas.DRAW_AREA
import io.github.jasondorjeshort.dystopo.app.areas.MapRow
import io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList
import io.github.jasondorjeshort.dystopo.app.areas.PickerButton
import io.github.jasondorjeshort.dystopo.app.areas.offlineMapInputs
import io.github.jasondorjeshort.dystopo.app.areas.packBoxes
import io.github.jasondorjeshort.dystopo.app.areas.rowAction
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.firstrun.ImportPicker
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sinh

/**
 * The Offline maps screen's state (main thread; `POST /packs/ui` and `POST /areas/ui` set it too).
 *
 * T235 (ADR 0056 B1b): one list, "Offline maps", for every map on the phone however it arrived; the Packs screen and the Offline
 * areas sheet were two. [visible] is the Areas state's open flag while no picker card stands over the map, so the top chips and
 * the first-run card, which read it, hide over the list and the country list and stay during a card on the map.
 */
object PacksUi {
    /** The list is up. Setting it opens the list (closing a picker card on the map first) or closes it. */
    var visible: Boolean
        get() = Areas.screenOpen && !AreaPicker.onMap()
        set(v) {
            if (v && AreaPicker.onMap()) AreaPicker.close()
            Areas.screenOpen = v
        }

    /** T53: the "From server" section is open (the list shrinks to the lower part, the map shows the catalog outlines). T235: the fold opens with it. */
    var catalogOpen by mutableStateOf(false)

    /** T235: the list at its lower height after Show on map, the map above it ("Full list" goes back). */
    var lowered by mutableStateOf(false)

    /** T235: the fold "More ways to add a map" opened (true) or closed (false) by a tap; null: open while the list is empty. */
    var foldOpen by mutableStateOf<Boolean?>(null)

    /** Bumped to make the open screen fit the camera to the catalog again (`POST /packs/ui?catalog=1`). */
    var fitRequest by mutableStateOf(0)

    /** T57: bumped by `POST /packs/catalog/refresh` (and the Refresh chip) to reload the open catalog section. */
    var catalogRefresh by mutableStateOf(0)

    /** T222: the catalog row whose detail is open (`cat:<id>`); T235: the list's rows open through [Areas.detailOpen]. */
    var detail by mutableStateOf<String?>(null)

    /** T222: a tap on a catalog row: its detail opens, or closes. */
    fun toggle(key: String) {
        detail = if (detail == key) null else key
    }

    /** T57: the list's first visible item and how many rows it has (for `POST /packs/ui` answers; T235: every row of the one list). */
    @Volatile var firstVisibleItem = 0
    @Volatile var localRows = 0

    /** Height of the list while the catalog section is open or after Show on map; the map shows above it. */
    const val CATALOG_SHEET = 0.6f

    /** T235: the screen's own words (ADR 0031: the owner's to adjust); the title is [Screens.OFFLINE_MAPS]. */
    const val MORE_WAYS = "More ways to add a map"
    const val FROM_SERVER = "From server"
    const val EMPTY = "No offline maps yet. Choose a country or draw an area."
    const val FULL_LIST = "Full list"
    const val LINK_LINE = "Download from a link (the address of <name>-dem.pmtiles)"
    const val IMPORT_LINE = "Or import <name>-dem.pmtiles (and -outdoors.pmtiles) from this phone"
    const val IMPORT = "Import"
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val Bad = Color(0xFFB0302A)

/**
 * The map's way to the offline maps: the "Offline maps" chip beside Settings, and when open the one list (T235, ADR 0056 B1b),
 * full screen when it opens, and at [PacksUi.CATALOG_SHEET] of the window, the map with the outlines above it, while the
 * server's catalog is open or after Show on map. Drawn over the map by one line in MainActivity's map screen.
 */
@Composable
fun PacksOverlay() {
    val open = Areas.screenOpen
    // T235: the list opens full screen each time, with the fold by the list's own rule.
    LaunchedEffect(open) { if (!open) { PacksUi.catalogOpen = false; PacksUi.lowered = false; PacksUi.foldOpen = null } }
    Box(Modifier.fillMaxSize()) {
        if (!PacksUi.visible) {
            if (io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.open) return@Box // T262: not over the Tracks and routes screen
            // T33: the top-right chips in one row (Settings, Offline maps); track controls sit in the row below (TrackControls).
            val density = androidx.compose.ui.platform.LocalDensity.current
            io.github.jasondorjeshort.dystopo.app.MapChrome { // T91: capped font scale, and the search box ends before this row
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).control("settingsAndPacks").onGloballyPositioned { c -> // T238: the tag
                            with(density) { c.positionInRoot().x.toDp() }.let { if (it != io.github.jasondorjeshort.dystopo.app.TopChips.topRowLeft) io.github.jasondorjeshort.dystopo.app.TopChips.topRowLeft = it }
                        },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Chip(Screens.SETTINGS) { io.github.jasondorjeshort.dystopo.app.SettingsUi.visible = !io.github.jasondorjeshort.dystopo.app.SettingsUi.visible }
                        Chip(Screens.OFFLINE_MAPS) { PacksUi.visible = true }
                    }
                }
            }
        } else if (AreaPicker.mode != AreaPicker.Mode.Countries) { // the country list is a sheet of its own (`areas/AreasScreen.kt`)
            BackHandler { PacksUi.visible = false }
            OfflineMapsScreen(PackManager.get()) { PacksUi.visible = false }
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

/** T235: the list's fixed items before the rows (the intro line, the picker's two ways in), for the catalog's scroll target. */
private const val ITEMS_BEFORE_ROWS = 2

/**
 * T235 (ADR 0056 B1b, proposal 0056's "The one list, specified"): the title "Offline maps" with its help and Close; the intro
 * line; "Choose a country" and "Draw an area"; one row per offline map ([OfflineMapList], drawn by [MapRow]: a tap opens its
 * details and actions) or the empty line; the trip width row; the fold "More ways to add a map" with the server's catalog under
 * "From server" (its outlines and fit as T53 made them), the link box and Import. The rows are T234's poll, every 1.5 s (0.7 s
 * while one is busy), with the outlines of every row on the map. T226's tap guard on both heights.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineMapsScreen(manager: PackManager, close: () -> Unit) {
    val app = DystopoApp.instance
    var inputs by remember { mutableStateOf<OfflineMapList.Inputs?>(null) } // T234: the one list's inputs, gathered off the UI thread
    var refresh by remember { mutableStateOf(0) } // bumped when a row's action ends, so the list reads again at once
    var removing by remember { mutableStateOf<Set<String>>(emptySet()) } // rows whose Remove runs (a pack has no T221 removal)
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // T53: the server's catalog
    val client = manager.catalogClient
    var catalogRows by remember { mutableStateOf<List<PackCatalogClient.Row>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    val catalogOpen = PacksUi.catalogOpen
    val listState = rememberLazyListState()

    val base = inputs
    val records = Areas.list
    val removals = Areas.removals // T221
    val rows = remember(base, records, removals, removing) {
        base?.let { OfflineMapList.rows(it.copy(records = records, removals = removals, removing = removing)) }.orEmpty()
    }
    val empty = base != null && rows.isEmpty()
    val foldShown = catalogOpen || (PacksUi.foldOpen ?: empty)
    val low = catalogOpen || PacksUi.lowered

    LaunchedEffect(refresh) {
        while (true) {
            val i = withContext(Dispatchers.IO) { offlineMapInputs(app) }
            inputs = i
            val now = OfflineMapList.rows(i.copy(records = Areas.list, removals = Areas.removals))
            AreaOutlines.show(Areas.list, packBoxes(now)) // T235: every row's outline, a pack with no record by its bounds
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(if (now.any { it.tone == OfflineMapList.Tone.BUSY }) 700 else 1500) // T150: paused in the background
        }
    }
    LaunchedEffect(rows.size) { PacksUi.localRows = rows.size }
    LaunchedEffect(listState.firstVisibleItemIndex) { PacksUi.firstVisibleItem = listState.firstVisibleItemIndex }
    // T235: the catalog opens with "From server" at the top of the lower list (T222's note: at font scale 2.0 its header sat
    // clipped below the window until the list scrolled). Rows that load above it later keep it there (the list keeps its key).
    val serverIndex = ITEMS_BEFORE_ROWS + (if (empty) 1 else rows.size) + 2
    LaunchedEffect(catalogOpen, PacksUi.fitRequest, base == null, catalogRows.isEmpty()) {
        if (catalogOpen) listState.scrollToItem(serverIndex)
    }

    LaunchedEffect(catalogOpen, PacksUi.catalogRefresh, PacksUi.fitRequest) {
        if (!catalogOpen) return@LaunchedEffect
        catalogLoading = true
        catalogError = withContext(Dispatchers.IO) { runCatching { client.refresh() }.exceptionOrNull()?.message }
        catalogLoading = false
        val first = withContext(Dispatchers.IO) { client.rows() }
        catalogRows = first
        CatalogOverlay.show(first)
        withContext(Dispatchers.IO) { CatalogOverlay.framing(first, runCatching { DystopoApp.instance.map.camera() }.getOrNull())?.let { fitAbove(it) } }
        while (true) {
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(if (catalogRows.any { it.status == CatalogStatus.DOWNLOADING || it.status == CatalogStatus.CHECKING }) 700 else 2000) // T150: paused in the background
            val r = withContext(Dispatchers.IO) { client.rows() }
            catalogRows = r
            CatalogOverlay.show(r)
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

    // T222: the controls of a catalog row's stopped or paused download.
    fun jobAction(j: Map<String, Any?>, action: String) {
        val name = j["name"] as? String ?: return
        when (action) {
            DownloadStatus.Action.RESUME_NOW.label, DownloadStatus.Action.RETRY.label -> background("Trying again") { manager.downloads.retry(name) }
            DownloadStatus.Action.STORAGE.label -> openStorage(close)
            else -> background("Removing $name") {
                // An update's stopped download goes; the map it would have updated stays.
                if (j["update"] == true) manager.downloads.cancel(name) else manager.delete(name)
                io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(DystopoApp.instance).region.dismiss(name)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    // T222: a fresh node for each form. Updating one node from the sheet's modifiers (no status bar padding) to the full screen's
    // recursed in Compose's inset padding until the main thread froze and the stack overflowed (main's screen did it too).
    key(low) {
    Column(
        (if (low) Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(PacksUi.CATALOG_SHEET).background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).border(1.dp, Line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).pointerInput(Unit) { detectTapGestures { } }
        else Modifier.fillMaxSize().background(Paper).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) // T226: a tap on its paper stays out of the overlay's chips beneath
            .navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Screens.OFFLINE_MAPS, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                OfflineMapsHelpIcon() // T109
            }
            if (low) {
                Chip(PacksUi.FULL_LIST) { PacksUi.catalogOpen = false; PacksUi.lowered = false }
                Spacer(Modifier.width(6.dp))
            }
            Chip("Close") { close() }
        }
        message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 13.sp)) }
        // T79: the import started from here (progress and result come from the importer).
        io.github.jasondorjeshort.dystopo.app.firstrun.FirstRun.message?.takeIf { it.startsWith("Import") }?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 13.sp)) }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") { IntroLine() }
            // T142 (ADR 0041 §5): the picker's ways in; the third is on the long-press card and a search result.
            item(key = "pickers") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PickerButton(CHOOSE_COUNTRY) { AreaPicker.openCountries() }
                    PickerButton(DRAW_AREA) { AreaPicker.startDraw(null) }
                }
            }
            if (empty) item(key = "empty") { BasicText(PacksUi.EMPTY, style = TextStyle(color = Ink, fontSize = 14.sp)) }
            items(rows, key = { it.id }) { row ->
                MapRow(row, row.detailKey in Areas.detailOpen) { action ->
                    rowAction(row, action, base, { id, on -> removing = if (on) removing + id else removing - id }) { refresh++ }
                }
            }
            item(key = "width") { CorridorWidthRow() } // T130
            // T235: the fold, open by itself while the list is empty.
            item(key = "fold") {
                Row(
                    Modifier.fillMaxWidth().clickable { val o = !foldShown; PacksUi.foldOpen = o; if (!o) PacksUi.catalogOpen = false }.padding(top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText((if (foldShown) "▾ " else "▸ ") + PacksUi.MORE_WAYS, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                }
            }
            if (foldShown) {
                // T53: "From server"
                item(key = "server-header") {
                    Row(
                        Modifier.fillMaxWidth().clickable { PacksUi.catalogOpen = !catalogOpen }.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText((if (catalogOpen) "▾ " else "▸ ") + PacksUi.FROM_SERVER, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                        if (catalogOpen) Chip("Refresh") { PacksUi.catalogRefresh++ }
                    }
                }
            }
            if (foldShown && catalogOpen) {
                item(key = "server-status") {
                    // T79: the alpha said "No server set (Settings)." while Settings had no server field; the status now
                    // opens Settings › Sources (the server URL and its Test), and names the server the catalog came from.
                    val server = client.configuredServer()
                    val text = when {
                        server == null -> "No server set. Set one in ${Screens.SETTINGS} › ${Screens.SOURCES} ›"
                        catalogLoading -> "Loading the catalog from $server..."
                        catalogError != null -> "Catalog unavailable: $catalogError. Check the server in ${Screens.SETTINGS} › ${Screens.SOURCES} ›"
                        catalogRows.isEmpty() -> "$server has no maps."
                        else -> "${DownloadStatus.count(catalogRows.size, "map")} on $server"
                    }
                    val linked = server == null || catalogError != null
                    BasicText(
                        text,
                        style = TextStyle(color = if (catalogError != null) Color(0xFFB0302A) else if (linked) Color(0xFF2C7BB6) else Muted, fontSize = 13.sp, fontWeight = if (linked) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = if (linked) Modifier.clickable { close(); io.github.jasondorjeshort.dystopo.app.SettingsUi.open(io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.SERVER_GROUP) }.padding(vertical = 4.dp) else Modifier,
                    )
                }
                items(catalogRows, key = { "cat-" + it.pack.id }) { r ->
                    CatalogRow(
                        r,
                        onShow = { r.pack.bounds?.let { b -> thread { fitAbove(b) } } },
                        onDownload = { background("Downloading ${r.pack.name}") { client.download(r.pack.id) } },
                        onUpdate = { background("Updating ${r.pack.name}") { client.update(r.pack.name) } },
                        onDelete = { background("Removing ${r.pack.name}") { manager.delete(r.pack.name) } },
                        onJobAction = { a -> r.job?.let { jobAction(it, a) } },
                    )
                }
            }
            // The link box and Import, while the catalog is closed (at the lower height the catalog keeps the room, as before).
            if (foldShown && !catalogOpen) {
                item(key = "link") {
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(PacksUi.LINK_LINE, style = TextStyle(color = Muted, fontSize = 13.sp))
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
                // T79: map files already on the phone (Downloads, a USB stick, a cloud drive) through the system file picker.
                item(key = "import") {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(PacksUi.IMPORT_LINE, Modifier.weight(1f), style = TextStyle(color = Muted, fontSize = 13.sp))
                        Spacer(Modifier.width(8.dp))
                        Chip(PacksUi.IMPORT) { ImportPicker.request() }
                    }
                }
            }
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

/**
 * T53: one map of the server's catalog: name, status and size, and its actions; T222: a tap also opens the zooms, the profile,
 * the build date and the area, and a stopped or paused download says so with its own controls.
 */
@Composable
private fun CatalogRow(r: PackCatalogClient.Row, onShow: () -> Unit, onDownload: () -> Unit, onUpdate: () -> Unit, onDelete: () -> Unit, onJobAction: (String) -> Unit) {
    val p = r.pack
    val color = StatusColors.getValue(r.status)
    val key = "cat:${p.id}"
    val job = r.job
    val halted = (job?.get("word") as? String)?.takeIf { it == DownloadStatus.WORD_PAUSED || it == DownloadStatus.WORD_STOPPED }
    Column(
        Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).clickable { PacksUi.toggle(key); onShow() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(p.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            if (halted != null) BasicText(halted.uppercase(Locale.US), style = TextStyle(color = if (halted == DownloadStatus.WORD_STOPPED) Bad else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            else BasicText(CatalogOverlay.statusText(r.status).uppercase(Locale.US), style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        BasicText(DownloadStatus.sizeText(p.bytes), style = TextStyle(color = Ink, fontSize = 14.sp))
        if (PacksUi.detail == key) {
            val parts = listOfNotNull(
                if (p.minZoom != null && p.maxZoom != null) "z${p.minZoom}-${p.maxZoom}" else null,
                p.profile ?: "terrain only",
                p.builtDate?.let { "built $it" },
            )
            BasicText(parts.joinToString("  ·  "), style = TextStyle(color = Ink, fontSize = 14.sp))
            p.bounds?.let { b -> BasicText(area(b), style = TextStyle(color = Muted, fontSize = 12.sp)) }
            (job?.get("error") as? String)?.takeIf { halted != null }?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 3) }
        }
        if (halted != null) (job?.get("sentence") as? String)?.let { BasicText(it, style = TextStyle(color = if (halted == DownloadStatus.WORD_STOPPED) Bad else Muted, fontSize = 13.sp)) }
        else if (r.status == CatalogStatus.DOWNLOADING && job != null) {
            val done = (job["bytesDone"] as? Number)?.toLong() ?: 0L
            val total = (job["bytesTotal"] as? Number)?.toLong()
            val fraction = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            BasicText("${if (job["update"] == true) "Updating" else "Downloading"}: ${mb(done)} of ${total?.let(::mb) ?: "?"}", style = TextStyle(color = Muted, fontSize = 13.sp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (halted != null) JobActions(job, onJobAction)
            else when (r.status) {
                CatalogStatus.AVAILABLE -> Chip("Download", onClick = onDownload)
                CatalogStatus.UPDATE -> { Chip("Update", onClick = onUpdate); Chip("Remove", onClick = onDelete) }
                CatalogStatus.INSTALLED, CatalogStatus.CHECKING -> Chip("Remove", onClick = onDelete)
                CatalogStatus.DOWNLOADING -> {}
            }
        }
    }
}

/** A box's rough size and corners, for the catalog list. */
private fun area(b: PackBounds): String {
    val w = (b.maxLon - b.minLon) * 111_320 * cos(b.centerLat * PI / 180)
    val h = (b.maxLat - b.minLat) * 110_570
    // T101: in the user's units ("120 x 85 km", "75 x 53 mi").
    val size = io.github.jasondorjeshort.dystopo.render.UnitFormat.size(w, h, io.github.jasondorjeshort.dystopo.app.AppSettings.units)
    return size + String.format(Locale.US, "  ·  %.2f, %.2f to %.2f, %.2f", b.minLat, b.minLon, b.maxLat, b.maxLon)
}

/** T222: a catalog row's download controls, by their labels. Remove on an update's download leaves the installed map, and says so. */
@Composable
private fun JobActions(j: Map<String, Any?>?, onAction: (String) -> Unit) {
    (j?.get("actions") as? List<*>).orEmpty().forEach { a ->
        val action = a.toString()
        Chip(if (action == DownloadStatus.Action.REMOVE.label && j?.get("update") == true) "Cancel update" else action) { onAction(action) }
    }
}

/** T222: the way to the Storage screen from a download that does not fit. */
private fun openStorage(close: () -> Unit) {
    close()
    io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = true
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
