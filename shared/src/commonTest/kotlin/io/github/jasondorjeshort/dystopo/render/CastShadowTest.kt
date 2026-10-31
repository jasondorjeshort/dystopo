package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T181: a synthetic cone's shadow on flat ground points away from the sun and is as long as the summit's height over
 * the tangent of the sun's altitude.
 */
class CastShadowTest {
    private val deg = PI / 180

    /** A cone 500 m high with 27 degree sides, its summit rounded over 150 m (so a ray's step cannot miss the top), its foot 1140 m out. */
    private val cone = TerrainFixture(45.0, 7.0) { e, n ->
        val r = hypot(e, n)
        (500.0 * (1 - (sqrt(r * r + 150.0 * 150.0) - 150.0) / 1000.0)).coerceAtLeast(0.0)
    }

    private val summit = cone.heightAt(0.0, 0.0)

    /** The exact shadow tip along the anti-sun line: the farthest d whose ray toward the sun meets the cone. */
    private fun expectedTip(altDeg: Double): Double {
        val t = tan(altDeg * deg)
        var d = 3000.0
        while (d > 0) {
            // Ray from d metres beyond the summit (on the anti-sun side) back toward the sun: does any ground rise above it?
            var s = 1.0
            var shaded = false
            while (s < d + 1000) {
                val off = d - s // signed distance from the summit along the line
                if (cone.heightAt(off, 0.0) - Horizon.dropM(s) > s * t) { shaded = true; break }
                s += 0.5
            }
            if (shaded) return d
            d -= 0.5
        }
        return 0.0
    }

    @Test
    fun theShadowOfAConeFallsWestOfAnEasternSunWithTheExpectedLength() {
        val zoom = 14
        val alt = 15.0
        // One row through the summit, from the summit 3 km west: a sun due east casts the shadow along it.
        val gx = floor(cone.gx(zoom)).toInt()
        val gy = floor(cone.gy(zoom)).toInt()
        val cells = (3000 / cone.mpp(zoom)).toInt()
        val row = MercatorWindow(zoom, gx - cells, gy, cells + 1, 1)
        val windows = CastShadow.windowsFor(row, 90.0, 5000.0)
        val r = CastShadow.compute(cone.terrain(windows), row, 90.0, alt, 5000.0)
        assertEquals(0, r.noDataCells)
        // Distance west of the summit of each cell's centre, and where the shade stops.
        val m = cone.mpp(zoom)
        val west = DoubleArray(row.width) { i -> cone.gx(zoom) - (row.x0 + i + 0.5) } // in pixels
        var tip = 0.0
        for (i in 0 until row.width) if (r.shade[i] == 1f) tip = maxOf(tip, west[i] * m)
        val expected = expectedTip(alt)
        // About summit / tan(15) = 1866 m from the summit.
        assertEquals(summit / tan(alt * deg), expected, 60.0) // the rounded top: the ray grazes it 95 m west of the summit
        // The tip is resolved to a cell (the last shaded cell centre is up to a cell short of it), the march to 2 m.
        assertEquals(expected, tip, m * 1.5, "shadow tip at $tip m, expected $expected m (cell $m m)")
        // The row from just below the rounded top (steeper than the sun from 95 m out) to the tip is in shade (the west
        // face, then the cast shadow), beyond it sun.
        for (i in 0 until row.width) {
            val d = west[i] * m
            if (d in 150.0..expected - m) assertEquals(1f, r.shade[i], "west $d m")
            if (d > expected + m) assertEquals(0f, r.shade[i], "west $d m")
        }
    }

    @Test
    fun theShadowPointsAwayFromTheSun() {
        val zoom = 13
        val window = cone.window(zoom, (2600 / cone.mpp(zoom)).toInt())
        val az = 135.0
        val alt = 20.0
        val r = CastShadow.compute(cone.terrain(CastShadow.windowsFor(window, az, 6000.0)), window, az, alt, 6000.0)
        val m = cone.mpp(zoom)
        // The cast part (beyond the cone's foot): its mean bearing from the summit is the sun's opposite.
        var se = 0.0
        var sn = 0.0
        var cast = 0
        var tip = 0.0
        for (j in 0 until r.height) for (i in 0 until r.width) {
            if (r.shade[j * r.width + i] != 1f) continue
            val e = (window.x0 + i + 0.5 - cone.gx(zoom)) * m
            val n = -(window.y0 + j + 0.5 - cone.gy(zoom)) * m
            val d = hypot(e, n)
            if (d < 1150) continue
            se += e / d
            sn += n / d
            cast++
            tip = maxOf(tip, d)
        }
        assertTrue(cast > 100, "cast cells $cast")
        val bearing = (atan2(se, sn) / deg + 360) % 360
        assertEquals(315.0, bearing, 1.0, "mean bearing of the cast shadow")
        assertEquals(expectedTip(alt), tip, 2 * m)
        // Nothing beyond the foot on the sun's side is in shade.
        for (j in 0 until r.height) for (i in 0 until r.width) {
            val e = (window.x0 + i + 0.5 - cone.gx(zoom)) * m
            val n = -(window.y0 + j + 0.5 - cone.gy(zoom)) * m
            if (hypot(e, n) > 1150 && e - n > 0) assertEquals(0f, r.shade[j * r.width + i], "sunward cell $e, $n")
        }
    }

    @Test
    fun nightIsAllShadeAndAHighSunCastsNothingPastTheFoot() {
        val zoom = 12
        val window = cone.window(zoom, 40)
        val terrain = cone.terrain(CastShadow.windowsFor(window, 180.0, 3000.0))
        val night = CastShadow.compute(terrain, window, 180.0, -2.0, 3000.0)
        assertTrue(night.night)
        assertEquals(window.width * window.height, night.shadedCells)
        assertEquals(0L, night.steps)
        val high = CastShadow.compute(terrain, window, 180.0, 80.0, 3000.0)
        assertEquals(0, high.shadedCells)
        assertEquals(0.0, high.shadedFraction)
    }

    @Test
    fun theRangeFollowsTheSun() {
        assertEquals(Horizon.DEFAULT_RANGE_M, CastShadow.rangeFor(2.0))
        assertEquals(CastShadow.RELIEF_M / tan(20 * deg), CastShadow.rangeFor(20.0), 1e-6)
        assertEquals(Horizon.DEFAULT_RANGE_M, CastShadow.rangeFor(-5.0))
    }
}
