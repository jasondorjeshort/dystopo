package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.roundToInt

/** T314: which flights show route lines on the map (the "Flight tracking: Route lines" setting, ADR 0044). */
enum class RouteLinesMode(val key: String, val label: String) {
    /** Only the tapped flight: its seen track, the dotted estimate before it and the dashed rest (T311's lines, then T314's). */
    SELECTED("selected", "Selected flight"),

    /** Also one faint line origin > aircraft > destination for every other flight whose route fits. */
    ALL("all", "All flights");

    companion object {
        val DEFAULT = SELECTED
        fun of(s: String): RouteLinesMode? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}

/**
 * T314: the route lines the Flights layer draws, decided here (pure; the layer only draws the output):
 *
 * - [estimatedPast]: the path before the app saw the plane, a straight (great-circle) estimate from the origin airport of
 *   the current leg to the first point of the kept history, where the solid track starts, so the two always join. The
 *   tracker keeps at most [AircraftRules.HISTORY_MAX] points and forgets an aircraft after [AircraftRules.DROP_S], so that
 *   point is where the app's track starts, not where the aircraft was first ever seen.
 * - [plan]: under [RouteLinesMode.SELECTED] the selected flight's lines only; under [RouteLinesMode.ALL] also one faint
 *   line origin > current position > destination for each other flight with a plausible route, at most [CAP] of them,
 *   the nearest the view's centre.
 */
object RouteLines {
    /**
     * Faint lines drawn at most (the flights nearest the view's centre). Fifty because the cost that matters is legibility:
     * around a hub at a wide view the feed answers hundreds of aircraft (833 around four hubs in T311's round 3), and past a
     * few dozen the faint lines merge into a web that hides the map and the selected flight; the vertices (50 lines of at
     * most [MAX_POINTS]) are far below what one GeoJSON source carries without effort.
     */
    const val CAP = 50

    /** Points per planned line, scaled with its length ([points]). */
    const val MIN_POINTS = 16
    const val MAX_POINTS = 32

    /** A line gains one point per this many metres over [MIN_POINTS] ([MAX_POINTS] from 4,000 km). */
    const val METRES_PER_POINT = 250_000.0

    /** The selected flight's remaining route keeps T311's 64 segments (it is unchanged by T314). */
    const val REMAINING_SEGMENTS = 64

    /**
     * The points of a line [lengthM] long: [MIN_POINTS] for a short one, one more each [METRES_PER_POINT], at most
     * [MAX_POINTS] (32 points over 4,000 km is a vertex every 130 km, where a great circle's chord sags by well under a
     * line's width at the zooms that show the whole line; 16 on a short line keeps the curve smooth when zoomed in).
     */
    fun points(lengthM: Double): Int = (MIN_POINTS + (lengthM / METRES_PER_POINT).roundToInt()).coerceIn(MIN_POINTS, MAX_POINTS)

    /** Why [estimatedPast] drew nothing, or null when it drew a line (for `GET /aircraft`). */
    fun estimatedPastWhyNot(match: RoutePlausibility.Match?, history: List<TrackPoint>): String? {
        if (match == null) return "no route"
        if (!match.plausible) return "route not plausible"
        val o = match.origin ?: return "no origin"
        val d = match.destination ?: return "no destination"
        val first = history.firstOrNull() ?: return "no track"
        val toFirst = AircraftGeo.distanceM(o.lat, o.lon, first.lat, first.lon)
        if (toFirst <= RoutePlausibility.GROUND_M) return "track starts at the origin"
        val legM = AircraftGeo.distanceM(o.lat, o.lon, d.lat, d.lon)
        val slack = RoutePlausibility.END_SLACK_SHARE * legM + RoutePlausibility.END_SLACK_M
        if (AircraftGeo.alongTrackM(o.lat, o.lon, d.lat, d.lon, first.lat, first.lon) < -slack) return "track starts on an earlier leg"
        // An A-B-A route lies on one great circle both ways, so a track begun on the way out to B projects onto the leg
        // back from B as well; it shows as a later point nearer the origin (B) than the first one: the track went through
        // the origin after it began, so it began on an earlier leg (and the solid track already covers the origin).
        val nearest = history.minOf { AircraftGeo.distanceM(o.lat, o.lon, it.lat, it.lon) }
        if (nearest < toFirst - RoutePlausibility.GROUND_M) return "track passed the origin after it began"
        return null
    }

    /**
     * The estimated path before the app saw the aircraft: the great circle from the current leg's origin airport to the
     * first point of [history] (its ends exactly the airport and that point), [points] of it; null when the route is not plausible,
     * when that point lies within [RoutePlausibility.GROUND_M] of the origin (the solid track covers it), or when the track
     * began on an earlier leg ([estimatedPastWhyNot]).
     */
    fun estimatedPast(match: RoutePlausibility.Match?, history: List<TrackPoint>): List<Pair<Double, Double>>? {
        if (estimatedPastWhyNot(match, history) != null) return null
        val o = match!!.origin!!
        val first = history.first()
        val n = points(AircraftGeo.distanceM(o.lat, o.lon, first.lat, first.lon))
        val gc = AircraftGeo.greatCircle(o.lat, o.lon, first.lat, first.lon, n - 1)
        return listOf(o.lat to o.lon) + gc.subList(1, gc.size - 1) + (first.lat to first.lon)
    }

    /**
     * One faint line origin > ([lat], [lon]) > destination of [match]'s current leg, [points] of it in all (the two great
     * circles share the aircraft's point, which lies on it exactly); null when the route is not plausible.
     */
    fun faintLine(match: RoutePlausibility.Match?, lat: Double, lon: Double): List<Pair<Double, Double>>? {
        if (match?.plausible != true) return null
        val o = match.origin ?: return null
        val d = match.destination ?: return null
        val la = AircraftGeo.distanceM(o.lat, o.lon, lat, lon)
        val lb = AircraftGeo.distanceM(lat, lon, d.lat, d.lon)
        val n = points(la + lb)
        val segs = n - 1
        val sa = if (la + lb <= 0.0) 1 else (segs * la / (la + lb)).roundToInt().coerceIn(1, segs - 1)
        val sb = segs - sa
        val a = AircraftGeo.greatCircle(o.lat, o.lon, lat, lon, sa)
        val b = AircraftGeo.greatCircle(lat, lon, d.lat, d.lon, sb)
        return listOf(o.lat to o.lon) + a.subList(1, a.size - 1) + (lat to lon) + b.subList(1, b.size - 1) + (d.lat to d.lon)
    }

    /** One flight's faint line. */
    data class Faint(val hex: String, val points: List<Pair<Double, Double>>)

    /** The lines of one plan: the selected flight's estimated past and remaining route, the faint lines, and the counts. */
    data class Plan(
        val mode: RouteLinesMode,
        val selected: String?,
        val estimatedPast: List<Pair<Double, Double>>?,
        val estimatedFrom: Airport?,
        val estimatedPastWhyNot: String?,
        val remaining: List<Pair<Double, Double>>?,
        val faint: List<Faint>,
        /** Flights with route lines drawn: the selected one when it has any, and each faint line. */
        val drawn: Int,
        /** Flights with a plausible route left without a faint line by [CAP]. */
        val capped: Int,
    ) {
        companion object {
            val NONE = Plan(RouteLinesMode.DEFAULT, null, null, null, null, null, emptyList(), 0, 0)
        }
    }

    /**
     * The plan for [aircraft] (as the layer draws them) under [mode]: [selected]'s estimated past (from [history], its kept
     * track) and remaining great circle to its destination; under [RouteLinesMode.ALL] the faint lines of every other
     * flight with a plausible route, the [cap] nearest ([centreLat], [centreLon]) (the list's order when null).
     */
    fun plan(
        aircraft: List<AircraftNow>,
        mode: RouteLinesMode,
        selected: String?,
        history: List<TrackPoint>,
        centreLat: Double?,
        centreLon: Double?,
        cap: Int = CAP,
    ): Plan {
        val sel = selected?.let { h -> aircraft.firstOrNull { it.hex == h } }
        var past: List<Pair<Double, Double>>? = null
        var from: Airport? = null
        var why: String? = null
        var remaining: List<Pair<Double, Double>>? = null
        if (sel != null) {
            why = estimatedPastWhyNot(sel.route, history)
            past = estimatedPast(sel.route, history)
            if (past != null) from = sel.route?.origin
            remaining = sel.route?.takeIf { it.plausible }?.destination?.let { d -> AircraftGeo.greatCircle(sel.lat, sel.lon, d.lat, d.lon, REMAINING_SEGMENTS) }
        }
        var faint = emptyList<Faint>()
        var capped = 0
        if (mode == RouteLinesMode.ALL) {
            val others = aircraft.filter { it.hex != sel?.hex && it.route?.plausible == true }
            val ordered = if (centreLat == null || centreLon == null) others
            else others.sortedBy { AircraftGeo.distanceM(centreLat, centreLon, it.lat, it.lon) }
            val kept = ordered.take(cap.coerceAtLeast(0))
            capped = ordered.size - kept.size
            faint = kept.mapNotNull { a -> faintLine(a.route, a.lat, a.lon)?.let { Faint(a.hex, it) } }
        }
        val selDrawn = if (sel != null && (past != null || remaining != null || history.isNotEmpty())) 1 else 0
        return Plan(mode, sel?.hex, past, from, if (sel == null) null else why, remaining, faint, selDrawn + faint.size, capped)
    }
}
