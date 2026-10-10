package io.github.jasondorjeshort.dystopo.gpx

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpxFormatTest {
    @Test
    fun parsesTimes() {
        assertEquals(0L, GpxFormat.parseTime("1970-01-01T00:00:00Z"))
        // 2025-08-14T16:30:00Z: 20314 days after the epoch.
        assertEquals(20314L * 86_400_000L + 16 * 3_600_000L + 30 * 60_000L, GpxFormat.parseTime("2025-08-14T16:30:00Z"))
        val base = GpxFormat.parseTime("2025-08-14T16:30:00Z")!!
        assertEquals(base + 123, GpxFormat.parseTime("2025-08-14T16:30:00.123Z"))
        assertEquals(base + 500, GpxFormat.parseTime("2025-08-14T16:30:00.5Z"))
        assertEquals(base + 123, GpxFormat.parseTime("2025-08-14T16:30:00.123999Z")) // truncated
        assertEquals(base, GpxFormat.parseTime("2025-08-14T09:30:00-07:00"))
        assertEquals(base, GpxFormat.parseTime("2025-08-14T18:30:00+0200"))
        assertEquals(base, GpxFormat.parseTime("2025-08-14T18:30:00+02"))
        assertEquals(base, GpxFormat.parseTime("2025-08-14T16:30:00")) // no offset: UTC
        assertEquals(base, GpxFormat.parseTime(" 2025-08-14T16:30:00Z\n"))
        assertEquals(GpxFormat.parseTime("2024-03-01T00:00:00Z")!! - 86_400_000L, GpxFormat.parseTime("2024-02-29T00:00:00Z"))
        assertEquals(-86_400_000L, GpxFormat.parseTime("1969-12-31T00:00:00Z"))
        assertEquals(GpxFormat.parseTime("2025-08-15T00:00:00Z"), GpxFormat.parseTime("2025-08-14T24:00:00Z"))
    }

    @Test
    fun rejectsBadTimes() {
        for (bad in listOf("", "2025-08-14", "2025-13-01T00:00:00Z", "2023-02-29T00:00:00Z", "2025-08-14T25:00:00Z",
            "2025-08-14T16:30Z", "2025-08-14T16:30:00Q", "2025-08-14T16:30:00.Z", "yesterday")) {
            assertNull(GpxFormat.parseTime(bad), bad)
        }
    }

    @Test
    fun formatsTimes() {
        assertEquals("1970-01-01T00:00:00Z", GpxFormat.formatTime(0))
        assertEquals("2025-08-14T16:30:00Z", GpxFormat.formatTime(GpxFormat.parseTime("2025-08-14T16:30:00Z")!!))
        assertEquals("2025-08-14T16:30:00.007Z", GpxFormat.formatTime(GpxFormat.parseTime("2025-08-14T16:30:00.007Z")!!))
        assertEquals("1969-12-31T23:59:59.999Z", GpxFormat.formatTime(-1))
    }

    @Test
    fun timeRoundTripsOverCenturies() {
        val rnd = Random(5)
        repeat(2000) {
            val t = rnd.nextLong(-5_000_000_000_000L, 10_000_000_000_000L) // ~1811..2286
            assertEquals(t, GpxFormat.parseTime(GpxFormat.formatTime(t)))
        }
    }

    @Test
    fun civilDays() {
        assertEquals(0L, GpxFormat.daysFromCivil(1970, 1, 1))
        assertEquals(11_017L, GpxFormat.daysFromCivil(2000, 3, 1))
        assertEquals(Triple(2000, 2, 29), GpxFormat.civilFromDays(11_016))
        for (d in -800_000L..800_000L step 997) {
            val (y, m, day) = GpxFormat.civilFromDays(d)
            assertEquals(d, GpxFormat.daysFromCivil(y, m, day))
        }
    }

    @Test
    fun decimalsAreShortestAndPlain() {
        assertEquals("1", GpxFormat.formatDecimal(1.0))
        assertEquals("0", GpxFormat.formatDecimal(0.0))
        assertEquals("0", GpxFormat.formatDecimal(-0.0))
        assertEquals("-121.7365", GpxFormat.formatDecimal(-121.7365))
        assertEquals("0.00001", GpxFormat.formatDecimal(1e-5))
        assertEquals("-0.000123", GpxFormat.formatDecimal(-1.23e-4))
        assertEquals("12345678", GpxFormat.formatDecimal(1.2345678e7))
        assertEquals("1230000000", GpxFormat.formatDecimal(1.23e9))
        val rnd = Random(9)
        repeat(2000) {
            val v = (rnd.nextDouble() - 0.5) * listOf(1e-9, 1e-3, 1.0, 360.0, 1e12)[it % 5]
            val s = GpxFormat.formatDecimal(v)
            assertEquals(false, 'E' in s || 'e' in s, s)
            assertEquals(v, s.toDouble(), s)
        }
    }
}
