package io.github.jasondorjeshort.dystopo.app.debug

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T150: `GET /debug/cpu`'s stat parsing and delta ([ThreadCpu]) and the StrictMode counting rule ([StrictModeCounts]). */
class DebugRoutesTest {
    @AfterTest fun after() = StrictModeCounts.clear()

    private fun stat(tid: Int, name: String, utime: Long, stime: Long) =
        "$tid ($name) S 1 1 0 0 -1 4194624 100 0 0 0 $utime $stime 0 0 10 -10 40 0 123 0 0 18446744073709551615 0 0 0 0 0 0 0 0"

    @Test
    fun parsesAStatLine() {
        val t = ThreadCpu.parse(stat(4242, "HeapTaskDaemon", 120, 30), mainTid = 4000)!!
        assertEquals(4242, t.tid)
        assertEquals("HeapTaskDaemon", t.name)
        assertEquals(150L, t.ticks)
        assertEquals(false, t.main)
        // A name with spaces and parentheses: between the first '(' and the last ')'.
        val odd = ThreadCpu.parse(stat(4000, "a (b) c", 7, 3), mainTid = 4000)!!
        assertEquals("a (b) c", odd.name)
        assertEquals(10L, odd.ticks)
        assertEquals(true, odd.main)
        assertNull(ThreadCpu.parse("garbage", 1))
    }

    @Test
    fun deltaGivesPercentOfOneCore() {
        val a = ThreadCpu.Snapshot(0, null, listOf(
            ThreadCpu.Ticks(1, "main", 100, 0, main = true), ThreadCpu.Ticks(2, "HeapTaskDaemon", 50, 0), ThreadCpu.Ticks(3, "idle", 9, 0),
        ))
        val b = ThreadCpu.Snapshot(60_000, null, listOf(
            ThreadCpu.Ticks(1, "main", 130, 0, main = true), ThreadCpu.Ticks(2, "HeapTaskDaemon", 50, 0), ThreadCpu.Ticks(3, "idle", 9, 0),
            ThreadCpu.Ticks(4, "new", 6, 0),
        ))
        val d = ThreadCpu.delta(a, b, ticksPerSecond = 100)
        @Suppress("UNCHECKED_CAST") val rows = (d["threads"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals(30L, rows["main"]!!["ticks"])
        assertEquals(0.5, rows["main"]!!["percent"]) // 0.3 s of CPU in 60 s
        assertEquals(0L, rows["HeapTaskDaemon"]!!["ticks"]) // listed always
        assertNull(rows["idle"]) // used nothing: left out
        assertEquals(true, rows["new"]!!["new"])
    }

    @Test
    fun countsByTheTopmostAppFrameAndExemptsTheRest() {
        val app = arrayOf(
            StackTraceElement("libcore.io.BlockGuardOs", "open", "BlockGuardOs.java", 1),
            StackTraceElement("java.io.FileInputStream", "<init>", "FileInputStream.java", 2),
            StackTraceElement("io.github.jasondorjeshort.dystopo.app.debug.DebugRoutes", "violate", "DebugRoutes.kt", 80),
            StackTraceElement("io.github.jasondorjeshort.dystopo.app.MapController", "onMain", "MapController.kt", 250),
        )
        val library = arrayOf(
            StackTraceElement("libcore.io.BlockGuardOs", "open", "BlockGuardOs.java", 1),
            StackTraceElement("org.maplibre.android.maps.MapView", "onCreate", "MapView.java", 3),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 4),
        )
        assertEquals("io.github.jasondorjeshort.dystopo.app.debug.DebugRoutes.violate(DebugRoutes.kt:80)", StrictModeCounts.record("DiskReadViolation", app))
        StrictModeCounts.record("DiskReadViolation", app)
        assertNull(StrictModeCounts.record("DiskReadViolation", library))
        val j = StrictModeCounts.json()
        assertEquals(2L, j["counted"])
        assertEquals(1L, j["exempt"])
        @Suppress("UNCHECKED_CAST") val rows = j["byFrame"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0]["count"])
        assertEquals("DiskReadViolation", rows[0]["kind"])
    }
}
