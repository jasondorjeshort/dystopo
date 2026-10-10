package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.render.ContourLabel
import io.github.jasondorjeshort.dystopo.render.ContourSimplify
import io.github.jasondorjeshort.dystopo.render.TextMeasurer
import io.github.jasondorjeshort.dystopo.render.TextSize
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.LabelPlacer
import io.github.jasondorjeshort.dystopo.style.LineTicks
import io.github.jasondorjeshort.dystopo.style.Marker
import io.github.jasondorjeshort.dystopo.style.OsmScene
import io.github.jasondorjeshort.dystopo.style.OsmStyle
import io.github.jasondorjeshort.dystopo.style.PlacedLabel
import io.github.jasondorjeshort.dystopo.style.SceneLayer
import io.github.jasondorjeshort.dystopo.style.SceneWindow
import io.github.jasondorjeshort.dystopo.style.SourceTile
import io.github.jasondorjeshort.dystopo.style.StyleColor
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import io.github.jasondorjeshort.dystopo.style.TextAdvances
import io.github.jasondorjeshort.dystopo.style.TickSide
import io.github.jasondorjeshort.dystopo.style.Ticks
import kotlinx.serialization.Serializable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

@Serializable
data class OsmLayerStats(
    /** Features that passed the layer's filter (per source tile they appear in). */
    val features: Int,
    /** Rings, merged lines, or label candidates. */
    val pieces: Int,
    /** Vertices after clipping. */
    val vertices: Int,
    val labelsPlaced: Int? = null,
    val labelsDropped: Int? = null,
    /** Time to place this layer's labels. */
    val placeMs: Double? = null,
    /** Time to draw this layer (labels: their share of the label drawing, by count). */
    val drawMs: Double? = null,
)

@Serializable
data class OsmStats(
    val archive: String,
    val style: String,
    /** Zoom of the archive tiles drawn; below the render zoom means overzoom. */
    val tileZoom: Int,
    val tiles: Int,
    val tilesMissing: Int,
    /** Tile bytes after decompression. */
    val tileBytes: Long,
    val labelsPlaced: Int,
    /** Contour labels removed because an OSM label took their place. */
    val contourLabelsDropped: Int,
    /** Line segments with downhill ticks drawn on their left because the DEM says that side is lower (against the OSM way direction). */
    val ticksFlipped: Int = 0,
    /** Line segments where the DEM could not tell the sides apart; they took their whole line's side (else the right, the OSM convention). */
    val ticksUndecided: Int = 0,
    val layers: Map<String, OsmLayerStats>,
)

/** Measures bold italic text (water names), the font [OsmLayer] draws them with. */
object AwtItalicMeasurer : TextMeasurer {
    private val frc = FontRenderContext(null, true, true)

    fun font(fontPx: Double): Font = Font(Font.SANS_SERIF, Font.BOLD or Font.ITALIC, 1).deriveFont(fontPx.toFloat())

    override fun measure(text: String, fontPx: Double): TextSize {
        val bounds = font(fontPx).createGlyphVector(frc, text).visualBounds
        return TextSize(bounds.width, bounds.height)
    }
}

/** Per-character advances in a font, for curved labels drawn one character at a time. */
class AwtAdvances(private val font: (Double) -> Font) : TextAdvances {
    private val frc = FontRenderContext(null, true, true)

    override fun advances(text: String, fontPx: Double): DoubleArray {
        val f = font(fontPx)
        val gv = f.createGlyphVector(frc, text)
        // One glyph per char for the scripts we label; otherwise measure each char alone.
        if (gv.numGlyphs == text.length) return DoubleArray(text.length) { gv.getGlyphMetrics(it).advance.toDouble() }
        return DoubleArray(text.length) { f.createGlyphVector(frc, text.substring(it, it + 1)).getGlyphMetrics(0).advance.toDouble() }
    }
}

/**
 * OpenStreetMap vector data from a PMTiles archive (OpenMapTiles schema), styled by an [OsmStyle]
 * and drawn over the terrain. Fills and lines draw below or above the contour lines as the style
 * says; labels draw last, and contour labels that would collide with an OSM label are dropped.
 */
class OsmLayer private constructor(
    private val scene: OsmScene,
    /** The placed OSM labels, in drawing order (goldens record them). */
    val labels: List<PlacedLabel>,
    private val textScale: Double,
    private val baseStats: OsmStats,
    private val placer: LabelPlacer,
    /** Elevation at an image pixel position (bilinear), NaN outside; for downhill tick sides. */
    private val elevation: (Double, Double) -> Float,
) {
    private val drawMs = LinkedHashMap<String, Double>()
    private var contourLabelsDropped = 0
    private var ticksFlipped = 0
    private var ticksUndecided = 0

    val stats: OsmStats
        get() = baseStats.copy(
            contourLabelsDropped = contourLabelsDropped,
            ticksFlipped = ticksFlipped,
            ticksUndecided = ticksUndecided,
            layers = baseStats.layers.mapValues { (id, s) -> s.copy(drawMs = drawMs[id]?.let { (it * 100).roundToInt() / 100.0 }) },
        )

    companion object {
        /** Null when `--osm` is not set. Needs the render's [window] to place tiles. */
        fun build(style: StyleOptions, window: MercatorWindow?, grid: io.github.jasondorjeshort.dystopo.dem.ElevationGrid, timings: Timings, prefix: String = ""): OsmLayer? {
            val file = style.osm ?: return null
            requireNotNull(window) { "--osm needs the map window; it is supported by 'render' only" }
            require(window.width == grid.width && window.height == grid.height) { "grid does not match the window" }
            val osmStyle = timings.time("${prefix}osmStyle") {
                style.osmStyle?.let { OsmStyle.parse(it.readText(Charsets.UTF_8)) } ?: DefaultOsmStyle.style
            }
            val reader = timings.time("${prefix}osmOpen") { PmtilesReader.open(FileRangeSource(file), Gzip.decompressors) }
            require(reader.header.tileType == TileType.MVT) { "${file.path} holds ${reader.header.tileType} tiles, not vector tiles" }
            val tileZoom = window.zoom.coerceIn(reader.header.minZoom, reader.header.maxZoom)
            val ids = io.github.jasondorjeshort.dystopo.style.TilePlacement.tilesFor(
                tileZoom, window.zoom, window.x0, window.y0, window.width, window.height, window.tileSize,
            )
            var missing = 0
            var bytes = 0L
            val raw = timings.time("${prefix}osmRead") {
                ids.mapNotNull { id ->
                    val b = reader.tile(id.z, id.x, id.y)
                    if (b == null) missing++ else bytes += b.size
                    b?.let { id to it }
                }
            }
            val tiles = timings.time("${prefix}osmDecode") { raw.map { (id, b) -> SourceTile(id, VectorTile.decode(b)) } }
            val sceneWindow = SceneWindow(window.zoom, window.x0, window.y0, window.width, window.height, window.tileSize)
            val scene = timings.time("${prefix}osmScene") { OsmScene.build(osmStyle, tiles, sceneWindow, style.units) }
            val advances = AwtAdvances(AwtTextMeasurer::font)
            val italicAdvances = AwtAdvances(AwtItalicMeasurer::font)
            // First use of a font face in AWT loads it (slow for the italic face); keep that out of osmLabels.
            timings.time("${prefix}osmFontInit") {
                advances.advances("Ag", 12.0)
                italicAdvances.advances("Ag", 12.0)
                AwtItalicMeasurer.measure("Ag", 12.0)
            }
            val placer = LabelPlacer(
                AwtTextMeasurer, window.width, window.height, window.zoom.toDouble(), style.textScale, AwtItalicMeasurer,
                advances, italicAdvances,
            )
            val labels = if (style.noLabels) emptyList() else timings.time("${prefix}osmLabels") { placer.placeAll(scene.labels) }

            val layerStats = LinkedHashMap<String, OsmLayerStats>()
            for (layer in osmStyle.layers) {
                if (layer is StyleLayer.Contours) continue
                val c = scene.counts[layer.id] ?: continue
                val lc = placer.counts[layer.id]
                layerStats[layer.id] = OsmLayerStats(
                    features = c.features,
                    pieces = c.pieces,
                    vertices = c.vertices,
                    labelsPlaced = if (layer is StyleLayer.Symbol) lc?.placed ?: 0 else null,
                    labelsDropped = if (layer is StyleLayer.Symbol) lc?.dropped ?: 0 else null,
                    placeMs = lc?.let { it.micros / 10 / 100.0 },
                )
            }
            val stats = OsmStats(
                archive = file.path,
                style = style.osmStyle?.path ?: "built-in:${osmStyle.name}",
                tileZoom = tileZoom,
                tiles = ids.size,
                tilesMissing = missing,
                tileBytes = bytes,
                labelsPlaced = labels.size,
                contourLabelsDropped = 0,
                layers = layerStats,
            )
            return OsmLayer(scene, labels, style.textScale, stats, placer, LineTicks.bilinear(grid.width, grid.height) { i, j -> grid[i, j] })
        }
    }

    /** Draws everything: OSM fills and lines around the contour lines, then contour labels, then OSM labels. */
    fun draw(image: BufferedImage, contours: ContourLayer) {
        val split = scene.layers.indexOfFirst { it is SceneLayer.Contours }.let { if (it < 0) scene.layers.size else it }
        val g2 = graphics(image)
        for (layer in scene.layers.subList(0, split)) drawLayer(g2, layer)
        g2.dispose()
        ContourLayer(contours.spec, contours.lines, emptyList(), contours.stats).draw(image)
        val g3 = graphics(image)
        for (layer in scene.layers.subList(minOf(split + 1, scene.layers.size), scene.layers.size)) drawLayer(g3, layer)
        g3.dispose()
        val kept = contourLabelsKept(contours.labels)
        contourLabelsDropped = contours.labels.size - kept.size
        ContourLayer(contours.spec, emptyList(), kept, contours.stats).draw(image)
        val g4 = graphics(image)
        val start = System.nanoTime()
        for (label in labels) drawLabel(g4, label)
        val perLabel = (System.nanoTime() - start) / 1e6
        // Label drawing is shared; attribute it by count.
        if (labels.isNotEmpty()) labels.groupingBy { it.style.id }.eachCount().forEach { (id, n) -> drawMs[id] = perLabel * n / labels.size }
        g4.dispose()
    }

    /** Contour labels give way to OSM labels (peak names and trail names matter more): the ones that stay. */
    fun contourLabelsKept(labels: List<ContourLabel>): List<ContourLabel> = labels.filter { !placer.collides(it.corners(2.0)) }

    private fun graphics(image: BufferedImage): Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun color(c: StyleColor, opacity: Double = 1.0) = Color(c.r, c.g, c.b, (c.a * opacity).roundToInt().coerceIn(0, 255))

    private fun drawLayer(g2: Graphics2D, layer: SceneLayer) {
        val start = System.nanoTime()
        val zoom = scene.window.zoom.toDouble()
        when (layer) {
            is SceneLayer.Fill -> {
                val path = Path2D.Double(Path2D.WIND_NON_ZERO)
                for (ring in layer.rings) {
                    path.moveTo(ring[0], ring[1])
                    for (i in 1 until ring.size / 2) path.lineTo(ring[2 * i], ring[2 * i + 1])
                    path.closePath()
                }
                g2.color = color(layer.style.color, layer.style.opacity.at(zoom))
                g2.fill(path)
            }
            is SceneLayer.Line -> {
                val s = layer.style
                val path = Path2D.Double()
                for (line in layer.lines) {
                    path.moveTo(line[0], line[1])
                    for (i in 1 until line.size / 2) path.lineTo(line[2 * i], line[2 * i + 1])
                }
                val width = (s.width.at(zoom) * layer.widthScale).toFloat()
                val opacity = (s.opacity.at(zoom) * layer.opacityScale).coerceIn(0.0, 1.0)
                val casing = s.casingWidth.at(zoom).toFloat()
                if (s.casingColor != null && casing > 0) {
                    g2.color = color(s.casingColor!!, opacity)
                    g2.stroke = BasicStroke(width + 2 * casing, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(path)
                }
                g2.color = color(s.color, opacity)
                val dash = s.dash
                g2.stroke = if (dash.isNullOrEmpty()) {
                    BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                } else {
                    val d = FloatArray(dash.size) { maxOf(0.1f, (dash[it] * width).toFloat()) }
                    BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, d, 0f)
                }
                g2.draw(path)
                s.ticks?.let { t -> drawTicks(g2, layer, t, zoom) }
            }
            is SceneLayer.Contours -> {}
        }
        drawMs[layer.style.id] = (System.nanoTime() - start) / 1e6
    }

    /** Tick hatching on each line; [TickSide.DOWNHILL] asks the DEM which side is lower, per segment. */
    private fun drawTicks(g2: Graphics2D, layer: SceneLayer.Line, t: Ticks, zoom: Double) {
        val length = t.length.at(zoom)
        val spacing = t.spacing.at(zoom)
        val ticks = Path2D.Double()
        for (line in layer.lines) {
            // Raw cliff ways zig-zag at the pixel scale; ticks follow a lightly simplified copy.
            val simple = ContourSimplify.simplify(line, false, 0.75)
            val segs = when (t.side) {
                TickSide.RIGHT -> LineTicks.ticks(simple, spacing, length, right = true)
                TickSide.LEFT -> LineTicks.ticks(simple, spacing, length, right = false)
                TickSide.DOWNHILL -> {
                    val d = LineTicks.downhillTicks(simple, spacing, length, length + 2.0, elevation)
                    ticksFlipped += d.segmentsLeft
                    ticksUndecided += d.segmentsUndecided
                    d.ticks
                }
            }
            for (seg in segs) {
                ticks.moveTo(seg[0], seg[1])
                ticks.lineTo(seg[2], seg[3])
            }
        }
        g2.stroke = BasicStroke((t.width.at(zoom) * layer.widthScale).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g2.draw(ticks)
    }

    private fun drawMarker(g2: Graphics2D, label: PlacedLabel, m: Marker, halo: Color) =
        MarkerShapes.draw(g2, m, label.markerX, label.markerY, label.markerPx, halo)

    private fun drawLabel(g2: Graphics2D, label: PlacedLabel) {
        val s = label.style
        val halo = color(s.haloColor)
        val haloStroke = BasicStroke((2 * s.haloWidth * textScale).toFloat().coerceAtLeast(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        label.marker?.let { drawMarker(g2, label, it, halo) }
        val frc = g2.fontRenderContext
        val font = if (s.italic) AwtItalicMeasurer.font(label.fontPx) else AwtTextMeasurer.font(label.fontPx)
        label.glyphs?.let { glyphs ->
            // Curved label: halos for every glyph first, so a halo never covers a neighbor's ink.
            val shapes = glyphs.map { gl ->
                val gv = font.createGlyphVector(frc, gl.text)
                val t = AffineTransform()
                t.translate(gl.x, gl.y)
                t.rotate(gl.angle)
                t.translate(-gv.logicalBounds.width / 2, label.fontPx * 0.36)
                t.createTransformedShape(gv.outline)
            }
            g2.color = halo
            g2.stroke = haloStroke
            shapes.forEach { g2.draw(it) }
            g2.color = color(s.color)
            shapes.forEach { g2.fill(it) }
            return
        }
        val n = label.lines.size
        for ((i, text) in label.lines.withIndex()) {
            val glyphs = font.createGlyphVector(frc, text)
            val ink = glyphs.visualBounds
            val t = AffineTransform()
            t.translate(label.x, label.y)
            t.rotate(label.angle)
            t.translate(0.0, (i - (n - 1) / 2.0) * label.lineAdvance)
            // Center horizontally on the ink; vertically on the cap height so lines share a baseline grid.
            t.translate(-ink.centerX, label.fontPx * 0.36)
            val shape = t.createTransformedShape(glyphs.outline)
            g2.color = halo
            g2.stroke = haloStroke
            g2.draw(shape)
            g2.color = color(s.color)
            g2.fill(shape)
        }
    }
}
