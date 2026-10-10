package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos

/**
 * Offline route planning over a pack's trail network (T45):
 * - `route --pack out/rainier-outdoors.pmtiles --from 46.7865,-121.7365 --to 46.8025,-121.7290 [--mode hike|bike]
 *   [--osm out/rainier-outdoors.pmtiles] [--gpx out/route.gpx]`: opens `<pack>-graph.idx` next to the pack
 *   (building it first when missing or stale), plans the route, renders it over the DEM pack's terrain
 *   with an elevation profile (`out/route-<pack>.png`) and prints the route as JSON. Exit 1 when no route.
 * - `route build --pack ... [--dem ...]`: (re)builds the graph and reports size, timings and counters.
 *
 * Elevations come from the pack's DEM archive (`<name>-dem.pmtiles` next to it, or `--dem`).
 */
class RouteCommand : CliktCommand(name = "route") {
    init {
        subcommands(RouteBuildCommand())
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Plan a route along the trails of a vector pack (graph built on demand next to the pack), render it and print JSON; `route build` (re)builds the graph."

    private val pack by option("--pack", help = "Vector PMTiles pack (outdoors or OpenMapTiles schema)").file(mustExist = true, canBeDir = false)
    private val dem by option("--dem", help = "DEM pack for elevation and the render (default: <name>-dem.pmtiles next to --pack)").file(mustExist = true, canBeDir = false)
    private val from by option("--from", help = "Start lat,lon")
    private val to by option("--to", help = "End lat,lon")
    private val mode by option("--mode", help = "hike (default) or bike").choice("hike", "bike").default("hike")
    private val snap by option("--snap", help = "How far (m) a point may be from the nearest trail or road").double().default(Router.DEFAULT_SNAP_M)
    private val name by option("--name", help = "Route name").default("Route")
    private val graphFile by option("--graph", help = "Graph file (default: <pack name>-graph.idx next to the pack)").file()
    private val gpx by option("--gpx", help = "Write the route as GPX here").file()
    private val noRender by option("--no-render", help = "Skip the PNG").flag()
    private val zoomOption by option("--zoom", help = "Render zoom (default: the deepest up to 15 that fits about 12 Mpx)").int().restrictTo(8..16)
    private val style by StyleOptions()
    private val out by option("--out", help = "Output PNG (default out/route-<pack>.png)").file()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val packFile = pack ?: throw UsageError("--pack is required")
        val (fromLat, fromLon) = latLon(from ?: throw UsageError("--from lat,lon is required"), "--from")
        val (toLat, toLon) = latLon(to ?: throw UsageError("--to lat,lon is required"), "--to")
        val timings = Timings()
        val demFile = dem ?: RouteGraphs.defaultDem(packFile)
        val (graph, info) = timings.time("graph") { RouteGraphs.openOrBuild(packFile, demFile, graphFile) }
        val router = Router(graph)
        timings.time("derive") { graph.adjStart; graph.component; graph.nearest(fromLat, fromLon, 1.0) }
        val result = timings.time("route") { router.route(fromLat, fromLon, toLat, toLon, TravelMode.of(mode)!!, snap, name) }
        val report = linkedMapOf<String, Any?>("pack" to packFile.path, "dem" to demFile?.path, "graph" to info + ("summary" to graph.json()))
        when (result) {
            is RouteResult.Failed -> {
                report["route"] = null
                report["failure"] = result.json()
                report["timingsMs"] = timings.ms
                echo(pretty(report))
                throw ProgramResult(1)
            }
            is RouteResult.Found -> {
                val plan = result.plan
                report["route"] = plan.json()
                // Drawn as a track: a solid line with direction chevrons, start ring, end square and the elevation profile.
                val asTrack = Track(plan.route.id, plan.route.name, listOf(TrackSegment(plan.route.points.map { TrackPoint(it.lat, it.lon, it.ele) })))
                val doc = GpxDocument(tracks = listOf(asTrack))
                gpx?.let { f ->
                    f.absoluteFile.parentFile?.mkdirs()
                    f.writeText(Gpx.write(GpxDocument(routes = listOf(plan.route))), Charsets.UTF_8)
                    report["gpx"] = f.path
                }
                if (!noRender) report["render"] = render(doc, demFile, packFile, timings)
                report["timingsMs"] = timings.ms
                echo(pretty(report))
            }
        }
    }

    private fun render(doc: GpxDocument, demFile: File?, packFile: File, timings: Timings): Map<String, Any?> {
        if (demFile == null) return mapOf("skipped" to "no DEM pack (pass --dem)")
        val fit = TrackDrawing.fitBounds(doc.tracks, emptyList(), emptyList(), 0.15) ?: return mapOf("skipped" to "empty route")
        // At least 3:4 either way, so a route running north-south still shows the country beside it.
        val k = cos((fit.south + fit.north) / 2 * PI / 180)
        val wM = (fit.east - fit.west) * k
        val hM = fit.north - fit.south
        val box = when {
            wM < 0.75 * hM -> {
                val pad = (0.75 * hM / k - (fit.east - fit.west)) / 2
                BoundingBox(fit.west - pad, fit.south, fit.east + pad, fit.north)
            }
            hM < 0.75 * wM -> {
                val pad = (0.75 * wM - hM) / 2
                BoundingBox(fit.west, fit.south - pad, fit.east, fit.north + pad)
            }
            else -> fit
        }
        val zoom = zoomOption ?: GpxCommand.fitZoom(box, maxZoom = 15)
        val window = MercatorWindow.forBbox(box, zoom)
        val pyramid = RouteGraphs.pyramid(demFile)
        val grid = timings.time("demWindow") { pyramid.window(zoom, window.x0, window.y0, window.width, window.height) }
        val (map, _, _, osm) = MapRenderer.render(grid, zoom, style, timings, window = window)
        val overlay = GpxOverlay(window, style.textScale, 110.0)
        val image = timings.time("drawRoute") { overlay.draw(map, doc, 150) }
        val outFile = out ?: File("out/route-${PackNames.parse(packFile.name)?.pack ?: packFile.nameWithoutExtension}.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile?.mkdirs()
            ImageIO.write(image, "png", outFile)
        }
        return linkedMapOf(
            "output" to outFile.path, "zoom" to zoom, "widthPx" to image.width, "heightPx" to image.height,
            "bbox" to listOf(box.west, box.south, box.east, box.north), "osmTiles" to osm?.tiles,
            "routePoints" to overlay.stats.trackPoints, "profileSamples" to overlay.stats.profileSamples, "pointsOutside" to overlay.stats.pointsOutside,
        )
    }

    private fun latLon(s: String, opt: String): Pair<Double, Double> {
        val parts = s.split(',').map { it.trim().toDoubleOrNull() }
        val lat = parts.getOrNull(0)
        val lon = parts.getOrNull(1)
        if (parts.size != 2 || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) throw UsageError("$opt must be lat,lon in degrees")
        return lat to lon
    }
}

class RouteBuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build (or rebuild) a pack's routing graph and report size, timings and counters."

    private val pack by option("--pack", help = "Vector PMTiles pack").file(mustExist = true, canBeDir = false).required()
    private val dem by option("--dem", help = "DEM pack (default: <name>-dem.pmtiles next to --pack; none: no elevation)").file(mustExist = true, canBeDir = false)
    private val out by option("--out", help = "Graph file (default: <pack name>-graph.idx next to the pack)").file()

    override fun run() {
        val (graph, info) = RouteGraphs.build(pack, dem ?: RouteGraphs.defaultDem(pack), out ?: RouteGraphs.defaultFile(pack))
        echo(pretty(info + ("summary" to graph.json()) + ("networks" to RouteGraphs.networks(graph, 6))))
    }
}

internal object RouteGraphs {
    fun defaultFile(pack: File): File = File(pack.absoluteFile.parentFile, RoutingGraph.fileNameFor(pack.name))

    fun defaultDem(pack: File): File? {
        val name = PackNames.parse(pack.name)?.pack ?: return null
        return File(pack.absoluteFile.parentFile, PackNames.fileName(name, PackRole.DEM)).takeIf { it.isFile }
    }

    /** The [n] largest connected networks: km of line and one node in each (a point to route to or from). */
    fun networks(g: RoutingGraph, n: Int): List<Map<String, Any?>> {
        val sample = HashMap<Int, Int>()
        for (v in 0 until g.nodeCount) sample.putIfAbsent(g.component[v], v)
        return g.componentLengthM.withIndex().sortedByDescending { it.value }.take(n).map { (c, m) ->
            val v = sample.getValue(c)
            linkedMapOf("network" to c, "km" to Router.km(m), "node" to listOf(g.nodeLat[v] / 1e6, g.nodeLon[v] / 1e6))
        }
    }

    fun reader(file: File): PmtilesReader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)

    private val decoder = demDecoder

    fun pyramid(dem: File, cacheTiles: Int = 256): DemPyramid {
        val r = reader(dem)
        val h = r.header
        return DemPyramid(h.minZoom, h.maxZoom, decoder, cacheTiles) { z, x, y -> synchronized(r) { r.tile(z, x, y) } }
    }

    fun demFingerprint(dem: File?): String = dem?.let { RoutingGraphBuilder.fingerprint(reader(it).header) }.orEmpty()

    /** Builds the graph for [pack] (elevation from [dem]) into [out]; returns it with a report. */
    fun build(pack: File, dem: File?, out: File): Pair<RoutingGraph, Map<String, Any?>> {
        try {
            val t0 = System.nanoTime()
            // A small cache, as on the phone: the builder samples in tile order.
            val pyramid = dem?.let { pyramid(it, cacheTiles = 32) }
            val sampler = pyramid?.let { DemElevation(it) }
            val builder = RoutingGraphBuilder(sampler, demFingerprint(dem)).withClock { System.nanoTime() / 1_000_000 }
            val graph = builder.build(reader(pack), pack.name, System.currentTimeMillis())
            val t1 = System.nanoTime()
            val bytes = graph.encode()
            out.absoluteFile.parentFile?.mkdirs()
            out.writeBytes(bytes)
            val t2 = System.nanoTime()
            return graph to linkedMapOf(
                "pack" to pack.path, "dem" to dem?.path, "file" to out.path, "action" to "built", "bytes" to bytes.size,
                "buildMs" to (t1 - t0) / 1_000_000, "writeMs" to (t2 - t1) / 1_000_000,
                "demTilesDecoded" to pyramid?.tilesDecoded, "elevationZoom" to sampler?.zoom,
                "stats" to builder.stats!!.json(),
            )
        } catch (e: IllegalArgumentException) {
            throw CliktError("routing graph build failed for ${pack.path}: ${e.message}", statusCode = 1)
        }
    }

    /** Opens the graph next to [pack] (or [file]); builds it first when missing, unreadable or stale. */
    fun openOrBuild(pack: File, dem: File?, file: File?): Pair<RoutingGraph, Map<String, Any?>> {
        val f = file ?: defaultFile(pack)
        val fingerprint = RoutingGraphBuilder.fingerprint(reader(pack).header)
        val demFp = demFingerprint(dem)
        var reason = "missing"
        if (f.isFile) {
            val t0 = System.nanoTime()
            val g = runCatching { RoutingGraph.decode(f.readBytes()) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            g.onSuccess {
                if (it.source.fingerprint == fingerprint && it.source.demFingerprint == demFp) {
                    return it to linkedMapOf("file" to f.path, "action" to "loaded", "bytes" to f.length(), "loadMs" to ms)
                }
            }
            reason = g.exceptionOrNull()?.message ?: "stale (pack changed)"
        }
        val (g, info) = build(pack, dem, f)
        return g to (info + ("rebuildReason" to reason))
    }
}

private fun pretty(value: Any?): String = reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(value)))
