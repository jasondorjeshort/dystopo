package io.github.jasondorjeshort.dystopo.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T187: the dry stretches between sources along a route ([WaterGaps]), as `devtool water`'s `longestGapM` counts them. */
class WaterGapsTest {
    private fun item(alongM: Double, kind: WaterKind = WaterKind.STREAM, cls: WaterClass? = WaterClass.LIKELY_FLOWING) =
        WaterItem(alongM, kind, if (kind == WaterKind.STREAM) cls else null, WaterBasis.MAPPED, null, null, false, false, false, null, 0.0, 46.79, -121.74)

    @Test
    fun aSourceIsAFlowingStreamASpringATapOrALake() {
        assertTrue(item(0.0).isSource)
        assertFalse(item(0.0, cls = WaterClass.MAY_RUN_DRY).isSource)
        assertFalse(item(0.0, cls = WaterClass.USUALLY_DRY).isSource)
        for (k in listOf(WaterKind.SPRING, WaterKind.TAP, WaterKind.LAKE)) assertTrue(item(0.0, k).isSource, k.key)
    }

    @Test
    fun theLongestStretchRunsFromTheStartASourceOrToTheEnd() {
        val items = listOf(item(1_000.0), item(2_000.0, cls = WaterClass.MAY_RUN_DRY), item(8_500.0, WaterKind.SPRING), item(9_000.0))
        assertEquals(1_000.0 to 8_500.0, WaterGaps.longest(items, 10_000.0), "the seasonal stream does not end the stretch")
        assertEquals(0.0 to 12_000.0, WaterGaps.longest(emptyList(), 12_000.0), "no water: the whole route")
        assertEquals(9_000.0 to 20_000.0, WaterGaps.longest(items, 20_000.0), "to the end")
        assertEquals(0.0 to 0.0, WaterGaps.longest(emptyList(), 0.0))
    }

    @Test
    fun theStretchAfterAnItemEndsAtTheNextSource() {
        val items = listOf(item(1_000.0), item(2_000.0, cls = WaterClass.USUALLY_DRY), item(8_500.0, WaterKind.LAKE))
        assertEquals(7_500.0, WaterGaps.after(items, 0, 10_000.0))
        assertEquals(6_500.0, WaterGaps.after(items, 1, 10_000.0), "from a gully to the lake")
        assertEquals(1_500.0, WaterGaps.after(items, 2, 10_000.0), "the last source to the end")
        assertFalse(WaterGaps.lastSource(items, 0))
        assertTrue(WaterGaps.lastSource(items, 2))
        assertTrue(WaterGaps.after(items, 0, 10_000.0) > WaterGaps.LONG_M)
    }
}
