package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T145: one kept problem report and everything made from it, pure (`ReportTest`): the text the Report a problem row
 * shows and Open GitHub issue copies ([shown], through [Scrub.text]), the issue's title ([title]) and the prefilled
 * link ([IssueLink]). The files are `files/reports/<kind>-<UTC stamp>.txt` in T144's format (`dystopo-report 1`, read
 * by [MainWatchdog.Dump.parse]): `freeze-` from the watchdog, `crash-` from [CrashCapture], `exit-` from the
 * `ApplicationExitInfo` records read at launch ([ExitImport]). Header keys this file adds to T144's: `exception`,
 * `thread` and `appFrame` (crash), `reason`, `reasonName`, `description`, `pid`, `pssKb`, `rssKb` (exit), `versionCode`
 * and `android` (both); a reader skips keys it does not know.
 *
 * [shown] keeps the stacks a reader needs (the research's "crashing thread, UI thread and blocked workers"): every
 * section of a crash; of a freeze or an ANR, the main thread's sections, blocked threads and running threads with an
 * app frame, then one line counting the threads left out; at most [FRAMES_SHOWN] frames a section.
 */
class Report(val id: String, val dump: MainWatchdog.Dump) {
    val kind: String get() = dump.kind ?: "unknown"
    val atMs: Long get() = dump.header["atMs"]?.toLongOrNull() ?: 0L

    /** The kind in words, as the row lists it and the issue title begins. */
    val kindLabel: String get() = KIND_LABELS[kind] ?: kind.replaceFirstChar { it.uppercase() }

    /** The frame or exception that names the problem in a line: the app frame, or the exception's class. */
    val headline: String?
        get() = (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
            ?: dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
            ?: dump.header["reasonName"]

    /** The issue's title (scrubbed like the text): `Crash: IllegalStateException at Routes.debugCrash (0.1.12-alpha)`. */
    fun title(known: Scrub.Known = Scrub.Known()): String {
        val exception = dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
        val frame = (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
        val what = listOfNotNull(exception, frame?.let { "at $it" }).joinToString(" ").ifEmpty { null }
        val version = dump.header["version"]
        return Scrub.text(kindLabel + (what?.let { ": $it" } ?: "") + (version?.let { " ($it)" } ?: ""), known)
    }

    /** The header block of [shown] and of the issue body, one `Label: value` a line. */
    private fun headerLines(): List<String> {
        val h = dump.header
        val out = ArrayList<String>()
        out += "Time: " + (h["at"] ?: utc(atMs))
        out += "App: " + listOfNotNull(h["version"], h["versionCode"]?.let { "($it)" }).joinToString(" ") + (h["build"]?.let { ", $it" } ?: "")
        val api = h["sdk"]?.let { "API $it" }
        out += "Android: " + (h["android"]?.let { a -> a + (api?.let { " ($it)" } ?: "") } ?: api ?: "?") + (h["device"]?.let { ", $it" } ?: "")
        h["reasonName"]?.let { out += "Reason: $it" + (h["reason"]?.let { r -> " ($r)" } ?: "") }
        h["description"]?.takeIf { it.isNotBlank() }?.let { out += "System's note: $it" }
        h["exception"]?.let { out += "Exception: $it" }
        h["thread"]?.let { out += "Thread: $it" }
        h["silentMs"]?.let { out += "UI thread silent for: $it ms (threshold ${h["freezeMs"] ?: "?"} ms)" }
        h["samples"]?.let { out += "Samples: ${h["topStackShare"] ?: "?"} of $it had the stack below" }
        (h["appFrame"] ?: h["blockingFrame"])?.takeIf { it != "-" }?.let { out += "App frame: $it" }
        h["pssKb"]?.toLongOrNull()?.takeIf { it > 0 }?.let { out += "Memory at the last system sample: PSS ${it / 1024} MB" + (h["rssKb"]?.toLongOrNull()?.takeIf { it > 0 }?.let { r -> ", RSS ${r / 1024} MB" } ?: "") }
        return out
    }

    /** The sections [shown] keeps, each cut to [FRAMES_SHOWN] frames, and how many threads it left out. */
    private fun keptSections(): Pair<List<Pair<String, List<String>>>, Int> {
        if (kind == "crash") return dump.sections.map { (t, f) -> t to f.take(FRAMES_SHOWN) } to 0
        val kept = dump.sections.filter { (title, frames) -> keep(title, frames) }
        return kept.map { (t, f) -> t to f.take(FRAMES_SHOWN) } to (dump.sections.size - kept.size)
    }

    /** The report as the row shows it and Open GitHub issue copies: the header in words, then the kept stacks; scrubbed. */
    fun shown(known: Scrub.Known = Scrub.Known()): String {
        val text = buildString {
            append("Dystopo problem report: ").append(kindLabel).append('\n')
            for (l in headerLines()) append(l).append('\n')
            val (sections, left) = keptSections()
            for ((title, frames) in sections) {
                append('\n').append(title).append('\n')
                for (f in frames) append("    at ").append(f).append('\n')
            }
            if (left > 0) append('\n').append("$left other threads left out (waiting or idle, no app frame)").append('\n')
        }
        return Scrub.text(text, known)
    }

    /** The issue body before encoding: the header and the top [frames] frames of the first stack, then the clipboard line. */
    fun body(known: Scrub.Known, frames: Int): String {
        val text = buildString {
            append("Dystopo problem report: ").append(kindLabel).append('\n')
            for (l in headerLines()) append(l).append('\n')
            val first = keptSections().first.firstOrNull()
            if (first != null && frames > 0) {
                append('\n').append("```").append('\n').append(first.first).append('\n')
                for (f in first.second.take(frames)) append("    at ").append(f).append('\n')
                append("```").append('\n')
            }
            append('\n').append(IssueLink.CLIPBOARD_LINE).append('\n')
        }
        return Scrub.text(text, known)
    }

    companion object {
        const val FRAMES_SHOWN = 40
        const val APP_PACKAGE = MainWatchdog.APP_PACKAGE
        private const val WATCHDOG = "$APP_PACKAGE.app.MainWatchdog"
        private const val DETECTOR = "$APP_PACKAGE.app.FreezeDetector"

        val KIND_LABELS = linkedMapOf(
            "crash" to "Crash", "native-crash" to "Native crash", "anr" to "Not responding (ANR)",
            "low-memory" to "Closed for memory", "freeze" to "Freeze",
        )

        /** `io.github...app.Routes.debugCrash(Routes.kt:120)` -> `Routes.debugCrash`. */
        fun shortFrame(frame: String): String {
            val method = frame.substringBefore('(')
            val parts = method.split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else method
        }

        /** Whether a freeze's or an ANR's thread section is shown: the main thread, a blocked one, or a running one in app code. */
        fun keep(title: String, frames: List<String>): Boolean {
            val t = title.lowercase()
            if (t.startsWith("main") || t.startsWith("thread main ") || t.startsWith("\"main\"")) return true
            if ("blocked" in t || "waiting to lock" in t) return true
            val running = "runnable" in t || "native" in t || "(running" in t
            // The watchdog's own thread runs app code while it dumps; it is not a worker the UI waited on.
            return running && frames.any { it.startsWith(APP_PACKAGE) && !it.startsWith(WATCHDOG) && !it.startsWith(DETECTOR) }
        }

        fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
        fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}

/**
 * T145: the prefilled `issues/new` link. GitHub states no limit for a prefilled URL and fails the whole link past it
 * (the crash-report survey of wave 20), so the encoded body stays under [MAX_BODY] (4 kB): the header and as many of the
 * first stack's top frames as fit, ending with [CLIPBOARD_LINE]; the full text goes on the clipboard first. Past the cap
 * even with no frame, the link carries the title alone. `labels=crash` is applied only for users with triage rights (a
 * convenience for the owner); no `template=` (a prefilled body replaces the template's; the plain template in
 * `.github/ISSUE_TEMPLATE/` serves manual filers).
 */
object IssueLink {
    const val REPO = "https://github.com/jasondorjeshort/dystopo"
    const val LABEL = "crash"
    const val MAX_BODY = 4000
    const val TOP_FRAMES = 12
    const val CLIPBOARD_LINE = "The full report is on your clipboard; paste it below."

    data class Link(val url: String, val title: String, val body: String?, val encodedBodyLength: Int, val frames: Int, val titleOnly: Boolean)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun build(report: Report, known: Scrub.Known = Scrub.Known()): Link {
        val title = report.title(known)
        val base = "$REPO/issues/new?title=${enc(title)}&labels=${enc(LABEL)}"
        for (frames in TOP_FRAMES downTo 0) {
            val body = report.body(known, frames)
            val e = enc(body)
            if (e.length <= MAX_BODY) return Link("$base&body=$e", title, body, e.length, frames, false)
        }
        return Link(base, title, null, 0, 0, true)
    }
}
