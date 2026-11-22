package io.github.jasondorjeshort.dystopo.app.panorama

import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.Back
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.FlightPicked
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.LetGo
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.Opened
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.PeakPicked
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.SatelliteTapped
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets.SunTapped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T327: the Panorama target's rule (`PanoramaTargets.next`): set by a row or a name, kept across the switch, let go by Back. */
class PanoramaTargetTest {
    private val rainier = "Mount Rainier@3470"

    @Test
    fun aPeakRowOrNameMakesItTheTargetAndTurns() {
        val s = PanoramaTargets.next(TargetState(), PeakPicked(rainier, "Mount Rainier"))
        assertEquals(PanoramaTarget(TargetKind.PEAK, rainier, "Mount Rainier"), s.state.target)
        assertEquals(rainier, s.state.peak)
        assertTrue(s.turn)
        // The name defaults to the key's name part (a pick by the route or a name on the plot carries the key alone).
        assertEquals("Mount Rainier", PanoramaTargets.next(TargetState(), PeakPicked(rainier)).state.target?.name)
    }

    @Test
    fun thePicksXLetsTheTargetGo() {
        val picked = PanoramaTargets.next(TargetState(), PeakPicked(rainier)).state
        val s = PanoramaTargets.next(picked, PeakPicked(null))
        assertNull(s.state.target)
        assertNull(s.state.peak)
        assertFalse(s.turn)
    }

    @Test
    fun aFlightPickedLaterIsTheTargetAndItsCloseLeavesThePeakPicked() {
        val peak = PanoramaTargets.next(TargetState(), PeakPicked(rainier)).state
        val flight = PanoramaTargets.next(peak, FlightPicked("a1b2c3", "N123AB"))
        assertEquals(TargetKind.FLIGHT, flight.state.target?.kind)
        assertTrue(flight.turn)
        val closed = PanoramaTargets.next(flight.state, FlightPicked(null))
        assertNull(closed.state.target)
        assertEquals(rainier, closed.state.peak)
        // Letting go of the peak while the flight is the target leaves the flight the target.
        assertEquals(TargetKind.FLIGHT, PanoramaTargets.next(flight.state, PeakPicked(null)).state.target?.kind)
    }

    @Test
    fun theSunsRowTurnsOnlyWhileTheSunIsUp() {
        val down = PanoramaTargets.next(TargetState(), SunTapped(up = false))
        assertNull(down.state.target)
        assertFalse(down.turn)
        val up = PanoramaTargets.next(TargetState(), SunTapped(up = true))
        assertEquals(TargetKind.SUN, up.state.target?.kind)
        assertEquals("Sun", up.state.target?.name)
        assertTrue(up.turn)
        // A second tap on the sun's row lets it go.
        assertNull(PanoramaTargets.next(up.state, SunTapped(up = true)).state.target)
    }

    @Test
    fun aSatelliteRowIsTheTargetAndASecondTapLetsItGo() {
        val s = PanoramaTargets.next(TargetState(), SatelliteTapped("gps-12", "GPS 12"))
        assertEquals(PanoramaTarget(TargetKind.SATELLITE, "gps-12", "GPS 12"), s.state.target)
        assertTrue(s.turn)
        assertNull(PanoramaTargets.next(s.state, SatelliteTapped("gps-12", "GPS 12")).state.target)
        assertEquals("gps-5", PanoramaTargets.next(s.state, SatelliteTapped("gps-5", "GPS 5")).state.target?.id)
    }

    @Test
    fun aPickKeptAcrossTheSwitchIsTheTargetAtTheOpening() {
        val s = PanoramaTargets.next(TargetState(), Opened(rainier, null, null, null))
        assertEquals(TargetKind.PEAK, s.state.target?.kind)
        assertEquals("Mount Rainier", s.state.target?.name)
        assertTrue(s.turn)
        // The peak before a flight; a flight alone; nothing kept, no target.
        assertEquals(TargetKind.PEAK, PanoramaTargets.next(TargetState(), Opened(rainier, null, "a1b2c3", "N123AB")).state.target?.kind)
        assertEquals("N123AB", PanoramaTargets.next(TargetState(), Opened(null, null, "a1b2c3", "N123AB")).state.target?.name)
        val none = PanoramaTargets.next(TargetState(target = PanoramaTarget(TargetKind.SUN, "sun", "Sun")), Opened(null, null, null, null))
        assertNull(none.state.target)
        assertFalse(none.turn)
    }

    @Test
    fun backLetsGoOfTheSunOrASatelliteThenAFlightThenAPeakThenIsNotUsed() {
        var s = TargetState(peak = rainier, flight = "a1b2c3", target = PanoramaTarget(TargetKind.SUN, "sun", "Sun"))
        val order = ArrayList<String>()
        while (true) {
            val step = PanoramaTargets.next(s, Back)
            if (!step.used) break
            order += listOfNotNull(s.target?.kind?.key ?: "none", s.flight?.let { "flight" }, s.peak?.let { "peak" }).joinToString("+")
            s = step.state
        }
        assertEquals(listOf("sun+flight+peak", "none+flight+peak", "none+peak"), order)
        assertEquals(TargetState(), s)
        // A peak that is the target: Back lets the pick and the target go together.
        val p = PanoramaTargets.next(PanoramaTargets.next(TargetState(), PeakPicked(rainier)).state, Back)
        assertNull(p.state.target)
        assertNull(p.state.peak)
    }

    @Test
    fun letGoDropsTheTargetAndItsPick() {
        val peak = PanoramaTargets.next(TargetState(), PeakPicked(rainier)).state
        assertEquals(TargetState(), PanoramaTargets.next(peak, LetGo).state)
        val sat = PanoramaTargets.next(peak, SatelliteTapped("gps-12", "GPS 12")).state
        assertEquals(TargetState(peak = rainier), PanoramaTargets.next(sat, LetGo).state)
    }
}
