package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.Utm
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Wmm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin

/** T167: the paper sizes a printed sheet offers, in millimetres, portrait. */
enum class Paper(val key: String, val label: String, val widthMm: Double, val heightMm: Double) {
    A4("a4", "A4", 210.0, 297.0),
    LETTER("letter", "Letter", 215.9, 279.4),
    A3("a3", "A3", 297.0, 420.0),
    ;

    companion object {
        fun parse(key: String): Paper? = entries.firstOrNull { it.key == key.trim().lowercase() }

        /** The default paper for a sheet centred at a place, as units are (ADR 0022): Letter in the US, A4 elsewhere. */
        fun defaultAt(lat: Double, lon: Double): Paper = if (UnitRegions.isUnitedStates(lat, lon)) LETTER else A4
    }
}

/** T167: the coordinate grid over a sheet's map: UTM lines (the default), latitude and longitude ticks, both, or none. */
enum class SheetGrid(val key: String) {
    UTM("utm"),
    LATLON("latlon"),
    BOTH("both"),
    NONE("none"),
    ;

    val utm: Boolean get() = this == UTM || this == BOTH
    val latLon: Boolean get() = this == LATLON || this == BOTH

    companion object {
        fun parse(key: String): SheetGrid? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** A rectangle on the paper in millimetres from the top-left corner. */
data class RectMm(val x: Double, val y: Double, val w: Double, val h: Double)

/** Which edge of the map frame a grid label sits on. */
enum class FrameEdge { TOP, BOTTOM, LEFT, RIGHT }

/** One grid line as a polyline in frame pixels (x, y pairs), for the coordinate [value] it draws. */
class GridLine(val value: Double, val xy: DoubleArray)

/** A grid label where a line meets the frame: [at] is the frame pixel along the edge (x on top and bottom, y on the sides). */
data class EdgeLabel(val edge: FrameEdge, val at: Double, val text: String)

/** The UTM grid of a sheet: [zone] and hemisphere, the line [spacingM], the lines and their edge labels. */
class UtmGrid(
    val zone: Int,
    val north: Boolean,
    val spacingM: Double,
    val eastings: List<GridLine>,
    val northings: List<GridLine>,
    val labels: List<EdgeLabel>,
) {
    /** "10N": the zone and hemisphere as printed. */
    val zoneName: String get() = "$zone${if (north) "N" else "S"}"
}

/** Latitude and longitude ticks every [spacingArcSec] seconds of arc: the meridians and parallels on the frame, labelled. */
class Graticule(val spacingArcSec: Int, val lons: List<Double>, val lats: List<Double>, val labels: List<EdgeLabel>)

/**
 * The three norths at the sheet's centre: magnetic north ([declinationDeg], east positive, from the World
 * Magnetic Model for [year]) and UTM grid north ([convergenceDeg], east positive) against true north, which is
 * straight up on the sheet (Web Mercator keeps meridians vertical).
 */
data class NorthAngles(val declinationDeg: Double, val convergenceDeg: Double, val year: Double) {
    /** Magnetic north against grid north, east positive: what a compass bearing on the UTM grid corrects by. */
    val magneticFromGridDeg: Double get() = declinationDeg - convergenceDeg
}

/**
 * T167: the page maths of a printed map sheet. The paper holds a title band, the map frame with a band for
 * the grid labels around it, and a footer for the scale bar, the north diagram and the notes ([frameMm]).
 * The map fills the frame at a true scale ([scale] is the denominator, 25000 for 1:25,000) at the sheet's
 * centre: [plan] picks the Web Mercator zoom whose style suits the scale (lines and text drawn as on a
 * [STYLE_DPI] screen) and a tile size that makes one window pixel one page pixel, then adjusts the page's
 * [dpi] by the tile size's rounding (a fraction of a percent) so the scale stays exact when the page is
 * placed on paper of its size. Frame pixels are this window's pixels: [frameX] and [frameY] place a point.
 */
data class PageLayout(
    val paper: Paper,
    val landscape: Boolean,
    /** Scale denominator: 25000 is 1:25,000. */
    val scale: Double,
    val centerLat: Double,
    val centerLon: Double,
    /** Web Mercator zoom of the map window (the style's zoom: contour interval, which features show). */
    val zoom: Int,
    /** Window tile size in pixels: one window pixel is one page pixel. */
    val tileSize: Int,
    /** The page's pixels per inch, within a fraction of a percent of [nominalDpi]. */
    val dpi: Double,
    val nominalDpi: Double,
    val frameMm: RectMm,
    val windowX0: Int,
    val windowY0: Int,
) {
    val pageWidthMm: Double get() = if (landscape) paper.heightMm else paper.widthMm
    val pageHeightMm: Double get() = if (landscape) paper.widthMm else paper.heightMm

    fun px(mm: Double): Double = mm * dpi / MM_PER_INCH

    val pageWidthPx: Int get() = px(pageWidthMm).roundToInt()
    val pageHeightPx: Int get() = px(pageHeightMm).roundToInt()
    val frameXPx: Int get() = px(frameMm.x).roundToInt()
    val frameYPx: Int get() = px(frameMm.y).roundToInt()
    val frameWidthPx: Int get() = px(frameMm.w).roundToInt()
    val frameHeightPx: Int get() = px(frameMm.h).roundToInt()

    /** Ground metres per page pixel at the centre: [scale] times the pixel's size on paper. */
    val metersPerPixel: Double get() = scale * METERS_PER_INCH / dpi

    /** Map line widths and text scale by this: page pixels per style pixel ([STYLE_DPI]). */
    val styleScale: Double get() = nominalDpi / STYLE_DPI

    /** Frame pixel x of a longitude, y of a latitude (fractional; the frame's top-left corner is 0, 0). */
    fun frameX(lon: Double): Double = WebMercator.lonToX(lon, zoom) * tileSize - windowX0
    fun frameY(lat: Double): Double = WebMercator.latToY(lat, zoom) * tileSize - windowY0
    fun lonAt(x: Double): Double = WebMercator.xToLon((x + windowX0) / tileSize, zoom)
    fun latAt(y: Double): Double = WebMercator.yToLat((y + windowY0) / tileSize, zoom)

    /** The frame's extent in degrees. */
    val bbox: BoundingBox get() = BoundingBox(lonAt(0.0), latAt(frameHeightPx.toDouble()), lonAt(frameWidthPx.toDouble()), latAt(0.0))

    /** The ground the frame covers at the centre, metres. */
    val groundWidthM: Double get() = frameWidthPx * metersPerPixel
    val groundHeightM: Double get() = frameHeightPx * metersPerPixel

    /** "1:25,000". */
    val ratioText: String get() = ratio(scale)

    /** T183: the frame's centre (latitude, longitude): the requested centre within a pixel. */
    val frameCenter: Pair<Double, Double> get() = latAt(frameHeightPx / 2.0) to lonAt(frameWidthPx / 2.0)

    /**
     * T183: the MapLibre camera zoom (512 px tiles) that draws the frame at exactly this scale when the map is
     * rendered at [pixelRatio] physical pixels per style pixel, one physical pixel being one frame pixel. The
     * phone's sheet snapshots at `dpi / STYLE_DPI`, so lines and text are as thick on paper as on a 150 dpi screen.
     */
    fun mapLibreZoom(pixelRatio: Double): Double = zoom + ln(tileSize / (512.0 * pixelRatio)) / ln(2.0)

    /** The UTM grid over the frame, in the zone of the centre; [spacingM] defaults to [gridSpacingMeters]. */
    fun utmGrid(spacingM: Double = gridSpacingMeters(scale)): UtmGrid {
        val zone = Utm.zoneFor(centerLon)
        val north = centerLat >= 0
        val w = frameWidthPx.toDouble()
        val h = frameHeightPx.toDouble()
        // The frame's extent in grid metres, from points around its edge (grid lines are slightly rotated).
        var e0 = Double.MAX_VALUE; var e1 = -Double.MAX_VALUE; var n0 = Double.MAX_VALUE; var n1 = -Double.MAX_VALUE
        for (i in 0..8) for ((x, y) in listOf(w * i / 8 to 0.0, w * i / 8 to h, 0.0 to h * i / 8, w to h * i / 8)) {
            val p = Utm.forward(latAt(y), lonAt(x), zone, north)
            e0 = min(e0, p.easting); e1 = max(e1, p.easting); n0 = min(n0, p.northing); n1 = max(n1, p.northing)
        }
        val steps = 24
        fun line(value: Double, easting: Boolean): GridLine {
            val from = (if (easting) n0 else e0) - spacingM
            val to = (if (easting) n1 else e1) + spacingM
            val xy = DoubleArray(2 * (steps + 1))
            for (k in 0..steps) {
                val t = from + (to - from) * k / steps
                val (lat, lon) = if (easting) Utm.inverse(value, t, zone, north) else Utm.inverse(t, value, zone, north)
                xy[2 * k] = frameX(lon)
                xy[2 * k + 1] = frameY(lat)
            }
            return GridLine(value, xy)
        }
        val eastings = multiples(e0, e1, spacingM).map { line(it, true) }
        val northings = multiples(n0, n1, spacingM).map { line(it, false) }
        val labels = ArrayList<EdgeLabel>()
        for ((edges, lines, suffix) in listOf(
            Triple(listOf(FrameEdge.TOP, FrameEdge.BOTTOM), eastings, "mE"),
            Triple(listOf(FrameEdge.LEFT, FrameEdge.RIGHT), northings, "mN"),
        )) for (edge in edges) {
            var first = true
            for (l in lines) {
                val at = crossing(l.xy, edge, w, h) ?: continue
                labels += EdgeLabel(edge, at, utmLabel(l.value, spacingM, full = first, suffix = suffix))
                first = false
            }
        }
        return UtmGrid(zone, north, spacingM, eastings, northings, labels)
    }

    /** Meridians and parallels every [spacingArcSec] (default [graticuleSpacingArcSec]), labelled where they meet the frame. */
    fun graticule(spacingArcSec: Int = graticuleSpacingArcSec(scale, centerLat)): Graticule {
        val step = spacingArcSec / 3600.0
        val b = bbox
        val lons = multiples(b.west, b.east, step)
        val lats = multiples(b.south, b.north, step)
        val labels = ArrayList<EdgeLabel>()
        for (lon in lons) for (edge in listOf(FrameEdge.TOP, FrameEdge.BOTTOM)) labels += EdgeLabel(edge, frameX(lon), dms(lon, lat = false))
        for (lat in lats) for (edge in listOf(FrameEdge.LEFT, FrameEdge.RIGHT)) labels += EdgeLabel(edge, frameY(lat), dms(lat, lat = true))
        return Graticule(spacingArcSec, lons, lats, labels)
    }

    /** True, grid and magnetic north at the centre on [epochMs] (the print date). */
    fun north(epochMs: Long): NorthAngles {
        val zone = Utm.zoneFor(centerLon)
        val north = centerLat >= 0
        val p = Utm.forward(centerLat, centerLon, zone, north)
        val (lat2, lon2) = Utm.inverse(p.easting, p.northing + 1000.0, zone, north)
        // Bearing of grid north from true north: east positive.
        val dx = (lon2 - centerLon) * cos(centerLat * PI / 180)
        val dy = lat2 - centerLat
        val convergence = atan2(dx, dy) * 180 / PI
        val year = Wmm.decimalYear(epochMs)
        return NorthAngles(Wmm.declination(centerLat, centerLon, year), convergence, year)
    }

    companion object {
        const val MM_PER_INCH = 25.4
        const val METERS_PER_INCH = 0.0254
        const val DEFAULT_SCALE = 25_000.0

        /** The scales offered besides "fit the view": 1:10,000, 1:25,000 (the default) and 1:50,000. */
        val SCALES = listOf(10_000, 25_000, 50_000)
        const val DEFAULT_DPI = 300.0

        /** Map lines and text are drawn as on a screen of this many pixels per inch (0.17 mm per style pixel). */
        const val STYLE_DPI = 150.0
        const val MAX_ZOOM = 17

        /** The paper's edge band printers leave blank. */
        const val MARGIN_MM = 10.0
        /** Around the frame, for the grid labels. */
        const val LABEL_BAND_MM = 5.0
        /** Above the frame, for the title. */
        const val TITLE_MM = 10.0
        /** Below the frame, for the scale bar, the north diagram and the notes. */
        const val FOOTER_MM = 40.0

        /** The map frame on the paper. */
        fun frameMm(paper: Paper, landscape: Boolean): RectMm {
            val w = if (landscape) paper.heightMm else paper.widthMm
            val h = if (landscape) paper.widthMm else paper.heightMm
            val top = MARGIN_MM + TITLE_MM + LABEL_BAND_MM
            val bottom = LABEL_BAND_MM + FOOTER_MM + MARGIN_MM
            val side = MARGIN_MM + LABEL_BAND_MM
            return RectMm(side, top, w - 2 * side, h - top - bottom)
        }

        /** The sheet of [paper] at 1:[scale] centred on [centerLat], [centerLon], drawn at about [dpi]. */
        fun plan(paper: Paper, landscape: Boolean, scale: Double, centerLat: Double, centerLon: Double, dpi: Double = DEFAULT_DPI): PageLayout {
            require(scale >= 500 && scale.isFinite()) { "scale must be at least 1:500 (got $scale)" }
            require(dpi in 20.0..1200.0) { "dpi must be 20 to 1200 (got $dpi)" }
            require(centerLat in -80.0..84.0) { "the sheet's centre must be between 80 S and 84 N (UTM; got $centerLat)" }
            val cosLat = cos(centerLat * PI / 180)
            val ground = WebMercator.EARTH_CIRCUMFERENCE_M * cosLat
            val target = scale * METERS_PER_INCH / dpi // ground metres per page pixel
            // The style zoom: where a 256 px tile's pixel is one STYLE_DPI pixel on paper at this scale (independent of dpi).
            val styleZoom = ln(ground * STYLE_DPI / (256 * scale * METERS_PER_INCH)) / ln(2.0)
            val zoom = styleZoom.roundToInt().coerceIn(1, MAX_ZOOM)
            val tileSize = (ground / (target * (1L shl zoom))).roundToInt().coerceAtLeast(16)
            val actual = ground / (tileSize.toDouble() * (1L shl zoom))
            val effectiveDpi = scale * METERS_PER_INCH / actual
            val frame = frameMm(paper, landscape)
            val fw = (frame.w * effectiveDpi / MM_PER_INCH).roundToInt()
            val fh = (frame.h * effectiveDpi / MM_PER_INCH).roundToInt()
            val cx = WebMercator.lonToX(centerLon, zoom) * tileSize
            val cy = WebMercator.latToY(centerLat, zoom) * tileSize
            return PageLayout(
                paper, landscape, scale, centerLat, centerLon, zoom, tileSize, effectiveDpi, dpi, frame,
                floor(cx - fw / 2.0).toInt(), floor(cy - fh / 2.0).toInt(),
            )
        }

        /** The Web Mercator midpoint of [box]: the centre of a sheet fitted to it. */
        fun center(box: BoundingBox): Pair<Double, Double> {
            val y = (WebMercator.latToY(box.north, 0) + WebMercator.latToY(box.south, 0)) / 2
            return WebMercator.yToLat(y, 0) to (box.west + box.east) / 2
        }

        /**
         * "Fit the view": the scale at which [box] just fits the frame of [paper], rounded up to a whole
         * denominator (so the sheet shows all of it and prints the exact ratio it used).
         */
        fun fitScale(box: BoundingBox, paper: Paper, landscape: Boolean): Double {
            val (lat, _) = center(box)
            val perWorld = WebMercator.EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180)
            val groundW = (box.east - box.west) / 360.0 * perWorld
            val groundH = (WebMercator.latToY(box.south, 0) - WebMercator.latToY(box.north, 0)) * perWorld
            val frame = frameMm(paper, landscape)
            return ceil(max(groundW / (frame.w / 1000), groundH / (frame.h / 1000))).coerceAtLeast(500.0)
        }

        /** UTM grid spacings offered, metres. */
        private val GRID_SPACINGS = doubleArrayOf(100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0)

        /** Grid lines at least this far apart on paper. */
        const val MIN_GRID_MM = 24.0

        /** The smallest spacing at least [MIN_GRID_MM] apart on paper: 1 km at 1:25,000, 500 m at 1:10,000, 2 km at 1:50,000. */
        fun gridSpacingMeters(scale: Double): Double =
            GRID_SPACINGS.firstOrNull { it / scale * 1000 >= MIN_GRID_MM } ?: GRID_SPACINGS.last()

        private val ARC_SPACINGS = intArrayOf(5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 18_000, 36_000)

        /** The smallest graticule step (seconds of arc) whose meridians are at least [MIN_GRID_MM] apart at [lat]: 30" at 1:25,000 near 47 N. */
        fun graticuleSpacingArcSec(scale: Double, lat: Double): Int {
            val metersPerArcSec = WebMercator.EARTH_CIRCUMFERENCE_M / 360 / 3600 * cos(lat * PI / 180)
            return ARC_SPACINGS.firstOrNull { it * metersPerArcSec / scale * 1000 >= MIN_GRID_MM } ?: ARC_SPACINGS.last()
        }

        /** "1:25,000": the ratio with a thousands comma, as printed under the scale bar. */
        fun ratio(scale: Double): String {
            val digits = scale.roundToLong().toString()
            val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
            return "1:$grouped"
        }

        /**
         * A UTM grid label: the full value with its unit for the first line on an edge ("594000mE"), else the
         * principal digits, the kilometres within the 100 km square ("95"; "94.5" for a spacing under 1 km).
         */
        fun utmLabel(value: Double, spacingM: Double, full: Boolean, suffix: String): String {
            if (full) return "${value.roundToLong()}$suffix"
            val km = (value / 1000) % 100
            return if (spacingM < 1000) UnitFormat.fixed(km, 1).let { if (km < 10) "0$it" else it }
            else km.roundToInt().toString().padStart(2, '0')
        }

        /** Degrees, minutes and seconds with the hemisphere: 46°51'30"N, 121°45'W (seconds left out when zero). */
        fun dms(value: Double, lat: Boolean): String {
            val hemi = if (lat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
            val total = (abs(value) * 3600).roundToLong()
            val d = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return "$d°${m.toString().padStart(2, '0')}'" + (if (s != 0L) "${s.toString().padStart(2, '0')}\"" else "") + hemi
        }

        /** Multiples of [step] in [from], [to]. */
        private fun multiples(from: Double, to: Double, step: Double): List<Double> {
            val first = ceil(from / step - 1e-9).toLong()
            val last = floor(to / step + 1e-9).toLong()
            return (first..last).map { it * step }
        }

        /** Where a polyline crosses one frame edge, as the pixel along that edge; null when it does not within the frame. */
        private fun crossing(xy: DoubleArray, edge: FrameEdge, w: Double, h: Double): Double? {
            for (i in 0 until xy.size / 2 - 1) {
                val x0 = xy[2 * i]; val y0 = xy[2 * i + 1]; val x1 = xy[2 * i + 2]; val y1 = xy[2 * i + 3]
                val (a0, a1, b0, b1, c, limit) = when (edge) {
                    FrameEdge.TOP -> Six(y0, y1, x0, x1, 0.0, w)
                    FrameEdge.BOTTOM -> Six(y0, y1, x0, x1, h, w)
                    FrameEdge.LEFT -> Six(x0, x1, y0, y1, 0.0, h)
                    FrameEdge.RIGHT -> Six(x0, x1, y0, y1, w, h)
                }
                if ((a0 - c) * (a1 - c) > 0 || a0 == a1) continue
                val t = (c - a0) / (a1 - a0)
                val at = b0 + t * (b1 - b0)
                if (at in 0.0..limit) return at
            }
            return null
        }

        private data class Six(val a0: Double, val a1: Double, val b0: Double, val b1: Double, val c: Double, val limit: Double)
    }
}

/**
 * T183: the sheet options a print request carries (devtool's `GET /print`, the phone's `POST /print`): `scale`
 * (a denominator from 500, default 25000, or `fit`: [scale] null), `paper` (`a4|letter|a3`; null: by region),
 * `landscape=1` and `grid` (`utm|latlon|both|none`, default utm). [parse] throws [IllegalArgumentException] with
 * the message a 400 carries.
 */
data class SheetQuery(val scale: Double?, val paper: Paper?, val landscape: Boolean, val grid: SheetGrid) {
    companion object {
        fun parse(q: Map<String, String>): SheetQuery {
            val scaleText = (q["scale"] ?: PageLayout.DEFAULT_SCALE.toLong().toString()).trim().lowercase()
            val scale = if (scaleText == "fit") null else scaleText.toDoubleOrNull()?.takeIf { it >= 500 && it.isFinite() }
                ?: throw IllegalArgumentException("scale must be a denominator of at least 500 or fit (got '$scaleText')")
            val paper = q["paper"]?.let { Paper.parse(it) ?: throw IllegalArgumentException("paper must be a4, letter or a3") }
            val grid = q["grid"]?.let { SheetGrid.parse(it) ?: throw IllegalArgumentException("grid must be utm, latlon, both or none") } ?: SheetGrid.UTM
            val landscape = q["landscape"]?.trim()?.lowercase() in listOf("1", "true", "on", "yes")
            return SheetQuery(scale, paper, landscape, grid)
        }
    }
}

/** T183: a margin font: [sizePt] points on paper, bold or regular (Noto Sans in devtool, the phone's sans serif). */
data class SheetFont(val sizePt: Double, val bold: Boolean)

/** T183: a box in page pixels: [x], [y] its top-left corner, [w] and [h] its size. */
data class BoxPx(val x: Double, val y: Double, val w: Double, val h: Double) {
    val maxX: Double get() = x + w
    val maxY: Double get() = y + h

    /** Whether [o] overlaps this box (touching edges do not count; an empty box overlaps nothing). */
    fun intersects(o: BoxPx): Boolean {
        if (w <= 0 || h <= 0 || o.w <= 0 || o.h <= 0) return false
        return o.x + o.w > x && o.y + o.h > y && o.x < x + w && o.y < y + h
    }
}

/**
 * T183: where [SheetMargin] draws: page pixels from the page's top-left corner, y down, colours as ARGB. devtool
 * draws through AWT, the phone through `android.graphics.Canvas`; text measuring is the platform's, everything
 * else (positions, sizes, texts, the north diagram) is [SheetMargin]'s.
 */
interface SheetCanvas {
    /** [font]'s size in page pixels as the platform holds it (a line of a paragraph is 1.3 of it). */
    fun fontPx(font: SheetFont): Double

    /** The advance width of [text] in [font], page pixels: what a paragraph wraps by. */
    fun advance(text: String, font: SheetFont): Double

    /** Where [text] would be drawn by [text] with these arguments: its ink bounds, unpadded. */
    fun measure(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean): BoxPx

    /**
     * Fills [text] with its baseline at [y]; [align] 0 puts its left edge at [x], 0.5 its middle, 1 its right edge;
     * [rotate] turns it a quarter left about ([x], [y]) (reading upward). Returns its ink bounds padded by 2 px.
     */
    fun text(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean, color: Int): BoxPx

    /** [text] unrotated with a [halo] outline under its [color] fill (labels inside the map). */
    fun haloText(text: String, font: SheetFont, x: Double, y: Double, align: Double, color: Int, halo: Int)

    fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double)

    /** The polyline [xy] (x, y pairs) moved by [dx], [dy]. */
    fun polyline(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double)

    /** A rectangle filled with [fill] and then outlined with [stroke] at [width], either null to skip it. */
    fun rect(x: Double, y: Double, w: Double, h: Double, fill: Int?, stroke: Int?, width: Double)

    /** The closed polygon [xy] (x, y pairs) filled. */
    fun polygon(xy: DoubleArray, color: Int)

    /** Drawing is clipped to this rectangle until [unclip]. */
    fun clip(x: Double, y: Double, w: Double, h: Double)
    fun unclip()
}

/**
 * T183 (moved from devtool's `SheetComposer`, T167): the margins of a printed sheet around its map: the title band
 * (the title and the ratio), the grid over the map with its labels in the band around the frame (UTM lines, or
 * latitude and longitude ticks inside the frame), the frame, and the footer in three columns: the scale bars with
 * the ratio under them and the contour interval; the true, grid and magnetic north diagram with the angles to a
 * degree; the datum, zone, heights, date, sources and the "print at 100 percent" note. devtool draws it over
 * its rendered map, the phone over a MapLibre snapshot; [texts] lists every margin text in drawing order.
 * [contourInterval] and [contourUnits] ("ft" or "m") are the map's; [units] picks the scale bars (feet adds
 * a kilometre bar) and the heights line.
 */
class SheetMargin(
    val layout: PageLayout,
    val title: String,
    val grid: SheetGrid,
    val units: ContourUnits,
    val contourInterval: Double,
    val contourUnits: String,
    val north: NorthAngles,
    /** The print date as printed, YYYY-MM-DD. */
    val date: String,
    val attribution: String,
) {
    val utm: UtmGrid? = if (grid.utm) layout.utmGrid() else null
    val graticule: Graticule? = if (grid.latLon) layout.graticule() else null

    /** Every margin text in drawing order (a paragraph whole), filled by [paint]. */
    val texts = ArrayList<String>()

    private fun mm(v: Double) = layout.px(v)
    private fun pt(v: Double) = v * layout.dpi / 72
    private fun regular(sizePt: Double) = SheetFont(sizePt, false)
    private fun bold(sizePt: Double) = SheetFont(sizePt, true)

    /** Draws the margins and the grid on [c]; the map is already in the frame. */
    fun paint(c: SheetCanvas) {
        texts.clear()
        val fx = layout.frameXPx.toDouble()
        val fy = layout.frameYPx.toDouble()
        val fw = layout.frameWidthPx.toDouble()
        val fh = layout.frameHeightPx.toDouble()

        // Title band: the title on the left, the ratio on the right.
        val titleBase = mm(PageLayout.MARGIN_MM + 7.0)
        text(c, title, bold(14.0), fx, titleBase)
        text(c, layout.ratioText, bold(11.0), fx + fw, titleBase, align = 1.0)

        drawGrid(c, fx, fy, fw, fh)
        c.rect(fx, fy, fw, fh, null, INK, mm(0.35))

        val top = fy + fh + mm(PageLayout.LABEL_BAND_MM + 1.0)
        val colW = fw / 3
        scaleColumn(c, fx, top, colW - mm(4.0))
        northColumn(c, fx + colW, top, colW - mm(3.0))
        notesColumn(c, fx + 2 * colW, top, colW)
    }

    private fun drawGrid(c: SheetCanvas, fx: Double, fy: Double, fw: Double, fh: Double) {
        val labelFont = regular(6.0)
        utm?.let { grid ->
            c.clip(fx, fy, fw, fh)
            for (l in grid.eastings + grid.northings) c.polyline(l.xy, fx, fy, GRID_INK, mm(0.15))
            c.unclip()
            val gap = mm(1.0)
            val last = HashMap<FrameEdge, BoxPx>()
            for (lbl in grid.labels) {
                val (x, y, rotate, align) = when (lbl.edge) {
                    FrameEdge.TOP -> Quad(fx + lbl.at, fy - gap, false, 0.5)
                    FrameEdge.BOTTOM -> Quad(fx + lbl.at, fy + fh + gap + pt(6.0) * 0.72, false, 0.5)
                    FrameEdge.LEFT -> Quad(fx - gap, fy + lbl.at, true, 0.5)
                    FrameEdge.RIGHT -> Quad(fx + fw + gap + pt(6.0) * 0.72, fy + lbl.at, true, 0.5)
                }
                val box = c.measure(lbl.text, labelFont, x, y, align, rotate)
                if (last[lbl.edge]?.intersects(box) == true) continue
                last[lbl.edge] = box
                text(c, lbl.text, labelFont, x, y, align, rotate, record = false)
            }
            texts += grid.labels.map { it.text }.distinct()
        }
        graticule?.let { gr ->
            val w = mm(0.2)
            val tick = mm(2.5)
            for (lon in gr.lons) {
                val x = fx + layout.frameX(lon)
                c.line(x, fy, x, fy + tick, LATLON_INK, w)
                c.line(x, fy + fh, x, fy + fh - tick, LATLON_INK, w)
            }
            for (lat in gr.lats) {
                val y = fy + layout.frameY(lat)
                c.line(fx, y, fx + tick, y, LATLON_INK, w)
                c.line(fx + fw, y, fx + fw - tick, y, LATLON_INK, w)
            }
            // Small crosses where meridians and parallels meet inside the map.
            val arm = mm(1.2)
            for (lon in gr.lons) for (lat in gr.lats) {
                val x = fx + layout.frameX(lon)
                val y = fy + layout.frameY(lat)
                c.line(x - arm, y, x + arm, y, LATLON_INK, w)
                c.line(x, y - arm, x, y + arm, LATLON_INK, w)
            }
            // Labels just inside the frame beside each tick, haloed, so they share no band with UTM labels.
            val font = regular(5.5)
            val inset = tick + mm(0.6)
            for (lbl in gr.labels) {
                when (lbl.edge) {
                    FrameEdge.TOP -> c.haloText(lbl.text, font, fx + lbl.at, fy + inset + pt(5.5) * 0.72, 0.5, LATLON_INK, HALO)
                    FrameEdge.BOTTOM -> c.haloText(lbl.text, font, fx + lbl.at, fy + fh - inset, 0.5, LATLON_INK, HALO)
                    FrameEdge.LEFT -> c.haloText(lbl.text, font, fx + inset, fy + lbl.at + pt(5.5) * 0.36, 0.0, LATLON_INK, HALO)
                    FrameEdge.RIGHT -> c.haloText(lbl.text, font, fx + fw - inset, fy + lbl.at + pt(5.5) * 0.36, 1.0, LATLON_INK, HALO)
                }
            }
            texts += gr.labels.map { it.text }.distinct()
        }
    }

    private data class Quad(val x: Double, val y: Double, val rotate: Boolean, val align: Double)

    /** Scale bars (the sheet's units first, kilometres too on a feet sheet), the ratio under them, the contour interval. */
    private fun scaleColumn(c: SheetCanvas, x: Double, top: Double, width: Double) {
        val metersPerMm = layout.scale / 1000
        val maxMm = width / layout.px(1.0)
        val bars = if (units == ContourUnits.FEET) listOf(ContourUnits.FEET, ContourUnits.METERS) else listOf(ContourUnits.METERS)
        var y = top + mm(2.5)
        for (u in bars) {
            val bar = UnitFormat.scaleBar(metersPerMm, maxMm - 12, u) ?: continue
            scaleBar(c, x, y, mm(bar.px), bar.value, bar.label)
            y += mm(7.0)
        }
        y += pt(12.0) * 0.72 - mm(1.0)
        text(c, layout.ratioText, bold(12.0), x, y)
        y += mm(4.5)
        text(c, contourLine(contourInterval, contourUnits), regular(7.0), x, y)
    }

    /** One bar: alternating black and white blocks, "0" under its start and the length under its end. */
    private fun scaleBar(c: SheetCanvas, x: Double, y: Double, length: Double, value: Double, label: String) {
        val lead = (value / 10.0.pow(floor(log10(value)))).roundToInt()
        val parts = when (lead) { 2 -> 4; else -> 5 }
        val h = mm(1.6)
        for (i in 0 until parts) c.rect(x + length * i / parts, y, length / parts, h, if (i % 2 == 0) INK else WHITE, INK, mm(0.15))
        val f = regular(6.5)
        val base = y + h + mm(0.8) + pt(6.5) * 0.72
        text(c, "0", f, x, base, align = 0.5)
        text(c, label, f, x + length, base, align = 0.5)
    }

    /** True north up, grid north (with a UTM grid) and magnetic north at their angles, small ones opened up to be seen, and the numbers. */
    private fun northColumn(c: SheetCanvas, x: Double, top: Double, width: Double) {
        // The diagram is not to scale, as on printed sheets (the text gives the real angles): grid north opens to at
        // least 10 degrees and magnetic north to at least 3, and 8 more than grid north when both lean the same way,
        // so the labels clear the star, each other's rays and the text.
        val gn = if (utm != null) max(abs(north.convergenceDeg), 10.0) * (if (north.convergenceDeg < 0) -1.0 else 1.0) else null
        var mn = max(abs(north.declinationDeg), 3.0) * (if (north.declinationDeg < 0) -1.0 else 1.0)
        if (gn != null && sign(gn) == sign(mn) && abs(mn) < abs(gn) + 8) mn = (abs(gn) + 8) * sign(mn)
        val baseX = x + mm(if (mn < 0 || (gn ?: 0.0) < 0) 13.0 else 5.0)
        val baseY = top + mm(25.0)
        val len = mm(22.0)
        val stroke = mm(0.25)
        fun ray(angleDeg: Double, length: Double = len): Pair<Double, Double> {
            val a = angleDeg * DEG
            val tx = baseX + length * sin(a)
            val ty = baseY - length * cos(a)
            c.line(baseX, baseY, tx, ty, INK, stroke)
            return tx to ty
        }
        // True north: a star at the tip.
        val (tx, ty) = ray(0.0)
        c.polygon(star(tx, ty - mm(1.6), mm(1.5)), INK)
        val f = bold(6.0)
        var right = baseX + mm(2.0)
        if (gn != null) {
            // Shorter than the others, labelled above its tip.
            val (gx, gy) = ray(gn, mm(15.0))
            right = max(right, text(c, "GN", f, gx, gy - mm(0.8), align = 0.5).maxX)
        }
        val (mx, my) = ray(mn)
        // A half arrowhead on magnetic north.
        val a = mn * DEG
        c.polygon(
            doubleArrayOf(
                mx, my,
                mx - mm(2.2) * sin(a) + mm(0.9) * cos(a), my + mm(2.2) * cos(a) + mm(0.9) * sin(a),
                mx - mm(2.2) * sin(a), my + mm(2.2) * cos(a),
            ),
            INK,
        )
        val mnBox = text(c, "MN", f, mx + mm(0.9) * sign(mn), my + pt(6.0) * 0.36, align = if (mn >= 0) 0.0 else 1.0)
        right = maxOf(right, mnBox.maxX, mx)

        var y = top + mm(1.0) + pt(6.5) * 0.72
        val textX = max(x + mm(17.0), right + mm(2.0))
        for (l in northLines(north, utm != null)) y = paragraph(c, l, regular(6.5), textX, y, x + width - textX)
    }

    private fun star(cx: Double, cy: Double, r: Double): DoubleArray {
        val xy = DoubleArray(20)
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.42
            val a = PI / 5 * i
            xy[2 * i] = cx + rr * sin(a)
            xy[2 * i + 1] = cy - rr * cos(a)
        }
        return xy
    }

    /** Datum and zone, heights, the date, the sources, and the note to print at actual size. */
    private fun notesColumn(c: SheetCanvas, x: Double, top: Double, width: Double) {
        val f = regular(6.5)
        var y = top + mm(1.0) + pt(6.5) * 0.72
        y = paragraph(c, gridLine(), f, x, y, width)
        y = paragraph(c, "Heights in ${if (units == ContourUnits.FEET) "feet" else "metres"} above sea level (EGM2008).", f, x, y, width)
        y = paragraph(c, "Printed $date.", f, x, y, width)
        y = paragraph(c, "Sources: $attribution", regular(5.5), x, y, width)
        paragraph(c, PRINT_NOTE, bold(6.5), x, y + mm(0.5), width)
    }

    /** The grid, datum and zone line of the notes. */
    fun gridLine(): String {
        val u = utm
        val g = graticule
        return when {
            u != null && g != null -> "UTM zone ${u.zoneName}, ${spacing(u.spacingM)} grid; latitude and longitude every ${arc(g.spacingArcSec)}. Datum WGS84."
            u != null -> "UTM zone ${u.zoneName}, ${spacing(u.spacingM)} grid. Datum WGS84."
            g != null -> "Latitude and longitude every ${arc(g.spacingArcSec)}. Datum WGS84. UTM zone ${Utm.zoneFor(layout.centerLon)}."
            else -> "Datum WGS84. UTM zone ${Utm.zoneFor(layout.centerLon)}."
        }
    }

    /** Word-wrapped text from baseline [y]; returns the baseline after it. */
    private fun paragraph(c: SheetCanvas, text: String, font: SheetFont, x: Double, y: Double, width: Double): Double {
        val lineH = c.fontPx(font) * 1.3
        var line = ""
        var base = y
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && c.advance(next, font) > width) {
                text(c, line, font, x, base, record = false)
                base += lineH
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) { text(c, line, font, x, base, record = false); base += lineH }
        texts += text
        return base
    }

    private fun text(
        c: SheetCanvas, s: String, font: SheetFont, x: Double, y: Double, align: Double = 0.0, rotate: Boolean = false, record: Boolean = true,
    ): BoxPx {
        val box = c.text(s, font, x, y, align, rotate, INK)
        if (record) texts += s
        return box
    }

    companion object {
        const val INK = 0xFF1F1A17.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        /** UTM lines: near-black at two thirds. */
        const val GRID_INK = 0xAA101010.toInt()
        /** Latitude and longitude ticks and labels. */
        const val LATLON_INK = 0xFF0B2E6B.toInt()
        const val HALO = 0xDCFFFFFF.toInt()
        private const val DEG = PI / 180.0

        const val PRINT_NOTE = "Print at 100 percent (actual size), not fit to page, or the scale is wrong."

        /** "Contour interval 40 feet". */
        fun contourLine(interval: Double, units: String): String {
            val text = if (interval == interval.roundToLong().toDouble()) interval.roundToLong().toString() else interval.toString()
            return "Contour interval $text ${if (units == "ft") "feet" else "metres"}"
        }

        /** The north diagram's text: magnetic north, and with a UTM grid grid north and the angle between them. */
        fun northLines(north: NorthAngles, utm: Boolean): List<String> {
            val lines = ArrayList<String>()
            lines += "Magnetic north (MN) is ${degrees(north.declinationDeg)} of true north (star)."
            if (utm) {
                lines += "Grid north (GN) is ${degrees(north.convergenceDeg)} of true north."
                lines += "Magnetic north is ${degrees(north.magneticFromGridDeg)} of grid north."
            }
            lines += "At the sheet's centre in ${north.year.toInt()}; the declination changes every year. The diagram is not to scale."
            return lines
        }

        /** "15° east", "1° west", "less than 1° east" (to a degree). */
        fun degrees(v: Double): String {
            val d = v.roundToInt()
            return if (d == 0) "less than 1°" + (if (v >= 0) " east" else " west") else "${abs(d)}° ${if (d > 0) "east" else "west"}"
        }

        /** "1 km", "500 m", "2.5 km": a grid spacing. */
        fun spacing(m: Double) = if (m >= 1000) "${UnitFormat.fixed(m / 1000, if (m % 1000 == 0.0) 0 else 1)} km" else "${m.roundToInt()} m"

        /** "30\"", "1'", "1°": a graticule step. */
        fun arc(s: Int) = when {
            s % 3600 == 0 -> "${s / 3600}°"
            s % 60 == 0 -> "${s / 60}'"
            else -> "$s\""
        }
    }
}
