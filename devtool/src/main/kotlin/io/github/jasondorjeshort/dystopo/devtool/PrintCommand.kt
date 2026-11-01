package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.render.ContourLabel
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.BoxPx
import io.github.jasondorjeshort.dystopo.render.SheetCanvas
import io.github.jasondorjeshort.dystopo.render.SheetFont
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import io.github.jasondorjeshort.dystopo.render.SheetMargin
import io.github.jasondorjeshort.dystopo.render.UnitRegions
import io.github.jasondorjeshort.dystopo.render.TripReport
import io.github.jasondorjeshort.dystopo.render.TripReportPage
import io.github.jasondorjeshort.dystopo.render.TripSummary
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.PaceFits
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.GregorianCalendar
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * T167: one printed map sheet, as asked for on the command line (`devtool print`) or by `GET /print`
 * ([PrintRoutes]). [scale] null means "fit the view": the sheet fits [fitBox] and prints the ratio it used.
 */
data class SheetRequest(
    val title: String,
    val centerLat: Double,
    val centerLon: Double,
    val scale: Double? = PageLayout.DEFAULT_SCALE,
    val fitBox: BoundingBox? = null,
    /** Null: by region, Letter in the US and A4 elsewhere (ADR 0022). */
    val paper: Paper? = null,
    val landscape: Boolean = false,
    val grid: SheetGrid = SheetGrid.UTM,
    /** Null: by region, feet in the US and metres elsewhere. */
    val units: ContourUnits? = null,
    val dpi: Double = PageLayout.DEFAULT_DPI,
    val source: String = "best",
    /** The vector archive drawn over the terrain (trails, water, names), or null for terrain only. */
    val osm: File? = null,
    val gpx: GpxDocument? = null,
    /** Label size, 1.0 being 12 style pixels (about 6 pt on paper). */
    val textScale: Double = 1.2,
    /** The print date: the declination is for it and the sheet says it. */
    val epochMs: Long = System.currentTimeMillis(),
    val area: String? = null,
)

@Serializable
data class SheetGpxStats(val trackPoints: Int, val routePoints: Int, val waypointsDrawn: Int, val waypointsOutside: Int)

/** The JSON report of a sheet: what it shows and how long each step took. */
@Serializable
data class PrintReport(
    val title: String,
    val area: String?,
    val scale: Double,
    val ratio: String,
    val fit: Boolean,
    val paper: String,
    val landscape: Boolean,
    /** The page's pixels per inch: the nominal dpi moved by the window's tile size rounding so the scale is exact. */
    val dpi: Double,
    val nominalDpi: Double,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    /** x, y, width, height of the map frame on the paper, mm. */
    val frameMm: List<Double>,
    /** west, south, east, north of the map frame. */
    val extent: List<Double>,
    /** Ground width and height of the frame at the centre, metres. */
    val groundMeters: List<Double>,
    val center: List<Double>,
    val zoom: Int,
    val tileSize: Int,
    val styleScale: Double,
    val grid: String,
    val utmZone: String?,
    val gridSpacingM: Double?,
    val gridLines: Int,
    val graticuleArcSec: Int?,
    val declinationDeg: Double,
    val convergenceDeg: Double,
    val magneticFromGridDeg: Double,
    val date: String,
    val units: String,
    val contourInterval: Double,
    val contourUnits: String,
    val contours: ContourStats,
    val elevationMinMeters: Double?,
    val elevationMaxMeters: Double?,
    val noData: Boolean,
    val provenance: List<String>,
    val attribution: String,
    val osm: OsmStats?,
    val gpx: SheetGpxStats?,
    /** Every text printed outside the map, in drawing order: what a reader of the PNG should find. */
    val margin: List<String>,
    val sourceCounters: Map<String, Long>,
    var pdf: String? = null,
    var pdfBytes: Long? = null,
    var pdfPages: Int? = null,
    var png: String? = null,
    var pngBytes: Long? = null,
    val timingsMs: Map<String, Long>,
)

/** A composed sheet: the page image, its layout and report, and (for the golden) where text and map labels were drawn. */
class PrintedSheet(
    val layout: PageLayout,
    val page: BufferedImage,
    val report: PrintReport,
    /** Text drawn by the composer, page pixels: the golden compares these loosely. */
    val textBoxes: List<Rectangle2D>,
    /** Contour labels drawn on the map, frame pixels. */
    val mapLabels: List<ContourLabel>,
) {
    fun png(): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(page, "png", it)) { "no PNG writer" } }.toByteArray()

    fun pdf(): ByteArray = PrintSheet.pdf(page, layout, report.title)
}

/** T167: makes a printed sheet: plans the page, renders the map through [MapRenderer], draws the margins, writes the PDF. */
object PrintSheet {
    /** Fetches the elevation for [req] and makes its sheet. */
    fun make(req: SheetRequest, timings: Timings = Timings()): PrintedSheet {
        val paper = req.paper ?: Paper.defaultAt(req.centerLat, req.centerLon)
        val scale = req.scale ?: PageLayout.fitScale(requireNotNull(req.fitBox) { "fit needs a box" }, paper, req.landscape)
        val layout = timings.time("plan") { PageLayout.plan(paper, req.landscape, scale, req.centerLat, req.centerLon, req.dpi) }
        val window = window(layout)
        require(window.width.toLong() * window.height <= MAX_MAP_PIXELS) { "the map would be ${window.width}x${window.height} px; lower the dpi" }
        val src = ElevationSource.byId(req.source)
        val fetched = timings.time("fetch") { src.fetch(window) }
        val attribution = fetched.attribution(src) + (if (req.osm != null) " " + SourceRegistry.builtIn.attribution(LiveTiles.OSM_PACK_REGISTRY_IDS) else "")
        return fromGrid(req.copy(paper = paper), layout, fetched.grid, attribution, fetched.provenance, fetched.counters, timings)
    }

    /** The map window of [layout]: one pixel per page pixel. */
    fun window(layout: PageLayout) =
        MercatorWindow(layout.zoom, layout.windowX0, layout.windowY0, layout.frameWidthPx, layout.frameHeightPx, layout.tileSize)

    /** Bigger maps than this (an A3 at 300 dpi is 14 M) are refused: the server renders them in memory. */
    const val MAX_MAP_PIXELS = 30_000_000L

    /** The renderer's options for a sheet: text and lines at [PageLayout.styleScale], the sheet's units and archive. */
    fun style(layout: PageLayout, req: SheetRequest, units: ContourUnits): StyleOptions {
        val args = mutableListOf(
            "--units", units.symbol,
            "--text-scale", (req.textScale * layout.styleScale).toString(),
            "--line-scale", layout.styleScale.toString(),
            // Steep-slope thinning and simplification are in pixels: keep them at the style's density.
            "--min-spacing", (3.0 * layout.styleScale).toString(),
            "--simplify", (0.5 * layout.styleScale).toString(),
        )
        req.osm?.let { args += listOf("--osm", it.path) }
        val holder = object : CliktCommand(name = "print-style") {
            val style by StyleOptions()
            override fun run() = Unit
        }
        holder.parse(args)
        return holder.style
    }

    /** The sheet over an elevation [grid] already in [layout]'s window (the golden passes a synthetic one). */
    fun fromGrid(
        req: SheetRequest,
        layout: PageLayout,
        grid: ElevationGrid,
        attribution: String,
        provenance: List<String>,
        counters: Map<String, Long> = emptyMap(),
        timings: Timings = Timings(),
    ): PrintedSheet {
        val units = req.units ?: UnitRegions.contourUnitsAt(req.centerLat, req.centerLon)
        val window = window(layout)
        val style = style(layout, req, units)
        val render = MapRenderer.render(grid, layout.zoom, style, timings, window = window)
        val overlay = SheetOverlay(layout, req.textScale * layout.styleScale)
        val gpx = req.gpx?.let { doc -> timings.time("drawGpx") { overlay.draw(render.image, doc) } }
        val labels = ContourLayer.build(grid, layout.zoom, style, Timings()).labels
        val north = layout.north(req.epochMs)
        val date = Instant.ofEpochMilli(req.epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val composer = SheetComposer(layout, req, units, render.contours, north, date, attribution)
        val page = timings.time("compose") { composer.compose(render.image) }
        val b = layout.bbox
        val r2 = { v: Double -> (v * 100).roundToLong() / 100.0 }
        val report = PrintReport(
            title = req.title,
            area = req.area,
            scale = layout.scale,
            ratio = layout.ratioText,
            fit = req.scale == null,
            paper = layout.paper.key,
            landscape = layout.landscape,
            dpi = r2(layout.dpi),
            nominalDpi = layout.nominalDpi,
            pageWidthPx = layout.pageWidthPx,
            pageHeightPx = layout.pageHeightPx,
            frameMm = listOf(layout.frameMm.x, layout.frameMm.y, layout.frameMm.w, layout.frameMm.h),
            extent = listOf(b.west, b.south, b.east, b.north).map { (it * 1e5).roundToLong() / 1e5 },
            groundMeters = listOf(layout.groundWidthM.roundToLong().toDouble(), layout.groundHeightM.roundToLong().toDouble()),
            center = listOf(layout.centerLat, layout.centerLon).map { (it * 1e5).roundToLong() / 1e5 },
            zoom = layout.zoom,
            tileSize = layout.tileSize,
            styleScale = layout.styleScale,
            grid = req.grid.key,
            utmZone = composer.utm?.zoneName,
            gridSpacingM = composer.utm?.spacingM,
            gridLines = (composer.utm?.let { it.eastings.size + it.northings.size } ?: 0),
            graticuleArcSec = composer.graticule?.spacingArcSec,
            declinationDeg = (north.declinationDeg * 10).roundToLong() / 10.0,
            convergenceDeg = (north.convergenceDeg * 100).roundToLong() / 100.0,
            magneticFromGridDeg = (north.magneticFromGridDeg * 10).roundToLong() / 10.0,
            date = date,
            units = units.symbol,
            contourInterval = render.contours.interval,
            contourUnits = render.contours.units,
            contours = render.contours,
            elevationMinMeters = grid.finiteMin(),
            elevationMaxMeters = grid.finiteMax(),
            noData = grid.finiteMin() == null,
            provenance = provenance,
            attribution = attribution,
            osm = render.osm,
            gpx = gpx,
            margin = composer.texts,
            sourceCounters = counters,
            timingsMs = timings.ms,
        )
        val offset = { r: Rectangle2D -> Rectangle2D.Double(r.x + layout.frameXPx - 3, r.y + layout.frameYPx - 3, r.width + 6, r.height + 6) }
        return PrintedSheet(layout, page, report, composer.boxes + overlay.labelBoxes.map(offset), labels)
    }

    /**
     * The sheet as a one-page PDF of [layout]'s paper size: the page image as one JPEG at the page's dpi, placed
     * at the exact paper size so the scale holds, with the viewer preference that asks print dialogs for actual
     * size (no fit to page).
     */
    fun pdf(page: BufferedImage, layout: PageLayout, title: String, quality: Float = 0.92f): ByteArray =
        PDDocument().use { doc ->
            val w = (layout.pageWidthMm / PageLayout.MM_PER_INCH * 72).toFloat()
            val h = (layout.pageHeightMm / PageLayout.MM_PER_INCH * 72).toFloat()
            val p = PDPage(PDRectangle(w, h))
            doc.addPage(p)
            val image = JPEGFactory.createFromImage(doc, page, quality, layout.dpi.roundToInt())
            PDPageContentStream(doc, p).use { it.drawImage(image, 0f, 0f, w, h) }
            doc.documentInformation.title = title
            doc.documentInformation.creator = "Dystopo"
            doc.documentInformation.creationDate = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            doc.documentCatalog.viewerPreferences = PDViewerPreferences(COSDictionary()).apply {
                setPrintScaling(PDViewerPreferences.PRINT_SCALING.None)
            }
            val out = ByteArrayOutputStream()
            doc.save(out, CompressParameters.NO_COMPRESSION) // plain objects: page count and preferences readable without a parser
            out.toByteArray()
        }

    /** The page count of a PDF, read back with PDFBox. */
    fun pdfPages(bytes: ByteArray): Int = org.apache.pdfbox.Loader.loadPDF(bytes).use { it.numberOfPages }

    /** The first vector archive among [packs] whose bounds hold the point: the sheet's trails, water and names. */
    fun osmPackAt(lat: Double, lon: Double, packs: List<File>): File? = packs.firstOrNull { f ->
        runCatching {
            val h = PmtilesReader.open(FileRangeSource(f), Gzip.decompressors).header
            lat in h.minLat..h.maxLat && lon in h.minLon..h.maxLon
        }.getOrDefault(false)
    }

    /** Output stem for a sheet: out/<name>-sheet-<ratio>-<paper>. */
    fun stem(name: String, layout: PageLayout): String =
        "${name.replace(Regex("[^A-Za-z0-9._-]+"), "-")}-sheet-${if (layout.scale == layout.scale.roundToLong().toDouble()) layout.scale.roundToLong() else layout.scale}-${layout.paper.key}${if (layout.landscape) "-landscape" else ""}"
}

/** The route, tracks and waypoints of a GPX file on a sheet's map, at the sheet's style scale [k]. */
class SheetOverlay(private val layout: PageLayout, private val k: Double) {
    private val trackColor = Color(0x8e, 0x24, 0xe0)
    private val routeColor = Color(0xe6, 0x7e, 0x22)
    private val waypointColor = Color(0x1d, 0x4e, 0xd8)

    /** Where waypoint names were drawn, frame pixels (the golden compares them loosely). */
    val labelBoxes = ArrayList<Rectangle2D>()

    fun draw(map: BufferedImage, doc: GpxDocument): SheetGpxStats {
        val g = map.createGraphics()
        hints(g)
        val w = map.width.toDouble()
        val h = map.height.toDouble()
        fun line(points: List<Pair<Double, Double>>, color: Color, width: Double, dashed: Boolean) {
            if (points.size < 2) return
            val p = Path2D.Double()
            points.forEachIndexed { i, (lat, lon) -> if (i == 0) p.moveTo(layout.frameX(lon), layout.frameY(lat)) else p.lineTo(layout.frameX(lon), layout.frameY(lat)) }
            g.color = Color(255, 255, 255, 220)
            g.stroke = BasicStroke(((width + 2.5) * k).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
            g.color = color
            val sw = (width * k).toFloat()
            g.stroke = if (dashed) BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(3 * sw, 2 * sw), 0f)
            else BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
        }
        for (r in doc.routes) line(r.points.map { it.lat to it.lon }, routeColor, 3.0, dashed = true)
        val segments = doc.tracks.flatMap { t -> t.segments.map { s -> s.points.map { it.lat to it.lon } } }
        for (s in segments) line(s, trackColor, 3.0, dashed = false)
        var drawn = 0
        var outside = 0
        val font = RenderFonts.bold(9.5 * k)
        for (wp in doc.waypoints) {
            val x = layout.frameX(wp.lon)
            val y = layout.frameY(wp.lat)
            if (x < 0 || y < 0 || x >= w || y >= h) { outside++; continue }
            val r = 4.0 * k
            val dot = Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r)
            g.color = Color.WHITE
            g.stroke = BasicStroke((2 * k).toFloat())
            g.draw(dot)
            g.color = waypointColor
            g.fill(dot)
            if (wp.name.isNotEmpty()) {
                val shape = font.createGlyphVector(g.fontRenderContext, wp.name).getOutline((x + r + 2 * k).toFloat(), (y + 3.4 * k).toFloat())
                g.color = Color(255, 255, 255, 230)
                g.stroke = BasicStroke((2.4 * k).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(shape)
                g.color = Color(0x1f, 0x1a, 0x17)
                g.fill(shape)
                labelBoxes += shape.bounds2D
            }
            drawn++
        }
        g.dispose()
        return SheetGpxStats(segments.sumOf { it.size }, doc.routes.sumOf { it.points.size }, drawn, outside)
    }
}

internal fun hints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
}

/**
 * Draws a sheet's page around its rendered map: the map in the frame, then the margins `shared`'s [SheetMargin]
 * lays out (T183: moved there from here so the phone draws the same sheet), through [AwtSheetCanvas]. [texts]
 * lists every margin text in drawing order, [boxes] where text was drawn (the golden compares them loosely).
 */
class SheetComposer(
    private val layout: PageLayout,
    req: SheetRequest,
    units: ContourUnits,
    contours: ContourStats,
    north: io.github.jasondorjeshort.dystopo.render.NorthAngles,
    date: String,
    attribution: String,
) {
    private val margin = SheetMargin(layout, req.title, req.grid, units, contours.interval, contours.units, north, date, attribution)
    val utm get() = margin.utm
    val graticule get() = margin.graticule
    val texts: List<String> get() = margin.texts
    val boxes = ArrayList<Rectangle2D>()

    fun compose(map: BufferedImage): BufferedImage {
        val page = BufferedImage(layout.pageWidthPx, layout.pageHeightPx, BufferedImage.TYPE_INT_RGB)
        val g = page.createGraphics()
        hints(g)
        g.color = Color.WHITE
        g.fillRect(0, 0, page.width, page.height)
        g.drawImage(map, layout.frameXPx, layout.frameYPx, null)
        margin.paint(AwtSheetCanvas(g, layout, boxes))
        g.dispose()
        return page
    }
}

/**
 * T183: [SheetCanvas] on AWT, with the bundled Noto Sans ([RenderFonts]) at the page's dpi. Records every text's
 * box (padded by 2 px, 3 px with a halo) in [boxes].
 */
class AwtSheetCanvas(private val g: Graphics2D, private val layout: PageLayout, private val boxes: MutableList<Rectangle2D>) : SheetCanvas {
    private val fonts = HashMap<SheetFont, Font>()

    private fun font(f: SheetFont): Font = fonts.getOrPut(f) {
        val px = f.sizePt * layout.dpi / 72
        if (f.bold) RenderFonts.bold(px) else RenderFonts.regular(px)
    }

    private fun color(argb: Int) = Color(argb, true)

    override fun fontPx(font: SheetFont): Double = font(font).size2D.toDouble()

    override fun advance(text: String, font: SheetFont): Double = font(font).getStringBounds(text, g.fontRenderContext).width

    override fun measure(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean): BoxPx {
        val b = font(font).createGlyphVector(g.fontRenderContext, text).visualBounds
        return box(transform(x, y, b.width, align, rotate).createTransformedShape(b).bounds2D)
    }

    override fun text(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean, color: Int): BoxPx {
        val gv = font(font).createGlyphVector(g.fontRenderContext, text)
        val width = gv.logicalBounds.width
        val shape = transform(x, y, width, align, rotate).createTransformedShape(gv.getOutline(0f, 0f))
        g.color = color(color)
        g.fill(shape)
        val r = shape.bounds2D.let { Rectangle2D.Double(it.x - 2, it.y - 2, it.width + 4, it.height + 4) }
        boxes += r
        return box(r)
    }

    override fun haloText(text: String, font: SheetFont, x: Double, y: Double, align: Double, color: Int, halo: Int) {
        val f = font(font)
        val gv = f.createGlyphVector(g.fontRenderContext, text)
        val shape = transform(x, y, gv.logicalBounds.width, align, false).createTransformedShape(gv.getOutline(0f, 0f))
        g.color = color(halo)
        g.stroke = BasicStroke((f.size2D * 0.25).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = color(color)
        g.fill(shape)
        boxes += shape.bounds2D.let { Rectangle2D.Double(it.x - 3, it.y - 3, it.width + 6, it.height + 6) }
    }

    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) {
        g.color = color(color)
        g.stroke = BasicStroke(width.toFloat())
        g.draw(Line2D.Double(x0, y0, x1, y1))
    }

    override fun polyline(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) {
        val p = Path2D.Double()
        for (i in 0 until xy.size / 2) if (i == 0) p.moveTo(dx + xy[0], dy + xy[1]) else p.lineTo(dx + xy[2 * i], dy + xy[2 * i + 1])
        g.color = color(color)
        g.stroke = BasicStroke(width.toFloat())
        g.draw(p)
    }

    override fun roundLine(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) {
        if (xy.size < 4) return
        val p = Path2D.Double()
        for (i in 0 until xy.size / 2) if (i == 0) p.moveTo(dx + xy[0], dy + xy[1]) else p.lineTo(dx + xy[2 * i], dy + xy[2 * i + 1])
        g.color = color(color)
        g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(p)
    }

    override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Int?, stroke: Int?, width: Double) {
        val r = Rectangle2D.Double(x, y, w, h)
        fill?.let { g.color = color(it); g.fill(r) }
        stroke?.let {
            g.color = color(it)
            g.stroke = BasicStroke(width.toFloat())
            g.draw(r)
        }
    }

    override fun polygon(xy: DoubleArray, color: Int) {
        val p = Path2D.Double()
        for (i in 0 until xy.size / 2) if (i == 0) p.moveTo(xy[0], xy[1]) else p.lineTo(xy[2 * i], xy[2 * i + 1])
        p.closePath()
        g.color = color(color)
        g.fill(p)
    }

    override fun clip(x: Double, y: Double, w: Double, h: Double) {
        g.clip = Rectangle2D.Double(x, y, w, h)
    }

    override fun unclip() {
        g.clip = null
    }

    private fun box(r: Rectangle2D) = BoxPx(r.x, r.y, r.width, r.height)

    private fun transform(x: Double, y: Double, width: Double, align: Double, rotate: Boolean) = AffineTransform().apply {
        translate(x, y)
        if (rotate) rotate(-Math.PI / 2)
        translate(-width * align, 0.0)
    }
}

/** T198: a made trip report: the page image, its layout, the summary behind it and its JSON report. */
class PrintedReport(
    val layout: PageLayout,
    val page: BufferedImage,
    val summary: TripSummary,
    val report: LinkedHashMap<String, Any?>,
    /** Text drawn by the page, page pixels: the golden compares these loosely. */
    val textBoxes: List<Rectangle2D>,
    /** Contour labels drawn on the map, frame pixels. */
    val mapLabels: List<ContourLabel>,
) {
    val title: String get() = report["title"] as String

    fun png(): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(page, "png", it)) { "no PNG writer" } }.toByteArray()

    fun pdf(): ByteArray = PrintSheet.pdf(page, layout, title)

    fun json(): String = JsonWriter.write(report)
}

/**
 * T198: the trip report of a track as devtool makes it (`devtool print --gpx <file> --report`): `shared`'s [TripReport]
 * plans the page and works out the figures, the map is [MapRenderer]'s as on the sheet (T167), and [TripReportPage]
 * draws the track, the profile strip and the margin through [AwtSheetCanvas]. The PDF is [PrintSheet.pdf]'s.
 */
object TripReportSheet {
    data class Request(
        val track: Track,
        val title: String,
        /** Null: by region, Letter in the US and A4 elsewhere. */
        val paper: Paper? = null,
        val landscape: Boolean = false,
        /** Null: by region, feet in the US and metres elsewhere. */
        val units: ContourUnits? = null,
        val dpi: Double = PageLayout.DEFAULT_DPI,
        val source: String = "best",
        val osm: File? = null,
        /** The walker's fits (`--pace-fit`); a hike without one is held against the standard estimate. */
        val fits: Map<TravelMode, PaceFit> = emptyMap(),
        val textScale: Double = 1.2,
        val epochMs: Long = System.currentTimeMillis(),
        /** The zone the walk's day and the print date are read in. */
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    /** The units [req] asks for, else by region at the track's first point. */
    fun unitsOf(req: Request): ContourUnits =
        req.units ?: req.track.points.firstOrNull()?.let { UnitRegions.contourUnitsAt(it.lat, it.lon) } ?: ContourUnits.METERS

    /** Plans the page, fetches the elevation for its frame and makes the report; [packs] are searched for a vector archive when [Request.osm] is null. */
    fun make(req: Request, packs: List<File>, timings: Timings = Timings()): PrintedReport {
        val units = unitsOf(req)
        val summary = timings.time("summary") { TripReport.summarize(req.track, units, req.fits) }
        val layout = timings.time("plan") { TripReport.plan(summary, req.paper, req.landscape, req.dpi) }
        val window = PrintSheet.window(layout)
        require(window.width.toLong() * window.height <= PrintSheet.MAX_MAP_PIXELS) { "the map would be ${window.width}x${window.height} px; lower the dpi" }
        val osm = req.osm ?: PrintSheet.osmPackAt(layout.centerLat, layout.centerLon, packs)
        val src = ElevationSource.byId(req.source)
        val fetched = timings.time("fetch") { src.fetch(window) }
        val attribution = fetched.attribution(src) + (if (osm != null) " " + SourceRegistry.builtIn.attribution(LiveTiles.OSM_PACK_REGISTRY_IDS) else "")
        return fromGrid(req.copy(units = units, osm = osm), summary, layout, fetched.grid, attribution, fetched.provenance, fetched.counters, timings)
    }

    /** The report over an elevation [grid] already in [layout]'s window (the golden passes a synthetic one). */
    fun fromGrid(
        req: Request,
        summary: TripSummary,
        layout: PageLayout,
        grid: ElevationGrid,
        attribution: String,
        provenance: List<String>,
        counters: Map<String, Long> = emptyMap(),
        timings: Timings = Timings(),
    ): PrintedReport {
        val units = summary.units
        val window = PrintSheet.window(layout)
        val sheetReq = SheetRequest(
            title = req.title, centerLat = layout.centerLat, centerLon = layout.centerLon, paper = layout.paper, landscape = layout.landscape,
            units = units, dpi = req.dpi, osm = req.osm, textScale = req.textScale, epochMs = req.epochMs,
        )
        val style = PrintSheet.style(layout, sheetReq, units)
        val render = MapRenderer.render(grid, layout.zoom, style, timings, window = window)
        val labels = ContourLayer.build(grid, layout.zoom, style, Timings()).labels
        val printed = Instant.ofEpochMilli(req.epochMs).atZone(req.zone).toLocalDate().toString()
        val walked = summary.stats.startTime?.let { Instant.ofEpochMilli(it).atZone(req.zone).toLocalDate().toString() }
        val pageDraw = TripReportPage(
            layout, summary, req.title, walked, printed, attribution, SheetMargin.contourLine(render.contours.interval, render.contours.units),
        )
        val boxes = ArrayList<Rectangle2D>()
        val page = timings.time("compose") {
            val image = BufferedImage(layout.pageWidthPx, layout.pageHeightPx, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            hints(g)
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(render.image, layout.frameXPx, layout.frameYPx, null)
            pageDraw.paint(AwtSheetCanvas(g, layout, boxes))
            g.dispose()
            image
        }
        val b = layout.bbox
        val r5 = { v: Double -> (v * 1e5).roundToLong() / 1e5 }
        val report = linkedMapOf<String, Any?>(
            "title" to req.title,
            "track" to summary.name,
            "walked" to walked,
            "scale" to layout.scale,
            "ratio" to layout.ratioText,
            "paper" to layout.paper.key,
            "landscape" to layout.landscape,
            "dpi" to (layout.dpi * 100).roundToLong() / 100.0,
            "nominalDpi" to layout.nominalDpi,
            "pageWidthPx" to layout.pageWidthPx,
            "pageHeightPx" to layout.pageHeightPx,
            "frameMm" to listOf(layout.frameMm.x, layout.frameMm.y, layout.frameMm.w, layout.frameMm.h),
            "extent" to listOf(b.west, b.south, b.east, b.north).map(r5),
            "center" to listOf(layout.centerLat, layout.centerLon).map(r5),
            "zoom" to layout.zoom,
            "contourInterval" to render.contours.interval,
            "contourUnits" to render.contours.units,
        )
        report.putAll(summary.json())
        report["stopLabels"] = pageDraw.stopLabels
        report["profileBoxPx"] = pageDraw.profileBox?.let { listOf(it.x, it.y, it.w, it.h).map { v -> v.roundToLong() } }
        report["figuresBoxPx"] = pageDraw.figuresBox?.let { listOf(it.x, it.y, it.w, it.h).map { v -> v.roundToLong() } }
        report["margin"] = pageDraw.texts.toList()
        report["printed"] = printed
        report["provenance"] = provenance
        report["attribution"] = attribution
        report["osmArchive"] = req.osm?.name
        report["elevationMinMeters"] = grid.finiteMin()
        report["elevationMaxMeters"] = grid.finiteMax()
        report["sourceCounters"] = counters
        report["timingsMs"] = timings.ms
        return PrintedReport(layout, page, summary, report, boxes, labels)
    }

    /**
     * The track a GPX file's report is of: its first track with points (every segment), else its first route as one
     * segment without times; [fallbackName] names it when the file does not.
     */
    fun trackOf(doc: GpxDocument, fallbackName: String): Track? {
        doc.tracks.firstOrNull { t -> t.segments.any { it.points.isNotEmpty() } }?.let { t ->
            return t.copy(name = t.name.ifBlank { fallbackName }, segments = t.segments.filter { it.points.isNotEmpty() })
        }
        val r = doc.routes.firstOrNull { it.points.size >= 2 } ?: return null
        return Track("route", r.name.ifBlank { fallbackName }, listOf(TrackSegment(r.points.map { TrackPoint(it.lat, it.lon, it.ele, it.time) })))
    }

    /** Output stem for a report: out/<name>-report-<paper>. */
    fun stem(name: String, layout: PageLayout): String =
        "${name.replace(Regex("[^A-Za-z0-9._-]+"), "-")}-report-${layout.paper.key}${if (layout.landscape) "-landscape" else ""}"
}

/**
 * `devtool print`: a map sheet to print, as a PDF and a PNG of the page in `out/`, with a JSON report (T167).
 * T198: `--gpx <file> --report` makes the trip report of the file's track instead ([TripReportSheet]).
 */
class PrintCommand : CliktCommand(name = "print") {
    override fun help(context: Context) =
        "Make a printable map sheet (PDF and PNG of the page, 300 dpi) for an area or a centre point at a true scale, with a UTM grid, " +
            "scale bars, the north diagram, the contour interval, the sources and the date. Prints a JSON report. " +
            "Print the PDF at 100 percent (actual size), not fit to page."

    private val area by option("--area", help = "Named area (see 'devtool areas'): the sheet is centred on it, or fits it with --scale fit")
    private val bbox by option("--bbox", help = "west,south,east,north in degrees, instead of --area")
    private val center by option("--center", help = "lat,lon of the sheet's centre, instead of --area or --bbox")
    private val scaleText by option("--scale", help = "Scale denominator: 25000 (default, 1:25,000), 10000, 50000 or any other, or 'fit' to fit the area")
        .default("25000")
    private val paperText by option("--paper", help = "a4, letter or a3 (default: Letter in the US, A4 elsewhere)").choice(*Paper.entries.map { it.key }.toTypedArray())
    private val landscape by option("--landscape", help = "Landscape instead of portrait").flag()
    private val grid by option("--grid", help = "utm (default, 1 km at 1:25,000), latlon (ticks), both, or none").choice(*SheetGrid.entries.map { it.key }.toTypedArray()).default("utm")
    private val gpx by option("--gpx", help = "GPX file: its tracks, routes and waypoints in view are drawn").file(mustExist = true, canBeDir = false)
    private val title by option("--title", help = "Title (default: the area's description up to its first full stop, else its name)")
    private val unitsText by option("--units", help = "ft or m (default: feet in the US, metres elsewhere)").choice("ft", "m")
    private val osm by option("--osm", help = "Vector archive for trails, water and names (default: a pack in out/ that covers the centre)").file(mustExist = true, canBeDir = false)
    private val noOsm by option("--no-osm", help = "Terrain only").flag()
    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val dpi by option("--dpi", help = "Page resolution").double().default(PageLayout.DEFAULT_DPI)
    private val textScale by option("--text-scale", help = "Label size; 1.0 is about 6 pt on paper").double().default(1.2)
    private val out by option("--out", help = "Output stem (default out/<area>-sheet-<scale>-<paper>); .pdf and .png are added")
    private val report by option(
        "--report",
        help = "With --gpx: the trip report of its first track instead of a map sheet (the map fitted to the track, the elevation profile with the stops, the day's figures)",
    ).flag()
    private val paceFit by option(
        "--pace-fit",
        help = "With --report: the walker's fit as the phone stores it (the app.paceFit setting); default the standard estimate",
    )

    override fun run() {
        if (report) return runReport()
        val timings = Timings()
        val fit = scaleText.trim().lowercase() == "fit"
        val scale = if (fit) null else scaleText.trim().replace(",", "").toDoubleOrNull()?.takeIf { it >= 500 }
            ?: throw UsageError("--scale must be a denominator of at least 500 (25000 for 1:25,000) or 'fit'")
        val (name, box, lat, lon) = where()
        if (fit && box == null) throw UsageError("--scale fit needs --area or --bbox")
        val areaDef = name?.let { Areas.load()[it] }
        val sheetTitle = title ?: areaDef?.description?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: name ?: "Map sheet"
        val packs = LiveTiles.findPacks(emptyList()).second
        val archive = when {
            noOsm -> null
            osm != null -> osm
            else -> PrintSheet.osmPackAt(lat, lon, packs)
        }
        val req = SheetRequest(
            title = sheetTitle, centerLat = lat, centerLon = lon, scale = scale, fitBox = box,
            paper = paperText?.let { Paper.parse(it) }, landscape = landscape, grid = SheetGrid.parse(grid)!!,
            units = unitsText?.let { ContourUnits.parse(it) }, dpi = dpi, source = source, osm = archive,
            gpx = gpx?.let { GpxCommand.load(it).document }, textScale = textScale, area = name,
        )
        val sheet = PrintSheet.make(req, timings)
        val stem = out ?: "out/${PrintSheet.stem(name ?: "map", sheet.layout)}"
        val pdfFile = File("$stem.pdf")
        val pngFile = File("$stem.png")
        pdfFile.absoluteFile.parentFile.mkdirs()
        val pdf = timings.time("writePdf") { sheet.pdf().also { pdfFile.writeBytes(it) } }
        timings.time("writePng") { pngFile.writeBytes(sheet.png()) }
        sheet.report.apply {
            this.pdf = pdfFile.path
            pdfBytes = pdfFile.length()
            pdfPages = PrintSheet.pdfPages(pdf)
            png = pngFile.path
            pngBytes = pngFile.length()
        }
        echo(reportJson.encodeToString(sheet.report.copy(timingsMs = LinkedHashMap(timings.ms))))
    }

    /** T198: `--gpx <file> --report`: the trip report's PDF and PNG in out/ and its JSON, every margin figure listed. */
    private fun runReport() {
        val timings = Timings()
        val file = gpx ?: throw UsageError("--report needs --gpx <track>")
        if (area != null || bbox != null || center != null) throw UsageError("--report fits the map to the track; leave out --area, --bbox and --center")
        val doc = timings.time("readGpx") { GpxCommand.load(file).document }
        val track = TripReportSheet.trackOf(doc, file.nameWithoutExtension) ?: throw UsageError("${file.path} has no track or route with points")
        val fits = paceFit?.let { v ->
            val stored = PaceFits.decode(v) ?: throw UsageError("--pace-fit is not a fit this build reads (got '$v')")
            stored.fits.filter { it.ready }.associateBy { it.mode }
        } ?: emptyMap()
        val req = TripReportSheet.Request(
            track = track, title = title ?: track.name, paper = paperText?.let { Paper.parse(it) }, landscape = landscape,
            units = unitsText?.let { ContourUnits.parse(it) }, dpi = dpi, source = source, osm = if (noOsm) null else osm,
            fits = fits, textScale = textScale,
        )
        val packs = if (noOsm) emptyList() else LiveTiles.findPacks(emptyList()).second
        val made = TripReportSheet.make(req, packs, timings)
        val stem = out ?: "out/${TripReportSheet.stem(file.nameWithoutExtension, made.layout)}"
        val pdfFile = File("$stem.pdf")
        val pngFile = File("$stem.png")
        pdfFile.absoluteFile.parentFile.mkdirs()
        val pdf = timings.time("writePdf") { made.pdf().also { pdfFile.writeBytes(it) } }
        timings.time("writePng") { pngFile.writeBytes(made.png()) }
        made.report["fits"] = fits.keys.map { it.label }
        made.report["pdf"] = pdfFile.path
        made.report["pdfBytes"] = pdfFile.length()
        made.report["pdfPages"] = PrintSheet.pdfPages(pdf)
        made.report["png"] = pngFile.path
        made.report["pngBytes"] = pngFile.length()
        made.report["timingsMs"] = LinkedHashMap(timings.ms)
        echo(made.json())
    }

    private data class Where(val name: String?, val box: BoundingBox?, val lat: Double, val lon: Double)

    private fun where(): Where {
        center?.let { c ->
            if (area != null || bbox != null) throw UsageError("use --center, --area or --bbox, not several")
            val p = c.split(",").map { it.trim().toDoubleOrNull() }
            if (p.size != 2 || p.any { it == null }) throw UsageError("--center must be lat,lon")
            return Where(null, null, p[0]!!, p[1]!!)
        }
        val (name, box) = Areas.resolve(area, bbox)
        val (lat, lon) = PageLayout.center(box)
        return Where(name, box, lat, lon)
    }
}
