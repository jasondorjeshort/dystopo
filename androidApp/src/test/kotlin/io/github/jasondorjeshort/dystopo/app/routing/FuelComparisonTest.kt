package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.routing.Crow
import io.github.jasondorjeshort.dystopo.routing.DriveMetric
import io.github.jasondorjeshort.dystopo.routing.DrivePreference
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T285 (ADR 0063 Decision 7): least fuel's line on the route card, and the session file keeping it. */
class FuelComparisonTest {
    private val slope = ElevationSampler { lat, _ -> 1600.0 + lat * 1000 }

    /** A drive in the shape of T284's Les Houches to Chamonix under least fuel (16.0 MJ, 481 s), on synthetic points. */
    private fun fuelDrive(remote: Boolean = false, metric: DriveMetric = DriveMetric.FUEL): PlannedRoute =
        Crow.plan(45.8905, 6.8000, 45.9180, 6.8650, slope, "Route").copy(
            mode = TravelMode.DRIVE, timeS = 481.0, metric = metric, fuelIndex = 15_950_000.0,
            memory = if (remote) linkedMapOf("remote" to "http://s") else emptyMap(),
        )

    @Test
    fun theLineShowsThePercentAndTheMinutes() {
        val c = assertNotNull(FuelComparison.of(fuelDrive(), DrivePreference.FUEL))
        assertEquals(LineState.WORKING, c.state)
        assertTrue(c.wantsLeastTime)
        assertNull(c.line, "nothing while the least-time plan runs")
        // The least-time route: 23.8 MJ, 461 s. 1 - 15.95 / 23.8 = 33.0 %; 20 s rounds to 0 min.
        val done = c.copy(leastTimeFuelIndex = 23_800_000.0, leastTimeS = 461.0, state = LineState.READY)
        assertEquals(33, done.percent)
        assertEquals(0, done.minutes)
        assertEquals("about 33 % less fuel than the least-time route, under a minute longer", done.line)
        // Not longer at all (0 s, or the least-time route a few seconds slower): "no longer".
        assertEquals("about 33 % less fuel than the least-time route, no longer", done.copy(timeS = 461.0).line)
        assertEquals("about 33 % less fuel than the least-time route, no longer", done.copy(timeS = 455.0).line)
        // 90 s rounds to 2 min (half up).
        assertEquals("about 33 % less fuel than the least-time route, 2 min longer", done.copy(timeS = 551.0).line)
        assertEquals(2, done.copy(timeS = 551.0).json()["minutes"])
        val slower = done.copy(timeS = 461.0 + 150.0)
        assertEquals("about 33 % less fuel than the least-time route, 3 min longer", slower.line)
        assertEquals(1, done.copy(timeS = 491.0).minutes, "30 s rounds up to a minute")
        val j = done.json()
        assertEquals("fuel", j["metric"]); assertEquals(33, j["percent"]); assertEquals(0, j["minutes"]); assertEquals(true, j["shown"])
        assertEquals(23_800_000L, j["leastTimeFuelIndex"]); assertEquals(461L, j["leastTimeS"])
    }

    @Test
    fun theLineIsHiddenUnderThreePercentButTheNumbersStay() {
        val c = FuelComparison(DrivePreference.FUEL, DriveMetric.FUEL, false, 97.6, 600.0, 100.0, 590.0, LineState.READY)
        assertEquals(2, c.percent)
        assertNull(c.line)
        assertEquals(false, c.json()["shown"])
        assertEquals(2, c.json()["percent"])
        assertEquals("about 3 % less fuel than the least-time route, under a minute longer", c.copy(fuelIndex = 97.0).line)
        // The same road under both: 0 %, hidden (the rainier drives).
        assertNull(c.copy(fuelIndex = 100.0, timeS = 590.0).line)
        // A least-time route a few seconds slower (the two searches' rounding) reads 0 minutes, never "-1 min longer".
        assertEquals(0, c.copy(fuelIndex = 50.0, timeS = 500.0).minutes)
        // A failed second plan: no numbers, no line.
        assertNull(c.copy(leastTimeFuelIndex = null, leastTimeS = null, state = LineState.NONE).line)
    }

    @Test
    fun aServersPlanHasNoPercentAndSaysWhenItWasNotLeastFuel() {
        // Asked for least fuel, the server planned for least time (it echoed time, or nothing): the card says so.
        val time = assertNotNull(FuelComparison.of(fuelDrive(remote = true, metric = DriveMetric.TIME), DrivePreference.FUEL))
        assertFalse(time.wantsLeastTime, "never a second plan for a server's route")
        assertEquals(LineState.NONE, time.state)
        assertEquals(FuelComparison.SERVER_TIME, time.line)
        assertEquals("Planned for least time: the server does not plan for least fuel.", time.line)
        // The server planned for least fuel: no line (the percentage needs both plans, ADR 0063).
        val fuel = assertNotNull(FuelComparison.of(fuelDrive(remote = true), DrivePreference.FUEL))
        assertNull(fuel.line)
        assertNull(fuel.copy(leastTimeFuelIndex = 23_800_000.0, leastTimeS = 461.0, state = LineState.READY).line)
        // Asked for least time: nothing to say.
        assertNull(FuelComparison.of(fuelDrive(remote = true, metric = DriveMetric.TIME), DrivePreference.TIME)!!.line)
        // A least-time drive on the phone: no comparison; a hike: none at all.
        assertFalse(FuelComparison.of(fuelDrive(metric = DriveMetric.TIME), DrivePreference.TIME)!!.wantsLeastTime)
        assertNull(FuelComparison.of(fuelDrive().copy(mode = TravelMode.HIKE), DrivePreference.FUEL))
    }

    @Test
    fun theSessionFileKeepsTheMetricAndTheNumbers() {
        val p = fuelDrive()
        val done = FuelComparison.of(p, DrivePreference.FUEL)!!.copy(
            leastTimeFuelIndex = 23_800_000.0, leastTimeS = 461.0, state = LineState.READY, ms = 140L, searchBytes = 1_234_567L,
        )
        val text = SessionRoute.encode(p, 45.8905 to 6.8000, 45.9180 to 6.8650, 1L, fuel = done)
        assertTrue("\"metric\":\"fuel\"" in text, text)
        val r = assertNotNull(SessionRoute.decode(text))
        assertEquals(DriveMetric.FUEL, r.plan.metric)
        assertEquals(15_950_000.0, r.plan.fuelIndex)
        val back = assertNotNull(r.fuel)
        assertEquals(done.line, back.line)
        assertEquals(33, back.percent)
        assertEquals(0, back.minutes)
        assertEquals(DrivePreference.FUEL, back.prefer)
        assertEquals(140L, back.ms)
        assertEquals(1_234_567L, back.searchBytes)
        // Saved while the second plan ran (the process went first): no line after the restart.
        val working = SessionRoute.decode(SessionRoute.encode(p, 45.8905 to 6.8000, 45.9180 to 6.8650, 1L, fuel = FuelComparison.of(p, DrivePreference.FUEL)))!!
        assertEquals(LineState.NONE, working.fuel!!.state)
        assertNull(working.fuel!!.line)
        // A server's least-time plan keeps its sentence.
        val remote = fuelDrive(remote = true, metric = DriveMetric.TIME)
        val rr = SessionRoute.decode(SessionRoute.encode(remote, 45.8905 to 6.8000, 45.9180 to 6.8650, 1L, fuel = FuelComparison.of(remote, DrivePreference.FUEL)))!!
        assertEquals(FuelComparison.SERVER_TIME, rr.fuel!!.line)
        // A file from before T285 has none of it.
        val old = SessionRoute.decode(SessionRoute.encode(p.copy(metric = null, fuelIndex = null), 45.8905 to 6.8000, 45.9180 to 6.8650, 1L))!!
        assertNull(old.fuel)
        assertNull(old.plan.metric)
    }
}
