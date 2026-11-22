package io.github.jasondorjeshort.dystopo.gnss

/**
 * T333: the watch behind the recording's "GPS signal unusually weak" line (the idea of 2026-11-19, the owner's answer:
 * build it now, cautiously). Fed the satellites and the terrain rim at each reading of a recording; it fires once when at
 * least [minAbove] satellites stand above the rim and every one of them reads under [weakBelow] dB-Hz for [holdMs] on end.
 * A stronger satellite, or fewer than [minAbove] above the rim, starts the count again; once fired it stays fired until
 * [reset] (a new recording). Pure and not thread-safe: the caller feeds it from one thread.
 *
 * A satellite counts by its strongest band; one the phone does not track (C/N0 0) does not count; one behind the rim is
 * [GnssHealth.behind] (the rim plus its margin; with no rim known, the flat horizon). Indoors hears few satellites, so the
 * count of [MIN_ABOVE] keeps it silent there; under a ridge most of the weak ones stand behind the rim. The 25 dB-Hz is
 * [GnssHealth.FAULT_T4], uncalibrated (to be checked against recorded tracks).
 */
class WeakSignalWatch(
    /** How long the weak run must last; a var so a debug route can shorten it for a test. */
    var holdMs: Long = HOLD_MS,
    val minAbove: Int = MIN_ABOVE,
    val weakBelow: Double = GnssHealth.FAULT_T4,
) {
    /** One reading: how many satellites stand above the rim, the strongest of them (dB-Hz), and whether all are weak. */
    data class Reading(val above: Int, val strongest: Double?, val weak: Boolean)

    /** When the weak run began (the caller's clock, ms), or null while the signal is not weak. */
    var since: Long? = null
        private set

    /** When it fired, or null. */
    var firedAt: Long? = null
        private set

    val fired: Boolean get() = firedAt != null

    /** The last reading fed, or null before any. */
    var last: Reading? = null
        private set

    /** Readings fed since the last [reset]. */
    var readings = 0L
        private set

    /** The weak run so far at [now], ms (0 while not weak). */
    fun runMs(now: Long): Long = since?.let { (now - it).coerceAtLeast(0) } ?: 0L

    /** The reading of [satellites] against the [rim] (angle by azimuth, degrees; null: the flat horizon). */
    fun read(satellites: List<Satellite>, rim: ((Double) -> Double)?): Reading {
        val best = satellites.filter { it.tracked }.groupBy { it.id }.values.map { s -> s.maxBy { it.cn0 } }
        val above = best.filter { !GnssHealth.behind(it.elevationDeg, it.azimuthDeg, rim) }
        val strongest = above.maxOfOrNull { it.cn0 }
        return Reading(above.size, strongest, above.size >= minAbove && strongest != null && strongest < weakBelow)
    }

    /**
     * Feeds one reading at [now] (ms on a monotonic clock); true only at the reading that fires. After firing it reads on
     * (for the route) but never fires again until [reset].
     */
    fun feed(now: Long, satellites: List<Satellite>, rim: ((Double) -> Double)?): Boolean = feed(now, read(satellites, rim))

    fun feed(now: Long, r: Reading): Boolean {
        readings++
        last = r
        if (!r.weak) {
            since = null
            return false
        }
        val start = since ?: now.also { since = it }
        if (fired || now - start < holdMs) return false
        firedAt = now
        return true
    }

    /** Starts again (a new recording, a pause): not fired, no run. */
    fun reset() {
        since = null
        firedAt = null
        last = null
        readings = 0
    }

    /** Starts the run again without forgetting that it fired (a pause: the line stays, the count restarts). */
    fun restartRun() {
        since = null
    }

    companion object {
        /** Five minutes on end (the owner's answer of 2026-11-21). */
        const val HOLD_MS = 5 * 60_000L

        /** At least this many satellites above the rim (wave 46's choice (k): four would let the indoors case fire). */
        const val MIN_ABOVE = 8

        /**
         * The raw condition, the rim not yet read: at least [minAbove] weak satellites above the flat horizon. The rim is
         * swept only while this holds. A rim only moves satellites behind it, so no rim can make the watch's condition
         * hold where this does not (it needs that many weak ones above the rim, and the rim stands at or above the flat
         * horizon); where it holds, the rim may still hide the strong ones that keep the flat reading from being weak.
         */
        fun raw(satellites: List<Satellite>, minAbove: Int = MIN_ABOVE, weakBelow: Double = GnssHealth.FAULT_T4): Boolean =
            satellites.filter { it.tracked }.groupBy { it.id }.values.map { s -> s.maxBy { it.cn0 } }
                .count { it.cn0 < weakBelow && !GnssHealth.behind(it.elevationDeg, it.azimuthDeg, null) } >= minAbove
    }
}
