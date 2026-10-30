package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity

/**
 * The live readout's fixed numbers per activity (T160), from the readout idea's research (2026-10-25,
 * `docs/ideas/2026-10-25-live-velocity-readout-by-travel-mode.md`) where it gives one, derived where it does not
 * (each says which). Times are seconds, speeds m/s.
 */
object ReadoutTuning {
    /**
     * The speed window by damping: foot 10 to 30 s and bike 3 to 5 s (research), road and rail 2 to 5 s and air
     * 1 to 5 s (a ground speed that follows the vehicle), boat 5 to 30 s (marine damping, low to high).
     */
    fun speedWindowS(activity: Activity, damping: Damping): Double = pick(damping, when (activity) {
        Activity.FOOT -> W(10.0, 20.0, 30.0)
        Activity.BIKE -> W(3.0, 4.0, 5.0)
        Activity.ROAD, Activity.RAIL -> W(2.0, 3.0, 5.0)
        Activity.BOAT -> W(5.0, 15.0, 30.0)
        Activity.AIR -> W(1.0, 3.0, 5.0)
    })

    /** The course window by damping (derived: about half the speed window; boats yaw, so theirs is the same as speed). */
    fun courseWindowS(activity: Activity, damping: Damping): Double = pick(damping, when (activity) {
        Activity.FOOT -> W(5.0, 10.0, 20.0)
        Activity.BIKE -> W(2.0, 3.0, 5.0)
        Activity.ROAD, Activity.RAIL -> W(1.0, 2.0, 4.0)
        Activity.BOAT -> W(5.0, 15.0, 30.0)
        Activity.AIR -> W(1.0, 2.0, 4.0)
    })

    /** The climb rate's display lag (an aircraft vertical speed indicator's 5 to 10 s, research), by damping. */
    fun climbLagS(damping: Damping): Double = pick(damping, W(5.0, 7.0, 10.0))

    /** Without the fix's own speed, distance over time is used over at least this long (research: "only fair over 30 s or more"). */
    const val POSITION_WINDOW_S = 30.0

    /**
     * Along-route speed (distance along the navigated line over a long window): foot 60 s, bike 20 s (derived:
     * long enough that the route's vertex spacing and the projection's jitter of a few metres stay under 5%);
     * null where the fix speed is used instead.
     */
    fun routeWindowS(activity: Activity): Double? = when (activity) {
        Activity.FOOT -> 60.0
        Activity.BIKE -> 20.0
        else -> null
    }

    /** Standing still: a dash below [stopMps], speed again above [goMps] (research: 0.3 to 0.5 m/s on foot, with hysteresis). */
    data class Still(val stopMps: Double, val goMps: Double)

    fun still(activity: Activity): Still = when (activity) {
        Activity.FOOT -> Still(0.3, 0.5)
        Activity.BIKE -> Still(0.5, 1.0)
        Activity.BOAT -> Still(0.3, 0.5)
        Activity.ROAD, Activity.RAIL, Activity.AIR -> Still(0.5, 1.5)
    }

    /** The still decision reads the mean fix speed over this many seconds (derived: fast enough that a stop shows within a few seconds). */
    const val STILL_WINDOW_S = 3.0

    /**
     * The climb rate is hidden below [hideMps] and shown again above [showMps] (research: "may be left out when near
     * zero"; derived: 50 and 100 m/h on the ground, 100 and 200 ft/min in the air), and always hidden when it is
     * under twice its own uncertainty.
     */
    data class ClimbGate(val hideMps: Double, val showMps: Double)

    fun climbGate(activity: Activity): ClimbGate =
        if (activity == Activity.AIR) ClimbGate(100 * FT / 60, 200 * FT / 60) else ClimbGate(50.0 / 3600, 100.0 / 3600)

    /**
     * The elevation filter's vertical acceleration noise (m²/s³). With the barometer (derived: the climb rate's
     * effective window about 10 to 15 s on foot, so the 7 s display lag makes the research's 20 to 30 s); on GNSS
     * alone larger, so the height follows a summit or a pass within about a minute instead of overshooting, the
     * climb rate then mostly hidden by its own uncertainty. Road, rail and air never use the barometer.
     */
    fun verticalNoise(activity: Activity, fused: Boolean): Double = when (activity) {
        Activity.FOOT, Activity.BOAT -> if (fused) 1e-5 else 3e-4
        Activity.BIKE -> if (fused) 1e-4 else 1e-3
        Activity.ROAD, Activity.RAIL -> 0.006
        Activity.AIR -> 0.1
    }

    /** The prior spread of the vertical speed at start (m/s). */
    fun verticalSpeedPriorMps(activity: Activity): Double = when (activity) {
        Activity.FOOT, Activity.BOAT -> 0.5
        Activity.BIKE -> 1.0
        Activity.ROAD, Activity.RAIL -> 3.0
        Activity.AIR -> 10.0
    }

    /**
     * How long a GNSS height error stays correlated (s). With the barometer, one GNSS update per this long (research:
     * one per 30 s); on GNSS alone every fix is used with its variance scaled by this over the fix interval, the same
     * information spread smoothly. Derived: 30 s at walking and boat speeds, 10 s in vehicles and the air, where the
     * multipath changes as fast as the ground moves past.
     */
    fun gnssCorrelationS(activity: Activity): Double = when (activity) {
        Activity.FOOT, Activity.BIKE, Activity.BOAT -> 30.0
        Activity.ROAD, Activity.RAIL, Activity.AIR -> 10.0
    }

    /** No fix for longer than this (or three fix intervals) dims the values and counts the gap. */
    const val GAP_S = 5.0

    private const val FT = 0.3048

    private data class W(val low: Double, val medium: Double, val high: Double)

    private fun pick(d: Damping, w: W) = when (d) {
        Damping.LOW -> w.low
        Damping.MEDIUM -> w.medium
        Damping.HIGH -> w.high
    }
}
