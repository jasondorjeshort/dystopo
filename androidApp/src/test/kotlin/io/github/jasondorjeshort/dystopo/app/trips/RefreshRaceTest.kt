package io.github.jasondorjeshort.dystopo.app.trips

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T91 (the T65 follow-up): a list refresh computed before a route opened must not close that route. */
class RefreshRaceTest {
    @Test
    fun aRefreshThatReadNoRouteDoesNotCloseOneOpenedSince() {
        // The import picker's case: refresh read "nothing open", then the import opened route r1.
        assertFalse(refreshApplies(readId = null, nowId = "r1"))
    }

    @Test
    fun aRefreshForAnotherRouteDoesNotReplaceTheOpenOne() {
        assertFalse(refreshApplies(readId = "r1", nowId = "r2"))
    }

    @Test
    fun aRefreshForTheOpenRouteApplies() {
        // Including a remote deletion of it: the answer is null and the editor closes.
        assertTrue(refreshApplies(readId = "r1", nowId = "r1"))
    }

    @Test
    fun aRefreshWithNothingOpenHasNothingToApply() {
        assertFalse(refreshApplies(readId = null, nowId = null))
        assertFalse(refreshApplies(readId = "r1", nowId = null))
    }
}
