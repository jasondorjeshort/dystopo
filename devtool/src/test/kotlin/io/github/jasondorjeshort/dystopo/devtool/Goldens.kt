package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.render.ContourLabel
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds [StyleOptions] without a real command line: the options are parsed by a throwaway
 * command, so tests get exactly the CLI defaults (and pick up new options and defaults as the
 * renderer grows) without a parallel settings class in main code.
 */
fun styleOptions(vararg args: String): StyleOptions {
    val holder = object : CliktCommand(name = "golden") {
        val style by StyleOptions()
        override fun run() = Unit
    }
    holder.parse(args.toList())
    return holder.style
}

/**
 * One golden image: [terrain] rendered through the full pipeline at [zoom], [sizePx] square,
 * with the cell size Web Mercator has at 45 degrees latitude for that zoom, centered on
 * ([centerX], [centerY]) meters of the terrain.
 */
data class GoldenCase(
    val terrain: SyntheticTerrain,
    val zoom: Int,
    val sizePx: Int = 256,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val args: List<String> = emptyList(),
) {
    val name: String get() = "${terrain.name}-z$zoom"
    val fileName: String get() = "$name.png"
    val cellSizeMeters: Double get() = WebMercator.metersPerPixel(45.0, zoom)

    fun grid(): ElevationGrid = terrain.grid(sizePx, sizePx, cellSizeMeters, centerX, centerY)

    fun style(): StyleOptions = styleOptions(*args.toTypedArray())
}

/** Result of rendering a [GoldenCase]: the image, its report numbers, and where labels were drawn. */
class GoldenRender(val image: BufferedImage, val stats: ContourStats, val labels: List<ContourLabel>)

object Goldens {
    /**
     * The approved set. Three landscapes at two zoom-equivalents each: the low zoom shows each
     * whole preset with sparse contours, the high zoom a close-up with dense contours, cliff
     * thinning and several labels.
     */
    val cases = listOf(
        GoldenCase(SyntheticTerrain.MIXED, zoom = 12),
        GoldenCase(SyntheticTerrain.MIXED, zoom = 14, centerX = 900.0, centerY = -1000.0),
        GoldenCase(SyntheticTerrain.CONE, zoom = 12),
        GoldenCase(SyntheticTerrain.CONE, zoom = 14, centerX = 500.0, centerY = 300.0),
        GoldenCase(SyntheticTerrain.LAKE_CLIFF, zoom = 13),
        GoldenCase(SyntheticTerrain.LAKE_CLIFF, zoom = 15, centerX = 250.0, centerY = 0.0),
    )

    /** Source directory of the approved images (relative to the devtool project dir, where Gradle runs tests). */
    val sourceDir: File get() = File(System.getProperty("dystopo.goldens.dir") ?: "src/test/resources/goldens")

    /** Where failing comparisons write their actual and diff images. */
    val diffDir: File get() = File(System.getProperty("dystopo.goldens.diffDir") ?: "build/golden-diffs")

    fun render(case: GoldenCase): GoldenRender {
        val grid = case.grid()
        val style = case.style()
        val (image, stats) = MapRenderer.render(grid, case.zoom, style, Timings())
        // Same inputs, same placement: rebuild the contour layer to learn where the labels went.
        val labels = ContourLayer.build(grid, case.zoom, style, Timings()).labels
        return GoldenRender(image, stats, labels)
    }

    /**
     * Pixels covered by labels and their halos, grown by [marginPx]. Text is where machines differ
     * (font files, hinting, rasterizer), so these pixels get a looser tolerance.
     */
    fun labelMask(labels: List<ContourLabel>, width: Int, height: Int, marginPx: Double = 3.0): BooleanArray {
        val mask = BooleanArray(width * height)
        for (label in labels) {
            val halfW = label.width / 2 + label.fontPx * 0.11 + marginPx
            val halfH = label.height / 2 + label.fontPx * 0.11 + marginPx
            val reach = ceil(kotlin.math.hypot(halfW, halfH)).toInt() + 1
            val c = cos(label.angle)
            val s = sin(label.angle)
            val cx = label.x + 0.5
            val cy = label.y + 0.5
            for (py in (cy.toInt() - reach)..(cy.toInt() + reach)) {
                if (py !in 0 until height) continue
                for (px in (cx.toInt() - reach)..(cx.toInt() + reach)) {
                    if (px !in 0 until width) continue
                    val dx = px + 0.5 - cx
                    val dy = py + 0.5 - cy
                    // Into the label's frame (the drawing rotates by +angle about the center).
                    val u = dx * c + dy * s
                    val v = -dx * s + dy * c
                    if (abs(u) <= halfW && abs(v) <= halfH) mask[py * width + px] = true
                }
            }
        }
        return mask
    }
}
