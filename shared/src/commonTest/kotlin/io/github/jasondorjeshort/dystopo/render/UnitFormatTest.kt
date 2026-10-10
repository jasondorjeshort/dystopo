package io.github.jasondorjeshort.dystopo.render

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitFormatTest {
    @Test
    fun elevationsInBothUnits() {
        assertEquals("4392 m", UnitFormat.elevation(4392.0, ContourUnits.METERS))
        assertEquals("14410 ft", UnitFormat.elevation(4392.2, ContourUnits.FEET))
        assertEquals("13200 ft", UnitFormat.elevation(13200 * 0.3048, ContourUnits.FEET)) // no grouping separator
    }

    @Test
    fun distancesStepThroughPrecisions() {
        assertEquals("850 m", UnitFormat.distance(850.0, ContourUnits.METERS))
        assertEquals("8.14 km", UnitFormat.distance(8141.0, ContourUnits.METERS))
        assertEquals("23.5 km", UnitFormat.distance(23_456.0, ContourUnits.METERS))
        assertEquals("312 km", UnitFormat.distance(312_400.0, ContourUnits.METERS))
        assertEquals("450 ft", UnitFormat.distance(137.16, ContourUnits.FEET))
        assertEquals("5.06 mi", UnitFormat.distance(8141.0, ContourUnits.FEET))
    }

    @Test
    fun fixedDecimals() {
        assertEquals("0.05", UnitFormat.fixed(0.049, 2))
        assertEquals("-1.5", UnitFormat.fixed(-1.46, 1))
        assertEquals("0.00", UnitFormat.fixed(-0.001, 2))
        assertEquals("10", UnitFormat.fixed(9.6, 0))
    }

    @Test
    fun preferenceIsSeededOnceAndThenKept() {
        val unset = UnitPreference()
        assertEquals(ContourUnits.METERS, unset.effective(ContourUnits.METERS))
        // Home is Rainier: seeded to feet.
        val home = unset.seeded(46.85, -121.76, "pack:rainier")
        assertEquals(UnitPreference(ContourUnits.FEET, "pack:rainier"), home)
        // Travelling to Mont Blanc keeps feet: a set preference is never re-seeded.
        assertEquals(home, home.seeded(45.83, 6.86, "fix"))
        // A first seed abroad gives metres.
        assertEquals(ContourUnits.METERS, unset.seeded(45.83, 6.86, "fix").units)
        // The user's choice wins over any seed.
        val chosen = UnitPreference(ContourUnits.METERS, "user")
        assertEquals(chosen, chosen.seeded(46.85, -121.76, "pack:rainier"))
        assertEquals(unset, unset.seeded(Double.NaN, 0.0, "fix"))
    }
}
