package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.ShadeAlong
import io.github.jasondorjeshort.dystopo.routing.ShadeAlongResult
import io.github.jasondorjeshort.dystopo.routing.ShadeEvent
import io.github.jasondorjeshort.dystopo.routing.ShadeLight
import io.github.jasondorjeshort.dystopo.routing.ShadeText
import io.github.jasondorjeshort.dystopo.routing.TravelMode
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
 *
 * T194: `--gpx <route> --start <ISO>` is terrain shade along a route ([ShadeAlong]: a ray toward the sun every 25 m at
 * the time the walker reaches it at the route's standard pace), its stretches, the first sun-loss and sun-gain, the
 * card's line and the spoken line's candidates, with a PNG of the route over the overlay at the start instant above the
 * elevation profile in the light met (`--frozen`: every sample at the start, to check against the overlay); `--source`
 * also takes a DEM pack. `--tile-bench` times the sun tiles of the area over a pack for each `--low-sun-km` cap.
 */
class ShadeCommand : AreaCommand("shade") {
    override fun help(context: Context) =
        "Shade on the terrain at an instant (cast and attached shadows, night as shade): PNG with the shade as a translucent cool tint " +
            "over the hillshade + JSON; --point adds the point answer (terrain sunrise and sunset, the gaps when a peak blocks the sun). " +
            "T194: --gpx <route> --start <ISO> gives the terrain shade along the route at the time the walker reaches each point " +
            "(stretches, the first sun-loss and sun-gain, a map and profile PNG); --tile-bench times the sun tiles over a DEM pack."

    private val at by option("--at", help = "The instant, ISO 8601 (2026-10-30T16:00Z); not with --gpx")
    private val source by option("--source", help = "Elevation source id, or (T194) a DEM pack .pmtiles read as the phone reads its pack").default("best")
    private val gpx by option("--gpx", help = "T194: terrain shade along this GPX's first route (or track) at the time the walker reaches each point; needs --start").file(mustExist = true, canBeDir = false)
    private val start by option("--start", help = "T194: with --gpx, the instant the walk starts, ISO 8601")
    private val fromM by option("--from-m", help = "T194: with --gpx, start this far along the line (default 0)").double().default(0.0)
    private val mode by option("--mode", help = "T194: with --gpx, the pace: hike (default) or bike").choice("hike", "bike").default("hike")
    private val frozen by option("--frozen", help = "T194: with --gpx, every sample at --start (the overlay's one instant, for a side-by-side check)").flag()
    private val tileBench by option("--tile-bench", help = "T194: time the sun tiles of the area at --zoom over the DEM pack --source names, once per --low-sun-km").flag()
    private val lowSunKm by option("--low-sun-km", help = "T194: with --tile-bench, the low-sun range caps to compare, km (0: uncapped, before T194; default 0,15)").default("0,${(Horizon.LOW_SUN_RANGE_M / 1000).roundToInt()}")
    private val benchTiles by option("--bench-tiles", help = "T194: with --tile-bench, at most this many tiles (default 16)").int().default(16)
    private val point by option("--point", help = "lat,lon for the point answer (the horizon and the day's sun on the terrain)")
    private val pointZoom by option("--point-zoom", help = "DEM zoom of the point answer's fine layer").int().default(ShadeTiles.POINT_ZOOM)
    private val pointCoarseZooms by option("--point-coarse-zooms", help = "Zooms between the point answer's fine layer and its coarse one").int().default(Horizon.COARSE_ZOOMS)
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
        if (gpx != null) return alongRoute(gpx!!)
        if (tileBench) return bench()
        val at = at ?: throw UsageError("--at is required (or --gpx with --start)")
        val (name, box) = Areas.resolve(area, bbox)
        val epochMs = parseInstant(at) ?: throw UsageError("--at must be an ISO 8601 instant (got $at)")
        val clockZone = runCatching { ZoneId.of(zone) }.getOrElse { throw UsageError("--zone: unknown zone $zone") }
        val timings = Timings()
        val target = MercatorWindow.forBbox(box, zoom)
        val src = if (packFile() == null) elevationSource() else null
        val sun = CastShadow.sunAt(target, epochMs)
        val cap = rangeKm?.let { it * 1000 } ?: Horizon.DEFAULT_RANGE_M

        val provenance = LinkedHashSet<String>()
        var attribution = ""
        val pyramid = packFile()?.let { RouteGraphs.pyramid(it) }
        fun read(w: MercatorWindow): io.github.jasondorjeshort.dystopo.dem.ElevationGrid {
            if (pyramid != null) return pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height)
            val r = src!!.fetch(w)
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
            val t = timings.time("point.fetch") { Horizon.terrainAround(lat, lon, pointZoom, coarseZooms = pointCoarseZooms, read = ::read) }
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
            "steps" to result.steps, "stepsPerCell" to round1(result.steps.toDouble() / maxOf(1, cells)), "skipsPerCell" to round1(result.skips.toDouble() / maxOf(1, cells)),
            "marchMs" to round1(result.ms), "marchMsPer256Tile" to round1(result.ms * 65_536 / maxOf(1, cells)),
            "reference" to ref,
            "point" to answer?.let { pointReport(it, epochMs, target, result, iso, clock, horizonFile) + ("coarseZooms" to pointCoarseZooms) },
            "output" to outFile.path,
            "timingsMs" to timings.ms,
            "provenance" to provenance.toList(),
            "attribution" to attribution,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    /** T194: the DEM pack `--source` names (a .pmtiles file), or null for an elevation source id. */
    private fun packFile(): File? {
        val f = File(source)
        val pack = source.endsWith(".pmtiles") || f.isFile
        if (pack && !f.isFile) throw UsageError("--source: no DEM pack at $source")
        return if (pack) f else null
    }

    private fun elevationSource(): ElevationSource {
        if (source !in ElevationSource.ids) throw UsageError("--source must be a DEM pack (.pmtiles) or one of ${ElevationSource.ids.joinToString()}")
        return ElevationSource.byId(source)
    }

    /**
     * T194: terrain shade along [file]'s first route (else its first track) for a walk starting at `--start`
     * ([ShadeAlong.compute] at the standard pace of `--mode`, over the pack or the source as the phone reads its pack):
     * the stretches, the first sun-loss and sun-gain as JSON, and a PNG of the route on the shade overlay at the start
     * instant above the elevation profile coloured by the light the walker meets.
     */
    private fun alongRoute(file: File) {
        val startMs = start?.let { parseInstant(it) ?: throw UsageError("--start must be an ISO 8601 instant (got $it)") }
            ?: throw UsageError("--gpx needs --start")
        val clockZone = runCatching { ZoneId.of(zone) }.getOrElse { throw UsageError("--zone: unknown zone $zone") }
        val timings = Timings()
        val doc = io.github.jasondorjeshort.dystopo.gpx.Gpx.parse(file.readText(Charsets.UTF_8))
        val route = doc.routes.firstOrNull()?.takeIf { it.points.size >= 2 }
        val track = doc.tracks.firstOrNull()
        val path = when {
            route != null -> NavPath.of(route, doc.waypoints)
            track != null && track.points.size >= 2 -> NavPath.of(track, doc.waypoints)
            else -> throw UsageError("${file.path} has no route or track with two points")
        }
        val travel = if (mode == "bike") TravelMode.BIKE else TravelMode.HIKE
        val timing = TurnAround(path, TurnPace.of(CostModel(travel)))
        val pack = packFile()
        val pyramid = pack?.let { RouteGraphs.pyramid(it) }
        val src = if (pack == null) elevationSource() else null
        val provenance = LinkedHashSet<String>()
        val read: (MercatorWindow) -> io.github.jasondorjeshort.dystopo.dem.ElevationGrid = { w ->
            if (pyramid != null) pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height)
            else src!!.fetch(w).also { provenance += it.provenance }.grid
        }
        val maxZoom = minOf(pyramid?.maxZoom ?: ShadeAlong.MAX_ZOOM, ShadeAlong.MAX_ZOOM)
        val r = timings.time("along") { ShadeAlong.compute(path, timing, fromM, startMs, maxZoom, read, frozen = frozen) }
        // The overlay at the start instant over the route's box, as the phone's shade tiles draw it.
        val (mapImage, overlayAgree) = timings.time("overlay") { drawRouteMap(r, startMs, r.stats.zoom, read) }
        val profile = drawProfile(r, path, clockZone)
        val stamp = Instant.ofEpochMilli(startMs).toString().replace(":", "").replace("-", "").removeSuffix("00Z").removeSuffix("Z")
        val outFile = out ?: File("out/${file.nameWithoutExtension}-shade-$stamp${if (frozen) "-frozen" else ""}.png")
        timings.time("draw") {
            outFile.absoluteFile.parentFile.mkdirs()
            val w = maxOf(mapImage.width, profile.width)
            val img = BufferedImage(w, mapImage.height + profile.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            g.drawImage(mapImage, (w - mapImage.width) / 2, 0, null)
            g.drawImage(profile, 0, mapImage.height, null)
            g.dispose()
            ImageIO.write(img, "png", outFile)
        }
        val clock: (Long) -> String = { ms -> Instant.ofEpochMilli(ms).atZone(clockZone).toLocalTime().withNano(0).toString() }
        val units = io.github.jasondorjeshort.dystopo.render.ContourUnits.METERS
        val report = linkedMapOf<String, Any?>(
            "command" to "shade", "gpx" to file.path, "name" to path.name, "lengthM" to round1(path.totalM),
            "ascentM" to round1(path.totalAscentM), "mode" to mode, "pace" to timing.pace.label, "walkTimeS" to timing.totalS.roundToLong(),
            "start" to Instant.ofEpochMilli(startMs).toString(), "startClock" to clock(startMs), "zone" to zone, "source" to source,
            "end" to Instant.ofEpochMilli(r.samples.last().atMs).toString(), "endClock" to clock(r.samples.last().atMs),
        ) + r.json(clock) + linkedMapOf(
            "card" to ShadeText.cardLine(r, r.fromM, units),
            "spokenCandidates" to r.stretches.indices.filter { i ->
                i > 0 && r.stretches[i].light == ShadeLight.SUN && r.stretches[i - 1].light == ShadeLight.SHADE && r.stretches[i].minutes > ShadeText.LONG_SUN_MIN
            }.map { i -> linkedMapOf("index" to i, "fromM" to round1(r.stretches[i].fromM), "text" to ShadeText.spoken(r, i, ShadeText.LEAD_M, units)) },
            "overlayAtStart" to overlayAgree,
            "output" to outFile.path, "timingsMs" to timings.ms, "provenance" to provenance.toList(),
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    /**
     * T194: the route's box with a margin, the hillshade with the shade overlay at [startMs] (each cell's own sun, as the
     * tiles), the route drawn in the light each sample met (orange sun, dark blue terrain shade, black sun down, grey no
     * data) and the first sun-loss and sun-gain marked; with how many samples agree with the overlay's cell under them
     * (all of them with `--frozen`, up to the ray's sub-cell start).
     */
    private fun drawRouteMap(
        r: ShadeAlongResult, startMs: Long, zoom: Int, read: (MercatorWindow) -> io.github.jasondorjeshort.dystopo.dem.ElevationGrid,
    ): Pair<BufferedImage, Map<String, Any?>> {
        val lats = r.samples.map { it.lat }
        val lons = r.samples.map { it.lon }
        // The route's box with a margin, the narrower side widened to three quarters of the longer (in metres).
        val k = cos(Math.toRadians(lats.average()))
        var halfLat = (lats.max() - lats.min()) * 0.65 + 0.002
        var halfLon = (lons.max() - lons.min()) * 0.65 + 0.003
        if (halfLon * k < 0.75 * halfLat) halfLon = 0.75 * halfLat / k
        if (halfLat < 0.75 * halfLon * k) halfLat = 0.75 * halfLon * k
        val cLat = (lats.max() + lats.min()) / 2
        val cLon = (lons.max() + lons.min()) / 2
        val box = io.github.jasondorjeshort.dystopo.geo.BoundingBox(cLon - halfLon, cLat - halfLat, cLon + halfLon, cLat + halfLat)
        val target = MercatorWindow.forBbox(box, zoom)
        val field = CastShadow.SunField(startMs)
        val terrain = Terrain(CastShadow.windowsFor(target, field).map { (w, until) -> TerrainLayer(w, read(w), until) })
        val shade = CastShadow.compute(terrain, target, CastShadow.SunField(startMs))
        val fine = terrain.layers[0]
        val grid = fine.grid.crop(target.x0 - fine.window.x0, target.y0 - fine.window.y0, target.width, target.height)
        val pixels = Shading.sunShade(grid, shade.shade, opacity)
        val base = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
        base.setRGB(0, 0, grid.width, grid.height, pixels, 0, grid.width)
        val scale = maxOf(1, minOf(4, 720 / maxOf(grid.width, grid.height)))
        val img = BufferedImage(grid.width * scale, grid.height * scale, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(base, 0, 0, img.width, img.height, null)
        fun px(lon: Double) = (WebMercator.lonToX(lon, zoom) * target.tileSize - target.x0) * scale
        fun py(lat: Double) = (WebMercator.latToY(lat, zoom) * target.tileSize - target.y0) * scale
        var agree = 0
        var compared = 0
        for (s in r.samples) {
            val i = (WebMercator.lonToX(s.lon, zoom) * target.tileSize - target.x0).toInt()
            val j = (WebMercator.latToY(s.lat, zoom) * target.tileSize - target.y0).toInt()
            if (i !in 0 until target.width || j !in 0 until target.height) continue
            val v = shade.shade[j * target.width + i]
            if (v.isNaN() || s.light == ShadeLight.NO_DATA) continue
            compared++
            if ((v == 1f) == (s.light != ShadeLight.SUN)) agree++
        }
        g.stroke = BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(255, 255, 255, 230)
        for (k in 1 until r.samples.size) g.draw(Line2D.Double(px(r.samples[k - 1].lon), py(r.samples[k - 1].lat), px(r.samples[k].lon), py(r.samples[k].lat)))
        g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (k in 1 until r.samples.size) {
            g.color = lightColour(r.samples[k - 1].light)
            g.draw(Line2D.Double(px(r.samples[k - 1].lon), py(r.samples[k - 1].lat), px(r.samples[k].lon), py(r.samples[k].lat)))
        }
        fun mark(e: ShadeEvent?, label: String) {
            e ?: return
            g.color = Color(200, 0, 0)
            g.stroke = BasicStroke(2.5f)
            g.draw(Ellipse2D.Double(px(e.lon) - 8, py(e.lat) - 8, 16.0, 16.0))
            g.font = RenderFonts.bold(13.0)
            g.color = Color(255, 255, 255, 220)
            g.fillRect(px(e.lon).toInt() + 10, py(e.lat).toInt() - 12, g.fontMetrics.stringWidth(label) + 6, 17)
            g.color = Color(150, 0, 0)
            g.drawString(label, px(e.lon).toFloat() + 13, py(e.lat).toFloat() + 1)
        }
        mark(r.firstSunLoss, "sun lost")
        mark(r.firstSunGain, "sun back")
        val sun = CastShadow.sunAt(target, startMs)
        g.font = RenderFonts.regular(12.0)
        val lines = listOf(
            "overlay at ${Instant.ofEpochMilli(startMs)}, sun ${sun.azimuthDeg.roundToInt()}°, ${"%.1f".format(sun.apparentAltitudeDeg)}° up",
            "line: the light when reached" + if (r.frozen) " (frozen at the start)" else "",
        )
        for ((n, text) in lines.withIndex()) {
            val yy = img.height - 10 - (lines.size - 1 - n) * 18
            g.color = Color(255, 255, 255, 220)
            g.fillRect(6, yy - 14, g.fontMetrics.stringWidth(text) + 10, 18)
            g.color = Color(30, 30, 30)
            g.drawString(text, 11, yy)
        }
        g.dispose()
        return img to linkedMapOf("compared" to compared, "agree" to agree, "zoom" to zoom, "shadedFraction" to round4(shade.shadedFraction))
    }

    /** T194: the elevation profile along the line from the walk's start, filled in the light each sample met, with clock ticks and the stretches. */
    private fun drawProfile(r: ShadeAlongResult, path: NavPath, zone: ZoneId): BufferedImage {
        val w = 960
        val h = 300
        val left = 56.0
        val right = 16.0
        val top = 40.0
        val bottom = 64.0
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        val eles = r.samples.map { it.eleM ?: path.pointAt(it.alongM).ele ?: 0.0 }
        val lo = (eles.min() / 50).toInt() * 50.0 - 50
        val hi = (eles.max() / 50).toInt() * 50.0 + 100
        val span = (r.toM - r.fromM).coerceAtLeast(1.0)
        fun x(d: Double) = left + (d - r.fromM) / span * (w - left - right)
        fun y(e: Double) = top + (hi - e) / (hi - lo) * (h - top - bottom)
        // The light bar under the axis and the profile's fill, sample by sample.
        for (k in r.samples.indices) {
            val a = if (k == 0) r.fromM else (r.samples[k - 1].alongM + r.samples[k].alongM) / 2
            val b = if (k == r.samples.size - 1) r.toM else (r.samples[k].alongM + r.samples[k + 1].alongM) / 2
            val c = lightColour(r.samples[k].light)
            g.color = Color(c.red, c.green, c.blue, 110)
            val path2 = Path2D.Double()
            path2.moveTo(x(a), y(lo))
            path2.lineTo(x(a), y(eles[maxOf(0, k - 1)].let { (it + eles[k]) / 2 }))
            path2.lineTo(x(r.samples[k].alongM), y(eles[k]))
            path2.lineTo(x(b), y(eles[minOf(eles.size - 1, k + 1)].let { (it + eles[k]) / 2 }))
            path2.lineTo(x(b), y(lo))
            path2.closePath()
            g.fill(path2)
        }
        // The merged stretches as a bar below the plot.
        for (s in r.stretches) {
            g.color = lightColour(s.light)
            g.fill(java.awt.geom.Rectangle2D.Double(x(s.fromM), h - bottom + 6, x(s.toM) - x(s.fromM), 12.0))
        }
        g.color = Color(40, 40, 40)
        g.stroke = BasicStroke(2f)
        val line = Path2D.Double()
        for (k in r.samples.indices) if (k == 0) line.moveTo(x(r.samples[k].alongM), y(eles[k])) else line.lineTo(x(r.samples[k].alongM), y(eles[k]))
        g.draw(line)
        g.font = RenderFonts.regular(11.0)
        var e0 = (lo / 100).toInt() * 100.0
        while (e0 <= hi) {
            if (e0 >= lo) {
                g.color = Color(225, 225, 225)
                g.stroke = BasicStroke(1f)
                g.draw(Line2D.Double(left, y(e0), w - right, y(e0)))
                g.color = Color(60, 60, 60)
                g.drawString("${e0.toInt()} m", 6f, (y(e0) + 4).toFloat())
            }
            e0 += 100.0
        }
        // Distance ticks every 500 m and the clock every 15 minutes along the bottom.
        var d = (r.fromM / 500).toInt() * 500.0
        while (d <= r.toM) {
            if (d >= r.fromM) {
                g.color = Color(60, 60, 60)
                g.drawString(if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000), (x(d) - 12).toFloat(), (h - bottom + 32).toFloat())
            }
            d += 500.0
        }
        val t0 = r.samples.first().atMs
        val t1 = r.samples.last().atMs
        var t = (t0 / 900_000L + 1) * 900_000L
        while (t <= t1) {
            val i = r.samples.indexOfFirst { it.atMs >= t }.coerceAtLeast(0)
            val xd = x(r.samples[i].alongM)
            g.color = Color(120, 120, 120)
            g.draw(Line2D.Double(xd, top, xd, top + 6))
            g.drawString(Instant.ofEpochMilli(t).atZone(zone).toLocalTime().toString(), (xd - 14).toFloat(), (top - 4).toFloat())
            t += 900_000L
        }
        g.font = RenderFonts.bold(12.0)
        g.color = Color(30, 30, 30)
        val legend = "${path.name}: ${Instant.ofEpochMilli(t0).atZone(zone).toLocalTime().withNano(0)} to ${Instant.ofEpochMilli(t1).atZone(zone).toLocalTime().withNano(0)} ($zone)" +
            "   sun / terrain shade / sun down; bar: stretches (under ${ShadeAlong.ABSORB_M.toInt()} m absorbed)"
        g.drawString(legend, left.toFloat(), (h - 10).toFloat())
        g.dispose()
        return img
    }

    private fun lightColour(l: ShadeLight): Color = when (l) {
        ShadeLight.SUN -> Color(235, 160, 20)
        ShadeLight.SHADE -> Color(30, 58, 110)
        ShadeLight.DOWN -> Color(20, 20, 20)
        ShadeLight.NO_DATA -> Color(160, 160, 160)
    }

    /**
     * T194: the phone's sun tile time below and above the low-sun cap: every tile of the area at `--zoom` (at most
     * `--bench-tiles`) made from the DEM pack as the phone's `ShadeService` makes it (plan, terrain read, march), once
     * per cap in `--low-sun-km` (0: uncapped, before T194), each cap twice so the second pass reads warm DEM tiles.
     */
    private fun bench() {
        val pack = packFile() ?: throw UsageError("--tile-bench needs --source <DEM pack .pmtiles>")
        val epochMs = at?.let { parseInstant(it) ?: throw UsageError("--at must be an ISO 8601 instant (got $it)") } ?: throw UsageError("--tile-bench needs --at")
        val (name, box) = Areas.resolve(area, bbox)
        val caps = lowSunKm.split(",").map { it.trim().toDoubleOrNull() ?: throw UsageError("--low-sun-km must be numbers (got $lowSunKm)") }
        val z = zoom
        val x0 = WebMercator.lonToX(box.west, z).toInt()
        val x1 = WebMercator.lonToX(box.east, z).toInt()
        val y0 = WebMercator.latToY(box.north, z).toInt()
        val y1 = WebMercator.latToY(box.south, z).toInt()
        val tiles = (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }.take(benchTiles)
        val pyramid = RouteGraphs.pyramid(pack)
        val o = io.github.jasondorjeshort.dystopo.tiles.SunShadeOptions(epochMs)
        val runs = caps.map { km ->
            var last: List<Map<String, Any?>> = emptyList()
            for (pass in 1..2) {
                last = tiles.map { (x, y) ->
                    val t0 = System.nanoTime()
                    val plan = ShadeTiles.sunPlan(z, x, y, pyramid.maxZoom, o, lowSunM = km * 1000)
                    val terrain = ShadeTiles.sunSample(pyramid, plan)
                    val t1 = System.nanoTime()
                    val tile = ShadeTiles.sunRender(terrain, plan, o)
                    val t2 = System.nanoTime()
                    linkedMapOf(
                        "tile" to "$z/$x/$y", "altitudeDeg" to round2(plan.sun.apparentAltitudeDeg), "sampleMs" to round1((t1 - t0) / 1e6),
                        "marchMs" to round1((t2 - t1) / 1e6), "ms" to round1((t2 - t0) / 1e6),
                        "cellsRead" to plan.windows.sumOf { it.first.width.toLong() * it.first.height }, "steps" to tile.marchSteps, "shadowPixels" to tile.shadowPixels,
                    )
                }
            }
            fun avg(k: String) = round1(last.map { (it[k] as Number).toDouble() }.average())
            linkedMapOf(
                "lowSunKm" to km, "tiles" to last.size, "avgMs" to avg("ms"), "maxMs" to last.maxOf { it["ms"] as Double },
                "avgSampleMs" to avg("sampleMs"), "avgMarchMs" to avg("marchMs"), "avgCellsRead" to last.map { (it["cellsRead"] as Long).toDouble() }.average().roundToLong(),
                "avgSteps" to last.map { (it["steps"] as Long).toDouble() }.average().roundToLong(),
                "shadowPixels" to last.sumOf { it["shadowPixels"] as Int }, "perTile" to last,
            )
        }
        val report = linkedMapOf<String, Any?>(
            "command" to "shade", "tileBench" to true, "area" to name, "zoom" to z, "at" to Instant.ofEpochMilli(epochMs).toString(),
            "pack" to pack.path, "packMaxZoom" to pyramid.maxZoom, "lowSunDeg" to Horizon.LOW_SUN_DEG, "runs" to runs,
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
