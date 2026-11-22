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
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.horizon.Enu
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.PhoneAttitude
import io.github.jasondorjeshort.dystopo.horizon.Quat
import io.github.jasondorjeshort.dystopo.horizon.LayeredSkyline
import io.github.jasondorjeshort.dystopo.horizon.OffView
import io.github.jasondorjeshort.dystopo.horizon.OffViewArrow
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene
import io.github.jasondorjeshort.dystopo.horizon.PanoramaSweep
import io.github.jasondorjeshort.dystopo.horizon.PanoramaText
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import io.github.jasondorjeshort.dystopo.horizon.RimBox
import io.github.jasondorjeshort.dystopo.png.TerrariumPngDecoder
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.tan

/**
 * T322, T324: the Panorama view in devtool: the skyline from a point at several ranges ([LayeredSkyline]) swept at the
 * steps [PanoramaSweep] proposes for the zoom, and the scene [PanoramaScene] builds for an aim ([PanoramaAim]: the aim at
 * opening unless `--tilt` or `--fov` is given) drawn as the phone draws it: the layers' outlines far to near, the level
 * line, the bearing tape, the pack's peaks named, the zoom buttons' corner, and with `--target` the edge arrow and its
 * line ([OffView]). A JSON report of the aim, the sweeps, the names placed and left out with their reasons, the target and
 * the scene's build time. T329: `--roll` and a tilt past Drag's -45 draw a Follow aim ([PanoramaAim.follow]); `--attitude`
 * with `--rotation` draws the aim [PhoneAttitude] derives from a rotation vector and prints it with its axes.
 */
class PanoramaCommand : CliktCommand(name = "panorama") {
    override fun help(context: Context) =
        "The Panorama view from a point: the skyline at several ranges drawn far to near through a lens (bearing, tilt, roll, " +
            "field of view, or a phone's rotation vector; the aim at opening by default), the bearing tape, the level line, the peak names and an optional " +
            "target's edge arrow (PNG), with the names placed, the labels left out, the sweeps' timings and the scene's build time (JSON)."

    private val area by option("--area", help = "Named area: its centre is the default point and out/<area>-outdoors.pmtiles the default pack")
    private val from by option("--from", help = "lat,lon of the observer (default: the area's centre)")
    private val bearing by option("--bearing", help = "Bearing at the view's centre, degrees true (default: toward the highest skyline peak, else the highest skyline)").double()
    private val tilt by option("--tilt", help = "Tilt from the level, degrees (-90 to 90; past -45, Drag's limit, the aim follows the phone; default: the level line two thirds down)").double()
    private val roll by option("--roll", help = "Roll, degrees (positive: the phone turned clockwise); the aim follows the phone").double()
    private val attitude by option("--attitude", help = "The phone's rotation vector x,y,z,w (as the sensor gives it); the aim follows it, the vector's north taken as true")
    private val rotation by option("--rotation", help = "The display's rotation with --attitude, degrees").choice("0", "90", "180", "270").default("0")
    private val fov by option("--fov", help = "Field of view across the width, degrees (10 to 100; default 60)").double()
    private val width by option("--width", help = "Plot width, px").int().default(1080)
    private val height by option("--height", help = "Plot height, px (default three quarters of the width: the phone's plot upright)").int()
    private val dp by option("--dp", help = "px per dp (2.625: a 411 dp wide phone at 1080 px)").double().default(2.625)
    private val rangeKm by option("--range-km", help = "The view range, km (the farthest skyline; the app's view range, at most 100)").double().default(100.0)
    private val stepDeg by option("--step-deg", help = "Sweep at this bearing step alone, degrees (default: the coarse and the fine step the zoom needs)").double()
    private val eye by option("--eye-m", help = "Eye height above the ground, metres").double().default(Viewshed.EYE_HEIGHT_M)
    private val source by option("--source", help = "Elevation source (when no --dem is given)").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val dem by option("--dem", help = "DEM pack (Terrarium PMTiles, e.g. the phone's own pack) to read instead of --source").file(mustExist = true, canBeDir = false)
    private val pack by option("--pack", help = "Outdoors vector pack for the peaks (default out/<area>-outdoors.pmtiles when it exists)").file(mustExist = true, canBeDir = false)
    private val target by option("--target", help = "elevation,bearing (degrees) of a picked thing: its edge arrow and line when it is off the view")
    private val targetName by option("--target-name", help = "The target's name in its line").default("Target")
    private val out by option("--out", help = "Output PNG (default out/<area>-panorama-b<bearing>-t<tilt>-f<fov>.png)").file()

    override fun run() {
        val box = area?.let { Areas.resolve(it, null).second }
        val (lat, lon) = from?.let(::parseLatLon)
            ?: box?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }
            ?: throw UsageError("--from or --area is required")
        val rangeM = rangeKm * 1000
        val w = width.toDouble()
        val h = (height ?: (width * 3 / 4)).toDouble()
        if (rangeM <= 0 || rangeM > Viewshed.MAX_RANGE_M) throw UsageError("--range-km must be in (0, ${Viewshed.MAX_RANGE_M / 1000}]")
        fov?.let { if (it < PanoramaAim.MIN_FOV_DEG || it > PanoramaAim.MAX_FOV_DEG) throw UsageError("--fov must be in [${PanoramaAim.MIN_FOV_DEG}, ${PanoramaAim.MAX_FOV_DEG}]") }
        tilt?.let { if (it < PanoramaAim.FOLLOW_MIN_TILT_DEG || it > PanoramaAim.FOLLOW_MAX_TILT_DEG) throw UsageError("--tilt must be in [${PanoramaAim.FOLLOW_MIN_TILT_DEG}, ${PanoramaAim.FOLLOW_MAX_TILT_DEG}]") }
        val q = attitude?.let(::parseQuat)
        if (q != null && (bearing != null || tilt != null || roll != null)) throw UsageError("--attitude gives the bearing, the tilt and the roll; leave those out")
        val rotationDeg = rotation.toInt()
        stepDeg?.let { if (it <= 0 || it > 5) throw UsageError("--step-deg must be in (0, 5]") }
        val targetDir = target?.let(::parseTarget)
        val fovDeg = fov ?: PanoramaAim.OPENING_FOV_DEG
        val steps = stepDeg?.let { listOf(it) } ?: PanoramaSweep.steps(fovDeg, w)
        val timings = Timings()
        val plan = Viewshed.plan(lat, lon, rangeM)
        val provenance = LinkedHashSet<String>()
        var attribution = ""
        val demFile = dem
        val read: (MercatorWindow) -> ElevationGrid = if (demFile != null) {
            val reader = PmtilesReader.open(FileRangeSource(demFile), Gzip.decompressors)
            val hd = reader.header
            val pyramid = DemPyramid(hd.minZoom, hd.maxZoom, TerrariumPngDecoder(ZlibInflate.decompressor, imageIoDecoder), cacheTiles = 512) { z, x, y ->
                synchronized(reader) { reader.tile(z, x, y) }
            }
            ({ win -> pyramid.window(win.zoom, win.x0, win.y0, win.width, win.height) })
        } else {
            val src = ElevationSource.byId(source)
            ({ win ->
                val r = src.fetch(win)
                provenance += r.provenance
                attribution = r.attribution(src)
                r.grid
            })
        }
        val terrain = timings.time("fetch") { Viewshed.terrain(plan, read) }
        val ranges = LayeredSkyline.ranges(rangeM)
        // Coarse to fine, as the phone sweeps; the finest is drawn.
        val sweeps = steps.map { s -> timings.time("sweep.$s") { LayeredSkyline.sweep(terrain, lat, lon, ranges, s, eye) } }
        val skyline = sweeps.last()

        val packFile = pack ?: area?.let { File("out/$it-outdoors.pmtiles").takeIf { f -> f.isFile } }
        val found: List<Peak> = if (packFile != null) {
            val reader = PmtilesReader.open(FileRangeSource(packFile), Gzip.decompressors)
            val z = minOf(Viewshed.peakZoom(lat, rangeM), reader.header.maxZoom)
            timings.time("peaks.read") { Viewshed.readPeaks({ tz, tx, ty -> synchronized(reader) { reader.tile(tz, tx, ty) } }, lat, lon, rangeM, z) }
        } else emptyList()
        val views = timings.time("peaks.test") { PanoramaRender.peakViews(terrain, skyline, found) }
        val ranked = HorizonLabels.rank(PeakRim.labels(views))
        val centre = bearing ?: PanoramaRender.defaultBearing(skyline, ranked)
        val opening = PanoramaAim.opening(w, h, centre, fovDeg)
        val t = tilt ?: opening.tiltDeg
        val aim = when {
            q != null -> PanoramaAim.follow(PhoneAttitude.axes(q, rotationDeg), fovDeg)
            roll != null || t < PanoramaAim.MIN_TILT_DEG -> PanoramaAim.follow(opening.bearingDeg, t, roll ?: 0.0, fovDeg)
            else -> PanoramaAim(opening.bearingDeg, t, fovDeg)
        }
        val drawn = timings.time("render") { PanoramaRender.draw(aim, w, h, dp, skyline, ranked, targetDir?.let { Triple(targetName, it.first, it.second) }) }
        val cost = PanoramaRender.sceneCost(drawn)

        val name = area ?: "point"
        val rollPart = if (aim.follow) "-r${aim.rollDeg.roundToInt()}" else ""
        val outFile = out ?: File("out/$name-panorama-b${aim.bearingDeg.roundToInt()}-t${aim.tiltDeg.roundToInt()}$rollPart-f${aim.fovDeg.roundToInt()}.png")
        timings.time("write") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(drawn.image, "png", outFile)
        }
        val scene = drawn.scene
        val farthestPeak = views.maxOfOrNull { it.distanceM }
        val report = linkedMapOf<String, Any?>(
            "command" to "panorama", "area" to area, "from" to listOf(lat, lon),
            "source" to if (demFile != null) "dem:${demFile.name}" else source,
            "aim" to linkedMapOf(
                "bearingDeg" to r1(aim.bearingDeg), "tiltDeg" to r2(aim.tiltDeg), "rollDeg" to r2(aim.rollDeg), "fovDeg" to aim.fovDeg,
                "follow" to aim.follow, "opening" to (tilt == null && fov == null && roll == null && q == null),
                "width" to w, "height" to h, "dp" to dp, "pxPerDegAtCentre" to r2(scene.lens.pxPerDegAtCentre), "levelY" to scene.level?.let { r1(it[1]) },
                "level" to scene.level?.map { r1(it) },
                "axes" to aim.axes?.let { a -> linkedMapOf("forward" to enu(a.forward), "right" to enu(a.right), "up" to enu(a.up)) },
            ),
            "attitude" to q?.let { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z, "w" to it.w, "rotationDeg" to rotationDeg, "north" to "the vector's (magnetic on a phone), taken as true") },
            "steps" to steps,
            "sweeps" to sweeps.map { s ->
                linkedMapOf("stepDeg" to s.stepDeg, "samples" to s.profiles.sumOf { it.samples }, "ms" to s.sweepMs.sum(), "perRangeMs" to s.sweepMs)
            },
            "skylines" to skyline.profiles.mapIndexed { i, p ->
                linkedMapOf(
                    "rangeKm" to ranges[i] / 1000, "samples" to p.samples, "bearingsWithoutData" to p.bearingsWithoutData,
                    "maxAngleDeg" to r2(p.angleDeg.max()), "maxAngleBearingDeg" to r1(p.bearingDeg(p.highest)),
                    "medianReachedKm" to r1(p.reachedM.sorted()[p.size / 2] / 1000),
                    "rings" to scene.layers.first { it.index == i }.rings.size, "outlinePoints" to scene.layers.first { it.index == i }.rings.sumOf { it.size / 2 },
                )
            },
            "pack" to packFile?.path, "peaksRead" to found.size, "candidates" to ranked.size,
            "farthestPeakKm" to farthestPeak?.let { r1(it / 1000) },
            "named" to scene.names.map { n ->
                val p = n.placed
                linkedMapOf("name" to p.label.name, "x" to r1(p.x), "y" to r1(p.y), "markX" to r1(n.markX), "markY" to r1(n.markY),
                    "distanceKm" to r1(p.label.distanceM / 1000), "bearingDeg" to r1(p.label.bearingDeg), "angleDeg" to r2(p.label.angleDeg),
                    "score" to r2(p.scored.score), "dominant" to p.label.dominant, "leader" to (p.leader != null))
            },
            "dominant" to ranked.firstOrNull { it.label.dominant }?.label?.name,
            "closest" to ranked.minByOrNull { it.label.distanceM }?.label?.name,
            "leftOut" to scene.leftOut.map { l ->
                linkedMapOf("name" to l.label.name, "distanceKm" to r1(l.label.distanceM / 1000), "bearingDeg" to r1(l.label.bearingDeg),
                    "angleDeg" to r2(l.label.angleDeg), "reason" to l.reason)
            },
            "tape" to linkedMapOf(
                "tickStepDeg" to scene.tape.tickStepDeg, "labelStepDeg" to scene.tape.labelStepDeg, "ticks" to scene.tape.ticks.size,
                "labels" to scene.tape.labels.map { it.text }, "facing" to scene.tape.facing?.text,
            ),
            "target" to targetDir?.let { (el, az) ->
                val a = drawn.arrow
                linkedMapOf("name" to targetName, "elevationDeg" to el, "bearingDeg" to az, "onView" to (a == null),
                    "side" to a?.side?.word, "degrees" to a?.let { r1(it.degrees) }, "x" to a?.let { r1(it.x) }, "y" to a?.let { r1(it.y) }, "line" to a?.text)
            },
            "sceneMs" to linkedMapOf("builds" to cost.builds, "placed" to r2(cost.placedMs), "held" to r2(cost.heldMs)),
            "terrainLayers" to terrain.layers.map { l -> linkedMapOf("zoom" to l.window.zoom, "width" to l.window.width, "height" to l.window.height, "cellM" to r1(l.window.cellSizeMeters)) },
            "output" to outFile.path,
            "timingsMs" to timings.ms,
            "provenance" to provenance.toList(),
            "attribution" to attribution,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun parseLatLon(s: String): Pair<Double, Double> {
        val p = s.split(",").map { it.trim().toDoubleOrNull() }
        if (p.size != 2 || p.any { it == null }) throw UsageError("--from must be lat,lon (got $s)")
        return p[0]!! to p[1]!!
    }

    private fun parseQuat(s: String): Quat {
        val p = s.split(",").map { it.trim().toDoubleOrNull() }
        if (p.size !in 3..4 || p.any { it == null }) throw UsageError("--attitude must be x,y,z,w (or x,y,z) of the rotation vector (got $s)")
        return PhoneAttitude.quat(p.map { it!! }.toDoubleArray()) ?: throw UsageError("--attitude must be a rotation (not of zero length; got $s)")
    }

    private fun enu(v: Enu) = listOf(r4(v.e), r4(v.n), r4(v.u))

    private fun parseTarget(s: String): Pair<Double, Double> {
        val p = s.split(",").map { it.trim().toDoubleOrNull() }
        if (p.size != 2 || p.any { it == null } || p[0]!! !in -90.0..90.0) throw UsageError("--target must be elevation,bearing in degrees (got $s)")
        return p[0]!! to p[1]!!
    }
}

private fun r1(d: Double) = (d * 10).roundToLong() / 10.0
private fun r2(d: Double) = (d * 100).roundToLong() / 100.0
private fun r4(d: Double) = (d * 10_000).roundToLong() / 10_000.0

/**
 * T322, T324: the Panorama drawn from a [PanoramaScene], as the phone's Canvas draws it: the sky shaded by elevation, each
 * layer's outline filled far to near (the nearest darkest) with its ridge line darker, the level line dashed, the tape, the
 * candidates' triangles, the names on plates with their leader lines, the zoom buttons, and a target's edge arrow.
 */
object PanoramaRender {
    /** The names' and the tape's text sizes, dp. */
    const val NAME_DP = 12.0
    const val TAPE_TEXT_DP = 10.0

    /** The sky at the level and overhead, the farthest layer's tint and the nearest's. */
    private val SKY_LOW = Color(0xE8, 0xF0, 0xF6)
    private val SKY_HIGH = Color(0x9C, 0xC2, 0xE2)
    private val FAR = Color(0xB9, 0xC6, 0xD6)
    private val NEAR = Color(0x3C, 0x4E, 0x3E)

    /** The image, the [scene] it was drawn from, the text boxes drawn (where a golden compares loosely), the target's [arrow]; [rebuild] builds the scene again (for its cost). */
    class Drawn(
        val image: BufferedImage, val scene: PanoramaScene, val textBoxes: List<RimBox>, val arrow: OffViewArrow?,
        val rebuild: (held: Boolean) -> PanoramaScene,
    ) {
        val placed: List<HorizonLabels.Placed> get() = scene.names.map { it.placed }
    }

    /** The peaks of [found] tested on the terrain and the view range's skyline ([Viewshed.peaks], as the Horizon view does). */
    fun peakViews(terrain: Terrain, skyline: LayeredSkyline, found: List<Peak>): List<PeakView> = Viewshed.peaks(terrain, skyline.outer, found)

    /** The default centre: the dominant label's bearing (the highest skyline peak), else the outer skyline's highest bin. */
    fun defaultBearing(skyline: LayeredSkyline, ranked: List<HorizonLabels.Scored>): Double =
        ranked.firstOrNull { it.label.dominant }?.label?.bearingDeg ?: skyline.outer.bearingDeg(skyline.outer.highest)

    private fun mix(a: Color, b: Color, t: Double): Color = Color(
        (a.red + (b.red - a.red) * t).roundToInt(), (a.green + (b.green - a.green) * t).roundToInt(), (a.blue + (b.blue - a.blue) * t).roundToInt(),
    )

    private fun text(fm: FontMetrics) = PanoramaText({ fm.stringWidth(it).toDouble() }, -fm.ascent.toDouble(), fm.descent.toDouble())

    /** The scene's build time: [builds] builds with the names placed and as many with them held (a drag's frames). */
    class Cost(val builds: Int, val placedMs: Double, val heldMs: Double)

    fun sceneCost(d: Drawn, builds: Int = 30): Cost {
        repeat(5) { d.rebuild(false); d.rebuild(true) }
        fun time(held: Boolean): Double {
            val t0 = System.nanoTime()
            repeat(builds) { d.rebuild(held) }
            return (System.nanoTime() - t0) / 1e6 / builds
        }
        return Cost(builds, time(false), time(true))
    }

    /**
     * The view of [aim] on a [width] by [height] px plot at [dp] over [skyline], naming [ranked], with [target] (name,
     * elevation, bearing) shown by its edge arrow when it is off the view, or ringed on it.
     */
    fun draw(
        aim: PanoramaAim, width: Double, height: Double, dp: Double, skyline: LayeredSkyline, ranked: List<HorizonLabels.Scored>,
        target: Triple<String, Double, Double>? = null,
    ): Drawn {
        val w = width.roundToInt()
        val h = height.roundToInt()
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val nameFont = RenderFonts.regular(NAME_DP * dp)
        val tapeFont = RenderFonts.regular(TAPE_TEXT_DP * dp)
        val nameText = text(g.getFontMetrics(nameFont))
        val tapeText = text(g.getFontMetrics(tapeFont))
        val zoomCorner = PanoramaScene.zoomCorner(width, height, dp)
        val scene = PanoramaScene.build(aim, width, height, dp, skyline, ranked, nameText, tapeText, listOf(zoomCorner))
        val rebuild = { held: Boolean ->
            PanoramaScene.build(aim, width, height, dp, skyline, ranked, nameText, tapeText, listOf(zoomCorner), held = if (held) scene.names else null)
        }
        val lens = scene.lens
        val text = ArrayList<RimBox>()

        // The sky: from the level's tint to the overhead's at 60 degrees, along the centre column. T329: at a roll, between
        // where the level and 60 degrees up fall on the view's bearing (the same two points with no roll).
        val sky0: Pair<Double, Double>?
        val sky60: Pair<Double, Double>?
        if (aim.rollDeg == 0.0 && aim.axes == null) {
            fun rowOf(el: Double): Double? = (el - aim.tiltDeg).takeIf { abs(it) < 85 }?.let { lens.cy - lens.focalPx * tan(it * PI / 180) }
            sky0 = rowOf(0.0)?.let { 0.0 to it }
            sky60 = rowOf(60.0)?.let { 0.0 to it }
        } else {
            fun at(el: Double) = (el - aim.tiltDeg).takeIf { abs(it) < 85 }?.let { lens.project(el, aim.bearingDeg) }?.let { it.x to it.y }
            sky0 = at(0.0)
            sky60 = at(60.0)
        }
        g.paint = if (sky0 != null && sky60 != null && kotlin.math.hypot(sky0.first - sky60.first, sky0.second - sky60.second) > 1) {
            GradientPaint(sky0.first.toFloat(), sky0.second.toFloat(), SKY_LOW, sky60.first.toFloat(), sky60.second.toFloat(), SKY_HIGH)
        } else if (aim.tiltDeg > 30) SKY_HIGH else SKY_LOW
        g.fillRect(0, 0, w, h)

        // The layers, far to near.
        val n = skyline.profiles.size
        for (layer in scene.layers) {
            val tint = mix(FAR, NEAR, if (n == 1) 1.0 else (n - 1 - layer.index).toDouble() / (n - 1))
            val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
            for (ring in layer.rings) {
                path.moveTo(ring[0], ring[1])
                for (i in 1 until ring.size / 2) path.lineTo(ring[2 * i], ring[2 * i + 1])
                path.closePath()
            }
            g.color = tint
            g.fill(path)
            g.color = mix(tint, Color.BLACK, 0.35)
            g.stroke = BasicStroke((1 * dp).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (ridge in layer.ridges) {
                val p = Path2D.Double()
                p.moveTo(ridge[0], ridge[1])
                for (i in 1 until ridge.size / 2) p.lineTo(ridge[2 * i], ridge[2 * i + 1])
                g.draw(p)
            }
        }

        // The level line.
        scene.level?.let { l ->
            g.color = Color(30, 30, 30, 150)
            g.stroke = BasicStroke((1.2 * dp).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf((6 * dp).toFloat(), (4 * dp).toFloat()), 0f)
            g.draw(Line2D.Double(l[0], l[1], l[2], l[3]))
        }

        // The tape.
        val tape = scene.tape
        g.color = Color(255, 255, 255, 200)
        g.fill(java.awt.geom.Rectangle2D.Double(tape.box.left, tape.box.top, tape.box.right - tape.box.left, tape.box.bottom - tape.box.top))
        g.color = Color(40, 40, 40)
        g.stroke = BasicStroke((1 * dp).toFloat())
        for (t in tape.ticks) g.draw(Line2D.Double(t.x, t.top, t.x, t.bottom))
        g.font = tapeFont
        for (l in tape.labels + listOfNotNull(tape.facing)) {
            g.drawString(l.text, l.box.left.toFloat(), l.baseline.toFloat())
            text += l.box
        }

        // The candidates' triangles, the names' leaders, triangles and plates.
        val s = 5 * dp
        g.color = Color(90, 90, 90, 170)
        for (m in scene.marks) if (!m.named) g.fill(triangle(m.x, m.y, s * 0.55))
        g.color = Color(30, 30, 30, 140)
        g.stroke = BasicStroke((1 * dp).toFloat())
        for (nm in scene.names) nm.placed.leader?.let { (a, b) -> g.draw(Line2D.Double(a.x, a.y, b.x, b.y)) }
        g.font = nameFont
        val fm = g.fontMetrics
        for (nm in scene.names) {
            val p = nm.placed
            val tri = triangle(nm.markX, nm.markY, s)
            if (p.label.outOfSight) {
                g.color = Color.WHITE; g.fill(tri)
                g.color = Color(25, 25, 25); g.stroke = BasicStroke((1.5 * dp).toFloat()); g.draw(tri)
            } else {
                g.color = Color(25, 25, 25); g.fill(tri)
            }
            g.color = Color(255, 255, 255, 215)
            val b = p.box
            g.fill(RoundRectangle2D.Double(b.left, b.top, b.right - b.left, b.bottom - b.top, 4 * dp, 4 * dp))
            g.color = Color(20, 20, 20)
            g.drawString(p.label.name, (p.x - fm.stringWidth(p.label.name) / 2.0).toFloat(), p.y.toFloat())
            text += b
        }

        // The zoom buttons.
        for ((i, b) in PanoramaScene.zoomButtons(width, height, dp).withIndex()) {
            g.color = Color(255, 255, 255, 220)
            g.fill(RoundRectangle2D.Double(b.left, b.top, b.right - b.left, b.bottom - b.top, 12 * dp, 12 * dp))
            g.color = Color(40, 40, 40)
            g.stroke = BasicStroke((2 * dp).toFloat())
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            val a = 9 * dp
            g.draw(Line2D.Double(cx - a, cy, cx + a, cy))
            if (i == 0) g.draw(Line2D.Double(cx, cy - a, cx, cy + a))
        }

        // The target: ringed on the view, else its edge arrow and line.
        var arrow: OffViewArrow? = null
        if (target != null) {
            val (tn, el, az) = target
            val keep = RimBox(14 * dp, tape.box.bottom + 14 * dp, width - 14 * dp, height - 14 * dp)
            arrow = OffView.of(lens, el, az, tn, keep, follow = aim.follow)
            g.color = Color(0xB0, 0x30, 0x10)
            g.stroke = BasicStroke((2.5 * dp).toFloat())
            val a = arrow
            if (a == null) {
                lens.mark(el, az)?.let { p -> g.draw(java.awt.geom.Ellipse2D.Double(p.x - 10 * dp, p.y - 10 * dp, 20 * dp, 20 * dp)) }
            } else {
                // A filled arrowhead at the edge, pointing out of the view, and the line beside it on a plate.
                val len = 12 * dp
                val half = 8 * dp
                val tipX = a.x + a.dirX * len / 2
                val tipY = a.y + a.dirY * len / 2
                val bx = a.x - a.dirX * len / 2
                val by = a.y - a.dirY * len / 2
                val head = Path2D.Double().apply {
                    moveTo(tipX, tipY); lineTo(bx - a.dirY * half, by + a.dirX * half); lineTo(bx + a.dirY * half, by - a.dirX * half); closePath()
                }
                g.fill(head)
                val tw = fm.stringWidth(a.text).toDouble()
                val pad = 4 * dp
                val gap = 6 * dp
                val tx = when {
                    a.dirX > 0 -> a.x - len / 2 - gap - tw
                    a.dirX < 0 -> a.x + len / 2 + gap
                    else -> (a.x - tw / 2).coerceIn(pad, width - tw - pad)
                }
                val ty = when {
                    a.dirY < 0 -> a.y + len / 2 + gap + fm.ascent
                    a.dirY > 0 -> a.y - len / 2 - gap - fm.descent
                    else -> a.y + (fm.ascent - fm.descent) / 2.0
                }
                val plate = RimBox(tx - pad, ty - fm.ascent - pad / 2, tx + tw + pad, ty + fm.descent + pad / 2)
                g.color = Color(255, 255, 255, 230)
                g.fill(RoundRectangle2D.Double(plate.left, plate.top, plate.right - plate.left, plate.bottom - plate.top, 4 * dp, 4 * dp))
                g.color = Color(0xB0, 0x30, 0x10)
                g.drawString(a.text, tx.toFloat(), ty.toFloat())
                text += plate
            }
        }
        g.dispose()
        return Drawn(image, scene, text, arrow, rebuild)
    }

    private fun triangle(x: Double, y: Double, s: Double) = Path2D.Double().apply { moveTo(x, y - s); lineTo(x + s, y + s * 0.7); lineTo(x - s, y + s * 0.7); closePath() }
}
