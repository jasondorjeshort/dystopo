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
    fun overlap() {
        val card = floatArrayOf(21f, 210f, 1059f, 900f)
        assertTrue(TopChips.overlap(card, floatArrayOf(21f, 330f, 500f, 420f)))
        assertFalse(TopChips.overlap(card, floatArrayOf(21f, 916f, 500f, 1000f)))
        assertFalse(TopChips.overlap(card, floatArrayOf(21f, 900f, 500f, 1000f)), "touching edges do not overlap")
        assertFalse(TopChips.overlap(card, null))
        assertFalse(TopChips.overlap(null, card))
    }
}
