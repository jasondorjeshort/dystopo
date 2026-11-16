package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.app.StartupText
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T302: Report recent stats on a full history (synthetic figures, public names only): its medians, its body's length and its scrub. */
class RecentStatsTest {
    private val system = linkedMapOf("version" to "0.1.32-alpha", "versionCode" to "1734", "build" to "release", "sdk" to "35", "android" to "15", "device" to "Pixel 8")
    private val known = Scrub.Known(packs = listOf("rainier", "view-2026-10-25-1"), areas = listOf("Paradise loop"), searches = listOf("camp muir"))

    private val budget: RecentStats.Budget by lazy {
        val f = listOf(File("../tools/launch-budget.json"), File("tools/launch-budget.json")).first { it.isFile }
        RecentStats.Budget.parse(f.readText())
    }

    /** A cold start's history line with every budget figure, [i] varying them, and T280's two header figures. */
    private fun line(i: Int, over: String? = null): String {
        val stats = linkedMapOf<String, String>()
        for ((k, ceiling) in budget.ceilings) stats[k] = (if (k == over) ceiling + 50 + i else ceiling * (60 + i) / 100).toString()
        stats["fdTableMs"] = "1${i % 10}.${i % 7}"
        stats["packageInfoMs"] = "0.${i % 9}"
        return "0.1.32-alpha cold, first frame 3.${10 + i} s, longest stall 0.${10 + i} s, finished" + StartupText.STATS_SEP +
            stats.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }

    /** What a phone with a store moved by copy after a refused rename might say, a pack name and its path in the refusal. */
    private val phone: List<String> = RecentStats.tileLines(linkedMapOf(
        "entries" to 18_234L, "bytes" to 812_000_000L, "maxBytes" to 2_147_483_648L, "pinnedBytes" to 300_000_000L,
        "emptyDropped" to 7L, "emptyDroppedKept" to 2L, "moveState" to "done",
        "move" to linkedMapOf("method" to "copy", "renameMs" to 3L, "copyMs" to 41_200L, "files" to 18_000L, "bytes" to 800_000_000L, "at" to 1_763_200_000_000L,
            "thisLaunch" to false, "renameError" to "/data/user/0/io.github.jasondorjeshort.dystopo/cache/tiles -> /data/user/0/io.github.jasondorjeshort.dystopo/no_backup/tiles: EXDEV near rainier"),
    )) + RecentStats.areaLine(listOf("done" to 300_000_000L, "done" to 1_000_000L, "partial" to 20_000_000L)) +
        RecentStats.memoryLines(listOf("s t=1763200000000 up=60 pid=4242 heapKb=40000 nativeKb=30000 pssKb=180000 threads=80 fds=200",
            "s t=1763200060000 up=120 pid=4242 heapKb=52000 nativeKb=31000 pssKb=190000 threads=82 fds=210"), 256L * 1024 * 1024)

    private val leaks = listOf(
        Regex("(?<![\\d.:])\\d{1,3}\\.\\d{4,}(?![\\d.])"), // a coordinate's precision
        Regex("(?i)rainier|paradise|camp muir|view-2026|/data/|no_backup"),
    )

    @Test
    fun theBudgetIsReadWithItsCeilingsAndTheEmulatorsMedians() {
        assertEquals(300L, budget.ceilings["prepare"])
        assertEquals(210.0, budget.emulator["prepare"], "the median of the sweeps' medians")
        assertEquals(4909L, budget.ceilings["firstFrameMs"])
        assertEquals("prepare", budget.stages.first())
        assertFalse("firstFrameMs" in budget.stages, "the frames are not stages")
    }

    @Test
    fun theMediansStandBesideTheLimits() {
        val history = listOf(
            "0.1.32-alpha warm, first frame 0.50 s, longest stall 0.00 s, finished",
            "0.1.32-alpha cold, first frame 3.00 s, longest stall 0.00 s, finished | firstFrameMs=3000 prepare=100 maplibre=400 fdTableMs=12.5",
            "0.1.32-alpha cold, first frame 3.20 s, longest stall 0.00 s, finished | firstFrameMs=3200 prepare=200 maplibre=400 packageInfoMs=0.4",
            "0.1.32-alpha cold, first frame 3.40 s, longest stall 0.00 s, finished | firstFrameMs=3400 prepare=300 maplibre=400 custom=7",
        )
        val lines = RecentStats.stepLines(RecentStats.figures(history), budget)
        assertEquals(listOf(
            "first frame 3200 ms, limit 4909 ms", "prepare 200 ms, limit 300 ms", "maplibre 400 ms, limit 302 ms, over",
            "custom 7 ms (1 of 3)", "open-files table 12.5 ms (1 of 3)", "package read 0.4 ms (1 of 3)",
        ), lines)
        val r = Report(RecentStats.ID, RecentStats.dump(1_763_300_000_000L, system, history, budget, listOf("Offline maps: none")))
        assertEquals("Recent stats", r.kindLabel)
        assertEquals("Recent stats (0.1.32-alpha)", r.title())
        assertEquals("4 starts", r.headline)
        assertTrue(r.isStats && !r.isStartup)
        assertTrue(r.shown().startsWith("Dystopo recent stats\nTime: "), r.shown())
        assertTrue(RecentStats.stepsTitle(3) in r.shown(), r.shown())
        // A history before T302: no figures, said so.
        val old = Report(RecentStats.ID, RecentStats.dump(1_763_300_000_000L, system, history.take(1), null, emptyList()))
        assertTrue(RecentStats.NO_STEPS in old.shown() && RecentStats.NO_BUDGET in old.shown(), old.shown())
    }

    @Test
    fun aFullHistoryFitsTheLinkScrubbedAndTheClipboardHoldsTheRest() {
        val history = (0 until StartupText.HISTORY_KEEP).map { line(it, over = "composition") }
        val r = Report(RecentStats.ID, RecentStats.dump(1_763_300_000_000L, system, history, budget, phone))
        val shown = r.shown(known)
        for (re in leaks) assertNull(re.find(shown), "shown text leaks ${re.pattern}: $shown")
        assertTrue("<file>" in shown, shown)
        for (k in budget.ceilings.keys) assertTrue("\n  ${RecentStats.name(k)} " in shown, "$k missing: $shown")
        assertTrue("Tiles dropped as empty since this start: 7 (2 of them in offline maps)" in shown, shown)
        assertTrue("Tile store move: done, by copy in 41200 ms, 18000 files, 762.9 MB, on 2025-11-15" in shown, shown)
        assertTrue("Offline maps: 3 (2 done, 1 partial), 306.1 MB" in shown, shown)
        assertTrue("Memory, last sample: Java heap 50.8 MB of 256.0 MB, native 30.3 MB, PSS 185.5 MB, 82 threads, 210 open files" in shown, shown)
        assertTrue("Memory, highest of the last 2 samples: Java heap 50.8 MB, native 30.3 MB, PSS 185.5 MB" in shown, shown)
        assertEquals(20, shown.lines().count { it.startsWith("  0.1.32-alpha cold, first frame") }, "every start's words")
        assertEquals(20, shown.lines().count { it.startsWith("  0.1.32-alpha: first frame") }, "every start's figures")
        assertFalse(" | " in shown, "no raw figures")

        val link = IssueLink.build(r, known)
        assertFalse(link.titleOnly)
        assertTrue(link.encodedBodyLength <= IssueLink.MAX_BODY, "${link.encodedBodyLength}")
        assertFalse("labels=" in link.url, "not a crash")
        val body = URLDecoder.decode(link.url.substringAfter("&body="), "UTF-8")
        assertEquals(link.body, body)
        for (re in leaks) assertNull(re.find(body), "body leaks ${re.pattern}: $body")
        for (k in budget.ceilings.keys) assertTrue("\n  ${RecentStats.name(k)} " in body, "$k missing from the body: $body")
        assertTrue("composition " in body && ", over" in body, body)
        assertTrue(body.lines().any { it.startsWith("Tile store move: ") || it.startsWith("  Tile store move: ") }, body)
        assertTrue(body.trimEnd().endsWith(IssueLink.STATS_CLIPBOARD_LINE), body)
        assertTrue(body.contains(history.last().substringBefore(" | ")), "the newest start is in the body")
        val clip = assertNotNull(link.clipboard)
        for (re in leaks) assertNull(re.find(clip), "clipboard leaks ${re.pattern}: $clip")
        // Body and clipboard together hold every line the row shows.
        val together = body + "\n" + clip
        for (l in shown.lines().filter { it.startsWith("  0.1.32-alpha") }) assertTrue(l in together, "lost: $l")
    }

    @Test
    fun aShortHistoryIsCompleteInTheBody() {
        val history = listOf(line(1))
        val r = Report(RecentStats.ID, RecentStats.dump(1_763_300_000_000L, system, history, budget, phone))
        val link = IssueLink.build(r, known)
        assertNull(link.clipboard, link.body)
        assertFalse(IssueLink.STATS_CLIPBOARD_LINE in link.body.orEmpty())
        assertEquals(r.shown(known), link.body)
    }
}
