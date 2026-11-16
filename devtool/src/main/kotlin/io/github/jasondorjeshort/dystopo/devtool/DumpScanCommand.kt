package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import io.github.jasondorjeshort.dystopo.reports.DumpScan
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * `devtool dump-scan <file or folder>...` (T258): reads diagnostic dumps (`dystopo-dump 1`, Report a problem › Send
 * diagnostics on the phone, `GET /debug/dump` in debug builds; `docs/reference/diagnostic-dump.md`) and prints JSON:
 *
 * - `dumps`: each file read, with its header's version, build, device and Android, its samples, process runs and hours;
 * - `growth`: each figure over every accepted dump's samples together ([DumpScan.growth]: the least-squares slope per
 *   hour pooled within each process run, the peak, the newest value, the hours covered), and `table`, the same as text;
 * - `starts`: the first-frame medians of the starts the dumps list, by version and kind (the same start in two dumps of
 *   one phone counts twice);
 * - `refused`: a dump that still holds a place by the scrubber's rules (a coordinate pair, a keyed latitude or longitude,
 *   a box), with the line numbers and never the lines, and a file that is not a dump; neither is read further. Any
 *   refusal makes the exit status 2, after the JSON.
 *
 * A folder is read for its `*.txt` files whose first line is a dump's, not recursively.
 *
 * T302, `--recent-stats [--budget tools/launch-budget.json]`: adds `recentStats` with `sources`: the dumps given together (their
 * `starts` lines' cold-start figures, pooled) and each Report recent stats text pasted from an issue (a file holding
 * `Dystopo recent stats`, read by its step lines; a folder's such files too), each with its figures' phone median
 * (`phoneMedianMs`, `phoneStarts`) beside the emulator's (`emulatorMedianMs`, the median of the budget's `medianMs`), the
 * ceiling (`ceilingMs`), `overCeiling` and `ratio` (phone over emulator), and `table` as text ([RecentStatsScan]). A report
 * text is refused like a dump when it holds a place.
 */
class DumpScanCommand : CliktCommand(name = "dump-scan") {
    override fun help(context: Context) =
        "T258: reads diagnostic dumps (files or folders) and prints the growth of each memory figure, the starts' first-frame medians, and refuses a dump that holds a place."

    private val paths by argument("PATH", help = "A dump file, or a folder of them").file(mustExist = true).multiple(required = true)
    private val recentStats by option("--recent-stats", help = "T302: also put the phone's recent cold-start figures (the dumps' starts, or a Report recent stats text) beside the emulator's medians and the launch budget's ceilings").flag()
    private val budget by option("--budget", help = "The launch budget --recent-stats reads").file(mustExist = true, canBeDir = false).default(File("tools/launch-budget.json"))

    override fun run() {
        val files = paths.flatMap { p ->
            if (p.isDirectory) p.listFiles { f -> f.isFile && f.name.endsWith(".txt") && (isDump(f) || (recentStats && isStatsReport(f))) }.orEmpty().sortedBy { it.name } else listOf(p)
        }
        val (out, refusedAny) = scan(files, if (recentStats) budget else null)
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(out))))
        if (refusedAny) throw ProgramResult(2)
    }

    private fun isDump(f: File): Boolean = runCatching { f.bufferedReader().use { it.readLine()?.startsWith(DumpFormat.PREFIX) == true } }.getOrDefault(false)

    private fun isStatsReport(f: File): Boolean = runCatching { STATS_MARK in f.readText(Charsets.UTF_8) }.getOrDefault(false)

    companion object {
        /** T302: the first line of a Report recent stats text (`RecentStats` on the phone). */
        const val STATS_MARK = "Dystopo recent stats"

        /** The report for [files] and whether any was refused; [budget]: `--recent-stats` with that launch budget. */
        fun scan(files: List<File>, budget: File? = null): Pair<Map<String, Any?>, Boolean> {
            val dumps = ArrayList<Map<String, Any?>>()
            val refused = ArrayList<Map<String, Any?>>()
            val runs = ArrayList<List<DumpFormat.Sample>>()
            val starts = ArrayList<String>()
            val statsReports = ArrayList<Pair<File, Map<String, Pair<Double, Int>>>>()
            for (f in files) {
                val text = f.readText(Charsets.UTF_8)
                if (budget != null && !text.startsWith(DumpFormat.PREFIX) && STATS_MARK in text) {
                    val places = DumpScan.places(text)
                    if (places.isNotEmpty()) {
                        refused += linkedMapOf("file" to f.path, "reason" to "holds a place (a coordinate or a box) on ${places.size} line(s)", "lines" to places.take(50))
                        continue
                    }
                    statsReports += f to RecentStatsScan.fromReport(text)
                    continue
                }
                val dump = try {
                    DumpFormat.parse(text)
                } catch (e: IllegalArgumentException) {
                    refused += linkedMapOf("file" to f.path, "reason" to (e.message ?: "not a dump"))
                    continue
                }
                val places = DumpScan.places(text)
                if (places.isNotEmpty()) {
                    refused += linkedMapOf("file" to f.path, "reason" to "holds a place (a coordinate or a box) on ${places.size} line(s)", "lines" to places.take(50))
                    continue
                }
                val r = DumpScan.runs(dump.samples)
                runs += r
                starts += dump.section(DumpFormat.S_STARTS)
                val h = dump.header
                dumps += linkedMapOf(
                    "file" to f.path, "format" to dump.format, "at" to h["at"], "version" to h["version"], "build" to h["build"],
                    "device" to h["device"], "android" to h["android"], "samples" to r.sumOf { it.size }, "runs" to r.size,
                    "hours" to round(r.sumOf { run -> spanHours(run) }, 2), "starts" to dump.section(DumpFormat.S_STARTS).size,
                    "logLines" to dump.section(DumpFormat.S_LOGCAT).size, "bytes" to f.length(),
                )
            }
            val growth = DumpScan.growth(runs)
            val startStats = DumpScan.starts(starts)
            val out = linkedMapOf<String, Any?>(
                "files" to files.size, "read" to dumps.size + statsReports.size, "refused" to refused,
                "dumps" to dumps,
                "growth" to growth.map { g ->
                    linkedMapOf("figure" to g.figure, "unit" to g.unit, "slopePerHour" to g.slopePerHour?.let { round(it, 2) }, "peak" to g.peak,
                        "last" to g.last, "hours" to round(g.hours, 2), "runs" to g.runs, "samples" to g.samples)
                },
                "table" to table(growth),
                "starts" to startStats.map { s ->
                    linkedMapOf("version" to s.version, "kind" to s.kind, "starts" to s.starts, "withFirstFrame" to s.withFrame, "firstFrameMedianS" to s.firstFrameMedianS)
                },
            )
            if (budget != null) {
                val b = RecentStatsScan.Budget.parse(budget.readText(Charsets.UTF_8))
                val sources = ArrayList<Map<String, Any?>>()
                if (dumps.isNotEmpty()) sources += RecentStatsScan.json("dumps", dumps.map { it["file"] }, RecentStatsScan.fromStarts(starts), b)
                for ((f, figures) in statsReports) sources += RecentStatsScan.json("report", listOf(f.path), figures, b)
                out["recentStats"] = linkedMapOf("budget" to budget.path, "sources" to sources)
            }
            return out to refused.isNotEmpty()
        }

        private fun spanHours(run: List<DumpFormat.Sample>): Double {
            val ts = run.mapNotNull { it.t }
            return if (ts.size < 2) 0.0 else (ts.max() - ts.min()) / 3_600_000.0
        }

        private fun round(x: Double, digits: Int): Double = DumpScan.fixed(x, digits).toDouble()

        /** The growth as fixed-width lines: figure, slope per hour, peak, newest, hours, runs, samples. */
        fun table(growth: List<DumpScan.Growth>): List<String> {
            val head = listOf("figure", "unit", "slope/h", "peak", "last", "hours", "runs", "samples")
            val rows = growth.map { g ->
                listOf(g.figure, g.unit, g.slopePerHour?.let { DumpScan.fixed(it, 1) } ?: "-", g.peak.toString(), g.last.toString(),
                    DumpScan.fixed(g.hours, 2), g.runs.toString(), g.samples.toString())
            }
            val widths = head.indices.map { i -> (rows.map { it[i].length } + head[i].length).max() }
            return (listOf(head) + rows).map { r -> r.mapIndexed { i, c -> if (i < 2) c.padEnd(widths[i]) else c.padStart(widths[i]) }.joinToString("  ").trimEnd() }
        }
    }
}

/**
 * T302: `dump-scan --recent-stats`, pure: the phone's cold-start figures (from a dump's `starts` lines, the `key=value` pairs
 * after ` | ` that `StartupText.historyLine` writes; or from a Report recent stats text's step lines) beside
 * `tools/launch-budget.json`. Each figure is `key` to (median ms, starts with it).
 */
object RecentStatsScan {
    const val SEP = " | "

    /** The phone's words for a figure (`RecentStats.NAMES` on the phone), back to the budget's keys. */
    val NAMES = linkedMapOf("first frame" to "firstFrameMs", "map drawn" to "fullyLoadedMs", "open-files table" to "fdTableMs", "package read" to "packageInfoMs")

    class Budget(val ceilings: Map<String, Long>, val emulator: Map<String, Double>, val order: List<String>) {
        companion object {
            fun parse(text: String): Budget {
                val o = MiniJson.parseObject(text)
                val ceilings = linkedMapOf<String, Long>()
                val emulator = linkedMapOf<String, Double>()
                val order = ArrayList<String>()
                for (group in listOf("frames", "stages")) {
                    val g = o[group] as? Map<*, *> ?: continue
                    for ((k, v) in g) {
                        val key = k as? String ?: continue
                        val m = v as? Map<*, *> ?: continue
                        (m["ceilingMs"] as? Number)?.let { ceilings[key] = it.toLong() }
                        (m["medianMs"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.let(DumpScan::median)?.let { emulator[key] = it }
                        order += key
                    }
                }
                return Budget(ceilings, emulator, order)
            }
        }
    }

    /** The figures of every `starts` line that has them (a cold start written by T302's build or later), pooled. */
    fun fromStarts(lines: List<String>): Map<String, Pair<Double, Int>> {
        val values = LinkedHashMap<String, MutableList<Double>>()
        for (line in lines) {
            val tail = line.substringAfter(SEP, "")
            for (part in tail.split(' ')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val v = part.substring(eq + 1).toDoubleOrNull()?.takeIf { it >= 0 } ?: continue
                values.getOrPut(part.substring(0, eq)) { ArrayList() } += v
            }
        }
        return values.mapValues { (_, xs) -> DumpScan.median(xs)!! to xs.size }
    }

    private val STEP = Regex("^\\s+(.+?) ([0-9.]+) ms(?: \\((\\d+) of \\d+\\))?(?:, limit \\d+ ms)?(?:, over)?$")
    private val COLD = Regex("the median of (\\d+) recent cold start")

    /** A report's step lines: `  prepare 210 ms, limit 300 ms` (the section after `Start steps, the median of N ...`). */
    fun fromReport(text: String): Map<String, Pair<Double, Int>> {
        val out = LinkedHashMap<String, Pair<Double, Int>>()
        var cold = 0
        var inSteps = false
        for (line in text.lines()) {
            val c = COLD.find(line)
            if (c != null) { cold = c.groupValues[1].toInt(); inSteps = true; continue }
            if (!inSteps) continue
            if (line.isBlank()) break
            val m = STEP.find(line) ?: continue
            val key = NAMES[m.groupValues[1]] ?: m.groupValues[1]
            out[key] = m.groupValues[2].toDouble() to (m.groupValues[3].toIntOrNull() ?: cold)
        }
        return out
    }

    /** One source's figures in the budget's order (then any other), beside the emulator's medians and the ceilings, and as text. */
    fun json(kind: String, files: List<Any?>, figures: Map<String, Pair<Double, Int>>, budget: Budget): Map<String, Any?> {
        val keys = (budget.order + figures.keys).distinct().filter { it in figures }
        val rows = keys.map { k ->
            val (phone, n) = figures.getValue(k)
            val emu = budget.emulator[k]
            val ceiling = budget.ceilings[k]
            linkedMapOf("figure" to k, "phoneMedianMs" to DumpScan.fixed(phone, 1).toDouble(), "phoneStarts" to n,
                "emulatorMedianMs" to emu?.let { DumpScan.fixed(it, 1).toDouble() }, "ceilingMs" to ceiling,
                "overCeiling" to ceiling?.let { phone > it }, "ratio" to emu?.takeIf { it > 0 }?.let { DumpScan.fixed(phone / it, 2).toDouble() })
        }
        return linkedMapOf("kind" to kind, "files" to files, "coldStarts" to figures.values.maxOfOrNull { it.second }, "figures" to rows, "table" to table(rows))
    }

    fun table(rows: List<Map<String, Any?>>): List<String> {
        val head = listOf("figure", "phone", "starts", "emulator", "ceiling", "ratio", "over")
        val cells = rows.map { r ->
            listOf(r["figure"].toString(), r["phoneMedianMs"].toString(), r["phoneStarts"].toString(), r["emulatorMedianMs"]?.toString() ?: "-",
                r["ceilingMs"]?.toString() ?: "-", r["ratio"]?.toString() ?: "-", if (r["overCeiling"] == true) "OVER" else "")
        }
        val widths = head.indices.map { i -> (cells.map { it[i].length } + head[i].length).max() }
        return (listOf(head) + cells).map { r -> r.mapIndexed { i, c -> if (i == 0) c.padEnd(widths[i]) else c.padStart(widths[i]) }.joinToString("  ").trimEnd() }
    }
}
