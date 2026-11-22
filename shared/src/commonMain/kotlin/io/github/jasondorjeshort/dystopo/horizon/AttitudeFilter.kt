package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.exp

/**
 * T329: the Panorama's attitude filter, wave 46's choice (c), as a pure step function: [step] takes the state before, a
 * sample of the rotation vector ([Quat], before the display's rotation and the declination) and its time, and returns
 * the state after. Nothing here reads a clock.
 *
 * - **The filter** works on the rotation itself: each sample pulls the filtered attitude toward it along the shorter arc
 *   by 1 - exp(-dt / tau), a first-order lag. The time constant follows the speed of the turn: [TAU_MOVING_MS] while
 *   moving, [TAU_STILL_MS] when nearly still. The speed is read from the gap between the sample and the filtered
 *   attitude (a lag's steady gap is the speed times tau): at or under [STILL_GAP_DEG] tau is the still one, at or over
 *   [MOVING_GAP_DEG] the moving one, straight between. A gap over [MOVING_GAP_DEG] is a turn, never noise: a still
 *   phone's rotation vector shakes by tenths of a degree.
 * - **The drawn aim rests**: once the filtered attitude has stayed within [REST_DEG] of where its still spell began for
 *   [REST_MS], [State.resting] is true and [State.drawn] stays at the attitude it rested at, so the view is not rebuilt and
 *   the names are placed; it leaves when the filtered attitude moves more than [REST_DEG] from there.
 * - A sample more than [RESET_MS] after the one before (the view paused, the sensor stopped), or the first, starts again
 *   at that sample.
 *
 * The constants are first values (the survey of 2026-11-22 found none published for this use), checked on a simulated
 * attitude with jitter; a real phone tunes them later (`GET /panorama`'s figures).
 */
object AttitudeFilter {
    /** The time constant while the phone turns, ms: 90% of a quarter turn's step in 10 samples at 20 ms (184 ms). */
    const val TAU_MOVING_MS = 80.0

    /** The time constant when nearly still, ms: at 20 ms samples it cuts a still phone's jitter to about a fifth. */
    const val TAU_STILL_MS = 300.0

    /** At or under this gap between the sample and the filtered attitude the phone is nearly still, degrees (3.3 °/s at [TAU_STILL_MS]). */
    const val STILL_GAP_DEG = 1.0

    /** At or over this gap it is turning, degrees (about 13 °/s at [TAU_STILL_MS], 50 °/s at [TAU_MOVING_MS]). */
    const val MOVING_GAP_DEG = 4.0

    /** The drawn aim rests within this many degrees (choice (c)). */
    const val REST_DEG = 0.3

    /** ... once the filtered attitude has stayed within [REST_DEG] this long, ms (choice (c)). */
    const val REST_MS = 150.0

    /** A sample after a gap longer than this starts the filter again at it, ms. */
    const val RESET_MS = 500.0

    /**
     * The filter's state: [filtered] the attitude after the last sample at [timeNanos]; [anchor] where the current still
     * spell began (at [anchorNanos]) or, while [resting], the attitude it rests at; [drawn] what the view draws ([anchor]
     * while resting, else [filtered]); [tauMs] the time constant the last step used.
     */
    data class State(
        val filtered: Quat, val timeNanos: Long, val anchor: Quat, val anchorNanos: Long, val resting: Boolean, val tauMs: Double,
    ) {
        val drawn: Quat get() = if (resting) anchor else filtered
    }

    /** The time constant for a gap of [gapDeg] between the sample and the filtered attitude, ms. */
    fun tauMs(gapDeg: Double): Double = when {
        gapDeg <= STILL_GAP_DEG -> TAU_STILL_MS
        gapDeg >= MOVING_GAP_DEG -> TAU_MOVING_MS
        else -> TAU_STILL_MS + (TAU_MOVING_MS - TAU_STILL_MS) * (gapDeg - STILL_GAP_DEG) / (MOVING_GAP_DEG - STILL_GAP_DEG)
    }

    /** The state after [sample] at [timeNanos] (a sensor event's clock, nanoseconds) given [prev] (null: the first sample). */
    fun step(prev: State?, sample: Quat, timeNanos: Long): State {
        val q = sample.normalized()
        val dtMs = prev?.let { (timeNanos - it.timeNanos) / 1e6 }
        if (prev == null || dtMs == null || dtMs > RESET_MS || dtMs < 0) return State(q, timeNanos, q, timeNanos, false, TAU_STILL_MS)
        if (dtMs == 0.0) return prev
        val tau = tauMs(prev.filtered.angleDegTo(q))
        val filtered = prev.filtered.slerpToward(q, 1 - exp(-dtMs / tau))
        return if (prev.resting) {
            if (filtered.angleDegTo(prev.anchor) > REST_DEG) State(filtered, timeNanos, filtered, timeNanos, false, tau)
            else State(filtered, timeNanos, prev.anchor, prev.anchorNanos, true, tau)
        } else if (filtered.angleDegTo(prev.anchor) > REST_DEG) {
            State(filtered, timeNanos, filtered, timeNanos, false, tau)
        } else if ((timeNanos - prev.anchorNanos) / 1e6 >= REST_MS) {
            // Rests at the attitude it has now, so the drawing does not step back to where the spell began.
            State(filtered, timeNanos, filtered, timeNanos, true, tau)
        } else {
            State(filtered, timeNanos, prev.anchor, prev.anchorNanos, false, tau)
        }
    }
}
