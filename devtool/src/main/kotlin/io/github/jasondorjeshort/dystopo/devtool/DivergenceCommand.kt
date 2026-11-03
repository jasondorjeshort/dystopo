package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.DivergenceText
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.SyntheticWalk
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import io.github.jasondorjeshort.dystopo.routing.TrailDivergence
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `devtool divergence` (T213): where GPX tracks leave a pack's mapped trails, as the phone's `POST /divergence/run` finds
 * it ([TrailDivergence]: off the line by more than the larger of twice the fix's accuracy and 25 m for 100 m, agreed by
 * two days or one track at 10 m or better all the way), printed as JSON with a PNG of the trails, the tracks and the
 * stretches:
 *
 *   devtool divergence --pack out/rainier-outdoors.pmtiles --gpx out/divergence-synthetic
 *   devtool divergence --pack out/rainier-outdoors.pmtiles --synthesize out/divergence-synthetic
 *
 * `--gpx` takes files or directories (their `.gpx` files), repeated. The graph is the pack's (`<pack>-graph` next to it,
 * built when missing, heights from the DEM beside it). `records` are the confirmed stretches (what the phone marks),
 * `unconfirmed` the rest, each with its line, length, track and traversal counts, days, median offset, the nearest mapped
 * line at its midpoint and the card's two strings; `--members` adds every traversal.
 *
 * `--synthesize DIR` first writes four synthetic tracks into DIR over a public Mount Rainier trail (Paradise Inn to
 * Panorama Point, out and back, one a day from 2026-09-01, [SyntheticWalk] with `--noise-m` (5) of position noise and a
 * reported accuracy of twice that plus 2 m): three leave the trail by `--offset-m` (60) for `--length-m` (300) on a stretch picked where the offset line
 * stays 40 m from every other line, the fourth stays on it. The pick is in `synthetic`.
 */
class DivergenceCommand : CliktCommand(name = "divergence") {
    override fun help(context: Context) =
        "T213: where GPX tracks leave a pack's mapped trails (off by twice the accuracy or 25 m for 100 m, two days or one accurate track); JSON and a PNG."

    private val gpx by option("--gpx", help = "GPX file or directory of .gpx files (repeat)").file(mustExist = true).multiple()
    private val pack by option("--pack", help = "Outdoors vector pack whose trail graph the tracks are compared with (its DEM next to it)").file(mustExist = true, canBeDir = false).required()
    private val graphFile by option("--graph", help = "Graph file (default: <pack>-graph next to the pack, built when missing)").file(canBeDir = false)
    private val synthesize by option("--synthesize", help = "Write four synthetic tracks into this directory first (three leave the trail)").file(canBeFile = false)
    private val offsetM by option("--offset-m", help = "Synthetic: how far the three leave the trail, m").double().default(60.0)
    private val lengthM by option("--length-m", help = "Synthetic: for how long, m").double().default(300.0)
    private val noiseM by option("--noise-m", help = "Synthetic: position noise, m (the reported accuracy is twice it plus 2 m: 12 m at the default)").double().default(5.0)
    private val png by option("--png", help = "PNG of the trails, the tracks and the stretches").file(canBeDir = false).default(File("out/divergence.png"))
    private val members by option("--members", help = "List every traversal of each stretch").flag()
    private val utcOffsetH by option("--utc-offset-h", help = "Hours east of UTC that place the days (the phone's zone)").double().default(0.0)

    /** Public points of the Skyline loop at Paradise (as `pace-fit`). */
    private val paradise = "Paradise Inn" to (46.7859 to -121.7356)
    private val panorama = "Panorama Point" to (46.8022 to -121.7288)

    override fun run() {
        val t0 = System.nanoTime()
        val (g, graphReport) = RouteGraphs.openOrBuild(pack, RouteGraphs.defaultDem(pack), graphFile)
        val h = RouteGraphs.reader(pack).header
        val t1 = System.nanoTime()
        val inputs = gpx.toMutableList()
        var synthetic: Map<String, Any?>? = null
        synthesize?.let { dir ->
            synthetic = synthesize(g, dir)
            inputs += dir
        }
        val files = inputs.flatMap { f -> if (f.isDirectory) f.listFiles { x -> x.isFile && x.name.endsWith(".gpx", true) }?.sortedBy { it.name }.orEmpty() else listOf(f) }.distinct()
        if (files.isEmpty()) throw UsageError("no GPX: give --gpx files or directories, or --synthesize")
        val tracks = ArrayList<Track>()
        val unread = ArrayList<Map<String, Any?>>()
        for (f in files) {
            try {
                tracks += Gpx.parse(f.readText(Charsets.UTF_8)).tracks
            } catch (e: Exception) {
                unread += linkedMapOf("file" to f.path, "error" to e.message)
            }
        }
        val t2 = System.nanoTime()
        val covers = { lat: Double, lon: Double -> lon in h.minLon..h.maxLon && lat in h.minLat..h.maxLat }
        val report = TrailDivergence.detect(
            tracks, TrackMatch.snapper(g), TrailDivergence.edges(g), covers, (utcOffsetH * 3_600_000).toLong(),
        )
        val t3 = System.nanoTime()
        png.absoluteFile.parentFile?.mkdirs()
        val drawn = draw(g, tracks, report, png)
        val t4 = System.nanoTime()
        fun strings(d: TrailDivergence.Divergence) = linkedMapOf(
            "title" to DivergenceText.TITLE, "body" to DivergenceText.body(d, ContourUnits.METERS), "bodyFeet" to DivergenceText.body(d, ContourUnits.FEET),
        )
        val out = linkedMapOf<String, Any?>(
            "command" to "divergence", "pack" to pack.path, "files" to files.size, "tracks" to tracks.size, "unread" to unread.ifEmpty { null },
            "records" to report.confirmed.map { it.json(members) + mapOf("strings" to strings(it)) },
            "unconfirmed" to report.records.filter { !it.confirmed }.map { it.json(members) },
            "perTrack" to tracks.map { t ->
                val mine = report.candidates.filter { it.trackId == t.id }
                linkedMapOf(
                    "id" to t.id, "name" to t.name, "points" to t.points.size, "candidates" to mine.size,
                    "inRecord" to report.confirmed.any { t.id in it.trackIds },
                )
            },
            "counts" to report.counts, "thresholds" to TrailDivergence.thresholds(),
            "synthetic" to synthetic, "png" to png.path, "drawn" to drawn, "graph" to graphReport,
            "timingsMs" to linkedMapOf("graph" to (t1 - t0) / 1_000_000, "read" to (t2 - t1) / 1_000_000, "detect" to (t3 - t2) / 1_000_000, "png" to (t4 - t3) / 1_000_000),
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(out))))
    }

    /** The four synthetic tracks into [dir] ([DivergenceCommand]'s help); what was picked. */
    private fun synthesize(g: RoutingGraph, dir: File): Map<String, Any?> {
        val r = Router(g).route(paradise.second.first, paradise.second.second, panorama.second.first, panorama.second.second, TravelMode.HIKE)
        val plan = (r as? RouteResult.Found)?.plan ?: throw UsageError("no route from ${paradise.first} to ${panorama.first}: $r")
        val base = plan.route.points.map { TrackPoint(it.lat, it.lon, it.ele ?: throw UsageError("the pack's graph has no heights (no DEM next to it)")) }
        val line = resample(base, 5.0)
        val total = (line.size - 1) * 5.0
        // The stretch: the first place (from 300 m in) where the offset line on one side keeps 40 m from every line.
        val ramp = 40.0
        var pick: Pair<Double, Int>? = null
        var s0 = 300.0
        search@ while (s0 + lengthM + ramp < total - 200) {
            for (side in listOf(1, -1)) {
                var clear = true
                var s = s0
                while (s <= s0 + lengthM) {
                    val (lat, lon) = offsetAt(line, s, side * offsetM)
                    if (g.nearest(lat, lon, 40.0) != null) { clear = false; break }
                    s += 10.0
                }
                if (clear) { pick = s0 to side; break@search }
            }
            s0 += 25.0
        }
        val (from, side) = pick ?: throw UsageError("no stretch of the route keeps ${offsetM.toInt()} m off every other line for ${lengthM.toInt()} m")
        fun east(s: Double): Double = when {
            s < from - ramp || s > from + lengthM + ramp -> 0.0
            s < from -> offsetM * (s - (from - ramp)) / ramp
            s > from + lengthM -> offsetM * ((from + lengthM + ramp) - s) / ramp
            else -> offsetM
        }
        val detour = line.indices.map { i ->
            val s = i * 5.0
            val (lat, lon) = offsetAt(line, s, side * east(s))
            TrackPoint(lat, lon, line[i].ele)
        }
        dir.mkdirs()
        val truth = PaceFit(TravelMode.HIKE, 4.3 / 3.6, 3.5, -0.05, 0, 0.0)
        val written = ArrayList<Map<String, Any?>>()
        for (i in 0 until 4) {
            val faithful = i == 3
            val start = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli() + i * 86_400_000L
            val name = if (faithful) "Synthetic walk on the trail (T213)" else "Synthetic walk off the trail (T213)"
            val track = SyntheticWalk.track("divergence-${i + 1}", name, if (faithful) line else detour, truth, start, 213 + i, gpsNoiseM = noiseM)
            val f = File(dir, "divergence-${i + 1}${if (faithful) "-faithful" else ""}.gpx")
            f.writeText(Gpx.write(GpxDocument(tracks = listOf(track))), Charsets.UTF_8)
            written += linkedMapOf("file" to f.path, "faithful" to faithful, "points" to track.points.size, "start" to Instant.ofEpochMilli(start).toString())
        }
        val (mLat, mLon) = offsetAt(line, from + lengthM / 2, side * offsetM)
        return linkedMapOf(
            "route" to "${paradise.first} to ${panorama.first}", "routeKm" to Math.round(total / 100) / 10.0,
            "fromM" to from, "lengthM" to lengthM, "offsetM" to offsetM, "side" to if (side > 0) "left" else "right",
            "mid" to linkedMapOf("lat" to Math.round(mLat * 1e6) / 1e6, "lon" to Math.round(mLon * 1e6) / 1e6),
            "noiseM" to noiseM, "accuracyM" to 2 * noiseM + 2, "written" to written,
        )
    }

    /** [line] every [stepM] along it (heights interpolated). */
    private fun resample(line: List<TrackPoint>, stepM: Double): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        out += line[0]
        var carry = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val d = TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            var s = stepM - carry
            while (s <= d) {
                val f = s / d
                out += TrackPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon), a.ele!! + f * (b.ele!! - a.ele!!))
                s += stepM
            }
            carry = d - (s - stepM)
        }
        return out
    }

    /** The point [m] metres to the left (negative: right) of [line] (resampled every 5 m) at [s] along it. */
    private fun offsetAt(line: List<TrackPoint>, s: Double, m: Double): Pair<Double, Double> {
        val i = (s / 5.0).toInt().coerceIn(0, line.size - 2)
        val a = line[i]; val b = line[i + 1]
        val brg = TrackStats.bearingDeg(a.lat, a.lon, b.lat, b.lon) * PI / 180
        val mPerLat = 111_195.0
        val mPerLon = mPerLat * cos(a.lat * PI / 180)
        // Left of the direction of travel: the bearing minus 90 degrees.
        val east = -cos(brg) * m
        val north = sin(brg) * m
        return (a.lat + north / mPerLat) to (a.lon + east / mPerLon)
    }

    /** The trails near the tracks (grey), the tracks (blue) and the stretches (dashed purple, a dot at the midpoint, confirmed only). */
    private fun draw(g: RoutingGraph, tracks: List<Track>, report: TrailDivergence.Report, out: File): Map<String, Any?> {
        val pts = tracks.flatMap { it.points }
        if (pts.isEmpty()) return linkedMapOf("error" to "no points")
        val pad = 0.002
        val minLat = pts.minOf { it.lat } - pad; val maxLat = pts.maxOf { it.lat } + pad
        val minLon = pts.minOf { it.lon } - pad; val maxLon = pts.maxOf { it.lon } + pad
        val k = cos((minLat + maxLat) / 2 * PI / 180)
        val w = 1400
        val hgt = ((maxLat - minLat) / ((maxLon - minLon) * k) * w).toInt().coerceIn(400, 2400)
        fun x(lon: Double) = (lon - minLon) / (maxLon - minLon) * w
        fun y(lat: Double) = (maxLat - lat) / (maxLat - minLat) * hgt
        val img = BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.color = Color(0xF4, 0xF1, 0xE8)
        gr.fillRect(0, 0, w, hgt)
        var edges = 0
        gr.stroke = BasicStroke(2.5f)
        gr.color = Color(0x8C, 0x7B, 0x6B)
        for (e in 0 until g.edgeCount) {
            val n = g.pointCount(e)
            var inside = false
            val p = Path2D.Double()
            for (j in 0 until n) {
                val lat = g.pointLatE6(e, j) / 1e6; val lon = g.pointLonE6(e, j) / 1e6
                if (lat in minLat..maxLat && lon in minLon..maxLon) inside = true
                if (j == 0) p.moveTo(x(lon), y(lat)) else p.lineTo(x(lon), y(lat))
            }
            if (inside) { gr.draw(p); edges++ }
        }
        gr.stroke = BasicStroke(1.2f)
        for ((i, t) in tracks.withIndex()) {
            gr.color = if (report.confirmed.any { t.id in it.trackIds }) Color(0x2C, 0x7B, 0xB6, 170) else Color(0x1A, 0x98, 0x50, 170)
            for (seg in t.segments) {
                val p = Path2D.Double()
                seg.points.forEachIndexed { j, q -> if (j == 0) p.moveTo(x(q.lon), y(q.lat)) else p.lineTo(x(q.lon), y(q.lat)) }
                gr.draw(p)
            }
            if (i > 40) break
        }
        val purple = Color(0x7B, 0x32, 0x94)
        for (d in report.records) {
            val p = Path2D.Double()
            d.line.forEachIndexed { j, q -> if (j == 0) p.moveTo(x(q.lon), y(q.lat)) else p.lineTo(x(q.lon), y(q.lat)) }
            gr.color = Color.WHITE
            gr.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (d.confirmed) gr.draw(p)
            gr.color = if (d.confirmed) purple else Color(0x99, 0x99, 0x99)
            gr.stroke = BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(14f, 9f), 0f)
            gr.draw(p)
            if (d.confirmed) {
                val m = d.mid
                gr.color = Color.WHITE
                gr.fillOval((x(m.lon) - 10).toInt(), (y(m.lat) - 10).toInt(), 20, 20)
                gr.color = purple
                gr.fillOval((x(m.lon) - 7).toInt(), (y(m.lat) - 7).toInt(), 14, 14)
                gr.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
                gr.drawString("${d.tracks} tracks, ${Math.round(d.lengthM)} m", (x(m.lon) + 14).toInt(), (y(m.lat) + 5).toInt())
            }
        }
        gr.color = Color(0x2B, 0x24, 0x20)
        gr.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        gr.drawString("T213 divergence: grey trails, blue tracks in a record, green tracks in none, dashed purple the confirmed stretches (grey: unconfirmed)", 10, hgt - 12)
        gr.dispose()
        ImageIO.write(img, "png", out)
        return linkedMapOf("width" to w, "height" to hgt, "edges" to edges, "stretches" to report.records.size)
    }
}
