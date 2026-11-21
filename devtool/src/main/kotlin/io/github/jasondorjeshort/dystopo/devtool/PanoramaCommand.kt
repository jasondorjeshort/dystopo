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
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.LayeredSkyline
import io.github.jasondorjeshort.dystopo.horizon.LensView
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
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * T322: the Panorama view's first pass in devtool: the skyline from a point at several ranges ([LayeredSkyline]) drawn far
 * to near through the lens ([LensView]: bearing, tilt, roll, field of view), the bearing tape along the top, the level
 * line, and the pack's peaks named by [HorizonLabels.place] through the lens; a JSON report of the names placed, the
 * labels left out and the timings.
 */
class PanoramaCommand : CliktCommand(name = "panorama") {
    override fun help(context: Context) =
        "The Panorama view from a point: the skyline at several ranges drawn far to near through a lens (bearing, tilt, roll, " +
            "field of view), the bearing tape, the level line and the peak names (PNG), with the names placed, the labels left " +
            "out and the sweep's timings (JSON)."

    private val area by option("--area", help = "Named area: its centre is the default point and out/<area>-outdoors.pmtiles the default pack")
    private val from by option("--from", help = "lat,lon of the observer (default: the area's centre)")
    private val bearing by option("--bearing", help = "Bearing at the view's centre, degrees true (default: toward the highest skyline peak, else the highest skyline)").double()
    private val tilt by option("--tilt", help = "Tilt from the horizontal, degrees (-90 straight down, +90 straight up)").double().default(0.0)
    private val roll by option("--roll", help = "Roll, degrees (positive: the phone turned clockwise)").double().default(0.0)
    private val fov by option("--fov", help = "Field of view across the width, degrees").double().default(PanoramaRender.DEFAULT_FOV_DEG)
    private val width by option("--width", help = "Image width, px").int().default(1080)
    private val height by option("--height", help = "Image height, px (square by default: the Horizon view's plot)").int().default(1080)
    private val dp by option("--dp", help = "px per dp (2.625: a 411 dp wide phone at 1080 px)").double().default(2.625)
    private val rangeKm by option("--range-km", help = "The view range, km (the farthest skyline; the app's view range, at most 100)").double().default(100.0)
    private val stepDeg by option("--step-deg", help = "Bearing step of the sweeps, degrees").double().default(PanoramaRender.DEFAULT_STEP_DEG)
    private val eye by option("--eye-m", help = "Eye height above the ground, metres").double().default(Viewshed.EYE_HEIGHT_M)
    private val source by option("--source", help = "Elevation source (when no --dem is given)").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val dem by option("--dem", help = "DEM pack (Terrarium PMTiles) to read instead of --source").file(mustExist = true, canBeDir = false)
    private val pack by option("--pack", help = "Outdoors vector pack for the peaks (default out/<area>-outdoors.pmtiles when it exists)").file(mustExist = true, canBeDir = false)
    private val out by option("--out", help = "Output PNG (default out/<area>-panorama-<bearing>-<fov>.png)").file()

    override fun run() {
        val box = area?.let { Areas.resolve(it, null).second }
        val (lat, lon) = from?.let(::parseLatLon)
            ?: box?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }
            ?: throw UsageError("--from or --area is required")
        val rangeM = rangeKm * 1000
        if (rangeM <= 0 || rangeM > Viewshed.MAX_RANGE_M) throw UsageError("--range-km must be in (0, ${Viewshed.MAX_RANGE_M / 1000}]")
        if (fov <= 0 || fov >= LensView.MAX_FOV_DEG) throw UsageError("--fov must be in (0, ${LensView.MAX_FOV_DEG})")
        if (tilt < -90 || tilt > 90) throw UsageError("--tilt must be in [-90, 90]")
        if (stepDeg <= 0 || stepDeg > 5) throw UsageError("--step-deg must be in (0, 5]")
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
        val ranges = LayeredSkyline.ranges(rangeM)
        val skyline = timings.time("sweep") { LayeredSkyline.sweep(terrain, lat, lon, ranges, stepDeg, eye) }

        val packFile = pack ?: area?.let { File("out/$it-outdoors.pmtiles").takeIf { f -> f.isFile } }
        val found: List<Peak> = if (packFile != null) {
            val reader = PmtilesReader.open(FileRangeSource(packFile), Gzip.decompressors)
            val z = minOf(Viewshed.peakZoom(lat, rangeM), reader.header.maxZoom)
            timings.time("peaks.read") { Viewshed.readPeaks({ tz, tx, ty -> synchronized(reader) { reader.tile(tz, tx, ty) } }, lat, lon, rangeM, z) }
        } else emptyList()
        val views = timings.time("peaks.test") { PanoramaRender.peakViews(terrain, skyline, found) }
        val ranked = HorizonLabels.rank(PeakRim.labels(views))
        val centre = bearing ?: PanoramaRender.defaultBearing(skyline, ranked)
        val lens = LensView(width.toDouble(), height.toDouble(), dp, centre, tilt, roll, fov)
        val drawn = timings.time("render") { PanoramaRender.draw(lens, skyline, ranked) }

        val name = area ?: "point"
        val outFile = out ?: File("out/$name-panorama-b${centre.roundToInt()}-t${tilt.roundToInt()}-r${roll.roundToInt()}-f${fov.roundToInt()}.png")
        timings.time("write") {
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(drawn.image, "png", outFile)
        }
        val placedSet = drawn.placed.map { it.label }.toSet()
        val farthestPeak = views.maxOfOrNull { it.distanceM }
        val report = linkedMapOf<String, Any?>(
            "command" to "panorama", "area" to area, "from" to listOf(lat, lon),
            "source" to if (demFile != null) "dem:${demFile.name}" else source,
            "view" to linkedMapOf(
                "bearingDeg" to r1(centre), "tiltDeg" to tilt, "rollDeg" to roll, "fovDeg" to fov, "width" to width, "height" to height, "dp" to dp,
                "pxPerDegAtCentre" to r2(lens.pxPerDegAtCentre), "dpPerDegAtCentre" to r2(lens.pxPerDegAtCentre / dp), "refDpPerDeg" to r2(LensView.REF_DP_PER_DEG),
            ),
            "stepDeg" to stepDeg,
            "skylines" to skyline.profiles.mapIndexed { i, p ->
                linkedMapOf(
                    "rangeKm" to ranges[i] / 1000, "ms" to skyline.sweepMs[i], "samples" to p.samples, "bearingsWithoutData" to p.bearingsWithoutData,
                    "maxAngleDeg" to r2(p.angleDeg.max()), "maxAngleBearingDeg" to r1(p.bearingDeg(p.highest)),
                    "inViewMaxAngleDeg" to PanoramaRender.inViewMax(lens, p)?.let(::r2),
                    "medianReachedKm" to r1(p.reachedM.sorted()[p.size / 2] / 1000),
                )
            },
            "pack" to packFile?.path, "peaksRead" to found.size, "candidates" to ranked.size,
            "farthestPeakKm" to farthestPeak?.let { r1(it / 1000) },
            "named" to drawn.placed.map { p ->
                linkedMapOf("name" to p.label.name, "x" to r1(p.x), "y" to r1(p.y), "distanceKm" to r1(p.label.distanceM / 1000),
                    "bearingDeg" to r1(p.label.bearingDeg), "angleDeg" to r2(p.label.angleDeg), "score" to r2(p.scored.score), "leader" to (p.leader != null))
            },
            "leftOut" to ranked.filter { it.label !in placedSet }.map { s ->
                linkedMapOf("name" to s.label.name, "distanceKm" to r1(s.label.distanceM / 1000), "bearingDeg" to r1(s.label.bearingDeg),
                    "angleDeg" to r2(s.label.angleDeg), "reason" to if (lens.mark(s.label.angleDeg, s.label.bearingDeg) == null) "off the view" else "no room")
            },
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
}

private fun r1(d: Double) = (d * 10).roundToLong() / 10.0
private fun r2(d: Double) = (d * 100).roundToLong() / 100.0

/**
 * T322: the Panorama drawn through a [LensView]: each pixel's direction tinted by the nearest skyline above it (the
 * layers far to near, the nearest darkest, a darker edge where one layer meets another or the sky), the level line
 * dashed, the bearing tape along the top, and the names placed by [HorizonLabels.place] on plates with their triangles.
 */
object PanoramaRender {
    /**
     * The bearing step of the sweeps, degrees. T322's measurement: at the narrowest zoom tried (15 degrees, 27 dp per
     * degree at the centre on a 411 dp view) 0.25 drew the skyline as visible straight runs of about 7 dp; 0.1 (about
     * 2.7 dp) did not. The four ranges at 0.1 took 0.62 M samples from Paradise and 1.88 M on the salt flats (0.25 M and
     * 0.75 M at 0.25), about 0.3 s on the desktop JVM.
     */
    const val DEFAULT_STEP_DEG = 0.1

    /**
     * The default field of view, degrees: T322's proposal from the renders (the report). At 60 the summit and its
     * neighbours from Paradise are named and fit a square view level; the salt flats' 3.4 degree ridge stands about
     * 21 dp; at 100 it is 10 dp and fewer names stand.
     */
    const val DEFAULT_FOV_DEG = 60.0

    /** The tape's height and the names' text size, dp. */
    const val TAPE_DP = 22.0
    const val NAME_DP = 12.0

    /** The sky at the level and overhead, the farthest layer's tint and the nearest's. */
    private val SKY_LOW = Color(0xE8, 0xF0, 0xF6)
    private val SKY_HIGH = Color(0x9C, 0xC2, 0xE2)
    private val FAR = Color(0xB9, 0xC6, 0xD6)
    private val NEAR = Color(0x3C, 0x4E, 0x3E)

    class Drawn(val image: BufferedImage, val placed: List<HorizonLabels.Placed>, val textBoxes: List<RimBox>)

    /** The peaks of [found] tested on the terrain and the view range's skyline ([Viewshed.peaks], as the Horizon view does). */
    fun peakViews(terrain: Terrain, skyline: LayeredSkyline, found: List<Peak>): List<PeakView> = Viewshed.peaks(terrain, skyline.outer, found)

    /** The default centre: the dominant label's bearing (the highest skyline peak), else the outer skyline's highest bin. */
    fun defaultBearing(skyline: LayeredSkyline, ranked: List<HorizonLabels.Scored>): Double =
        ranked.firstOrNull { it.label.dominant }?.label?.bearingDeg ?: skyline.outer.bearingDeg(skyline.outer.highest)

    /** The highest angle of [p] at the bearings the view's centre row spans, or null when the level is off the view. */
    fun inViewMax(lens: LensView, p: io.github.jasondorjeshort.dystopo.dem.HorizonProfile): Double? {
        var best: Double? = null
        var x = 0.0
        while (x <= lens.width) {
            val (_, az) = lens.ray(x, lens.cy)
            val a = p.angleAt(az)
            if (best == null || a > best) best = a
            x += 2.0
        }
        return best
    }

    private fun mix(a: Color, b: Color, t: Double): Int {
        val r = (a.red + (b.red - a.red) * t).roundToInt()
        val g = (a.green + (b.green - a.green) * t).roundToInt()
        val bb = (a.blue + (b.blue - a.blue) * t).roundToInt()
        return (r shl 16) or (g shl 8) or bb
    }

    fun draw(lens: LensView, skyline: LayeredSkyline, ranked: List<HorizonLabels.Scored>): Drawn {
        val w = lens.width.toInt()
        val h = lens.height.toInt()
        val n = skyline.profiles.size
        val layer = IntArray(w * h)
        val elev = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (el, az) = lens.ray(x + 0.5, y + 0.5)
            layer[y * w + x] = skyline.layerAt(el, az)
            elev[y * w + x] = el
        }
        val tint = IntArray(n) { i -> mix(FAR, NEAR, if (n == 1) 1.0 else (n - 1 - i).toDouble() / (n - 1)) }
        val edge = IntArray(n) { i -> mix(Color(tint[i]), Color.BLACK, 0.35) }
        val px = IntArray(w * h)
        for (k in px.indices) {
            val l = layer[k]
            val x = k % w
            val y = k / w
            val border = (y > 0 && layer[k - w] != l) || (x > 0 && layer[k - 1] != l)
            px[k] = when {
                l < 0 -> mix(SKY_LOW, SKY_HIGH, (elev[k] / 60).coerceIn(0.0, 1.0))
                border -> edge[l]
                else -> tint[l]
            }
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, px, 0, w)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val dp = lens.dp
        val text = ArrayList<RimBox>()
        drawLevel(g, lens)
        val tapeBox = drawTape(g, lens, text)
        // The names, kept off the tape.
        g.font = RenderFonts.regular(NAME_DP * dp)
        val fm = g.fontMetrics
        // Past the image's edges is blocked too, so a name stands wholly on the view (a forced one may still be clipped).
        val big = 1e6
        val edges = listOf(RimBox(-big, -big, 0.0, big), RimBox(lens.width, -big, big, big), RimBox(-big, -big, big, 0.0), RimBox(-big, lens.height, big, big))
        val placed = HorizonLabels.place(lens, ranked, { fm.stringWidth(it).toDouble() }, -fm.ascent.toDouble(), fm.descent.toDouble(), blocked = edges + tapeBox)
        val s = 5 * dp
        // The candidates not named: a small grey triangle where they stand on the view.
        val named = placed.map { it.label }.toSet()
        g.color = Color(90, 90, 90, 170)
        for (r in ranked) if (r.label !in named) lens.mark(r.label.angleDeg, r.label.bearingDeg)?.let { at -> g.fill(triangle(at.x, at.y, s * 0.55)) }
        for (p in placed) p.leader?.let { (a, b) ->
            g.color = Color(30, 30, 30, 140)
            g.stroke = BasicStroke((1 * dp).toFloat())
            g.draw(Line2D.Double(a.x, a.y, b.x, b.y))
        }
        for (p in placed) {
            val at = lens.mark(p.label.angleDeg, p.label.bearingDeg) ?: continue
            val tri = triangle(at.x, at.y, s)
            if (p.label.outOfSight) {
                g.color = Color.WHITE; g.fill(tri)
                g.color = Color(25, 25, 25); g.stroke = BasicStroke((1.5 * dp).toFloat()); g.draw(tri)
            } else {
                g.color = Color(25, 25, 25); g.fill(tri)
            }
            g.color = Color(255, 255, 255, 215)
            val b = p.box
            g.fill(java.awt.geom.RoundRectangle2D.Double(b.left, b.top, b.right - b.left, b.bottom - b.top, 4 * dp, 4 * dp))
            g.color = Color(20, 20, 20)
            g.drawString(p.label.name, (p.x - fm.stringWidth(p.label.name) / 2.0).toFloat(), p.y.toFloat())
            text += b
        }
        g.dispose()
        return Drawn(image, placed, text)
    }

    private fun triangle(x: Double, y: Double, s: Double) = Path2D.Double().apply { moveTo(x, y - s); lineTo(x + s, y + s * 0.7); lineTo(x - s, y + s * 0.7); closePath() }

    /** The level (0 degrees) as projected, dashed, across every bearing in front of the viewer. */
    private fun drawLevel(g: Graphics2D, lens: LensView) {
        g.color = Color(30, 30, 30, 150)
        g.stroke = BasicStroke((1.2 * lens.dp).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf((6 * lens.dp).toFloat(), (4 * lens.dp).toFloat()), 0f)
        val path = Path2D.Double()
        var open = false
        var b = 0.0
        val far = 4 * (lens.width + lens.height)
        while (b <= 360.0) {
            val p = lens.project(0.0, b)
            if (p == null || abs(p.x - lens.cx) > far || abs(p.y - lens.cy) > far) open = false
            else if (!open) { path.moveTo(p.x, p.y); open = true } else path.lineTo(p.x, p.y)
            b += 0.1
        }
        g.draw(path)
    }

    /**
     * The bearing tape along the top: a plate [TAPE_DP] high, with a tick wherever the bearing along its lower edge
     * crosses a multiple of the tick step and a label at the label step (N E S W at the cardinals); the steps follow the
     * view's degrees per dp. Returns the plate's box.
     */
    private fun drawTape(g: Graphics2D, lens: LensView, text: MutableList<RimBox>): RimBox {
        val dp = lens.dp
        val tapeH = TAPE_DP * dp
        val box = RimBox(0.0, 0.0, lens.width, tapeH)
        g.color = Color(255, 255, 255, 200)
        g.fill(java.awt.geom.Rectangle2D.Double(0.0, 0.0, lens.width, tapeH))
        val dpPerDeg = lens.pxPerDegAtCentre / dp
        val tick = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 45.0, 90.0).first { it * dpPerDeg >= 12 }
        val label = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 45.0, 90.0).first { it >= tick && it * dpPerDeg >= 48 && (it / tick) % 1.0 == 0.0 }
        g.font = RenderFonts.regular(10 * dp)
        val fm = g.fontMetrics
        g.stroke = BasicStroke((1 * dp).toFloat())
        var prev = lens.ray(0.0, tapeH).second
        var x = 1.0
        while (x <= lens.width) {
            val az = lens.ray(x, tapeH).second
            // A step crossing a multiple of the tick (the short way round, so 359 to 1 crosses 0).
            var d = az - prev
            if (d > 180) d -= 360
            if (d < -180) d += 360
            val lo = minOf(prev, prev + d)
            val hi = maxOf(prev, prev + d)
            val m = floor(hi / tick) * tick
            if (m > lo && m <= hi && abs(d) < 10) {
                val deg = ((m % 360) + 360) % 360
                val major = abs(deg / label - (deg / label).roundToInt()) < 1e-9
                g.color = Color(40, 40, 40)
                g.draw(Line2D.Double(x, tapeH, x, tapeH - (if (major) 6.0 else 3.5) * dp))
                if (major) {
                    val s = when (deg.roundToInt()) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "${deg.roundToInt()}°" }
                    val sw = fm.stringWidth(s)
                    g.drawString(s, (x - sw / 2.0).toFloat(), (tapeH - 8 * dp).toFloat())
                    text += RimBox(x - sw / 2.0, tapeH - 8 * dp - fm.ascent, x + sw / 2.0, tapeH - 8 * dp + fm.descent)
                }
            }
            prev = az
            x += 1.0
        }
        return box
    }
}
