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
 * writes one dump per freeze to `files/reports/` and rearms when a heartbeat runs again. From the first silent second
 * the main thread's stack is sampled every [FreezeDetector.sampleEveryMs] (200 ms), so the dump names the frame the
 * main thread spent the freeze in rather than wherever it happened to be at 3 s. It ticks only between the activity's
 * `onResume` and `onPause` ([resumed], [paused]; the clock restarts on resume), never before the first frame after
 * the process started (the cold start's long first frame is not a freeze), and skips while a debugger is attached.
 * Both thresholds are overridable for tests (`POST /debug/watchdog?beatMs=&freezeMs=`, not persisted). The rule it
 * guards: the UI thread never takes a lock and never does disk or network I/O.
 *
 * **The dump file** (T145 reads this store; [Dump.parse] is the reader): `files/reports/freeze-<UTC yyyyMMdd-HHmmss-SSS>.txt`,
 * UTF-8 text, at most [KEEP] freeze files kept (the oldest deleted first):
 * ```
 * dystopo-report 1                      first line: the format and its version
 * kind: freeze                          header lines `key: value` up to the first empty line, in this order:
 * atMs: 1761400000000                   epoch ms when the dump was written
 * at: 2026-10-25T20:00:00.000Z          the same, ISO 8601 UTC
 * silentMs: 3012                        how long the heartbeat had gone unanswered then
 * beatMs: 1000                          the heartbeat interval and the threshold in force
 * freezeMs: 3000
 * samples: 10                           main-stack samples taken during the silence
 * topStackShare: 9                      samples that had the most common stack
 * blockingFrame: io.github...Routes.freezeUi(Routes.kt:120)   topmost app frame of that stack, or of the main
 *                                       stack at the dump when there was no sample; `-` when no app frame
 * version: 0.1.12-alpha                 versionName; then build (debug|release), sdk, device
 * build: debug
 * sdk: 35
 * device: sdk_gphone64_x86_64
 *
 * main (most common sampled stack):     sections: a line naming the thread and its state, then one frame per
 * \tat java.lang.Thread.sleep(Native Method)   line, tab then `at `, as Java prints them; an empty line ends it
 *
 * thread main (TIMED_WAITING)           every thread of `Thread.getAllStackTraces()`, main first
 * \tat ...
 * ```
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
        dir = File(app.filesDir, REPORTS_DIR)
        thread = Thread(::loop, "ui-watchdog").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }.also { it.start() }
    }

    /** The activity resumed (main thread): the clock restarts; the first frame after the process started arms it. */
    fun resumed() {
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
        detector.reset()
    }

    /** `POST /debug/watchdog?beatMs=&freezeMs=`: overrides the two thresholds for this process. */
    fun configure(beatMs: Long?, freezeMs: Long?) {
        beatMs?.let { require(it in 50..60_000) { "beatMs must be 50..60000" } }
        freezeMs?.let { require(it in 100..120_000) { "freezeMs must be 100..120000" } }
        detector.configure(beatMs ?: detector.beatMs, freezeMs ?: detector.freezeMs)
        thread?.interrupt()
    }

    private fun armed(): Boolean = resumed && firstFrameSeen && !android.os.Debug.isDebuggerConnected()

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
                if (step.dump) runCatching { dump(now, mainThread) }.onFailure { Log.w(TAG, "freeze dump failed", it) }
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

    private fun dump(now: Long, mainThread: Thread) {
        val d = dir ?: return
        val silent = detector.silentMs(now)
        val all = Thread.getAllStackTraces()
        val mainNow = all[mainThread]?.map(StackTraceElement::toString) ?: mainThread.stackTrace.map(StackTraceElement::toString)
        val (top, share, samples) = detector.topStack()
        val stack = top ?: mainNow
        val epoch = System.currentTimeMillis()
        val header = linkedMapOf(
            "kind" to "freeze", "atMs" to epoch.toString(), "at" to iso(epoch), "silentMs" to silent.toString(),
            "beatMs" to detector.beatMs.toString(), "freezeMs" to detector.freezeMs.toString(),
            "samples" to samples.toString(), "topStackShare" to share.toString(), "blockingFrame" to (blockingFrame(stack) ?: "-"),
            "version" to BuildConfig.VERSION_NAME, "build" to if (BuildConfig.DEBUG) "debug" else "release",
            "sdk" to Build.VERSION.SDK_INT.toString(), "device" to Build.MODEL,
        )
        val threads = ArrayList<Pair<String, List<String>>>()
        threads += "main (most common sampled stack)" to stack
        val ordered = all.entries.sortedWith(compareBy({ it.key !== mainThread }, { it.key.name }))
        for ((t, frames) in ordered) threads += "thread ${t.name} (${t.state})" to frames.map(StackTraceElement::toString)
        val text = Dump.format(header, threads)
        d.mkdirs()
        val file = File(d, PREFIX + fileStamp(epoch) + ".txt")
        val tmp = File(d, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        prune(d)
        lastDump = linkedMapOf("file" to file.name, "silentMs" to silent, "blockingFrame" to header["blockingFrame"], "samples" to samples)
        Log.w(TAG, "UI thread unanswered for $silent ms; dump ${file.name}, blocking frame ${header["blockingFrame"]}")
    }

    private fun prune(d: File) {
        val dumps = list(d)
        dumps.drop(KEEP).forEach { it.delete() }
    }

    /** The freeze dumps in [d], newest first. */
    fun list(d: File? = dir): List<File> =
        d?.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }?.sortedByDescending { it.name }.orEmpty()

    /** The topmost frame of [stack] in the app's own package (the watchdog's own frames excepted), or null. */
    fun blockingFrame(stack: List<String>): String? = stack.firstOrNull { it.startsWith(APP_PACKAGE) && !it.startsWith(MainWatchdog::class.java.name) && !it.startsWith(FreezeDetector::class.java.name) }

    /** `GET /debug/watchdog`: thresholds, state, counters and the dumps on the device (newest first, with their headers). */
    fun json(): Map<String, Any?> = linkedMapOf(
        "armed" to armed(), "resumed" to resumed, "firstFrameSeen" to firstFrameSeen,
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
 * sleeps; [answered] is the heartbeat running on the main thread, which rearms the report. A freeze is reported once,
 * however long it lasts.
 */
class FreezeDetector(beatMs: Long, freezeMs: Long, val sampleAfterMs: Long = 1_000L, val sampleEveryMs: Long = 200L) {
    @Volatile var beatMs = beatMs
        private set
    @Volatile var freezeMs = freezeMs
        private set

    class Step(val post: Boolean, val seq: Long, val sample: Boolean, val dump: Boolean, val sleepMs: Long)

    private var seq = 0L
    private var outstanding = -1L // seq of the heartbeat not yet answered, -1 none
    private var sentAt = 0L
    private var nextBeatAt = 0L
    private var lastSampleAt = 0L
    private var reported = false
    private val samples = HashMap<List<String>, Int>()
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
        if (!armed) {
            reset()
            return Step(false, 0, false, false, beatMs)
        }
        if (outstanding < 0) {
            if (now < nextBeatAt) return Step(false, 0, false, false, nextBeatAt - now)
            outstanding = ++seq
            sentAt = now
            beats++
            return Step(true, outstanding, false, false, minOf(beatMs, sampleAfterMs))
        }
        val silent = now - sentAt
        longestSilentMs = maxOf(longestSilentMs, silent)
        val sample = silent >= sampleAfterMs && now - lastSampleAt >= sampleEveryMs
        if (sample) lastSampleAt = now
        val dump = !reported && silent >= freezeMs
        if (dump) {
            reported = true
            freezes++
        }
        val untilSampling = (sampleAfterMs - silent).coerceAtLeast(0)
        return Step(false, 0, sample, dump, if (untilSampling > 0) minOf(untilSampling, sampleEveryMs) else sampleEveryMs)
    }

    /** The heartbeat [seq] ran on the main thread at [now]: the next one goes one interval after it was sent. */
    @Synchronized
    fun answered(seq: Long, now: Long) {
        if (seq != outstanding) return
        outstanding = -1L
        nextBeatAt = sentAt + beatMs
        reported = false
        clearSamples()
    }

    @Synchronized
    fun addSample(stack: List<String>) {
        samples.merge(stack, 1, Int::plus)
        sampleCount++
    }

    /** The most common sampled main stack, how many samples had it, and how many were taken. */
    @Synchronized
    fun topStack(): Triple<List<String>?, Int, Int> {
        val top = samples.maxByOrNull { it.value }
        return Triple(top?.key, top?.value ?: 0, sampleCount)
    }

    @Synchronized
    fun silentMs(now: Long): Long = if (outstanding < 0) 0L else now - sentAt

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf("beats" to beats, "freezes" to freezes, "longestSilentMs" to longestSilentMs, "beatOutstanding" to (outstanding >= 0))
}
