package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.routing.BestStartAim
import io.github.jasondorjeshort.dystopo.routing.Crow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * T209 (the verifier's FIX): the start-time sweep's cache key holds everything the sweep reads that can change under the
 * same plan, so a changed turn-back margin (`turnMarginMin`, the deadline's margin) is a new key and a new sweep, not the
 * old line.
 */
class RouteBestStartTest {
    private val plan = Crow.plan(46.7864, -121.7366, 46.8024, -121.7289, null, "Paradise to Panorama Point")
    private val now = 1_784_118_600_000L // 2026-07-15 12:30 UTC

    @Test
    fun aChangedMarginIsANewKey() {
        val a = RouteBestStart.keyOf(plan, BestStartAim.SHADE, now, 7, 30)
        assertEquals(a, RouteBestStart.keyOf(plan, BestStartAim.SHADE, now + 60_000L, 7, 30), "the same plan, day and margin share one sweep")
        assertNotEquals(a, RouteBestStart.keyOf(plan, BestStartAim.SHADE, now, 7, 240))
        assertNotEquals(a, RouteBestStart.keyOf(plan, BestStartAim.SUN, now, 7, 30))
        assertNotEquals(a, RouteBestStart.keyOf(plan, BestStartAim.SHADE, now + 86_400_000L, 7, 30))
        assertNotEquals(a, RouteBestStart.keyOf(plan, BestStartAim.SHADE, now, 8, 30))
    }
}
