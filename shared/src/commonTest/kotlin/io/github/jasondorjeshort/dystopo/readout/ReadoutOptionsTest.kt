package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadoutOptionsTest {
    @Test
    fun defaultsPerActivity() {
        fun shown(a: Activity) = ReadoutEntry.entries.filter { ReadoutOptions.defaults(a)[it].shown }.map { it.code }
        assertEquals(listOf("speed", "climb", "course", "elevation"), shown(Activity.FOOT))
        assertEquals(listOf("speed", "course", "elevation"), shown(Activity.BIKE))
        assertEquals(listOf("speed", "elevation"), shown(Activity.ROAD))
        assertEquals(listOf("speed", "elevation"), shown(Activity.RAIL))
        assertEquals(listOf("speed", "course"), shown(Activity.BOAT))
        assertEquals(listOf("speed", "climb", "course", "elevation"), shown(Activity.AIR))
        for (a in Activity.entries) {
            val o = ReadoutOptions.defaults(a)
            assertTrue(o.barometer)
            assertFalse(o.speed.pace || o.speed.knots, "$a: speed, not pace, and never knots by default")
            assertTrue(ReadoutEntry.entries.none { o[it].plusMinus } && ReadoutEntry.entries.all { o[it].damping == Damping.MEDIUM })
        }
    }

    @Test
    fun knotsOnlyForBoatAndAir() {
        for (a in Activity.entries) {
            val o = ReadoutOptions.defaults(a).let { it.with(ReadoutEntry.SPEED, it.speed.copy(knots = true)) }
            assertEquals(a == Activity.BOAT || a == Activity.AIR, o.knots, "$a")
            assertEquals(if (o.knots) "kn" else "km/h", ReadoutText.speedUnit(o, ContourUnits.METERS))
        }
    }

    @Test
    fun entryOptionsRoundTripAndTolerateNewKeys() {
        val e = EntryOptions(shown = false, pace = true, damping = Damping.HIGH, plusMinus = true, knots = true)
        assertEquals("shown=0;pace=1;damping=high;pm=1;knots=1", e.encode())
        assertEquals(e, EntryOptions.decode(e.encode(), EntryOptions(shown = true)))
        val def = EntryOptions(shown = true)
        assertEquals(def.copy(damping = Damping.LOW), EntryOptions.decode("damping=low;future=7;pm=x", def))
        assertEquals(def, EntryOptions.decode(null, def))
        assertEquals(ReadoutEntry.CLIMB, ReadoutEntry.of("climb"))
    }

    @Test
    fun textInTheUsersUnits() {
        val foot = ReadoutOptions.defaults(Activity.FOOT)
        fun speed(mps: Double, o: ReadoutOptions = foot, u: ContourUnits = ContourUnits.METERS) =
            ReadoutText.of(ReadoutValue(ReadoutEntry.SPEED, ValueState.SHOWN, mps, 0.1), o, u)!!.full
        assertEquals("4.5 km/h", speed(1.3)) // T161: half units below 10
        assertEquals("3.0 mph", speed(1.3, u = ContourUnits.FEET))
        assertEquals("1.5 mph", speed(0.645, u = ContourUnits.FEET))
        assertEquals("0.0 km/h", speed(0.01))
        assertEquals("12 km/h", speed(3.3))
        val pace = foot.with(ReadoutEntry.SPEED, foot.speed.copy(pace = true))
        assertEquals("12:50 /km", speed(1.3, pace))
        assertEquals("20:40 /mi", speed(1.3, pace, ContourUnits.FEET))
        val boat = ReadoutOptions.defaults(Activity.BOAT).let { it.with(ReadoutEntry.SPEED, it.speed.copy(knots = true)) }
        assertEquals("10 kn", speed(5.144, boat))
        assertEquals("– km/h", ReadoutText.of(ReadoutValue(ReadoutEntry.SPEED, ValueState.DASH), foot, ContourUnits.METERS)!!.full)
        assertNull(ReadoutText.of(ReadoutValue(ReadoutEntry.CLIMB, ValueState.HIDDEN), foot, ContourUnits.METERS))

        fun climb(mps: Double, o: ReadoutOptions, u: ContourUnits) = ReadoutText.of(ReadoutValue(ReadoutEntry.CLIMB, ValueState.SHOWN, mps, 0.01), o, u)!!.full
        assertEquals("360 m/h", climb(0.1, foot, ContourUnits.METERS))
        assertEquals("1180 ft/h", climb(0.1, foot, ContourUnits.FEET))
        val air = ReadoutOptions.defaults(Activity.AIR)
        assertEquals("1000 ft/min", climb(5.08, air, ContourUnits.FEET))
        assertEquals("-305 m/min", climb(-5.08, air, ContourUnits.METERS))

        val withPm = foot.with(ReadoutEntry.ELEVATION, foot.elevation.copy(plusMinus = true))
        assertEquals("1645 m ± 4", ReadoutText.of(ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, 1645.2, 3.6), withPm, ContourUnits.METERS)!!.full)
        assertEquals("5397 ft", ReadoutText.of(ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, 1645.0, 3.6), foot, ContourUnits.FEET)!!.full)
        assertEquals("245°", ReadoutText.of(ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, 244.6, 5.0), foot, ContourUnits.METERS)!!.full)
        assertEquals("0°", ReadoutText.of(ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, 359.7, 5.0), foot, ContourUnits.METERS)!!.full)
    }
}
