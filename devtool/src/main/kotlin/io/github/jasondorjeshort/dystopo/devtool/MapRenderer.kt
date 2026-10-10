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
import io.github.jasondorjeshort.dystopo.render.Hillshade
import java.awt.image.BufferedImage

/** Map style options shared by render and compare. */
class StyleOptions : OptionGroup("Style") {
    val interval by option("--interval", help = "Contour interval in --units (default: from the zoom table, see ContourStyle)").double()
    val units by option("--units").choice("ft", "m").default("ft")
    val indexEvery by option("--index-every", help = "Every Nth contour is an index contour: bold and labeled (default: from the zoom table; 0 disables)").int()
    val simplify by option("--simplify", help = "Douglas-Peucker tolerance in pixels (0 disables)").double().default(0.5)
    val smooth by option("--smooth", help = "Chaikin smoothing iterations after simplification (0 disables)").int().default(0)
    val minSpacing by option("--min-spacing", help = "Drop non-index contours where lines would be closer than this many pixels (0 disables)").double().default(3.0)
    val textScale by option("--text-scale", help = "Label size multiplier; 1.0 is 12 px text").double().default(1.4)
    val noLabels by option("--no-labels").flag()
    val zFactor by option("--z-factor", help = "Hillshade vertical exaggeration").double().default(1.0)
    val noHillshade by option("--no-hillshade").flag()
    val noContours by option("--no-contours").flag()
}

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

    fun render(grid: ElevationGrid, zoom: Int, style: StyleOptions, timings: Timings, prefix: String = ""): Pair<BufferedImage, ContourStats> {
        val shade = if (style.noHillshade) null else timings.time("${prefix}hillshade") {
            Hillshade.compute(grid, zFactor = style.zFactor)
        }
        val contours = ContourLayer.build(grid, zoom, style, timings, prefix)
        val image = timings.time("${prefix}draw") { draw(grid, shade, contours) }
        return image to contours.stats
    }

    private fun draw(grid: ElevationGrid, shade: FloatArray?, contours: ContourLayer): BufferedImage {
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

        contours.draw(image)
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
