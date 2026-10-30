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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
