package io.github.jasondorjeshort.dystopo.app.nav

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.Maneuver
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WayClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T126: the spoken words per direction in both unit systems: the card's direction with the distance in front as the
 * card writes it, the "then" cue kept, the direction alone at the turn, and the unit symbols spelt out for the engine.
 */
class SpokenTextTest {
    private fun ins(type: Maneuver, name: String? = null, cls: WayClass? = null, along: Double = 1000.0, lead: Double = 300.0, then: Instruction? = null, bearing: Int = 0) =
        Instruction(type, Instruction.NORMAL, along, 46.78, -121.73, name = name, wayClass = cls, bearingDeg = bearing, leadM = lead, then = then)

    private val left = ins(Maneuver.LEFT, "Stevens Canyon Road")
    private val m = ContourUnits.METERS
    private val ft = ContourUnits.FEET

    @Test
    fun eachTypeInMetres() {
        val drive = TravelMode.DRIVE
        assertEquals("In 300 m, turn left onto Stevens Canyon Road", SpokenText.of(left, 300.0, m, drive))
        assertEquals("In 1.20 km, keep right onto Sunrise Park Road", SpokenText.of(ins(Maneuver.KEEP_RIGHT, "Sunrise Park Road"), 1200.0, m, drive))
        assertEquals("In 150 m, bear left onto the service road", SpokenText.of(ins(Maneuver.SLIGHT_LEFT, cls = WayClass.SERVICE), 150.0, m, drive))
        assertEquals("In 200 m, arrive at the end of the route", SpokenText.of(ins(Maneuver.ARRIVE), 200.0, m, drive))
        assertEquals("In 45 m, turn sharp right onto Wonderland Trail", SpokenText.of(ins(Maneuver.SHARP_RIGHT, "Wonderland Trail"), 45.0, m, TravelMode.HIKE))
    }

    @Test
    fun eachTypeInFeetAndMiles() {
        val drive = TravelMode.DRIVE
        assertEquals("In 0.2 mi, turn left onto Stevens Canyon Road", SpokenText.of(left, 300.0, ft, drive))
        assertEquals("In 0.7 mi, keep right onto Sunrise Park Road", SpokenText.of(ins(Maneuver.KEEP_RIGHT, "Sunrise Park Road"), 1200.0, ft, drive))
        assertEquals("In 492 ft, arrive at the end of the route", SpokenText.of(ins(Maneuver.ARRIVE), 150.0, ft, drive))
        assertEquals("In 148 ft, turn sharp right onto Wonderland Trail", SpokenText.of(ins(Maneuver.SHARP_RIGHT, "Wonderland Trail"), 45.0, ft, TravelMode.HIKE))
    }

    @Test
    fun theThenCueIsKept() {
        val cued = left.copy(then = ins(Maneuver.RIGHT, "Mather Memorial Parkway", along = 1100.0))
        assertEquals("In 300 m, turn left onto Stevens Canyon Road, then turn right", SpokenText.of(cued, 300.0, m, TravelMode.DRIVE))
        assertEquals("In 492 ft, turn left onto Stevens Canyon Road, then turn right", SpokenText.of(cued, 150.0, ft, TravelMode.DRIVE))
    }

    @Test
    fun atTheTurnTheDirectionAlone() {
        // Closer than navigation's pass distance (40 m driving, 10 m walking) there is no distance to say.
        assertEquals("Turn left onto Stevens Canyon Road", SpokenText.of(left, Directions.passM(TravelMode.DRIVE) - 1, m, TravelMode.DRIVE))
        assertEquals("Head south on Paradise Road", SpokenText.of(ins(Maneuver.DEPART, "Paradise Road", along = 0.0, bearing = 180), 0.0, ft, TravelMode.DRIVE))
        assertEquals("In 40 m, turn left onto Stevens Canyon Road", SpokenText.of(left, 40.0, m, TravelMode.DRIVE))
    }

    @Test
    fun theVoiceSpellsOutTheUnits() {
        assertEquals("In 300 metres, turn left onto Stevens Canyon Road", SpokenText.forVoice("In 300 m, turn left onto Stevens Canyon Road"))
        assertEquals("In 1 metre, turn left", SpokenText.forVoice("In 1 m, turn left"))
        assertEquals("In 1.20 kilometres, keep right", SpokenText.forVoice("In 1.20 km, keep right"))
        assertEquals("In 984 feet, turn left", SpokenText.forVoice("In 984 ft, turn left"))
        assertEquals("In 1 foot, turn left", SpokenText.forVoice("In 1 ft, turn left"))
        assertEquals("In 0.1 miles, keep right", SpokenText.forVoice("In 0.1 mi, keep right"))
        assertEquals("In 21 miles, keep right onto the mile road", SpokenText.forVoice("In 21 mi, keep right onto the mile road"))
        // A way named with a number and a letter is left alone unless the letter is a unit after a space.
        assertEquals("Turn left onto Route 410", SpokenText.forVoice("Turn left onto Route 410"))
    }

    @Test
    fun theGateSaysEachDirectionOnce() {
        val g = SpeechGate()
        assertFalse(g.take(0, 400.0, 300.0, enabled = true), "outside the lead")
        assertTrue(g.take(0, 290.0, 300.0, enabled = true))
        assertFalse(g.take(0, 100.0, 300.0, enabled = true), "never twice")
        assertFalse(g.take(1, 10.0, 300.0, enabled = false), "nothing while off")
        assertEquals(1, g.skippedOff)
        assertTrue(g.take(1, 5.0, 300.0, enabled = true), "turned on before the turn: said then")
        g.reset()
        assertTrue(g.take(0, 0.0, 300.0, enabled = true), "a new navigation starts afresh")
        assertEquals(0, g.skippedOff)
    }
}
