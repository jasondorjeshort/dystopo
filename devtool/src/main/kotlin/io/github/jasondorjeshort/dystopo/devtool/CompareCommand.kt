package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

@Serializable
data class SourceSummary(
    val source: String,
    val elevationMinMeters: Double,
    val elevationMaxMeters: Double,
    val counters: Map<String, Long>,
    val contours: ContourStats,
    val attribution: String,
)

/** Elevation differences (second source minus first) over cells where both have data. */
@Serializable
data class DiffStats(
    val sources: List<String>,
    val cells: Long,
    val meanMeters: Double,
    val meanAbsMeters: Double,
    val rmsMeters: Double,
    val maxAbsMeters: Double,
)

@Serializable
data class CompareReport(
    val area: String?,
    val bbox: List<Double>,
    val zoom: Int,
    val output: String,
    val panelWidthPx: Int,
    val panelHeightPx: Int,
    val cellSizeMeters: Double,
    val sources: List<SourceSummary>,
    val diff: DiffStats?,
    val timingsMs: Map<String, Long>,
)

class CompareCommand : AreaCommand("compare") {
    override fun help(context: Context) =
        "Render the same area from several elevation sources, side by side on an identical pixel grid."

    private val sources by option("--sources", help = "Comma-separated sources, left to right")
        .split(",").default(listOf("terrarium", "3dep"))
    private val style by StyleOptions()
    private val out by option("--out", help = "Output PNG (default out/<area>-compare-z<zoom>.png)").file()

    override fun run() {
        sources.forEach { if (it !in ElevationSource.ids) throw UsageError("unknown source '$it'") }
        val (name, box) = Areas.resolve(area, bbox)
        val window = MercatorWindow.forBbox(box, zoom)
        val timings = Timings()

        val grids = ArrayList<ElevationGrid>()
        val panels = ArrayList<BufferedImage>()
        val summaries = ArrayList<SourceSummary>()
        for (id in sources) {
            val src = ElevationSource.byId(id)
            val fetched = timings.time("$id.fetch") { src.fetch(window) }
            val (image, contours) = MapRenderer.render(fetched.grid, style, timings, "$id.")
            grids += fetched.grid
            panels += image
            summaries += SourceSummary(
                id, fetched.grid.min().toDouble(), fetched.grid.max().toDouble(), fetched.counters, contours, src.attribution,
            )
        }

        val outFile = out ?: File("out/${name ?: "bbox"}-compare-z$zoom.png")
        timings.time("composeAndWrite") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(sideBySide(panels, sources), "png", outFile)
        }

        val report = CompareReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            zoom = zoom,
            output = outFile.path,
            panelWidthPx = window.width,
            panelHeightPx = window.height,
            cellSizeMeters = Math.round(window.cellSizeMeters * 100) / 100.0,
            sources = summaries,
            diff = if (grids.size >= 2) diff(grids[0], grids[1], sources.take(2)) else null,
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
    }

    private fun diff(a: ElevationGrid, b: ElevationGrid, names: List<String>): DiffStats {
        var n = 0L
        var sum = 0.0
        var sumAbs = 0.0
        var sumSq = 0.0
        var maxAbs = 0.0
        for (i in a.values.indices) {
            val va = a.values[i]
            val vb = b.values[i]
            if (va.isNaN() || vb.isNaN()) continue
            val d = (vb - va).toDouble()
            n++
            sum += d
            sumAbs += abs(d)
            sumSq += d * d
            maxAbs = maxOf(maxAbs, abs(d))
        }
        fun r(x: Double) = Math.round(x * 100) / 100.0
        return if (n == 0L) DiffStats(names, 0, 0.0, 0.0, 0.0, 0.0)
        else DiffStats(names, n, r(sum / n), r(sumAbs / n), r(sqrt(sumSq / n)), r(maxAbs))
    }

    private fun sideBySide(panels: List<BufferedImage>, labels: List<String>): BufferedImage {
        val gap = 16
        val labelHeight = 56
        val w = panels.sumOf { it.width } + gap * (panels.size - 1)
        val h = panels.maxOf { it.height } + labelHeight
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 36)
        var x = 0
        for ((panel, label) in panels.zip(labels)) {
            g.color = Color(40, 40, 40)
            g.drawString(label, x + 12, 42)
            g.drawImage(panel, x, labelHeight, null)
            x += panel.width + gap
        }
        g.dispose()
        return out
    }
}
