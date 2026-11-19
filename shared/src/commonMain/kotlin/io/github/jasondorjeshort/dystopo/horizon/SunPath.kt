package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.nav.Sun

/**
 * T308: one point of the sun's path across the Horizon view's plot: at [timeMs] the sun's centre stands at [azimuthDeg]
 * (clockwise from true north) and [altitudeDeg] (apparent, refraction included); [clear] when that centre is above the
 * terrain's skyline there (or above the flat horizon without one).
 */
data class SunSample(val timeMs: Long, val azimuthDeg: Double, val altitudeDeg: Double, val clear: Boolean)

/**
 * T308: the sun's day at the Horizon view's point: [path] the arc to draw, [terrain] the times from [Sun.onTerrain] on the
 * plot's own horizon (the call the turn-around clock reaches through `ShadeTiles.pointAnswer`, so the terrain sunset is
 * the same number; the upper limb's times) with the flat horizon's day in [Sun.TerrainDay.flat]; [onTerrain] false when
 * the point had no terrain horizon and the flat one stood in for it.
 */
class HorizonSun(val year: Int, val month: Int, val day: Int, val path: List<SunSample>, val terrain: Sun.TerrainDay, val onTerrain: Boolean)

/** T308: the sun's path for the Horizon view (the idea "Today's sun path across the skyline"). */
object SunPath {
    /** The arc is sampled this often (ms): five minutes, a smooth arc at the plot's size. */
    const val STEP_MS = 5 * 60_000L

    /** Samples are kept down to this apparent altitude (degrees), so the arc meets the rim at rising and setting. */
    const val LOWEST_DEG = -1.0

    private const val DAY_MS = 86_400_000L

    /**
     * The sun's day at [lat], [lon] on the civil date [year]-[month]-[day] (the date whose solar noon it is, as [Sun.day]):
     * the path every [stepMs] over the 24 hours around that noon wherever the sun stands at or above [LOWEST_DEG], and the
     * terrain times over [horizonDeg] (a
     * `HorizonProfile::angleAt`), or over the flat horizon when it is null.
     */
    fun of(lat: Double, lon: Double, year: Int, month: Int, day: Int, horizonDeg: ((Double) -> Double)?, stepMs: Long = STEP_MS): HorizonSun {
        require(stepMs in 60_000L..3_600_000L) { "stepMs must be 1 min to 1 h" }
        val h = horizonDeg ?: { 0.0 }
        val terrain = Sun.onTerrain(lat, lon, year, month, day, h)
        val start = terrain.flat.noonMs - DAY_MS / 2
        val path = ArrayList<SunSample>()
        var t = start
        while (t <= start + DAY_MS) {
            val p = Sun.position(lat, lon, t)
            if (p.apparentAltitudeDeg >= LOWEST_DEG) path += SunSample(t, p.azimuthDeg, p.apparentAltitudeDeg, p.apparentAltitudeDeg >= h(p.azimuthDeg))
            t += stepMs
        }
        return HorizonSun(year, month, day, path, terrain, horizonDeg != null)
    }

    /** As [of] for the local day of [epochMs] at the point ([Sun.dayAt]: by its mean solar time). */
    fun at(lat: Double, lon: Double, epochMs: Long, horizonDeg: ((Double) -> Double)?): HorizonSun {
        val d = Sun.dayAt(lat, lon, epochMs)
        return of(lat, lon, d.year, d.month, d.day, horizonDeg)
    }

    /**
     * The path's samples on each whole hour of a clock [offsetMs] from UTC (the phone's zone) while the sun is up, for
     * the arc's hour marks: the sun's place at that hour, whether it is clear of the terrain.
     */
    fun hourMarks(sun: HorizonSun, lat: Double, lon: Double, offsetMs: Long, horizonDeg: ((Double) -> Double)?): List<SunSample> {
        val first = sun.path.firstOrNull()?.timeMs ?: return emptyList()
        val last = sun.path.last().timeMs
        val h = horizonDeg ?: { 0.0 }
        val out = ArrayList<SunSample>()
        var t = (first + offsetMs).floorDiv(3_600_000L) * 3_600_000L - offsetMs
        while (t <= last) {
            if (t >= first) {
                val p = Sun.position(lat, lon, t)
                if (p.apparentAltitudeDeg >= 0) out += SunSample(t, p.azimuthDeg, p.apparentAltitudeDeg, p.apparentAltitudeDeg >= h(p.azimuthDeg))
            }
            t += 3_600_000L
        }
        return out
    }
}
