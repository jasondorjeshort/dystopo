package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.max

/**
 * Which named peaks the Horizon view shows (T308; T309 the owner's correction of 2026-11-20: "if there's a MUCH closer
 * peak just barely out of sight we definitely want to show it"). Which of them the plot names, and where, is
 * [HorizonLabels]' ranking and placement; this object turns peaks into its labels.
 *
 * **Candidates** ([candidates]): every peak tested (within the plot's 30 km) that is in sight ([PeakView.visible], or no
 * blocker marched: `clearanceDeg` +90) or only just out of sight, hidden by no more than [HIDDEN_M] of height:
 * `clearanceDeg >= -atan(HIDDEN_M / distanceM)`. A height, not an angle: DEM cells and shaved tops are tens of metres off
 * at any distance, where an angle would forgive 17 m at 500 m and 870 m at 25 km. Listed closest first. The highest
 * skyline peak ([summit]) is the dominant label (Mount Rainier from Paradise).
 */
object PeakRim {
    /** A peak hidden by no more than this height (metres) at its distance is "just out of sight" and still shown. */
    const val HIDDEN_M = 60.0

    /** The skyline peaks, clockwise from true north. */
    fun skyline(peaks: List<PeakView>): List<PeakView> = peaks.filter { it.skyline }.sortedWith(compareBy({ it.bearingDeg }, { it.distanceM }))

    /** The angle (degrees, negative) a peak at [distanceM] may be hidden by and still count as just out of sight. */
    fun hiddenLimitDeg(distanceM: Double): Double = -atan(HIDDEN_M / max(distanceM, 1.0)) * 180 / PI

    /** In sight: the line of sight clears its top, or nothing was marched before it (`clearanceDeg` +90). */
    fun inSight(p: PeakView): Boolean = p.visible || p.clearanceDeg >= 90.0

    /** Shown: in sight or just out of sight. */
    fun isCandidate(p: PeakView): Boolean = inSight(p) || p.clearanceDeg >= hiddenLimitDeg(p.distanceM)

    /** The candidates, closest first (the list's order). */
    fun candidates(peaks: List<PeakView>): List<PeakView> = peaks.filter(::isCandidate).sortedWith(compareBy({ it.distanceM }, { it.bearingDeg }))

    /** The highest skyline peak of the view (by elevation; nearer first on a tie), or null when none stands on the skyline. */
    fun summit(peaks: List<PeakView>): PeakView? = peaks.filter { it.skyline }.minWithOrNull(compareByDescending<PeakView> { it.eleM }.thenBy { it.distanceM })

    /**
     * The candidates as [HorizonLabel]s (closest first), [PeakView] as each one's `ref`: the clearance 0 or more while in
     * sight (a visible peak's tolerance may leave it a hair negative), the prominence the elevation over the median of the
     * other candidates within [HorizonLabels.PROMINENCE_NEAR_M] (no prominence in the map's peaks), the summit dominant.
     */
    fun labels(peaks: List<PeakView>): List<HorizonLabel> {
        val c = candidates(peaks)
        val top = summit(peaks)
        return c.map { p ->
            val near = c.filter { it !== p && TrackStats.distanceM(it.peak.lat, it.peak.lon, p.peak.lat, p.peak.lon) <= HorizonLabels.PROMINENCE_NEAR_M }.map { it.eleM }
            HorizonLabel(
                p.peak.name, LabelKind.PEAK, p.bearingDeg, p.angleDeg, p.distanceM, if (inSight(p)) max(p.clearanceDeg, 0.0) else p.clearanceDeg,
                p.eleM, HorizonLabels.prominence(p.eleM, near), dominant = p === top, ref = p,
            )
        }
    }
}
