package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.dem.Terrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

/**
 * T322: the skyline from one position at several ranges, nearest first ([rangesM], ascending; [profiles] one each), for
 * the Panorama's layered ridges drawn far to near. Each is a separate [Horizon.sweep] over the same terrain at the same
 * bearing step: a ray to a longer range takes the same samples as a shorter one up to that range, so at every bearing the
 * angle never falls as the range grows. [sweepMs] is each sweep's time.
 */
class LayeredSkyline(val rangesM: List<Double>, val profiles: List<HorizonProfile>, val sweepMs: List<Long>) {
    val stepDeg: Double get() = profiles.first().stepDeg

    /** The farthest skyline (the view range's). */
    val outer: HorizonProfile get() = profiles.last()

    /**
     * Which layer the direction ([elDeg], [azDeg]) falls on: the index of the nearest range whose skyline stands at or
     * above it (that ground hides the farther ones), or -1 for the sky above the farthest.
     */
    fun layerAt(elDeg: Double, azDeg: Double): Int {
        for (i in profiles.indices) if (elDeg <= profiles[i].angleAt(azDeg)) return i
        return -1
    }

    /**
     * T324: each skyline's bins as unit directions (east, north, up per bin, flat), computed once: the Panorama's scene
     * turns them into the lens's axes on every frame of a drag ([PanoramaScene]), so the trigonometry is done here once.
     */
    val directions: List<DoubleArray> by lazy {
        profiles.map { p ->
            DoubleArray(3 * p.size).also { d ->
                for (i in 0 until p.size) {
                    val el = p.angleDeg[i] * PI / 180
                    val az = p.bearingDeg(i) * PI / 180
                    d[3 * i] = cos(el) * sin(az)
                    d[3 * i + 1] = cos(el) * cos(az)
                    d[3 * i + 2] = sin(el)
                }
            }
        }
    }

    companion object {
        /** The near checkpoints, metres: the skylines drawn in front of the view range's (the owner's "about 3, 10, 30"). */
        val CHECKPOINTS_M = listOf(3_000.0, 10_000.0, 30_000.0)

        /** The ranges swept for a view range of [viewRangeM]: the checkpoints short of it, then the view range itself. */
        fun ranges(viewRangeM: Double): List<Double> = CHECKPOINTS_M.filter { it < viewRangeM } + viewRangeM

        /**
         * The skylines from ([lat], [lon]) at [observerHeightM] over [terrain] (read out to the largest of [rangesM]),
         * one [Horizon.sweep] per range at [stepDeg].
         */
        fun sweep(terrain: Terrain, lat: Double, lon: Double, rangesM: List<Double>, stepDeg: Double, observerHeightM: Double): LayeredSkyline {
            require(rangesM.isNotEmpty() && rangesM.zipWithNext().all { (a, b) -> a < b }) { "rangesM must be ascending" }
            val ms = ArrayList<Long>()
            val profiles = rangesM.map { r ->
                val t0 = TimeSource.Monotonic.markNow()
                Horizon.sweep(terrain, lat, lon, stepDeg, r, observerHeightM).also { ms += t0.elapsedNow().inWholeMilliseconds }
            }
            return LayeredSkyline(rangesM, profiles, ms)
        }
    }
}
