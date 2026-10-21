package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** T122: a route or trip fit clears the chrome at the top of the map and the card at the bottom ([FitPadding]). */
class FitPaddingTest {
    private val d = 2.625f // a Pixel 6

    @Test
    fun clearsTheChipsAndTheCard() {
        // The Routes chip's bottom at 600 px, the route card's top 900 px above the map's bottom, a 2,200 px map.
        val p = FitPadding.of(chromeBottomPx = 600, cardTopPx = 900, mapHeightPx = 2200, density = d)
        assertContentEquals(intArrayOf(105, 600 + 42, 105, 900 + 42), p)
        assertTrue(p[1] >= 600)
    }

    @Test
    fun nothingMeasuredYetKeepsTheOldTop() {
        assertContentEquals(intArrayOf(105, 252, 105, 42), FitPadding.of(0, 0, 2200, d))
    }

    @Test
    fun theTopGivesWayBeforeTheFrameIsASliver() {
        // Font scale 2.0 with the tracks list open: the chrome reaches 1,300 px and the card 800 px of a 2,200 px map.
        val p = FitPadding.of(1300, 800, 2200, d)
        val frame = 2200 - p[1] - p[3]
        assertTrue(frame >= (FitPadding.MIN_FRAME_DP * d).toInt(), "frame $frame")
        assertContentEquals(intArrayOf(105, 2200 - 842 - 420, 105, 842), p)
        // Taller still: the top stops at the status bar and the bottom gives way too, never below 0.
        val q = FitPadding.of(2000, 2000, 2200, d)
        assertTrue(q[1] == (FitPadding.MIN_TOP_DP * d).toInt() && q[3] >= 0 && 2200 - q[1] - q[3] >= (FitPadding.MIN_FRAME_DP * d).toInt(), q.toList().toString())
    }
}
