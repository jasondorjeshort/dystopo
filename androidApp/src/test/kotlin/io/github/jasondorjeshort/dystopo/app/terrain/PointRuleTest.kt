package io.github.jasondorjeshort.dystopo.app.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T358: the point read's pure rules ([PointRule]). */
class PointRuleTest {
    @Test
    fun theNetworkIsFetchedBehindOnlyWhereDeeperUnderTheSetting() {
        // A coarse map at z11, a server storing z15: fetched behind with the setting on, not with it off.
        assertEquals(15, PointRule.behindZoom(11, 15, detail = true, allowed = true))
        assertNull(PointRule.behindZoom(11, 15, detail = false, allowed = true))
        // Held as deep as the network (a fetched tile, a z15 map): nothing more to fetch.
        assertNull(PointRule.behindZoom(15, 15, detail = true, allowed = true))
        // The direct reader stores z12: a z11 map is passed by one zoom, a z13 map is not.
        assertEquals(12, PointRule.behindZoom(11, 12, detail = true, allowed = true))
        assertNull(PointRule.behindZoom(13, 12, detail = true, allowed = true))
        // No fetch may run (offline, Mobile data, the session limit) or no source: nothing, whatever is held.
        assertNull(PointRule.behindZoom(11, 15, detail = true, allowed = false))
        assertNull(PointRule.behindZoom(null, 15, detail = true, allowed = false))
        assertNull(PointRule.behindZoom(11, null, detail = true, allowed = true))
        // Nothing held: the network's tile, as the map's own tiles ask it there, the setting on or off; at most z15.
        assertEquals(15, PointRule.behindZoom(null, 15, detail = false, allowed = true))
        assertEquals(15, PointRule.behindZoom(null, 17, detail = true, allowed = true))
    }

    @Test
    fun aHeldCellRanksAheadOnlyPastTheMapsDepth() {
        assertEquals(12, PointRule.floor(11))
        assertEquals(16, PointRule.floor(15))
        assertEquals(0, PointRule.floor(null))
    }

    @Test
    fun theComposedPyramidHoldsEveryDepthPastTheShallowestMap() {
        assertEquals(12..15, PointRule.heldDepths(listOf(11, 14))) // the fixture's two maps
        assertEquals(8..15, PointRule.heldDepths(emptyList()))
        assertEquals(15..15, PointRule.heldDepths(listOf(15)))
    }

    @Test
    fun insideABoxInMetres() {
        val box = io.github.jasondorjeshort.dystopo.packs.PackBounds(-121.82, 46.80, -121.68, 46.90) // the fixture's fine map
        // 0.0025 deg north of the south edge: about 278 m, inside T357's 300 m band (the merged read blends there).
        assertEquals(278.3, PointRule.insideM(box, 46.8025, -121.75), 0.5)
        assertEquals(true, PointRule.insideM(box, 46.85, -121.75) > 400.0)
        assertEquals(true, PointRule.insideM(box, 46.79, -121.75) < 0.0)
    }

    @Test
    fun bilinearAsDemElevation() {
        assertEquals(15.0, PointRule.bilinear(10f, 20f, 10f, 20f, 0.5, 0.5), 1e-9)
        assertEquals(10.0, PointRule.bilinear(10f, 20f, 30f, 40f, 0.0, 0.0), 1e-9)
        assertEquals(25.0, PointRule.bilinear(10f, 20f, 30f, 40f, 0.5, 0.5), 1e-9)
        // One cell missing: the nearest of the four.
        assertEquals(20.0, PointRule.bilinear(10f, 20f, Float.NaN, 40f, 0.7, 0.2), 1e-9)
        assertEquals(Double.NaN, PointRule.bilinear(10f, 20f, Float.NaN, 40f, 0.2, 0.7))
    }
}
