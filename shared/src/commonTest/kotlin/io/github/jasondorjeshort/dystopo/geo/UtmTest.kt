package io.github.jasondorjeshort.dystopo.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class UtmTest {
    @Test
    fun zones() {
        assertEquals(10, Utm.zoneFor(-121.76)) // Rainier
        assertEquals(32, Utm.zoneFor(6.86)) // Mont Blanc
        assertEquals(1, Utm.zoneFor(-180.0))
        assertEquals(10 to true, Utm.zoneFromEpsg(26910))
        assertEquals(32 to true, Utm.zoneFromEpsg(32632))
        assertEquals(19 to false, Utm.zoneFromEpsg(32719))
    }

    @Test
    fun centralMeridianOnTheEquatorIsTheFalseOrigin() {
        val p = Utm.forward(0.0, -123.0, 10)
        assertEquals(500_000.0, p.easting, 1e-6)
        assertEquals(0.0, p.northing, 1e-6)
    }

    @Test
    fun northingOnCentralMeridianIsScaledMeridianArc() {
        // Meridian arc from the equator to 45°N on GRS80/WGS84 is 4,984,944.378 m; UTM scales it by 0.9996.
        val p = Utm.forward(45.0, -123.0, 10)
        assertEquals(500_000.0, p.easting, 1e-6)
        assertEquals(4_984_944.378 * 0.9996, p.northing, 0.01)
    }

    @Test
    fun roundTripsAcrossTheZone() {
        for (lat in listOf(-45.0, 0.5, 46.85, 70.0)) {
            for (dLon in listOf(-3.0, -1.2, 0.0, 2.7)) {
                val lon = -123.0 + dLon
                val north = lat >= 0
                val p = Utm.forward(lat, lon, 10, north)
                val (lat2, lon2) = Utm.inverse(p.easting, p.northing, 10, north)
                // 1e-8 degrees is about 1 mm; the third-order series is good to well under that.
                assertEquals(lat, lat2, 1e-8)
                assertEquals(lon, lon2, 1e-8)
            }
        }
    }

    @Test
    fun scaleIsAboutOneKilometerPerKilometer() {
        // 0.01° of latitude near 47°N is ~1112 m on the ground; UTM near the central meridian scales by ~0.9996.
        val a = Utm.forward(46.80, -123.0, 10)
        val b = Utm.forward(46.81, -123.0, 10)
        assertEquals(1111.6, b.northing - a.northing, 1.5)
    }
}
