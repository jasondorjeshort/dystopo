package io.github.jasondorjeshort.dystopo.app.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T122: the help pop-up goes beside its icon, never over it ([PopupPlace]); at font scale 2.0 two texts covered theirs. */
class PopupPlaceTest {
    /** A bubble whose height grows with the text scale: [px] at scale 1.0. */
    private fun bubble(px: Int): (Float) -> Int = { s -> (px * s).toInt() }

    @Test
    fun besideTheIconAtTheSystemScaleWhenItFits() {
        assertEquals(PopupPlace("below", 2f, true), PopupPlace.choose(true, below = 900, above = 100, fontScale = 2f, need = bubble(400)))
        assertEquals(PopupPlace("above", 2f, true), PopupPlace.choose(true, below = 100, above = 900, fontScale = 2f, need = bubble(400)))
        assertEquals(PopupPlace("centre", 2f, true), PopupPlace.choose(false, below = 0, above = 0, fontScale = 2f, need = bubble(4000)))
    }

    @Test
    fun theLargerSideWithSmallerTextRatherThanOverTheIcon() {
        // 1,000 px at 2.0, 760 px of room below, 500 above: below, at the first step that fits (1.6 -> 800; 1.5 -> 750).
        val p = PopupPlace.choose(true, below = 760, above = 500, fontScale = 2f, need = bubble(500))
        assertEquals(PopupPlace("below", 1.5f, true), p)
        assertTrue(p.fits)
    }

    @Test
    fun stepsDownOnlyAsFarAsNeeded() {
        val p = PopupPlace.choose(true, below = 300, above = 860, fontScale = 2f, need = bubble(500))
        assertEquals(PopupPlace("above", 1.7f, true), p) // 1.9 -> 950, 1.8 -> 900, 1.7 -> 850
    }

    @Test
    fun scrollsAtTheFloorWhenNothingFits() {
        val p = PopupPlace.choose(true, below = 200, above = 300, fontScale = 2f, need = bubble(500))
        assertEquals(PopupPlace("above", 1.5f, false), p)
        // At font scale 1.0 the text is never made smaller than the system's 1.0.
        assertEquals(PopupPlace("below", 1f, false), PopupPlace.choose(true, below = 300, above = 200, fontScale = 1f, need = bubble(500)))
        // Below 1.0 the system's own scale is the floor too.
        assertFalse(PopupPlace.choose(true, below = 100, above = 100, fontScale = 0.85f, need = bubble(500)).textScale < 0.85f)
    }
}
