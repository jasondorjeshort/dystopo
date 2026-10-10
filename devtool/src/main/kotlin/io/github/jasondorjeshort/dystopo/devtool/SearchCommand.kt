package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.search.PlaceKind
import io.github.jasondorjeshort.dystopo.search.SearchIndex
import io.github.jasondorjeshort.dystopo.search.SearchIndexBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Offline place search over a vector pack (T39):
 * - `search --pack out/rainier-outdoors.pmtiles --query para [--lat --lon] [--limit] [--kind peak,hut]`:
 *   opens `<pack>-search.idx` next to the pack, building it first when missing or stale, and prints
 *   the hits as JSON.
 * - `search build --pack ... [--out file]`: (re)builds the index and reports size, timings and counts.
 */
class SearchCommand : CliktCommand(name = "search") {
    init {
        subcommands(SearchBuildCommand())
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Offline place search: query a pack's search index (built on demand), or `search build` to (re)build it."

    private val pack by option("--pack", help = "Vector PMTiles pack (outdoors or OpenMapTiles schema)").file(mustExist = true, canBeDir = false)
    private val query by option("--query", "-q", help = "Search text (prefix match, accents ignored)")
    private val lat by option("--lat", help = "Search centre latitude (ranks nearer places higher); default: pack centre").double()
    private val lon by option("--lon", help = "Search centre longitude").double()
    private val limit by option("--limit", help = "Maximum hits").int().default(10)
    private val kind by option("--kind", help = "Only these kinds, comma-separated (${PlaceKind.entries.joinToString(",") { it.label }})")
    private val index by option("--index", help = "Index file (default: <pack name>-search.idx next to the pack)").file()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val packFile = pack ?: throw UsageError("--pack is required")
        val q = query ?: throw UsageError("--query is required (or use `search build`)")
        val kinds = kind?.split(',')?.map { k -> PlaceKind.ofLabel(k.trim()) ?: throw UsageError("unknown kind '$k'") }?.toSet()
        val (idx, info) = SearchIndexes.openOrBuild(packFile, index)
        val reader = SearchIndexes.reader(packFile)
        val cLat = lat ?: (reader.header.minLat + reader.header.maxLat) / 2
        val cLon = lon ?: (reader.header.minLon + reader.header.maxLon) / 2
        val t0 = System.nanoTime()
        val hits = idx.search(q, cLat, cLon, limit, kinds)
        val queryMs = (System.nanoTime() - t0) / 1e6
        echo(pretty(linkedMapOf(
            "query" to q, "center" to listOf(cLat, cLon), "queryMs" to Math.round(queryMs * 100) / 100.0,
            "index" to info, "count" to hits.size, "hits" to hits.map { it.json() },
        )))
    }
}

class SearchBuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build (or rebuild) a pack's place-search index and report size, timings and counts."

    private val pack by option("--pack", help = "Vector PMTiles pack").file(mustExist = true, canBeDir = false).required()
    private val out by option("--out", help = "Index file (default: <pack name>-search.idx next to the pack)").file()
    private val quiet by option("--no-entries", help = "Leave the top entries out of the report").flag()

    override fun run() {
        val (idx, info) = SearchIndexes.build(pack, out ?: SearchIndexes.defaultFile(pack))
        val report = LinkedHashMap<String, Any?>(info)
        report["index"] = idx.json()
        if (!quiet) report["topEntries"] = idx.entries.take(15).map { it.json() }
        echo(pretty(report))
    }
}

internal object SearchIndexes {
    fun defaultFile(pack: File): File = File(pack.absoluteFile.parentFile, SearchIndex.fileNameFor(pack.name))

    fun reader(pack: File): PmtilesReader = PmtilesReader.open(FileRangeSource(pack), Gzip.decompressors)

    /** Builds the index for [pack] into [out]; returns it with a report (bytes, timings, counters). */
    fun build(pack: File, out: File): Pair<SearchIndex, Map<String, Any?>> {
        try {
            val t0 = System.nanoTime()
            val builder = SearchIndexBuilder(reader(pack), pack.name)
            val idx = builder.build(System.currentTimeMillis())
            val t1 = System.nanoTime()
            val bytes = idx.encode()
            out.absoluteFile.parentFile?.mkdirs()
            out.writeBytes(bytes)
            val t2 = System.nanoTime()
            val s = builder.stats!!
            return idx to linkedMapOf(
                "pack" to pack.path, "file" to out.path, "action" to "built", "bytes" to bytes.size,
                "buildMs" to (t1 - t0) / 1_000_000, "writeMs" to (t2 - t1) / 1_000_000,
                "zooms" to s.zooms, "tiles" to s.tiles, "tileErrors" to s.tileErrors, "features" to s.features,
                "occurrences" to s.occurrences, "entries" to s.entries, "tokens" to s.tokens,
            )
        } catch (e: IllegalArgumentException) {
            throw CliktError("search index build failed for ${pack.path}: ${e.message}", statusCode = 1)
        }
    }

    /** Opens the index next to [pack] (or [file]); builds it first when it is missing, unreadable or stale. */
    fun openOrBuild(pack: File, file: File?): Pair<SearchIndex, Map<String, Any?>> {
        val f = file ?: defaultFile(pack)
        val fingerprint = SearchIndexBuilder.fingerprint(reader(pack).header)
        var reason = "missing"
        if (f.isFile) {
            val t0 = System.nanoTime()
            val idx = runCatching { SearchIndex.decode(f.readBytes()) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            idx.onSuccess { if (it.source.fingerprint == fingerprint) return it to linkedMapOf("file" to f.path, "action" to "loaded", "bytes" to f.length(), "loadMs" to ms, "entries" to it.entries.size) }
            reason = idx.exceptionOrNull()?.message ?: "stale (pack changed)"
        }
        val (idx, info) = build(pack, f)
        return idx to (info + ("rebuildReason" to reason))
    }
}

private fun pretty(value: Any?): String = reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(value)))
