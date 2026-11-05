package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineKind
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.nav.TerrainSunset
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** T209: what the start-time sweep looks for: terrain shade on a hot day ([HotDay]), the sun otherwise. [key] is the JSON value. */
enum class BestStartAim(val key: String) {
    SHADE("shade"),
    SUN("sun");

    /** The light whose minutes count. */
    val light: ShadeLight get() = if (this == SHADE) ShadeLight.SHADE else ShadeLight.SUN

    companion object {
        /** Terrain shade when [tempC] (the air at the route, null for unknown) is a hot day by [HotDay.hot], else the sun. */
        fun of(tempC: Double?): BestStartAim = if (HotDay.hot(tempC)) SHADE else SUN
    }
}

/** T209: what the card's line says ([ShadeText.bestStartLine]). [key] is the JSON value. */
enum class BestStartKind(val key: String) {
    /** Another start has more of the aim's light: "Start at 7:30 for the most terrain shade (about 70 min in shade)". */
    PICK("pick"),

    /** The walker's own start has as much as any: "Your start time is the shadiest". */
    CURRENT("current"),

    /** The deadline is what binds (no start finishes before it, or the latest that does is the pick): "Start by 6:30 to finish before terrain sunset". */
    DEADLINE("deadline"),

    /** No start meets the aim's light at all: "No terrain shade along this route today". */
    NONE("none"),

    /** T218: every start that finishes in time is as sunny (or as shady) as any other: no line, there is nothing to choose. */
    ANY("any"),

    /** No daylight to sweep (no sunrise or sunset that day): no line. */
    NO_DAY("noDay"),
}

/**
 * T209: one start time of the sweep: when the walk starts and ends at the route's pace, the minutes met in each light
 * (the stretches' minutes, as the navigation card counts them), whether it finishes before the deadline less the margin
 * ([deadlineMet]), which pass added it (`grid` every [BestStart.STEP_MIN], `refine` [BestStart.REFINE_MIN] either side
 * of the grid's pick, `current` the walker's own start, `latest` the latest start that meets the deadline), and whether
 * its rays needed terrain the shared read did not hold ([reread]).
 */
class StartCandidate(
    val startMs: Long,
    val arrivalMs: Long,
    val shadeMin: Double,
    val sunMin: Double,
    val downMin: Double,
    val noDataMin: Double,
    val deadlineMet: Boolean,
    val pass: String,
    val reread: Boolean = false,
) {
    val walkMin: Double get() = (arrivalMs - startMs) / 60_000.0

    /** The minutes in [aim]'s light. */
    fun minutes(aim: BestStartAim): Double = if (aim == BestStartAim.SHADE) shadeMin else sunMin

    /** The share of the walking time in [aim]'s light, 0 to 1. */
    fun fraction(aim: BestStartAim): Double = if (walkMin > 0) minutes(aim) / walkMin else 0.0

    fun json(clock: ((Long) -> String)? = null): Map<String, Any?> = linkedMapOf(
        "start" to clock?.invoke(startMs), "startMs" to startMs, "end" to clock?.invoke(arrivalMs), "arrivalMs" to arrivalMs,
        "shadeFraction" to r3(fraction(BestStartAim.SHADE)), "sunFraction" to r3(fraction(BestStartAim.SUN)),
        "shadeMin" to r1(shadeMin), "sunMin" to r1(sunMin), "downMin" to r1(downMin), "noDataMin" to r1(noDataMin),
        "deadlineMet" to deadlineMet, "pass" to pass, "reread" to reread,
    )
}

/** T209: what one sweep took: the starts marched, the rays and ray samples, the terrain read and the time. */
class BestStartStats(
    val starts: Int,
    val rays: Long,
    val steps: Long,
    val zoom: Int,
    val coarseZoom: Int?,
    val coarsened: Boolean,
    val cellsRead: Long,
    val rereads: Int,
    val readMs: Double,
    val marchMs: Double,
    val totalMs: Double,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "starts" to starts, "rays" to rays, "steps" to steps, "zoom" to zoom, "coarseZoom" to coarseZoom, "coarsened" to coarsened,
        "cellsRead" to cellsRead, "rereads" to rereads, "readMs" to r1(readMs), "marchMs" to r1(marchMs), "totalMs" to r1(totalMs),
        "msPerStart" to r1(if (starts > 0) marchMs / starts else 0.0),
    )
}

/**
 * T209: the start-time sweep's answer: the [aim], what the line says ([kind]) and the start it names ([pick]; null for
 * [BestStartKind.DEADLINE] when no start of the day finishes in time, [BestStartKind.NONE] and [BestStartKind.NO_DAY]),
 * every start marched ([candidates], by time), the window swept ([fromMs] to [toMs]: the day's daylight, from now on the
 * phone), the walker's own start ([currentMs]), the [deadline] and [marginMin] the walk must finish inside, the latest
 * start that does ([latestStartMs]), and whether the day swept is tomorrow ([tomorrow]).
 */
class BestStartResult(
    val aim: BestStartAim,
    val kind: BestStartKind,
    val pick: StartCandidate?,
    val candidates: List<StartCandidate>,
    val fromMs: Long,
    val toMs: Long,
    val currentMs: Long?,
    val deadline: Deadline?,
    val marginMin: Int,
    val latestStartMs: Long?,
    val tomorrow: Boolean,
    val walkS: Double,
    val stats: BestStartStats?,
) {
    /** The deadline is the sun going behind the terrain ([TerrainSunset.BEHIND]), not the flat sunset. */
    val byTerrain: Boolean get() = deadline?.kind == DeadlineKind.TERRAIN && deadline.terrain == TerrainSunset.BEHIND

    /** The walker's own start, when it was marched. */
    val current: StartCandidate? get() = currentMs?.let { c -> candidates.firstOrNull { it.startMs == c } }

    fun json(clock: ((Long) -> String)? = null): Map<String, Any?> = linkedMapOf(
        "aim" to aim.key, "kind" to kind.key, "line" to clock?.let { ShadeText.bestStartLine(this, it) },
        "pick" to pick?.json(clock), "current" to current?.json(clock), "tomorrow" to tomorrow,
        "from" to clock?.invoke(fromMs), "fromMs" to fromMs, "to" to clock?.invoke(toMs), "toMs" to toMs,
        "walkMin" to r1(walkS / 60),
        "deadline" to deadline?.let { d ->
            linkedMapOf(
                "kind" to d.kind.key, "atMs" to d.atMs, "at" to d.atMs?.let { clock?.invoke(it) }, "label" to d.label,
                "terrain" to d.terrain?.key, "terrainSunsetMs" to d.terrainSunsetMs, "flatSunsetMs" to d.flatSunsetMs, "byTerrain" to byTerrain,
            )
        },
        "marginMin" to marginMin, "latestStartMs" to latestStartMs, "latestStart" to latestStartMs?.let { clock?.invoke(it) },
        "rule" to linkedMapOf(
            "stepMin" to BestStart.STEP_MIN, "refineMin" to BestStart.REFINE_MIN, "tieMin" to BestStart.TIE_MIN, "startByWithinMin" to BestStart.START_BY_WITHIN_MIN,
            "hotDayC" to HotDay.THRESHOLD_C, "objective" to "the fraction of walking time in ${if (aim == BestStartAim.SHADE) "terrain shade" else "sun"}",
        ),
        "table" to candidates.map { it.json(clock) },
        "stats" to stats?.json(),
    )
}

/**
 * T209: the start time with the most terrain shade on a hot day, the most sun otherwise (the idea of 2026-11-01, "best
 * start time for a route"), over [ShadeAlong]'s rays at the time the walker reaches each sample.
 *
 * - **Starts**: every [STEP_MIN] minutes on the clock across the window (the day's daylight at the route's start: flat
 *   sunrise to flat sunset; on the phone from now on), plus the walker's own start, then [REFINE_MIN] minutes either side
 *   of the grid's pick.
 * - **The objective**: the minutes in the aim's light (the stretches' minutes, so what the navigation card would say),
 *   that is the fraction of the walking time, which the pace fixes whatever the start. Starts within [TIE_MIN] minutes of
 *   the best tie; among them the walker's own start, else the nearest to it (the earliest without one). T218: when every
 *   start that meets the deadline ties, the kind is [BestStartKind.ANY] and there is no line.
 * - **The deadline** ([Deadline.resolve], T218: the one the turn-back clock counts to, by the `turnDeadline` setting at the
 *   route's end): a start whose walk ends after it less [marginMin] is excluded (the turn-back clock's margin). When a
 *   start that misses it has more of the light and the latest start that meets it is as good as any that does, or no
 *   start does, the line is the deadline's ("Start by 6:30 to finish before sunset"). T218: only when the finish at the
 *   walker's own start (else at the start chosen) is within [START_BY_WITHIN_MIN] of the deadline or past it; further off,
 *   the line is the one it would be with no deadline binding, a start that still meets it.
 * - **The terrain** is read once for every start ([sharedWindows]: the samples' places do not move with the start, so the
 *   fine layer is the same for all; the coarse layer is the union of each start's reach toward its sun, a zoom coarser
 *   only when that union would pass [ShadeAlong.MAX_CELLS]); a start whose rays reach past it falls back to
 *   [ShadeAlong.compute] with the shared terrain offered as `reuse`.
 *
 * Deterministic: the same route, pace, window and terrain give the same table, so a cached pick is stable.
 */
object BestStart {
    /** The grid's step, minutes: the sun moves 7.5 degrees in that time, and a day is about thirty starts. */
    const val STEP_MIN = 30

    /** The refinement either side of the grid's pick, minutes. */
    const val REFINE_MIN = 15

    /** Starts within this many minutes of the best count as as good (the stretches' minutes move by about this from model noise). */
    const val TIE_MIN = 1.0

    /** Below this many minutes in the aim's light at the best start, there is nothing to suggest ([BestStartKind.NONE]). */
    const val MIN_SHOWN_MIN = 1.0

    /**
     * T218: "Start by" shows when the finish is within this many minutes of the deadline or past it. The project's own
     * number, stated in the help text: no source gives a margin, the nearest precedents being user-set lead alerts (the
     * survey `clock-times-and-deadlines.md`, section (c)).
     */
    const val START_BY_WITHIN_MIN = 120

    /** The deadline's latest start is shown on this many minutes. */
    const val LATEST_ROUND_MIN = 5

    private const val MIN_MS = 60_000L

    /**
     * The phone's sweep at [nowMs]: today from now (or sunrise) to sunset with now as the walker's start; when no start of
     * today finishes before the deadline, or the sun has set, tomorrow's daylight ([BestStartResult.tomorrow]).
     * [deadlineFor] gives the deadline for the local day of an instant (the terrain sunset at the route's end).
     */
    fun suggest(
        path: NavPath, timing: TurnAround, aim: BestStartAim, nowMs: Long, deadlineFor: (Long) -> Deadline?, marginMin: Int,
        maxZoom: Int, read: (MercatorWindow) -> ElevationGrid, tileSize: Int = 256, lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): BestStartResult {
        val p = path.points.first()
        val today = Sun.dayAt(p.lat, p.lon, nowMs)
        val rise = today.sunriseMs
        val set = today.sunsetMs
        if (rise != null && set != null && nowMs < set) {
            val r = sweep(path, timing, aim, max(nowMs, rise), set, deadlineFor(today.noonMs), marginMin, maxZoom, read, nowMs, false, tileSize, lowSunM)
            if (r.kind == BestStartKind.NO_DAY || r.candidates.any { it.deadlineMet }) return r
        }
        val next = Sun.dayAt(p.lat, p.lon, today.noonMs + 86_400_000L)
        val nRise = next.sunriseMs
        val nSet = next.sunsetMs
        if (nRise == null || nSet == null) return noDay(aim, nowMs, timing)
        return sweep(path, timing, aim, nRise, nSet, deadlineFor(next.noonMs), marginMin, maxZoom, read, null, true, tileSize, lowSunM)
    }

    /** The daylight at the route's start on the local day of [atMs] (flat sunrise to flat sunset), null without either. */
    fun daylight(path: NavPath, atMs: Long): Pair<Long, Long>? {
        val p = path.points.first()
        val d = Sun.dayAt(p.lat, p.lon, atMs)
        val rise = d.sunriseMs ?: return null
        val set = d.sunsetMs ?: return null
        return rise to set
    }

    private fun noDay(aim: BestStartAim, atMs: Long, timing: TurnAround) =
        BestStartResult(aim, BestStartKind.NO_DAY, null, emptyList(), atMs, atMs, null, null, 0, null, false, timing.totalS, null)

    /**
     * The sweep over [fromMs] to [toMs] for [aim]: see the object's comment. [currentMs] is the walker's own start (marched
     * and preferred on a tie; it may lie outside the window), [deadline] the walk must end [marginMin] before (null: none
     * that day). [read] gives the elevation as [ShadeAlong.compute] reads it.
     */
    fun sweep(
        path: NavPath, timing: TurnAround, aim: BestStartAim, fromMs: Long, toMs: Long, deadline: Deadline?, marginMin: Int,
        maxZoom: Int, read: (MercatorWindow) -> ElevationGrid, currentMs: Long? = null, tomorrow: Boolean = false,
        tileSize: Int = 256, lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): BestStartResult {
        val clock = TimeSource.Monotonic
        val t0 = clock.markNow()
        val walkMs = (timing.totalS * 1000).roundToLong()
        val latest = deadline?.atMs?.let { it - marginMin * MIN_MS - walkMs }?.let { floorTo(it, LATEST_ROUND_MIN * MIN_MS) }
        val step = STEP_MIN * MIN_MS
        val grid = ArrayList<Long>()
        var s = ceilTo(fromMs, step)
        while (s <= toMs) { grid += s; s += step }
        if (grid.isEmpty() && currentMs == null) {
            return BestStartResult(aim, BestStartKind.NO_DAY, null, emptyList(), fromMs, toMs, null, deadline, marginMin, latest, tomorrow, timing.totalS, null)
        }

        // One terrain for every start the sweep can march: the quarter hours of the window, the walker's start, the latest start.
        val quarter = REFINE_MIN * MIN_MS
        val reach = ArrayList<Long>()
        s = ceilTo(fromMs, quarter)
        while (s <= toMs) { reach += s; s += quarter }
        currentMs?.let { reach += it }
        latest?.let { if (it in fromMs..toMs) reach += it }
        val base = ShadeAlong.plan(path, timing, 0.0, reach.firstOrNull() ?: fromMs)
        val zoom = ShadeAlong.fineZoom(base, min(maxZoom, ShadeAlong.MAX_ZOOM), tileSize)
        val (need, coarsened) = sharedWindows(path, timing, reach, zoom, tileSize, lowSunM)
        val tr = clock.markNow()
        val terrain = Terrain.read(need, read)
        var readMs = tr.elapsedNow().inWholeMicroseconds / 1000.0
        var cellsRead = need.sumOf { it.first.width.toLong() * it.first.height }
        var marchMs = 0.0
        var rays = 0L
        var steps = 0L
        var rereads = 0

        val done = LinkedHashMap<Long, StartCandidate>()
        fun evaluate(start: Long, pass: String) {
            if (start in done) return
            val tm = clock.markNow()
            val planned = ShadeAlong.plan(path, timing, 0.0, start)
            val want = ShadeAlong.windows(planned, zoom, tileSize, lowSunM = lowSunM)
            val inside = coarsened || inside(terrain, want)
            val samples: List<ShadeSample>
            if (inside) {
                val (sm, st) = ShadeAlong.march(planned, terrain, lowSunM = lowSunM)
                samples = sm
                steps += st
            } else {
                val r = ShadeAlong.compute(path, timing, 0.0, start, maxZoom, read, tileSize, reuse = terrain, lowSunM = lowSunM)
                samples = r.samples
                steps += r.stats.steps
                readMs += r.stats.readMs
                cellsRead += r.stats.cellsRead
                rereads++
            }
            rays += samples.size
            marchMs += tm.elapsedNow().inWholeMicroseconds / 1000.0
            val st = ShadeAlong.stretches(samples, 0.0, path.totalM)
            fun mins(l: ShadeLight) = st.filter { it.light == l }.sumOf { it.minutes }
            val arrival = start + walkMs
            val met = deadline?.atMs == null || arrival <= deadline.atMs!! - marginMin * MIN_MS
            done[start] = StartCandidate(start, arrival, mins(ShadeLight.SHADE), mins(ShadeLight.SUN), mins(ShadeLight.DOWN), mins(ShadeLight.NO_DATA), met, pass, !inside)
        }

        for (g in grid) evaluate(g, "grid")
        currentMs?.let { evaluate(it, "current") }
        choose(done.values, aim, currentMs, fromMs)?.let { p ->
            for (d in listOf(-quarter, quarter)) {
                val r = p.startMs + d
                if (r in fromMs..toMs) evaluate(r, "refine")
            }
        }
        val chosen = choose(done.values, aim, currentMs, fromMs)
        var pick = chosen
        val bestAll = done.values.maxOfOrNull { it.minutes(aim) } ?: 0.0
        val ok = done.values.filter { it.deadlineMet }
        val bestOk = ok.maxOfOrNull { it.minutes(aim) } ?: 0.0
        val latestOk = ok.maxByOrNull { it.startMs }
        // T218: how near the deadline the walker's own start (else the one chosen) finishes decides whether "Start by" shows.
        val deadlineAt = deadline?.atMs
        val near = deadlineAt != null && chosen != null && (currentMs ?: chosen.startMs) + walkMs >= deadlineAt - START_BY_WITHIN_MIN * MIN_MS
        var kind = when {
            chosen == null -> if (latest != null) BestStartKind.DEADLINE else BestStartKind.NO_DAY
            bestAll < MIN_SHOWN_MIN -> BestStartKind.NONE
            // The deadline binds: a start that misses it has more of the light, and the latest start that meets it is as
            // good as any that does (the light grows toward the deadline, or no start in time has any).
            near && bestAll > bestOk + TIE_MIN && latestOk != null && latestOk.minutes(aim) >= bestOk - TIE_MIN -> BestStartKind.DEADLINE
            // T218: nothing to choose between the starts that finish in time.
            ok.size >= 2 && bestOk - ok.minOf { it.minutes(aim) } <= TIE_MIN -> BestStartKind.ANY
            chosen.startMs == currentMs -> BestStartKind.CURRENT
            else -> BestStartKind.PICK
        }
        if (kind == BestStartKind.DEADLINE && pick != null && latest != null && latest > pick.startMs && latest in fromMs..toMs) {
            evaluate(latest, "latest")
            pick = done[latest]
        }
        if (kind == BestStartKind.DEADLINE && pick == null && latest == null) kind = BestStartKind.NO_DAY
        val table = done.values.sortedBy { it.startMs }
        val stats = BestStartStats(
            table.size, rays, steps, terrain.zoom, terrain.layers.getOrNull(1)?.window?.zoom, coarsened, cellsRead, rereads,
            readMs, marchMs, t0.elapsedNow().inWholeMicroseconds / 1000.0,
        )
        return BestStartResult(aim, kind, pick, table, fromMs, toMs, currentMs, deadline, marginMin, latest, tomorrow, timing.totalS, stats)
    }

    /**
     * The start the line names among [all] that meet the deadline: the most minutes in [aim]'s light, ties within
     * [TIE_MIN] going to [currentMs] itself, else the nearest to it (or to [fromMs]), the earlier of two as near.
     */
    fun choose(all: Collection<StartCandidate>, aim: BestStartAim, currentMs: Long?, fromMs: Long): StartCandidate? {
        val ok = all.filter { it.deadlineMet }
        val best = ok.maxOfOrNull { it.minutes(aim) } ?: return null
        val ties = ok.filter { it.minutes(aim) >= best - TIE_MIN }
        currentMs?.let { c -> ties.firstOrNull { it.startMs == c }?.let { return it } }
        val anchor = currentMs ?: fromMs
        return ties.minWith(compareBy<StartCandidate> { abs(it.startMs - anchor) }.thenBy { it.startMs })
    }

    /**
     * The terrain every start of [starts] needs at reference [zoom]: the fine layer (the same for every start, the samples'
     * places do not move) and the union of each start's coarse layer at the coarsest zoom any of them asked for, made
     * coarser still while it would pass [ShadeAlong.MAX_CELLS] ([Pair.second] true then: the starts march over it as it is).
     */
    fun sharedWindows(
        path: NavPath, timing: TurnAround, starts: List<Long>, zoom: Int, tileSize: Int = 256, lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): Pair<List<Pair<MercatorWindow, Double>>, Boolean> {
        var fine: Pair<MercatorWindow, Double>? = null
        val coarse = ArrayList<MercatorWindow>()
        for (st in starts) {
            val w = ShadeAlong.windows(ShadeAlong.plan(path, timing, 0.0, st), zoom, tileSize, lowSunM = lowSunM)
            if (fine == null) fine = w[0]
            if (w.size > 1) coarse += w[1].first
        }
        val f = fine ?: ShadeAlong.windows(ShadeAlong.plan(path, timing, 0.0, 0L), zoom, tileSize, coarseZooms = 0, lowSunM = lowSunM)[0]
        if (coarse.isEmpty()) return listOf(f) to false
        val single = zoom - Horizon.COARSE_ZOOMS
        var z = coarse.minOf { it.zoom }
        var l = Long.MAX_VALUE; var t = Long.MAX_VALUE; var r = Long.MIN_VALUE; var b = Long.MIN_VALUE
        for (w in coarse) {
            val sh = w.zoom - z
            l = min(l, (w.x0 shr sh).toLong()); t = min(t, (w.y0 shr sh).toLong())
            r = max(r, ((w.x0 + w.width - 1) shr sh).toLong()); b = max(b, ((w.y0 + w.height - 1) shr sh).toLong())
        }
        while ((r - l + 1) * (b - t + 1) > ShadeAlong.MAX_CELLS && z > 0) {
            z--
            l = l shr 1; t = t shr 1; r = r shr 1; b = b shr 1
        }
        val union = MercatorWindow(z, l.toInt(), t.toInt(), (r - l + 1).toInt(), (b - t + 1).toInt(), tileSize)
        return listOf(f, union to Double.POSITIVE_INFINITY) to (z != single)
    }

    /** True when each window of [need] lies inside [terrain]'s layer of the same index, zoom and band (fewer windows allowed). */
    private fun inside(terrain: Terrain, need: List<Pair<MercatorWindow, Double>>): Boolean {
        if (need.size > terrain.layers.size) return false
        return need.indices.all { i ->
            val have = terrain.layers[i]
            val (w, until) = need[i]
            have.window.zoom == w.zoom && have.untilPx == until && w.x0 >= have.window.x0 && w.y0 >= have.window.y0 &&
                w.x0 + w.width <= have.window.x0 + have.window.width && w.y0 + w.height <= have.window.y0 + have.window.height
        }
    }

    private fun floorTo(ms: Long, step: Long): Long {
        val q = ms / step
        return (if (ms % step != 0L && ms < 0) q - 1 else q) * step
    }

    private fun ceilTo(ms: Long, step: Long): Long = floorTo(ms + step - 1, step)
}

private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
private fun r3(v: Double) = kotlin.math.round(v * 1000) / 1000
