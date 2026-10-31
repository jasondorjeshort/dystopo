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
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineKind
import io.github.jasondorjeshort.dystopo.nav.DeadlineSetting
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.nav.TurnClock
import io.github.jasondorjeshort.dystopo.nav.WayBack
import io.github.jasondorjeshort.dystopo.nav.WayPoint
import io.github.jasondorjeshort.dystopo.nav.Navigator
import io.github.jasondorjeshort.dystopo.nav.NavEvent
import io.github.jasondorjeshort.dystopo.routing.CostModel
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
 *
 * T163: `devtool nav --turn-around --path <gpx> [--path-track] --deadline sunset|civil|<ISO instant> --margin-min 30`
 * prints the turn-around clock ([TurnAround]) at readings along the line (`--along-m` for one), at `--now` (default the
 * line's first track time): the day's sun times at the line's end, and per reading the state, the turn-back time, the
 * return time each way and the arrival. `--retrace` times the way out back along the line only (free tracking).
 *
 * T180, the clock series and the direction flips: `nav --turn-around --path <gpx> --path-track --retrace --replay` feeds
 * the track's points to the phone's free-tracking way back ([WayBack]: the anchor by the speed rule, the [Retrace] flips)
 * and prints a reading every `--every-s` seconds of fix time (and at each flip), the anchor and the flips;
 * `nav --turn-around --path <route gpx> --fixes <walk gpx>` navigates the route with the walk's points ([Navigator]: the
 * [PathTracker]'s progress and jumps, "Heading back") and prints the same series, the flips and whether it arrived.
 * `--expect-flips N` exits 1 unless N flips happen.
 */
class NavCommand : CliktCommand(name = "nav") {
    init {
        subcommands(NavReplayCommand())
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Navigation on the JVM (T65): `nav replay` runs a GPX walk along a route or track through the phone's off-route logic; " +
            "T163: `nav --turn-around --path <gpx> --deadline sunset|civil|<iso instant> --margin-min 30` prints the turn-around clock along the line."

    // T163: the turn-around clock.
    private val turnAround by option("--turn-around", help = "T163: print the turn-around clock along --path as JSON").flag()
    private val pathFile by option("--path", help = "T163: GPX with the route (or track) the clock runs along").file(mustExist = true, canBeDir = false)
    private val pathTrack by option("--path-track", help = "T163: use the GPX's first track instead of its first route").flag()
    private val deadline by option("--deadline", help = "T163: sunset (default), civil (end of civil twilight) or an ISO 8601 instant (2025-08-14T18:30:00-07:00)").default("sunset")
    private val marginMin by option("--margin-min", help = "T163: minutes to be out before the deadline (default 30)").int().default(TurnAround.DEFAULT_MARGIN_MIN)
    private val now by option("--now", help = "T163: the time of the reading, ISO 8601 (default: the line's first track time, else now)")
    private val alongM by option("--along-m", help = "T163: one reading this far along the line (default: --samples readings over it)").double()
    private val samples by option("--samples", help = "T163: readings along the line (default 11: every tenth)").int().default(11)
    private val mode by option("--mode", help = "T163: hike (default) or bike: the time model").choice("hike", "bike").default("hike")
    private val retrace by option("--retrace", help = "T163: free tracking: the way out is always back along the line (the recorded track)").flag()
    private val replay by option("--replay", help = "T180: with --retrace, feed the track's points one by one as a recording (the anchor, the flips) and print the clock series").flag()
    private val fixesFile by option("--fixes", help = "T180: navigate --path with this GPX's track points as fixes and print the clock series and the flips").file(mustExist = true, canBeDir = false)
    private val everyS by option("--every-s", help = "T180: seconds of fix time between readings of the series (default 300)").int().default(300)
    private val expectFlips by option("--expect-flips", help = "T180: exit 1 unless the series has this many flips to heading back").int()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        if (!turnAround) throw UsageError("use `nav replay ...` or `nav --turn-around --path <gpx> ...`")
        val f = pathFile ?: throw UsageError("--turn-around needs --path")
        val t0 = System.nanoTime()
        val doc = Gpx.parse(f.readText(Charsets.UTF_8))
        val route = doc.routes.firstOrNull()
        val track = doc.tracks.firstOrNull()
        val path = when {
            !pathTrack && route != null && route.points.size >= 2 -> NavPath.of(route, doc.waypoints)
            track != null && track.points.size >= 2 -> NavPath.of(track, doc.waypoints)
            else -> throw UsageError("${f.path} has no route or track with two points")
        }
        val nowMs = now?.let { parseInstant(it) ?: throw UsageError("--now must be an ISO 8601 instant (got $it)") }
            ?: track?.points?.firstOrNull()?.time ?: System.currentTimeMillis()
        val end = path.points.last()
        val dl = when (deadline.trim().lowercase()) {
            "sunset" -> Deadline.resolve(DeadlineSetting.SUNSET, end.lat, end.lon, nowMs, 0)
            "civil" -> Deadline.resolve(DeadlineSetting(DeadlineKind.CIVIL), end.lat, end.lon, nowMs, 0)
            else -> Deadline(DeadlineKind.TIME, parseInstant(deadline) ?: throw UsageError("--deadline must be sunset, civil or an ISO 8601 instant (got $deadline)"), DeadlineKind.TIME.label)
        }
        if (marginMin !in TurnAround.MARGIN_RANGE) throw UsageError("--margin-min must be ${TurnAround.MARGIN_RANGE.first} to ${TurnAround.MARGIN_RANGE.last}")
        if (replay || fixesFile != null) {
            if (replay && !retrace) throw UsageError("--replay needs --retrace (free tracking)")
            return series(f, doc, path, dl, nowMs, t0)
        }
        val ta = TurnAround(path, TurnPace.of(CostModel(TravelMode.of(mode)!!)), retraceOnly = retrace)
        val day = Sun.dayAt(end.lat, end.lon, nowMs)
        // Clock times in the line's mean solar time (the JSON's ISO instants are UTC); whole minutes of offset.
        val zone = java.time.ZoneOffset.ofTotalSeconds(((end.lon / 15) * 60).roundToLong().toInt() * 60)
        val clock: (Long) -> String = { ms -> java.time.Instant.ofEpochMilli(ms).atOffset(zone).toLocalTime().withSecond(0).withNano(0).toString() }
        val iso: (Long?) -> String? = { ms -> ms?.let { java.time.Instant.ofEpochMilli(it).toString() } }
        val n = samples.coerceAtLeast(2)
        val at = alongM?.let { listOf(it) } ?: (0 until n).map { path.totalM * it / (n - 1) }
        val readings = at.map { a ->
            val c = ta.clock(a, nowMs, dl, marginMin)
            linkedMapOf<String, Any?>("text" to c.text(clock)) + (c.json() - setOf("deadline", "marginMin", "pace", "nowMs", "loop")) +
                linkedMapOf("turnBack" to iso(c.turnBackMs), "arrival" to iso(c.arrivalMs))
        }
        val report = linkedMapOf<String, Any?>(
            "command" to "nav --turn-around", "pathFile" to f.path, "name" to path.name,
            "totalM" to Math.round(path.totalM), "totalAscentM" to Math.round(path.totalAscentM), "loop" to ta.loop, "retraceOnly" to retrace,
            "now" to iso(nowMs), "nowMs" to nowMs, "marginMin" to marginMin, "pace" to ta.pace.label, "mode" to mode,
            "deadline" to linkedMapOf("kind" to dl.kind.key, "at" to iso(dl.atMs), "atMs" to dl.atMs, "label" to dl.label),
            "sun" to linkedMapOf(
                "lat" to end.lat, "lon" to end.lon, "date" to "%04d-%02d-%02d".format(day.year, day.month, day.day),
                "noon" to iso(day.noonMs), "sunset" to iso(day.sunsetMs), "civilTwilightEnd" to iso(day.civilEndMs),
            ),
            "totalTimeS" to Math.round(ta.totalS),
            "label" to ta.clock(at.first(), nowMs, dl, marginMin).label(clock),
            "clockZone" to zone.id,
            "readings" to readings,
            "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        )
        echo(pretty(report))
    }

    /** T180: the clock fix by fix, through the phone's free-tracking way back (`--replay`) or navigation (`--fixes`). */
    private fun series(f: File, doc: GpxDocument, path: NavPath, dl: Deadline, nowMs: Long, t0: Long) {
        val pace = TurnPace.of(CostModel(TravelMode.of(mode)!!))
        val end = path.points.last()
        val zone = java.time.ZoneOffset.ofTotalSeconds(((end.lon / 15) * 60).roundToLong().toInt() * 60)
        val clock: (Long) -> String = { ms -> java.time.Instant.ofEpochMilli(ms).atOffset(zone).toLocalTime().withSecond(0).withNano(0).toString() }
        val readings = ArrayList<Map<String, Any?>>()
        val flips = ArrayList<Map<String, Any?>>()
        var lastAt = Long.MIN_VALUE
        var wasBack = false
        fun reading(i: Int, t: Long, c: TurnClock, back: Boolean, extra: Map<String, Any?>) {
            val flip = back && !wasBack
            if (flip) flips += linkedMapOf<String, Any?>("fix" to i, "time" to java.time.Instant.ofEpochMilli(t).toString(), "alongM" to Math.round(c.alongM)) + extra
            if (flip || lastAt == Long.MIN_VALUE || t - lastAt >= everyS * 1000L) {
                lastAt = t
                readings += linkedMapOf<String, Any?>(
                    "fix" to i, "time" to clock(t), "text" to c.text(clock), "state" to c.state.key, "colour" to c.colour,
                    "headingBack" to back, "alongM" to Math.round(c.alongM), "retraceS" to c.retraceS.roundToLong(), "returnS" to c.returnS.roundToLong(),
                ) + extra
            }
            wasBack = back
        }
        val report = linkedMapOf<String, Any?>("command" to "nav --turn-around", "pathFile" to f.path, "name" to path.name)
        if (replay) {
            val pts = doc.tracks.flatMap { it.points }
            if (pts.any { it.time == null }) throw UsageError("--replay needs a <time> on every point")
            val wb = WayBack(pace)
            pts.forEachIndexed { i, p ->
                wb.add(WayPoint(p.lat, p.lon, p.ele, p.time, p.speedMps, p.flags != 0))
                val c = wb.clock(p.time!!, dl, marginMin) ?: return@forEachIndexed
                reading(i, p.time!!, c, wb.retrace.headingBack, linkedMapOf("anchor" to wb.anchorSource.key))
            }
            report += linkedMapOf(
                "mode" to "recording", "fixes" to pts.size, "anchor" to wb.json()["anchor"], "retrace" to wb.retrace.json(),
                "flips" to flips, "flipCount" to wb.retrace.flips,
            )
        } else {
            val walk = Gpx.parse(fixesFile!!.readText(Charsets.UTF_8)).tracks.flatMap { it.points }
            if (walk.isEmpty() || walk.any { it.time == null }) throw UsageError("--fixes needs track points with a <time> each")
            val nav = Navigator(path, NavOptions(mode = TravelMode.of(mode)!!))
            val ta = TurnAround(path, pace)
            var farthest = 0.0
            walk.forEachIndexed { i, p ->
                val s = nav.onFix(p.lat, p.lon, p.time!!, p.accuracyM)
                if (!s.offRoute) farthest = maxOf(farthest, s.progress)
                if (s.arrived) return@forEachIndexed
                val c = ta.clock(s.progress, p.time!!, dl, marginMin, if (s.offRoute) s.projection.distanceM else 0.0, farthest, s.headingBack)
                reading(i, p.time!!, c, s.headingBack, linkedMapOf("progressM" to Math.round(s.progress), "offRoute" to s.offRoute))
            }
            report += linkedMapOf(
                "mode" to "navigation", "fixesFile" to fixesFile!!.path, "fixes" to walk.size, "totalM" to Math.round(path.totalM), "loop" to nav.loop,
                "wayBackStretches" to nav.wayBackFrom.map { (a, b) -> linkedMapOf("fromM" to Math.round(a), "toM" to Math.round(b)) },
                "tracker" to nav.tracker.json(), "arrived" to (nav.state?.arrived ?: false),
                "events" to nav.events.groupingBy { it.type.substringBefore(':') }.eachCount(),
                "flips" to flips, "flipCount" to nav.events.count { it.type == NavEvent.HEADING_BACK },
            )
        }
        report += linkedMapOf(
            "deadline" to linkedMapOf("kind" to dl.kind.key, "at" to dl.atMs?.let { java.time.Instant.ofEpochMilli(it).toString() }, "label" to dl.label),
            "marginMin" to marginMin, "pace" to pace.label, "everyS" to everyS, "clockZone" to zone.id, "now" to java.time.Instant.ofEpochMilli(nowMs).toString(),
            "readings" to readings, "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
        )
        val got = report["flipCount"] as Int
        val failures = expectFlips?.let { if (got != it) listOf("expected $it flips, got $got") else emptyList() } ?: emptyList()
        report["ok"] = failures.isEmpty()
        report["failures"] = failures
        echo(pretty(report))
        if (failures.isNotEmpty()) throw ProgramResult(1)
    }

    private fun parseInstant(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
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
    private val mode by option("--mode", help = "hike (default), bike, drive or crow (T108: one arrival, the hike time model): the time model (and the directions' mode)").choice("hike", "bike", "drive", "crow").default("hike")
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
