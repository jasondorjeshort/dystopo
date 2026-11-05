package io.github.jasondorjeshort.dystopo.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** T218: the one clock form, in both styles, at the hours that go wrong. */
class ClockTextTest {
    private val s = ClockText.SPACE

    @Test
    fun twelveHour() {
        assertEquals("12:00${s}AM", ClockText.of(0, 0, ClockStyle.H12), "midnight")
        assertEquals("12:00${s}PM", ClockText.of(12, 0, ClockStyle.H12), "noon")
        assertEquals("12:59${s}PM", ClockText.of(12, 59, ClockStyle.H12))
        assertEquals("11:59${s}PM", ClockText.of(23, 59, ClockStyle.H12))
        assertEquals("7:05${s}AM", ClockText.of(7, 5, ClockStyle.H12), "no zero on the hour")
        assertEquals("1:00${s}PM", ClockText.of(13, 0, ClockStyle.H12))
    }

    @Test
    fun twentyFourHour() {
        assertEquals("00:00", ClockText.of(0, 0, ClockStyle.H24), "midnight")
        assertEquals("12:00", ClockText.of(12, 0, ClockStyle.H24), "noon")
        assertEquals("12:59", ClockText.of(12, 59, ClockStyle.H24))
        assertEquals("23:59", ClockText.of(23, 59, ClockStyle.H24))
        assertEquals("07:05", ClockText.of(7, 5, ClockStyle.H24), "the hour padded, as the platform's short form")
    }

    @Test
    fun noTimeIsLongerThanTheLongest() {
        for (style in ClockStyle.entries) for (h in 0..23) for (m in 0..59) {
            val text = ClockText.of(h, m, style)
            assert(text.length <= ClockText.LONGEST.length) { text }
        }
        assertEquals(ClockText.LONGEST, ClockText.of(12, 59, ClockStyle.H12))
    }

    @Test
    fun outOfRangeAndParsing() {
        assertFailsWith<IllegalArgumentException> { ClockText.of(24, 0, ClockStyle.H24) }
        assertFailsWith<IllegalArgumentException> { ClockText.of(0, 60, ClockStyle.H12) }
        assertEquals(ClockStyle.H12, ClockStyle.parse("12"))
        assertEquals(ClockStyle.H24, ClockStyle.parse(" 24 "))
        assertNull(ClockStyle.parse("13"))
        assertNull(ClockStyle.parse(null))
    }

    @Test
    fun theStoredDeadlineKeepsItsOwnForm() {
        assertEquals("15:00", DeadlineSetting(DeadlineKind.TIME, 15 * 60).value)
        assertEquals("09:05", DeadlineSetting.hhmm(9 * 60 + 5))
    }
}
