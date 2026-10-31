package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T164: the pace fit on pieces drawn from a known curve, the setting value, and the cost model reading the fit. */
class PaceFitTest {
    private val truth = PaceFit(TravelMode.HIKE, 1.45, 3.2, -0.06, 0, 0.0)

    @AfterTest
    fun reset() = PaceFits.clearActive()

    private fun gauss(r: Random): Double = sqrt(-2 * ln(r.nextDouble().coerceAtLeast(1e-12))) * cos(2 * kotlin.math.PI * r.nextDouble())

    /** Pieces of 200 m at grades from -35 % to +35 % over [tracks] tracks, speed from [curve] with log-normal noise. */
    private fun pieces(curve: PaceFit, n: Int, tracks: Int, noise: Double, seed: Int, outliers: Double = 0.0): List<PaceSample> {
        val r = Random(seed)
        return List(n) { i ->
            val g = -0.35 + 0.7 * r.nextDouble()
            var v = curve.speedMps(g) * exp(noise * gauss(r))
            if (r.nextDouble() < outliers) v *= 0.3 // a rest the stop cut missed, a photo
            PaceSample(g, 200.0, 200.0 / v, i % tracks)
        }
    }

    private fun within(want: Double, got: Double, share: Double, what: String) =
        assertTrue(abs(got - want) <= share * abs(want), "$what: want $want within ${share * 100} %, got $got")

    @Test
    fun recoversAKnownCurveWithin10Percent() {
        val f = assertNotNull(PaceFits.fit(pieces(truth, 400, 6, 0.08, 1), TravelMode.HIKE))
        within(truth.peakMps, f.peakMps, 0.10, "peak")
        within(truth.width, f.width, 0.10, "width")
        within(truth.peakGrade, f.peakGrade, 0.10, "peak grade")
        assertEquals(6, f.tracks)
        assertEquals(80_000.0, f.metres, 1e-6)
        assertTrue(f.ready)
    }

    @Test
    fun outliersDoNotPullTheFit() {
        // One piece in eight at a third of the speed: least squares alone would drag the peak down; Huber does not.
        val f = assertNotNull(PaceFits.fit(pieces(truth, 400, 6, 0.06, 2, outliers = 0.12), TravelMode.HIKE))
        within(truth.peakMps, f.peakMps, 0.10, "peak")
        within(truth.width, f.width, 0.10, "width")
        within(truth.peakGrade, f.peakGrade, 0.10, "peak grade")
    }

    @Test
    fun recentTracksWeighMore() {
        // Ten old tracks at 1.2 m/s peak, the five newest at 1.6: the fit leans to the newest.
        val slow = truth.copy(peakMps = 1.2)
        val fast = truth.copy(peakMps = 1.6)
        val s = pieces(fast, 150, 5, 0.03, 3) + pieces(slow, 300, 10, 0.03, 4).map { it.copy(track = it.track + 5) }
        val f = assertNotNull(PaceFits.fit(s, TravelMode.HIKE))
        val flat = assertNotNull(PaceFits.fit(s.map { it.copy(track = 0) }, TravelMode.HIKE)) // the same pieces, all equally recent
        assertTrue(f.peakMps > flat.peakMps + 0.02, "the newest tracks count most: ${f.peakMps} against ${flat.peakMps} unweighted")
        assertTrue(PaceFits.recency(0) == 1.0 && abs(PaceFits.recency(10) - 0.5) < 1e-12)
    }

    @Test
    fun tooLittleOrOneSidedData() {
        assertNull(PaceFits.fit(pieces(truth, 9, 2, 0.05, 5), TravelMode.HIKE), "fewer than 10 pieces fit nothing")
        // Only climbs steeper than the steepest peak grade tried: the peak grade cannot be told from the peak speed; Tobler's -0.05 is kept.
        val up = pieces(truth, 200, 5, 0.05, 6).map { it.copy(grade = abs(it.grade) + 0.06) }
        assertEquals(-0.05, assertNotNull(PaceFits.fit(up, TravelMode.HIKE)).peakGrade, 1e-9)
    }

    @Test
    fun readyFromFiveTracksOver20Km() {
        assertFalse(truth.copy(tracks = 4, metres = 30_000.0).ready)
        assertFalse(truth.copy(tracks = 5, metres = 19_999.0).ready)
        assertTrue(truth.copy(tracks = 5, metres = 20_000.0).ready)
        PaceFits.setActive(listOf(truth.copy(tracks = 4, metres = 30_000.0), truth.copy(mode = TravelMode.BIKE, tracks = 6, metres = 90_000.0)))
        assertEquals(setOf(TravelMode.BIKE), PaceFits.active.keys)
    }

    @Test
    fun settingValueRoundTrips() {
        val f = truth.copy(tracks = 5, metres = 23_456.0, spread = 0.123)
        val text = PaceFits.encode(listOf(f), mapOf(TravelMode.BIKE to (2 to 8000.0)))
        assertEquals("1;hike:1.45,3.2,-0.06,5,23456,0.123,0.0,0.0,0;bike:,,,2,8000,", text)
        val back = assertNotNull(PaceFits.decode(text))
        assertEquals(f, back.fit(TravelMode.HIKE))
        // T184: the whole-track statistic rides in three trailing fields; a value from before T184 reads as a fit with no range.
        val ranged = f.copy(rangeLow = 0.912, rangeHigh = 1.087, rangeTracks = 7)
        val text2 = PaceFits.encode(listOf(ranged))
        assertEquals("1;hike:1.45,3.2,-0.06,5,23456,0.123,0.912,1.087,7", text2)
        assertEquals(ranged, assertNotNull(PaceFits.decode(text2)).fit(TravelMode.HIKE))
        assertEquals(f, assertNotNull(PaceFits.decode("1;hike:1.45,3.2,-0.06,5,23456,0.123")).fit(TravelMode.HIKE))
        assertEquals(0, assertNotNull(PaceFits.decode("1;hike:1.45,3.2,-0.06,5,23456,0.123,0,1.1,7")).fit(TravelMode.HIKE)!!.rangeTracks, "a malformed range is no range")
        assertNull(back.fit(TravelMode.BIKE))
        assertEquals(2 to 8000.0, back.count(TravelMode.BIKE))
        assertEquals(0, assertNotNull(PaceFits.decode("")).fits.size)
        assertNull(PaceFits.decode("2;hike:1,2,3,4,5,6"), "another version is not read")
        assertNull(PaceFits.decode("1;hike:fast"))
        assertEquals(0, assertNotNull(PaceFits.decode("1;skate:1.0,2.0,0.0,5,30000,0")).fits.size, "a newer build's mode is skipped")
    }

    @Test
    fun activitiesMapToModes() {
        assertEquals(TravelMode.HIKE, PaceFits.modeOf(Activity.FOOT))
        assertEquals(TravelMode.BIKE, PaceFits.modeOf(Activity.BIKE))
        assertEquals(TravelMode.DRIVE, PaceFits.modeOf(Activity.ROAD))
        for (a in listOf(Activity.RAIL, Activity.BOAT, Activity.AIR)) assertNull(PaceFits.modeOf(a))
        val t = Track("t", "x", listOf(TrackSegment(listOf(TrackPoint(0.0, 0.0)))))
        assertEquals(TravelMode.BIKE, PaceFits.modeOf(t.copy(type = "Cycling"), 1.0), "the GPX type before the speed")
        assertEquals(TravelMode.HIKE, PaceFits.modeOf(t, 1.3))
        assertEquals(TravelMode.BIKE, PaceFits.modeOf(t, 5.0))
        assertEquals(TravelMode.DRIVE, PaceFits.modeOf(t, 20.0))
        assertNull(PaceFits.modeOf(t.copy(activity = Activity.AIR), 1.0))
    }

    @Test
    fun costModelReadsTheActiveFit() {
        val standard = CostModel(TravelMode.HIKE)
        assertFalse(standard.fitted)
        assertEquals(7200.0, standard.timeS(5000.0, 600.0), 1e-6)
        assertEquals(standard.timeS(5000.0, 600.0), standard.timeS(5000.0, 600.0, 600.0), 1e-9, "Naismith ignores descent")
        assertEquals(8 * 100.0, standard.slopePenalty(1000.0, 100.0, 0.0), 1e-9)

        val slow = truth.copy(peakMps = 1.0, tracks = 5, metres = 25_000.0)
        PaceFits.setActive(listOf(slow))
        val mine = CostModel(TravelMode.HIKE)
        assertTrue(mine.fitted)
        assertTrue(mine.timeS(5000.0, 600.0) > standard.timeS(5000.0, 600.0), "a slower walker takes longer")
        assertEquals(5000.0 / slow.flatMps + 600 * slow.climbSPerM, mine.timeS(5000.0, 600.0), 1e-6)
        assertEquals(1000.0 / slow.flatMps, mine.timeS(1000.0, 0.0, 0.0), 1e-9)
        assertTrue(mine.slopePenalty(1000.0, 100.0, 0.0) > 0)
        assertTrue(mine.slopePenalty(1000.0, 0.0, 40.0) >= 0.0, "never negative: a gentle descent is faster, the cost stays admissible")
        assertFalse(CostModel(TravelMode.BIKE).fitted, "no bike fit")
        assertFalse(CostModel(TravelMode.CROW).fitted, "crow keeps the standard")
        assertFalse(CostModel(TravelMode.DRIVE).fitted)
        assertFalse(CostModel(TravelMode.HIKE, pace = null).fitted)
        // Along a line: flat 1 km at the flat speed.
        val flat = listOf(TrackPoint(0.0, 0.0, 100.0), TrackPoint(0.009, 0.0, 100.0))
        assertEquals(1000.0 / slow.flatMps, mine.pathTimeS(flat, 1000.0, 0.0), 2.0)
        assertEquals(standard.timeS(1000.0, 0.0), standard.pathTimeS(flat, 1000.0, 0.0), 1e-9)
    }

    @Test
    fun wholeTrackRatiosTellOneDayFromAnother() {
        // Five tracks each walked at its own factor of the curve, with piece noise that averages out over a track.
        val factors = listOf(0.88, 0.95, 1.0, 1.05, 1.12)
        val r = Random(21)
        val s = factors.flatMapIndexed { t, k ->
            List(60) {
                val g = -0.3 + 0.6 * r.nextDouble()
                PaceSample(g, 200.0, 200.0 / (truth.speedMps(g) * k * exp(0.08 * gauss(r))), t)
            }
        }
        val f = assertNotNull(PaceFits.fit(s, TravelMode.HIKE))
        val ratios = PaceFits.wholeTrackRatios(s, f)
        assertEquals(5, ratios.size)
        // Time over prediction goes as one over the walker's factor: the fastest day is the lowest ratio.
        for (i in 1 until 5) assertTrue(ratios[i].ratio < ratios[i - 1].ratio, "${ratios.map { it.ratio }}")
        within(factors[4] / factors[0], ratios[0].ratio / ratios[4].ratio, 0.04, "the slowest over the fastest day")
        assertTrue(f.spread > 0.05, "the pieces scatter (${f.spread}) more than the tracks' own ratios settle")
        val ranged = PaceFits.withRange(f, ratios)
        assertEquals(5, ranged.rangeTracks)
        assertTrue(ranged.rangeLow < 1 && ranged.rangeHigh > 1, "$ranged")
        // Leaving each track out widens the ratios (the honest form with few tracks).
        val loo = PaceFits.wholeTrackRatios(s, f, leaveOneOut = true)
        assertTrue(loo.first().ratio / loo.last().ratio > ratios.first().ratio / ratios.last().ratio, "${loo.map { it.ratio }} against ${ratios.map { it.ratio }}")
        // Short tracks give no ratio.
        assertEquals(0, PaceFits.wholeTrackRatios(s.map { it.copy(lengthM = 10.0, timeS = it.timeS / 20) }, f).size)
    }

    @Test
    fun theRangeRule() {
        val f = truth.copy(tracks = 6, metres = 30_000.0, rangeLow = 0.97, rangeHigh = 1.03, rangeTracks = 6)
        // 4.5 h: the floor of +-5 % widens a narrow statistic; 10-minute steps above 3 h, outwards.
        val e = PaceRange.estimate(4.5 * 3600, f)
        assertTrue(e.isRange)
        assertEquals("about 4:30", e.headline)
        assertEquals("4:10 to 4:50", e.rangeText)
        assertEquals(0.95, e.lowFactor!!, 1e-9)
        assertEquals(1.05, e.highFactor!!, 1e-9)
        // 2 h with a wider statistic: kept as measured, 5-minute steps.
        val w = PaceRange.estimate(7200.0, f.copy(rangeLow = 0.9, rangeHigh = 1.12))
        assertEquals("about 2:00", w.headline)
        assertEquals("1:45 to 2:15", w.rangeText)
        // Past the cap of +-25 %, under five measured tracks, without a measurement or a fit: the typical time alone.
        val wide = PaceRange.estimate(7200.0, f.copy(rangeLow = 0.7))
        assertFalse(wide.isRange)
        assertEquals(PaceRange.WIDE, wide.reason)
        assertEquals("typical 2:00", wide.headline)
        assertNull(wide.rangeText)
        assertEquals(PaceRange.WIDE, PaceRange.estimate(7200.0, f.copy(rangeHigh = 1.3)).reason)
        assertEquals(PaceRange.FEW_TRACKS, PaceRange.estimate(7200.0, f.copy(rangeTracks = 4)).reason)
        assertEquals(PaceRange.NOT_MEASURED, PaceRange.estimate(7200.0, f.copy(rangeLow = 0.0, rangeHigh = 0.0, rangeTracks = 0)).reason)
        val std = PaceRange.estimate(9_000.0, null)
        assertEquals(PaceRange.STANDARD, std.reason)
        assertEquals("typical 2:30", std.headline)
        // A short walk keeps a range of at least one step.
        val short = PaceRange.estimate(600.0, f)
        assertEquals("0:05 to 0:15", short.rangeText)
        assertEquals(0.5, PaceRange.percentile(listOf(0.0, 1.0), 0.5), 1e-12)
        assertEquals(1.4, PaceRange.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.1), 1e-12)
    }

    @Test
    fun climbRateReadsLikeNaismith() {
        // Tobler's own curve: about 12 min/km on the flat, 1090 m an hour of climb on a 15 % grade in Naismith's sense.
        val t = PaceFit.TOBLER
        assertEquals(1000.0 / (6.0 / 3.6 * exp(-3.5 * 0.05)), t.flatPaceSPerKm, 1e-6)
        assertTrue(t.climbRateMph in 1050.0..1150.0, "${t.climbRateMph}")
        assertEquals(t.flatPaceSPerKm, 1000.0 / t.flatMps, 1e-9)
    }
}
