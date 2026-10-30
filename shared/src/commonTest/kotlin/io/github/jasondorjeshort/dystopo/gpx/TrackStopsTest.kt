package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T171: the stop cut T164's pace fit reads. */
class TrackStopsTest {
    private val mPerDeg = SyntheticTracks.M_PER_DEG
    private val t0 = 1_700_000_000_000L

    /**
     * East along the equator at 1.2 m/s with a fix a second (5 m accuracy, about 3 m of drifting error and 1.5 m
     * of jitter), following [plan]: (seconds, walking?) legs in order.
     */
    private fun walk(seed: Int, vararg plan: Pair<Int, Boolean>): List<TrackPoint> {
        val rnd = Random(seed)
        fun gauss(): Double = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        val out = ArrayList<TrackPoint>()
        var x = 0.0
        var t = 0
        var dx = 0.0
        var dy = 0.0
        val c = 0.98
        val s = sqrt(1 - c * c)
        fun fix() {
            dx = c * dx + s * 3.0 * gauss(); dy = c * dy + s * 3.0 * gauss()
            out += TrackPoint((dy + 1.5 * gauss()) / mPerDeg, (x + dx + 1.5 * gauss()) / mPerDeg, 500.0, t0 + t * 1000L, accuracyM = 5.0)
        }
        fix()
        for ((secs, moving) in plan) repeat(secs) {
            t++
            if (moving) x += 1.2
            fix()
        }
        return out
    }

    @Test
    fun aThreeMinuteStandstillIsCutAndAThirtySecondPauseIsNot() {
        for (seed in 1..8) {
            val pts = walk(seed, 300 to true, 180 to false, 300 to true, 30 to false, 300 to true)
            val cut = TrackStops.cut(listOf(pts))
            println("T171 stop test seed $seed: ${cut.stops.map { "${it.startIndex}..${it.endIndex} ${it.durationS} s" }}; stretches ${cut.stretches.map { "${it.startIndex}..${it.endIndex} ${it.distanceM.toInt()} m" }}")
            val stop = cut.stops.single()
            // The standstill is from 300 s to 480 s; the stay reaches about radius / speed (12 s) past each end, a few
            // seconds more where the smoothed line rounds the start of walking.
            assertTrue(stop.startIndex in 285..305 && stop.endIndex in 470..500, "seed $seed: stop ${stop.startIndex}..${stop.endIndex}")
            assertTrue(stop.durationS in 165.0..215.0, "seed $seed: ${stop.durationS} s")
            assertEquals(2, cut.stretches.size)
            assertEquals(0, cut.stretches[0].startIndex)
            assertEquals(stop.startIndex, cut.stretches[0].endIndex)
            assertEquals(stop.endIndex, cut.stretches[1].startIndex)
            assertEquals(pts.lastIndex, cut.stretches[1].endIndex)
            // The stretches hold the walked 1,080 m (less what the stay's edges took) and the 30 s pause.
            val walked = cut.stretches.sumOf { it.distanceM }
            assertTrue(abs(walked - 1080.0) < 1080.0 * 0.05, "seed $seed: walked $walked m")
            assertTrue(cut.stretches[1].durationS > 600.0)
        }
    }

    @Test
    fun gapsSplitTheStretchesAndUntimedSegmentsAreSkipped() {
        val a = walk(1, 120 to true, 120 to false)
        val b = walk(2, 120 to true).map { it.copy(time = it.time!! + 3_600_000L) }
        val cut = TrackStops.cut(listOf(a, b, a.map { it.copy(time = null) }))
        assertEquals(listOf(0), cut.stops.map { it.segment })
        assertEquals(listOf(0, 1), cut.stretches.map { it.segment })
        assertEquals(b.lastIndex, cut.stretches[1].endIndex)
    }

    @Test
    fun aSparseTrackWithoutAccuracyKeepsItsStop() {
        // Vertices a minute or more apart and no accuracy (a hand-made or imported track): a 10 minute stay at one
        // place is a stop between two stretches.
        fun p(m: Double, min: Int) = TrackPoint(0.0, m / mPerDeg, 100.0, t0 + min * 60_000L)
        val pts = listOf(p(0.0, 0), p(80.0, 1), p(160.0, 2), p(160.0, 12), p(240.0, 13), p(320.0, 14))
        val cut = TrackStops.cut(listOf(pts))
        assertEquals(listOf(2 to 3), cut.stops.map { it.startIndex to it.endIndex })
        assertEquals(600.0, cut.stoppedS)
        assertEquals(listOf(160.0, 160.0), cut.stretches.map { kotlin.math.round(it.distanceM) })
    }
}
