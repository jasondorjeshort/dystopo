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
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.PaceFits
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
                    " on mapped trails (so far $n, ${io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(m, units)})."
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

    /** The card's lines (ADR 0031 strings). */
    const val STANDARD = "Standard estimate"
    const val STANDARD_BY_SETTING = "Standard estimate (your setting)"
    /** The row's chips. */
    const val YOUR_PACE = "Your pace"
    const val USE_STANDARD = "Use the standard estimate"
    const val RESET = "Reset"
}
