package io.github.jasondorjeshort.dystopo.app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T144: the UI-thread watchdog, in every build (release too; no setting: the research found one practice). A worker
 * thread posts a heartbeat to the main looper every [beatMs] (1 s); when one stays unanswered for [freezeMs] (3 s) it
 * writes one dump per freeze to `files/reports/`, rewrites that file every [FreezeDetector.rewriteEveryMs] (5 s) while
 * the freeze lasts and once more when a heartbeat runs again (T227), so a freeze's true length reaches the report even
 * when the process is killed in it. From the first silent second the main thread's stack is sampled every
 * [FreezeDetector.sampleEveryMs] (200 ms), so the dump names the frame the main thread spent the whole freeze in
 * rather than wherever it happened to be at 3 s. It ticks only between the activity's
 * `onResume` and `onPause` ([resumed], [paused]; the clock restarts on resume), never before the first frame after
 * the process started (the cold start's long first frame is not a freeze), never while a start's window is open (T228:
 * [StartupTrace] reports that time, and arms this from its close), and skips while a debugger is attached.
 * Both thresholds are overridable for tests (`POST /debug/watchdog?beatMs=&freezeMs=`, not persisted). The rule it
 * guards: the UI thread never takes a lock and never does disk or network I/O.
 *
 * **The dump file** (T145 reads this store; [Dump.parse] is the reader): `files/reports/freeze-<UTC yyyyMMdd-HHmmss-SSS>.txt`,
 * UTF-8 text, at most [KEEP] freeze files kept (the oldest deleted first):
 * ```
 * dystopo-report 1                      first line: the format and its version
 * kind: freeze                          header lines `key: value` up to the first empty line, in this order:
 * atMs: 1761400000000                   epoch ms of the freeze's first write (the file's stamp; a rewrite keeps it)
 * at: 2026-10-25T20:00:00.000Z          the same, ISO 8601 UTC
 * silentMs: 12004                       how long the heartbeat had gone unanswered at this write; the freeze's length if ended
 * ended: true                           T227: true once the heartbeat returned; false: still frozen at this write, so
 *                                       silentMs is a lower bound (the process died in the freeze, or was still in it).
 *                                       A file from before T227 has no such key and its silentMs is the 3 s of its one write
 * beatMs: 1000                          the heartbeat interval and the threshold in force
 * freezeMs: 3000
 * samples: 10                           main-stack samples taken during the whole silence so far
 * topStackShare: 9                      samples that had the most common stack
 * blockingFrame: io.github...debug.DebugRoutes.freezeUi(DebugRoutes.kt:70)   topmost app frame of that stack, or of the main
 *                                       stack at the dump when there was no sample; `-` when no app frame
 * version: 0.1.12-alpha                 versionName; then build (debug|release), sdk, device
 * build: debug
 * sdk: 35
 * device: sdk_gphone64_x86_64
 *
 * main (most common sampled stack)      sections: a line naming the thread and its state, then one frame per
 * \tat java.lang.Thread.sleep(Native Method)   line, tab then `at `, as Java prints them; an empty line ends it
 *
 * main (sampled 3 of 60)                T227: the histogram's next stacks, by count, [FreezeFile.STACKS_WRITTEN] in all
 * \tat ...
 *
 * thread main (TIMED_WAITING)           every thread of `Thread.getAllStackTraces()`, main first, as of the last
 * \tat ...                              write inside the freeze: the write after it ended keeps them (by then they
 * ```                                   no longer show what the main thread waited on)
 * A reader skips header keys and sections it does not know; a later version only adds them.
 */
object MainWatchdog {
    const val TAG = "DystopoWatchdog"
    const val REPORTS_DIR = "reports"
    const val PREFIX = "freeze-"
    const val KEEP = 5
    const val DEFAULT_BEAT_MS = 1_000L
    const val DEFAULT_FREEZE_MS = 3_000L
    const val APP_PACKAGE = "io.github.jasondorjeshort.dystopo"

    private val detector = FreezeDetector(DEFAULT_BEAT_MS, DEFAULT_FREEZE_MS)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var dir: File? = null
    private var file: FreezeFile? = null // the worker's alone
    @Volatile private var resumed = false
    @Volatile private var firstFrameSeen = false
    @Volatile private var thread: Thread? = null
    @Volatile var lastDump: Map<String, Any?>? = null
        private set

    val beatMs: Long get() = detector.beatMs
    val freezeMs: Long get() = detector.freezeMs

    /** Starts the worker (once per process); dumps go to `files/reports/`. */
    @Synchronized
    fun start(app: android.app.Application) {
        if (thread != null) return
        dir = File(app.filesDir, REPORTS_DIR).also { file = FreezeFile(it) }
        thread = Thread(::loop, "ui-watchdog").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }.also { it.start() }
    }

    /** The activity resumed (main thread): the clock restarts; the first frame after the process started arms it. */
    fun resumed() {
        detector.mainRan(SystemClock.elapsedRealtime())
        detector.reset()
        resumed = true
        // A frame callback runs before that frame's layout and draw; the message posted from it runs after them, so the
        // long first frame of a cold start (observed: 10 s inflating Compose on a just-booted emulator) is not counted.
        if (!firstFrameSeen) runCatching { Choreographer.getInstance().postFrameCallback { main.post { firstFrameSeen = true } } }
        thread?.interrupt() // wake the worker so the first heartbeat goes now, not after a long idle sleep
    }

    /** The activity paused (main thread): no heartbeats until it resumes. */
    fun paused() {
        resumed = false
        detector.mainRan(SystemClock.elapsedRealtime())
        detector.reset()
    }

    /** `POST /debug/watchdog?beatMs=&freezeMs=`: overrides the two thresholds for this process. */
    fun configure(beatMs: Long?, freezeMs: Long?) {
        beatMs?.let { require(it in 50..60_000) { "beatMs must be 50..60000" } }
        freezeMs?.let { require(it in 100..120_000) { "freezeMs must be 100..120000" } }
        detector.configure(beatMs ?: detector.beatMs, freezeMs ?: detector.freezeMs)
        thread?.interrupt()
    }

    /** T228: no dump while a start's window is open ([StartupTrace] reports that time); armed again from its close. */
    private fun armed(): Boolean = resumed && firstFrameSeen && !android.os.Debug.isDebuggerConnected() && !StartupTrace.isOpen

    /** T228: a start's window closed: the clock starts over, so one freeze gives one report and there is no gap. */
    fun startupClosed() {
        detector.reset()
        thread?.interrupt()
    }

    private fun loop() {
        val mainThread = Looper.getMainLooper().thread
        while (true) {
            val wait = try {
                val now = SystemClock.elapsedRealtime()
                val step = detector.step(now, armed())
                if (step.post) {
                    val seq = step.seq
                    main.post { detector.answered(seq, SystemClock.elapsedRealtime()) }
                }
                if (step.sample) detector.addSample(mainThread.stackTrace.map(StackTraceElement::toString))
                step.dump?.let { freeze -> runCatching { dump(freeze, mainThread) }.onFailure { Log.w(TAG, "freeze dump failed", it) } }
                step.sleepMs
            } catch (e: Throwable) {
                Log.w(TAG, "watchdog step failed", e)
                1_000L
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                // resumed or reconfigured: step again now
            }
        }
    }

    private fun dump(freeze: FreezeDetector.Freeze, mainThread: Thread) {
        val f = file ?: return
        val written = f.write(freeze, System.currentTimeMillis(), detector.beatMs, detector.freezeMs, linkedMapOf(
            "version" to BuildConfig.VERSION_NAME, "build" to if (BuildConfig.DEBUG) "debug" else "release",
            "sdk" to Build.VERSION.SDK_INT.toString(), "device" to Build.MODEL,
        )) {
            Thread.getAllStackTraces().entries.sortedWith(compareBy({ it.key !== mainThread }, { it.key.name }))
                .map { (t, frames) -> "thread ${t.name} (${t.state})" to frames.map(StackTraceElement::toString) }
        }
        lastDump = linkedMapOf("file" to written.name, "silentMs" to freeze.silentMs, "ended" to freeze.ended, "blockingFrame" to f.blockingFrame, "samples" to freeze.samples)
        Log.w(TAG, "UI thread silent for ${freeze.silentMs} ms; dump ${written.name}, ended ${freeze.ended}, blocking frame ${f.blockingFrame}")
    }

    /** The freeze dumps in [d], newest first. */
    fun list(d: File? = dir): List<File> =
        d?.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }?.sortedByDescending { it.name }.orEmpty()

    /** The topmost frame of [stack] in the app's own code, or null: see [isAppFrame]. */
    fun blockingFrame(stack: List<String>): String? = stack.firstOrNull(::isAppFrame)

    /**
     * Whether [frame] is the app's own code a reader wants named: in our package, not this watchdog's or the start trace's
     * own, and not lambda plumbing. T228: a release stack carries `Foo.$r8$lambda$...(Foo.kt:0)` and
     * `Foo$$ExternalSyntheticLambda3.run(R8$$SyntheticClass:0)` below the real frames (ADR 0058; D8 writes the same in a debug
     * build), the topmost app frames when the lambda is a reference to a platform method; they name no line.
     */
    fun isAppFrame(frame: String): Boolean = frame.startsWith(APP_PACKAGE) && OWN_FRAMES.none(frame::startsWith) && PLUMBING.none(frame::contains)

    private val OWN_FRAMES = listOf("$APP_PACKAGE.app.MainWatchdog", "$APP_PACKAGE.app.FreezeDetector", "$APP_PACKAGE.app.StartupTrace", "$APP_PACKAGE.app.StartupWindow")
    private val PLUMBING = listOf("\$\$ExternalSynthetic", "\$\$SyntheticClass", "\$r8\$", "-\$\$Nest\$")

    /** `GET /debug/watchdog`: thresholds, state, counters and the dumps on the device (newest first, with their headers). */
    fun json(): Map<String, Any?> = linkedMapOf(
        "armed" to armed(), "resumed" to resumed, "firstFrameSeen" to firstFrameSeen, "startupWindowOpen" to StartupTrace.isOpen,
        "debugger" to runCatching { android.os.Debug.isDebuggerConnected() }.getOrNull(),
        "beatMs" to detector.beatMs, "freezeMs" to detector.freezeMs, "sampleEveryMs" to detector.sampleEveryMs,
    ) + detector.json() + linkedMapOf(
        "dir" to dir?.path, "lastDump" to lastDump,
        "dumps" to list().map { f -> linkedMapOf<String, Any?>("file" to f.name, "bytes" to f.length()) + (runCatching { Dump.parse(f.readText()).header }.getOrNull() ?: emptyMap()) },
    )

    /** `GET /health` `watchdog`: the short form. */
    fun summary(): Map<String, Any?> = linkedMapOf("armed" to armed(), "freezes" to detector.freezes, "dumps" to list().size, "lastDump" to lastDump)

    private fun iso(epoch: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epoch))
    private fun fileStamp(epoch: Long) = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epoch))

    /**
     * T227: the one file of each freeze, on the worker thread only. Every write of a freeze goes to the file its first write
     * named, whole and atomically (`tmp` then rename), so a kill leaves the last complete one. The threads are read while
     * the freeze lasts; the write after it ended keeps the last ones read. Pure but for the folder ([MainWatchdogTest]).
     */
    class FreezeFile(private val dir: File, private val keep: Int = KEEP) {
        private class Open(val id: Long, val file: File, val atMs: Long, var threads: List<Pair<String, List<String>>>)
        private var open: Open? = null

        /** The `blockingFrame` of the last write. */
        var blockingFrame = "-"
            private set

        /** [threads] gives the sections of every thread, the main thread's first. */
        fun write(f: FreezeDetector.Freeze, epochMs: Long, beatMs: Long, freezeMs: Long, system: Map<String, String>, threads: () -> List<Pair<String, List<String>>>): File {
            val first = open?.id != f.id
            val o = if (first) Open(f.id, File(dir, PREFIX + fileStamp(epochMs) + ".txt"), epochMs, emptyList()).also { open = it } else open!!
            if (!f.ended) o.threads = threads()
            val top = f.stacks.firstOrNull()
            val stack = top?.first ?: o.threads.firstOrNull()?.second.orEmpty()
            blockingFrame = blockingFrame(stack) ?: "-"
            val header = linkedMapOf(
                "kind" to "freeze", "atMs" to o.atMs.toString(), "at" to iso(o.atMs), "silentMs" to f.silentMs.toString(), "ended" to f.ended.toString(),
                "beatMs" to beatMs.toString(), "freezeMs" to freezeMs.toString(),
                "samples" to f.samples.toString(), "topStackShare" to (top?.second ?: 0).toString(), "blockingFrame" to blockingFrame,
            ) + system
            val sections = ArrayList<Pair<String, List<String>>>()
            sections += "main (most common sampled stack)" to stack
            for ((frames, n) in f.stacks.drop(1).take(STACKS_WRITTEN - 1)) sections += "main (sampled $n of ${f.samples})" to frames
            sections += o.threads
            dir.mkdirs()
            val tmp = File(dir, o.file.name + ".tmp")
            tmp.writeText(Dump.format(header, sections))
            if (!tmp.renameTo(o.file)) { o.file.delete(); tmp.renameTo(o.file) }
            if (first) list(dir).drop(keep).forEach { it.delete() }
            if (f.ended) open = null
            return o.file
        }

        companion object {
            const val STACKS_WRITTEN = 5
        }
    }

    /** A parsed report file: its header (`key: value` lines) and its sections (a title line, then frames). Pure, tested. */
    class Dump(val format: String, val header: Map<String, String>, val sections: List<Pair<String, List<String>>>) {
        val kind: String? get() = header["kind"]
        val blockingFrame: String? get() = header["blockingFrame"]?.takeIf { it != "-" }

        companion object {
            const val MAGIC = "dystopo-report 1"

            fun format(header: Map<String, String>, sections: List<Pair<String, List<String>>>): String = buildString {
                append(MAGIC).append('\n')
                for ((k, v) in header) append(k).append(": ").append(v.replace('\n', ' ')).append('\n')
                for ((title, frames) in sections) {
                    append('\n').append(title).append('\n')
                    for (f in frames) append("\tat ").append(f).append('\n')
                }
            }

            fun parse(text: String): Dump {
                val lines = text.split('\n').map { it.trimEnd('\r') }
                require(lines.firstOrNull()?.startsWith("dystopo-report ") == true) { "not a report file" }
                val header = LinkedHashMap<String, String>()
                var i = 1
                while (i < lines.size && lines[i].isNotEmpty()) {
                    val c = lines[i].indexOf(": ")
                    if (c > 0) header[lines[i].substring(0, c)] = lines[i].substring(c + 2)
                    i++
                }
                val sections = ArrayList<Pair<String, List<String>>>()
                var title: String? = null
                var frames = ArrayList<String>()
                for (line in lines.drop(i)) {
                    when {
                        line.isEmpty() -> { title?.let { sections += it to frames }; title = null; frames = ArrayList() }
                        line.startsWith("\tat ") -> frames += line.removePrefix("\tat ")
                        title == null -> title = line
                    }
                }
                title?.let { sections += it to frames }
                return Dump(lines[0], header, sections)
            }
        }
    }
}

/**
 * T144: the watchdog's clock, pure so it is tested without a looper ([MainWatchdog] drives it). One heartbeat is out at a
 * time; [step] says what the worker does now (post a heartbeat, sample the main stack, write the dump) and how long it
 * sleeps; [answered] is the heartbeat running on the main thread, which ends the silence. T227: a freeze is one [Freeze.id]
 * and is written at [freezeMs], every [rewriteEveryMs] while it lasts and once more after the heartbeat returns; each
 * [Step.dump] is a copy of the samples, so the last write has them all whatever the main thread does meanwhile.
 */
class FreezeDetector(beatMs: Long, freezeMs: Long, val sampleAfterMs: Long = 1_000L, val sampleEveryMs: Long = 200L, val rewriteEveryMs: Long = 5_000L) {
    @Volatile var beatMs = beatMs
        private set
    @Volatile var freezeMs = freezeMs
        private set

    class Step(val post: Boolean, val seq: Long, val sample: Boolean, val dump: Freeze?, val sleepMs: Long)

    /** What one write of a freeze's dump says: [stacks] is the histogram with its counts, the most common first. */
    class Freeze(val id: Long, val silentMs: Long, val ended: Boolean, val stacks: List<Pair<List<String>, Int>>, val samples: Int)

    private var seq = 0L
    private var outstanding = -1L // seq of the heartbeat not yet answered, -1 none
    private var sentAt = 0L
    private var nextBeatAt = 0L
    private var lastSampleAt = 0L
    private var reported = false
    private var lastWriteAt = 0L
    private var closing: Freeze? = null // the write owed after the heartbeat returned; a reset does not lose it
    private val samples = LinkedHashMap<List<String>, Int>()
    private var sampleCount = 0

    var beats = 0L
        private set
    var freezes = 0L
        private set
    var longestSilentMs = 0L
        private set

    @Synchronized
    fun configure(beatMs: Long, freezeMs: Long) {
        this.beatMs = beatMs
        this.freezeMs = freezeMs
        reset()
    }

    /** Forget the heartbeat in flight (paused, resumed, reconfigured): the clock starts over. */
    @Synchronized
    fun reset() {
        outstanding = -1L
        nextBeatAt = 0L
        reported = false
        clearSamples()
    }

    private fun clearSamples() {
        samples.clear()
        sampleCount = 0
    }

    @Synchronized
    fun step(now: Long, armed: Boolean): Step {
        closing?.let {
            closing = null
            return Step(false, 0, false, it, 0)
        }
        if (!armed) {
            reset()
            return Step(false, 0, false, null, beatMs)
        }
        if (outstanding < 0) {
            if (now < nextBeatAt) return Step(false, 0, false, null, nextBeatAt - now)
            outstanding = ++seq
            sentAt = now
            beats++
            return Step(true, outstanding, false, null, minOf(beatMs, sampleAfterMs))
        }
        val silent = now - sentAt
        longestSilentMs = maxOf(longestSilentMs, silent)
        val sample = silent >= sampleAfterMs && now - lastSampleAt >= sampleEveryMs
        if (sample) lastSampleAt = now
        val write = silent >= freezeMs && (!reported || now - lastWriteAt >= rewriteEveryMs)
        if (write) {
            if (!reported) freezes++
            reported = true
            lastWriteAt = now
        }
        val untilSampling = (sampleAfterMs - silent).coerceAtLeast(0)
        return Step(false, 0, sample, if (write) freeze(silent, ended = false) else null, if (untilSampling > 0) minOf(untilSampling, sampleEveryMs) else sampleEveryMs)
    }

    /**
     * The heartbeat [seq] ran on the main thread at [now]: the next one goes one interval after it was sent. A freeze
     * already written owes its last write, with its true length and every sample.
     */
    @Synchronized
    fun answered(seq: Long, now: Long) {
        if (seq != outstanding) return
        val silent = now - sentAt
        longestSilentMs = maxOf(longestSilentMs, silent)
        if (reported) closing = freeze(silent, ended = true)
        outstanding = -1L
        nextBeatAt = sentAt + beatMs
        reported = false
        clearSamples()
    }

    /**
     * The main thread ran something other than the heartbeat at [now] (`onPause`, `onResume`, which then reset the clock):
     * the silence is over, even if the heartbeat lost its place in the queue.
     */
    @Synchronized
    fun mainRan(now: Long) {
        if (outstanding >= 0) answered(outstanding, now)
    }

    /** One more main stack of this silence. A sample that lost the race with the heartbeat is not part of any freeze. */
    @Synchronized
    fun addSample(stack: List<String>) {
        if (outstanding < 0) return
        if (stack in samples || samples.size < MAX_STACKS) samples.merge(stack, 1, Int::plus)
        sampleCount++
    }

    /** The most common sampled main stack, how many samples had it, and how many were taken. */
    @Synchronized
    fun topStack(): Triple<List<String>?, Int, Int> {
        val top = samples.maxByOrNull { it.value }
        return Triple(top?.key, top?.value ?: 0, sampleCount)
    }

    private fun freeze(silent: Long, ended: Boolean) =
        Freeze(outstanding, silent, ended, samples.entries.sortedByDescending { it.value }.map { it.key to it.value }, sampleCount)

    @Synchronized
    fun silentMs(now: Long): Long = if (outstanding < 0) 0L else now - sentAt

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf("beats" to beats, "freezes" to freezes, "longestSilentMs" to longestSilentMs, "beatOutstanding" to (outstanding >= 0))

    private companion object {
        /** Distinct stacks kept in one silence (a stack past it still counts in the total): a long busy freeze stays small. */
        const val MAX_STACKS = 100
    }
}
