package io.github.jasondorjeshort.dystopo.app.areas

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
import io.github.jasondorjeshort.dystopo.areas.TripCorridor
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import kotlin.concurrent.thread

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
/** T136: opaque (it was 95%): the trip card under the sheet showed through it. */
private val Paper = Color(0xFFFFFCF5)
private val Link = Color(0xFF2C7BB6)
private val Teal = Color(0xFF00796B)

/**
 * T75: the areas on the device ("Offline areas", opened from the pack list or `POST /areas/ui?open=1`): each
 * with its kind, state, bytes on the device (and the estimate), coverage now, a pin toggle, Show (camera to it)
 * and Remove; the running corridor download's progress on top. While open, every area's outline is on the map
 * ([AreaOutlines]): a box as a rectangle, a corridor as a translucent band as wide as its buffer. Drawn over the
 * map by one line in MainActivity.
 *
 * T95: an area whose source has moved on to a newer version says [OLDER_NOTE] and offers Update (a download again
 * at the current version) instead of "Download again"; until then its tiles are served at the version they were
 * fetched at.
 *
 * T140 (ADR 0041): a polygon area's card says its size on the ground ([polygonLine]), a disc's its radius ([discLine]),
 * and its outline is the rings, filled; a record this build cannot read ([Areas.Record.opaque]) reads "Needs a newer app" with
 * the sentence and the raw reason behind its tap, Show on map and Remove only (T234).
 *
 * T151: an interrupted download (the app closed, the network stopped answering) is partial, so Retry shows at once, and
 * its card adds [resumeLine] ("Continues by itself", with the minutes during a backoff) while [ResumeCheck] will carry it on.
 * The coverage the sheet polls every 1.5 s is counted row by row ([Areas.coverage]).
 *
 * T221: a row keeps its name, kind, state and size; a tap on it opens its zooms and bounds ([areaDetailLines]). Remove leaves
 * the row where it is, reading "Removing" with no actions, until the tiles are gone.
 *
 * T234 (ADR 0056, B1a): the list is the one list of offline maps ([OfflineMapList]): every pack on the phone, however it
 * arrived, joined with the area records, the downloads and the removals, one row each ([MapRow]: a name, a state word, a
 * size; a tap opens the rest and the actions). `GET /areas/rows` reports it.
 *
 * T235 (ADR 0056 B1b): the list is drawn by the Offline maps screen (`packs/PacksScreen.kt`, full screen on opening) from the
 * parts here ([MapRow], [PickerButton], [CorridorWidthRow], [rowAction]); this overlay keeps the picker's card over the map and
 * the country list's sheet, both with T226's tap guard, and the outlines.
 */
@Composable
fun AreasOverlay() {
    AreasSheet() // T115: the help pop-up ([io.github.jasondorjeshort.dystopo.app.help.HelpHost]) moved to the end of MainActivity's MapScreen
}

@Composable
private fun AreasSheet() {
    val app = DystopoApp.instance
    val open = Areas.screenOpen
    LaunchedEffect(open) {
        app.map.mapView?.let { AreaOutlines.bind(it); PickerLayer.bind(it) }
        AreaOutlines.show(if (open) Areas.list else emptyList())
        if (!open && AreaPicker.mode != null) AreaPicker.close() // T142: closed from elsewhere (help, settings): the picker goes too
    }
    if (!open) return
    // T142: drawing, around a point and a country's summary are a card over the map; the country list is a sheet of its own.
    // T235 (ADR 0056 B1b): the list itself is the Offline maps screen's (`packs/PacksScreen.kt`), which `Areas.screenOpen` opens.
    val picker = AreaPicker.mode
    if (picker != null && picker != AreaPicker.Mode.Countries) {
        PickerCard()
        return
    }
    if (picker == AreaPicker.Mode.Countries) CountrySheet()
}

/** T142: the country list's sheet ([COUNTRY_SHEET] of the window), over the map; Back returns to the list it came from. */
@Composable
private fun CountrySheet() {
    val app = DystopoApp.instance
    var pinned by remember { mutableStateOf(0L) } // T142: the country rows' budget test (read here: the UI thread takes no lock)
    LaunchedEffect(Unit) {
        while (true) {
            pinned = withContext(Dispatchers.IO) { runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).cache.pinnedBytes }.getOrDefault(0L) }
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(1500) // T150: paused in the background
        }
    }
    BackHandler { AreaPicker.close() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(COUNTRY_SHEET)
                .background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .pointerInput(Unit) { detectTapGestures { } } // T235 (T226's guard): a tap on its paper stays out of the map and the controls beneath
                .navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(CHOOSE_COUNTRY, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Action("Back") { AreaPicker.close() }
            }
            CountryListPane(pinned)
        }
    }
}

/**
 * T235 (ADR 0056 B1b): a tap on a row's action ([MapRow]) on the Offline maps screen: Storage opens it, anything else runs on a
 * worker ([runRowAction]); [removing] marks a Remove while it runs, and [done] is called on the UI thread after.
 */
internal fun rowAction(row: OfflineMapList.Row, action: String, inputs: OfflineMapList.Inputs?, removing: (String, Boolean) -> Unit, done: () -> Unit) {
    val app = DystopoApp.instance
    if (action == OfflineMapList.STORAGE) {
        Areas.screenOpen = false
        io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = true
        return
    }
    if (action == OfflineMapList.REMOVE) removing(row.id, true)
    thread(name = "dystopo-offline-map-row") {
        val result = runCatching { runRowAction(row, action, inputs ?: OfflineMapList.Inputs(), app) }
        result.exceptionOrNull()?.let { android.util.Log.w(Areas.TAG, "${row.id}: $action failed", it) }
        app.map.onMain {
            if (action == OfflineMapList.REMOVE) removing(row.id, false)
            done()
        }
    }
}

/** T235: the outlines of the rows a record does not draw: a pack with no record, by its bounds (west, south, east, north). */
internal fun packBoxes(rows: List<OfflineMapList.Row>): Map<String, List<Double>> =
    rows.filter { it.pack != null && it.recordId == null && it.bounds?.size == 4 }.associate { it.id to it.bounds!! }

/** T235: the list's title help ([AREAS_HELP]'s first text, kept beside it); the Offline maps screen places it after its title. */
@Composable
internal fun OfflineMapsHelpIcon() {
    HelpIcon("areas.offline") // T109
}

/** T130, T235: the list's line under the title, with the corridors' help ([AREAS_HELP]'s second text). */
@Composable
internal fun IntroLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(AREAS_INTRO, Modifier.weight(1f, fill = false), style = TextStyle(color = Muted, fontSize = 12.sp))
        HelpIcon("areas.corridor") // T109
    }
}

/** T142: a picker button of the list (outlined, so it reads as a way in rather than a map's action). */
@Composable
internal fun PickerButton(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.border(1.5.dp, Teal, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * T234 (ADR 0056, B1a): one row of the one list ([OfflineMapList.Row]): its name, its state word and its size; a tap opens what
 * it holds, whether it counts in the budget, its shape, zooms and bounds, where it came from, and its actions (Show on map,
 * Show first, Update, Retry, Keep on this phone or Allow clearing, Rename, Remove ...). Rename edits here; [onAction] runs the
 * rest ([runRowAction]). Replaces T75's area card, T91's pack note on it and T140's needs-newer card (the state word says it).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MapRow(row: OfflineMapList.Row, open: Boolean, onAction: (String) -> Unit) {
    var renaming by remember(row.id) { mutableStateOf<String?>(null) } // T142: the card's Rename
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).clickable { Areas.toggleDetail(row.detailKey) }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        renaming?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).border(1.dp, Color(0xFFE2D8C6), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    BasicTextField(t, { renaming = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
                }
                val id = row.recordId
                Action("Save") { val n = t.trim(); renaming = null; if (n.isNotEmpty() && id != null) thread { runCatching { rename(id, n) } } }
            }
        }
        if (renaming == null) Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(row.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(row.state, style = TextStyle(color = toneColor(row.tone), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            BasicText(if (open) " ▾" else " ▸", style = TextStyle(color = Muted, fontSize = 13.sp)) // T221: the row opens
        }
        BasicText(OfflineMapList.sizeText(row.bytes), style = TextStyle(color = Muted, fontSize = 12.sp))
        if (open) {
            for (line in row.detail) BasicText(line, style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (a in row.actions) when (a) {
                    OfflineMapList.RENAME -> Action(a) { renaming = row.name }
                    OfflineMapList.REMOVE -> Action(a, Color(0xFFD73027)) { onAction(a) }
                    OfflineMapList.DISMISS -> Action(a, Muted) { onAction(a) }
                    else -> Action(a) { onAction(a) }
                }
            }
        }
    }
}

/** T234: a state word's colour by its tone. */
private fun toneColor(t: OfflineMapList.Tone): Color = when (t) {
    OfflineMapList.Tone.BUSY -> Teal
    OfflineMapList.Tone.BAD -> Color(0xFFB0302A)
    OfflineMapList.Tone.GOOD -> Teal
    OfflineMapList.Tone.PLAIN -> Muted
}

/**
 * T234: the inputs of the one list ([OfflineMapList.rows]), read off the UI thread by the sheet's 1.5 s poll and `GET /areas/rows`:
 * the packs ([PackManager.list], cached by size and time), the records, the downloads, the region job, the tile area job, the
 * catalog held ([io.github.jasondorjeshort.dystopo.app.packs.PackCatalogClient.rows], which fetches nothing), and the coverage of
 * the records with no pack only (a pack-backed record's is a find over the same list).
 */
internal fun offlineMapInputs(app: DystopoApp = DystopoApp.instance): OfflineMapList.Inputs {
    val manager = PackManager.get(app)
    val now = System.currentTimeMillis()
    val records = Areas.all()
    val packs = manager.list().map { l ->
        val i = l.info
        OfflineMapList.Pack(
            i.name, i.bytes, i.usable, i.dem != null, i.vector != null, i.bounds?.toList(), i.minZoom, i.maxZoom, i.attribution,
            listOfNotNull(l.files.dem, l.files.vector).maxOfOrNull { it.lastModified() } ?: 0L, l.errors,
        )
    }
    val service = io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app)
    return OfflineMapList.Inputs(
        packs = packs, records = records, removals = Areas.removals,
        coverage = records.filter { it.pack == null && it.opaque == null }.associate { it.id to (Areas.coverage(it)["share"] as? Number)?.toDouble() },
        jobs = manager.downloads.snapshot().mapNotNull { OfflineMapList.Job.of(it) },
        regionJob = runCatching { OfflineMapList.RegionJob.of(service.region.json(), now) }.getOrNull(),
        areaJob = AreaDownload.job?.let { j -> OfflineMapList.AreaJob(j.id, j.running, (j.json()["percent"] as? Number)?.toDouble(), j.isNew) },
        queued = TripCorridors.queued,
        catalog = runCatching { manager.catalogClient.rows() }.getOrDefault(emptyList()).associate { it.pack.name to it.status },
        older = records.mapNotNull { r -> if (Areas.older(r).isNullOrEmpty()) null else r.id to Areas.fromOtherSource(r) }.toMap(),
        active = manager.activeName(), units = AppSettings.units, now = now,
        indexing = !service.cache.ready, // T242
    )
}

/**
 * T234: [action] on [row] as its tap does, on the caller's thread (never the UI thread: the deletes take as long as they take):
 * the sheet's taps and `POST /areas/rows/action`. Remove follows [OfflineMapList.removePlan]; Storage and Rename are the sheet's
 * own (the route answers Storage by opening it, and Rename is `POST /areas/{id}/rename`).
 */
internal fun runRowAction(row: OfflineMapList.Row, action: String, inputs: OfflineMapList.Inputs, app: DystopoApp = DystopoApp.instance): Map<String, Any?> {
    require(action in row.actions) { "${row.id} offers ${row.actions}, not '$action'" }
    val manager = PackManager.get(app)
    val region = io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).region
    val p = row.pack
    fun record() = row.recordId?.let { Areas.get(it) } ?: throw NoSuchElementException("no area for ${row.id}")
    fun hasJob(name: String) = manager.downloads.job(name)?.state.let { it == "queued" || it == "running" || it == "paused" || it == "failed" }
    return when (action) {
        OfflineMapList.SHOW_ON_MAP -> {
            val b = row.bounds ?: throw IllegalStateException("${row.id} has no bounds")
            // T235 (ADR 0056): the list drops to its lower height, the map above it fitted to the row, and "Full list" goes back.
            app.map.onMain { io.github.jasondorjeshort.dystopo.app.packs.PacksUi.lowered = true }
            linkedMapOf("camera" to io.github.jasondorjeshort.dystopo.app.packs.fitAbove(PackBounds(b[0], b[1], b[2], b[3])))
        }
        OfflineMapList.SHOW_FIRST -> manager.activate(p ?: throw IllegalStateException("${row.id} is not a pack"), moveCamera = false)
        OfflineMapList.UPDATE -> if (p != null) manager.catalogClient.update(p).json() else record().let { r -> AreaDownload.start(r.area, r.name, r.tripId) }
        OfflineMapList.DOWNLOAD_AGAIN -> record().let { r -> AreaDownload.start(r.area, r.name, r.tripId) }
        OfflineMapList.RETRY, OfflineMapList.RESUME_NOW -> when {
            p == null -> record().let { r -> AreaDownload.start(r.area, r.name, r.tripId) }
            hasJob(p) -> manager.downloads.retry(p).json()
            else -> region.retry()
        }
        OfflineMapList.DISMISS -> linkedMapOf("area" to Areas.dismiss(record().id)?.json())
        OfflineMapList.KEEP, OfflineMapList.ALLOW_CLEARING -> TripCorridors.userPin(record().id, action == OfflineMapList.KEEP).json()
        OfflineMapList.STORAGE -> {
            app.map.onMain { Areas.screenOpen = false; io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = true }
            linkedMapOf("storage" to true)
        }
        OfflineMapList.REMOVE -> {
            val plan = OfflineMapList.removePlan(row, inputs) ?: throw IllegalStateException("${row.id} offers no Remove")
            val out = linkedMapOf<String, Any?>("plan" to plan.toString())
            plan.tripRemove?.let { out["area"] = TripCorridors.userRemove(it) }
            plan.cancelJob?.let { out["jobCancelled"] = manager.downloads.cancel(it) }
            if (plan.cancelRegion) out["region"] = region.cancel()
            out["records"] = plan.records.mapNotNull { id -> Areas.get(id)?.let { Areas.remove(it.id) } }
            plan.deletePack?.let { name -> if (manager.find(name) != null || hasJob(name)) out["pack"] = manager.delete(name) }
            plan.dismissRegion?.let { region.dismiss(it) }
            out
        }
        else -> throw IllegalArgumentException("'$action' is the sheet's own (Rename: POST /areas/{id}/rename)")
    }
}

/**
 * T221: a removing row's state: "Removing" until the tiles are counted (and for an area with none to count, a pack), then
 * "Removing: 1,200 of 4,000 tiles". Pure, tested.
 */
internal fun removingLine(done: Int, total: Int?): String = when {
    total == null || total == 0 -> "Removing"
    else -> String.format(Locale.US, "Removing: %,d of %,d %s", done, total, if (total == 1) "tile" else "tiles")
}

/**
 * T221: the rows in order: [records] as they are, each of the [removals] (out of the list by then) back where it was by the
 * time it was made, so a row that stays does not jump. Pure, tested.
 */
internal fun rowsInPlace(records: List<Areas.Record>, removals: List<Areas.Removal>): List<Any> {
    val rows = ArrayList<Any>(records)
    for (x in removals) {
        val at = rows.indexOfFirst { row -> (if (row is Areas.Removal) row.record.createdAt else (row as Areas.Record).createdAt) > x.record.createdAt }
        if (at < 0) rows += x else rows.add(at, x)
    }
    return rows
}

/**
 * T140 (ADR 0041 §4): a disc's card line, "Within 5 km of a point" or "Within 3 mi of a point" in the units setting: a
 * whole number of kilometres or miles (the presets) without decimals, else [UnitFormat.distance]. Pure, tested.
 */
internal fun discLine(radiusM: Double, units: ContourUnits): String {
    val (per, unit) = if (units == ContourUnits.FEET) 1609.344 to "mi" else 1000.0 to "km"
    val v = radiusM / per
    val text = if (v >= 1 && kotlin.math.abs(v - Math.round(v)) < 0.01) "${Math.round(v)} $unit" else UnitFormat.distance(radiusM, units)
    return "Within $text of a point"
}

/** T140: a polygon area's card line, "Area of 12.7 km²" or "Area of 4.9 sq mi" in the units setting. Pure, tested. */
internal fun polygonLine(areaM2: Double, units: ContourUnits): String {
    val (v, unit) = if (units == ContourUnits.FEET) areaM2 / 2_589_988.110336 to "sq mi" else areaM2 / 1e6 to "km²"
    val n = if (v >= 100) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%.1f", v)
    return "Area of $n $unit"
}

/** T142: the country list's sheet (taller: the map above it shows nothing the list needs). */
private const val COUNTRY_SHEET = 0.8f

/** T142 (ADR 0041 §5, "renamable"): an area's new name, in its record and its area (the id stays: it is the geometry's). */
fun rename(id: String, name: String): Areas.Record {
    val n = name.trim()
    require(n.isNotEmpty()) { "a name is needed" }
    return Areas.update(Areas.get(id)?.id ?: throw NoSuchElementException("no area '$id'")) { it.copy(name = n, area = it.area.withName(n)) }!!
}

/** T95: an area whose source serves a newer version than its tiles were fetched at. */
internal const val OLDER_NOTE = "Older than the source. Update downloads the newer data."

/** T106: an area fetched from another source than the one set now (the map does not read its tiles any more). */
internal const val OTHER_SOURCE_NOTE = "Downloaded from another source. Update downloads it from the one set now."

/**
 * T106 (a T95 follow-up): the area card's corridor line in the units setting, "Corridor 12.4 km, 500 m each side" or
 * "Corridor 7.7 mi, 1640 ft each side" ([UnitFormat.distance]; it was metric whatever the setting). Pure, tested.
 */
internal fun corridorLine(lengthM: Double, bufferM: Double, units: ContourUnits, trip: Boolean = false): String =
    (if (trip) "Trip corridor " else "Corridor ") + "${UnitFormat.distance(lengthM, units)}, ${UnitFormat.distance(bufferM, units)} each side"

/** T130: the sheet's line under the title. */
internal const val AREAS_INTRO = "Saved maps stay on this phone until you remove them. Every trip keeps the map along its route here."

/** T130: the width row's label, and a width chip's text ([TripCorridor.WIDTH_CHOICES_M]; 0 is Off) in the units setting. Pure, tested. */
internal const val CORRIDOR_WIDTH_LABEL = "Download each trip's map:"
internal fun corridorWidthChip(m: Int, units: ContourUnits): String = if (m == 0) "Off" else UnitFormat.distance(m.toDouble(), units)

/** T136: applies settings the sheet changed as `POST /settings` does (validated, saved, synced). */
private fun settingsApply(params: Map<String, Any?>) {
    val parsed = io.github.jasondorjeshort.dystopo.app.SettingsRoutes.parse(params, AppSettings.units) as? io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Ok ?: return
    io.github.jasondorjeshort.dystopo.app.SettingsRoutes(DystopoApp.instance).apply(parsed)
}

/** T130: the trip corridor's width, each side of the route ([TripCorridors.applyWidth]; `POST /settings?tripCorridorM=`). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CorridorWidthRow() {
    var width by remember(io.github.jasondorjeshort.dystopo.app.SettingsUi.revision) { mutableStateOf(AppSettings.tripCorridorM) } // T136: a synced width shows
    val units = AppSettings.units
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(CORRIDOR_WIDTH_LABEL, Modifier.padding(vertical = 4.dp), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        for (m in (TripCorridor.WIDTH_CHOICES_M + width).distinct().sorted()) {
            val on = m == width
            BasicText(
                corridorWidthChip(m, units),
                style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(if (on) Teal else Color.Transparent, RoundedCornerShape(12.dp)).clickable {
                    width = m
                    thread { settingsApply(mapOf("tripCorridorM" to m)) } // T136: through the settings, so it syncs
                }.padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        BasicText(if (width == 0) "" else "each side", Modifier.padding(vertical = 4.dp), style = TextStyle(color = Muted, fontSize = 12.sp))
    }
}

/**
 * T109: the help pop-ups of the sheet's header and its first line, by the key each [HelpIcon] names; a control removed
 * here takes its text with it. `POST /help/open` opens the sheet. The budget and the corridor width are read when the
 * pop-up opens: the pinned budget as set, the corridor in the units setting ([UnitFormat.distance]).
 */
internal val AREAS_HELP: List<HelpText> = run {
    val show = { Areas.screenOpen = true }
    listOf(
        HelpText("areas.offline", io.github.jasondorjeshort.dystopo.app.firstrun.Screens.OFFLINE_MAPS, "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.OFFLINE_MAPS} (${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.DATA_AND_STORAGE}, or the chip on the map)", show = show) {
            "Offline maps keep the map on this phone for use with no signal. A map from your server or a file, or one downloaded " +
                "with routing, stays until you remove it. A drawn area, a country or a trip's map counts in a budget of " +
                "${io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(AppSettings.pinnedCacheMb)}, set in ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.STORAGE}; one that reads May be cleared goes first when space runs short."
        },
        HelpText("areas.corridor", "Corridors", "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.OFFLINE_MAPS}, the line under the title", show = show) { units ->
            corridorHelp(AppSettings.tripCorridorM, units)
        },
    )
}

/**
 * T130: the corridors' help text: every trip keeps the map within the width each side (or, off, only the trips you keep by
 * hand), counted in the budget; Remove lets it go. In the units setting. Pure, tested. T235: in ADR 0056's words, naming no
 * button of the trip card (B2 renames them).
 */
internal fun corridorHelp(widthM: Int, units: ContourUnits): String {
    val w = UnitFormat.distance((if (widthM > 0) widthM else TripCorridor.DEFAULT_WIDTH_M).toDouble(), units)
    val head = if (widthM > 0) "Every trip downloads the map within $w each side of its route; editing the trip updates it, deleting it frees it."
    else "Trips do not download their map on their own; a trip's card can download the map within $w each side."
    return "$head It counts in the offline maps' budget, and the trip says when it does not fit. Remove it on the trip's card or here to let it go."
}

/** T91: why a pack-backed area has no Pin or Unpin (also the 409 of `POST /areas/{id}/pin?on=0`). T235: in ADR 0056's words. */
internal const val PACK_NOTE = "This offline map is never cleared, so it has no Keep on this phone; it stays until you remove it."

@Composable
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(text, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
}

/**
 * The areas' outlines on the map while the Areas sheet is open: a box as an outlined rectangle; a corridor as its
 * centre line under a translucent band whose width follows the zoom so it is as wide as the buffer on the ground.
 * Survives style reloads (re-added when a style finishes loading).
 */
object AreaOutlines {
    private const val SOURCE = "dystopo-areas"
    private const val FILL = "dystopo-areas-fill"
    private const val BAND = "dystopo-areas-band"
    private const val LINE = "dystopo-areas-line"
    private const val COLOR = "#00796b"
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }
    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var features = 0
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    /** T136: the ids drawn now (empty while the sheet is closed). */
    @Volatile private var drawn: Set<String> = emptySet()

    /** T235: the boxes of packs with no record drawn now ([packBoxes]: row id to west, south, east, north). */
    @Volatile var boxes = 0
        private set

    fun show(records: List<Areas.Record>, packBoxes: Map<String, List<Double>> = emptyMap()) {
        drawn = records.map { it.id }.toSet()
        val fs = ArrayList<Feature>()
        for ((id, b) in packBoxes) { // T235 (ADR 0056): a pack with no record is outlined by its bounds, as a box area is
            val ring = listOf(
                Point.fromLngLat(b[0], b[1]), Point.fromLngLat(b[2], b[1]), Point.fromLngLat(b[2], b[3]),
                Point.fromLngLat(b[0], b[3]), Point.fromLngLat(b[0], b[1]),
            )
            fs += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty("kind", "box"); addStringProperty("id", id) }
            fs += Feature.fromGeometry(LineString.fromLngLats(ring)).apply { addStringProperty("kind", "line"); addStringProperty("id", id) }
        }
        boxes = packBoxes.size
        for (r in records) {
            when (val s = r.area.shape) {
                is AreaShape.Box -> {
                    val ring = listOf(
                        Point.fromLngLat(s.west, s.south), Point.fromLngLat(s.east, s.south), Point.fromLngLat(s.east, s.north),
                        Point.fromLngLat(s.west, s.north), Point.fromLngLat(s.west, s.south),
                    )
                    fs += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty("kind", "box"); addStringProperty("id", r.id) }
                    fs += Feature.fromGeometry(LineString.fromLngLats(ring)).apply { addStringProperty("kind", "line"); addStringProperty("id", r.id) }
                }
                is AreaShape.Corridor -> {
                    val pts = s.points.map { Point.fromLngLat(it.lon, it.lat) }.let { if (it.size == 1) listOf(it[0], it[0]) else it }
                    // T106: the band is the corridor's outline filled once (a line as wide as the buffer folded into wedges at bends).
                    for (p in outline(r.id, s)) {
                        val rings = (listOf(p.outer) + p.holes).map { ring -> ring.map { Point.fromLngLat(it.lon, it.lat) } }
                        fs += Feature.fromGeometry(Polygon.fromLngLats(rings)).apply { addStringProperty("kind", "band"); addStringProperty("id", r.id) }
                    }
                    fs += Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("kind", "line"); addStringProperty("id", r.id) }
                }
                is AreaShape.Polygon -> { // T140: each part with its holes, filled once, and every ring outlined
                    for ((outer, holes) in polygonParts(s.rings)) {
                        val rings = (listOf(outer) + holes).map { ring -> (ring + ring.first()).map { Point.fromLngLat(it.lon, it.lat) } }
                        fs += Feature.fromGeometry(Polygon.fromLngLats(rings)).apply { addStringProperty("kind", "box"); addStringProperty("id", r.id) }
                        for (ring in rings) fs += Feature.fromGeometry(LineString.fromLngLats(ring)).apply { addStringProperty("kind", "line"); addStringProperty("id", r.id) }
                    }
                }
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        features = fs.size
        main.post { apply() }
    }

    /** T106: each corridor's outline polygons, made once per area and shape (the sheet redraws every 1.5 s). */
    private val outlines = HashMap<String, Pair<AreaShape.Corridor, List<CorridorOutline.Polygon>>>()

    @Synchronized
    private fun outline(id: String, s: AreaShape.Corridor): List<CorridorOutline.Polygon> {
        outlines[id]?.takeIf { it.first == s }?.let { return it.second }
        return CorridorOutline.polygons(s.points, s.bufferM).also { outlines[id] = s to it }
    }

    /**
     * T106: the corridors' outline polygons, for `GET /areas` checks of the outline (`outline` there). T136: every corridor
     * the app keeps ([records], made here when the sheet has not drawn it yet), with `drawn` (on the map now, while the
     * sheet is open) and its `tripId`; it listed only the ones the sheet had drawn, so nothing while it stayed closed.
     */
    fun json(records: List<Areas.Record>): Map<String, Any?> {
        val shown = drawn
        val corridors = records.mapNotNull { r ->
            val s = r.area.shape as? AreaShape.Corridor ?: return@mapNotNull null
            if (r.pack != null) return@mapNotNull null
            val polys = outline(r.id, s)
            linkedMapOf(
                "id" to r.id, "tripId" to r.tripId, "drawn" to (r.id in shown), "polygons" to polys.size,
                "holes" to polys.sumOf { it.holes.size }, "points" to polys.sumOf { p -> p.outer.size + p.holes.sumOf { it.size } },
            )
        }
        synchronized(this) { outlines.keys.retainAll(records.map { it.id }.toSet()) }
        // T140: the polygon areas' outlines (each part with its holes, as drawn).
        val polygons = records.mapNotNull { r ->
            val s = r.area.shape as? AreaShape.Polygon ?: return@mapNotNull null
            val parts = polygonParts(s.rings)
            linkedMapOf("id" to r.id, "drawn" to (r.id in shown), "parts" to parts.size, "holes" to parts.sumOf { it.second.size }, "points" to s.rings.sumOf { it.size })
        }
        return linkedMapOf("features" to features, "packBoxes" to boxes, "corridors" to corridors, "polygons" to polygons)
    }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val kind = Expression.get("kind")
            style.addLayer(
                FillLayer(FILL, SOURCE).withFilter(Expression.eq(kind, Expression.literal("box")))
                    .withProperties(PropertyFactory.fillColor(COLOR), PropertyFactory.fillOpacity(0.10f)),
            )
            // T106: the corridor's outline polygons ([CorridorOutline]), filled once.
            style.addLayer(
                FillLayer(BAND, SOURCE).withFilter(Expression.eq(kind, Expression.literal("band")))
                    .withProperties(PropertyFactory.fillColor(COLOR), PropertyFactory.fillOpacity(0.18f)),
            )
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(Expression.eq(kind, Expression.literal("line"))).withProperties(
                    PropertyFactory.lineColor(COLOR), PropertyFactory.lineWidth(2f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                ),
            )
        }
        apply()
    }

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
        }
    }
}

/**
 * T140: [rings] (even-odd, never overlapping) as the polygons a map draws: each outer ring with the holes directly in it
 * (a ring held by an odd number of others is a hole of the outer ring one level up that holds it). Pure, tested.
 */
internal fun polygonParts(rings: List<List<AreaPoint>>): List<Pair<List<AreaPoint>, List<List<AreaPoint>>>> {
    val depth = PolygonRings.depths(rings)
    val outers = rings.indices.filter { depth[it] % 2 == 0 }
    val holes = outers.associateWith { ArrayList<List<AreaPoint>>() }
    for (i in rings.indices) {
        if (depth[i] % 2 == 0) continue
        val p = rings[i].first()
        outers.firstOrNull { depth[it] == depth[i] - 1 && PolygonRings.contains(rings[it], p.lat, p.lon) }?.let { holes.getValue(it) += rings[i] }
    }
    return outers.map { rings[it] to holes.getValue(it).toList() }
}

/**
 * T170 (T157's follow-up; ADR 0031, the owner's words to adjust): an area card's zooms, "z8-15", and for an area whose
 * elevation stops below its deepest zoom (the medium detail) "z8-14, elevation to z13". Pure, tested.
 */
internal fun zoomText(area: io.github.jasondorjeshort.dystopo.areas.AreaOfInterest): String =
    "z${area.minZoom}-${area.maxZoom}" + (area.demMaxZoom?.let { ", elevation to z$it" } ?: "")

/**
 * T221: what a tap on an area's row opens: "Zooms z8-14, elevation to z13" ([zoomText]) and "Bounds 46.70, -121.90 to 46.95,
 * -121.50" (south-west to north-east, latitude first, as the catalog's rows print a box). Pure, tested.
 */
internal fun areaDetailLines(area: io.github.jasondorjeshort.dystopo.areas.AreaOfInterest): List<String> = area.bounds.let { b ->
    listOf("Zooms ${zoomText(area)}", String.format(Locale.US, "Bounds %.2f, %.2f to %.2f, %.2f", b.minLat, b.minLon, b.maxLat, b.maxLon))
}
