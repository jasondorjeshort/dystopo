package io.github.jasondorjeshort.dystopo.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T307: the GPS view's summary and the check's verdict over synthetic satellite sets. */
class GnssHealthTest {
    /** [n] readings a second apart of the same satellites, each C/N0 moved by up to [jitter] dB-Hz from reading to reading. */
    private fun run(sats: List<Satellite>, n: Int = 120, jitter: Double = 1.0): List<GnssHealth.Sample> =
        (0 until n).map { k -> GnssHealth.Sample(k * 1000L, sats.mapIndexed { i, s -> if (s.cn0 > 0) s.copy(cn0 = s.cn0 + jitter * (((k + i) % 3) - 1)) else s }) }

    private fun case(name: String, horizon: ((Double) -> Double)? = null) = assertNotNull(GnssCases.of(name, horizon))

    @Test
    fun openSkyIsHealthy() {
        val v = GnssHealth.judge(run(case(GnssCases.OPEN)), horizon = null)
        assertEquals(GnssHealth.Kind.HEALTHY, v.kind)
        assertTrue(v.t4!! >= 35, "t4 ${v.t4}")
        assertTrue(v.usedMedian >= 4)
        assertEquals(listOf(Band.L1, Band.L5), v.bands)
        assertTrue(GnssHealth.Note.NO_HORIZON in v.notes)
        assertFalse(GnssHealth.Note.NO_GPS in v.notes)
    }

    @Test
    fun indoorsIsWeakNotAFault() {
        val v = GnssHealth.judge(run(case(GnssCases.INDOORS)), horizon = null)
        assertEquals(GnssHealth.Kind.WEAK, v.kind)
        assertTrue(GnssHealth.Note.FEW_IN_VIEW in v.notes, "${v.notes}")
        assertTrue(GnssHealth.Note.FEW_USED in v.notes)
        assertEquals(4, v.inViewMedian)
    }

    @Test
    fun allWeakWithManyInViewMayBeAFault() {
        val v = GnssHealth.judge(run(case(GnssCases.FAULT)), horizon = null)
        assertEquals(GnssHealth.Kind.FAULT, v.kind)
        assertTrue(v.t4!! < GnssHealth.FAULT_T4)
        assertTrue(v.strongestAbove!! < GnssHealth.STRONG_ANY)
        assertTrue(v.aboveHorizon >= GnssHealth.MANY_IN_VIEW)
    }

    @Test
    fun oneStrongSatelliteClearsTheFault() {
        val sats = case(GnssCases.FAULT).toMutableList()
        sats[0] = sats[0].copy(cn0 = 36.0)
        assertEquals(GnssHealth.Kind.WEAK, GnssHealth.judge(run(sats), null).kind)
    }

    @Test
    fun theMiddleBandIsAWeakSignal() {
        val v = GnssHealth.judge(run(case(GnssCases.WEAK)), horizon = null)
        assertEquals(GnssHealth.Kind.WEAK, v.kind)
        assertTrue(v.t4!! in 25.0..35.0, "t4 ${v.t4}")
        assertTrue(GnssHealth.Note.LOW_SIGNAL in v.notes)
    }

    @Test
    fun oneConstellationMissingStillHealthy() {
        // No GPS at all, the other systems strong: T4 comes from the best constellation heard.
        val sats = case(GnssCases.OPEN).filter { it.constellation != Constellation.GPS }
        val v = GnssHealth.judge(run(sats), horizon = null)
        assertEquals(GnssHealth.Kind.HEALTHY, v.kind)
        assertTrue(v.t4Constellation != Constellation.GPS)
        assertTrue(GnssHealth.Note.NO_GPS in v.notes)
        assertFalse(Constellation.GPS in GnssHealth.summarize(sats).constellations)
        // And without GLONASS: the same.
        val noGlonass = GnssHealth.judge(run(case(GnssCases.OPEN).filter { it.constellation != Constellation.GLONASS }), null)
        assertEquals(GnssHealth.Kind.HEALTHY, noGlonass.kind)
        assertFalse(Constellation.GLONASS in noGlonass.constellations)
    }

    @Test
    fun aSlowFirstFixIsShownNotJudged() {
        val v = GnssHealth.judge(run(case(GnssCases.OPEN)), horizon = null, ttffMs = 125_000)
        assertEquals(GnssHealth.Kind.HEALTHY, v.kind)
        assertEquals(125_000L, v.ttffMs)
        assertEquals(125_000L, GnssHealth.json(v)["ttffMs"])
    }

    @Test
    fun weakOnlyBehindARidgeIsAWeakSignalNotAFault() {
        val ridge = GnssCases.SYNTHETIC_RIDGE
        val sats = case(GnssCases.RIDGE, ridge)
        val v = GnssHealth.judge(run(sats), ridge)
        assertEquals(GnssHealth.Kind.WEAK, v.kind)
        assertTrue(v.behindHorizon >= 8, "behind ${v.behindHorizon}")
        assertTrue(GnssHealth.Note.BEHIND_TERRAIN in v.notes)
        assertTrue(v.satellites.filter { it.behind }.all { it.medianCn0 < 25 })
    }

    @Test
    fun theTerrainTakesTheBlameAwayFromThePhone() {
        // Every satellite weak (13 to 22 dB-Hz), most of them behind a ridge: with the ridge known that is a blocked sky;
        // over a flat horizon the same readings would read as a fault.
        val ridge = GnssCases.SYNTHETIC_RIDGE
        val behind = (0 until 9).map { i -> Satellite(Constellation.GPS, i + 1, 13.0 + i, 10.0, -50.0 + i * 12, used = false) }
        val open = (0 until 3).map { i -> Satellite(Constellation.GALILEO, i + 1, 18.0 + i, 50.0, 150.0 + i * 30, used = i == 0) }
        val sats = behind + open
        assertTrue(behind.all { GnssHealth.behind(it.elevationDeg, it.azimuthDeg, ridge) })
        assertTrue(open.none { GnssHealth.behind(it.elevationDeg, it.azimuthDeg, ridge) })
        assertEquals(GnssHealth.Kind.WEAK, GnssHealth.judge(run(sats), ridge).kind)
        assertEquals(GnssHealth.Kind.FAULT, GnssHealth.judge(run(sats), null).kind)
    }

    @Test
    fun nothingHeardIsWeakWithNoFigures() {
        val v = GnssHealth.judge(run(emptyList(), n = 10), null)
        assertEquals(GnssHealth.Kind.WEAK, v.kind)
        assertNull(v.t4)
        assertTrue(GnssHealth.Note.NONE_HEARD in v.notes)
        assertEquals(GnssHealth.Kind.WEAK, GnssHealth.judge(emptyList(), null).kind)
    }

    @Test
    fun theMedianIgnoresAFewBadReadings() {
        // Open sky with every tenth reading dropped to 10 dB-Hz (a hand over the antenna for a moment): still healthy.
        val sats = case(GnssCases.OPEN)
        val samples = (0 until 120).map { k -> GnssHealth.Sample(k * 1000L, if (k % 10 == 0) sats.map { it.copy(cn0 = 10.0) } else sats) }
        assertEquals(GnssHealth.Kind.HEALTHY, GnssHealth.judge(samples, null).kind)
    }

    @Test
    fun theSummaryCountsSatellitesNotSignals() {
        val sats = case(GnssCases.OPEN)
        val s = GnssHealth.summarize(sats)
        assertEquals(sats.map { it.id }.distinct().size, s.inView)
        assertTrue(sats.size > s.inView, "dual-band signals counted once")
        assertEquals(4, s.best4.size)
        assertEquals(s.best4.sortedDescending(), s.best4)
        assertEquals("L1 + L5", s.bandLine)
        assertEquals("L1 only", GnssHealth.summarize(case(GnssCases.INDOORS)).bandLine)
        assertNull(GnssHealth.summarize(listOf(Satellite(Constellation.GPS, 1, 30.0, 40.0, 0.0, true))).bandLine)
        val untracked = Satellite(Constellation.GPS, 9, 0.0, 40.0, 0.0, false)
        assertEquals(s.inView, GnssHealth.summarize(sats + untracked).inView, "a satellite not heard is not in view")
    }

    @Test
    fun bandsFromCarrierFrequencies() {
        assertEquals(Band.L1, Band.of(1_575_420_000.0))
        assertEquals(Band.L1, Band.of(1_602_562_500.0))
        assertEquals(Band.L1, Band.of(1_561_098_000.0))
        assertEquals(Band.L5, Band.of(1_176_450_000.0))
        assertEquals(Band.E5B, Band.of(1_207_140_000.0))
        assertEquals(Band.L2, Band.of(1_227_600_000.0))
        assertNull(Band.of(null))
        assertNull(Band.of(2_000_000_000.0))
    }

    @Test
    fun theListPutsUsedFirstThenTheStrongest() {
        val a = Satellite(Constellation.GPS, 1, 40.0, 10.0, 0.0, used = false)
        val b = Satellite(Constellation.GPS, 2, 30.0, 10.0, 0.0, used = true)
        val c = Satellite(Constellation.GPS, 3, 35.0, 10.0, 0.0, used = true)
        assertEquals(listOf(c, b, a), GnssHealth.listOrder(listOf(a, b, c)))
    }

    @Test
    fun casesAreKnownByName() {
        for (n in GnssCases.NAMES) assertTrue(GnssCases.of(n)!!.isNotEmpty(), n)
        assertNull(GnssCases.of("nope"))
        assertEquals(SkyPlotOrientation.PHONE, SkyPlotOrientation.DEFAULT)
        assertEquals(SkyPlotOrientation.NORTH, SkyPlotOrientation.of("North"))
    }
}
