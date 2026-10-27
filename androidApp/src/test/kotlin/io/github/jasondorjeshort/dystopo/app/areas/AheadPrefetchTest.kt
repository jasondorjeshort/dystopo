package io.github.jasondorjeshort.dystopo.app.areas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T150: the ahead-on-the-route poll rests in the background unless a navigation runs ([AheadPrefetch.polls]). */
class AheadPrefetchTest {
    @Test
    fun pollsInTheForegroundAlways() {
        assertTrue(AheadPrefetch.polls(foreground = true, navigating = false, holdingPins = false))
        assertTrue(AheadPrefetch.polls(foreground = true, navigating = true, holdingPins = true))
    }

    @Test
    fun inTheBackgroundOnlyWhileNavigatingOrReleasing() {
        assertFalse(AheadPrefetch.polls(foreground = false, navigating = false, holdingPins = false))
        assertTrue(AheadPrefetch.polls(foreground = false, navigating = true, holdingPins = true))
        // Navigation ended while in the background: one more tick releases the last segments' pins.
        assertTrue(AheadPrefetch.polls(foreground = false, navigating = false, holdingPins = true))
    }
}
