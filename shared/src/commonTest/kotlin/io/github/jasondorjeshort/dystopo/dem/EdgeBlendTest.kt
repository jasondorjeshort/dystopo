package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T357: the one blend weight at a map's edge ([EdgeBlend]). */
class EdgeBlendTest {
    /** The fine map's box of the two-maps fixture (public Rainier). */
    private val box = PackBounds(-121.82, 46.80, -121.68, 46.90)
    private val band = EdgeBlend.BAND_M
    private val degLat = 1.0 / EdgeBlend.M_PER_DEG // one metre of latitude, in degrees

    @Test
    fun zeroOnAndOutsideTheBoxOneFromTheBandInside() {
        assertEquals(0.0, EdgeBlend.weight(box, -121.75, 46.80))
        assertEquals(0.0, EdgeBlend.weight(box, -121.75, 46.79))
        assertEquals(0.0, EdgeBlend.weight(box, -121.90, 46.85))
        assertEquals(1.0, EdgeBlend.weight(box, -121.75, 46.80 + (band + 1) * degLat))
        assertEquals(1.0, EdgeBlend.weight(box, -121.75, 46.85), "the middle of the box")
        assertEquals(0.5, EdgeBlend.weight(box, -121.75, 46.80 + band / 2 * degLat), 1e-6, "half way through the band: the smoothstep's middle")
    }

    @Test
    fun theWeightRisesSmoothlyThroughTheBand() {
        var last = 0.0
        var steepest = 0.0
        for (m in 0..400) {
            val w = EdgeBlend.weight(box, -121.75, 46.80 + m * degLat)
            assertTrue(w >= last, "monotonic at $m m")
            steepest = maxOf(steepest, w - last)
            last = w
        }
        // A smoothstep over 300 m rises at most 1.5 / 300 per metre: no step anywhere.
        assertTrue(steepest <= 1.5 / band + 1e-6, "steepest rise $steepest per metre")
        assertEquals(1.0, last)
    }

    @Test
    fun theNearestSideCountsEastAndWestInGroundMetres() {
        // 150 m east of the west side, far from the south and north sides: half the weight, as 150 m north of the south side.
        val lonDeg = 150.0 / (EdgeBlend.M_PER_DEG * EdgeBlend.cosLat(46.85))
        assertEquals(0.5, EdgeBlend.weight(box, -121.82 + lonDeg, 46.85), 1e-6)
        assertEquals(150.0, EdgeBlend.insideM(box, -121.82 + lonDeg, 46.85), 1e-6)
        // The corner: the nearer side wins.
        assertTrue(EdgeBlend.weight(box, -121.82 + lonDeg, 46.80 + 50 * degLat) < 0.5)
    }

    @Test
    fun theInnerBoxIsWhereEveryWeightIsOne() {
        val inner = assertNotNull(EdgeBlend.inner(box))
        for ((lon, lat) in listOf(inner.minLon to inner.minLat, inner.maxLon to inner.maxLat, inner.minLon to inner.maxLat, inner.maxLon to inner.minLat)) {
            assertEquals(1.0, EdgeBlend.weight(box, lon, lat), 1e-9, "corner $lon, $lat")
        }
        assertTrue(abs(inner.minLat - (box.minLat + band * degLat)) < 1e-9)
        assertNull(EdgeBlend.inner(PackBounds(-121.75, 46.80, -121.749, 46.801)), "a box narrower than two bands has no inner part")
    }

    @Test
    fun anExtentReachesTheBandWhereItOverlapsTheBoxButNotItsInnerPart() {
        assertTrue(EdgeBlend.reachesBand(box, PackBounds(-121.76, 46.79, -121.74, 46.81)), "across the south edge")
        assertTrue(EdgeBlend.reachesBand(box, PackBounds(-121.76, 46.801, -121.74, 46.802)), "inside the band only")
        assertFalse(EdgeBlend.reachesBand(box, PackBounds(-121.76, 46.84, -121.74, 46.86)), "well inside")
        assertFalse(EdgeBlend.reachesBand(box, PackBounds(-121.76, 46.70, -121.74, 46.72)), "outside")
    }
}
