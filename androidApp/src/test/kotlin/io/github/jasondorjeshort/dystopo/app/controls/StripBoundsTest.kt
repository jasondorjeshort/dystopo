package io.github.jasondorjeshort.dystopo.app.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T224: the readout strip is bounded by the right-hand chips as well as the top-left column ([StripBounds]). */
class StripBoundsTest {
    private val gap = 16

    @Test
    fun noChipsChangeNothing() {
        val r = StripBounds.room(x = 21, bottomPx = 1500, ceilingPx = 300, maxWidthPx = 900, naturalWidthPx = 900, minHeightPx = 190, chipLeftPx = 0, chipBottomPx = 0, gapPx = gap)
        assertEquals(300, r.ceilingPx)
        assertEquals(900, r.maxWidthPx)
        assertFalse(r.byChips)
    }

    @Test
    fun aStripThatStopsShortOfTheChipsChangesNothing() {
        // Landscape: the strip's widest reach ends left of the chips' left edge.
        val r = StripBounds.room(21, 900, 300, 900, 900, 190, chipLeftPx = 21 + 900 + gap, chipBottomPx = 800, gapPx = gap)
        assertEquals(300, r.ceilingPx)
        assertEquals(900, r.maxWidthPx)
        assertFalse(r.byChips)
        // The room to its right reaches the chips, the strip itself does not.
        val short = StripBounds.room(21, 900, 300, maxWidthPx = 2000, naturalWidthPx = 700, minHeightPx = 190, chipLeftPx = 21 + 700 + gap, chipBottomPx = 800, gapPx = gap)
        assertEquals(300, short.ceilingPx)
        assertEquals(2000, short.maxWidthPx)
        assertFalse(short.byChips)
    }

    @Test
    fun chipsAboveItsCeilingChangeNothing() {
        val r = StripBounds.room(21, 1500, ceilingPx = 600, maxWidthPx = 900, naturalWidthPx = 900, minHeightPx = 190, chipLeftPx = 500, chipBottomPx = 500, gapPx = gap)
        assertEquals(600, r.ceilingPx)
        assertFalse(r.byChips)
    }

    @Test
    fun itStaysUnderTheChipsWhileTheElevationFits() {
        val r = StripBounds.room(21, 1500, 300, 900, 900, 190, chipLeftPx = 500, chipBottomPx = 800, gapPx = gap)
        assertEquals(816, r.ceilingPx)
        assertEquals(900, r.maxWidthPx)
        assertTrue(r.byChips)
    }

    @Test
    fun elseItStopsShortOfTheirLeftEdge() {
        val r = StripBounds.room(21, 900, 300, 900, 900, 190, chipLeftPx = 500, chipBottomPx = 800, gapPx = gap)
        assertEquals(300, r.ceilingPx, "the ceiling stands")
        assertEquals(500 - gap - 21, r.maxWidthPx)
        assertTrue(r.byChips)
        // No room beside the chips either: a width too small to draw the elevation, and the bar hides.
        assertEquals(0, StripBounds.room(21, 900, 300, 900, 900, 190, chipLeftPx = 30, chipBottomPx = 800, gapPx = gap).maxWidthPx)
    }
}
