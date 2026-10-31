package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T184: a planned route against recorded tracks ([PastEfforts]): a match at 85 % overlap, a refusal at 60 % ("part of
 * this trail was recorded"), a reversed traversal labelled, an out-and-back track timed on the route's own leg, the
 * wider tolerance of a low-accuracy track, and the card's line.
 */
class PastEffortTest {
    // A synthetic 3 km route due east from a point near Paradise (Mount Rainier); positions in metres east and north of it.
    private val lat0 = 46.786
    private val lon0 = -121.735
    private val mLat = 111_195.0
    private val mLon = mLat * cos(lat0 * PI / 180)
    private fun lat(y: Double) = lat0 + y / mLat
    private fun lon(x: Double) = lon0 + x / mLon

    private val route: List<RoutePoint> = (0..60).map { RoutePoint(lat(0.0), lon(it * 50.0)) }
    private val t0 = GpxFormat.daysFromCivil(2026, 6, 14) * 86_400_000L + 15 * 3_600_000L // 2026-06-14 15:00 UTC

    /**
     * A walk through [corners] (x, y metres) at [mps], one fix every 3 s, each fix [accuracyM] accurate, with a stop of
     * [stopS] at corner [stopAt].
     */
    private fun walk(id: String, corners: List<Pair<Double, Double>>, start: Long = t0, mps: Double = 1.2, accuracyM: Double = 8.0, stopAt: Int = -1, stopS: Double = 0.0): Track {
        val pts = ArrayList<TrackPoint>()
        var t = start.toDouble()
        fun emit(x: Double, y: Double) { pts += TrackPoint(lat(y), lon(x), 1600.0, kotlin.math.round(t).toLong(), accuracyM = accuracyM) }
        for (c in 0 until corners.size - 1) {
            val (ax, ay) = corners[c]
            val (bx, by) = corners[c + 1]
            val len = hypot(bx - ax, by - ay)
            var s = 0.0
            while (s < len) {
                emit(ax + (bx - ax) * s / len, ay + (by - ay) * s / len)
                s += mps * 3
                t += 3000
            }
            if (c + 1 == stopAt) { var w = 0.0; while (w < stopS) { emit(bx, by); t += 3000; w += 3 } }
        }
        emit(corners.last().first, corners.last().second)
        return Track(id, "synthetic $id", listOf(TrackSegment(pts)))
    }

    /** The route's line with a parallel variant [offset] m north from [from] to [to] metres along it. */
    private fun variant(from: Double, to: Double, offset: Double = 60.0) =
        listOf(0.0 to 0.0, from to 0.0, from to offset, to to offset, to to 0.0, 3000.0 to 0.0)

    @Test
    fun matchesAt85PercentOverlap() {
        val m = PastEfforts.match(route, walk("a", variant(1275.0, 1725.0)))
        assertEquals(PastEfforts.Kind.FULL, m.kind, "$m")
        assertTrue(m.coverage in 0.84..0.90, "coverage ${m.coverage}")
        assertFalse(m.reversed)
        assertTrue(abs(m.startMs!! - t0) <= 6_000, "left the start within a fix or two of the first one: ${m.startMs!! - t0} ms")
        // 3,120 m at 1.2 m/s: 2,600 s, within a fix or two.
        assertTrue(abs(m.elapsedS!! - 2600) < 10, "elapsed ${m.elapsedS}")
        assertEquals(PastEfforts.TOLERANCE_M, m.toleranceM)
    }

    @Test
    fun refusesAt60PercentOverlap() {
        val m = PastEfforts.match(route, walk("b", variant(900.0, 2100.0)))
        assertEquals(PastEfforts.Kind.PARTIAL, m.kind, "$m")
        assertTrue(m.coverage in 0.58..0.65, "coverage ${m.coverage}")
        assertNull(m.elapsedS, "a partial overlap gives no time")
        val s = PastEfforts.summary(route, listOf(walk("b", variant(900.0, 2100.0))))
        assertNull(s.best)
        assertEquals(PastEffortText.PARTIAL, s.line(t0))
        // Under half: nothing at all.
        assertEquals(PastEfforts.Kind.NONE, PastEfforts.match(route, walk("c", variant(500.0, 2500.0))).kind)
    }

    @Test
    fun labelsAReversedTraversal() {
        val m = PastEfforts.match(route, walk("r", listOf(3000.0 to 0.0, 0.0 to 0.0)))
        assertEquals(PastEfforts.Kind.FULL, m.kind, "$m")
        assertTrue(m.reversed)
        assertTrue(abs(m.elapsedS!! - 2500) < 10, "elapsed ${m.elapsedS}")
        val s = PastEfforts.summary(route, listOf(walk("r", listOf(3000.0 to 0.0, 0.0 to 0.0))))
        assertEquals("You did this trail in 0:42, June (other direction)", s.line(t0))
    }

    @Test
    fun anOutAndBackTrackIsTimedOnTheRoutesOwnLeg() {
        val track = walk("o", listOf(0.0 to 0.0, 3000.0 to 0.0, 0.0 to 0.0), stopAt = 1, stopS = 300.0)
        val out = PastEfforts.match(route, track)
        assertEquals(PastEfforts.Kind.FULL, out.kind, "$out")
        assertFalse(out.reversed, "the way out walks the route's way")
        assertTrue(abs(out.elapsedS!! - 2500) < 10, "the way out only, without the stop or the way back: ${out.elapsedS}")
        // The route the other way: the way back, the same direction; it may include the stop at the far end.
        val back = PastEfforts.match(route.reversed(), track)
        assertEquals(PastEfforts.Kind.FULL, back.kind, "$back")
        assertFalse(back.reversed)
        assertTrue(abs(back.elapsedS!! - 2500) < 10, "the way back, without the stop at the far end: ${back.elapsedS}")
        assertTrue(back.startMs!! >= t0 + 2_790_000, "the way back starts when the walker leaves the far end")
    }

    @Test
    fun aLowAccuracyTrackGetsTheWiderTolerance() {
        val line = listOf(0.0 to 32.0, 3000.0 to 32.0)
        assertEquals(PastEfforts.Kind.NONE, PastEfforts.match(route, walk("g", line, accuracyM = 8.0)).kind, "32 m off at 25 m")
        val poor = PastEfforts.match(route, walk("p", line, accuracyM = 20.0))
        assertEquals(PastEfforts.LOW_ACCURACY_TOLERANCE_M, poor.toleranceM)
        assertEquals(PastEfforts.Kind.FULL, poor.kind, "$poor")
    }

    @Test
    fun bestAndLatestOnTheCard() {
        val june = walk("j", listOf(0.0 to 0.0, 3000.0 to 0.0), start = t0, mps = 1.3)
        val sept = walk("s", listOf(0.0 to 0.0, 3000.0 to 0.0), start = t0 + 90L * 86_400_000L, mps = 1.1)
        val s = PastEfforts.summary(route, listOf(sept, june))
        assertTrue(abs(s.best!!.elapsedS!! - 3000 / 1.3) < 10, "${s.best!!.elapsedS}")
        assertEquals("j", assertNotNull(s.best).trackId)
        assertEquals("s", assertNotNull(s.latest).trackId)
        assertEquals("You did this trail in 0:38, June; latest 0:45, September", s.line(t0 + 100L * 86_400_000L))
        // One track: one time; another year names it.
        val one = PastEfforts.summary(route, listOf(june))
        assertEquals("You did this trail in 0:38, June 2026", one.line(t0 + 400L * 86_400_000L))
        // Same direction wins over a faster reverse walk.
        val rev = walk("x", listOf(3000.0 to 0.0, 0.0 to 0.0), mps = 1.6)
        assertEquals("j", PastEfforts.summary(route, listOf(june, rev)).best!!.trackId)
        assertNull(PastEfforts.summary(route, emptyList()).line(t0))
    }
}
