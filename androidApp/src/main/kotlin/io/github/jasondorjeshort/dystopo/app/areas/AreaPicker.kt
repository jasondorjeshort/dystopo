package io.github.jasondorjeshort.dystopo.app.areas

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineView
import io.github.jasondorjeshort.dystopo.app.online.RegionDownload
import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * T142 (ADR 0041 §5, the area picker's first pass): the three ways to choose an area in the Offline areas sheet, each
 * ending in the same size-first summary ([PickerCard]):
 * - **Choose a country** ([Mode.Countries], the list in [CountryListPane]; a row opens [Mode.Country]);
 * - **Draw an area** ([Mode.Draw], the editor in [AreaDraw]);
 * - **Keep offline around here** ([Mode.Around], from the long-press card and a chosen search result): presets 2, 5, 10,
 *   20 km or 1, 3, 5, 10 mi by the units setting (default 5 km or 3 mi), a typed value up to 20 km, the circle drawn live.
 *
 * The summary is the phone's own plan ([AreaDownload.plan]; the server is asked once, at Download): "About 170 MB (1,300
 * tiles) at full detail", the other detail as a choice ("Less detail (about 30 MB)" or "Full detail (about 170 MB)"), the
 * time line, and the refusal of a plan over the budget, with no Download then (T152: the heap no longer refuses an area; past
 * the tile cache index's comfortable size the plan says so in `GET /areas/draw` and Download proceeds). The choice of detail is
 * always offered (T142's decision for an area within the budget: every rectangle picker of the survey keeps its detail
 * control in view, whatever the size); a drawn area and a disc open at full detail, a country at less detail unless its
 * full detail fits the budget. Names: "Drawn area" or "Around here" with the date, the search result's or the country's
 * name; the name field renames it before Download, and the area card's Rename after.
 *
 * T153: **Download this region** ([Mode.Region], the online overlay's button and `POST /areas/region`): the visible box
 * as a rectangle, with no span cap and a detail chosen for the view ([regionDetail]: full from z10, T170 medium from z8, less below), the other
 * a link. At full detail a box within the server's build limits ([RegionDownload.packFits]) keeps the pack path (a server-
 * or phone-built pack with the trail graph and search, outside the pinned budget, sized at the pack's z8 to z14); any other
 * box, and less detail, takes the area path (tiles only, [AreaDownload] with T151's cursor and T156's chain); the summary
 * says which ([regionPathLine]).
 *
 * T157 (ADR 0041 §5's T157 note): three details, full, medium (elevation to z13 with vector to z14, [AreaDetail.MEDIUM]) and
 * less; the card shows the two not shown as choices with their sizes ([alternativeLines]) and, at medium detail,
 * [MEDIUM_DETAIL_HINT] under the size line. A country opens at the deepest detail that fits the budget; a region's medium
 * detail takes the area path (a pack holds one zoom range).
 *
 * Plans are made on one worker thread, newest request first (a stale one is skipped), and published to the UI thread.
 */
object AreaPicker {
    private const val TAG = "DystopoPicker"
    const val MIN_ZOOM = 8
    const val FULL_MAX_ZOOM = 15
    const val LESS_MAX_ZOOM = AreaDownload.LESS_DETAIL_MAX_ZOOM

    sealed class Mode(val key: String) {
        object Countries : Mode("countries")
        object Draw : Mode("draw")
        data class Around(val center: AreaPoint, val radiusM: Double) : Mode("around")
        data class Country(val code: String) : Mode("country")
        /** T153: "Download this region" for [box], the view's camera at [viewZoom]. */
        data class Region(val box: PackBounds, val viewZoom: Double) : Mode("region")
    }

    /** What the picker shows (null: the sheet's list of areas). Main thread writes. */
    var mode by mutableStateOf<Mode?>(null)
        private set
    /** The country list's filter text. */
    var filter by mutableStateOf("")
    /** The area's name (renamable before Download). */
    var name by mutableStateOf("")
    /** The detail the user chose (`full`, `medium`, `less`), null for the default ([defaultDetail]). */
    var detailChoice by mutableStateOf<String?>(null)
    /** The newest plan published. */
    var summary by mutableStateOf<Summary?>(null)
        private set
    /** Why the last Download did not start. */
    var downloadError by mutableStateOf<String?>(null)
        private set
    /** The around flow's typed value, as typed. */
    var typed by mutableStateOf("")

    /** Whether Cancel goes back to the sheet's list (opened from the sheet) or to the map (the long-press card, search). */
    private var backToSheet = true
    @Volatile private var revision = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val planner = Executors.newSingleThreadExecutor { r -> Thread(r, "area-picker-plan").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    /**
     * One plan of the shape in view: [full] (z8 to z15) and [less] (z8 to z13); [fullPending] while a country's full detail is
     * measured (T157: and its medium detail, while [medium] is null). T153: [pack] when [full] is a region's pack path (the
     * box at z8 to z14, within the server's build limits). T157: [medium] (elevation to z13, vector to z14).
     */
    class Summary(
        val revision: Long, val full: AreaDownload.Plan?, val less: AreaDownload.Plan?, val fullPending: Boolean, val error: String?, val pack: Boolean = false,
        val medium: AreaDownload.Plan? = null,
    ) {
        /** T157: the plan of [detail] (`full`, `medium`, `less`). */
        fun plan(detail: String): AreaDownload.Plan? = when (detail) {
            DETAIL_LESS -> less
            DETAIL_MEDIUM -> medium
            else -> full
        }
    }

    // ---- entering and leaving (main thread) ----

    fun openCountries() {
        leaveMode()
        backToSheet = true
        Areas.screenOpen = true
        mode = Mode.Countries
    }

    /** A country's summary, at [detail] or the default. */
    fun openCountry(code: String, detail: String?) {
        val c = Countries.byCode(code) ?: throw NoSuchElementException("no country '$code' (GET /areas/countries)")
        val fromList = mode == Mode.Countries
        leaveMode()
        backToSheet = true
        Areas.screenOpen = true
        name = c.name
        detailChoice = detail
        mode = Mode.Country(c.code)
        if (!fromList) filter = ""
        countryArea(c, LESS_MAX_ZOOM)?.let { PickerLayer.area(it) }
        thread(name = "picker-fit") { fit(countryBox(c.rings.map { AreaOfInterest.openRing(it) })) }
        replan()
    }

    /** Starts drawing [ring] (null: from the current view). False when the map is not laid out. */
    fun startDraw(ring: List<AreaPoint>?): Boolean {
        val start = ring ?: AreaDraw.fromView() ?: return false
        leaveMode()
        backToSheet = true
        Areas.screenOpen = true
        detailChoice = null
        name = defaultName(AreaDraw.area(start), java.time.LocalDate.now())
        mode = Mode.Draw
        AreaDraw.begin(start) // replans
        return true
    }

    /** "Keep offline around here" at ([lat], [lon]): from the long-press card ([placeName] null) or a search result (its name). */
    fun startAround(lat: Double, lon: Double, placeName: String?, fromSheet: Boolean = false, radiusM: Double? = null) {
        leaveMode()
        backToSheet = fromSheet
        Areas.screenOpen = true
        detailChoice = null
        val r = (radiusM ?: defaultRadius(AppSettings.units)).coerceIn(AreaOfInterest.MIN_BUFFER_M, MAX_RADIUS_M)
        val center = AreaPoint(lat, lon)
        name = placeName ?: defaultName(AreaOfInterest.around(center, r), java.time.LocalDate.now())
        typed = ""
        mode = Mode.Around(center, r)
        aroundChanged()
    }

    /** The around flow's radius (clamped to 20 km: "draw an area for more"). */
    fun setRadius(m: Double) {
        val a = mode as? Mode.Around ?: return
        mode = a.copy(radiusM = m.coerceIn(AreaOfInterest.MIN_BUFFER_M, MAX_RADIUS_M))
        aroundChanged()
    }

    private fun aroundChanged() {
        val a = mode as? Mode.Around ?: return
        val area = AreaOfInterest.around(a.center, a.radiusM)
        PickerLayer.area(area)
        thread(name = "picker-fit") { fit(area.bounds) }
        replan()
    }

    /**
     * T153: "Download this region" for [box] (the visible map) seen at [viewZoom]: the rectangle drawn, the camera fitted
     * above the card, the summary planned. [packName] is the generated name ([RegionDownload.defaultName], read off the UI
     * thread). Main thread.
     */
    fun openRegion(box: PackBounds, viewZoom: Double, packName: String) {
        leaveMode()
        backToSheet = false
        Areas.screenOpen = true
        detailChoice = null
        name = packName
        mode = Mode.Region(box, viewZoom)
        PickerLayer.area(regionArea(box, FULL_MAX_ZOOM, null))
        thread(name = "picker-fit") { fit(box) }
        replan()
    }

    /**
     * T153: [openRegion] from any thread but the UI's: [box] and [viewZoom] default to the camera's (the overlay's button
     * passes none, so the view is read as it is at the tap). Throws when the map is not laid out.
     */
    fun openRegionOffMain(box: PackBounds? = null, viewZoom: Double? = null) {
        val app = DystopoApp.instance
        val view = if (box == null || viewZoom == null) app.map.onMain { OnlineView.currentOnMain(app) } else null
        val b = box ?: view?.bounds ?: throw IllegalStateException("the map is not laid out")
        val z = viewZoom ?: view?.zoom ?: throw IllegalStateException("the map is not laid out")
        regionRing(b) // the box is checked before the card opens
        val n = OnlineService.get(app).region.defaultName()
        app.map.onMain { openRegion(b, z, n) }
    }

    /** Back: from a summary to the country list it came from, else out of the picker. */
    fun back() {
        if (mode is Mode.Country && backToSheet) { leaveMode(); mode = Mode.Countries; return }
        close()
    }

    /** Leaves the picker: to the sheet's list when it was opened from the sheet, else to the map. */
    fun close() {
        leaveMode()
        mode = null
        if (!backToSheet) Areas.screenOpen = false
        backToSheet = true
    }

    private fun leaveMode() {
        revision++
        AreaDraw.end()
        PickerLayer.clear()
        summary = null
        downloadError = null
    }

    /** True while a picker card stands over the map (the long-press card stays shut then). */
    fun onMap(): Boolean = Areas.screenOpen && mode.let { it != null && it != Mode.Countries }

    /** [AreaDraw]'s ring changed: redrawn, and planned again. */
    fun drawingChanged() {
        if (mode != Mode.Draw) return
        PickerLayer.drawing(AreaDraw.ring)
        replan()
    }

    // ---- the plan ----

    private fun replan() {
        val rev = ++revision
        val m = mode ?: return
        val ring = AreaDraw.ring
        val label = name.ifBlank { null }
        planner.execute {
            if (rev != revision) return@execute // a newer shape is waiting
            try {
                when (m) {
                    Mode.Draw -> AreaDownload.plan(AreaDraw.area(ring, label)).let { p -> publish(Summary(rev, p, p.lessDetail, false, null, medium = p.mediumDetail)) }
                    is Mode.Around -> AreaDownload.plan(AreaOfInterest.around(m.center, m.radiusM, MIN_ZOOM, FULL_MAX_ZOOM, label)).let { p -> publish(Summary(rev, p, p.lessDetail, false, null, medium = p.mediumDetail)) }
                    is Mode.Country -> {
                        val c = Countries.byCode(m.code)!!
                        val lessArea = countryArea(c, AreaDetail.LESS)
                        if (lessArea == null) { publish(Summary(rev, null, null, false, TOO_LARGE_ROW)); return@execute }
                        val less = AreaDownload.plan(lessArea, withLessDetail = false)
                        publish(Summary(rev, null, less, true, null))
                        if (rev != revision) return@execute
                        val medium = AreaDownload.plan(countryArea(c, AreaDetail.MEDIUM)!!, withLessDetail = false) // T157
                        publish(Summary(rev, null, less, true, null, medium = medium))
                        if (rev != revision) return@execute
                        val full = AreaDownload.plan(countryArea(c, AreaDetail.FULL)!!, withLessDetail = false)
                        publish(Summary(rev, full, less, false, null, medium = medium))
                    }
                    is Mode.Region -> {
                        // T153: within the server's build limits, full detail is the pack (sized at its z8 to z14); else the area path's z8 to z15.
                        if (RegionDownload.packFits(m.box)) {
                            val less = AreaDownload.plan(regionArea(m.box, AreaDetail.LESS, label), withLessDetail = false)
                            if (rev != revision) return@execute
                            val medium = AreaDownload.plan(regionArea(m.box, AreaDetail.MEDIUM, label), withLessDetail = false) // T157: tiles, the area path
                            if (rev != revision) return@execute
                            val pack = AreaDownload.plan(regionArea(m.box, RegionDownload.DEFAULT_MAX_ZOOM, label), withLessDetail = false)
                            publish(Summary(rev, pack, less, false, null, pack = true, medium = medium))
                        } else {
                            AreaDownload.plan(regionArea(m.box, FULL_MAX_ZOOM, label)).let { p -> publish(Summary(rev, p, p.lessDetail, false, null, medium = p.mediumDetail)) }
                        }
                    }
                    Mode.Countries -> Unit
                }
            } catch (e: RuntimeException) { // no source, or a shape the format refuses (a ring across the antimeridian)
                Log.w(TAG, "plan failed", e)
                publish(Summary(rev, null, null, false, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun publish(s: Summary) = main.post { if (s.revision == revision) summary = s }

    /**
     * The detail shown: the user's choice, else full for a drawn area or a disc, for a country full only when it fits, and
     * for a region the view's ([regionDetail]).
     */
    fun detailOf(s: Summary?): String = detailChoice ?: when (val m = mode) {
        is Mode.Region -> regionDetail(m.viewZoom)
        else -> defaultDetail(m is Mode.Country, s?.full?.let { it.refusal == null }, s?.medium?.let { it.refusal == null })
    }

    fun planOf(s: Summary?): AreaDownload.Plan? = s?.plan(detailOf(s))

    /** T153: true when the plan shown is a region's pack path (full detail within the server's build limits). */
    fun packPath(s: Summary?): Boolean = mode is Mode.Region && s?.pack == true && detailOf(s) == DETAIL_FULL

    /** T153: the refusal of the plan shown: the pinned budget's, except on the pack path (a pack is not pinned tiles). */
    fun refusalOf(s: Summary?): String? = if (packPath(s)) null else planOf(s)?.refusal

    /** T153: the size line of the plan shown; the pack path's z8 to z14 is this card's full detail. */
    fun sizeLineOf(s: Summary?): String? = planOf(s)?.let { p ->
        if (packPath(s)) sizeLine(p.bytes, p.tiles, p.area.minZoom, FULL_MAX_ZOOM) else sizeLine(p.bytes, p.tiles, p.area.minZoom, p.area.maxZoom, p.area.demMaxZoom)
    }

    /** T157: the line under the size line: what the medium detail keeps and drops; null at the other details. */
    fun hintOf(s: Summary?): String? = if (s != null && s.error == null && detailOf(s) == DETAIL_MEDIUM) MEDIUM_DETAIL_HINT else null

    /** T153: a region's path line ([regionPathLine]); null for the other flows. */
    fun pathLineOf(s: Summary?): String? = if (mode is Mode.Region && s != null && s.error == null) regionPathLine(s.pack, detailOf(s)) else null

    /** Starts the download of the plan shown (any thread but the UI's: it records the area). */
    fun download(): Map<String, Any?> {
        class Pick(val plan: AreaDownload.Plan?, val label: String, val mode: Mode?, val pack: Boolean, val refusal: String?)
        val app = DystopoApp.instance
        val k = app.map.onMain { summary.let { s -> Pick(planOf(s), name.trim(), mode, packPath(s), refusalOf(s)) } }
        val p = k.plan
        check(p != null) { "nothing planned yet" }
        k.refusal?.let { throw IllegalStateException(it) }
        return try {
            val region = k.mode as? Mode.Region
            if (region != null && k.pack) {
                // T153: the pack path, as the button did before: the server's (or the phone's) build, then the pack downloaded.
                val r = OnlineService.get(app).region
                val started = r.start(region.box, null, null, RegionDownload.packNameFrom(k.label, r.defaultName()))
                linkedMapOf("kind" to "box", "path" to PATH_PACK, "region" to started).also { app.map.onMain { close() } }
            } else {
                val n = k.label.ifEmpty { p.area.name ?: defaultName(p.area, java.time.LocalDate.now()) }
                val started = AreaDownload.start(p.area.withName(n), n)
                (if (region != null) linkedMapOf<String, Any?>("path" to PATH_AREA) + started else started).also { app.map.onMain { close(); Areas.screenOpen = true } }
            }
        } catch (e: RuntimeException) {
            main.post { downloadError = e.message }
            throw e
        }
    }

    /** The picker's state for `GET /areas/draw` (main thread). */
    fun json(): Map<String, Any?> {
        val s = summary
        val p = planOf(s)
        val m = mode
        return linkedMapOf(
            "mode" to m?.key, "sheetOpen" to Areas.screenOpen, "name" to name, "detail" to detailOf(s), "detailChosen" to detailChoice,
            "revision" to revision, "plannedRevision" to s?.revision, "planned" to (s != null && s.revision == revision && !s.fullPending),
            "error" to (downloadError ?: s?.error),
            "sizeLine" to sizeLineOf(s),
            "alternative" to s?.let { alternativeLine(detailOf(it), it) }, "timeLine" to p?.let { timeLine(it.bytes, it.bytesPerMs) },
            // T157: every detail not shown, as the card lists them (`detail`, `line`), and the medium detail's hint.
            "alternatives" to s?.let { alternativeLines(detailOf(it), it).map { (d, l) -> linkedMapOf("detail" to d, "line" to l) } }, "hint" to hintOf(s),
            "refusal" to refusalOf(s), "refused" to (refusalOf(s) != null), "canDownload" to (p != null && refusalOf(s) == null),
            "pastHeapLimit" to p?.pastHeapLimit, "heapTiles" to p?.heapTiles, // T152: said, never a refusal
            "bytes" to p?.bytes, "tiles" to p?.tiles, "zooms" to p?.let { listOf(it.area.minZoom, it.area.maxZoom) }, "demMaxZoom" to p?.area?.demMaxZoom,
            "id" to p?.area?.id, // T157: the id of the area Download would record (each detail's differs)
            "full" to s?.full?.json(), "medium" to s?.medium?.json(), "less" to s?.less?.json(), "layerFeatures" to PickerLayer.features,
            "drawing" to AreaDraw.json(),
            "around" to (m as? Mode.Around)?.let { a ->
                linkedMapOf(
                    "lat" to a.center.lat, "lon" to a.center.lon, "radiusM" to a.radiusM, "presetsM" to radiusPresets(AppSettings.units),
                    "presets" to radiusPresets(AppSettings.units).map { presetLabel(it, AppSettings.units) }, "sentence" to AROUND_SENTENCE,
                    "typed" to typed, "backToSheet" to backToSheet,
                )
            },
            "country" to (m as? Mode.Country)?.let { c -> Countries.byCode(c.code)?.let { linkedMapOf("code" to it.code, "name" to it.name, "nameLong" to it.nameLong, "toleranceM" to it.toleranceM) } },
            "filter" to filter,
            "region" to (m as? Mode.Region)?.let { r -> // T153
                linkedMapOf(
                    "bbox" to listOf(r.box.minLon, r.box.minLat, r.box.maxLon, r.box.maxLat), "viewZoom" to r.viewZoom,
                    "defaultDetail" to regionDetail(r.viewZoom), "packFits" to s?.pack, "path" to s?.let { if (packPath(it)) PATH_PACK else PATH_AREA },
                    "pathLine" to pathLineOf(s), "spanDeg" to listOf(r.box.maxLon - r.box.minLon, r.box.maxLat - r.box.minLat),
                    "packTiles" to RegionDownload.tileCount(r.box, RegionDownload.DEFAULT_MIN_ZOOM..RegionDownload.DEFAULT_MAX_ZOOM),
                    "limits" to linkedMapOf("maxSpanDeg" to RegionDownload.MAX_SPAN_DEG, "maxTiles" to RegionDownload.TILE_LIMIT),
                )
            },
        )
    }

    /** The camera on [b] in the band above the card. */
    private fun fit(b: PackBounds) {
        runCatching { io.github.jasondorjeshort.dystopo.app.packs.fitAbove(b, 1.0 - CARD_SHARE) }
    }

    /** The share of the screen the picker card may take (its height cap); the camera fits in the rest. */
    const val CARD_SHARE = 0.48

    /** "Up to 20 km around a point; draw an area for more" holds: the typed value stops there. */
    const val MAX_RADIUS_M = AreaOfInterest.MAX_BUFFER_M
}

/**
 * T153 (ADR 0041 §5, "a detail level chosen for the view"): a region's default detail from the view's zoom: full from
 * [REGION_FULL_FROM_ZOOM] (a city view: the pack path when the box fits the server's limits), less below it (a state
 * view, whose full detail runs to tens of gigabytes). T170 (T157's follow-up): medium from [REGION_MEDIUM_FROM_ZOOM] (a
 * county or a range of hills: every path and place for little more than less detail). Pure, tested.
 */
internal fun regionDetail(viewZoom: Double): String = when {
    viewZoom >= REGION_FULL_FROM_ZOOM -> DETAIL_FULL
    viewZoom >= REGION_MEDIUM_FROM_ZOOM -> DETAIL_MEDIUM
    else -> DETAIL_LESS
}

/** T153: the view zoom from which a region opens at full detail. */
internal const val REGION_FULL_FROM_ZOOM = 10.0
/** T170: the view zoom from which a region opens at medium detail (below [REGION_FULL_FROM_ZOOM]). */
internal const val REGION_MEDIUM_FROM_ZOOM = 8.0

/**
 * T153: the line under a region's size line saying which path the download takes: the pack ([REGION_PACK_LINE]) at full
 * detail when [packFits], else tiles only, with the reason ([REGION_TILES_LINE], [REGION_LESS_LINE]). Pure, tested.
 */
internal fun regionPathLine(packFits: Boolean, detail: String): String = when {
    detail != DETAIL_FULL -> REGION_LESS_LINE // T157: the medium detail is tiles too
    packFits -> REGION_PACK_LINE
    else -> REGION_TILES_LINE
}

/** T153: the rectangle of [box] as an area (a polygon, counter-clockwise) at z8 to [maxZoom]. */
internal fun regionArea(box: PackBounds, maxZoom: Int, name: String?): AreaOfInterest =
    AreaDraw.area(regionRing(box), name).let { it.withZooms(it.minZoom, maxZoom) }

/** T157: the rectangle of [box] as an area at [detail] (the medium detail's elevation cap included). */
internal fun regionArea(box: PackBounds, detail: AreaDetail, name: String?): AreaOfInterest = AreaDraw.area(regionRing(box), name).at(detail)

/** T153: [box]'s corners as a ring. Pure, tested. */
internal fun regionRing(box: PackBounds): List<AreaPoint> {
    require(box.maxLon > box.minLon && box.maxLat > box.minLat) { "the view's box is empty or crosses the 180th meridian" }
    return listOf(AreaPoint(box.minLat, box.minLon), AreaPoint(box.minLat, box.maxLon), AreaPoint(box.maxLat, box.maxLon), AreaPoint(box.maxLat, box.minLon))
}

/** T153: `bbox=west,south,east,north` as a box. Pure, tested. */
internal fun regionBox(raw: String): PackBounds {
    val v = raw.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("bbox must be west,south,east,north") }
    require(v.size == 4) { "bbox must be west,south,east,north" }
    return PackBounds(v[0], v[1], v[2], v[3])
}

/** T153: the two paths a region takes (`GET /areas/picker` `region.path`, the download's `path`). */
internal const val PATH_PACK = "pack"
internal const val PATH_AREA = "area"

/** T153: the card's title, the overlay's button. */
internal const val REGION_TITLE = "Download this region"
/** T153: the path lines (ADR 0031: the owner's to adjust). */
internal const val REGION_PACK_LINE = "With routing and place search (a map pack)"
internal const val REGION_TILES_LINE = "Tiles only: routing needs a smaller box"
internal const val REGION_LESS_LINE = "Tiles only: routing needs full detail"

/**
 * T142: the default detail: full for a drawn area or a disc; for a country full only when [fullFits], else (T157) medium when
 * [mediumFits], else less. Pure, tested.
 */
internal fun defaultDetail(country: Boolean, fullFits: Boolean?, mediumFits: Boolean? = null): String = when {
    !country || fullFits == true -> DETAIL_FULL
    mediumFits == true -> DETAIL_MEDIUM
    else -> DETAIL_LESS
}

/**
 * T142: the other detail as a choice under the size line: "Less detail (about 30 MB)" when full is shown, "Full detail
 * (about 170 MB)" when less is (just "Full detail" while it is measured). T157: the first of [alternativeLines]. Pure, tested.
 */
internal fun alternativeLine(shown: String, s: AreaPicker.Summary): String? = alternativeLines(shown, s).firstOrNull()?.second

/**
 * T157: the details not shown, as choices under the size line, deepest first after the shown one's neighbour: at full
 * detail "Medium detail (about 60 MB)" then "Less detail (about 30 MB)"; at medium "Full detail (...)" then "Less detail
 * (...)"; at less "Medium detail (...)" then "Full detail (...)". A detail being measured shows its title alone; one with
 * no plan coming is left out. (detail key, line) pairs. Pure, tested.
 */
internal fun alternativeLines(shown: String, s: AreaPicker.Summary): List<Pair<String, String>> {
    val order = when (shown) {
        DETAIL_FULL -> listOf(DETAIL_MEDIUM, DETAIL_LESS)
        DETAIL_MEDIUM -> listOf(DETAIL_FULL, DETAIL_LESS)
        else -> listOf(DETAIL_MEDIUM, DETAIL_FULL)
    }
    return order.mapNotNull { d ->
        val p = s.plan(d)
        val pending = p == null && s.fullPending && d != DETAIL_LESS
        if (p == null && !pending) null else d to detailChoiceLine(AreaDetail.byKey(d)!!, p?.bytes)
    }
}

/** T142: "Full detail (about 170 MB)", or "Full detail" without a size. Pure, tested. */
internal fun fullDetailLine(bytes: Long?): String = detailChoiceLine(AreaDetail.FULL, bytes)

/** T142 (ADR 0041 §5): the around flow's presets in metres: 2, 5, 10, 20 km, or 1, 3, 5, 10 mi. Pure, tested. */
internal fun radiusPresets(units: ContourUnits): List<Double> =
    if (units == ContourUnits.FEET) listOf(1.0, 3.0, 5.0, 10.0).map { it * MILE_M } else listOf(2_000.0, 5_000.0, 10_000.0, 20_000.0)

/** T142: 5 km, or 3 mi. Pure, tested. */
internal fun defaultRadius(units: ContourUnits): Double = if (units == ContourUnits.FEET) 3 * MILE_M else 5_000.0

/** T142: a preset's chip, "5 km" or "3 mi". Pure, tested. */
internal fun presetLabel(m: Double, units: ContourUnits): String =
    if (units == ContourUnits.FEET) "${Math.round(m / MILE_M)} mi" else "${Math.round(m / 1000)} km"

/** T142: a typed radius in kilometres or miles as metres; null when it is not a positive number. Pure, tested. */
internal fun typedRadius(text: String, units: ContourUnits): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let { it * if (units == ContourUnits.FEET) MILE_M else 1000.0 }

/** T142: the box to fit a country to: its own box, or the largest part's when the country lies across the antimeridian. */
internal fun countryBox(rings: List<List<AreaPoint>>): PackBounds {
    val parts = PolygonRings.outer(rings)
    val spans = parts.map { r -> r.minOf { it.lon } to r.maxOf { it.lon } }
    val all = PolygonRings.bounds(rings, 0.0)
    if (spans.none { it.first <= -179.9 } || spans.none { it.second >= 179.9 }) return PackBounds(all.minLon, all.minLat, all.maxLon, all.maxLat)
    val big = parts.maxByOrNull { PolygonRings.ringAreaM2(it) }!!
    return PolygonRings.bounds(listOf(big), 0.0).let { PackBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) }
}

private const val MILE_M = 1609.344

/** T142: the sheet's two picker buttons and the cards' titles. */
internal const val DRAW_AREA = "Draw an area"
internal const val AROUND_HERE = "Keep offline around here"
/** T142 (ADR 0041 §5): the around flow's sentence. */
internal const val AROUND_SENTENCE = "Up to 20 km around a point; draw an area for more"
/** T142: the drawing card's hint. */
internal const val DRAW_HINT = "Tap the map to add a corner. Drag a corner or a midpoint to move it; long-press a corner to remove it."
internal const val NAME_LABEL = "Name"
internal const val OTHER_RADIUS = "Other:"

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Link = Color(0xFF2C7BB6)
private val Teal = Color(0xFF00796B)
private val Red = Color(0xFFB0302A)
private val Line = Color(0xFFE2D8C6)

/**
 * T142: the picker's card at the bottom of the map (drawing, around a point, a country's summary): the flow's own controls,
 * the name, then the size-first summary and Download. Capped at [AreaPicker.CARD_SHARE] of the screen, scrolling past it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PickerCard() {
    val m = AreaPicker.mode ?: return
    val app = DystopoApp.instance
    LaunchedEffect(Unit) { app.map.mapView?.let { PickerLayer.bind(it) } }
    BackHandler { AreaPicker.back() }
    val maxH = (LocalConfiguration.current.screenHeightDp * AreaPicker.CARD_SHARE).dp
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxH)
                .background(Paper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val country = (m as? AreaPicker.Mode.Country)?.let { Countries.byCode(it.code) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = when (m) {
                    AreaPicker.Mode.Draw -> DRAW_AREA
                    is AreaPicker.Mode.Around -> AROUND_HERE
                    is AreaPicker.Mode.Region -> REGION_TITLE
                    else -> country?.name ?: CHOOSE_COUNTRY
                }
                BasicText(title, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                CardAction("Cancel", Muted) { AreaPicker.back() }
            }
            when (m) {
                AreaPicker.Mode.Draw -> {
                    BasicText(DRAW_HINT, style = TextStyle(color = Muted, fontSize = 12.sp))
                    CardAction("Undo", if (AreaDraw.canUndo) Link else Muted) { AreaDraw.undo() }
                }
                is AreaPicker.Mode.Around -> AroundControls(m)
                is AreaPicker.Mode.Country -> country?.nameLong?.takeIf { it != country.name }?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 12.sp)) }
                else -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(NAME_LABEL, style = TextStyle(color = Muted, fontSize = 13.sp))
                Box(Modifier.weight(1f).background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    BasicTextField(AreaPicker.name, { AreaPicker.name = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                }
            }
            SummaryLines()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AroundControls(a: AreaPicker.Mode.Around) {
    val units = AppSettings.units
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in radiusPresets(units)) {
            val on = kotlin.math.abs(p - a.radiusM) < 1 && AreaPicker.typed.isBlank()
            BasicText(
                presetLabel(p, units),
                style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(if (on) Teal else Color.Transparent, RoundedCornerShape(12.dp)).clickable { AreaPicker.typed = ""; AreaPicker.setRadius(p) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(OTHER_RADIUS, style = TextStyle(color = Muted, fontSize = 13.sp))
            Box(Modifier.width(56.dp).background(Color.White, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                BasicTextField(
                    AreaPicker.typed,
                    { t -> AreaPicker.typed = t; typedRadius(t, units)?.let { AreaPicker.setRadius(it) } },
                    singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            BasicText(if (units == ContourUnits.FEET) "mi" else "km", style = TextStyle(color = Muted, fontSize = 13.sp))
        }
    }
    BasicText(AROUND_SENTENCE, style = TextStyle(color = Muted, fontSize = 12.sp))
}

/** The size-first summary: the size line, the other detail as a choice, the time line, the refusal or Download. */
@Composable
private fun SummaryLines() {
    // T156: POST_NOTIFICATIONS (Android 13+) asked once, at the first Download (the download's notification); denied, the download still runs.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val s = AreaPicker.summary
    AreaPicker.downloadError?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 12.sp)) }
    if (s == null) { BasicText(MEASURING, style = TextStyle(color = Muted, fontSize = 13.sp)); return }
    s.error?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 13.sp)); return }
    val shown = AreaPicker.detailOf(s)
    val p = AreaPicker.planOf(s)
    if (p == null) BasicText(MEASURING, style = TextStyle(color = Muted, fontSize = 13.sp))
    else BasicText(AreaPicker.sizeLineOf(s) ?: "", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    AreaPicker.hintOf(s)?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 12.sp)) } // T157: what medium detail drops
    AreaPicker.pathLineOf(s)?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 13.sp)) } // T153: which path, and why
    for ((d, alt) in alternativeLines(shown, s)) CardAction(alt) { AreaPicker.detailChoice = d } // T157: the two other details
    if (p == null) return
    timeLine(p.bytes, p.bytesPerMs)?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 12.sp)) }
    val refusal = AreaPicker.refusalOf(s)
    refusal?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 12.sp)) }
    if (refusal == null) {
        BasicText(
            "Download",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.background(Teal, RoundedCornerShape(16.dp)).clickable {
                if (io.github.jasondorjeshort.dystopo.app.tracking.NotificationPrompt.shouldAsk(context)) notificationAsk.launch(io.github.jasondorjeshort.dystopo.app.tracking.NotificationPrompt.PERMISSION)
                thread(name = "picker-download") { runCatching { AreaPicker.download() } }
            }
                .padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun CardAction(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(text, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
}

