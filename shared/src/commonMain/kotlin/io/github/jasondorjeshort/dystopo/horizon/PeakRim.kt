package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.PeakView
import kotlin.math.floor

/**
 * T308: which named peaks the Horizon view shows (the idea "Peak names on the sky plot's skyline"). Only peaks that stand
 * on the skyline count ([PeakView.skyline]: in view, and nothing farther out along their bearing rises above them). The
 * rim names at most one per [SECTOR_DEG] sector of true bearing (0 to 30, 30 to 60, ...), the highest by elevation
 * (nearer first on a tie), and none within [MIN_GAP_DEG] of a higher one named (two sectors' winners either side of
 * their border), so a dense range does not pile names on one another; every skyline peak is in the list.
 */
object PeakRim {
    const val SECTOR_DEG = 30.0

    /** Two names on the rim stand at least this far apart in bearing (degrees). */
    const val MIN_GAP_DEG = 10.0

    /** The peaks on the skyline, clockwise from true north (the list's order: the way round the plot). */
    fun skyline(peaks: List<PeakView>): List<PeakView> = peaks.filter { it.skyline }.sortedWith(compareBy({ it.bearingDeg }, { it.distanceM }))

    /** The sector of [bearingDeg]: 0 for 0 to under [sectorDeg] degrees, and so on round. */
    fun sector(bearingDeg: Double, sectorDeg: Double = SECTOR_DEG): Int {
        val n = (360 / sectorDeg).toInt()
        return floor((((bearingDeg % 360) + 360) % 360) / sectorDeg).toInt().coerceIn(0, n - 1)
    }

    /** The bearing between [a] and [b] the short way round, 0 to 180 degrees. */
    fun apart(a: Double, b: Double): Double {
        val d = (((a - b) % 360) + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /**
     * The peaks the rim names, clockwise from north: the highest skyline peak of each sector, then, highest first, each
     * kept only when no higher one kept stands within [minGapDeg].
     */
    fun labelled(peaks: List<PeakView>, sectorDeg: Double = SECTOR_DEG, minGapDeg: Double = MIN_GAP_DEG): List<PeakView> {
        val order = compareByDescending<PeakView> { it.eleM }.thenBy { it.distanceM }
        val winners = skyline(peaks).groupBy { sector(it.bearingDeg, sectorDeg) }.values.map { s -> s.minWith(order) }.sortedWith(order)
        val kept = ArrayList<PeakView>()
        for (p in winners) if (kept.none { apart(it.bearingDeg, p.bearingDeg) < minGapDeg }) kept += p
        return kept.sortedBy { it.bearingDeg }
    }
}
