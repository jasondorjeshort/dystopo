package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T159: `POST /location/replay`'s `accuracy=` ranges and `stall=` ([ReplayScript]). */
class ReplayScriptTest {
    private val timed = (0 until 10).map { i -> TrackExport.Timed(i * 100L, LocationFix(46.78 + i * 1e-4, -121.73, null, 1_000_000L + i * 1000L, 5.0)) }

    @Test
    fun parsing() {
        assertEquals(5.0, ReplayScript.defaultAccuracy("5"))
        assertEquals(8.0, ReplayScript.defaultAccuracy("120@4-6,8"))
        assertNull(ReplayScript.defaultAccuracy("120@4-6"))
        assertNull(ReplayScript.defaultAccuracy(null))
        val s = ReplayScript.parse("5,120@4-6,200@8", "60@3", 10)
        assertEquals(listOf(ReplayScript.AccuracyRange(120.0, 4, 6), ReplayScript.AccuracyRange(200.0, 8, 8)), s.ranges)
        assertEquals(ReplayScript.Stall(60.0, 3), s.stall)
        assertEquals(5, ReplayScript.parse(null, "30", 10).stall!!.beforeFix, "the middle fix by default")
        assertEquals(ReplayScript(), ReplayScript.parse(null, null, 10))
    }

    @Test
    fun refusals() {
        for ((acc, stall) in listOf("0" to null, "abc" to null, "120@6-4" to null, "120@" to null, null to "0", null to "-5", null to "60@0", null to "60@10", null to "4000", null to "x@3")) {
            assertFailsWith<IllegalArgumentException>("$acc $stall") { ReplayScript.parse(acc, stall, 10) }
        }
    }

    @Test
    fun applying() {
        val out = ReplayScript.parse("120@4-6", "60@5", timed.size).apply(timed)
        assertEquals(timed.size, out.size)
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0, 120.0, 120.0, 120.0, 5.0, 5.0, 5.0), out.map { it.fix.accuracyM })
        // Before the stall unchanged; from fix 5 on, 60 s later in real time and in the fixes' own time.
        for (i in 0 until 5) assertEquals(timed[i].atMs, out[i].atMs)
        for (i in 5 until 10) {
            assertEquals(timed[i].atMs + 60_000, out[i].atMs)
            assertEquals(timed[i].fix.time + 60_000, out[i].fix.time)
        }
        assertTrue(out.zipWithNext().all { (a, b) -> b.fix.time > a.fix.time && b.atMs >= a.atMs })
        assertEquals(timed, ReplayScript().apply(timed), "no script: the replay as before")
    }
}
