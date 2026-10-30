package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetarAltimeterTest {
    private fun near(expected: Double, actual: Double, tol: Double = 0.01) = assertTrue(abs(expected - actual) <= tol, "expected $expected, got $actual")

    @Test
    fun publishedReportsInHPaAndInches() {
        // The two worked examples of the METAR article of the English Wikipedia (retrieved 2026-10-30).
        val lbbg = assertNotNull(MetarAltimeter.parse("METAR LBBG 041600Z 12012MPS 090V150 1400 R04/P1500N R22/P1500U +SN BKN022 OVC050 M04/M07 Q1020 NOSIG 8849//91="))
        assertEquals("LBBG", lbbg.station)
        assertEquals(Triple(4, 16, 0), Triple(lbbg.day, lbbg.hour, lbbg.minute))
        assertEquals("Q1020", lbbg.group)
        near(1020.0, lbbg.hPa)

        val kttn = assertNotNull(MetarAltimeter.parse("METAR KTTN 051853Z 04011KT 1/2SM VCTS SN FZFG BKN003 OVC010 M02/M02 A3006 RMK AO2 TSB40 SLP176 P0002 T10171017="))
        assertEquals("KTTN", kttn.station)
        assertEquals("A3006", kttn.group)
        near(30.06 * 33.8639, kttn.hPa) // 1017.95 hPa; the remark's SLP176 (1017.6) is not read

        // As published by the aviationweather.gov data API on 2026-10-30 (its JSON gives `altim` rounded to whole hPa: 1014).
        val ksea = assertNotNull(MetarAltimeter.parse("METAR KSEA 300653Z 02004KT 10SM BKN055 BKN070 08/06 A2994 RMK AO2 SLP147 T00780061"))
        assertEquals("KSEA", ksea.station)
        near(1013.88, ksea.hPa)
        val egll = assertNotNull(MetarAltimeter.parse("METAR EGLL 300720Z 07004KT 040V100 1300 R09L/1200D R09R/1500N BR OVC002 10/10 Q1029"))
        near(1029.0, egll.hPa)
    }

    @Test
    fun missingOrRemarksOnlyIsNull() {
        assertNull(MetarAltimeter.parse("METAR KXYZ 301200Z AUTO 00000KT 10SM CLR 10/05 A//// RMK AO2 SLP100"))
        assertNull(MetarAltimeter.parse("METAR KXYZ 301200Z AUTO 00000KT 10SM CLR 10/05 RMK AO2 SLP100 A2992"))
        assertNull(MetarAltimeter.parse("not a report"))
        assertNull(MetarAltimeter.parse("METAR KXYZ 3012Z A2992"))
        assertNull(MetarAltimeter.parse("METAR KXYZ 301200Z A0100")) // 34 hPa: not a sea-level setting
        // SPECI and COR are read like METAR.
        assertEquals("A2992", MetarAltimeter.parse("SPECI COR KXYZ 301210Z 18005KT A2992")?.group)
    }

    @Test
    fun observationTimeTakesMonthAndYearFromTheReference() {
        val ref = GpxFormat.parseTime("2026-10-30T07:10:00Z")!!
        val ksea = MetarAltimeter.parse("METAR KSEA 300653Z 02004KT 10SM A2994")!!
        assertEquals(GpxFormat.parseTime("2026-10-30T06:53:00Z"), ksea.timeMs(ref))
        // A day after the reference's is last month's; 31 September does not exist.
        assertEquals(GpxFormat.parseTime("2026-09-29T23:50:00Z"), MetarAltimeter.parse("METAR KSEA 292350Z A2994")!!.timeMs(GpxFormat.parseTime("2026-10-01T00:20:00Z")!!))
        assertNull(MetarAltimeter.parse("METAR KSEA 312350Z A2994")!!.timeMs(GpxFormat.parseTime("2026-10-01T00:20:00Z")!!))
        assertEquals(GpxFormat.parseTime("2025-12-31T23:50:00Z"), MetarAltimeter.parse("METAR KSEA 312350Z A2994")!!.timeMs(GpxFormat.parseTime("2026-01-01T00:20:00Z")!!))
    }

    @Test
    fun altimeterMath() {
        near(0.0, Altimeter.pressureAltitudeM(Altimeter.STANDARD_HPA), 1e-9)
        // ISA: 1000 m is 898.75 hPa.
        near(1000.0, Altimeter.pressureAltitudeM(898.75), 0.5)
        // 1 hPa is about 8.3 m at sea level and more up high.
        near(8.3, Altimeter.metresPerHPa(1013.0), 0.1)
        assertTrue(Altimeter.metresPerHPa(800.0) > 9.5)
        // The station sigma grows with distance and age.
        val sp = StationPressure("KSEA", 1013.9, 47.4447, -122.3144, 0L)
        near(0.5, sp.sigmaHPa(0.0, 0L))
        assertTrue(sp.sigmaHPa(100_000.0, 0L) > 1.1)
        assertTrue(sp.sigmaHPa(0.0, 12 * 3_600_000L) > 1.2)
    }
}
