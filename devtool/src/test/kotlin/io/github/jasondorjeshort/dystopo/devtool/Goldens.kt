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

    /** Approved label list next to the image (see [Goldens.formatLabels]). */
    val labelsFileName: String get() = "$name.labels.txt"
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

    /** A label as recorded in a golden's `.labels.txt`: text, center in pixels, angle in degrees. */
    data class RecordedLabel(val text: String, val x: Double, val y: Double, val angleDeg: Double) {
        override fun toString() = "\"$text\" at (${fmt(x)}, ${fmt(y)}) ${fmt(angleDeg)}°"
    }

    /**
     * The approved labels of a golden as text, one per line: `text x y angleDeg`, sorted by
     * position. The image comparison is loose inside label boxes (fonts differ by machine), and
     * those boxes come from the render under test, so an extra label, or one with the wrong
     * number, would otherwise pass; this list pins count, text and placement exactly.
     */
    fun formatLabels(labels: List<ContourLabel>): String =
        "# text x y angleDeg; written by ./gradlew updateGoldens\n" +
            record(labels).joinToString("") { "${it.text} ${fmt(it.x)} ${fmt(it.y)} ${fmt(it.angleDeg)}\n" }

    fun parseLabels(text: String): List<RecordedLabel> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val f = line.split(Regex("\\s+"))
            require(f.size == 4) { "bad label line '$line'" }
            RecordedLabel(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        }

    fun record(labels: List<ContourLabel>): List<RecordedLabel> = labels
        .map { RecordedLabel(it.text, round1(it.x), round1(it.y), round1(Math.toDegrees(it.angle))) }
        .sortedWith(compareBy({ it.y }, { it.x }, { it.text }))

    /**
     * Differences between approved and actual labels: every approved label needs an actual one
     * with the same text within [maxOffsetPx] and [maxAngleDeg], and nothing may be left over.
     * Empty when they match.
     */
    fun compareLabels(
        expected: List<RecordedLabel>,
        actual: List<RecordedLabel>,
        maxOffsetPx: Double = 2.0,
        maxAngleDeg: Double = 2.0,
    ): List<String> {
        val unmatched = actual.toMutableList()
        val problems = ArrayList<String>()
        for (e in expected) {
            val hit = unmatched.firstOrNull {
                it.text == e.text && kotlin.math.hypot(it.x - e.x, it.y - e.y) <= maxOffsetPx && abs(it.angleDeg - e.angleDeg) <= maxAngleDeg
            }
            if (hit != null) {
                unmatched.remove(hit)
                continue
            }
            val near = unmatched.minByOrNull { kotlin.math.hypot(it.x - e.x, it.y - e.y) }
            problems += "missing $e" + (near?.let { "; nearest actual $it" } ?: "")
        }
        for (a in unmatched) problems += "unexpected $a"
        if (expected.size != actual.size) problems.add(0, "label count ${actual.size}, approved ${expected.size}")
        return problems
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun fmt(x: Double) = round1(x).toString()

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
