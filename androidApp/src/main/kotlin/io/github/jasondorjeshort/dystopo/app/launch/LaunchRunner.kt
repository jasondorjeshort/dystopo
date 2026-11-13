package io.github.jasondorjeshort.dystopo.app.launch

import io.github.jasondorjeshort.dystopo.launch.Bound
import io.github.jasondorjeshort.dystopo.launch.Runs
import io.github.jasondorjeshort.dystopo.launch.Stage
import io.github.jasondorjeshort.dystopo.launch.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR 0061 step 1: runs the declared launch ([stages], `shared` `launch.Launch`). Each bound stage starts when its needs are
 * done: a MAIN stage inline where its binding is ([run]: the bindings are the lines `DystopoApp.onCreate` and the activity
 * ran before, in the same order), a WORKER stage on [worker] ([start]: by default a daemon thread of its own, named
 * `launch-<stage>`; not `Dispatchers.IO`, whose cold start cost about 12 ms of main-thread and dispatch time on the emulator,
 * more than half the 20 ms the runner may cost); an OBSERVED stage is one whose own thread the
 * runner only times ([begin], [end]). Each value is a [Slot]; a stage's body returns its one value, published when it ends,
 * and a stage that throws publishes its failure to its slots.
 *
 * Every stage has a [Span]: when it was bound, ready to start (its needs done, and bound), started, ended, on which thread,
 * the main thread's wait on it, its failure. The runner's own overhead ([overheadNs]) is the sum over stages of start minus
 * ready-to-start (the injected delay excluded) plus its own main-thread time outside the bodies. Pure on the JVM: the clock,
 * the main-thread test and the worker come in ([LaunchRunnerTest]).
 */
class LaunchRunner(
    val stages: List<Stage>,
    private val isMain: () -> Boolean,
    private val worker: (String, Runnable) -> Unit = { name, r -> Thread(r, name).apply { isDaemon = true }.start() },
    private val nanos: () -> Long = System::nanoTime,
    private val delayMs: (Stage) -> Long = { 0L },
    private val strict: Boolean = true,
    private val hooks: Hooks = Hooks(),
) {
    /** The platform's side: the start trace's mark for a main-thread wait, a release build's main-thread read. */
    class Hooks(
        val waiting: (Stage?, Boolean) -> Unit = { _, _ -> },
        val mainRead: (Slot<*>, String, Boolean) -> Unit = { _, _, _ -> },
    )

    class Span(val stage: Stage) {
        @Volatile var boundAt = -1L
        @Volatile var readyAt = -1L
        @Volatile var startAt = -1L
        @Volatile var endAt = -1L
        @Volatile var thread: Thread? = null
        @Volatile var threadName: String? = null
        @Volatile var waitNs = 0L
        @Volatile var delayedMs = 0L
        /** The runner's own main-thread time for this stage (its part of [mainOverheadNs]). */
        @Volatile var runnerNs = 0L
        @Volatile var failure: Throwable? = null
        /** The class of the body the binding passed ([run]): the frame right above the runner's in a stack from inside it ([stageAt]). */
        @Volatile var bodyClass: String? = null
        val done = Done()

        /** Start minus ready-to-start, the delay excluded; 0 before it started. */
        val lagNs: Long get() = if (startAt < 0 || readyAt < 0) 0L else (startAt - readyAt - delayedMs * 1_000_000).coerceAtLeast(0)
    }

    private val byName = stages.associateBy { it.name }
    val spans: Map<String, Span> = stages.associate { it.name to Span(it) }
    private val slots = ConcurrentHashMap<String, Slot<*>>()
    private val mainNs = AtomicLong()

    /** When [MainActivity] reached its gate, and how long the main thread waited there. */
    @Volatile var gateAt = -1L
    @Volatile var gateWaitNs = 0L

    /** The runner's own main-thread time outside the bodies and the waits. */
    val mainOverheadNs: Long get() = mainNs.get()

    /** The sum over the stages off the main thread of start minus ready-to-start (a main stage's is inside [mainOverheadNs]). */
    val workerLagNs: Long get() = spans.values.filter { it.stage.runs != Runs.MAIN }.sumOf { it.lagNs }

    /**
     * ADR 0061's figure: the sum over stages of start minus ready-to-start, plus the runner's main-thread time. A MAIN stage's
     * start minus ready-to-start is the runner's own main-thread time, so it is counted once, there.
     */
    val overheadNs: Long get() = workerLagNs + mainNs.get()

    /** The slot of [v] (one per value, made on first ask). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> slot(v: Value<*>): Slot<T> = slots.getOrPut(v.name) {
        val writer = stages.firstOrNull { v in it.publishes }?.name ?: v.name
        Slot<Any>(isMain, name = v.name, gate = v.gate, onTimeout = v.onTimeout, stage = writer, strict = strict, onMainWait = { s, who, begins ->
            hooks.mainRead(s, who, begins)
            hooks.waiting(byName[writer], begins)
        })
    } as Slot<T>

    fun span(name: String): Span = spans[name] ?: throw IllegalArgumentException("no stage $name")

    /**
     * A MAIN stage, run now on this (the main) thread once its needs are done; returns what [body] returns, published. A stage
     * already run (a later activity, start or resume of the same process) runs [body] straight: a launch is the process's first.
     */
    fun <R> run(stage: Stage, body: () -> R): R {
        val s = span(stage.name)
        if (s.boundAt >= 0) return body()
        val t0 = nanos()
        check(stage.runs == Runs.MAIN && stage.bound == Bound.RUNNER) { "${stage.name} is not a main-thread stage the runner runs" }
        s.boundAt = t0
        s.bodyClass = body.javaClass.name
        val waited = awaitNeeds(stage)
        s.readyAt = maxOf(t0 + waited, needsEnd(stage))
        val delay = delayMs(stage)
        if (delay > 0) {
            s.delayedMs = delay
            Thread.sleep(delay)
        }
        s.thread = Thread.currentThread()
        s.threadName = Thread.currentThread().name
        s.startAt = nanos()
        (s.startAt - t0 - waited - delay * 1_000_000).let { mainNs.addAndGet(it); s.runnerNs += it }
        val r = try {
            body()
        } catch (e: Throwable) {
            ended(s, null, e)
            throw e
        }
        ended(s, r, null)
        return r
    }

    private fun ended(s: Span, value: Any?, failure: Throwable?) {
        val t1 = nanos()
        finish(s, value, failure, t1)
        (nanos() - t1).let { mainNs.addAndGet(it); s.runnerNs += it }
    }

    /** A WORKER stage: started on the worker once its needs are done; returns at once. */
    fun start(stage: Stage, body: () -> Any?) {
        val t0 = nanos()
        val s = span(stage.name)
        if (s.boundAt >= 0) return
        check(stage.runs == Runs.WORKER && stage.bound == Bound.RUNNER) { "${stage.name} is not a worker stage the runner runs" }
        s.boundAt = t0
        worker("launch-${stage.name}") {
            for (n in stage.needs) span(n).done.await(Long.MAX_VALUE)
            s.readyAt = maxOf(t0, needsEnd(stage))
            val t = Thread.currentThread()
            val was = t.name
            t.name = "launch-${stage.name}" // what a freeze report's stacks show (a pool's thread is renamed while it runs the stage)
            s.thread = t
            s.threadName = t.name
            val delay = delayMs(stage)
            if (delay > 0) {
                s.delayedMs = delay
                Thread.sleep(delay)
            }
            s.startAt = nanos()
            try {
                finish(s, body(), null)
            } catch (e: Throwable) {
                finish(s, null, e)
            } finally {
                s.thread = null
                t.name = was
            }
        }
        if (isMain()) (nanos() - t0).let { mainNs.addAndGet(it); s.runnerNs += it }
    }

    /** An OBSERVED stage begins on [thread] (its owner's own thread). */
    fun begin(stage: Stage, thread: String) {
        val s = span(stage.name)
        if (s.boundAt >= 0) return
        val now = nanos()
        s.boundAt = now
        s.readyAt = now
        s.startAt = now
        s.threadName = thread
    }

    /** An OBSERVED stage ends with [value] (or [failure]). */
    fun end(stage: Stage, value: Any?, failure: Throwable? = null) = finish(span(stage.name), value, failure)

    private fun finish(s: Span, value: Any?, failure: Throwable?, at: Long = nanos()) {
        s.endAt = at
        s.failure = failure
        for (v in s.stage.publishes) {
            val slot = slot<Any>(v)
            when {
                failure != null -> slot.failed(IllegalStateException("stage ${s.stage.name} failed: ${failure.message}", failure))
                value != null && s.stage.publishes.size == 1 -> slot.set(value)
            }
        }
        s.done.complete(Unit)
    }

    private fun needsEnd(stage: Stage): Long = stage.needs.maxOfOrNull { span(it).endAt } ?: -1L

    /** The main thread waits for a need not done yet (bounded, traced); returns the nanos it waited. */
    private fun awaitNeeds(stage: Stage): Long {
        var waited = 0L
        for (n in stage.needs) {
            val need = span(n)
            if (need.done.isCompleted) continue
            val t = nanos()
            hooks.waiting(need.stage, true)
            try {
                need.done.await(MAIN_WAIT_MS)
            } finally {
                hooks.waiting(need.stage, false)
            }
            val w = nanos() - t
            need.waitNs += w
            waited += w
        }
        return waited
    }

    /**
     * `MainActivity.onCreate`'s one wait before `startCamera` ([io.github.jasondorjeshort.dystopo.launch.Gate.BEFORE_ACTIVITY]):
     * every stage of that gate done, at most [MAIN_WAIT_MS]; on expiry it proceeds (traced).
     */
    fun awaitGate(gate: io.github.jasondorjeshort.dystopo.launch.Gate) {
        if (gateAt >= 0) return
        val t = nanos()
        var waited = 0L
        for (s in stages.filter { it.gate <= gate && it.bound == Bound.RUNNER }) {
            val sp = span(s.name)
            if (sp.boundAt < 0 || sp.done.isCompleted) continue
            val w0 = nanos()
            hooks.waiting(s, true)
            try {
                sp.done.await(MAIN_WAIT_MS)
            } finally {
                hooks.waiting(s, false)
            }
            val w = nanos() - w0
            sp.waitNs += w
            waited += w
        }
        gateAt = nanos()
        gateWaitNs = waited
        mainNs.addAndGet(gateAt - t - waited)
    }

    /** The MAIN stage whose body is [frame]'s class (a stack's frame right above [run]'s), or null. */
    fun stageAt(frame: StackTraceElement): Stage? =
        spans.values.firstOrNull { it.stage.runs == Runs.MAIN && it.bodyClass == frame.className }?.stage

    /** The awaited stage's thread while the main thread waits on it, for the trace's dump. */
    fun threadOf(stage: Stage): Thread? = spans[stage.name]?.thread

    /** Adds [ns] of the runner's own main-thread time spent outside a stage (its making, before the first stage). */
    fun addMain(ns: Long) {
        mainNs.addAndGet(ns)
    }

    /**
     * A stage's end, as the runner's own threads wait for it: a latch, so making the runner loads no coroutine machinery on the
     * main thread (the values' slots, which other code reads, are [CompletableDeferred][kotlinx.coroutines.CompletableDeferred]s).
     */
    class Done {
        private val latch = CountDownLatch(1)
        val isCompleted: Boolean get() = latch.count == 0L
        fun complete(@Suppress("UNUSED_PARAMETER") u: Unit) = latch.countDown()
        /** Waits at most [ms]; true when done. */
        fun await(ms: Long): Boolean = isCompleted || latch.await(ms, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** The bound on a main-thread wait for a stage (as `Tracking.WAIT_MS`). */
        const val MAIN_WAIT_MS = 30_000L
    }
}
