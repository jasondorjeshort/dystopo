package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T258: `dump-scan` over a folder of synthetic dumps: growth read together, a dump with a coordinate refused. */
class DumpScanCommandTest {
    private fun dump(dir: File, name: String, pid: Int, heapStart: Long, logLine: String): File {
        val t0 = 1_762_700_000_000L
        val samples = (0..60).map { i ->
            DumpFormat.sampleLine(linkedMapOf("t" to t0 + i * 60_000L, "up" to 60L + i * 60, "pid" to pid, "heapKb" to heapStart + i * 10L, "pssKb" to 200_000, "rec" to "idle"))
        }
        val text = DumpFormat.format(
            linkedMapOf("kind" to "diagnostics", "version" to "0.1.25-alpha", "build" to "release", "device" to "Pixel 8", "android" to "15"),
            listOf(
                DumpFormat.S_SAMPLES to samples,
                DumpFormat.S_STARTS to listOf("0.1.25-alpha cold, first frame 1.00 s, longest stall 0.10 s, finished", "0.1.25-alpha cold, first frame 2.00 s, longest stall 0.10 s, finished"),
                DumpFormat.S_LOGCAT to listOf(logLine),
            ),
        )
        return File(dir, name).also { it.writeText(text) }
    }

    @Test
    fun readsAFolderTogetherAndRefusesAPlace() {
        val dir = Files.createTempDirectory("dump-scan").toFile()
        try {
            dump(dir, "a.txt", 1, 40_000, "D Dystopo: fix at <coord>")
            dump(dir, "b.txt", 2, 30_000, "D Dystopo: view <coords>")
            dump(dir, "c.txt", 3, 99_000, "D Dystopo: fix at 46.852812")
            File(dir, "notes.txt").writeText("not a dump\n")
            val files = dir.listFiles()!!.filter { it.name.endsWith(".txt") }.sortedBy { it.name }
            val (out, refusedAny) = DumpScanCommand.scan(files)
            assertTrue(refusedAny)
            assertEquals(2, out["read"])
            @Suppress("UNCHECKED_CAST") val refused = out["refused"] as List<Map<String, Any?>>
            assertEquals(listOf("c.txt", "notes.txt"), refused.map { File(it["file"] as String).name })
            assertFalse(refused.toString().contains("46.85"), "a refusal names lines, never their text")
            @Suppress("UNCHECKED_CAST") val growth = out["growth"] as List<Map<String, Any?>>
            val heap = growth.first { it["figure"] == "heapKb" }
            assertEquals(600.0, heap["slopePerHour"]) // 10 kB a minute in both runs
            assertEquals(2, heap["runs"])
            assertEquals(2.0, heap["hours"])
            assertEquals(40_600L, heap["peak"])
            @Suppress("UNCHECKED_CAST") val starts = out["starts"] as List<Map<String, Any?>>
            assertEquals(4, starts.single()["starts"])
            assertEquals(1.5, starts.single()["firstFrameMedianS"])
            @Suppress("UNCHECKED_CAST") val table = out["table"] as List<String>
            assertTrue(table[0].startsWith("figure"))
            assertTrue(table.any { it.startsWith("heapKb") && "600.0" in it })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** T302: `--recent-stats` puts a dump's cold-start figures and a pasted report's steps beside the launch budget. */
    @Test
    fun recentStatsBesideTheBudget() {
        val dir = Files.createTempDirectory("dump-scan-stats").toFile()
        try {
            val budget = File(dir, "budget.json").apply {
                writeText("""{"frames": {"firstFrameMs": {"ceilingMs": 4909, "medianMs": [3651, 3621, 3927]}}, "stages": {"prepare": {"ceilingMs": 300, "medianMs": [196, 210, 240]}}}""")
            }
            val text = DumpFormat.format(
                linkedMapOf("kind" to "diagnostics", "version" to "0.1.32-alpha"),
                listOf(DumpFormat.S_STARTS to listOf(
                    "0.1.32-alpha warm, first frame 0.50 s, longest stall 0.00 s, finished",
                    "0.1.32-alpha cold, first frame 4.00 s, longest stall 0.00 s, finished | firstFrameMs=4000 prepare=250 fdTableMs=12.5",
                    "0.1.32-alpha cold, first frame 6.00 s, longest stall 0.00 s, finished | firstFrameMs=6000 prepare=350",
                )),
            )
            val dump = File(dir, "dump.txt").apply { writeText(text) }
            val report = File(dir, "issue.txt").apply {
                writeText("Dystopo recent stats\nTime: 2026-11-16T00:00:00.000Z\n\nStart steps, the median of 4 recent cold starts, against the app's limits:\n" +
                    "  first frame 3200 ms, limit 4909 ms\n  prepare 400 ms, limit 300 ms, over\n  package read 0.4 ms (1 of 4)\n\nThis phone now:\n  Offline maps: none\n")
            }
            val (out, refusedAny) = DumpScanCommand.scan(listOf(dump, report), budget)
            assertFalse(refusedAny, out.toString())
            assertEquals(2, out["read"])
            @Suppress("UNCHECKED_CAST") val sources = (out["recentStats"] as Map<String, Any?>)["sources"] as List<Map<String, Any?>>
            assertEquals(listOf("dumps", "report"), sources.map { it["kind"] })
            @Suppress("UNCHECKED_CAST") val fromDump = sources[0]["figures"] as List<Map<String, Any?>>
            assertEquals(listOf("firstFrameMs", "prepare", "fdTableMs"), fromDump.map { it["figure"] })
            val frame = fromDump[0]
            assertEquals(5000.0, frame["phoneMedianMs"])
            assertEquals(2, frame["phoneStarts"])
            assertEquals(3651.0, frame["emulatorMedianMs"])
            assertEquals(4909L, frame["ceilingMs"])
            assertEquals(true, frame["overCeiling"])
            assertEquals(1.37, frame["ratio"])
            assertEquals(false, fromDump[1]["overCeiling"])
            assertEquals(null, fromDump[2]["ceilingMs"])
            @Suppress("UNCHECKED_CAST") val fromReport = sources[1]["figures"] as List<Map<String, Any?>>
            assertEquals(listOf("firstFrameMs", "prepare", "packageInfoMs"), fromReport.map { it["figure"] })
            assertEquals(400.0, fromReport[1]["phoneMedianMs"])
            assertEquals(4, fromReport[1]["phoneStarts"])
            assertEquals(1, fromReport[2]["phoneStarts"])
            @Suppress("UNCHECKED_CAST") val table = sources[0]["table"] as List<String>
            assertTrue(table[0].startsWith("figure") && table.any { it.startsWith("firstFrameMs") && "OVER" in it }, table.toString())
            // Without the option a report text is not a dump.
            assertTrue(DumpScanCommand.scan(listOf(report)).second)
        } finally {
            dir.deleteRecursively()
        }
    }
}
