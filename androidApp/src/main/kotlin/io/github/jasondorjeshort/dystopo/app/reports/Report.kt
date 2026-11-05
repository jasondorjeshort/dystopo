package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.app.StartupText
import io.github.jasondorjeshort.dystopo.app.StartupWindow
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
 *
 * T228, a `startup` file ([io.github.jasondorjeshort.dystopo.app.StartupTrace]): "Startup freeze", or "Last start" when it
 * passed the thresholds. Its text puts the stalls and what held the main thread first, then the timeline, then the
 * start-length figures among the header's, then the stacks, and the recent starts last ([startupParts]); its title names
 * the longest hold, not the start's length. A body ([startupBody]) carries what fits the link in that order.
 */
class Report(val id: String, val dump: MainWatchdog.Dump) {
    val kind: String get() = dump.kind ?: "unknown"
    val atMs: Long get() = dump.header["atMs"]?.toLongOrNull() ?: 0L

    /** T228: a start's trace. */
    val isStartup: Boolean get() = kind == "startup"

    /** The kind in words, as the row lists it and the issue title begins. */
    val kindLabel: String
        get() = when {
            isStartup -> if (startupReasons.isEmpty()) "Last start" else "Startup freeze"
            else -> KIND_LABELS[kind] ?: kind.replaceFirstChar { it.uppercase() }
        }

    private val startupReasons: List<String> get() = dump.header["reasons"].orEmpty().split(',').filter { it.isNotEmpty() && it != "-" }

    /** The frame or exception that names the problem in a line: the app frame, or the exception's class. */
    val headline: String?
        get() = if (isStartup) startupWhat() else (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
            ?: dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
            ?: dump.header["reasonName"]

    /** The issue's title (scrubbed like the text): `Crash: IllegalStateException at Routes.debugCrash (0.1.12-alpha)`. */
    fun title(known: Scrub.Known = Scrub.Known()): String {
        if (isStartup) return Scrub.text(kindLabel + (startupWhat()?.let { ": $it" } ?: "") + (dump.header["version"]?.let { " ($it)" } ?: ""), known)
        val exception = dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
        val frame = (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
        val what = listOfNotNull(exception, frame?.let { "at $it" }).joinToString(" ").ifEmpty { null }
        val version = dump.header["version"]
        return Scrub.text(kindLabel + (what?.let { ": $it" } ?: "") + (version?.let { " ($it)" } ?: ""), known)
    }

    /**
     * T228: what a start's title says after the kind: the longest hold of the main thread and its frame (or step), whichever
     * reason brought the report, since the complaint is the freezing: `14.2 s, at PackSet.open`; the blank map's and the
     * start that ended's own words when nothing held it; the first frame's length only on a start with no reason at all.
     */
    private fun startupWhat(): String? {
        val h = dump.header
        val ended = h["outcome"] == StartupWindow.ENDED
        val reasons = startupReasons
        val top = h["topFreezeMs"]?.toLongOrNull()
        val where = h["blockingFrame"]?.takeIf { it != "-" }?.let(::shortFrame) ?: h["topFreezeMark"]
        val held = if (top != null && where != null) String.format(Locale.ROOT, "%.1f s, at %s", top / 1000.0, where) else null
        val onlyMap = reasons == listOf(StartupWindow.REASON_MAP)
        return when {
            reasons.isEmpty() -> h["firstFrameMs"]?.toLongOrNull()?.let { "first frame " + String.format(Locale.ROOT, "%.1f s", it / 1000.0) }
            onlyMap -> "map not drawn after ${(h["mapMs"]?.toLongOrNull() ?: 15_000L) / 1000} s"
            held != null -> held + if (ended) ", process ended" else ""
            ended -> "process ended during the start"
            StartupWindow.REASON_MAP in reasons -> "map not drawn after ${(h["mapMs"]?.toLongOrNull() ?: 15_000L) / 1000} s"
            else -> null
        }
    }

    /** T228: why this report exists, in words; a stall first, the start's length last. */
    private fun startupWhy(): List<String> {
        val h = dump.header
        val out = ArrayList<String>()
        val secs = { key: String -> h[key]?.toLongOrNull()?.let(StartupText::seconds) ?: "?" }
        val reasons = startupReasons
        val kindName = h["startKind"] ?: "?"
        if (StartupWindow.REASON_STALL in reasons) out += "The main thread stopped answering for ${secs("longestStallMs")} after the first frame."
        when (h["outcome"]) {
            StartupWindow.ENDED -> out += "Outcome: the process ended during the start, after ${secs("elapsedMs")}" +
                (h["exitReasonName"]?.let { n -> " (${n}, reason ${h["exitReason"] ?: "?"})" } ?: "") + "."
            StartupWindow.NO_FIRST_FRAME -> out += "Outcome: no first frame within ${secs("elapsedMs")}."
            StartupWindow.LEFT -> if (StartupWindow.REASON_NOT_FINISHED in reasons) out += "Outcome: the app was left after ${secs("elapsedMs")}, before its first frame."
            StartupWindow.RUNNING -> out += "Outcome: still measuring when this was written (${secs("elapsedMs")} so far)."
        }
        if (StartupWindow.REASON_MAP in reasons) out += "The map was not fully drawn ${(h["mapMs"]?.toLongOrNull() ?: 15_000L) / 1000} s after the first frame."
        if (StartupWindow.REASON_FIRST_FRAME in reasons) out += "The first frame took ${secs("firstFrameMs").takeIf { h["firstFrameMs"] != null } ?: "over " + secs("thresholdMs")}, over the ${secs("thresholdMs")} limit for a $kindName start."
        if (reasons.isEmpty()) out += "Passed the thresholds: ${kindName} start, first frame ${secs("firstFrameMs")}, longest stall ${secs("longestStallMs")}."
        return out
    }

    /** T228: a start's header in words, after the stalls and the timeline. */
    private fun startupHeader(): List<String> {
        val h = dump.header
        val out = ArrayList<String>()
        out += "Time: " + (h["at"] ?: utc(atMs))
        out += "App: " + listOfNotNull(h["version"], h["versionCode"]?.let { "($it)" }).joinToString(" ") + (h["build"]?.let { ", $it" } ?: "")
        val api = h["sdk"]?.let { "API $it" }
        out += "Android: " + (h["android"]?.let { a -> a + (api?.let { " ($it)" } ?: "") } ?: api ?: "?") + (h["device"]?.let { ", $it" } ?: "")
        val first = when (h["firstStart"]) { "install" -> ", first after the install"; "update" -> ", first after an update"; else -> "" }
        val source = if (h["kindSource"] == "startInfo") " (the system's start record)" else ""
        out += "Start: ${h["startKind"] ?: "?"}$source$first" + (h["firstFrameMs"]?.toLongOrNull()?.let { ", first frame ${StartupText.seconds(it)}" } ?: ", no first frame") + " (limit ${h["thresholdMs"]?.toLongOrNull()?.let(StartupText::seconds) ?: "?"})"
        h["processStartToAttachMs"]?.let { out += "Loading and field initialisers before attachBaseContext, not sampled: $it ms" }
        val phone = ArrayList<String>()
        h["sinceBootS"]?.let { phone += "$it s since boot" }
        if (h["ramAvailMb"] != null) phone += "RAM ${h["ramAvailMb"]} MB free of ${h["ramTotalMb"] ?: "?"} MB" + if (h["lowMemory"] == "true") ", low" else ""
        h["thermal"]?.let { phone += "thermal $it" }
        h["batterySaver"]?.let { phone += "battery saver " + if (it == "true") "on" else "off" }
        if (phone.isNotEmpty()) out += "Phone: " + phone.joinToString(", ")
        if (h["heapMaxMb"] != null) out += "Java heap at the first frame: ${h["heapUsedMb"] ?: "?"} MB of ${h["heapMaxMb"]} MB" + (h["gcCount"]?.let { ", GCs $it" } ?: "")
        h["packs"]?.let { n -> out += "Packs: $n, ${(h["packBytes"]?.toLongOrNull() ?: 0L) / (1024 * 1024)} MB" }
        h["startInfoReason"]?.let { r -> out += "System's start record: $r, ${h["startInfoType"] ?: "?"}" + (h["startInfoTimes"]?.let { t -> "; $t" } ?: "") }
        return out
    }

    /** T228: a start's sections by kind. */
    private fun info(title: String): List<String> = dump.sections.firstOrNull { it.first == title }?.second.orEmpty()
    private val snapshots: List<Pair<String, List<String>>> get() = dump.sections.filter { it.first.startsWith(StartupText.S_SNAPSHOT + " ") }
    private fun startupStacks(): Pair<List<Pair<String, List<String>>>, Int> {
        val named = setOf(StartupText.S_STALLS, StartupText.S_BLOCKED, StartupText.S_TIMELINE, StartupText.S_HISTORY)
        val stacks = dump.sections.filter { it.first !in named && !it.first.startsWith(StartupText.S_SNAPSHOT + " ") }
        val kept = stacks.filter { (t, f) -> keep(t, f) }
        return kept.map { (t, f) -> t to f.take(FRAMES_SHOWN) } to (stacks.size - kept.size)
    }

    /** T228: the whole text of a start's report, in its order. */
    private fun startupShown(): String = buildString {
        append("Dystopo problem report: ").append(kindLabel).append('\n')
        for (l in startupWhy()) append(l).append('\n')
        fun part(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            append('\n').append(title).append('\n')
            for (l in lines) append("  ").append(l).append('\n')
        }
        part(STALLS_TITLE, info(StartupText.S_STALLS))
        part(BLOCKED_TITLE, info(StartupText.S_BLOCKED))
        part(TIMELINE_TITLE, info(StartupText.S_TIMELINE))
        append('\n')
        for (l in startupHeader()) append(l).append('\n')
        for ((t, lines) in snapshots) part(t.replaceFirstChar { it.uppercase() }, lines)
        val (sections, left) = startupStacks()
        for ((title, frames) in sections) {
            append('\n').append(title).append('\n')
            for (f in frames) append("    at ").append(f).append('\n')
        }
        if (left > 0) append('\n').append("$left other threads left out (waiting or idle, no app frame)").append('\n')
        part(HISTORY_TITLE, info(StartupText.S_HISTORY))
    }

    /** T228: how much of a start's report one link's body carries, the rest going to the clipboard. */
    class Rung(val frames: Int, val history: Int, val minStepMs: Long, val stalls: Int = 5, val blocked: Int = 3)

    private fun longEnough(line: String, minMs: Long): Boolean {
        val m = STEP.find(line) ?: return true
        return ((m.groupValues[2].toDouble() - m.groupValues[1].toDouble()) * 1000).toLong() >= minMs
    }

    /** T228: the issue body of a start's report: [startupShown]'s order, cut to [rung]; [IssueLink.CLIPBOARD_LINE] unless complete. */
    fun startupBody(known: Scrub.Known, rung: Rung): String {
        val text = buildString {
            append("Dystopo problem report: ").append(kindLabel).append('\n')
            for (l in startupWhy()) append(l).append('\n')
            fun part(title: String, lines: List<String>) {
                if (lines.isEmpty()) return
                append('\n').append(title).append('\n')
                for (l in lines) append("  ").append(l).append('\n')
            }
            part(STALLS_TITLE, info(StartupText.S_STALLS).take(rung.stalls))
            part(BLOCKED_TITLE, info(StartupText.S_BLOCKED).take(rung.blocked))
            val timeline = info(StartupText.S_TIMELINE)
            val steps = timeline.filter { longEnough(it, rung.minStepMs) }
            part(TIMELINE_TITLE, steps + if (steps.size < timeline.size) listOf("(${timeline.size - steps.size} steps under ${rung.minStepMs} ms left out)") else emptyList())
            append('\n')
            for (l in startupHeader()) append(l).append('\n')
            for ((t, lines) in snapshots) part(t.replaceFirstChar { it.uppercase() }, lines)
            val first = startupStacks().first.firstOrNull()
            if (first != null && rung.frames > 0) {
                append('\n').append("```").append('\n').append(first.first).append('\n')
                for (f in first.second.take(rung.frames)) append("    at ").append(f).append('\n')
                append("```").append('\n')
            }
            part(HISTORY_TITLE, info(StartupText.S_HISTORY).takeLast(rung.history))
            if (startupRemainder(known, rung) != null) append('\n').append(IssueLink.CLIPBOARD_LINE).append('\n')
        }
        return Scrub.text(text, known)
    }

    /** T228: what [startupBody] at [rung] lacks, scrubbed; null when it holds it all. */
    fun startupRemainder(known: Scrub.Known, rung: Rung): String? {
        val text = buildString {
            fun part(title: String, lines: List<String>) {
                if (lines.isEmpty()) return
                if (isNotEmpty()) append('\n')
                append(title).append('\n')
                for (l in lines) append("  ").append(l).append('\n')
            }
            part(STALLS_TITLE, info(StartupText.S_STALLS).drop(rung.stalls))
            part(BLOCKED_TITLE, info(StartupText.S_BLOCKED).drop(rung.blocked))
            val timeline = info(StartupText.S_TIMELINE)
            if (timeline.any { !longEnough(it, rung.minStepMs) }) part(TIMELINE_TITLE, timeline)
            val (sections, left) = startupStacks()
            sections.forEachIndexed { i, (title, frames) ->
                val carried = if (i == 0) rung.frames.coerceIn(0, frames.size) else 0
                if (carried < frames.size) {
                    if (isNotEmpty()) append('\n')
                    append(title).append(if (carried > 0) " (from frame ${carried + 1})" else "").append('\n')
                    for (f in frames.drop(carried)) append("    at ").append(f).append('\n')
                }
            }
            if (left > 0) {
                if (isNotEmpty()) append('\n')
                append("$left other threads left out (waiting or idle, no app frame)").append('\n')
            }
            part(HISTORY_TITLE, info(StartupText.S_HISTORY).dropLast(rung.history))
        }
        return text.ifEmpty { null }?.let { Scrub.text(it, known) }
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
        h["silentMs"]?.let { out += "UI thread silent for: ${if (h["ended"] == "true") "" else "at least "}$it ms (threshold ${h["freezeMs"] ?: "?"} ms)" }
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
        if (isStartup) return Scrub.text(startupShown(), known)
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

    /**
     * What [shown] has that a body carrying the top [frames] frames of the first stack lacks, scrubbed; null when the body
     * holds it all (no stack, or the one kept stack fits and no thread was left out). The header is not repeated: the first
     * stack's frames past the body's, headed by its title, then the other kept stacks and the left-out line.
     */
    fun remainder(known: Scrub.Known, frames: Int): String? {
        val (sections, left) = keptSections()
        val first = sections.firstOrNull() ?: return if (left > 0) Scrub.text("$left other threads left out (waiting or idle, no app frame)\n", known) else null
        val carried = frames.coerceIn(0, first.second.size)
        if (carried == first.second.size && sections.size == 1 && left == 0) return null
        val text = buildString {
            if (carried < first.second.size) {
                append(first.first).append(if (carried > 0) " (from frame ${carried + 1})" else "").append('\n')
                for (f in first.second.drop(carried)) append("    at ").append(f).append('\n')
            }
            for ((title, fs) in sections.drop(1)) {
                if (isNotEmpty()) append('\n')
                append(title).append('\n')
                for (f in fs) append("    at ").append(f).append('\n')
            }
            if (left > 0) append('\n').append("$left other threads left out (waiting or idle, no app frame)").append('\n')
        }
        return Scrub.text(text, known)
    }

    /** The issue body before encoding: the header and the top [frames] frames of the first stack, then [IssueLink.CLIPBOARD_LINE] unless the body is complete. */
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
            if (remainder(known, frames) != null) append('\n').append(IssueLink.CLIPBOARD_LINE).append('\n')
        }
        return Scrub.text(text, known)
    }

    companion object {
        const val FRAMES_SHOWN = 40
        const val APP_PACKAGE = MainWatchdog.APP_PACKAGE
        private const val STALLS_TITLE = "Main thread stalls after the first frame (500 ms and up, the longest first):"
        private const val BLOCKED_TITLE = "What held the main thread before the first frame:"
        private const val TIMELINE_TITLE = "Timeline, from the start of the window:"
        private const val HISTORY_TITLE = "Recent starts, the newest last:"
        private val STEP = Regex("^(\\d+\\.\\d+) s to (\\d+\\.\\d+) s ")

        /** T228: what a start's body carries, the most first (the history is the clue to "sometimes", the top stack is named in its first lines). */
        val RUNGS = listOf(
            Rung(12, 20, 20), Rung(8, 20, 20), Rung(12, 10, 20), Rung(8, 10, 20), Rung(8, 5, 20), Rung(4, 5, 50), Rung(4, 0, 50), Rung(0, 5, 50),
            Rung(0, 0, 100), Rung(0, 0, 500, stalls = 3, blocked = 2),
        )

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
            return running && frames.any(MainWatchdog::isAppFrame)
        }

        fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
        fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}

/**
 * T145: the prefilled `issues/new` link. GitHub states no limit for a prefilled URL and fails the whole link past it
 * (the crash-report survey of wave 20), so the encoded body stays under [MAX_BODY] (4 kB): the header and as many of the
 * first stack's top frames as fit, ending with [CLIPBOARD_LINE] when more of the stack exists; that remainder
 * ([Report.remainder]) goes on the clipboard first, and nothing is copied when the body is complete. Past the cap even
 * with no frame, the link carries the title alone and the clipboard holds the whole text. `labels=crash` is applied only for users with triage rights (a
 * convenience for the owner); no `template=` (a prefilled body replaces the template's; the plain template in
 * `.github/ISSUE_TEMPLATE/` serves manual filers).
 */
object IssueLink {
    const val REPO = "https://github.com/jasondorjeshort/dystopo"
    const val LABEL = "crash"
    const val MAX_BODY = 4000
    const val TOP_FRAMES = 12
    const val CLIPBOARD_LINE = "The rest of the stack trace is on your clipboard; paste it below."

    /** [clipboard] is what Open GitHub issue copies: the whole text for a title-only link, the part the body lacks, or null (the body is complete). */
    data class Link(val url: String, val title: String, val body: String?, val encodedBodyLength: Int, val frames: Int, val titleOnly: Boolean, val clipboard: String?)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun build(report: Report, known: Scrub.Known = Scrub.Known()): Link {
        val title = report.title(known)
        val base = "$REPO/issues/new?title=${enc(title)}&labels=${enc(LABEL)}"
        if (report.isStartup) {
            for (rung in Report.RUNGS) {
                val body = report.startupBody(known, rung)
                val e = enc(body)
                if (e.length <= MAX_BODY) return Link("$base&body=$e", title, body, e.length, rung.frames, false, report.startupRemainder(known, rung))
            }
            return Link(base, title, null, 0, 0, true, report.shown(known))
        }
        for (frames in TOP_FRAMES downTo 0) {
            val body = report.body(known, frames)
            val e = enc(body)
            if (e.length <= MAX_BODY) return Link("$base&body=$e", title, body, e.length, frames, false, report.remainder(known, frames))
        }
        return Link(base, title, null, 0, 0, true, report.shown(known))
    }
}
