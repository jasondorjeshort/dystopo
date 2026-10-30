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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T171 on the Skyline loop fixture (`commonTest/resources/gpx/skyline-loop.gpx`, synthetic, public trails at
 * Paradise): the fixture itself has no accuracy and is left as it is; densified to a fix every 5 s and given GPS
 * noise and 100 m bursts, its smoothed distance and gain come back to the fixture's.
 */
class SkylineSmoothingTest {
    private val track = Gpx.parse(javaClass.getResourceAsStream("/gpx/skyline-loop.gpx")!!.use { it.readBytes().decodeToString() }).tracks.single()
    private val mPerDeg = SyntheticTracks.M_PER_DEG

    @Test
    fun theFixtureIsNotSmoothedAndKeepsItsStop() {
        val pts = track.segments.single().points
        assertSame(pts, TrackDrawing.line(track).single())
        val cut = TrackStops.cut(track)
        // The 15 minute stop at Panorama Point, and no other.
        assertEquals(1, cut.stops.size, "${cut.stops}")
        assertEquals(900.0, cut.stops.single().durationS)
        assertEquals(2, cut.stretches.size)
        val all = TrackStats.of(track).distanceM
        assertEquals(all, cut.stretches.sumOf { it.distanceM }, 1.0)
    }

    @Test
    fun noisyDensifiedLoopSmoothsBackToTheFixture() {
        val pts = track.segments.single().points
        val truth = ArrayList<TrackPoint>()
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val span = b.time!! - a.time!!
            var t = 0L
            while (t < span) {
                val f = t.toDouble() / span
                truth += TrackPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, a.ele!! + (b.ele!! - a.ele!!) * f, a.time!! + t)
                t += 5000
            }
        }
        truth += pts.last()
        val rnd = Random(171)
        fun gauss(): Double = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        var dx = 0.0
        var dy = 0.0
        var dz = 0.0
        val c = 0.92
        val s = sqrt(1 - c * c)
        val mLon = mPerDeg * cos(truth[0].lat * PI / 180)
        val fixes = truth.mapIndexed { k, p ->
            dx = c * dx + s * 3.0 * gauss(); dy = c * dy + s * 3.0 * gauss(); dz = c * dz + s * 5.0 * gauss()
            val bad = k % 100 in 60 until 72
            val ex = if (bad) 50.0 * gauss() else dx + 1.5 * gauss()
            val ey = if (bad) 50.0 * gauss() else dy + 1.5 * gauss()
            val ez = if (bad) 60.0 * gauss() else dz + 2.0 * gauss()
            p.copy(lat = p.lat + ey / mPerDeg, lon = p.lon + ex / mLon, ele = p.ele!! + ez, accuracyM = if (bad) 100.0 else 5.0,
                verticalAccuracyM = if (bad) 150.0 else 8.0, flags = if (bad) TrackPoint.INACCURATE else 0)
        }
        val trueStats = TrackStats.of(listOf(truth), StatsOptions(smoothing = null))
        val raw = TrackStats.of(listOf(fixes), StatsOptions(smoothing = null))
        val smooth = TrackStats.of(listOf(fixes))
        println("T171 Skyline loop densified, ${fixes.size} fixes (${fixes.count { it.inaccurate }} flagged): truth ${trueStats.distanceM} m up ${trueStats.ascentM}; " +
            "raw ${raw.distanceM} m up ${raw.ascentM}; smoothed ${smooth.distanceM} m up ${smooth.ascentM}")
        assertTrue(raw.distanceM > trueStats.distanceM * 1.2, "raw ${raw.distanceM}")
        assertTrue(abs(smooth.distanceM - trueStats.distanceM) <= trueStats.distanceM * 0.05, "smoothed ${smooth.distanceM} against ${trueStats.distanceM}")
        assertTrue(smooth.ascentM!! <= trueStats.ascentM!! * 1.1 && smooth.ascentM!! >= trueStats.ascentM!! * 0.85, "smoothed gain ${smooth.ascentM} against ${trueStats.ascentM}")
        // The stop at Panorama Point survives the noise.
        val stops = TrackStops.cut(listOf(fixes)).stops
        assertTrue(stops.any { it.durationS in 840.0..960.0 }, "$stops")
    }
}
