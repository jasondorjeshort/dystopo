package io.github.jasondorjeshort.dystopo.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T137: the online indicator column starts below the navigation card while navigating ([TopChips.onlineTop]). */
class TopChipsTest {
    @Test
    fun belowTheSearchColumnWhenNotNavigating() {
        assertEquals(124.dp, TopChips.onlineTop(searchBottom = 120.dp, navCardBottom = 0.dp))
        assertNull(TopChips.onlineTop(0.dp, 0.dp))
    }

    @Test
    fun belowTheNavCardWhileNavigating() {
        // The card starts at the same height as the column did (60 dp under the status bar) and ends far below it.
        assertEquals(326.dp, TopChips.onlineTop(searchBottom = 120.dp, navCardBottom = 320.dp))
        // Measured before the search column: still below the card.
        assertEquals(326.dp, TopChips.onlineTop(searchBottom = 0.dp, navCardBottom = 320.dp))
        // A search column longer than the card (its results open): below both.
        assertEquals(404.dp, TopChips.onlineTop(searchBottom = 400.dp, navCardBottom = 320.dp))
    }

    @Test
    fun chipTopsFollowTheCardInPx() {
        // T146: the tops the chip columns are measured at in the card's own layout pass (density 2.625, 6 dp = 16 px).
        val d = 2.625f
        val c = TopChips.Chip.ONLINE
        assertEquals(240, TopChips.chipTopPx(c, navCardBottomPx = 0, searchBottomPx = 0, trackBottomPx = 0, fallbackPx = 240, density = d))
        assertEquals(Math.round((300 / d + 4) * d), TopChips.chipTopPx(c, 0, 300, 0, 240, d), "below the search column")
        assertEquals(Math.round((900 / d + 6) * d), TopChips.chipTopPx(c, 900, 300, 0, 240, d), "below the card while navigating")
        assertEquals(916, TopChips.chipTopPx(TopChips.Chip.TRACKS, 900, 300, 0, 240, d))
        assertEquals(240, TopChips.chipTopPx(TopChips.Chip.TRACKS, 0, 300, 0, 240, d), "under the status bar without a card")
        assertEquals(1216, TopChips.chipTopPx(TopChips.Chip.ROUTES, 900, 300, 1200, 400, d), "below the track controls")
        assertEquals(400, TopChips.chipTopPx(TopChips.Chip.ROUTES, 900, 300, 0, 400, d))
        // A card that grows by a wrapped turn line moves both columns by the same amount.
        val grow = 70
        assertEquals(grow, TopChips.chipTopPx(TopChips.Chip.TRACKS, 900 + grow, 300, 0, 240, d) - TopChips.chipTopPx(TopChips.Chip.TRACKS, 900, 300, 0, 240, d))
        assertTrue(TopChips.chipTopPx(c, 900 + grow, 300, 0, 240, d) >= 900 + grow)
    }

    @Test
    fun overlap() {
        val card = floatArrayOf(21f, 210f, 1059f, 900f)
        assertTrue(TopChips.overlap(card, floatArrayOf(21f, 330f, 500f, 420f)))
        assertFalse(TopChips.overlap(card, floatArrayOf(21f, 916f, 500f, 1000f)))
        assertFalse(TopChips.overlap(card, floatArrayOf(21f, 900f, 500f, 1000f)), "touching edges do not overlap")
        assertFalse(TopChips.overlap(card, null))
        assertFalse(TopChips.overlap(null, card))
    }
}
