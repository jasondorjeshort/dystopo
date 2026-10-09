package io.github.jasondorjeshort.dystopo.dem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerticalDatumTest {
    @Test
    fun ellipsoidShiftIsTheTranslationAlongUp() {
        // On the equator at 0° longitude "up" is +X, so the shift is -TX.
        assertEquals(-0.99343, Datums.wgs84MinusNad83Height(0.0, 0.0), 1e-9)
        // At the north pole "up" is +Z.
        assertEquals(0.52655, Datums.wgs84MinusNad83Height(90.0, 0.0), 1e-9)
    }

    @Test
    fun ellipsoidShiftIsAboutAMeterAcrossTheUs() {
        for ((lat, lon) in listOf(46.85 to -121.76, 39.0 to -105.0, 40.7 to -74.0, 25.8 to -80.2)) {
            val shift = Datums.wgs84MinusNad83Height(lat, lon)
            assertTrue(shift in -2.0..0.0, "shift at $lat,$lon = $shift")
        }
    }

    @Test
    fun navd88ToEgm2008CombinesTheModels() {
        val geoid18 = GeoidModel { _, _ -> -17.0 }
        val egm2008 = GeoidModel { _, _ -> -16.5 }
        val shift = Datums.wgs84MinusNad83Height(39.0, -105.0)
        assertEquals(-17.0 + shift + 16.5, Datums.navd88ToEgm2008(39.0, -105.0, geoid18, egm2008), 1e-12)
    }
}
