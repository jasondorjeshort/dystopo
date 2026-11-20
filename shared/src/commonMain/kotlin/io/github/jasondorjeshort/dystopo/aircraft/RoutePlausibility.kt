package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.abs
import kotlin.math.max

/**
 * T311: whether a call sign's route from the table fits where the aircraft is and where it is going, checked every
 * poll (adsb.lol's own flag is decided once per call sign and cached; the finding). The rule, from the research of
 * 2026-11-20 for this task (adsb.lol's `plausible.py`: within max(50 nm, 20 % of the leg) of the leg, with no heading
 * test; tar1090 only shows that flag; the rest is the research's recommendation):
 *
 * - each leg A to B (consecutive airports) of length L is tried: the aircraft within max([MIN_TOLERANCE_M],
 *   [TOLERANCE_SHARE] x L) of the great circle A to B, and its along-track place between A and B with a slack of
 *   10 % of L plus 30 km at each end;
 * - airborne with a track and over [HEADING_MIN_KT], its track within [HEADING_DEG] of the bearing to B (within
 *   [NEAR_END_M] of either airport, [HEADING_NEAR_DEG]: turns, patterns, holds); not tested at all while arriving (within
 *   [TERMINAL_M] of B, not climbing faster than [TERMINAL_FPM]) or departing (within [TERMINAL_M] of A, climbing at least
 *   [TERMINAL_FPM]): approach and departure vectors point anywhere (round 3, the owner's report of 2026-11-20: a United
 *   flight overhead read "Route unknown"; on 833 live aircraft around four hubs, 12 of 15 heading rejections of UAL were
 *   such vectors, most of them wrong; a level aircraft near its origin still takes the test, since treating it as
 *   departing accepted a stale route);
 * - on the ground: within [GROUND_M] of A (taxiing out) or B (in);
 * - of the legs that pass, the one with the least cross-track distance for its tolerance is the current leg: its A is the
 *   origin, its B the destination.
 *
 * No leg passes: the route is not shown (the call sign alone, "Route unknown" on the card).
 */
object RoutePlausibility {
    const val MIN_TOLERANCE_M = 40_000.0
    const val TOLERANCE_SHARE = 0.15
    const val END_SLACK_SHARE = 0.10
    const val END_SLACK_M = 30_000.0
    const val HEADING_DEG = 60.0
    const val HEADING_NEAR_DEG = 90.0
    const val NEAR_END_M = 30_000.0
    const val HEADING_MIN_KT = 80.0
    const val GROUND_M = 15_000.0
    const val TERMINAL_M = 150_000.0
    const val TERMINAL_FPM = 500.0

    /** The leg the aircraft is on, or why none is. */
    data class Match(
        val route: FlightRoute,
        val leg: Int?,
        val crossTrackM: Double?,
        val toleranceM: Double?,
        val reason: String,
    ) {
        val plausible: Boolean get() = leg != null
        val origin: Airport? get() = leg?.let { route.airports[it] }
        val destination: Airport? get() = leg?.let { route.airports[it + 1] }

        fun json(): Map<String, Any?> = linkedMapOf(
            "airports" to route.airports.map { it.code }, "plausible" to plausible, "leg" to leg,
            "origin" to origin?.code, "destination" to destination?.code,
            "crossTrackKm" to crossTrackM?.let { Math2.r1(it / 1000) }, "toleranceKm" to toleranceM?.let { Math2.r1(it / 1000) },
            "reason" to reason,
        )
    }

    fun check(route: FlightRoute, lat: Double, lon: Double, trackDeg: Double?, groundSpeedKt: Double?, onGround: Boolean, verticalRateFpm: Double? = null, terminal: Boolean = true): Match {
        val a = route.airports
        if (a.size < 2) return Match(route, null, null, null, "one airport")
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        var bestXt = 0.0
        var bestTol = 0.0
        val reasons = ArrayList<String>()
        for (i in 0 until a.size - 1) {
            val p = a[i]
            val q = a[i + 1]
            val legM = AircraftGeo.distanceM(p.lat, p.lon, q.lat, q.lon)
            val toA = AircraftGeo.distanceM(p.lat, p.lon, lat, lon)
            val toB = AircraftGeo.distanceM(q.lat, q.lon, lat, lon)
            if (onGround) {
                if (toA <= GROUND_M || toB <= GROUND_M) {
                    val score = minOf(toA, toB) / GROUND_M
                    if (score < bestScore) { best = i; bestScore = score; bestXt = 0.0; bestTol = GROUND_M }
                } else reasons += "leg $i: on the ground away from its airports"
                continue
            }
            val tol = max(MIN_TOLERANCE_M, TOLERANCE_SHARE * legM)
            val xt = AircraftGeo.crossTrackM(p.lat, p.lon, q.lat, q.lon, lat, lon)
            val at = AircraftGeo.alongTrackM(p.lat, p.lon, q.lat, q.lon, lat, lon)
            val slack = END_SLACK_SHARE * legM + END_SLACK_M
            if (abs(xt) > tol) { reasons += "leg $i: ${(abs(xt) / 1000).toInt()} km off the leg"; continue }
            if (at < -slack || at > legM + slack) { reasons += "leg $i: beyond its ends"; continue }
            val arriving = terminal && toB <= TERMINAL_M && (verticalRateFpm == null || verticalRateFpm <= TERMINAL_FPM)
            val departing = terminal && toA <= TERMINAL_M && verticalRateFpm != null && verticalRateFpm >= TERMINAL_FPM
            if (trackDeg != null && (groundSpeedKt ?: 0.0) >= HEADING_MIN_KT && toB > 1000.0 && !arriving && !departing) {
                val want = AircraftGeo.bearingDeg(lat, lon, q.lat, q.lon)
                val limit = if (toA <= NEAR_END_M || toB <= NEAR_END_M) HEADING_NEAR_DEG else HEADING_DEG
                if (AircraftGeo.turnDeg(trackDeg, want) > limit) { reasons += "leg $i: heading away from ${q.code}"; continue }
            }
            val score = abs(xt) / tol
            if (score < bestScore) { best = i; bestScore = score; bestXt = xt; bestTol = tol }
        }
        return if (best != null) Match(route, best, abs(bestXt), bestTol, "fits leg $best")
        else Match(route, null, null, null, reasons.joinToString("; ").ifEmpty { "no leg" })
    }
}

internal object Math2 {
    fun r1(v: Double): Double = kotlin.math.round(v * 10) / 10.0
}
