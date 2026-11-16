package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import io.github.jasondorjeshort.dystopo.reports.Scrub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T258: the dump's pure parts: the token mask, the file name, the strings, and a logcat line through the scrub as the dump runs it. */
class DiagnosticDumpTest {
    @Test
    fun theDebugTokenIsMasked() {
        assertEquals("I Dystopo: debug server on port 40123 token <token>", DiagnosticDump.maskToken("I Dystopo: debug server on port 40123 token 3f9a2c7d1e5b4a60"))
        assertEquals("token short", DiagnosticDump.maskToken("token short"), "only a token-like run")
    }

    @Test
    fun fileNameAndStrings() {
        assertTrue(Regex("dystopo-diagnostics-\\d{8}-\\d{4}\\.txt").matches(DiagnosticDump.fileName(1_762_780_000_000L)))
        assertEquals("Saved dystopo-diagnostics-20261110-1530.txt", dumpSaved("dystopo-diagnostics-20261110-1530.txt"))
        assertEquals("Saved dystopo-diagnostics-20261110-1530.txt in Download", dumpSaved("dystopo-diagnostics-20261110-1530.txt", "Download"))
        assertEquals("Send diagnostics", DUMP_TITLE)
    }

    /** T301: the folder's name from the saved document's id, null where the id names none (the row then says a plain "Saved <name>"). */
    @Test
    fun folderFromTheDocumentId() {
        assertEquals("Download", DiagnosticDump.folderOf("primary:Download/dystopo-diagnostics-20261110-1530.txt"))
        assertEquals("Reports", DiagnosticDump.folderOf("primary:Documents/Reports/dystopo-diagnostics-20261110-1530 (1).txt"))
        assertEquals("Download", DiagnosticDump.folderOf("raw:/storage/emulated/0/Download/dystopo-diagnostics-20261110-1530.txt"))
        assertEquals("Documents", DiagnosticDump.folderOf("home:dystopo-diagnostics-20261110-1530.txt"))
        assertEquals(null, DiagnosticDump.folderOf("msf:1000000123"))
        assertEquals(null, DiagnosticDump.folderOf("1000000123"))
        assertEquals(null, DiagnosticDump.folderOf("primary:dystopo-diagnostics-20261110-1530.txt"))
        assertEquals(null, DiagnosticDump.folderOf(null))
        assertEquals(null, DiagnosticDump.folderOf(""))
    }

    /** A dump as [DiagnosticDump.build] makes it: every line through the scrub with the phone's names; a full ring, a full start history and a long log. */
    private fun fullDump(startLine: (Int) -> String = { "0.1.31-alpha cold, first frame 2.41 s, longest stall 0.62 s, ok at Paradise loop 46.85281, -121.76044" }): String {
        val prepared = Scrub.prepare(Scrub.Known(packs = listOf("rainier"), areas = listOf("Paradise loop"), roots = listOf("/data/user/0/io.github.jasondorjeshort.dystopo/files")))
        fun clean(lines: List<String>) = lines.map { DiagnosticDump.maskToken(Scrub.text(it, prepared)) }
        val header = linkedMapOf(
            "kind" to "diagnostics", "atMs" to "1762780000000", "at" to "2026-11-10T13:06:40.000Z", "version" to "0.1.31-alpha", "versionCode" to "1733",
            "build" to "debug", "sdk" to "35", "android" to "15", "device" to "Google sdk_gphone64_x86_64", "pid" to "4242", "upS" to "86400",
            "samplePeriodS" to "60", "sampleKeep" to "1440", "logLines" to "4000", "logError" to "-",
        )
        val samples = (0 until 1440).map { i ->
            DumpFormat.sampleLine(linkedMapOf("t" to 1762693600000L + i * 60_000L, "up" to i * 60, "pid" to 4242, "heapKb" to 40_000 + i, "nativeKb" to 30_000, "pssKb" to 150_000 + 2 * i, "threads" to 80, "fds" to 200))
        }
        val starts = (0 until 20).map(startLine)
        val log = (0 until 4000).map { "11-10 15:30:00.123  4242  4260 I DystopoTiles: opened /data/user/0/io.github.jasondorjeshort.dystopo/files/packs/rainier-dem.pmtiles at 46.85281, -121.76044 token 3f9a2c7d1e5b4a60 line $it" }
        return DumpFormat.format(header.mapValues { DiagnosticDump.maskToken(Scrub.text(it.value, prepared)) }, listOf(
            DumpFormat.S_SAMPLES to clean(samples), DumpFormat.S_STARTS to clean(starts),
            DumpFormat.S_DIAGNOSTICS to clean(listOf("Packs: rainier, 143 MB")), DumpFormat.S_LOGCAT to clean(log),
        ))
    }

    /** T301: the issue body is the header-only summary, under the link's 4,000 encoded characters, scrubbed, the file's name last. */
    @Test
    fun theIssueBodyIsTheHeaderUnderTheCapAndScrubbed() {
        val name = "dystopo-diagnostics-20261110-1530.txt"
        val text = fullDump()
        assertTrue(text.length > 400_000, "a full dump: ${text.length}")
        val link = DiagnosticDump.issueLink(text, name)
        assertFalse(link.titleOnly)
        assertEquals("Diagnostics (0.1.31-alpha)", link.title)
        assertTrue(link.encodedBodyLength <= IssueLink.MAX_BODY, "encoded ${link.encodedBodyLength}")
        assertTrue(link.url.startsWith("https://github.com/jasondorjeshort/dystopo/issues/new?title=Diagnostics%20%280.1.31-alpha%29&body="), link.url.take(120))
        val body = assertNotNull(link.body)
        assertTrue(body.endsWith("Drag the saved file onto this box: $name"), body)
        assertTrue("Version: 0.1.31-alpha (1733, debug)" in body, body)
        assertTrue("Phone: Google sdk_gphone64_x86_64, Android 15 (API 35)" in body, body)
        assertTrue("Memory samples: 1440" in body, body)
        assertTrue("Memory, last sample: heap 41439 kB, native 30000 kB, PSS 152878 kB, 80 threads, 200 open files" in body, body)
        assertTrue("Memory, highest: heap 41439 kB, PSS 152878 kB" in body, body)
        assertEquals(DiagnosticDump.ISSUE_STARTS, body.lines().count { it.startsWith("- 0.1.31-alpha cold") }, body)
        // Scrubbed: the starts lines' area and coordinates are placeholders; nothing of the log, the snapshot or the token.
        for (raw in listOf("Paradise", "46.85", "121.76", "rainier", "/data/", "3f9a2c7d", "DystopoTiles", "Packs:")) assertFalse(raw in body, "$raw in $body")
        assertTrue("<area>" in body && "<coords>" in body, body)
        assertEquals(link.url.substringAfter("&body="), IssueLink.enc(body))
    }

    /** T301: long start lines: fewer starts, still under the cap; past it even with none, the title alone. */
    @Test
    fun longStartsShrinkTheBody() {
        val name = "dystopo-diagnostics-20261110-1530.txt"
        val link = DiagnosticDump.issueLink(fullDump { "0.1.31-alpha cold ${"x".repeat(900)} $it" }, name)
        assertTrue(link.encodedBodyLength <= IssueLink.MAX_BODY)
        val shown = assertNotNull(link.body).lines().count { it.startsWith("- 0.1.31-alpha cold") }
        assertTrue(shown in 1 until DiagnosticDump.ISSUE_STARTS, "starts shown: $shown")
        assertTrue(link.body!!.lines().last { it.startsWith("- ") }.endsWith(" 19"), "the newest kept")
        val huge = DiagnosticDump.issueLink(fullDump(), "x".repeat(5000) + ".txt")
        assertTrue(huge.titleOnly)
        assertEquals(null, huge.body)
    }

    @Test
    fun aLogcatLineAsTheDumpScrubsIt() {
        val prepared = Scrub.prepare(Scrub.Known(packs = listOf("rainier"), areas = listOf("Paradise loop"), roots = listOf("/data/user/0/io.github.jasondorjeshort.dystopo/files")))
        val line = "11-10 15:30:00.123  4242  4260 I DystopoTiles: opened /data/user/0/io.github.jasondorjeshort.dystopo/files/packs/rainier-dem.pmtiles " +
            "for Paradise loop at 46.85281, -121.76044 lat=46.85 tile 12/654/1430"
        val out = DiagnosticDump.maskToken(Scrub.text(line, prepared))
        assertEquals("11-10 15:30:00.123  4242  4260 I DystopoTiles: opened <file> for <area> at <coords> lat=<coord> tile <tile>", out)
        assertEquals(emptyList(), io.github.jasondorjeshort.dystopo.reports.DumpScan.places(DumpFormat.format(mapOf("kind" to "diagnostics"), listOf(DumpFormat.S_LOGCAT to listOf(out)))))
    }
}
