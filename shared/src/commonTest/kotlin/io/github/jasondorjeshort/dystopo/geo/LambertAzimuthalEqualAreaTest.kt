package io.github.jasondorjeshort.dystopo.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class LambertAzimuthalEqualAreaTest {
    private val laea = LambertAzimuthalEqualArea.EUROPE

    @Test
    fun matchesTheEpsgGuidanceNoteExample() {
        // IOGP Guidance Note 7-2, Lambert Azimuthal Equal Area (EPSG:3035): 50N 5E -> 3962799.45, 2999718.85.
        val p = laea.forward(50.0, 5.0)
        assertEquals(3_962_799.45, p.easting, 0.01)
        assertEquals(2_999_718.85, p.northing, 0.01)
        val (lat, lon) = laea.inverse(3_962_799.45, 2_999_718.85)
        assertEquals(50.0, lat, 1e-7) // the published E, N are rounded to 5 mm
        assertEquals(5.0, lon, 1e-7)
    }

    @Test
    fun originMapsToFalseOrigin() {
        val p = laea.forward(52.0, 10.0)
        assertEquals(4_321_000.0, p.easting, 1e-6)
        assertEquals(3_210_000.0, p.northing, 1e-6)
        val (lat, lon) = laea.inverse(4_321_000.0, 3_210_000.0)
        assertEquals(52.0, lat, 1e-8) // the latitude series is good to a fraction of a millimetre
        assertEquals(10.0, lon, 1e-9)
    }

    @Test
    fun roundTripsAcrossAustriaAndEurope() {
        for (lat in listOf(36.0, 46.4, 47.0742, 48.2, 49.0, 61.6, 70.0)) for (lon in listOf(-9.0, 9.5, 12.6947, 17.2, 30.0)) {
            val p = laea.forward(lat, lon)
            val (lat2, lon2) = laea.inverse(p.easting, p.northing)
            // 1e-8 degrees is about a millimetre.
            assertEquals(lat, lat2, 1e-8, "lat at $lat,$lon")
            assertEquals(lon, lon2, 1e-8, "lon at $lat,$lon")
        }
    }

    @Test
    fun grossglocknerFallsInItsBevTile() {
        // BEV publishes the ALS DTM as 50 km EPSG:3035 tiles named by their south-west corner; the
        // Grossglockner summit is the maximum (3797.76 m) of tile N2650000 E4500000.
        val p = laea.forward(47.0742, 12.6947)
        assertEquals(4_500_000.0, kotlin.math.floor(p.easting / 50_000) * 50_000)
        assertEquals(2_650_000.0, kotlin.math.floor(p.northing / 50_000) * 50_000)
    }
}
