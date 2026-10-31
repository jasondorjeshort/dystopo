package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.TerrainLayer
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.CastShadow
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import io.github.jasondorjeshort.dystopo.tiles.SunPointAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * T181: sun and shade on the terrain of an area at an instant ([CastShadow] over a fine layer and a coarse one toward
 * the sun), as the PNG of the base map with the shade in the shade tiles' cool tint, and with `--point` the point
 * answer ([ShadeTiles.pointAnswer]: the horizon sweep crossed with the day's sun path) and its horizon chart.
 */
class ShadeCommand : AreaCommand("shade") {
    override fun help(context: Context) =
        "Shade on the terrain at an instant (cast and attached shadows, night as shade): PNG with the shade as a translucent cool tint " +
            "over the hillshade + JSON; --point adds the point answer (terrain sunrise and sunset, the gaps when a peak blocks the sun)."

    private val at by option("--at", help = "The instant, ISO 8601 (2026-10-30T16:00Z)").required()
    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val point by option("--point", help = "lat,lon for the point answer (the horizon and the day's sun on the terrain)")
    private val pointZoom by option("--point-zoom", help = "DEM zoom of the point answer's fine layer").int().default(ShadeTiles.POINT_ZOOM)
    private val eye by option("--eye-m", help = "The point answer's eye height above the ground, metres").double().default(Horizon.EYE_HEIGHT_M)
    private val zone by option("--zone", help = "Time zone of the clock times in the report (the ISO instants are UTC)").default("UTC")
    private val rangeKm by option("--range-km", help = "The most the rays look toward the sun, km (each ray: the relief over the tangent of its sun's altitude, at most this; default 30)").double()
    private val nearCells by option("--near-cells", help = "Cells of the fine layer toward the sun before the coarse one").int().default(Horizon.NEAR_CELLS)
    private val coarseZooms by option("--coarse-zooms", help = "Zooms between the fine layer and the coarse one (0: the fine layer all the way)").int().default(Horizon.COARSE_ZOOMS)
    private val growth by option("--growth", help = "A ray's step as a share of its distance").double().default(Horizon.STEP_GROWTH)
    private val opacity by option("--opacity", help = "Opacity of the shade tint").double().default(ShadeTiles.SUN_SHADE_OPACITY)
    private val reference by option("--reference", help = "Also march a reference (40 km, steps of 0.5 percent, coarse layer 2 zooms down, 128 near cells) and count the cells that differ").flag()
    private val out by option("--out", help = "Output PNG (default out/<area>-shade-z<zoom>-<instant>.png)").file()

    override fun run() {
        val (name, box) = Areas.resolve(area, bbox)
        val epochMs = parseInstant(at) ?: throw UsageError("--at must be an ISO 8601 instant (got $at)")
        val clockZone = runCatching { ZoneId.of(zone) }.getOrElse { throw UsageError("--zone: unknown zone $zone") }
        val timings = Timings()
        val target = MercatorWindow.forBbox(box, zoom)
        val src = ElevationSource.byId(source)
        val sun = CastShadow.sunAt(target, epochMs)
        val cap = rangeKm?.let { it * 1000 } ?: Horizon.DEFAULT_RANGE_M

        val provenance = LinkedHashSet<String>()
        var attribution = ""
        fun read(w: MercatorWindow): io.github.jasondorjeshort.dystopo.dem.ElevationGrid {
            val r = src.fetch(w)
            provenance += r.provenance
            attribution = r.attribution(src)
            return r.grid
        }
        // Each cell marches toward its own sun (CastShadow.SunField), as the tiles do; the range follows its altitude up
        // to the cap (--range-km, else 30 km).
        fun march(near: Int, coarse: Int, capM: Double, step: Double, label: String): Pair<Terrain, CastShadow.Result> {
            val field = CastShadow.SunField(epochMs)
            val windows = CastShadow.windowsFor(target, field, capM, near, coarse)
            val terrain = timings.time("${label}fetch") { Terrain(windows.map { (w, until) -> TerrainLayer(w, read(w), until) }) }
            val result = timings.time("${label}march") { CastShadow.compute(terrain, target, field, step, capM) }
            return terrain to result
        }
        val (terrain, result) = march(nearCells, coarseZooms, cap, growth, "")
        val fine = terrain.layers[0]
        val grid = fine.grid.crop(target.x0 - fine.window.x0, target.y0 - fine.window.y0, target.width, target.height)

        val ref = if (reference) {
            val refRange = maxOf(cap, 40_000.0)
            val (_, r) = march(128, 2, refRange, 0.005, "reference.")
            var differ = 0
            var shadeOnlyHere = 0
            for (i in r.shade.indices) {
                val a = result.shade[i]
                val b = r.shade[i]
                if (a.isNaN() || b.isNaN() || a == b) continue
                differ++
                if (a == 1f) shadeOnlyHere++
            }
            linkedMapOf(
                "rangeM" to refRange.roundToLong(), "nearCells" to 128, "coarseZooms" to 2, "growth" to 0.005,
                "shadedFraction" to round4(r.shadedFraction), "steps" to r.steps, "marchMs" to round1(r.ms),
                "differingCells" to differ, "differingFraction" to round5(differ.toDouble() / maxOf(1, r.shadedCells + r.litCells)),
                "shadeOnlyInThisRun" to shadeOnlyHere, "sunOnlyInThisRun" to differ - shadeOnlyHere,
            )
        } else null

        val pointLatLon = point?.let { parseLatLon(it) }
        val answer = pointLatLon?.let { (lat, lon) ->
            val t = timings.time("point.fetch") { Horizon.terrainAround(lat, lon, pointZoom, read = ::read) }
            timings.time("point.answer") { ShadeTiles.pointAnswer(t, lat, lon, epochMs, eye) }
        }

        val stamp = Instant.ofEpochMilli(epochMs).toString().replace(":", "").replace("-", "").removeSuffix("00Z").removeSuffix("Z")
        val outFile = out ?: File("out/${name ?: "bbox"}-shade-z$zoom-$stamp.png")
        val horizonFile = File(outFile.path.removeSuffix(".png") + "-horizon.png")
        timings.time("draw") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(drawMap(grid, result, target, sun, epochMs, pointLatLon), "png", outFile)
            if (answer != null) ImageIO.write(drawHorizon(answer, epochMs), "png", horizonFile)
        }

        val iso: (Long?) -> String? = { ms -> ms?.let { Instant.ofEpochMilli(it).toString() } }
        val clock: (Long?) -> String? = { ms -> ms?.let { Instant.ofEpochMilli(it).atZone(clockZone).toLocalTime().withNano(0).toString() } }
        val cells = result.width * result.height
        val report = linkedMapOf<String, Any?>(
            "command" to "shade", "area" to name, "bbox" to listOf(box.west, box.south, box.east, box.north), "zoom" to zoom,
            "at" to iso(epochMs), "atMs" to epochMs, "source" to source,
            "sun" to linkedMapOf(
                "lat" to round4(target.latAt(target.height / 2)), "lon" to round4(target.lonAt(target.width / 2)),
                "azimuthDeg" to round2(sun.azimuthDeg), "altitudeDeg" to round2(sun.altitudeDeg), "apparentAltitudeDeg" to round2(sun.apparentAltitudeDeg),
                "night" to result.night,
            ),
            "convention" to "shade only: ${"#%06x".format(ShadeTiles.SUN_SHADE_RGB)} at opacity $opacity over the hillshade, sunlit ground untouched, night all shade",
            "rangeCapM" to cap.roundToLong(), "rangeAtCentreM" to CastShadow.rangeFor(sun.apparentAltitudeDeg, cap).roundToLong(), "nearCells" to nearCells, "coarseZooms" to coarseZooms, "growth" to growth,
            "layers" to terrain.layers.map { l ->
                linkedMapOf(
                    "zoom" to l.window.zoom, "x0" to l.window.x0, "y0" to l.window.y0, "width" to l.window.width, "height" to l.window.height,
                    "cellM" to round2(l.window.cellSizeMeters), "untilPx" to l.untilPx.takeIf { it.isFinite() },
                    "noDataCells" to l.grid.values.count { it.isNaN() },
                )
            },
            "widthPx" to result.width, "heightPx" to result.height, "cellM" to round2(target.cellSizeMeters),
            "shadedCells" to result.shadedCells, "litCells" to result.litCells, "noDataCells" to result.noDataCells,
            "shadedFraction" to round4(result.shadedFraction),
            "steps" to result.steps, "stepsPerCell" to round1(result.steps.toDouble() / maxOf(1, cells)),
            "marchMs" to round1(result.ms), "marchMsPer256Tile" to round1(result.ms * 65_536 / maxOf(1, cells)),
            "reference" to ref,
            "point" to answer?.let { pointReport(it, epochMs, target, result, iso, clock, horizonFile) },
            "output" to outFile.path,
            "timingsMs" to timings.ms,
            "provenance" to provenance.toList(),
            "attribution" to attribution,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun pointReport(
        a: SunPointAnswer, epochMs: Long, target: MercatorWindow, result: CastShadow.Result,
        iso: (Long?) -> String?, clock: (Long?) -> String?, horizonFile: File,
    ): Map<String, Any?> {
        val h = a.horizon
        val d = a.day
        val flat = d.flat
        val sunNow = Sun.position(h.lat, h.lon, epochMs)
        val upNow = sunNow.apparentAltitudeDeg + Sun.SEMI_DIAMETER_DEG > h.angleAt(sunNow.azimuthDeg)
        // The overlay at the same point (when it lies in the area): the cell the point is in.
        val gx = WebMercator.lonToX(h.lon, target.zoom) * target.tileSize - target.x0
        val gy = WebMercator.latToY(h.lat, target.zoom) * target.tileSize - target.y0
        val overlay = if (gx >= 0 && gy >= 0 && gx < target.width && gy < target.height) {
            result.shade[gy.toInt() * target.width + gx.toInt()].let { if (it.isNaN()) null else it == 1f }
        } else null
        val top = h.highest
        val riseAz = d.sunriseMs?.let { Sun.position(h.lat, h.lon, it).azimuthDeg }
        val setAz = d.sunsetMs?.let { Sun.position(h.lat, h.lon, it).azimuthDeg }
        return linkedMapOf(
            "lat" to h.lat, "lon" to h.lon, "groundM" to round1(h.groundM), "eyeM" to h.observerHeightM, "zoom" to a.zoom,
            "date" to "%04d-%02d-%02d".format(flat.year, flat.month, flat.day),
            "flat" to linkedMapOf("sunrise" to iso(flat.sunriseMs), "noon" to iso(flat.noonMs), "sunset" to iso(flat.sunsetMs),
                "sunriseClock" to clock(flat.sunriseMs), "sunsetClock" to clock(flat.sunsetMs)),
            "terrain" to linkedMapOf("sunrise" to iso(d.sunriseMs), "sunset" to iso(d.sunsetMs),
                "sunriseClock" to clock(d.sunriseMs), "sunsetClock" to clock(d.sunsetMs),
                "sunriseAzimuthDeg" to riseAz?.let(::round1), "sunriseHorizonDeg" to riseAz?.let { round2(h.angleAt(it)) },
                "sunsetAzimuthDeg" to setAz?.let(::round1), "sunsetHorizonDeg" to setAz?.let { round2(h.angleAt(it)) },
                "alwaysUp" to d.alwaysUp),
            "sunriseLaterByMin" to if (d.sunriseMs != null && flat.sunriseMs != null) round1((d.sunriseMs!! - flat.sunriseMs!!) / 60_000.0) else null,
            "sunsetEarlierByMin" to if (d.sunsetMs != null && flat.sunsetMs != null) round1((flat.sunsetMs!! - d.sunsetMs!!) / 60_000.0) else null,
            "sunMinutes" to round1(d.sunMinutes),
            "sunlit" to d.sunlit.map { linkedMapOf("from" to iso(it.startMs), "to" to iso(it.endMs), "fromClock" to clock(it.startMs), "toClock" to clock(it.endMs)) },
            "gaps" to d.gaps.map {
                linkedMapOf("from" to iso(it.span.startMs), "to" to iso(it.span.endMs), "fromClock" to clock(it.span.startMs), "toClock" to clock(it.span.endMs),
                    "minutes" to round1(it.span.minutes), "bearingDeg" to round1(it.bearingDeg), "horizonDeg" to round2(it.horizonDeg))
            },
            "atInstant" to linkedMapOf("sunAzimuthDeg" to round2(sunNow.azimuthDeg), "sunApparentAltitudeDeg" to round2(sunNow.apparentAltitudeDeg),
                "horizonDeg" to round2(h.angleAt(sunNow.azimuthDeg)), "sunUp" to upNow, "overlayShade" to overlay),
            "horizon" to linkedMapOf(
                "stepDeg" to h.stepDeg, "rangeM" to h.rangeM.roundToLong(), "samples" to h.samples, "bearingsWithoutData" to h.bearingsWithoutData,
                "highest" to linkedMapOf("bearingDeg" to h.bearingDeg(top), "angleDeg" to round2(h.angleDeg[top]), "distanceM" to h.distanceM[top].roundToLong()),
                "lowestDeg" to round2(h.angleDeg.min()), "minReachedM" to h.reachedM.min().roundToLong(),
                "byOctant" to (0 until 8).associate { o -> listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[o] to round2(h.angleAt(o * 45.0)) },
            ),
            "sweepMs" to round1(a.sweepMs), "sunEvaluations" to d.evaluations,
            "horizonPng" to horizonFile.path,
        )
    }

    /** The base map with the shade tint, a sun arrow and the point marked. */
    private fun drawMap(
        grid: io.github.jasondorjeshort.dystopo.dem.ElevationGrid, r: CastShadow.Result, target: MercatorWindow,
        sun: Sun.Position, epochMs: Long, point: Pair<Double, Double>?,
    ): BufferedImage {
        val pixels = Shading.sunShade(grid, r.shade, opacity)
        val image = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, grid.width, grid.height, pixels, 0, grid.width)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // The sun's direction in the top right corner: an arrow from the centre toward the sun.
        val cx = grid.width - 60.0
        val cy = 60.0
        g.color = Color(255, 255, 255, 210)
        g.fill(Ellipse2D.Double(cx - 48, cy - 48, 96.0, 96.0))
        g.color = Color(60, 60, 60)
        g.stroke = BasicStroke(1.2f)
        g.draw(Ellipse2D.Double(cx - 48, cy - 48, 96.0, 96.0))
        val ex = cx + 36 * sin(Math.toRadians(sun.azimuthDeg))
        val ey = cy - 36 * cos(Math.toRadians(sun.azimuthDeg))
        g.color = Color(230, 150, 0)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Double(cx, cy, ex, ey))
        g.fill(Ellipse2D.Double(ex - 7, ey - 7, 14.0, 14.0))
        g.font = RenderFonts.bold(11.0)
        g.color = Color(40, 40, 40)
        g.drawString("N", (cx - 4).toFloat(), (cy - 36).toFloat())
        g.font = RenderFonts.regular(12.0)
        val label = "${Instant.ofEpochMilli(epochMs)}  sun ${sun.azimuthDeg.roundToInt()}°, ${"%.1f".format(sun.apparentAltitudeDeg)}° up"
        g.color = Color(255, 255, 255, 210)
        g.fillRect(6, grid.height - 24, g.fontMetrics.stringWidth(label) + 10, 18)
        g.color = Color(30, 30, 30)
        g.drawString(label, 11, grid.height - 10)
        if (point != null) {
            val px = WebMercator.lonToX(point.second, target.zoom) * target.tileSize - target.x0
            val py = WebMercator.latToY(point.first, target.zoom) * target.tileSize - target.y0
            g.color = Color(200, 0, 0)
            g.stroke = BasicStroke(2.5f)
            g.draw(Ellipse2D.Double(px - 7, py - 7, 14.0, 14.0))
        }
        g.dispose()
        return image
    }

    /** The horizon around the point (grey below it) and the day's sun path: yellow while up, blue behind the terrain. */
    private fun drawHorizon(a: SunPointAnswer, epochMs: Long): BufferedImage {
        val h: HorizonProfile = a.horizon
        val d = a.day
        val path = ArrayList<Triple<Double, Double, Boolean>>()
        val from = (d.flat.sunriseMs ?: (d.flat.noonMs - 43_200_000L)) - 3_600_000L
        val to = (d.flat.sunsetMs ?: (d.flat.noonMs + 43_200_000L)) + 3_600_000L
        var t = from
        while (t <= to) {
            val p = Sun.position(h.lat, h.lon, t)
            path += Triple(p.azimuthDeg, p.apparentAltitudeDeg, p.apparentAltitudeDeg + Sun.SEMI_DIAMETER_DEG > h.angleAt(p.azimuthDeg))
            t += 5 * 60_000L
        }
        val top = (maxOf(h.angleDeg.max(), path.maxOf { it.second }) + 4).coerceAtLeast(10.0)
        val bottom = (minOf(h.angleDeg.min(), -2.0) - 2)
        val w = 720
        val ht = 300
        val left = 40
        val plotH = ht - 30
        fun x(az: Double) = left + az / 360.0 * (w - left - 10)
        fun y(alt: Double) = 10 + (top - alt) / (top - bottom) * (plotH - 10)
        val image = BufferedImage(w, ht, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(236, 244, 252)
        g.fillRect(0, 0, w, ht)
        val terrain = Path2D.Double()
        terrain.moveTo(x(0.0), y(bottom))
        for (i in 0..h.size) terrain.lineTo(x(i * h.stepDeg), y(h.angleDeg[i % h.size]))
        terrain.lineTo(x(360.0), y(bottom))
        terrain.closePath()
        g.color = Color(150, 140, 125)
        g.fill(terrain)
        g.color = Color(120, 120, 120)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(4f, 4f), 0f)
        g.draw(Line2D.Double(x(0.0), y(0.0), x(360.0), y(0.0)))
        for (p in path) {
            g.color = if (p.third) Color(245, 180, 0) else Color(40, 80, 170)
            g.fill(Ellipse2D.Double(x(p.first) - 2.5, y(p.second) - 2.5, 5.0, 5.0))
        }
        val now = Sun.position(h.lat, h.lon, epochMs)
        g.color = Color(200, 0, 0)
        g.stroke = BasicStroke(2f)
        g.draw(Ellipse2D.Double(x(now.azimuthDeg) - 6, y(now.apparentAltitudeDeg) - 6, 12.0, 12.0))
        g.font = RenderFonts.regular(11.0)
        g.color = Color(30, 30, 30)
        for ((k, n) in listOf("N", "E", "S", "W", "N").withIndex()) g.drawString(n, (x(k * 90.0) - 4).toFloat(), (ht - 8).toFloat())
        var a0 = (bottom / 5).toInt() * 5
        while (a0 <= top) {
            g.drawString("$a0°", 4f, (y(a0.toDouble()) + 4).toFloat())
            a0 += 5
        }
        g.dispose()
        return image
    }

    private fun parseLatLon(s: String): Pair<Double, Double> {
        val p = s.split(",").map { it.trim().toDoubleOrNull() }
        if (p.size != 2 || p.any { it == null }) throw UsageError("--point must be lat,lon (got $s)")
        return p[0]!! to p[1]!!
    }

    private fun parseInstant(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
    private fun round2(d: Double) = (d * 100).roundToLong() / 100.0
    private fun round4(d: Double) = (d * 10_000).roundToLong() / 10_000.0
    private fun round5(d: Double) = (d * 100_000).roundToLong() / 100_000.0
}
