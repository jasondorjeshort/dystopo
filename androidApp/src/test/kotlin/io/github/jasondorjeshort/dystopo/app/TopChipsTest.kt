package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T137, T224: what is left of the chips' own arithmetic once the layout rule places them (T239: `MapLayoutTest` and
 * `OverlayLayoutTest` hold the placement): the stack the navigation card's cap reads, the overlap test, the columns' ids.
 */
class TopChipsTest {
    @Test
    fun eachColumnIsAManifestControl() {
        assertEquals(listOf("onlineIndicator", "waypointAndTracks", "routesChip"), TopChips.Chip.entries.map { it.control })
        assertTrue(TopChips.Chip.entries.all { it.control in MapLayout.RULED }, "the rule places every chip column")
    }

    @Test
    fun theStackUnderTheCardCountsOnlyWhatIsUp() {
        assertEquals(16 + 100 + 16 + 90, TopChips.stackPx(tracksPx = 100, routesPx = 90, gapPx = 16))
        assertEquals(16 + 100, TopChips.stackPx(100, 0, 16))
        assertEquals(0, TopChips.stackPx(0, 0, 16))
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
