package io.github.jasondorjeshort.dystopo.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockCheckTest {
    @Test
    fun httpDateParses() {
        assertEquals(1791632080000L, ClockCheck.httpDate("Sat, 10 Oct 2026 11:34:40 GMT"))
        assertNull(ClockCheck.httpDate("yesterday"))
        assertNull(ClockCheck.httpDate(null))
    }

    @Test
    fun offsetFromTheShortestRoundTrip() {
        val now = 1_000_000_000L
        // Peer 3 h 12 min ahead; a Date header (1 s resolution) read during a 200 ms request.
        val ahead = 3 * 3_600_000L + 12 * 60_000L
        val c = ClockCheck.of(
            listOf(
                ClockSample(now + ahead - 500, now, now + 2_000, 1000), // slow request
                ClockSample(now + ahead + 5_000, now + 5_000, now + 5_200, 1000),
            ),
            "server", now,
        )
        assertNotNull(c)
        assertEquals(2, c.samples)
        assertTrue(kotlin.math.abs(c.offsetMs - ahead) <= 500, "${c.offsetMs}")
        assertEquals(600, c.uncertaintyMs)
        assertTrue(c.warn)
        assertEquals("This phone's clock is 3 h 12 min behind server's. Edits are ordered by time, so set the date and time automatically.", c.message())
    }

    @Test
    fun smallDifferencesAreNotWarnedAbout() {
        val c = ClockCheck.of(listOf(ClockSample(10_000 + 90_000, 10_000, 10_100)), "phone", 0)!!
        assertFalse(c.warn)
        assertNull(c.message())
        val ahead = ClockCheck.of(listOf(ClockSample(0, 2 * 86_400_000L + 3_600_000, 2 * 86_400_000L + 3_600_100)), "Pixel", 0)!!
        assertEquals("This phone's clock is 2 days 1 h ahead of Pixel's. Edits are ordered by time, so set the date and time automatically.", ahead.message())
        assertNull(ClockCheck.of(emptyList(), "x", 0))
    }
}
