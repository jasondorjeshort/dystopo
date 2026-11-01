package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Slope
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.ExposureAlong
import io.github.jasondorjeshort.dystopo.routing.ExposureParams
import io.github.jasondorjeshort.dystopo.routing.ExposureResult
import io.github.jasondorjeshort.dystopo.routing.ExposureText
import io.github.jasondorjeshort.dystopo.routing.RidgeRule
import io.github.jasondorjeshort.dystopo.routing.StretchKind
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * T195: a route's terrain ([ExposureAlong]): the distance and time on ground over 30 and over 35 degrees, the compass
 * sides of that ground, the time along ridges ([RidgeRule]) and the stretches.
 *
 * - `exposure --gpx <route> [--dem <dem pack> | --pack <outdoors pack>] [--mode hike] [--units ft|m]`: the JSON (the
 *   card's line in both units, the sheet's lines, every stretch) and `out/exposure-<gpx name>.png`: the map of the
 *   route (pass `--slope-shading avalanche` for the slope overlay under it) with the route coloured by band (grey
 *   under 30, orange 30 to 34, red 35 and over) and the ridge stretches ticked, and under it the elevation profile
 *   with the same colours, the ridge stretches as a dark bar above it, and the lines.
 * - `exposure --ridge-map --area <name> | --bbox <w,s,e,n> [--thresholds 10,15,20] [--dem <dem pack>]`: the ridge
 *   rule's tuning maps, `out/exposure-ridges-<area>-t<threshold>.png` per threshold: the area's hillshade at z14
 *   with every pixel whose TPI against the [RidgeRule.RING_M] ring is above the threshold tinted (the grid's own
 *   pixels on the ring, nearest pixel), and the share of pixels marked.
 */
class ExposureCommand : CliktCommand(name = "exposure") {
    override fun help(context: Context) =
        "A route's terrain from the DEM: distance and time on ground over 30 and 35 degrees, the sides it faces, " +
            "time along ridges, the stretches; JSON and a map and profile PNG. --ridge-map: the ridge rule's tuning maps."

    private val gpxFile by option("--gpx", help = "Route as GPX (its tracks, else its routes)").file(mustExist = true, canBeDir = false)
    private val pack by option("--pack", help = "Outdoors pack; its DEM is <name>-dem.pmtiles next to it").file(mustExist = true, canBeDir = false)
    private val dem by option("--dem", help = "DEM pack (default: next to --pack, else out/rainier-dem.pmtiles)").file(mustExist = true, canBeDir = false)
    private val mode by option("--mode", help = "Travel mode for the times").choice("hike", "bike", "crow").default("hike")
    private val ridgeMap by option("--ridge-map", help = "Write the ridge rule's tuning maps for --area or --bbox").flag()
    private val area by option("--area", help = "Named area for --ridge-map")
    private val bbox by option("--bbox", help = "west,south,east,north for --ridge-map")
    private val thresholds by option("--thresholds", help = "TPI thresholds for --ridge-map, metres, comma-separated").default("10,15,20")
    private val noRender by option("--no-render", help = "Skip the PNG").flag()
    private val out by option("--out", help = "Output PNG (default out/exposure-<gpx name>.png)").file()
    private val style by StyleOptions()

    override fun run() {
        val report = if (ridgeMap) ridgeMaps() else routeReport()
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun demFile(): File = dem ?: pack?.let { RouteGraphs.defaultDem(it) ?: throw UsageError("no DEM pack next to --pack; pass --dem") }
        ?: File("out/rainier-dem.pmtiles").takeIf { it.isFile } ?: throw UsageError("pass --dem or --pack")

    private fun routeReport(): Map<String, Any?> {
        val gpx = gpxFile ?: throw UsageError("--gpx is required (or --ridge-map)")
        val timings = Timings()
        val doc = Gpx.parse(gpx.readText(Charsets.UTF_8))
        val points = doc.tracks.flatMap { t -> t.segments.flatMap { s -> s.points.map { RoutePoint(it.lat, it.lon, it.ele) } } }
            .ifEmpty { doc.routes.flatMap { it.points } }
        if (points.size < 2) throw UsageError("the GPX has no track or route with two points")
        val demFile = demFile()
        val pyramid = RouteGraphs.pyramid(demFile)
        val params = ExposureParams()
        val sampler = DemElevation(pyramid, zoom = params.zoom)
        val travel = TravelMode.of(mode) ?: TravelMode.HIKE
        val result = timings.time("find") { ExposureAlong.find(points, sampler, CostModel(travel), params) }
        val units = if (style.units == "m") ContourUnits.METERS else ContourUnits.FEET
        val report = linkedMapOf<String, Any?>(
            "gpx" to gpx.path, "dem" to demFile.path, "demMaxZoom" to pyramid.maxZoom, "routePoints" to points.size,
            "elevationSamples" to sampler.samples, "units" to units.symbol,
            "rule" to linkedMapOf(
                "sampleM" to params.sampleM, "window" to "Horn 3x3, one z${params.zoom} pixel apart", "steepDeg" to Slope.STEEP_DEG,
                "verySteepDeg" to Slope.VERY_STEEP_DEG, "ridgeRingM" to RidgeRule.RING_M, "ridgeThresholdM" to RidgeRule.THRESHOLD_M,
                "ringPoints" to RidgeRule.RING_POINTS, "minSideM" to params.minSideM,
            ),
            "cardMetric" to ExposureText.cardLine(result, ContourUnits.METERS), "cardImperial" to ExposureText.cardLine(result, ContourUnits.FEET),
        ) + result.json(units)
        val full = LinkedHashMap(report)
        if (!noRender) full["render"] = render(gpx, points, result, pyramid, units, timings)
        full["timingsMs"] = timings.ms
        return full
    }

    private val ink = Color(0x1f, 0x1a, 0x17)
    private val gentle = Color(0x8a, 0x8a, 0x8a)
    private val orange = Color(0xF5, 0x90, 0x00)
    private val red = Color(0xE0, 0x20, 0x20)
    private val ridgeInk = Color(0x24, 0x1e, 0x6e)

    private fun bandColor(slopeDeg: Double): Color = when {
        slopeDeg.isNaN() -> Color(0xcc, 0xcc, 0xcc)
        slopeDeg >= Slope.VERY_STEEP_DEG -> red
        slopeDeg >= Slope.STEEP_DEG -> orange
        else -> gentle
    }

    private fun render(gpx: File, points: List<RoutePoint>, r: ExposureResult, pyramid: io.github.jasondorjeshort.dystopo.tiles.DemPyramid, units: ContourUnits, timings: Timings): Map<String, Any?> {
        val asTrack = Track("exposure", "route", listOf(TrackSegment(points.map { TrackPoint(it.lat, it.lon, it.ele) })))
        val fit = TrackDrawing.fitBounds(listOf(asTrack), emptyList(), emptyList(), 0.12) ?: return mapOf("skipped" to "empty route")
        val zoom = GpxCommand.fitZoom(fit, maxZoom = 15)
        val window = MercatorWindow.forBbox(fit, zoom)
        val grid = timings.time("demWindow") { pyramid.window(zoom, window.x0, window.y0, window.width, window.height) }
        val (map, _, _, _) = MapRenderer.render(grid, zoom, style, timings, window = window)

        val profileH = 240
        val lines = listOfNotNull(
            ExposureText.cardLine(r, units), ExposureText.timeLine(r), ExposureText.ridgeLine(r, units),
        ) + r.stretches.map { ExposureText.row(it, units) }
        val lineH = 17
        val w = max(map.width, 760)
        val mx = (w - map.width) / 2 // the map centred over the profile
        val ts = window.tileSize
        fun px(lat: Double, lon: Double) = (WebMercator.lonToX(lon, zoom) * ts - window.x0 + mx) to (WebMercator.latToY(lat, zoom) * ts - window.y0)
        val image = BufferedImage(w, map.height + profileH + 16 + lineH * lines.size + 10, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.drawImage(map, mx, 0, null)

        // The route by band: a white casing, then each step in its colour; ridge steps get a dark outer line.
        val casing = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(255, 255, 255, 220)
        g.stroke = casing
        g.draw(path(points.map { px(it.lat, it.lon) }))
        for (s in r.samples) if (s.ridge) {
            g.color = ridgeInk
            g.stroke = BasicStroke(11f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(path(stepLine(points, s.fromM, s.toM).map { px(it.first, it.second) }))
        }
        for (s in r.samples) {
            g.color = bandColor(s.slopeDeg)
            g.stroke = BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(path(stepLine(points, s.fromM, s.toM).map { px(it.first, it.second) }))
        }
        // Numbered stretch markers, as the sheet lists them.
        g.font = AwtTextMeasurer.font(12.0)
        for ((i, st) in r.stretches.withIndex()) {
            val (x, y) = px(st.lat, st.lon)
            val label = "${i + 1}"
            g.color = Color.WHITE
            g.fillOval(x.toInt() - 9, y.toInt() - 9, 18, 18)
            g.color = if (st.kind == StretchKind.RIDGE) ridgeInk else ink
            g.drawOval(x.toInt() - 9, y.toInt() - 9, 18, 18)
            g.drawString(label, x.toInt() - g.fontMetrics.stringWidth(label) / 2, y.toInt() + 5)
        }

        // The profile: ground height against distance, each step filled in its band's colour, ridges as a bar above.
        val top = map.height + 8
        val left = 56
        val right = w - 16
        val plotTop = top + 44
        val plotBottom = top + profileH - 22
        val eles = r.samples.map { it.eleM }.filter { !it.isNaN() }
        val lo = eles.minOrNull() ?: 0.0
        val hi = max((eles.maxOrNull() ?: 1.0), lo + 10)
        fun xOf(m: Double) = left + (right - left) * m / r.lengthM
        fun yOf(e: Double) = plotBottom - (plotBottom - plotTop) * (e - lo) / (hi - lo)
        for (s in r.samples) {
            if (s.eleM.isNaN()) continue
            g.color = bandColor(s.slopeDeg)
            val x0 = xOf(s.fromM)
            val x1 = xOf(s.toM)
            val y = yOf(s.eleM)
            g.fillRect(x0.toInt(), y.toInt(), max(1, (x1 - x0).roundToInt()), (plotBottom - y).toInt())
            if (s.ridge) {
                g.color = ridgeInk
                g.fillRect(x0.toInt(), top + 26, max(1, (x1 - x0).roundToInt()), 10)
            }
        }
        g.color = ink
        g.stroke = BasicStroke(1.5f)
        g.drawLine(left, plotBottom, right, plotBottom)
        g.drawLine(left, plotTop, left, plotBottom)
        g.font = AwtTextMeasurer.font(11.0)
        g.drawString(UnitFormat.elevation(hi, units), 4, plotTop + 10)
        g.drawString(UnitFormat.elevation(lo, units), 4, plotBottom)
        g.drawString("0", left, plotBottom + 15)
        val end = UnitFormat.distance(r.lengthM, units)
        g.drawString(end, right - g.fontMetrics.stringWidth(end), plotBottom + 15)
        g.drawString("ridge", 4, top + 35)
        // Legend.
        var lx = left
        for ((c, t) in listOf(gentle to "under 30°", orange to "30 to 34°", red to "35° and over", ridgeInk to "along a ridge")) {
            g.color = c; g.fillRect(lx, top + 6, 14, 10)
            g.color = ink; g.drawString(t, lx + 18, top + 15)
            lx += 30 + g.fontMetrics.stringWidth(t)
        }
        var y = top + profileH + 8
        g.font = AwtTextMeasurer.font(12.0)
        val heads = lines.size - r.stretches.size
        for ((i, l) in lines.withIndex()) {
            y += lineH
            g.drawString(if (i >= heads) "${i - heads + 1}. $l" else l, 8, y)
        }
        g.dispose()
        val outFile = out ?: File("out/exposure-${gpx.nameWithoutExtension}.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile?.mkdirs()
            ImageIO.write(image, "png", outFile)
        }
        return linkedMapOf(
            "output" to outFile.path, "zoom" to zoom, "widthPx" to image.width, "heightPx" to image.height,
            "slopeShading" to style.slopeShading.key, "lines" to lines,
        )
    }

    /** The route's line from [fromM] to [toM] along it (lat, lon pairs). */
    private fun stepLine(points: List<RoutePoint>, fromM: Double, toM: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var along = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            val s0 = along
            val s1 = along + d
            along = s1
            if (s1 < fromM || s0 > toM || d <= 0) continue
            val t0 = ((fromM - s0) / d).coerceIn(0.0, 1.0)
            val t1 = ((toM - s0) / d).coerceIn(0.0, 1.0)
            if (out.isEmpty()) out += (a.lat + (b.lat - a.lat) * t0) to (a.lon + (b.lon - a.lon) * t0)
            out += (a.lat + (b.lat - a.lat) * t1) to (a.lon + (b.lon - a.lon) * t1)
        }
        return out
    }

    private fun path(xy: List<Pair<Double, Double>>): Path2D.Double = Path2D.Double().apply {
        xy.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    }

    // ---- the ridge rule's tuning maps ----

    private fun ridgeMaps(): Map<String, Any?> {
        val (name, box) = Areas.resolve(area, bbox)
        val timings = Timings()
        val demFile = demFile()
        val pyramid = RouteGraphs.pyramid(demFile)
        val zoom = ExposureParams().zoom
        val window = MercatorWindow.forBbox(box, zoom)
        val grid = timings.time("demWindow") { pyramid.window(zoom, window.x0, window.y0, window.width, window.height) }
        val (lat, _) = io.github.jasondorjeshort.dystopo.render.PageLayout.center(box)
        val cellM = ExposureParams().cellM(lat)
        val ringPx = RidgeRule.RING_M / cellM
        val offsets = (0 until RidgeRule.RING_POINTS).map { k ->
            val b = 2 * PI * k / RidgeRule.RING_POINTS
            (ringPx * sin(b)).roundToInt() to (-ringPx * cos(b)).roundToInt()
        }
        val wd = grid.width
        val ht = grid.height
        val tpi = timings.time("tpi") {
            FloatArray(wd * ht) { i ->
                val x = i % wd
                val y = i / wd
                val c = grid.values[i]
                if (c.isNaN()) return@FloatArray Float.NaN
                var sum = 0.0
                var n = 0
                for ((dx, dy) in offsets) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= wd || yy >= ht) continue
                    val v = grid.values[yy * wd + xx]
                    if (!v.isNaN()) { sum += v; n++ }
                }
                if (n < RidgeRule.MIN_RING_POINTS) Float.NaN else (c - sum / n).toFloat()
            }
        }
        val (base, _, _, _) = MapRenderer.render(grid, zoom, style, timings, window = window)
        val label = name ?: "bbox"
        val maps = ArrayList<Map<String, Any?>>()
        for (t in thresholds.split(',').mapNotNull { it.trim().toDoubleOrNull() }) {
            val img = BufferedImage(wd, ht, BufferedImage.TYPE_INT_RGB)
            img.graphics.drawImage(base, 0, 0, null)
            var marked = 0
            var valid = 0
            for (i in tpi.indices) {
                val v = tpi[i]
                if (v.isNaN()) continue
                valid++
                if (v > t) {
                    marked++
                    val x = i % wd
                    val y = i / wd
                    val o = img.getRGB(x, y)
                    // Blend 60 % of the ridge ink over the map.
                    val rr = (((o shr 16) and 255) * 0.4 + 0x24 * 0.6).toInt()
                    val gg = (((o shr 8) and 255) * 0.4 + 0x1e * 0.6).toInt()
                    val bb = ((o and 255) * 0.4 + 0xd0 * 0.6).toInt()
                    img.setRGB(x, y, (rr shl 16) or (gg shl 8) or bb)
                }
            }
            val f = File("out/exposure-ridges-$label-t${t.toInt()}.png")
            f.absoluteFile.parentFile?.mkdirs()
            ImageIO.write(img, "png", f)
            maps += linkedMapOf("thresholdM" to t, "output" to f.path, "markedShare" to if (valid == 0) 0.0 else (marked * 1000.0 / valid).roundToInt() / 1000.0)
        }
        return linkedMapOf(
            "area" to name, "bbox" to listOf(box.west, box.south, box.east, box.north), "dem" to demFile.path, "zoom" to zoom,
            "cellM" to (cellM * 100).roundToInt() / 100.0, "ringM" to RidgeRule.RING_M, "ringPx" to (ringPx * 10).roundToInt() / 10.0,
            "widthPx" to wd, "heightPx" to ht, "maps" to maps, "timingsMs" to timings.ms,
        )
    }
}
