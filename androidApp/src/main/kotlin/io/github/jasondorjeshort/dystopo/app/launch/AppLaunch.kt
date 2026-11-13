package io.github.jasondorjeshort.dystopo.app.launch

import android.os.Looper
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.StartupTrace
import io.github.jasondorjeshort.dystopo.app.debug.StrictModeCounts
import io.github.jasondorjeshort.dystopo.launch.Bound
import io.github.jasondorjeshort.dystopo.launch.Launch
import io.github.jasondorjeshort.dystopo.launch.Runs
import io.github.jasondorjeshort.dystopo.launch.Stage
import io.github.jasondorjeshort.dystopo.launch.Value
import io.github.jasondorjeshort.dystopo.launch.Work
import io.github.jasondorjeshort.dystopo.launch.markMatches
import java.io.File

/**
 * T266 (ADR 0061 step 1): the process's one [LaunchRunner] over `shared`'s [Launch], its slots, the debug delays and
 * `GET /debug/launch`. `DystopoApp.onCreate` and `MainActivity` bind each stage's body (the lines they ran before, in the
 * same order and on the same threads); `Tracking`, `OnlineService.get`, `PackManager.get` and the world overview's copy
 * read their slots here.
 */
object AppLaunch {
    private fun isMain() = Looper.myLooper() == Looper.getMainLooper()
    private fun now() = SystemClock.elapsedRealtimeNanos()

    /** `POST /debug/launch/delay`'s file: `stage ms` lines, taken (and forgotten) at the next launch. */
    private const val DELAY_FILE = ".launch-delay"

    @Volatile private var delays: Map<String, Long> = emptyMap()

    /** How long `shared`'s list took to make, on the thread that made this object (`launch-make`, `DystopoApp.attachBaseContext`). */
    private val listNs: Long = run { val t = now(); Launch.stages; now() - t }

    val runner: LaunchRunner = LaunchRunner(
        Launch.stages, ::isMain, nanos = ::now,
        delayMs = { delays[it.name] ?: 0L },
        strict = BuildConfig.DEBUG,
        hooks = LaunchRunner.Hooks(
            waiting = { s, begins -> StartupTrace.waitingFor(s?.name, begins) },
            mainRead = { slot, who, begins -> if (begins) StartupTrace.mainThreadRead(slot.name, who) },
        ),
    )

    fun <T : Any> slot(v: Value<*>): Slot<T> = runner.slot(v)

    /** `DystopoApp.onCreate`, first: the debug builds' delays for this launch (read once, then forgotten), and the trace's hooks. */
    /** The main thread's part of the list's and the runner's making (its wait for `launch-make`, or the making): runner time. */
    @Volatile var madeNs = 0L
        private set

    /** [calledAt]: when `DystopoApp.onCreate` called this (before this object's making, if the main thread is the one making it). */
    fun install(app: DystopoApp, calledAt: Long) {
        madeNs = now() - calledAt
        runner.addMain(madeNs)
        StartupTrace.awaitedThread = { name -> Launch.stage(name)?.let(runner::threadOf) }
        if (BuildConfig.DEBUG_ENDPOINTS) delays = allowingDisk { takeDelays(app) }
        if (BuildConfig.DEBUG) StrictModeCounts.stageOf = ::stageOf
    }

    /**
     * The stage a main-thread StrictMode violation with [stack] happened in: the stage whose body is the frame right above the
     * runner's in the stack; without one, the observed stage whose mark is in force (the composition, the style), else
     * `outside a stage: <mark>`, else [AFTER].
     */
    fun stageOf(stack: Array<StackTraceElement>): String {
        val runner = LaunchRunner::class.java.name
        val i = stack.indexOfLast { it.className == runner && it.methodName == "run" }
        if (i > 0) this.runner.stageAt(stack[i - 1])?.let { return it.name }
        val m = StartupTrace.markInForceName() ?: return AFTER
        return Launch.stageOf(m)?.takeIf { it.bound == Bound.OBSERVED }?.name ?: "outside a stage: $m"
    }

    const val AFTER = "after the launch"

    // ---------- the debug delays ----------

    private fun file(app: DystopoApp) = File(app.filesDir, DELAY_FILE)

    fun pendingDelays(app: DystopoApp): Map<String, Long> = runCatching {
        file(app).readLines().mapNotNull { l -> l.trim().split(' ').takeIf { it.size == 2 }?.let { (s, ms) -> ms.toLongOrNull()?.let { s to it } } }.toMap()
    }.getOrDefault(emptyMap())

    /** Holds [stage] [ms] at the next launch (0 forgets it), once. A file, so a force-stop keeps it. */
    fun setDelay(app: DystopoApp, stage: String, ms: Long) {
        val next = pendingDelays(app).toMutableMap()
        if (ms <= 0) next.remove(stage) else next[stage] = ms
        val f = file(app)
        if (next.isEmpty()) f.delete() else f.writeText(next.entries.joinToString("") { "${it.key} ${it.value}\n" })
    }

    private fun takeDelays(app: DystopoApp): Map<String, Long> = pendingDelays(app).also { if (it.isNotEmpty()) file(app).delete() }

    /** The delays this launch took. */
    val delaysTaken: Map<String, Long> get() = delays

    private fun <R> allowingDisk(block: () -> R): R {
        val old = StrictMode.allowThreadDiskWrites()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(old)
        }
    }

    // ---------- GET /debug/launch ----------

    private fun ms(ns: Long, origin: Long): Long? = if (ns < 0) null else ns / 1_000_000 - origin

    /** `GET /debug/startup`'s `launch`: the runner's overhead (ADR 0061 step 1's figure) and its parts. */
    fun summary(): Map<String, Any?> = linkedMapOf(
        "overheadMs" to runner.overheadNs / 1e6,
        "mainMs" to runner.mainOverheadNs / 1e6,
        "lagMs" to runner.workerLagNs / 1e6,
        "madeMs" to madeNs / 1e6,
        "listMs" to listNs / 1e6,
    )

    /** The observed MAIN stages' spans, from the start trace's marks: from the stage's first mark to the next mark not its own. */
    private fun observed(marks: List<Pair<String, Long>>): Map<String, Pair<Long, Long?>> {
        val out = LinkedHashMap<String, Pair<Long, Long?>>()
        for (s in Launch.stages.filter { it.bound == Bound.OBSERVED && it.runs == Runs.MAIN }) {
            val i = marks.indexOfFirst { (n, _) -> s.marks.any { markMatches(it, n) } }
            if (i < 0) continue
            var j = i
            while (j + 1 < marks.size && s.marks.any { markMatches(it, marks[j + 1].first) }) j++
            out[s.name] = marks[i].second to marks.getOrNull(j + 1)?.second
        }
        return out
    }

    fun json(app: DystopoApp?): Map<String, Any?> {
        val origin = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)
        val frameAt = StartupTrace.firstFrameAt()
        val seen = observed(StartupTrace.markTimes())
        val strict = StrictModeCounts.byStage()
        val rows = Launch.stages.map { s ->
            val sp = runner.span(s.name)
            val obs = seen[s.name]
            val start = if (sp.startAt >= 0) ms(sp.startAt, origin) else obs?.first?.minus(origin)
            val end = if (sp.endAt >= 0) ms(sp.endAt, origin) else obs?.second?.minus(origin)
            val counts = strict[s.name].orEmpty()
            linkedMapOf(
                "name" to s.name, "runs" to s.runs.name, "bound" to s.bound.name, "thread" to (sp.threadName ?: if (obs != null) "main" else null),
                "needs" to s.needs, "gate" to s.gate.name, "work" to s.work.map { it.name }, "marks" to s.marks,
                "publishes" to s.publishes.map { it.name }, "reads" to s.reads.map { it.name }, "allowed" to s.allowed,
                "boundMs" to ms(sp.boundAt, origin), "readyMs" to ms(sp.readyAt, origin), "startMs" to start, "endMs" to end,
                "ms" to if (start != null && end != null) end - start else null,
                "lagMs" to sp.lagNs / 1e6, "runnerMs" to sp.runnerNs / 1e6, "waitMs" to sp.waitNs / 1e6, "delayedMs" to sp.delayedMs,
                "beforeFirstFrame" to (end != null && frameAt != null && end + origin <= frameAt),
                "failure" to sp.failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
                "strictMode" to counts, "undeclared" to undeclared(s, counts),
            )
        }
        return linkedMapOf(
            "stages" to rows,
            "overheadMs" to runner.overheadNs / 1e6, "mainMs" to runner.mainOverheadNs / 1e6, "madeMs" to madeNs / 1e6, "workerLagMs" to runner.workerLagNs / 1e6,
            "gates" to linkedMapOf("BEFORE_ACTIVITY" to linkedMapOf("atMs" to ms(runner.gateAt, origin), "waitMs" to runner.gateWaitNs / 1e6)),
            "firstFrameMs" to frameAt?.minus(origin),
            "slots" to Launch.values.map { v ->
                val sl = slot<Any>(v)
                linkedMapOf("name" to v.name, "stage" to sl.stage, "gate" to v.gate.name, "onTimeout" to v.onTimeout.name, "ready" to (sl.value != null), "failure" to sl.failure?.message)
            },
            "strictModeOutside" to strict.filterKeys { Launch.stage(it) == null },
            "delaysTaken" to delays,
            "pendingDelays" to app?.let(::pendingDelays),
        )
    }

    /** The StrictMode kinds a stage's declared work does not allow (the device tour's check, ADR 0061), empty when it does. */
    fun undeclared(s: Stage, counts: Map<String, Long>): List<String> = counts.keys.filter { kind ->
        val need = workOf(kind) ?: return@filter false
        s.runs == Runs.MAIN && need !in s.work
    }

    /** The work a thread-policy violation kind stands for, or null for kinds no declaration names. */
    fun workOf(kind: String): Work? = when (kind) {
        "DiskReadViolation", "DiskWriteViolation", "UnbufferedIoViolation" -> Work.DISK
        "NetworkViolation" -> Work.SOCKET
        else -> null
    }
}
