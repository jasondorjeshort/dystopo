package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.GpxException
import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.SmoothingOptions
import io.github.jasondorjeshort.dystopo.gpx.StatsOptions
import io.github.jasondorjeshort.dystopo.gpx.StopOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.gpx.TrackSmoothing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.gpx.TrackStops
import io.github.jasondorjeshort.dystopo.gpx.XmlException
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

@Serializable
data class GpxProfilePoint(val distanceM: Double, val eleM: Double?)

/** Stats of one track or route, rounded for reading. */
@Serializable
data class GpxItemStats(
    val kind: String,
    val name: String,
    val id: String,
    val segments: Int,
    val points: Int,
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    val startTime: String?,
    val endTime: String?,
    val durationS: Double?,
    val duration: String?,
    val movingTimeS: Double?,
    val movingTime: String?,
    val movingSpeedKmh: Double?,
    /** Moving pace, minutes:seconds per kilometer. */
    val movingPacePerKm: String?,
    val overallPacePerKm: String?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    /** west, south, east, north */
    val bbox: List<Double>?,
    val profile: List<GpxProfilePoint>? = null,
)

@Serializable
data class GpxWaypointInfo(val name: String, val lat: Double, val lon: Double, val eleM: Double?, val symbol: String?, val id: String)

@Serializable
data class GpxInfoReport(
    val file: String,
    val creator: String,
    val metadataName: String?,
    val waypoints: List<GpxWaypointInfo>,
    val tracks: List<GpxItemStats>,
    val routes: List<GpxItemStats>,
    /** Elements skipped on import (not in the model) and points dropped, by name. */
    val ignored: Map<String, Int>,
    val statsRules: Map<String, Double>,
    val timingsMs: Map<String, Long>,
)

@Serializable
data class GpxOverlayStats(
    val trackPoints: Int,
    val routePoints: Int,
    val arrows: Int,
    val waypointsDrawn: Int,
    /** Waypoints outside the rendered window. */
    val waypointsOutside: Int,
    /** Track and route points outside the rendered window (clipped). */
    val pointsOutside: Int,
    val profileSamples: Int,
    /** Units of the profile's axis labels: the map's contour units (`--units`). */
    val profileUnits: String = "m",
    /** The profile's axis labels as drawn: highest and lowest elevation, then the distance ticks. */
    val profileLabels: List<String> = emptyList(),
)

@Serializable
data class GpxRenderReport(
    val file: String,
    val render: RenderReport,
    val overlay: GpxOverlayStats,
    val tracks: List<GpxItemStats>,
    val routes: List<GpxItemStats>,
)

@Serializable
data class GpxExportReport(
    val input: String,
    val output: String,
    val waypoints: Int,
    val routes: Int,
    val tracks: Int,
    val trackPoints: Int,
    val bytes: Long,
    /** Parsing the written file gives the same document as parsing the input (ids from the same seed). */
    val roundTripEqual: Boolean,
    val timingsMs: Map<String, Long>,
)

@Serializable
data class GpxGapInfo(val start: String, val end: String, val durationS: Double, val reason: String)

@Serializable
data class GpxStopInfo(val segment: Int, val startIndex: Int, val endIndex: Int, val start: String, val end: String, val durationS: Double)

/** `gpx --stats`: one track's raw and smoothed stats (T171), its flagged fixes, gaps and stops. */
@Serializable
data class GpxTrackSmoothingStats(
    val name: String,
    val id: String,
    val activity: String?,
    val points: Int,
    /** Points with an accuracy. */
    val pointsWithAccuracy: Int,
    /** Points flagged over the recording profile's accuracy bound: they add no distance or elevation. */
    val flaggedPoints: Int,
    /** Segments the smoother changed (the others have no accuracy; see `--assume-accuracy`). */
    val smoothedSegments: Int,
    val raw: GpxItemStats,
    val smoothed: GpxItemStats,
    /** Smoothed distance against raw, percent (negative: shorter). */
    val distanceChangePct: Double?,
    val ascentChangeM: Double?,
    val gaps: List<GpxGapInfo>,
    val stops: List<GpxStopInfo>,
    val stoppedS: Double,
    /** Moving stretches between stops (T164's input). */
    val stretches: Int,
)

@Serializable
data class GpxSmoothingReport(
    val file: String,
    val tracks: List<GpxTrackSmoothingStats>,
    val smoothing: Map<String, Double?>,
    val stopRules: Map<String, Double>,
    val timingsMs: Map<String, Long>,
)

/** `devtool gpx ...`: GPX import, stats, rendering and export. */
class GpxCommand : CliktCommand(name = "gpx") {
    init {
        subcommands(GpxInfoCommand(), GpxRenderCommand(), GpxExportCommand())
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Tracks, waypoints and routes from GPX files: stats (info), map render (render), round-trip export (export); " +
            "--stats <file>: raw against smoothed track stats, flagged fixes, gaps and stops."

    private val statsFile by option("--stats", help = "GPX file: print each track's raw and smoothed stats, flagged count, gaps and stops as JSON").file()
    private val assumeAccuracy by option("--assume-accuracy", help = "With --stats: smooth tracks without accuracy as if every fix had this accuracy (m)").double()
    private val profileSamples by option("--profile", help = "With --stats: elevation profile samples per track (0: none)").int().default(0)

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val file = statsFile ?: throw UsageError("give a subcommand (info, render, export) or --stats <file>")
        val timings = Timings()
        val doc = timings.time("parse") { load(file).document }
        val smoothing = SmoothingOptions(assumedAccuracyM = assumeAccuracy)
        val stopOptions = StopOptions(smoothing = smoothing)
        val tracks = timings.time("stats") {
            doc.tracks.map { t ->
                val segs = t.segments.map { it.points }
                val raw = TrackStats.of(segs, StatsOptions(smoothing = null))
                val smooth = TrackStats.of(segs, StatsOptions(smoothing = smoothing))
                val cut = TrackStops.cut(segs, stopOptions)
                GpxTrackSmoothingStats(
                    name = t.name,
                    id = t.id,
                    activity = t.activity?.code,
                    points = raw.points,
                    pointsWithAccuracy = segs.sumOf { s -> s.count { it.accuracyM != null } },
                    flaggedPoints = segs.sumOf { s -> s.count { !TrackSmoothing.counts(it) } },
                    smoothedSegments = segs.count { TrackSmoothing.smoothable(it, smoothing) },
                    raw = itemStats("track", t.name, t.id, raw, if (profileSamples >= 2) TrackStats.profile(segs, profileSamples, null) else null),
                    smoothed = itemStats("track", t.name, t.id, smooth, if (profileSamples >= 2) TrackStats.profile(segs, profileSamples, smoothing) else null),
                    distanceChangePct = raw.distanceM.takeIf { it > 0 }?.let { round((smooth.distanceM - it) / it * 100, 2) },
                    ascentChangeM = if (raw.ascentM != null && smooth.ascentM != null) round(smooth.ascentM!! - raw.ascentM!!) else null,
                    gaps = t.gaps.map { GpxGapInfo(GpxFormat.formatTime(it.start), GpxFormat.formatTime(it.end), it.durationMs / 1000.0, it.reason.code) },
                    stops = cut.stops.map { GpxStopInfo(it.segment, it.startIndex, it.endIndex, GpxFormat.formatTime(it.startTime), GpxFormat.formatTime(it.endTime), it.durationS) },
                    stoppedS = cut.stoppedS,
                    stretches = cut.stretches.size,
                )
            }
        }
        val report = GpxSmoothingReport(
            file = file.path,
            tracks = tracks,
            smoothing = mapOf(
                "accelerationDensity" to smoothing.accelerationDensity,
                "verticalAccelerationDensity" to smoothing.verticalAccelerationDensity,
                "minAccuracyM" to smoothing.minAccuracyM,
                "unknownAccuracyM" to smoothing.unknownAccuracyM,
                "assumedAccuracyM" to smoothing.assumedAccuracyM,
            ),
            stopRules = mapOf("radiusM" to stopOptions.radiusM, "minStopS" to stopOptions.minStopS),
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
    }

    companion object {
        /** Ids are random per run unless seeded; the export round trip check seeds both parses alike. */
        fun load(file: File, seed: Long? = null): Gpx.ParseResult {
            if (!file.isFile) throw UsageError("no such file: ${file.path}")
            val text = file.readText(Charsets.UTF_8)
            try {
                return Gpx.read(text, if (seed == null) UuidGenerator() else UuidGenerator(Random(seed)))
            } catch (e: XmlException) {
                throw UsageError("${file.path} is not well-formed XML: ${e.message}")
            } catch (e: GpxException) {
                throw UsageError("${file.path} is not GPX: ${e.message}")
            }
        }

        /** The deepest zoom an automatic `gpx render` uses (trail detail; OSM packs stop at 14). */
        const val MAX_AUTO_ZOOM = 14

        /** Pixel budget for an automatic zoom: about 4000x3000, a few seconds to render. */
        const val MAX_AUTO_PIXELS = 12_000_000L

        /** Hard limit for an explicit `--zoom`. */
        const val MAX_PIXELS = 40_000_000L

        /** The deepest zoom up to [maxZoom] whose window over [box] fits in [maxPixels] (at least 1). */
        fun fitZoom(box: BoundingBox, maxZoom: Int = MAX_AUTO_ZOOM, maxPixels: Long = MAX_AUTO_PIXELS): Int {
            for (z in maxZoom downTo 1) {
                val w = MercatorWindow.forBbox(box, z)
                if (w.width.toLong() * w.height <= maxPixels) return z
            }
            return 1
        }

        fun round(v: Double, digits: Int = 1): Double {
            var f = 1.0
            repeat(digits) { f *= 10 }
            return (v * f).roundToLong() / f
        }

        fun hms(seconds: Double): String {
            val s = seconds.roundToLong()
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        fun pace(sPerKm: Double): String {
            val s = sPerKm.roundToLong()
            return "%d:%02d".format(s / 60, s % 60)
        }

        fun itemStats(kind: String, name: String, id: String, st: TrackStats, profile: List<ProfileSample>?): GpxItemStats = GpxItemStats(
            kind = kind,
            name = name,
            id = id,
            segments = st.segments,
            points = st.points,
            distanceM = round(st.distanceM),
            ascentM = st.ascentM?.let { round(it) },
            descentM = st.descentM?.let { round(it) },
            minEleM = st.minEleM?.let { round(it) },
            maxEleM = st.maxEleM?.let { round(it) },
            startTime = st.startTime?.let(GpxFormat::formatTime),
            endTime = st.endTime?.let(GpxFormat::formatTime),
            durationS = st.durationS,
            duration = st.durationS?.let(::hms),
            movingTimeS = st.movingTimeS,
            movingTime = st.movingTimeS?.let(::hms),
            movingSpeedKmh = st.movingSpeedMps?.let { round(it * 3.6, 2) },
            movingPacePerKm = st.movingPaceSPerKm?.let(::pace),
            overallPacePerKm = st.overallPaceSPerKm?.let(::pace),
            avgHeartRate = st.avgHeartRate?.let { round(it) },
            maxHeartRate = st.maxHeartRate,
            bbox = st.bounds?.let { listOf(it.west, it.south, it.east, it.north) },
            profile = profile?.map { GpxProfilePoint(round(it.distanceM), it.eleM?.let { e -> round(e) }) },
        )

        fun allStats(doc: GpxDocument, options: StatsOptions, profileSamples: Int): Pair<List<GpxItemStats>, List<GpxItemStats>> {
            val tracks = doc.tracks.map { t ->
                itemStats("track", t.name, t.id, TrackStats.of(t, options), if (profileSamples >= 2) TrackStats.profile(t, profileSamples) else null)
            }
            val routes = doc.routes.map { r ->
                val segs = listOf(r.points.map { io.github.jasondorjeshort.dystopo.model.TrackPoint(it.lat, it.lon, it.ele, it.time) })
                itemStats("route", r.name, r.id, TrackStats.of(r, options), if (profileSamples >= 2) TrackStats.profile(segs, profileSamples) else null)
            }
            return tracks to routes
        }
    }
}

class GpxInfoCommand : CliktCommand(name = "info") {
    override fun help(context: Context) = "Parse a GPX file and print waypoints and per-track/route stats as JSON."

    private val file by argument(help = "GPX file").file()
    private val profile by option("--profile", help = "Elevation profile samples per track/route in the report (0: none)").int().default(11)
    private val threshold by option("--ele-threshold", help = "Ascent/descent hysteresis in meters").double().default(StatsOptions().elevationThresholdM)
    private val movingSpeed by option("--moving-speed", help = "Minimum speed in m/s for an interval to count as moving").double().default(StatsOptions().movingSpeedMps)

    override fun run() {
        val timings = Timings()
        val parsed = timings.time("parse") { GpxCommand.load(file) }
        val doc = parsed.document
        val options = StatsOptions(threshold, movingSpeed)
        val (tracks, routes) = timings.time("stats") { GpxCommand.allStats(doc, options, profile) }
        val report = GpxInfoReport(
            file = file.path,
            creator = doc.creator,
            metadataName = doc.metadata?.name,
            waypoints = doc.waypoints.map { GpxWaypointInfo(it.name, it.lat, it.lon, it.ele, it.symbol, it.id) },
            tracks = tracks,
            routes = routes,
            ignored = parsed.ignored,
            statsRules = mapOf("elevationThresholdM" to options.elevationThresholdM, "movingSpeedMps" to options.movingSpeedMps),
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
    }
}

class GpxExportCommand : CliktCommand(name = "export") {
    override fun help(context: Context) = "Parse a GPX file and write it back as GPX 1.1; checks that the output parses to the same data."

    private val file by argument(help = "GPX file").file()
    private val out by option("--out", help = "Output GPX").file().required()

    override fun run() {
        val timings = Timings()
        val seed = 24L
        val doc = timings.time("parse") { GpxCommand.load(file, seed).document }
        val text = timings.time("write") { Gpx.write(doc) }
        out.absoluteFile.parentFile.mkdirs()
        out.writeText(text, Charsets.UTF_8)
        val again = timings.time("reparse") { GpxCommand.load(out, seed).document }
        val report = GpxExportReport(
            input = file.path,
            output = out.path,
            waypoints = doc.waypoints.size,
            routes = doc.routes.size,
            tracks = doc.tracks.size,
            trackPoints = doc.tracks.sumOf { t -> t.segments.sumOf { it.points.size } },
            bytes = out.length(),
            roundTripEqual = again == doc,
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
        if (!report.roundTripEqual) throw com.github.ajalt.clikt.core.ProgramResult(1)
    }
}

class GpxRenderCommand : CliktCommand(name = "render") {
    override fun help(context: Context) =
        "Render the terrain (and OSM with --osm) around a GPX file and draw its tracks, routes and waypoints. Prints a JSON report."

    private val file by argument(help = "GPX file").file()
    private val area by option("--area", help = "Named area to render instead of fitting the GPX bounds")
    private val bbox by option("--bbox", help = "west,south,east,north to render instead of fitting the GPX bounds")
    private val margin by option("--margin", help = "Margin around the GPX bounds, as a fraction of their size").double().default(0.12)
    private val zoomOption by option("--zoom", help = "Zoom (default: the deepest up to ${GpxCommand.MAX_AUTO_ZOOM} whose render fits about ${GpxCommand.MAX_AUTO_PIXELS / 1_000_000} Mpx)").int().restrictTo(1..17)
    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val style by StyleOptions()
    private val arrowSpacing by option("--arrow-spacing", help = "Pixels between direction arrows along tracks").double().default(110.0)
    private val profileHeight by option("--profile-height", help = "Height of the elevation profile strip under the map (0: none)").int().default(150)
    private val out by option("--out", help = "Output PNG (default out/gpx-<file>-z<zoom>.png)").file()

    override fun run() {
        val timings = Timings()
        val parsed = timings.time("parse") { GpxCommand.load(file) }
        val doc = parsed.document
        val (name, box) = if (area != null || bbox != null) Areas.resolve(area, bbox) else {
            val fit = TrackDrawing.fitBounds(doc.tracks, doc.routes, doc.waypoints, margin)
                ?: throw UsageError("${file.path} has no points; pass --area or --bbox")
            null to fit
        }
        val zoom = zoomOption ?: GpxCommand.fitZoom(box)
        val window = MercatorWindow.forBbox(box, zoom)
        if (window.width.toLong() * window.height > GpxCommand.MAX_PIXELS) throw UsageError("render would be ${window.width}x${window.height} px; lower --zoom")
        val src = ElevationSource.byId(source)
        val fetched = timings.time("fetch") { src.fetch(window) }
        val grid = fetched.grid
        val (map, contours, shading, osm) = MapRenderer.render(grid, zoom, style, timings, window = window)
        val (tracks, routes) = timings.time("stats") { GpxCommand.allStats(doc, StatsOptions(), 0) }
        // The profile speaks the map's units (the contour labels and peak elevations follow --units too).
        val overlay = GpxOverlay(window, style.textScale, arrowSpacing, ContourUnits.parse(style.units))
        val image = timings.time("drawGpx") { overlay.draw(map, doc, profileHeight) }
        val outFile = out ?: File("out/gpx-${file.nameWithoutExtension}-z$zoom.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
        }
        val render = RenderReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            zoom = zoom,
            source = source,
            output = outFile.path,
            widthPx = image.width,
            heightPx = image.height,
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
        echo(reportJson.encodeToString(GpxRenderReport(file.path, render, overlay.stats, tracks, routes)))
    }
}

/**
 * Draws a GPX document over a rendered map: tracks as a violet line on a white casing with white
 * direction chevrons, routes as a dashed orange line, a green ring at each track start and a red
 * square at each end, waypoints as blue dots with names, and an elevation profile strip below.
 */
class GpxOverlay(
    private val window: MercatorWindow,
    private val textScale: Double,
    private val arrowSpacing: Double,
    /** Profile labels: elevations in feet and distances in miles, or metres and kilometres. */
    private val units: ContourUnits = ContourUnits.METERS,
) {
    private val trackColor = Color(0x8e, 0x24, 0xe0)
    private val routeColor = Color(0xe6, 0x7e, 0x22)
    private val casing = Color(255, 255, 255, 220)
    private val waypointColor = Color(0x1d, 0x4e, 0xd8)
    private val labelColor = Color(0x1f, 0x1a, 0x17)
    private val haloColor = Color(255, 255, 255, 225)

    var stats = GpxOverlayStats(0, 0, 0, 0, 0, 0, 0)
        private set

    fun x(lon: Double): Double = WebMercator.lonToX(lon, window.zoom) * window.tileSize - window.x0
    fun y(lat: Double): Double = WebMercator.latToY(lat, window.zoom) * window.tileSize - window.y0

    private fun inside(px: Double, py: Double) = px >= 0 && py >= 0 && px < window.width && py < window.height

    fun draw(map: BufferedImage, doc: GpxDocument, profileHeight: Int): BufferedImage {
        val image = BufferedImage(map.width, map.height + profileHeight.coerceAtLeast(0), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(map, 0, 0, null)
        g.clip = Rectangle2D.Double(0.0, 0.0, map.width.toDouble(), map.height.toDouble())

        var outside = 0
        var arrows = 0
        var routePoints = 0
        for (r in doc.routes) {
            val xy = DoubleArray(r.points.size * 2)
            r.points.forEachIndexed { i, p -> xy[2 * i] = x(p.lon); xy[2 * i + 1] = y(p.lat); if (!inside(xy[2 * i], xy[2 * i + 1])) outside++ }
            routePoints += r.points.size
            line(g, xy, routeColor, 3.2f, dashed = true)
        }
        val segments = doc.tracks.flatMap { t -> TrackDrawing.line(t) }.filter { it.isNotEmpty() }
        val segXy = segments.map { pts ->
            DoubleArray(pts.size * 2).also { xy ->
                pts.forEachIndexed { i, p -> xy[2 * i] = x(p.lon); xy[2 * i + 1] = y(p.lat); if (!inside(xy[2 * i], xy[2 * i + 1])) outside++ }
            }
        }
        for (xy in segXy) line(g, xy, trackColor, 4.2f, dashed = false)
        for (xy in segXy) {
            for (a in TrackDrawing.arrows(xy, arrowSpacing)) {
                chevron(g, a.x, a.y, a.angle)
                arrows++
            }
        }
        // Ends under starts: on a loop the green ring stays visible around the red square.
        for (xy in segXy) square(g, xy[xy.size - 2], xy[xy.size - 1], 5.5, Color(0xc0, 0x1f, 0x1f))
        for (xy in segXy) ring(g, xy[0], xy[1], 9.0, Color(0x1e, 0x9e, 0x3e))

        var drawn = 0
        var wptOutside = 0
        val taken = ArrayList<Rectangle2D>()
        val font = AwtTextMeasurer.font(9.5 * textScale)
        for (w in doc.waypoints) {
            val px = x(w.lon)
            val py = y(w.lat)
            if (!inside(px, py)) { wptOutside++; continue }
            dot(g, px, py, 5.5, waypointColor)
            taken += Rectangle2D.Double(px - 6, py - 6, 12.0, 12.0)
            drawn++
        }
        for (w in doc.waypoints) {
            val px = x(w.lon)
            val py = y(w.lat)
            if (!inside(px, py) || w.name.isEmpty()) continue
            label(g, w.name, px, py, font, taken, map.width, map.height)
        }
        g.clip = null

        var samples = 0
        profileLabels.clear()
        if (profileHeight > 0) samples = profile(g, doc, 0, map.height, map.width, profileHeight)
        g.dispose()
        stats = GpxOverlayStats(
            trackPoints = segments.sumOf { it.size },
            routePoints = routePoints,
            arrows = arrows,
            waypointsDrawn = drawn,
            waypointsOutside = wptOutside,
            pointsOutside = outside,
            profileSamples = samples,
            profileUnits = units.symbol,
            profileLabels = profileLabels.toList(),
        )
        return image
    }

    private fun path(xy: DoubleArray) = Path2D.Double().apply {
        moveTo(xy[0], xy[1])
        for (i in 1 until xy.size / 2) lineTo(xy[2 * i], xy[2 * i + 1])
    }

    private fun line(g: Graphics2D, xy: DoubleArray, color: Color, width: Float, dashed: Boolean) {
        if (xy.size < 4) return
        val p = path(xy)
        g.color = casing
        g.stroke = BasicStroke(width + 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(p)
        g.color = Color(color.red, color.green, color.blue, 235)
        g.stroke = if (dashed) {
            BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(3 * width, 2 * width), 0f)
        } else {
            BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        g.draw(p)
    }

    /** A white chevron pointing along [angle], centered on the line. */
    private fun chevron(g: Graphics2D, cx: Double, cy: Double, angle: Double) {
        val t = AffineTransform()
        t.translate(cx, cy)
        t.rotate(angle)
        val p = Path2D.Double().apply {
            moveTo(-3.2, -4.2)
            lineTo(3.0, 0.0)
            lineTo(-3.2, 4.2)
        }
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(t.createTransformedShape(p))
    }

    private fun ring(g: Graphics2D, cx: Double, cy: Double, r: Double, color: Color) {
        val e = Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        g.color = Color.WHITE
        g.stroke = BasicStroke(5.5f)
        g.draw(e)
        g.color = color
        g.stroke = BasicStroke(3f)
        g.draw(e)
    }

    private fun square(g: Graphics2D, cx: Double, cy: Double, r: Double, color: Color) {
        val s = Rectangle2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.5f)
        g.draw(s)
        g.color = color
        g.fill(s)
    }

    private fun dot(g: Graphics2D, cx: Double, cy: Double, r: Double, color: Color) {
        val e = Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.5f)
        g.draw(e)
        g.color = color
        g.fill(e)
    }

    /** A haloed label beside a marker: right, left, above or below, whichever first avoids earlier labels and markers. */
    private fun label(g: Graphics2D, text: String, px: Double, py: Double, font: java.awt.Font, taken: MutableList<Rectangle2D>, w: Int, h: Int) {
        val gv = font.createGlyphVector(g.fontRenderContext, text)
        val ink = gv.visualBounds
        val gap = 8.0
        val candidates = listOf(
            px + gap to py + ink.height / 2,
            px - gap - ink.width to py + ink.height / 2,
            px - ink.width / 2 to py - gap,
            px - ink.width / 2 to py + gap + ink.height,
        )
        val fits = candidates.map { (lx, by) -> Rectangle2D.Double(lx - 2, by - ink.height - 2, ink.width + 4, ink.height + 4) }
        val pick = fits.indices.firstOrNull { i ->
            val r = fits[i]
            r.x >= 0 && r.y >= 0 && r.maxX <= w && r.maxY <= h && taken.none { it.intersects(r) }
        } ?: 0
        val (lx, by) = candidates[pick]
        taken += fits[pick]
        val shape = gv.getOutline((lx - ink.x).toFloat(), by.toFloat())
        g.color = haloColor
        g.stroke = BasicStroke((2.6 * textScale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = labelColor
        g.fill(shape)
    }

    private val profileLabels = ArrayList<String>()

    private fun drawProfileLabel(g: Graphics2D, text: String, x: Int, y: Int) {
        g.drawString(text, x, y)
        profileLabels += text
    }

    /** Elevation profile of all tracks (one after another) in a strip at ([x0], [y0]); returns the sample count. */
    private fun profile(g: Graphics2D, doc: GpxDocument, x0: Int, y0: Int, width: Int, height: Int): Int {
        g.color = Color(250, 248, 243)
        g.fillRect(x0, y0, width, height)
        g.color = Color(180, 172, 160)
        g.drawLine(x0, y0, x0 + width, y0)
        val segments = doc.tracks.flatMap { t -> t.segments.map { it.points } }
        val pad = 34
        val plotW = width - 2 * pad
        val plotH = height - 2 * 22
        if (plotW < 20 || plotH < 20) return 0
        val samples = TrackStats.profile(segments, plotW.coerceAtMost(2000))
        val eles = samples.mapNotNull { it.eleM }
        val font = AwtTextMeasurer.font(10.0)
        g.font = font
        if (eles.isEmpty()) {
            g.color = labelColor
            g.drawString("no elevation in track", x0 + pad, y0 + height / 2)
            return samples.size
        }
        val lo = eles.min()
        val hi = maxOf(eles.max(), lo + 10)
        val total = samples.last().distanceM.coerceAtLeast(1.0)
        val top = y0 + 18.0
        fun sx(d: Double) = x0 + pad + d / total * plotW
        fun sy(e: Double) = top + (hi - e) / (hi - lo) * plotH
        val area = Path2D.Double()
        area.moveTo(sx(0.0), top + plotH)
        for (s in samples) s.eleM?.let { area.lineTo(sx(s.distanceM), sy(it)) }
        area.lineTo(sx(total), top + plotH)
        area.closePath()
        g.color = Color(trackColor.red, trackColor.green, trackColor.blue, 60)
        g.fill(area)
        val line = Path2D.Double()
        var first = true
        for (s in samples) s.eleM?.let { if (first) line.moveTo(sx(s.distanceM), sy(it)) else line.lineTo(sx(s.distanceM), sy(it)); first = false }
        g.color = trackColor
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(line)
        g.color = labelColor
        drawProfileLabel(g, UnitFormat.elevation(hi, units), 4, sy(hi).toInt() + 4)
        drawProfileLabel(g, UnitFormat.elevation(lo, units), 4, sy(lo).toInt() + 4)
        // Distance ticks every 1 km or mile (5 for long tracks).
        val unitM = if (units == ContourUnits.FEET) UnitFormat.METERS_PER_MILE else 1000.0
        val unitName = if (units == ContourUnits.FEET) "mi" else "km"
        val step = if (total > 20 * unitM) 5 * unitM else unitM
        var d = 0.0
        g.stroke = BasicStroke(1f)
        while (d <= total + 1) {
            val xx = sx(d).toInt()
            g.drawLine(xx, (top + plotH).toInt(), xx, (top + plotH).toInt() + 4)
            drawProfileLabel(g, "${(d / unitM).roundToInt()} $unitName", xx - 10, (top + plotH).toInt() + 16)
            d += step
        }
        return samples.size
    }
}
