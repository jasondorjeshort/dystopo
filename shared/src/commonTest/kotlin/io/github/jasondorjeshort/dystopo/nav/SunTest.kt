package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Sun] against the US Naval Observatory's published one-day tables (Astronomical Applications Department,
 * `aa.usno.navy.mil/api/rstt/oneday`, fetched 2026-10-30, public domain) for Paradise, Mount Rainier (46.786 N,
 * 121.735 W): sunset and the end of civil twilight, to the minute. The task asks for 2 minutes on two dates; three are
 * held to that.
 */
class SunTest {
    private val lat = 46.786
    private val lon = -121.735

    /** Epoch ms of a UTC date and time. */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Sun.epochDay(y, mo, d) * 86_400_000L + (h * 60 + mi) * 60_000L

    private fun assertWithin(expected: Long, actual: Long?, minutes: Double, what: String) {
        assertNotNull(actual, what)
        val off = abs(actual - expected) / 60_000.0
        assertTrue(off <= minutes, "$what: off by $off min")
    }

    @Test
    fun paradiseAgainstTheAlmanac() {
        // USNO, local times: 2026-06-21 (UTC-7) set 21:05, civil end 21:44; 2026-12-21 (UTC-8) set 16:21, civil end
        // 16:57; 2026-10-30 (UTC-7) set 17:54, civil end 18:25. In UTC, each falls on the next calendar day.
        val cases = listOf(
            Triple(Triple(2026, 6, 21), utc(2026, 6, 22, 4, 5), utc(2026, 6, 22, 4, 44)),
            Triple(Triple(2026, 12, 21), utc(2026, 12, 22, 0, 21), utc(2026, 12, 22, 0, 57)),
            Triple(Triple(2026, 10, 30), utc(2026, 10, 31, 0, 54), utc(2026, 10, 31, 1, 25)),
        )
        for ((date, set, civil) in cases) {
            val day = Sun.day(lat, lon, date.first, date.second, date.third)
            // The almanac rounds to the minute: within 2 minutes is the task's line; within 1 is what this gives.
            assertWithin(set, day.sunsetMs, 1.0, "sunset $date")
            assertWithin(civil, day.civilEndMs, 1.0, "civil twilight end $date")
        }
    }

    @Test
    fun solarNoonAndTheDayAtAnInstant() {
        // USNO's upper transit: 2026-06-21 13:09 local (20:09 UTC).
        val day = Sun.day(lat, lon, 2026, 6, 21)
        assertWithin(utc(2026, 6, 21, 20, 9), day.noonMs, 1.0, "transit")
        // Any hour of that local day picks it, the evening included (UTC already the 22nd).
        assertEquals(day, Sun.dayAt(lat, lon, utc(2026, 6, 21, 15, 0)))
        assertEquals(day, Sun.dayAt(lat, lon, utc(2026, 6, 22, 4, 30)))
    }

    @Test
    fun positionAtNoonAndAtSunset() {
        val day = Sun.day(lat, lon, 2026, 6, 21)
        val noon = Sun.position(lat, lon, day.noonMs)
        // Solstice: declination 23.44, the sun due south at 90 - 46.786 + 23.44 degrees.
        assertEquals(23.44, noon.declinationDeg, 0.02)
        assertEquals(180.0, noon.azimuthDeg, 0.5)
        assertEquals(90 - lat + 23.44, noon.altitudeDeg, 0.05)
        assertEquals(0.0, noon.hourAngleDeg, 0.3)
        // At sunset the centre is 0.833 degrees below the geometric horizon, in the north-west in June.
        val set = Sun.position(lat, lon, day.sunsetMs!!)
        assertEquals(-0.833, set.altitudeDeg, 0.02)
        assertTrue(set.azimuthDeg in 300.0..310.0, "June sunset azimuth ${set.azimuthDeg}")
        assertTrue(set.apparentAltitudeDeg > set.altitudeDeg, "refraction lifts the sun near the horizon")
        // Equation of time near the June solstice: about -1.8 min; early November its yearly maximum, about +16.4.
        assertEquals(-1.8, noon.equationOfTimeMin, 0.3)
        assertEquals(16.4, Sun.position(lat, lon, utc(2026, 11, 3, 20, 0)).equationOfTimeMin, 0.2)
    }

    @Test
    fun polarSummerHasNoSunset() {
        val d = Sun.day(78.2, 15.6, 2026, 6, 21) // Svalbard at midsummer
        assertNull(d.sunsetMs)
        assertNull(d.civilEndMs)
    }

    @Test
    fun calendarRoundTrip() {
        for (day in listOf(-719468L, -1L, 0L, 20_000L, 20_757L, 2_932_896L)) {
            val (y, m, d) = Sun.civil(day)
            assertEquals(day, Sun.epochDay(y, m, d))
        }
        assertEquals(Triple(1970, 1, 1), Sun.civil(0))
        assertEquals(Triple(2026, 10, 30), Sun.civil(Sun.epochDay(2026, 10, 30)))
    }
}
