package io.github.jasondorjeshort.dystopo.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against swisstopo's published examples: "Formulas and constants for the calculation of the Swiss
 * conformal cylindrical projection" (refsys), chapter 3 (Rigi) and chapter 7.1 (the five EUREF
 * points, computed with REFRAME), and the approximate-formula note (ch1903wgs84).
 */
class SwissProjectionTest {
    private fun dms(d: Int, m: Int, s: Double) = d + m / 60.0 + s / 3600.0

    /** 1e-8 degrees is ~1 mm. */
    private val mm = 1e-8

    private class Ref(
        val name: String, val e: Double, val n: Double,
        val besselLat: Double, val besselLon: Double, val besselH: Double,
        val etrsLat: Double, val etrsLon: Double, val etrsH: Double,
    )

    private val euref = listOf(
        Ref("Zimmerwald", 2_602_030.740, 1_191_775.030, dms(46, 52, 42.269284), dms(7, 27, 58.416328), 897.361, dms(46, 52, 37.540562), dms(7, 27, 54.983506), 947.149),
        Ref("Chrischona", 2_617_306.920, 1_268_507.870, dms(47, 34, 6.404965), dms(7, 40, 10.574820), 457.138, dms(47, 34, 1.385301), dms(7, 40, 6.983077), 504.935),
        Ref("Pfaender", 2_776_668.590, 1_265_372.250, dms(47, 31, 0.092644), dms(9, 47, 8.465989), 1043.616, dms(47, 30, 55.172797), dms(9, 47, 3.697723), 1089.372),
        Ref("La Givrine", 2_497_312.650, 1_145_626.140, dms(46, 27, 19.272743), dms(6, 6, 9.983811), 1206.367, dms(46, 27, 14.690021), dms(6, 6, 7.326361), 1258.274),
        Ref("Monte Generoso", 2_722_759.060, 1_087_648.190, dms(45, 55, 49.707052), dms(9, 1, 20.606368), 1634.472, dms(45, 55, 45.438020), dms(9, 1, 16.389053), 1685.027),
    )

    @Test
    fun rigiProjectsAsInTheWorkedExample() {
        val p = SwissProjection.besselToLv95(dms(47, 3, 28.95659233), dms(8, 29, 11.11127154))
        assertEquals(2_679_520.05, p.easting, 0.005)
        assertEquals(1_212_273.44, p.northing, 0.005)
        val (lat, lon) = SwissProjection.lv95ToBessel(2_679_520.05, 1_212_273.44)
        assertEquals(dms(47, 3, 28.95659233), lat, 1e-7)
        assertEquals(dms(8, 29, 11.11127154), lon, 1e-7)
    }

    @Test
    fun bernIsTheFalseOrigin() {
        val p = SwissProjection.besselToLv95(dms(46, 57, 8.66), dms(7, 26, 22.50))
        assertEquals(2_600_000.0, p.easting, 1e-6)
        assertEquals(1_200_000.0, p.northing, 1e-6)
    }

    @Test
    fun eurefPointsProjectionOnBessel() {
        for (r in euref) {
            val p = SwissProjection.besselToLv95(r.besselLat, r.besselLon)
            assertEquals(r.e, p.easting, 0.002, r.name)
            assertEquals(r.n, p.northing, 0.002, r.name)
            val (lat, lon) = SwissProjection.lv95ToBessel(r.e, r.n)
            assertEquals(r.besselLat, lat, 5 * mm, r.name)
            assertEquals(r.besselLon, lon, 5 * mm, r.name)
        }
    }

    @Test
    fun eurefPointsWgs84() {
        for (r in euref) {
            val p = SwissProjection.forward(r.etrsLat, r.etrsLon, r.etrsH)
            assertEquals(r.e, p.easting, 0.002, r.name)
            assertEquals(r.n, p.northing, 0.002, r.name)
            val (lat, lon) = SwissProjection.inverse(r.e, r.n, r.besselH)
            assertEquals(r.etrsLat, lat, 2 * mm, r.name)
            assertEquals(r.etrsLon, lon, 2 * mm, r.name)
            // Without heights (how the DEM source calls it) the error stays around a centimetre.
            val flat = SwissProjection.forward(r.etrsLat, r.etrsLon)
            assertEquals(r.e, flat.easting, 0.05, r.name)
            assertEquals(r.n, flat.northing, 0.05, r.name)
        }
    }

    @Test
    fun approximateFormulaExample() {
        // ch1903wgs84: 46°02'38.87", 8°43'49.79" -> E 2 699 999.76, N 1 099 999.97 (exact: 2 700 000 / 1 100 000).
        val lat = dms(46, 2, 38.87)
        val lon = dms(8, 43, 49.79)
        val approx = SwissProjection.forwardApproximate(lat, lon)
        assertEquals(2_699_999.76, approx.easting, 0.01)
        assertEquals(1_099_999.97, approx.northing, 0.01)
        // The rigorous path lands on the exact value to the precision the note gives it (0.01").
        val exact = SwissProjection.forward(lat, lon)
        assertEquals(2_700_000.0, exact.easting, 0.3)
        assertEquals(1_100_000.0, exact.northing, 0.3)
    }

    @Test
    fun rigorousAndApproximateAgreeAcrossSwitzerland() {
        var worst = 0.0
        var lat = 45.8
        while (lat <= 47.8) {
            var lon = 5.95
            while (lon <= 10.5) {
                val a = SwissProjection.forward(lat, lon)
                val b = SwissProjection.forwardApproximate(lat, lon)
                worst = maxOf(worst, kotlin.math.hypot(a.easting - b.easting, a.northing - b.northing))
                lon += 0.25
            }
            lat += 0.25
        }
        // swisstopo: the approximate formulas are better than 1 m in Switzerland (the box corners sit a little outside).
        assertTrue(worst < 1.5, "worst disagreement $worst m")
    }

    @Test
    fun roundTripIsWithinMillimetres() {
        for (e in listOf(2_480_000.0, 2_600_000.0, 2_617_000.0, 2_830_000.0)) {
            for (n in listOf(1_070_000.0, 1_091_000.0, 1_200_000.0, 1_300_000.0)) {
                val (lat, lon) = SwissProjection.inverse(e, n)
                val p = SwissProjection.forward(lat, lon)
                // The height is dropped on each datum shift, so the round trip is good to a few mm.
                assertEquals(e, p.easting, 5e-3)
                assertEquals(n, p.northing, 5e-3)
            }
        }
    }
}
