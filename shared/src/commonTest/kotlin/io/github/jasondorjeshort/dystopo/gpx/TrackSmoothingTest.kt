package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T171: the accuracy-weighted Kalman smoother and what stats and drawings read from it. */
class TrackSmoothingTest {
    private val mPerDeg = SyntheticTracks.M_PER_DEG

    /**
     * A noisy recording over [SyntheticTerrain.MIXED] (null island, nothing real) with three 100 m climbs added:
     * a wavy loop of about 4.1 km and 300 m of gain walked at 1.2 m/s with a fix every 3 s. Good fixes say 5 m and are off by a slowly drifting error (about
     * 3 m) plus 1.5 m of jitter, heights by about 6 m; [badEvery] bursts of 12 fixes say 100 m and are off by
     * about 50 m (heights 60 m), flagged as over a 50 m bound when [flag]. Returns the truth and the fixes.
     */
    private fun noisyLoop(seed: Int, flag: Boolean, badEvery: Int = 80): Pair<List<TrackPoint>, List<TrackPoint>> {
        val terrain = SyntheticTerrain.MIXED
        val rnd = Random(seed)
        fun gauss(): Double = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        // The path, finely, then resampled every 3.6 m of length.
        val fine = (0..20_000).map { i ->
            val a = 2 * PI * i / 20_000
            val r = 550.0 + 70.0 * sin(7 * a)
            r * cos(a) to r * sin(a)
        }
        val truth = ArrayList<TrackPoint>()
        val fixes = ArrayList<TrackPoint>()
        var along = 0.0
        var next = 0.0
        var driftX = 0.0
        var driftY = 0.0
        var driftZ = 0.0
        var k = 0
        val t0 = 1_700_000_000_000L
        for (i in fine.indices) {
            if (i > 0) along += kotlin.math.hypot(fine[i].first - fine[i - 1].first, fine[i].second - fine[i - 1].second)
            if (along < next) continue
            next += 3.6
            val (x, y) = fine[i]
            val time = t0 + k * 3000L
            // The terrain's gentle relief plus three climbs of 100 m round the loop, so the gain is a hike's.
            val p0 = SyntheticTracks.point(terrain, x, y, time)
            val true_ = p0.copy(ele = p0.ele!! + 50.0 * (1 - cos(3 * kotlin.math.atan2(y, x))))
            truth += true_
            // A first-order drift with a time constant of 60 s and a 3 m spread, plus white jitter.
            val c = 0.95
            val s = sqrt(1 - c * c)
            driftX = c * driftX + s * 3.0 * gauss(); driftY = c * driftY + s * 3.0 * gauss(); driftZ = c * driftZ + s * 5.0 * gauss()
            val bad = k % badEvery in 40 until 52
            val (ex, ey, ez) = if (bad) Triple(50.0 * gauss(), 50.0 * gauss(), 60.0 * gauss()) else Triple(driftX + 1.5 * gauss(), driftY + 1.5 * gauss(), driftZ + 2.0 * gauss())
            fixes += true_.copy(
                lat = true_.lat - ey / mPerDeg, lon = true_.lon + ex / mPerDeg, ele = true_.ele!! + ez,
                accuracyM = if (bad) 100.0 else 5.0, verticalAccuracyM = if (bad) 150.0 else 8.0,
                flags = if (bad && flag) TrackPoint.INACCURATE else 0, extensions = null,
            )
            k++
        }
        return truth to fixes
    }

    @Test
    fun hundredMetreFixesNeitherLengthenTheLineNorInflateTheGain() {
        val (truth, fixes) = noisyLoop(seed = 7, flag = true)
        val trueStats = TrackStats.of(listOf(truth), StatsOptions(smoothing = null))
        val raw = TrackStats.of(listOf(fixes), StatsOptions(smoothing = null))
        val smooth = TrackStats.of(listOf(fixes))
        println("T171 synthetic loop, ${fixes.size} fixes (${fixes.count { it.inaccurate }} flagged): truth ${trueStats.distanceM} m up ${trueStats.ascentM}; " +
            "raw ${raw.distanceM} m up ${raw.ascentM}; smoothed ${smooth.distanceM} m up ${smooth.ascentM}")
        assertTrue(raw.distanceM > trueStats.distanceM * 1.2, "raw ${raw.distanceM} should be over 20 % long against ${trueStats.distanceM}")
        assertTrue(abs(smooth.distanceM - trueStats.distanceM) <= trueStats.distanceM * 0.05, "smoothed ${smooth.distanceM} within 5 % of ${trueStats.distanceM}")
        assertTrue(raw.ascentM!! > trueStats.ascentM!! * 1.5, "raw gain ${raw.ascentM} inflated against ${trueStats.ascentM}")
        assertTrue(smooth.ascentM!! <= trueStats.ascentM!! * 1.15, "smoothed gain ${smooth.ascentM} not inflated over ${trueStats.ascentM}")
        assertTrue(smooth.ascentM!! >= trueStats.ascentM!! * 0.7, "smoothed gain ${smooth.ascentM} keeps the climbs of ${trueStats.ascentM}")
        // Times and the point count are the raw track's.
        assertEquals(raw.points, smooth.points)
        assertEquals(raw.durationS, smooth.durationS)
        // The smoothed points are one per raw point and close to the truth where the fixes were good.
        val s = TrackSmoothing.smooth(listOf(fixes)).single()
        assertEquals(fixes.size, s.size)
        val errors = s.indices.filter { !fixes[it].inaccurate }.map { TrackStats.distanceM(s[it].lat, s[it].lon, truth[it].lat, truth[it].lon) }.sorted()
        assertTrue(errors[errors.size / 2] < 4.0, "median smoothed error ${errors[errors.size / 2]} m")
    }

    @Test
    fun unflaggedHundredMetreFixesAreWeightedDown() {
        // The same fixes with no bound (an air profile has none): the weighting alone keeps the line.
        val (truth, fixes) = noisyLoop(seed = 11, flag = false)
        val trueM = TrackStats.of(listOf(truth), StatsOptions(smoothing = null)).distanceM
        val raw = TrackStats.of(listOf(fixes), StatsOptions(smoothing = null)).distanceM
        val smooth = TrackStats.of(listOf(fixes)).distanceM
        println("T171 synthetic loop unflagged: truth $trueM m, raw $raw m, smoothed $smooth m")
        assertTrue(raw > trueM * 1.2)
        assertTrue(abs(smooth - trueM) <= trueM * 0.08, "smoothed $smooth against $trueM")
    }

    @Test
    fun aTrackWithoutAccuracyIsLeftAlone() {
        val pts = SyntheticTracks.loop(SyntheticTerrain.CONE, 0.0, 0.0, 800.0, 200)
        assertSame(pts, TrackSmoothing.smoothSegment(pts))
        assertSame(pts, TrackSmoothing.line(listOf(pts)).single())
        assertEquals(TrackStats.of(listOf(pts), StatsOptions(smoothing = null)), TrackStats.of(listOf(pts)))
        val route = Route("r", "r", pts.map { RoutePoint(it.lat, it.lon, it.ele) })
        assertEquals(TrackStats.of(route, StatsOptions(smoothing = null)), TrackStats.of(route))
        // Untimed points cannot be smoothed even with an assumed accuracy.
        val untimed = pts.map { it.copy(time = null, accuracyM = 5.0) }
        assertSame(untimed, TrackSmoothing.smoothSegment(untimed, SmoothingOptions(assumedAccuracyM = 5.0)))
        // With an assumed accuracy a timed track is smoothed (a 200-step circle loses a little at its corners).
        val assumed = TrackStats.of(listOf(pts), StatsOptions(smoothing = SmoothingOptions(assumedAccuracyM = 10.0))).distanceM
        assertTrue(abs(assumed - 2 * PI * 800) < 2 * PI * 800 * 0.01, "assumed $assumed")
    }

    @Test
    fun aFlaggedFixBoundsTheLineButAddsNoDistance() {
        // North along a meridian, 1 m/s, a fix a second at 3 m, one wild flagged fix 300 m east in the middle.
        val t0 = 1_000_000L
        val pts = (0..200).map { i ->
            val wild = i == 100
            TrackPoint(i / mPerDeg, if (wild) 300.0 / mPerDeg else 0.0, 100.0, t0 + i * 1000L, accuracyM = if (wild) 400.0 else 3.0, flags = if (wild) TrackPoint.INACCURATE else 0)
        }
        val raw = TrackStats.of(listOf(pts), StatsOptions(smoothing = null))
        val s = TrackStats.of(listOf(pts))
        assertTrue(raw.distanceM > 700.0, "raw ${raw.distanceM}")
        assertTrue(abs(s.distanceM - 200.0) < 1.0, "smoothed ${s.distanceM}")
        val line = TrackDrawing.line(SyntheticTracks.track("t", pts)).single()
        assertEquals(200, line.size) // the flagged fix is not a vertex
        val smoothed = TrackSmoothing.smooth(listOf(pts)).single()
        assertEquals(201, smoothed.size)
        // ...but it was weighed: its own smoothed position stays on the meridian, within a metre.
        assertTrue(abs(smoothed[100].lon * mPerDeg) < 1.0, "flagged fix smoothed to ${smoothed[100].lon * mPerDeg} m east")
        // A segment whose fixes are all flagged keeps them all.
        val allBad = pts.map { it.copy(flags = TrackPoint.INACCURATE) }
        assertEquals(201, TrackSmoothing.line(listOf(allBad)).single().size)
    }

    @Test
    fun profileFollowsTheSmoothedLine() {
        val (_, fixes) = noisyLoop(seed = 3, flag = true)
        val raw = TrackStats.profile(listOf(fixes), 50, null)
        val smooth = TrackStats.profile(listOf(fixes), 50)
        assertEquals(TrackStats.of(listOf(fixes)).distanceM, smooth.last().distanceM, 1e-6)
        assertTrue(raw.last().distanceM > smooth.last().distanceM * 1.2)
    }
}
