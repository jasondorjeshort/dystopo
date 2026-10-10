package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthetic tracks over [SyntheticTerrain] for tests: positions in terrain meters mapped to degrees
 * around null island (0, 0), where a degree of latitude or longitude is the same length, elevations
 * from the terrain, times from a constant walking speed. Nothing here is a real place.
 */
object SyntheticTracks {
    /** Meters per degree on the stats sphere at the equator. */
    val M_PER_DEG = TrackStats.EARTH_RADIUS_M * PI / 180.0

    fun point(terrain: SyntheticTerrain, x: Double, y: Double, timeMs: Long?, hr: Int? = null) = TrackPoint(
        lat = -y / M_PER_DEG + 0.0, // terrain y grows south; + 0.0 turns -0.0 (written as "0") into 0.0
        lon = x / M_PER_DEG,
        ele = terrain.elevationAt(x, y),
        time = timeMs,
        extensions = hr?.let { GpxExtensions.trackPointExtension(heartRate = it, cadence = 50) },
    )

    /** A circle of [radius] m around ([cx], [cy]) in [n] steps, walked at [speed] m/s from [start]. */
    fun loop(terrain: SyntheticTerrain, cx: Double, cy: Double, radius: Double, n: Int, speed: Double = 1.2, start: Long = 1_700_000_000_000L): List<TrackPoint> {
        val step = 2 * PI * radius / n
        return (0..n).map { i ->
            val a = 2 * PI * i / n
            point(terrain, cx + radius * cos(a), cy + radius * sin(a), start + (i * step / speed * 1000).roundToInt(), hr = 100 + i % 40)
        }
    }

    /** A straight line from ([x0], [y0]) to ([x1], [y1]) and back, [n] steps each way. */
    fun outAndBack(terrain: SyntheticTerrain, x0: Double, y0: Double, x1: Double, y1: Double, n: Int, speed: Double = 1.0): List<TrackPoint> {
        val out = (0..n).map { i -> x0 + (x1 - x0) * i / n to y0 + (y1 - y0) * i / n }
        val path = out + out.reversed().drop(1)
        val step = kotlin.math.hypot(x1 - x0, y1 - y0) / n
        return path.mapIndexed { i, (x, y) -> point(terrain, x, y, 1_700_000_000_000L + (i * step / speed * 1000).toLong()) }
    }

    fun track(name: String, vararg segments: List<TrackPoint>) =
        Track(id = "00000000-0000-4000-8000-000000000000", name = name, segments = segments.map { TrackSegment(it) })
}
