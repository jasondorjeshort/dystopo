package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T203: terrain estimates in a recording: kept with their source and the flagged mark, out of the distance until a good fix. */
class TerrainRecordTest {
    private var now = 1_000_000L
    private val store = InMemoryTrackStore()
    private val rec = TrackRecorder(store, clock = { now })

    private fun fix(i: Int, source: String = "gps", acc: Double = 5.0) =
        LocationFix(46.80 + i * 0.0002, -121.73, null, 1_000_000L + i * 5_000L, acc, source)

    @Test
    fun estimatesAreMarkedAndCountOnlyOnceAGoodFixFollows() {
        rec.start("walk")
        for (i in 0..4) assertTrue(rec.onFix(fix(i)))
        val before = rec.liveStats()!!.distanceM
        // The GPS goes; the estimates come in (22 m apart each).
        for (i in 5..9) assertTrue(rec.onFix(fix(i, TrackRecorder.ESTIMATE_SOURCE, 60.0)))
        val points = rec.livePoints().flatten()
        val est = points.drop(5)
        assertTrue(est.all { it.estimated && it.inaccurate }, "estimated and flagged")
        assertTrue(est.all { it.flags == TrackPoint.ESTIMATED or TrackPoint.INACCURATE })
        assertEquals(5L, rec.flagged)
        // Out of the distance while no good fix follows them.
        assertEquals(before, rec.liveStats()!!.distanceM, 1e-6)
        // A poor GPS fix does not confirm them; a good one does.
        assertTrue(rec.onFix(fix(10, acc = 200.0)))
        assertEquals(before, rec.liveStats()!!.distanceM, 1e-6) // a flagged fix adds no distance (T171)
        assertTrue(rec.onFix(fix(11)))
        val after = rec.liveStats()!!.distanceM
        assertEquals(22.2 * 11, after, 5.0)
        // The flags survive the store.
        assertEquals(5, store.track(rec.activeId!!)!!.points.count { it.estimated })
    }

    @Test
    fun confirmedLeavesOnlyTheTrailingEstimates() {
        val g = TrackPoint(46.8, -121.7, flags = 0)
        val e = TrackPoint(46.8, -121.7, flags = TrackPoint.ESTIMATED or TrackPoint.INACCURATE)
        val p = TrackPoint(46.8, -121.7, flags = TrackPoint.INACCURATE)
        assertEquals(listOf(listOf(g, e, g)), TrackRecorder.confirmed(listOf(listOf(g, e, g))))
        assertEquals(listOf(listOf(g, p)), TrackRecorder.confirmed(listOf(listOf(g, e, p, e))))
        assertEquals(listOf(listOf(g, e), listOf(g)), TrackRecorder.confirmed(listOf(listOf(g, e), listOf(g, e))))
        assertEquals(listOf(listOf(g)), TrackRecorder.confirmed(listOf(listOf(g), listOf(e, e))))
        assertTrue(TrackPoint.ESTIMATED and TrackPoint.KNOWN_FLAGS != 0, "a known flag: sync and GPX keep it")
    }
}
