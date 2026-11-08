package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.debug.ThreadCpu
import io.github.jasondorjeshort.dystopo.app.reports.IssueLink
import io.github.jasondorjeshort.dystopo.app.reports.Report
import io.github.jasondorjeshort.dystopo.app.reports.Reports
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T228: the start trace's clock, its report rules, its leftovers and its text, on a fake clock and synthetic stacks. */
class StartupTraceTest {
    private val app = "io.github.jasondorjeshort.dystopo.app"
    private val system = linkedMapOf("version" to "0.1.21-alpha", "versionCode" to "21", "build" to "release", "sdk" to "35", "android" to "15", "device" to "Pixel 8")
    private val packStack = listOf("java.io.RandomAccessFile.read(RandomAccessFile.java:1)", "$app.PmTiles.header(PmTiles.kt:40)", "$app.PackSet.open(Packs.kt:120)", "$app.DystopoApp.onCreate(DystopoApp.kt:60)", "android.app.ActivityThread.main(ActivityThread.java:8000)")
    private val holdStack = listOf("java.lang.Thread.sleep(Native Method)", "$app.Foo.slow(Foo.kt:7)", "android.os.Handler.handleCallback(Handler.java:959)")

    private fun window(kind: String = StartupWindow.PROVISIONAL, at: Long = 1_000, t: StartupThresholds = StartupThresholds()) =
        StartupWindow("4242-$at", at, 1_761_400_000_000L, kind) { t }

    /** Runs the worker's steps from [from] to [to], every 100 ms; the first-frame case answers each heartbeat at once unless [silent]. */
    private fun run(w: StartupWindow, from: Long, to: Long, silent: Boolean = false, stack: List<String> = holdStack, state: String = "waiting"): List<StartupWindow.Step> {
        val steps = ArrayList<StartupWindow.Step>()
        var now = from
        while (now <= to) {
            val s = w.step(now)
            steps += s
            if (s.close) return steps
            if (s.post && !silent) w.answered(s.seq, now + 1)
            if (s.sample) w.addSample(state, 10, stack)
            now += 100
        }
        return steps
    }

    /** A cold start: attach 200 ms after process start, the app's onCreate, the activity. */
    private fun coldTo(w: StartupWindow, frameAt: Long?, packMs: Long = 100): StartupWindow {
        w.mark("attachBaseContext", 1_200)
        w.mark("PackSet.open", 1_300)
        run(w, 1_300, 1_300 + packMs, stack = packStack, state = "disk")
        val end = 1_300 + packMs
        w.mark("DystopoApp.onCreate done", end)
        w.appCreated(end)
        assertTrue(w.activityCreated(end + 50))
        w.mark("MainActivity.onCreate", end + 50)
        if (frameAt != null) {
            run(w, end + 50, frameAt - 1, stack = holdStack)
            w.firstFrame(frameAt)
        }
        return w
    }

    @Test
    fun aColdStartCountsFromProcessStartAndNamesItsFirstFrame() {
        val w = coldTo(window(), frameAt = 4_000)
        assertEquals(StartupWindow.COLD, w.kind)
        val r = w.record(4_100, null)
        assertEquals(3_000, r.firstFrameMs)
        assertEquals(StartupWindow.COLD, r.kind)
        assertTrue(r.activitySeen)
        assertEquals("attachBaseContext", r.marks.first().name)
        assertEquals(200, r.marks.first().atMs)
    }

    @Test
    fun anOnCreateAfterTheAppWasLongDoneIsWarmNotCold() {
        val w = window()
        w.mark("attachBaseContext", 1_200)
        w.appCreated(1_300)
        assertFalse(w.activityCreated(1_300 + StartupWindow.COLD_WITHIN_MS + 1), "a background process brought forward is not this window")
        assertEquals(StartupWindow.PROVISIONAL, w.kind)
        // StartupTrace then opens a warm window from that onCreate: its first frame counts from there, not from process start.
        val warm = window(StartupWindow.WARM, at = 22_000)
        warm.mark("MainActivity.onCreate", 22_000)
        warm.firstFrame(22_900)
        assertEquals(900, warm.record(23_000, null).firstFrameMs)
        assertEquals(StartupWindow.WARM, warm.record(23_000, null).kind)
        assertTrue(warm.record(23_000, null).reasons.isEmpty(), "21 s after process start is not a slow start")
    }

    @Test
    fun aBackgroundProcessWindowClosesAndIsDiscardedWithNoActivity() {
        val w = window()
        w.mark("attachBaseContext", 1_200)
        w.appCreated(1_300)
        val steps = run(w, 1_300, 1_300 + StartupWindow.PROVISIONAL_MS + 100)
        assertTrue(steps.last().close)
        val r = w.record(9_000, null)
        assertEquals(StartupWindow.BACKGROUND, r.outcome)
        assertFalse(r.activitySeen)
        assertTrue(StartupLeftover.discard(r.outcome, r.activitySeen, 100))
    }

    @Test
    fun aHotWindowClosesFiveSecondsAfterItsFirstFrame() {
        val w = window(StartupWindow.HOT, at = 50_000)
        w.mark("onStart", 50_000)
        w.firstFrame(50_300)
        assertEquals(300, w.record(50_400, null).firstFrameMs)
        val steps = run(w, 50_300, 56_000)
        assertTrue(steps.last().close)
        assertTrue(w.closed)
        assertEquals(StartupWindow.FINISHED, w.record(60_000, null).outcome)
        assertEquals(50_300 + 5_000L, 50_000 + w.record(60_000, null).elapsedMs)
    }

    @Test
    fun theStartInfoRecordsKindWins() {
        val w = window(StartupWindow.WARM, at = 5_000)
        w.startInfoKind(assertNotNull(StartupText.startTypeKind(1)))
        assertEquals(StartupWindow.COLD, w.kind)
        assertEquals("startInfo", w.record(5_100, null).kindSource)
        assertEquals(StartupWindow.WARM, StartupText.startTypeKind(2))
        assertEquals(StartupWindow.HOT, StartupText.startTypeKind(3))
        assertNull(StartupText.startTypeKind(0))
    }

    @Test
    fun aStallKeepsItsTrueLengthItsMarkAndItsFrame() {
        val w = coldTo(window(), frameAt = 2_000)
        w.mark("style loaded", 2_050)
        val s1 = w.step(2_100)
        assertTrue(s1.post)
        // Unanswered: no sampling for the first 250 ms, then every 100 ms.
        assertFalse(w.step(2_200).sample)
        assertFalse(w.step(2_300).sample)
        var sampled = 0
        for (t in 2_400L..5_000L step 100) if (w.step(t).sample) { w.addSample("waiting", 0, holdStack); sampled++ }
        assertTrue(sampled >= 20, "sampled $sampled")
        w.answered(s1.seq, 5_123)
        val r = w.record(5_200, null)
        val stall = r.stalls.single()
        assertEquals(3_023, stall.ms)
        assertEquals(1_100, stall.atMs)
        assertEquals("style loaded", stall.mark)
        assertEquals("$app.Foo.slow(Foo.kt:7)", MainWatchdog.blockingFrame(stall.stack))
        assertEquals(sampled, stall.top)
        assertEquals(sampled, stall.samples)
        assertEquals("user-requested, force-stopped", StartupText.exitName(10, "force-stopped"))
        assertEquals("not responding (ANR)", StartupText.exitName(6, "not responding (ANR)"))
        assertTrue(stall.ended)
        assertEquals(listOf(StartupWindow.REASON_STALL), r.reasons)
    }

    @Test
    fun aShortSilenceIsNoStallAndOneUnderTwoSecondsIsNoReport() {
        val w = coldTo(window(), frameAt = 2_000)
        val s = w.step(2_100)
        w.answered(s.seq, 2_500)
        assertTrue(w.record(2_600, null).stalls.isEmpty(), "under 500 ms")
        val s2 = w.step(2_700)
        w.answered(s2.seq, 3_700)
        val r = w.record(3_800, null)
        assertEquals(1, r.stalls.size)
        assertTrue(r.reasons.isEmpty(), "a stall of 1 s keeps its line but brings no report")
    }

    @Test
    fun beforeTheFirstFrameSamplesGoToTheMarkInForceNotToAStall() {
        val w = coldTo(window(), frameAt = 3_000, packMs = 800)
        val r = w.record(3_100, null)
        val pack = r.marks.first { it.name == "PackSet.open" }
        assertEquals(800, pack.ms)
        assertTrue(pack.samples >= 8)
        assertEquals(pack.samples, pack.states["disk"])
        assertEquals("$app.PmTiles.header(PmTiles.kt:40)", MainWatchdog.blockingFrame(pack.stack))
        assertTrue(r.stalls.isEmpty())
    }

    @Test
    fun aSampleBelongsToTheMarkInForceWhenItsStackWasRead() {
        val w = window(StartupWindow.WARM)
        w.mark("first", 1_000)
        val inForce = w.markInForce()
        w.mark("second", 1_050) // the main thread passed a mark while the worker read the stack
        w.addSample("running", 10, holdStack, inForce)
        val lines = w.record(1_100, null).marks
        assertEquals(1, lines.first { it.name == "first" }.samples)
        assertEquals(0, lines.first { it.name == "second" }.samples)
    }

    @Test
    fun aHealthyThreeSecondColdStartWritesNoReport() {
        val w = coldTo(window(), frameAt = 4_000)
        w.fullyLoaded(9_000)
        val steps = run(w, 4_000, 40_000)
        assertTrue(steps.last().close)
        val r = w.record(40_000, null)
        assertEquals(3_000, r.firstFrameMs)
        assertTrue(r.reasons.isEmpty(), "reasons ${r.reasons}")
        assertEquals(StartupWindow.FINISHED, r.outcome)
    }

    @Test
    fun eachReportRuleFires() {
        // The first frame over the kind's threshold, for each kind's own figure.
        val slow = coldTo(window(), frameAt = 6_100)
        assertEquals(listOf(StartupWindow.REASON_FIRST_FRAME), slow.also { it.fullyLoaded(6_200) }.record(6_300, null).reasons)
        val warm = window(StartupWindow.WARM, at = 1_000).apply { mark("MainActivity.onCreate", 1_000); firstFrame(3_001); fullyLoaded(3_100) }
        assertEquals(listOf(StartupWindow.REASON_FIRST_FRAME), warm.record(3_200, null).reasons)
        val warmOk = window(StartupWindow.WARM, at = 1_000).apply { firstFrame(2_999) }
        assertTrue(warmOk.record(3_000, null).reasons.isEmpty())
        val hot = window(StartupWindow.HOT, at = 1_000).apply { firstFrame(2_501) }
        assertEquals(listOf(StartupWindow.REASON_FIRST_FRAME), hot.record(3_000, null).reasons)
        // The map still not drawn 15 s after the first frame, with a responsive main thread.
        val blank = coldTo(window(), frameAt = 2_000)
        val steps = run(blank, 2_000, 16_900)
        assertEquals(1, steps.count { it.snapshot == 5_000 })
        assertEquals(0, steps.count { it.snapshot == 15_000 })
        val more = run(blank, 17_000, 17_100)
        assertEquals(1, more.count { it.snapshot == 15_000 })
        assertEquals(listOf(StartupWindow.REASON_MAP), blank.record(17_200, null).reasons)
        // A map drawn at 10 s takes no snapshot at 15 s and earns nothing.
        val drawn = coldTo(window(), frameAt = 2_000)
        drawn.fullyLoaded(12_000)
        assertEquals(0, run(drawn, 12_000, 18_000).count { it.snapshot > 0 })
        assertTrue(drawn.record(18_000, null).reasons.isEmpty())
        // No first frame: past the limit at 120 s; the process ended; left quickly earns nothing, left late does.
        val none = coldTo(window(), frameAt = null)
        val close = run(none, 2_000, 1_000 + StartupWindow.MAX_MS + 100)
        assertTrue(close.last().close)
        assertEquals(StartupWindow.NO_FIRST_FRAME, none.record(0, null).outcome)
        assertEquals(listOf(StartupWindow.REASON_NOT_FINISHED), none.record(0, null).reasons)
        val quick = coldTo(window(), frameAt = null).also { it.requestClose(StartupWindow.LEFT) }
        assertTrue(quick.step(2_000).close)
        assertTrue(quick.record(2_000, null).reasons.isEmpty())
        val late = coldTo(window(), frameAt = null).also { it.requestClose(StartupWindow.LEFT) }
        assertTrue(late.step(7_000).close)
        assertEquals(listOf(StartupWindow.REASON_NOT_FINISHED), late.record(7_000, null).reasons)
    }

    @Test
    fun theWindowRunsOnPastAStallAtItsClose() {
        val w = coldTo(window(), frameAt = 2_000)
        w.fullyLoaded(3_000)
        run(w, 2_000, 31_800)
        var now = 31_900L
        var beat = w.step(now)
        while (!beat.post) { now += 1; beat = w.step(now) }
        assertFalse(w.closed)
        // Past the 30 s line (first frame + 30 s), the heartbeat out and silent: it stays open.
        for (t in 32_200L..40_000L step 100) assertFalse(w.step(t).close, "closed at $t during a stall")
        w.answered(beat.seq, 40_050)
        assertTrue(w.step(40_100).close)
        val r = w.record(40_100, null)
        val stall = r.stalls.single()
        assertTrue(stall.ms in 8_000..8_200, "${stall.ms}")
        assertTrue(stall.ended)
        assertEquals(listOf(StartupWindow.REASON_STALL), r.reasons)
    }

    @Test
    fun aStallStillRunningAtTheMaximumIsWrittenAsRunning() {
        val w = coldTo(window(), frameAt = 2_000)
        w.fullyLoaded(2_100)
        val s = w.step(2_200)
        assertTrue(s.post)
        var closedAt = -1L
        for (t in 2_300L..122_000L step 100) if (w.step(t).close) { closedAt = t; break }
        assertEquals(1_000 + StartupWindow.MAX_MS, closedAt)
        val stall = w.record(closedAt, null).stalls.single()
        assertFalse(stall.ended)
        assertTrue(StartupText.stallLine(stall).endsWith("still running"), StartupText.stallLine(stall))
    }

    @Test
    fun theInProgressFileIsWrittenOnlyInAStallAndGoneWhenTheStartIsHealthy() {
        val w = window()
        w.mark("attachBaseContext", 1_200)
        assertEquals(StartupWindow.Progress.NONE, w.step(1_900).progress)
        assertEquals(StartupWindow.Progress.WRITE, w.step(2_000).progress, "1 s after the window opened, before the first frame")
        assertEquals(StartupWindow.Progress.NONE, w.step(2_500).progress)
        assertEquals(StartupWindow.Progress.WRITE, w.step(3_000).progress, "every second")
        w.appCreated(3_100)
        assertTrue(w.activityCreated(3_150))
        w.firstFrame(3_200)
        val deleting = w.step(3_300)
        assertEquals(StartupWindow.Progress.DELETE, deleting.progress, "a healthy first frame removes it")
        assertTrue(deleting.post)
        val lastPosted = deleting.seq
        assertEquals(StartupWindow.Progress.NONE, w.step(3_400).progress)
        // A stall: the heartbeat of step 3 300 ms stays out. From 1 s in, every second; its end under the report's line removes the file.
        val out = lastPosted
        assertEquals(StartupWindow.Progress.NONE, w.step(4_000).progress)
        assertEquals(StartupWindow.Progress.WRITE, w.step(4_400).progress)
        w.answered(out, 4_500)
        assertEquals(StartupWindow.Progress.DELETE, w.step(4_600).progress)
    }

    @Test
    fun aStallThatEarnsAReportKeepsItsFileAfterItEnds() {
        val w = coldTo(window(), frameAt = 2_000)
        val beat = w.step(2_100) // the first frame came: anything written before it is settled, and the first heartbeat goes
        assertTrue(beat.post)
        val writes = (3_100L..5_100L step 500).count { w.step(it).progress == StartupWindow.Progress.WRITE }
        assertTrue(writes >= 2, "writes $writes")
        w.answered(beat.seq, 5_200)
        assertEquals(StartupWindow.Progress.WRITE, w.step(5_300).progress, "one write says it ended")
        assertEquals(StartupWindow.Progress.NONE, w.step(5_400).progress)
        assertEquals(StartupWindow.Progress.NONE, w.step(6_100).progress)
    }

    private fun report(w: StartupWindow, now: Long, history: List<String> = listOf("0.1.20-alpha cold, first frame 1.20 s, longest stall 0.00 s, finished")): Report {
        val r = w.record(now, null)
        val head = mapOf("importance" to "100", "firstStart" to "update", "sinceBootS" to "120", "ramAvailMb" to "900", "ramTotalMb" to "3000", "lowMemory" to "false", "thermal" to "none", "batterySaver" to "false", "heapMaxMb" to "512", "heapUsedMb" to "40", "gcCount" to "3", "packs" to "2", "packBytes" to (180L * 1024 * 1024).toString())
        return Report("startup-x", MainWatchdog.Dump.parse(StartupText.format(r, head, system, history)))
    }

    /** A leftover as the in-progress file wrote it: the process force-closed in a 30 s stall under `DystopoApp.onCreate`. */
    private fun progressDump(activity: Boolean = false, importance: String = "100"): MainWatchdog.Dump {
        val w = window()
        w.mark("attachBaseContext", 1_200)
        w.mark("DystopoApp.onCreate", 1_250)
        run(w, 1_250, 6_250, stack = holdStack)
        if (activity) {
            w.appCreated(6_260)
            w.activityCreated(6_270)
        }
        val r = w.record(6_300, StartupWindow.RUNNING)
        return MainWatchdog.Dump.parse(StartupText.format(r, mapOf("importance" to importance), system, listOf(StartupText.historyLine(r, "0.1.21-alpha"))))
    }

    @Test
    fun aLeftoverBecomesAReportSayingTheProcessEndedAndWhy() {
        val d = progressDump()
        assertEquals(StartupLeftover.Verdict.REPORT, StartupLeftover.verdict(d.header, emptySet()), "foreground at attach, no activity yet: a launch killed in onCreate")
        val line = StartupText.historyLine(d, StartupWindow.ENDED)
        assertTrue(line.startsWith("0.1.21-alpha cold, first frame none, longest stall 0.00 s, ended"), line)
        val text = StartupLeftover.report(d, 10, "the user stopped the app", listOf(line))
        val r = Report("startup-y", MainWatchdog.Dump.parse(text))
        assertEquals("Startup freeze", r.kindLabel)
        assertEquals(StartupWindow.ENDED, r.dump.header["outcome"])
        assertEquals("10", r.dump.header["exitReason"])
        assertTrue(StartupWindow.REASON_NOT_FINISHED in r.dump.header["reasons"]!!.split(','))
        val shown = r.shown()
        assertTrue("the process ended during the start" in shown && "the user stopped the app" in shown, shown)
        assertTrue("under DystopoApp.onCreate, at Foo.slow(Foo.kt:7)" in shown, shown)
        assertEquals("Startup freeze: 5.3 s, at Foo.slow, process ended (0.1.21-alpha)", r.title(), "the time on the splash, at the step that took longest")
        assertEquals("cold", r.dump.header["startKind"])
        assertEquals("false", r.dump.header["activity"], "killed during DystopoApp.onCreate")
        assertTrue(shown.indexOf("What held the main thread") < shown.indexOf("Timeline"), shown)
        assertEquals(listOf(StartupWindow.REASON_NOT_FINISHED), r.dump.header["reasons"]!!.split(','), "not a first frame over its limit: there was none")
        assertEquals(listOf(line), r.dump.sections.first { it.first == StartupText.S_HISTORY }.second)
    }

    @Test
    fun aBackgroundLeftoverIsDiscardedAndAFinishedOneIsNotReportedTwice() {
        val background = progressDump(activity = false, importance = "400")
        assertEquals(StartupLeftover.Verdict.DISCARD, StartupLeftover.verdict(background.header, emptySet()))
        val activity = progressDump(activity = true, importance = "400")
        assertEquals(StartupLeftover.Verdict.REPORT, StartupLeftover.verdict(activity.header, emptySet()), "an activity mark makes it a start, whatever the importance at attach")
        val fg = progressDump()
        assertEquals(StartupLeftover.Verdict.DUPLICATE, StartupLeftover.verdict(fg.header, setOf(fg.header["startId"]!!)), "killed between the final write and the delete")
        assertEquals(StartupLeftover.Verdict.REPORT, StartupLeftover.verdict(fg.header, setOf("9-9")))
    }

    @Test
    fun startupReportsHaveTheirOwnQuota() {
        val dir = File.createTempFile("startup", "").let { it.delete(); it.mkdirs(); it }
        try {
            fun file(prefix: String, n: Int) = File(dir, "$prefix$n.txt").writeText("dystopo-report 1\nkind: x\natMs: ${1_000 + n}\n")
            for (n in 1..5) file(MainWatchdog.PREFIX, n)
            for (n in 6..9) file(StartupTrace.PREFIX, n)
            Reports.prune(dir)
            val left = Reports.list(dir).map { it.name }
            assertEquals(listOf("startup-9.txt", "startup-8.txt", "startup-7.txt"), left.filter { it.startsWith(StartupTrace.PREFIX) }, "the newest 3")
            assertEquals(5, left.count { it.startsWith(MainWatchdog.PREFIX) }, "no freeze pushed out by a newer start: $left")
            file(CrashCapture_PREFIX, 20)
            Reports.prune(dir)
            assertEquals(5, Reports.list(dir).count { !it.name.startsWith(StartupTrace.PREFIX) }, "the others' own five")
            assertEquals(3, Reports.list(dir).count { it.name.startsWith(StartupTrace.PREFIX) })
        } finally {
            dir.deleteRecursively()
        }
    }

    private val CrashCapture_PREFIX = "crash-"

    /** A full report: a stall, a held step, a long timeline, snapshots and a whole history. */
    private fun big(stalls: Int = 8, marks: Int = 40, history: Int = 20, longNames: Boolean = false): Report {
        val w = window()
        w.mark("attachBaseContext", 1_200)
        val deep = List(60) { if (longNames) "$app.very.long.package.name.SomeRatherLongClassName.someRatherLongMethodName$it(SomeRatherLongClassName.kt:$it)" else "$app.Some.method$it(Some.kt:$it)" }
        var t = 1_300L
        for (i in 0 until marks) {
            w.mark(if (i % 2 == 0) "a step that took long $i" else "short $i", t)
            if (i % 2 == 0) { run(w, t, t + 300, stack = deep); t += 400 } else t += 5
        }
        w.appCreated(t)
        w.activityCreated(t + 10)
        w.firstFrame(t + 2_000)
        t += 2_100
        for (i in 0 until stalls) {
            var s = w.step(t)
            while (!s.post && !s.close) { t += 1; s = w.step(t) }
            if (s.close) break
            t += 300
            w.step(t)
            w.addSample("running", 10, deep)
            t += 2_000 + i * 100
            w.answered(s.seq, t)
            t += 100
        }
        w.addSnapshot(StartupWindow.Snapshot(5_000, listOf("thread main (RUNNABLE)" to holdStack), listOf("tiles served: dem 3, osm 4, contours 5, glyphs 6", "tiles missing: 0, errors: 0", "map: frames 40, pending tiles 2, style failed no"), listOf("pendingTiles", "notFullyLoaded")))
        return report(w, t, List(history) { "0.1.21-alpha cold, first frame 1.2$it s, longest stall 0.00 s, finished" })
    }

    @Test
    fun theBodyLeadsWithWhatHeldTheThreadBeforeTheFirstFrameThenStallsAndStaysUnderTheLimit() {
        val r = big(stalls = 2, marks = 12)
        val link = IssueLink.build(r, Scrub.Known())
        assertFalse(link.titleOnly, "a full report still gets a body")
        assertTrue(link.encodedBodyLength in 1..IssueLink.MAX_BODY, "${link.encodedBodyLength}")
        val body = assertNotNull(link.body)
        val order = listOf("What held the main thread", "Main thread stalls", "Timeline", "Start: cold", "```").map { body.indexOf(it).also { i -> assertTrue(i >= 0, "$it missing: $body") } }
        assertEquals(order.sorted(), order, body)
        assertTrue(URLDecoder.decode(link.url.substringAfter("&body="), "UTF-8") == body)
        // The rest is on the clipboard: the deep stack's later frames and the whole timeline.
        val clip = assertNotNull(link.clipboard)
        assertTrue("method30" in clip, clip.take(400))
        assertTrue(body.trimEnd().endsWith(IssueLink.CLIPBOARD_LINE), body)
    }

    @Test
    fun thePathologicalReportStillGetsABodyWithItsTimeline() {
        val r = big(stalls = 12, marks = 60, longNames = true)
        val link = IssueLink.build(r, Scrub.Known())
        assertFalse(link.titleOnly)
        assertTrue(link.encodedBodyLength <= IssueLink.MAX_BODY, "${link.encodedBodyLength}")
        assertTrue("Timeline" in link.body!!)
    }

    @Test
    fun theTitleNamesTheTimeToTheFirstFrameAtTheStepThatTookLongest() {
        val w = coldTo(window(), frameAt = 15_500, packMs = 14_000)
        val r = report(w.also { it.fullyLoaded(15_600) }, 15_700)
        assertEquals("Startup freeze: 14.5 s, at PmTiles.header (0.1.21-alpha)", r.title(), "14.5 s on the splash, 14 of it in the longest step")
        assertTrue(r.shown().startsWith("Dystopo problem report: Startup freeze\nThe first frame took 14.50 s, over the 5.00 s limit for a cold start.\n"), r.shown().take(300))
        assertTrue(r.shown().indexOf("What held the main thread") < r.shown().indexOf("Timeline"), r.shown().take(300))
        assertTrue("14.00 s under PackSet.open, at PmTiles.header(PmTiles.kt:40)" in r.shown(), r.shown().take(500))
        // A start stuck on the splash that also stalled later: the splash leads, even past a longer stall.
        val both = coldTo(window(), frameAt = 6_500, packMs = 5_000)
        both.fullyLoaded(6_600)
        val beat = both.step(6_700)
        for (t in 7_000L..16_000L step 100) if (both.step(t).sample) both.addSample("waiting", 0, holdStack)
        both.answered(beat.seq, 16_100)
        val rb0 = report(both, 16_200)
        assertEquals("Startup freeze: 5.5 s, at PmTiles.header (0.1.21-alpha)", rb0.title())
        assertTrue(rb0.shown().indexOf("What held the main thread") < rb0.shown().indexOf("Main thread stalls"), rb0.shown().take(300))
        assertTrue(rb0.dump.sections.first { it.first.startsWith("main ") }.first.startsWith("main under PackSet.open"), "the longest step's stack comes first")
        // With a healthy first frame, a stall takes the title.
        val b = coldTo(window(), frameAt = 2_000)
        b.fullyLoaded(2_100)
        val s = b.step(2_200)
        for (t in 2_500L..5_000L step 100) if (b.step(t).sample) b.addSample("waiting", 0, holdStack)
        b.answered(s.seq, 5_200)
        assertEquals("Startup freeze: 3.0 s, at Foo.slow (0.1.21-alpha)", report(b, 5_300).title())
        // Samples spread over many stacks: the step's own name, not one frame that happened to come up twice.
        val spread = window(StartupWindow.WARM, at = 1_000)
        spread.mark("compose SettingsSheet", 1_000)
        for (i in 0 until 20) spread.addSample("running", 10, listOf("$app.Compose.step$i(Compose.kt:$i)"))
        spread.firstFrame(4_000)
        spread.fullyLoaded(4_100)
        assertEquals("Startup freeze: 3.0 s, at compose SettingsSheet (0.1.21-alpha)", report(spread, 4_200).title())
        // A blank map alone says so.
        val blank = coldTo(window(), frameAt = 2_000)
        run(blank, 2_000, 17_100)
        val rb = report(blank, 17_200)
        assertEquals("Startup freeze: map not drawn after 15 s (0.1.21-alpha)", rb.title())
        assertEquals("Startup freeze", rb.kindLabel)
        // A normal start reads as what it is.
        val ok = coldTo(window(), frameAt = 2_400)
        ok.fullyLoaded(2_500)
        val rok = report(ok, 2_600)
        assertEquals("Last start: first frame 1.4 s (0.1.21-alpha)", rok.title())
        assertEquals("Last start", rok.kindLabel)
        assertTrue("Passed the thresholds" in rok.shown(), rok.shown())
    }

    @Test
    fun theHistoryIsTheLastSectionAndTheHeaderIsReadable() {
        val r = big(stalls = 1, marks = 4, history = 3)
        assertEquals(StartupText.S_HISTORY, r.dump.sections.last().first)
        val shown = r.shown()
        assertTrue(shown.trimEnd().lines().last().endsWith("finished"), shown.takeLast(200))
        assertTrue(shown.indexOf("Recent starts") > shown.indexOf("    at "), "after the stacks")
        for (line in listOf("Start: cold, first after an update", "Phone: 120 s since boot, RAM 900 MB free of 3000 MB, thermal none, battery saver off", "Java heap at the first frame: 40 MB of 512 MB, GCs 3", "Offline maps: 2, 180 MB", "Loading and field initialisers before attachBaseContext, not sampled: 200 ms")) {
            assertTrue(line in shown, "$line: $shown")
        }
    }

    @Test
    fun theScrubLeavesATimelineUnchanged() {
        val r = big(stalls = 2, marks = 8)
        val lines = r.dump.sections.filter { it.first in setOf(StartupText.S_TIMELINE, StartupText.S_STALLS, StartupText.S_BLOCKED, StartupText.S_HISTORY) }.flatMap { it.second }
        assertTrue(lines.size > 10)
        for (l in lines) assertEquals(l, Scrub.text(l), "the scrub changed a line")
        // Real marks too, with a view's source in them.
        val w = window()
        for ((i, name) in listOf("attachBaseContext and content providers", "AppSettings.load", "BundledAssets.install", "PackSet.open", "collectContourGarbage's identities", "resumePending", "view from fix", "view from saved", "first traversal: attach and Compose's setup", "compose SettingsSheet", "composition applied: layout and draw").withIndex()) w.mark(name, 1_200L + i * 137)
        w.firstFrame(4_000)
        w.fullyLoaded(9_123)
        for (m in w.record(9_200, null).marks) StartupText.timelineLine(m).let { assertEquals(it, Scrub.text(it)) }
    }

    @Test
    fun aBlockedMainThreadNamesTheLocksHolderAndWhatItWasDoing() {
        val of = "$app.tracking.Tracking\$Companion.of(Tracking.kt:849)"
        val mainStack = listOf(of, "$app.tracking.TrackControlsKt.Controls(TrackControls.kt:80)", "android.view.Choreographer.doFrame(Choreographer.java:1)")
        val holderFrames = listOf("android.database.sqlite.SQLiteConnection.nativeOpen(Native Method)", "$app.tracking.TrackDb.open(TrackDb.kt:12)", of, "$app.trips.TripCorridors.reconcile(TripCorridors.kt:40)")
        val w = window(StartupWindow.WARM)
        w.mark("Tracking.of", 1_000)
        val inForce = w.markInForce()
        assertTrue(w.wantsLockDump(inForce))
        w.addSample(StartupWindow.LOCK, 0, mainStack, inForce)
        w.addLockDump(inForce, mainStack, listOf(
            "thread dystopo-corridors (RUNNABLE)" to holderFrames,
            "thread idle-one (WAITING)" to listOf("java.lang.Object.wait(Native Method)"),
            "thread other-blocked (BLOCKED)" to listOf(of),
        ))
        assertFalse(w.wantsLockDump(inForce), "one dump a step")
        for (i in 0 until 5) w.addSample(StartupWindow.LOCK, 0, mainStack, inForce)
        w.firstFrame(4_000)
        w.fullyLoaded(4_100)
        val r = report(w, 4_200)
        assertEquals("Startup freeze: 3.0 s, at Tracking\$Companion.of (0.1.21-alpha)", r.title())
        val shown = r.shown()
        assertTrue("3.00 s under Tracking.of, at Tracking\$Companion.of(Tracking.kt:849), 6 of 6 samples, lock 6, CPU 0.00 s, the lock held by dystopo-corridors at TrackDb.open(TrackDb.kt:12)" in shown, shown)
        val holder = shown.indexOf("thread dystopo-corridors (RUNNABLE), the lock's likely holder while main was blocked under Tracking.of")
        assertTrue(holder > 0, shown)
        assertTrue("TrackDb.open(TrackDb.kt:12)" in shown.substring(holder), shown)
        assertTrue(shown.indexOf("main under Tracking.of") < holder, "main's stack, then the holder's")
        assertTrue("thread other-blocked (BLOCKED), while main was blocked" in shown, shown)
        assertFalse("idle-one" in shown, "an idle thread is counted, not shown")
        // A lock whose stacks name nobody says so by naming nobody, and a blocked thread is never the holder.
        assertNull(StartupText.likelyHolder(mainStack, listOf("thread a (BLOCKED)" to listOf(of), "thread b (RUNNABLE)" to listOf("$app.Foo.a(Foo.kt:1)"))))
    }

    @Test
    fun theLockDumpsStopAtTheirCap() {
        val w = window(StartupWindow.WARM)
        val stack = listOf("$app.A.b(A.kt:1)")
        for (i in 0 until 5) {
            w.mark("m$i", 1_000L + i)
            val at = w.markInForce()
            if (w.wantsLockDump(at)) w.addLockDump(at, stack, emptyList())
        }
        assertEquals(StartupWindow.MAX_LOCK_DUMPS, w.record(2_000, null).lockDumps.size)
    }

    @Test
    fun threadsWithOneStackAreOneSection() {
        val one = listOf("$app.Foo.a(Foo.kt:1)")
        val out = StartupText.collapseThreads(listOf(
            "thread main (RUNNABLE)" to one,
            "thread direct-1 (BLOCKED)" to holdStack, "thread direct-2 (BLOCKED)" to holdStack, "thread other (BLOCKED)" to holdStack,
            "thread direct-3 (WAITING)" to holdStack,
        ))
        assertEquals(listOf("thread main (RUNNABLE)", "thread direct-1 (BLOCKED) and 2 more like it", "thread direct-3 (WAITING)"), out.map { it.first })
        assertTrue(Report.keep(out[1].first, out[1].second), "still kept as a blocked thread")
    }

    @Test
    fun aKnownPackNameInAStackIsStillScrubbed() {
        val w = coldTo(window(), frameAt = 7_000, packMs = 5_000)
        w.fullyLoaded(7_100)
        val r = report(w, 7_200)
        val shown = r.shown(Scrub.Known(packs = listOf("PmTiles")))
        assertFalse("PmTiles" in shown, shown)
        assertTrue("<pack>" in shown)
    }

    @Test
    fun theAppFrameTestSkipsReleasePlumbing() {
        // A release stack (ADR 0058): the real frame on top, plumbing below it, and a reference to a platform method whose only app frames are plumbing.
        val real = listOf(
            "java.lang.Thread.sleep(Native Method)",
            "$app.debug.DebugRoutes.freezeUi(DebugRoutes.kt:70)",
            "$app.debug.DebugRoutes.\$r8\$lambda\$abc123(DebugRoutes.kt:0)",
            "$app.debug.DebugRoutes\$\$ExternalSyntheticLambda3.run(R8\$\$SyntheticClass:0)",
            "android.os.Handler.handleCallback(Handler.java:959)",
        )
        assertEquals("$app.debug.DebugRoutes.freezeUi(DebugRoutes.kt:70)", MainWatchdog.blockingFrame(real))
        val reference = listOf(
            "android.view.View.invalidate(View.java:1)",
            "$app.Foo\$\$ExternalSyntheticLambda0.run(R8\$\$SyntheticClass:0)",
            "$app.Foo.\$r8\$lambda\$xyz(Foo.kt:0)",
            "$app.Foo.-\$\$Nest\$mbar(Foo.kt:0)",
            "$app.Foo.caller(Foo.kt:33)",
        )
        assertEquals("$app.Foo.caller(Foo.kt:33)", MainWatchdog.blockingFrame(reference))
        assertNull(MainWatchdog.blockingFrame(reference.take(3)), "nothing but plumbing names nothing")
        assertFalse(MainWatchdog.isAppFrame("$app.StartupTrace.mark(StartupTrace.kt:1)"))
        assertTrue(MainWatchdog.isAppFrame("$app.StartupRoutes.hold(StartupRoutes.kt:1)"), "the injected stall is what a test looks for")
        // The same test for the crash and ANR frames.
        val e = IllegalStateException("x")
        e.stackTrace = arrayOf(
            StackTraceElement("$app.Foo\$\$ExternalSyntheticLambda0", "run", "R8\$\$SyntheticClass", 0),
            StackTraceElement("$app.Foo", "real", "Foo.kt", 9),
        )
        val crash = MainWatchdog.Dump.parse(io.github.jasondorjeshort.dystopo.app.reports.CrashCapture.format(1L, "main", e, system))
        assertEquals("$app.Foo.real(Foo.kt:9)", crash.header["appFrame"])
        val anr = "\"main\" prio=5 tid=1 Blocked\n  at $app.Foo\$\$ExternalSyntheticLambda0.run(R8\$\$SyntheticClass:0)\n  at $app.Foo.real(Foo.kt:9)\n\n"
        val exit = MainWatchdog.Dump.parse(io.github.jasondorjeshort.dystopo.app.reports.ExitImport.format("anr", 6, 1L, 1, null, 0, 0, anr, system))
        assertEquals("$app.Foo.real(Foo.kt:9)", exit.header["appFrame"])
    }

    @Test
    fun aMainThreadsSampleReadsAsWhatItWasDoing() {
        assertEquals("lock", StartupWindow.classify("BLOCKED", 'S', 0, null))
        assertEquals("disk", StartupWindow.classify("RUNNABLE", 'D', 0, null))
        assertEquals("running", StartupWindow.classify("RUNNABLE", 'R', 10, null))
        assertEquals("starved", StartupWindow.classify("RUNNABLE", 'R', 0, null))
        assertEquals("idle", StartupWindow.classify("RUNNABLE", 'S', 0, "android.os.MessageQueue.nativePollOnce(Native Method)"))
        assertEquals("waiting", StartupWindow.classify("TIMED_WAITING", 'S', 0, "java.lang.Thread.sleep(Native Method)"))
        val t = assertNotNull(ThreadCpu.parse("4000 (dystopo.app) D 1 2 3 4 5 6 7 8 9 10 120 30 0 0", 4000))
        assertEquals('D', t.state)
        assertEquals(150, t.ticks)
    }

    @Test
    fun theFirstStartAndTheHistoryLine() {
        assertEquals("install", StartupText.firstStart(null, 5, 5), "no record, never updated")
        assertEquals("update", StartupText.firstStart(null, 5, 9), "no record: the first release with a trace")
        assertEquals("no", StartupText.firstStart(9, 5, 9))
        assertEquals("update", StartupText.firstStart(7, 5, 9), "the package changed since the last start, the version name or not")
        val w = coldTo(window(), frameAt = 2_000)
        val line = StartupText.historyLine(w.record(2_100, StartupWindow.FINISHED), "0.1.21-alpha")
        assertEquals("0.1.21-alpha cold, first frame 1.00 s, longest stall 0.00 s, finished", line)
    }

    @Test
    fun marksAfterTheCloseAreIgnoredAndTheyStopAtTheirCap() {
        val w = window(StartupWindow.WARM)
        repeat(StartupWindow.MAX_MARKS + 10) { w.mark("m$it", 1_000L + it) }
        assertEquals(StartupWindow.MAX_MARKS, w.record(2_000, null).marks.size)
        w.requestClose(StartupWindow.FINISHED)
        w.step(2_100)
        w.firstFrame(2_200)
        assertNull(w.record(2_300, null).firstFrameMs)
    }
}
