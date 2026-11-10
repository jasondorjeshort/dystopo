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
}
