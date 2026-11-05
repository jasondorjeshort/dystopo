package io.github.jasondorjeshort.dystopo.app

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import io.github.jasondorjeshort.dystopo.app.debug.ThreadCpu
import io.github.jasondorjeshort.dystopo.app.reports.Report
import io.github.jasondorjeshort.dystopo.app.reports.ReportUi
import io.github.jasondorjeshort.dystopo.app.reports.Reports
import io.github.jasondorjeshort.dystopo.app.settings.Diagnostics
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T228: a trace of every start, in every build, no setting (T144's reasoning). A tester whose app froze at launch sends
 * one report that says where the time went; a later task moves the launch's work off the main thread on this evidence.
 * This measures only.
 *
 * **Windows.** One opens at process start (`DystopoApp.attachBaseContext`; provisional until an activity appears), at a
 * `MainActivity.onCreate` in a live process, and at an `onStart` with none open. **Cold** is an `onCreate` within
 * [StartupWindow.COLD_WITHIN_MS] of `DystopoApp.onCreate`'s end in a new process, measured from process start; **warm** is
 * any other `onCreate`, measured from it (a process started by WorkManager or the tracking service and brought forward
 * later is warm); **hot** is the `onStart` case. On API 35 and up, `ApplicationStartInfo`'s start type wins when the
 * record matches this pid and this window's time. A window closes [StartupWindow.CLOSE_AFTER_MS] after the first drawn
 * frame ([StartupWindow.HOT_CLOSE_AFTER_MS] when hot), or at the end of a stall still running then, at
 * [StartupWindow.MAX_MS] at most, or at the activity's `onStop`; its worker then stops. [MainWatchdog] writes no freeze
 * dump while one is open and is armed from the close with its detector reset. Skipped under a debugger.
 *
 * **Threads.** The worker (`startup-trace`, one per window) never touches the app's storage: it samples and decides. All
 * file and binder work goes to one short-lived I/O thread ([io], `startup-trace-io`), so a disk that holds the main thread
 * cannot stop the samples that would show it.
 *
 * **What it records**: [StartupWindow]. **The files**, in `files/reports/`, format `dystopo-report 1` (`MainWatchdog.Dump`;
 * every line of a section is a `\tat ` line, stack or not; [StartupText.format]):
 * - `startup-<UTC stamp>-<pid>.txt`: a report, when [StartupWindow.reasons] is not empty; quota [Reports.KEEP_STARTUP],
 *   of its own.
 * - `trace-<UTC stamp>-<pid>.txt`: every start's trace, the newest [KEEP_TRACES], hidden from the list; Report the last start
 *   reads the newest.
 * - `.startup-progress-<start id>`: the in-progress file: rewritten every second from 1 s into a stall (before the first
 *   frame, from 1 s after the window opens). The normal end writes the final files, then deletes it. A leftover found at
 *   the next launch becomes a report ([StartupLeftover]).
 * - `.startup-history`: one line a start, the newest [StartupText.HISTORY_KEEP]; the last section of each report.
 *
 * Header keys beyond the common ones: `startId`, `pid`, `startKind`, `kindSource`, `outcome` (`finished`, `running`,
 * `ended`, `noFirstFrame`, `left`), `reasons` (comma-separated `stall`, `mapNotDrawn`, `firstFrame`, `notFinished`, or `-`),
 * `thresholdMs`, `firstFrameMs`, `longestStallMs`, `topFreezeMs`, `topFreezeMark`, `blockingFrame`, `activity`,
 * `processStartToAttachMs` (loading and field initialisers, not sampled), `importance`, `firstStart`, `sinceBootS`,
 * `ramAvailMb`, `ramTotalMb`, `lowMemory`, `thermal`, `batterySaver`, `heapMaxMb`, `heapUsedMb`, `gcCount`, `packs`,
 * `packBytes`, `startInfoReason`, `startInfoType`, `startInfoTimes`, `exitReason`, `exitReasonName`.
 */
object StartupTrace {
    const val TAG = "DystopoStartup"
    /** A scratch build flips this for the cost check (`tools/android-startup.py`); nothing else ever does. */
    const val ENABLED = true
    const val PREFIX = "startup-"
    const val TRACE_PREFIX = "trace-"
    const val PROGRESS_PREFIX = ".startup-progress-"
    const val HISTORY = ".startup-history"
    const val KEEP_TRACES = 3

    /** Debug routes override these (`POST /debug/startup/thresholds`). */
    @Volatile var thresholds = StartupThresholds()

    private class Active(val window: StartupWindow) {
        lateinit var thread: Thread
        val head = ConcurrentHashMap<String, String>()
        var framePosted = false // main thread only
        val progressQueued = AtomicBoolean(false)
    }

    @Volatile private var context: Context? = null
    @Volatile private var current: Active? = null
    @Volatile private var lastClosed: Active? = null
    private val mainThread: Thread by lazy { Looper.getMainLooper().thread }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val writeLock = Any()

    /** All the file and binder work: one thread, gone 2 s after its last job. */
    private val ioPool by lazy {
        ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "startup-trace-io").apply { isDaemon = true } }
    }

    private fun io(what: String, job: () -> Unit) {
        ioPool.execute { try { job() } catch (e: Throwable) { Log.w(TAG, "$what failed", e) } }
    }

    private fun reportsDir(): File? = context?.let { File(it.filesDir, MainWatchdog.REPORTS_DIR) }

    /** A window is open: [MainWatchdog] holds its dumps (read on the watchdog's thread). */
    val isOpen: Boolean get() = current?.window?.closed == false

    // ---------- the hooks, all on the main thread ----------

    /** `DystopoApp.attachBaseContext`: the process's window. */
    fun attach(base: Context) {
        if (!ENABLED || Debug.isDebuggerConnected()) return
        context = base
        val now = SystemClock.elapsedRealtime()
        val start = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(now).coerceAtMost(now)
        open(StartupWindow(newId(start), start, System.currentTimeMillis() - (now - start), StartupWindow.PROVISIONAL) { thresholds }, leftovers = true)
        mark("attachBaseContext")
    }

    /** The end of `DystopoApp.onCreate`. */
    fun appCreated() {
        mark("DystopoApp.onCreate done")
        current?.window?.appCreated(SystemClock.elapsedRealtime())
    }

    /** A named step begins; it lasts until the next mark. Only the main thread's steps count (a reload runs them off it too). */
    fun mark(name: String) {
        val a = current ?: return
        if (Thread.currentThread() === mainThread) a.window.mark(name, SystemClock.elapsedRealtime())
    }

    /** The top of `MainActivity.onCreate`. */
    fun activityCreated() {
        if (context == null || Debug.isDebuggerConnected()) return
        val now = SystemClock.elapsedRealtime()
        val a = current
        if (a != null && !a.window.closing) {
            if (a.window.activityCreated(now)) {
                mark("MainActivity.onCreate")
                return
            }
            a.window.requestClose(StartupWindow.BACKGROUND) // a process started in the background and brought forward later
            a.thread.interrupt()
        }
        open(StartupWindow(newId(now), now, System.currentTimeMillis(), StartupWindow.WARM) { thresholds }, leftovers = false)
        mark("MainActivity.onCreate")
    }

    /** `MainActivity.onStart`. */
    fun started() {
        if (context == null || Debug.isDebuggerConnected()) return
        val a = current
        if (a == null || a.window.closing) {
            val now = SystemClock.elapsedRealtime()
            open(StartupWindow(newId(now), now, System.currentTimeMillis(), StartupWindow.HOT) { thresholds }, leftovers = false)
        }
        mark("onStart")
        StartupRoutes.stallPoint("onStart")
    }

    /** `MainActivity.onResume`: the next frame callback runs before that frame's layout and draw, the message it posts after them. */
    fun resumed() {
        val a = current ?: return
        mark("onResume")
        if (a.framePosted) return
        a.framePosted = true
        runCatching {
            Choreographer.getInstance().postFrameCallback {
                mark("first frame's composition, layout and draw")
                main.post { firstFrame(a) }
            }
        }
    }

    /** `MainActivity.onStop`: nothing more to see; before the first frame, the tester left. */
    fun stopped() {
        val a = current ?: return
        if (a.window.closing) return
        a.window.requestClose(if (a.window.firstFrameSeen) StartupWindow.FINISHED else StartupWindow.LEFT)
        a.thread.interrupt()
    }

    private fun firstFrame(a: Active) {
        a.window.firstFrame(SystemClock.elapsedRealtime())
        a.thread.interrupt()
        StartupRoutes.stallPoint("afterFirstFrame")
    }

    /** `MapController`: the style's callback ran. */
    fun styleLoaded() {
        current?.window?.styleLoaded(SystemClock.elapsedRealtime())
    }

    /** `MapController.fullyLoaded` read true (after a frame). */
    fun fullyLoaded() {
        val w = current?.window ?: return
        if (!w.fullyLoadedSeen) w.fullyLoaded(SystemClock.elapsedRealtime())
    }

    // ---------- the worker ----------

    private fun newId(at: Long) = "${Process.myPid()}-$at"

    private fun open(w: StartupWindow, leftovers: Boolean) {
        val a = Active(w)
        a.thread = Thread({ runCatching { loop(a, leftovers) }.onFailure { Log.w(TAG, "the trace stopped", it); closed(a) } }, "startup-trace")
            .apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        current = a
        a.thread.start()
    }

    private fun closed(a: Active) {
        a.window.requestClose(StartupWindow.FINISHED)
        lastClosed = a
        if (current === a) current = null
        MainWatchdog.startupClosed()
    }

    private fun loop(a: Active, leftovers: Boolean) {
        val w = a.window
        val pid = Process.myPid()
        var lastCpuMs = -1L
        var frameHandled = false
        io("the header") { readHead(a) }
        if (leftovers) io("the leftovers") { processLeftovers() }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (Debug.isDebuggerConnected() && !w.closing) w.requestClose(StartupWindow.BACKGROUND)
            val step = w.step(now)
            if (step.post) main.post { w.answered(step.seq, SystemClock.elapsedRealtime()) }
            if (step.sample) {
                val stat = ThreadCpu.readThread(pid)
                val cpu = stat?.let { it.ticks * 1000 / ThreadCpu.clockTicks } ?: -1L
                val delta = if (lastCpuMs >= 0 && cpu >= 0) cpu - lastCpuMs else 0L
                lastCpuMs = cpu
                val stack = mainThread.stackTrace.map(StackTraceElement::toString)
                w.addSample(StartupWindow.classify(mainThread.state.name, stat?.state ?: '?', delta, stack.firstOrNull()), delta, stack)
            }
            if (!frameHandled && w.firstFrameSeen) {
                frameHandled = true
                val rt = Runtime.getRuntime()
                a.head["heapMaxMb"] = (rt.maxMemory() / MB).toString()
                a.head["heapUsedMb"] = ((rt.totalMemory() - rt.freeMemory()) / MB).toString()
                runCatching { Debug.getRuntimeStat("art.gc.gc-count") }.getOrNull()?.let { a.head["gcCount"] = it }
                if (Build.VERSION.SDK_INT >= 35) io("the start record") { readStartInfo(a) }
            }
            if (step.snapshot > 0) runCatching { snapshot(a, step.snapshot) }.onFailure { Log.w(TAG, "snapshot failed", it) }
            when (step.progress) {
                StartupWindow.Progress.WRITE -> if (a.progressQueued.compareAndSet(false, true)) io("the in-progress file") {
                    try { writeProgress(a) } finally { a.progressQueued.set(false) }
                }
                StartupWindow.Progress.DELETE -> io("the in-progress file") { reportsDir()?.let { progressFile(it, w.id).delete() } }
                StartupWindow.Progress.NONE -> {}
            }
            if (step.flush) io("the trace so far") { flush(a) }
            if (step.close) {
                io("the final trace") { finish(a) }
                closed(a)
                return
            }
            try {
                Thread.sleep(step.sleepMs)
            } catch (_: InterruptedException) {
                // a first frame, a close: step again now
            }
        }
    }

    /** Every thread, the tile counters and why the map is not idle: counts and reason names only, never a tile path. */
    private fun snapshot(a: Active, afterMs: Int) {
        val threads = Thread.getAllStackTraces().entries.sortedWith(compareBy({ it.key !== mainThread }, { it.key.name }))
            .map { (t, frames) -> "thread ${t.name} (${t.state})" to frames.map(StackTraceElement::toString).take(StartupText.MAX_FRAMES) }
        val app = context?.applicationContext as? DystopoApp
        val c = app?.tileCounters
        val counters = ArrayList<String>()
        if (c != null) {
            counters += "tiles served: dem ${c.dem.served.get()}, osm ${c.osm.served.get()}, contours ${c.contours.served.get()}, glyphs ${c.glyphs.served.get()}"
            counters += "tiles missing: ${c.dem.notFound.get() + c.osm.notFound.get() + c.contours.notFound.get()}, errors: ${c.dem.errors.get() + c.osm.errors.get() + c.contours.errors.get()}"
        }
        val map = app?.map
        val blockers = map?.idleBlockers(repair = false).orEmpty()
        if (map != null) counters += "map: frames ${map.framesRendered}, pending tiles ${map.pendingTiles()}, style failed ${if (map.styleError != null) "yes" else "no"}"
        a.window.addSnapshot(StartupWindow.Snapshot(afterMs, threads, counters, blockers))
    }

    // ---------- the I/O thread ----------

    private fun readHead(a: Active) {
        val ctx = context ?: return
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        a.head["importance"] = state.importance.toString()
        a.head["sinceBootS"] = (SystemClock.elapsedRealtime() / 1000).toString()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        a.head["ramAvailMb"] = (mem.availMem / MB).toString()
        a.head["ramTotalMb"] = (mem.totalMem / MB).toString()
        a.head["lowMemory"] = mem.lowMemory.toString()
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        a.head["batterySaver"] = power.isPowerSaveMode.toString()
        if (Build.VERSION.SDK_INT >= 29) a.head["thermal"] = StartupText.thermalName(power.currentThermalStatus)
        val roots = listOfNotNull(ctx.filesDir, ctx.getExternalFilesDir(null))
        val packs = PackSet.catalog(roots)
        a.head["packs"] = packs.size.toString()
        a.head["packBytes"] = packs.sumOf { p -> p.files.sumOf { it.length() } }.toString()
        val dir = reportsDir() ?: return
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        a.head["firstStart"] = StartupText.firstStart(readHistory(dir).lastOrNull(), BuildConfig.VERSION_NAME, info?.firstInstallTime ?: 0L, info?.lastUpdateTime ?: 0L)
    }

    @androidx.annotation.RequiresApi(35)
    private fun readStartInfo(a: Active) {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val w = a.window
        val records = am.getHistoricalProcessStartReasons(5)
        val pid = Process.myPid()
        val slack = if (w.processWindow) 5_000L else 2_000L
        val r = records.firstOrNull { rec ->
            val launch = rec.startupTimestamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH] ?: rec.startupTimestamps[ApplicationStartInfo.START_TIMESTAMP_FORK]
            rec.pid == pid && launch != null && kotlin.math.abs(launch / 1_000_000 - w.openAt) <= slack
        } ?: return
        a.head["startInfoReason"] = StartupText.startReasonName(r.reason)
        val type = StartupText.startTypeKind(r.startType)
        a.head["startInfoType"] = type ?: "unset"
        type?.let(w::startInfoKind)
        val names = linkedMapOf(
            ApplicationStartInfo.START_TIMESTAMP_LAUNCH to "launch", ApplicationStartInfo.START_TIMESTAMP_FORK to "fork",
            ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION to "bind", ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE to "application created",
            ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME to "first frame", ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN to "fully drawn",
        )
        a.head["startInfoTimes"] = r.startupTimestamps.entries.sortedBy { it.value }.joinToString(", ") { (k, v) ->
            "${names[k] ?: "stamp $k"} ${StartupText.seconds(v / 1_000_000 - w.openAt)}"
        }
    }

    private fun progressFile(dir: File, id: String) = File(dir, PROGRESS_PREFIX + id)

    private fun text(a: Active, outcome: String?, history: List<String>): String {
        val rec = a.window.record(SystemClock.elapsedRealtime(), outcome)
        return StartupText.format(rec, a.head.toMap(), Reports.system(), history)
    }

    private fun writeProgress(a: Active) {
        synchronized(writeLock) {
            val dir = reportsDir() ?: return
            if (a.window.closed) return
            val rec = a.window.record(SystemClock.elapsedRealtime(), StartupWindow.RUNNING)
            Reports.write(progressFile(dir, a.window.id), StartupText.format(rec, a.head.toMap(), Reports.system(), readHistory(dir) + StartupText.historyLine(rec, BuildConfig.VERSION_NAME)))
        }
    }

    /** The window so far as the kept files, marked `running`; the final write replaces them. Off the main thread. */
    private fun flush(a: Active) {
        synchronized(writeLock) {
            val dir = reportsDir() ?: return
            if (a.window.closed) return
            val rec = a.window.record(SystemClock.elapsedRealtime(), StartupWindow.RUNNING)
            val body = StartupText.format(rec, a.head.toMap(), Reports.system(), readHistory(dir) + StartupText.historyLine(rec, BuildConfig.VERSION_NAME))
            val name = StartupText.fileName(rec)
            if (rec.reasons.isNotEmpty()) Reports.write(File(dir, PREFIX + name), body)
            Reports.write(File(dir, TRACE_PREFIX + name), body)
            pruneTraces(dir)
            Reports.prune(dir)
        }
    }

    /** `Report a problem` and `GET /reports`: what the open window has so far, so a freeze a moment ago is on the list. */
    fun flushForRead() {
        current?.let(::flush)
    }

    private fun finish(a: Active) {
        synchronized(writeLock) {
            val dir = reportsDir() ?: return
            val rec = a.window.record(SystemClock.elapsedRealtime(), null)
            if (StartupLeftover.discard(rec.outcome, rec.activitySeen, a.head["importance"]?.toIntOrNull())) {
                progressFile(dir, rec.id).delete()
                return
            }
            val history = addHistory(dir, StartupText.historyLine(rec, BuildConfig.VERSION_NAME))
            val body = StartupText.format(rec, a.head.toMap(), Reports.system(), history)
            val name = StartupText.fileName(rec)
            if (rec.reasons.isNotEmpty()) {
                Reports.write(File(dir, PREFIX + name), body)
                Log.w(TAG, "start report ${PREFIX + name}: ${rec.reasons}")
            }
            Reports.write(File(dir, TRACE_PREFIX + name), body)
            progressFile(dir, rec.id).delete()
            pruneTraces(dir)
            Reports.prune(dir)
            main.post { ReportUi.changed() }
        }
    }

    private fun readHistory(dir: File): List<String> =
        runCatching { File(dir, HISTORY).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun addHistory(dir: File, line: String): List<String> {
        val lines = (readHistory(dir) + line).takeLast(StartupText.HISTORY_KEEP)
        Reports.write(File(dir, HISTORY), lines.joinToString("\n") + "\n")
        return lines
    }

    private fun pruneTraces(dir: File) {
        traces(dir).drop(KEEP_TRACES).forEach { it.delete() }
    }

    /** The kept traces, newest first. */
    fun traces(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(TRACE_PREFIX) && f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name }

    /** A start's in-progress file left by a process that died: a report, or nothing (see [StartupLeftover.verdict]). */
    private fun processLeftovers() {
        synchronized(writeLock) {
            val ctx = context ?: return
            val dir = reportsDir() ?: return
            val leftovers = dir.listFiles { f -> f.isFile && f.name.startsWith(PROGRESS_PREFIX) }.orEmpty()
            if (leftovers.isEmpty()) return
            val own = current?.window?.id
            val kept = (Reports.list(dir).filter { it.name.startsWith(PREFIX) } + traces(dir)).mapNotNull { Reports.read(it)?.dump?.header }
            val finals = kept.filter { it["outcome"] != StartupWindow.RUNNING }.mapNotNull { it["startId"] }.toSet()
            for (f in leftovers) {
                if (f.name.endsWith(".tmp")) { f.delete(); continue }
                if (f.name == PROGRESS_PREFIX + own) continue
                val dump = runCatching { MainWatchdog.Dump.parse(f.readText()) }.getOrNull()
                if (dump == null) { f.delete(); continue }
                when (StartupLeftover.verdict(dump.header, finals)) {
                    StartupLeftover.Verdict.REPORT -> {
                        val pid = dump.header["pid"]?.toIntOrNull() ?: 0
                        val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
                            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(ctx.packageName, pid, 1).firstOrNull { it.pid == pid }
                        }.getOrNull() else null
                        val line = StartupText.historyLine(dump, StartupWindow.ENDED)
                        val history = addHistory(dir, line)
                        val text = StartupLeftover.report(dump, exit?.reason, exit?.reason?.let(Diagnostics::reasonName), history)
                        val name = StartupText.fileName(dump.header)
                        Reports.write(File(dir, PREFIX + name), text)
                        Reports.write(File(dir, TRACE_PREFIX + name), text)
                        Log.w(TAG, "a start ended early: report ${PREFIX + name}")
                    }
                    StartupLeftover.Verdict.DISCARD, StartupLeftover.Verdict.DUPLICATE -> {}
                }
                f.delete()
            }
            pruneTraces(dir)
            Reports.prune(dir)
            main.post { ReportUi.changed() }
        }
    }

    // ---------- the routes ----------

    /** `GET /debug/startup`. */
    fun json(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        val a = current
        val shown = a ?: lastClosed
        val dir = reportsDir()
        return linkedMapOf(
            "enabled" to ENABLED,
            "debugger" to Debug.isDebuggerConnected(),
            "state" to when { a != null && !a.window.closed -> "open"; shown != null -> "closed"; else -> "none" },
            "open" to isOpen,
            "window" to shown?.window?.json(now),
            "head" to shown?.head?.toMap(),
            "thresholds" to thresholds.json(),
            "tuning" to StartupWindow.tuningJson(),
            "pendingStall" to StartupRoutes.pendingJson(),
            "history" to dir?.let(::readHistory),
            "traces" to dir?.let { traces(it).map(File::getName) },
            "progress" to dir?.listFiles { f -> f.name.startsWith(PROGRESS_PREFIX) }?.map(File::getName),
        )
    }

    private const val MB = 1024L * 1024L
}

/** What counts as slow: Android vitals' excessive start times (ADR 0044: one practice, no setting), and this wave's stall and map figures. */
data class StartupThresholds(val coldMs: Long = 5_000, val warmMs: Long = 2_000, val hotMs: Long = 1_500, val stallMs: Long = 2_000, val mapMs: Long = 15_000) {
    fun of(kind: String): Long = when (kind) {
        StartupWindow.WARM -> warmMs
        StartupWindow.HOT -> hotMs
        else -> coldMs
    }

    fun json(): Map<String, Any?> = linkedMapOf("coldMs" to coldMs, "warmMs" to warmMs, "hotMs" to hotMs, "stallMs" to stallMs, "mapMs" to mapMs)
}

/**
 * T228: one window's clock and record, pure so it is tested without a looper ([StartupTrace] drives it; [step] says what the
 * worker does now, as `FreezeDetector` does for the watchdog).
 *
 * **Marks** name each step of the launch; a step lasts until the next mark. **Before the first frame** the main looper
 * cannot answer a post (the launch's messages run first), so the worker samples the main stack every [SAMPLE_EVERY_MS]
 * with the thread's state and CPU time, attributed to the mark in force; that is reported by mark, not as a stall. **After
 * the first frame** one heartbeat at a time every [BEAT_EVERY_MS] finds stalls; while one is unanswered for
 * [STALL_SAMPLE_AFTER_MS] the worker samples the same way. A stall of [STALL_MIN_MS] and up keeps its true start and length,
 * its mark, its most common stack, that stack's share of the samples and its state. **A blank map with a responsive main
 * thread:** when the map has not been fully loaded [SNAPSHOT_AT] after the first frame, one snapshot each.
 */
class StartupWindow(val id: String, val openAt: Long, val epochAt: Long, kind: String, private val thresholds: () -> StartupThresholds = { StartupThresholds() }) {
    enum class Progress { NONE, WRITE, DELETE }

    class Step(val post: Boolean, val seq: Long, val sample: Boolean, val snapshot: Int, val progress: Progress, val flush: Boolean, val close: Boolean, val sleepMs: Long)

    class Mark(val name: String, val at: Long) {
        var samples = 0
        var cpuMs = 0L
        val states = LinkedHashMap<String, Int>()
        val stacks = LinkedHashMap<List<String>, Int>()
    }

    /** One line of the timeline: [ms] is null for a point after the first frame. */
    class MarkLine(val name: String, val atMs: Long, val ms: Long?, val samples: Int, val cpuMs: Long, val states: Map<String, Int>, val stack: List<String>, val top: Int)

    class StallLine(val atMs: Long, val ms: Long, val mark: String, val stack: List<String>, val top: Int, val samples: Int, val cpuMs: Long, val states: Map<String, Int>, val ended: Boolean)

    class Snapshot(val afterMs: Int, val threads: List<Pair<String, List<String>>>, val counters: List<String>, val blockers: List<String>)

    /** The window at one moment: all a file or a route says. */
    class Record(
        val id: String, val epochAt: Long, val kind: String, val kindSource: String, val processWindow: Boolean, val activitySeen: Boolean,
        val marks: List<MarkLine>, val firstFrameMs: Long?, val elapsedMs: Long, val stalls: List<StallLine>, val snapshots: List<Snapshot>,
        val outcome: String, val mapNotDrawn: Boolean, val thresholdMs: Long, val reasons: List<String>,
    ) {
        val longestStallMs: Long get() = stalls.maxOfOrNull { it.ms } ?: 0L

        /** The longest time the main thread was held: a stall, or a step before the first frame. Length, frame, mark. */
        fun topFreeze(): Triple<Long, String?, String>? {
            val stall = stalls.maxByOrNull { it.ms }
            val step = marks.filter { it.ms != null && it.samples > 0 }.maxByOrNull { it.ms!! }
            if (stall == null && step == null) return null
            return if (step == null || (stall != null && stall.ms >= step.ms!!)) Triple(stall!!.ms, MainWatchdog.blockingFrame(stall.stack), stall.mark)
            else Triple(step.ms!!, MainWatchdog.blockingFrame(step.stack), step.name)
        }
    }

    @Volatile var kind = kind
        private set
    private var kindSource = "app"
    val processWindow = kind == PROVISIONAL

    private val marks = ArrayList<Mark>()
    private var appCreatedAt = -1L
    private var activitySeen = kind != PROVISIONAL
    @Volatile private var firstFrameAt = -1L
    private var styleLoadedAt = -1L
    @Volatile private var fullyLoadedAt = -1L
    private var requested: String? = null
    private var outcome: String? = null
    @Volatile var closed = false
        private set
    private var closedAt = -1L

    private var seq = 0L
    private var outstanding = -1L
    private var sentAt = 0L
    private var nextBeatAt = 0L
    private var lastSampleAt = -SAMPLE_EVERY_MS
    private val stalls = ArrayList<StallLine>()
    private val stallStacks = LinkedHashMap<List<String>, Int>()
    private val stallStates = LinkedHashMap<String, Int>()
    private var stallSamples = 0
    private var stallCpuMs = 0L
    private val snapshots = ArrayList<Snapshot>()
    private var snapshotsDue = SNAPSHOT_AT.toMutableList()
    private var mapNotDrawn = false

    private var lastProgressAt = -1L
    private var progressExists = false
    private var progressCurrent = false
    private var flushed = false

    val firstFrameSeen: Boolean get() = firstFrameAt >= 0
    val fullyLoadedSeen: Boolean get() = fullyLoadedAt >= 0
    @get:Synchronized val closing: Boolean get() = requested != null || closed

    @Synchronized
    fun mark(name: String, at: Long) {
        if (closed || marks.size >= MAX_MARKS) return
        marks += Mark(name, at)
    }

    @Synchronized
    fun appCreated(at: Long) {
        appCreatedAt = at
    }

    /** `MainActivity.onCreate` at [at]: true when this window carries on (a cold start, or one already open); false: it was a background process's. */
    @Synchronized
    fun activityCreated(at: Long): Boolean {
        if (kind != PROVISIONAL) {
            activitySeen = true
            return true
        }
        if (appCreatedAt >= 0 && at - appCreatedAt <= COLD_WITHIN_MS) {
            kind = COLD
            activitySeen = true
            return true
        }
        return false
    }

    /** API 35 and up: the system's start type for this window. */
    @Synchronized
    fun startInfoKind(k: String) {
        kind = k
        kindSource = "startInfo"
    }

    @Synchronized
    fun firstFrame(at: Long) {
        if (firstFrameAt >= 0 || closed) return
        firstFrameAt = at
        nextBeatAt = 0L
        mark("first frame", at)
    }

    @Synchronized
    fun styleLoaded(at: Long) {
        if (styleLoadedAt >= 0 || closed) return
        styleLoadedAt = at
        mark("style loaded", at)
    }

    @Synchronized
    fun fullyLoaded(at: Long) {
        if (fullyLoadedAt >= 0 || closed) return
        fullyLoadedAt = at
        mark("map fully loaded", at)
    }

    /** The heartbeat [seq] ran on the main thread at [now]: a stall of [STALL_MIN_MS] and up is kept, with its true length. */
    @Synchronized
    fun answered(seq: Long, now: Long) {
        if (seq != outstanding) return
        val silent = now - sentAt
        if (silent >= STALL_MIN_MS && !closed) stalls += stallLine(silent, true)
        outstanding = -1L
        nextBeatAt = sentAt + BEAT_EVERY_MS
        clearStall()
        progressCurrent = false
    }

    private fun clearStall() {
        stallStacks.clear()
        stallStates.clear()
        stallSamples = 0
        stallCpuMs = 0L
    }

    private fun stallLine(silent: Long, ended: Boolean): StallLine {
        val top = stallStacks.maxByOrNull { it.value }
        val mark = marks.lastOrNull { it.at <= sentAt }?.name ?: "-"
        return StallLine(sentAt - openAt, silent, mark, top?.key.orEmpty(), top?.value ?: 0, stallSamples, stallCpuMs, LinkedHashMap(stallStates), ended)
    }

    /** One sample of the main thread: to the mark in force before the first frame, to the stall after it. */
    @Synchronized
    fun addSample(state: String, cpuDeltaMs: Long, stack: List<String>) {
        if (closed) return
        if (firstFrameAt < 0) {
            val m = marks.lastOrNull() ?: return
            m.samples++
            m.cpuMs += cpuDeltaMs
            m.states.merge(state, 1, Int::plus)
            if (stack in m.stacks || m.stacks.size < MAX_STACKS) m.stacks.merge(stack, 1, Int::plus)
        } else if (outstanding >= 0) {
            stallSamples++
            stallCpuMs += cpuDeltaMs
            stallStates.merge(state, 1, Int::plus)
            if (stack in stallStacks || stallStacks.size < MAX_STACKS) stallStacks.merge(stack, 1, Int::plus)
        }
    }

    @Synchronized
    fun addSnapshot(s: Snapshot) {
        if (!closed) snapshots += s
    }

    /** Asks the worker to close at its next step; [why] is the outcome, unless a stall's own close comes first. */
    @Synchronized
    fun requestClose(why: String) {
        if (requested == null && !closed) requested = why
    }

    private fun closeDue(now: Long): String? {
        requested?.let { return it }
        val stallRunning = outstanding >= 0 && now - sentAt >= STALL_SAMPLE_AFTER_MS
        if (kind == PROVISIONAL) {
            if (appCreatedAt >= 0 && now - appCreatedAt >= PROVISIONAL_MS) return BACKGROUND
            return if (now - openAt >= MAX_MS) NO_FIRST_FRAME else null
        }
        if (now - openAt >= MAX_MS) return if (firstFrameAt < 0) NO_FIRST_FRAME else FINISHED
        val closeAfter = if (kind == HOT) HOT_CLOSE_AFTER_MS else CLOSE_AFTER_MS
        return if (firstFrameAt >= 0 && now >= firstFrameAt + closeAfter && !stallRunning) FINISHED else null
    }

    @Synchronized
    fun step(now: Long): Step {
        if (closed) return Step(false, 0, false, 0, Progress.NONE, false, true, 0)
        closeDue(now)?.let { why ->
            if (outstanding >= 0 && now - sentAt >= STALL_MIN_MS) {
                stalls += stallLine(now - sentAt, false)
                clearStall()
            }
            outstanding = -1L
            if (firstFrameAt >= 0 && now - firstFrameAt >= thresholds().mapMs && fullyLoadedAt < 0) mapNotDrawn = true
            outcome = why
            closed = true
            closedAt = now
            return Step(false, 0, false, 0, Progress.NONE, false, true, 0)
        }
        var post = false
        var sample = false
        var stepSeq = 0L
        val frame = firstFrameAt
        if (frame < 0) {
            sample = now - lastSampleAt >= SAMPLE_EVERY_MS
        } else if (outstanding < 0) {
            if (now >= nextBeatAt) {
                outstanding = ++seq
                stepSeq = outstanding
                sentAt = now
                post = true
            }
        } else {
            sample = now - sentAt >= STALL_SAMPLE_AFTER_MS && now - lastSampleAt >= SAMPLE_EVERY_MS
        }
        if (sample) lastSampleAt = now

        var snapshot = 0
        if (frame >= 0) {
            if (fullyLoadedAt < 0 && now - frame >= thresholds().mapMs) mapNotDrawn = true
            val due = snapshotsDue.firstOrNull()
            if (due != null && now - frame >= due) {
                snapshotsDue.removeAt(0)
                if (fullyLoadedAt < 0) snapshot = due
            }
        }

        val flush = !flushed && frame >= 0 && now - frame >= FLUSH_AFTER_FRAME_MS
        if (flush) flushed = true

        val silent = if (outstanding >= 0) now - sentAt else 0L
        val running = if (frame < 0) now - openAt >= PROGRESS_AFTER_MS else silent >= PROGRESS_AFTER_MS
        val progress = when {
            running && (lastProgressAt < 0 || now - lastProgressAt >= PROGRESS_EVERY_MS) -> {
                lastProgressAt = now
                progressExists = true
                progressCurrent = true
                Progress.WRITE
            }
            running || !progressExists -> Progress.NONE
            reasons(now, RUNNING).isEmpty() -> {
                progressExists = false
                progressCurrent = false
                lastProgressAt = -1L
                Progress.DELETE
            }
            !progressCurrent || lastProgressAt < frame -> { // the stall ended, or the first frame came: one write says so
                lastProgressAt = now
                progressCurrent = true
                Progress.WRITE
            }
            else -> Progress.NONE
        }
        val sleep = when {
            frame < 0 || outstanding >= 0 -> SAMPLE_EVERY_MS
            else -> (nextBeatAt - now).coerceIn(1L, BEAT_EVERY_MS)
        }
        return Step(post, stepSeq, sample, snapshot, progress, flush, false, sleep)
    }

    /** Why this window earns a report, the first named first in the report's text; empty for a normal start. */
    private fun reasons(now: Long, outcome: String): List<String> {
        val t = thresholds()
        val out = ArrayList<String>()
        val longest = maxOf(stalls.maxOfOrNull { it.ms } ?: 0L, if (outstanding >= 0) now - sentAt else 0L)
        if (longest >= t.stallMs) out += REASON_STALL
        if (mapNotDrawn) out += REASON_MAP
        val threshold = t.of(kind)
        if (firstFrameAt >= 0) {
            if (firstFrameAt - openAt > threshold) out += REASON_FIRST_FRAME
        } else if (outcome == NO_FIRST_FRAME || outcome == ENDED) {
            out += REASON_NOT_FINISHED
        } else if (outcome == LEFT) {
            if (now - openAt > threshold) out += REASON_NOT_FINISHED
        } else if (outcome == RUNNING && now - openAt > threshold) {
            out += REASON_FIRST_FRAME // a report written while the window is open: past the limit already
        }
        return out
    }

    /** The window as of [now]; [asOutcome] overrides the outcome (a write while it is open). */
    @Synchronized
    fun record(now: Long, asOutcome: String?): Record {
        val at = if (closed && closedAt >= 0) closedAt else now
        val out = asOutcome ?: outcome ?: RUNNING
        val preFrameEnd = if (firstFrameAt >= 0) firstFrameAt else at
        val lines = marks.mapIndexed { i, m ->
            val top = m.stacks.maxByOrNull { it.value }
            val pre = m.at < preFrameEnd || (firstFrameAt < 0)
            val end = marks.getOrNull(i + 1)?.at ?: preFrameEnd
            MarkLine(m.name, m.at - openAt, if (pre) (minOf(end, preFrameEnd) - m.at).coerceAtLeast(0) else null, m.samples, m.cpuMs, LinkedHashMap(m.states), top?.key.orEmpty(), top?.value ?: 0)
        }
        val all = ArrayList(stalls)
        if (outstanding >= 0 && !closed && at - sentAt >= STALL_MIN_MS) all += stallLine(at - sentAt, false)
        return Record(
            id, epochAt, if (kind == PROVISIONAL) COLD else kind, kindSource, processWindow, activitySeen, lines,
            if (firstFrameAt >= 0) firstFrameAt - openAt else null, at - openAt, all, ArrayList(snapshots), out, mapNotDrawn,
            thresholds().of(kind), reasons(at, out),
        )
    }

    @Synchronized
    fun json(now: Long): Map<String, Any?> {
        val r = record(now, null)
        return linkedMapOf(
            "id" to id, "kind" to kind, "kindSource" to kindSource, "open" to !closed, "outcome" to (outcome ?: RUNNING), "elapsedMs" to r.elapsedMs,
            "firstFrameMs" to r.firstFrameMs, "styleLoadedMs" to styleLoadedAt.takeIf { it >= 0 }?.minus(openAt), "fullyLoadedMs" to fullyLoadedAt.takeIf { it >= 0 }?.minus(openAt),
            "activitySeen" to activitySeen, "longestStallMs" to r.longestStallMs, "mapNotDrawn" to mapNotDrawn, "thresholdMs" to r.thresholdMs, "reasons" to r.reasons,
            "marks" to r.marks.map { linkedMapOf("name" to it.name, "atMs" to it.atMs, "ms" to it.ms, "samples" to it.samples, "cpuMs" to it.cpuMs, "states" to it.states, "frame" to MainWatchdog.blockingFrame(it.stack)) },
            "stalls" to r.stalls.map { linkedMapOf("atMs" to it.atMs, "ms" to it.ms, "mark" to it.mark, "frame" to MainWatchdog.blockingFrame(it.stack), "top" to it.top, "samples" to it.samples, "cpuMs" to it.cpuMs, "states" to it.states, "ended" to it.ended) },
            "snapshots" to r.snapshots.map { linkedMapOf("afterMs" to it.afterMs, "threads" to it.threads.size, "counters" to it.counters, "blockers" to it.blockers) },
        )
    }

    companion object {
        const val PROVISIONAL = "provisional"
        const val COLD = "cold"
        const val WARM = "warm"
        const val HOT = "hot"

        const val FINISHED = "finished"
        const val RUNNING = "running"
        const val ENDED = "ended"
        const val NO_FIRST_FRAME = "noFirstFrame"
        const val LEFT = "left"
        const val BACKGROUND = "background"

        const val REASON_STALL = "stall"
        const val REASON_MAP = "mapNotDrawn"
        const val REASON_FIRST_FRAME = "firstFrame"
        const val REASON_NOT_FINISHED = "notFinished"

        const val SAMPLE_EVERY_MS = 100L
        const val BEAT_EVERY_MS = 100L
        const val STALL_SAMPLE_AFTER_MS = 250L
        const val STALL_MIN_MS = 500L
        const val CLOSE_AFTER_MS = 30_000L
        const val HOT_CLOSE_AFTER_MS = 5_000L
        const val MAX_MS = 120_000L
        const val COLD_WITHIN_MS = 1_000L
        const val PROVISIONAL_MS = 5_000L
        const val PROGRESS_AFTER_MS = 1_000L
        const val PROGRESS_EVERY_MS = 1_000L
        const val FLUSH_AFTER_FRAME_MS = 5_000L
        val SNAPSHOT_AT = listOf(5_000, 15_000)
        const val MAX_MARKS = 64
        const val MAX_STACKS = 100

        /** What the main thread was doing, from Java's state, `/proc`'s state and whether it used CPU since the last sample. */
        fun classify(javaState: String, procState: Char, cpuDeltaMs: Long, topFrame: String?): String = when {
            javaState == "BLOCKED" -> "lock"
            procState == 'D' -> "disk"
            cpuDeltaMs > 0 -> "running"
            procState == 'R' -> "starved"
            topFrame?.contains("nativePollOnce") == true -> "idle"
            else -> "waiting"
        }

        fun tuningJson(): Map<String, Any?> = linkedMapOf(
            "sampleEveryMs" to SAMPLE_EVERY_MS, "beatEveryMs" to BEAT_EVERY_MS, "stallSampleAfterMs" to STALL_SAMPLE_AFTER_MS, "stallMinMs" to STALL_MIN_MS,
            "closeAfterMs" to CLOSE_AFTER_MS, "hotCloseAfterMs" to HOT_CLOSE_AFTER_MS, "maxMs" to MAX_MS, "coldWithinMs" to COLD_WITHIN_MS, "snapshotAtMs" to SNAPSHOT_AT,
        )
    }
}

/**
 * T228: a window as a report file, pure ([StartupTraceTest]). Times carry their units between them ("1.2 s to 3.4 s"), since
 * `PrivacyCheck` reads adjacent bare decimals as a coordinate pair, and `Scrub.text` leaves such a line unchanged.
 */
object StartupText {
    const val HISTORY_KEEP = 20
    const val MAX_FRAMES = 64
    const val MAX_STALLS = 20
    const val MAX_STACKS = 5
    const val MIN_STACK_MS = 100L

    const val S_STALLS = "stalls"
    const val S_BLOCKED = "blocked"
    const val S_TIMELINE = "timeline"
    const val S_SNAPSHOT = "snapshot"
    const val S_HISTORY = "history"

    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

    /** `io.github...app.PackSet.open(Packs.kt:120)` -> `PackSet.open(Packs.kt:120)`. */
    fun brief(frame: String): String {
        val open = frame.indexOf('(')
        val method = if (open >= 0) frame.substring(0, open) else frame
        val tail = if (open >= 0) frame.substring(open) else ""
        return method.split('.').takeLast(2).joinToString(".") + tail
    }

    private fun states(states: Map<String, Int>): String =
        states.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }

    fun timelineLine(m: StartupWindow.MarkLine): String {
        val detail = if (m.samples >= 2) " (CPU ${seconds(m.cpuMs)}, ${states(m.states)})" else ""
        return if (m.ms != null) "${seconds(m.atMs)} to ${seconds(m.atMs + m.ms)} ${m.name}$detail" else "${seconds(m.atMs)} ${m.name}"
    }

    fun stallLine(s: StartupWindow.StallLine): String {
        val frame = MainWatchdog.blockingFrame(s.stack)?.let { ", at ${brief(it)}" } ?: ""
        val share = if (s.samples > 0) ", ${s.top} of ${s.samples} samples, ${states(s.states)}, CPU ${seconds(s.cpuMs)}" else ""
        return "${seconds(s.atMs)} to ${seconds(s.atMs + s.ms)} (${seconds(s.ms)}) under ${s.mark}$frame$share${if (s.ended) "" else ", still running"}"
    }

    fun blockedLine(m: StartupWindow.MarkLine): String {
        val frame = MainWatchdog.blockingFrame(m.stack)?.let { ", at ${brief(it)}" } ?: ""
        return "${seconds(m.ms ?: 0)} under ${m.name}$frame, ${m.top} of ${m.samples} samples, ${states(m.states)}, CPU ${seconds(m.cpuMs)}"
    }

    fun historyLine(r: StartupWindow.Record, version: String): String =
        "$version ${r.kind}, first frame ${r.firstFrameMs?.let(::seconds) ?: "none"}, longest stall ${seconds(r.longestStallMs)}, ${r.outcome}"

    /** A leftover's line, from what its file says. */
    fun historyLine(d: MainWatchdog.Dump, outcome: String): String {
        val h = d.header
        val frame = h["firstFrameMs"]?.toLongOrNull()?.let(::seconds) ?: "none"
        val stall = seconds(h["longestStallMs"]?.toLongOrNull() ?: 0L)
        return "${h["version"] ?: "?"} ${h["startKind"] ?: "?"}, first frame $frame, longest stall $stall, $outcome"
    }

    /** `install`, `update` or `no`: from the newest history line's version, and on an empty history from the package's own times. */
    fun firstStart(lastHistoryLine: String?, version: String, firstInstall: Long, lastUpdate: Long): String = when {
        lastHistoryLine != null -> if (lastHistoryLine.substringBefore(' ') == version) "no" else "update"
        firstInstall == lastUpdate -> "install"
        else -> "update"
    }

    fun fileName(r: StartupWindow.Record): String = name(r.epochAt, r.id)

    fun fileName(header: Map<String, String>): String = name(header["atMs"]?.toLongOrNull() ?: 0L, header["startId"] ?: "0-0")

    private fun name(epoch: Long, id: String) = Report.stamp(epoch) + "-" + id.substringBefore('-') + ".txt"

    fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "status $status"
    }

    /** `ApplicationStartInfo`'s `START_TYPE_*` (1 cold, 2 warm, 3 hot; 0 unset), by number so it is tested on any API. */
    fun startTypeKind(type: Int): String? = when (type) {
        1 -> StartupWindow.COLD
        2 -> StartupWindow.WARM
        3 -> StartupWindow.HOT
        else -> null
    }

    /** `ApplicationStartInfo`'s `START_REASON_*`. */
    fun startReasonName(reason: Int): String = when (reason) {
        0 -> "alarm"
        1 -> "backup"
        2 -> "boot complete"
        3 -> "broadcast"
        4 -> "content provider"
        5 -> "job"
        6 -> "launcher"
        7 -> "launcher in recents"
        8 -> "other"
        9 -> "push"
        10 -> "service"
        11 -> "start activity"
        else -> "reason $reason"
    }

    fun format(r: StartupWindow.Record, head: Map<String, String>, system: Map<String, String>, history: List<String>): String {
        val top = r.topFreeze()
        val load = r.marks.firstOrNull()?.atMs?.takeIf { r.processWindow }
        val header = LinkedHashMap<String, String>()
        header["kind"] = "startup"
        header["atMs"] = r.epochAt.toString()
        header["at"] = Report.utc(r.epochAt)
        header["startId"] = r.id
        header["pid"] = r.id.substringBefore('-')
        header["startKind"] = r.kind
        header["kindSource"] = r.kindSource
        header["outcome"] = r.outcome
        header["reasons"] = r.reasons.joinToString(",").ifEmpty { "-" }
        header["thresholdMs"] = r.thresholdMs.toString()
        r.firstFrameMs?.let { header["firstFrameMs"] = it.toString() }
        header["elapsedMs"] = r.elapsedMs.toString()
        header["longestStallMs"] = r.longestStallMs.toString()
        top?.let { (ms, _, mark) ->
            header["topFreezeMs"] = ms.toString()
            header["topFreezeMark"] = mark
        }
        header["blockingFrame"] = top?.second ?: "-"
        header["activity"] = r.activitySeen.toString()
        load?.let { header["processStartToAttachMs"] = it.toString() }
        header += head
        header += system

        val sections = ArrayList<Pair<String, List<String>>>()
        val stalls = r.stalls.sortedByDescending { it.ms }
        if (stalls.isNotEmpty()) sections += S_STALLS to stalls.take(MAX_STALLS).map(::stallLine) + (if (stalls.size > MAX_STALLS) listOf("and ${stalls.size - MAX_STALLS} shorter") else emptyList())
        val held = r.marks.filter { it.ms != null && it.samples > 0 && it.ms >= MIN_STACK_MS }.sortedByDescending { it.ms }
        if (held.isNotEmpty()) sections += S_BLOCKED to held.map(::blockedLine)
        val timeline = ArrayList<String>()
        if (load != null) timeline += "${seconds(0)} to ${seconds(load)} loading and field initialisers (not sampled)"
        r.marks.mapTo(timeline, ::timelineLine)
        sections += S_TIMELINE to timeline
        for (s in r.snapshots) {
            sections += "$S_SNAPSHOT ${s.afterMs / 1000} s after the first frame" to s.counters + ("not idle because: " + s.blockers.joinToString(", ").ifEmpty { "-" })
        }
        // The stacks, the longest hold first.
        val stacks = ArrayList<Triple<Long, String, List<String>>>()
        for (s in stalls.take(MAX_STACKS)) if (s.stack.isNotEmpty()) stacks += Triple(s.ms, "main in the stall at ${seconds(s.atMs)} (${s.top} of ${s.samples} samples)", s.stack)
        for (m in held.take(MAX_STACKS)) if (m.stack.isNotEmpty()) stacks += Triple(m.ms ?: 0, "main under ${m.name} (${m.top} of ${m.samples} samples)", m.stack)
        for ((_, title, frames) in stacks.sortedByDescending { it.first }) sections += title to frames.take(MAX_FRAMES)
        for (s in r.snapshots) for ((title, frames) in s.threads) sections += "$title, ${s.afterMs / 1000} s after the first frame" to frames
        sections += S_HISTORY to history.takeLast(HISTORY_KEEP)
        return MainWatchdog.Dump.format(header, sections)
    }
}

/**
 * T228: what becomes of an in-progress file found at the next launch, pure. The tester force-closing a frozen app is the
 * case that matters most. Its own read of `getHistoricalProcessExitReasons` names the reason, user-requested included
 * (`ExitImport.kindOf` drops that one, and its import races this one).
 */
object StartupLeftover {
    enum class Verdict { REPORT, DISCARD, DUPLICATE }

    /** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND`. */
    const val IMPORTANCE_FOREGROUND = 100

    /** A background process killed by the system is not a slow start: no activity mark and not foreground at attach. */
    fun discard(outcome: String, activity: Boolean, importance: Int?): Boolean =
        outcome == StartupWindow.BACKGROUND || (!activity && importance != IMPORTANCE_FOREGROUND)

    /** [finals]: the start ids already written as final files (a kill between the final write and the delete). */
    fun verdict(header: Map<String, String>, finals: Set<String>): Verdict = when {
        header["startId"] in finals -> Verdict.DUPLICATE
        discard(header["outcome"] ?: "", header["activity"] == "true", header["importance"]?.toIntOrNull()) -> Verdict.DISCARD
        else -> Verdict.REPORT
    }

    /** The report text a leftover becomes: what it had, its outcome `ended`, the reason the system gave and the history. */
    fun report(dump: MainWatchdog.Dump, exitReason: Int?, exitName: String?, history: List<String>): String {
        val header = LinkedHashMap(dump.header)
        header["outcome"] = StartupWindow.ENDED
        val reasons = header["reasons"].orEmpty().split(',').filter { it.isNotEmpty() && it != "-" }
        header["reasons"] = (reasons + StartupWindow.REASON_NOT_FINISHED).distinct().joinToString(",")
        exitReason?.let { header["exitReason"] = it.toString() }
        exitName?.let { header["exitReasonName"] = it }
        val sections = dump.sections.filter { it.first != StartupText.S_HISTORY } + (StartupText.S_HISTORY to history)
        return MainWatchdog.Dump.format(header, sections)
    }
}
