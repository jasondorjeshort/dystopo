package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.gpx.StopOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackSmoothing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.gpx.TrackStops
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * T213: where the user's own tracks keep leaving the mapped trail network ("Your tracks leave the mapped trail here"),
 * the detection half of `docs/ideas/2026-10-29-trail-corrections-from-tracks.md` (the send to OpenStreetMap is a later,
 * opt-in task). Nothing here leaves the device; the input is the user's stored tracks and the trail graph.
 *
 * No app shows this to a track's owner and no mapper tool publishes defaults (the survey of 2026-11-02,
 * `out/wave27-research/track-vs-map-disagreement.md`: the mapper tools' knobs are a distance off the line, a minimum length
 * and a count of agreeing traces, all without published values), so the numbers are the project's own:
 *
 * 1. **Off the line** ([candidates]): each track is cut at its stops and smoothed as [TrackMatch] does (the same
 *    [TrackStops] cut, [TrackSmoothing] and flagged-fix rule; terrain estimates are left out too), and every fix is
 *    snapped with [TrackMatch]'s [TrackSnapper]. A fix is off the network when no line lies within [offM]: the larger of
 *    [ACCURACY_FACTOR] times its own accuracy and [MIN_OFF_M]. Not capped as [TrackMatch.toleranceM] is at 50 m, so a fix
 *    with a 40 m accuracy under trees needs 80 m before it counts (drift is not flagged). A run of consecutive off fixes
 *    of [MIN_LENGTH_M] or more along the track (measured on a running mean of the fixes thinned to [LINE_STEP_M]) is a candidate stretch; a jump of over [TrackMatch.JUMP_M] between fixes,
 *    a stop, or a fix outside the network's coverage ends a run. A run must touch the network at one end at least (it
 *    leaves or joins a mapped line), so a track wholly outside the graph is not one. A track that stays within the
 *    threshold is never a candidate. Tracks on a road, rail, boat or plane are not read.
 * 2. **Agreement** ([cluster]): candidates whose lines lie within [AGREE_M] of each other for [AGREE_SHARE] of the
 *    shorter one's length are one stretch (transitively). A stretch is **confirmed** when its traversals fall on
 *    [MIN_DAYS] different days, or when one traversal had an accuracy of [GOOD_ACCURACY_M] or better at every fix (the
 *    idea's "two traversals on different days, or one track with good accuracy all the way"). An ordinary single
 *    track stays unconfirmed: kept in the report, never marked.
 * 3. **The record** ([Divergence]): the line of the best-accuracy traversal (ties: the longest), its length, the
 *    distinct tracks, traversals and days, the median offset of all its fixes from the nearest mapped line (searched
 *    to [NEAR_M]), and that nearest line at the stretch's midpoint ([EdgeFinder]: its name and class).
 *
 * Wording is hedged as the mapper tools word it ("probable", "candidate"): [DivergenceText], never "error" or "wrong".
 * `devtool divergence` and the phone's `POST /divergence/run` run the same code.
 */
object TrailDivergence {
    /** Off the line by at least this, metres, whatever the accuracy (the idea's 25 m). */
    const val MIN_OFF_M = 25.0
    /** Off the line by more than this many times the fix's own accuracy. */
    const val ACCURACY_FACTOR = 2.0
    /** A stretch is this long along the track at least, metres (the idea's 100 m): shorter wobbles are dropped. */
    const val MIN_LENGTH_M = 100.0
    /** One traversal confirms a stretch alone when every fix along it is this accurate or better, metres. */
    const val GOOD_ACCURACY_M = 10.0
    /** Otherwise its traversals fall on this many different days. */
    const val MIN_DAYS = 2
    /** Two traversals agree when the shorter lies within this of the other for [AGREE_SHARE] of its length, metres. */
    const val AGREE_M = 25.0
    const val AGREE_SHARE = 0.5
    /** How far the record looks for the nearest mapped line, metres. */
    const val NEAR_M = 500.0
    /** The lines are compared at samples this far apart, metres. */
    const val SAMPLE_M = 10.0
    /**
     * Lengths are measured, and the record's line drawn, along the fixes thinned to one at least this far from the last
     * one kept, metres: at a walking pace a fix every few seconds with metres of jitter otherwise doubles the length.
     */
    const val LINE_STEP_M = 20.0
    /** And on a running mean of this many fixes either side of each ([steady]). */
    const val STEADY_HALF = 3

    private const val M_PER_DEG = 111_195.0
    private const val DAY_MS = 86_400_000L

    /** The threshold for a fix with accuracy [accuracyM] (null: none reported, so [MIN_OFF_M]). */
    fun offM(accuracyM: Double?): Double = max(ACCURACY_FACTOR * (accuracyM?.takeIf { it > 0 } ?: 0.0), MIN_OFF_M)

    /** Tracks of these activities are read (null: not said). Roads, rails, water and air are not trails. */
    fun reads(a: Activity?): Boolean = a == null || a == Activity.FOOT || a == Activity.BIKE

    /** A point of a stretch's line. */
    data class Pt(val lat: Double, val lon: Double)

    /** The nearest mapped line: where on it, how far, its name and class (from [EdgeSnap]). */
    data class NearEdge(val lat: Double, val lon: Double, val distanceM: Double, val name: String?, val wayClass: WayClass?) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "lat" to r6(lat), "lon" to r6(lon), "distanceM" to r1(distanceM), "name" to name, "class" to wayClass?.name?.lowercase(),
        )
    }

    /** The network's lines with their names, for the record's nearest edge ([RoutingGraph.nearest] or [Router.nearest]). */
    fun interface EdgeFinder {
        fun nearest(lat: Double, lon: Double, maxM: Double): EdgeSnap?
    }

    fun edges(g: RoutingGraph): EdgeFinder = EdgeFinder { lat, lon, maxM ->
        g.nearest(lat, lon, maxM)?.let { s -> s.copy(name = g.name(s.edge), wayClass = g.wayClass(s.edge)) }
    }

    fun edges(r: Router): EdgeFinder = EdgeFinder { lat, lon, maxM -> r.nearest(lat, lon, maxM) }

    /** One traversal off the network: a run of one track's fixes. */
    data class Stretch(
        val trackId: String,
        val trackName: String,
        val points: List<Pt>,
        val startMs: Long?,
        val lengthM: Double,
        /** The worst accuracy of its fixes; null when any fix reported none. */
        val worstAccuracyM: Double?,
        /** Each fix's distance from the nearest line within [NEAR_M] (absent beyond it). */
        val offsetsM: List<Double>,
        /** Whether it leaves a mapped line at its start and joins one at its end. */
        val leaves: Boolean,
        val joins: Boolean,
    ) {
        val precise: Boolean get() = worstAccuracyM != null && worstAccuracyM <= GOOD_ACCURACY_M

        fun json(): Map<String, Any?> = linkedMapOf(
            "trackId" to trackId, "name" to trackName, "startMs" to startMs, "start" to startMs?.let { GpxFormat.formatTime(it) },
            "points" to points.size, "lengthM" to r1(lengthM), "worstAccuracyM" to worstAccuracyM?.let { r1(it) }, "precise" to precise,
            "medianOffsetM" to median(offsetsM)?.let { r1(it) }, "leaves" to leaves, "joins" to joins,
        )
    }

    /** A stretch the tracks agree on: what the marker and the card show. */
    data class Divergence(
        val id: String,
        val line: List<Pt>,
        val lengthM: Double,
        val tracks: Int,
        val traversals: Int,
        val days: Int,
        val trackIds: List<String>,
        /** When each traversal began (sorted). */
        val startsMs: List<Long>,
        val medianOffsetM: Double?,
        val edge: NearEdge?,
        /** `days`, `accuracy`, or null while unconfirmed (no marker). */
        val confirmedBy: String?,
        val members: List<Stretch> = emptyList(),
    ) {
        val confirmed: Boolean get() = confirmedBy != null
        val mid: Pt get() = midpoint(line)

        fun json(withMembers: Boolean = false): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id, "confirmed" to confirmed, "confirmedBy" to confirmedBy, "tracks" to tracks, "traversals" to traversals, "days" to days,
            "lengthM" to r1(lengthM), "medianOffsetM" to medianOffsetM?.let { r1(it) },
            "mid" to linkedMapOf("lat" to r6(mid.lat), "lon" to r6(mid.lon)), "edge" to edge?.json(),
            "trackIds" to trackIds, "starts" to startsMs.map { GpxFormat.formatTime(it) },
            "line" to line.map { listOf(r6(it.lat), r6(it.lon)) },
        ).also { if (withMembers) it["members"] = members.map { m -> m.json() } }

        companion object {
            /** The record [json] wrote (the phone's store); null when it does not read. */
            @Suppress("UNCHECKED_CAST")
            fun fromJson(m: Map<String, Any?>): Divergence? = runCatching {
                val line = (m["line"] as List<List<Number>>).map { Pt(it[0].toDouble(), it[1].toDouble()) }
                val e = m["edge"] as Map<String, Any?>?
                Divergence(
                    m["id"] as String, line, (m["lengthM"] as Number).toDouble(), (m["tracks"] as Number).toInt(),
                    (m["traversals"] as Number).toInt(), (m["days"] as Number).toInt(), (m["trackIds"] as List<String>),
                    (m["starts"] as List<String>?).orEmpty().mapNotNull { GpxFormat.parseTime(it) }, (m["medianOffsetM"] as Number?)?.toDouble(),
                    e?.let {
                        NearEdge(
                            (it["lat"] as Number).toDouble(), (it["lon"] as Number).toDouble(), (it["distanceM"] as Number).toDouble(),
                            it["name"] as String?, (it["class"] as String?)?.let { c -> WayClass.entries.firstOrNull { w -> w.name.equals(c, true) } },
                        )
                    },
                    m["confirmedBy"] as String?,
                )
            }.getOrNull()
        }
    }

    /** What one detection found, with the counts behind it. */
    data class Report(val records: List<Divergence>, val candidates: List<Stretch>, val counts: Map<String, Any?>) {
        val confirmed: List<Divergence> get() = records.filter { it.confirmed }

        fun json(withMembers: Boolean = false): Map<String, Any?> = linkedMapOf(
            "records" to confirmed.map { it.json(withMembers) },
            "unconfirmed" to records.filter { !it.confirmed }.map { it.json(withMembers) },
            "counts" to counts, "thresholds" to thresholds(),
        )
    }

    fun thresholds(): Map<String, Any?> = linkedMapOf(
        "offM" to "max($ACCURACY_FACTOR x accuracy, $MIN_OFF_M)", "minLengthM" to MIN_LENGTH_M, "goodAccuracyM" to GOOD_ACCURACY_M,
        "minDays" to MIN_DAYS, "agreeM" to AGREE_M, "agreeShare" to AGREE_SHARE, "jumpM" to TrackMatch.JUMP_M,
    )

    /**
     * Every track's candidates and the stretches they agree on. [covers] says where the network has data (null: every
     * point); [utcOffsetMs] places the days.
     */
    fun detect(
        tracks: List<Track>, snapper: TrackSnapper, edges: EdgeFinder? = null, covers: ((Double, Double) -> Boolean)? = null,
        utcOffsetMs: Long = 0, stops: StopOptions = StopOptions(),
    ): Report {
        val all = ArrayList<Stretch>()
        var read = 0
        var skipped = 0
        for (t in tracks) {
            if (!reads(t.activity)) { skipped++; continue }
            read++
            all += candidates(t, snapper, covers, stops)
        }
        val records = cluster(all, snapper, edges, utcOffsetMs)
        return Report(
            records, all,
            linkedMapOf(
                "tracks" to tracks.size, "read" to read, "notTrails" to skipped, "candidates" to all.size,
                "confirmed" to records.count { it.confirmed }, "unconfirmed" to records.count { !it.confirmed },
            ),
        )
    }

    /** The runs of [track] off the network ([TrailDivergence] step 1). */
    fun candidates(track: Track, snapper: TrackSnapper, covers: ((Double, Double) -> Boolean)? = null, stops: StopOptions = StopOptions()): List<Stretch> {
        if (!reads(track.activity)) return emptyList()
        val raw = track.segments.map { it.points }
        val cut = TrackStops.cut(raw, stops)
        val smoothed = stops.smoothing?.let { TrackSmoothing.smooth(raw, it) } ?: raw
        val out = ArrayList<Stretch>()
        for (st in cut.stretches) {
            val seg = smoothed[st.segment]
            val rs = raw[st.segment]
            val pts = ArrayList<Pt>()
            val offsets = ArrayList<Double>()
            var start: Long? = null
            var worst: Double? = 0.0
            var len = 0.0
            var leaves = false
            // Whether the last fix kept was on the network (null: none yet, or after a jump or a gap in coverage).
            var lastOn: Boolean? = null
            var last: Pt? = null
            fun flush(joins: Boolean) {
                if (pts.size >= 2 && len >= MIN_LENGTH_M && (leaves || joins)) {
                    val length = lengthOf(thin(steady(pts)))
                    if (length >= MIN_LENGTH_M) out += Stretch(track.id, track.name, pts.toList(), start, length, worst, offsets.toList(), leaves, joins)
                }
                pts.clear(); offsets.clear(); start = null; worst = 0.0; len = 0.0; leaves = false
            }
            for (i in st.startIndex..st.endIndex) {
                val r = rs[i]
                if (!TrackSmoothing.counts(r) || r.estimated) continue
                val p = seg[i]
                val here = Pt(p.lat, p.lon)
                val prev = last
                if (prev != null && TrackStats.distanceM(prev.lat, prev.lon, here.lat, here.lon) > TrackMatch.JUMP_M) {
                    flush(joins = false)
                    lastOn = null
                }
                last = here
                if (covers != null && !covers(here.lat, here.lon)) {
                    flush(joins = false)
                    lastOn = null
                    continue
                }
                val on = snapper.snap(here.lat, here.lon, offM(r.accuracyM)) != null
                if (on) {
                    if (pts.isNotEmpty()) flush(joins = true)
                    lastOn = true
                    continue
                }
                if (pts.isEmpty()) {
                    leaves = lastOn == true
                    start = r.time ?: p.time
                } else {
                    val q = pts.last()
                    len += TrackStats.distanceM(q.lat, q.lon, here.lat, here.lon)
                }
                pts += here
                worst = r.accuracyM?.takeIf { it > 0 }?.let { a -> worst?.let { w -> max(w, a) } }
                snapper.snap(here.lat, here.lon, NEAR_M)?.let { offsets += it.distanceM }
                lastOn = false
            }
            flush(joins = false)
        }
        return out
    }

    /** The candidates the tracks agree on, as records ([TrailDivergence] steps 2 and 3); confirmed first, longest first. */
    fun cluster(all: List<Stretch>, snapper: TrackSnapper? = null, edges: EdgeFinder? = null, utcOffsetMs: Long = 0): List<Divergence> {
        if (all.isEmpty()) return emptyList()
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        val boxes = all.map { box(it.points) }
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (!boxes[i].near(boxes[j], AGREE_M)) continue
            if (agree(all[i].points, all[j].points)) parent[find(i)] = find(j)
        }
        val groups = all.indices.groupBy { find(it) }.values
        val out = groups.map { idx ->
            val ms = idx.map { all[it] }
            val best = ms.minWith(compareBy<Stretch> { it.worstAccuracyM ?: Double.MAX_VALUE }.thenByDescending { it.lengthM })
            val line = thin(steady(best.points))
            val days = ms.mapNotNull { it.startMs?.let { s -> (s + utcOffsetMs).floorDiv(DAY_MS) } }.toSet().size
            val by = when {
                days >= MIN_DAYS -> "days"
                ms.any { it.precise } -> "accuracy"
                else -> null
            }
            val mid = midpoint(line)
            val edge = edges?.nearest(mid.lat, mid.lon, NEAR_M)?.let { NearEdge(it.lat, it.lon, it.distanceM, it.name, it.wayClass) }
                ?: snapper?.snap(mid.lat, mid.lon, NEAR_M)?.let { NearEdge(it.lat, it.lon, it.distanceM, null, null) }
            Divergence(
                idOf(mid), line, lengthOf(line), ms.map { it.trackId }.distinct().size, ms.size, days, ms.map { it.trackId }.distinct(),
                ms.mapNotNull { it.startMs }.sorted(), median(ms.flatMap { it.offsetsM }), edge, by, ms,
            )
        }
        return out.sortedWith(compareBy<Divergence> { !it.confirmed }.thenByDescending { it.tracks }.thenByDescending { it.lengthM })
    }

    /** Whether two lines agree: the shorter lies within [AGREE_M] of the other for [AGREE_SHARE] of its samples. */
    fun agree(a: List<Pt>, b: List<Pt>): Boolean {
        if (a.size < 2 || b.size < 2) return false
        val (short, long) = if (lengthOf(a) <= lengthOf(b)) a to b else b to a
        val s = samples(short)
        if (s.isEmpty()) return false
        val near = s.count { distanceToLineM(it, long) <= AGREE_M }
        return near >= AGREE_SHARE * s.size
    }

    /** The shortest distance from [p] to the polyline [line], metres (a local plane around [p]). */
    fun distanceToLineM(p: Pt, line: List<Pt>): Double {
        val kx = M_PER_DEG * cos(p.lat * PI / 180)
        var best = Double.MAX_VALUE
        for (k in 0 until line.size - 1) {
            val ax = (line[k].lon - p.lon) * kx; val ay = (line[k].lat - p.lat) * M_PER_DEG
            val bx = (line[k + 1].lon - p.lon) * kx; val by = (line[k + 1].lat - p.lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val ex = ax + t * dx; val ey = ay + t * dy
            val d = sqrt(ex * ex + ey * ey)
            if (d < best) best = d
        }
        if (line.size == 1) best = TrackStats.distanceM(p.lat, p.lon, line[0].lat, line[0].lon)
        return best
    }

    fun lengthOf(line: List<Pt>): Double {
        var s = 0.0
        for (i in 1 until line.size) s += TrackStats.distanceM(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
        return s
    }

    /** The point halfway along [line]. */
    fun midpoint(line: List<Pt>): Pt {
        if (line.size < 2) return line.firstOrNull() ?: Pt(0.0, 0.0)
        val half = lengthOf(line) / 2
        var s = 0.0
        for (i in 1 until line.size) {
            val d = TrackStats.distanceM(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
            if (s + d >= half && d > 0) {
                val f = (half - s) / d
                return Pt(line[i - 1].lat + f * (line[i].lat - line[i - 1].lat), line[i - 1].lon + f * (line[i].lon - line[i - 1].lon))
            }
            s += d
        }
        return line.last()
    }

    /** Points along [line] every [SAMPLE_M] (its ends included). */
    private fun samples(line: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        out += line[0]
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val d = TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon)
            val n = max(1, kotlin.math.ceil(d / SAMPLE_M).toInt())
            for (k in 1..n) {
                val f = k.toDouble() / n
                out += Pt(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
            }
        }
        return out
    }

    /** [line] with each point the mean of the [STEADY_HALF] points either side of it (its ends kept): the fixes' jitter out. */
    fun steady(line: List<Pt>): List<Pt> {
        if (line.size <= 2) return line
        return List(line.size) { i ->
            if (i == 0 || i == line.size - 1) line[i] else {
                val from = maxOf(0, i - STEADY_HALF)
                val to = minOf(line.size - 1, i + STEADY_HALF)
                var la = 0.0; var lo = 0.0
                for (k in from..to) { la += line[k].lat; lo += line[k].lon }
                Pt(la / (to - from + 1), lo / (to - from + 1))
            }
        }
    }

    /** [line] with a point kept only [LINE_STEP_M] or more from the last one kept (its ends always). */
    fun thin(line: List<Pt>): List<Pt> {
        if (line.size <= 2) return line
        val out = ArrayList<Pt>()
        out += line[0]
        for (i in 1 until line.size - 1) {
            val q = out.last()
            if (TrackStats.distanceM(q.lat, q.lon, line[i].lat, line[i].lon) >= LINE_STEP_M) out += line[i]
        }
        out += line.last()
        return out
    }

    private class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
        fun near(o: Box, m: Double): Boolean {
            val dLat = m / M_PER_DEG
            val dLon = m / (M_PER_DEG * cos(minLat * PI / 180).coerceAtLeast(0.01))
            return minLat - dLat <= o.maxLat && o.minLat - dLat <= maxLat && minLon - dLon <= o.maxLon && o.minLon - dLon <= maxLon
        }
    }

    private fun box(line: List<Pt>) = Box(line.minOf { it.lat }, line.minOf { it.lon }, line.maxOf { it.lat }, line.maxOf { it.lon })

    /** A short id from the midpoint (about 10 m cells): the same stretch found again keeps it. */
    fun idOf(mid: Pt): String {
        val la = (mid.lat * 10_000).roundToLong()
        val lo = (mid.lon * 10_000).roundToLong()
        val h = (la * 1_000_003L) xor (lo * 998_244_353L)
        return "dv" + (h and 0xffffffffffL).toString(16)
    }

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun r1(v: Double) = (v * 10).roundToLong() / 10.0
    private fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6
}

/**
 * T213: what the phone says about a [TrailDivergence.Divergence] (ADR 0031 strings), hedged as the mapper tools word a
 * candidate: never "error" or "wrong".
 */
object DivergenceText {
    const val TITLE = "Your tracks leave the mapped trail here"
    const val DISMISS = "Dismiss"
    const val SETTING = "Show trail disagreements"

    /** "2 of your tracks follow a line about 140 m long that is not on the map" ("One of your tracks follows ..."). */
    fun body(d: TrailDivergence.Divergence, units: ContourUnits): String = body(d.tracks, d.lengthM, units)

    fun body(tracks: Int, lengthM: Double, units: ContourUnits): String {
        val who = if (tracks <= 1) "One of your tracks follows" else "$tracks of your tracks follow"
        return "$who a line about ${about(lengthM, units)} long that is not on the map"
    }

    /** A length rounded for "about": to 10 m or 10 ft below a kilometre or a tenth of a mile, else as the distance rule. */
    fun about(m: Double, units: ContourUnits): String {
        val unit = UnitFormat.distanceUnit(m, units)
        val v = m / unit.meters
        return when (unit) {
            io.github.jasondorjeshort.dystopo.render.DistanceUnit.FEET, io.github.jasondorjeshort.dystopo.render.DistanceUnit.METERS ->
                "${(v / 10).roundToLong() * 10} ${unit.symbol}"
            else -> "${UnitFormat.fixed(v, if (v < 10) 1 else 0)} ${unit.symbol}"
        }
    }
}
