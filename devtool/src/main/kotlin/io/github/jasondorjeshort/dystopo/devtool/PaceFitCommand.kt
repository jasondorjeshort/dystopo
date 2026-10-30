package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.PaceFits
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.SyntheticWalk
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant

/**
 * `devtool pace-fit` (T164): the user's own pace fitted to a directory of GPX tracks, as the phone fits it
 * ([PaceFits.fromTracks]: the stop cut, matching onto the trail network, the robust fit), printed as JSON:
 *
 *   devtool pace-fit --gpx-dir out/pace-synthetic --mode hike --pack out/rainier-outdoors.pmtiles
 *
 * With `--pack` the tracks are matched onto that pack's trail graph (heights from its DEM); without, their own smoothed
 * heights are used. `fit` is the mode's curve (peak speed, width, peak grade, the flat pace and the climb rate), `ready`
 * whether it meets the threshold the router waits for (5 tracks over 20 km), `setting` the synced value the phone would
 * store (`app.paceFit`), `tracks` every file's track with its mode, rank and matching counts. With `--pack` and a
 * ready fit, `example` plans Paradise to Panorama Point (Mount Rainier) both ways: the standard estimate and the fit.
 *
 * `--synthesize N` first writes N synthetic tracks into `--gpx-dir` ([SyntheticWalk]: out and back from Paradise to the
 * Skyline loop's public points over the pack's trails, at the curve `--truth-kmh`, `--truth-width`, `--truth-grade`,
 * one a day from 2026-09-01), then fits them; `truth` and `recovery` (fit over truth, per parameter) are in the report.
 */
class PaceFitCommand : CliktCommand(name = "pace-fit") {
    override fun help(context: Context) =
        "T164: fit the user's pace (a Tobler-shaped speed against grade) to a directory of GPX tracks; prints the fit as JSON."

    private val dir by option("--gpx-dir", help = "Directory of .gpx files (written first with --synthesize)").file(canBeFile = false).required()
    private val modeLabel by option("--mode", help = "Mode whose fit is printed").choice("hike", "bike").default("hike")
    private val pack by option("--pack", help = "Vector pack whose trail graph the tracks are matched onto (heights from the DEM next to it)").file(mustExist = true, canBeDir = false)
    private val since by option("--since", help = "Only tracks started at or after this ISO instant (the phone's reset)")
    private val synthesize by option("--synthesize", help = "Write N synthetic tracks into --gpx-dir first (needs --pack)").int().default(0)
    private val truthKmh by option("--truth-kmh", help = "Synthetic: the curve's peak speed, km/h").double().default(4.3)
    private val truthWidth by option("--truth-width", help = "Synthetic: the curve's width").double().default(3.5)
    private val truthGrade by option("--truth-grade", help = "Synthetic: the curve's peak grade").double().default(-0.05)
    private val seed by option("--seed", help = "Synthetic: the first track's seed").int().default(1)

    /** Public points of the Skyline loop at Paradise (the `skyline-loop.gpx` fixture's waypoints). */
    private val paradise = "Paradise Inn" to (46.7859 to -121.7356)
    private val destinations = listOf(
        "Glacier Vista" to (46.8016 to -121.7355),
        "Panorama Point" to (46.8022 to -121.7288),
        "Golden Gate Trail junction" to (46.7989 to -121.7227),
        "Stevens-Van Trump Memorial" to (46.7953 to -121.7168),
        "Myrtle Falls" to (46.7914 to -121.7325),
    )

    override fun run() {
        val t0 = System.nanoTime()
        val mode = TravelMode.of(modeLabel)!!
        PaceFits.clearActive()
        var graph: RoutingGraph? = null
        var graphReport: Map<String, Any?>? = null
        pack?.let { p ->
            val (g, info) = RouteGraphs.openOrBuild(p, RouteGraphs.defaultDem(p), null)
            graph = g
            graphReport = info
        }
        val truth = PaceFit(TravelMode.HIKE, truthKmh / 3.6, truthWidth, truthGrade, 0, 0.0)
        val written = ArrayList<Map<String, Any?>>()
        if (synthesize > 0) {
            val g = graph ?: throw UsageError("--synthesize needs --pack (the trails the synthetic walks follow)")
            dir.mkdirs()
            val router = Router(g)
            for (i in 0 until synthesize) {
                val (name, to) = destinations[i % destinations.size]
                val r = router.route(paradise.second.first, paradise.second.second, to.first, to.second, TravelMode.HIKE)
                val plan = (r as? RouteResult.Found)?.plan ?: throw UsageError("no route from ${paradise.first} to $name: $r")
                val line = plan.route.points.map { TrackPoint(it.lat, it.lon, it.ele ?: throw UsageError("the pack's graph has no heights (no DEM next to it)")) }
                val start = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli() + i * 86_400_000L
                val track = SyntheticWalk.track("synthetic-${i + 1}", "Synthetic walk to $name (T164)", line, truth, start, seed + i)
                val f = File(dir, "synthetic-${i + 1}.gpx")
                f.writeText(Gpx.write(GpxDocument(tracks = listOf(track))), Charsets.UTF_8)
                written += linkedMapOf("file" to f.path, "to" to name, "oneWayKm" to PlannedRoute.r1(plan.distanceM / 1000), "points" to track.points.size)
            }
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) throw UsageError("no .gpx files in ${dir.path}")
        val tracks = ArrayList<Track>()
        val unread = ArrayList<Map<String, Any?>>()
        for (f in files) {
            try {
                tracks += Gpx.parse(f.readText(Charsets.UTF_8)).tracks
            } catch (e: Exception) {
                unread += linkedMapOf("file" to f.name, "error" to e.message)
            }
        }
        val sinceMs = since?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrElse { throw UsageError("--since must be an ISO instant like 2026-09-01T00:00:00Z") } } ?: 0L
        val t1 = System.nanoTime()
        val report = PaceFits.fromTracks(tracks, graph?.let { TrackMatch.snapper(it) }, sinceMs)
        val t2 = System.nanoTime()
        val fit = report.fits[mode]
        val out = linkedMapOf<String, Any?>(
            "command" to "pace-fit", "dir" to dir.path, "mode" to mode.label, "pack" to pack?.path, "matched" to (graph != null),
            "files" to files.size, "tracks" to tracks.size, "unread" to unread.ifEmpty { null },
            "fit" to fit?.json(), "ready" to (fit?.ready == true),
            "threshold" to linkedMapOf("tracks" to PaceFits.MIN_TRACKS, "km" to PaceFits.MIN_METRES / 1000),
            "standard" to standard(mode),
            "setting" to report.setting(),
        )
        if (synthesize > 0) {
            out["truth"] = truth.json() - listOf("tracks", "km", "spread", "ready")
            out["written"] = written
            fit?.let { f ->
                out["recovery"] = linkedMapOf(
                    "peak" to PaceFit.r3(f.peakMps / truth.peakMps), "width" to PaceFit.r3(f.width / truth.width), "peakGrade" to PaceFit.r3(f.peakGrade / truth.peakGrade),
                )
            }
        }
        val g = graph
        if (g != null && fit != null && fit.ready) out["example"] = example(g, mode, fit, truth.takeIf { synthesize > 0 })
        out["trackRows"] = report.rows.map { it.json() }
        out["graph"] = graphReport
        out["timingsMs"] = linkedMapOf("load" to (t1 - t0) / 1_000_000, "fit" to (t2 - t1) / 1_000_000)
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(out))))
    }

    /** The standard estimate's numbers in the fit's terms (Naismith: 5 km/h plus an hour per 600 m; bike 16 km/h, 500 m). */
    private fun standard(mode: TravelMode): Map<String, Any?> {
        val m = CostModel(mode, pace = null)
        val flat = m.timeS(1000.0, 0.0)
        return linkedMapOf("flatPaceMinPerKm" to PaceFit.r3(flat / 60), "climbRateMph" to Math.round(3600.0 / (m.timeS(0.0, 1.0))))
    }

    /** Paradise to Panorama Point and back on the standard estimate and on [fit]. */
    private fun example(g: RoutingGraph, mode: TravelMode, fit: PaceFit, truth: PaceFit?): Map<String, Any?> {
        val to = destinations[1].second
        fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): PlannedRoute? =
            (Router(g).route(fromLat, fromLon, toLat, toLon, mode) as? RouteResult.Found)?.plan
        val legs = listOf("up" to (paradise.second to to), "down" to (to to paradise.second))
        return legs.associate { (name, ends) ->
            val (a, b) = ends
            PaceFits.clearActive()
            val std = plan(a.first, a.second, b.first, b.second)
            PaceFits.setActive(listOf(fit))
            val mine = plan(a.first, a.second, b.first, b.second)
            PaceFits.clearActive()
            name to linkedMapOf(
                "from" to if (name == "up") paradise.first else destinations[1].first, "to" to if (name == "up") destinations[1].first else paradise.first,
                "km" to std?.let { PlannedRoute.r1(it.distanceM / 1000) }, "ascentM" to std?.ascentM?.let { PlannedRoute.r1(it) },
                "standard" to std?.let { PlannedRoute.hm(it.timeS) }, "yours" to mine?.let { PlannedRoute.hm(it.timeS) },
                // With --synthesize: the time the synthetic walker itself takes along the same line (what the fit should predict).
                "truth" to truth?.let { t -> mine?.let { p -> PlannedRoute.hm(SyntheticWalk.timeS(p.route.points.map { TrackPoint(it.lat, it.lon, it.ele) }, t)) } },
            )
        }
    }
}
