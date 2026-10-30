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
import io.github.jasondorjeshort.dystopo.areas.AreaDetail
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
 * T157: a row shows the three details' sizes ([rowSizes]: less, medium, full) and opens at the deepest that fits the
 * budget; the medium detail (elevation z13, vector z14) is measured with the less detail's covers plus z14's, and the full
 * detail, when its floor is past the budget, is estimated from z14's cover ([estimateFull]) instead of filled at z15.
 *
 * A row over the budget stays, greyed, with ADR 0041 §5's budget sentence; a country no area can hold (Antarctica; T152:
 * the heap no longer refuses one) stays, greyed, with [TOO_LARGE_ROW]. Sizes are
 * measured on the phone, off the UI thread ([CountrySizes]), over the same cover and estimate as the download's manifest.
 * T170 (T142's follow-up; T150 measured 60 s of CPU per list open): each country's cover counts per zoom are kept in
 * `files/areas/country-covers.txt` ([CoverCounts]) once measured, for the bundled set they were measured from, so a list
 * opened again (in this process or a later one) adds up sizes from stored counts instead of filling every cover again.
 * No sovereignty text anywhere: the list shows `NAME` and Natural Earth's continent, nothing else.
 */
object CountrySizes {
    private const val TAG = "DystopoCountries"

    /**
     * One country's sizes: at less detail, at full detail (T157: measured when its floor fits the budget, else estimated from
     * z14's cover, [CountryMeasure.estimated]), and (T157) at medium detail; the area ids of each.
     */
    class Entry(
        val less: CountryMeasure, val full: CountryMeasure?, val lessId: String?, val fullId: String?,
        val medium: CountryMeasure? = null, val mediumId: String? = null,
    ) {
        /** T157: the ids of the three details' areas (a record of any of them is this country's). */
        val ids: List<String> get() = listOfNotNull(lessId, mediumId, fullId)
    }

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
    private val revisionPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val REVISION_MS = 150L

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

    /** T170: the stored cover counts, read on the worker's first pass and written when it idles after measuring. */
    private var covers: CoverCounts? = null
    private var coversDirty = false
    /** T170: for `GET /areas/countries` `coverCache`: the file's countries, counts filled and reused, the last load and save. */
    @Volatile var coverStats: Map<String, Any?> = emptyMap()
        private set
    @Volatile private var filled = 0
    @Volatile private var reused = 0

    private fun coverFile() = java.io.File(java.io.File(DystopoApp.instance.filesDir, "areas").apply { mkdirs() }, CoverCounts.FILE_NAME)

    private fun loadCovers(): CoverCounts {
        covers?.let { return it }
        val t0 = System.currentTimeMillis()
        val key = CoverCounts.keyOf(Countries.all)
        val c = runCatching { coverFile().takeIf { it.isFile }?.readText()?.let { CoverCounts.parse(it, key) } }
            .onFailure { Log.w(TAG, "reading ${CoverCounts.FILE_NAME} failed", it) }.getOrNull() ?: CoverCounts(key)
        covers = c
        coverStats = linkedMapOf("file" to CoverCounts.FILE_NAME, "key" to key, "countriesRead" to c.size, "loadMs" to System.currentTimeMillis() - t0)
        return c
    }

    private fun saveCovers() {
        val c = covers ?: return
        if (!coversDirty) return
        coversDirty = false
        val t0 = System.currentTimeMillis()
        runCatching {
            val f = coverFile()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(c.text())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "writing ${CoverCounts.FILE_NAME} failed", it) }
        coverStats = coverStats + mapOf("countriesSaved" to c.size, "saveMs" to System.currentTimeMillis() - t0, "saves" to ((coverStats["saves"] as? Int ?: 0) + 1))
    }

    private fun loop() {
        while (true) {
            // T150: no measuring and no 5 s re-check in the background (the list stays composed behind HOME; a country can take 30 s).
            io.github.jasondorjeshort.dystopo.app.Foreground.block()
            val server = server()
            val demFirst = AreaDownload.zooms(countryLessProbe, server).first.first
            if (measuredFor != demFirst) { entries = emptyMap(); measuredFor = demFirst }
            val next = want.firstOrNull { it !in entries } ?: extra.firstOrNull { it !in entries }
            if (next == null) {
                saveCovers() // T170: once the rows asked for are measured
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
            // T170: one recomposition per [REVISION_MS] at most (stored sizes arrive in a burst; each bump redraws the list).
            if (revisionPending.compareAndSet(false, true)) main.postDelayed({ revisionPending.set(false); revision++ }, REVISION_MS)
        }
    }

    private fun measure(c: Country, server: String?): Entry {
        val tooLarge = Entry(CountryMeasure(0, 0, true), null, null, null)
        // T170: the stored counts and ids first ([CoverCounts]); the country's polygon (its rings decoded, an id written out
        // over every vertex) is made only for what is not stored yet.
        val store = loadCovers()
        val known = store.idsOf(c.code)
        if (known != null && known.isEmpty()) return tooLarge
        val real by lazy(LazyThreadSafetyMode.NONE) { AreaDetail.entries.associateWith { countryArea(c, it) } }
        if (known == null && real[AreaDetail.LESS] == null) {
            store.putIds(c.code, emptyMap())
            coversDirty = true
            return tooLarge
        }
        val ids = known ?: AreaDetail.entries.associateWith { real.getValue(it)!!.id }.also { store.putIds(c.code, it); coversDirty = true }
        val pinned = runCatching { OnlineService.get(DystopoApp.instance).cache.pinnedBytes }.getOrDefault(0L)
        // T157: every zoom's cover filled once for the three details (the shape is the same; only the zooms differ).
        // T170: and kept for the next open; a zoom stored before is not filled again. The zooms come from the probe at
        // the same detail (they depend on the detail and the server, not the shape).
        val counts = HashMap<Int, Int>(store.of(c.code))
        fun measured(d: AreaDetail) = AreaDownload.zooms(countryLessProbe.at(d), server).let { (dz, oz) ->
            measureCounts(dz, oz) { z ->
                counts[z]?.also { reused++ } ?: TileCover.of(real.getValue(d)!!, z, 1).size.also { n ->
                    counts[z] = n
                    store.put(c.code, z, n)
                    coversDirty = true
                    filled++
                }
            }
        }
        val less = measured(AreaDetail.LESS)
        val medium = measured(AreaDetail.MEDIUM)
        // Full detail filled only when it might fit the budget (z15 costs four times z14's tiles): [fullAtLeast] first;
        // else estimated from z14's cover.
        val full = if (areaBudgetError(pinned, fullAtLeast(less), 0, AppSettings.pinnedCacheMb) == null) measured(AreaDetail.FULL)
        else estimateFull(medium, counts[AreaDetail.MEDIUM.maxZoom] ?: 0)
        coverStats = coverStats + mapOf("filled" to filled, "reused" to reused)
        return Entry(less, full, ids[AreaDetail.LESS], ids[AreaDetail.FULL], medium, ids[AreaDetail.MEDIUM])
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
 * T170: the cover counts of the bundled countries, per country code and zoom (the tiles of [TileCover.of] with margin 1 of
 * the country's polygon area; the same at every detail, since only the zooms differ), for the set [key] names
 * ([keyOf]: the codes, names, boxes and simplification tolerances of every country, and [VERSION]), and the area ids of
 * its three details ([AreaOfInterest.id] goes over every vertex). The text form, one country per line after a header line:
 * `country-covers <VERSION> <key>`, then `<code>\t<z>:<count>,<z>:<count>,...\t<id full>,<id medium>,<id less>` (the ids
 * in [AreaDetail] order, `-` for a country no area can hold). A file of another version or set reads as empty, so a new
 * bundled set or cover rule is measured afresh. Pure, tested.
 */
internal class CoverCounts(val key: String) {
    private val byCode = HashMap<String, MutableMap<Int, Int>>()
    private val idsByCode = HashMap<String, Map<AreaDetail, String>>()

    val size: Int get() = (byCode.keys + idsByCode.keys).size

    fun of(code: String): Map<Int, Int> = byCode[code].orEmpty()

    fun put(code: String, z: Int, count: Int) {
        byCode.getOrPut(code) { java.util.TreeMap() }[z] = count
    }

    /** The area ids of the country's three details; empty for a country no area can hold; null when not stored. */
    fun idsOf(code: String): Map<AreaDetail, String>? = idsByCode[code]

    fun putIds(code: String, ids: Map<AreaDetail, String>) {
        idsByCode[code] = ids
    }

    fun text(): String = buildString {
        append("country-covers ").append(VERSION).append(' ').append(key).append('\n')
        for (code in (byCode.keys + idsByCode.keys).sorted()) {
            append(code).append('\t').append(of(code).entries.joinToString(",") { (z, n) -> "$z:$n" })
            idsByCode[code]?.let { ids -> append('\t').append(if (ids.isEmpty()) TOO_LARGE else AreaDetail.entries.joinToString(",") { ids[it].orEmpty() }) }
            append('\n')
        }
    }

    companion object {
        const val FILE_NAME = "country-covers.txt"
        /** The ids field of a country no area can hold. */
        const val TOO_LARGE = "-"
        /** Bumped when the cover rule changes (the margin, [TileCover]'s fill), so stored counts are measured again. */
        const val VERSION = 1

        /** The bundled set's fingerprint: what the covers depend on, without decoding a ring. Pure, tested. */
        fun keyOf(countries: List<Country>): String {
            var h = 1125899906842597L
            for (c in countries) {
                for (v in listOf<Any>(c.code, c.name, c.west, c.south, c.east, c.north, c.toleranceM)) h = 31 * h + v.hashCode()
            }
            return "${countries.size}-${java.lang.Long.toHexString(h)}"
        }

        /** [text] read back for the set [key]; empty when the header names another version or set, a bad pair skipped. */
        fun parse(text: String, key: String): CoverCounts {
            val out = CoverCounts(key)
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != "country-covers $VERSION $key") return out
            for (line in lines) {
                val code = line.substringBefore('\t', "").takeIf { it.isNotBlank() } ?: continue
                val fields = line.split('\t')
                for (p in fields.getOrElse(1) { "" }.split(',')) {
                    val z = p.substringBefore(':').toIntOrNull()
                    val n = p.substringAfter(':', "").toIntOrNull()
                    if (z != null && n != null && z in 0..AreaOfInterest.MAX_ZOOM && n >= 0) out.put(code, z, n)
                }
                val ids = fields.getOrNull(2)?.trim() ?: continue
                if (ids == TOO_LARGE) out.putIds(code, emptyMap())
                else ids.split(',').takeIf { l -> l.size == AreaDetail.entries.size && l.all { it.isNotBlank() } }
                    ?.let { l -> out.putIds(code, AreaDetail.entries.zip(l).toMap()) }
            }
            return out
        }
    }
}

/**
 * T142: a country's tiles and estimated bytes at one detail, or [tooLarge] when no area can hold it (T152: never the heap);
 * [topTiles] is the cover's count at the deepest zoom. T157: [estimated] when not filled at every zoom ([estimateFull]).
 */
class CountryMeasure(val bytes: Long, val tiles: Int, val tooLarge: Boolean, val topTiles: Int = 0, val estimated: Boolean = false)

/**
 * T157: the full detail from the medium detail's measure [medium] and its z14 cover's count [z14]: the medium detail plus the
 * DEM at z14 (the same cover) and at z15 (four tiles under each z14 one), so an estimate a little high (the margin is one
 * tile at each zoom, not four). Used when the full detail's floor is past the budget, so z15 is not filled. Pure, tested.
 */
internal fun estimateFull(medium: CountryMeasure, z14: Int): CountryMeasure {
    val z15 = 4L * z14
    val bytes = medium.bytes + z14 * AreaManifest.estimate(AreaManifest.ROUTE_DEM, 14) + z15 * AreaManifest.estimate(AreaManifest.ROUTE_DEM, 15)
    return CountryMeasure(bytes, (medium.tiles + z14 + z15).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false, z15.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), estimated = true)
}

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

/** T157: [c] as a polygon area at [detail] (the medium detail's elevation cap included); null as [countryArea]. Pure, tested. */
internal fun countryArea(c: Country, detail: AreaDetail): AreaOfInterest? = countryArea(c, detail.maxZoom)?.at(detail)

/**
 * T142: the tiles and bytes of [area]'s manifest at DEM zooms [dem] and vector zooms [osm] with [margin] (the totals of
 * [AreaManifest.tileItems], each zoom's cover made once for both routes; T152: rows, so at any size). Pure, tested against
 * [AreaManifest.tileItems].
 */
internal fun measureArea(area: AreaOfInterest, dem: IntRange?, osm: IntRange?, margin: Int = 1): CountryMeasure =
    measureCounts(dem, osm) { z -> TileCover.of(area, z, margin).size }

/** T157: [measureArea] over the cover counts [count] gives per zoom (filled once and shared between details by the caller). Pure, tested. */
internal fun measureCounts(dem: IntRange?, osm: IntRange?, count: (Int) -> Int): CountryMeasure {
    var bytes = 0L
    var tiles = 0
    var top = 0
    for (z in (listOfNotNull(dem, osm).flatMap { it.toList() }).toSortedSet()) {
        val n = count(z)
        if (dem != null && z in dem) { tiles += n; bytes += n * AreaManifest.estimate(AreaManifest.ROUTE_DEM, z) }
        if (osm != null && z in osm) { tiles += n; bytes += n * AreaManifest.estimate(AreaManifest.ROUTE_OSM, z) }
        top = n
    }
    return CountryMeasure(bytes, tiles, false, top)
}

/** T142: a country row's state: being measured, downloadable, over the pinned budget, or too large for the heap. */
enum class RowState { MEASURING, FITS, OVER_BUDGET, TOO_LARGE }

/**
 * T142: what a country row shows: the detail it opens at, its size line, and the sentence of a greyed row. T157: [sizeText]
 * is the three details' sizes ([rowSizes]) once measured.
 */
class CountryRow(val detail: String, val bytes: Long?, val tiles: Int?, val state: RowState, val sizeText: String, val sentence: String?) {
    val greyed: Boolean get() = state == RowState.OVER_BUDGET || state == RowState.TOO_LARGE
}

/**
 * T142: the row of a country measured as [e] (null: not yet) with [pinnedBytes] pinned, [alreadyBytes] of them its own
 * (downloaded before), and a budget of [budgetMb]: full detail when it fits the budget, else (T157) medium detail when it
 * does, else less detail; over the budget at less detail greyed with ADR 0041 §5's sentence; no area can hold it greyed
 * with [TOO_LARGE_ROW]. T157: the size text gives every detail measured ([rowSizes]). Pure, tested.
 */
internal fun countryRow(e: CountrySizes.Entry?, pinnedBytes: Long, alreadyBytes: Long, budgetMb: Long): CountryRow {
    if (e == null) return CountryRow(DETAIL_LESS, null, null, RowState.MEASURING, MEASURING, null)
    if (e.less.tooLarge) return CountryRow(DETAIL_LESS, null, null, RowState.TOO_LARGE, "", TOO_LARGE_ROW)
    val text = rowSizes(e.less.bytes, e.medium?.takeIf { !it.tooLarge }?.bytes, e.full?.takeIf { !it.tooLarge }?.bytes)
    fun fits(m: CountryMeasure?) = m?.takeIf { !it.tooLarge && areaBudgetError(pinnedBytes, it.bytes, alreadyBytes, budgetMb) == null }
    fits(e.full)?.let { return CountryRow(DETAIL_FULL, it.bytes, it.tiles, RowState.FITS, text, null) }
    fits(e.medium)?.let { return CountryRow(DETAIL_MEDIUM, it.bytes, it.tiles, RowState.FITS, text, null) }
    val over = areaBudgetError(pinnedBytes, e.less.bytes, alreadyBytes, budgetMb)
    return CountryRow(DETAIL_LESS, e.less.bytes, e.less.tiles, if (over == null) RowState.FITS else RowState.OVER_BUDGET, text, over)
}

/** T142: "About 28 MB at less detail" or "About 230 MB at full detail" (T157: "at medium detail"). Pure, tested. */
internal fun rowSize(bytes: Long, detail: String): String = "About ${aboutSize(bytes)} at ${detailWord(AreaDetail.byKey(detail) ?: AreaDetail.LESS)} detail"

/**
 * T157 (ADR 0031: the owner's words to adjust): a country row's three sizes, "Less 28 MB · Medium 40 MB · Full 230 MB"; a
 * detail not measured yet is left out. Pure, tested.
 */
internal fun rowSizes(less: Long, medium: Long?, full: Long?): String =
    listOfNotNull(AreaDetail.LESS to less, medium?.let { AreaDetail.MEDIUM to it }, full?.let { AreaDetail.FULL to it })
        .joinToString(" · ") { (d, b) -> "${detailWord(d).replaceFirstChar { it.uppercase() }} ${aboutSize(b)}" }

/** T142: [countries] grouped by continent (alphabetical), each group in the given (alphabetical) order. Pure, tested. */
internal fun byContinent(countries: List<Country>): List<Pair<String, List<Country>>> =
    countries.groupBy { it.continent }.toSortedMap().map { (k, v) -> k to v }

/** T142: the rows the list shows for [filter]: every country grouped by continent, or [Countries.find]'s matches in its order. */
internal fun countryRows(all: List<Country>, filter: String): List<Pair<String?, List<Country>>> =
    if (filter.isBlank()) byContinent(all).map { (k, v) -> k to v } else listOf(null to Countries.find(filter, all))

const val DETAIL_FULL = "full"
const val DETAIL_LESS = "less"
/** T157: the medium detail (elevation z13, vector z14; [AreaDetail.MEDIUM]). */
const val DETAIL_MEDIUM = "medium"

/** T157: the details by their keys, deepest first. */
val DETAILS = listOf(DETAIL_FULL, DETAIL_MEDIUM, DETAIL_LESS)

/** T142: the button in the sheet, and the list's title. */
internal const val CHOOSE_COUNTRY = "Choose a country"
/** T142: the filter box's hint. */
internal const val FILTER_HINT = "Filter by name or code"
/** T142: a row no area can hold (the owner's words in T142; T152: no longer the heap's limit). */
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
                    val rec = records.firstOrNull { r -> e != null && r.id in e.ids }
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
