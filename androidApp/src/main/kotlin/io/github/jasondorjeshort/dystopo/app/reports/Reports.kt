package io.github.jasondorjeshort.dystopo.app.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.io.File

/**
 * T145: the problem reports kept on the phone, in `files/reports/` (the store T144's watchdog writes its freeze dumps
 * to): what Settings › About and advanced › Report a problem lists ([ReportRow]) and `GET /reports` returns. Nothing
 * leaves the phone until the tester taps Open GitHub issue, and then only through their browser, under their account.
 *
 * Three writers, one format ([Report]): the watchdog's `freeze-` files; [CrashCapture]'s `crash-` files, written by the
 * uncaught-exception handler before the default handler runs; and [ExitImport]'s `exit-` files, one per
 * `ApplicationExitInfo` record (API 30 and up) newer than the last one seen: a crash ([ApplicationExitInfo.REASON_CRASH],
 * kept only when no `crash-` file was written for it), a native crash, an ANR with its thread dump
 * (`getTraceInputStream`), and a kill for low memory where the device reports one. Read once at launch on a worker
 * thread ([start]); the newest seen record's time is kept in `files/reports/.exits-seen`. The newest [KEEP] reports of
 * every kind together are kept, the oldest deleted first ([prune]). No setting: the crash-report survey found one
 * practice (opt-in per report).
 */
object Reports {
    const val TAG = "DystopoReports"
    const val KEEP = 5
    private const val SEEN = ".exits-seen"
    val PREFIXES = listOf(MainWatchdog.PREFIX, CrashCapture.PREFIX, ExitImport.PREFIX)

    @Volatile private var dir: File? = null
    @Volatile var lastImport: Map<String, Any?>? = null
        private set

    /** What the read at launch found (`GET /reports` `launchImport`). */
    @Volatile var launchImport: Map<String, Any?>? = null
        private set

    /** At process start (`DystopoApp.onCreate`): the crash handler at once, the exit records on a worker. */
    fun start(app: DystopoApp) {
        val d = File(app.filesDir, MainWatchdog.REPORTS_DIR)
        dir = d
        CrashCapture.install(d)
        Thread({ runCatching { launchImport = importExits(app) }.onFailure { Log.w(TAG, "exit import failed", it) } }, "reports-import").apply { isDaemon = true }.start()
    }

    fun dir(context: Context = DystopoApp.instance): File = dir ?: File(context.filesDir, MainWatchdog.REPORTS_DIR).also { dir = it }

    /** Reads the exit records not seen yet (idempotent; `GET /reports` calls it too, so a route never races the launch). */
    @Synchronized
    fun importExits(context: Context): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return mapOf("supported" to false).also { lastImport = it }
        val d = dir(context)
        val seenFile = File(d, SEEN)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, 16).filter { it.timestamp > seen }
        val crashTimes = list(d).filter { it.name.startsWith(CrashCapture.PREFIX) }.mapNotNull { atMs(it) }
        val written = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (e in records.sortedBy { it.timestamp }) {
            val kind = ExitImport.kindOf(e.reason)
            if (kind == null) { skipped += "${e.reason}"; continue }
            if (kind == "crash" && ExitImport.hasOwnCrash(e.timestamp, crashTimes)) { skipped += "crash (own report)"; continue }
            val trace = if (e.reason == ApplicationExitInfo.REASON_ANR) runCatching { e.traceInputStream?.use { s -> ExitImport.readCapped(s) } }.getOrNull() else null
            val text = ExitImport.format(kind, e.reason, e.timestamp, e.pid, e.description, e.pss, e.rss, trace, system())
            d.mkdirs()
            val f = File(d, "${ExitImport.PREFIX}${Report.stamp(e.timestamp)}-${e.pid}.txt")
            write(f, text)
            written += f.name
        }
        records.maxOfOrNull { it.timestamp }?.let { write(seenFile, it.toString()) }
        prune(d)
        return linkedMapOf<String, Any?>("supported" to true, "records" to records.size, "written" to written, "skipped" to skipped, "seenBefore" to seen)
            .also { lastImport = it }
    }

    /** This build and phone, the header lines every report carries. */
    fun system(): Map<String, String> = linkedMapOf(
        "version" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE.toString(),
        "build" to if (BuildConfig.DEBUG) "debug" else "release", "sdk" to Build.VERSION.SDK_INT.toString(),
        "android" to (Build.VERSION.RELEASE ?: ""), "device" to deviceName(),
    )

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model else "${maker.replaceFirstChar { it.uppercase() }} $model"
    }

    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Every report file in [d] (any writer's), newest first by the time in its header. */
    fun list(d: File = dir()): List<File> =
        d.listFiles { f -> f.isFile && f.name.endsWith(".txt") && PREFIXES.any { f.name.startsWith(it) } }.orEmpty()
            .sortedByDescending { atMs(it) ?: it.lastModified() }

    private fun atMs(f: File): Long? = runCatching {
        f.bufferedReader().useLines { lines -> lines.take(8).firstOrNull { it.startsWith("atMs: ") }?.removePrefix("atMs: ")?.trim()?.toLong() }
    }.getOrNull()

    /** Keeps the newest [KEEP] reports of every kind together. */
    fun prune(d: File = dir()) {
        list(d).drop(KEEP).forEach { it.delete() }
    }

    fun read(f: File): Report? = runCatching { Report(f.name.removeSuffix(".txt"), MainWatchdog.Dump.parse(f.readText())) }.getOrNull()

    /** The kept reports, newest first (unreadable files left out). */
    fun all(): List<Report> = list().mapNotNull(::read)

    fun get(id: String): Report? = list().firstOrNull { it.name == "$id.txt" }?.let(::read)

    fun delete(id: String): Boolean = list().firstOrNull { it.name == "$id.txt" }?.delete() == true

    /**
     * The names this phone knows a report could carry, for [Scrub.Known]: its packs, its offline areas (names and
     * packs), the search box's text and the selected result. Off the UI thread (lists the pack folders).
     */
    fun known(app: DystopoApp = DystopoApp.instance): Scrub.Known {
        val packs = runCatching { io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(app).catalog().map { it.name } }.getOrDefault(emptyList())
        val areas = runCatching { io.github.jasondorjeshort.dystopo.app.areas.Areas.all().flatMap { listOfNotNull(it.name, it.pack) } }.getOrDefault(emptyList())
        val searches = runCatching {
            val s = io.github.jasondorjeshort.dystopo.app.search.SearchUi
            listOfNotNull(s.query.takeIf { it.isNotBlank() }, s.selected?.hit?.entry?.name)
        }.getOrDefault(emptyList())
        val roots = listOfNotNull(app.filesDir?.path, app.cacheDir?.path, app.getExternalFilesDir(null)?.path)
        return Scrub.Known(packs = packs, areas = areas, searches = searches, roots = roots)
    }
}

/**
 * T145: the uncaught-exception handler. Writes `crash-<UTC stamp>.txt` ([format]) synchronously, then hands the
 * exception to the handler that was installed before it (the platform's, which kills the process and records
 * `REASON_CRASH`). Installed once per process ([Reports.start]); a failure to write never stops the default handler.
 */
object CrashCapture {
    const val PREFIX = "crash-"
    const val MAX_FRAMES = 64
    /** Blocked threads kept in a crash file besides the crashing and main threads (a stalled render can block dozens). */
    const val MAX_BLOCKED = 10

    @Volatile private var installed = false

    @Synchronized
    fun install(dir: File) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val now = System.currentTimeMillis()
                val main = android.os.Looper.getMainLooper().thread
                val others = if (t === main) emptyMap() else mapOf(main to main.stackTrace)
                val blocked = Thread.getAllStackTraces().filter { (th, _) -> th !== t && th !== main && th.state == Thread.State.BLOCKED }.entries.take(MAX_BLOCKED).associate { it.key to it.value }
                Reports.write(File(dir, "$PREFIX${Report.stamp(now)}.txt"), format(now, t.name, e, Reports.system(), others + blocked))
                Reports.prune(dir)
            } catch (_: Throwable) {
                // the report is best effort; the platform's handler must still run
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The crash file: the header, the crashing thread's stack and its causes, then [others] (the main thread, blocked threads). Pure. */
    fun format(atMs: Long, thread: String, e: Throwable, system: Map<String, String>, others: Map<Thread, Array<StackTraceElement>> = emptyMap()): String {
        val frames = e.stackTrace.map(StackTraceElement::toString)
        val all = generateSequence(e) { it.cause }.take(8).toList()
        val appFrame = all.flatMap { it.stackTrace.map(StackTraceElement::toString) }.firstOrNull { it.startsWith(MainWatchdog.APP_PACKAGE) && !it.startsWith(CrashCapture::class.java.name) }
        val header = linkedMapOf(
            "kind" to "crash", "atMs" to atMs.toString(), "at" to Report.utc(atMs), "thread" to thread,
            "exception" to describe(e), "appFrame" to (appFrame ?: "-"),
        ) + system
        val sections = ArrayList<Pair<String, List<String>>>()
        sections += "thread $thread (crashing): ${describe(e)}" to frames.take(MAX_FRAMES)
        for (c in all.drop(1)) sections += "caused by: ${describe(c)}" to c.stackTrace.map(StackTraceElement::toString).take(MAX_FRAMES)
        for ((t, st) in others) sections += "thread ${t.name} (${t.state})" to st.map(StackTraceElement::toString).take(MAX_FRAMES)
        return MainWatchdog.Dump.format(header, sections)
    }

    private fun describe(e: Throwable): String = e.javaClass.name + (e.message?.let { ": " + it.replace('\n', ' ').take(500) } ?: "")
}

/**
 * T145: an `ApplicationExitInfo` record as a report file, pure apart from the stream it is given. ANR thread dumps are
 * the system's text format (`"name" prio=5 tid=1 Blocked`, then `  at frame` and `  - waiting to lock ... held by
 * thread N` lines); each thread becomes a section whose title is its first line plus its lock lines, native frames
 * dropped. A native crash's trace stream is a binary tombstone and is not read.
 */
object ExitImport {
    const val PREFIX = "exit-"
    const val MAX_TRACE_BYTES = 1 shl 20
    const val MAX_FRAMES = 64

    /** `REASON_*` values (the constants' numbers, so this compiles and is tested on any API). */
    fun kindOf(reason: Int): String? = when (reason) {
        3 -> "low-memory"
        4 -> "crash"
        5 -> "native-crash"
        6 -> "anr"
        else -> null
    }

    /** Whether the handler already wrote a crash file for the record at [exitAt] (written up to 15 s before the process died). */
    fun hasOwnCrash(exitAt: Long, crashTimes: List<Long>): Boolean = crashTimes.any { it in (exitAt - 15_000)..(exitAt + 2_000) }

    fun readCapped(s: java.io.InputStream): String {
        val bytes = s.readNBytesCompat(MAX_TRACE_BYTES)
        return String(bytes, Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun format(kind: String, reason: Int, atMs: Long, pid: Int, description: String?, pssKb: Long, rssKb: Long, trace: String?, system: Map<String, String>): String {
        val sections = trace?.let(::threads).orEmpty()
        val main = sections.firstOrNull { it.first.startsWith("\"main\"") }
        val appFrame = main?.second?.firstOrNull { it.startsWith(MainWatchdog.APP_PACKAGE) }
        val header = linkedMapOf(
            "kind" to kind, "atMs" to atMs.toString(), "at" to Report.utc(atMs),
            "reason" to reason.toString(), "reasonName" to io.github.jasondorjeshort.dystopo.app.settings.Diagnostics.reasonName(reason),
            "description" to (description ?: ""), "pid" to pid.toString(), "pssKb" to pssKb.toString(), "rssKb" to rssKb.toString(),
        ) + (if (kind == "anr") mapOf("appFrame" to (appFrame ?: "-")) else emptyMap()) + system
        val ordered = sections.sortedBy { if (it.first.startsWith("\"main\"")) 0 else 1 }
        return MainWatchdog.Dump.format(header, ordered)
    }

    /** The Java threads of an ANR dump: (first line plus lock lines, frames). Pure. */
    fun threads(trace: String): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        var title: String? = null
        var locks = ArrayList<String>()
        var frames = ArrayList<String>()
        fun flush() {
            title?.let { out += (if (locks.isEmpty()) it else "$it (${locks.joinToString("; ")})") to frames.take(MAX_FRAMES) }
            title = null
            locks = ArrayList()
            frames = ArrayList()
        }
        for (raw in trace.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("\"") -> { flush(); title = line.trim() }
                title == null -> {}
                line.isBlank() -> flush()
                line.trimStart().startsWith("at ") -> frames += line.trimStart().removePrefix("at ")
                line.trimStart().startsWith("- ") -> line.trimStart().removePrefix("- ").let { if ("lock" in it || "waiting" in it) locks += it }
            }
        }
        flush()
        return out
    }
}
