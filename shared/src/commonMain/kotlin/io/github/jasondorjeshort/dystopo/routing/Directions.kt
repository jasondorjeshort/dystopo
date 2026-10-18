package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** T102: what a route does at an [Instruction]. The codes are stable: JSON, the browser, a later voice formatter. */
enum class Maneuver(val code: String) {
    DEPART("depart"),
    /** Straight on where the way's name changes (at a junction with a choice). */
    CONTINUE("continue"),
    SLIGHT_LEFT("slight-left"), LEFT("left"), SHARP_LEFT("sharp-left"),
    SLIGHT_RIGHT("slight-right"), RIGHT("right"), SHARP_RIGHT("sharp-right"),
    /** A fork: another way leaves close beside the one to take. */
    KEEP_LEFT("keep-left"), KEEP_RIGHT("keep-right"),
    U_TURN("u-turn"),
    /** A via point of a trip (with a turn back, when the route reverses there). */
    VIA("via"),
    /** The start of a straight leg: no trail or road joins the via points. */
    OFF_NETWORK("off-network"),
    ARRIVE("arrive");

    companion object {
        fun of(code: String?): Maneuver? = entries.firstOrNull { it.code == code }
    }
}

/**
 * T102: one way meeting a junction of the graph, as seen from the junction: where it heads ([bearingDeg], over its
 * first [Directions.BEARING_M]), the point next to the junction along it (microdegrees: how the route's own way in and
 * way out are found), its name and class, and whether the mode may leave the junction along it ([enterable]: usable,
 * and for drive not a one-way the other way). [speedKmh]: drive's speed on it (tagged, else the class speed). [level]:
 * the junction node's level ([RoutingGraph.nodeLevel]), so a bridge cut at a border is not a junction of the road below.
 */
data class Branch(
    val bearingDeg: Double,
    val nextLonE6: Int,
    val nextLatE6: Int,
    val name: String?,
    val wayClass: WayClass,
    val enterable: Boolean,
    val speedKmh: Double = 0.0,
    val level: Int = 0,
)

/** T102: where a route's junctions come from: the graph it was planned on ([TileRouter] is one). */
fun interface JunctionSource {
    /** The ways at the graph node at exactly ([lonE6], [latE6]) for [mode] (twins in other tiles and packs joined); null when no node is there. */
    fun junction(lonE6: Int, latE6: Int, mode: TravelMode): List<Branch>?
}

/**
 * T102: one written direction along a route. Structured so a voice formatter and a detail-level filter can follow
 * without a format change: [type], [priority] (1 major: always said; 2 normal: the default level; 3 minor: only at
 * the most detailed level), where it is ([alongM] metres from the route's start, [lat], [lon]), the way the route
 * continues on ([name], else [wayClass]; [sameWay] when the turn keeps to the way already followed), the signed turn
 * ([turnDeg], positive right) and the heading after it ([bearingDeg]), when to announce it ([leadM] before it), and how
 * far the route then runs to the next instruction ([stretchM]). [via]: the via point's index for [Maneuver.VIA].
 * T116: [then], the "then" cue: the next instruction shown when it comes too soon to be announced on its own
 * ([Directions.cued]); derived from the list, never stored ([io.github.jasondorjeshort.dystopo.model.RouteDirections]
 * keeps no cue, and a list read back gets its cues again from [Directions.shown]).
 */
data class Instruction(
    val type: Maneuver,
    val priority: Int,
    val alongM: Double,
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val wayClass: WayClass? = null,
    val sameWay: Boolean = false,
    val turnDeg: Int = 0,
    val bearingDeg: Int = 0,
    val leadM: Double = 0.0,
    val stretchM: Double = 0.0,
    val via: Int? = null,
    val then: Instruction? = null,
) {
    /** What the way is called in the text: its name (OSM name, else route name, else ref), else its class ([Directions.classWord]). */
    val label: String get() = name ?: Directions.classWord(wayClass)

    fun json(mode: TravelMode): Map<String, Any?> = linkedMapOf(
        "type" to type.code, "priority" to priority, "text" to DirectionsText.of(this, mode),
        "alongM" to r1(alongM), "lat" to r6(lat), "lon" to r6(lon),
        "name" to name, "class" to wayClass?.label, "sameWay" to sameWay.takeIf { it },
        "turnDeg" to turnDeg, "bearingDeg" to bearingDeg, "leadM" to r1(leadM), "stretchM" to r1(stretchM), "via" to via,
        "then" to then?.type?.code, // T116: the cue's manoeuvre (its words are in `text`)
    )

    companion object {
        const val MAJOR = 1
        const val NORMAL = 2
        const val MINOR = 3

        private fun r1(v: Double) = (v * 10).roundToLong() / 10.0
        private fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6

        /** Reads what [json] wrote (the server's `/route` answer for [RemoteRouter]); null when it is not an instruction. */
        fun parse(m: Map<*, *>?): Instruction? {
            m ?: return null
            fun d(k: String) = (m[k] as? Number)?.toDouble()
            val type = Maneuver.of(m["type"] as? String) ?: return null
            return Instruction(
                type, (d("priority") ?: NORMAL.toDouble()).toInt().coerceIn(MAJOR, MINOR), d("alongM") ?: return null,
                d("lat") ?: return null, d("lon") ?: return null, m["name"] as? String,
                WayClass.entries.firstOrNull { it.label == m["class"] }, m["sameWay"] == true,
                (d("turnDeg") ?: 0.0).roundToInt(), (d("bearingDeg") ?: 0.0).roundToInt(), d("leadM") ?: 0.0, d("stretchM") ?: 0.0,
                d("via")?.toInt(),
            )
        }
    }
}

/**
 * T102, the manoeuvre layer (PLANNING › Routing, Turn directions): written instructions at real decisions only, in
 * every mode. A route's points are looked up in the graph ([JunctionSource]); at each junction the turn angle comes
 * from the route's own line over [BEARING_M] either side, the other ways from the junction:
 *
 * - **No choice, no instruction.** Where no other way the mode may take leaves the junction (a bend, two ways joined
 *   end to end, a tile cut), nothing is said, however sharp the bend; on a trail a turn is an instruction only where
 *   another way leaves.
 * - **Straight on is merged.** Straight through a junction (under [STRAIGHT_DEG]) on the same way says nothing, so a
 *   run of straight-through junctions is one stretch; straight on where the way's name changes is [Maneuver.CONTINUE].
 *   The way compared is the one last announced, so a name change at a junction with no choice is said at the next
 *   junction that has one.
 * - **Forks** (another way within [FORK_DEG] of the one taken, both ahead) are keep left or keep right; other turns
 *   are classed by angle: slight under [SLIGHT_DEG], a turn under [SHARP_DEG], sharp, then a U-turn from [U_TURN_DEG].
 *   The road itself splitting (another way of the same name) and, by car, a lesser road leaving the road followed
 *   (a pull-out, a parking lot) are no fork and no reason to say a bend.
 * - **Close together.** A keep or slight turn that only stays on the way, or a continue, within [mergeM] before the
 *   next instruction is left to that one (a split just before a turn, the short link of a triangle junction).
 * - **Priority.** Forks and turns are [Instruction.MAJOR], slight turns and name changes [Instruction.NORMAL]; any
 *   turn where another way is straighter than the one taken is major (going straight would be wrong); a turn that keeps
 *   to the way already followed while every other way turns more is one level less (a road's bend at a side road).
 *   The default level ([DEFAULT_LEVEL]) shows major and normal.
 * - **Names**: OSM name, else the hiking route's name, else ref (the graph's one name per edge, [WayAttrs.of]), else
 *   the class ([classWord]).
 * - **Lead distance** by mode and speed ([leadM]): tens of metres on foot, hundreds on a bike, up to two miles in a car
 *   at the approach road's speed; never further back than the previous instruction.
 * - **"Then" cues** (T116, [cued]): where the next instruction shown follows within its own lead distance (its
 *   announcement would have to start before this one is passed: about 20 s on foot or a bike, 45 s in a car), this
 *   one also says what comes next: "Head south on the service road, then turn right". The cue is worked out from the
 *   list at the detail level shown ([shown] sets it), so saved directions and the server's carry no field for it.
 *
 * Depart and arrive frame every list; a trip's via points are [Maneuver.VIA], a straight leg starts with
 * [Maneuver.OFF_NETWORK] ([ofPath]). Text: [DirectionsText]; a voice formatter would read the same fields.
 */
object Directions {
    /** The route's line and each way's bearing are measured over this many metres from the junction. */
    const val BEARING_M = 25.0
    const val STRAIGHT_DEG = 25.0
    const val SLIGHT_DEG = 50.0
    const val SHARP_DEG = 130.0
    const val U_TURN_DEG = 165.0
    /** Another way within this of the one taken is a fork... */
    const val FORK_DEG = 40.0
    /** ...when both are within this of straight ahead. */
    const val FORK_AHEAD_DEG = 70.0
    /** One way is straighter than another by at least this. */
    const val MARGIN_DEG = 10.0
    /** Branches this close in bearing with the same name are one way (parallel copies from overlapping packs). */
    const val SAME_BRANCH_DEG = 5.0
    /** A way found by bearing (no exact point match) must be within this of the route's line. */
    const val MATCH_DEG = 35.0
    /** The priority levels shown by default: major and normal. */
    const val DEFAULT_LEVEL = Instruction.NORMAL

    /**
     * How far before an instruction it is announced, by mode and speed (km/h; null for the mode's usual pace): about
     * 20 s ahead on foot (20 to 60 m) and on a bike (40 to 200 m), about 45 s in a car (150 m to two miles).
     */
    fun leadM(mode: TravelMode, speedKmh: Double? = null): Double {
        val v = (speedKmh?.takeIf { it > 0 } ?: defaultKmh(mode)) / 3.6
        return when (mode) {
            TravelMode.HIKE, TravelMode.CROW -> (v * 20).coerceIn(20.0, 60.0)
            TravelMode.BIKE -> (v * 20).coerceIn(40.0, 200.0)
            TravelMode.DRIVE -> (v * 45).coerceIn(150.0, 3200.0)
        }
    }

    /** The pace [leadM] assumes when no speed is known: 5 km/h walking, 16 km/h cycling, 50 km/h driving. */
    fun defaultKmh(mode: TravelMode): Double = when (mode) {
        TravelMode.HIKE, TravelMode.CROW -> 5.0
        TravelMode.BIKE -> 16.0
        TravelMode.DRIVE -> 50.0
    }

    /** An instruction this close before the next one that only keeps to the way is left to the next one ([of]). */
    fun mergeM(mode: TravelMode): Double = when (mode) {
        TravelMode.HIKE, TravelMode.CROW -> 15.0
        TravelMode.BIKE -> 25.0
        TravelMode.DRIVE -> 60.0
    }
    private val QUIET_IF_FOLLOWED = setOf(Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT, Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT)

    /** How far past an instruction navigation still shows it (the junction's size and GPS error). */
    fun passM(mode: TravelMode): Double = when (mode) {
        TravelMode.HIKE, TravelMode.CROW -> 10.0
        TravelMode.BIKE -> 20.0
        TravelMode.DRIVE -> 40.0
    }

    /** The unnamed way's word in the text: "the path", "the road", ... */
    fun classWord(c: WayClass?): String = when (c) {
        null -> "the way"
        WayClass.PATH -> "the path"
        WayClass.FOOTWAY -> "the footpath"
        WayClass.CYCLEWAY -> "the cycleway"
        WayClass.BRIDLEWAY -> "the bridleway"
        WayClass.STEPS -> "the steps"
        WayClass.TRACK -> "the track"
        WayClass.SERVICE -> "the service road"
        WayClass.MINOR, WayClass.TERTIARY, WayClass.SECONDARY, WayClass.PRIMARY -> "the road"
        WayClass.TRUNK, WayClass.MOTORWAY -> "the highway"
    }

    /** -180 (exclusive) to 180 degrees. */
    fun norm(d: Double): Double {
        var a = d % 360.0
        if (a > 180) a -= 360
        if (a <= -180) a += 360
        return a
    }

    /** The manoeuvre for a turn of [turnDeg] (positive right) where no fork is involved; null when straight on. */
    fun classify(turnDeg: Double): Maneuver? {
        val a = abs(turnDeg)
        val right = turnDeg > 0
        return when {
            a < STRAIGHT_DEG -> null
            a < SLIGHT_DEG -> if (right) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            a < SHARP_DEG -> if (right) Maneuver.RIGHT else Maneuver.LEFT
            a < U_TURN_DEG -> if (right) Maneuver.SHARP_RIGHT else Maneuver.SHARP_LEFT
            else -> Maneuver.U_TURN
        }
    }

    /** Eight compass words for a bearing. */
    fun compass(deg: Double): String = WORDS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    private val WORDS = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    /**
     * The instructions shown at detail [level] (priority up to it), each [Instruction.stretchM] running to the next one
     * shown, and each with its "then" cue ([cued]).
     */
    fun shown(list: List<Instruction>, level: Int = DEFAULT_LEVEL): List<Instruction> {
        val s = cued(list, level).filter { it.priority <= level }
        return s.mapIndexed { k, i -> i.copy(stretchM = if (k + 1 < s.size) s[k + 1].alongM - i.alongM else 0.0) }
    }

    /** How much shorter than the gap a lead may be and still count as reaching back to it: the saved field's decimetres. */
    const val THEN_SLACK_M = 0.5

    /**
     * T116: [list] with its "then" cues at detail [level]: an instruction shown there gets the next one shown
     * ([Instruction.then], without a cue of its own) when that one's lead reaches back to it, that is when its lead
     * distance ([leadM], capped at the gap to the instruction before it) is the whole gap: its announcement would
     * have to start before this one is passed, so both are said together, as navigation apps say "then". A
     * continue (a new name, nothing to do) is no cue. Every other instruction loses any cue it had. The list keeps
     * its order, priorities and distances; only the text changes ([DirectionsText.of]).
     */
    fun cued(list: List<Instruction>, level: Int = DEFAULT_LEVEL): List<Instruction> {
        val out = ArrayList<Instruction>(list.size)
        var next: Instruction? = null // the next one at the level, after the current index
        for (k in list.indices.reversed()) {
            val i = list[k]
            if (i.priority > level) { out += if (i.then == null) i else i.copy(then = null); continue }
            val n = next
            val cue = n?.takeIf { it.type != Maneuver.CONTINUE && it.type != Maneuver.DEPART && it.alongM - i.alongM <= it.leadM + THEN_SLACK_M }
            out += if (cue == i.then) i else i.copy(then = cue)
            next = if (i.then == null) i else i.copy(then = null)
        }
        out.reverse()
        return out
    }

    /**
     * Navigation's next instruction: the index in [list] of the first one shown at [level] that the walker at
     * [progressM] along the line has not yet passed by [passM]; -1 when none is left.
     */
    fun next(list: List<Instruction>, progressM: Double, level: Int = DEFAULT_LEVEL, passM: Double = 10.0): Int =
        list.indexOfFirst { it.priority <= level && it.alongM + passM > progressM }

    /**
     * [list] moved onto another copy of its line (navigation's [io.github.jasondorjeshort.dystopo.nav.NavPath], whose
     * points may be quantised or deduplicated differently): [project] gives the new along-distance of a point near an
     * expected one (the old along-distance scaled by [scale]); the result stays in order.
     */
    fun reanchor(list: List<Instruction>, scale: Double, project: (lat: Double, lon: Double, nearAlongM: Double) -> Double): List<Instruction> {
        var last = 0.0
        return list.map { i ->
            val a = maxOf(last, if (i.type == Maneuver.DEPART) 0.0 else project(i.lat, i.lon, i.alongM * scale))
            last = a
            i.copy(alongM = a)
        }
    }

    /** A trip's instructions: every leg's points, its via points ([Maneuver.VIA]) and straight legs ([Maneuver.OFF_NETWORK]). */
    fun ofPath(path: RoutePath, junctions: JunctionSource?): List<Instruction> {
        val pts = path.points
        val idx = path.viaIndexes
        val straight = path.legs.indices.filter { path.legs[it].kind == LegKind.STRAIGHT }.map { idx[it]..idx[it + 1] }
        return of(pts, TravelMode.of(path.mode) ?: TravelMode.HIKE, junctions, if (idx.size > 2) idx.subList(1, idx.size - 1) else emptyList(), straight)
    }

    /**
     * T108: [path]'s instructions where some legs' inner instructions are already known ([known]: by leg index, each
     * measured from the start of its leg, as [innerByLeg] gives them): a leg an edit left as it was keeps what was said
     * along it (perhaps planned on another phone or on the server, where this phone has no graph), and a leg the
     * server planned brings the server's; every other leg, the via points, the straight legs and the frame come from
     * [junctions] ([ofPath]). Leads are capped and stretches recomputed over the merged list.
     */
    fun splice(path: RoutePath, junctions: JunctionSource?, known: Map<Int, List<Instruction>>): List<Instruction> {
        val fresh = ofPath(path, junctions)
        if (known.isEmpty() || path.legs.isEmpty() || TravelMode.of(path.mode) == TravelMode.CROW) return fresh
        val at = viaAlong(path)
        fun inside(a: Double, i: Int) = a > at[i] + EDGE_M && a < at[i + 1] - EDGE_M
        val kept = fresh.filter { ins -> ins.type in FRAME || known.keys.none { i -> i in path.legs.indices && inside(ins.alongM, i) } }
        val moved = known.flatMap { (i, list) ->
            if (i !in path.legs.indices) emptyList()
            else list.filter { it.type !in FRAME }.map { it.copy(alongM = at[i] + it.alongM) }.filter { inside(it.alongM, i) }
        }
        val all = (kept + moved).sortedBy { it.alongM }
        return all.mapIndexed { k, ins ->
            ins.copy(
                leadM = if (k == 0) 0.0 else minOf(ins.leadM, ins.alongM - all[k - 1].alongM),
                stretchM = if (k + 1 < all.size) all[k + 1].alongM - ins.alongM else 0.0,
            )
        }
    }

    /**
     * T108: [list] (instructions along [path]) cut into its legs: per leg, the instructions strictly inside it (not the
     * depart, via, off-network start or arrive that frame it), each measured from the start of its leg.
     */
    fun innerByLeg(path: RoutePath, list: List<Instruction>): List<List<Instruction>> {
        if (path.legs.isEmpty()) return emptyList()
        val at = viaAlong(path)
        return path.legs.indices.map { i ->
            list.filter { it.type !in FRAME && it.alongM > at[i] + EDGE_M && it.alongM < at[i + 1] - EDGE_M }.map { it.copy(alongM = it.alongM - at[i]) }
        }
    }

    /** The distance along [path] of each via point, measured as [of] measures (consecutive equal points once). */
    fun viaAlong(path: RoutePath): DoubleArray {
        val pts = path.points
        val idx = path.viaIndexes
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + TrackStats.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        return DoubleArray(idx.size) { cum[idx[it]] }
    }

    /** Instructions at a via point or an end; [splice] takes them from the fresh list, never from a kept leg. */
    private val FRAME = setOf(Maneuver.DEPART, Maneuver.VIA, Maneuver.OFF_NETWORK, Maneuver.ARRIVE)

    /** How close to a via point an instruction counts as at it rather than inside a leg (route points are cm-exact). */
    private const val EDGE_M = 0.5

    /**
     * The instructions along [points] travelled in [mode]: depart, one at each real decision (see the object's KDoc),
     * a [Maneuver.VIA] at each index in [vias] (numbered by its place in that list, from 1), a [Maneuver.OFF_NETWORK]
     * at the start of each range in [straight] (a straight leg; no lookups inside it), and arrive. Without [junctions]
     * (a remote route, a track) only those frame the list. Consecutive equal points count once, as navigation counts them.
     */
    fun of(
        points: List<RoutePoint>, mode: TravelMode, junctions: JunctionSource?,
        vias: List<Int> = emptyList(), straight: List<IntRange> = emptyList(),
    ): List<Instruction> {
        if (mode == TravelMode.CROW) return arrivalOnly(points)
        val pts = ArrayList<RoutePoint>(points.size)
        val map = IntArray(points.size)
        for ((i, p) in points.withIndex()) {
            if (pts.isEmpty() || pts.last().lat != p.lat || pts.last().lon != p.lon) pts += p
            map[i] = pts.size - 1
        }
        val n = pts.size
        if (n < 2) return emptyList()
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + TrackStats.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        val viaAt = HashMap<Int, Int>()
        for ((k, v) in vias.withIndex()) if (v in points.indices) map[v].takeIf { it in 1 until n - 1 }?.let { viaAt.getOrPut(it) { k + 1 } }
        val offAt = HashMap<Int, Int>() // straight leg start -> end
        val inStraight = BooleanArray(n)
        for (r in straight) {
            if (r.first !in points.indices || r.last !in points.indices) continue
            val a = map[r.first]
            val b = map[r.last]
            if (b <= a) continue
            offAt[a] = b
            for (i in a..b) inStraight[i] = true
        }
        // Pass 1: the graph's junctions along the line.
        val jn = arrayOfNulls<List<Branch>>(n)
        if (junctions != null) for (i in 1 until n - 1) if (!inStraight[i]) jn[i] = junctions.junction(e6(pts[i].lon), e6(pts[i].lat), mode)
        val stop = BooleanArray(n) { i -> (jn[i]?.size ?: 0) >= 3 || i in viaAt || i in offAt }

        fun bearing(a: Int, b: Int) = TrackStats.bearingDeg(pts[a].lat, pts[a].lon, pts[b].lat, pts[b].lon)
        // The route's heading into and out of point i, over BEARING_M or to the nearest junction or via point.
        fun back(i: Int): Double {
            var j = i - 1
            while (j > 0 && cum[i] - cum[j] < BEARING_M && !stop[j]) j--
            return bearing(j, i)
        }
        fun ahead(i: Int): Double {
            var j = i + 1
            while (j < n - 1 && cum[j] - cum[i] < BEARING_M && !stop[j]) j++
            return bearing(i, j)
        }
        fun diff(a: Double, b: Double) = abs(norm(a - b))
        fun at(branches: List<Branch>, other: Int, travelled: Double): Branch? {
            val lon = e6(pts[other].lon)
            val lat = e6(pts[other].lat)
            return branches.firstOrNull { it.nextLonE6 == lon && it.nextLatE6 == lat }
                ?: branches.minByOrNull { diff(it.bearingDeg, travelled) }?.takeIf { diff(it.bearingDeg, travelled) <= MATCH_DEG }
        }

        val out = ArrayList<Instruction>()
        // The way the route starts on: the way out of its first point when that is a node, else the way in at the first
        // node it meets, else the way in at its last point.
        fun endNode(i: Int) = if (junctions == null || inStraight[i]) null else junctions.junction(e6(pts[i].lon), e6(pts[i].lat), mode)
        val first = (1 until n - 1).firstOrNull { jn[it] != null }
        val startWay = endNode(0)?.let { at(it, 1, ahead(0)) }
            ?: first?.let { i -> at(jn[i]!!, i - 1, (back(i) + 180) % 360) }
            ?: endNode(n - 1)?.let { at(it, n - 2, (back(n - 1) + 180) % 360) }
        var curName: String? = startWay?.name
        var curClass: WayClass? = startWay?.wayClass
        fun add(type: Maneuver, priority: Int, i: Int, name: String?, cls: WayClass?, same: Boolean, turn: Double, bearingOut: Double, speed: Double?, via: Int? = null) {
            out += Instruction(
                type, priority, cum[i], pts[i].lat, pts[i].lon, name, cls, same, turn.roundToInt(), ((bearingOut + 360) % 360).roundToInt() % 360,
                if (type == Maneuver.DEPART) 0.0 else leadM(mode, speed), via = via,
            )
        }
        if (0 in offAt) {
            add(Maneuver.OFF_NETWORK, Instruction.MAJOR, 0, null, null, false, 0.0, ahead(0), null)
            curName = null; curClass = null
        } else {
            add(Maneuver.DEPART, Instruction.MAJOR, 0, curName, curClass, false, 0.0, ahead(0), null)
        }

        for (i in 1 until n - 1) {
            val via = viaAt[i]
            if (i in offAt) {
                add(Maneuver.OFF_NETWORK, Instruction.MAJOR, i, null, null, false, norm(ahead(i) - back(i)), ahead(i), null, via)
                curName = null; curClass = null
                continue
            }
            if (via != null) {
                val turn = norm(ahead(i) - back(i))
                val reverses = abs(turn) >= SHARP_DEG
                add(Maneuver.VIA, if (reverses) Instruction.MAJOR else Instruction.NORMAL, i, curName, curClass, true, turn, ahead(i), null, via)
                continue
            }
            val all = jn[i] ?: continue
            val inBearing = back(i)
            val outBearing = ahead(i)
            val wayIn = at(all, i - 1, (inBearing + 180) % 360)
            val wayOut = at(all, i + 1, outBearing)
            val level = (wayOut ?: wayIn)?.level ?: 0
            val others = ArrayList<Branch>()
            for (b in all) {
                if (b === wayIn || b === wayOut || !b.enterable || b.level != level) continue
                // A parallel copy of a way already counted (two packs, or the way in or out itself).
                if (listOfNotNull(wayIn, wayOut).any { w -> w.name == b.name && diff(w.bearingDeg, b.bearingDeg) <= SAME_BRANCH_DEG }) continue
                if (others.any { o -> (o.nextLonE6 == b.nextLonE6 && o.nextLatE6 == b.nextLatE6) || (o.name == b.name && diff(o.bearingDeg, b.bearingDeg) <= SAME_BRANCH_DEG) }) continue
                others += b
            }
            if (others.isEmpty()) continue // no choice here
            val turn = norm(outBearing - inBearing)
            val dev = abs(turn)
            val devs = others.map { diff(it.bearingDeg, inBearing) }
            val rel = others.map { norm(it.bearingDeg - outBearing) }
            val outName = wayOut?.name
            val outClass = wayOut?.wayClass ?: curClass
            val same = if (outName != null) outName.equals(curName, ignoreCase = true) else curName == null && outClass == curClass
            // Rivals: the other ways that decide how this junction is said. The same road splitting (a way with the name
            // of the way out) and, by car, a lesser road leaving the road followed (a pull-out, a parking lot, a
            // campground loop) leave the junction a choice, but following the road there says nothing of them.
            val rival = others.map { b ->
                !(outName != null && b.name.equals(outName, ignoreCase = true)) &&
                    !(mode == TravelMode.DRIVE && same && outClass != null && b.wayClass.code < outClass.code)
            }
            val fork = others.indices.filter { rival[it] && abs(rel[it]) < FORK_DEG && devs[it] < FORK_AHEAD_DEG }.minByOrNull { abs(rel[it]) }
            val straighter = others.indices.any { rival[it] && devs[it] + MARGIN_DEG < dev }
            val straightest = others.indices.all { !rival[it] || devs[it] > dev + MARGIN_DEG }
            val type: Maneuver
            var priority: Int
            if (fork != null && dev < FORK_AHEAD_DEG) {
                type = if (rel[fork] > 0) Maneuver.KEEP_LEFT else Maneuver.KEEP_RIGHT
                priority = Instruction.MAJOR
            } else {
                val t = classify(turn)
                if (t == null) {
                    // Straight on: nothing to say on the same way (merged), or a new name.
                    if (outName == null || same) continue
                    type = Maneuver.CONTINUE
                    priority = Instruction.NORMAL
                } else {
                    type = t
                    priority = when {
                        straighter -> Instruction.MAJOR
                        t == Maneuver.SLIGHT_LEFT || t == Maneuver.SLIGHT_RIGHT -> Instruction.NORMAL
                        else -> Instruction.MAJOR
                    }
                    if (straightest && same) priority = minOf(Instruction.MINOR, priority + 1)
                }
            }
            add(type, priority, i, outName ?: if (same) curName else null, outClass, same, turn, outBearing, wayIn?.speedKmh?.takeIf { mode == TravelMode.DRIVE })
            if (outName != null) { curName = outName; curClass = outClass } else if (!same) { curName = null; curClass = outClass }
        }
        add(Maneuver.ARRIVE, Instruction.MAJOR, n - 1, curName, curClass, true, 0.0, back(n - 1), null)
        // A keep or slight turn that stays on the way, or a continue, right before the next instruction is said by it
        // (the next one names the way): a split before a turn, a short link in a triangle junction.
        val merge = mergeM(mode)
        val kept = out.filterIndexed { k, ins ->
            val next = out.getOrNull(k + 1)
            k == 0 || next == null || next.type == Maneuver.ARRIVE || next.alongM - ins.alongM >= merge ||
                !(ins.type == Maneuver.CONTINUE || ins.sameWay && ins.type in QUIET_IF_FOLLOWED)
        }
        // Leads never reach back past the previous instruction; stretches run to the next one.
        return kept.mapIndexed { k, ins ->
            ins.copy(
                leadM = if (k == 0) 0.0 else minOf(ins.leadM, ins.alongM - kept[k - 1].alongM),
                stretchM = if (k + 1 < kept.size) kept[k + 1].alongM - ins.alongM else 0.0,
            )
        }
    }

    /**
     * T108: a crow route's directions: one arrival at the end of the line (a straight line has nothing to turn at; its
     * legs' bearings are in the summary, [io.github.jasondorjeshort.dystopo.model.RouteSummary.bearings]). Empty for fewer
     * than two distinct points.
     */
    fun arrivalOnly(points: List<RoutePoint>): List<Instruction> {
        var along = 0.0
        var last: RoutePoint? = null
        var before: RoutePoint? = null
        for (p in points) {
            val q = last
            if (q != null && q.lat == p.lat && q.lon == p.lon) continue
            if (q != null) along += TrackStats.distanceM(q.lat, q.lon, p.lat, p.lon)
            before = q
            last = p
        }
        val end = last ?: return emptyList()
        val from = before ?: return emptyList()
        val bearing = TrackStats.bearingDeg(from.lat, from.lon, end.lat, end.lon)
        return listOf(
            Instruction(
                Maneuver.ARRIVE, Instruction.MAJOR, along, end.lat, end.lon, sameWay = true,
                bearingDeg = ((bearing + 360) % 360).roundToInt() % 360, leadM = minOf(leadM(TravelMode.CROW), along),
            ),
        )
    }

    /** Microdegrees, as the graph stores coordinates (route points from the graph come back exactly). */
    fun e6(deg: Double): Int = (deg * 1e6).roundToLong().toInt()
}

/**
 * T102: the English text of an [Instruction] (text first; a voice formatter can read the same fields later). No
 * distances here: [withDistance] puts one in front, in the user's units through [UnitFormat.distance]. T116: an
 * instruction with a "then" cue ([Instruction.then]) ends with the next manoeuvre in a few words ([cue]): "Turn left
 * onto Stevens Canyon Road, then turn right"; the next line names the way.
 */
object DirectionsText {
    fun of(i: Instruction, mode: TravelMode): String {
        val text = plain(i, mode)
        val next = i.then ?: return text
        return "$text, then ${cue(next, mode)}"
    }

    /**
     * T116: the cue's words for [next]: its manoeuvre without the way ("turn right", "keep left", "turn around",
     * "pass via point 2", "head north off the trails", "arrive").
     */
    fun cue(next: Instruction, mode: TravelMode): String = when (next.type) {
        Maneuver.SLIGHT_LEFT -> "bear left"
        Maneuver.SLIGHT_RIGHT -> "bear right"
        Maneuver.LEFT -> "turn left"
        Maneuver.RIGHT -> "turn right"
        Maneuver.SHARP_LEFT -> "turn sharp left"
        Maneuver.SHARP_RIGHT -> "turn sharp right"
        Maneuver.KEEP_LEFT -> "keep left"
        Maneuver.KEEP_RIGHT -> "keep right"
        Maneuver.U_TURN -> "turn around"
        Maneuver.ARRIVE -> "arrive"
        Maneuver.DEPART, Maneuver.CONTINUE, Maneuver.VIA, Maneuver.OFF_NETWORK -> plain(next, mode).replaceFirstChar { it.lowercase() }
    }

    private fun plain(i: Instruction, mode: TravelMode): String {
        val onto = if (i.sameWay) "to stay on ${i.label}" else "onto ${i.label}"
        return when (i.type) {
            Maneuver.DEPART -> if (i.name == null && i.wayClass == null) "Head ${Directions.compass(i.bearingDeg.toDouble())}"
            else "Head ${Directions.compass(i.bearingDeg.toDouble())} on ${i.label}"
            Maneuver.CONTINUE -> "Continue onto ${i.label}"
            Maneuver.SLIGHT_LEFT -> "Bear left $onto"
            Maneuver.SLIGHT_RIGHT -> "Bear right $onto"
            Maneuver.LEFT -> "Turn left $onto"
            Maneuver.RIGHT -> "Turn right $onto"
            Maneuver.SHARP_LEFT -> "Turn sharp left $onto"
            Maneuver.SHARP_RIGHT -> "Turn sharp right $onto"
            Maneuver.KEEP_LEFT -> "Keep left $onto"
            Maneuver.KEEP_RIGHT -> "Keep right $onto"
            Maneuver.U_TURN -> "Turn around $onto"
            Maneuver.VIA -> if (abs(i.turnDeg) >= Directions.SHARP_DEG) "Turn back at via point ${i.via ?: ""}".trimEnd() else "Pass via point ${i.via ?: ""}".trimEnd()
            Maneuver.OFF_NETWORK -> "Head ${Directions.compass(i.bearingDeg.toDouble())} " + if (mode == TravelMode.DRIVE) "off the roads" else "off the trails"
            Maneuver.ARRIVE -> "Arrive at the end of the route"
        }
    }

    /** "In 300 m, turn left onto Wonderland Trail". */
    fun withDistance(i: Instruction, distanceM: Double, units: ContourUnits, mode: TravelMode): String {
        val t = of(i, mode)
        return "In ${UnitFormat.distance(distanceM, units)}, " + t.replaceFirstChar { it.lowercase() }
    }
}
