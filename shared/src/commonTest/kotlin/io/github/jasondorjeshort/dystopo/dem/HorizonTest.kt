package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T181: [Horizon] against synthetic ridges whose horizon is known in closed form (up to a fine numeric maximum). */
class HorizonTest {
    private val deg = PI / 180

    /** The exact horizon toward [bearingDeg]: the highest apparent angle along the bearing, every metre out to [rangeM]. */
    private fun expected(f: TerrainFixture, bearingDeg: Double, eye: Double, rangeM: Double, k: Double = Horizon.REFRACTION_K): Double {
        var best = Double.NEGATIVE_INFINITY
        var s = 1.0
        while (s <= rangeM) {
            val h = f.heightAt(s * sin(bearingDeg * deg), s * cos(bearingDeg * deg))
            val t = (h - Horizon.dropM(s, k) - eye) / s
            if (t > best) best = t
            s += 1.0
        }
        return atan(best) / deg
    }

    /** A rounded ridge running north and south, [height] m high, its crest [distance] m east of the point, [width] m e-folding. */
    private fun ridge(distance: Double, height: Double, width: Double) = TerrainFixture(46.0, -121.0) { e, _ ->
        height * exp(-((e - distance) / width).let { it * it })
    }

    @Test
    fun aRidgeToTheEastStandsWhereItShould() {
        val f = ridge(2000.0, 300.0, 300.0)
        val zoom = 14
        val terrain = f.terrain(listOf(f.window(zoom, 600) to Double.POSITIVE_INFINITY))
        val p = Horizon.sweep(terrain, f.lat, f.lon, stepDeg = 1.0, rangeM = 3500.0)
        assertEquals(360, p.size)
        assertEquals(0, p.bearingsWithoutData)
        // East: the ridge, about 8.5 degrees up, about 2 km out.
        val east = expected(f, 90.0, 0.0, 3500.0)
        assertTrue(east in 8.0..9.0, "expected east horizon $east")
        assertEquals(east, p.angleAt(90.0), 0.05)
        assertEquals(2000.0, p.distanceAt(90.0), 120.0)
        // Every bearing whose highest ground lies well inside the range (toward the north and south the ridge's flank
        // keeps rising to the range's end, where the last step can fall short), and the flat ones (level ground: a
        // hair below 0 from the curvature).
        for (b in (45..135) + (180..360)) {
            val e = expected(f, b.toDouble(), 0.0, 3500.0)
            assertEquals(e, p.angleDeg[b % 360], 0.08, "bearing $b")
        }
        assertTrue(abs(p.angleAt(270.0)) < 0.01, "west is level: ${p.angleAt(270.0)}")
        // Off-axis the ridge is farther and lower: monotonic from east to north-east.
        assertTrue(p.angleAt(90.0) > p.angleAt(60.0) && p.angleAt(60.0) > p.angleAt(40.0))
        // Interpolation between bins.
        assertEquals((p.angleDeg[60] + p.angleDeg[61]) / 2, p.angleAt(60.5), 1e-9)
        assertEquals(p.angleDeg[0], p.angleAt(360.0), 1e-9)
    }

    @Test
    fun anEyeAboveTheGroundLowersTheHorizon() {
        val f = ridge(2000.0, 300.0, 300.0)
        val terrain = f.terrain(listOf(f.window(14, 600) to Double.POSITIVE_INFINITY))
        val ground = Horizon.sweep(terrain, f.lat, f.lon, rangeM = 3500.0)
        val tall = Horizon.sweep(terrain, f.lat, f.lon, rangeM = 3500.0, observerHeightM = 100.0)
        assertEquals(expected(f, 90.0, 100.0, 3500.0), tall.angleAt(90.0), 0.05)
        assertTrue(tall.angleAt(90.0) < ground.angleAt(90.0) - 2)
        // Level ground seen from 100 m up dips below level.
        assertTrue(tall.angleAt(270.0) < -1.0, "dip ${tall.angleAt(270.0)}")
    }

    @Test
    fun aFarRidgeIsReadFromTheCoarseLayerWithTheCurvature() {
        // 120 m high, 20 km east, wide: the curvature takes 27 m off it (0.08 degrees), refraction already counted.
        val f = ridge(20_000.0, 120.0, 1500.0)
        val zoom = 13
        val windows = Horizon.windowsAround(f.lat, f.lon, zoom, rangeM = 25_000.0)
        assertEquals(2, windows.size)
        assertEquals(zoom - Horizon.COARSE_ZOOMS, windows[1].first.zoom)
        val terrain = f.terrain(windows)
        val p = Horizon.sweep(terrain, f.lat, f.lon, rangeM = 25_000.0)
        val curved = expected(f, 90.0, 0.0, 25_000.0)
        val flatEarth = expected(f, 90.0, 0.0, 25_000.0, k = 1.0)
        assertTrue(flatEarth - curved > 0.06, "curvature worth ${flatEarth - curved} degrees")
        assertEquals(curved, p.angleAt(90.0), 0.02)
        assertEquals(20_000.0, p.distanceAt(90.0), 1500.0)
        // The flat-earth ray (k = 1: the line of sight bends exactly with the ground) sees it higher.
        val noDrop = Horizon.sweep(terrain, f.lat, f.lon, rangeM = 25_000.0, k = 1.0)
        assertEquals(flatEarth, noDrop.angleAt(90.0), 0.02)
        assertEquals(25_000.0, p.reachedM[90], 600.0)
    }

    @Test
    fun theDataEndIsReported() {
        val f = ridge(2000.0, 300.0, 300.0)
        val terrain = f.terrain(listOf(f.window(14, 100) to Double.POSITIVE_INFINITY))
        val p = Horizon.sweep(terrain, f.lat, f.lon, rangeM = 30_000.0)
        val edge = 100 * f.mpp(14)
        // Due west the window ends 100 cells out; the ridge (2 km) is past it, so east is level too.
        assertTrue(p.reachedM[270] <= edge + f.mpp(14) && p.reachedM[270] > edge * 0.9, "reached ${p.reachedM[270]} of $edge")
        assertTrue(abs(p.angleAt(90.0)) < 0.5)
    }
}
