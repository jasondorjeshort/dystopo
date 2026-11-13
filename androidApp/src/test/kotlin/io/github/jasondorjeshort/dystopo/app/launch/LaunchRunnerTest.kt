package io.github.jasondorjeshort.dystopo.app.launch

import io.github.jasondorjeshort.dystopo.launch.Gate
import io.github.jasondorjeshort.dystopo.launch.OnTimeout
import io.github.jasondorjeshort.dystopo.launch.Runs
import io.github.jasondorjeshort.dystopo.launch.Stage
import io.github.jasondorjeshort.dystopo.launch.Value
import io.github.jasondorjeshort.dystopo.launch.Work
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T266 (ADR 0061): the runner's order, slots, failures and overhead, on the JVM with this thread as the main thread. */
class LaunchRunnerTest {
    private val main = Thread.currentThread()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "test-worker").apply { isDaemon = true } }

    private val v = Value<Any>("v", Gate.LATER)
    private val settings = Stage("settings", gate = Gate.BEFORE_ACTIVITY)
    private val worker = Stage("worker", needs = listOf("settings"), runs = Runs.WORKER, work = setOf(Work.DISK), gate = Gate.LATER, publishes = listOf(v))
    private val after = Stage("after", needs = listOf("settings"), gate = Gate.BEFORE_STYLE)

    private fun runner(stages: List<Stage>, strict: Boolean = true, delay: (Stage) -> Long = { 0L }) =
        LaunchRunner(stages, { Thread.currentThread() === main }, { _, r -> pool.execute(r) }, delayMs = delay, strict = strict)

    @Test fun aWorkerStageStartsAfterItsNeedsAndPublishesItsValue() {
        val r = runner(listOf(settings, worker, after))
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        r.run(settings) { order += "settings" }
        r.start(worker) { order += "worker:" + Thread.currentThread().name; "built" }
        r.run(after) { order += "after" }
        assertEquals("built", r.slot<String>(v).await(5_000, { "test" }, allowMain = true))
        assertTrue(order.indexOf("settings") < order.indexOfFirst { it.startsWith("worker") })
        assertEquals("worker:launch-worker", order.first { it.startsWith("worker") }, "the worker thread is named after its stage while it runs")
        val sp = r.span("worker")
        assertTrue(sp.startAt >= r.span("settings").endAt && sp.readyAt >= r.span("settings").endAt)
        assertEquals("launch-worker", sp.threadName)
        assertTrue(r.overheadNs >= 0 && r.overheadNs < TimeUnit.SECONDS.toNanos(1), "overhead ${r.overheadNs} ns")
    }

    @Test fun aMainReadBeforeReadinessFailsByNameInDebugAndWaitsInRelease() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val strict = runner(listOf(settings, worker))
        strict.run(settings) {}
        strict.start(worker) { gate.await(); "late" }
        val e = assertFailsWith<IllegalStateException> { strict.slot<String>(v).get(5_000) { "Caller.site(Caller.kt:1)" } }
        assertTrue(e.message!!.contains("Caller.site(Caller.kt:1)") && e.message!!.contains("main thread") && e.message!!.contains("stage worker"), e.message)
        gate.countDown()
        assertEquals("late", strict.slot<String>(v).await(5_000, { "after" }, allowMain = true))

        val seen = ArrayList<String>()
        val release = LaunchRunner(listOf(settings, worker), { Thread.currentThread() === main }, { _, r -> pool.execute(r) }, strict = false,
            hooks = LaunchRunner.Hooks(mainRead = { s, who, begins -> if (begins) seen += "${s.name} $who" }))
        release.run(settings) {}
        release.start(worker) { Thread.sleep(100); "waited" }
        assertEquals("waited", release.slot<String>(v).get(5_000) { "Caller.site" })
        assertEquals(listOf("v Caller.site"), seen, "the release read is told to the trace")
    }

    @Test fun aFailedStagePublishesItsFailureNamingTheStage() {
        val r = runner(listOf(settings, worker))
        r.run(settings) {}
        r.start(worker) { throw IllegalStateException("disk gone") }
        val e = pool.submit<Throwable?> { runCatching { r.slot<String>(v).get(5_000) { "reader" } }.exceptionOrNull() }.get()
        assertTrue(e is IllegalStateException && e.message!!.contains("stage worker"), e?.message)
        assertTrue(r.span("worker").failure is IllegalStateException)
    }

    @Test fun theReaderBuildsATrackerWhoseStageFailed() {
        val t = Value<Any>("tracker", Gate.LATER, OnTimeout.NEXT_READER_BUILDS)
        val stage = Stage("tracking", runs = Runs.WORKER, gate = Gate.LATER, publishes = listOf(t))
        val r = runner(listOf(stage))
        r.start(stage) { throw IllegalStateException("locked") }
        assertNull(pool.submit<Any?> { r.slot<Any>(t).await(5_000, { "corridors" }) }.get())
    }

    @Test fun aDelayIsHeldBeforeTheBodyAndNotCountedAsOverhead() {
        val r = runner(listOf(settings, worker), delay = { if (it.name == "worker") 150L else 0L })
        r.run(settings) {}
        r.start(worker) { "x" }
        assertEquals("x", r.slot<String>(v).await(5_000, { "t" }, allowMain = true))
        val sp = r.span("worker")
        assertEquals(150L, sp.delayedMs)
        assertTrue(sp.startAt - sp.readyAt >= TimeUnit.MILLISECONDS.toNanos(150))
        assertTrue(sp.lagNs < TimeUnit.MILLISECONDS.toNanos(100), "lag ${sp.lagNs} ns")
    }

    @Test fun aStageRunsOnceAndALaterCallRunsItsBodyStraight() {
        val r = runner(listOf(settings))
        assertEquals(1, r.run(settings) { 1 })
        val first = r.span("settings").startAt
        assertEquals(2, r.run(settings) { 2 })
        assertEquals(first, r.span("settings").startAt)
    }

    @Test fun theMainThreadWaitsAtTheGateForAWorkerStageOfIt() {
        val early = Stage("early", runs = Runs.WORKER, gate = Gate.BEFORE_ACTIVITY)
        val waits = ArrayList<Pair<String?, Boolean>>()
        val r = LaunchRunner(listOf(early), { Thread.currentThread() === main }, { _, r -> pool.execute(r) },
            hooks = LaunchRunner.Hooks(waiting = { s, b -> waits += s?.name to b }))
        r.start(early) { Thread.sleep(100) }
        r.awaitGate(Gate.BEFORE_ACTIVITY)
        assertTrue(r.span("early").done.isCompleted)
        assertTrue(r.gateWaitNs >= TimeUnit.MILLISECONDS.toNanos(50), "waited ${r.gateWaitNs} ns")
        assertEquals(listOf<Pair<String?, Boolean>>("early" to true, "early" to false), waits, "the wait is a mark of its own")
    }

    @Test fun theSlotIsTheSameObjectForAValue() {
        val r = runner(listOf(settings, worker))
        assertSame(r.slot<Any>(v), r.slot<Any>(v))
    }
}
