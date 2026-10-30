package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.Drainage
import io.github.jasondorjeshort.dystopo.dem.DrainageLine
import io.github.jasondorjeshort.dystopo.dem.DrainageParams
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.FlowGrid
import io.github.jasondorjeshort.dystopo.dem.StreamClass
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.render.Hillshade
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
data class DrainageClassStats(val lines: Int, val km: Double)

@Serializable
data class DrainageRun(
    val thresholdKm2: Double,
    /** True for the run drawn in the PNG. */
    val drawn: Boolean,
    /** Lines (between channel heads, confluences, class changes and breaks). */
    val channels: Int,
    val lengthKm: Double,
    val byClass: Map<String, DrainageClassStats>,
    /** Share of the derived length within `bufferM` of a mapped `waterway` line; null without a pack. */
    val withinMappedFraction: Double?,
    /** Share of the mapped `waterway` length inside the grid within `bufferM` of a derived line; null without a pack. */
    val mappedCoveredFraction: Double?,
    /** Share of the derived length on `landcover` ice (glaciers); null without a pack. */
    val onIceFraction: Double?,
    /** Lines whose catchment may reach past the grid (the area is a lower bound). */
    val incompleteLines: Int,
    val sourceCells: Int,
    val channelCells: Int,
    val flatSuppressedCells: Int,
    /** Channel cells under the mask (water polygons, and glaciers unless `--keep-ice`): the network runs on beneath, nothing is drawn. */
    val maskedCells: Int,
    val droppedShort: Int,
    val extractMs: Long,
)

@Serializable
data class DrainageMapped(
    val archive: String,
    val tileZoom: Int,
    val tiles: Int,
    /** `waterway` features read (per tile they appear in). */
    val waterwayFeatures: Int,
    /** Mapped waterway length inside the grid, km (each tile counts only its own extent, so buffers are not counted twice). */
    val waterwayKm: Double,
    /** Grid cells inside a `water` polygon: not drawn. */
    val waterCells: Int,
    /** Grid cells on `landcover` ice. */
    val iceCells: Int,
)

@Serializable
data class DrainageReport(
    val area: String?,
    val bbox: List<Double>,
    val zoom: Int,
    val source: String,
    val output: String,
    val widthPx: Int,
    val heightPx: Int,
    val cellSizeMeters: Double,
    val cells: Int,
    val raisedCells: Int,
    val maxRaiseMeters: Double,
    val outlets: Int,
    /** Share of cells whose upslope area may reach past the grid edge. */
    val openCellFraction: Double,
    /** Bytes of the flow arrays and one extraction (filled, receiver, area, order, flags, slope), excluding the input grid. */
    val flowBytes: Long,
    /** JVM heap in use after the flow grid was built, MB (after a GC request; an indication, not a measurement of the peak). */
    val heapUsedMb: Long,
    val bufferM: Double,
    /** True when glaciers are masked like water (the default; `--keep-ice` turns it off). */
    val iceMasked: Boolean,
    val params: Map<String, Double>,
    val classesNote: String,
    val mapped: DrainageMapped?,
    val runs: List<DrainageRun>,
    val timingsMs: Map<String, Long>,
    val provenance: List<String>,
    val attribution: String,
)

/**
 * T165: stream lines derived from the DEM ([FlowGrid], [Drainage]) over a whole area in one grid, drawn over the
 * hillshade with the mapped waterways of an outdoors pack under them, and scored against those waterways for a
 * sweep of channel-start thresholds.
 */
class DrainageCommand : AreaCommand("drainage") {
    override fun help(context: Context) =
        "Derive stream lines from the elevation grid of an area (pit fill, D8 flow, catchment classes); PNG over the hillshade and a JSON report " +
            "scored against the mapped waterways of an outdoors pack."

    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val pack by option("--pack", help = "Outdoors vector pack for the mapped waterways, water and ice (default out/<area>-outdoors.pmtiles when it exists)")
        .file(mustExist = true, canBeDir = false)
    private val thresholds by option("--thresholds", help = "Channel-start thresholds to sweep, km2, comma-separated").default("0.05,0.1,0.25")
    private val draw by option("--draw", help = "Threshold drawn in the PNG, km2 (added to the sweep if missing)").double().default(DrainageParams().thresholdKm2)
    private val bufferM by option("--buffer-m", help = "Distance from a mapped waterway that counts as on it, metres").double().default(30.0)
    private val flatDeg by option("--flat-deg", help = "Channels flatter than this are not drawn, degrees").double().default(DrainageParams().flatDeg)
    private val gateSlopeDeg by option("--gate-slope", help = "Reference slope of the Montgomery-Dietrich gate, degrees").double().default(DrainageParams().referenceSlopeDeg)
    private val minCells by option("--min-cells", help = "Headwater pieces shorter than this many cells are dropped").int().default(DrainageParams().minCells)
    private val keepIce by option("--keep-ice", help = "Draw channels on glaciers too (by default `landcover` ice is masked like water: meltwater on a glacier is no stream to map)").flag()
    private val out by option("--out", help = "Output PNG (default out/<area>-drainage-z<zoom>.png)").file()
    private val inset by option("--inset", help = "Also write a close-up of west,south,east,north (inside the area), scaled by --inset-scale, beside the PNG as -inset.png")
    private val insetScale by option("--inset-scale", help = "Pixel scale of the --inset close-up").int().default(3)

    override fun run() {
        val (name, box) = Areas.resolve(area, bbox)
        val window = MercatorWindow.forBbox(box, zoom)
        val src = ElevationSource.byId(source)
        val timings = Timings()
        val sweep = (thresholds.split(",").map { it.trim().toDouble() } + draw).distinct().sorted()

        val fetched = timings.time("fetch") { src.fetch(window) }
        val grid = fetched.grid
        val rowArea = DoubleArray(grid.height) { j -> WebMercator.metersPerPixel(window.latAt(j), zoom, window.tileSize).let { it * it } }
        val slope = timings.time("slope") { Slope.compute(grid) }
        val packFile = pack ?: name?.let { File("out/$it-outdoors.pmtiles") }?.takeIf { it.isFile }
        val mapped = packFile?.let { f -> timings.time("pack") { MappedWater.read(f, window, grid, bufferM) } }
        val mask = mapped?.let { m -> if (keepIce) m.water else BooleanArray(m.water.size) { m.water[it] || m.ice[it] } }
        val flow = timings.time("flow") { FlowGrid.build(grid, rowArea) }
        System.gc()
        val heapUsedMb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1L shl 20) }

        val base = DrainageParams(referenceSlopeDeg = gateSlopeDeg, flatDeg = flatDeg, minCells = minCells)
        var drawnLines: List<DrainageLine> = emptyList()
        val runs = sweep.map { t ->
            val started = System.nanoTime()
            val result = Drainage.extract(flow, slope, base.copy(thresholdKm2 = t), mask)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (t == draw) drawnLines = result.lines
            val cellM = grid.cellSizeMeters
            val byClass = StreamClass.entries.associate { c ->
                val ls = result.lines.filter { it.streamClass == c }
                c.key to DrainageClassStats(ls.size, round3(ls.sumOf { Drainage.lengthCells(it) } * cellM / 1000))
            }
            val score = mapped?.score(result.lines)
            DrainageRun(
                thresholdKm2 = t,
                drawn = t == draw,
                channels = result.lines.size,
                lengthKm = round3(result.lines.sumOf { Drainage.lengthCells(it) } * cellM / 1000),
                byClass = byClass,
                withinMappedFraction = score?.first,
                mappedCoveredFraction = score?.second,
                onIceFraction = score?.third,
                incompleteLines = result.lines.count { !it.catchmentComplete },
                sourceCells = result.stats.sourceCells,
                channelCells = result.stats.channelCells,
                flatSuppressedCells = result.stats.flatSuppressed,
                maskedCells = result.stats.maskSuppressed,
                droppedShort = result.stats.droppedShort,
                extractMs = ms,
            )
        }

        val outFile = out ?: File("out/${name ?: "bbox"}-drainage-z$zoom.png")
        timings.time("draw") {
            val image = drawMap(grid, mapped, drawnLines)
            outFile.absoluteFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
            inset?.let { text ->
                val (_, b) = Areas.resolve(null, text)
                val x0 = (WebMercator.lonToX(b.west, zoom) * window.tileSize - window.x0).toInt().coerceIn(0, grid.width - 1)
                val x1 = (WebMercator.lonToX(b.east, zoom) * window.tileSize - window.x0).toInt().coerceIn(x0 + 1, grid.width)
                val y0 = (WebMercator.latToY(b.north, zoom) * window.tileSize - window.y0).toInt().coerceIn(0, grid.height - 1)
                val y1 = (WebMercator.latToY(b.south, zoom) * window.tileSize - window.y0).toInt().coerceIn(y0 + 1, grid.height)
                val close = BufferedImage((x1 - x0) * insetScale, (y1 - y0) * insetScale, BufferedImage.TYPE_INT_RGB)
                close.createGraphics().apply {
                    drawImage(image.getSubimage(x0, y0, x1 - x0, y1 - y0), 0, 0, close.width, close.height, null)
                    dispose()
                }
                ImageIO.write(close, "png", File(outFile.path.removeSuffix(".png") + "-inset.png"))
            }
        }
        val cells = grid.width * grid.height
        val report = DrainageReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            zoom = zoom,
            source = source,
            output = outFile.path,
            widthPx = grid.width,
            heightPx = grid.height,
            cellSizeMeters = (grid.cellSizeMeters * 100).roundToLong() / 100.0,
            cells = cells,
            raisedCells = flow.raisedCells,
            maxRaiseMeters = round3(flow.maxRaiseMeters),
            outlets = flow.outlets,
            openCellFraction = round3(flow.open.count { it }.toDouble() / cells),
            // filled 4, receiver 4, area 4, order 4, open 1, slope 4; per extraction slope-area 4, flags 4.
            flowBytes = cells * 29L,
            heapUsedMb = heapUsedMb,
            bufferM = bufferM,
            iceMasked = mapped != null && !keepIce,
            params = mapOf(
                "referenceSlopeDeg" to base.referenceSlopeDeg, "gateMin" to base.gateMin, "gateMax" to base.gateMax,
                "flatDeg" to base.flatDeg, "minCells" to base.minCells.toDouble(), "dryBelowKm2" to base.dryBelowKm2,
                "runningFromKm2" to base.runningFromKm2, "steepFromDeg" to base.steepFromDeg, "steepFullDeg" to base.steepFullDeg,
                "steepFactor" to base.steepFactor,
            ),
            classesNote = "The classes are the project's own: no published source gives class boundaries by catchment. They use the catchment " +
                "area and its mean slope only (no climate, no season): likely dry under ${base.dryBelowKm2} km2, seasonal to " +
                "${base.runningFromKm2} km2, likely running above, both boundaries scaled down to ${base.steepFactor}x on catchments " +
                "steeper than ${base.steepFromDeg}..${base.steepFullDeg} degrees.",
            mapped = mapped?.stats,
            runs = runs,
            timingsMs = timings.ms,
            provenance = fetched.provenance,
            attribution = fetched.attribution(src) + (if (mapped != null) " Map data © OpenStreetMap contributors (ODbL)." else ""),
        )
        echo(reportJson.encodeToString(report))
    }

    private fun drawMap(grid: ElevationGrid, mapped: MappedWater?, lines: List<DrainageLine>): BufferedImage {
        val w = grid.width
        val h = grid.height
        val shade = Hillshade.compute(grid)
        val pixels = IntArray(w * h) { i ->
            val b = shade[i]
            if (b.isNaN()) 0xC83CC8 else {
                val k = 0.35 + 0.65 * b
                val water = mapped?.water?.get(i) == true
                val ice = mapped?.ice?.get(i) == true
                val (r, g, bl) = when {
                    water -> Triple(170, 205, 235)
                    ice -> Triple(236, 244, 250)
                    else -> Triple(246, 241, 228)
                }
                ((r * k).roundToInt() shl 16) or ((g * k).roundToInt() shl 8) or (bl * k).roundToInt()
            }
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.translate(0.5, 0.5)
        // Mapped waterways: a wide pale band under the derived lines, so agreement reads as blue on cyan.
        if (mapped != null) {
            g.color = Color(80, 210, 255, 150)
            g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (line in mapped.waterways) g.draw(path(line))
        }
        for (cls in StreamClass.entries) {
            when (cls) {
                StreamClass.LIKELY_RUNNING -> {
                    g.color = Color(0, 30, 140)
                    g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                }
                StreamClass.SEASONAL -> {
                    g.color = Color(20, 90, 220)
                    g.stroke = BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(6f, 3f), 0f)
                }
                StreamClass.LIKELY_DRY -> {
                    g.color = Color(120, 50, 170)
                    g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(2f, 2.5f), 0f)
                }
            }
            for (line in lines) if (line.streamClass == cls) g.draw(path(line.points))
        }
        g.dispose()
        return image
    }

    private fun round3(d: Double) = (d * 1000).roundToLong() / 1000.0
}

/** Interleaved x,y grid coordinates as a path. */
private fun path(points: DoubleArray): Path2D.Double = Path2D.Double().apply {
    moveTo(points[0], points[1])
    for (k in 1 until points.size / 2) lineTo(points[2 * k], points[2 * k + 1])
}

/**
 * The mapped water of an outdoors pack over a grid: `waterway` lines (in grid coordinates, for drawing and for
 * the score), `water` polygons and `landcover` ice as cell masks, and a buffer of [bufferM] around the waterways.
 */
private class MappedWater(
    val waterways: List<DoubleArray>,
    /** Per waterway line, which points lie inside their own tile's extent (for an unduplicated length). */
    private val own: List<BooleanArray>,
    val water: BooleanArray,
    val ice: BooleanArray,
    private val near: BooleanArray,
    private val width: Int,
    private val height: Int,
    private val cellSizeMeters: Double,
    private val bufferPx: Float,
    val stats: DrainageMapped,
) {
    /** (share of derived length near a mapped waterway, share of mapped length near a derived line, share on ice). */
    fun score(lines: List<DrainageLine>): Triple<Double, Double, Double> {
        var total = 0.0
        var nearMapped = 0.0
        var onIce = 0.0
        for (line in lines) sample(line.points, null) { x, y, len ->
            total += len
            val i = index(x, y)
            if (i >= 0 && near[i]) nearMapped += len
            if (i >= 0 && ice[i]) onIce += len
        }
        val derivedNear = mask { g -> for (line in lines) g.draw(path(line.points)) }
        var mappedTotal = 0.0
        var covered = 0.0
        for ((k, line) in waterways.withIndex()) sample(line, own[k]) { x, y, len ->
            val i = index(x, y)
            if (i < 0) return@sample
            mappedTotal += len
            if (derivedNear[i]) covered += len
        }
        fun frac(a: Double, b: Double) = if (b > 0) (a / b * 10000).roundToLong() / 10000.0 else 0.0
        return Triple(frac(nearMapped, total), frac(covered, mappedTotal), frac(onIce, total))
    }

    private fun index(x: Double, y: Double): Int {
        val i = floor(x + 0.5).toInt()
        val j = floor(y + 0.5).toInt()
        return if (i < 0 || j < 0 || i >= width || j >= height) -1 else j * width + i
    }

    /** Walks [points] in steps of a quarter cell; [keep] (per point) skips segments starting outside their own tile. */
    private inline fun sample(points: DoubleArray, keep: BooleanArray?, f: (Double, Double, Double) -> Unit) {
        for (k in 1 until points.size / 2) {
            if (keep != null && !keep[k - 1]) continue
            val x0 = points[2 * k - 2]
            val y0 = points[2 * k - 1]
            val dx = points[2 * k] - x0
            val dy = points[2 * k + 1] - y0
            val segment = hypot(dx, dy)
            val steps = (segment * 4).toInt().coerceAtLeast(1)
            val len = segment / steps * cellSizeMeters
            for (s in 0 until steps) {
                val t = (s + 0.5) / steps
                f(x0 + dx * t, y0 + dy * t, len)
            }
        }
    }

    /** A cell mask from drawing with a [bufferPx]-wide round stroke. */
    private fun mask(draw: (Graphics2D) -> Unit): BooleanArray = rasterize(width, height) { g ->
        g.stroke = BasicStroke(2 * bufferPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        draw(g)
    }

    companion object {
        fun read(file: File, window: MercatorWindow, grid: ElevationGrid, bufferM: Double): MappedWater {
            val reader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
            require(reader.header.tileType == TileType.MVT) { "${file.path} holds ${reader.header.tileType} tiles, not vector tiles" }
            val tz = reader.header.maxZoom
            // Global pixels at the window's zoom per tile at tz.
            val scale = window.tileSize * Math.pow(2.0, (window.zoom - tz).toDouble())
            val tx0 = floor(window.x0 / scale).toInt()
            val tx1 = floor((window.x0 + window.width - 1) / scale).toInt()
            val ty0 = floor(window.y0 / scale).toInt()
            val ty1 = floor((window.y0 + window.height - 1) / scale).toInt()
            val waterways = ArrayList<DoubleArray>()
            val own = ArrayList<BooleanArray>()
            val waterPolys = ArrayList<Path2D.Double>()
            val icePolys = ArrayList<Path2D.Double>()
            var tiles = 0
            var features = 0
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val bytes = reader.tile(tz, tx, ty) ?: continue
                tiles++
                val tile = VectorTile.decode(bytes)
                fun layerOf(name: String) = tile.layer(name)
                val extent = (layerOf("waterway") ?: layerOf("water") ?: layerOf("landcover"))?.extent ?: 4096
                fun gx(p: TilePoint) = (tx + p.x.toDouble() / extent) * scale - window.x0 - 0.5
                fun gy(p: TilePoint) = (ty + p.y.toDouble() / extent) * scale - window.y0 - 0.5
                fun inside(p: TilePoint) = p.x in 0 until extent && p.y in 0 until extent
                layerOf("waterway")?.features?.forEach { f ->
                    val geom = f.geometry as? Geometry.Lines ?: return@forEach
                    features++
                    for (line in geom.lines) {
                        if (line.size < 2) continue
                        waterways += DoubleArray(line.size * 2) { if (it % 2 == 0) gx(line[it / 2]) else gy(line[it / 2]) }
                        own += BooleanArray(line.size) { inside(line[it]) }
                    }
                }
                fun polygons(layer: String, filter: (io.github.jasondorjeshort.dystopo.mvt.Feature) -> Boolean, into: MutableList<Path2D.Double>) {
                    layerOf(layer)?.features?.forEach { f ->
                        val geom = f.geometry as? Geometry.Polygons ?: return@forEach
                        if (!filter(f)) return@forEach
                        for (poly in geom.polygons) {
                            val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
                            for (ring in listOf(poly.exterior) + poly.interiors) {
                                if (ring.isEmpty()) continue
                                p.moveTo(gx(ring[0]), gy(ring[0]))
                                for (k in 1 until ring.size) p.lineTo(gx(ring[k]), gy(ring[k]))
                                p.closePath()
                            }
                            into += p
                        }
                    }
                }
                polygons("water", { true }, waterPolys)
                polygons("landcover", { it.properties["class"]?.asText() == "ice" }, icePolys)
            }
            val w = grid.width
            val h = grid.height
            val water = rasterize(w, h) { g -> waterPolys.forEach { g.fill(it) } }
            val ice = rasterize(w, h) { g -> icePolys.forEach { g.fill(it) } }
            val bufferPx = (bufferM / grid.cellSizeMeters).toFloat()
            val near = rasterize(w, h) { g ->
                g.stroke = BasicStroke(2 * bufferPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                waterways.forEach { g.draw(path(it)) }
            }
            val partial = MappedWater(waterways, own, water, ice, near, w, h, grid.cellSizeMeters, bufferPx, DrainageMapped(file.path, tz, tiles, features, 0.0, 0, 0))
            var km = 0.0
            for ((k, line) in waterways.withIndex()) partial.sample(line, own[k]) { x, y, len -> if (partial.index(x, y) >= 0) km += len / 1000 }
            return MappedWater(
                waterways, own, water, ice, near, w, h, grid.cellSizeMeters, bufferPx,
                DrainageMapped(file.path, tz, tiles, features, (km * 1000).roundToLong() / 1000.0, water.count { it }, ice.count { it }),
            )
        }

        /** Draws into a grid-sized binary image (pixel i covers grid coordinates i-0.5..i+0.5) and returns the set cells. */
        fun rasterize(w: Int, h: Int, draw: (Graphics2D) -> Unit): BooleanArray {
            val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.translate(0.5, 0.5)
            draw(g)
            g.dispose()
            val raster = img.raster
            val row = IntArray(w)
            val out = BooleanArray(w * h)
            for (y in 0 until h) {
                raster.getSamples(0, y, w, 1, 0, row)
                for (x in 0 until w) out[y * w + x] = row[x] > 127
            }
            return out
        }
    }
}
