package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToLong

@Serializable
data class RenderReport(
    val area: String?,
    val bbox: List<Double>,
    val zoom: Int,
    val source: String,
    val output: String,
    val widthPx: Int,
    val heightPx: Int,
    val cellSizeMeters: Double,
    val elevationMinMeters: Double,
    val elevationMaxMeters: Double,
    val sourceCounters: Map<String, Long>,
    val contourInterval: Double,
    val contourUnits: String,
    val contours: ContourStats,
    val shading: ShadingStats,
    val timingsMs: Map<String, Long>,
    val attribution: String,
)

class RenderCommand : AreaCommand("render") {
    override fun help(context: Context) =
        "Fetch elevation for an area and render hillshade + contours to a PNG. Prints a JSON report."

    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val style by StyleOptions()
    private val out by option("--out", help = "Output PNG (default out/<area>-<source>-z<zoom>.png)").file()

    override fun run() {
        val (name, box) = Areas.resolve(area, bbox)
        val window = MercatorWindow.forBbox(box, zoom)
        val src = ElevationSource.byId(source)
        val timings = Timings()

        val fetched = timings.time("fetch") { src.fetch(window) }
        val grid = fetched.grid
        val (image, contours, shading) = MapRenderer.render(grid, zoom, style, timings)
        val outFile = out ?: File("out/${name ?: "bbox"}-$source-z$zoom.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
        }

        val report = RenderReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            zoom = zoom,
            source = source,
            output = outFile.path,
            widthPx = grid.width,
            heightPx = grid.height,
            cellSizeMeters = (grid.cellSizeMeters * 100).roundToLong() / 100.0,
            elevationMinMeters = grid.min().toDouble(),
            elevationMaxMeters = grid.max().toDouble(),
            sourceCounters = fetched.counters,
            contourInterval = contours.interval,
            contourUnits = contours.units,
            contours = contours,
            shading = shading,
            timingsMs = timings.ms,
            attribution = src.attribution,
        )
        echo(reportJson.encodeToString(report))
    }
}
