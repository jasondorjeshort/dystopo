package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.dem.FlowGrid
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.WaterAlong
import io.github.jasondorjeshort.dystopo.routing.WaterClass
import io.github.jasondorjeshort.dystopo.routing.WaterItem
import io.github.jasondorjeshort.dystopo.routing.WaterKind
import io.github.jasondorjeshort.dystopo.routing.WaterParams
import io.github.jasondorjeshort.dystopo.routing.WaterTiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * T179: water along a route ([WaterAlong]) and the truth table of its terrain classes against USGS NHD flow codes.
 *
 * - `water --gpx <route> --pack <outdoors pack> [--truth <csv>]`: the crossings, springs, taps and lakes along the
 *   route as JSON (with the same run without the `drainage` layer, so the layer's addition is counted), drawn as
 *   numbered markers over the terrain with the mapped and derived lines to `out/water-<gpx name>.png`; with
 *   `--truth`, each stream crossing is matched to the nearest NHD point within `--snap-m` and tabulated.
 * - `water --truth <csv> --area <name> [--pack <outdoors pack>]`: the area table: every NHD point
 *   (`tools/nhd-flowlines.py`) against the terrain class of the largest catchment within `--snap-m` on one z13 flow
 *   grid of the area plus `--margin-km` ([FlowGrid], as `drainage-pack` builds it), with a sweep of the class
 *   thresholds and the catchment percentiles per flow code; with `--pack`, the class the mapped `waterway` tags
 *   give the same points (the nearest line within `--snap-m`).
 */
class WaterCommand : CliktCommand(name = "water") {
    override fun help(context: Context) =
        "Water along a route (stream crossings classed from the map and the terrain, springs, taps, lakes) as JSON and a PNG; " +
            "with --truth, the terrain classes against USGS NHD flow codes (per route, or over an --area)."

    private val gpxFile by option("--gpx", help = "Route as GPX (its tracks, else its routes)").file(mustExist = true, canBeDir = false)
    private val pack by option("--pack", help = "Outdoors vector pack (with a `drainage` layer when drainage-pack wrote one)").file(mustExist = true, canBeDir = false)
    private val dem by option("--dem", help = "DEM pack for the PNG (default: <name>-dem.pmtiles next to --pack)").file(mustExist = true, canBeDir = false)
    private val truth by option("--truth", help = "CSV of NHD flowline points from tools/nhd-flowlines.py").file(mustExist = true, canBeDir = false)
    private val area by option("--area", help = "Named area for the area truth table (with --truth, without --gpx)")
    private val bbox by option("--bbox", help = "west,south,east,north for the area truth table instead of --area")
    private val source by option("--source", help = "Elevation source of the area table's flow grid").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val marginKm by option("--margin-km", help = "Flow grid margin around the area, so catchments are whole").double().default(3.0)
    private val snapM by option("--snap-m", help = "How far a truth point may be from a crossing, or a catchment cell from a truth point, metres").double().default(30.0)
    private val dryBelow by option("--dry-below", help = "Gully under this catchment, km2").double().default(WaterParams().dryBelowKm2)
    private val flowingFrom by option("--flowing-from", help = "Likely flowing from this catchment, km2").double().default(WaterParams().flowingFromKm2)
    private val dropBelow by option("--drop-below", help = "Derived crossings under this catchment are left out, km2").double().default(WaterParams().dropBelowKm2)
    private val noRender by option("--no-render", help = "Skip the PNG").flag()
    private val out by option("--out", help = "Output PNG (default out/water-<gpx name>[-<pack>].png)").file()
    private val style by StyleOptions()

    private val params by lazy { WaterParams(dryBelowKm2 = dryBelow, flowingFromKm2 = flowingFrom, dropBelowKm2 = dropBelow) }

    override fun run() {
        val report = when {
            gpxFile != null -> routeReport()
            truth != null && (area != null || bbox != null) -> areaReport()
            else -> throw UsageError("give --gpx with --pack, or --truth with --area (or --bbox)")
        }
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    // ---- the route ----

    private fun routeReport(): Map<String, Any?> {
        val packFile = pack ?: throw UsageError("--pack is required with --gpx")
        val timings = Timings()
        val doc = Gpx.parse(gpxFile!!.readText(Charsets.UTF_8))
        val points = doc.tracks.flatMap { t -> t.segments.flatMap { s -> s.points.map { RoutePoint(it.lat, it.lon, it.ele) } } }
            .ifEmpty { doc.routes.flatMap { it.points } }
        if (points.size < 2) throw UsageError("the GPX has no track or route with two points")
        val reader = PmtilesReader.open(FileRangeSource(packFile), Gzip.decompressors)
        val zoom = min(reader.header.maxZoom, 14)
        val tiles = WaterTiles { z, x, y -> synchronized(reader) { reader.tile(z, x, y) } }
        val result = timings.time("find") { WaterAlong.find(points, tiles, zoom, params) }
        val mappedOnly = timings.time("findWithoutDrainage") { WaterAlong.find(points, tiles, zoom, params, drainage = null) }
        val lengthM = (1 until points.size).sumOf { TrackStats.distanceM(points[it - 1].lat, points[it - 1].lon, points[it].lat, points[it].lon) }
        val items = result.items
        val report = linkedMapOf<String, Any?>(
            "gpx" to gpxFile!!.path, "pack" to packFile.path, "routeM" to round1(lengthM), "routePoints" to points.size,
            "params" to paramsJson(), "stats" to result.stats.json(),
            "counts" to counts(items),
            "withoutDrainage" to linkedMapOf("counts" to counts(mappedOnly.items), "streams" to mappedOnly.items.count { it.kind == WaterKind.STREAM }),
            "drainageAdds" to (items.count { it.kind == WaterKind.STREAM } - mappedOnly.items.count { it.kind == WaterKind.STREAM }),
            "longestGapM" to longestGap(items, lengthM),
            "items" to items.mapIndexed { i, it -> linkedMapOf<String, Any?>("n" to i + 1) + it.json() },
        )
        truth?.let { f -> report["truth"] = timings.time("truth") { routeTruth(items, TruthPoints.read(f)) } }
        if (!noRender) report["render"] = render(doc, points, items, reader, zoom, packFile, timings)
        report["timingsMs"] = timings.ms
        return report
    }

    private fun counts(items: List<WaterItem>): Map<String, Int> = items.groupingBy { it.label }.eachCount().toSortedMap()

    /** The longest stretch of the route with no item that is likely to hold water (a likely flowing stream, a spring, a tap, a lake). */
    private fun longestGap(items: List<WaterItem>, lengthM: Double): Double {
        val wet = items.filter { it.kind != WaterKind.STREAM || it.waterClass == WaterClass.LIKELY_FLOWING }.map { it.alongM }
        var last = 0.0
        var gap = 0.0
        for (a in wet + lengthM) { gap = max(gap, a - last); last = a }
        return round1(gap)
    }

    private fun paramsJson() = linkedMapOf(
        "dryBelowKm2" to params.dryBelowKm2, "flowingFromKm2" to params.flowingFromKm2, "dropBelowKm2" to params.dropBelowKm2,
        "mergeM" to params.mergeM, "nearM" to params.nearM, "bridgeM" to params.bridgeM,
    )

    /** Each stream crossing against the nearest NHD point within [snapM]: the final class, the tag's alone and the terrain's alone. */
    private fun routeTruth(items: List<WaterItem>, t: TruthPoints): Map<String, Any?> {
        val rows = ArrayList<Map<String, Any?>>()
        val finalTable = Confusion()
        val terrainTable = Confusion()
        val mappedTable = Confusion()
        var unmatched = 0
        for ((i, it) in items.withIndex()) {
            if (it.kind != WaterKind.STREAM) continue
            val hit = t.nearest(it.lat, it.lon, snapM)
            val code = hit?.let { h -> t.fcode[h] }
            val nhd = code?.let { c -> nhdClass(c) }
            if (nhd == null) unmatched++
            val terrain = it.catchmentKm2?.let { k -> WaterAlong.classOf(k, params) ?: WaterClass.USUALLY_DRY }
            val mapped = it.mappedClass?.let { c -> WaterAlong.mappedClassOf(c, it.intermittent) }
            if (nhd != null) {
                finalTable.add(nhd, it.waterClass)
                terrain?.let { c -> terrainTable.add(nhd, c) }
                mapped?.let { c -> mappedTable.add(nhd, c) }
            }
            rows += linkedMapOf(
                "n" to i + 1, "alongM" to round1(it.alongM), "class" to it.waterClass?.key, "basis" to it.basis.key, "name" to it.name,
                "catchmentKm2" to it.catchmentKm2, "terrainClass" to terrain?.key, "mappedClass" to mapped?.key,
                "nhdFcode" to code, "nhdClass" to nhd?.key, "nhdName" to hit?.let { h -> t.name[h].ifEmpty { null } },
                "nhdDistanceM" to hit?.let { h -> round1(t.distanceM(h, it.lat, it.lon)) },
            )
        }
        return linkedMapOf(
            "file" to truth?.path, "snapM" to snapM, "unmatchedStreams" to unmatched,
            "final" to finalTable.json(), "terrainOnly" to terrainTable.json(), "mappedOnly" to mappedTable.json(), "crossings" to rows,
            "note" to "NHD codes were set from aerial photos and maps at compile time; a code is a second opinion, not a measurement.",
        )
    }

    private fun render(doc: GpxDocument, points: List<RoutePoint>, items: List<WaterItem>, reader: PmtilesReader, packZoom: Int, packFile: File, timings: Timings): Map<String, Any?> {
        val demFile = dem ?: RouteGraphs.defaultDem(packFile) ?: return mapOf("skipped" to "no DEM pack (pass --dem)")
        val asTrack = Track("water", "route", listOf(TrackSegment(points.map { TrackPoint(it.lat, it.lon, it.ele) })))
        val trackDoc = GpxDocument(tracks = listOf(asTrack))
        val fit = TrackDrawing.fitBounds(trackDoc.tracks, emptyList(), emptyList(), 0.12) ?: return mapOf("skipped" to "empty route")
        val zoom = GpxCommand.fitZoom(fit, maxZoom = 15)
        val window = MercatorWindow.forBbox(fit, zoom)
        val grid = timings.time("demWindow") { RouteGraphs.pyramid(demFile).window(zoom, window.x0, window.y0, window.width, window.height) }
        val (map, _, _, _) = MapRenderer.render(grid, zoom, style, timings, window = window)
        val g0 = map.createGraphics()
        g0.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val drawn = timings.time("drawWater") { drawLines(g0, reader, packZoom, fit, window) }
        g0.dispose()
        val overlay = GpxOverlay(window, style.textScale, 160.0)
        val withRoute = overlay.draw(map, trackDoc, 0)
        val legendRows = items.size
        val rowH = 18
        val image = BufferedImage(withRoute.width, withRoute.height + 12 + rowH * max(1, legendRows), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.drawImage(withRoute, 0, 0, null)
        val font = AwtTextMeasurer.font(11.0)
        g.font = font
        var outside = 0
        // Markers last-first so the first item sits on top where two overlap.
        for (i in items.indices.reversed()) {
            val it = items[i]
            val px = WebMercator.lonToX(it.lon, zoom) * window.tileSize - window.x0
            val py = WebMercator.latToY(it.lat, zoom) * window.tileSize - window.y0
            if (px < 0 || py < 0 || px >= window.width || py >= window.height) { outside++; continue }
            marker(g, px, py, i + 1, colorOf(it))
        }
        g.color = Color(0x1f, 0x1a, 0x17)
        if (items.isEmpty()) g.drawString("No water found along the route.", 8, withRoute.height + 12 + 12)
        for ((i, it) in items.withIndex()) {
            val y = withRoute.height + 12 + rowH * i + 12
            g.color = colorOf(it)
            g.fill(Ellipse2D.Double(8.0, y - 10.0, 12.0, 12.0))
            g.color = Color(0x1f, 0x1a, 0x17)
            val extra = listOfNotNull(
                it.name, it.catchmentKm2?.let { k -> "catchment ${"%.2f".format(k)} km2" }, if (it.offsetM > 0) "${it.offsetM.toInt()} m off" else null,
                if (it.bridge) "bridge" else null, if (it.merged) "merged" else null, if (it.intermittent) "intermittent" else null,
            ).joinToString(", ")
            g.drawString("${i + 1}.  ${"%.2f".format(it.alongM / 1000)} km  ${it.label} (${it.basis.label})" + if (extra.isNotEmpty()) "  - $extra" else "", 26, y)
        }
        g.dispose()
        val outFile = out ?: File("out/water-${gpxFile!!.nameWithoutExtension}-${packFile.nameWithoutExtension}.png")
        timings.time("writePng") {
            outFile.absoluteFile.parentFile?.mkdirs()
            ImageIO.write(image, "png", outFile)
        }
        return linkedMapOf(
            "output" to outFile.path, "zoom" to zoom, "widthPx" to image.width, "heightPx" to image.height,
            "markers" to items.size - outside, "markersOutside" to outside, "waterwayLinesDrawn" to drawn.first, "drainageLinesDrawn" to drawn.second,
        )
    }

    /** The mapped waterways (blue, intermittent dashed) and the derived lines (thin, by class) of the pack under the window. */
    private fun drawLines(g: Graphics2D, reader: PmtilesReader, z: Int, box: BoundingBox, window: MercatorWindow): Pair<Int, Int> {
        val x0 = floor(WebMercator.lonToX(box.west, z)).toInt()
        val x1 = floor(WebMercator.lonToX(box.east, z)).toInt()
        val y0 = floor(WebMercator.latToY(box.north, z)).toInt()
        val y1 = floor(WebMercator.latToY(box.south, z)).toInt()
        val k = (1 shl (window.zoom - z)).toDouble().let { if (window.zoom >= z) it else 1.0 / (1 shl (z - window.zoom)) }
        var ww = 0
        var dr = 0
        val dash = floatArrayOf(6f, 4f)
        for (ty in y0..y1) for (tx in x0..x1) {
            val bytes = reader.tile(z, tx, ty) ?: continue
            val tile = try { MvtDecoder.decode(bytes) } catch (e: IllegalArgumentException) { continue }
            for (name in listOf("drainage", "waterway")) {
                val layer = tile.layer(name) ?: continue
                val e = layer.extent.toDouble()
                for (f in layer.features) {
                    val lines = (f.geometry as? Geometry.Lines)?.lines ?: continue
                    if (name == "drainage") {
                        val km2 = f.properties["catchment_km2"]?.asDouble() ?: 0.0
                        val cls = WaterAlong.classOf(km2, params)
                        g.color = when (cls) {
                            WaterClass.LIKELY_FLOWING -> Color(0x00, 0x89, 0x7b)
                            WaterClass.MAY_RUN_DRY -> Color(0x00, 0xb8, 0xd4)
                            WaterClass.USUALLY_DRY -> Color(0xef, 0x6c, 0x00)
                            null -> Color(0x90, 0x90, 0x90, 160)
                        }
                        g.stroke = BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(5f, 3f), 0f)
                        dr++
                    } else {
                        val inter = (f.properties["intermittent"]?.asDouble() ?: 0.0) != 0.0
                        g.color = Color(0x12, 0x5b, 0xd6)
                        g.stroke = if (inter) BasicStroke(2.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f) else BasicStroke(2.6f)
                        ww++
                    }
                    for (pl in lines) {
                        val path = Path2D.Double()
                        for ((j, p) in pl.withIndex()) {
                            val px = (tx + p.x / e) * k * window.tileSize - window.x0
                            val py = (ty + p.y / e) * k * window.tileSize - window.y0
                            if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        g.draw(path)
                    }
                }
            }
        }
        return ww to dr
    }

    private fun colorOf(it: WaterItem): Color = when (it.kind) {
        WaterKind.STREAM -> when (it.waterClass) {
            WaterClass.LIKELY_FLOWING -> Color(0x0b, 0x3d, 0x91)
            WaterClass.MAY_RUN_DRY -> Color(0x1f, 0x8f, 0xb8)
            else -> Color(0x8a, 0x6a, 0x45)
        }
        WaterKind.SPRING, WaterKind.TAP -> Color(0x1b, 0x8a, 0x3c)
        WaterKind.LAKE -> Color(0x5b, 0x3f, 0xb5)
    }

    private fun marker(g: Graphics2D, x: Double, y: Double, n: Int, c: Color) {
        val r = 9.0
        g.color = Color.WHITE
        g.fill(Ellipse2D.Double(x - r - 1.5, y - r - 1.5, 2 * r + 3, 2 * r + 3))
        g.color = c
        g.fill(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
        g.color = Color.WHITE
        val s = n.toString()
        val fm = g.fontMetrics
        g.drawString(s, (x - fm.stringWidth(s) / 2.0).toFloat(), (y + fm.ascent / 2.0 - 1.5).toFloat())
    }

    // ---- the area table ----

    private fun areaReport(): Map<String, Any?> {
        val timings = Timings()
        val (name, box) = Areas.resolve(area, bbox)
        val t = timings.time("truth") { TruthPoints.read(truth!!) }
        val zoom = 13
        val mLat = marginKm / 111.32
        val mLon = marginKm / (111.32 * cos((box.south + box.north) / 2 * PI / 180))
        val big = BoundingBox(box.west - mLon, box.south - mLat, box.east + mLon, box.north + mLat)
        val window = MercatorWindow.forBbox(big, zoom)
        val src = ElevationSource.byId(source)
        val fetched = timings.time("fetch") { src.fetch(window) }
        val grid = fetched.grid
        val rowArea = DoubleArray(grid.height) { j -> WebMercator.metersPerPixel(window.latAt(j), zoom, window.tileSize).let { it * it } }
        val flow = timings.time("flow") { FlowGrid.build(grid, rowArea) }
        val cellM = grid.cellSizeMeters
        val r = ceil(snapM / cellM).toInt()

        // The largest catchment within snapM of each truth point inside the area.
        val km2 = DoubleArray(t.size) { Double.NaN }
        val open = BooleanArray(t.size)
        timings.time("sample") {
            for (i in 0 until t.size) {
                if (t.lon[i] < box.west || t.lon[i] > box.east || t.lat[i] < box.south || t.lat[i] > box.north) continue
                val cx = floor(WebMercator.lonToX(t.lon[i], zoom) * window.tileSize - window.x0).toInt()
                val cy = floor(WebMercator.latToY(t.lat[i], zoom) * window.tileSize - window.y0).toInt()
                var best = -1.0
                var bestOpen = false
                for (dy in -r..r) for (dx in -r..r) {
                    if (hypot(dx.toDouble(), dy.toDouble()) * cellM > snapM + cellM / 2) continue
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= grid.width || y >= grid.height) continue
                    val c = y * grid.width + x
                    val a = flow.areaM2[c].toDouble()
                    if (a > best) { best = a; bestOpen = flow.open[c] }
                }
                if (best >= 0) { km2[i] = best / 1e6; open[i] = bestOpen }
            }
        }
        val mapped = pack?.let { f -> timings.time("pack") { MappedLines.read(f, box) } }

        val byCode = linkedMapOf<String, Int>()
        val used = ArrayList<Int>()
        var outside = 0
        var openCount = 0
        var otherCode = 0
        for (i in 0 until t.size) {
            if (km2[i].isNaN()) { outside++; continue }
            val code = t.fcode[i]
            byCode["$code"] = (byCode["$code"] ?: 0) + 1
            if (nhdClass(code) == null) { otherCode++; continue }
            if (open[i]) { openCount++; continue }
            used += i
        }
        fun table(p: WaterParams): Confusion {
            val c = Confusion()
            for (i in used) c.add(nhdClass(t.fcode[i])!!, WaterAlong.classOf(km2[i], p))
            return c
        }
        val sweep = ArrayList<Map<String, Any?>>()
        for (d in listOf(0.25, 0.5, 1.0)) for (f in listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0)) {
            if (f < d) continue
            val c = table(WaterParams(dryBelowKm2 = d, flowingFromKm2 = f, dropBelowKm2 = min(params.dropBelowKm2, d)))
            sweep += linkedMapOf("dryBelowKm2" to d, "flowingFromKm2" to f) + c.scores()
        }
        val percentiles = linkedMapOf<String, Any?>()
        for (cls in listOf(WaterClass.LIKELY_FLOWING, WaterClass.MAY_RUN_DRY, WaterClass.USUALLY_DRY)) {
            val v = used.filter { nhdClass(t.fcode[it]) == cls }.map { km2[it] }.sorted()
            if (v.isEmpty()) continue
            fun q(p: Double) = round(v[min(v.size - 1, (p * (v.size - 1)).toInt())] * 1000) / 1000
            percentiles[nhdWord(cls)] = linkedMapOf("points" to v.size, "p10" to q(0.1), "p25" to q(0.25), "p50" to q(0.5), "p75" to q(0.75), "p90" to q(0.9),
                "underDropFraction" to frac(v.count { it < params.dropBelowKm2 }, v.size), "underDryFraction" to frac(v.count { it < params.dryBelowKm2 }, v.size))
        }
        val report = linkedMapOf<String, Any?>(
            "area" to name, "bbox" to listOf(box.west, box.south, box.east, box.north), "gridBbox" to listOf(big.west, big.south, big.east, big.north),
            "zoom" to zoom, "cellSizeM" to round1(cellM), "cells" to grid.width * grid.height, "snapM" to snapM, "snapCells" to r,
            "truth" to truth!!.path, "truthPoints" to t.size, "pointsOutsideArea" to outside, "pointsByFcode" to byCode,
            "pointsOtherCode" to otherCode, "pointsOpenCatchment" to openCount, "pointsUsed" to used.size,
            "params" to paramsJson(), "table" to table(params).json(), "percentilesKm2" to percentiles, "sweep" to sweep,
        )
        mapped?.let { m ->
            val c = Confusion()
            var none = 0
            for (i in used) {
                val hit = m.nearest(t.lon[i], t.lat[i], snapM)
                if (hit == null) { none++; continue }
                c.add(nhdClass(t.fcode[i])!!, WaterAlong.mappedClassOf(hit.first, hit.second))
            }
            report["mappedTags"] = linkedMapOf("pack" to pack!!.path, "pointsWithoutMappedLine" to none, "table" to c.json())
        }
        report["provenance"] = fetched.provenance
        report["attribution"] = fetched.attribution(src) + " Truth: USGS National Hydrography Dataset (public domain)." +
            (if (mapped != null) " Map data © OpenStreetMap contributors (ODbL)." else "")
        report["timingsMs"] = timings.ms
        return report
    }

    private fun frac(a: Int, b: Int) = if (b == 0) 0.0 else round(a * 10000.0 / b) / 10000

    companion object {
        /** NHD FCode to the class it stands for; null for codes outside the comparison (artificial paths, canals, pipes, unclassified). */
        fun nhdClass(fcode: Int): WaterClass? = when (fcode) {
            46006 -> WaterClass.LIKELY_FLOWING
            46003 -> WaterClass.MAY_RUN_DRY
            46007 -> WaterClass.USUALLY_DRY
            else -> null
        }

        fun nhdWord(c: WaterClass): String = when (c) {
            WaterClass.LIKELY_FLOWING -> "perennial"
            WaterClass.MAY_RUN_DRY -> "intermittent"
            WaterClass.USUALLY_DRY -> "ephemeral"
        }

        private fun round1(v: Double) = round(v * 10) / 10
    }
}

/** A confusion table: NHD class (rows) against the class given (columns, "dropped" for none). */
internal class Confusion {
    private val cells = HashMap<Pair<WaterClass, WaterClass?>, Int>()
    var total = 0
        private set

    fun add(nhd: WaterClass, given: WaterClass?) {
        cells[nhd to given] = (cells[nhd to given] ?: 0) + 1
        total++
    }

    private fun n(a: WaterClass, b: WaterClass?) = cells[a to b] ?: 0
    private fun row(a: WaterClass) = WaterClass.entries.sumOf { n(a, it) } + n(a, null)
    private fun col(b: WaterClass?) = WaterClass.entries.sumOf { n(it, b) }

    fun scores(): Map<String, Any?> {
        if (total == 0) return mapOf("points" to 0)
        val agree = WaterClass.entries.sumOf { n(it, it) }
        val present = WaterClass.entries.filter { row(it) >= max(1, total / 100) }
        val recalls = present.associateWith { n(it, it).toDouble() / row(it) }
        val per = WaterClass.LIKELY_FLOWING
        val notPer = WaterClass.entries.filter { it != per }
        val trueNeg = notPer.sumOf { a -> (listOf<WaterClass?>(null) + notPer).sumOf { b -> n(a, b) } }
        val negRows = notPer.sumOf { row(it) }
        val binaryRight = n(per, per) + trueNeg
        return linkedMapOf(
            "points" to total, "agreement" to r4(agree.toDouble() / total),
            "balancedAccuracy" to r4(recalls.values.average()),
            "perennialRecall" to if (row(per) > 0) r4(n(per, per).toDouble() / row(per)) else null,
            "perennialPrecision" to if (col(per) > 0) r4(n(per, per).toDouble() / col(per)) else null,
            "perennialVsNotAccuracy" to r4(binaryRight.toDouble() / total),
            // Perennial against not perennial with each side weighted alike (NHD has almost no ephemeral codes, so "may run dry"
            // and "usually dry" both mean "not perennial" here).
            "perennialBalanced" to if (row(per) > 0 && negRows > 0) r4((n(per, per).toDouble() / row(per) + trueNeg.toDouble() / negRows) / 2) else null,
        )
    }

    fun json(): Map<String, Any?> {
        val rows = linkedMapOf<String, Any?>()
        for (a in WaterClass.entries) {
            if (row(a) == 0) continue
            rows[WaterCommand.nhdWord(a)] = linkedMapOf<String, Any?>().apply {
                for (b in WaterClass.entries) put(b.key, n(a, b))
                put("dropped", n(a, null))
            }
        }
        return linkedMapOf("rows" to rows) + scores()
    }

    private fun r4(v: Double) = round(v * 10000) / 10000
}

/** NHD flowline points read from the CSV `tools/nhd-flowlines.py` writes, with a grid index for nearest lookups. */
internal class TruthPoints(val lon: DoubleArray, val lat: DoubleArray, val fcode: IntArray, val name: Array<String>) {
    val size: Int get() = lon.size
    private val cell = 0.002 // degrees, about 150 to 220 m
    private val index = HashMap<Long, MutableList<Int>>().also { m ->
        for (i in lon.indices) m.getOrPut(key(floor(lon[i] / cell).toInt(), floor(lat[i] / cell).toInt())) { ArrayList() } += i
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    fun distanceM(i: Int, la: Double, lo: Double) = TrackStats.distanceM(lat[i], lon[i], la, lo)

    /** The nearest point within [maxM] of ([la], [lo]), or null. */
    fun nearest(la: Double, lo: Double, maxM: Double): Int? {
        val cx = floor(lo / cell).toInt()
        val cy = floor(la / cell).toInt()
        var best: Int? = null
        var bestD = maxM
        for (dy in -1..1) for (dx in -1..1) for (i in index[key(cx + dx, cy + dy)] ?: continue) {
            val d = distanceM(i, la, lo)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        fun read(file: File): TruthPoints {
            val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val head = csv(lines.first())
            val iLon = head.indexOf("lon"); val iLat = head.indexOf("lat"); val iCode = head.indexOf("fcode"); val iName = head.indexOf("name")
            require(iLon >= 0 && iLat >= 0 && iCode >= 0) { "${file.name}: needs lon, lat and fcode columns" }
            val rows = lines.drop(1).map { csv(it) }
            return TruthPoints(
                DoubleArray(rows.size) { rows[it][iLon].toDouble() }, DoubleArray(rows.size) { rows[it][iLat].toDouble() },
                IntArray(rows.size) { rows[it][iCode].toDoubleOrNull()?.toInt() ?: 0 }, Array(rows.size) { if (iName >= 0) rows[it][iName] else "" },
            )
        }

        /** One CSV line (RFC 4180 quoting). */
        fun csv(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var q = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> q = !q
                    c == ',' && !q -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}

/** The mapped `waterway` lines of an outdoors pack over a box at its last zoom, with a bucket index (the area table's tag column). */
internal class MappedLines private constructor(private val segs: List<DoubleArray>, private val attrs: List<Pair<String?, Boolean>>) {
    private val bucket = 0.002
    private val index = HashMap<Long, MutableList<Int>>().also { m ->
        for ((i, s) in segs.withIndex()) {
            val x0 = floor(min(s[0], s[2]) / bucket).toInt(); val x1 = floor(max(s[0], s[2]) / bucket).toInt()
            val y0 = floor(min(s[1], s[3]) / bucket).toInt(); val y1 = floor(max(s[1], s[3]) / bucket).toInt()
            for (y in y0..y1) for (x in x0..x1) m.getOrPut((x.toLong() shl 32) or (y.toLong() and 0xffffffffL)) { ArrayList() } += i
        }
    }

    /** (waterway class, intermittent) of the nearest line within [maxM] of ([lo], [la]), or null. */
    fun nearest(lo: Double, la: Double, maxM: Double): Pair<String?, Boolean>? {
        val kx = 111_320.0 * cos(la * PI / 180)
        val ky = 110_574.0
        val cx = floor(lo / bucket).toInt(); val cy = floor(la / bucket).toInt()
        var best = -1
        var bestD = maxM
        for (dy in -1..1) for (dx in -1..1) for (i in index[((cx + dx).toLong() shl 32) or ((cy + dy).toLong() and 0xffffffffL)] ?: continue) {
            val s = segs[i]
            val ax = (s[0] - lo) * kx; val ay = (s[1] - la) * ky; val bx = (s[2] - lo) * kx; val by = (s[3] - la) * ky
            val ddx = bx - ax; val ddy = by - ay
            val l2 = ddx * ddx + ddy * ddy
            val u = if (l2 == 0.0) 0.0 else ((-ax * ddx - ay * ddy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + u * ddx, ay + u * ddy)
            if (d <= bestD) { bestD = d; best = i }
        }
        return if (best < 0) null else attrs[best]
    }

    companion object {
        fun read(file: File, box: BoundingBox): MappedLines {
            val reader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
            val z = min(reader.header.maxZoom, 14)
            val segs = ArrayList<DoubleArray>()
            val attrs = ArrayList<Pair<String?, Boolean>>()
            for (t in WebMercator.tilesCovering(box, z)) {
                val bytes = reader.tile(t.z, t.x, t.y) ?: continue
                val layer = MvtDecoder.decode(bytes).layer("waterway") ?: continue
                val e = layer.extent.toDouble()
                for (f in layer.features) {
                    val lines = (f.geometry as? Geometry.Lines)?.lines ?: continue
                    val a = (f.properties["class"] as? Value.StringValue)?.value to ((f.properties["intermittent"]?.asDouble() ?: 0.0) != 0.0)
                    for (pl in lines) for (j in 0 until pl.size - 1) {
                        val p = pl[j]; val q = pl[j + 1]
                        segs += doubleArrayOf(
                            WebMercator.xToLon(t.x + p.x / e, z), WebMercator.yToLat(t.y + p.y / e, z),
                            WebMercator.xToLon(t.x + q.x / e, z), WebMercator.yToLat(t.y + q.y / e, z),
                        )
                        attrs += a
                    }
                }
            }
            return MappedLines(segs, attrs)
        }
    }
}
