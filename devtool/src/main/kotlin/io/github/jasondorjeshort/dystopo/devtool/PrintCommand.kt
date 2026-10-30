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
import io.github.jasondorjeshort.dystopo.render.FrameEdge
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.render.UnitRegions
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
import java.awt.Shape
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign

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
 * Draws a sheet's page around its rendered map: the title band, the grid over the map with its labels in the
 * band around the frame, the frame, and the footer: the scale bars with the ratio under them and the contour
 * interval; the true, grid and magnetic north diagram with the angles to a degree; the datum, zone, date,
 * sources and the "print at 100 percent" note. [texts] lists every margin text in drawing order.
 */
class SheetComposer(
    private val layout: PageLayout,
    private val req: SheetRequest,
    private val units: ContourUnits,
    private val contours: ContourStats,
    private val north: io.github.jasondorjeshort.dystopo.render.NorthAngles,
    private val date: String,
    private val attribution: String,
) {
    val utm = if (req.grid.utm) layout.utmGrid() else null
    val graticule = if (req.grid.latLon) layout.graticule() else null
    val texts = ArrayList<String>()
    val boxes = ArrayList<Rectangle2D>()

    private val ink = Color(0x1f, 0x1a, 0x17)
    private val gridInk = Color(0x10, 0x10, 0x10, 170)
    private fun mm(v: Double) = layout.px(v)
    private fun pt(v: Double) = v * layout.dpi / 72
    private fun regular(sizePt: Double): Font = RenderFonts.regular(pt(sizePt))
    private fun bold(sizePt: Double): Font = RenderFonts.bold(pt(sizePt))

    fun compose(map: BufferedImage): BufferedImage {
        val page = BufferedImage(layout.pageWidthPx, layout.pageHeightPx, BufferedImage.TYPE_INT_RGB)
        val g = page.createGraphics()
        hints(g)
        g.color = Color.WHITE
        g.fillRect(0, 0, page.width, page.height)
        val fx = layout.frameXPx.toDouble()
        val fy = layout.frameYPx.toDouble()
        val fw = layout.frameWidthPx.toDouble()
        val fh = layout.frameHeightPx.toDouble()
        g.drawImage(map, layout.frameXPx, layout.frameYPx, null)

        // Title band: the title on the left, the ratio on the right.
        val titleBase = mm(PageLayout.MARGIN_MM + 7.0)
        text(g, req.title, bold(14.0), fx, titleBase)
        text(g, layout.ratioText, bold(11.0), fx + fw, titleBase, align = 1.0)

        drawGrid(g, fx, fy, fw, fh)
        g.color = ink
        g.stroke = BasicStroke(mm(0.35).toFloat())
        g.draw(Rectangle2D.Double(fx, fy, fw, fh))

        val top = fy + fh + mm(PageLayout.LABEL_BAND_MM + 1.0)
        val colW = fw / 3
        scaleColumn(g, fx, top, colW - mm(4.0))
        northColumn(g, fx + colW, top, colW - mm(3.0))
        notesColumn(g, fx + 2 * colW, top, colW)
        g.dispose()
        return page
    }

    private fun drawGrid(g: Graphics2D, fx: Double, fy: Double, fw: Double, fh: Double) {
        val frame = Rectangle2D.Double(fx, fy, fw, fh)
        val labelFont = regular(6.0)
        utm?.let { grid ->
            g.clip = frame
            g.color = gridInk
            g.stroke = BasicStroke(mm(0.15).toFloat())
            for (l in grid.eastings + grid.northings) {
                val p = Path2D.Double()
                for (i in 0 until l.xy.size / 2) if (i == 0) p.moveTo(fx + l.xy[0], fy + l.xy[1]) else p.lineTo(fx + l.xy[2 * i], fy + l.xy[2 * i + 1])
                g.draw(p)
            }
            g.clip = null
            g.color = ink
            val gap = mm(1.0)
            val last = HashMap<FrameEdge, Rectangle2D>()
            for (lbl in grid.labels) {
                val (x, y, rotate, align) = when (lbl.edge) {
                    FrameEdge.TOP -> Quad(fx + lbl.at, fy - gap, false, 0.5)
                    FrameEdge.BOTTOM -> Quad(fx + lbl.at, fy + fh + gap + pt(6.0) * 0.72, false, 0.5)
                    FrameEdge.LEFT -> Quad(fx - gap, fy + lbl.at, true, 0.5)
                    FrameEdge.RIGHT -> Quad(fx + fw + gap + pt(6.0) * 0.72, fy + lbl.at, true, 0.5)
                }
                val box = measure(g, lbl.text, labelFont, x, y, align, rotate)
                if (last[lbl.edge]?.intersects(box) == true) continue
                last[lbl.edge] = box
                text(g, lbl.text, labelFont, x, y, align, rotate, record = false)
            }
            texts += grid.labels.map { it.text }.distinct()
        }
        graticule?.let { gr ->
            g.color = Color(0x0b, 0x2e, 0x6b)
            g.stroke = BasicStroke(mm(0.2).toFloat())
            val tick = mm(2.5)
            for (lon in gr.lons) {
                val x = fx + layout.frameX(lon)
                g.draw(Line2D.Double(x, fy, x, fy + tick))
                g.draw(Line2D.Double(x, fy + fh, x, fy + fh - tick))
            }
            for (lat in gr.lats) {
                val y = fy + layout.frameY(lat)
                g.draw(Line2D.Double(fx, y, fx + tick, y))
                g.draw(Line2D.Double(fx + fw, y, fx + fw - tick, y))
            }
            // Small crosses where meridians and parallels meet inside the map.
            val arm = mm(1.2)
            for (lon in gr.lons) for (lat in gr.lats) {
                val x = fx + layout.frameX(lon)
                val y = fy + layout.frameY(lat)
                g.draw(Line2D.Double(x - arm, y, x + arm, y))
                g.draw(Line2D.Double(x, y - arm, x, y + arm))
            }
            // Labels just inside the frame beside each tick, haloed, so they share no band with UTM labels.
            val font = regular(5.5)
            val inset = tick + mm(0.6)
            for (lbl in gr.labels) {
                when (lbl.edge) {
                    FrameEdge.TOP -> haloText(g, lbl.text, font, fx + lbl.at, fy + inset + pt(5.5) * 0.72, 0.5)
                    FrameEdge.BOTTOM -> haloText(g, lbl.text, font, fx + lbl.at, fy + fh - inset, 0.5)
                    FrameEdge.LEFT -> haloText(g, lbl.text, font, fx + inset, fy + lbl.at + pt(5.5) * 0.36, 0.0)
                    FrameEdge.RIGHT -> haloText(g, lbl.text, font, fx + fw - inset, fy + lbl.at + pt(5.5) * 0.36, 1.0)
                }
            }
            texts += gr.labels.map { it.text }.distinct()
        }
    }

    private data class Quad(val x: Double, val y: Double, val rotate: Boolean, val align: Double)

    /** Scale bars (the sheet's units first, kilometres too on a feet sheet), the ratio under them, the contour interval. */
    private fun scaleColumn(g: Graphics2D, x: Double, top: Double, width: Double) {
        val metersPerMm = layout.scale / 1000
        val maxMm = width / layout.px(1.0)
        val bars = if (units == ContourUnits.FEET) listOf(ContourUnits.FEET, ContourUnits.METERS) else listOf(ContourUnits.METERS)
        var y = top + mm(2.5)
        for (u in bars) {
            val bar = UnitFormat.scaleBar(metersPerMm, maxMm - 12, u) ?: continue
            scaleBar(g, x, y, mm(bar.px), bar.value, bar.label)
            y += mm(7.0)
        }
        y += pt(12.0) * 0.72 - mm(1.0)
        text(g, layout.ratioText, bold(12.0), x, y)
        y += mm(4.5)
        val interval = contours.interval.let { if (it == it.roundToLong().toDouble()) it.roundToLong().toString() else it.toString() }
        val word = if (contours.units == "ft") "feet" else "metres"
        text(g, "Contour interval $interval $word", regular(7.0), x, y)
    }

    /** One bar: alternating black and white blocks, "0" under its start and the length under its end. */
    private fun scaleBar(g: Graphics2D, x: Double, y: Double, length: Double, value: Double, label: String) {
        val lead = (value / Math.pow(10.0, Math.floor(Math.log10(value)))).roundToInt()
        val parts = when (lead) { 2 -> 4; else -> 5 }
        val h = mm(1.6)
        g.stroke = BasicStroke(mm(0.15).toFloat())
        for (i in 0 until parts) {
            val r = Rectangle2D.Double(x + length * i / parts, y, length / parts, h)
            g.color = if (i % 2 == 0) ink else Color.WHITE
            g.fill(r)
            g.color = ink
            g.draw(r)
        }
        val f = regular(6.5)
        val base = y + h + mm(0.8) + pt(6.5) * 0.72
        text(g, "0", f, x, base, align = 0.5)
        text(g, label, f, x + length, base, align = 0.5)
    }

    /** True north up, grid north (with a UTM grid) and magnetic north at their angles, small ones opened up to be seen, and the numbers. */
    private fun northColumn(g: Graphics2D, x: Double, top: Double, width: Double) {
        // The diagram is not to scale, as on printed sheets (the text gives the real angles): grid north opens to at
        // least 10 degrees and magnetic north to at least 3, and 8 more than grid north when both lean the same way,
        // so the labels clear the star, each other's rays and the text.
        val gn = if (utm != null) maxOf(abs(north.convergenceDeg), 10.0) * (if (north.convergenceDeg < 0) -1.0 else 1.0) else null
        var mn = maxOf(abs(north.declinationDeg), 3.0) * (if (north.declinationDeg < 0) -1.0 else 1.0)
        if (gn != null && sign(gn) == sign(mn) && abs(mn) < abs(gn) + 8) mn = (abs(gn) + 8) * sign(mn)
        val baseX = x + mm(if (mn < 0 || (gn ?: 0.0) < 0) 13.0 else 5.0)
        val baseY = top + mm(25.0)
        val len = mm(22.0)
        g.color = ink
        g.stroke = BasicStroke(mm(0.25).toFloat())
        fun ray(angleDeg: Double, length: Double = len): Pair<Double, Double> {
            val a = Math.toRadians(angleDeg)
            val tx = baseX + length * Math.sin(a)
            val ty = baseY - length * Math.cos(a)
            g.draw(Line2D.Double(baseX, baseY, tx, ty))
            return tx to ty
        }
        // True north: a star at the tip.
        val (tx, ty) = ray(0.0)
        g.fill(star(tx, ty - mm(1.6), mm(1.5)))
        val f = bold(6.0)
        var right = baseX + mm(2.0)
        if (gn != null) {
            // Shorter than the others, labelled above its tip.
            val (gx, gy) = ray(gn, mm(15.0))
            text(g, "GN", f, gx, gy - mm(0.8), align = 0.5)
            right = maxOf(right, boxes.last().maxX)
        }
        val (mx, my) = ray(mn)
        // A half arrowhead on magnetic north.
        val a = Math.toRadians(mn)
        val head = Path2D.Double().apply {
            moveTo(mx, my)
            lineTo(mx - mm(2.2) * Math.sin(a) + mm(0.9) * Math.cos(a), my + mm(2.2) * Math.cos(a) + mm(0.9) * Math.sin(a))
            lineTo(mx - mm(2.2) * Math.sin(a), my + mm(2.2) * Math.cos(a))
            closePath()
        }
        g.fill(head)
        text(g, "MN", f, mx + mm(0.9) * sign(mn), my + pt(6.0) * 0.36, align = if (mn >= 0) 0.0 else 1.0)
        right = maxOf(right, boxes.last().maxX, mx)

        val year = north.year.toInt()
        val lines = ArrayList<String>()
        lines += "Magnetic north (MN) is ${degrees(north.declinationDeg)} of true north (star)."
        if (utm != null) {
            lines += "Grid north (GN) is ${degrees(north.convergenceDeg)} of true north."
            lines += "Magnetic north is ${degrees(north.magneticFromGridDeg)} of grid north."
        }
        lines += "At the sheet's centre in $year; the declination changes every year. The diagram is not to scale."
        var y = top + mm(1.0) + pt(6.5) * 0.72
        val textX = maxOf(x + mm(17.0), right + mm(2.0))
        for (l in lines) y = paragraph(g, l, regular(6.5), textX, y, x + width - textX)
    }

    /** "15° east", "1° west", "0°" (to a degree). */
    private fun degrees(v: Double): String {
        val d = v.roundToInt()
        return if (d == 0) "less than 1°" + (if (v >= 0) " east" else " west") else "${abs(d)}° ${if (d > 0) "east" else "west"}"
    }

    private fun star(cx: Double, cy: Double, r: Double): Shape = Path2D.Double().apply {
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.42
            val a = Math.PI / 5 * i
            val px = cx + rr * Math.sin(a)
            val py = cy - rr * Math.cos(a)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        closePath()
    }

    /** Datum and zone, heights, the date, the sources, and the note to print at actual size. */
    private fun notesColumn(g: Graphics2D, x: Double, top: Double, width: Double) {
        val f = regular(6.5)
        var y = top + mm(1.0) + pt(6.5) * 0.72
        val gridLine = when {
            utm != null && graticule != null -> "UTM zone ${utm.zoneName}, ${spacing(utm.spacingM)} grid; latitude and longitude every ${arc(graticule.spacingArcSec)}. Datum WGS84."
            utm != null -> "UTM zone ${utm.zoneName}, ${spacing(utm.spacingM)} grid. Datum WGS84."
            graticule != null -> "Latitude and longitude every ${arc(graticule.spacingArcSec)}. Datum WGS84. UTM zone ${io.github.jasondorjeshort.dystopo.geo.Utm.zoneFor(layout.centerLon)}."
            else -> "Datum WGS84. UTM zone ${io.github.jasondorjeshort.dystopo.geo.Utm.zoneFor(layout.centerLon)}."
        }
        y = paragraph(g, gridLine, f, x, y, width)
        y = paragraph(g, "Heights in ${if (units == ContourUnits.FEET) "feet" else "metres"} above sea level (EGM2008).", f, x, y, width)
        y = paragraph(g, "Printed $date.", f, x, y, width)
        y = paragraph(g, "Sources: $attribution", regular(5.5), x, y, width)
        paragraph(g, "Print at 100 percent (actual size), not fit to page, or the scale is wrong.", bold(6.5), x, y + mm(0.5), width)
    }

    private fun spacing(m: Double) = if (m >= 1000) "${UnitFormat.fixed(m / 1000, if (m % 1000 == 0.0) 0 else 1)} km" else "${m.roundToInt()} m"
    private fun arc(s: Int) = when {
        s % 3600 == 0 -> "${s / 3600}°"
        s % 60 == 0 -> "${s / 60}'"
        else -> "$s\""
    }

    /** Word-wrapped text from baseline [y]; returns the baseline after it. */
    private fun paragraph(g: Graphics2D, text: String, font: Font, x: Double, y: Double, width: Double): Double {
        val frc = g.fontRenderContext
        val lineH = font.size2D * 1.3
        var line = ""
        var base = y
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && font.getStringBounds(next, frc).width > width) {
                drawLine(g, line, font, x, base)
                base += lineH
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) { drawLine(g, line, font, x, base); base += lineH }
        texts += text
        return base
    }

    private fun drawLine(g: Graphics2D, s: String, font: Font, x: Double, y: Double) = text(g, s, font, x, y, record = false)

    private fun measure(g: Graphics2D, s: String, font: Font, x: Double, y: Double, align: Double, rotate: Boolean): Rectangle2D {
        val b = font.createGlyphVector(g.fontRenderContext, s).visualBounds
        val t = transform(x, y, b.width, align, rotate)
        return t.createTransformedShape(b).bounds2D
    }

    private fun transform(x: Double, y: Double, width: Double, align: Double, rotate: Boolean) = AffineTransform().apply {
        translate(x, y)
        if (rotate) rotate(-Math.PI / 2)
        translate(-width * align, 0.0)
    }

    /** Draws [s] with its baseline at [y]; [align] 0 puts its left edge at [x], 0.5 its middle, 1 its right edge. */
    private fun text(g: Graphics2D, s: String, font: Font, x: Double, y: Double, align: Double = 0.0, rotate: Boolean = false, record: Boolean = true) {
        val gv = font.createGlyphVector(g.fontRenderContext, s)
        val width = gv.logicalBounds.width
        val shape = transform(x, y, width, align, rotate).createTransformedShape(gv.getOutline(0f, 0f))
        g.color = ink
        g.fill(shape)
        boxes += shape.bounds2D.let { Rectangle2D.Double(it.x - 2, it.y - 2, it.width + 4, it.height + 4) }
        if (record) texts += s
    }

    private fun haloText(g: Graphics2D, s: String, font: Font, x: Double, y: Double, align: Double) {
        val gv = font.createGlyphVector(g.fontRenderContext, s)
        val shape = transform(x, y, gv.logicalBounds.width, align, false).createTransformedShape(gv.getOutline(0f, 0f))
        g.color = Color(255, 255, 255, 220)
        g.stroke = BasicStroke((font.size2D * 0.25).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = Color(0x0b, 0x2e, 0x6b)
        g.fill(shape)
        boxes += shape.bounds2D.let { Rectangle2D.Double(it.x - 3, it.y - 3, it.width + 6, it.height + 6) }
    }
}

/**
 * `devtool print`: a map sheet to print, as a PDF and a PNG of the page in `out/`, with a JSON report (T167).
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

    override fun run() {
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
        val stem = out ?: "out/${PrintSheet.stem(name ?: "sheet", sheet.layout)}"
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
