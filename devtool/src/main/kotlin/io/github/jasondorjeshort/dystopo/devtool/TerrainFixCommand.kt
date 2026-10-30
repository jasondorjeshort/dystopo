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
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.nav.TerrainFixOptions
import io.github.jasondorjeshort.dystopo.nav.TerrainFixReplay
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * `devtool terrain-fix` (T166): the terrain-matched position measured on a walked line. Reads the first track of
 * `--gpx` (or its first route, timed by Tobler's hiking function when it has no times), replays its elevation as
 * the barometer, its spacing as the step counter and its bearing as the compass ([TerrainFixReplay.Sensors]),
 * cuts the GPS over each `--cut <from>..<to>` (metres walked, `km` allowed) and runs the shared
 * [io.github.jasondorjeshort.dystopo.nav.TerrainFix] over the graph of `--pack` with the DEM next to it. Prints
 * per cut the terrain, the error's median, p90 and max over `--runs` seeds, the coverage (the share of steps
 * whose spread held the error) and a timeline:
 *
 *   devtool terrain-fix --gpx shared/src/commonTest/resources/gpx/skyline-loop.gpx --cut 0..2500 --cut 5600..7400
 *
 * `--flat` replaces the DEM and the line's elevation by one constant (the case where the terrain says nothing);
 * `--no-elevation` and `--no-heading` drop one likelihood, to see what each contributes.
 */
class TerrainFixCommand : CliktCommand(name = "terrain-fix") {
    override fun help(context: Context) =
        "T166: replay a GPX with the GPS cut over --cut stretches; a particle filter on the pack's trails matches the elevation to the DEM. Prints error median, p90 and coverage per cut as JSON."

    private val gpxFile by option("--gpx", help = "GPX whose first track (or route) is the walk").file(mustExist = true, canBeDir = false).required()
    private val pack by option("--pack", help = "Vector pack whose trails are the graph (default out/rainier-outdoors.pmtiles)").file(mustExist = true, canBeDir = false)
    private val dem by option("--dem", help = "DEM pack (default: <name>-dem.pmtiles next to --pack)").file(mustExist = true, canBeDir = false)
    private val cuts by option("--cut", help = "GPS cut as <from>..<to> metres walked (or km: 1.2km..3km); repeatable").multiple(required = true)
    private val runs by option("--runs", help = "Runs per cut (seeds 1..N), pooled in the statistics").int().default(5)
    private val stepS by option("--step-s", help = "Replay step (s)").double().default(5.0)
    private val particles by option("--particles", help = "Particle count").int().default(TerrainFixOptions().particles)
    private val eleSigma by option("--ele-sigma", help = "Elevation likelihood width (m)").double().default(TerrainFixOptions().eleSigmaM)
    private val corrLength by option("--corr-length", help = "Distance between independent looks (m)").double().default(TerrainFixOptions().corrLengthM)
    private val noElevation by option("--no-elevation", help = "Drop the elevation likelihood (stride and heading only)").flag()
    private val noHeading by option("--no-heading", help = "Drop the heading (likelihood and junction choice)").flag()
    private val flat by option("--flat", help = "A flat world: the DEM and the line's elevation one constant").flag()
    private val strideBias by option("--stride-bias", help = "Step counter distance bias (fraction)").double().default(TerrainFixReplay.Sensors().strideBias)
    private val strideGrade by option("--stride-grade", help = "Extra bias per unit grade").double().default(TerrainFixReplay.Sensors().strideGrade)
    private val headingNoise by option("--heading-noise", help = "Compass noise (deg)").double().default(TerrainFixReplay.Sensors().headingNoiseDeg)
    private val headingBias by option("--heading-bias", help = "Compass bias (deg)").double().default(TerrainFixReplay.Sensors().headingBiasDeg)
    private val baroDrift by option("--baro-drift", help = "Barometer drift (m per hour)").double().default(TerrainFixReplay.Sensors().baroDriftMPerH)
    private val seedError by option("--seed-error", help = "Per-axis error of the seed fix (m)").double().default(TerrainFixReplay.Sensors().seedErrorM)

    override fun run() {
        val t0 = System.nanoTime()
        val doc = Gpx.parse(gpxFile.readText(Charsets.UTF_8))
        val track = doc.tracks.firstOrNull()?.points.orEmpty()
        val route = doc.routes.firstOrNull()?.points.orEmpty()
        val raw: List<Triple<Double, Double, Double>>
        val times: List<Long?>
        if (track.size >= 2) {
            raw = track.map { Triple(it.lat, it.lon, it.ele ?: throw UsageError("every point needs an <ele> (the barometer)")) }
            times = track.map { it.time }
        } else if (route.size >= 2) {
            raw = route.map { Triple(it.lat, it.lon, it.ele ?: throw UsageError("every point needs an <ele> (the barometer)")) }
            times = route.map { it.time }
        } else throw UsageError("${gpxFile.path} has no track or route with two points")
        val flatEle = 1000.0
        val pts = if (flat) raw.map { Triple(it.first, it.second, flatEle) } else raw
        val timed = times.all { it != null } && times.zipWithNext().all { (a, b) -> b!! >= a!! }
        val line = if (timed) pts.zip(times).map { (p, t) -> TerrainFixReplay.LinePoint(p.first, p.second, p.third, (t!! - times.first()!!) / 1000.0) }
        else TerrainFixReplay.toblerTimes(pts)
        val truth = TerrainFixReplay.resample(line, stepS)

        val packFile = pack ?: File("out/rainier-outdoors.pmtiles").takeIf { it.isFile } ?: throw UsageError("--pack is required (out/rainier-outdoors.pmtiles not found)")
        val demFile = dem ?: RouteGraphs.defaultDem(packFile) ?: throw UsageError("no DEM pack next to ${packFile.path}; pass --dem")
        val (graph, graphReport) = RouteGraphs.openOrBuild(packFile, demFile, null)
        val pyramid = RouteGraphs.pyramid(demFile, cacheTiles = 256)
        val demSampler = DemElevation(pyramid)
        val sampler: ElevationSampler = if (flat) ElevationSampler { _, _ -> flatEle } else demSampler
        val options = TerrainFixOptions(particles = particles, eleSigmaM = eleSigma, corrLengthM = corrLength, useElevation = !noElevation, useHeading = !noHeading)
        val sensors = TerrainFixReplay.Sensors(
            strideBias = strideBias, strideGrade = strideGrade, headingBiasDeg = headingBias, headingNoiseDeg = headingNoise, baroDriftMPerH = baroDrift, seedErrorM = seedError,
        )
        val t1 = System.nanoTime()
        val cutReports = cuts.map { spec ->
            val (from, to) = parseCut(spec)
            val c0 = System.nanoTime()
            val results = (1..runs).map { seed ->
                try {
                    TerrainFixReplay.runCut(graph, sampler, truth, from, to, options, sensors, seed)
                } catch (e: IllegalArgumentException) {
                    throw UsageError("--cut $spec: ${e.message}")
                } catch (e: IllegalStateException) {
                    throw UsageError("--cut $spec: ${e.message}")
                }
            }
            linkedMapOf<String, Any?>("cut" to spec, "fromM" to from, "toM" to to, "terrain" to TerrainFixReplay.terrain(truth, from, to)) +
                TerrainFixReplay.summary(results) + linkedMapOf("elapsedMs" to (System.nanoTime() - c0) / 1_000_000)
        }
        val report = linkedMapOf<String, Any?>(
            "command" to "terrain-fix", "gpx" to gpxFile.path, "pack" to packFile.path, "dem" to demFile.path,
            "graph" to linkedMapOf("edges" to graph.edgeCount, "action" to graphReport["action"]),
            "line" to linkedMapOf("points" to line.size, "lengthM" to truth.last().alongM.toLong(), "durationS" to truth.last().timeS.toLong(), "timed" to if (timed) "gpx" else "tobler", "flat" to flat),
            "stepS" to stepS, "options" to options.json(), "sensors" to sensors.json(),
            "cuts" to cutReports,
            "demSamples" to demSampler.samples, "demTilesDecoded" to pyramid.tilesDecoded,
            "setupMs" to (t1 - t0) / 1_000_000, "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun parseCut(spec: String): Pair<Double, Double> {
        val parts = spec.split("..")
        if (parts.size != 2) throw UsageError("--cut $spec: expected <from>..<to>")
        fun metres(s: String): Double {
            val t = s.trim().lowercase()
            return when {
                t.endsWith("km") -> t.removeSuffix("km").toDoubleOrNull()?.times(1000)
                t.endsWith("m") -> t.removeSuffix("m").toDoubleOrNull()
                else -> t.toDoubleOrNull()
            } ?: throw UsageError("--cut $spec: '$s' is not a distance")
        }
        val a = metres(parts[0])
        val b = metres(parts[1])
        if (b <= a) throw UsageError("--cut $spec: the end must be beyond the start")
        return a to b
    }
}
