package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.RidgeRule
import io.github.jasondorjeshort.dystopo.routing.ShadeText
import kotlin.math.max
import kotlin.math.round

/**
 * T196: the numbers of [WayDown.find].
 *
 * - [dropM] 100 m, **the app's own constant**, not a figure from any source and never a safe distance: the survey of
 *   2026-10-31 (a weather service, two alpine clubs, a mountaineering council, a wilderness-medicine society) names
 *   exposed ground only in words (a summit, a ridge or crest, the highest point around) and tells the walker to go
 *   down, into a gully or a depression; none gives a height. The point the card names is the first one along the line
 *   at least this much lower than the ground where the walker stands and not on a ridge by [RidgeRule]. Why 100 m:
 *   it is several times both the ridge rule's own 15 m and the elevation model's few metres of vertical error, so the
 *   point is plainly lower ground and not noise or the crest's own shoulder (a crest that passes the rule stands 15 m
 *   or more above its 150 m ring; 100 m below it is beneath the shoulder of every crest the Paradise maps marked); and
 *   it is short enough that the answer is the first few minutes of the descent (at the standard pace 100 m of descent
 *   on a trail is some 10 to 25 minutes), not the walk to the valley floor. Owner-adjustable here, not a setting.
 * - [sampleM] 25 m between the points read along each line (T195's [io.github.jasondorjeshort.dystopo.routing.ExposureParams.sampleM]:
 *   shorter than that reads the same ground twice at z14, longer skips a short drop off a crest).
 * - [searchM] 5 km along each line: at the standard pace that is about an hour of walking; a way down further than
 *   that is not "the quickest way off" the ridge and the card names none in that direction (a long crest walk, a
 *   plateau). It bounds the work too: 200 points of 17 elevation reads each, well under a frame's worth on the phone.
 */
data class WayDownParams(
    val dropM: Double = WayDown.DROP_M,
    val sampleM: Double = WayDown.SAMPLE_M,
    val searchM: Double = WayDown.SEARCH_M,
) {
    init {
        require(dropM > 0 && sampleM > 0 && searchM > 0) { "bad way-down parameters" }
    }
}

/** T196: which way along the lines the walker has: back along the way they came, or on along the navigated route. */
enum class WayDownDirection(val key: String, val words: String) {
    BACK("back", "along the way back"),
    AHEAD("ahead", "along the route"),
}

/**
 * T196: the first point along [direction] that is [WayDownParams.dropM] lower than the walker's ground and off the
 * ridge: [distanceM] along the line from the walker, [timeS] at the clock's pace ([TurnPace]), the point and its ground,
 * [dropM] how much lower it is, its [tpiM], and [stretch] the line from the walker to it (what "Show the way down" draws).
 */
class WayDownExit(
    val direction: WayDownDirection,
    val distanceM: Double,
    val timeS: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val dropM: Double,
    val tpiM: Double,
    val stretch: List<NavPoint>,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "direction" to direction.key, "distanceM" to r1(distanceM), "timeS" to r1(timeS), "minutes" to round(timeS / 6) / 10,
        "lat" to r6(lat), "lon" to r6(lon), "eleM" to r1(eleM), "dropM" to r1(dropM), "tpiM" to r1(tpiM),
        "stretchPoints" to stretch.size,
    )
}

/** T196: how the search along one line ended: an exit, the window's end, the line's end, or no line at all. */
enum class WayDownSearch(val key: String) {
    FOUND("found"),
    WINDOW("noneWithinWindow"),
    LINE_END("noneBeforeLineEnd"),
    NO_LINE("noLine"),
}

/**
 * T196: the answer at one position. [exposed] when the walker's ground is on a ridge or a summit by [RidgeRule] ([tpiM]
 * above its threshold); then [exits] the first lower ground off the ridge along each line, the quicker first, and
 * [searches] how each line's search ended. Below a ridge (or without elevation data, [noData]) there are no exits.
 */
class WayDownResult(
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val tpiM: Double,
    val exposed: Boolean,
    val exits: List<WayDownExit>,
    val searches: Map<WayDownDirection, WayDownSearch>,
    val pace: String,
    val params: WayDownParams,
) {
    val noData: Boolean get() = eleM.isNaN() || tpiM.isNaN()

    /** The quicker way down, or null (below a ridge, or none found within the window). */
    val quickest: WayDownExit? get() = exits.firstOrNull()

    fun json(): Map<String, Any?> = linkedMapOf(
        "exposed" to exposed, "noData" to noData,
        "lat" to r6(lat), "lon" to r6(lon), "eleM" to if (eleM.isNaN()) null else r1(eleM), "tpiM" to if (tpiM.isNaN()) null else r1(tpiM),
        "rule" to linkedMapOf("ringM" to RidgeRule.RING_M, "thresholdM" to RidgeRule.THRESHOLD_M, "dropM" to params.dropM, "sampleM" to params.sampleM, "searchM" to params.searchM),
        "pace" to pace,
        "exits" to exits.map { it.json() },
        "searches" to searches.entries.associate { (d, s) -> d.key to s.key },
        "card" to WayDownText.cardLines(this), "spoken" to WayDownText.spoken(this),
    )
}

/**
 * T196, after a storm warning: the quickest way off exposed ground. From the walker's position, the way back (T180's
 * retrace path: the recorded line from where the walker is on it back to its start, or the navigated route behind) and,
 * when navigating, the route ahead, it answers whether the walker is exposed, on a ridge or a summit by T195's
 * [RidgeRule] (the open-ground test by mapped woods is a second pass, not built: the critic and the survey found the
 * pack's `wood` polygons too patchy and there is no polygon reader), and if so the first point along each line that is
 * [WayDownParams.dropM] lower and off the ridge, with the time to reach it at the turn-around clock's pace ([TurnPace],
 * each step timed by its climb and descent on the elevation model, as [io.github.jasondorjeshort.dystopo.routing.ExposureAlong]
 * times a route). Terrain facts only ([WayDownText]): where lower ground is, never where it is safe.
 *
 * The sampler should read the pack at z14, as [RidgeRule] wants (`DemElevation(pyramid, zoom = 14)`).
 */
object WayDown {
    /** The app's own figure (see [WayDownParams.dropM]): not a safe distance, and no source gives one. */
    const val DROP_M = 100.0
    const val SAMPLE_M = 25.0
    const val SEARCH_M = 5000.0

    /**
     * The answer at ([lat], [lon]): [back] and [ahead] are lines starting at (or near) the walker and running away from
     * them ([behind] and [onward] cut them from a whole line); either may be null or shorter than two points.
     */
    fun find(
        dem: ElevationSampler,
        lat: Double,
        lon: Double,
        back: List<NavPoint>?,
        ahead: List<NavPoint>?,
        pace: TurnPace,
        params: WayDownParams = WayDownParams(),
    ): WayDownResult {
        val ele = dem.elevation(lat, lon)
        val tpi = RidgeRule.tpiM(dem, lat, lon, ele)
        val exposed = RidgeRule.isRidge(tpi)
        val searches = LinkedHashMap<WayDownDirection, WayDownSearch>()
        val exits = ArrayList<WayDownExit>()
        if (exposed) {
            for ((dir, line) in listOf(WayDownDirection.BACK to back, WayDownDirection.AHEAD to ahead)) {
                if (line == null || line.isEmpty()) { searches[dir] = WayDownSearch.NO_LINE; continue }
                val (exit, how) = search(dem, lat, lon, ele, line, dir, pace, params)
                searches[dir] = how
                exit?.let { exits += it }
            }
        }
        exits.sortBy { it.timeS }
        return WayDownResult(lat, lon, ele, tpi, exposed, exits, searches, pace.label, params)
    }

    /** Walks [line] (the walker's position put first) in steps of [WayDownParams.sampleM] for the first exit. */
    private fun search(
        dem: ElevationSampler,
        lat: Double,
        lon: Double,
        startEle: Double,
        line: List<NavPoint>,
        dir: WayDownDirection,
        pace: TurnPace,
        params: WayDownParams,
    ): Pair<WayDownExit?, WayDownSearch> {
        val pts = ArrayList<NavPoint>(line.size + 1)
        pts += NavPoint(lat, lon, startEle)
        for (p in line) if (TrackStats.distanceM(pts.last().lat, pts.last().lon, p.lat, p.lon) > 0.5) pts += p
        if (pts.size < 2) return null to WayDownSearch.NO_LINE
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + TrackStats.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        val total = cum.last()
        val end = minOf(total, params.searchM)
        var seg = 0
        fun at(m: Double): Pair<Double, Double> {
            while (seg < pts.size - 2 && cum[seg + 1] < m) seg++
            val a = pts[seg]
            val b = pts[seg + 1]
            val d = cum[seg + 1] - cum[seg]
            val t = if (d > 0) ((m - cum[seg]) / d).coerceIn(0.0, 1.0) else 0.0
            return (a.lat + (b.lat - a.lat) * t) to (a.lon + (b.lon - a.lon) * t)
        }
        var prevM = 0.0
        var prevEle = startEle
        var timeS = 0.0
        var m = 0.0
        while (m < end) {
            m = minOf(m + params.sampleM, end)
            val (la, lo) = at(m)
            val e = dem.elevation(la, lo)
            val dz = if (e.isNaN() || prevEle.isNaN()) 0.0 else e - prevEle
            timeS += pace.timeS(m - prevM, max(0.0, dz), max(0.0, -dz))
            prevM = m
            if (!e.isNaN()) prevEle = e
            if (e.isNaN() || startEle.isNaN() || startEle - e < params.dropM) continue
            val tpi = RidgeRule.tpiM(dem, la, lo, e)
            if (RidgeRule.isRidge(tpi)) continue
            val stretch = ArrayList<NavPoint>()
            for (i in pts.indices) if (cum[i] < m) stretch += pts[i] else break
            stretch += NavPoint(la, lo, e)
            return WayDownExit(dir, m, timeS, la, lo, e, startEle - e, tpi, stretch) to WayDownSearch.FOUND
        }
        return null to if (total > params.searchM) WayDownSearch.WINDOW else WayDownSearch.LINE_END
    }

    /** The part of [points] from [alongM] back to its start, reversed (the walker's place first): the way back. */
    fun behind(points: List<NavPoint>, alongM: Double): List<NavPoint> {
        val path = pathOf(points) ?: return points.toList()
        val a = alongM.coerceIn(0.0, path.totalM)
        val out = ArrayList<NavPoint>()
        out += path.pointAt(a)
        for (i in path.points.indices.reversed()) if (path.cumM[i] < a) out += path.points[i]
        return out
    }

    /** The part of [points] from [alongM] on to its end (the walker's place first): the route ahead. */
    fun onward(points: List<NavPoint>, alongM: Double): List<NavPoint> {
        val path = pathOf(points) ?: return points.toList()
        val a = alongM.coerceIn(0.0, path.totalM)
        val out = ArrayList<NavPoint>()
        out += path.pointAt(a)
        for (i in path.points.indices) if (path.cumM[i] > a) out += path.points[i]
        return out
    }

    /** A [NavPath] over [points], or null with fewer than two distinct points. */
    private fun pathOf(points: List<NavPoint>): NavPath? =
        if (points.size < 2) null else runCatching { NavPath("line", points) }.getOrNull()
}

/**
 * T196: the words (pure; `WayDownTest`; ADR 0031, the owner adjusts them). The survey of 2026-10-31: terrain words only,
 * the walk to lower ground and its time; never "safe", "shelter" or "danger", and no lightning advice in the app's voice.
 */
object WayDownText {
    /** The card's action that draws the quicker stretch on the map. */
    const val ACTION = "Show the way down"

    /** The action once the stretch is drawn. */
    const val ACTION_HIDE = "Hide the way down"

    /** The help text (the survey's, word for word). */
    const val HELP = "Shows where the ground is lower than the ridge you are on, and how long the walk is. " +
        "It does not say where lower or wooded ground is safe from lightning."

    /**
     * The card's lines, the quicker direction first: "25 min down to lower ground along the way back", then "or 40 min
     * along the route"; empty below a ridge or with no way down within the window.
     */
    fun cardLines(r: WayDownResult): List<String> {
        if (!r.exposed) return emptyList()
        return r.exits.mapIndexed { i, x ->
            if (i == 0) "${ShadeText.minutes(x.timeS / 60)} down to lower ground ${x.direction.words}"
            else "or ${ShadeText.minutes(x.timeS / 60)} ${x.direction.words}"
        }
    }

    /** The spoken clause after the warning's line: "25 minutes down to lower ground along the way back." (the quicker only). */
    fun spoken(r: WayDownResult): String? {
        val x = r.quickest?.takeIf { r.exposed } ?: return null
        return "${ShadeText.minutesSpoken(x.timeS / 60)} down to lower ground ${x.direction.words}."
    }
}

