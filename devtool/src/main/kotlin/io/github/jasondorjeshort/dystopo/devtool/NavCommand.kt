package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavReplay
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.math.roundToLong

/**
 * `devtool nav replay` (T65): the phone's navigation (T61) on the JVM. Follows the line of `--path` (a
 * GPX route, or its track with `--path-track` or when it has no route; its waypoints become navigation
 * waypoints) with the track points of `--fixes` as fixes, through the shared [NavReplay], and prints the
 * events (off route, back on route, waypoints, arrival), the farthest distance off the line, progress
 * samples and the pace ratio `etaVsPlan` as JSON. Exit 1 when `--expect-off` / `--expect-arrive` do not hold,
 * so the off-route rules can be checked without an emulator:
 *
 *   devtool nav replay --path shared/src/commonTest/resources/gpx/skyline-loop.gpx --path-track \
 *       --fixes shared/src/commonTest/resources/gpx/skyline-offroute.gpx --expect-off 1 --expect-arrive
 *
 * T102: with `--pack <vector pack>` the line's turn directions are read from that pack's graph (as the phone reads
 * them for a kept or saved route) and the report has `directions` (those shown at the default level, as text) and,
 * in each progress sample, `next` (the direction navigation shows there) and `aheadM`; `directionsPassed` counts
 * those behind the walker at the end. `--expect-directions N` exits 1 unless N are shown.
 */
class NavCommand : CliktCommand(name = "nav") {
    init {
        subcommands(NavReplayCommand())
    }

    override fun help(context: Context) = "Navigation on the JVM (T65): `nav replay` runs a GPX walk along a route or track through the phone's off-route logic."

    override fun run() = Unit
}

class NavReplayCommand : CliktCommand(name = "replay") {
    override fun help(context: Context) =
        "Follow --path (GPX route, or track with --path-track) with the track points of --fixes as GPS fixes; print events, max distance off the line, progress and etaVsPlan as JSON."

    private val pathFile by option("--path", help = "GPX with the route (or track) to follow").file(mustExist = true, canBeDir = false).required()
    private val pathTrack by option("--path-track", help = "Follow the GPX's first track instead of its first route").flag()
    private val fixesFile by option("--fixes", help = "GPX whose track points are the fixes, in order, with their times (default: --path's track)").file(mustExist = true, canBeDir = false)
    private val offRouteM by option("--off-route-m", help = "Off the line beyond this (m)").double().default(NavOptions().offRouteM)
    private val offRouteS by option("--off-route-s", help = "...for this long (s of fix time)").double().default(NavOptions().offRouteSeconds)
    private val backOnRouteM by option("--back-on-route-m", help = "Back on route within this (m)").double()
    private val arriveM by option("--arrive-m", help = "Arrived within this of the end (m)").double().default(NavOptions().arriveM)
    private val mode by option("--mode", help = "hike (default), bike or drive: the time model (and the directions' mode)").choice("hike", "bike", "drive").default("hike")
    private val pack by option("--pack", help = "T102: vector pack whose graph gives the line's turn directions (graph file next to it, built when missing)").file(mustExist = true, canBeDir = false)
    private val expectDirections by option("--expect-directions", help = "Exit 1 unless this many directions are shown (with --pack)").int()
    private val samples by option("--samples", help = "Progress samples in the report").int().default(20)
    private val expectOff by option("--expect-off", help = "Exit 1 unless exactly this many off-route events happen").int()
    private val expectArrive by option("--expect-arrive", help = "Exit 1 unless the walk arrives").flag()

    override fun run() {
        val doc = parse(pathFile)
        val route = doc.routes.firstOrNull()
        val track = doc.tracks.firstOrNull()
        val path = when {
            !pathTrack && route != null && route.points.size >= 2 -> NavPath.of(route, doc.waypoints)
            track != null && track.points.size >= 2 -> NavPath.of(track, doc.waypoints)
            else -> throw UsageError("${pathFile.path} has no route or track with two points")
        }
        val walk = (fixesFile?.let(::parse) ?: doc).tracks.flatMap { it.points }
        if (walk.isEmpty()) throw UsageError("no track points to use as fixes")
        if (walk.any { it.time == null }) throw UsageError("every fix needs a <time> (the off-route timer runs on fix time)")
        val defaults = NavOptions()
        val options = NavOptions(
            offRouteM = offRouteM, offRouteSeconds = offRouteS,
            backOnRouteM = backOnRouteM ?: minOf(defaults.backOnRouteM, offRouteM), arriveM = arriveM,
            mode = TravelMode.of(mode)!!,
        )
        val t0 = System.nanoTime()
        val report = linkedMapOf<String, Any?>("command" to "nav replay", "pathFile" to pathFile.path, "fixesFile" to (fixesFile ?: pathFile).path)
        report += NavReplay.run(path, walk.map { NavReplay.Fix(it.lat, it.lon, it.time!!) }, options, samples)
        // T102: the directions along the line from the pack's graph, and which one navigation shows at each sample.
        val shown = pack?.let { f ->
            val (graph, _) = RouteGraphs.openOrBuild(f, RouteGraphs.defaultDem(f), null)
            val points = path.points.map { RoutePoint(it.lat, it.lon, it.ele) }
            Directions.shown(Directions.of(points, options.mode, Router(graph).junctions))
        }
        if (shown != null) {
            val passM = Directions.passM(options.mode)
            report["directions"] = shown.map { "${DirectionsText.of(it, options.mode)} at ${it.alongM.roundToLong()} m" }
            @Suppress("UNCHECKED_CAST")
            report["progress"] = (report["progress"] as List<Map<String, Any?>>).map { sample ->
                val at = (sample["progressM"] as Number).toDouble()
                val k = Directions.next(shown, at, passM = passM)
                sample + linkedMapOf("next" to shown.getOrNull(k)?.let { DirectionsText.of(it, options.mode) }, "aheadM" to shown.getOrNull(k)?.let { (it.alongM - at).roundToLong() })
            }
            val end = (report["finalProgressM"] as Number?)?.toDouble() ?: 0.0
            report["directionsPassed"] = Directions.next(shown, end, passM = passM).let { if (it < 0) shown.size else it }
        }
        report["elapsedMs"] = (System.nanoTime() - t0) / 1_000_000
        val offEvents = (report["counts"] as Map<*, *>)["offRoute"] as Int? ?: 0
        val failures = buildList {
            expectOff?.let { if (offEvents != it) add("expected $it off-route events, got $offEvents") }
            if (expectArrive && report["arrived"] != true) add("expected to arrive")
            expectDirections?.let { n -> val got = (report["directions"] as List<*>?)?.size; if (got != n) add("expected $n directions, got $got") }
        }
        report["ok"] = failures.isEmpty()
        report["failures"] = failures
        echo(pretty(report))
        if (failures.isNotEmpty()) throw ProgramResult(1)
    }

    private fun parse(f: File): GpxDocument = Gpx.parse(f.readText(Charsets.UTF_8))
}

private fun pretty(value: Any?): String = reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(value)))
