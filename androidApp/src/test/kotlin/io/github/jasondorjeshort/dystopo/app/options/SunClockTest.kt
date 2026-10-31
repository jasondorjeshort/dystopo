package io.github.jasondorjeshort.dystopo.app.options

import io.github.jasondorjeshort.dystopo.nav.Sun
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T188: the sun-and-shade time control's rules and the long-press card's sun line ([SunClock]). */
class SunClockTest {
    private val pacific = TimeZone.getTimeZone("America/Los_Angeles")
    private val paradise = 46.786 to -121.735
    private val day = Sun.day(paradise.first, paradise.second, 2026, 10, 30)
    private val hour = 3_600_000L

    @Test
    fun theSliderRunsAnHourPastSunriseAndSunsetOnTheStepGrid() {
        val r = SunClock.range(day.sunriseMs, day.sunsetMs, day.noonMs)
        assertTrue(r.startMs <= day.sunriseMs!! - hour && r.startMs > day.sunriseMs!! - hour - SunClock.STEP_MS)
        assertTrue(r.endMs >= day.sunsetMs!! + hour && r.endMs < day.sunsetMs!! + hour + SunClock.STEP_MS)
        assertEquals(0, r.startMs % SunClock.STEP_MS)
        assertEquals(0, r.endMs % SunClock.STEP_MS)
        // Paradise on 30 October: sunrise 7:46 and sunset 17:54 PDT, so the slider runs 6:45 to 18:55.
        assertEquals("6:45", SunClock.clock(r.startMs, pacific))
        assertEquals("18:55", SunClock.clock(r.endMs, pacific))
    }

    @Test
    fun polarDaysGetTheWholeDayAroundNoon() {
        val r = SunClock.range(null, null, day.noonMs)
        assertTrue(r.endMs - r.startMs >= 24 * hour)
        assertTrue(day.noonMs in r.startMs..r.endMs)
    }

    @Test
    fun itOpensAtNowWhileTheSunIsUpTodayElseTwoHoursBeforeSunset() {
        val now = day.noonMs + 37 * 60_000L + 1234
        assertEquals(SunClock.floorStep(now) to "now", SunClock.opening(now, today = true, sunUpNow = true, sunsetMs = day.sunsetMs, noonMs = day.noonMs))
        // Today after dark, or any other day: two hours before that day's sunset (15:54 PDT, on the grid 15:50).
        val (at, why) = SunClock.opening(now, today = true, sunUpNow = false, sunsetMs = day.sunsetMs, noonMs = day.noonMs)
        assertEquals("beforeSunset", why)
        assertEquals("15:50", SunClock.clock(at, pacific))
        assertEquals("beforeSunset", SunClock.opening(now, today = false, sunUpNow = true, sunsetMs = day.sunsetMs, noonMs = day.noonMs).second)
        assertEquals(SunClock.floorStep(day.noonMs) to "noon", SunClock.opening(now, today = false, sunUpNow = false, sunsetMs = null, noonMs = day.noonMs))
    }

    @Test
    fun snapAndFractionStayInsideTheDay() {
        val r = SunClock.range(day.sunriseMs, day.sunsetMs, day.noonMs)
        assertEquals(r.startMs, r.snap(r.startMs - 5 * hour))
        assertEquals(r.endMs, r.snap(r.endMs + 5 * hour))
        assertEquals(r.startMs + SunClock.STEP_MS, r.snap(r.startMs + SunClock.STEP_MS / 2 + 1))
        assertEquals(r.startMs, r.at(0.0))
        assertEquals(r.endMs, r.at(1.0))
        assertEquals(0.5, r.fraction((r.startMs + r.endMs) / 2), 1e-9)
    }

    @Test
    fun theLineNamesSunriseSunsetAndTheZone() {
        val open = Sun.onTerrain(paradise.first, paradise.second, 2026, 10, 30, { 0.0 })
        val line = SunClock.line(open, pacific, locale = Locale.US)
        assertTrue(Regex("^Sun reaches here 7:4\\d, leaves 17:5\\d PDT$").matches(line), line)
    }

    @Test
    fun aPeakThatHidesTheSunIsAGapBetweenTheTimes() {
        // A peak 30 degrees high from 170 to 190 degrees: the sun goes behind it around noon.
        val peak = Sun.onTerrain(paradise.first, paradise.second, 2026, 10, 30, { b -> if (b in 170.0..190.0) 30.0 else 0.0 })
        assertEquals(1, peak.gaps.size)
        val line = SunClock.line(peak, pacific, locale = Locale.US)
        assertTrue(Regex("^Sun reaches here 7:4\\d, hidden 1[23]:\\d\\d to 1[23]:\\d\\d, leaves 17:5\\d PDT$").matches(line), line)
    }

    @Test
    fun aDayWithoutSunAndAnotherDaysPrefix() {
        val hollow = Sun.onTerrain(paradise.first, paradise.second, 2026, 10, 30, { 60.0 })
        assertEquals("No direct sun reaches here", SunClock.line(hollow, pacific, locale = Locale.US))
        val label = SunClock.dateLabel(LocalDate.of(2026, 11, 2), Locale.US)
        assertEquals("Mon 2 Nov", label)
        val open = Sun.onTerrain(paradise.first, paradise.second, 2026, 11, 2, { 0.0 })
        // 2 November is after the change to standard time.
        assertTrue(Regex("^Mon 2 Nov: sun reaches here 6:\\d\\d, leaves 16:\\d\\d PST$").matches(SunClock.line(open, pacific, label, Locale.US)))
    }

    @Test
    fun noonOfADateIsLocalNoonInTheZone() {
        assertEquals("12:00", SunClock.clock(SunClock.noonOf(LocalDate.of(2026, 10, 30), pacific), pacific))
    }
}
