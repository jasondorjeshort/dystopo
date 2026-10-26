package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T145: the report files, the text the row shows, and the prefilled issue link, on synthetic input (public places only). */
class ReportTest {
    private val system = linkedMapOf("version" to "0.1.13-alpha", "versionCode" to "13", "build" to "release", "sdk" to "35", "android" to "15", "device" to "Pixel 8")
    private val known = Scrub.Known(packs = listOf("rainier", "view-2026-10-25-1"), areas = listOf("Paradise loop"), searches = listOf("camp muir"))

    /** A crash thrown in app code with a message carrying what a real one could: a view at Rainier, a tile, a pack, a file. */
    private fun crash(message: String = "failed at 46.8529, -121.7604 lat=46.85 lon=-121.76 tile 12/654/1430.png pack rainier " +
        "file /data/user/0/io.github.jasondorjeshort.dystopo/files/packs/rainier-dem.pmtiles near Paradise loop, searching camp muir, view-46.85n-121.76w-z8-14"): Report {
        val e = IllegalStateException(message, RuntimeException("cause at 46.85291 -121.76044"))
        e.stackTrace = arrayOf(
            StackTraceElement("io.github.jasondorjeshort.dystopo.app.reports.ReportRoutes", "crash", "ReportRoutes.kt", 120),
            StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 959),
        )
        val text = CrashCapture.format(1_761_400_000_000L, "main", e, system)
        return Report("crash-20251025-134640-000", MainWatchdog.Dump.parse(text))
    }

    private val leaks = listOf(
        Regex("(?<![\\d.:])\\d{1,3}\\.\\d{2,}(?![\\d.])"), // a decimal with two or more fractional digits (not a version or a time): a coordinate
        Regex("\\b\\d{1,2}/\\d+/\\d+"), // a tile path
        Regex("(?i)rainier|paradise|camp muir|view-46|view-2026|/data/"),
    )

    @Test
    fun aCrashFileReadsBackWithItsHeaderAndStacks() {
        val r = crash()
        assertEquals("crash", r.kind)
        assertEquals("Crash", r.kindLabel)
        assertEquals(1_761_400_000_000L, r.atMs)
        assertEquals("io.github.jasondorjeshort.dystopo.app.reports.ReportRoutes.crash(ReportRoutes.kt:120)", r.dump.header["appFrame"])
        assertEquals("ReportRoutes.crash", r.headline)
        assertEquals(listOf("thread main (crashing)", "caused by"), r.dump.sections.map { it.first.substringBefore(':') })
        assertEquals("Pixel 8", r.dump.header["device"])
    }

    @Test
    fun theShownTextNamesNoPlaceTilePackOrFile() {
        val r = crash()
        val shown = r.shown(known)
        for (re in leaks) assertNull(re.find(shown), "shown text leaks ${re.pattern}: $shown")
        assertTrue(shown.startsWith("Dystopo problem report: Crash\n"), shown)
        assertTrue("App: 0.1.13-alpha (13), release" in shown, shown)
        assertTrue("Android: 15 (API 35), Pixel 8" in shown, shown)
        assertTrue("    at io.github.jasondorjeshort.dystopo.app.reports.ReportRoutes.crash(ReportRoutes.kt:120)" in shown, shown)
        assertTrue("<coords>" in shown && "<tile>" in shown && "<pack>" in shown && "<file>" in shown && "<area>" in shown && "<search>" in shown, shown)
        assertEquals("Crash: IllegalStateException at ReportRoutes.crash (0.1.13-alpha)", r.title(known))
    }

    @Test
    fun theLinkIsPrefilledShortAndLabelled() {
        val r = crash()
        val l = IssueLink.build(r, known)
        assertFalse(l.titleOnly)
        assertTrue(l.url.startsWith("https://github.com/jasondorjeshort/dystopo/issues/new?title="), l.url)
        assertTrue("&labels=crash&body=" in l.url, l.url)
        assertFalse("template=" in l.url)
        assertFalse('+' in l.url, "spaces are %20, never +")
        assertTrue(l.encodedBodyLength <= IssueLink.MAX_BODY)
        val body = URLDecoder.decode(l.url.substringAfter("&body="), "UTF-8")
        assertEquals(l.body, body)
        assertTrue(body.trimEnd().endsWith(IssueLink.CLIPBOARD_LINE), body)
        for (re in leaks) assertNull(re.find(body), "body leaks ${re.pattern}: $body")
        for (re in leaks) assertNull(re.find(URLDecoder.decode(l.url, "UTF-8")), "link leaks ${re.pattern}")
    }

    @Test
    fun aLongReportKeepsFewerFramesThenOnlyTheTitle() {
        // Frames long enough that twelve of them pass the cap: fewer are kept.
        val e = IllegalStateException("boom")
        e.stackTrace = Array(40) { StackTraceElement("io.github.jasondorjeshort.dystopo.app.Deep" + "x".repeat(300), "m$it", "Deep.kt", it + 1) }
        val r = Report("crash-1", MainWatchdog.Dump.parse(CrashCapture.format(1L, "worker", e, system)))
        val l = IssueLink.build(r)
        assertFalse(l.titleOnly)
        assertTrue(l.frames in 1 until IssueLink.TOP_FRAMES, "frames ${l.frames}")
        assertTrue(l.encodedBodyLength <= IssueLink.MAX_BODY)
        // A header alone past the cap (a 5000-character note from the system): the title alone.
        val big = Report("exit-2", MainWatchdog.Dump.parse(ExitImport.format("crash", 4, 1L, 1, "y ".repeat(2500), 0, 0, null, system)))
        val t = IssueLink.build(big)
        assertTrue(t.titleOnly)
        assertFalse("&body=" in t.url)
        assertTrue("&labels=crash" in t.url)
        // The shown text (the clipboard) is never cut.
        assertTrue(big.shown().length > 900, "the clipboard holds the whole report")
    }

    @Test
    fun anAnrDumpBecomesThreadSections() {
        val trace = """
            ----- pid 4242 at 2026-10-25 13:46:40.123 -----
            Cmd line: io.github.jasondorjeshort.dystopo

            DALVIK THREADS (3):
            "main" prio=5 tid=1 Sleeping
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a7c4b8 self=0xb4000071
              native: #00 pc 000000000005e4d8  /apex/com.android.runtime/lib64/bionic/libc.so (nanosleep+8)
              at java.lang.Thread.sleep(Native method)
              - sleeping on <0x0e5a1b2c> (a java.lang.Object)
              at io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:207)
              at android.os.Handler.handleCallback(Handler.java:959)

            "tile-3" prio=5 tid=21 Blocked
              at io.github.jasondorjeshort.dystopo.app.TileService.demTile(TileService.kt:300)
              - waiting to lock <0x0abc> (a java.lang.Object) held by thread 22

            "binder:4242_1" prio=5 tid=8 Native
              at android.os.BinderProxy.transactNative(Native method)

            ----- end 4242 -----
        """.trimIndent()
        val threads = ExitImport.threads(trace)
        assertEquals(listOf("\"main\"", "\"tile-3\"", "\"binder:4242_1\""), threads.map { it.first.substringBefore(' ') })
        assertEquals(listOf("java.lang.Thread.sleep(Native method)", "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:207)", "android.os.Handler.handleCallback(Handler.java:959)"), threads[0].second)
        assertTrue("waiting to lock" in threads[1].first)
        val text = ExitImport.format("anr", 6, 1_761_400_000_000L, 4242, "Input dispatching timed out", 200_000, 300_000, trace, system)
        val r = Report("exit-1", MainWatchdog.Dump.parse(text))
        assertEquals("anr", r.kind)
        assertEquals("Not responding (ANR)", r.kindLabel)
        assertEquals("io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:207)", r.dump.header["appFrame"])
        val shown = r.shown()
        assertTrue("Reason: not responding (ANR) (6)" in shown, shown)
        assertTrue("System's note: Input dispatching timed out" in shown, shown)
        assertTrue("\"tile-3\"" in shown, "a blocked thread is shown")
        assertFalse("binder:" in shown, "an idle thread with no app frame is left out")
        assertTrue("1 other threads left out" in shown, shown)
        assertEquals("Not responding (ANR): at Routes.freezeUi (0.1.13-alpha)", r.title())
    }

    @Test
    fun exitKindsAndTheCrashTheHandlerAlreadyWrote() {
        assertEquals("low-memory", ExitImport.kindOf(3))
        assertEquals("crash", ExitImport.kindOf(4))
        assertEquals("native-crash", ExitImport.kindOf(5))
        assertEquals("anr", ExitImport.kindOf(6))
        assertNull(ExitImport.kindOf(10), "a force-stop is not a problem report")
        assertTrue(ExitImport.hasOwnCrash(100_000, listOf(99_000)))
        assertFalse(ExitImport.hasOwnCrash(100_000, listOf(50_000)))
        val lowMemory = Report("exit-2", MainWatchdog.Dump.parse(ExitImport.format("low-memory", 3, 5L, 1, null, 400_000, 0, null, system)))
        assertEquals("Closed for memory", lowMemory.kindLabel)
        assertTrue("Memory at the last system sample: PSS 390 MB" in lowMemory.shown(), lowMemory.shown())
    }

    @Test
    fun aFreezeDumpShowsTheMainStackAndBlockedThreads() {
        val header = linkedMapOf("kind" to "freeze", "atMs" to "7", "at" to "2026-10-25T13:46:40.000Z", "silentMs" to "3012", "beatMs" to "1000", "freezeMs" to "3000",
            "samples" to "10", "topStackShare" to "9", "blockingFrame" to "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:207)") + system
        val sections = listOf(
            "main (most common sampled stack)" to listOf("java.lang.Thread.sleep(Native Method)", "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:207)"),
            "thread main (TIMED_WAITING)" to listOf("java.lang.Thread.sleep(Native Method)"),
            "thread tile-1 (BLOCKED)" to listOf("io.github.jasondorjeshort.dystopo.app.TileService.demTile(TileService.kt:300)"),
            "thread pool-2 (WAITING)" to listOf("jdk.internal.misc.Unsafe.park(Native Method)"),
        )
        val r = Report("freeze-1", MainWatchdog.Dump.parse(MainWatchdog.Dump.format(header, sections)))
        val shown = r.shown()
        assertTrue("UI thread silent for: 3012 ms (threshold 3000 ms)" in shown, shown)
        assertTrue("thread tile-1 (BLOCKED)" in shown)
        assertFalse("pool-2" in shown)
        assertEquals("Freeze: at Routes.freezeUi (0.1.13-alpha)", r.title())
    }
}
