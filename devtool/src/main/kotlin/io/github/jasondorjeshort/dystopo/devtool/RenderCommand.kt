package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.render.ContourLine
import io.github.jasondorjeshort.dystopo.render.Contours
import io.github.jasondorjeshort.dystopo.render.Hillshade
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToLong

private const val FEET_TO_METERS = 0.3048

@Serializable
data class RenderReport(
    val area: String?,
    val bbox: List<Double>,
    val zoom: Int,
    val output: String,
    val widthPx: Int,
    val heightPx: Int,
    val cellSizeMeters: Double,
    val elevationMinMeters: Double,
    val elevationMaxMeters: Double,
    val tiles: Int,
    val tilesDownloaded: Int,
    val contourInterval: Double,
    val contourUnits: String,
    val contourLevels: Int,
    val contourLines: Int,
    val contourPoints: Int,
    val timingsMs: Map<String, Long>,
    val attribution: String,
)

class RenderCommand : CliktCommand(name = "render") {
    override fun help(context: Context) =
        "Fetch elevation for an area and render hillshade + contours to a PNG. Prints a JSON report."

    private val area by option("--area", help = "Named area (see 'devtool areas')")
    private val bbox by option("--bbox", help = "west,south,east,north in degrees (instead of --area)")
    private val zoom by option("--zoom", help = "Elevation tile zoom; 13 is ~10-20 m/px")
        .int().restrictTo(1..TerrariumSource.MAX_ZOOM).default(13)
    private val interval by option("--interval", help = "Contour interval in --units").double().default(40.0)
    private val units by option("--units").choice("ft", "m").default("ft")
    private val indexEvery by option("--index-every", help = "Every Nth contour is drawn bold").int().default(5)
    private val zFactor by option("--z-factor", help = "Hillshade vertical exaggeration").double().default(1.0)
    private val noHillshade by option("--no-hillshade").flag()
    private val noContours by option("--no-contours").flag()
    private val out by option("--out", help = "Output PNG (default out/<area>-z<zoom>.png)").file()

    override fun run() {
        val (name, box) = resolveArea()
        val timings = LinkedHashMap<String, Long>()
        fun <T> timed(label: String, block: () -> T): T {
            val start = System.nanoTime()
            return block().also { timings[label] = (System.nanoTime() - start) / 1_000_000 }
        }

        val mosaic = timed("fetchAndDecode") { TerrariumSource().mosaic(box, zoom) }
        val grid = mosaic.grid

        val shade = if (noHillshade) null else timed("hillshade") { Hillshade.compute(grid, zFactor = zFactor) }

        val unitMeters = if (units == "ft") FEET_TO_METERS else 1.0
        val levelsInUnits = Contours.levels(grid.min() / unitMeters, grid.max() / unitMeters, interval)
        val lines = if (noContours) emptyList() else timed("contours") {
            Contours.trace(grid, levelsInUnits.map { it * unitMeters })
        }

        val image = timed("draw") { draw(grid.width, grid.height, shade, lines, unitMeters) }
        val outFile = out ?: File("out/${name ?: "bbox"}-z$zoom.png")
        timed("writePng") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
        }

        val report = RenderReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            zoom = zoom,
            output = outFile.path,
            widthPx = grid.width,
            heightPx = grid.height,
            cellSizeMeters = (grid.cellSizeMeters * 100).roundToLong() / 100.0,
            elevationMinMeters = grid.min().toDouble(),
            elevationMaxMeters = grid.max().toDouble(),
            tiles = mosaic.tiles,
            tilesDownloaded = mosaic.downloaded,
            contourInterval = interval,
            contourUnits = units,
            contourLevels = if (noContours) 0 else levelsInUnits.size,
            contourLines = lines.size,
            contourPoints = lines.sumOf { it.pointCount },
            timingsMs = timings,
            attribution = TerrariumSource.ATTRIBUTION,
        )
        echo(reportJson.encodeToString(report))
    }

    private fun resolveArea(): Pair<String?, BoundingBox> {
        val areaName = area
        val bboxText = bbox
        return when {
            areaName != null && bboxText != null -> throw UsageError("use --area or --bbox, not both")
            areaName != null -> {
                val areas = Areas.load()
                val found = areas[areaName]
                    ?: throw UsageError("unknown area '$areaName'; known: ${areas.keys.joinToString()}")
                areaName to found.boundingBox()
            }
            bboxText != null -> {
                val parts = bboxText.split(",").map { it.trim().toDoubleOrNull() }
                if (parts.size != 4 || parts.any { it == null }) throw UsageError("--bbox must be west,south,east,north")
                null to BoundingBox(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
            }
            else -> throw UsageError("one of --area or --bbox is required")
        }
    }

    private fun draw(w: Int, h: Int, shade: FloatArray?, lines: List<ContourLine>, unitMeters: Double): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        // Warm paper tone, darkened by the hillshade.
        val paper = intArrayOf(246, 241, 228)
        val pixels = IntArray(w * h) { i ->
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
        val indexInterval = interval * indexEvery
        for (line in lines) {
            val levelInUnits = line.level / unitMeters
            val isIndex = indexEvery > 0 && abs(levelInUnits / indexInterval - Math.round(levelInUnits / indexInterval)) < 1e-6
            g2.stroke = if (isIndex) bold else thin
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
