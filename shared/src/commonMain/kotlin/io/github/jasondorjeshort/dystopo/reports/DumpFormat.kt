package io.github.jasondorjeshort.dystopo.reports

import kotlin.math.abs
import kotlin.math.max

/**
 * T258: the diagnostic dump's text format, `dystopo-dump 1` (the phone writes it from Report a problem › Send
 * diagnostics; `devtool dump-scan` reads it; documented in `docs/reference/diagnostic-dump.md`). Pure.
 *
 * ```
 * dystopo-dump 1          the format and its version; a reader refuses another major version
 * kind: diagnostics       header lines `key: value` up to the first empty line
 * atMs: 1762780000000
 * ...
 *
 * == samples              a section: `== <name>`, then its lines as they are, up to the next empty line
 * s t=1762779940000 up=3600 pid=4242 heapKb=45120 ...
 * ```
 * A section line is never empty: [format] writes an empty line, and one that begins with a blank or with `== `, with a
 * leading blank, which [parse] removes. A
 * reader skips header keys, sections and sample keys it does not know.
 *
 * A sample line ([sampleLine], [parseSample]) is `s` and then `key=value` pairs, the values integers (memory in kB) or a
 * word (`rec=idle`): never a decimal, so the report scrubber leaves every sample as it is.
 */
object DumpFormat {
    const val MAGIC = "dystopo-dump 1"
    const val PREFIX = "dystopo-dump "
    private const val SECTION = "== "

    const val S_SAMPLES = "samples"
    const val S_STARTS = "starts"
    const val S_DIAGNOSTICS = "diagnostics"
    const val S_LOGCAT = "logcat"

    /** A parsed dump: its first line, the header, the sections in order. */
    data class Dump(val format: String, val header: Map<String, String>, val sections: List<Pair<String, List<String>>>) {
        fun section(name: String): List<String> = sections.firstOrNull { it.first == name }?.second.orEmpty()
        val samples: List<Sample> get() = section(S_SAMPLES).mapNotNull(::parseSample)
    }

    fun format(header: Map<String, String>, sections: List<Pair<String, List<String>>>): String = buildString {
        append(MAGIC).append('\n')
        for ((k, v) in header) append(k).append(": ").append(v.replace('\n', ' ')).append('\n')
        for ((name, lines) in sections) {
            append('\n').append(SECTION).append(name).append('\n')
            for (raw in lines) {
                val line = raw.replace("\r", "").replace('\n', ' ')
                // A line that would read as an end, a section or an escape gets a leading blank, which the reader removes.
                if (line.isEmpty() || line.startsWith(' ') || line.startsWith(SECTION)) append(' ')
                append(line).append('\n')
            }
        }
    }

    /** The dump in [text]; throws [IllegalArgumentException] for a file that is not one, or of another major version. */
    fun parse(text: String): Dump {
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val first = lines.firstOrNull().orEmpty()
        require(first.startsWith(PREFIX)) { "not a diagnostic dump" }
        require(first.removePrefix(PREFIX).substringBefore('.').trim() == "1") { "a dump of format ${first.removePrefix(PREFIX)}; this reader knows 1" }
        val header = LinkedHashMap<String, String>()
        var i = 1
        while (i < lines.size && lines[i].isNotEmpty()) {
            val c = lines[i].indexOf(": ")
            if (c > 0) header[lines[i].substring(0, c)] = lines[i].substring(c + 2)
            i++
        }
        val sections = ArrayList<Pair<String, List<String>>>()
        var name: String? = null
        var body = ArrayList<String>()
        for (line in lines.drop(i)) {
            when {
                line.isEmpty() -> { name?.let { sections += it to body }; name = null; body = ArrayList() }
                line.startsWith(SECTION) && name == null -> name = line.removePrefix(SECTION)
                name != null -> body += if (line.startsWith(' ')) line.substring(1) else line
            }
        }
        name?.let { sections += it to body }
        return Dump(first, header, sections)
    }

    /** One sample: its keys and values as written. */
    data class Sample(val values: Map<String, String>) {
        /** Epoch ms of the sample. */
        val t: Long? get() = values["t"]?.toLongOrNull()
        /** Seconds since the process started. */
        val up: Long? get() = values["up"]?.toLongOrNull()
        val pid: String? get() = values["pid"]
        fun number(key: String): Long? = values[key]?.toLongOrNull()
    }

    /** `s k=v k=v ...`; a value's blanks and `=` become `_`. */
    fun sampleLine(values: Map<String, Any?>): String = buildString {
        append('s')
        for ((k, v) in values) {
            if (v == null) continue
            append(' ').append(k).append('=').append(v.toString().replace(Regex("[\\s=]+"), "_").ifEmpty { "-" })
        }
    }

    fun parseSample(line: String): Sample? {
        if (!line.startsWith("s ")) return null
        val values = LinkedHashMap<String, String>()
        for (part in line.substring(2).split(' ')) {
            val eq = part.indexOf('=')
            if (eq > 0) values[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return Sample(values).takeIf { it.t != null }
    }

    /**
     * The sample keys and their units, in the order the phone writes them (`docs/reference/diagnostic-dump.md`). `t`,
     * `up`, `pid`, `fg`, `rec` and `cpuUs` are context, not figures whose growth is read.
     */
    val FIGURES: Map<String, String> = linkedMapOf(
        "heapKb" to "kB", "nativeKb" to "kB", "pssKb" to "kB", "threads" to "count", "fds" to "count",
        "demServed" to "tiles", "osmServed" to "tiles", "contourServed" to "tiles", "tileKb" to "kB", "tileErrors" to "count",
        "cacheRuns" to "count", "trims" to "count",
    )

    /** Keys that are context, not figures. */
    val CONTEXT = setOf("t", "up", "pid", "fg", "rec", "cpuUs")
}

/**
 * T258: what `devtool dump-scan` reads from dumps, pure: the growth of each figure ([growth]), the starts' first-frame
 * medians ([starts]) and the refusal of a dump that still holds a place ([places]).
 */
object DumpScan {
    /**
     * One figure's growth over the dumps' samples: [slopePerHour] is the least-squares slope pooled within each process
     * run (a run ends where `pid` changes or `up` goes back: a new process starts its heap again), so a restart is never
     * read as a fall nor a start as a rise; null with under two samples in every run. [hours] is the runs' spans summed.
     */
    data class Growth(
        val figure: String, val unit: String, val slopePerHour: Double?, val peak: Long, val last: Long,
        val hours: Double, val runs: Int, val samples: Int,
    )

    /** The samples split into process runs, each in time order. */
    fun runs(samples: List<DumpFormat.Sample>): List<List<DumpFormat.Sample>> {
        val out = ArrayList<List<DumpFormat.Sample>>()
        var cur = ArrayList<DumpFormat.Sample>()
        for (s in samples.sortedBy { it.t }) {
            val prev = cur.lastOrNull()
            val newRun = prev != null && (prev.pid != s.pid || (s.up != null && prev.up != null && s.up!! < prev.up!!))
            if (newRun) { out += cur; cur = ArrayList() }
            cur += s
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Each figure of [DumpFormat.FIGURES] (and any other numeric key a newer phone writes) over [runs]. */
    fun growth(runs: List<List<DumpFormat.Sample>>): List<Growth> {
        val keys = LinkedHashSet<String>(DumpFormat.FIGURES.keys)
        for (r in runs) for (s in r) for ((k, v) in s.values) if (k !in DumpFormat.CONTEXT && v.toLongOrNull() != null) keys += k
        val newest = runs.flatten().maxByOrNull { it.t ?: 0L }
        return keys.mapNotNull { key ->
            var sxy = 0.0
            var sxx = 0.0
            var n = 0
            var peak = Long.MIN_VALUE
            var hours = 0.0
            var withData = 0
            for (r in runs) {
                val pts = r.mapNotNull { s -> s.number(key)?.let { v -> s.t?.let { t -> t / 3_600_000.0 to v.toDouble() } } }
                if (pts.isEmpty()) continue
                withData++
                n += pts.size
                for (p in pts) peak = max(peak, p.second.toLong())
                if (pts.size < 2) continue
                hours += pts.last().first - pts.first().first
                val tm = pts.sumOf { it.first } / pts.size
                val ym = pts.sumOf { it.second } / pts.size
                for ((t, y) in pts) { sxy += (t - tm) * (y - ym); sxx += (t - tm) * (t - tm) }
            }
            if (n == 0) return@mapNotNull null
            Growth(key, DumpFormat.FIGURES[key] ?: "", if (sxx > 0) sxy / sxx else null, peak, newest?.number(key) ?: peak, hours, withData, n)
        }
    }

    /** First-frame times of the starts with one (`version`, `kind`, its starts, the median in seconds). */
    data class Starts(val version: String, val kind: String, val starts: Int, val withFrame: Int, val firstFrameMedianS: Double?)

    private val START = Regex("^(\\S+) (\\S+), first frame (none|[0-9.]+ s), longest stall ([0-9.]+) s, (\\S+)")

    /** The `starts` lines (`StartupText.historyLine`: `0.1.24-alpha cold, first frame 1.23 s, longest stall 0.40 s, finished`) by version and kind. */
    fun starts(lines: List<String>): List<Starts> {
        val parsed = lines.mapNotNull { START.find(it.trim()) }.map { m ->
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3].takeIf { it != "none" }?.removeSuffix(" s")?.toDoubleOrNull())
        }
        return parsed.groupBy { it.first to it.second }.map { (k, v) ->
            val frames = v.mapNotNull { it.third }
            Starts(k.first, k.second, v.size, frames.size, median(frames))
        }.sortedWith(compareBy({ it.version }, { it.kind }))
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * The 1-based numbers of [text]'s lines that still hold a place by the report scrubber's rules: a coordinate pair, a
     * keyed latitude or longitude, a value in degrees, a lone decimal of a metre's precision, a box. A dump with any is
     * refused: the phone scrubs every line before writing it, so one here is a defect, and the dumps of several testers
     * are read together only without a place.
     */
    fun places(text: String): List<Int> {
        val prepared = Scrub.prepare()
        return text.split('\n').withIndex().filter { (_, line) ->
            val r = Scrub.scrub(line, prepared).replaced
            PLACE_RULES.any { (r[it] ?: 0) > 0 }
        }.map { it.index + 1 }
    }

    private val PLACE_RULES = listOf("coords", "coord", "bbox")

    /** [x] with [digits] decimals, for a table cell. */
    fun fixed(x: Double, digits: Int): String {
        var p = 1.0
        repeat(digits) { p *= 10 }
        val r = kotlin.math.round(abs(x) * p).toLong()
        val whole = r / p.toLong()
        val frac = (r % p.toLong()).toString().padStart(digits, '0')
        return (if (x < 0 && r != 0L) "-" else "") + whole + if (digits > 0) ".$frac" else ""
    }
}
