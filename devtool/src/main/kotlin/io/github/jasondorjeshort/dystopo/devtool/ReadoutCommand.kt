package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.Navigator
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.readout.Damping
import io.github.jasondorjeshort.dystopo.readout.FallWarning
import io.github.jasondorjeshort.dystopo.readout.HeightSource
import io.github.jasondorjeshort.dystopo.readout.PressureFixtures
import io.github.jasondorjeshort.dystopo.readout.PressureSeries
import io.github.jasondorjeshort.dystopo.readout.PressureTrend
import io.github.jasondorjeshort.dystopo.readout.StationTemperature
import io.github.jasondorjeshort.dystopo.readout.TrendResult
import io.github.jasondorjeshort.dystopo.render.UnitRegions
import io.github.jasondorjeshort.dystopo.readout.MetarAltimeter
import io.github.jasondorjeshort.dystopo.readout.PressureSample
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutEstimator
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.readout.StationPressure
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.server.reportJson
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.math.sqrt

/**
 * `devtool readout` (T160): the live readout's estimator ([ReadoutEstimator]) replayed over a GPX's track points as
 * fixes (their accuracy, speed and course when the GPX carries them, T158's extensions), with an optional barometer
 * series (`--pressure`, CSV of time and hPa), DEM pack (`--dem`), navigated line (`--path`: along-route speed) and
 * weather station report (`--station-report`, placed at the first fix). Prints the readout at every fix (or every
 * `--every`th) as JSON: each entry's state, value (SI), sigma, source and text in `--units`, the heights side by
 * side, the barometer's state, and a summary with counts and the elevation's RMS against the GPX's own heights.
 *
 *   devtool readout --gpx shared/src/commonTest/resources/gpx/skyline-loop.gpx --activity foot
 *
 * T178: the barometer's trend and the pressure-fall warning (`shared`'s [PressureTrend]). With `--gpx` and `--pressure`
 * the report adds `trend`, the trend at the last fix (the fixes' heights from `--dem`, else their GPX heights as GNSS).
 * `--pressure` alone reads the trend's own CSV (`time,hPa,heightM,source,speedMps,lat,lon`, a `# station,...` line;
 * [PressureSeries]) and reports `trend` at its last row. `--trend-fixture <name>|all` runs the synthetic fixtures the
 * acceptance tests use ([PressureFixtures]), writes each as `out/pressure-<name>.csv` (which `--pressure` reads back)
 * and reports each verdict with its interval against the expected one. `--warning` picks the setting (default standard);
 * `--station-temp` and `--station-elev` give a station temperature (degrees C, m) for the reduction.
 *
 *   devtool readout --trend-fixture all
 *   devtool readout --pressure out/pressure-still-fall.csv --warning fewer
 */
class ReadoutCommand : CliktCommand(name = "readout") {
    override fun help(context: Context) =
        "Replay a GPX track through the live readout (speed, climb, course, elevation) and print the series as JSON."

    private val gpxFile by option("--gpx", help = "GPX whose track points are the fixes, in order, with times (not needed with --trend-fixture or a trend CSV alone)").file(mustExist = true, canBeDir = false)
    private val activity by option("--activity", help = "foot (default), bike, road, rail, boat or air").choice(*Activity.entries.map { it.code }.toTypedArray()).default("foot")
    private val pressureFile by option("--pressure", help = "CSV of barometer samples: time (ISO 8601 or epoch ms), hPa; a header line is skipped").file(mustExist = true, canBeDir = false)
    private val demFile by option("--dem", help = "DEM pack (PMTiles) for the terrain height on foot").file(mustExist = true, canBeDir = false)
    private val pathFile by option("--path", help = "GPX route (or track with --path-track) being navigated: along-route speed on foot and bike").file(mustExist = true, canBeDir = false)
    private val pathTrack by option("--path-track", help = "Follow --path's first track instead of its first route").flag()
    private val stationReport by option("--station-report", help = "A weather station report with an altimeter group, taken as observed at the first fix's place and time")
    private val units by option("--units", help = "m (default) or ft for the text").choice("m", "ft").default("m")
    private val pace by option("--pace", help = "Pace instead of speed").flag()
    private val knots by option("--knots", help = "Knots (boat and air only)").flag()
    private val damping by option("--damping", help = "low, medium (default) or high, for every entry").choice("low", "medium", "high").default("medium")
    private val noBarometer by option("--no-barometer", help = "The barometer switch off").flag()
    private val every by option("--every", help = "Print every Nth readout (the summary counts all)").int().default(1)
    // T178: the barometer's trend.
    private val trendFixture by option("--trend-fixture", help = "Run a synthetic pressure-trend fixture (${PressureFixtures.NAMES.joinToString(", ")}) or all; writes out/pressure-<name>.csv")
    private val warning by option("--warning", help = "The pressure-fall warning: off, more (3 hPa), standard (4, default) or fewer (6)").choice(*FallWarning.entries.map { it.code }.toTypedArray()).default("standard")
    private val stationTemp by option("--station-temp", help = "A weather station's temperature (degrees C) for the trend's reduction").double()
    private val stationElev by option("--station-elev", help = "That station's elevation (m)").double()

    override fun run() {
        val t0 = System.nanoTime()
        val w = FallWarning.parse(warning)!!
        trendFixture?.let { return runFixtures(it, w, t0) }
        val gpxFile = gpxFile ?: run {
            val f = pressureFile ?: throw UsageError("--gpx is required (or --pressure alone for the trend, or --trend-fixture)")
            return runTrendCsv(f, w, t0)
        }
        val doc = Gpx.parse(gpxFile.readText(Charsets.UTF_8))
        val points = doc.tracks.firstOrNull()?.points.orEmpty()
        if (points.isEmpty()) throw UsageError("${gpxFile.path} has no track points")
        if (points.any { it.time == null }) throw UsageError("every track point needs a <time>")
        val act = Activity.of(activity)!!
        val d = Damping.of(damping)!!
        var options = ReadoutOptions.defaults(act).copy(barometer = !noBarometer)
        for (e in ReadoutEntry.entries) options = options.with(e, options[e].copy(damping = d))
        options = options.with(ReadoutEntry.SPEED, options.speed.copy(pace = pace, knots = knots))
        val u = if (units == "ft") ContourUnits.FEET else ContourUnits.METERS

        val dem = demFile?.let { DemElevation(RouteGraphs.pyramid(it)) }
        val est = ReadoutEstimator(ActivityProfile.of(act), options, dem)
        val pressure = pressureFile?.let(::readPressure).orEmpty().sortedBy { it.timeMs }
        val nav = pathFile?.let { f ->
            val pdoc = Gpx.parse(f.readText(Charsets.UTF_8))
            val route = pdoc.routes.firstOrNull()
            val track = pdoc.tracks.firstOrNull()
            val path = when {
                !pathTrack && route != null && route.points.size >= 2 -> NavPath.of(route)
                track != null && track.points.size >= 2 -> NavPath.of(track)
                else -> throw UsageError("${f.path} has no route or track with two points")
            }
            Navigator(path, NavOptions(mode = if (act == Activity.BIKE) TravelMode.BIKE else TravelMode.HIKE))
        }
        stationReport?.let { text ->
            val first = points.first()
            val parsed = MetarAltimeter.parse(text) ?: throw UsageError("no altimeter group in --station-report")
            // The report's own day and time belong to a live fetch (T173); a replay places it at the first fix.
            est.onStationPressure(StationPressure(parsed.station, parsed.hPa, first.lat, first.lon, first.time!!))
        }

        val trend = PressureTrend() // T178
        val series = ArrayList<Map<String, Any?>>()
        val counts = linkedMapOf<String, Int>()
        fun count(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
        var sq = 0.0
        var nEle = 0
        var maxSpeed = 0.0
        var p = 0
        var last: Readout? = null
        for ((i, pt) in points.withIndex()) {
            val t = pt.time!!
            while (p < pressure.size && pressure[p].timeMs <= t) {
                trend.onPressure(pressure[p].timeMs, pressure[p].hPa) // T178
                est.onPressure(pressure[p++])
            }
            val demH = dem?.elevation(pt.lat, pt.lon)?.takeIf { it.isFinite() }
            trend.onFix(t, demH ?: pt.ele, if (demH != null) demSource(pt.lat, pt.lon) else if (pt.ele != null) HeightSource.GNSS else null, pt.speedMps, pt.lat, pt.lon)
            val fix = LocationFix(pt.lat, pt.lon, pt.ele, t, pt.accuracyM, speedMps = pt.speedMps, courseDeg = pt.courseDeg, verticalAccuracyM = pt.verticalAccuracyM)
            val along = nav?.onFix(pt.lat, pt.lon, t, pt.accuracyM)?.let { s -> if (s.offRoute) null else s.progress }
            est.onFix(fix, along)
            val r = est.readout(t)
            last = r
            for (e in ReadoutEntry.entries) {
                val v = r[e]
                count("${e.code}.${v.state.code}")
                v.source?.takeIf { v.state != ValueState.HIDDEN }?.let { count("${e.code}.source.$it") }
            }
            if (r.speed.state == ValueState.SHOWN) maxSpeed = maxOf(maxSpeed, r.speed.value!!)
            val ele = r.elevation.value
            if (ele != null && pt.ele != null) { sq += (ele - pt.ele!!) * (ele - pt.ele!!); nEle++ }
            if (i % every == 0 || i == points.size - 1) {
                series += linkedMapOf(
                    "i" to i, "time" to GpxFormat.formatTime(t), "lat" to pt.lat, "lon" to pt.lon, "gpxEle" to pt.ele,
                ) + ReadoutEntry.entries.associate { e ->
                    e.code to (r[e].json() + ("text" to ReadoutText.of(r[e], options, u)?.full))
                } + linkedMapOf(
                    "gapS" to r.gapS, "heights" to r.heights.json(), "barometer" to r.barometer.json(),
                    "fixSpeedMps" to r.fixSpeedMps?.let(::r3), "routeSpeedMps" to r.routeSpeedMps?.let(::r3),
                )
            }
        }
        val report = linkedMapOf<String, Any?>(
            "command" to "readout", "gpxFile" to gpxFile.path, "activity" to act.code,
            "options" to (ReadoutEntry.entries.associate { it.code to options[it].encode() } + ("barometer" to options.barometer)),
            "units" to units,
            "inputs" to linkedMapOf(
                "fixes" to points.size, "pressureSamples" to pressure.size, "dem" to demFile?.path, "path" to pathFile?.path,
                "stationReport" to stationReport, "fixesWithSpeed" to points.count { it.speedMps != null },
            ),
            "summary" to linkedMapOf(
                "readouts" to points.size, "printed" to series.size, "states" to counts.toSortedMap(),
                "maxSpeedMps" to r3(maxSpeed), "elevationRmsVsGpxM" to if (nEle > 0) r3(sqrt(sq / nEle)) else null,
            ),
            "counters" to last?.counters, "provenance" to last?.provenance,
            "trend" to if (pressure.isEmpty()) null else trend.evaluate(points.last().time!! + 1, w, station(null)).json(), // T178
            "series" to series,
            "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    /** T178: bare earth where the DEM is 3DEP (the United States), a surface model elsewhere. */
    private fun demSource(lat: Double, lon: Double) = if (UnitRegions.isUnitedStates(lat, lon)) HeightSource.DEM_BARE_EARTH else HeightSource.DEM_SURFACE

    /** T178: the station temperature from the options (taken as current), else [fromFile]. */
    private fun station(fromFile: StationTemperature?, nowMs: Long = System.currentTimeMillis()): StationTemperature? {
        val t = stationTemp ?: return fromFile
        val e = stationElev ?: throw UsageError("--station-temp needs --station-elev")
        return StationTemperature("cli", t, e, nowMs)
    }

    /** T178: `--pressure` alone: the trend's CSV, the trend at its last row. */
    private fun runTrendCsv(f: File, w: FallWarning, t0: Long) {
        val series = try {
            PressureSeries.parseCsv(f.readText(Charsets.UTF_8)) { GpxFormat.parseTime(it) }
        } catch (e: IllegalArgumentException) {
            throw UsageError("${f.path}: ${e.message}")
        }
        val end = series.endMs ?: throw UsageError("${f.path} has no rows")
        val t = PressureTrend()
        series.feed(t)
        val st = station(series.station, end)
        val r = t.evaluate(end + 1, w, st)
        emit(linkedMapOf(
            "command" to "readout", "pressureFile" to f.path, "rows" to series.rows.size, "warning" to w.code,
            "station" to st?.let { linkedMapOf("station" to it.station, "tempC" to it.tempC, "elevationM" to it.elevationM) },
            "trend" to r.json(), "verdict" to verdict(r), "counters" to t.counters(),
            "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        ))
    }

    /** T178: `--trend-fixture`: each fixture's verdict, its interval and the CSV written to `out/`. */
    private fun runFixtures(which: String, w: FallWarning, t0: Long) {
        val names = if (which == "all") PressureFixtures.NAMES else listOf(which)
        val end = 1_790_000_000_000L
        File("out").mkdirs()
        val results = names.map { n ->
            val fx = PressureFixtures.of(n, end) ?: throw UsageError("--trend-fixture must be all or one of ${PressureFixtures.NAMES.joinToString(", ")}")
            val csv = File("out", "pressure-$n.csv")
            csv.writeText(fx.series.toCsv(), Charsets.UTF_8)
            val t = PressureTrend()
            fx.series.feed(t)
            val r = t.evaluate(end + 1, w, station(fx.series.station, end))
            val expected = if (w == FallWarning.STANDARD) fx.expectAlert else null
            linkedMapOf(
                "fixture" to n, "description" to fx.description, "csv" to csv.path,
                "expectedAlert" to expected, "alert" to r.alert, "pass" to expected?.let { it == r.alert },
                "verdict" to verdict(r), "trend" to r.json(),
            )
        }
        emit(linkedMapOf(
            "command" to "readout", "trendFixtures" to names, "warning" to w.code,
            "passed" to results.count { it["pass"] == true }, "of" to results.count { it["pass"] != null },
            "fixtures" to results,
            "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        ))
    }

    /** One line: the verdict, the shown tendency with its interval and why. */
    private fun verdict(r: TrendResult): String {
        val q = r.quoted ?: return "not enough data (${r.threeHourMissing ?: "-"}, ${r.oneHourMissing ?: "-"})"
        fun f(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
        val what = if (r.alert) "ALERT" else "no alert"
        return "$what: ${r.shown} ${q.mode} fall ${f(q.fall)} +/- ${f(q.interval.halfWidth)} hPa (lower ${f(q.fallLower)}, threshold ${q.thresholdHPa ?: "off"}), ${q.reason}; band ${r.label}"
    }

    private fun emit(report: Map<String, Any?>) {
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun readPressure(f: File): List<PressureSample> = f.readLines(Charsets.UTF_8).mapIndexedNotNull { n, line ->
        val cells = line.split(',').map { it.trim() }
        if (line.isBlank() || cells.size < 2) return@mapIndexedNotNull null
        val t = cells[0].toLongOrNull() ?: GpxFormat.parseTime(cells[0])
        val hPa = cells[1].toDoubleOrNull()
        if (t == null || hPa == null) {
            if (n == 0) return@mapIndexedNotNull null // a header
            throw UsageError("${f.path} line ${n + 1}: expected time,hPa")
        }
        PressureSample(t, hPa)
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0
}
