package io.github.jasondorjeshort.dystopo.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals("5.1 mi", UnitFormat.distance(8141.0, ContourUnits.FEET)) // "5.06 mi" before T101
    }

    @Test
    fun metricDistanceRuleIsUnchangedExceptAtRoundingEdges() {
        assertEquals("0 m", UnitFormat.distance(0.0, ContourUnits.METERS))
        assertEquals("999 m", UnitFormat.distance(999.4, ContourUnits.METERS))
        assertEquals("1.00 km", UnitFormat.distance(999.6, ContourUnits.METERS)) // "1000 m" before T101
        assertEquals("1.00 km", UnitFormat.distance(1000.0, ContourUnits.METERS))
        assertEquals("9.99 km", UnitFormat.distance(9_994.0, ContourUnits.METERS))
        assertEquals("10.0 km", UnitFormat.distance(9_996.0, ContourUnits.METERS)) // "10.00 km" before T101
        assertEquals("99.9 km", UnitFormat.distance(99_940.0, ContourUnits.METERS))
        assertEquals("100 km", UnitFormat.distance(99_960.0, ContourUnits.METERS))
        assertEquals("81.9 km", UnitFormat.distance(81_937.1, ContourUnits.METERS)) // Paradise to Sunrise by road
        assertEquals("4500 km", UnitFormat.distance(4_500_000.0, ContourUnits.METERS))
    }

    @Test
    fun imperialReadsFeetBelowATenthOfAMileThenMilesWithOneDecimal() {
        val mi = UnitFormat.METERS_PER_MILE
        assertEquals("0 ft", UnitFormat.distance(0.0, ContourUnits.FEET))
        assertEquals("30 ft", UnitFormat.distance(9.144, ContourUnits.FEET))
        assertEquals("500 ft", UnitFormat.distance(152.4, ContourUnits.FEET))
        assertEquals("527 ft", UnitFormat.distance(527.4 * 0.3048, ContourUnits.FEET))
        // 527.6 ft would show as 528 ft, a tenth of a mile: miles from there.
        assertEquals("0.1 mi", UnitFormat.distance(527.6 * 0.3048, ContourUnits.FEET))
        assertEquals("0.1 mi", UnitFormat.distance(0.1 * mi, ContourUnits.FEET))
        assertEquals("0.6 mi", UnitFormat.distance(1000.0, ContourUnits.FEET)) // the largest route snap; "0.62 mi" before
        assertEquals("1.0 mi", UnitFormat.distance(mi, ContourUnits.FEET))
        assertEquals("9.9 mi", UnitFormat.distance(9.94 * mi, ContourUnits.FEET))
        assertEquals("10.0 mi", UnitFormat.distance(9.96 * mi, ContourUnits.FEET))
        assertEquals("50.9 mi", UnitFormat.distance(81_937.1, ContourUnits.FEET)) // Paradise to Sunrise: miles, never 268825 ft
        assertEquals("99.9 mi", UnitFormat.distance(99.94 * mi, ContourUnits.FEET))
        assertEquals("100 mi", UnitFormat.distance(99.96 * mi, ContourUnits.FEET))
        assertEquals("2796 mi", UnitFormat.distance(4_500_000.0, ContourUnits.FEET))
        assertEquals(DistanceUnit.FEET, UnitFormat.distanceUnit(160.0, ContourUnits.FEET))
        assertEquals(DistanceUnit.MILES, UnitFormat.distanceUnit(161.0, ContourUnits.FEET))
        assertEquals(DistanceUnit.METERS, UnitFormat.distanceUnit(999.0, ContourUnits.METERS))
        assertEquals(DistanceUnit.KILOMETERS, UnitFormat.distanceUnit(1000.0, ContourUnits.METERS))
    }

    @Test
    fun boxSizesUseTheLongerSidesUnit() {
        assertEquals("120 x 85 km", UnitFormat.size(120_300.0, 85_100.0, ContourUnits.METERS))
        assertEquals("75 x 53 mi", UnitFormat.size(120_300.0, 85_100.0, ContourUnits.FEET))
        assertEquals("4.2 x 0.9 km", UnitFormat.size(4_200.0, 900.0, ContourUnits.METERS))
        assertEquals("800 x 650 m", UnitFormat.size(800.0, 650.0, ContourUnits.METERS))
        assertEquals("394 x 328 ft", UnitFormat.size(120.0, 100.0, ContourUnits.FEET))
    }

    @Test
    fun metresPerPixelFollowZoomAndLatitude() {
        // The 512-pixel world of MapLibre: 78271.5 m per pixel at zoom 0 on the equator, halved per zoom.
        assertEquals(78_271.517, UnitFormat.metersPerPixel(0.0, 0.0), 0.001)
        assertEquals(78_271.517 / 8192, UnitFormat.metersPerPixel(13.0, 0.0), 1e-6)
        // At Rainier (46.85 N) a pixel is cos(46.85) = 0.6839 as long; far north at Svalbard (78.2 N) 0.2045.
        assertEquals(0.68391, UnitFormat.metersPerPixel(10.0, 46.85) / UnitFormat.metersPerPixel(10.0, 0.0), 1e-5)
        assertEquals(0.20450, UnitFormat.metersPerPixel(10.0, 78.2) / UnitFormat.metersPerPixel(10.0, 0.0), 1e-5)
        // Web Mercator stops at 85.05 degrees; the pole would give 0.
        assertEquals(UnitFormat.metersPerPixel(5.0, 85.051129), UnitFormat.metersPerPixel(5.0, 90.0), 1e-12)
        // A 256-pixel world has twice the metres per pixel.
        assertEquals(2 * UnitFormat.metersPerPixel(12.0, 46.85), UnitFormat.metersPerPixel(12.0, 46.85, tileSize = 256.0), 1e-9)
    }

    @Test
    fun niceLengthsAreOneTwoOrFiveTimesAPowerOfTen() {
        assertEquals(1.0, UnitFormat.niceFloor(1.0))
        assertEquals(1.0, UnitFormat.niceFloor(1.99))
        assertEquals(2.0, UnitFormat.niceFloor(2.0))
        assertEquals(2.0, UnitFormat.niceFloor(4.99))
        assertEquals(5.0, UnitFormat.niceFloor(5.0))
        assertEquals(5.0, UnitFormat.niceFloor(9.99))
        assertEquals(1000.0, UnitFormat.niceFloor(1000.0), 1e-9)
        assertEquals(1000.0, UnitFormat.niceFloor(1999.0), 1e-9)
        assertEquals(0.1, UnitFormat.niceFloor(0.1), 1e-12)
        assertEquals(0.05, UnitFormat.niceFloor(0.07), 1e-12)
        assertEquals(0.2, UnitFormat.niceFloor(0.3), 1e-12)
    }

    @Test
    fun scaleBarPicksTheDistanceRulesUnit() {
        fun bar(maxM: Double, units: ContourUnits) = UnitFormat.scaleBar(maxM / 100, 100.0, units)!!
        // Metric: metres up to 500 m, kilometres from 1 km.
        assertEquals("500 m", bar(999.0, ContourUnits.METERS).label)
        assertEquals("1 km", bar(1000.0, ContourUnits.METERS).label)
        assertEquals("1 km", bar(1999.0, ContourUnits.METERS).label)
        assertEquals("2 km", bar(4999.0, ContourUnits.METERS).label)
        assertEquals("50 km", bar(60_000.0, ContourUnits.METERS).label)
        assertEquals("20 m", bar(45.0, ContourUnits.METERS).label)
        // Imperial: feet up to 500 ft, miles from 0.1 mi (528 ft); never "1000 ft" or "2000 ft".
        assertEquals("200 ft", bar(150.0, ContourUnits.FEET).label) // 492 ft fits; 0.05 mi would be under a tenth
        assertEquals("500 ft", bar(160.0, ContourUnits.FEET).label) // 525 ft fits
        assertEquals("0.1 mi", bar(161.0, ContourUnits.FEET).label)
        assertEquals("0.2 mi", bar(500.0, ContourUnits.FEET).label)
        assertEquals("0.5 mi", bar(1500.0, ContourUnits.FEET).label)
        assertEquals("1 mi", bar(1700.0, ContourUnits.FEET).label)
        assertEquals("20 mi", bar(40_000.0, ContourUnits.FEET).label)
        assertEquals("5 ft", bar(2.0, ContourUnits.FEET).label)
        val b = bar(1500.0, ContourUnits.FEET)
        assertEquals(DistanceUnit.MILES, b.unit)
        assertEquals(0.5, b.value, 1e-12)
        assertEquals(0.5 * UnitFormat.METERS_PER_MILE, b.meters, 1e-9)
        assertEquals(b.meters / 15.0, b.px, 1e-9)
        assertEquals(null, UnitFormat.scaleBar(0.0, 100.0, ContourUnits.METERS))
        assertEquals(null, UnitFormat.scaleBar(Double.NaN, 100.0, ContourUnits.FEET))
        assertEquals(null, UnitFormat.scaleBar(1.0, 0.0, ContourUnits.FEET))
    }

    @Test
    fun scaleBarFitsAndAgreesWithTheDistanceRuleAtEveryZoom() {
        // Every zoom from 3 to 18 in tenths, far north and south of Rainier too: the bar fits,
        // is at least 40% of the target, is a nice number, and reads in the unit the distance rule gives its length.
        for (lat in listOf(46.85, 78.2, -51.0, 0.0)) { // Rainier, Svalbard, Torres del Paine, the equator
            for (step in 30..180) {
                val z = step / 10.0
                val mpp = UnitFormat.metersPerPixel(z, lat)
                for (units in ContourUnits.entries) {
                    val b = UnitFormat.scaleBar(mpp, 110.0, units)!!
                    assertTrue(b.px <= 110.0 + 1e-6 && b.px >= 44.0 - 1e-6, "z$z lat $lat $units: ${b.px} px")
                    assertEquals(b.unit, UnitFormat.distanceUnit(b.meters, units), "z$z lat $lat ${b.label}")
                    assertEquals(b.value, UnitFormat.niceFloor(b.value), 1e-9 * b.value)
                    assertEquals(b.meters, b.px * mpp, 1e-6 * b.meters)
                }
            }
        }
        // The same zoom draws a shorter length far north: a pixel covers fewer metres there.
        assertEquals("20 km", UnitFormat.scaleBar(UnitFormat.metersPerPixel(8.0, 46.85), 110.0, ContourUnits.METERS)!!.label)
        assertEquals("5 km", UnitFormat.scaleBar(UnitFormat.metersPerPixel(8.0, 78.2), 110.0, ContourUnits.METERS)!!.label)
        assertEquals("10 mi", UnitFormat.scaleBar(UnitFormat.metersPerPixel(8.0, 46.85), 110.0, ContourUnits.FEET)!!.label)
        assertEquals("2 mi", UnitFormat.scaleBar(UnitFormat.metersPerPixel(8.0, 78.2), 110.0, ContourUnits.FEET)!!.label)
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
