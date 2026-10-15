package io.github.jasondorjeshort.dystopo.app.firstrun

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T106 (a T95 follow-up): the first-run card (no pack) gives the bottom of the map to a trip card, the Routes list, a
 * planned route's card or navigation; after a relaunch with no pack it covered an open trip's card.
 */
class FirstRunCardYieldTest {
    @Test
    fun theCardYieldsToTheCardsBelowIt() {
        assertFalse(cardsOwnTheBottom(trip = false, routePhase = "idle", navigating = false))
        assertTrue(cardsOwnTheBottom(trip = true, routePhase = "idle", navigating = false))
        assertTrue(cardsOwnTheBottom(trip = false, routePhase = "planned", navigating = false))
        assertTrue(cardsOwnTheBottom(trip = false, routePhase = "waitingForEnd", navigating = false))
        assertTrue(cardsOwnTheBottom(trip = false, routePhase = "idle", navigating = true))
    }
}
