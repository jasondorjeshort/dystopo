package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackStatsTest {
    private val mPerDeg = SyntheticTracks.M_PER_DEG

    private fun close(expected: Double, actual: Double?, tol: Double, msg: String = "") {
        assertTrue(actual != null && abs(expected - actual) <= tol, "$msg expected $expected ± $tol, got $actual")
    }

    @Test
    fun haversineKnownDistances() {
        // One degree of latitude anywhere, and of longitude on the equator, is 2πR/360.
        close(mPerDeg, TrackStats.distanceM(0.0, 0.0, 1.0, 0.0), 1e-6)
        close(mPerDeg, TrackStats.distanceM(45.0, 10.0, 46.0, 10.0), 1e-6)
        close(mPerDeg, TrackStats.distanceM(0.0, 179.5, 0.0, -179.5), 1e-6) // across the antimeridian
        // A degree of longitude at 60° is half as long (to first order; exact great circle is a little shorter).
        close(mPerDeg / 2, TrackStats.distanceM(60.0, 0.0, 60.0, 1.0), 5.0)
        close(PI * TrackStats.EARTH_RADIUS_M, TrackStats.distanceM(0.0, 0.0, 0.0, 180.0), 1e-3)
        assertEquals(0.0, TrackStats.distanceM(46.8, -121.7, 46.8, -121.7))
        close(90.0, TrackStats.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        close(180.0, TrackStats.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun ascentHysteresis() {
        fun ad(vararg e: Double, t: Double = 3.0) = TrackStats.ascentDescent(e.toList(), t)
        // A steady climb in small steps counts in full.
        assertEquals(100.0 to 0.0, TrackStats.ascentDescent((0..50).map { it * 2.0 }, 3.0))
        // Noise below the threshold counts nothing.
        assertEquals(0.0 to 0.0, ad(100.0, 101.0, 99.5, 102.0, 100.0, 101.9, 99.0))
        // Up 10, down 10, up 10: every run is counted.
        assertEquals(20.0 to 10.0, ad(0.0, 10.0, 0.0, 10.0))
        // A 2 m dip in a climb is ignored: one run of 20.
        assertEquals(20.0 to 0.0, ad(0.0, 10.0, 8.0, 20.0))
        // Noise on top of a climb: the turning points absorb it.
        assertEquals(50.0 to 0.0, ad(0.0, 1.0, 0.5, 2.0, 1.5, 10.0, 9.0, 30.0, 29.0, 50.0, 49.5))
        // Ends with a small drop: the last run still counts to its extreme.
        assertEquals(30.0 to 0.0, ad(0.0, 30.0, 29.0))
        // Loop: ascent equals descent.
        assertEquals(40.0 to 40.0, ad(100.0, 120.0, 140.0, 120.0, 100.0))
        assertEquals(0.0 to 0.0, ad(5.0))
        // Threshold 0 is plain summing of every change.
        assertEquals(3.0 to 2.0, ad(0.0, 1.0, 0.0, 1.0, 0.0, 1.0, t = 0.0))
    }

    @Test
    fun movingTimeDurationAndPace() {
        // North along the meridian, 10 m every 10 s (1 m/s), then a 10 minute stop, then 5 more steps.
        val t0 = 1_000_000L
        val pts = ArrayList<TrackPoint>()
        for (i in 0..10) pts += TrackPoint(i * 10 / mPerDeg, 0.0, 1000.0 + i, t0 + i * 10_000L)
        val stopEnd = t0 + 100_000L + 600_000L
        pts += TrackPoint(100 / mPerDeg, 0.0, 1010.0, stopEnd) // standing still
        for (i in 1..5) pts += TrackPoint((100 + i * 10) / mPerDeg, 0.0, 1010.0 - i, stopEnd + i * 10_000L)
        val s = TrackStats.of(listOf(pts))
        close(150.0, s.distanceM, 1e-6)
        assertEquals(750.0, s.durationS)
        assertEquals(150.0, s.movingTimeS)
        close(1.0, s.movingSpeedMps, 1e-9)
        close(1000.0, s.movingPaceSPerKm, 1e-6) // 16:40 per km
        close(5000.0, s.overallPaceSPerKm, 1e-6)
        assertEquals(10.0, s.ascentM)
        assertEquals(5.0, s.descentM)
        assertEquals(1000.0, s.minEleM)
        assertEquals(1010.0, s.maxEleM)
        assertEquals(t0, s.startTime)
        assertEquals(stopEnd + 50_000L, s.endTime)
        assertNull(s.avgHeartRate)
    }

    @Test
    fun segmentGapsAddNoDistance() {
        val a = listOf(TrackPoint(0.0, 0.0), TrackPoint(0.0, 0.01))
        val b = listOf(TrackPoint(1.0, 0.0), TrackPoint(1.0, 0.01))
        val s = TrackStats.of(listOf(a, b))
        close(TrackStats.distanceM(0.0, 0.0, 0.0, 0.01) + TrackStats.distanceM(1.0, 0.0, 1.0, 0.01), s.distanceM, 1e-6)
        assertEquals(2, s.segments)
        assertEquals(4, s.points)
        assertNull(s.ascentM)
        assertNull(s.durationS)
        assertNull(s.movingTimeS)
        assertEquals(1.0, s.bounds!!.north)
        assertEquals(0.01, s.bounds!!.east)
        val profile = TrackStats.profile(listOf(a, b), 3)
        close(s.distanceM, profile.last().distanceM, 1e-9)
        assertNull(profile[1].eleM)
        // T41: samples say which segment they lie on, so a profile can leave a gap between segments.
        val fine = TrackStats.profile(listOf(a, b), 9)
        assertEquals(0, fine.first().segment)
        assertEquals(1, fine.last().segment)
        assertEquals(fine.map { it.segment }.sorted(), fine.map { it.segment })
        val firstOfSecond = fine.indexOfFirst { it.segment == 1 }
        // The first sample past the gap is at or beyond the first segment's length.
        assertTrue(fine[firstOfSecond].distanceM >= TrackStats.distanceM(0.0, 0.0, 0.0, 0.01) - 1e-6)
    }

    @Test
    fun emptyAndSinglePointTracks() {
        val empty = TrackStats.of(emptyList())
        assertEquals(0.0, empty.distanceM)
        assertNull(empty.bounds)
        val one = TrackStats.of(listOf(listOf(TrackPoint(1.0, 1.0, 5.0, 10L))))
        assertEquals(0.0, one.distanceM)
        assertEquals(0.0, one.ascentM)
        assertEquals(0.0, one.durationS)
        assertNull(one.movingTimeS)
        // A point has no area: its box is padded so callers can still fit a view to it.
        val b = one.bounds!!
        assertEquals(1.0, (b.west + b.east) / 2, 1e-12)
        assertEquals(1.0, (b.south + b.north) / 2, 1e-12)
        assertEquals(2 * TrackStats.DEGENERATE_PAD_DEG, b.east - b.west, 1e-12)
        // A track straight north: real north-south extent, padded east-west.
        val line = TrackStats.of(listOf(listOf(TrackPoint(1.0, 2.0, null, null), TrackPoint(1.5, 2.0, null, null)))).bounds!!
        assertEquals(1.0, line.south)
        assertEquals(1.5, line.north)
        assertTrue(line.west < 2.0 && line.east > 2.0)
        assertEquals(emptyList(), TrackStats.profile(emptyList(), 10))
    }

    @Test
    fun profileInterpolatesByDistance() {
        // 1 km due north climbing linearly 0 -> 100 m, with a point lacking elevation in the middle.
        val pts = listOf(
            TrackPoint(0.0, 0.0, 0.0),
            TrackPoint(250 / mPerDeg, 0.0, null),
            TrackPoint(500 / mPerDeg, 0.0, 50.0),
            TrackPoint(1000 / mPerDeg, 0.0, 100.0),
        )
        val p = TrackStats.profile(listOf(pts), 11)
        assertEquals(11, p.size)
        for ((i, s) in p.withIndex()) {
            close(i * 100.0, s.distanceM, 1e-6, "distance $i")
            close(i * 10.0, s.eleM, 1e-6, "ele $i")
            close(i * 100.0 / mPerDeg, s.lat, 1e-12, "lat $i")
        }
    }

    @Test
    fun syntheticConeOutAndBack() {
        // From the cone's flank (2 km out) to the summit and back. The cone is steeper than the
        // rolling noise everywhere on the way, so the climb is one run: ascent = descent = relief.
        val t = SyntheticTerrain.CONE
        val pts = SyntheticTracks.outAndBack(t, 2000.0, 0.0, 0.0, 0.0, 200)
        val s = TrackStats.of(listOf(pts))
        val top = pts.maxOf { it.ele!! }
        close(4000.0, s.distanceM, 0.5)
        close(top - pts.first().ele!!, s.ascentM, 1e-6, "ascent")
        close(top - pts.last().ele!!, s.descentM, 1e-6, "descent")
        close(t.elevationAt(0.0, 0.0), top, 2.0, "summit")
        assertEquals(top, s.maxEleM)
        close(4000.0, s.movingTimeS, 1.0) // 1 m/s
        val profile = TrackStats.profile(listOf(pts), 5)
        close(t.elevationAt(0.0, 0.0), profile[2].eleM, 0.01, "summit in the middle of the profile")
    }

    @Test
    fun syntheticLoopWithHeartRate() {
        // A 1.5 km radius circle on the cone's flank: rolling noise only, so ascent ≈ descent and both are small.
        val t = SyntheticTerrain.CONE
        val pts = SyntheticTracks.loop(t, 0.0, 0.0, 1500.0, 400)
        val s = TrackStats.of(listOf(pts))
        close(2 * PI * 1500.0, s.distanceM, 2 * PI * 1500.0 * 1e-4)
        close(s.ascentM!!, s.descentM, 3.0, "closed loop: within the threshold (the last run counts to its extreme)")
        assertTrue(s.ascentM!! < 100.0, "flank contour walk, got ${s.ascentM}")
        close(119.5, s.avgHeartRate, 0.5)
        assertEquals(139.0, s.maxHeartRate)
        close(1.2, s.movingSpeedMps, 0.01)
    }

    @Test
    fun routeStats() {
        val r = Route("id", "r", listOf(RoutePoint(0.0, 0.0, 10.0), RoutePoint(0.0, 0.01, 30.0), RoutePoint(0.0, 0.02, 20.0)))
        val s = TrackStats.of(r)
        close(2 * TrackStats.distanceM(0.0, 0.0, 0.0, 0.01), s.distanceM, 1e-6)
        assertEquals(20.0, s.ascentM)
        assertEquals(10.0, s.descentM)
        assertNull(s.durationS)
    }
}
