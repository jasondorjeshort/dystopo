package io.github.jasondorjeshort.dystopo.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T144: the UI-thread watchdog's clock ([FreezeDetector]) and its dump file ([MainWatchdog.Dump]); T227: the rewrites. */
class MainWatchdogTest {
    /** Runs the worker's loop against a fake clock until [until]; the main thread answers a heartbeat after [answerAfter] ms (null: never). */
    private class Sim(val d: FreezeDetector) {
        var now = 0L
        val writes = ArrayList<FreezeDetector.Freeze>()
        val dumps get() = writes.size
        var samples = 0
        var posted = 0
        private var pending: Pair<Long, Long>? = null // seq, when it will be answered

        fun run(until: Long, armed: Boolean = true, answerAfter: (Long) -> Long? = { 5L }) {
            while (now < until) {
                pending?.let { (seq, at) -> if (now >= at) { d.answered(seq, at); pending = null } }
                val s = d.step(now, armed)
                if (s.post) {
                    posted++
                    answerAfter(now)?.let { pending = s.seq to now + it }
                }
                if (s.sample) { samples++; d.addSample(listOf("io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:1)", "android.os.Looper.loop")) }
                s.dump?.let { writes += it }
                now += minOf(s.sleepMs, 50).coerceAtLeast(1)
            }
        }
    }

    @Test
    fun aHealthyMainThreadIsNeverReported() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.run(60_000)
        assertEquals(0, sim.dumps)
        assertEquals(0, sim.samples, "no sampling while the heartbeats come back")
        assertTrue(sim.posted in 55..61, "one heartbeat a second (${sim.posted})")
    }

    @Test
    fun aFreezeThatEndsIsRewrittenWhileItLastsAndOnceWithItsTrueLength() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        // The heartbeat sent at 10 s comes back 12 s later: one freeze, written at 3 s, at 8 s and after it ended.
        sim.run(40_000) { sent -> if (sent in 10_000..10_999) 12_000L else 5L }
        assertEquals(1L, sim.d.freezes)
        assertEquals(3, sim.dumps, sim.writes.map { it.silentMs }.toString())
        assertEquals(1, sim.writes.map { it.id }.distinct().size, "one freeze, one id")
        assertEquals(listOf(false, false, true), sim.writes.map { it.ended })
        assertTrue(sim.writes[0].silentMs in 3_000..3_200, "${sim.writes[0].silentMs}")
        assertTrue(sim.writes[1].silentMs in 8_000..8_400, "${sim.writes[1].silentMs}")
        assertEquals(12_000, sim.writes[2].silentMs, "the true length")
        assertEquals(12_000, sim.d.longestSilentMs)
        assertTrue(sim.samples >= 50, "sampled every 200 ms after the first silent second (${sim.samples})")
        val (top, share, n) = sim.d.topStack()
        assertEquals(0, n, "the samples are dropped when the heartbeat returns")
        assertNull(top)
        assertEquals(0, share)
    }

    @Test
    fun itRearmsAfterAHeartbeatReturnsAndReportsTheNextFreeze() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.run(60_000) { sent -> if (sent in 5_000..5_999 || sent in 30_000..30_999) 4_000L else 5L }
        assertEquals(2L, sim.d.freezes)
        assertEquals(2, sim.writes.map { it.id }.distinct().size, "two freezes, two ids")
        assertEquals(2, sim.writes.count { it.ended })
    }

    @Test
    fun aFreezeThatHasNotEndedReadsItsLengthSoFarAndNotEnded() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.run(20_000) { sent -> if (sent in 5_000..5_999) null else 5L } // silent from about 5 s, read at 20 s
        val last = sim.writes.last()
        assertFalse(last.ended)
        assertTrue(last.silentMs in 13_000..15_000, "${last.silentMs}")
        assertTrue(sim.writes.none { it.ended })
        assertEquals(1, sim.writes.map { it.id }.distinct().size)
        assertEquals(1L, sim.d.freezes)
        assertEquals(3, sim.writes.size, "3 s, 8 s and 13 s of silence")
    }

    @Test
    fun theSamplesLastToTheFinalWriteAndAreThenDropped() {
        val d = FreezeDetector(1_000, 3_000)
        val seq = d.step(0, true).seq
        assertTrue(d.step(3_000, true).dump != null)
        d.addSample(listOf("a"))
        d.addSample(listOf("b"))
        d.addSample(listOf("b"))
        d.answered(seq, 4_000)
        // A sample that lost the race with the heartbeat belongs to no freeze; the final write is still owed.
        d.addSample(listOf("late"))
        val last = d.step(4_100, true).dump!!
        assertTrue(last.ended)
        assertEquals(4_000, last.silentMs)
        assertEquals(3, last.samples)
        assertEquals(listOf(listOf("b") to 2, listOf("a") to 1), last.stacks)
        assertNull(d.topStack().first, "gone after the final write")
        assertNull(d.step(4_200, true).dump, "written once")
    }

    @Test
    fun aPauseAfterTheFreezeEndsItAndAResetDoesNotLoseTheLastWrite() {
        val d = FreezeDetector(1_000, 3_000)
        d.step(0, true)
        assertTrue(d.step(3_000, true).dump != null)
        // onPause ran on the main thread, so the silence is over, though its heartbeat has not run yet.
        d.mainRan(9_000)
        d.reset()
        val last = d.step(9_100, false).dump!!
        assertTrue(last.ended, "written though no longer armed")
        assertEquals(9_000, last.silentMs)
    }

    @Test
    fun aResetOffTheMainThreadLeavesTheFileNotEnded() {
        val d = FreezeDetector(1_000, 3_000)
        d.step(0, true)
        assertFalse(d.step(3_000, true).dump!!.ended)
        assertNull(d.step(3_200, false).dump, "a debugger attached: nobody saw the freeze end")
        assertEquals(0, d.topStack().third)
    }

    @Test
    fun oneFileForOneFreezeAndItsLastThreadsSurviveTheFinalWrite() {
        val dir = Files.createTempDirectory("freeze").toFile()
        try {
            val file = MainWatchdog.FreezeFile(dir)
            val stack = listOf("java.lang.Thread.sleep(Native Method)", "io.github.jasondorjeshort.dystopo.app.debug.DebugRoutes.freezeUi(DebugRoutes.kt:70)")
            val other = listOf("io.github.jasondorjeshort.dystopo.app.Other.run(Other.kt:1)")
            fun freeze(id: Long, ms: Long, ended: Boolean, samples: Int, vararg stacks: Pair<List<String>, Int>) = FreezeDetector.Freeze(id, ms, ended, stacks.toList(), samples)
            fun write(f: FreezeDetector.Freeze, at: Long, threads: String) =
                file.write(f, at, 1_000, 3_000, mapOf("version" to "1"), { listOf("thread main ($threads)" to stack) })
            val first = write(freeze(1, 3_000, false, 10, stack to 10), 1_000_000, "at 3 s")
            val second = write(freeze(1, 8_000, false, 35, stack to 30, other to 5), 1_005_000, "at 8 s")
            assertEquals(first, second)
            assertEquals(1, dir.listFiles()!!.size, "one file, and no tmp left")
            val middle = MainWatchdog.Dump.parse(first.readText())
            assertEquals("8000", middle.header["silentMs"])
            assertEquals("false", middle.header["ended"])
            val last = write(freeze(1, 12_000, true, 55, stack to 50, other to 5), 1_009_000, "after the freeze")
            assertEquals(first, last)
            val d = MainWatchdog.Dump.parse(last.readText())
            assertEquals("12000", d.header["silentMs"])
            assertEquals("true", d.header["ended"])
            assertEquals("1000000", d.header["atMs"], "the freeze's time stays its first write's")
            assertEquals("55", d.header["samples"])
            assertEquals("50", d.header["topStackShare"])
            assertEquals(stack[1], d.blockingFrame)
            assertEquals(listOf("main (most common sampled stack)", "main (sampled 5 of 55)", "thread main (at 8 s)"), d.sections.map { it.first }, "the threads as of the freeze, not after it")
            val next = write(freeze(2, 3_000, false, 1, other to 1), 2_000_000, "next")
            assertTrue(next != first, "the next freeze has its own file")
            assertEquals(2, MainWatchdog.list(dir).size)
            // No sample yet: the main thread's stack at the write names the frame, and the histogram stays capped.
            val bare = MainWatchdog.Dump.parse(write(freeze(3, 3_000, false, 0), 3_000_000, "bare").readText())
            assertEquals(stack, bare.sections.first().second)
            val many = (1..9).map { listOf("s$it") to 10 - it }.toTypedArray()
            val capped = MainWatchdog.Dump.parse(write(freeze(4, 3_000, false, 45, *many), 4_000_000, "many").readText())
            assertEquals(MainWatchdog.FreezeFile.STACKS_WRITTEN + 1, capped.sections.size)
            for (i in 5..8) write(freeze(i.toLong(), 3_000, false, 0), i * 1_000_000L, "more")
            assertEquals(MainWatchdog.KEEP, MainWatchdog.list(dir).size, "the oldest deleted")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aStallShorterThanTheThresholdIsNotAFreeze() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.run(30_000) { sent -> if (sent in 5_000..5_999) 2_000L else 5L } // POST /debug/freeze?ms=2000
        assertEquals(0, sim.dumps)
        assertTrue(sim.samples > 0, "sampled after the first silent second")
    }

    @Test
    fun notArmedMeansNoHeartbeatAndTheClockStartsOver() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.run(10_000, armed = false) { null }
        assertEquals(0, sim.posted)
        assertEquals(0, sim.dumps)
        // A heartbeat lost at pause (never answered) does not count once resumed: reset, then a fresh one.
        sim.run(12_000) { null }
        sim.d.reset()
        sim.run(13_500) { 5L }
        assertEquals(0, sim.dumps)
    }

    @Test
    fun theThresholdsCanBeOverridden() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        sim.d.configure(200, 600)
        sim.run(10_000) { sent -> if (sent in 2_000..2_199) 1_500L else 5L }
        assertEquals(1L, sim.d.freezes)
    }

    @Test
    fun theMostCommonSampledStackWins() {
        val d = FreezeDetector(1_000, 3_000)
        d.step(0, true)
        d.addSample(listOf("a"))
        d.addSample(listOf("b", "c"))
        d.addSample(listOf("b", "c"))
        val (top, share, n) = d.topStack()
        assertEquals(listOf("b", "c"), top)
        assertEquals(2, share)
        assertEquals(3, n)
    }

    @Test
    fun aBusyFreezeKeepsAtMostAHundredStacksAndCountsEverySample() {
        val d = FreezeDetector(1_000, 3_000)
        d.step(0, true)
        repeat(300) { d.addSample(listOf("s$it")) }
        d.addSample(listOf("s0"))
        val (top, share, n) = d.topStack()
        assertEquals(listOf("s0"), top)
        assertEquals(2, share)
        assertEquals(301, n)
    }

    @Test
    fun theBlockingFrameIsTheTopmostAppFrame() {
        val stack = listOf(
            "java.lang.Thread.sleep(Native Method)",
            "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:120)",
            "io.github.jasondorjeshort.dystopo.app.Routes.route\$lambda\$3(Routes.kt:200)",
            "android.os.Looper.loop(Looper.java:317)",
        )
        assertEquals("io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:120)", MainWatchdog.blockingFrame(stack))
        assertNull(MainWatchdog.blockingFrame(listOf("android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native Method)")))
        assertEquals("io.github.jasondorjeshort.dystopo.app.X.y(X.kt:1)",
            MainWatchdog.blockingFrame(listOf("io.github.jasondorjeshort.dystopo.app.MainWatchdog.loop(MainWatchdog.kt:1)", "io.github.jasondorjeshort.dystopo.app.X.y(X.kt:1)")))
    }

    @Test
    fun theDumpFileReadsBackAsWritten() {
        val header = linkedMapOf("kind" to "freeze", "atMs" to "1761400000000", "silentMs" to "3012", "blockingFrame" to "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:120)")
        val sections = listOf(
            "main (most common sampled stack)" to listOf("java.lang.Thread.sleep(Native Method)", "io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:120)"),
            "thread main (TIMED_WAITING)" to listOf("java.lang.Thread.sleep(Native Method)"),
            "thread empty (WAITING)" to emptyList(),
        )
        val text = MainWatchdog.Dump.format(header, sections)
        assertTrue(text.startsWith("dystopo-report 1\n"))
        val d = MainWatchdog.Dump.parse(text)
        assertEquals(header, d.header)
        assertEquals("freeze", d.kind)
        assertEquals("io.github.jasondorjeshort.dystopo.app.Routes.freezeUi(Routes.kt:120)", d.blockingFrame)
        assertEquals(sections, d.sections)
        // CRLF (a file copied through Windows) and unknown keys read too; a value never spans lines.
        val crlf = MainWatchdog.Dump.parse(text.replace("\n", "\r\n").replace("kind: freeze", "kind: freeze\r\nfuture: x"))
        assertEquals("x", crlf.header["future"])
        assertEquals(sections, crlf.sections)
        assertFalse(MainWatchdog.Dump.format(mapOf("k" to "a\nb"), emptyList()).contains("a\nb"))
        assertNull(MainWatchdog.Dump.parse(MainWatchdog.Dump.format(mapOf("blockingFrame" to "-"), emptyList())).blockingFrame)
        assertFailsWith<IllegalArgumentException> { MainWatchdog.Dump.parse("something else") }
    }
}
