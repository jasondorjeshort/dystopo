package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.dem.ViewshedResult
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.png.TerrariumPngDecoder
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.render.Hillshade
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * T182: what can I see from here ([Viewshed]): the ground visible from a point at eye height out to a range, drawn
 * as the phone's overlay over the hillshade (visible ground green at 35 percent, hidden ground clear, where the data
 * stopped hatched grey), and the named peaks of the outdoors pack in range with distance, true bearing and whether
 * they are in view, as JSON.
 */
class ViewshedCommand : CliktCommand(name = "viewshed") {
    override fun help(context: Context) =
        "What can I see from here: the visible ground from a point at eye height out to a range (PNG of the overlay over the " +
            "hillshade, the whole range and the near band) and the named peaks in view (JSON, sorted by distance)."

    private val area by option("--area", help = "Named area: its centre is the default point and out/<area>-outdoors.pmtiles the default pack")
    private val from by option("--from", help = "lat,lon of the observer (default: the area's centre)")
    private val rangeKm by option("--range-km", help = "How far to look, km (the phone's choices: 10, 30, 100)").double().default(Viewshed.DEFAULT_RANGE_KM.toDouble())
    private val eye by option("--eye-m", help = "Eye height above the ground, metres").double().default(Viewshed.EYE_HEIGHT_M)
    private val source by option("--source", help = "Elevation source (when no --dem is given)").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val dem by option("--dem", help = "DEM pack (Terrarium PMTiles) to read instead of --source: the data stops at its edge, as on the phone").file(mustExist = true, canBeDir = false)
    private val pack by option("--pack", help = "Outdoors vector pack for the peaks (default out/<area>-outdoors.pmtiles when it exists)").file(mustExist = true, canBeDir = false)
    private val out by option("--out", help = "Output PNG (default out/<area>-viewshed-<range>km.png; the near band beside it as -near.png)").file()

    override fun run() {
        val box = area?.let { Areas.resolve(it, null).second }
        val (lat, lon) = from?.let(::parseLatLon)
            ?: box?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }
            ?: throw UsageError("--from or --area is required")
        val rangeM = rangeKm * 1000
        if (rangeM <= 0 || rangeM > Viewshed.MAX_RANGE_M) throw UsageError("--range-km must be in (0, ${Viewshed.MAX_RANGE_M / 1000}]")
        val timings = Timings()
        val plan = Viewshed.plan(lat, lon, rangeM)

        val provenance = LinkedHashSet<String>()
        var attribution = ""
        val demFile = dem
        val read: (MercatorWindow) -> ElevationGrid = if (demFile != null) {
            val reader = PmtilesReader.open(FileRangeSource(demFile), Gzip.decompressors)
            val h = reader.header
            val pyramid = DemPyramid(h.minZoom, h.maxZoom, TerrariumPngDecoder(ZlibInflate.decompressor, imageIoDecoder), cacheTiles = 512) { z, x, y ->
                synchronized(reader) { reader.tile(z, x, y) }
            }
            ({ w -> pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) })
        } else {
            val src = ElevationSource.byId(source)
            ({ w ->
                val r = src.fetch(w)
                provenance += r.provenance
                attribution = r.attribution(src)
                r.grid
            })
        }
        val terrain = timings.time("fetch") { Viewshed.terrain(plan, read) }
        val result = timings.time("sweep") { Viewshed.compute(terrain, plan, eye) }

        val packFile = pack ?: area?.let { File("out/$it-outdoors.pmtiles").takeIf { f -> f.isFile } }
        val peakZoom = Viewshed.peakZoom(lat, rangeM)
        val peaks: List<PeakView> = if (packFile != null) {
            val reader = PmtilesReader.open(FileRangeSource(packFile), Gzip.decompressors)
            val z = minOf(peakZoom, reader.header.maxZoom)
            val found = timings.time("peaks.read") { Viewshed.readPeaks({ tz, tx, ty -> synchronized(reader) { reader.tile(tz, tx, ty) } }, lat, lon, rangeM, z) }
            timings.time("peaks.test") { Viewshed.peaks(terrain, result, found) }
        } else emptyList()

        val name = area ?: "point"
        val outFile = out ?: File("out/$name-viewshed-${rangeKm.roundToInt()}km.png")
        val nearFile = File(outFile.path.removeSuffix(".png") + "-near.png")
        timings.time("draw") {
            outFile.absoluteFile.parentFile.mkdirs()
            val last = terrain.layers.size - 1
            ImageIO.write(draw(terrain, last, result, peaks), "png", outFile)
            if (last > 0) ImageIO.write(draw(terrain, 0, result, peaks), "png", nearFile)
        }

        val inView = peaks.filter { it.visible }
        val report = linkedMapOf<String, Any?>(
            "command" to "viewshed", "area" to area, "from" to listOf(lat, lon), "rangeKm" to rangeKm,
            "source" to if (demFile != null) "dem:${demFile.name}" else source,
            "convention" to "visible ground #%06x at opacity %.2f, hidden ground clear, no data hatched #%06x".format(Viewshed.OVERLAY_RGB, Viewshed.OVERLAY_OPACITY, Viewshed.NO_DATA_RGB),
            "viewshed" to result.json(),
            "terrainLayers" to terrain.layers.map { l ->
                linkedMapOf("zoom" to l.window.zoom, "width" to l.window.width, "height" to l.window.height,
                    "cellM" to round1(l.window.cellSizeMeters), "untilPx" to l.untilPx.takeIf { it.isFinite() }?.let(::round1),
                    "noDataCells" to l.grid.values.count { it.isNaN() })
            },
            "terrainCells" to plan.terrainCells,
            "pack" to packFile?.path, "peakZoom" to peakZoom,
            "peaksConsidered" to peaks.size, "peaksInView" to inView.size, "peaksOnSkyline" to inView.count { it.skyline },
            "peaks" to inView.map { it.json() },
            "peaksHidden" to peaks.filter { !it.visible }.map { it.json() },
            "output" to outFile.path, "nearOutput" to nearFile.path.takeIf { terrain.layers.size > 1 },
            "timingsMs" to timings.ms,
            "provenance" to provenance.toList(),
            "attribution" to attribution,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    /**
     * The terrain layer [index]'s window: paper toned by the multidirectional hillshade, light grey without data, the
     * viewshed's state of each pixel's centre over it as the phone draws it ([Viewshed.overlayArgb]'s colours), the range
     * circle, the observer and the peaks (in view: dark triangle and name; hidden: a small grey triangle).
     */
    private fun draw(terrain: Terrain, index: Int, r: ViewshedResult, peaks: List<PeakView>): BufferedImage {
        val layer = terrain.layers[index]
        val w = layer.window
        val grid = layer.grid
        val light = Hillshade.multidirectional(grid)
        val visA = Viewshed.OVERLAY_OPACITY
        val hatchA = Viewshed.NO_DATA_OPACITY
        val image = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(w.width * w.height)
        for (j in 0 until w.height) {
            val lat = w.latAt(j)
            for (i in 0 until w.width) {
                val k = j * w.width + i
                var rr: Double
                var gg: Double
                var bb: Double
                if (grid.values[k].isNaN()) {
                    rr = 225.0; gg = 225.0; bb = 225.0
                } else {
                    val f = 0.35 + 0.65 * light[k]
                    rr = 246 * f; gg = 241 * f; bb = 228 * f
                }
                when (r.stateAt(lat, w.lonAt(i))) {
                    Viewshed.VISIBLE -> {
                        rr = rr * (1 - visA) + ((Viewshed.OVERLAY_RGB shr 16) and 0xFF) * visA
                        gg = gg * (1 - visA) + ((Viewshed.OVERLAY_RGB shr 8) and 0xFF) * visA
                        bb = bb * (1 - visA) + (Viewshed.OVERLAY_RGB and 0xFF) * visA
                    }
                    Viewshed.NO_DATA -> if ((w.x0 + i + w.y0 + j).mod(Viewshed.HATCH_PERIOD) == 0) {
                        val c = ((Viewshed.NO_DATA_RGB shr 16) and 0xFF).toDouble()
                        rr = rr * (1 - hatchA) + c * hatchA; gg = gg * (1 - hatchA) + c * hatchA; bb = bb * (1 - hatchA) + c * hatchA
                    }
                }
                px[k] = (rr.roundToInt().coerceIn(0, 255) shl 16) or (gg.roundToInt().coerceIn(0, 255) shl 8) or bb.roundToInt().coerceIn(0, 255)
            }
        }
        image.setRGB(0, 0, w.width, w.height, px, 0, w.width)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        fun x(lon: Double) = WebMercator.lonToX(lon, w.zoom) * w.tileSize - w.x0
        fun y(lat: Double) = WebMercator.latToY(lat, w.zoom) * w.tileSize - w.y0
        val ox = x(r.lon)
        val oy = y(r.lat)
        val cellM = w.cellSizeMeters
        val ringM = minOf(r.rangeM, if (index == terrain.layers.size - 1) r.rangeM else r.levels[index].toM)
        g.color = Color(40, 40, 40, 160)
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(6f, 4f), 0f)
        val rad = ringM / cellM
        g.draw(Ellipse2D.Double(ox - rad, oy - rad, 2 * rad, 2 * rad))
        g.stroke = BasicStroke(2.5f)
        g.color = Color(200, 0, 0)
        g.draw(Ellipse2D.Double(ox - 7, oy - 7, 14.0, 14.0))
        g.font = RenderFonts.bold(11.0)
        for (p in peaks) {
            val pxx = x(p.peak.lon)
            val pyy = y(p.peak.lat)
            if (pxx < 0 || pyy < 0 || pxx >= w.width || pyy >= w.height) continue
            val sz = if (p.visible) 1.0 else 0.6
            val tri = Path2D.Double().apply { moveTo(pxx, pyy - 7 * sz); lineTo(pxx - 6 * sz, pyy + 5 * sz); lineTo(pxx + 6 * sz, pyy + 5 * sz); closePath() }
            g.color = if (p.visible) Color(30, 20, 10) else Color(140, 140, 140)
            g.fill(tri)
            if (!p.visible) continue // hidden peaks unlabelled: the names in view stay readable
            val label = "${p.peak.name} ${p.eleM.roundToLong()} m" + if (p.visible && p.skyline) " *" else ""
            g.color = Color(255, 255, 255, 190)
            g.fillRect((pxx + 8).toInt(), (pyy - 6).toInt(), g.fontMetrics.stringWidth(label) + 4, 14)
            g.color = if (p.visible) Color(30, 20, 10) else Color(120, 120, 120)
            g.drawString(label, (pxx + 10).toFloat(), (pyy + 5).toFloat())
        }
        g.font = RenderFonts.regular(12.0)
        val caption = "viewshed z${w.zoom} (${"%.0f".format(cellM)} m cells), range ${"%.0f".format(r.rangeM / 1000)} km, eye ${r.eyeM} m: " +
            "green visible, clear hidden, hatched no data; named peaks in view (* on the skyline), grey: hidden"
        g.color = Color(255, 255, 255, 215)
        g.fillRect(6, w.height - 24, g.fontMetrics.stringWidth(caption) + 10, 18)
        g.color = Color(30, 30, 30)
        g.drawString(caption, 11, w.height - 10)
        g.dispose()
        return image
    }

    private fun parseLatLon(s: String): Pair<Double, Double> {
        val p = s.split(",").map { it.trim().toDoubleOrNull() }
        if (p.size != 2 || p.any { it == null }) throw UsageError("--from must be lat,lon (got $s)")
        return p[0]!! to p[1]!!
    }

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
}
