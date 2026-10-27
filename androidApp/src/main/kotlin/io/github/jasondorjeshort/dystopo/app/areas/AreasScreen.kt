package io.github.jasondorjeshort.dystopo.app.areas

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp
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
 * and its outline is the rings, filled; a record this build cannot read ([Areas.Record.opaque]) gets the needs-newer card
 * ([NeedsNewerCard]): the sentence, the raw reason under it, Show and Remove only.
 *
 * T151: an interrupted download (the app closed, the network stopped answering) is partial, so Retry shows at once, and
 * its card adds [resumeLine] ("Continues by itself", with the minutes during a backoff) while [ResumeCheck] will carry it on.
 * The coverage the sheet polls every 1.5 s is counted row by row ([Areas.coverage]).
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
    var coverage by remember { mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap()) }
    var progress by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var pinned by remember { mutableStateOf(0L) } // T142: the country rows' budget test (read here: the UI thread takes no lock)
    LaunchedEffect(Unit) {
        while (true) {
            val (c, p) = withContext(Dispatchers.IO) { Areas.all().associate { it.id to Areas.coverage(it) } to AreaDownload.json() }
            pinned = withContext(Dispatchers.IO) { runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).cache.pinnedBytes }.getOrDefault(0L) }
            coverage = c
            progress = p
            AreaOutlines.show(Areas.list)
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(1500) // T150: paused in the background
        }
    }
    // T142: drawing, around a point and a country's summary are a card over the map; the country list is this sheet.
    val picker = AreaPicker.mode
    if (picker != null && picker != AreaPicker.Mode.Countries) {
        PickerCard()
        return
    }
    val countries = picker == AreaPicker.Mode.Countries
    BackHandler { if (countries) AreaPicker.close() else Areas.screenOpen = false }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (countries) COUNTRY_SHEET else SHEET)
                .background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(if (countries) CHOOSE_COUNTRY else "Offline areas", Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    if (!countries) HelpIcon("areas.offline") // T109
                }
                if (countries) Action("Back") { AreaPicker.close() } else Action("Close") { Areas.screenOpen = false }
            }
            if (countries) { // T142
                CountryListPane(pinned)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    AREAS_INTRO,
                    Modifier.weight(1f, fill = false),
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
                HelpIcon("areas.corridor") // T109
            }
            // T142 (ADR 0041 §5): the picker's ways in from the sheet; "Keep offline around here" is on the long-press card and a search result.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerButton(CHOOSE_COUNTRY) { AreaPicker.openCountries() }
                PickerButton(DRAW_AREA) { AreaPicker.startDraw(null) }
            }
            CorridorWidthRow() // T130
            // T106: a refused job is finished too (it read "Downloading ...: 0 of 0 tiles" for as long as the sheet stayed open).
            progress?.takeIf { it["state"] !in setOf("done", "failed", "cancelled", "partial", "refused") }?.let { p ->
                BasicText(
                    "Downloading ${p["name"]}: ${p["done"]} of ${p["planned"]} tiles, ${mb((p["bytes"] as? Number)?.toLong() ?: 0)}",
                    style = TextStyle(color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
            val items = Areas.list
            if (items.isEmpty()) BasicText("No areas yet.", style = TextStyle(color = Ink, fontSize = 14.sp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in items) AreaRow(r, coverage[r.id])
            }
        }
    }
}

/** T142: a picker button of the sheet (outlined, so it reads as a way in rather than an area's action). */
@Composable
private fun PickerButton(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.border(1.5.dp, Teal, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun AreaRow(r: Areas.Record, cov: Map<String, Any?>?) {
    r.opaque?.let { NeedsNewerCard(r, it); return } // T140
    var renaming by remember(r.id) { mutableStateOf<String?>(null) } // T142: the card's Rename
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        renaming?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).border(1.dp, Color(0xFFE2D8C6), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    BasicTextField(t, { renaming = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
                }
                Action("Save") { val n = t.trim(); renaming = null; if (n.isNotEmpty()) thread { runCatching { rename(r.id, n) } } }
            }
        }
        if (renaming == null) Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(r.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // T91 (the T75 follow-up): a pack-backed area has no Pin or Unpin; the card says why.
            BasicText(if (r.pack != null) "KEPT AS A PACK" else if (r.pinned) "PINNED" else "NOT PINNED", style = TextStyle(color = if (r.pinned || r.pack != null) Teal else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        val kind = when {
            r.pack != null -> "Region pack ${r.pack}"
            // T140: a disc (the one-point corridor) by its radius, a polygon by its area on the ground.
            r.area.shape is AreaShape.Corridor && (r.area.shape as AreaShape.Corridor).points.size == 1 && r.tripId == null -> discLine((r.area.shape as AreaShape.Corridor).bufferM, AppSettings.units)
            r.area.shape is AreaShape.Corridor -> (r.area.shape as AreaShape.Corridor).bufferM.let { b -> corridorLine(r.area.lengthM, if (r.tripId != null) TripCorridor.widthOf(b).toDouble() else b, AppSettings.units, trip = r.tripId != null) } // T106: the units setting; T130: a trip's
            r.area.shape is AreaShape.Polygon -> polygonLine(PolygonRings.areaM2((r.area.shape as AreaShape.Polygon).rings), AppSettings.units)
            else -> "Box"
        }
        val share = (cov?.get("share") as? Number)?.toDouble()
        BasicText(
            "$kind · z${r.area.minZoom}-${r.area.maxZoom} · ${r.state}",
            style = TextStyle(color = Ink, fontSize = 13.sp),
        )
        BasicText(
            (if (r.pack != null) mb((cov?.get("packBytes") as? Number)?.toLong() ?: r.bytes) else "${mb(r.bytes)} downloaded (about ${mb(r.estimateBytes)} planned)") +
                (share?.let { String.format(Locale.US, " · %.0f%% on this phone", it * 100) } ?: ""),
            style = TextStyle(color = Muted, fontSize = 12.sp),
        )
        if (r.pack != null) BasicText(PACK_NOTE, style = TextStyle(color = Muted, fontSize = 12.sp))
        val older = !Areas.older(r).isNullOrEmpty() // T95
        // T106: from a source the phone no longer reads (its server changed) is older too, said as such.
        if (older) BasicText(if (Areas.fromOtherSource(r)) OTHER_SOURCE_NOTE else OLDER_NOTE, style = TextStyle(color = Color(0xFF9A5B00), fontSize = 12.sp, fontWeight = FontWeight.Bold))
        r.error?.let { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp), maxLines = 2) }
        // T151: an interrupted download continues by itself (the resume check); Retry stays for the impatient.
        resumeLine(r, System.currentTimeMillis())?.let { BasicText(it, style = TextStyle(color = Teal, fontSize = 12.sp)) }
        // T106 (a T91 follow-up): a failed, cut short or refused download offers Retry and Dismiss instead of staying listed as failed.
        val failed = r.pack == null && r.state != "downloading" && (r.error != null || r.state == "failed" || r.state == "partial")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Action("Show") { thread { val b = r.area.bounds; io.github.jasondorjeshort.dystopo.app.packs.fitAbove(PackBounds(b.minLon, b.minLat, b.maxLon, b.maxLat), 1.0 - SHEET) } }
            if (r.pack == null) Action(if (r.pinned) "Unpin" else "Pin") { thread { runCatching { TripCorridors.userPin(r.id, !r.pinned) } } } // T130: a trip's, also Stop keeping
            if (r.pack == null && r.state != "downloading") Action(if (failed) "Retry" else if (older) "Update" else "Download again") { thread { runCatching { AreaDownload.start(r.area, r.name, r.tripId) } } }
            if (failed) Action("Dismiss", Muted) { thread { runCatching { Areas.dismiss(r.id) } } }
            if (r.pack == null && renaming == null) Action("Rename") { renaming = r.name } // T142
            Action("Remove", Color(0xFFD73027)) { thread { runCatching { TripCorridors.userRemove(r.id) } } }
        }
    }
}

/**
 * T140 (ADR 0041 §2): the card of an area this build cannot read: [NeedsNewerApp.USER_SENTENCE] with the raw reason under
 * it; it stays pinned by its box (its tiles are kept), and only Show and Remove act on it.
 */
@Composable
private fun NeedsNewerCard(r: Areas.Record, o: Areas.Record.Opaque) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(r.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(if (r.pinned) "PINNED" else "NOT PINNED", style = TextStyle(color = if (r.pinned) Teal else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        BasicText(NeedsNewerApp.USER_SENTENCE, style = TextStyle(color = Color(0xFF9A5B00), fontSize = 13.sp, fontWeight = FontWeight.Bold))
        BasicText(o.reason, style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Action("Show") { thread { val b = r.area.bounds; io.github.jasondorjeshort.dystopo.app.packs.fitAbove(PackBounds(b.minLon, b.minLat, b.maxLon, b.maxLat), 1.0 - SHEET) } }
            Action("Remove", Color(0xFFD73027)) { thread { runCatching { TripCorridors.userRemove(r.id) } } }
        }
    }
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

private fun mb(bytes: Long): String = if (bytes >= 1_048_576) String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0) else String.format(Locale.US, "%d kB", bytes / 1024)

private const val SHEET = 0.55f

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
internal const val AREAS_INTRO = "Pinned areas stay on this phone until you unpin or remove them. Every trip keeps its corridor here."

/** T130: the width row's label, and a width chip's text ([TripCorridor.WIDTH_CHOICES_M]; 0 is Off) in the units setting. Pure, tested. */
internal const val CORRIDOR_WIDTH_LABEL = "Keep each trip offline:"
internal fun corridorWidthChip(m: Int, units: ContourUnits): String = if (m == 0) "Off" else UnitFormat.distance(m.toDouble(), units)

/** T136: applies settings the sheet changed as `POST /settings` does (validated, saved, synced). */
private fun settingsApply(params: Map<String, Any?>) {
    val parsed = io.github.jasondorjeshort.dystopo.app.SettingsRoutes.parse(params, AppSettings.units) as? io.github.jasondorjeshort.dystopo.app.SettingsRoutes.Parsed.Ok ?: return
    io.github.jasondorjeshort.dystopo.app.SettingsRoutes(DystopoApp.instance).apply(parsed)
}

/** T130: the trip corridor's width, each side of the route ([TripCorridors.applyWidth]; `POST /settings?tripCorridorM=`). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CorridorWidthRow() {
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
        HelpText("areas.offline", "Offline areas", "Offline areas (${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.DATA_AND_STORAGE}, or Packs)", show = show) {
            "Offline areas keep part of the map on this phone for use with no signal. A pinned area stays until you " +
                "unpin or remove it; an unpinned one is cleared first when space runs short. A region pack is never " +
                "cleared and needs no pin. Pinned areas share a budget of ${io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(AppSettings.pinnedCacheMb)}, set in " +
                "Storage; a download past it is refused."
        },
        HelpText("areas.corridor", "Corridors", "Offline areas, the line under the title", show = show) { units ->
            corridorHelp(AppSettings.tripCorridorM, units)
        },
    )
}

/**
 * T130: the corridors' help text: every trip keeps the map within the width each side (or, off, only the trips you keep by
 * hand), counted in the budget; Stop keeping or Unpin. In the units setting. Pure, tested.
 */
internal fun corridorHelp(widthM: Int, units: ContourUnits): String {
    val w = UnitFormat.distance((if (widthM > 0) widthM else TripCorridor.DEFAULT_WIDTH_M).toDouble(), units)
    val head = if (widthM > 0) "Every trip keeps the map within $w each side of its route offline; editing the trip updates it, deleting it frees it."
    else "Trips are not kept offline on their own; Keep offline on a trip keeps the map within $w each side."
    return "$head It counts in the offline areas' budget, and the trip says when it does not fit. Stop keeping, or Unpin here, lets it go."
}

/** T91: why a pack-backed area has no Pin or Unpin (also the 409 of `POST /areas/{id}/pin?on=0`). */
internal const val PACK_NOTE = "A region pack is never evicted, so it needs no pin; it stays until you remove it."

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

    fun show(records: List<Areas.Record>) {
        drawn = records.map { it.id }.toSet()
        val fs = ArrayList<Feature>()
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
        return linkedMapOf("features" to features, "corridors" to corridors, "polygons" to polygons)
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
