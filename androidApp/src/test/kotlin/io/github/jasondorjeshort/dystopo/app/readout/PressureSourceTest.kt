package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.readout.Altimeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T173: the weather station fetch's box, request, response reading and station choice (the pure parts of [PressureSource]). */
class PressureSourceTest {
    private val now = GpxFormat.parseTime("2026-10-30T17:20:00Z")!!

    @Test
    fun boxIsWholeDegreesAroundTheView() {
        // Paradise on Rainier: the cell 46N..47N, 122W..121W, one degree of latitude and two of longitude on each side.
        val b = PressureSource.boxAround(46.786, -121.735)
        assertEquals(PressureSource.Box(west = -124, south = 45, east = -119, north = 48), b)
        // Anywhere in the same cell gives the same box: the request says which cell, not where in it.
        assertEquals(b, PressureSource.boxAround(46.01, -121.99))
        // The equator: one degree of longitude holds 100 km.
        assertEquals(PressureSource.Box(-1, -2, 2, 1), PressureSource.boxAround(-0.5, 0.5))
        // Far north the longitude pad is capped at three degrees; the edges stay on the globe.
        val n = PressureSource.boxAround(89.5, 179.5)
        assertEquals(PressureSource.Box(176, 88, 180, 90), n)
    }

    @Test
    fun requestCarriesOnlyTheBoxAndTheFormat() {
        val url = PressureSource.requestUrl(PressureSource.DEFAULT_URL, PressureSource.boxAround(46.786, -121.735))
        assertEquals("https://aviationweather.gov/api/data/metar?bbox=45,-124,48,-119&format=json", url)
        val q = url.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf("bbox", "format"), q)
        assertTrue(url.substringAfter("bbox=").substringBefore('&').split(',').all { it.toIntOrNull() != null }, "whole degrees only")
    }

    private val response = """
        [
          {"icaoId":"KAAA","obsTime":1793380500,"altim":1012,"rawOb":"METAR KAAA 301715Z AUTO 00000KT 10SM OVC006 07/07 A2988 RMK AO2 SLP120","lat":46.9,"lon":-121.6,"elev":500},
          {"icaoId":"KBBB","obsTime":1793380500,"altim":1013,"rawOb":"METAR KBBB 301715Z 05006KT 10SM OVC048 08/04 A2991 RMK AO2","lat":46.2,"lon":-121.7,"elev":183},
          {"icaoId":"KAAA","obsTime":1793376900,"altim":1012,"rawOb":"METAR KAAA 301615Z AUTO 00000KT 10SM OVC006 07/07 A2985 RMK AO2","lat":46.9,"lon":-121.6,"elev":500},
          {"icaoId":"KCCC","obsTime":1793380500,"rawOb":"METAR KCCC 301715Z AUTO 00000KT 10SM A//// RMK AO2","lat":46.5,"lon":-121.0},
          {"icaoId":"EDDD","obsTime":1793380500,"rawOb":"METAR EDDD 301650Z 07004KT 9999 OVC020 10/10 Q1019","lat":47.9,"lon":-119.5},
          {"icaoId":"KOLD","obsTime":1793362500,"rawOb":"METAR KOLD 301215Z 00000KT 10SM A3001","lat":46.8,"lon":-121.8},
          {"icaoId":"KNOP"}
        ]
    """.trimIndent()

    @Test
    fun responseIsReadFromTheRawReports() {
        val r = PressureSource.parse(response, now)
        assertEquals(7, r.reports)
        assertEquals(2, r.unusable) // A//// and the entry with no report
        assertEquals(listOf("KAAA", "KBBB", "EDDD", "KOLD"), r.stations.map { it.id })
        val a = r.stations.first { it.id == "KAAA" }
        // The latest of KAAA's two reports, its value from the raw A2988 (the JSON's altim is rounded).
        assertEquals(GpxFormat.parseTime("2026-10-30T17:15:00Z"), a.timeMs)
        assertEquals(29.88 * Altimeter.HPA_PER_INHG, a.hPa, 1e-9)
        assertEquals("A2988", a.group)
        assertEquals(1019.0, r.stations.first { it.id == "EDDD" }.hPa)
    }

    @Test
    fun theNearestFreshStationWithinRangeIsChosen() {
        val st = PressureSource.parse(response, now).stations
        // From Paradise: KOLD is nearest but five hours old; EDDD is past 100 km; KAAA (about 16 km) beats KBBB (about 65 km).
        val ranked = PressureSource.inRange(st, 46.786, -121.735, now)
        assertEquals(listOf("KAAA", "KBBB"), ranked.map { it.first.id })
        assertTrue(ranked[0].second in 10_000.0..20_000.0, "${ranked[0].second}")
        assertEquals("KAAA", PressureSource.choose(ranked, null)?.first?.id)
        // Nothing in range: no prior.
        assertNull(PressureSource.choose(PressureSource.inRange(st, 40.0, -110.0, now), null))
        // Four hours later every report is too old.
        assertTrue(PressureSource.inRange(st, 46.786, -121.735, now + 4 * 3_600_000L).isEmpty())
    }

    @Test
    fun theChosenStationIsKeptUnlessClearlyBeaten() {
        val st = PressureSource.parse(response, now).stations
        val a = st.first { it.id == "KAAA" }
        val b = st.first { it.id == "KBBB" }
        // Between the two, a little nearer KBBB: KAAA is kept (the margin is 10 km), so its report is not applied twice over.
        val mid = PressureSource.inRange(st, 46.53, -121.65, now)
        assertEquals("KBBB", mid.first().first.id)
        assertEquals("KAAA", PressureSource.choose(mid, a)?.first?.id)
        // Next to KBBB it is replaced.
        assertEquals("KBBB", PressureSource.choose(PressureSource.inRange(st, 46.25, -121.7, now), a)?.first?.id)
        // A kept station that left the range gives way to the nearest.
        assertEquals("KBBB", PressureSource.choose(PressureSource.inRange(st, 46.2, -121.7, now).filter { it.first.id != "KAAA" }, a)?.first?.id)
        assertEquals("KBBB", PressureSource.choose(PressureSource.inRange(st, 46.25, -121.7, now), b)?.first?.id)
    }
}
