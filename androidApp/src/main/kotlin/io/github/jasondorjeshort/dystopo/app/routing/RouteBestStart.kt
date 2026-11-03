package io.github.jasondorjeshort.dystopo.app.routing

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.nav.NavShade
import io.github.jasondorjeshort.dystopo.app.readout.PressureSource
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineKind
import io.github.jasondorjeshort.dystopo.nav.DeadlineSetting
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.routing.BestStart
import io.github.jasondorjeshort.dystopo.routing.BestStartAim
import io.github.jasondorjeshort.dystopo.routing.BestStartResult
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.HotDay
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.ShadeAlong
import io.github.jasondorjeshort.dystopo.routing.ShadeText
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * T209: the start time for a planned route on the phone, over `shared`'s [BestStart]: the start of today (from now on;
 * tomorrow once today has no start that finishes in time) with the most terrain shade on a hot day and the most sun
 * otherwise, as one line on the route card ([BestStartLine], [ShadeText.bestStartLine]) and `GET /route` `bestStart`.
 * Never stored. One worker thread below normal priority, as [NavShade]'s; the last [CACHE] answers are kept by the route's
 * points, its mode, the aim and the local date, so the card and the debug route share one computation per plan (the
 * [RouteWater] and [RouteExposure] pattern).
 *
 * - **The terrain**: the DEM pack under the route's start ([io.github.jasondorjeshort.dystopo.app.options.ShadeService.pyramidAt],
 *   as navigation's terrain shade reads it), read once for every start.
 * - **The pace**: navigation's ([TurnPace.of] the mode's [CostModel], T164's fitted pace when the user has one).
 * - **The deadline**: the terrain sunset at the route's end ([Deadline.resolve] over the point answer of that day), less
 *   the turn-back margin (`turnMarginMin`), as the turn-back clock would count it.
 * - **The aim**: [HotDay] at the route's lowest point, from the chosen station's temperature lapsed there
 *   ([PressureSource.stationTemperature], at most 3 hours old; none is not a hot day). Debug: `POST /nav/shade` `at=` and
 *   `tempC=` ([NavShade.clockOffsetMs], [NavShade.syntheticTempC]) apply here too, so an emulator can plan a July morning.
 *
 * Not for drive routes. No setting (the survey of 2026-11-02: no app suggests a start for shade, so practice does not
 * split, ADR 0044); the hot-day line is in the help text.
 */
object RouteBestStart {
    /** One plan's answer: the sweep, the time, the temperature used, or why there is none. */
    class Result(
        val best: BestStartResult?,
        val ms: Long,
        val temperature: Map<String, Any?>,
        val state: String,
        val error: String? = null,
    ) {
        val line: String? get() = best?.let { ShadeText.bestStartLine(it, ::clock) }
    }

    /**
     * The cache's key: everything the sweep reads that can change under the same plan. [marginMin] is the turn-back
     * margin the deadline is counted with (`turnMarginMin`; the verifier's FIX: without it a changed margin kept the old
     * line); `turnDeadline` is not read (the sweep always counts to the terrain sunset).
     */
    internal data class Key(
        val points: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>, val mode: TravelMode, val aim: BestStartAim,
        val date: String, val packs: Int, val marginMin: Int,
    )

    /** The key of [plan]'s sweep at [nowMs] (its local date) for [aim], [packs] (the pack set's identity) and [marginMin]. */
    internal fun keyOf(plan: PlannedRoute, aim: BestStartAim, nowMs: Long, packs: Int, marginMin: Int): Key =
        Key(plan.route.points, plan.mode, aim, dateKey(nowMs), packs, marginMin)

    private const val CACHE = 6
    private const val TAG = "DystopoBestStart"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-best-start").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val cache = object : LinkedHashMap<Key, Future<Result>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Future<Result>>?) = size > CACHE
    }

    @Volatile var computed = 0
        private set

    private val app get() = DystopoApp.instance

    /** Whether [mode] gets the line: hike, bike and crow; not drive. */
    fun applies(mode: TravelMode): Boolean = mode != TravelMode.DRIVE

    private fun pathOf(plan: PlannedRoute): NavPath = NavPath.of(RouteSession.unifiedRoute(plan))

    /** The air at the route's lowest point: the debug temperature, else the station's lapsed there; tempC null when unknown. */
    fun temperature(path: NavPath): Map<String, Any?> {
        val lowest = path.points.mapNotNull { it.ele }.minOrNull()
        NavShade.syntheticTempC?.let { return linkedMapOf("tempC" to it, "source" to "synthetic", "routeLowestM" to lowest?.let(::r1)) }
        val st = PressureSource.stationTemperature() ?: return linkedMapOf("tempC" to null, "source" to "noStation")
        val ageMin = (System.currentTimeMillis() - st.timeMs) / 60_000.0
        val base = linkedMapOf<String, Any?>("stationTempC" to st.tempC, "stationEleM" to st.elevationM, "routeLowestM" to lowest?.let(::r1), "ageMin" to r1(ageMin))
        if (ageMin * 60_000 > HotDay.MAX_AGE_MS) return linkedMapOf<String, Any?>("tempC" to null, "source" to "stale") + base
        if (lowest == null) return linkedMapOf<String, Any?>("tempC" to null, "source" to "noHeight") + base
        return linkedMapOf<String, Any?>("tempC" to r1(HotDay.atHeight(st.tempC, st.elevationM, lowest)), "source" to "station") + base
    }

    /** The computation for [plan] (started now when new); null for drive or a line of fewer than two points. */
    fun start(plan: PlannedRoute): Future<Result>? {
        if (!applies(plan.mode) || plan.route.points.size < 2) return null
        val path = pathOf(plan)
        val temp = temperature(path)
        val aim = BestStartAim.of(temp["tempC"] as Double?)
        val now = NavShade.now()
        val packs = runCatching { System.identityHashCode(app.tiles.packs) }.getOrDefault(0)
        val margin = AppSettings.turnMarginMin
        val key = keyOf(plan, aim, now, packs, margin)
        synchronized(cache) {
            cache[key]?.let { return it }
            val f = worker.submit(Callable { compute(plan, path, aim, temp, now, margin) })
            cache[key] = f
            return f
        }
    }

    /** The result when it is ready now, else null (and the computation runs). */
    fun cached(plan: PlannedRoute): Result? = start(plan)?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }

    /** Waits up to [ms] for the result (off the main thread); null for drive or on a timeout. */
    fun await(plan: PlannedRoute, ms: Long = 10_000): Result? = runCatching { start(plan)?.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()

    private fun compute(plan: PlannedRoute, path: NavPath, aim: BestStartAim, temp: Map<String, Any?>, now: Long, marginMin: Int): Result {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val first = path.points.first()
            val shade = app.tiles.shade
            val pyramid = shade.pyramidAt(first.lat, first.lon)
                ?: return Result(null, SystemClock.elapsedRealtime() - t0, temp, "noData")
            val end = path.points.last()
            val deadlineFor: (Long) -> Deadline = { dayMs ->
                val a = runCatching { shade.pointAnswer(end.lat, end.lon, dayMs) }.getOrNull()
                Deadline.resolve(DeadlineSetting(DeadlineKind.TERRAIN), end.lat, end.lon, dayMs, 0L, a?.day, noTerrainData = a == null)
            }
            val timing = TurnAround(path, TurnPace.of(CostModel(plan.mode)))
            val r = BestStart.suggest(
                path, timing, aim, now, deadlineFor, marginMin, minOf(pyramid.maxZoom, ShadeAlong.MAX_ZOOM),
                read = { w -> pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) }, tileSize = pyramid.tileSize,
            )
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "${r.candidates.size} starts over ${path.totalM.toInt()} m in $ms ms (${r.stats?.msPerStart()} ms a start, ${r.stats?.rays} rays): ${ShadeText.bestStartLine(r, ::clock)}")
            return Result(r, ms, temp, "ready")
        } catch (e: Throwable) { // an out-of-memory on a long route is reported, not a crash
            Log.w(TAG, "start time for the route failed", e)
            return Result(null, SystemClock.elapsedRealtime() - t0, temp, "error", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            computed++
        }
    }

    private fun io.github.jasondorjeshort.dystopo.routing.BestStartStats.msPerStart(): Long = if (starts > 0) (marchMs / starts).toLong() else 0L

    /** `GET /route` `bestStart` (waits up to [waitMs]): `state` `ready|computing|noRoute|mode|noData|error`, the card's `line`, the sweep. */
    fun json(plan: PlannedRoute?, waitMs: Long = 10_000): Map<String, Any?> {
        if (plan == null || plan.route.points.size < 2) return linkedMapOf("state" to "noRoute")
        if (!applies(plan.mode)) return linkedMapOf("state" to "mode", "mode" to plan.mode.label)
        val r = await(plan, waitMs) ?: return linkedMapOf("state" to "computing")
        return linkedMapOf<String, Any?>(
            "state" to r.state, "line" to r.line, "ms" to r.ms, "error" to r.error, "temperature" to r.temperature,
            "now" to clock(NavShade.now()), "clockOffsetMs" to NavShade.clockOffsetMs, "computed" to computed,
        ) + (r.best?.json(::clock) ?: emptyMap())
    }

    /** The card's clock ("7:30"), the phone's zone, as the turn-back clock's. */
    fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun dateKey(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}

private val BestStartInk = Color(0xFF2B2420)

/**
 * T209: the route card's start-time line under the route's terrain line: "Start at 7:30 for the most terrain shade (about
 * 1 h 10 min in shade)", "Start at 10:00 for the most sun", "Start by 6:30 to finish before terrain sunset", "Your start
 * time is the shadiest" ([ShadeText.bestStartLine]), with its help icon. Nothing until the sweep answers, for drive
 * routes, or without elevation data under the route.
 */
@Composable
internal fun BestStartLine(plan: PlannedRoute) {
    if (!RouteBestStart.applies(plan.mode)) return
    var res by remember(plan) { mutableStateOf(RouteBestStart.cached(plan)) }
    LaunchedEffect(plan) {
        if (res == null) res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RouteBestStart.await(plan, 120_000) }
    }
    val line = res?.line ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(line, style = TextStyle(color = BestStartInk, fontSize = 13.sp), modifier = Modifier.weight(1f, fill = false))
        HelpIcon("route.bestStart")
    }
}

private const val HOT_C = HotDay.THRESHOLD_C

/** T209: the start-time line's help text (`HelpRegistry`), here beside its icon. */
internal val BEST_START_HELP: List<HelpText> = listOf(
    HelpText("route.bestStart", "Start time", "The map: route card", needs = "a planned hike or bike route") {
        "The start time today with the most terrain shade along the route on a hot day, else the most sun, keeping starts " +
            "that finish before terrain sunset with your turn-back margin. A hot day is ${HOT_C.toInt()} °C (${Math.round(HOT_C * 9 / 5 + 32)} °F) or more at the " +
            "route's lowest point, from the weather station. Trees are not counted."
    },
)
