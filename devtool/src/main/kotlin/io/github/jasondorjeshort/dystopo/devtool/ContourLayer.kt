package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.render.ContourLabel
import io.github.jasondorjeshort.dystopo.render.ContourLabels
import io.github.jasondorjeshort.dystopo.render.ContourLine
import io.github.jasondorjeshort.dystopo.render.ContourSimplify
import io.github.jasondorjeshort.dystopo.render.ContourSpec
import io.github.jasondorjeshort.dystopo.render.ContourStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Contours
import io.github.jasondorjeshort.dystopo.render.LabelParams
import io.github.jasondorjeshort.dystopo.render.TextMeasurer
import io.github.jasondorjeshort.dystopo.render.TextSize
import kotlinx.serialization.Serializable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/** Contour counters for JSON reports. */
@Serializable
data class ContourStats(
    val interval: Double,
    val units: String,
    val indexEvery: Int,
    val levels: Int,
    /** Lines traced, before any cutting. */
    val linesTraced: Int,
    /** Lines (or pieces) drawn after steep-slope cutting. */
    val lines: Int,
    val indexLines: Int,
    val pointsTraced: Int,
    val pointsAfterCut: Int,
    val pointsAfterSimplify: Int,
    /** Points actually drawn, after optional smoothing. */
    val pointsDrawn: Int,
    val labels: Int,
) {
    companion object {
        fun empty(spec: ContourSpec) = ContourStats(spec.interval, spec.units.symbol, spec.indexEvery, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/** Contour lines and labels for one grid, ready to draw. */
class ContourLayer(val spec: ContourSpec, val lines: List<ContourLine>, val labels: List<ContourLabel>, val stats: ContourStats) {
    companion object {
        fun empty(spec: ContourSpec) = ContourLayer(spec, emptyList(), emptyList(), ContourStats.empty(spec))

        fun spec(zoom: Int, style: StyleOptions): ContourSpec =
            ContourStyle.resolve(zoom, ContourUnits.parse(style.units), style.interval, style.indexEvery)

        /** Traces, cuts, simplifies, smooths and labels. Grid coordinates are image pixels (1 sample per pixel). */
        fun build(grid: ElevationGrid, zoom: Int, style: StyleOptions, timings: Timings, prefix: String = ""): ContourLayer {
            val spec = spec(zoom, style)
            if (style.noContours) return empty(spec)
            val levels = spec.levelsMeters(grid.min().toDouble(), grid.max().toDouble())
            val traced = timings.time("${prefix}contours") { Contours.trace(grid, levels) }
            val cut = timings.time("${prefix}contourCut") { Contours.cutSteep(traced, grid, spec, style.minSpacing) }
            val simplified = timings.time("${prefix}contourSimplify") { ContourSimplify.simplifyAll(cut, style.simplify) }
            val smoothed = timings.time("${prefix}contourSmooth") { ContourSimplify.smoothAll(simplified, style.smooth) }
            val labels = if (style.noLabels) emptyList() else timings.time("${prefix}contourLabels") {
                ContourLabels.place(simplified, spec, AwtTextMeasurer, LabelParams(textScale = style.textScale), grid.width, grid.height)
            }
            val stats = ContourStats(
                interval = spec.interval,
                units = spec.units.symbol,
                indexEvery = spec.indexEvery,
                levels = levels.size,
                linesTraced = traced.size,
                lines = smoothed.size,
                indexLines = smoothed.count { spec.isIndex(it.level) },
                pointsTraced = traced.sumOf { it.pointCount },
                pointsAfterCut = cut.sumOf { it.pointCount },
                pointsAfterSimplify = simplified.sumOf { it.pointCount },
                pointsDrawn = smoothed.sumOf { it.pointCount },
                labels = labels.size,
            )
            return ContourLayer(spec, smoothed, labels, stats)
        }
    }

    private val lineColor = Color(140, 85, 35, 210)
    private val labelColor = Color(110, 60, 20)
    private val haloColor = Color(246, 241, 228, 200)

    /** Draws lines, then labels (halo first) on top of [image]. */
    fun draw(image: BufferedImage) {
        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.color = lineColor
        val thin = BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val bold = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (line in lines) {
            g2.stroke = if (spec.isIndex(line.level)) bold else thin
            val path = Path2D.Double()
            // +0.5: grid sample (i, j) is the center of image pixel (i, j).
            path.moveTo(line.points[0] + 0.5, line.points[1] + 0.5)
            for (i in 1 until line.pointCount) path.lineTo(line.points[2 * i] + 0.5, line.points[2 * i + 1] + 0.5)
            if (line.closed) path.closePath()
            g2.draw(path)
        }
        drawLabels(g2)
        g2.dispose()
    }

    private fun drawLabels(g2: java.awt.Graphics2D) {
        val frc = g2.fontRenderContext
        for (label in labels) {
            val glyphs = AwtTextMeasurer.font(label.fontPx).createGlyphVector(frc, label.text)
            val ink = glyphs.visualBounds
            val transform = AffineTransform()
            transform.translate(label.x + 0.5, label.y + 0.5)
            transform.rotate(label.angle)
            // Center the ink box on the label position.
            transform.translate(-ink.centerX, -ink.centerY)
            val shape = transform.createTransformedShape(glyphs.outline)
            // Halo in the paper tone: wide enough to break the contour under the text and keep it
            // legible over dark hillshade, translucent so the shading still shows through.
            g2.color = haloColor
            g2.stroke = BasicStroke((label.fontPx * 0.22).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(shape)
            g2.color = labelColor
            g2.fill(shape)
        }
    }
}
