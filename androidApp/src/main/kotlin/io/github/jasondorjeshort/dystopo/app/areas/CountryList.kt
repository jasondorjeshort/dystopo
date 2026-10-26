package io.github.jasondorjeshort.dystopo.app.areas

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.areas.AreaLimit
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.boundaries.Country
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T142 ("Choose a country" in the Offline areas sheet; the owner's answer of 2026-10-25, ADR 0041 §5): the bundled
 * countries (T141's [Countries], Natural Earth) as a list grouped by continent, then alphabetical, with a filter box over
 * [Countries.find]. Each row is the country's `NAME`, its size at the row's detail and a downloaded mark. A country is
 * written as a polygon area of its rings with T141's simplification tolerance as `bufferM` (ADR 0041's writer rule), at
 * less detail (z8 to z13) unless its full-detail size (z8 to z15) fits the pinned budget: a departure from the catalogue
 * apps of the picker survey, which ship one fixed detail, because these tiles are pyramids eight times larger at z15.
 *
 * A row over the budget stays, greyed, with ADR 0041 §5's budget sentence; a row the heap's tile limit refuses even at
 * less detail ([AreaLimit], and Antarctica, which no area can hold) stays, greyed, with [TOO_LARGE_ROW]. Sizes are
 * measured on the phone, off the UI thread ([CountrySizes]), over the same cover and estimate as the download's manifest.
 * No sovereignty text anywhere: the list shows `NAME` and Natural Earth's continent, nothing else.
 */
object CountrySizes {
    private const val TAG = "DystopoCountries"

    /** One country's sizes: at less detail, and at full detail when the less-detail size fits the budget (else not measured). */
    class Entry(val less: CountryMeasure, val full: CountryMeasure?, val lessId: String?, val fullId: String?)

    /** Measured countries by code (a snapshot the worker replaces; the UI only reads it). */
    @Volatile var entries: Map<String, Entry> = emptyMap()
        private set

    /** Bumped on the main thread when [entries] changes, so the list recomposes. */
    var revision by mutableStateOf(0)
        private set

    /** The codes to measure, in order (the rows shown, or a route's); the worker idles when all are measured. */
    @Volatile private var want: List<String> = emptyList()
    /** Codes a route asked for (`GET /areas/countries?wait=`), measured after the rows shown. */
    @Volatile private var extra: List<String> = emptyList()
    /** The DEM zooms' first zoom the entries were measured with (a server from z6, the public sources from z8). */
    @Volatile private var measuredFor: Int? = null
    private val lock = Object()
    private var worker: Thread? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Measures [codes] (first to last) in the background; replaces the previous request. */
    fun request(codes: List<String>) {
        want = codes
        synchronized(lock) {
            if (worker == null) worker = Thread({ loop() }, "country-sizes").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
            lock.notifyAll()
        }
    }

    /** Measures [codes] after the rows shown (a debug route's), keeping the list's own request. */
    fun requestExtra(codes: List<String>) {
        extra = codes
        request(want)
    }

    /** Waits up to [ms] until every code of [codes] is measured; true when they are. */
    fun await(codes: List<String>, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            val e = entries
            if (codes.all { it in e }) return true
            if (System.currentTimeMillis() >= until) return false
            Thread.sleep(50)
        }
    }

    private fun server(): String? = runCatching { OnlineService.get(DystopoApp.instance).fetcher?.server }.getOrNull()

    private fun loop() {
        while (true) {
            val server = server()
            val demFirst = AreaDownload.zooms(countryLessProbe, server).first.first
            if (measuredFor != demFirst) { entries = emptyMap(); measuredFor = demFirst }
            val next = want.firstOrNull { it !in entries } ?: extra.firstOrNull { it !in entries }
            if (next == null) {
                synchronized(lock) { if (want.all { it in entries } && extra.all { it in entries }) lock.wait(5_000) }
                continue
            }
            val c = Countries.byCode(next)
            val e = try {
                c?.let { measure(it, server) }
            } catch (t: Throwable) { // never stop the worker: an error leaves the country too large to show a size for
                Log.w(TAG, "measuring $next failed", t)
                Entry(CountryMeasure(0, 0, true), null, null, null)
            } ?: Entry(CountryMeasure(0, 0, true), null, null, null)
            entries = entries + (next to e)
            main.post { revision++ }
        }
    }

    private fun measure(c: Country, server: String?): Entry {
        val heap = AreaDownload.heapTiles()
        val lessArea = countryArea(c, AreaPicker.LESS_MAX_ZOOM) ?: return Entry(CountryMeasure(0, 0, true), null, null, null)
        val (dem, osm) = AreaDownload.zooms(lessArea, server)
        val less = measureArea(lessArea, dem, osm, heap)
        val pinned = runCatching { OnlineService.get(DystopoApp.instance).cache.pinnedBytes }.getOrDefault(0L)
        val fullArea = countryArea(c, AreaPicker.FULL_MAX_ZOOM)!!
        // Full detail only when it might fit the budget (it costs eight times the tiles): [fullAtLeast] first.
        val full = if (!less.tooLarge && areaBudgetError(pinned, fullAtLeast(less), 0, AppSettings.pinnedCacheMb) == null) {
            AreaDownload.zooms(fullArea, server).let { (d, o) -> measureArea(fullArea, d, o, heap) }
        } else null
        return Entry(less, full, lessArea.id, fullArea.id)
    }

    /** A small polygon only for [AreaDownload.zooms]' DEM range (it depends on the server, not the shape). */
    private val countryLessProbe = AreaOfInterest(
        AreaShape.Polygon(listOf(listOf(io.github.jasondorjeshort.dystopo.areas.AreaPoint(0.0, 0.0), io.github.jasondorjeshort.dystopo.areas.AreaPoint(0.0, 0.1), io.github.jasondorjeshort.dystopo.areas.AreaPoint(0.1, 0.1)))),
        AreaPicker.MIN_ZOOM, AreaPicker.LESS_MAX_ZOOM,
    )

    /** Forgets every size (a test, or the budget's change of mind about full detail). */
    fun clear() {
        entries = emptyMap()
        synchronized(lock) { lock.notifyAll() }
    }
}

/**
 * T142: a country's tiles and estimated bytes at one detail, or [tooLarge] when the heap's tile limit refuses it;
 * [topTiles] is the cover's count at the deepest zoom.
 */
class CountryMeasure(val bytes: Long, val tiles: Int, val tooLarge: Boolean, val topTiles: Int = 0)

/**
 * T142: a floor of the full-detail bytes from the less-detail measure [less]: every z13 tile has at least one tile under
 * it at z14 and z15 (DEM) and at z14 (vector), so full detail is at least that much more. A country whose floor is past
 * the budget is not measured at full detail (Switzerland's took 37 s on the emulator for 13.8 GB). Pure, tested.
 */
internal fun fullAtLeast(less: CountryMeasure): Long =
    less.bytes + less.topTiles.toLong() * (AreaManifest.estimate(AreaManifest.ROUTE_DEM, 14) + AreaManifest.estimate(AreaManifest.ROUTE_DEM, 15) + AreaManifest.estimate(AreaManifest.ROUTE_OSM, 14))

/**
 * T142: [c] as a polygon area from z8 to [maxZoom], named by its `NAME`, its rings open and its simplification tolerance
 * added to `bufferM` (ADR 0041's writer rule); null when no area can hold it (Antarctica: past 85.05° and around the
 * world). Pure, tested.
 */
internal fun countryArea(c: Country, maxZoom: Int): AreaOfInterest? = try {
    AreaOfInterest(AreaShape.Polygon(c.rings.map { AreaOfInterest.openRing(it) }, c.toleranceM), AreaPicker.MIN_ZOOM, maxZoom, c.name)
} catch (e: IllegalArgumentException) {
    null
}

/**
 * T142: the tiles and bytes of [area]'s manifest at DEM zooms [dem] and vector zooms [osm] with [margin] (the totals of
 * [AreaManifest.tileItems], each zoom's cover made once for both routes), or too large when [AreaLimit] refuses it before
 * anything is filled. Pure, tested against [AreaManifest.tileItems].
 */
internal fun measureArea(area: AreaOfInterest, dem: IntRange?, osm: IntRange?, maxTiles: Long, margin: Int = 1): CountryMeasure {
    if (AreaLimit.refusal(area, dem, osm, maxTiles, margin) != null) return CountryMeasure(0, 0, true)
    var bytes = 0L
    var tiles = 0
    var top = 0
    for (z in (listOfNotNull(dem, osm).flatMap { it.toList() }).toSortedSet()) {
        val n = TileCover.of(area, z, margin).size
        if (dem != null && z in dem) { tiles += n; bytes += n * AreaManifest.estimate(AreaManifest.ROUTE_DEM, z) }
        if (osm != null && z in osm) { tiles += n; bytes += n * AreaManifest.estimate(AreaManifest.ROUTE_OSM, z) }
        top = n
    }
    return CountryMeasure(bytes, tiles, false, top)
}

/** T142: a country row's state: being measured, downloadable, over the pinned budget, or too large for the heap. */
enum class RowState { MEASURING, FITS, OVER_BUDGET, TOO_LARGE }

/** T142: what a country row shows: the detail it opens at, its size line, and the sentence of a greyed row. */
class CountryRow(val detail: String, val bytes: Long?, val tiles: Int?, val state: RowState, val sizeText: String, val sentence: String?) {
    val greyed: Boolean get() = state == RowState.OVER_BUDGET || state == RowState.TOO_LARGE
}

/**
 * T142: the row of a country measured as [e] (null: not yet) with [pinnedBytes] pinned, [alreadyBytes] of them its own
 * (downloaded before), and a budget of [budgetMb]: full detail when it fits the budget, else less detail; over the budget
 * at less detail greyed with ADR 0041 §5's sentence; refused by the heap greyed with [TOO_LARGE_ROW]. Pure, tested.
 */
internal fun countryRow(e: CountrySizes.Entry?, pinnedBytes: Long, alreadyBytes: Long, budgetMb: Long): CountryRow {
    if (e == null) return CountryRow(DETAIL_LESS, null, null, RowState.MEASURING, MEASURING, null)
    if (e.less.tooLarge) return CountryRow(DETAIL_LESS, null, null, RowState.TOO_LARGE, "", TOO_LARGE_ROW)
    e.full?.takeIf { !it.tooLarge && areaBudgetError(pinnedBytes, it.bytes, alreadyBytes, budgetMb) == null }?.let {
        return CountryRow(DETAIL_FULL, it.bytes, it.tiles, RowState.FITS, rowSize(it.bytes, DETAIL_FULL), null)
    }
    val over = areaBudgetError(pinnedBytes, e.less.bytes, alreadyBytes, budgetMb)
    return CountryRow(DETAIL_LESS, e.less.bytes, e.less.tiles, if (over == null) RowState.FITS else RowState.OVER_BUDGET, rowSize(e.less.bytes, DETAIL_LESS), over)
}

/** T142: "About 28 MB at less detail" or "About 230 MB at full detail". Pure, tested. */
internal fun rowSize(bytes: Long, detail: String): String = "About ${aboutSize(bytes)} at ${if (detail == DETAIL_FULL) "full" else "less"} detail"

/** T142: [countries] grouped by continent (alphabetical), each group in the given (alphabetical) order. Pure, tested. */
internal fun byContinent(countries: List<Country>): List<Pair<String, List<Country>>> =
    countries.groupBy { it.continent }.toSortedMap().map { (k, v) -> k to v }

/** T142: the rows the list shows for [filter]: every country grouped by continent, or [Countries.find]'s matches in its order. */
internal fun countryRows(all: List<Country>, filter: String): List<Pair<String?, List<Country>>> =
    if (filter.isBlank()) byContinent(all).map { (k, v) -> k to v } else listOf(null to Countries.find(filter, all))

const val DETAIL_FULL = "full"
const val DETAIL_LESS = "less"

/** T142: the button in the sheet, and the list's title. */
internal const val CHOOSE_COUNTRY = "Choose a country"
/** T142: the filter box's hint. */
internal const val FILTER_HINT = "Filter by name or code"
/** T142: a row the heap's tile limit refuses even at less detail (the owner's words in T142). */
internal const val TOO_LARGE_ROW = "Too large to download whole at this detail; draw an area instead"
/** T142: a row or a summary still being measured. */
internal const val MEASURING = "Measuring…"
/** T142: a row's mark for a country on this phone, and one downloading now. */
internal const val DOWNLOADED_MARK = "DOWNLOADED"
internal const val DOWNLOADING_MARK = "DOWNLOADING"
/** T142: the mark of a country whose area record is in [state] (`done`, `downloading`); none otherwise. Pure, tested. */
internal fun downloadMark(state: String?): String? = when (state) {
    "done" -> DOWNLOADED_MARK
    "downloading" -> DOWNLOADING_MARK
    else -> null
}
internal const val NO_MATCH = "No country matches"

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Grey = Color(0xFF9A9088)
private val Line = Color(0xFFE2D8C6)
private val Teal = Color(0xFF00796B)

/**
 * T142: the country list in the Offline areas sheet ([AreaPicker.Mode.Countries]): the filter box, then the rows; a row
 * that can be downloaded opens its summary ([AreaPicker.openCountry]). [pinnedBytes] comes from the sheet's own poll (the UI
 * thread takes no lock).
 */
@Composable
internal fun CountryListPane(pinnedBytes: Long, modifier: Modifier = Modifier) {
    val all by produceState<List<Country>?>(null) { value = withContext(Dispatchers.Default) { Countries.all } }
    val list = all ?: run { BasicText(MEASURING, style = TextStyle(color = Muted, fontSize = 13.sp)); return }
    val filter = AreaPicker.filter
    val rows = remember(list, filter) { countryRows(list, filter) }
    DisposableEffect(rows) {
        CountrySizes.request(rows.flatMap { g -> g.second.map { it.code } })
        onDispose { CountrySizes.request(emptyList()) }
    }
    @Suppress("UNUSED_VARIABLE") val rev = CountrySizes.revision // recompose as sizes arrive
    val sizes = CountrySizes.entries
    val records = Areas.list
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
            if (filter.isEmpty()) BasicText(FILTER_HINT, style = TextStyle(color = Muted, fontSize = 15.sp))
            BasicTextField(filter, { AreaPicker.filter = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
        }
        if (rows.all { it.second.isEmpty() }) BasicText(NO_MATCH, style = TextStyle(color = Muted, fontSize = 14.sp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((continent, countries) in rows) {
                if (continent != null) item(key = "h-$continent") {
                    BasicText(continent, Modifier.padding(top = 6.dp), style = TextStyle(color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                items(countries, key = { it.code }) { c ->
                    val e = sizes[c.code]
                    val rec = records.firstOrNull { r -> e != null && (r.id == e.lessId || r.id == e.fullId) }
                    CountryRowView(c, countryRow(e, pinnedBytes, rec?.takeIf { it.pinned }?.bytes ?: 0L, AppSettings.pinnedCacheMb), rec)
                }
            }
        }
    }
}

@Composable
private fun CountryRowView(c: Country, row: CountryRow, rec: Areas.Record?) {
    val click = if (row.greyed) Modifier else Modifier.clickable { AreaPicker.openCountry(c.code, null) }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).then(click).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(c.name, Modifier.weight(1f), style = TextStyle(color = if (row.greyed) Grey else Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            downloadMark(rec?.state)?.let { BasicText(it, style = TextStyle(color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
        }
        if (row.sizeText.isNotEmpty()) BasicText(row.sizeText, style = TextStyle(color = if (row.greyed) Grey else Muted, fontSize = 12.sp))
        row.sentence?.let { BasicText(it, style = TextStyle(color = Grey, fontSize = 12.sp)) }
    }
}
