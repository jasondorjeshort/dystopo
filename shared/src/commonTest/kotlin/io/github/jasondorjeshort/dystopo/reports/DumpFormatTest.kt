package io.github.jasondorjeshort.dystopo.reports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T258: the diagnostic dump's format, its round trip, the scan's growth and medians, and the refusal of a place (synthetic data; Rainier only). */
class DumpFormatTest {
    private fun sample(t: Long, up: Long, pid: Int, heap: Long, pss: Long = 200_000, rec: String = "idle") = DumpFormat.sampleLine(
        linkedMapOf("t" to t, "up" to up, "pid" to pid, "heapKb" to heap, "nativeKb" to 50_000, "pssKb" to pss, "threads" to 70, "fds" to 120, "fg" to 1, "rec" to rec, "cpuUs" to 900),
    )

    @Test
    fun roundTrip() {
        val header = linkedMapOf("kind" to "diagnostics", "atMs" to "1762780000000", "version" to "0.1.25-alpha", "multi" to "a\nb")
        val sections = listOf(
            DumpFormat.S_SAMPLES to listOf(sample(1_762_779_940_000, 60, 42, 45_000), sample(1_762_780_000_000, 120, 42, 46_000)),
            DumpFormat.S_STARTS to listOf("0.1.25-alpha cold, first frame 1.20 s, longest stall 0.40 s, finished"),
            DumpFormat.S_LOGCAT to listOf("11-10 12:00:00.000  4242  4242 I Dystopo: up", "", "  indented", "== not a section", " == nor this"),
        )
        val text = DumpFormat.format(header, sections)
        assertTrue(text.startsWith(DumpFormat.MAGIC + "\n"))
        val back = DumpFormat.parse(text)
        assertEquals(DumpFormat.MAGIC, back.format)
        assertEquals(header + ("multi" to "a b"), back.header)
        assertEquals(sections, back.sections)
        assertEquals(DumpFormat.format(back.header, back.sections), text, "a dump written from what was read is the same bytes")
        val s = back.samples
        assertEquals(2, s.size)
        assertEquals(46_000L, s[1].number("heapKb"))
        assertEquals("idle", s[1].values["rec"])
        assertEquals(120L, s[1].up)
    }

    @Test
    fun refusesAnotherFormat() {
        assertFailsWith<IllegalArgumentException> { DumpFormat.parse("dystopo-report 1\nkind: crash\n") }
        assertFailsWith<IllegalArgumentException> { DumpFormat.parse("dystopo-dump 2\nkind: diagnostics\n") }
        DumpFormat.parse("dystopo-dump 1.1\nkind: diagnostics\n") // a minor version reads
    }

    @Test
    fun sampleLinesPassTheScrubberUnchanged() {
        val line = sample(1_762_779_940_000, 3600, 4242, 45_120, rec = "recording")
        assertEquals(line, Scrub.text(line))
        assertNull(DumpFormat.parseSample("x t=1"))
        assertNull(DumpFormat.parseSample("s heapKb=1"), "a sample has a time")
        assertEquals("s a=two_words b=-", DumpFormat.sampleLine(linkedMapOf("a" to "two words", "b" to "", "c" to null)))
    }

    @Test
    fun aCoordinateInALogLineComesOutAsCoord() {
        val prepared = Scrub.prepare(Scrub.Known(packs = listOf("rainier")))
        assertEquals("11-10 12:00:00.000  4242  4250 D Dystopo: fix at <coord> from <pack>", Scrub.text("11-10 12:00:00.000  4242  4250 D Dystopo: fix at 46.852812 from rainier", prepared))
        assertEquals("camera lat=<coord> lon=<coord>", Scrub.text("camera lat=46.85 lon=-121.76", prepared))
        assertEquals(Scrub.text("view 46.8528, -121.7604", Scrub.Known()), Scrub.text("view 46.8528, -121.7604", Scrub.prepare()))
    }

    @Test
    fun growthIsPooledWithinRunsSoARestartIsNotAFall() {
        val h = 3_600_000L
        val t0 = 1_762_700_000_000L
        // Run 1: heap 40000 -> 46000 kB over 3 h (2000 kB/h); then a new process starts low and grows at the same rate.
        val run1 = (0..3).map { sample(t0 + it * h, 60 + it * 3600L, 1, 40_000 + it * 2_000L) }
        val run2 = (0..2).map { sample(t0 + (5 + it) * h, 60 + it * 3600L, 2, 30_000 + it * 2_000L) }
        val samples = (run1 + run2).mapNotNull(DumpFormat::parseSample)
        val runs = DumpScan.runs(samples.shuffled(kotlin.random.Random(1)))
        assertEquals(2, runs.size)
        val heap = DumpScan.growth(runs).first { it.figure == "heapKb" }
        assertEquals(2000.0, assertNotNull(heap.slopePerHour), 1e-6)
        assertEquals(46_000L, heap.peak)
        assertEquals(34_000L, heap.last, "the newest sample's value")
        assertEquals(5.0, heap.hours, 1e-9)
        assertEquals(2, heap.runs)
        assertEquals(7, heap.samples)
        assertEquals("kB", heap.unit)
        val pss = DumpScan.growth(runs).first { it.figure == "pssKb" }
        assertEquals(0.0, assertNotNull(pss.slopePerHour), 1e-9)
        assertTrue(DumpScan.growth(runs).none { it.figure in DumpFormat.CONTEXT })
    }

    @Test
    fun startsMediansByVersionAndKind() {
        val lines = listOf(
            "0.1.24-alpha cold, first frame 1.00 s, longest stall 0.10 s, finished",
            "0.1.24-alpha cold, first frame 3.00 s, longest stall 0.10 s, finished",
            "0.1.24-alpha cold, first frame 2.00 s, longest stall 0.10 s, finished",
            "0.1.24-alpha warm, first frame none, longest stall 9.00 s, noFirstFrame",
            "0.1.25-alpha cold, first frame 0.50 s, longest stall 0.00 s, finished",
            "0.1.25-alpha cold, first frame 1.50 s, longest stall 0.00 s, finished",
            "not a start",
        )
        val s = DumpScan.starts(lines)
        assertEquals(listOf("0.1.24-alpha cold", "0.1.24-alpha warm", "0.1.25-alpha cold"), s.map { "${it.version} ${it.kind}" })
        assertEquals(2.0, s[0].firstFrameMedianS)
        assertEquals(3, s[0].starts)
        assertNull(s[1].firstFrameMedianS)
        assertEquals(1, s[1].starts)
        assertEquals(0, s[1].withFrame)
        assertEquals(1.0, s[2].firstFrameMedianS)
    }

    @Test
    fun theScannerRefusesADumpWithACoordinate() {
        val clean = DumpFormat.format(
            linkedMapOf("kind" to "diagnostics", "version" to "0.1.25-alpha"),
            listOf(
                DumpFormat.S_SAMPLES to listOf(sample(1_762_779_940_000, 60, 42, 45_000)),
                DumpFormat.S_DIAGNOSTICS to listOf("Heap: 45.1 MB of 256.0 MB", "Peak: 50.2 MB"),
                DumpFormat.S_LOGCAT to listOf("11-10 12:00:00.000  4242  4250 D Dystopo: fix at <coord> in <pack>, tile <tile>.png"),
            ),
        )
        assertEquals(emptyList(), DumpScan.places(clean))
        val dirty = clean.replace("fix at <coord>", "fix at 46.852812") + "\n"
        val bad = DumpScan.places(dirty)
        assertEquals(1, bad.size)
        assertTrue(dirty.split('\n')[bad[0] - 1].contains("46.852812"))
        assertEquals(1, DumpScan.places("view 46.8528, -121.7604\n").size)
        assertEquals(1, DumpScan.places("lat=46.85\n").size)
    }

    @Test
    fun fixed() {
        assertEquals("1.50", DumpScan.fixed(1.5, 2))
        assertEquals("12", DumpScan.fixed(12.4, 0))
        assertEquals("0.0", DumpScan.fixed(-0.01, 1))
    }
}
