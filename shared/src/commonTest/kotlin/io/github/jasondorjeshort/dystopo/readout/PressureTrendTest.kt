package io.github.jasondorjeshort.dystopo.readout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T178: the barometer's trend and the warning, on the synthetic fixtures the task names ([PressureFixtures]): the still
 * fall, the two reductions of a climb (and the 1000 m climb the critic's 4 hPa figure belongs to), the last-hour fall,
 * the treeline step and the forest noise; then the coverage, the exclusions, the warning's memory and the parsers.
 */
class PressureTrendTest {
    private val end = 1_790_000_000_000L

    private fun run(name: String, warning: FallWarning = FallWarning.STANDARD, edit: (PressureSeries) -> PressureSeries = { it }): TrendResult {
        val f = assertNotNull(PressureFixtures.of(name, end))
        val s = edit(f.series)
        val t = PressureTrend()
        s.feed(t)
        return t.evaluate(end, warning, s.station)
    }

    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol, got $actual")

    @Test
    fun aStillPhoneUnderAFourHectopascalFallRaisesTheWarning() {
        val r = run("still-fall")
        val t3 = assertNotNull(r.threeHour)
        assertEquals("raw", t3.mode)
        near(-4.0, t3.change, 0.15) // the phone's own 4 hPa
        near(-4.0 * kotlin.math.exp(PressureTrend.G * 1650 / (PressureTrend.R * PressureTrend.isaK(825.0))), t3.seaLevelChange, 0.2) // about 4.8 at sea level
        assertTrue(t3.interval.halfWidth < 0.5, "a still phone needs no height: ${t3.interval}")
        assertEquals(0.0, t3.interval.temperature)
        assertTrue(t3.passes)
        assertTrue(r.alert)
        assertEquals(TrendBand.FAST, r.band)
        assertEquals("↓", r.arrow)
        assertEquals("falling fast", r.label)
        // The row changes the verdict: Fewer (6 hPa) lets it pass, More (3) and Standard warn, Off never.
        assertFalse(run("still-fall", FallWarning.FEWER).alert)
        assertTrue(run("still-fall", FallWarning.MORE).alert)
        assertFalse(run("still-fall", FallWarning.OFF).alert)
        assertEquals(TrendBand.FAST, run("still-fall", FallWarning.OFF).band) // the arrow stays
    }

    @Test
    fun aClimbWithTheStationTemperatureWithinThreeKelvinReadsUnderOneAndAHalf() {
        val r = run("climb-station")
        val t3 = assertNotNull(r.threeHour)
        assertEquals("reduced", t3.mode)
        near(600.0, t3.netClimbM!!, 5.0)
        assertTrue(abs(t3.seaLevelChange) < 1.5, "false trend ${t3.seaLevelChange}")
        assertTrue(t3.sigmaTK!! < 3.5, "σ_T ${t3.sigmaTK}")
        assertFalse(r.alert)
        assertTrue(r.temperature.startsWith("station"))
    }

    @Test
    fun theSameClimbOnTheStandardAtmosphereOnADayTenKelvinOff() {
        // 600 m: about 2.7 hPa of false fall at sea level (0.0043 × 0.6 × p0), under the threshold.
        val r = run("climb-standard")
        val t3 = assertNotNull(r.threeHour)
        near(-2.7, t3.seaLevelChange, 0.5)
        assertEquals(10.0, t3.sigmaTK)
        assertTrue(t3.interval.temperature > 2.0, "the σ_T term covers it: ${t3.interval}")
        assertTrue(t3.fallLower < 0.75 * 4, "lower bound ${t3.fallLower}")
        assertFalse(r.alert)
        assertEquals(TrendBand.STEADY, r.band) // the interval swallows it: no arrow claims a fall
        assertFalse(r.significant)
        // 1000 m (the critic's "about 4 hPa"): the value passes the threshold, the σ_T = 10 K interval alone stops it.
        val k = run("climb-1000-standard")
        val k3 = assertNotNull(k.threeHour)
        assertTrue(k3.fall >= 4.0, "false fall ${k3.fall}")
        assertEquals("intervalTooWide", k3.reason)
        assertTrue(k3.fall - k3.interval.temperature < 3.0, "without the temperature term it would warn: ${k3.interval}")
        assertFalse(k.alert)
    }

    @Test
    fun aFallInTheLastHourIsCaughtByTheOneHourCheck() {
        val r = run("last-hour-fall")
        val t3 = assertNotNull(r.threeHour)
        val t1 = assertNotNull(r.oneHour)
        assertFalse(t3.passes, "the 3 h difference alone: ${t3.fall}")
        near(3.0, t3.fall, 0.3) // three quarters of the fall is in the last half-hour's mean
        assertTrue(t1.passes)
        assertEquals("1h", r.shown)
        assertTrue(r.alert)
        near(-3.5, t1.seaLevelChange, 0.3)
        assertEquals(TrendBand.VERY_FAST, r.band)
    }

    @Test
    fun theTreelineStepOnASurfaceDemDoesNotWarn() {
        val r = run("treeline-step")
        assertFalse(r.alert)
        val t1 = assertNotNull(r.oneHour)
        assertEquals("reduced", t1.mode)
        assertTrue(t1.fall >= 2.0, "the step reads as a fall of ${t1.fall} in the last hour")
        assertEquals("intervalTooWide", t1.reason) // the 15 m bound does the work
        assertTrue(r.band!! <= TrendBand.SLOWLY, "the arrow reads what is certain: ${r.band}")
        // The same heights called bare earth (a 4 m bound) would warn: the bound is what earns its keep.
        val bare = run("treeline-step") { s -> s.copy(rows = s.rows.map { it.copy(source = HeightSource.DEM_BARE_EARTH) }) }
        assertTrue(bare.alert)
    }

    @Test
    fun aForestWalkWithFifteenMetresOfHeightNoiseDoesNotWarn() {
        val r = run("forest-noise")
        assertFalse(r.alert)
        assertTrue(abs(assertNotNull(r.threeHour).seaLevelChange) < 1.5)
    }

    @Test
    fun gnssHeightsWhileMovingNeverWarn() {
        // The still fall, but walking with GNSS heights only (±8 m): the fall is there, the warning is not.
        val r = run("still-fall") { s ->
            s.copy(rows = s.rows.mapIndexed { i, row -> row.copy(source = HeightSource.GNSS, heightM = row.heightM!! + ((i * 37) % 17 - 8), lat = 46.0 + i * 1e-5, speedMps = 1.1) })
        }
        val t3 = assertNotNull(r.threeHour)
        assertEquals("reduced", t3.mode)
        assertTrue(t3.gnssHeights)
        assertFalse(t3.passes)
        assertFalse(r.alert)
    }

    @Test
    fun coverageAndTheFirstHour() {
        val f = assertNotNull(PressureFixtures.of("still-fall", end))
        // Two hours: no 3 h tendency, an arrow from the 1 h check.
        val t = PressureTrend()
        PressureSeries(f.series.rows.filter { it.tMs >= end - 2 * 3_600_000L }).feed(t)
        val r = t.evaluate(end, FallWarning.STANDARD)
        assertNull(r.threeHour)
        assertEquals("coverage", r.threeHourMissing)
        assertNotNull(r.oneHour)
        assertEquals("ok", r.status)
        assertEquals("1h", r.shown)
        // Half an hour: not enough data, no arrow.
        val u = PressureTrend()
        PressureSeries(f.series.rows.filter { it.tMs >= end - 1_800_000L }).feed(u)
        val q = u.evaluate(end, FallWarning.STANDARD)
        assertEquals("notEnoughData", q.status)
        assertNull(q.arrow)
        // A hole of 25 minutes in the middle leaves 2 h 35 min of the 3 h: still enough.
        val v = PressureTrend()
        PressureSeries(f.series.rows.filter { it.tMs < end - 85 * 60_000L || it.tMs >= end - 60 * 60_000L }).feed(v)
        val w = v.evaluate(end, FallWarning.STANDARD)
        assertEquals(null, w.threeHourMissing, "covered ${w.coveredMin}")
        assertTrue(w.alert)
    }

    @Test
    fun warmUpSpikesAndVehiclesAreDropped() {
        val t = PressureTrend()
        t.onListenerStart(0)
        assertFalse(t.onPressure(5_000, 900.0))
        assertTrue(t.onPressure(11_000, 900.0))
        assertEquals(1L, t.counters()["warmUpDropped"])
        // One minute squeezed 3 hPa over its neighbours, one at 20 m/s: neither counts.
        val s = PressureTrend()
        for (m in 0 until 200) {
            val tm = m * 60_000L + 30_000
            s.onFix(tm, 1650.0, HeightSource.DEM_BARE_EARTH, if (m == 120) 20.0 else 0.0, 46.786, -121.735)
            s.onPressure(tm, 830.0 + if (m == 100) 3.0 else 0.0)
        }
        val r = s.evaluate(200 * 60_000L, FallWarning.STANDARD)
        assertEquals(1, r.excluded["spike"])
        assertEquals(1, r.excluded["fast"])
        assertEquals(TrendBand.STEADY, r.band)
    }

    @Test
    fun theWarningIsRaisedOnceClearedUnderHalfAndNotRepeatedWithinThreeHours() {
        val a = FallAlert()
        val fall = run("still-fall")
        assertEquals(FallAlert.Event.RAISE, a.update(fall))
        assertEquals(FallAlert.Event.NONE, a.update(fall)) // once
        val calm = run("recovery")
        assertEquals(FallAlert.Event.CLEAR, a.update(calm))
        assertFalse(a.active)
        // Within 3 h of the last raise, the same band again is not repeated; a worse band is.
        assertEquals(FallAlert.Event.NONE, a.update(fall))
        val worse = run("last-hour-fall")
        assertEquals(FallAlert.Event.RAISE, a.update(worse))
        assertEquals(TrendBand.VERY_FAST, a.raisedBand)
        // The setting off clears it.
        assertEquals(FallAlert.Event.CLEAR, a.update(run("still-fall", FallWarning.OFF)))
    }

    @Test
    fun theSeriesSurvivesARestart() {
        val f = assertNotNull(PressureFixtures.of("still-fall", end))
        val t = PressureTrend()
        f.series.feed(t)
        val before = t.evaluate(end, FallWarning.STANDARD)
        val lines = t.minutes().map { it.encode() }
        val u = PressureTrend()
        u.restore(lines.mapNotNull { TrendMinute.decode(it) })
        val after = u.evaluate(end, FallWarning.STANDARD)
        assertEquals(before.minutes, after.minutes)
        near(before.threeHour!!.seaLevelChange, after.threeHour!!.seaLevelChange, 0.01)
        // The CSV form round-trips too, with the station line.
        val g = assertNotNull(PressureFixtures.of("climb-station", end)).series
        val back = PressureSeries.parseCsv(g.toCsv())
        assertEquals(g.rows.size, back.rows.size)
        assertEquals(g.station, back.station)
        // A clock that goes back (a replay's file times) starts afresh.
        u.onPressure(end - 3_600_000L, 830.0)
        assertEquals(1L, u.counters()["clockResets"])
    }

    @Test
    fun bandsAndWords() {
        assertEquals(TrendBand.STEADY, TrendBand.of(0.05))
        assertEquals(TrendBand.SLOWLY, TrendBand.of(-1.5))
        assertEquals(TrendBand.CHANGING, TrendBand.of(-1.6))
        assertEquals(TrendBand.FAST, TrendBand.of(6.0))
        assertEquals(TrendBand.VERY_FAST, TrendBand.of(-6.1))
        assertEquals(FallWarning.STANDARD, FallWarning.DEFAULT)
        assertEquals(FallWarning.MORE, FallWarning.parse("3"))
        assertEquals(FallWarning.FEWER, FallWarning.parse("fewer"))
        assertEquals(FallWarning.OFF, FallWarning.parse("Off"))
        assertNull(FallWarning.parse("5"))
        val t = run("still-fall").threeHour!!
        assertTrue(PressureText.body(t, inHg = false).matches(Regex("Down 4\\.\\d hPa in 3 hours \\(sea-level equivalent\\)\\. Weather may turn worse; check your plans\\.")), PressureText.body(t, false))
        assertTrue(PressureText.body(t, inHg = true).startsWith("Down 0.1"), PressureText.body(t, true))
        assertEquals("Weather station nearby: down 3.1 hPa in 3 hours.", PressureText.station(-3.1, false))
        assertEquals("Weather station nearby: steady over 3 hours.", PressureText.station(0.0, false))
    }

    @Test
    fun theReportsTemperatureAndTendency() {
        val ksea = MetarWeather.parse("METAR KSEA 300653Z 02004KT 10SM BKN055 BKN070 08/06 A2994 RMK AO2 SLP147 T00780061")
        assertEquals(7.8, ksea.tempC) // the remarks' tenths win over the body's 08
        assertNull(ksea.tendencyHPa)
        val cold = MetarWeather.parse("METAR LBBG 041600Z 12012MPS 090V150 1400 R04/P1500N R22/P1500U +SN BKN022 OVC050 M04/M07 Q1020 NOSIG")
        assertEquals(-4.0, cold.tempC)
        val tend = MetarWeather.parse("METAR KXYZ 301200Z 00000KT 10SM CLR M01/ A3001 RMK AO2 SLP170 T10111022 56032")
        assertEquals(-1.1, tend.tempC)
        assertEquals(-3.2, tend.tendencyHPa)
        assertEquals(1.5, MetarWeather.parse("METAR KXYZ 301200Z 00000KT 10SM CLR 10/05 A3001 RMK 52015").tendencyHPa)
        assertNull(MetarWeather.parse("METAR KXYZ 301200Z 00000KT 1/2SM FG A3001").tempC)
    }
}
