package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import io.github.jasondorjeshort.dystopo.reports.Scrub
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("Send diagnostics", DUMP_TITLE)
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
