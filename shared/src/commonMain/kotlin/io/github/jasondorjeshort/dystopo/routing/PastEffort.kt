package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.gpx.TrackSmoothing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * T184: a planned route against one recorded track: did the user walk this trail before, and how long did it take.
 *
 * No app publishes its tolerances (the survey, `out/wave23-research/arrival-time-range.md`: Strava matches by the points
 * nearest a segment's start and end, loosely on purpose), so the rule is conservative:
 * - The route is cut into samples every [STEP_M]; a sample is covered when a piece of the track (between two of its
 *   fixes that count, [TrackSmoothing.counts], at most [MAX_PIECE_M] apart) lies within the tolerance: [TOLERANCE_M]
 *   (phone GPS under trees), [LOW_ACCURACY_TOLERANCE_M] for a low-accuracy track (its fixes' median accuracy over
 *   [LOW_ACCURACY_M]). [Match.coverage] is the share of the route's length covered by any part of the track.
 * - In order: the samples are matched to the track forwards (each to the earliest covering piece at or after the last
 *   one) and backwards (the latest at or before); the direction that covers more is the traversal, forwards when both
 *   cover [MATCH_SHARE] (an out-and-back track walked the route's way first). Backwards is "other direction".
 * - A match ([Kind.FULL]) needs that ordered coverage of [MATCH_SHARE] or more, both ends of the route covered and among
 *   the matched samples (the ends matching, as Strava's start and finish checks), and the track's own time between the
 *   two ends spent within the tolerance of the route for [ON_ROUTE_SHARE] or more of it (no long side trip inside the
 *   time). Its time ([Match.elapsedS]) is the whole time between the two ends, moving and resting (the recording's
 *   clock), and [Match.startMs] when it began.
 * - Otherwise a coverage from [PARTIAL_SHARE] is [Kind.PARTIAL]: "part of this trail was recorded", never a time.
 */
object PastEfforts {
    const val TOLERANCE_M = 25.0
    const val LOW_ACCURACY_TOLERANCE_M = 40.0
    const val LOW_ACCURACY_M = 15.0
    const val MATCH_SHARE = 0.8
    const val PARTIAL_SHARE = 0.5
    /**
     * Share of the track's time between the two ends spent near the route for a match: below it the time holds a side trip
     * or a variant too long to call this trail's. Lower than [MATCH_SHARE] because a variant over the uncovered fifth of a
     * route is longer than that fifth.
     */
    const val ON_ROUTE_SHARE = 0.7
    const val STEP_M = 10.0
    const val MAX_PIECE_M = 250.0
    /** An end counts as matched when a matched sample lies within this of it along the route. */
    const val END_M = 50.0
    /** At an end, fixes this much farther than the track's nearest still count as being there (GPS scatter at a stop). */
    const val END_SLACK_M = 5.0
    private const val CELL_M = 50.0
    private const val M_PER_DEG = 111_195.0

    enum class Kind { FULL, PARTIAL, NONE }

    data class Match(
        val trackId: String,
        val name: String,
        val kind: Kind,
        /** Share of the route's length within the tolerance of any part of the track. */
        val coverage: Double,
        /** Share covered in order (the chosen direction's); 0 when not ordered. */
        val ordered: Double,
        val toleranceM: Double,
        val reversed: Boolean,
        /** [Kind.FULL] only: when the traversal began, and its time from end to end, moving and resting. */
        val startMs: Long?,
        val elapsedS: Double?,
        /** Share of the track's time between the ends spent within the tolerance of the route. */
        val onRoute: Double?,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "trackId" to trackId, "name" to name, "kind" to kind.name.lowercase(), "coverage" to r3(coverage), "ordered" to r3(ordered),
            "toleranceM" to toleranceM, "direction" to if (kind == Kind.FULL) (if (reversed) "other" else "same") else null,
            "startMs" to startMs, "elapsedS" to elapsedS?.roundToLong(), "elapsed" to elapsedS?.let { TimeEstimate.hm(it) },
            "onRoute" to onRoute?.let { r3(it) },
        )
    }

    /** What the route card says about past efforts: the best and the latest full match (same direction first), or a partial one. */
    data class Summary(val best: Match?, val latest: Match?, val partial: Match?, val matches: List<Match>) {
        val found: Boolean get() = best != null

        /** The card's line, or null with nothing to say ([utcOffsetMs] places the month; [nowMs] decides whether the year shows). */
        fun line(nowMs: Long, utcOffsetMs: Long = 0): String? = PastEffortText.line(this, nowMs, utcOffsetMs)

        fun json(nowMs: Long, utcOffsetMs: Long = 0): Map<String, Any?> = linkedMapOf(
            "state" to when { best != null -> "matched"; partial != null -> "partial"; else -> "none" },
            "line" to line(nowMs, utcOffsetMs), "best" to best?.json(), "latest" to latest?.json(), "partial" to partial?.json(),
            "tracks" to matches.size, "full" to matches.count { it.kind == Kind.FULL }, "partials" to matches.count { it.kind == Kind.PARTIAL },
        )
    }

    /** The tolerance for [track]: wider when its fixes' median accuracy is poor; [TOLERANCE_M] when it reports none. */
    fun toleranceM(track: Track): Double {
        val acc = track.points.mapNotNull { it.accuracyM?.takeIf { a -> a > 0 } }.sorted()
        if (acc.isEmpty()) return TOLERANCE_M
        return if (acc[acc.size / 2] > LOW_ACCURACY_M) LOW_ACCURACY_TOLERANCE_M else TOLERANCE_M
    }

    /** Every track against [route]; the summary keeps the full and partial matches. */
    fun summary(route: List<RoutePoint>, tracks: List<Track>): Summary {
        val all = tracks.map { match(route, it) }.filter { it.kind != Kind.NONE }
        val full = all.filter { it.kind == Kind.FULL }
        val pick = full.filter { !it.reversed }.ifEmpty { full }
        val best = pick.minByOrNull { it.elapsedS!! }
        val latest = pick.maxByOrNull { it.startMs ?: Long.MIN_VALUE }
        val partial = if (best == null) all.filter { it.kind == Kind.PARTIAL }.maxByOrNull { it.coverage } else null
        return Summary(best, latest, partial, all)
    }

    /** A local plane around the route's first point, metres. */
    private class Plane(val lat0: Double, val lon0: Double) {
        val kx = M_PER_DEG * cos(lat0 * PI / 180)
        fun x(lon: Double) = (lon - lon0) * kx
        fun y(lat: Double) = (lat - lat0) * M_PER_DEG
    }

    private class Fix(val x: Double, val y: Double, val time: Long)

    fun match(route: List<RoutePoint>, track: Track): Match {
        val tol = toleranceM(track)
        fun none(cov: Double = 0.0) = Match(track.id, track.name, if (cov >= PARTIAL_SHARE) Kind.PARTIAL else Kind.NONE, cov, 0.0, tol, false, null, null, null)
        if (route.size < 2) return none()
        val plane = Plane(route[0].lat, route[0].lon)
        // The route's samples: one every STEP_M, each standing for its share of the length.
        val sx = ArrayList<Double>()
        val sy = ArrayList<Double>()
        val sAlong = ArrayList<Double>()
        var along = 0.0
        sx += plane.x(route[0].lon); sy += plane.y(route[0].lat); sAlong += 0.0
        for (i in 1 until route.size) {
            val ax = plane.x(route[i - 1].lon); val ay = plane.y(route[i - 1].lat)
            val bx = plane.x(route[i].lon); val by = plane.y(route[i].lat)
            val len = TrackStats.distanceM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
            val n = maxOf(1, kotlin.math.ceil(len / STEP_M).toInt())
            for (k in 1..n) {
                val f = k.toDouble() / n
                sx += ax + f * (bx - ax); sy += ay + f * (by - ay); sAlong += along + f * len
            }
            along += len
        }
        val lengthM = along
        if (lengthM <= 0) return none()
        val n = sx.size
        val weight = DoubleArray(n) { i ->
            val prev = if (i > 0) sAlong[i] - sAlong[i - 1] else 0.0
            val next = if (i < n - 1) sAlong[i + 1] - sAlong[i] else 0.0
            (prev + next) / 2
        }
        // The track's fixes that count, with times, in order.
        val fixes = ArrayList<Fix>()
        for (seg in track.segments) for (p in seg.points) {
            val t = p.time ?: continue
            if (!TrackSmoothing.counts(p)) continue
            fixes += Fix(plane.x(p.lon), plane.y(p.lat), t)
        }
        if (fixes.size < 2) return none()
        // Pieces k (fix k to k + 1) in a grid of CELL_M cells by their bounding boxes.
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for (k in 0 until fixes.size - 1) {
            val a = fixes[k]; val b = fixes[k + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            if (dx * dx + dy * dy > MAX_PIECE_M * MAX_PIECE_M) continue
            val x0 = floor(minOf(a.x, b.x) / CELL_M).toInt(); val x1 = floor(maxOf(a.x, b.x) / CELL_M).toInt()
            val y0 = floor(minOf(a.y, b.y) / CELL_M).toInt(); val y1 = floor(maxOf(a.y, b.y) / CELL_M).toInt()
            for (cx in x0..x1) for (cy in y0..y1) grid.getOrPut(key(cx, cy)) { ArrayList() } += k
        }
        // Each sample's covering pieces (sorted) and where on each the sample projects (0..1).
        val cand = arrayOfNulls<IntArray>(n)
        val frac = arrayOfNulls<DoubleArray>(n)
        val dist = arrayOfNulls<DoubleArray>(n)
        var covered = 0.0
        for (i in 0 until n) {
            val px = sx[i]; val py = sy[i]
            val cx = floor(px / CELL_M).toInt(); val cy = floor(py / CELL_M).toInt()
            val found = HashMap<Int, Double>()
            val dists = HashMap<Int, Double>()
            for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                val list = grid[key(gx, gy)] ?: continue
                for (k in list) {
                    if (k in found) continue
                    val a = fixes[k]; val b = fixes[k + 1]
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val l2 = dx * dx + dy * dy
                    val t = if (l2 > 0) (((px - a.x) * dx + (py - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                    val ex = a.x + t * dx - px; val ey = a.y + t * dy - py
                    val d = sqrt(ex * ex + ey * ey)
                    if (d <= tol) { found[k] = t; dists[k] = d }
                }
            }
            if (found.isNotEmpty()) {
                val ks = found.keys.sorted()
                cand[i] = ks.toIntArray()
                frac[i] = DoubleArray(ks.size) { found[ks[it]]!! }
                dist[i] = DoubleArray(ks.size) { dists[ks[it]]!! }
                covered += weight[i]
            }
        }
        val coverage = (covered / lengthM).coerceIn(0.0, 1.0)
        if (coverage < MATCH_SHARE) return none(coverage)
        // In order, forwards and backwards: (ordered share, first matched position, last matched position, matched ends).
        class Pass(val share: Double, val first: Double, val last: Double, val firstAlong: Double, val lastAlong: Double)
        // The greedy pass takes the first covering piece of a pass by an end of the route, up to a tolerance off it. At
        // the two ends the time is read where the track was nearest that end, within END_SLACK_M of its nearest, in the
        // same pass (a run of pieces at most two apart): the last such piece where the traversal leaves its first end
        // ([late]), the first where it reaches its other end, so a wait at the trailhead or at the top stays out of it.
        fun atEnd(i: Int, pos: Double, late: Boolean): Double {
            val ks = cand[i] ?: return pos
            val fs = frac[i]!!
            val ds = dist[i]!!
            val j = ks.indexOfFirst { it == floor(pos).toInt() }.takeIf { it >= 0 } ?: return pos
            var lo = j
            while (lo > 0 && ks[lo] - ks[lo - 1] <= 2) lo--
            var hi = j
            while (hi < ks.size - 1 && ks[hi + 1] - ks[hi] <= 2) hi++
            val best = (lo..hi).minOf { ds[it] }
            val near = (lo..hi).filter { ds[it] <= best + END_SLACK_M }
            val m = if (late) near.last() else near.first()
            return ks[m] + fs[m]
        }
        fun pass(forward: Boolean): Pass? {
            var cur = if (forward) -1.0 else Double.MAX_VALUE
            var w = 0.0
            var first = Double.NaN; var last = Double.NaN; var firstAlong = 0.0; var lastAlong = 0.0; var lastI = -1
            for (i in 0 until n) {
                val ks = cand[i] ?: continue
                val fs = frac[i]!!
                var pos = Double.NaN
                if (forward) {
                    for (j in ks.indices) { val p = ks[j] + fs[j]; if (p >= cur) { pos = p; break } }
                } else {
                    for (j in ks.indices.reversed()) { val p = ks[j] + fs[j]; if (p <= cur) { pos = p; break } }
                }
                if (pos.isNaN()) continue
                cur = pos
                w += weight[i]
                if (first.isNaN()) { first = atEnd(i, pos, late = forward); firstAlong = sAlong[i] }
                last = pos; lastAlong = sAlong[i]; lastI = i
            }
            if (first.isNaN()) return null
            last = atEnd(lastI, last, late = !forward)
            return Pass((w / lengthM).coerceIn(0.0, 1.0), first, last, firstAlong, lastAlong)
        }
        val fwd = pass(true)
        val back = pass(false)
        val chosen = when {
            fwd != null && fwd.share >= MATCH_SHARE -> fwd
            back != null && back.share >= MATCH_SHARE -> back
            else -> listOfNotNull(fwd, back).maxByOrNull { it.share }
        }
        val reversed = chosen != null && chosen === back && chosen !== fwd
        val ordered = chosen?.share ?: 0.0
        fun partial(onRoute: Double? = null) = Match(track.id, track.name, Kind.PARTIAL, coverage, ordered, tol, false, null, null, onRoute)
        if (chosen == null || ordered < MATCH_SHARE) return partial()
        // The ends: both route ends covered, and matched in this traversal.
        if (cand[0] == null || cand[n - 1] == null || chosen.firstAlong > END_M || lengthM - chosen.lastAlong > END_M) return partial()
        fun timeAt(pos: Double): Double {
            val k = floor(pos).toInt().coerceIn(0, fixes.size - 2)
            val f = (pos - k).coerceIn(0.0, 1.0)
            return fixes[k].time + f * (fixes[k + 1].time - fixes[k].time)
        }
        val lo = minOf(chosen.first, chosen.last)
        val hi = maxOf(chosen.first, chosen.last)
        val t0 = timeAt(lo)
        val t1 = timeAt(hi)
        val elapsedS = (t1 - t0) / 1000.0
        if (elapsedS <= 0) return partial()
        // The track's time between the ends spent near the route: no long side trip inside the time.
        val near = nearRoute(fixes, floor(lo).toInt(), kotlin.math.ceil(hi).toInt().coerceAtMost(fixes.size - 1), sx, sy, tol)
        if (near < ON_ROUTE_SHARE) return partial(near)
        // The track's clock runs with its positions whichever way the route goes: the traversal began at the lower one.
        return Match(track.id, track.name, Kind.FULL, coverage, ordered, tol, reversed, t0.roundToLong(), elapsedS, near)
    }

    /** Share of the time between fixes [from] and [to] spent at fixes within [tol] (plus a sample step) of the route's samples. */
    private fun nearRoute(fixes: List<Fix>, from: Int, to: Int, sx: List<Double>, sy: List<Double>, tol: Double): Double {
        if (to <= from) return 1.0
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for (i in sx.indices) grid.getOrPut(key(floor(sx[i] / CELL_M).toInt(), floor(sy[i] / CELL_M).toInt())) { ArrayList() } += i
        val reach = tol + STEP_M / 2
        fun near(f: Fix): Boolean {
            val cx = floor(f.x / CELL_M).toInt(); val cy = floor(f.y / CELL_M).toInt()
            for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                for (i in grid[key(gx, gy)] ?: continue) {
                    val dx = sx[i] - f.x; val dy = sy[i] - f.y
                    if (dx * dx + dy * dy <= reach * reach) return true
                }
            }
            return false
        }
        var total = 0.0
        var inside = 0.0
        for (k in from until to) {
            val dt = (fixes[k + 1].time - fixes[k].time).toDouble()
            if (dt <= 0) continue
            total += dt
            if (near(fixes[k]) && near(fixes[k + 1])) inside += dt
        }
        return if (total > 0) inside / total else 1.0
    }

    private fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
}

/** T184: the route card's past-effort line (ADR 0031 strings). */
object PastEffortText {
    const val PARTIAL = "Part of this trail was recorded"
    const val OTHER_DIRECTION = " (other direction)"
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** "June", or "June 2025" when not in [nowMs]'s year (both in local time by [utcOffsetMs]). */
    fun month(ms: Long, nowMs: Long, utcOffsetMs: Long = 0): String {
        val (y, m, _) = GpxFormat.civilFromDays((ms + utcOffsetMs).floorDiv(86_400_000L))
        val (ny, _, _) = GpxFormat.civilFromDays((nowMs + utcOffsetMs).floorDiv(86_400_000L))
        return MONTHS[m - 1] + if (y != ny) " $y" else ""
    }

    private fun one(m: PastEfforts.Match, nowMs: Long, off: Long) = "${TimeEstimate.hm(m.elapsedS!!)}, ${month(m.startMs!!, nowMs, off)}"

    /**
     * "You did this trail in 3:52, June", with "; latest 4:05, September" when the latest is another, slower one, and
     * " (other direction)" when only the reverse was walked; "Part of this trail was recorded" for a partial overlap.
     */
    fun line(s: PastEfforts.Summary, nowMs: Long, utcOffsetMs: Long = 0): String? {
        val best = s.best
        if (best == null) return if (s.partial != null) PARTIAL else null
        val latest = s.latest
        val dir = if (best.reversed) OTHER_DIRECTION else ""
        val tail = if (latest != null && latest !== best && latest.trackId != best.trackId) "; latest ${one(latest, nowMs, utcOffsetMs)}" else ""
        return "You did this trail in ${one(best, nowMs, utcOffsetMs)}$tail$dir"
    }
}
