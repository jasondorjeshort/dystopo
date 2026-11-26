package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * T365: how far a guessed position is from where the aircraft really was (the Next of
 * `docs/findings/2026-11-23-flights-guessed-between-polls-at-slow-refresh.md`). The guess is the tracker's own
 * ([AircraftTracker.moveUp]: along the reported track at the reported ground speed); the truth is a later real fix of the
 * same aircraft. Pure: polls and times are passed in; the devtool's `aircraft --report` reads a recorded folder into it.
 */
object GuessError {
    /** One real position of one aircraft: [atMs] when it was there (the poll's time minus `seen_pos`). */
    data class Fix(val hex: String, val atMs: Long, val report: AircraftReport)

    /** One guess compared with the truth: the phase of the fix it was made from, the error, and the guess's age. */
    data class Sample(val hex: String, val phase: FlightPhase, val errorM: Double, val ageS: Double)

    /** Median, p90 and p99 (nearest rank) of [n] samples from [aircraft] aircraft, in metres. */
    data class Stats(val n: Int, val aircraft: Int, val medianM: Double, val p90M: Double, val p99M: Double)

    /** Where the tracker draws [from] after [ageS] seconds, moved for at most [capS] (the tracker's cap). */
    fun guess(from: AircraftReport, ageS: Double, capS: Double): Pair<Double, Double> = AircraftTracker.moveUp(from, ageS, capS)

    /**
     * The distance in metres between the guess made from [from] (its position at [fromAtMs]) and the real position
     * ([toLat], [toLon]) at [toAtMs], the guess moved for at most [capS] seconds.
     */
    fun errorM(from: AircraftReport, fromAtMs: Long, toLat: Double, toLon: Double, toAtMs: Long, capS: Double): Double {
        val (lat, lon) = guess(from, (toAtMs - fromAtMs) / 1000.0, capS)
        return AircraftGeo.distanceM(lat, lon, toLat, toLon)
    }

    /** The angle in degrees an error of [errorM] subtends for an observer [rangeM] from the aircraft (the error across the line of sight). */
    fun angleDeg(errorM: Double, rangeM: Double): Double = atan2(errorM, rangeM) * 180.0 / PI

    /**
     * Each poll's fixes, as the tracker would take them: per aircraft, a fix only when its position changed (a position
     * the feed repeats with a growing `seen_pos` is the same fix), in time order.
     */
    fun tracks(polls: List<AircraftPoll>): Map<String, List<Fix>> {
        val out = LinkedHashMap<String, MutableList<Fix>>()
        for (p in polls.sortedBy { it.atMs }) for (r in p.aircraft) {
            val at = p.atMs - (r.seenPosS * 1000).roundToLong()
            val list = out.getOrPut(r.hex) { ArrayList() }
            val last = list.lastOrNull()
            if (last != null && (at <= last.atMs || (last.report.lat == r.lat && last.report.lon == r.lon))) continue
            list += Fix(r.hex, at, r)
        }
        return out
    }

    /**
     * From each fix, the guess [horizonS] seconds ahead compared with the real fix nearest that time (within
     * [toleranceS]); fixes with none are skipped. The tracker's cap is lifted to the horizon plus the tolerance, so the
     * guess runs to the truth's own time (with the default 30 s cap every longer row would read the 30 s guess).
     */
    fun samples(tracks: Map<String, List<Fix>>, horizonS: Double, toleranceS: Double): List<Sample> {
        val out = ArrayList<Sample>()
        val cap = horizonS + toleranceS
        for ((hex, fixes) in tracks) {
            for ((i, f) in fixes.withIndex()) {
                val target = f.atMs + (horizonS * 1000).roundToLong()
                var best: Fix? = null
                for (j in i + 1 until fixes.size) {
                    val g = fixes[j]
                    if (g.atMs > target + toleranceS * 1000) break
                    if (abs(g.atMs - target) <= toleranceS * 1000 && (best == null || abs(g.atMs - target) < abs(best.atMs - target))) best = g
                }
                val t = best ?: continue
                out += Sample(hex, FlightPhase.of(f.report), errorM(f.report, f.atMs, t.report.lat, t.report.lon, t.atMs, cap), (t.atMs - f.atMs) / 1000.0)
            }
        }
        return out
    }

    /** The [q] quantile (0..1) of [sorted] by nearest rank, or NaN for none. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun stats(samples: List<Sample>): Stats {
        val e = samples.map { it.errorM }.sorted()
        return Stats(e.size, samples.map { it.hex }.toSet().size, quantile(e, 0.5), quantile(e, 0.9), quantile(e, 0.99))
    }
}

/**
 * T365: the phase a fix is binned by (the finding's rows): on the ground; climbing or descending (500 ft a minute or
 * more); terminal (level below 10,000 ft or slower than 200 kt); cruise (level, at or above 10,000 ft, 200 kt or faster).
 */
enum class FlightPhase(val json: String) {
    GROUND("ground"),
    CLIMB_DESCENT("climbOrDescent"),
    TERMINAL("terminal"),
    CRUISE("cruise");

    companion object {
        const val CLIMB_FPM = 500.0
        const val LOW_FT = 10_000.0
        const val SLOW_KT = 200.0

        fun of(r: AircraftReport): FlightPhase {
            if (r.onGround) return GROUND
            if (abs(r.verticalRateFpm ?: 0.0) >= CLIMB_FPM) return CLIMB_DESCENT
            val alt = r.altBaroFt ?: r.altGeomFt
            val gs = r.groundSpeedKt
            if ((alt != null && alt < LOW_FT) || (gs != null && gs < SLOW_KT)) return TERMINAL
            return CRUISE
        }
    }
}
