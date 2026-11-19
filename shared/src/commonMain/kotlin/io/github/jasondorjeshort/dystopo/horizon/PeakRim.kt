package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.PeakView
import kotlin.math.floor

/**
 * T308: which named peaks the Horizon view shows (the idea "Peak names on the sky plot's skyline"). Only peaks that stand
 * on the skyline count ([PeakView.skyline]: in view, and nothing farther out along their bearing rises above them). The
 * rim names at most one per [SECTOR_DEG] sector of true bearing (0 to 30, 30 to 60, ...), the highest by elevation
 * (nearer first on a tie), so a dense range does not pile names on one another; every skyline peak is in the list.
 */
object PeakRim {
    const val SECTOR_DEG = 30.0

    /** The peaks on the skyline, clockwise from true north (the list's order: the way round the plot). */
    fun skyline(peaks: List<PeakView>): List<PeakView> = peaks.filter { it.skyline }.sortedWith(compareBy({ it.bearingDeg }, { it.distanceM }))

    /** The sector of [bearingDeg]: 0 for 0 to under [sectorDeg] degrees, and so on round. */
    fun sector(bearingDeg: Double, sectorDeg: Double = SECTOR_DEG): Int {
        val n = (360 / sectorDeg).toInt()
        return floor((((bearingDeg % 360) + 360) % 360) / sectorDeg).toInt().coerceIn(0, n - 1)
    }

    /** The peaks the rim names: the highest skyline peak of each sector, clockwise from north. */
    fun labelled(peaks: List<PeakView>, sectorDeg: Double = SECTOR_DEG): List<PeakView> =
        skyline(peaks).groupBy { sector(it.bearingDeg, sectorDeg) }.values
            .map { s -> s.minWith(compareByDescending<PeakView> { it.eleM }.thenBy { it.distanceM }) }
            .sortedBy { it.bearingDeg }
}
