package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

class CanadaLambertTest {
    private val atlas = LambertConformalConic.CANADA_ATLAS

    @Test
    fun matchesTheEpsgGuidanceNoteExample() {
        // IOGP Guidance Note 7-2, Lambert Conic Conformal (2SP), NAD27 / Texas South Central (Clarke 1866):
        // 28d30'N 96d00'W -> E 2963503.91 ftUS, N 254759.80 ftUS.
        val ftUs = 1200.0 / 3937.0
        val texas = LambertConformalConic(
            6_378_206.4, 294.978_698_2, 27.0 + 50.0 / 60, -99.0, 28.0 + 23.0 / 60, 30.0 + 17.0 / 60, 2_000_000.0 * ftUs, 0.0,
        )
        val p = texas.forward(28.5, -96.0)
        assertEquals(2_963_503.91 * ftUs, p.easting, 0.01)
        assertEquals(254_759.80 * ftUs, p.northing, 0.01)
        val (lat, lon) = texas.inverse(p.easting, p.northing)
        assertEquals(28.5, lat, 1e-9)
        assertEquals(-96.0, lon, 1e-9)
    }

    @Test
    fun originMapsToZero() {
        val p = atlas.forward(49.0, -95.0)
        assertEquals(0.0, p.easting, 1e-6)
        assertEquals(0.0, p.northing, 1e-6)
    }

    @Test
    fun hrdemTileCornersMatchTheirPublishedFootprint() {
        // HRDEM mosaic tile 2_4 is the EPSG:3979 square x -2000 to -1500 km, y 500 to 1000 km; the NRCan STAC
        // item gives its footprint as lon -126.852867 to -117.040404, lat 49.392567 to 55.396741 (agreeing to about
        // 1 m: the footprint is computed by GDAL from a densified outline, possibly with a datum shift).
        val (latNw, lonNw) = atlas.inverse(-2_000_000.0, 1_000_000.0)
        val (latSe, lonSe) = atlas.inverse(-1_500_000.0, 500_000.0)
        val (latSw, _) = atlas.inverse(-2_000_000.0, 500_000.0)
        val (_, lonNe) = atlas.inverse(-1_500_000.0, 1_000_000.0)
        assertEquals(-126.852867, minOf(lonNw, atlas.inverse(-2_000_000.0, 500_000.0).second), 5e-5)
        assertEquals(-117.040404, maxOf(lonSe, lonNe), 5e-5)
        assertEquals(49.392567, minOf(latSe, latSw), 5e-5)
        assertEquals(55.396741, maxOf(latNw, atlas.inverse(-1_500_000.0, 1_000_000.0).first), 5e-5)
    }

    @Test
    fun roundTripsAcrossCanada() {
        for (lat in listOf(41.7, 45.0, 49.0, 53.1105, 60.0, 69.5, 82.5)) for (lon in listOf(-141.0, -119.1566, -95.0, -75.7, -52.6)) {
            val p = atlas.forward(lat, lon)
            val (lat2, lon2) = atlas.inverse(p.easting, p.northing)
            assertEquals(lat, lat2, 1e-9, "lat at $lat,$lon")
            assertEquals(lon, lon2, 1e-9, "lon at $lat,$lon")
        }
    }

    @Test
    fun mountRobsonFallsInHrdemTile2x4() {
        // Tile i_j covers x from (i - 6) * 500 km and y from (j - 3) * 500 km.
        val p = atlas.forward(53.1105, -119.1566)
        assertEquals(2, floor(p.easting / 500_000).toInt() + 6)
        assertEquals(4, floor(p.northing / 500_000).toInt() + 3)
    }
}
