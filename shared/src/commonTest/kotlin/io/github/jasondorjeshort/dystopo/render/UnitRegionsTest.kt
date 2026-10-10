package io.github.jasondorjeshort.dystopo.render

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitRegionsTest {
    private fun units(lat: Double, lon: Double) = UnitRegions.contourUnitsAt(lat, lon)

    @Test
    fun feetInTheUnitedStates() {
        for ((name, p) in listOf(
            "Rainier" to (46.853 to -121.760),
            "Glacier NP" to (48.70 to -113.80),
            "Bonneville" to (40.75 to -113.90),
            "Denali" to (63.07 to -151.00),
            "Juneau" to (58.30 to -134.42),
            "Mauna Kea" to (19.82 to -155.47),
            "Key West" to (24.56 to -81.78),
            "Cape Cod" to (41.68 to -70.00),
            "El Paso" to (31.79 to -106.42),
            "San Diego" to (32.72 to -117.16),
            "Buffalo" to (42.89 to -78.88),
            "Attu (west of 180)" to (52.90 to 173.20),
            "San Juan PR" to (18.45 to -66.10),
        )) assertEquals(ContourUnits.FEET, units(p.first, p.second), name)
    }

    @Test
    fun metresElsewhere() {
        for ((name, p) in listOf(
            "Mont Blanc" to (45.83 to 6.86),
            "Everest" to (27.98 to 86.92),
            "Waterton (Canada, just north of 49)" to (49.05 to -113.90),
            "Vancouver" to (49.28 to -123.12),
            "Victoria" to (48.43 to -123.37),
            "Toronto" to (43.65 to -79.38),
            "Montreal" to (45.50 to -73.57),
            "Whitehorse" to (60.72 to -135.05),
            "Prince Rupert" to (54.31 to -130.32),
            "Tijuana" to (32.50 to -117.00),
            "Monterrey" to (25.67 to -100.31),
            "Nassau" to (25.05 to -77.35),
            "Havana" to (23.11 to -82.37),
            "Kilimanjaro" to (-3.07 to 37.35),
            "Fiordland" to (-45.40 to 167.70),
        )) assertEquals(ContourUnits.METERS, units(p.first, p.second), name)
    }

    @Test
    fun wrapsLongitude() {
        assertEquals(ContourUnits.FEET, units(46.853, -121.760 + 360.0))
        assertEquals(ContourUnits.METERS, units(45.83, 6.86 - 360.0))
    }
}
