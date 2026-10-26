package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T144: the UI-thread watchdog's clock ([FreezeDetector]) and its dump file ([MainWatchdog.Dump]). */
class MainWatchdogTest {
    /** Runs the worker's loop against a fake clock until [until]; the main thread answers a heartbeat after [answerAfter] ms (null: never). */
    private class Sim(val d: FreezeDetector) {
        var now = 0L
        var dumps = 0
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
                if (s.dump) dumps++
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
    fun aFreezeIsReportedOnceHoweverLongItLasts() {
        val sim = Sim(FreezeDetector(1_000, 3_000))
        // The heartbeat sent at 10 s comes back 12 s later: one freeze, one dump.
        sim.run(40_000) { sent -> if (sent in 10_000..10_999) 12_000L else 5L }
        assertEquals(1, sim.dumps)
        assertEquals(1L, sim.d.freezes)
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
        assertEquals(2, sim.dumps)
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
        assertEquals(1, sim.dumps)
    }

    @Test
    fun theMostCommonSampledStackWins() {
        val d = FreezeDetector(1_000, 3_000)
        d.addSample(listOf("a"))
        d.addSample(listOf("b", "c"))
        d.addSample(listOf("b", "c"))
        val (top, share, n) = d.topStack()
        assertEquals(listOf("b", "c"), top)
        assertEquals(2, share)
        assertEquals(3, n)
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
