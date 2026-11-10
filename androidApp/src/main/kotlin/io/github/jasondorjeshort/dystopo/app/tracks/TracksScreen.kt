package io.github.jasondorjeshort.dystopo.app.tracks

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker
import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.app.tracking.TrackingUiState
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.sync.RouteListItem

/**
 * The Tracks and routes screen's state (T262; main thread, `POST /tracks/ui` sets it too). One full-screen list, built like
 * Offline maps, replaces the Tracks sheet and the Routes sheet (T219): every track and route, newest first, under a filter
 * (All, Tracks, Routes). The Tracks chip of the unfolded track bar opens it on Tracks, the Routes chip on Routes
 * ([io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.listOpen] and [Trips.listOpen] read and set [open]).
 */
object TracksUi {
    enum class Filter(val code: String, val label: String) { ALL("all", ALL_LABEL), TRACKS("tracks", TRACKS_LABEL), ROUTES("routes", ROUTES_LABEL) }

    /** T272: the list's order (the survey's point 3: name and length beside newest first); not a setting (survey point 6). */
    enum class Sort(val code: String, val label: String) { NEWEST("newest", SORT_NEWEST), NAME("name", SORT_NAME), LENGTH("length", SORT_LENGTH) }

    var sort by mutableStateOf(Sort.NEWEST)

    /** T272: select mode: the rows carry a tick box, the bar under the list acts on the ticked ones ([selected], row keys). */
    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())

    /** T272: the select bar's Delete is asking. */
    var bulkDeleteAsk by mutableStateOf(false)

    /** T272: opens the folder picker for the select bar's Export GPX (set while the screen is composed). */
    internal var pickFolder: (() -> Unit)? = null

    /** T272: the rows a folder pick is for (their keys, taken when the picker opened). */
    internal var exportKeys: List<String> = emptyList()

    /** T272: select mode on, with [first] ticked (a long press on a row), or off. */
    fun select(on: Boolean, first: String? = null) {
        selecting = on
        selected = if (on && first != null) setOf(first) else emptySet()
        bulkDeleteAsk = false
        menu = null
        renaming = null
        deleteAsk = null
    }

    /** T272: ticks or unticks the row [key]. */
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
        bulkDeleteAsk = false
    }

    var open by mutableStateOf(false)
        private set
    var filter by mutableStateOf(Filter.TRACKS)

    /** The title row's overflow (Sync now, Sync status, Share) is open. */
    var moreOpen by mutableStateOf(false)

    /** The tracks' Import line (Pick file and the app's own GPX files) is open. */
    var importOpen by mutableStateOf(false)

    /** The row whose menu is open ([rowKey]), its name being edited, its delete being asked. */
    var menu by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<String?>(null)
    var deleteAsk by mutableStateOf<String?>(null)

    /** Opens the screen on [f]. */
    fun show(f: Filter) {
        filter = f
        open = true
    }

    fun close() {
        open = false
        select(false)
        moreOpen = false
        importOpen = false
        menu = null
        renaming = null
        deleteAsk = null
    }
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Line = Color(0xFFE2D8C6)
private val Link = Color(0xFF2C7BB6)
private val Violet = Color(0xFF7B3294)
private val Bad = Color(0xFFB0302A)

/** A row's kind ("track" or "route"), and the key its menu, rename and delete state go by ("track:<id>"). */
internal fun rowKey(kind: String, id: String): String = "$kind:$id"

/** T262: one row of the list, as drawn and as `GET /tracks/ui` reports it. Pure. */
internal data class ListRow(
    val kind: String,
    val id: String,
    val name: String,
    val line: String,
    val activity: String?,
    val shown: Boolean,
    val recording: Boolean,
    val createdAt: Long,
    /** A route's third line (its via points, waypoints, distance off trail), or null. */
    val counts: String? = null,
    /** T272: its length in metres for the Length sort (a track's recorded distance, a route's planned one), or null. */
    val lengthM: Double? = null,
) {
    val key: String get() = rowKey(kind, id)
}

/**
 * T262: the list's rows under [filter], newest first (by creation; ties by id): each track with its activity (or "Track"),
 * date, length (the live one while it records) and "recording"; each route with its mode, date and the card's figures.
 * T272: in [sort]'s order ([sortRows]). Pure, tested.
 */
internal fun listRows(
    tracks: List<TrackSummary>,
    distances: Map<String, Double>,
    activeId: String?,
    liveDistanceM: Double,
    hiddenTracks: Set<String>,
    routes: List<RouteListItem>,
    hiddenRoutes: Set<String>,
    filter: TracksUi.Filter,
    units: ContourUnits,
    date: (Long) -> String,
    routeFigures: (RouteListItem) -> String,
    sort: TracksUi.Sort = TracksUi.Sort.NEWEST,
): List<ListRow> {
    val out = ArrayList<ListRow>()
    if (filter != TracksUi.Filter.ROUTES) for (t in tracks) {
        val d = distances[t.id] ?: liveDistanceM.takeIf { t.id == activeId }
        val activity = t.activity?.let { RecordingProfiles.label(it) }
        out += ListRow(
            "track", t.id, t.name, trackLine(activity, date(t.createdAt), d?.let { UnitFormat.distance(it, units) }, t.recording),
            activity, t.id !in hiddenTracks, t.recording, t.createdAt, lengthM = d,
        )
    }
    if (filter != TracksUi.Filter.TRACKS) for (r in routes) {
        out += ListRow(
            "route", r.id, r.name.ifBlank { Trips.UNNAMED }, routeLine(TravelMode.labelOf(r.mode).replaceFirstChar { it.uppercase() }, date(r.createdAt), routeFigures(r)),
            null, r.id !in hiddenRoutes, false, r.createdAt,
            io.github.jasondorjeshort.dystopo.app.trips.routeRowCounts(r.viaPoints, r.waypoints, if (r.straightM > 0) UnitFormat.distance(r.straightM, units) else null),
            lengthM = r.distanceM,
        )
    }
    return sortRows(out, sort)
}

/**
 * T272: [rows] in [sort]'s order: newest first (by creation; ties by id); by name, A to Z ignoring case, ties newest first;
 * by length, longest first, a row with no length last, ties newest first. Pure, tested.
 */
internal fun sortRows(rows: List<ListRow>, sort: TracksUi.Sort): List<ListRow> {
    val newest = compareByDescending<ListRow> { it.createdAt }.thenBy { it.id }
    return when (sort) {
        TracksUi.Sort.NEWEST -> rows.sortedWith(newest)
        TracksUi.Sort.NAME -> rows.sortedWith(compareBy<ListRow> { it.name.lowercase() }.then(newest))
        TracksUi.Sort.LENGTH -> rows.sortedWith(compareBy<ListRow> { it.lengthM == null }.thenByDescending { it.lengthM ?: 0.0 }.then(newest))
    }
}

/** T272: the select bar's count ("3 selected"). Pure, tested. */
internal fun selectedLine(n: Int): String = "$n selected"

/**
 * T272: the question before the select bar's Delete, naming what goes ("Delete 2 tracks and 1 route?"); a track that
 * records is not deleted from here (it ends on its panel), so it is not counted. Pure, tested.
 */
internal fun bulkDeleteQuestion(tracks: Int, routes: Int): String {
    fun n(k: Int, one: String) = "$k $one" + if (k == 1) "" else "s"
    val what = listOfNotNull(n(tracks, "track").takeIf { tracks > 0 }, n(routes, "route").takeIf { routes > 0 }).joinToString(" and ")
    return "Delete $what? They are deleted on your other devices when they sync."
}

/** T272: the select bar's actions, in order. */
internal val SELECT_ACTIONS = listOf(SHOW, HIDE, EXPORT_GPX, SHARE_GPX, DELETE)

/** T272: a sort chip's words ("Newest"). */
internal fun sortLabel(s: TracksUi.Sort): String = s.label

/** T262: a track row's second line: its activity (or "Track"), date, length, and "recording". Pure, tested. */
internal fun trackLine(activity: String?, date: String, distance: String?, recording: Boolean): String =
    listOfNotNull(activity ?: TRACK_WORD, date, distance, RECORDING_WORD.takeIf { recording }).joinToString(" · ")

/** T262: a route row's second line: its mode as a route ("Hike route"), date, then the card's figures. Pure, tested. */
internal fun routeLine(mode: String, date: String, figures: String): String = "$mode route · $date · $figures"

/** T262: the word beside the eye. Pure, tested. */
internal fun shownWord(shown: Boolean): String = if (shown) SHOWN else HIDDEN

/** T262: the eye's action for a screen reader and `uiautomator dump`: what a tap does. Pure, tested. */
internal fun eyeAction(shown: Boolean, name: String): String = if (shown) "Hide $name on the map" else "Show $name on the map"

/** T262: the question before a delete. Pure, tested. */
internal fun deleteQuestion(kind: String, name: String): String =
    "Delete the ${if (kind == "route") "route" else "track"} $name? It is deleted on your other devices when they sync."

/** T262: a row's menu, in its order (rename, export, statistics, delete, as the survey has them; then what the sheets had). Pure, tested. */
internal fun menuOf(kind: String, recording: Boolean = false): List<String> =
    if (kind == "route") listOf(OPEN, RENAME, EXPORT_GPX, SHARE_GPX, GO, SHARE_LINK, DELETE)
    else listOfNotNull(STATISTICS, RENAME, EXPORT_GPX, SHARE_GPX, GO, ACTIVITY, REPORT, DELETE.takeIf { !recording })

/** T262: the list with nothing in it under [filter]. Pure, tested. */
internal fun emptyLine(filter: TracksUi.Filter): String = when (filter) {
    TracksUi.Filter.TRACKS -> TRACKS_EMPTY
    TracksUi.Filter.ROUTES -> io.github.jasondorjeshort.dystopo.app.trips.ROUTES_EMPTY
    TracksUi.Filter.ALL -> ALL_EMPTY
}

/** T262: a filter chip's words with its count ("Tracks · 3"). Pure, tested. */
internal fun filterLabel(f: TracksUi.Filter, count: Int): String = "${f.label} · $count"

/** T262 (ADR 0031: the owner's to adjust). */
internal const val TITLE = "Tracks and routes"
internal const val ALL_LABEL = "All"
internal const val TRACKS_LABEL = "Tracks"
internal const val ROUTES_LABEL = "Routes"
internal const val SHOWN = "Shown"
internal const val HIDDEN = "Hidden"
internal const val TRACK_WORD = "Track"
internal const val RECORDING_WORD = "recording"
internal const val OPEN = "Open"
internal const val STATISTICS = "Statistics"
internal const val RENAME = "Rename"
internal const val EXPORT_GPX = "Export GPX"
internal const val GO = "Go"
internal const val ACTIVITY = "Activity"
internal const val REPORT = "Report"
internal const val SHARE_LINK = "Share link"
internal const val DELETE = "Delete"
internal const val CANCEL = "Cancel"
internal const val SAVE = "Save"
internal const val CLOSE = "Close"
internal const val TRACKS_EMPTY = "No tracks yet. Record one with the track bar, or import a GPX file."
internal const val ALL_EMPTY = "No tracks or routes yet."
internal const val MORE_ACTIONS = "More actions"

/** T272 (ADR 0031: the owner's to adjust). */
internal const val SHARE_GPX = "Share GPX"
internal const val SELECT = "Select"
internal const val SELECT_ALL = "All"
internal const val SELECT_NONE = "None"
internal const val DONE = "Done"
internal const val SHOW = "Show"
internal const val HIDE = "Hide"
internal const val SORT_WORD = "Sort"
internal const val SORT_NEWEST = "Newest"
internal const val SORT_NAME = "Name"
internal const val SORT_LENGTH = "Length"
internal const val NOTHING_SELECTED = "Tick tracks and routes to act on them together."

/** T262: the list's help, by the key of the icon after its title. */
internal val TRACKS_LIST_HELP: List<HelpText> = listOf(
    HelpText("tracks.list", TITLE, "The map, top right: Tracks, then Tracks; or Routes", show = { TracksUi.show(TracksUi.Filter.ALL) }) {
        "Every track and route, newest first or by name or length. The eye shows or hides one on this phone's map; a hidden " +
            "one stays here, marked Hidden. A tap opens its card; the dots hold Rename, Export GPX, Share GPX, Statistics and " +
            "Delete. Select, or a long press, ticks several to show, hide, export, share or delete together."
    },
)

/**
 * T262: the Tracks and routes screen, full screen over the map like Offline maps (`packs/PacksScreen.kt`), with Back and
 * Close. The title with its help and Close; Import (a track's GPX, or a route's under the Routes filter) and the overflow
 * (Sync now, Sync status, Share); the filter chips with their counts; the message line; then the rows ([listRows]): the
 * name, a line with the activity or mode, date and length, the eye with "Shown" or "Hidden", and the dots that open the
 * row's menu ([menuOf]). A tap on a row opens its card on the map (a track's statistics, a route's trip card). One line
 * in `MainActivity`.
 *
 * T272: the sort chips (Newest, Name, Length) under the filters; Select (or a long press on a row) turns on select mode,
 * where a tap ticks a row and the bar under the list shows, hides, exports (into a folder the user picks), shares (the
 * system's share sheet, [io.github.jasondorjeshort.dystopo.app.ShareGpx]) or deletes (asking first) the ticked ones; the
 * row menu's Share GPX shares one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksScreen() {
    if (!TracksUi.open) return
    // T229: until the tracker is ready the screen says so and has no rows yet.
    val tracking = TrackingUiState.tracking
    val ui = TrackingUiState.ui
    val units = AppSettings.units
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Thread { io.github.jasondorjeshort.dystopo.app.tracking.importUri(Tracking.of(DystopoApp.instance), uri) }.start()
    }
    // T272: the select bar's Export GPX: the system's folder picker, then one file per ticked row.
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> folderPicked(uri) }
    DisposableEffect(folder) {
        TracksUi.pickFolder = { folder.launch(null) }
        onDispose { TracksUi.pickFolder = null }
    }
    BackHandler { if (TracksUi.selecting) TracksUi.select(false) else TracksUi.close() }
    LaunchedEffect(tracking) { tracking?.refreshUi() }
    val sort = TracksUi.sort
    val all = remember(ui.tracks, ui.distances, ui.activeId, ui.distanceM, ui.hidden, Trips.list, Trips.hiddenRoutes, units, sort) {
        listRows(
            ui.tracks, ui.distances, ui.activeId, ui.distanceM, ui.hidden, Trips.list, Trips.hiddenRoutes, TracksUi.Filter.ALL, units,
            { ms -> io.github.jasondorjeshort.dystopo.app.trips.shortDate(ms) },
            { r -> io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.figures(r.distanceM, r.ascentM, r.descentM, r.timeS, TravelMode.of(r.mode)) },
            sort,
        )
    }
    val filter = TracksUi.filter
    val rows = all.filter { filter == TracksUi.Filter.ALL || (filter == TracksUi.Filter.TRACKS) == (it.kind == "track") }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Paper).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding() // a tap on its paper stays out of the map
                .navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(TITLE, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                    HelpIcon("tracks.list")
                }
                Chip(CLOSE) { TracksUi.close() }
            }
            if (tracking == null) {
                BasicText(io.github.jasondorjeshort.dystopo.app.tracking.TRACKS_STARTING, style = TextStyle(color = Ink, fontSize = 14.sp))
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filter == TracksUi.Filter.ROUTES) {
                    io.github.jasondorjeshort.dystopo.app.routing.RouteImportAction() // T65: a route's GPX from the system's picker
                } else {
                    Action("Import") { TracksUi.importOpen = !TracksUi.importOpen }
                    if (TracksUi.importOpen) Action("Pick file") {
                        TracksUi.importOpen = false
                        picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*"))
                    }
                }
                Action(if (TracksUi.selecting) DONE else SELECT) { TracksUi.select(!TracksUi.selecting) } // T272
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    io.github.jasondorjeshort.dystopo.app.routing.OverflowDots(MORE_ACTIONS) { TracksUi.moreOpen = !TracksUi.moreOpen }
                }
            }
            if (TracksUi.moreOpen) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    io.github.jasondorjeshort.dystopo.app.sync.SyncNowAction() // T44
                    io.github.jasondorjeshort.dystopo.app.share.ShareAction() // T54: phone-to-phone sync
                }
            }
            if (TracksUi.importOpen && filter != TracksUi.Filter.ROUTES) io.github.jasondorjeshort.dystopo.app.tracking.ImportList(tracking) { TracksUi.importOpen = false }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in TracksUi.Filter.entries) {
                    val n = when (f) { TracksUi.Filter.ALL -> all.size; TracksUi.Filter.TRACKS -> all.count { it.kind == "track" }; TracksUi.Filter.ROUTES -> all.count { it.kind == "route" } }
                    FilterChip(filterLabel(f, n), f == filter) { TracksUi.filter = f; TracksUi.menu = null }
                }
            }
            // T272: the order (the survey's point 3).
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(SORT_WORD, Modifier.align(Alignment.CenterVertically).padding(end = 2.dp), style = TextStyle(color = Muted, fontSize = 13.sp))
                for (o in TracksUi.Sort.entries) SortChip(sortLabel(o), o == sort) { TracksUi.sort = o }
            }
            ui.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) }
            if (filter == TracksUi.Filter.ROUTES) io.github.jasondorjeshort.dystopo.app.routing.RouteImportPicker.line?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) } // T219
            if (ActivityPicker.target != null && ActivityPicker.target != ActivityPicker.NEW) io.github.jasondorjeshort.dystopo.app.tracking.ActivityPickerPanel { } // T172: a track's activity
            if (rows.isEmpty()) BasicText(emptyLine(filter), style = TextStyle(color = Ink, fontSize = 14.sp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key }) { r -> ItemRow(r) }
            }
            if (TracksUi.selecting) SelectBar(rows, all) // T272
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(r: ListRow) {
    val route = r.kind == "route"
    val menuOpen = TracksUi.menu == r.key
    val selecting = TracksUi.selecting
    val ticked = r.key in TracksUi.selected
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selecting) TickBox(ticked) // T272
            Column(
                Modifier.weight(1f)
                    .combinedClickable(
                        onClickLabel = if (selecting) (if (ticked) "Untick ${r.name}" else "Tick ${r.name}") else null,
                        onLongClickLabel = if (selecting) null else "Select ${r.name}",
                        onLongClick = { if (!selecting) TracksUi.select(true, r.key) }, // T272: a long press starts select mode
                    ) { if (selecting) TracksUi.toggle(r.key) else open(r) }
                    .padding(vertical = 2.dp),
            ) {
                BasicText(
                    r.name, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (route) Violet else Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(r.line, style = TextStyle(color = Ink, fontSize = 13.sp))
                r.counts?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
                if (!route) io.github.jasondorjeshort.dystopo.app.sync.SyncConflictNote(r.id) // T51
            }
            Eye(r)
            if (!selecting) io.github.jasondorjeshort.dystopo.app.routing.OverflowDots("$MORE_ACTIONS for ${r.name}") {
                TracksUi.menu = if (menuOpen) null else r.key
                TracksUi.renaming = null
                TracksUi.deleteAsk = null
            }
        }
        if (selecting) return@Column
        if (TracksUi.renaming == r.key) RenameLine(r)
        else if (TracksUi.deleteAsk == r.key) DeleteLine(r)
        else if (menuOpen) {
            FlowRow(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (a in menuOf(r.kind, r.recording)) Action(a, if (a == DELETE) Bad else Link) { menuAction(r, a) }
            }
        }
    }
}

/** T262: the eye and its word, one target of a thumb's size; "Hidden" in grey with the eye struck through. */
@Composable
private fun Eye(r: ListRow) {
    val colour = if (r.shown) Ink else Muted
    Column(
        Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)
            .clickable(onClickLabel = eyeAction(r.shown, r.name), role = Role.Switch) { setShown(r, !r.shown) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(24.dp, 16.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.8.dp.toPx())
            val eye = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h / 2)
                quadraticBezierTo(w / 2, -h * 0.35f, w, h / 2)
                quadraticBezierTo(w / 2, h * 1.35f, 0f, h / 2)
                close()
            }
            drawPath(eye, colour, style = stroke)
            if (r.shown) drawCircle(colour, radius = h * 0.24f, center = Offset(w / 2, h / 2))
            else {
                drawCircle(colour, radius = h * 0.22f, center = Offset(w / 2, h / 2), style = stroke)
                drawLine(colour, Offset(w * 0.12f, h * 0.95f), Offset(w * 0.88f, h * 0.05f), strokeWidth = 2.dp.toPx())
            }
        }
        BasicText(shownWord(r.shown), style = TextStyle(color = colour, fontSize = 12.sp, fontWeight = if (r.shown) FontWeight.Bold else FontWeight.Medium))
    }
}

@Composable
private fun RenameLine(r: ListRow) {
    var name by remember(r.key) { mutableStateOf(r.name) }
    Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
        }
        Action(SAVE) {
            val n = name.trim()
            TracksUi.renaming = null
            TracksUi.menu = null
            if (n.isNotEmpty() && n != r.name) rename(r, n)
        }
        Action(CANCEL) { TracksUi.renaming = null }
    }
}

@Composable
private fun DeleteLine(r: ListRow) {
    Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(deleteQuestion(r.kind, r.name), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End)) {
            Action(DELETE, Bad) {
                TracksUi.deleteAsk = null
                TracksUi.menu = null
                delete(r)
            }
            Action(CANCEL) { TracksUi.deleteAsk = null }
        }
    }
}

/** T262: a row's tap: its card on the map (a track's statistics, shown if it was hidden; a route's trip card). */
internal fun open(r: ListRow) {
    TracksUi.close()
    if (r.kind == "route") Trips.background { Trips.open(r.id) }
    else Thread { Tracking.of(DystopoApp.instance).showProfile(r.id) }.start()
}

/** T262: the eye's tap (and `POST /tracks/ui?eye=`). */
internal fun setShown(r: ListRow, shown: Boolean) {
    if (r.kind == "route") Trips.background { Trips.setShown(r.id, shown) }
    else Thread { Tracking.of(DystopoApp.instance).setShown(r.id, shown) }.start()
}

internal fun rename(r: ListRow, name: String) {
    if (r.kind == "route") Trips.background { Trips.rename(r.id, name) }
    else Thread { Tracking.of(DystopoApp.instance).rename(r.id, name) }.start()
}

internal fun delete(r: ListRow) {
    if (r.kind == "route") Trips.background { Trips.delete(r.id) }
    else Thread { Tracking.of(DystopoApp.instance).delete(r.id) }.start()
}

/** T262: a menu entry's tap (and `POST /tracks/ui?menu=<kind:id>&action=`): Rename and Delete open their line in the row. */
internal fun menuAction(r: ListRow, action: String) {
    val app = DystopoApp.instance
    when (action) {
        RENAME -> TracksUi.renaming = r.key
        DELETE -> TracksUi.deleteAsk = r.key
        OPEN, STATISTICS -> open(r)
        EXPORT_GPX -> {
            TracksUi.menu = null
            if (r.kind == "route") Trips.background {
                Trips.plan(r.id)?.let { p -> io.github.jasondorjeshort.dystopo.app.SaveFile.route(p) { line -> Tracking.readyOrNull?.refreshUi(line) } }
            } else io.github.jasondorjeshort.dystopo.app.SaveFile.track(r.id)
        }
        GO -> {
            TracksUi.close()
            if (r.kind == "route") Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startTrip(r.id) }.start()
            else Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startTrack(r.id) }.start()
        }
        ACTIVITY -> {
            TracksUi.menu = null
            val t = TrackingUiState.ui.tracks.firstOrNull { it.id == r.id }
            ActivityPicker.openForTrack(r.id, r.name, t?.activity)
        }
        REPORT -> {
            TracksUi.menu = null
            // T198: the trip report in the print dialog.
            io.github.jasondorjeshort.dystopo.app.print.SheetPrint.printReport(app, r.id) { why ->
                why?.let { msg -> Thread { Tracking.of(app).refreshUi("Report failed: $msg") }.start() }
            }
        }
        SHARE_GPX -> { // T272: the system's share sheet with the GPX file
            TracksUi.menu = null
            shareRows(listOf(r))
        }
        SHARE_LINK -> {
            TracksUi.menu = null
            Trips.background { runCatching { Trips.shareLink(r.id) }.onFailure { e -> Tracking.readyOrNull?.refreshUi("Share link failed: ${e.message}") } }
        }
    }
}

/** T272: Share GPX for [rows] (one from the row menu, or the ticked ones), off the main thread. */
internal fun shareRows(rows: List<ListRow>) {
    val say: (String) -> Unit = { line -> Tracking.readyOrNull?.refreshUi(line) }
    val keys = rows.map { it.kind to it.id }
    Thread({ io.github.jasondorjeshort.dystopo.app.ShareGpx.share(io.github.jasondorjeshort.dystopo.app.ShareGpx.items(keys), say) }, "dystopo-share").start()
}

/** T272: the folder picker's answer for the select bar's Export GPX (main thread): the files written there, off it. */
internal fun folderPicked(uri: Uri?) {
    val keys = TracksUi.exportKeys
    TracksUi.exportKeys = emptyList()
    if (uri == null || keys.isEmpty()) return
    val pairs = keys.mapNotNull { k -> k.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    Thread({
        val items = io.github.jasondorjeshort.dystopo.app.ShareGpx.items(pairs)
        io.github.jasondorjeshort.dystopo.app.ShareGpx.toFolder(uri, items) { line -> Tracking.readyOrNull?.refreshUi(line) }
    }, "dystopo-export").start()
}

/**
 * T272: a select-bar action on the ticked rows among [all] (main thread; `POST /tracks/ui?bulk=` too): Show and Hide set
 * each; Export GPX opens the folder picker; Share GPX the share sheet; Delete asks first ([TracksUi.bulkDeleteAsk]) unless
 * [confirmed], and leaves a recording track alone. Returns false when nothing is ticked.
 */
internal fun bulkAction(action: String, all: List<ListRow>, confirmed: Boolean = false): Boolean {
    val rows = all.filter { it.key in TracksUi.selected }
    if (rows.isEmpty()) return false
    when (action) {
        SHOW, HIDE -> {
            val on = action == SHOW
            val tracks = rows.filter { it.kind == "track" && it.shown != on }
            val routes = rows.filter { it.kind == "route" && it.shown != on }
            if (routes.isNotEmpty()) Trips.background { for (r in routes) Trips.setShown(r.id, on) }
            if (tracks.isNotEmpty()) Thread { val t = Tracking.of(DystopoApp.instance); for (r in tracks) t.setShown(r.id, on) }.start()
        }
        EXPORT_GPX -> {
            TracksUi.exportKeys = rows.map { it.key }
            TracksUi.pickFolder?.invoke() ?: return false
        }
        SHARE_GPX -> shareRows(rows)
        DELETE -> {
            if (!confirmed) { TracksUi.bulkDeleteAsk = true; return true }
            TracksUi.bulkDeleteAsk = false
            val gone = rows.filter { !it.recording }
            for (r in gone) delete(r)
            TracksUi.selected = TracksUi.selected - gone.map { it.key }.toSet()
        }
        else -> return false
    }
    return true
}

/** T272: the bar under the list in select mode: the count, All or None, the actions, Delete's question. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectBar(rows: List<ListRow>, all: List<ListRow>) {
    val ticked = all.filter { it.key in TracksUi.selected }
    val visible = rows.map { it.key }.toSet()
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Ink, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(if (ticked.isEmpty()) NOTHING_SELECTED else selectedLine(ticked.size), Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val allTicked = visible.isNotEmpty() && TracksUi.selected.containsAll(visible)
            Action(if (allTicked) SELECT_NONE else SELECT_ALL) { TracksUi.selected = if (allTicked) TracksUi.selected - visible else TracksUi.selected + visible; TracksUi.bulkDeleteAsk = false }
        }
        if (TracksUi.bulkDeleteAsk && ticked.isNotEmpty()) {
            val going = ticked.filter { !it.recording }
            BasicText(bulkDeleteQuestion(going.count { it.kind == "track" }, going.count { it.kind == "route" }), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End)) {
                Action(DELETE, Bad) { bulkAction(DELETE, all, confirmed = true) }
                Action(CANCEL) { TracksUi.bulkDeleteAsk = false }
            }
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (a in SELECT_ACTIONS) {
                    val enabled = ticked.isNotEmpty() && !(a == DELETE && ticked.all { it.recording })
                    Action(a, if (!enabled) Line else if (a == DELETE) Bad else Link) { if (enabled) bulkAction(a, all) }
                }
            }
        }
    }
}

/** T272: a row's tick box in select mode. */
@Composable
private fun TickBox(ticked: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        if (ticked) {
            drawRoundRect(Ink, cornerRadius = r)
            drawLine(Color.White, Offset(size.width * 0.22f, size.height * 0.52f), Offset(size.width * 0.42f, size.height * 0.72f), strokeWidth = 2.4.dp.toPx())
            drawLine(Color.White, Offset(size.width * 0.42f, size.height * 0.72f), Offset(size.width * 0.80f, size.height * 0.28f), strokeWidth = 2.4.dp.toPx())
        } else drawRoundRect(Muted, cornerRadius = r, style = Stroke(width = 1.8.dp.toPx()))
    }
}

/** T272: a sort chip, smaller than a filter chip, filled while chosen. */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (selected) Color.White else Ink, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
        modifier = Modifier.heightIn(min = 36.dp).background(if (selected) Muted else Color.White, RoundedCornerShape(14.dp)).border(1.dp, if (selected) Muted else Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.background(Color(0xEEFFFCF5), RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** T262: a filter chip, filled while chosen. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (selected) Color.White else Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
        modifier = Modifier.background(if (selected) Ink else Color.White, RoundedCornerShape(16.dp)).border(1.dp, if (selected) Ink else Line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** An action: a link's look with a thumb's height. */
@Composable
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}
