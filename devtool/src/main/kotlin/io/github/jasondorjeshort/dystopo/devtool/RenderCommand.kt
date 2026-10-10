package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
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
    /** Null when the grid has no data at all (see [noData]). */
    val elevationMinMeters: Double?,
    val elevationMaxMeters: Double?,
    /** True when every cell is NaN (open ocean, outside every source): the image is all no-data magenta. */
    val noData: Boolean,
    val sourceCounters: Map<String, Long>,
    val contourInterval: Double,
    val contourUnits: String,
    val contours: ContourStats,
    val shading: ShadingStats,
    val timingsMs: Map<String, Long>,
    /** Data source registry ids that contributed (see `devtool sources`). */
    val provenance: List<String>,
    val attribution: String,
    /** OSM overlay counts and timings; present with --osm. */
    val osm: OsmStats? = null,
)

/** Lowest elevation, or null for a grid with no data (where [ElevationGrid.min] is +Infinity, which JSON can't carry). */
fun ElevationGrid.finiteMin(): Double? = min().toDouble().takeIf { it.isFinite() }

/** Highest elevation, or null for a grid with no data. */
fun ElevationGrid.finiteMax(): Double? = max().toDouble().takeIf { it.isFinite() }

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
        val (image, contours, shading, osm) = MapRenderer.render(grid, zoom, style, timings, window = window)
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
            elevationMinMeters = grid.finiteMin(),
            elevationMaxMeters = grid.finiteMax(),
            noData = grid.finiteMin() == null,
            sourceCounters = fetched.counters,
            contourInterval = contours.interval,
            contourUnits = contours.units,
            contours = contours,
            shading = shading,
            timingsMs = timings.ms,
            provenance = fetched.provenance,
            attribution = fetched.attribution(src) + (if (osm != null) " Map data © OpenStreetMap contributors (ODbL)." else ""),
            osm = osm,
        )
        echo(reportJson.encodeToString(report))
    }
}
