package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.render.ContourLine
import io.github.jasondorjeshort.dystopo.render.Contours
import io.github.jasondorjeshort.dystopo.render.Hillshade
import kotlinx.serialization.Serializable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.abs

private const val FEET_TO_METERS = 0.3048

/** Map style options shared by render and compare. */
class StyleOptions : OptionGroup("Style") {
    val interval by option("--interval", help = "Contour interval in --units").double().default(40.0)
    val units by option("--units").choice("ft", "m").default("ft")
    val indexEvery by option("--index-every", help = "Every Nth contour is drawn bold").int().default(5)
    val zFactor by option("--z-factor", help = "Hillshade vertical exaggeration").double().default(1.0)
    val noHillshade by option("--no-hillshade").flag()
    val noContours by option("--no-contours").flag()

    val unitMeters: Double get() = if (units == "ft") FEET_TO_METERS else 1.0
}

@Serializable
data class ContourStats(val levels: Int, val lines: Int, val points: Int)

/** Records wall-clock time per labeled step, for JSON reports. */
class Timings {
    val ms = LinkedHashMap<String, Long>()

    fun <T> time(label: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { ms[label] = (System.nanoTime() - start) / 1_000_000 }
    }
}

object MapRenderer {
    private val paper = intArrayOf(246, 241, 228)
    private val noDataColor = (200 shl 16) or (60 shl 8) or 200 // magenta: impossible to miss

    fun render(grid: ElevationGrid, style: StyleOptions, timings: Timings, prefix: String = ""): Pair<BufferedImage, ContourStats> {
        val shade = if (style.noHillshade) null else timings.time("${prefix}hillshade") {
            Hillshade.compute(grid, zFactor = style.zFactor)
        }
        val unitMeters = style.unitMeters
        val levels = Contours.levels(grid.min() / unitMeters, grid.max() / unitMeters, style.interval)
        val lines = if (style.noContours) emptyList() else timings.time("${prefix}contours") {
            Contours.trace(grid, levels.map { it * unitMeters })
        }
        val image = timings.time("${prefix}draw") { draw(grid, shade, lines, style) }
        val stats = ContourStats(if (style.noContours) 0 else levels.size, lines.size, lines.sumOf { it.pointCount })
        return image to stats
    }

    private fun draw(grid: ElevationGrid, shade: FloatArray?, lines: List<ContourLine>, style: StyleOptions): BufferedImage {
        val w = grid.width
        val h = grid.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        // Warm paper tone, darkened by the hillshade.
        val pixels = IntArray(w * h) { i ->
            if (grid.values[i].isNaN()) return@IntArray noDataColor
            val k = if (shade == null) 1.0 else 0.35 + 0.65 * shade[i]
            val r = (paper[0] * k).toInt().coerceIn(0, 255)
            val g = (paper[1] * k).toInt().coerceIn(0, 255)
            val b = (paper[2] * k).toInt().coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }
        image.setRGB(0, 0, w, h, pixels, 0, w)

        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.color = Color(140, 85, 35, 210)
        val thin = BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val bold = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val indexInterval = style.interval * style.indexEvery
        for (line in lines) {
            val n = line.level / style.unitMeters / indexInterval
            g2.stroke = if (style.indexEvery > 0 && abs(n - Math.round(n)) < 1e-6) bold else thin
            val path = Path2D.Double()
            // +0.5: grid sample (i, j) is the center of image pixel (i, j).
            path.moveTo(line.points[0] + 0.5, line.points[1] + 0.5)
            for (i in 1 until line.pointCount) path.lineTo(line.points[2 * i] + 0.5, line.points[2 * i + 1] + 0.5)
            if (line.closed) path.closePath()
            g2.draw(path)
        }
        g2.dispose()
        return image
    }
}

/** Common --area/--bbox/--zoom handling. */
abstract class AreaCommand(name: String) : CliktCommand(name = name) {
    protected val area by option("--area", help = "Named area (see 'devtool areas')")
    protected val bbox by option("--bbox", help = "west,south,east,north in degrees (instead of --area)")
    protected val zoom by option("--zoom", help = "Web Mercator zoom of the output grid; 13 is ~10-20 m/px, 14 half that")
        .int().default(13)
}
