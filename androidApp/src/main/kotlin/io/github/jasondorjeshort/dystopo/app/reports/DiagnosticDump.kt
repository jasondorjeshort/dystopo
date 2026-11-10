package io.github.jasondorjeshort.dystopo.app.reports

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SaveFile
import io.github.jasondorjeshort.dystopo.app.StartupTrace
import io.github.jasondorjeshort.dystopo.app.settings.Diagnostics
import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T258: Report a problem › **Send diagnostics** ([ReportRow]): one text file, `dystopo-dump 1` ([DumpFormat];
 * `docs/reference/diagnostic-dump.md`), that the user saves through the system's save dialog ([SaveFile.bytes]) and
 * sends as they choose. A tap only, never automatic; the app sends nothing.
 *
 * [build] (a worker thread) assembles, in this order: the header (the kind, the time, this build and phone as every
 * report carries them, the sampler's period and span), the [MemorySampler] ring (`samples`), `.startup-history`
 * (`starts`: the last twenty starts' first-frame and stall times), the Diagnostics snapshot as Copy report writes it
 * (`diagnostics`, [Diagnostics.report]) and this process's own logcat lines (`logcat -d --pid`, the newest [LOG_LINES],
 * at most [LOG_BYTES]). Every line of every section and every header value passes through [Scrub] with the phone's
 * pack, area and search names and storage roots ([Reports.known]), so paths, coordinates, boxes, tile paths, query
 * strings and names become placeholders; the debug server's per-launch token in the log is masked as well.
 * [last] keeps the bytes of the newest dump made, which `GET /debug/dump?last=1` returns.
 */
object DiagnosticDump {
    const val LOG_LINES = 4000
    const val LOG_BYTES = 768 * 1024
    private const val TAG = "DystopoDump"

    /** A dump made: its file name, text and what it took. */
    class Built(val fileName: String, val text: String, val bytes: ByteArray, val lines: Int, val sections: Map<String, Int>, val buildMs: Long, val logcatMs: Long, val logcatError: String?)

    @Volatile var last: Built? = null
        private set

    /** The dump now (a worker thread: it reads files, takes a Diagnostics snapshot and runs logcat). */
    fun build(app: DystopoApp = DystopoApp.instance): Built {
        val t0 = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val prepared = Scrub.prepare(Reports.known(app))
        fun clean(lines: List<String>) = lines.map { maskToken(Scrub.text(it, prepared)) }

        val samples = MemorySampler.lines()
        StartupTrace.flushForRead()
        val starts = runCatching { File(Reports.dir(app), StartupTrace.HISTORY).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val diagnostics = runCatching { Diagnostics.report(Diagnostics.read(app)).lines().filter { it.isNotBlank() } }
            .getOrElse { listOf("diagnostics unavailable: ${it.javaClass.simpleName}") }
        val l0 = SystemClock.elapsedRealtime()
        val (log, logError) = logcat()
        val logMs = SystemClock.elapsedRealtime() - l0

        val header = linkedMapOf(
            "kind" to "diagnostics", "atMs" to now.toString(), "at" to Report.utc(now),
        ) + Reports.system() + linkedMapOf(
            "pid" to Process.myPid().toString(), "upS" to ((SystemClock.elapsedRealtime() - app.startedAt) / 1000).toString(),
            "samplePeriodS" to MemorySampler.PERIOD_S.toString(), "sampleKeep" to MemorySampler.KEEP.toString(),
            "logLines" to log.size.toString(), "logError" to (logError ?: "-"),
        )
        val sections = listOf(
            DumpFormat.S_SAMPLES to clean(samples),
            DumpFormat.S_STARTS to clean(starts),
            DumpFormat.S_DIAGNOSTICS to clean(diagnostics),
            DumpFormat.S_LOGCAT to clean(log),
        )
        val text = DumpFormat.format(header.mapValues { maskToken(Scrub.text(it.value, prepared)) }, sections)
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Built(
            fileName(now), text, bytes, text.count { it == '\n' }, sections.associate { it.first to it.second.size },
            SystemClock.elapsedRealtime() - t0, logMs, logError,
        ).also { last = it }
    }

    /** `dystopo-diagnostics-20261110-1530.txt` (local time: the name the user sees in their files). */
    fun fileName(atMs: Long): String =
        "dystopo-diagnostics-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(atMs)) + ".txt"

    /** This process's log lines, the newest [LOG_LINES] within [LOG_BYTES] (the newest kept), and an error in words, or null. */
    private fun logcat(): Pair<List<String>, String?> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", LOG_LINES.toString())
            .redirectErrorStream(true).start()
        val lines = ArrayList<String>()
        p.inputStream.bufferedReader().useLines { s -> s.forEach { lines += it } }
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
        var bytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            bytes += line.length + 1
            if (bytes > LOG_BYTES) break
            kept.addFirst(line)
        }
        kept.toList() to null
    } catch (e: Exception) {
        Log.w(TAG, "logcat failed", e)
        emptyList<String>() to (e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    private val TOKEN = Regex("(\\btoken[ =:]+)[A-Za-z0-9_-]{8,}")

    /** The debug server's per-launch token (`debug server on port N token T`) as `<token>`. Pure. */
    fun maskToken(s: String): String = TOKEN.replace(s) { it.groupValues[1] + "<token>" }

    /**
     * The tap (main thread): builds the dump on a worker, then opens the save dialog with [fileName]; [say] gets the line
     * shown under the button ([PREPARING] at once, then [dumpSaved] or the dialog's failure; nothing when cancelled).
     */
    fun save(say: (String) -> Unit) {
        say(PREPARING)
        Thread({
            try {
                val b = build()
                SaveFile.bytes(b.fileName, b.bytes, dumpSaved(b.fileName), say)
            } catch (e: Exception) {
                Log.w(TAG, "dump failed", e)
                say(FAILED)
            }
        }, "dystopo-dump").start()
    }
}

const val DUMP_TITLE = "Send diagnostics"
const val DUMP_TEXT = "Saves a file you can send with a report: the app's memory use once a minute over the last day, the times of the last " +
    "twenty starts, the figures under Technical details and the app's own log. Locations, place names, map names and file paths " +
    "are removed. The app sends nothing; you choose where the file goes."
const val PREPARING = "Preparing the file…"
const val FAILED = "Could not make the diagnostics file."

/** The line after the save. */
fun dumpSaved(fileName: String): String = "Saved $fileName"
