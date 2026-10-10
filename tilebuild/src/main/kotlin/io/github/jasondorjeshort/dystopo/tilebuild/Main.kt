package io.github.jasondorjeshort.dystopo.tilebuild

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Pretty JSON for the report printed to stdout. Planetiler's own logging goes to stderr (see [Logging]). */
val reportJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Where downloaded data is cached: `cache/` under the working directory, or `$DYSTOPO_CACHE` so
 * checkouts and worktrees share one copy of the OSM extracts and Planetiler's auxiliary data.
 */
object Caches {
    val root: File = File(System.getenv("DYSTOPO_CACHE")?.takeIf { it.isNotBlank() } ?: "cache")
}

class TileBuildCommand : CliktCommand(name = "tilebuild") {
    override fun help(context: Context) = """
        Build an OpenMapTiles-schema PMTiles archive from an OpenStreetMap extract with Planetiler.

        --extract is a Geofabrik region name ("liechtenstein", "washington"), a full Geofabrik path
        ("north-america/us/washington") or a local .osm.pbf file. Extracts are downloaded into
        <cache>/osm/; Planetiler's Natural Earth, water polygon and lake centerline data into
        <cache>/planetiler/ (several hundred MB, once). Prints one JSON report to stdout.
    """.trimIndent()

    private val extract by option("--extract", help = "Geofabrik region name or path, or a local .osm.pbf").required()
    private val out by option("--out", help = "Output .pmtiles file (overwritten)").file(canBeDir = false).required()
    private val bbox by option("--bbox", help = "Clip to west,south,east,north (degrees)")
    private val area by option("--area", help = "Clip to a named area from ${Areas.PUBLIC_FILE}")
    private val minZoom by option("--min-zoom", help = "Minimum zoom (default 0)").int().default(0)
    private val maxZoom by option("--max-zoom", help = "Maximum zoom (default 14, max 15)").int().default(14)
    private val threads by option("--threads", help = "Worker threads (default: all cores)").int()
    private val quiet by option("--quiet", help = "Only warnings and errors from Planetiler on stderr").flag()

    override fun run() {
        Logging.routeToStderr(quiet)
        val (areaName, clip) = when {
            bbox != null && area != null -> throw UsageError("use --area or --bbox, not both")
            area != null -> area to Areas.bbox(area!!)
            bbox != null -> null to Areas.parseBbox(bbox!!)
            else -> null to null
        }
        if (minZoom < 0 || maxZoom > 15 || minZoom > maxZoom) throw UsageError("zoom range must satisfy 0 <= min <= max <= 15")
        val input = Extracts.resolve(extract, File(Caches.root, "osm"))
        val request = BuildRequest(
            input = input,
            out = out,
            clipArea = areaName,
            bbox = clip,
            minZoom = minZoom,
            maxZoom = maxZoom,
            threads = threads,
            cacheRoot = Caches.root,
        )
        val report = TileBuild.run(request)
        echo(reportJson.encodeToString(report))
    }
}

fun main(args: Array<String>) = TileBuildCommand().main(args)
