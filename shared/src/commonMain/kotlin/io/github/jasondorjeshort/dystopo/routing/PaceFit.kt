package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * T164: a walker's (or rider's) own speed against grade, fitted to their recorded tracks: a Tobler-shaped curve
 * `v(g) = peakMps * exp(-width * |g - peakGrade|)`, `g` rise over run (0.1 is a 10 % climb). Tobler's own numbers are
 * 6 km/h, 3.5 and -0.05; a fit has the user's. [tracks] and [metres] are what it was fitted to; it is [ready] (the
 * router reads it, [PaceFits.active]) from [PaceFits.MIN_TRACKS] tracks over [PaceFits.MIN_METRES] in the mode.
 *
 * What the settings row shows: [flatPaceSPerKm] (seconds per km on the flat) and [climbRateMph], the climb rate in
 * Naismith's sense (metres of ascent per hour of time beyond the flat time, on a [CLIMB_GRADE] climb), so the two read
 * like the standard estimate's "5 km/h plus an hour per 600 m of climb".
 */
data class PaceFit(
    val mode: TravelMode,
    val peakMps: Double,
    val width: Double,
    val peakGrade: Double,
    val tracks: Int,
    val metres: Double,
    /** The robust spread of the pieces about the curve, as a factor on speed (0.12: a typical piece 12 % off). */
    val spread: Double = 0.0,
) {
    val ready: Boolean get() = tracks >= PaceFits.MIN_TRACKS && metres >= PaceFits.MIN_METRES

    /** Speed in m/s at grade [g] (rise over run; clamped to +-[MAX_GRADE], never below 5 % of the peak). */
    fun speedMps(g: Double): Double {
        val gc = g.coerceIn(-MAX_GRADE, MAX_GRADE)
        return peakMps * exp(-width * abs(gc - peakGrade)).coerceAtLeast(MIN_FACTOR)
    }

    val flatMps: Double get() = speedMps(0.0)
    val flatPaceSPerKm: Double get() = 1000.0 / flatMps

    /** Seconds per metre of ascent beyond the flat time, on a [CLIMB_GRADE] climb. */
    val climbSPerM: Double get() = (1.0 / speedMps(CLIMB_GRADE) - 1.0 / flatMps) / CLIMB_GRADE

    val climbRateMph: Double get() = 3600.0 / climbSPerM.coerceAtLeast(1e-6)

    /**
     * Seconds over [lengthM] that climbs [ascentM] and descends [descentM]: the climbing and descending parts take
     * the length in proportion to their height, both at the edge's mean absolute grade, the rest is flat (an edge
     * of the graph knows only its totals). Used for an edge's time and the router's slope cost.
     */
    fun timeS(lengthM: Double, ascentM: Double, descentM: Double): Double {
        if (lengthM <= 0) return 0.0
        val h = ascentM + descentM
        if (h <= 0) return lengthM / flatMps
        val g = h / lengthM
        val up = lengthM * ascentM / h
        val down = lengthM * descentM / h
        return up / speedMps(g) + down / speedMps(-g)
    }

    /** Naismith's form with this fit's numbers: [distanceM] at the flat pace plus [ascentM] at [climbSPerM]. */
    fun timeS(distanceM: Double, ascentM: Double): Double = distanceM / flatMps + ascentM.coerceAtLeast(0.0) * climbSPerM

    /**
     * Seconds along [points] (with heights): cut into pieces of [TrackMatch.PIECE_M] as the fit's own pieces were
     * measured, each at the speed of its net grade. Points without a height count as flat.
     */
    fun pathTimeS(points: List<TrackPoint>): Double {
        var t = 0.0
        var len = 0.0
        var e0: Double? = points.firstOrNull()?.ele
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            len += TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            if (e0 == null) e0 = b.ele
            if (len >= TrackMatch.PIECE_M || i == points.size - 1) {
                val e1 = b.ele
                val g = if (e0 != null && e1 != null && len > 0) (e1 - e0) / len else 0.0
                t += len / speedMps(g)
                len = 0.0
                e0 = e1
            }
        }
        return t
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.label, "peakKmh" to r3(peakMps * 3.6), "width" to r3(width), "peakGrade" to r3(peakGrade),
        "flatKmh" to r3(flatMps * 3.6), "flatPaceMinPerKm" to r3(flatPaceSPerKm / 60), "climbRateMph" to climbRateMph.roundToLong(),
        "tracks" to tracks, "km" to r3(metres / 1000), "spread" to r3(spread), "ready" to ready,
    )

    companion object {
        /** The grade the climb rate is read at: a steady mountain trail. */
        const val CLIMB_GRADE = 0.15
        const val MAX_GRADE = 1.0
        const val MIN_FACTOR = 0.05
        /** Tobler's hiking function (1993), the shape every fit takes. */
        val TOBLER = PaceFit(TravelMode.HIKE, 6.0 / 3.6, 3.5, -0.05, 0, 0.0)
        fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
    }
}

/** T164: one measured piece of a track: its grade (rise over run), length and moving time, and which track it is from. */
data class PaceSample(val grade: Double, val lengthM: Double, val timeS: Double, val track: Int = 0)

/**
 * T164: the fit and the fits in use. [fit] fits the curve to pieces; [fromTracks] runs the whole chain over tracks
 * (mode, stop cut, matching, recency, fit). [active] is what [CostModel] reads (the phone sets it from the synced
 * setting; empty everywhere else, so the server and the devtool keep the standard model unless asked).
 *
 * The regression: least squares on log speed, `ln v = ln peak - width * |g - peakGrade|`, solved in closed form for
 * each peak grade on a grid from [GRADE_MIN] to [GRADE_MAX] by 0.0025 (refined by a parabola through the best three)
 * and made robust by iteratively reweighted least squares with Huber weights (c = 1.345 robust sigmas, sigma from the
 * median absolute residual), 20 rounds at most. Weights: each piece's length (so every metre counts once) times its
 * track's recency, `0.5^(rank / HALF_LIFE_TRACKS)` with the newest track rank 0, over the newest [MAX_TRACKS] tracks of
 * the mode. A peak grade needs at least [SIDE_SHARE] of the weight on each side of it (else the peak and the width
 * trade off); with no grade that has, the peak grade is Tobler's -0.05 and only the peak and the width are fitted.
 */
object PaceFits {
    const val MIN_TRACKS = 5
    const val MIN_METRES = 20_000.0
    const val MAX_TRACKS = 30
    const val HALF_LIFE_TRACKS = 10.0
    const val GRADE_MIN = -0.15
    const val GRADE_MAX = 0.05
    const val GRADE_STEP = 0.0025
    const val SIDE_SHARE = 0.1
    /** Fewer pieces than this fit nothing. */
    const val MIN_PIECES = 10
    /** Pieces steeper than this are left out (a scramble, a wrong match across a switchback). */
    const val MAX_FIT_GRADE = 0.6
    const val VERSION = 1

    /** The fits [CostModel] reads, by mode: only [PaceFit.ready] ones. Set by the phone; empty elsewhere. */
    @Volatile var active: Map<TravelMode, PaceFit> = emptyMap()
        private set

    fun setActive(fits: Collection<PaceFit>) {
        active = fits.filter { it.ready }.associateBy { it.mode }
    }

    fun clearActive() { active = emptyMap() }

    /** The modes a fit is made for; drive keeps the roads' speeds and crow is a line, not a path. */
    val FITTED = listOf(TravelMode.HIKE, TravelMode.BIKE)

    /**
     * The mode a track's activity plans in: foot to hike, bike to bike, road to drive; rail, boat and air fit nothing.
     * A track without an activity (an import, a track from before T158) by its GPX type ("hiking", "cycling", ...) or,
     * failing that, its median moving speed ([inferred]).
     */
    fun modeOf(activity: Activity?): TravelMode? = when (activity) {
        Activity.FOOT -> TravelMode.HIKE
        Activity.BIKE -> TravelMode.BIKE
        Activity.ROAD -> TravelMode.DRIVE
        Activity.RAIL, Activity.BOAT, Activity.AIR -> null
        null -> null
    }

    fun modeOf(track: Track, medianMps: Double?): TravelMode? {
        if (track.activity != null) return modeOf(track.activity)
        val type = track.type?.lowercase().orEmpty()
        when {
            listOf("hik", "walk", "run", "foot", "trek").any { it in type } -> return TravelMode.HIKE
            listOf("bik", "cycl", "ride", "mtb").any { it in type } -> return TravelMode.BIKE
            listOf("driv", "car", "motor").any { it in type } -> return TravelMode.DRIVE
        }
        return inferred(medianMps)
    }

    /** By median moving speed: up to 2.5 m/s (9 km/h) on foot, up to 9 m/s (32 km/h) a bike, faster a road vehicle. */
    fun inferred(medianMps: Double?): TravelMode? = when {
        medianMps == null || medianMps.isNaN() -> null
        medianMps <= 2.5 -> TravelMode.HIKE
        medianMps <= 9.0 -> TravelMode.BIKE
        else -> TravelMode.DRIVE
    }

    /** Plausible piece speeds per mode, m/s: what falls outside is a mismatch, not a pace. */
    private fun plausible(mode: TravelMode): ClosedFloatingPointRange<Double> = when (mode) {
        TravelMode.BIKE -> 0.3..25.0
        else -> 0.05..7.0
    }

    /** Recency weight of the track ranked [rank] (0 the newest). */
    fun recency(rank: Int): Double = 0.5.pow(rank / HALF_LIFE_TRACKS)

    /**
     * Fits [samples] (their `track` a recency rank) in [mode]; null with fewer than [MIN_PIECES] usable pieces.
     * [tracks] and the metres are counted from the pieces used.
     */
    fun fit(samples: List<PaceSample>, mode: TravelMode): PaceFit? {
        val range = plausible(mode)
        val s = samples.filter { it.lengthM > 0 && it.timeS > 0 && abs(it.grade) <= MAX_FIT_GRADE && (it.lengthM / it.timeS) in range }
        if (s.size < MIN_PIECES) return null
        val n = s.size
        val g = DoubleArray(n) { s[it].grade }
        val y = DoubleArray(n) { ln(s[it].lengthM / s[it].timeS) }
        val base = DoubleArray(n) { s[it].lengthM * recency(s[it].track) }
        val huber = DoubleArray(n) { 1.0 }
        val w = DoubleArray(n)
        var best = Solve(0.0, 0.0, -0.05, Double.POSITIVE_INFINITY)
        var sigma = 0.0
        for (round in 0 until 20) {
            for (i in 0 until n) w[i] = base[i] * huber[i]
            val next = solve(g, y, w)
            val moved = abs(next.a - best.a) + abs(next.k - best.k) + abs(next.g0 - best.g0)
            best = next
            val r = DoubleArray(n) { abs(y[it] - (best.a - best.k * abs(g[it] - best.g0))) }
            sigma = 1.4826 * median(r)
            val c = 1.345 * sigma.coerceAtLeast(1e-6)
            for (i in 0 until n) huber[i] = if (r[i] <= c) 1.0 else c / r[i]
            if (round > 0 && moved < 1e-7) break
        }
        val tracks = s.map { it.track }.toSet().size
        val k = best.k.coerceIn(0.3, 20.0)
        val peak = exp(best.a).coerceIn(range.start, range.endInclusive)
        return PaceFit(mode, peak, k, best.g0, tracks, s.sumOf { it.lengthM }, exp(sigma) - 1)
    }

    private class Solve(val a: Double, val k: Double, val g0: Double, val sse: Double)

    /** The weighted least-squares fit for each peak grade on the grid, the best refined by a parabola. */
    private fun solve(g: DoubleArray, y: DoubleArray, w: DoubleArray): Solve {
        val total = w.sum()
        val steps = ((GRADE_MAX - GRADE_MIN) / GRADE_STEP).roundToLong().toInt()
        val grid = ArrayList<Solve?>()
        for (j in 0..steps) {
            val g0 = GRADE_MIN + j * GRADE_STEP
            var below = 0.0
            for (i in g.indices) if (g[i] < g0) below += w[i]
            grid += if (below < SIDE_SHARE * total || total - below < SIDE_SHARE * total) null else at(g, y, w, g0)
        }
        val bi = grid.indices.filter { grid[it] != null }.minByOrNull { grid[it]!!.sse } ?: return at(g, y, w, PaceFit.TOBLER.peakGrade)
        val b = grid[bi]!!
        val l = grid.getOrNull(bi - 1)
        val r = grid.getOrNull(bi + 1)
        if (l == null || r == null) return b
        // A parabola through the three SSEs: its vertex, kept within the neighbours.
        val den = l.sse - 2 * b.sse + r.sse
        if (den <= 0) return b
        val off = (0.5 * (l.sse - r.sse) / den).coerceIn(-1.0, 1.0)
        val refined = at(g, y, w, b.g0 + off * GRADE_STEP)
        return if (refined.sse <= b.sse) refined else b
    }

    /** `y = a - k |g - g0|` by weighted least squares at a fixed [g0]. */
    private fun at(g: DoubleArray, y: DoubleArray, w: DoubleArray, g0: Double): Solve {
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in g.indices) {
            val x = -abs(g[i] - g0)
            sw += w[i]; sx += w[i] * x; sy += w[i] * y[i]; sxx += w[i] * x * x; sxy += w[i] * x * y[i]
        }
        val det = sw * sxx - sx * sx
        val k = if (det > 1e-12) (sw * sxy - sx * sy) / det else 0.0
        val a = (sy - k * sx) / sw
        var sse = 0.0
        for (i in g.indices) {
            val e = y[i] - (a - k * abs(g[i] - g0))
            sse += w[i] * e * e
        }
        return Solve(a, k, g0, sse)
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** One track's part in [fromTracks]: its mode, its rank among the mode's tracks, and what matching found. */
    data class TrackRow(val id: String, val name: String, val startTime: Long?, val mode: TravelMode?, val rank: Int?, val match: TrackMatch.Result?) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "start" to startTime, "mode" to mode?.label, "rank" to rank,
            "used" to (rank != null && rank < MAX_TRACKS && (match?.pieces?.isNotEmpty() == true)),
        ) + (match?.json() ?: emptyMap())
    }

    /** What [fromTracks] found: a fit per fitted mode (null where too few pieces) and every track's row. */
    data class Report(val fits: Map<TravelMode, PaceFit?>, val rows: List<TrackRow>) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "fits" to FITTED.associate { m -> m.label to fits[m]?.json() },
            "tracks" to rows.map { it.json() },
        )
    }

    /**
     * The whole chain over [tracks]: each one's mode ([modeOf]), the ones started at or after [since] ranked newest first
     * per mode (the newest [MAX_TRACKS] used), each cut and matched ([TrackMatch.match] through [snapper], null: the
     * tracks' own heights), and a fit per mode in [FITTED].
     */
    fun fromTracks(tracks: List<Track>, snapper: TrackSnapper?, since: Long = 0L): Report {
        val start = tracks.associate { t -> t.id to t.points.firstNotNullOfOrNull { it.time } }
        val matches = HashMap<String, TrackMatch.Result>()
        val modes = HashMap<String, TravelMode?>()
        for (t in tracks) {
            val st = start[t.id]
            if (st == null || st < since) { modes[t.id] = null; continue }
            val m = TrackMatch.match(t, snapper)
            matches[t.id] = m
            modes[t.id] = modeOf(t, m.medianMps)
        }
        val rows = ArrayList<TrackRow>()
        val fits = LinkedHashMap<TravelMode, PaceFit?>()
        val byMode = tracks.filter { modes[it.id] != null }.groupBy { modes[it.id]!! }
        val ranks = HashMap<String, Int>()
        for ((mode, list) in byMode) {
            val ranked = list.filter { matches[it.id]?.pieces?.isNotEmpty() == true }.sortedWith(compareByDescending<Track> { start[it.id] }.thenBy { it.id })
            ranked.forEachIndexed { i, t -> ranks[t.id] = i }
            if (mode !in FITTED) continue
            val samples = ranked.take(MAX_TRACKS).flatMapIndexed { i, t -> matches[t.id]!!.pieces.map { it.copy(track = i) } }
            fits[mode] = fit(samples, mode)
        }
        for (m in FITTED) if (m !in fits) fits[m] = null
        for (t in tracks) rows += TrackRow(t.id, t.name, start[t.id], modes[t.id], ranks[t.id], matches[t.id])
        return Report(fits, rows)
    }

    // ---- the synced setting value (app.paceFit) ----

    /**
     * The fits as one setting value: `1;hike:<peak m/s>,<width>,<peak grade>,<tracks>,<metres>,<spread>;bike:...`, the
     * version first. A mode with too little to fit has empty curve fields (`hike:,,,2,8000,`): its counts still show.
     */
    fun encode(fits: Collection<PaceFit?>, counts: Map<TravelMode, Pair<Int, Double>> = emptyMap()): String {
        val parts = arrayListOf(VERSION.toString())
        val seen = HashSet<TravelMode>()
        for (f in fits.filterNotNull()) {
            seen += f.mode
            parts += "${f.mode.label}:${d(f.peakMps, 4)},${d(f.width, 4)},${d(f.peakGrade, 4)},${f.tracks},${f.metres.roundToLong()},${d(f.spread, 3)}"
        }
        for ((m, c) in counts) if (m !in seen) parts += "${m.label}:,,,${c.first},${c.second.roundToLong()},"
        return parts.joinToString(";")
    }

    /** A decoded setting value: the fits, and the counts of modes too thin to fit. */
    data class Stored(val fits: List<PaceFit>, val counts: Map<TravelMode, Pair<Int, Double>>) {
        fun fit(mode: TravelMode): PaceFit? = fits.firstOrNull { it.mode == mode }
        fun count(mode: TravelMode): Pair<Int, Double>? = fit(mode)?.let { it.tracks to it.metres } ?: counts[mode]
    }

    /** The setting value back; "" is no fit; null for a value this build does not read (another version, malformed). */
    fun decode(text: String): Stored? {
        val v = text.trim()
        if (v.isEmpty()) return Stored(emptyList(), emptyMap())
        val parts = v.split(';')
        if (parts[0] != VERSION.toString()) return null
        val fits = ArrayList<PaceFit>()
        val counts = LinkedHashMap<TravelMode, Pair<Int, Double>>()
        for (p in parts.drop(1)) {
            val colon = p.indexOf(':')
            if (colon <= 0) return null
            val mode = TravelMode.of(p.substring(0, colon)) ?: continue // a newer build's mode: skipped
            val f = p.substring(colon + 1).split(',')
            if (f.size < 5) return null
            val tracks = f[3].toIntOrNull() ?: return null
            val metres = f[4].toDoubleOrNull() ?: return null
            if (tracks < 0 || metres < 0 || !metres.isFinite()) return null
            if (f[0].isEmpty()) { counts[mode] = tracks to metres; continue }
            val peak = f[0].toDoubleOrNull() ?: return null
            val width = f[1].toDoubleOrNull() ?: return null
            val g0 = f[2].toDoubleOrNull() ?: return null
            if (!(peak > 0 && peak < 100 && width > 0 && width < 100 && abs(g0) < 1)) return null
            fits += PaceFit(mode, peak, width, g0, tracks, metres, f.getOrNull(5)?.toDoubleOrNull() ?: 0.0)
        }
        return Stored(fits, counts)
    }

    private fun d(v: Double, places: Int): String {
        val m = 10.0.pow(places)
        val r = (v * m).roundToLong() / m
        return r.toString()
    }
}
