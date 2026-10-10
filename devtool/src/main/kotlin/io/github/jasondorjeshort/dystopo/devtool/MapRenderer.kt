package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Smooth
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
    val shading by option("--shading", help = "hillshade: single light from the north-west; multi: blend of lights from 225-360 degrees, keeps steep faces readable; none: flat paper")
        .choice("hillshade", "multi", "none").default("hillshade")
    val slopeOverlay by option("--slope-overlay", help = "Tint slopes of 27 degrees and steeper in CalTopo-style bands").flag()
    val slopeOpacity by option("--slope-opacity", help = "Opacity of the slope-band tint, 0..1").double().default(0.5)
    val smoothDem by option("--smooth-dem", help = "Smooth the elevation grid with a (2r+1)x(2r+1) Gaussian before hillshade and slope only (contours stay raw); 1 or 2 tames striping, 0 disables")
        .int().restrictTo(0..Smooth.MAX_RADIUS).default(0)
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

/** A rendered map. Destructures as (image, contours) for callers that don't need the rest. */
data class RenderResult(val image: BufferedImage, val contours: ContourStats, val shading: ShadingStats)

object MapRenderer {
    fun render(grid: ElevationGrid, zoom: Int, style: StyleOptions, timings: Timings, prefix: String = ""): RenderResult {
        val (pixels, shading) = Shading.render(grid, style, timings, prefix)
        val contours = ContourLayer.build(grid, zoom, style, timings, prefix)
        val image = timings.time("${prefix}draw") { draw(grid, pixels, contours) }
        return RenderResult(image, contours.stats, shading)
    }

    private fun draw(grid: ElevationGrid, pixels: IntArray, contours: ContourLayer): BufferedImage {
        val image = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, grid.width, grid.height, pixels, 0, grid.width)
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
