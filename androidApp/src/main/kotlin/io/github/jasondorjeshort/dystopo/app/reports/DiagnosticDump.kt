package io.github.jasondorjeshort.dystopo.app.reports

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SaveFile
import io.github.jasondorjeshort.dystopo.app.ShareGpx
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
 *
 * T301: the tester must not have to find the file. [save] also writes a copy to `cacheDir/share/diagnostics/` (the
 * newest only, [shareCopy]), and after the dialog's save the row names the file and its folder ([dumpSaved], [folderOf];
 * a plain "Saved <name>" where the document's uri names no folder) and offers **Share** ([share]: the system's share
 * sheet on that copy through T272's FileProvider, `ShareGpx.authority`, `text/plain`) and **Open GitHub issue**
 * ([openIssue]: the dump's header-only summary as the body, [issueLink], under [IssueLink.MAX_BODY], ending with the
 * line that asks for the file to be dragged in, since a prefilled issue cannot attach one). Still nothing is sent by the app.
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
    fun save(say: (String) -> Unit, ready: (Saved?) -> Unit = {}) {
        say(PREPARING)
        ready(null)
        Thread({
            try {
                val b = build()
                val copy = runCatching { shareCopy(b) }.onFailure { Log.w(TAG, "the share copy", it) }.getOrNull()
                var savedAs: Pair<String, String?> = b.fileName to null
                SaveFile.bytes(b.fileName, b.bytes, line = { uri ->
                    savedAs = (displayName(uri) ?: b.fileName) to runCatching { folderOf(DocumentsContract.getDocumentId(uri)) }.getOrNull()
                    dumpSaved(savedAs.first, savedAs.second)
                }, say = say, after = {
                    val s = Saved(savedAs.first, savedAs.second, b, copy, issueLink(b.text, savedAs.first))
                    saved = s
                    ready(s)
                })
            } catch (e: Exception) {
                Log.w(TAG, "dump failed", e)
                say(FAILED)
            }
        }, "dystopo-dump").start()
    }

    // T301: after the save, Share and Open GitHub issue.

    /** A dump saved through the dialog: the name the folder gave it, the folder's name when the uri tells it, the share copy and the issue link. */
    class Saved(val name: String, val folder: String?, val built: Built, val copy: File?, val link: IssueLink.Link)

    /** The newest dump saved (for the routes and the row). */
    @Volatile var saved: Saved? = null
        private set
    @Volatile var shares = 0
        private set
    @Volatile var lastShare: Map<String, Any?>? = null
        private set

    /** The folder under T272's share folder: the provider's paths already cover it, so no second provider (the manifest merger refuses one). */
    const val SHARE_SUB = "diagnostics"

    /** The dump's copy for the share sheet, `cacheDir/share/diagnostics/<file name>`, the newest only. Worker thread. */
    fun shareCopy(b: Built, app: DystopoApp = DystopoApp.instance): File {
        val dir = File(File(app.cacheDir, ShareGpx.DIR), SHARE_SUB).apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != b.fileName) it.delete() }
        return File(dir, b.fileName).apply { writeBytes(b.bytes) }
    }

    /** The name the documents provider gave the saved file ("(1)" added beside an older one), or null. Worker thread. */
    private fun displayName(uri: Uri): String? = runCatching {
        DystopoApp.instance.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Share (main thread): the share copy (written again if the system cleared the cache) through T272's FileProvider,
     * `ACTION_SEND` as `text/plain` inside the chooser. Returns a line for the row only on a failure.
     */
    fun share(context: Context, s: Saved): String? {
        val app = DystopoApp.instance
        return try {
            val file = s.copy?.takeIf { it.isFile && it.length() == s.built.bytes.size.toLong() } ?: shareCopy(s.built, app)
            val uri = FileProvider.getUriForFile(app, ShareGpx.authority(app), file)
            val send = Intent(Intent.ACTION_SEND).setType(SHARE_MIME).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, s.name)
            send.clipData = ClipData.newRawUri(null, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, SHARE_TITLE)
            if (context is android.app.Activity) context.startActivity(chooser) else context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shares++
            lastShare = linkedMapOf("file" to file.name, "bytes" to file.length(), "uri" to uri.toString(), "mime" to SHARE_MIME, "action" to send.action, "subject" to s.name)
            null
        } catch (e: Exception) {
            Log.w(TAG, "the share sheet", e)
            dumpShareFailed(e.message ?: e.javaClass.simpleName).also { lastShare = linkedMapOf("error" to it) }
        }
    }

    /** Open GitHub issue (main thread): the header-only link in the browser. Returns the line under the buttons. */
    fun openIssue(context: Context, s: Saved): String = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        AFTER_DUMP_ISSUE
    } catch (_: ActivityNotFoundException) {
        NO_BROWSER_DUMP
    }

    /**
     * The folder's name from a saved document's id, or null where the id names none: `primary:Download/x.txt` and
     * `raw:/storage/emulated/0/Download/x.txt` -> `Download`; `home:x.txt` -> `Documents` (that root's folder);
     * `msf:1234`, `1234` or a file at a volume's top -> null. Pure, tested.
     */
    fun folderOf(documentId: String?): String? {
        if (documentId.isNullOrBlank()) return null
        val c = documentId.indexOf(':')
        val root = if (c > 0) documentId.substring(0, c) else ""
        val path = if (c >= 0) documentId.substring(c + 1) else documentId
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val name = parent.trimEnd('/').substringAfterLast('/')
        if (name.isNotBlank()) return name
        return if (root == "home" && path.isNotBlank()) "Documents" else null
    }

    /** How many `starts` lines the issue body tries first (newest last); fewer when the body would pass [IssueLink.MAX_BODY]. */
    const val ISSUE_STARTS = 5

    /**
     * The issue body from the dump's [text] (already scrubbed by [build]; nothing else is read): what the file is, the
     * header's build, phone and time, the ring's last and highest memory figures, the newest [starts] lines of `starts`,
     * and [dragLine] with the saved file's [name] last. The log, the Diagnostics snapshot and the samples themselves stay
     * in the file. Pure, tested.
     */
    fun issueBody(text: String, name: String, starts: Int = ISSUE_STARTS): String {
        val d = DumpFormat.parse(text)
        val h = d.header
        val samples = d.samples
        fun mem(s: DumpFormat.Sample?): String? = s?.let {
            listOfNotNull(
                it.number("heapKb")?.let { v -> "heap $v kB" }, it.number("nativeKb")?.let { v -> "native $v kB" }, it.number("pssKb")?.let { v -> "PSS $v kB" },
                it.number("threads")?.let { v -> "$v threads" }, it.number("fds")?.let { v -> "$v open files" },
            ).joinToString(", ").ifEmpty { null }
        }
        val peak = listOfNotNull(
            samples.mapNotNull { it.number("heapKb") }.maxOrNull()?.let { "heap $it kB" },
            samples.mapNotNull { it.number("pssKb") }.maxOrNull()?.let { "PSS $it kB" },
        ).joinToString(", ")
        val startLines = d.section(DumpFormat.S_STARTS).takeLast(starts).map(io.github.jasondorjeshort.dystopo.app.StartupText::historyWords) // T302: the words, not the figures
        return buildString {
            append(ISSUE_INTRO).append("\n\n")
            append("Version: ").append(h["version"] ?: "?").append(" (").append(h["versionCode"] ?: "?").append(", ").append(h["build"] ?: "?").append(")\n")
            append("Phone: ").append(h["device"] ?: "?").append(", Android ").append(h["android"] ?: "?").append(" (API ").append(h["sdk"] ?: "?").append(")\n")
            append("Made: ").append(h["at"] ?: "?").append(", ").append(h["upS"] ?: "?").append(" s after the app started\n")
            append("Memory samples: ").append(samples.size).append('\n')
            mem(samples.lastOrNull())?.let { append("Memory, last sample: ").append(it).append('\n') }
            if (peak.isNotEmpty()) append("Memory, highest: ").append(peak).append('\n')
            if (startLines.isNotEmpty()) {
                append("\nLast starts, newest last:\n")
                for (l in startLines) append("- ").append(l).append('\n')
            }
            append('\n').append(dragLine(name))
        }
    }

    /** The header-only issue link: the newest [ISSUE_STARTS] starts, fewer while the encoded body passes [IssueLink.MAX_BODY]; the title alone past that. Pure, tested. */
    fun issueLink(text: String, name: String): IssueLink.Link {
        val version = runCatching { DumpFormat.parse(text).header["version"] }.getOrNull() ?: "?"
        val title = "Diagnostics ($version)"
        val base = "${IssueLink.REPO}/issues/new?title=${IssueLink.enc(title)}"
        for (n in ISSUE_STARTS downTo 0) {
            val body = issueBody(text, name, n)
            val e = IssueLink.enc(body)
            if (e.length <= IssueLink.MAX_BODY) return IssueLink.Link("$base&body=$e", title, body, e.length, 0, false, null)
        }
        return IssueLink.Link(base, title, null, 0, 0, true, null)
    }
}

const val DUMP_TITLE = "Send diagnostics"
const val DUMP_TEXT = "Saves a file you can send with a report: the app's memory use once a minute over the last day, the times of the last " +
    "twenty starts, the figures under Technical details and the app's own log. Locations, place names, map names and file paths " +
    "are removed. The app sends nothing; you choose where the file goes."
const val PREPARING = "Preparing the file…"
const val FAILED = "Could not make the diagnostics file."

/** The line after the save (T301: with the folder's name where the saved document's uri tells it). */
fun dumpSaved(fileName: String, folder: String? = null): String = if (folder == null) "Saved $fileName" else "Saved $fileName in $folder"

// T301: Share and Open GitHub issue after the save (ADR 0031).
const val DUMP_SHARE = "Share"
const val SHARE_MIME = "text/plain"
const val SHARE_TITLE = "Share diagnostics"
const val DUMP_ISSUE_NOTICE = "Share sends the file to an app you pick. Open GitHub issue starts a public issue under your account " +
    "with the file's summary (version, phone, memory and the last starts); attach the saved file to it before you submit."
const val ISSUE_INTRO = "Diagnostics from Report a problem › Send diagnostics."
const val AFTER_DUMP_ISSUE = "The issue holds the file's summary; drag the saved file onto it before you submit."
const val NO_BROWSER_DUMP = "No browser to open the issue in; use Share to send the file."
fun dumpShareFailed(why: String): String = "Could not share the file: $why"
/** The issue body's last line. */
fun dragLine(name: String): String = "Drag the saved file onto this box: $name"
