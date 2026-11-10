package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
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
 */
class DumpScanCommand : CliktCommand(name = "dump-scan") {
    override fun help(context: Context) =
        "T258: reads diagnostic dumps (files or folders) and prints the growth of each memory figure, the starts' first-frame medians, and refuses a dump that holds a place."

    private val paths by argument("PATH", help = "A dump file, or a folder of them").file(mustExist = true).multiple(required = true)

    override fun run() {
        val files = paths.flatMap { p ->
            if (p.isDirectory) p.listFiles { f -> f.isFile && f.name.endsWith(".txt") && isDump(f) }.orEmpty().sortedBy { it.name } else listOf(p)
        }
        val (out, refusedAny) = scan(files)
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(out))))
        if (refusedAny) throw ProgramResult(2)
    }

    private fun isDump(f: File): Boolean = runCatching { f.bufferedReader().use { it.readLine()?.startsWith(DumpFormat.PREFIX) == true } }.getOrDefault(false)

    companion object {
        /** The report for [files] and whether any was refused. */
        fun scan(files: List<File>): Pair<Map<String, Any?>, Boolean> {
            val dumps = ArrayList<Map<String, Any?>>()
            val refused = ArrayList<Map<String, Any?>>()
            val runs = ArrayList<List<DumpFormat.Sample>>()
            val starts = ArrayList<String>()
            for (f in files) {
                val text = f.readText(Charsets.UTF_8)
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
                "files" to files.size, "read" to dumps.size, "refused" to refused,
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
