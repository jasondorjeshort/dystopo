package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*
import io.github.jasondorjeshort.dystopo.sources.elevation.ElevationSource // T80: nested Result needs the class, not the alias

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToLong

/** The tour's view of an `areas.json` entry: the plain area plus what a plausible render looks like. */
@Serializable
data class TourAreaDef(
    val bbox: List<Double>,
    val description: String = "",
    /** Public-knowledge [lowest, highest] ground elevation inside the bbox, meters. */
    val expectedElevation: List<Double>? = null,
    /** "world" (in the default tour), "expectOcean" (NaN allowed). */
    val tags: List<String> = emptyList(),
)

@Serializable
data class TourAreasFile(val areas: Map<String, TourAreaDef> = emptyMap())

@Serializable
data class TourCheck(val name: String, val pass: Boolean, val detail: String)

@Serializable
data class TourEntry(
    val area: String,
    val zoom: Int,
    val bbox: List<Double>,
    val tags: List<String>,
    val expectedElevation: List<Double>?,
    val output: String?,
    val widthPx: Int,
    val heightPx: Int,
    val cellSizeMeters: Double,
    /** Null when the grid has no data at all. */
    val elevationMinMeters: Double?,
    val elevationMaxMeters: Double?,
    /** [lat, lon] of the lowest and highest pixel, to find spikes and pits quickly. */
    val minAt: List<Double>?,
    val maxAt: List<Double>?,
    val nanFraction: Double,
    /** Sum of every `*datumUnconvertedCells` counter (3DEP/lidar cells no NGS geoid grid covers, left NaN for the fallback; expected 0 everywhere). */
    val datumUnconvertedCells: Long,
    val sourceCounters: Map<String, Long>,
    val provenance: List<String>,
    val timingsMs: Map<String, Long>,
    val error: String?,
    val checks: List<TourCheck>,
    val pass: Boolean,
    /** `--offline-ok` and the area's data is not all in the cache: not rendered, not counted as a failure. */
    val skipped: Boolean = false,
)

@Serializable
data class TourSummary(
    val source: String,
    val zooms: List<Int>,
    val toleranceMeters: Double,
    val maxNanFraction: Double,
    val renders: Int,
    val passed: Int,
    val failed: List<String>,
    /** True when run with `--offline-ok` (cache only, no network). */
    val offlineOk: Boolean = false,
    /** `area-zN` renders skipped by `--offline-ok` because their data was not cached. */
    val skipped: List<String> = emptyList(),
    val contactSheet: String,
    val totalMs: Long,
    val entries: List<TourEntry>,
)

/**
 * Renders public areas around the world with the normal pipeline (source fetch + [MapRenderer]) and
 * checks each result for plausibility. One run says whether sources, datums and compositing behave
 * in every hemisphere, at the equator, the antimeridian, high latitudes, below sea level and at sea.
 */
class TourCommand : CliktCommand(name = "tour") {
    override fun help(context: Context) =
        "Render areas from ${Areas.PUBLIC_FILE} at several zooms, check elevation range / NaN / datum, " +
            "write PNGs, summary.json and a contact sheet. Exit 1 if any check fails."

    private val areas by option("--areas", help = "Comma-separated area names (default: every area with expectedElevation)")
    private val tag by option("--tag", help = "Only areas with this tag, e.g. world")
    private val zooms by option("--zooms", help = "Comma-separated zooms").default("9,12")
    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val out by option("--out", help = "Output directory").file().default(File("out/tour"))
    private val offlineOk by option(
        "--offline-ok",
        help = "Cache only: never touch the network; an area whose data is not all in the cache is skipped (reported, not a failure). For CI with a cold cache.",
    ).flag()
    private val style by StyleOptions()

    /** Thrown by the [Http.beforeNetwork] hook under `--offline-ok`: the render needed [url], which is not cached. */
    class OfflineCacheMiss(val url: String) : java.io.IOException("not in the cache (--offline-ok): $url")

    companion object {
        /**
         * Slack on both ends of `expectedElevation`. Copernicus is a surface model: forest canopy adds
         * up to ~40 m, and its absolute vertical error is a few meters (LE90 < 4 m). Resampling only
         * narrows the range, so it never pushes a value outside. 50 m covers canopy and noise while
         * still catching datum mistakes (GEOID18 vs EGM2008 is tens of meters in places but a missed
         * conversion of the ellipsoid is 10-100 m) and wrong-cell bugs (hundreds to thousands of m).
         */
        const val TOLERANCE_METERS = 50.0

        /** NaN allowed on land: a few edge pixels of rounding, nothing more. */
        const val MAX_NAN_FRACTION = 0.001

        const val THUMB = 220
        const val LABEL_HEIGHT = 34
        const val PAD = 8
        const val SHEET_MAX_WIDTH = 2000

        fun load(root: File = File(".")): Map<String, TourAreaDef> {
            val file = File(root, Areas.PUBLIC_FILE)
            return Json { ignoreUnknownKeys = true }.decodeFromString<TourAreasFile>(file.readText()).areas
        }

        /**
         * A bbox whose west edge is east of its east edge crosses the antimeridian; it's unwrapped by
         * moving the east edge past 180° (e.g. 179.9..-179.9 becomes 179.9..180.1), so windows and
         * sources see one continuous longitude range.
         */
        fun boundingBox(bbox: List<Double>): BoundingBox {
            require(bbox.size == 4) { "bbox must be [west, south, east, north]" }
            val (w, s, e, n) = bbox
            return BoundingBox.unwrapped(w, s, e, n)
        }

        fun checks(def: TourAreaDef, min: Double?, max: Double?, nanFraction: Double, unconverted: Long, error: String?): List<TourCheck> {
            val result = ArrayList<TourCheck>()
            result += TourCheck("completed", error == null, error ?: "rendered")
            if (error != null) return result
            val ocean = "expectOcean" in def.tags
            val exp = def.expectedElevation
            if (exp != null && exp.size == 2) {
                val (lo, hi) = exp
                if (min == null || max == null) {
                    result += TourCheck("elevationRange", ocean, "no data at all" + if (ocean) " (ocean expected)" else "")
                } else {
                    val inside = min >= lo - TOLERANCE_METERS && max <= hi + TOLERANCE_METERS
                    result += TourCheck("elevationRange", inside, "observed [${fmt(min)}, ${fmt(max)}] vs expected [${fmt(lo)}, ${fmt(hi)}] +-${TOLERANCE_METERS.toInt()} m")
                    // The data should span the middle of the expected range: catches flattened, zeroed or shifted grids
                    // that would still sit inside the range.
                    val mid = (lo + hi) / 2
                    val spans = ocean || (min <= mid && max >= mid)
                    result += TourCheck("elevationSpan", spans, "observed range ${if (spans) "spans" else "misses"} the expected midpoint ${fmt(mid)}")
                }
            }
            if (!ocean) {
                result += TourCheck("nanFraction", nanFraction <= MAX_NAN_FRACTION, "NaN fraction ${"%.4f".format(nanFraction)} (max $MAX_NAN_FRACTION)")
            }
            // Since T21 every 3DEP window either has a geoid grid (GEOID18, GEOID12B) or skips 3DEP, so any unconverted cell is a bug.
            result += TourCheck("datum", unconverted == 0L, "$unconverted unconverted cells")
            return result
        }

        private fun fmt(v: Double) = v.roundToLong().toString()

        /** The [OfflineCacheMiss] in [e]'s cause chain (sources may wrap it), or null. */
        fun offlineMiss(e: Throwable?): OfflineCacheMiss? {
            var t = e
            val seen = HashSet<Throwable>()
            while (t != null && seen.add(t)) {
                if (t is OfflineCacheMiss) return t
                t = t.cause
            }
            return null
        }
    }

    override fun run() {
        val all = load()
        val zoomList = zooms.split(",").map { it.trim().toIntOrNull() ?: throw UsageError("--zooms must be integers") }
        val names = when {
            areas != null -> areas!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }.onEach {
                if (it !in all) throw UsageError("unknown public area '$it'; known: ${all.keys.joinToString()}")
            }
            tag != null -> all.filterValues { tag in it.tags }.keys.toList()
            else -> all.filterValues { it.expectedElevation != null }.keys.toList()
        }
        if (names.isEmpty()) throw UsageError("no areas selected")
        out.mkdirs()
        if (offlineOk) Http.beforeNetwork = { url -> throw OfflineCacheMiss(url) }

        val start = System.nanoTime()
        val entries = ArrayList<TourEntry>()
        val images = ArrayList<BufferedImage?>()
        for (name in names) for (z in zoomList) {
            val (entry, image) = renderOne(name, all.getValue(name), z)
            entries += entry
            images += image
        }
        val sheet = File(out, "contact-sheet.png")
        writeContactSheet(entries, images, sheet)

        val summary = TourSummary(
            source = source,
            zooms = zoomList,
            toleranceMeters = TOLERANCE_METERS,
            maxNanFraction = MAX_NAN_FRACTION,
            renders = entries.count { !it.skipped },
            passed = entries.count { it.pass && !it.skipped },
            failed = entries.filter { !it.pass }.map { "${it.area}-z${it.zoom}" },
            offlineOk = offlineOk,
            skipped = entries.filter { it.skipped }.map { "${it.area}-z${it.zoom}" },
            contactSheet = sheet.path,
            totalMs = (System.nanoTime() - start) / 1_000_000,
            entries = entries,
        )
        val text = reportJson.encodeToString(summary)
        File(out, "summary.json").writeText(text)
        echo(text)
        if (summary.failed.isNotEmpty()) throw ProgramResult(1)
    }

    private fun renderOne(name: String, def: TourAreaDef, zoom: Int): Pair<TourEntry, BufferedImage?> {
        val timings = Timings()
        var window: MercatorWindow? = null
        var fetched: ElevationSource.Result? = null
        var image: BufferedImage? = null
        var outFile: File? = null
        var miss: OfflineCacheMiss? = null
        val error = try {
            val box = boundingBox(def.bbox)
            window = MercatorWindow.forBbox(box, zoom)
            val src = ElevationSource.byId(source)
            fetched = timings.time("fetch") { src.fetch(window) }
            image = MapRenderer.render(fetched.grid, zoom, style, timings).image
            outFile = File(out, "$name-z$zoom.png")
            timings.time("writePng") { ImageIO.write(image, "png", outFile) }
            null
        } catch (e: Exception) {
            miss = offlineMiss(e)
            "${e::class.simpleName}: ${e.message}"
        }
        if (miss != null) {
            val entry = TourEntry(
                area = name, zoom = zoom, bbox = def.bbox, tags = def.tags, expectedElevation = def.expectedElevation,
                output = null, widthPx = window?.width ?: 0, heightPx = window?.height ?: 0, cellSizeMeters = 0.0,
                elevationMinMeters = null, elevationMaxMeters = null, minAt = null, maxAt = null, nanFraction = 1.0,
                datumUnconvertedCells = 0, sourceCounters = emptyMap(), provenance = emptyList(), timingsMs = timings.ms,
                error = null, checks = listOf(TourCheck("cached", true, "skipped: ${miss.message}")), pass = true, skipped = true,
            )
            return entry to null
        }
        val grid = fetched?.grid
        val min = grid?.min()?.toDouble()?.takeIf { it.isFinite() }
        val max = grid?.max()?.toDouble()?.takeIf { it.isFinite() }
        val nanFraction = grid?.let { g -> g.values.count { it.isNaN() }.toDouble() / g.values.size } ?: 1.0
        val counters = fetched?.counters ?: emptyMap()
        val unconverted = counters.filterKeys { it.endsWith("datumUnconvertedCells") }.values.sum()
        val checks = checks(def, min, max, nanFraction, unconverted, error)
        val entry = TourEntry(
            area = name,
            zoom = zoom,
            bbox = def.bbox,
            tags = def.tags,
            expectedElevation = def.expectedElevation,
            output = outFile?.path,
            widthPx = window?.width ?: 0,
            heightPx = window?.height ?: 0,
            cellSizeMeters = window?.let { (it.cellSizeMeters * 100).roundToLong() / 100.0 } ?: 0.0,
            elevationMinMeters = min?.let { Math.round(it * 10) / 10.0 },
            elevationMaxMeters = max?.let { Math.round(it * 10) / 10.0 },
            minAt = locate(window, grid?.values, min),
            maxAt = locate(window, grid?.values, max),
            nanFraction = Math.round(nanFraction * 1e5) / 1e5,
            datumUnconvertedCells = unconverted,
            sourceCounters = counters,
            provenance = fetched?.provenance ?: emptyList(),
            timingsMs = timings.ms,
            error = error,
            checks = checks,
            pass = checks.all { it.pass },
        )
        return entry to image
    }

    private fun locate(window: MercatorWindow?, values: FloatArray?, target: Double?): List<Double>? {
        if (window == null || values == null || target == null) return null
        val k = values.indexOfFirst { it.toDouble() == target }
        if (k < 0) return null
        fun r(v: Double) = Math.round(v * 1e4) / 1e4
        return listOf(r(window.latAt(k / window.width)), r(CopernicusSource.wrapLon(window.lonAt(k % window.width))))
    }

    /** Thumbnails in a grid, each with "area zN", min..max and a green PASS / red FAIL bar. */
    private fun writeContactSheet(entries: List<TourEntry>, images: List<BufferedImage?>, file: File) {
        val cellW = THUMB + PAD
        val cellH = THUMB + LABEL_HEIGHT + PAD
        val cols = maxOf(1, minOf(entries.size, (SHEET_MAX_WIDTH - PAD) / cellW))
        val rows = (entries.size + cols - 1) / cols
        val sheet = BufferedImage(cols * cellW + PAD, rows * cellH + PAD, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 40)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = RenderFonts.regular(12.0)
        for ((k, entry) in entries.withIndex()) {
            val x = PAD + (k % cols) * cellW
            val y = PAD + (k / cols) * cellH
            val img = images[k]
            if (img != null) {
                val scale = minOf(THUMB.toDouble() / img.width, THUMB.toDouble() / img.height)
                val w = (img.width * scale).toInt().coerceAtLeast(1)
                val h = (img.height * scale).toInt().coerceAtLeast(1)
                g.drawImage(img, x + (THUMB - w) / 2, y + (THUMB - h) / 2, w, h, null)
            } else {
                g.color = if (entry.skipped) Color(70, 70, 70) else Color(90, 20, 20)
                g.fillRect(x, y, THUMB, THUMB)
                g.color = Color.WHITE
                g.drawString(if (entry.skipped) "not cached" else "no image", x + 8, y + 20)
            }
            g.color = when {
                entry.skipped -> Color(120, 120, 120)
                entry.pass -> Color(40, 160, 60)
                else -> Color(210, 40, 40)
            }
            g.stroke = BasicStroke(3f)
            g.drawRect(x, y, THUMB, THUMB)
            g.fillRect(x, y + THUMB + 2, 44, 14)
            g.color = Color.WHITE
            g.drawString(if (entry.skipped) "SKIP" else if (entry.pass) "PASS" else "FAIL", x + 6, y + THUMB + 14)
            g.drawString("${entry.area} z${entry.zoom}", x + 50, y + THUMB + 14)
            val range = if (entry.skipped) "skipped (offline)" else if (entry.elevationMinMeters == null) "no data" else "${entry.elevationMinMeters!!.roundToLong()}..${entry.elevationMaxMeters!!.roundToLong()} m"
            val failed = entry.checks.filter { !it.pass }.joinToString(",") { it.name }
            g.color = Color(200, 200, 200)
            g.drawString(if (failed.isEmpty()) range else "$range  [$failed]", x, y + THUMB + 30)
        }
        g.dispose()
        file.absoluteFile.parentFile.mkdirs()
        ImageIO.write(sheet, "png", file)
    }
}
