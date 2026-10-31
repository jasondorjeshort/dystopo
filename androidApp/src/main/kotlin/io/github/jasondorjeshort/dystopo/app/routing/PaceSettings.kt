package io.github.jasondorjeshort.dystopo.app.routing

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.PaceFits
import io.github.jasondorjeshort.dystopo.routing.PaceRange
import io.github.jasondorjeshort.dystopo.routing.PastEfforts
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.TimeEstimate
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * T164: route times fitted to the user's own tracks, on the phone. The fit ([PaceFits.fromTracks]: the stop cut, the
 * tracks matched onto the trail network through [RoutePlanner]'s router, the robust fit per mode) is stored as a synced
 * setting value (`paceFit`, [AppSettings.paceFit]), so every device shows the same numbers; [activate] hands the ready
 * fits to the router ([PaceFits.active], read by every [CostModel]) unless "Use the standard estimate" is on
 * ([AppSettings.paceStandard]). Reset forgets the fit and counts only tracks started from then on ([AppSettings.paceSince]).
 *
 * A refit ([refitSoon]) runs in the background when a route has been planned and when the Settings row shows, each time the
 * track list changed since the last one, and only while a trail network is loaded (it never starts a graph build itself;
 * `POST /pace/refit` does, as planning a route would). A change of the fits in use plans the route on the card again ([changed]).
 *
 * Debug routes (registered from [io.github.jasondorjeshort.dystopo.app.Routes]):
 * - `GET /pace`: the settings (`standard`, `since`, `setting`), the stored fits (`stored`, by mode, with `flatPaceMinPerKm`,
 *   `climbRateMph`, `tracks`, `km`, `ready`), `active` (the modes the router times on a fit), the card's line per mode
 *   (`card`), the row's text (`row`), the standard numbers, and the last refit (`lastRefit`: when, why, ms, the report's
 *   per-track rows with mode, rank and matching counts, or its error).
 * - `POST /pace/refit[?wait=1]`: refits now (waiting up to 60 s for the trail network near the tracks, building it if
 *   needed); with `wait` answers with the result.
 * - `POST /pace/reset`: the row's Reset (the same as `POST /settings?paceFit=&paceSince=<now>`, then a refit).
 * The switch is `POST /settings?paceStandard=1|0`.
 *
 * T184, the route card's time as a range and "you did this trail in": [estimate] is [PaceRange.estimate] over the active
 * fit's whole-track statistic (stored with the fit), shown as the headline "about 4:30" ([headline]) and the second line
 * "4:10 to 4:50, based on your last 5 hikes" ([secondLine]); a "typical" time without a range under five measured tracks,
 * past the +-25 % cap or on the standard estimate. The setting `timeEstimateForm` (`range`, the default, or `one`:
 * today's single exact number and line). [pastEffort] matches the planned route against the recorded tracks
 * ([PastEfforts], on its own worker, the last [EFFORT_CACHE] results kept by the route's points and the track list; a
 * track whose box is nowhere near the route is not read again) for the line under the water line ([pastEffortLine]).
 * `GET /route` and `POST /route` carry both ([rangeJson], [pastEffortJson]).
 */
object PaceSettings {
    private const val TAG = "DystopoPace"
    /** At most this many of the newest tracks are read for a refit (three modes' [PaceFits.MAX_TRACKS]). */
    private const val READ_TRACKS = 90
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pace-fit").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    @Volatile private var signature: String? = null
    @Volatile private var running = false
    @Volatile var lastRefit: Map<String, Any?>? = null
        private set

    /** The stored fit, decoded (empty when unreadable). */
    fun stored(): PaceFits.Stored = PaceFits.decode(AppSettings.paceFit) ?: PaceFits.Stored(emptyList(), emptyMap())

    /** Hands the ready fits to the router, or none with "Use the standard estimate" on. */
    fun activate() {
        if (AppSettings.paceStandard) PaceFits.clearActive() else PaceFits.setActive(stored().fits)
    }

    /** After the fit or the switch changed ([SettingsRoutes.apply]): the router's fits, and a route on the card planned again. */
    fun changed() {
        val before = PaceFits.active
        activate()
        if (PaceFits.active != before && RouteSession.phase == "route") RouteSession.changeMode(RouteSession.mode)
    }

    /** The plural noun of a mode's tracks: "hikes", "rides". */
    fun noun(mode: TravelMode, n: Int): String = when (mode) {
        TravelMode.BIKE -> if (n == 1) "ride" else "rides"
        else -> if (n == 1) "hike" else "hikes"
    }

    /** The route card's line under the time (hike and bike; null for drive and crow, which have no fit). */
    fun cardLine(mode: TravelMode): String? {
        if (mode !in PaceFits.FITTED) return null
        PaceFits.active[mode]?.let { return basedOn(it) }
        return if (AppSettings.paceStandard && stored().fit(mode)?.ready == true) STANDARD_BY_SETTING else STANDARD
    }

    fun basedOn(f: PaceFit): String = "Based on your last ${f.tracks} ${noun(f.mode, f.tracks)}"

    /** The Settings row's text under its heading. */
    fun rowText(units: ContourUnits = AppSettings.units): String {
        val s = stored()
        val f = s.fit(TravelMode.HIKE)
        val std = CostModel(TravelMode.HIKE, pace = null)
        val standard = "Standard estimate: ${pace(1000.0 / (std.timeS(1000.0, 0.0)), units)} on the flat, ${climb(3600.0 / std.timeS(0.0, 1.0), units)} climbing."
        val mine = when {
            f != null && f.ready -> "Your pace from your last ${f.tracks} ${noun(f.mode, f.tracks)}: ${pace(f.flatMps, units)} on the flat, ${climb(f.climbRateMph, units)} climbing."
            else -> {
                val (n, m) = s.count(TravelMode.HIKE) ?: (0 to 0.0)
                "Your pace is used once you have ${PaceFits.MIN_TRACKS} hikes over ${io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(PaceFits.MIN_METRES, units)}" +
                    " on mapped trails" + (if (n == 0) " (none yet)." else " (so far $n, ${io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(m, units)}).")
            }
        }
        return "$mine $standard"
    }

    /** A pace as minutes per km or per mile. */
    fun pace(mps: Double, units: ContourUnits): String {
        val perUnit = if (units == ContourUnits.FEET) 1609.344 else 1000.0
        val min = perUnit / mps / 60
        return String.format(Locale.US, "%d min/%s", min.roundToLong(), if (units == ContourUnits.FEET) "mi" else "km")
    }

    /** A climb rate as metres or feet an hour (to the nearest 10). */
    fun climb(mph: Double, units: ContourUnits): String {
        val v = if (units == ContourUnits.FEET) mph / 0.3048 else mph
        return "${(v / 10).roundToLong() * 10} ${if (units == ContourUnits.FEET) "ft" else "m"} an hour"
    }

    /** "Use the standard estimate" on or off, as the row's chips set it (through the settings parser, so it syncs). */
    fun setStandard(app: DystopoApp, on: Boolean) = apply(app, SettingsRoutes.Parsed.Ok(null, false, null, null, paceStandard = on))

    /** Reset: forget the fit, count only tracks from now on, then refit (which finds none yet). */
    fun reset(app: DystopoApp) {
        apply(app, SettingsRoutes.Parsed.Ok(null, false, null, null, paceFit = "", paceSince = System.currentTimeMillis()))
        signature = null
        refitSoon(app, "reset", buildGraph = false)
    }

    private fun apply(app: DystopoApp, parsed: SettingsRoutes.Parsed.Ok) {
        SettingsRoutes(app).apply(parsed)
        app.map.onMain { SettingsUi.revision++ }
    }

    /**
     * A refit in the background when the track list changed since the last one ([why] is logged); with [buildGraph]
     * false only while a trail network is already loaded.
     */
    fun refitSoon(app: DystopoApp, why: String, buildGraph: Boolean = false) {
        if (running) return
        if (!buildGraph && RoutePlanner.current?.status != "ready") return
        worker.execute { runCatching { refit(app, why, force = false, waitMs = 0) }.onFailure { Log.w(TAG, "refit ($why) failed", it) } }
    }

    /** Refits now on the calling thread (never the main one); returns the `lastRefit` entry. */
    fun refit(app: DystopoApp, why: String, force: Boolean, waitMs: Long): Map<String, Any?> {
        running = true
        val t0 = System.currentTimeMillis()
        try {
            val t = Tracking.of(app)
            val list = t.call { t.store.list() }.filter { !it.recording }.sortedByDescending { it.createdAt }.take(READ_TRACKS)
            val sig = list.joinToString("|") { "${it.id}:${it.updatedAt}:${it.points}" } + "#" + AppSettings.paceSince
            if (!force && sig == signature) return lastRefit ?: emptyMap()
            val tracks: List<Track> = list.mapNotNull { s -> t.call { t.store.track(s.id) } }
            val points = tracks.mapNotNull { tr -> tr.points.firstOrNull()?.let { it.lat to it.lon } }
            val router = try {
                RoutePlanner.router(waitMs, points)
            } catch (e: RoutePlanner.NotReady) {
                return linkedMapOf<String, Any?>("at" to t0, "why" to why, "error" to "no trail network is ready near the tracks").also { lastRefit = it }
            }
            val report = PaceFits.fromTracks(tracks, TrackMatch.snapper(router), AppSettings.paceSince)
            val value = report.setting()
            val changed = value != AppSettings.paceFit
            if (changed) apply(app, SettingsRoutes.Parsed.Ok(null, false, null, null, paceFit = value))
            signature = sig
            Log.i(TAG, "refit ($why): ${tracks.size} tracks, ${value.ifEmpty { "no fit" }}${if (changed) " (changed)" else ""} in ${System.currentTimeMillis() - t0} ms")
            return linkedMapOf(
                "at" to t0, "why" to why, "ms" to System.currentTimeMillis() - t0, "tracks" to tracks.size, "setting" to value, "changed" to changed,
                "report" to report.json(),
            ).also { lastRefit = it }
        } finally {
            running = false
        }
    }

    fun json(): Map<String, Any?> {
        val s = stored()
        val units = AppSettings.units
        val std = CostModel(TravelMode.HIKE, pace = null)
        return linkedMapOf(
            "standard" to AppSettings.paceStandard, "since" to AppSettings.paceSince, "setting" to AppSettings.paceFit,
            "stored" to PaceFits.FITTED.associate { m -> m.label to (s.fit(m)?.json() ?: s.counts[m]?.let { (n, metres) -> linkedMapOf("tracks" to n, "km" to PaceFit.r3(metres / 1000), "ready" to false) }) },
            "active" to PaceFits.active.keys.map { it.label },
            "card" to PaceFits.FITTED.associate { m -> m.label to cardLine(m) },
            "row" to rowText(units),
            "standardHike" to linkedMapOf("flatPaceMinPerKm" to std.timeS(1000.0, 0.0) / 60, "climbRateMph" to (3600.0 / std.timeS(0.0, 1.0)).roundToLong()),
            "running" to running,
            "lastRefit" to lastRefit,
        )
    }

    /** `GET /pace`, `POST /pace/refit`, `POST /pace/reset`; null for any other path. */
    fun handle(app: DystopoApp, r: Request): Response? {
        if (!r.path.startsWith("/pace")) return null
        return when (r.method to r.path) {
            "GET" to "/pace" -> Response.json(json())
            "POST" to "/pace/refit" -> {
                if (r.query["wait"] == "1") Response.json(refit(app, "debug route", force = true, waitMs = 60_000) + ("pace" to json()))
                else {
                    worker.execute { runCatching { refit(app, "debug route", force = true, waitMs = 60_000) }.onFailure { Log.w(TAG, "refit failed", it) } }
                    Response.json(mapOf("started" to true))
                }
            }
            "POST" to "/pace/reset" -> {
                reset(app)
                Response.json(json())
            }
            else -> Response.json(mapOf("error" to "GET /pace, POST /pace/refit[?wait=1] or POST /pace/reset"), 404)
        }
    }

    // ---- T184: the time as a range, and past efforts on the route ----

    /** The card's estimate for [plan] (hike and bike; null for drive and crow, which keep their plain time). */
    fun estimate(plan: PlannedRoute): TimeEstimate? {
        if (plan.mode !in PaceFits.FITTED) return null
        return PaceRange.estimate(RouteSession.summaryOf(plan).timeS, PaceFits.active[plan.mode])
    }

    /** True when the card shows the estimate's form ("about", "typical", the range); false for the single exact number. */
    fun rangeForm(): Boolean = AppSettings.timeEstimateForm == AppSettings.TIME_RANGE

    /** The card's headline: distance, climb and time, the time "about 4:30" or "typical 4:30" in the range form. */
    fun headline(plan: PlannedRoute): String {
        val e = estimate(plan)?.takeIf { rangeForm() } ?: return RouteSession.summary(plan)
        val u = AppSettings.units
        val s = RouteSession.summaryOf(plan)
        val climb = s.ascentM?.let { " · +${UnitFormat.elevation(it, u)} −${UnitFormat.elevation(s.descentM ?: 0.0, u)}" } ?: ""
        return "${UnitFormat.distance(s.distanceM, u)}$climb · ${e.headline} ${plan.mode.label}"
    }

    /** The line under it: "4:10 to 4:50, based on your last 5 hikes" with a range, else whose pace it is ([cardLine]). */
    fun secondLine(plan: PlannedRoute): String? {
        val e = estimate(plan)?.takeIf { rangeForm() }
        val fit = PaceFits.active[plan.mode]
        if (e != null && e.isRange && fit != null) return "${e.rangeText}, ${basedOn(fit).replaceFirstChar { it.lowercase() }}"
        return cardLine(plan.mode)
    }

    /** `range` in `GET /route` and `POST /route`: the setting, the card's two lines and the estimate behind them. */
    fun rangeJson(plan: PlannedRoute?): Map<String, Any?>? {
        plan ?: return null
        val e = estimate(plan)
        return linkedMapOf<String, Any?>(
            "setting" to AppSettings.timeEstimateForm, "shown" to (e != null && rangeForm()),
            "headline" to headline(plan), "line" to secondLine(plan),
            "fit" to PaceFits.active[plan.mode]?.let { linkedMapOf("low" to PaceFit.r3(it.rangeLow), "high" to PaceFit.r3(it.rangeHigh), "tracks" to it.rangeTracks) },
        ) + (e?.json() ?: mapOf("form" to "plain", "reason" to "mode"))
    }

    private const val EFFORT_CACHE = 6
    private val effortWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "past-effort").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val effortCache = object : LinkedHashMap<Pair<List<RoutePoint>, String>, Future<EffortResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<List<RoutePoint>, String>, Future<EffortResult>>?) = size > EFFORT_CACHE
    }

    /** Each track's box (south, west, north, east) by `id:updatedAt`, so a track far from the route is not read again. */
    private val boxes = ConcurrentHashMap<String, DoubleArray>()

    /** One route's past efforts, with what it cost: tracks read from the store, and skipped (another mode, far away). */
    class EffortResult(val summary: PastEfforts.Summary, val ms: Long, val read: Int, val skipped: Int, val error: String? = null)

    private fun trackList(app: DystopoApp): List<TrackSummary> =
        Tracking.of(app).let { t -> t.call { t.store.list() }.filter { !it.recording }.sortedByDescending { it.createdAt }.take(READ_TRACKS) }

    /** The computation for [points] (started when new); null for fewer than two points or no track store. */
    private fun startEffort(app: DystopoApp, points: List<RoutePoint>, mode: TravelMode): Future<EffortResult>? {
        if (points.size < 2) return null
        val list = runCatching { trackList(app) }.getOrElse { return null }
        val key = points to (mode.label + "#" + list.joinToString("|") { "${it.id}:${it.updatedAt}" })
        synchronized(effortCache) {
            effortCache[key]?.let { return it }
            val f = effortWorker.submit(Callable { computeEffort(app, points, mode, list) })
            effortCache[key] = f
            return f
        }
    }

    private fun computeEffort(app: DystopoApp, points: List<RoutePoint>, mode: TravelMode, list: List<TrackSummary>): EffortResult {
        val t0 = System.currentTimeMillis()
        val pad = 0.003 // about 300 m
        val s = points.minOf { it.lat } - pad
        val n = points.maxOf { it.lat } + pad
        val w = points.minOf { it.lon } - pad * 1.5
        val e = points.maxOf { it.lon } + pad * 1.5
        fun overlaps(b: DoubleArray) = b[0] <= n && b[2] >= s && b[1] <= e && b[3] >= w
        var read = 0
        var skipped = 0
        return try {
            val t = Tracking.of(app)
            val near = ArrayList<Track>()
            for (sum in list) {
                // The track's own mode, where it says one: a ride is not "this trail" for a hike.
                val m = sum.activity?.let { PaceFits.modeOf(it) }
                if (m != null && m != mode) { skipped++; continue }
                val bkey = "${sum.id}:${sum.updatedAt}"
                val known = boxes[bkey]
                if (known != null && !overlaps(known)) { skipped++; continue }
                val track = t.call { t.store.track(sum.id) } ?: continue
                read++
                val pts = track.points
                if (pts.isEmpty()) continue
                val box = doubleArrayOf(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon })
                boxes[bkey] = box
                if (!overlaps(box)) continue
                if (PaceFits.modeOf(track, null).let { it != null && it != mode }) continue
                near += track
            }
            val summary = PastEfforts.summary(points, near)
            Log.i(TAG, "past efforts: ${near.size} tracks near the route of ${list.size} (read $read), ${summary.matches.size} overlapping in ${System.currentTimeMillis() - t0} ms")
            EffortResult(summary, System.currentTimeMillis() - t0, read, skipped)
        } catch (ex: Exception) {
            Log.w(TAG, "past efforts failed", ex)
            EffortResult(PastEfforts.Summary(null, null, null, emptyList()), System.currentTimeMillis() - t0, read, skipped, "${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    /** The result for [points] when it is ready now, else null (and the computation runs); for the card's first frame. */
    fun pastEffortCached(points: List<RoutePoint>, mode: TravelMode, app: DystopoApp = DystopoApp.instance): EffortResult? {
        if (mode !in PaceFits.FITTED) return null
        return startEffort(app, points, mode)?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }
    }

    /** Waits up to [ms] for [points]' past efforts (off the main thread); null for drive and crow, or on a timeout. */
    fun pastEffort(points: List<RoutePoint>, mode: TravelMode, ms: Long = 10_000, app: DystopoApp = DystopoApp.instance): EffortResult? {
        if (mode !in PaceFits.FITTED) return null
        return runCatching { startEffort(app, points, mode)?.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun utcOffset(now: Long): Long = TimeZone.getDefault().getOffset(now).toLong()

    /** The card's line: "You did this trail in 3:52, June", "Part of this trail was recorded", or null. */
    fun pastEffortLine(r: EffortResult?): String? {
        val now = System.currentTimeMillis()
        return r?.summary?.line(now, utcOffset(now))
    }

    /** `pastEffort` in `GET /route` and `POST /route` (waits up to [waitMs] for it). */
    fun pastEffortJson(plan: PlannedRoute?, waitMs: Long = 10_000): Map<String, Any?>? {
        plan ?: return null
        if (plan.mode !in PaceFits.FITTED) return linkedMapOf("state" to "mode")
        val r = pastEffort(plan.route.points, plan.mode, waitMs) ?: return linkedMapOf("state" to "computing")
        val now = System.currentTimeMillis()
        return r.summary.json(now, utcOffset(now)) + linkedMapOf(
            "ms" to r.ms, "read" to r.read, "skipped" to r.skipped, "error" to r.error,
            "rule" to linkedMapOf(
                "toleranceM" to PastEfforts.TOLERANCE_M, "lowAccuracyToleranceM" to PastEfforts.LOW_ACCURACY_TOLERANCE_M,
                "match" to PastEfforts.MATCH_SHARE, "partial" to PastEfforts.PARTIAL_SHARE, "onRoute" to PastEfforts.ON_ROUTE_SHARE,
            ),
        )
    }

    /** The card's lines (ADR 0031 strings). */
    const val STANDARD = "Standard estimate"
    const val STANDARD_BY_SETTING = "Standard estimate (your setting)"
    /** The row's chips. */
    const val YOUR_PACE = "Your pace"
    const val USE_STANDARD = "Use the standard estimate"
    const val RESET = "Reset"
}
