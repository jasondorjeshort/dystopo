package io.github.jasondorjeshort.dystopo.app.reports

import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.app.StartupText
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.reports.DumpFormat
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
 * T228, a `startup` file ([io.github.jasondorjeshort.dystopo.app.StartupTrace]): "Startup freeze", or "Start trace" when it
 * passed the thresholds (a kept trace, read by `GET /reports/{id}`; T302 took it off the row). The owner's freeze is the screen stuck on the splash (2026-11-05), so its text leads with what held
 * the main thread before the first frame, step by step, the longest first, then the stalls after it, the timeline, the
 * header's figures, the stacks, and the recent starts last ([startupShown]); its title names the time to the first frame at the
 * step that took longest of it, or else the longest stall. A body ([startupBody]) carries what fits the link in that order.
 *
 * T302, kind `stats` ([RecentStats], no file): Report recent stats, "Recent stats", the phone's recent figures in words; a body
 * ([statsBody]) carries the header, the steps and the phone's figures, then as many recent starts as fit.
 */
class Report(val id: String, val dump: MainWatchdog.Dump) {
    val kind: String get() = dump.kind ?: "unknown"
    val atMs: Long get() = dump.header["atMs"]?.toLongOrNull() ?: 0L

    /** T228: a start's trace. */
    val isStartup: Boolean get() = kind == "startup"

    /** T302: Report recent stats ([RecentStats]). */
    val isStats: Boolean get() = kind == RecentStats.KIND

    /** The kind in words, as the row lists it and the issue title begins. */
    val kindLabel: String
        get() = when {
            isStartup -> if (startupReasons.isEmpty()) "Start trace" else "Startup freeze"
            isStats -> RecentStats.LABEL
            else -> KIND_LABELS[kind] ?: kind.replaceFirstChar { it.uppercase() }
        }

    private val startupReasons: List<String> get() = dump.header["reasons"].orEmpty().split(',').filter { it.isNotEmpty() && it != "-" }

    /** The frame or exception that names the problem in a line: the app frame, or the exception's class. */
    val headline: String?
        get() = if (isStats) dump.header["starts"]?.let { "$it starts" } else if (isStartup) startupWhat() else (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
            ?: dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
            ?: dump.header["reasonName"]

    /** The issue's title (scrubbed like the text): `Crash: IllegalStateException at Routes.debugCrash (0.1.12-alpha)`. */
    fun title(known: Scrub.Known = Scrub.Known()): String {
        if (isStats) return Scrub.text(kindLabel + (dump.header["version"]?.let { " ($it)" } ?: ""), known)
        if (isStartup) return Scrub.text(kindLabel + (startupWhat()?.let { ": $it" } ?: "") + (dump.header["version"]?.let { " ($it)" } ?: ""), known)
        val exception = dump.header["exception"]?.substringBefore(':')?.substringAfterLast('.')
        val frame = (dump.header["appFrame"] ?: dump.header["blockingFrame"])?.takeIf { it != "-" }?.let(::shortFrame)
        val what = listOfNotNull(exception, frame?.let { "at $it" }).joinToString(" ").ifEmpty { null }
        val version = dump.header["version"]
        return Scrub.text(kindLabel + (what?.let { ": $it" } ?: "") + (version?.let { " ($it)" } ?: ""), known)
    }

    /**
     * T228: what a start's title says after the kind: stuck before the first frame, the time to it and the step that took longest
     * of it, by its frame (`14.2 s, at PackSet.open`); else the longest stall the same way; the blank map's and the start that
     * ended's own words when nothing held it; the first frame's length on a start with no reason at all.
     */
    private fun startupWhat(): String? {
        val h = dump.header
        val ended = h["outcome"] == StartupWindow.ENDED
        val reasons = startupReasons
        val top = h["topFreezeMs"]?.toLongOrNull()
        // A frame only when the samples agree on it: spread over many stacks, the step's own name says more.
        val share = h["topFreezeShare"]?.split(" of ")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val agreed = share.size == 2 && share[1] > 0 && share[0] * 3 >= share[1]
        val where = (if (agreed) h["blockingFrame"]?.takeIf { it != "-" }?.let(::shortFrame) else null) ?: h["topFreezeMark"]
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

    /** T228: why this report exists, in words; the splash first. */
    private fun startupWhy(): List<String> {
        val h = dump.header
        val out = ArrayList<String>()
        val secs = { key: String -> h[key]?.toLongOrNull()?.let(StartupText::seconds) ?: "?" }
        val reasons = startupReasons
        val kindName = h["startKind"] ?: "?"
        if (StartupWindow.REASON_FIRST_FRAME in reasons) out += if (h["firstFrameMs"] != null) "The first frame took ${secs("firstFrameMs")}, over the ${secs("thresholdMs")} limit for a $kindName start."
            else "No first frame yet, past the ${secs("thresholdMs")} limit for a $kindName start."
        when (h["outcome"]) {
            StartupWindow.ENDED -> out += "Outcome: the process ended during the start, after ${secs("elapsedMs")}" +
                (h["exitReasonName"]?.let { n -> " (${n}, reason ${h["exitReason"] ?: "?"})" } ?: "") + "."
            StartupWindow.NO_FIRST_FRAME -> out += "Outcome: no first frame within ${secs("elapsedMs")}."
            StartupWindow.LEFT -> if (StartupWindow.REASON_NOT_FINISHED in reasons) out += "Outcome: the app was left after ${secs("elapsedMs")}, before its first frame."
            StartupWindow.RUNNING -> out += "Outcome: still measuring when this was written (${secs("elapsedMs")} so far)."
        }
        if (StartupWindow.REASON_STALL in reasons) out += "The main thread stopped answering for ${secs("longestStallMs")} after the first frame."
        if (StartupWindow.REASON_MAP in reasons) out += "The map was not fully drawn ${(h["mapMs"]?.toLongOrNull() ?: 15_000L) / 1000} s after the first frame."
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
        h["packs"]?.let { n -> out += "Offline maps: $n, ${(h["packBytes"]?.toLongOrNull() ?: 0L) / (1024 * 1024)} MB" }
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
        part(BLOCKED_TITLE, info(StartupText.S_BLOCKED))
        part(STALLS_TITLE, info(StartupText.S_STALLS))
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
    class Rung(val frames: Int, val history: Int, val minStepMs: Long, val stalls: Int = 3, val blocked: Int = 6)

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
            part(BLOCKED_TITLE, info(StartupText.S_BLOCKED).take(rung.blocked))
            part(STALLS_TITLE, info(StartupText.S_STALLS).take(rung.stalls))
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
            part(BLOCKED_TITLE, info(StartupText.S_BLOCKED).drop(rung.blocked))
            part(STALLS_TITLE, info(StartupText.S_STALLS).drop(rung.stalls))
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

    /**
     * T302: a stats report's text: the header in words, then its sections in order ([RecentStats.dump]), the recent starts'
     * section cut to its newest [starts] lines and each start's figures to the newest [each]; [IssueLink.STATS_CLIPBOARD_LINE]
     * when [line] and something was cut.
     */
    private fun statsText(starts: Int, each: Int, line: Boolean = false): String = buildString {
        append("Dystopo recent stats").append('\n')
        for (l in statsHeader()) append(l).append('\n')
        var cut = false
        for ((title, lines) in dump.sections) {
            val n = when (title) { RecentStats.S_STARTS -> starts; RecentStats.S_EACH -> each; else -> Int.MAX_VALUE }
            val kept = lines.takeLast(n.coerceAtLeast(0))
            if (kept.size < lines.size) cut = true
            if (kept.isEmpty()) continue
            append('\n').append(title).append('\n')
            for (l in kept) append("  ").append(l).append('\n')
        }
        if (line && cut) append('\n').append(IssueLink.STATS_CLIPBOARD_LINE).append('\n')
    }

    private fun statsHeader(): List<String> {
        val h = dump.header
        val api = h["sdk"]?.let { "API $it" }
        return listOf(
            "Time: " + (h["at"] ?: utc(atMs)),
            "App: " + listOfNotNull(h["version"], h["versionCode"]?.let { "($it)" }).joinToString(" ") + (h["build"]?.let { ", $it" } ?: ""),
            "Android: " + (h["android"]?.let { a -> a + (api?.let { " ($it)" } ?: "") } ?: api ?: "?") + (h["device"]?.let { ", $it" } ?: ""),
        )
    }

    /** T302: the issue body of a stats report: [statsText] with the newest [starts] recent starts and [each] starts' figures, scrubbed. */
    fun statsBody(known: Scrub.Known, starts: Int, each: Int): String = Scrub.text(statsText(starts, each, line = true), known)

    /** T302: what [statsBody] at [starts] and [each] lacks (the older lines of the two cut sections), scrubbed; null when nothing was cut. */
    fun statsRemainder(known: Scrub.Known, starts: Int, each: Int): String? {
        val text = buildString {
            for ((title, lines) in dump.sections) {
                val n = when (title) { RecentStats.S_STARTS -> starts; RecentStats.S_EACH -> each; else -> continue }
                val left = lines.dropLast(n.coerceAtLeast(0))
                if (left.isEmpty()) continue
                if (isNotEmpty()) append('\n')
                append(title).append(" (the older ").append(left.size).append(")\n")
                for (l in left) append("  ").append(l).append('\n')
            }
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
        if (isStats) return Scrub.text(statsText(Int.MAX_VALUE, Int.MAX_VALUE), known)
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
        private const val BLOCKED_TITLE = "What held the main thread before the first frame (the longest first):"
        private const val TIMELINE_TITLE = "Timeline, from the start of the window:"
        private const val HISTORY_TITLE = "Recent starts, the newest last:"
        private val STEP = Regex("^(\\d+\\.\\d+) s to (\\d+\\.\\d+) s ")

        /** T228: what a start's body carries, the most first (the top stack before the small steps and the history; the clipboard has the rest). */
        val RUNGS = listOf(
            Rung(12, 20, 20), Rung(8, 20, 20), Rung(12, 10, 50), Rung(8, 10, 50), Rung(8, 5, 50), Rung(8, 5, 100), Rung(8, 5, 100, blocked = 4),
            Rung(6, 0, 100, blocked = 4), Rung(6, 0, 200, blocked = 3), Rung(4, 0, 500, blocked = 3), Rung(0, 0, 500, stalls = 2, blocked = 3),
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
            if ("(blocked" in t || " blocked" in t.substringBefore(", while main") || "waiting to lock" in t) return true
            if (StartupText.LOCK_HOLDER in t) return true
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
    /** T302: a stats report's last line when its body was cut. */
    const val STATS_CLIPBOARD_LINE = "The rest of the report is on your clipboard; paste it below."

    /** [clipboard] is what Open GitHub issue copies: the whole text for a title-only link, the part the body lacks, or null (the body is complete). */
    data class Link(val url: String, val title: String, val body: String?, val encodedBodyLength: Int, val frames: Int, val titleOnly: Boolean, val clipboard: String?)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun build(report: Report, known: Scrub.Known = Scrub.Known()): Link {
        val title = report.title(known)
        if (report.isStats) {
            // T302: not a crash, so no label; every start's figures first, then the starts' lines, the newest kept longest.
            val base = "$REPO/issues/new?title=${enc(title)}"
            val n = report.dump.sections.firstOrNull { it.first == RecentStats.S_STARTS }?.second?.size ?: 0
            val m = report.dump.sections.firstOrNull { it.first == RecentStats.S_EACH }?.second?.size ?: 0
            val rungs = (m downTo 0).map { n to it } + (n - 1 downTo 0).map { it to 0 }
            for ((starts, each) in rungs) {
                val body = report.statsBody(known, starts, each)
                val e = enc(body)
                if (e.length <= MAX_BODY) return Link("$base&body=$e", title, body, e.length, 0, false, report.statsRemainder(known, starts, each))
            }
            return Link(base, title, null, 0, 0, true, report.shown(known))
        }
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

/**
 * T302: Settings › About and advanced › Report a problem › **Report recent stats**, pure (`RecentStatsTest`); the phone's
 * figures are read by [Reports.recentStats]. One report, no file (id [ID], kind [KIND]): the recent cold starts' figures from
 * `.startup-history` ([StartupText.historyStats]) as medians beside the ceilings of `tools/launch-budget.json` (bundled as the
 * asset [BUDGET_ASSET]), then what only the phone can say now ([tileLines], [areaLine], [memoryLines]), the recent starts'
 * lines and each cold start's figures. Its body and clipboard: [IssueLink.build]. `devtool dump-scan --recent-stats` puts the
 * same figures, from a dump's `starts` or this report's text, beside the emulator's medians.
 */
object RecentStats {
    const val ID = "recent-stats"
    const val KIND = "stats"
    const val LABEL = "Recent stats"
    const val BUDGET_ASSET = "launch-budget.json"
    const val S_PHONE = "This phone now:"
    const val S_STARTS = "Recent starts, the newest last:"
    const val S_EACH = "Each recent cold start's figures in ms, the newest last:"
    const val NO_STEPS = "No cold start with step times yet; the app keeps them from this version on."
    const val NO_BUDGET = "(the app's limits could not be read)"

    /** The figures shown before the stages, and those after them; each figure's name in words ([name]). */
    val FRAMES = listOf("firstFrameMs", "fullyLoadedMs")
    val HEADER_FIGURES = listOf("fdTableMs", "packageInfoMs")
    val NAMES = linkedMapOf("firstFrameMs" to "first frame", "fullyLoadedMs" to "map drawn", "fdTableMs" to "open-files table", "packageInfoMs" to "package read")

    fun name(key: String): String = NAMES[key] ?: key

    fun stepsTitle(cold: Int): String = "Start steps, the median of $cold recent cold start${if (cold == 1) "" else "s"}, against the app's limits:"

    /** `tools/launch-budget.json`: each figure's ceiling and the emulator's median (the median of the sweeps' medians), the stages in order. */
    class Budget(val ceilings: Map<String, Long>, val emulator: Map<String, Double>, val stages: List<String>) {
        companion object {
            fun parse(text: String): Budget {
                val o = MiniJson.parseObject(text)
                val ceilings = linkedMapOf<String, Long>()
                val emulator = linkedMapOf<String, Double>()
                val stages = ArrayList<String>()
                for (group in listOf("frames", "stages")) {
                    val g = o[group] as? Map<*, *> ?: continue
                    for ((k, v) in g) {
                        val key = k as? String ?: continue
                        val m = v as? Map<*, *> ?: continue
                        (m["ceilingMs"] as? Number)?.let { ceilings[key] = it.toLong() }
                        (m["medianMs"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.let(::median)?.let { emulator[key] = it }
                        if (group == "stages") stages += key
                    }
                }
                return Budget(ceilings, emulator, stages)
            }
        }
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** A time in ms: one decimal for a fraction under 100 ms, else whole. */
    fun ms(x: Double): String = if (x < 100 && x % 1.0 != 0.0) String.format(Locale.ROOT, "%.1f ms", x) else "${Math.round(x)} ms"

    fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

    /** The cold starts' figures, oldest first (lines without figures left out). */
    fun figures(history: List<String>): List<Map<String, String>> = history.map(StartupText::historyStats).filter { it.isNotEmpty() }

    /** The figures in the order shown: the frames, the budget's stages, any other stage, the header's figures; only those some start has. */
    fun keys(figures: List<Map<String, String>>, budget: Budget?): List<String> {
        val keys = LinkedHashSet<String>(FRAMES)
        budget?.stages?.let(keys::addAll)
        figures.forEach { keys.addAll(it.keys) }
        keys.removeAll(HEADER_FIGURES.toSet())
        keys.addAll(HEADER_FIGURES)
        return keys.filter { k -> figures.any { it[k]?.toDoubleOrNull() != null } }
    }

    /** One line a figure: `prepare 210 ms, limit 300 ms` (`, over` past it; `(n of m)` when some starts lack it). */
    fun stepLines(figures: List<Map<String, String>>, budget: Budget?): List<String> = keys(figures, budget).mapNotNull { k ->
        val xs = figures.mapNotNull { it[k]?.toDoubleOrNull()?.takeIf { v -> v >= 0 } }
        val med = median(xs) ?: return@mapNotNull null
        val ceiling = budget?.ceilings?.get(k)
        name(k) + " " + ms(med) + (if (xs.size < figures.size) " (${xs.size} of ${figures.size})" else "") +
            (ceiling?.let { ", limit $it ms" + if (med > it) ", over" else "" } ?: "")
    }

    /** Each cold start's figures on a line: `0.1.32-alpha: first frame 3651, map drawn 4876, prepare 196, ...`. */
    fun eachLines(history: List<String>, budget: Budget?): List<String> {
        val withFigures = history.filter { StartupText.historyStats(it).isNotEmpty() }
        val keys = keys(figures(withFigures), budget)
        return withFigures.map { line ->
            val f = StartupText.historyStats(line)
            line.substringBefore(' ') + ": " + keys.mapNotNull { k -> f[k]?.let { "${name(k)} $it" } }.joinToString(", ")
        }
    }

    /** The tile store from `TileCache.json()` (the online store's figures, its move's among them); null: not ready. */
    fun tileLines(cache: Map<String, Any?>?): List<String> {
        if (cache == null) return listOf("Map tiles: not read, the tile store was not ready.")
        val out = ArrayList<String>()
        fun long(m: Map<*, *>?, k: String) = (m?.get(k) as? Number)?.toLong()
        val pinned = long(cache, "pinnedBytes")
        out += "Map tiles stored: ${long(cache, "entries") ?: 0L} files, ${mb(long(cache, "bytes") ?: 0L)} of ${mb(long(cache, "maxBytes") ?: 0L)} allowed" +
            (pinned?.takeIf { it > 0 }?.let { ", ${mb(it)} of them in offline maps" } ?: "")
        val dropped = long(cache, "emptyDropped") ?: 0L
        val droppedKept = long(cache, "emptyDroppedKept") ?: 0L
        out += "Tiles dropped as empty since this start: $dropped" + if (droppedKept > 0) " ($droppedKept of them in offline maps)" else ""
        val state = cache["moveState"] as? String
        val move = cache["move"] as? Map<*, *>
        out += if (state == null) "Tile store move: none on record" else buildString {
            append("Tile store move: ").append(state)
            val method = move?.get("method") as? String
            if (method == "none") append(", nothing to move")
            else if (method != null) {
                append(", by ").append(method)
                val t = if (method == "copy") long(move, "copyMs") else long(move, "renameMs")
                if (t != null) append(" in ").append(t).append(" ms")
            }
            long(move, "files")?.let { append(", ").append(it).append(" files") }
            long(move, "bytes")?.let { append(", ").append(mb(it)) }
            long(move, "at")?.takeIf { it > 0 }?.let { append(", on ").append(utcDay(it)) }
            if (move?.get("thisLaunch") == true) append(", this start")
            (move?.get("renameError") as? String)?.let { append("; the rename was refused: ").append(it.replace('\n', ' ').take(120)) }
            (move?.get("error") as? String)?.let { append("; error: ").append(it.replace('\n', ' ').take(120)) }
        }
        return out
    }

    /** The offline areas by state, `(state, bytes)` each, without their names. */
    fun areaLine(areas: List<Pair<String, Long>>): String {
        if (areas.isEmpty()) return "Offline maps: none"
        val byState = areas.groupBy { it.first }.entries.sortedByDescending { it.value.size }.joinToString(", ") { "${it.value.size} ${it.key}" }
        return "Offline maps: ${areas.size} ($byState), ${mb(areas.sumOf { it.second })}"
    }

    /** The memory sampler's ring (`MemorySampler.lines`): the newest sample's heap figures and the ring's highest. */
    fun memoryLines(samples: List<String>, heapMaxBytes: Long): List<String> {
        val parsed = samples.mapNotNull(DumpFormat::parseSample)
        val out = ArrayList<String>()
        fun kb(v: Long) = mb(v * 1024)
        val last = parsed.lastOrNull()
        out += "Memory, last sample: " + (last?.let { s ->
            listOfNotNull(
                s.number("heapKb")?.let { "Java heap ${kb(it)} of ${mb(heapMaxBytes)}" }, s.number("nativeKb")?.let { "native ${kb(it)}" },
                s.number("pssKb")?.let { "PSS ${kb(it)}" }, s.number("threads")?.let { "$it threads" }, s.number("fds")?.let { "$it open files" },
            ).joinToString(", ")
        } ?: "none yet (the first is taken 15 s after the app starts)")
        if (parsed.size > 1) {
            val peak = listOfNotNull(
                parsed.mapNotNull { it.number("heapKb") }.maxOrNull()?.let { "Java heap ${kb(it)}" },
                parsed.mapNotNull { it.number("nativeKb") }.maxOrNull()?.let { "native ${kb(it)}" },
                parsed.mapNotNull { it.number("pssKb") }.maxOrNull()?.let { "PSS ${kb(it)}" },
            )
            if (peak.isNotEmpty()) out += "Memory, highest of the last ${parsed.size} samples: " + peak.joinToString(", ")
        }
        return out
    }

    /** The report: [history] (`.startup-history`, oldest first), [budget] (null: the asset unreadable) and the phone's lines. */
    fun dump(atMs: Long, system: Map<String, String>, history: List<String>, budget: Budget?, phone: List<String>): MainWatchdog.Dump {
        val figures = figures(history)
        val header = linkedMapOf("kind" to KIND, "atMs" to atMs.toString(), "at" to Report.utc(atMs), "starts" to history.size.toString(), "coldStarts" to figures.size.toString()) + system
        val steps = stepLines(figures, budget).ifEmpty { listOf(NO_STEPS) } + if (budget == null) listOf(NO_BUDGET) else emptyList()
        val sections = listOf(
            stepsTitle(figures.size) to steps,
            S_PHONE to phone,
            S_STARTS to history.map(StartupText::historyWords),
            S_EACH to eachLines(history, budget),
        )
        return MainWatchdog.Dump(MainWatchdog.Dump.MAGIC, header, sections)
    }

    private fun utcDay(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
